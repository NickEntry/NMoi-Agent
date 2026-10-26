//! 接模型跑压缩：工具循环。
//!
//! [`super::CompressionJob`] 只管"草稿 + 校验 + 提交"，不知道模型长什么样。
//! 这个模块负责把两者接起来：
//!
//! 1. 组脚手架：压缩规则 + 要压缩的原文（每条消息一行 `[seq] role: 内容`）；
//! 2. 一轮一轮地让模型调草稿工具，把工具返回（包括失败）**当工具结果喂回去**；
//! 3. 见到 `compress_draft_done` 就试着 `commit`：覆盖率不够就把缺失项当一条 user 消息
//!    喂回去，让它继续补；
//! 4. 往返次数用尽就交出 `Err`，由调用方决定要不要走 `commit_fallback`。
//!
//! 有一点是故意的：**工具失败不结束这一轮**。模型写错 `edit` 的 find、超出草稿上限、
//! 把 done 提前调了，这些都是它自己该修正的事，失败信息回给它才有机会改。

use crate::archive::ArchiveStore;
use crate::clock::LogicalClock;
use crate::ids::{MessageId, SessionId};
use crate::message::{Message, OutgoingToolCall, Role};
use crate::runtime::{ChatClient, ChatRequest};
use crate::source::SourceTag;
use crate::stream::FinishReason;
use crate::tokens::estimate_tokens;
use crate::transport::Transport;

use super::{CompressError, CompressionJob, CompressionOutcome, JobState};

/// 工具循环的预算。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LoopConfig {
    /// 模型往返次数上限（含被退回后继续补的轮次）。
    pub max_exchanges: u32,
    /// 覆盖基准请求里的 `max_tokens`。`None` = 不动。
    pub max_tokens: Option<u32>,
}

impl Default for LoopConfig {
    fn default() -> Self {
        Self {
            max_exchanges: 8,
            // 摘要不需要长输出，但思考型模型的思考也算在里面，别卡太死
            max_tokens: Some(16_384),
        }
    }
}

/// 一次压缩的跑动记录。用来看"模型到底守不守规矩"。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LoopReport {
    /// 模型往返次数。
    pub exchanges: u32,
    /// 收到的草稿工具调用次数（含失败的）。
    pub tool_calls: u32,
    /// 覆盖率退回次数。
    pub rejections: u32,
    /// 工具失败的原文（按发生顺序）。事后排查"模型为什么写不完"靠这个。
    pub errors: Vec<String>,
    /// 最后一次的结束原因。
    pub finish: Option<FinishReason>,
    /// 提交结果。`None` 表示这轮没成，调用方该走兜底。
    pub outcome: Option<CompressionOutcome>,
}

/// 压缩脚手架：告诉模型它在干什么、要留什么、写在哪。
pub const SCAFFOLD: &str = "\
你是上下文压缩器。下面这段历史对话即将被移出上下文窗口，窗口里只留下你写的摘要。

规则：
1. 用 compress_draft_append 分段写入草稿。草稿不进对话上下文，写长没关系，但必须准确。
2. 写错了用 compress_draft_edit 改（只接受唯一匹配）；拿不准先用 compress_draft_read 读回来。
3. 必须留住：任务目标、做过的决定和理由、改过的文件路径与命令、失败的尝试及原因、还没做完的事。\
用户明确要求记住的内容照抄，不要改写。
4. 不要写「用户问……我回答……」的流水账，写成「改了什么、为什么、结果如何」。
5. **通篇用第一人称写**：写「我记得 / 我说过 / 我做过 / 我还没做完」，不写「用户与助手」的第三方报告。
   这份摘要之后会被当成你自己的记忆（D147：第三人称摘要的高置信率 22%，第一人称 62%）。
6. 写完调用 compress_draft_done。执行器会核对关键项，缺了会退回来让你补——补在原文里，别重写整篇。";

/// 把线上内容渲染成给压缩模型看的材料。
pub fn material(job: &CompressionJob) -> String {
    let mut out = String::new();
    let mut messages = 0usize;
    let mut tokens: u64 = 0;
    for unit in job.above() {
        for message in &unit.messages {
            messages += 1;
            tokens += u64::from(message.tokens);
            let seq = message.id.seq();
            if !message.tool_calls.is_empty() {
                let calls: Vec<String> = message
                    .tool_calls
                    .iter()
                    .map(|call| format!("{}({})", call.name, call.arguments))
                    .collect();
                out.push_str(&format!("[{seq}] assistant → 工具 {}\n", calls.join("; ")));
            }
            if let Some(reasoning) = message.reasoning.as_deref() {
                if !reasoning.trim().is_empty() {
                    out.push_str(&format!("[{seq}] 思考（可能有用，可压缩）: {reasoning}\n"));
                }
            }
            if !message.content.trim().is_empty() {
                out.push_str(&format!(
                    "[{seq}] {}: {}\n",
                    message.role.as_str(),
                    message.content
                ));
            }
        }
    }
    format!(
        "共 {} 个原子单元 / {} 条消息 / 约 {} token：\n\n{out}",
        job.above().len(),
        messages,
        tokens
    )
}

/// 组第一轮的消息：脚手架 + 材料。两条都进不了主上下文——它们只活在这条请求里。
pub fn scaffold(job: &CompressionJob) -> Vec<Message> {
    let session = SessionId::new();
    let created_at = 0;
    vec![
        Message::text(
            MessageId::new(session, 1),
            Role::System,
            SourceTag::InstructionInjection,
            SCAFFOLD,
            estimate_tokens(SCAFFOLD),
            created_at,
            LogicalClock::new(1),
        ),
        Message::text(
            MessageId::new(session, 2),
            Role::User,
            SourceTag::History,
            material(job),
            0,
            created_at,
            LogicalClock::new(2),
        ),
    ]
}

/// 跑完整个压缩循环。
///
/// 返回时 `report.outcome` 为 `None` 表示没提交成功（往返用尽），
/// **不自动兜底**——兜底要不要开，是调用方的决定。
pub fn drive<T: Transport, S: ArchiveStore>(
    client: &ChatClient<T>,
    base: &ChatRequest,
    job: &mut CompressionJob,
    archive: &mut S,
    config: &LoopConfig,
    now: i64,
) -> Result<LoopReport, CompressError> {
    let mut messages = scaffold(job);
    let mut report = LoopReport::default();
    let mut seq = 3u64;

    loop {
        if report.exchanges >= config.max_exchanges {
            return Err(CompressError::TooManyExchanges {
                max: config.max_exchanges,
            });
        }

        // 非流式，故意的：压缩是批量任务，没有"首字延迟"可言；而且
        // 实测本机代理在流式模式下**根本不下发 tool_calls**（非流式给两条，流式 0 帧）。
        // 工具循环靠的就是 tool_calls，走流式等于把命门交给上游的实现细节。见 D46。
        let mut request = base.clone();
        request.stream = false;
        request.messages = messages.clone();
        request.tools = job.tool_specs();
        if let Some(tokens) = config.max_tokens {
            request.max_tokens = Some(tokens);
        }

        let completion = client
            .complete(&request)
            .map_err(|error| CompressError::Model(error.to_string()))?;
        report.exchanges += 1;
        report.finish = Some(completion.finish);

        let text = completion.text;
        let outgoing: Vec<OutgoingToolCall> = completion
            .tool_calls
            .iter()
            .enumerate()
            .map(|(n, call)| {
                OutgoingToolCall::new(
                    call.id.clone().unwrap_or_else(|| format!("draft_{n}")),
                    call.name.clone(),
                    call.arguments.clone(),
                )
            })
            .collect();

        if outgoing.is_empty() {
            // 模型没调工具就停了。把话收回来，给它一次机会；这条 nudge 也算一次往返。
            if !text.trim().is_empty() {
                messages.push(plain_message(seq, Role::Assistant, &text));
                seq += 1;
            }
            messages.push(plain_message(
                seq,
                Role::User,
                "还没收到 compress_draft_done。请用草稿工具继续写，写完调用它。",
            ));
            seq += 1;
            report
                .errors
                .push("模型这一轮没有调用任何草稿工具".to_string());
            continue;
        }

        // 助手的工具调用消息
        let mut call_message = Message::tool_call_message(
            MessageId::new(SessionId::new(), seq),
            outgoing.clone(),
            estimate_tokens(&text),
            now,
            LogicalClock::new(seq),
        );
        call_message.content = text.clone();
        messages.push(call_message);
        seq += 1;

        for call in &outgoing {
            report.tool_calls += 1;
            let reply = match job.handle(&call.name, &call.arguments) {
                Ok(reply) => reply.text,
                Err(error) => {
                    // 关键：工具失败变成工具结果，不结束这一轮
                    let text = error.tool_text();
                    report.errors.push(format!("{}: {text}", call.name));
                    text
                }
            };
            messages.push(Message::tool_result(
                MessageId::new(SessionId::new(), seq),
                &call.id,
                reply,
                estimate_tokens(&call.arguments),
                now,
                LogicalClock::new(seq),
            ));
            seq += 1;
        }

        match job.state() {
            JobState::Done => match job.commit(archive, now) {
                Ok(outcome) => {
                    report.outcome = Some(outcome);
                    return Ok(report);
                }
                Err(CompressError::Coverage {
                    summary,
                    rounds_left,
                }) => {
                    report.rejections += 1;
                    messages.push(plain_message(
                        seq,
                        Role::User,
                        &format!(
                            "拒绝提交：{summary}。把缺的项补进草稿再调用 compress_draft_done\
                             （还可退回 {rounds_left} 次）。"
                        ),
                    ));
                    seq += 1;
                }
                Err(error) => return Err(error),
            },
            JobState::Forced => {
                return Err(CompressError::TooManyRounds {
                    max: job.max_rounds(),
                })
            }
            _ => {}
        }
    }
}

fn plain_message(seq: u64, role: Role, text: &str) -> Message {
    Message::text(
        MessageId::new(SessionId::new(), seq),
        role,
        if role == Role::Assistant {
            SourceTag::History
        } else {
            SourceTag::InstructionInjection
        },
        text,
        estimate_tokens(text),
        0,
        LogicalClock::new(seq),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::sync::{Arc, Mutex};

    use crate::archive::MemoryArchive;
    use crate::message::AtomicUnit;
    use crate::provider::{ProviderConfig, ProviderKind};
    use crate::transport::{HttpRequest, HttpResponse, Transport, TransportError};

    const PATH_KEY: &str = "crates/core/src/runtime.rs";

    /// 按脚本回放的非流式响应。每调一次 `complete` 弹一段 JSON，同时记下请求体。
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

    /// 一轮带工具调用的非流式响应。可以同时给多个调用（上游就会这么干）。
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

    fn text_body(text: &str) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":\"{}\"}},\
             \"finish_reason\":\"stop\"}}]}}",
            escape(text)
        )
    }

    fn history() -> (SessionId, Vec<AtomicUnit>) {
        let session = SessionId::new();
        let build = |seq: u64, role: Role, text: &str| {
            let message = Message::text(
                MessageId::new(session, seq),
                role,
                SourceTag::History,
                text,
                200,
                0,
                LogicalClock::new(seq),
            );
            AtomicUnit::turn(message).unwrap()
        };
        let units = vec![
            build(1, Role::User, &format!("帮我改一下 {PATH_KEY} 的重试逻辑")),
            build(2, Role::Assistant, "改成了先校验头部再开 socket。"),
            build(3, Role::User, "跑 cargo test 看看"),
            build(4, Role::Assistant, "全绿。"),
        ];
        (session, units)
    }

    fn job() -> CompressionJob {
        let (session, units) = history();
        CompressionJob::start(session, &units, 400, 0, Default::default()).unwrap()
    }

    fn base_request() -> ChatRequest {
        let provider = ProviderConfig::new("smoke", ProviderKind::OpenAiCompatible, "http://x/v1");
        ChatRequest::new(provider, "test-model")
    }

    #[test]
    fn material_renders_only_the_online_window() {
        let job = job();
        let text = material(&job);
        assert!(text.contains("[1] user:"), "{text}");
        assert!(text.contains(PATH_KEY));
        assert!(text.contains("2 个原子单元"));
        assert!(text.contains("2 条消息"), "{text}");
        // 线下的消息不该出现在材料里
        assert!(!text.contains("全绿"));
    }

    #[test]
    fn one_round_tool_call_then_commit() {
        let mut job = job();
        let transport = Scripted::new(vec![
            calls_body(&[(
                "c1",
                "compress_draft_append",
                &format!("{{\"text\":\"已按 {PATH_KEY} 改好重试逻辑\"}}"),
            )]),
            calls_body(&[("c2", "compress_draft_done", "{}")]),
        ]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();

        let report = drive(
            &client,
            &base_request(),
            &mut job,
            &mut archive,
            &LoopConfig::default(),
            5,
        )
        .unwrap();

        assert_eq!(report.exchanges, 2);
        assert_eq!(report.tool_calls, 2);
        assert_eq!(report.rejections, 0);
        let outcome = report.outcome.expect("应当提交成功");
        assert!(outcome.coverage.is_complete());
        assert_eq!(job.state(), JobState::Committed);
        // 请求必须是非流式（D46：流式下这个上游不给 tool_calls）
        let requests = transport.requests();
        assert!(requests[0].contains("\"stream\":false"), "{}", requests[0]);
    }

    #[test]
    fn several_calls_in_one_response_are_all_executed() {
        let mut job = job();
        // 上游会一次给多个调用；顺序必须是它们出现的顺序
        let transport = Scripted::new(vec![
            calls_body(&[
                ("a", "compress_draft_append", "{\"text\":\"第一段\"}"),
                ("b", "compress_draft_append", "{\"text\":\"第二段\"}"),
                (
                    "c",
                    "compress_draft_edit",
                    &format!("{{\"find\":\"第二段\",\"replace\":\"{PATH_KEY}\"}}"),
                ),
            ]),
            calls_body(&[("d", "compress_draft_done", "{}")]),
        ]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();
        let report = drive(
            &client,
            &base_request(),
            &mut job,
            &mut archive,
            &LoopConfig::default(),
            0,
        )
        .unwrap();
        assert_eq!(report.tool_calls, 4);
        let outcome = report.outcome.unwrap();
        assert!(outcome.summary.contains(PATH_KEY));
        assert!(
            !outcome.summary.contains("第二段"),
            "改过的片段应当已经被替换"
        );
    }

    #[test]
    fn coverage_rejection_is_fed_back_and_retried() {
        let mut job = job();
        let transport = Scripted::new(vec![
            calls_body(&[(
                "c1",
                "compress_draft_append",
                "{\"text\":\"改好了重试逻辑\"}",
            )]),
            calls_body(&[("c2", "compress_draft_done", "{}")]),
            calls_body(&[("c3", "compress_draft_read", "{}")]),
            calls_body(&[(
                "c4",
                "compress_draft_edit",
                &format!("{{\"find\":\"改好了\",\"replace\":\"已按 {PATH_KEY} 改好了\"}}"),
            )]),
            calls_body(&[("c5", "compress_draft_done", "{}")]),
        ]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();

        let report = drive(
            &client,
            &base_request(),
            &mut job,
            &mut archive,
            &LoopConfig::default(),
            5,
        )
        .unwrap();

        assert_eq!(report.rejections, 1);
        assert_eq!(report.exchanges, 5);
        assert!(report.outcome.is_some());

        let requests = transport.requests();
        assert_eq!(requests.len(), 5);
        assert!(
            requests[2].contains("拒绝提交"),
            "第三次请求里应当带着退回原因"
        );
        assert!(requests[2].contains(PATH_KEY), "退回原因里要带缺失的关键项");
    }

    #[test]
    fn missing_done_is_nudged_then_gives_up() {
        let mut job = job();
        let transport = Scripted::new(vec![text_body("我先想想。"), text_body("还是先想想。")]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();
        let config = LoopConfig {
            max_exchanges: 2,
            ..LoopConfig::default()
        };

        let err = drive(&client, &base_request(), &mut job, &mut archive, &config, 0).unwrap_err();
        assert_eq!(err, CompressError::TooManyExchanges { max: 2 });
        let requests = transport.requests();
        assert!(requests[1].contains("compress_draft_done"));
        assert_eq!(job.state(), JobState::Collecting, "没提交就不该改状态");
    }

    #[test]
    fn invalid_edit_is_returned_as_tool_result_not_fatal() {
        let mut job = job();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "compress_draft_append", "{\"text\":\"改好了\"}")]),
            calls_body(&[(
                "c2",
                "compress_draft_edit",
                "{\"find\":\"不存在\",\"replace\":\"x\"}",
            )]),
            calls_body(&[(
                "c3",
                "compress_draft_append",
                &format!("{{\"text\":\"已按 {PATH_KEY} 改好了\"}}"),
            )]),
            calls_body(&[("c4", "compress_draft_done", "{}")]),
        ]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();

        let report = drive(
            &client,
            &base_request(),
            &mut job,
            &mut archive,
            &LoopConfig::default(),
            0,
        )
        .unwrap();

        assert_eq!(report.rejections, 0);
        assert!(report.outcome.is_some());
        assert!(
            report.errors.iter().any(|line| line.contains("找不到")),
            "{:?}",
            report.errors
        );
        assert!(transport.requests()[2].contains("找不到"));
    }

    #[test]
    fn every_request_declares_four_tools_and_carries_history() {
        let mut job = job();
        let transport = Scripted::new(vec![
            calls_body(&[(
                "c1",
                "compress_draft_append",
                &format!("{{\"text\":\"已按 {PATH_KEY} 改好\"}}"),
            )]),
            calls_body(&[("c2", "compress_draft_done", "{}")]),
        ]);
        let client = ChatClient::new(transport.clone());
        let mut archive = MemoryArchive::new();
        drive(
            &client,
            &base_request(),
            &mut job,
            &mut archive,
            &LoopConfig::default(),
            0,
        )
        .unwrap();

        let requests = transport.requests();
        assert_eq!(requests.len(), 2);
        for name in super::super::DRAFT_TOOLS {
            assert!(requests[0].contains(name), "缺少工具声明 {name}");
        }
        assert!(requests[0].contains(PATH_KEY));
        assert!(requests[1].contains("已追加"), "{}", requests[1]);
    }
}
