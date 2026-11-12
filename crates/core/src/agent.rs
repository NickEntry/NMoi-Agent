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
use crate::message::{Message, OutgoingToolCall, Role, ToolStatus};
use crate::retry::RetryPolicy;
use crate::runtime::Collected;
use crate::runtime::{ChatClient, ChatRequest};
use crate::source::SourceTag;
use crate::stream::Completion;
use crate::stream::FinishReason;
use crate::tokens::estimate_tokens;
use crate::tools::{Lane, ToolContext, ToolOutcome, ToolRegistry};
use crate::transport::Transport;

/// 循环预算。
///
/// 默认值就是"什么都不设"：不限轮数、不动 `max_tokens`、不设绝对线、不走流式。
/// 所以用 `derive(Default)`——手写一份全零的 `impl` 只会被 clippy 指出来（D245）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct TurnConfig {
    /// 最多几轮工具往返。**0 = 不限制**（D245）。
    ///
    /// 一个 agent 该能在一句话里调任意多次工具，只要上下文还装得下——真正的边界是
    /// 绝对线（[TurnConfig::truncate_at]）、用户取消、和上游自己的限制。硬性轮数上限
    /// 是早期拍的安全阀，撞上会把话截断，用户什么都没做错却"回答被砍了一半"。
    /// 想给模型设个闸的人可以显式给一个数；到顶之后还会再给一轮不带工具的收尾。
    pub max_rounds: u32,
    /// 覆盖基准请求的 `max_tokens`。`None` = 不动。
    pub max_tokens: Option<u32>,
    /// 绝对线折算成的 token 上限（D90）。工具循环里**按字符增量**累加（L0），
    /// 一旦越过就立刻停手，不再发起下一轮——这时候继续跑只会撞上游的硬上限。
    ///
    /// `None` = 不设这一道闸。
    pub truncate_at: Option<u64>,
    /// 走流式（0.15n）：请求带 `stream: true`，增量经 [`crate::progress::emit`] 交出去。
    ///
    /// 默认 `false`：没装钩子时走流式只会多一层解析，界面也还是等整段。
    pub stream: bool,
    /// 上下文图片清单的上限（D248）。`0` = 用默认 20；硬上限 100（三家端点里最弱的
    /// Anthropic 就是 100 张/请求）。
    pub image_limit: u32,
    /// 用户发的图怎么进请求（D270）。
    pub image_mode: ImageMode,
    /// 图片清单满了之后还能临时多留几张（D272）：`0` = 关。
    pub image_temp_limit: u32,
}

/// 用户发的图怎么进请求（D270）。
///
/// 两种都成立，选哪种是用户的事：内联省一次工具往返，路径省 token。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ImageMode {
    /// 把字节直接发给模型（默认，跟以前一样）。
    #[default]
    Inline,
    /// 一张字节都不发，只把文件名告诉模型，让它自己调 `View-Image` 挑。
    ///
    /// 只在"真的能调 `View-Image`"时生效（要读权限，也就是会话绑了工作区）：
    /// 否则图会变成"看得见文件名、永远读不到"，比内联更糟。
    Path,
}

impl ImageMode {
    /// 认两个词：`path` / 其余一律 `inline`。
    ///
    /// 不在这里报错：设置里那一格是开关，传进来的值只可能是这两个；
    /// 真写错了，退回默认比让整轮发不出去好。
    pub fn parse(raw: &str) -> Self {
        match raw.trim() {
            "path" => Self::Path,
            _ => Self::Inline,
        }
    }
}

/// 协作式取消（D58）：**在轮次边界上**问一次，不打断正在飞的网络往返。
///
/// 为什么是 trait 而不是 `AtomicBool`：core 不该知道 UI 那个标志住在哪、谁在置它。
/// 测试里给一个"第 N 次问就说取消"的实现，就能把边界行为钉死，不用起线程。
pub trait Cancellation: Send + Sync {
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

/// 一次模型往返的产出（D253）。
///
/// 为什么要按轮记：`text` 每轮都被覆盖，多轮工具往返时**只有最后一轮的正文**能落库——
/// 前面几轮模型说过的话，用户屏幕上看见过，重开会话就没了。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TurnRound {
    /// 这一轮的正文（已切掉行内工具标签）。
    pub text: String,
    /// 这一轮自己的思考。空串 = 这一轮没有。
    pub reasoning: String,
    /// 这一轮发起的调用（按模型给的顺序）。空 = 这一轮没调工具。
    ///
    /// 带完整调用（不只是 id）：没有跑成的那几条（批次被取消）也要能落库——
    /// 库里那一轮发出去过什么，界面上就得能指出来。
    pub calls: Vec<OutgoingToolCall>,
}

/// 一轮跑完的结果。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TurnReport {
    /// 模型最后输出的正文（可能为空：它可能只想调工具）。
    pub text: String,
    /// 每一次模型往返自己的正文 / 思考 / 调用（D253）。落库按它逐轮写。
    pub rounds_detail: Vec<TurnRound>,
    /// 最后一轮**非空**的思考内容。空串 = 这一次没有思考。
    ///
    /// 为什么要单独带出来：`Completion.reasoning()` 以前在 `run_turn` 里被丢掉了，
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
    /// 连接中途断了（D327）：已经收到的正文留着，`interrupted` 里是原因。
    ///
    /// 和 `truncated` 分开记的理由跟 `cancelled` 一样——三种"没说完"要说三句话：
    /// 撞了绝对线（上下文满了）、连接断了（网络）、用户点了停止。以前断流借用 `truncated`，
    /// 界面上于是写着"上下文到了绝对线"，而用户其实什么都没满。
    pub cut: Option<String>,
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
    #[error("模型调用失败：{message}")]
    Model {
        message: String,
        /// 上游回的 HTTP 状态。401/403 说明**这把钥匙不行**，界面要据此标那一把。
        /// 拿不到（断流、解析失败、自己造的错误）就是 `None`。
        status: Option<u16>,
    },
}

impl AgentError {
    pub fn http_status(&self) -> Option<u16> {
        match self {
            AgentError::Model { status, .. } => *status,
        }
    }
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
        .map(|message| u64::from(estimate_tokens(&message.content())))
        .sum();

    // 到上限之后**再给一轮**：这一轮不带工具，让它把话说完（D244）。
    // 直接返回的话，用户看到的是"回答被砍了一半"，而他什么都没做错。
    let mut wrap_up = false;
    // 这一轮清单里那些图的字节（D269）：`request` 每轮从 `base` 重建，所以每轮都要重挂，
    // 但**同一张图只读一遍盘**。键是附件 id。
    let mut context_images: Vec<(String, crate::message::ImagePart)> = Vec::new();
    loop {
        // 取消的检查点之一：发请求之前。已经飞在路上的往返不会被打断（D58）。
        if cancel.is_cancelled() {
            return Ok(stopped(report));
        }
        // 换轮（0.15ah）：工具往返之后模型重新开口，上一轮那段话不算这一轮的正文。
        // 界面靠这一声把手里的缓冲清掉——不清的话收尾落库时正文会突然缩掉一半。
        crate::progress::round_start();
        let mut request = base.clone();
        request.stream = config.stream;
        request.messages = messages.clone();
        // 上下文图片（D248 / D269）：`View-Image` 可能就在**上一轮**里把图加进了清单，
        // 所以每轮发请求之前并一次——不然要等下一次对话才带得上（用户 2026-11-06 报的）。
        attach_context_images(&mut request, ctx, &mut context_images);
        // 冻结过就按冻结的名单，否则按 policy 现算（D76）。
        request.tools = if wrap_up {
            // 收尾那一轮不给工具：给了它就会继续调，永远收不了尾。
            Vec::new()
        } else {
            match ctx.declared {
                Some(names) => registry.specs_named(names),
                None => registry.specs_for(ctx.policy),
            }
        };
        if let Some(tokens) = config.max_tokens {
            request.max_tokens = Some(tokens);
        }

        // 流式（0.15n）：边收边把增量交出去，最终拼成与 `complete()` 同形的结果。
        // 断流不算失败——残文留着，下面按 `truncated` 收尾（D194）。
        let mut interrupted = false;
        // 断在哪儿（D327）：只有"流没等到终止信号"时才填，别的收尾原因不借这个字段。
        let mut cut_reason: Option<String> = None;
        let completion = if config.stream {
            let mut stream = client.chat(&request).map_err(|error| AgentError::Model {
                message: error.to_string(),
                status: error.http_status(),
            })?;
            // 边收边自己留一份（D327）：`collect_all` 出错时不会把已经收到的字还回来，
            // 而"收到过字"正是判断这一轮该不该按断流收尾的唯一依据。
            let mut seen_text = String::new();
            let mut seen_reasoning = String::new();
            let collected = stream.collect_all(|text, reasoning| {
                seen_text.push_str(text);
                seen_reasoning.push_str(reasoning);
                crate::progress::emit(text, reasoning);
            });
            let collected = match collected {
                Ok(collected) => collected,
                // 收到过字：按断流收尾，残文留着（D194 的同一条规矩——丢掉才是失败）。
                // 一个字都没有才算真失败：那种情况没有可以接的东西。
                Err(_) if !seen_text.trim().is_empty() => Collected {
                    completion: Completion {
                        text: seen_text,
                        reasoning: seen_reasoning,
                        tool_calls: Vec::new(),
                        finish: FinishReason::Unknown("interrupted".into()),
                        usage: Usage::default(),
                        model: None,
                        id: None,
                    },
                    interrupted: Some("连接在生成途中断了".to_string()),
                },
                Err(error) => {
                    return Err(AgentError::Model {
                        message: error.to_string(),
                        status: error.http_status(),
                    })
                }
            };
            // 端点不认 `stream`（回了一段普通 JSON / 干脆没回）→ 一个字都没收到、流就断了。
            // 这时候退回**非流式**重发这一轮一次：不这么做，"端点不支持流式"就变成"回答是空的"。
            //
            // 只有**一个字都没有**才退回：已经收到过正文的话，重发会把两段拼成一条不存在的回答（D40）。
            if collected.interrupted.is_some() && collected.completion.text.trim().is_empty() {
                let mut plain = request.clone();
                plain.stream = false;
                match retry {
                    Some(policy) => {
                        client
                            .complete_retrying(&plain, policy, cancel)
                            .map_err(|error| AgentError::Model {
                                message: error.to_string(),
                                status: error.error.http_status(),
                            })?
                    }
                    None => client.complete(&plain).map_err(|error| AgentError::Model {
                        message: error.to_string(),
                        status: error.http_status(),
                    })?,
                }
            } else {
                interrupted = collected.interrupted.is_some();
                if interrupted {
                    cut_reason = collected.interrupted.clone();
                }
                collected.completion
            }
        } else {
            match retry {
                Some(policy) => {
                    client
                        .complete_retrying(&request, policy, cancel)
                        .map_err(|error| AgentError::Model {
                            message: error.to_string(),
                            status: error.error.http_status(),
                        })?
                }
                None => client
                    .complete(&request)
                    .map_err(|error| AgentError::Model {
                        message: error.to_string(),
                        status: error.http_status(),
                    })?,
            }
        };
        report.rounds += 1;
        report.finish = Some(completion.finish);
        // 临时多留的图（D272）：这一轮的思考已经结束，它们该走了。
        // 放在这里而不是"下一轮请求之前"：下一轮请求正是它们该被看到的那一次。
        // 不收的话，一次对话里每多调一张图，上下文就永久涨一张。
        if let Some(session) = ctx.call_session {
            crate::images::trim(session, crate::images::limit_of(config.image_limit));
        }
        // 行内思考标签（D212）：有些端点把思考塞进正文（`<think>…</think>`，
        // 还会重复收尾、留空块）。先切出来，再决定用哪一份思考。
        let inline = crate::thinkparse::split(&completion.text);
        // 正文里的工具调用（D212）：端点没给结构化 `tool_calls` 时才认，
        // 认不出就原样留在正文里（见 `toolparse` 的文档）。
        let mut visible = inline.text.clone();
        let mut inline_calls = Vec::new();
        if completion.tool_calls.is_empty() {
            let (rest, calls) = crate::toolparse::extract(&visible);
            if !calls.is_empty() {
                visible = rest;
                inline_calls = calls;
            }
        }
        report.text = visible.clone();
        // 这一轮**自己**的思考：`report.reasoning()` 取"最后一轮非空"（收尾那轮常常没思考），
        // 但落库要按轮给，所以这里单独算一份。
        let round_reasoning = if !completion.reasoning.trim().is_empty() {
            // 端点自己给了 `reasoning_content`：以它为准（结构化的那份更可信）。
            completion.reasoning.clone()
        } else {
            inline.thinking.clone()
        };
        if !round_reasoning.trim().is_empty() {
            report.reasoning = round_reasoning.clone();
        }
        report.rounds_detail.push(TurnRound {
            text: visible.clone(),
            reasoning: round_reasoning,
            calls: Vec::new(),
        });
        report.usage = completion.usage;

        // 流断了（没等到终止信号）：这一轮到此为止，残文已经落在 `report.text` 里，
        // 落库会按 incomplete 处理——别让它看起来像"说完了"。
        if interrupted {
            report.finished = false;
            report.truncated = true;
            report.cut = cut_reason.clone();
            return Ok(report);
        }

        // 绝对线（D90）：生成中只按**字符增量**累加，不重新估算整份上下文。
        // 越线就地停手——下一轮请求只会更大，发出去就是撞上游的硬上限。
        spent = spent.saturating_add(u64::from(estimate_tokens(&completion.text)));
        if config.truncate_at.is_some_and(|limit| spent >= limit) {
            report.finished = false;
            report.truncated = true;
            return Ok(report);
        }

        if completion.tool_calls.is_empty() && inline_calls.is_empty() {
            report.finished = true;
            return Ok(report);
        }

        let outgoing: Vec<OutgoingToolCall> = if completion.tool_calls.is_empty() {
            inline_calls
                .iter()
                .enumerate()
                .map(|(index, call)| {
                    // 正文里的调用没有 id：给一个**这一轮唯一**的，工具结果才能配对。
                    OutgoingToolCall::new(
                        format!("call_{}_{index}", report.rounds),
                        call.name.clone(),
                        call.arguments.clone(),
                    )
                })
                .collect()
        } else {
            completion
                .tool_calls
                .iter()
                .enumerate()
                .map(|(index, call)| {
                    // 空串也算"没给"：不少中转把 `id` 写成 `""`，
                    // `unwrap_or_else` 只兜 `None`，兜不住它——于是这条调用带着空 id 进历史，
                    OutgoingToolCall::new(
                        call.id
                            .clone()
                            .filter(|id| !id.trim().is_empty())
                            .unwrap_or_else(|| format!("call_{}_{index}", report.rounds)),
                        call.name.clone(),
                        call.arguments.clone(),
                    )
                })
                .collect()
        };

        let mut assistant = Message::tool_call_message(
            MessageId::new(session, seq),
            outgoing.clone(),
            estimate_tokens(&completion.text),
            base.messages.first().map(|m| m.created_at).unwrap_or(0),
            LogicalClock::new(seq),
        );
        // 落库的是**切掉标签之后**的正文：历史里再出现 `<tool_call>` 会让下一轮模型
        // 以为上一轮没执行，重复调用。
        assistant.set_text(visible.clone());
        if !completion.reasoning.trim().is_empty() {
            assistant = assistant.with_reasoning(&completion.reasoning);
        }
        let assistant_index = messages.len();
        messages.push(assistant);
        seq += 1;

        // 这一轮的调用登记到这一轮名下（D253）：落库按轮分组，不靠"最后一条消息"猜。
        if let Some(round) = report.rounds_detail.last_mut() {
            round.calls = outgoing.clone();
        }

        // 一次消息里的 N 个调用算一个**批次**（D254）：按车道并行跑，
        // 结果按调用顺序回填。已经跑完的那些是真发生过的事，取消时也照常记录。
        let batch = execute_batch(registry, ctx, &outgoing, cancel);
        for (call, record) in outgoing.iter().zip(batch.records) {
            let Some(record) = record else {
                // 这一条没执行（批次里前面有人把整轮停下了）。仍然落一块 `killed`：
                // 它确实发出去过，界面上要能看见"这次调用没跑"。
                messages[assistant_index].set_tool_output(
                    &call.id,
                    "这一条没有执行（整轮被停）",
                    ToolStatus::Killed,
                );
                continue;
            };
            let text = record.outcome.clone();
            let status = if record.ok {
                ToolStatus::Ok
            } else {
                ToolStatus::Error
            };
            report.tool_calls.push(record);
            spent = spent.saturating_add(u64::from(estimate_tokens(&text)));
            // 结果长在调用块上（D258）：一条消息 = 一轮，不再另起一条 `role=tool` 的消息。
            messages[assistant_index].set_tool_output(&call.id, text, status);
        }
        if batch.cancelled {
            return Ok(stopped(report));
        }

        if config.max_rounds > 0 && report.rounds >= config.max_rounds {
            if !wrap_up {
                // 先别交卷：再来一轮不带工具的，让它把手上这件事说完（D244）。
                // 这一轮仍然算进 `rounds`——轮数是"发了几次请求"，不是"调了几次工具"。
                wrap_up = true;
                continue;
            }
            // 收尾那一轮它还是只给工具调用（说明没有工具它就不说话）：只能如实交出去。
            report.finished = false;
            return Ok(report);
        }
    }
}

/// 把这一轮清单里的图并进请求（D248 / D269）。
///
/// 挂到**最后一条用户消息**上：图属于"这一轮用户给的东西"，另起一条 `role: user`
/// 会改变对话的形状（模型会以为用户又说了句话）。`build_body` 也只认用户消息带图。
///
/// 字节按 id 缓存：`request` 每轮从 `base` 重建，所以每轮都要重挂一次，
/// 但同一张图在这一轮里只读一遍盘。
///
/// 读盘而不是查 `attachments` 表：工具自己写进 `/chat/images` 的图在表里没有行，
/// 查表会静默少一张——那正是"我明明让它看了图，它说没看到"的样子。
fn attach_context_images(
    request: &mut ChatRequest,
    ctx: &ToolContext<'_>,
    cache: &mut Vec<(String, crate::message::ImagePart)>,
) {
    let (Some(session), Some(dir)) = (ctx.call_session, ctx.chat_images) else {
        return;
    };
    let Some(target) = request
        .messages
        .iter()
        .rev()
        .find(|message| message.role == Role::User)
        .map(|message| message.id.clone())
    else {
        return;
    };
    // 清单是"这一轮要发哪几张"的**唯一**来源。缓存只省读盘，不决定发什么——
    // 一张图被挤掉（或用户移出）之后，缓存里那一条不能再跟着发出去（D272 撞出来的）。
    let listed = crate::images::list(session);
    cache.retain(|(id, _)| listed.iter().any(|image| &image.id == id));
    for image in &listed {
        if cache.iter().any(|(id, _)| id == &image.id) {
            continue;
        }
        let Ok(bytes) = std::fs::read(dir.join(&image.file)) else {
            continue;
        };
        cache.push((
            image.id.clone(),
            crate::message::ImagePart {
                mime: image.mime.clone(),
                data: crate::base64::encode(&bytes),
            },
        ));
    }
    let parts: Vec<crate::message::ImagePart> = listed
        .iter()
        .filter_map(|image| {
            cache
                .iter()
                .find(|(id, _)| id == &image.id)
                .map(|(_, part)| part.clone())
        })
        .collect();
    if parts.is_empty() {
        return;
    }
    match request.images.iter_mut().find(|(id, _)| id == &target) {
        Some((_, existing)) => existing.extend(parts),
        None => request.images.push((target, parts)),
    }
}

/// 一个批次跑完的样子（D254）。
struct BatchOutcome {
    /// 按**调用顺序**排好；`None` = 这一条没执行（批次被取消，或它前面那条把整轮停了）。
    records: Vec<Option<ToolRecord>>,
    /// 批次里有人看见"整轮取消"了。
    cancelled: bool,
}

/// 跑一个批次：按车道并行，结果按调用顺序回填。
///
/// 为什么不是"全并行"：判据是碰不碰共享状态，不是快不快。两个 `write_file` 一起跑，
/// 谁后写谁赢；两个 `read_file` 一起跑，只是快一点。车道见 [`Lane`]。
///
/// 为什么结果要按顺序排：wire 上顺序无所谓（都带 id），但历史里顺序乱了以后排查会疯，
/// 而且界面上的行会每批换一次顺序。
fn execute_batch(
    registry: &ToolRegistry,
    ctx: &ToolContext<'_>,
    calls: &[OutgoingToolCall],
    cancel: &dyn Cancellation,
) -> BatchOutcome {
    use std::collections::BTreeMap;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::sync::Mutex;

    // 车道 → 这一批里属于它的调用下标（保持调用顺序）。
    let mut lanes: BTreeMap<Lane, Vec<usize>> = BTreeMap::new();
    for (index, call) in calls.iter().enumerate() {
        lanes
            .entry(registry.lane_of(&call.name))
            .or_default()
            .push(index);
    }

    let slots: Mutex<Vec<Option<ToolRecord>>> =
        Mutex::new((0..calls.len()).map(|_| None).collect());
    let cancelled = AtomicBool::new(false);

    let slots_ref = &slots;
    let cancelled_ref = &cancelled;

    std::thread::scope(|scope| {
        for (lane, indexes) in &lanes {
            let workers = lane.concurrency().min(indexes.len()).max(1);
            let cursor = std::sync::Arc::new(AtomicUsize::new(0));
            for _ in 0..workers {
                let cursor = std::sync::Arc::clone(&cursor);
                scope.spawn(move || loop {
                    let slot = cursor.fetch_add(1, Ordering::SeqCst);
                    let Some(&index) = indexes.get(slot) else {
                        break;
                    };
                    // 检查点：每条调用之前。已经跑完的那几条留在 slots 里，照常回填。
                    if cancel.is_cancelled() {
                        cancelled_ref.store(true, Ordering::SeqCst);
                        break;
                    }
                    let call = &calls[index];
                    // 手动关闭（0.15ak）：先登记，界面才看得见"哪一条在跑"，也才有东西可关。
                    // 凭据活到结果回来为止——`Drop` 就是"它不在跑了"这件事本身。
                    let handle = ctx.call_session.map(|session| {
                        crate::kills::begin(session, &call.id, &call.name, &call.arguments)
                    });
                    let call_ctx = ToolContext {
                        call: handle.as_ref(),
                        ..*ctx
                    };
                    // 工具 panic 只该让这一条失败：整批跟着炸掉，用户会以为三条都没跑。
                    let outcome =
                        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            registry.invoke(&call.name, &call.arguments, &call_ctx)
                        })) {
                            Ok(outcome) => outcome,
                            Err(_) => {
                                ToolOutcome::failed("工具内部错误（panic）：这一条没跑完，其余照常")
                            }
                        };
                    drop(handle);
                    let text = outcome.text();
                    // 跑完的这一条留在"这一轮跑过什么"里（D251）：结果要等整轮收尾才落库，
                    // 中间这一段界面上不能一片空白——用户会以为工具没动。
                    if let Some(session) = ctx.call_session {
                        crate::kills::push_done(
                            session,
                            &call.id,
                            &call.name,
                            &call.arguments,
                            &text,
                            outcome.is_ok(),
                        );
                    }
                    let record = ToolRecord {
                        name: call.name.clone(),
                        call_id: call.id.clone(),
                        arguments: call.arguments.clone(),
                        ok: outcome.is_ok(),
                        outcome: text,
                    };
                    if let Ok(mut slots) = slots_ref.lock() {
                        slots[index] = Some(record);
                    }
                });
            }
        }
    });

    let records = slots
        .into_inner()
        .unwrap_or_else(|error| error.into_inner());
    BatchOutcome {
        records,
        cancelled: cancelled.load(Ordering::SeqCst),
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
        return Err(AgentError::Model {
            message:
                "history 是空的：至少要有一条用户消息（空 messages 上游只会回一个看不出原因的 400）"
                    .to_string(),
            status: None,
        });
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
    use crate::tools::Tool;
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

    /// 读到一半就报错的响应体（D327）：模拟"连接在生成途中断了"。
    ///
    /// `Scripted` 只会给一整段完整的 body，断流这种事它造不出来——而这一条的判据
    /// 恰恰是"已经收到的字还在不在"。
    struct CutAfter {
        bytes: Vec<u8>,
        sent: usize,
    }

    impl std::io::Read for CutAfter {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.sent >= self.bytes.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::ConnectionReset,
                    "连接被掐",
                ));
            }
            let left = &self.bytes[self.sent..];
            let take = left.len().min(buf.len());
            buf[..take].copy_from_slice(&left[..take]);
            self.sent += take;
            Ok(take)
        }
    }

    struct Cutting {
        body: Mutex<Option<Vec<u8>>>,
    }

    impl Transport for Arc<Cutting> {
        fn execute(&self, _request: HttpRequest) -> Result<HttpResponse, TransportError> {
            let body = self.body.lock().unwrap().take().unwrap_or_default();
            Ok(HttpResponse {
                status: 200,
                headers: vec![("content-type".into(), "text/event-stream".into())],
                body: Box::new(std::io::BufReader::new(CutAfter {
                    bytes: body,
                    sent: 0,
                })),
            })
        }
    }

    /// 连接在生成途中断了：**已经说出来的那半段必须留着**，并且标成"断了"（D327）。
    ///
    /// 以前这一条是 `collect_all` 报错 → `run_turn` 直接 `Err` → 屏幕上那半段话
    /// 连同库里的一起没了，用户只看到一张"重试/放弃"的卡，而那条回答他其实读了一半。
    #[test]
    fn a_stream_that_dies_midway_keeps_what_it_said() {
        let sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"前半段还在\"}}]}\n\n";
        let transport = Arc::new(Cutting {
            body: Mutex::new(Some(sse.as_bytes().to_vec())),
        });
        let client = ChatClient::new(transport);
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let registry = ToolRegistry::default();
        let config = TurnConfig {
            stream: true,
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
        .expect("收到过字就不该当失败");

        assert_eq!(report.text, "前半段还在", "{report:?}");
        assert!(!report.finished, "断掉的一轮不算说完了：{report:?}");
        assert!(report.cut.is_some(), "要能说出是断在哪儿：{report:?}");
    }

    /// 第 `at` 次被问"取消了吗"时开始说"是"。用 Cell 就够：run_turn 是单线程的。
    struct CancelAt {
        asked: std::sync::atomic::AtomicUsize,
        at: usize,
    }

    impl CancelAt {
        fn new(at: usize) -> Self {
            Self {
                asked: std::sync::atomic::AtomicUsize::new(0),
                at,
            }
        }
    }

    impl Cancellation for CancelAt {
        fn is_cancelled(&self) -> bool {
            let asked = self.asked.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
            asked >= self.at
        }
    }

    /// 一个可以观察"同时有几个在跑"的假工具（D254）。
    struct ProbeTool {
        name: &'static str,
        lane: Lane,
        running: Arc<std::sync::atomic::AtomicUsize>,
        peak: Arc<std::sync::atomic::AtomicUsize>,
        /// 进到工具里之后等几个同伴到齐再返回（0 = 不等）。
        wait_for: usize,
    }

    impl Tool for ProbeTool {
        fn name(&self) -> &str {
            self.name
        }
        fn description(&self) -> &str {
            "probe"
        }
        fn schema(&self) -> serde_json::Value {
            serde_json::json!({"type": "object", "properties": {}})
        }
        fn capability(&self) -> crate::policy::Capability {
            crate::policy::Capability::WorkspaceRead
        }
        fn lane(&self) -> Lane {
            self.lane
        }
        fn invoke(&self, args: &serde_json::Value, _ctx: &ToolContext<'_>) -> ToolOutcome {
            use std::sync::atomic::Ordering;
            let now = self.running.fetch_add(1, Ordering::SeqCst) + 1;
            self.peak.fetch_max(now, Ordering::SeqCst);
            // 等同伴到齐（有上限：串行执行时这里会等到超时，用例随即失败）。
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
            while self.running.load(Ordering::SeqCst) < self.wait_for
                && std::time::Instant::now() < deadline
            {
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            if let Some(ms) = args.get("delay").and_then(|value| value.as_u64()) {
                std::thread::sleep(std::time::Duration::from_millis(ms));
            }
            self.running.fetch_sub(1, Ordering::SeqCst);
            ToolOutcome::ok(format!("{} 完成", self.name))
        }
    }

    fn probe_registry(
        lane: Lane,
        wait_for: usize,
    ) -> (
        ToolRegistry,
        Arc<std::sync::atomic::AtomicUsize>,
        Arc<std::sync::atomic::AtomicUsize>,
    ) {
        let running = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let peak = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let mut registry = ToolRegistry::new();
        registry.register(Box::new(ProbeTool {
            name: "probe",
            lane,
            running: Arc::clone(&running),
            peak: Arc::clone(&peak),
            wait_for,
        }));
        (registry, running, peak)
    }

    fn probe_calls(count: usize) -> Vec<OutgoingToolCall> {
        (0..count)
            .map(|index| OutgoingToolCall::new(format!("c{index}"), "probe", "{}"))
            .collect()
    }

    #[test]
    fn read_lane_runs_a_batch_at_once() {
        let (registry, _running, peak) = probe_registry(Lane::Read, 3);
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let calls = probe_calls(3);

        let batch = execute_batch(&registry, &ctx, &calls, &NeverCancel);

        assert!(!batch.cancelled);
        assert_eq!(
            peak.load(std::sync::atomic::Ordering::SeqCst),
            3,
            "三条只读调用应该同时在里面：串行的话第一条会等到超时"
        );
        assert!(
            batch.records.iter().all(|slot| slot.is_some()),
            "三条都要有结果"
        );
    }

    #[test]
    fn workspace_lane_keeps_calls_serial() {
        let (registry, _running, peak) = probe_registry(Lane::Workspace, 2);
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let calls = probe_calls(3);

        let batch = execute_batch(&registry, &ctx, &calls, &NeverCancel);

        assert_eq!(
            peak.load(std::sync::atomic::Ordering::SeqCst),
            1,
            "改东西的车道只能一个工人：两个写撞在一起，谁后写谁赢"
        );
        assert!(batch.records.iter().all(|slot| slot.is_some()));
    }

    #[test]
    fn batch_records_come_back_in_call_order() {
        // 第 0 条最慢、第 2 条最快：完成顺序是 2、1、0，回填必须是 0、1、2。
        let (registry, _running, _peak) = probe_registry(Lane::Read, 0);
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let calls: Vec<OutgoingToolCall> = [80u64, 40, 0]
            .iter()
            .enumerate()
            .map(|(index, ms)| {
                OutgoingToolCall::new(format!("c{index}"), "probe", format!("{{\"delay\":{ms}}}"))
            })
            .collect();

        let batch = execute_batch(&registry, &ctx, &calls, &NeverCancel);

        let ids: Vec<String> = batch
            .records
            .iter()
            .map(|slot| slot.as_ref().expect("每条都要有结果").call_id.clone())
            .collect();
        assert_eq!(
            ids,
            vec!["c0", "c1", "c2"],
            "结果按调用顺序排，不按完成顺序"
        );
    }

    #[test]
    fn a_cancelled_batch_dispatches_nothing() {
        struct Always;
        impl Cancellation for Always {
            fn is_cancelled(&self) -> bool {
                true
            }
        }

        let (registry, running, _peak) = probe_registry(Lane::Read, 0);
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let calls = probe_calls(3);

        let batch = execute_batch(&registry, &ctx, &calls, &Always);

        assert!(batch.cancelled, "整轮已经取消了");
        assert!(
            batch.records.iter().all(|slot| slot.is_none()),
            "一条都不该执行"
        );
        assert_eq!(running.load(std::sync::atomic::Ordering::SeqCst), 0);
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
    fn an_image_added_by_a_tool_reaches_the_next_request_in_the_same_turn() {
        // 第 1 轮调 `View-Image` 把图加进清单，第 2 轮请求里就该带上它——
        // 不必等下一次对话（用户 2026-11-06：「查看后下一次请求就带上」）。
        let dir = tempfile::tempdir().unwrap();
        let images_dir = dir.path().join("images");
        std::fs::create_dir_all(&images_dir).unwrap();
        std::fs::write(images_dir.join("shot.png"), b"\x89PNG\r\n\x1a\n").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let session = "s-images-same-turn";
        crate::images::clear(session);
        let ctx = ToolContext::new(&policy, Some(&workspace))
            .with_images(Some(&images_dir), 20)
            .with_call_session(session);
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "View-Image", r#"{"path":"shot.png"}"#)]),
            text_body("看到了"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();

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
        crate::images::clear(session);

        assert_eq!(report.rounds, 2, "{report:?}");
        let seen = transport.requests();
        assert!(
            !seen[0].contains("data:image/png;base64,"),
            "第 1 轮还没看图，不该带图：{}",
            seen[0]
        );
        assert!(
            seen[1].contains("data:image/png;base64,"),
            "第 2 轮就该带上它：{}",
            seen[1]
        );
    }

    /// 临时额度（D272）：多留的那张**只多活一次请求**——下一次思考结束就收掉。
    #[test]
    fn a_temporarily_kept_image_survives_exactly_one_more_request() {
        let dir = tempfile::tempdir().unwrap();
        let images_dir = dir.path().join("images");
        std::fs::create_dir_all(&images_dir).unwrap();
        for (name, body) in [("a.png", "AAA"), ("b.png", "BBB"), ("c.png", "CCC")] {
            std::fs::write(images_dir.join(name), body.as_bytes()).unwrap();
        }
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let session = "s-images-room-turn";
        crate::images::clear(session);
        // 上限 1、临时 1：正常只带 1 张，满了还能多留 1 张。
        let ctx = ToolContext::new(&policy, Some(&workspace))
            .with_images(Some(&images_dir), 1)
            .with_image_room(1)
            .with_call_session(session);
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "View-Image", r#"{"path":"a.png"}"#)]),
            calls_body(&[("c2", "View-Image", r#"{"path":"b.png"}"#)]),
            calls_body(&[("c3", "View-Image", r#"{"path":"c.png"}"#)]),
            text_body("看完了"),
        ]);
        let client = ChatClient::new(transport.clone());

        let report = run_turn(
            &client,
            &base_request(),
            &history(),
            &ToolRegistry::workspace_defaults(),
            &ctx,
            &TurnConfig::default(),
            &NeverCancel,
            None,
        )
        .unwrap();
        crate::images::clear(session);

        assert_eq!(report.rounds, 4, "{report:?}");
        let seen = transport.requests();
        let has = |index: usize, payload: &str| seen[index].contains(payload);
        assert!(!has(0, "QUFB"), "第 1 轮还没看图");
        assert!(has(1, "QUFB"), "第 2 轮带 a：{}", seen[1]);
        // b 是临时多留的那张：第 3 轮它和 a 都在。
        assert!(
            has(2, "QUFB") && has(2, "QkJC"),
            "第 3 轮两张都在：{}",
            seen[2]
        );
        // 第 3 轮结束（思考结束）时 a 被收掉：第 4 轮只剩 b 和 c。
        assert!(!has(3, "QUFB"), "临时额度到期后 a 该走了：{}", seen[3]);
        assert!(
            has(3, "QkJC") && has(3, "Q0ND"),
            "第 4 轮是 b 和 c：{}",
            seen[3]
        );
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

    /// 端点不认 `stream`（回了一段普通 JSON）：退回非流式重发一次，别把回答变成空的。
    #[test]
    fn a_stream_that_yields_nothing_falls_back_to_plain_completion() {
        let (_dir, workspace, policy) = setup();
        // 第一次：请求是流式的，对面却回了一个普通 JSON（没有 data: 帧）；
        // 第二次（退回非流式）：正常的整段回复。
        let transport = Scripted::new(vec![
            r#"{"choices":[{"message":{"content":"这不是流"}}]}"#.to_string(),
            text_body("退回之后拿到的回答"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let config = TurnConfig {
            stream: true,
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

        assert_eq!(
            report.text, "退回之后拿到的回答",
            "不能因为对流就拿到空回答"
        );
        assert!(report.finished, "退回之后这一轮是正常收尾");
        assert_eq!(transport.requests().len(), 2, "应当正好重发一次");
        assert!(
            !transport.requests()[1].contains("\"stream\":true"),
            "第二次要是非流式：{}",
            transport.requests()[1]
        );
    }

    /// 流式（0.15n）：增量**边收边交出去**，最终正文与"整段"那条路一致。
    #[test]
    fn streaming_turn_emits_deltas_and_lands_the_same_text() {
        let (_dir, workspace, policy) = setup();
        let frames = [
            serde_json::json!({"choices":[{"index":0,"delta":{"content":"你"}}]}),
            serde_json::json!({"choices":[{"index":0,"delta":{"content":"好"}}]}),
            serde_json::json!({"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}),
        ];
        let mut sse = String::new();
        for frame in &frames {
            sse.push_str("data: ");
            sse.push_str(&frame.to_string());
            sse.push_str("\n\n");
        }
        sse.push_str("data: [DONE]\n\n");

        let transport = Scripted::new(vec![sse]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let config = TurnConfig {
            stream: true,
            ..TurnConfig::default()
        };

        let seen: std::rc::Rc<std::cell::RefCell<Vec<String>>> =
            std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let sink = {
            let seen = seen.clone();
            std::rc::Rc::new(move |text: &str, _reasoning: &str| {
                if !text.is_empty() {
                    seen.borrow_mut().push(text.to_string());
                }
            }) as crate::progress::Sink
        };

        let report = crate::progress::scope(sink, || {
            run_turn(
                &client,
                &base_request(),
                &history(),
                &registry,
                &ctx,
                &config,
                &NeverCancel,
                None,
            )
            .unwrap()
        });

        assert_eq!(report.text, "你好", "收尾的正文要是拼好的那一段");
        assert!(report.finished, "正常收尾");
        assert_eq!(
            *seen.borrow(),
            vec!["你".to_string(), "好".to_string()],
            "增量要按到达顺序交出去（界面靠它逐字画）"
        );
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

    /// 到上限之后再给**一轮不带工具**的收尾（D244）：那一轮把话说完就算完成。
    #[test]
    fn the_last_round_has_no_tools_and_can_finish_the_turn() {
        let (_dir, workspace, policy) = setup();
        let transport = Scripted::new(vec![
            calls_body(&[("c1", "list_dir", "{}")]),
            text_body("说完了，就这些。"),
        ]);
        let client = ChatClient::new(transport.clone());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let config = TurnConfig {
            max_rounds: 1,
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

        assert_eq!(2, report.rounds, "到顶之后还要再来一轮（不带工具）");
        assert!(
            report.finished,
            "收尾那一轮说出话就算做完了：{}",
            report.text
        );
        assert_eq!("说完了，就这些。", report.text);
        let requests = transport.requests();
        assert_eq!(2, requests.len());
        // 空数组会被 `build_body` 整个省掉：所以判据是"请求里没有 tools 这个键"。
        assert!(
            !requests[1].contains("\"tools\""),
            "收尾那一轮不能再给工具：{}",
            requests[1]
        );
        assert!(
            requests[0].contains("\"tools\""),
            "第一轮该带工具：{}",
            requests[0]
        );
    }

    /// 默认**不限制**轮数（D245）：连着调五次工具再收尾，照样能做完。
    ///
    /// 真正的边界是绝对线（`truncate_at`）与取消，不是"调了几次"。
    #[test]
    fn rounds_are_unlimited_by_default() {
        let (_dir, workspace, policy) = setup();
        let mut bodies = Vec::new();
        for index in 0..5 {
            bodies.push(calls_body(&[(
                format!("c{index}").as_str(),
                "list_dir",
                "{}",
            )]));
        }
        bodies.push(text_body("都看完了。"));
        let transport = Scripted::new(bodies);
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

        assert_eq!(0, TurnConfig::default().max_rounds, "默认就是不限制");
        assert_eq!(6, report.rounds, "5 轮工具 + 1 轮收尾");
        assert!(report.finished, "说完就算做完：{}", report.text);
        assert_eq!(5, report.tool_calls.len());
    }

    /// 收尾那一轮它还是只给工具调用（说明没有工具就不说话）：如实报"没收尾"，不装做完。
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

        // 2 轮 + 1 轮收尾：收尾那轮它还在要工具，只能交卷。
        assert_eq!(3, report.rounds);
        assert!(!report.finished, "轮数用尽时不能假装做完了");
        assert_eq!(3, report.tool_calls.len());
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
