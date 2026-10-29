//! 通用工具循环（P7）：让模型真的能读写工作区。
//!
//! 一次 `run_turn` 做这些事：
//!
//! 1. 把历史 + 工具声明发给模型；
//! 2. 模型要调工具就执行，把结果**当工具结果塞回对话**（失败、被拒也一样）；
//! 3. 模型不再调工具时结束，把它最后那段话交出来。
//!
//! # 为什么是非流式
//!
//! 和压缩循环同一个理由（D46）：实测这个上游在流式模式下**不下发 `tool_calls`**。
//! 这里的整个用途就是工具调用，走流式等于把命门交给上游的实现细节。
//! 等哪天真要流式工具调用，先去目标端点用 curl 验一遍再说。
//!
//! # 和 `compress::runner` 的关系
//!
//! 长得像，但**故意不合并**。压缩循环里头有它自己的语义：草稿不进上下文、覆盖率
//! 不合格就退回去、用尽轮次转兜底、提交权在代码里。那些东西一旦抽成"通用能力"，
//! 就会变成一堆只有压缩用得上的钩子。宁可先有两份各自读得懂的循环，等第三个用户
//! 出现、看清共同点之后再抽。
//!
//! # 工具失败不结束这一轮
//!
//! 路径越界、没权限、匹配不唯一——这些都是模型**能自己改**的事，所以结果回给它，
//! 让它换条路。只有"模型自己停了"才算这一轮结束。

use crate::budget::Usage;
use crate::clock::LogicalClock;
use crate::ids::{MessageId, SessionId};
use crate::message::{Message, OutgoingToolCall, Role};
use crate::retry::RetryPolicy;
use crate::runtime::{ChatClient, ChatRequest};
use crate::source::SourceTag;
use crate::stream::FinishReason;
use crate::tokens::estimate_tokens;
use crate::tools::{ToolContext, ToolRegistry};
use crate::transport::Transport;

/// 循环预算。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TurnConfig {
    /// 最多几轮工具往返。到顶就交出最后一段话，`finished = false`。
    pub max_rounds: u32,
    /// 覆盖基准请求的 `max_tokens`。`None` = 不动。
    pub max_tokens: Option<u32>,
    /// 绝对线折算成的 token 上限（D90）。工具循环里**按字符增量**累加（L0），
    /// 一旦越过就立刻停手，不再发起下一轮——这时候继续跑只会撞上游的硬上限。
    ///
    /// `None` = 不设这一道闸。
    pub truncate_at: Option<u64>,
}

impl Default for TurnConfig {
    fn default() -> Self {
        Self {
            // 一次任务里读几个文件、改几处，12 轮够用；再多通常说明模型在打转
            max_rounds: 12,
            max_tokens: None,
            truncate_at: None,
        }
    }
}

/// 协作式取消（D58）：**在轮次边界上**问一次，不打断正在飞的网络往返。
///
/// 为什么是 trait 而不是 `AtomicBool`：core 不该知道 UI 那个标志住在哪、谁在置它。
/// 测试里给一个"第 N 次问就说取消"的实现，就能把边界行为钉死，不用起线程。
pub trait Cancellation {
    fn is_cancelled(&self) -> bool;
}

/// 永不取消。默认路径用它，调用方不必为了"我不想取消"写闭包。
#[derive(Debug, Clone, Copy, Default)]
pub struct NeverCancel;

impl Cancellation for NeverCancel {
    fn is_cancelled(&self) -> bool {
        false
    }
}

/// 一次工具调用的记录（UI 的"工具详细"就用它渲染）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolRecord {
    pub name: String,
    pub call_id: String,
    /// 模型给的原始参数（未解析）。
    pub arguments: String,
    pub ok: bool,
    /// 喂回模型的那段文字。
    pub outcome: String,
}

/// 一轮跑完的结果。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TurnReport {
    /// 模型最后输出的正文（可能为空：它可能只想调工具）。
    pub text: String,
    /// 最后一轮**非空**的思考内容。空串 = 这一次没有思考。
    ///
    /// 为什么要单独带出来：`Completion.reasoning` 以前在 `run_turn` 里被丢掉了，
    /// 结果是"供应商后台看得到思考、客户端库里一条都没有"，界面上自然也就没有思考卡。
    /// 取"最后一轮非空"而不是"最后一轮"：收尾那一轮常常只回一句话、没有思考，
    /// 而用户想看的正是最后那次推理。
    pub reasoning: String,
    /// 工具往返轮数。
    pub rounds: u32,
    pub tool_calls: Vec<ToolRecord>,
    /// 最后一轮的结束原因。
    pub finish: Option<FinishReason>,
    /// `false` = 轮数用尽，模型还没收尾。调用方可以继续跑一轮，或就此打住。
    pub finished: bool,
    /// 这一轮是被取消打断的（`finished` 一定也是 `false`）。
    ///
    /// 和"轮数用尽"分开记：前者是用户的意思，后者是预算的意思，UI 该说的话不一样。
    pub cancelled: bool,
    /// 撞上绝对线被截断（D90）。此时 `finished` 也是 `false`，落库会标 `incomplete`。
    pub truncated: bool,
    /// 最后一次请求的用量。工具循环会发好几次，前面的那几次的 prompt 都更短，
    /// 拿它们去校准只会把因子算低——所以只留最后一次。
    pub usage: Usage,
}

impl TurnReport {
    pub fn ok_count(&self) -> usize {
        self.tool_calls.iter().filter(|record| record.ok).count()
    }

    pub fn failed_count(&self) -> usize {
        self.tool_calls.len() - self.ok_count()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AgentError {
    #[error("模型调用失败：{0}")]
    Model(String),
}

/// 跑一轮（可能包含多次工具往返）。
///
/// `history` 是会话现有的消息（含这一轮用户说的话）。返回值里的正文**不落库**——
/// 落库由引擎负责，这一层不碰存储。
///
/// `retry` 是**上层的时间点重试**（D70/D71）。`None` = 一次就一次，不重发。
/// 给了就按每一次工具往返各自算：第 2 轮撞了 503，等一会儿重发的是**那一次请求**，
/// 不是整轮——整轮重发会把已经执行过的工具再来一遍。
#[allow(clippy::too_many_arguments)]
pub fn run_turn<T: Transport>(
    client: &ChatClient<T>,
    base: &ChatRequest,
    history: &[Message],
    registry: &ToolRegistry,
    ctx: &ToolContext<'_>,
    config: &TurnConfig,
    cancel: &dyn Cancellation,
    retry: Option<&RetryPolicy>,
) -> Result<TurnReport, AgentError> {
    let mut messages: Vec<Message> = history.to_vec();
    let mut report = TurnReport::default();
    let mut seq = messages
        .iter()
        .map(|message| message.id.seq())
        .max()
        .unwrap_or(0)
        + 1;
    let session = SessionId::new();
    // 已经在上下文里的部分先记上：截断判据比的是"整份提示 + 新长出来的"。
    let mut spent: u64 = history
        .iter()
        .map(|message| u64::from(estimate_tokens(&message.content)))
        .sum();

    loop {
        // 取消的检查点之一：发请求之前。已经飞在路上的往返不会被打断（D58）。
        if cancel.is_cancelled() {
            return Ok(stopped(report));
        }
        let mut request = base.clone();
        request.stream = false;
        request.messages = messages.clone();
        // 冻结过就按冻结的名单，否则按 policy 现算（D76）。
        request.tools = match ctx.declared {
            Some(names) => registry.specs_named(names),
            None => registry.specs_for(ctx.policy),
        };
        if let Some(tokens) = config.max_tokens {
            request.max_tokens = Some(tokens);
        }

        let completion = match retry {
            Some(policy) => client
                .complete_retrying(&request, policy, cancel)
                .map_err(|error| AgentError::Model(error.to_string()))?,
            None => client
                .complete(&request)
                .map_err(|error| AgentError::Model(error.to_string()))?,
        };
        report.rounds += 1;
        report.finish = Some(completion.finish);
        report.text = completion.text.clone();
        if !completion.reasoning.trim().is_empty() {
            report.reasoning = completion.reasoning.clone();
        }
        report.usage = completion.usage;

        // 绝对线（D90）：生成中只按**字符增量**累加，不重新估算整份上下文。
        // 越线就地停手——下一轮请求只会更大，发出去就是撞上游的硬上限。
        spent = spent.saturating_add(u64::from(estimate_tokens(&completion.text)));
        if config.truncate_at.is_some_and(|limit| spent >= limit) {
            report.finished = false;
            report.truncated = true;
            return Ok(report);
        }

        if completion.tool_calls.is_empty() {
            report.finished = true;
            return Ok(report);
        }

        let outgoing: Vec<OutgoingToolCall> = completion
            .tool_calls
            .iter()
            .enumerate()
            .map(|(index, call)| {
                OutgoingToolCall::new(
                    call.id.clone().unwrap_or_else(|| format!("call_{index}")),
                    call.name.clone(),
                    call.arguments.clone(),
                )
            })
            .collect();

        let mut assistant = Message::tool_call_message(
            MessageId::new(session, seq),
            outgoing.clone(),
            estimate_tokens(&completion.text),
            base.messages.first().map(|m| m.created_at).unwrap_or(0),
            LogicalClock::new(seq),
        );
        assistant.content = completion.text.clone();
        messages.push(assistant);
        seq += 1;

        for call in &outgoing {
            // 检查点之二：每个工具调用之前。已经执行完的那些是真发生过的事，照常记录。
            if cancel.is_cancelled() {
                return Ok(stopped(report));
            }
            let outcome = registry.invoke(&call.name, &call.arguments, ctx);
            let text = outcome.text();
            report.tool_calls.push(ToolRecord {
                name: call.name.clone(),
                call_id: call.id.clone(),
                arguments: call.arguments.clone(),
                ok: outcome.is_ok(),
                outcome: text.clone(),
            });
            spent = spent.saturating_add(u64::from(estimate_tokens(&text)));
            messages.push(Message::tool_result(
                MessageId::new(session, seq),
                &call.id,
                text,
                estimate_tokens(&call.arguments),
                0,
                LogicalClock::new(seq),
            ));
            seq += 1;
        }

        if report.rounds >= config.max_rounds {
            // 到顶了：把手上的话交出去，明确告诉调用方"没收尾"
            report.finished = false;
            return Ok(report);
        }
    }
}

/// 收到取消：把手上已有的东西交出去，明确说"没收尾、是我停的"。
fn stopped(mut report: TurnReport) -> TurnReport {
    report.finished = false;
    report.cancelled = true;
    report
}

/// 把会话历史装成 `run_turn` 要的形状。空 `messages` 会被上游拒，这里先挡住。
pub fn ensure_non_empty(messages: &[Message]) -> Result<Vec<Message>, AgentError> {
    if messages.is_empty() {
        return Err(AgentError::Model(
            "history 是空的：至少要有一条用户消息（空 messages 上游只会回一个看不出原因的 400）"
                .into(),
        ));
    }
    Ok(messages.to_vec())
}

/// 一条最简系统提示：告诉模型它是谁、工具怎么用。平台侧可以整体替换。
pub const DEFAULT_SYSTEM: &str = "\
你在一个本地客户端里运行，可以调用工具读写用户的工作区。

规则：
1. 改文件之前先 read_file 读一遍——凭印象改，改到隔壁是常事。
2. 局部改动用 edit_file（唯一匹配），整篇重写用 write_file；覆盖会丢掉没读到的东西。
3. 工具返回「失败」或「拒绝执行」时不要重试同样的调用，看清楚原因再换一条路。
4. 做完说清楚改了什么、验证了什么。没有验证的就说没有验证。";

/// 组装一条系统消息（`SourceTag::InstructionInjection`，不进历史语义）。
pub fn system_message(text: &str, created_at: i64) -> Message {
    Message::text(
        MessageId::new(SessionId::new(), 1),
        Role::System,
        SourceTag::InstructionInjection,
        text,
        estimate_tokens(text),
        created_at,
        LogicalClock::new(1),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::sync::{Arc, Mutex};

    use crate::policy::{AssistantPolicy, WorkspaceBinding};
    use crate::provider::{ProviderConfig, ProviderKind};
    use crate::transport::{HttpRequest, HttpResponse, Transport, TransportError};
    use crate::workspace::Workspace;

    struct Scripted {
        bodies: Mutex<VecDeque<String>>,
        seen: Mutex<Vec<String>>,
    }

    impl Scripted {
        fn new(bodies: Vec<String>) -> Arc<Self> {
            Arc::new(Self {
                bodies: Mutex::new(bodies.into()),
                seen: Mutex::new(Vec::new()),
            })
        }

        fn requests(&self) -> Vec<String> {
            self.seen.lock().unwrap().clone()
        }
    }

    impl Transport for Arc<Scripted> {
        fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
            self.seen
                .lock()
                .unwrap()
                .push(String::from_utf8_lossy(&request.body).to_string());
            let body = self
                .bodies
                .lock()
                .unwrap()
                .pop_front()
                .ok_or_else(|| TransportError::Unsupported("脚本用完了".into()))?;
            Ok(HttpResponse {
                status: 200,
                headers: vec![("content-type".into(), "application/json".into())],
                body: Box::new(Cursor::new(body.into_bytes())),
            })
        }
    }

    fn escape(text: &str) -> String {
        text.replace('\\', "\\\\").replace('"', "\\\"")
    }

    fn calls_body(calls: &[(&str, &str, &str)]) -> String {
        let items: Vec<String> = calls
            .iter()
            .map(|(id, name, arguments)| {
                format!(
                    "{{\"id\":\"{id}\",\"type\":\"function\",\"function\":\
                     {{\"name\":\"{name}\",\"arguments\":\"{}\"}}}}",
                    escape(arguments)
                )
            })
            .collect();
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"\",\
             \"tool_calls\":[{}]}},\"finish_reason\":\"tool_calls\"}}]}}",
            items.join(",")
        )
    }

    /// 第 `at` 次被问"取消了吗"时开始说"是"。用 Cell 就够：run_turn 是单线程的。
    struct CancelAt {
        asked: std::cell::Cell<usize>,
        at: usize,
    }

    impl CancelAt {
        fn new(at: usize) -> Self {
            Self {
                asked: std::cell::Cell::new(0),
                at,
            }
        }
    }

    impl Cancellation for CancelAt {
        fn is_cancelled(&self) -> bool {
            let asked = self.asked.get() + 1;
            self.asked.set(asked);
            asked >= self.at
        }
    }

    fn text_body(text: &str) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"{}\"}},\
             \"finish_reason\":\"stop\"}}]}}",
            escape(text)
        )
    }

    fn setup() -> (tempfile::TempDir, Workspace, AssistantPolicy) {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a.txt"), "第一行\n第二行\n").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        (dir, workspace, policy)
    }

    fn history() -> Vec<Message> {
        let session = SessionId::new();
        vec![Message::text(
            MessageId::new(session, 1),
            Role::User,
            SourceTag::History,
            "把 a.txt 里的第二行读给我看",
            20,
            0,
            LogicalClock::new(1),
        )]
    }

    fn base_request() -> ChatRequest {
        let provider = ProviderConfig::new("smoke", ProviderKind::OpenAiCompatible, "http://x/v1");
        ChatRequest::new(provider, "test-model")
    }

    #[test]
    fn plain_reply_finishes_in_one_round() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![text_body("第二行是「第二行」。")]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();

        assert_eq!(report.rounds, 1);
        assert!(report.finished);
        assert!(report.tool_calls.is_empty());
        assert!(report.text.contains("第二行"));
        // 工具声明进请求了
        assert!(transport.requests()[0].contains("read_file"));
    }

    #[test]
    fn tool_call_is_executed_and_result_fed_back() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "read_file", r#"{"path":"a.txt"}"#)]),
            text_body("第二行是：第二行"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();

        assert_eq!(report.rounds, 2);
        assert!(report.finished);
        assert_eq!(report.tool_calls.len(), 1);
        assert_eq!(report.tool_calls[0].name, "read_file");
        assert!(report.tool_calls[0].ok);
        assert!(report.tool_calls[0].outcome.contains("第一行"));
        // 第二次请求里带着工具结果
        let requests = transport.requests();
        assert!(requests[1].contains("第一行"), "{}", requests[1]);
    }

    #[test]
    fn absolute_line_stops_the_loop_before_the_next_request() {
        // 第一轮就想调工具；绝对线在它还没落地时就到了 → 不再发第二次请求。
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "read_file", r#"{"path":"a.txt"}"#)]),
            text_body("这一句不该被发出去"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let config = TurnConfig {
            truncate_at: Some(1),
            ..TurnConfig::default()
        };
        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &config,
            &NeverCancel,
            None,
        )
        .unwrap();

        assert!(report.truncated, "过绝对线要明确记下来");
        assert!(!report.finished, "没说完——落库会标 incomplete");
        assert_eq!(report.rounds, 1);
        assert!(report.tool_calls.is_empty(), "截断之后不该再执行工具");
        assert_eq!(transport.requests().len(), 1, "不该有第二次请求");
    }

    #[test]
    fn truncation_is_independent_of_the_round_cap() {
        // 没设绝对线时，同样两轮脚本能正常跑完——证明上面那条红的不是别的原因。
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "read_file", r#"{"path":"a.txt"}"#)]),
            text_body("读完了"),
        ]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();
        assert!(report.finished);
        assert!(!report.truncated);
        assert_eq!(report.rounds, 2);
    }

    #[test]
    fn denied_tool_becomes_a_tool_result_and_the_loop_continues() {
        // 只读绑定：模型想写，被拒，然后改口
        let (_dir, workspace, _) = setup();
        let readonly =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "write_file", r#"{"path":"b.txt","content":"x"}"#)]),
            text_body("只能读，没写。"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&readonly, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();

        assert_eq!(report.rounds, 2);
        assert!(!report.tool_calls[0].ok);
        assert!(report.tool_calls[0].outcome.contains("拒绝执行"));
        assert!(report.tool_calls[0].outcome.contains("只读"));
        // 被拒之后没有凭空多出文件
        assert!(!workspace.root().join("b.txt").exists());
    }

    #[test]
    fn unknown_tool_lists_what_is_available() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "rm_rf", "{}")]),
            text_body("换个办法。"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();

        assert!(!report.tool_calls[0].ok);
        assert!(report.tool_calls[0].outcome.contains("未知工具"));
        assert!(report.tool_calls[0].outcome.contains("read_file"));
    }

    #[test]
    fn rounds_are_capped_and_reported_honestly() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "list_dir", "{}")]),
            calls_body(&[("c2", "list_dir", "{}")]),
            calls_body(&[("c3", "list_dir", "{}")]),
        ]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let config = TurnConfig {
            max_rounds: 2,
            ..TurnConfig::default()
        };

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &config,
            &NeverCancel,
            None,
        )
        .unwrap();

        assert_eq!(report.rounds, 2);
        assert!(!report.finished, "轮数用尽时不能假装做完了");
        assert_eq!(report.tool_calls.len(), 2);
    }

    #[test]
    fn empty_history_is_refused_before_the_request() {
        let err = ensure_non_empty(&[]).unwrap_err();
        assert!(err.to_string().contains("至少要有一条用户消息"));
        let single = history();
        assert_eq!(ensure_non_empty(&single).unwrap().len(), 1);
    }

    /// 取消在第一次请求之前就问一次：模型一次都不该被调用（D58 的检查点之一）。
    #[test]
    fn cancel_before_the_first_round_sends_nothing() {
        let transport = Scripted::new(vec![text_body("不该被用到")]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let (_dir, workspace, policy) = setup();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &CancelAt::new(1),
            None,
        )
        .unwrap();

        assert!(report.cancelled);
        assert!(!report.finished);
        assert_eq!(report.rounds, 0);
        assert!(report.tool_calls.is_empty());
        assert!(
            transport.requests().is_empty(),
            "取消在发请求之前就生效，模型一次都不该被调用"
        );
    }

    /// 取消在工具调用之间生效：已经跑完的工具是真发生过的事，照常留着；
    /// 后面的不再执行。这是"不伪造历史"和"真的停下来"之间的那个点。
    #[test]
    fn cancel_between_tool_calls_keeps_what_already_ran() {
        let (dir, workspace, policy) = setup();
        std::fs::write(dir.path().join("b.txt"), "beta\n").unwrap();
        let transport = Scripted::new(vec![calls_body(&[
            ("c1", "list_dir", "{}"),
            ("c2", "read_file", r#"{"path":"b.txt"}"#),
        ])]);
        let client = ChatClient::new(transport);
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));

        // 询问顺序：请求前(1)、第一个工具前(2)、第二个工具前(3)
        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &registry,
            &ctx,
            &TurnConfig::default(),
            &CancelAt::new(3),
            None,
        )
        .unwrap();

        assert!(report.cancelled);
        assert!(!report.finished, "自己停的不能算做完了");
        assert_eq!(report.rounds, 1);
        assert_eq!(report.tool_calls.len(), 1, "第二个工具不该被执行");
        assert_eq!(report.tool_calls[0].name, "list_dir");
    }
}
