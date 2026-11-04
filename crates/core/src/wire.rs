//! 库里的消息 → 发出去的消息（D258）。
//!
//! 一条库消息 = 一轮 = 一串 part；上游要的是另一种形状：
//! 带 `tool_calls` 的 assistant，后面紧跟 N 条 `tool` 结果。所以这里**展开**，
//! 而且只在这里展开——四个端口各写一遍的话，四条规则迟早对不上。
//!
//! 两条必须钉死的事实：
//!
//! 1. 一条库消息可能变成 N 条 wire 消息（N = 工具批数 + 1）；
//! 2. `tool` 消息必须紧跟在带对应 `call_id` 的 assistant 之后。文本在工具之后 →
//!    只能另起一条 assistant。

use crate::ids::MessageId;
use crate::message::{Message, OutgoingToolCall, Part, Role, ToolStatus};
use crate::runtime::ChatError;
use crate::source::SourceTag;

/// 发出去的一条消息。与库里的 [`Message`] 不是一回事：这里没有 parts、没有版本、
/// 没有归档形态——只有上游认的那几样。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WireMessage {
    /// 来自哪条库消息（图片按它取、报错时点名）。
    pub id: MessageId,
    pub role: Role,
    pub content: String,
    pub reasoning: Option<String>,
    pub tool_calls: Vec<OutgoingToolCall>,
    /// `role = tool` 时指向哪次调用。
    pub tool_call_id: Option<String>,
    pub source: SourceTag,
}

impl WireMessage {
    fn assistant(id: MessageId, source: SourceTag, content: String) -> Self {
        Self {
            id,
            role: Role::Assistant,
            content,
            reasoning: None,
            tool_calls: Vec::new(),
            tool_call_id: None,
            source,
        }
    }
}

/// 把历史展开成上游要的形状。
pub fn expand(messages: &[Message]) -> Result<Vec<WireMessage>, ChatError> {
    let mut out = Vec::new();
    for message in messages {
        expand_one(message, &mut out)?;
    }
    Ok(out)
}

fn expand_one(message: &Message, out: &mut Vec<WireMessage>) -> Result<(), ChatError> {
    if message.archived_ref.is_some() {
        return Err(ChatError::Unsupported(format!(
            "消息 {} 的内容在归档里（形态 {}），发请求前需要先取回原文",
            message.id, message.form
        )));
    }
    if message.role == Role::Tool {
        // 折行之后库里不该再有这种行。真撞上了就报错，不猜它该并到哪一块。
        return Err(ChatError::Unsupported(format!(
            "消息 {} 还是旧的 `role=tool` 形状，库需要迁移到 v16",
            message.id
        )));
    }
    if message.role != Role::Assistant {
        let mut text = String::new();
        let mut reasoning = None;
        for part in message.parts() {
            match part {
                Part::Text { text: value, .. } => push_text(&mut text, value),
                Part::Reasoning { text: value, .. } => {
                    if reasoning.is_none() && !value.trim().is_empty() {
                        reasoning = Some(value.clone());
                    }
                }
                Part::Notice { text: value, .. } => push_text(&mut text, value),
                Part::Tool(_) => {
                    return Err(ChatError::Unsupported(format!(
                        "只有助手消息能带工具块，{} 是 {}",
                        message.id, message.role
                    )))
                }
                Part::Image { .. } => {}
            }
        }
        let mut wire = WireMessage::assistant(message.id.clone(), message.source, text);
        wire.role = message.role;
        wire.reasoning = reasoning;
        out.push(wire);
        return Ok(());
    }

    // assistant：按"正文 → 一批工具 → 正文 → 一批工具"切开。
    let mut text = String::new();
    let mut tools: Vec<OutgoingToolCall> = Vec::new();
    let mut reasoning: Option<String> = None;
    let mut emitted_first = false;

    for part in message.parts() {
        match part {
            Part::Reasoning { text: value, .. } => {
                if reasoning.is_none() && !value.trim().is_empty() {
                    reasoning = Some(value.clone());
                }
            }
            Part::Text { text: value, .. } => {
                // 工具批之后的正文只能是**下一条** assistant：上游要求 tool 消息
                // 紧跟在带 tool_calls 的那条后面。
                if !tools.is_empty() {
                    flush(
                        message,
                        &mut text,
                        &mut tools,
                        &mut reasoning,
                        &mut emitted_first,
                        out,
                    )?;
                }
                push_text(&mut text, value);
            }
            Part::Notice { text: value, .. } => push_text(&mut text, value),
            Part::Tool(tool) => {
                if !tool.status.is_finished() {
                    return Err(ChatError::Unsupported(format!(
                        "消息 {} 的调用 {} 还没结果，不能发出去",
                        message.id, tool.call_id
                    )));
                }
                // 连续几个调用是**一个批次**：攒在一起，最后发成一条带 N 个 tool_calls 的 assistant。
                tools.push(OutgoingToolCall::new(
                    tool.call_id.clone(),
                    tool.name.clone(),
                    tool.arguments.clone(),
                ));
            }
            Part::Image { .. } => {}
        }
    }
    if !text.trim().is_empty() || !tools.is_empty() || (!emitted_first && reasoning.is_some()) {
        flush(
            message,
            &mut text,
            &mut tools,
            &mut reasoning,
            &mut emitted_first,
            out,
        )?;
    }
    Ok(())
}

fn push_text(into: &mut String, value: &str) {
    if value.is_empty() {
        return;
    }
    if !into.is_empty() {
        into.push_str("\n\n");
    }
    into.push_str(value);
}

fn flush(
    message: &Message,
    text: &mut String,
    tools: &mut Vec<OutgoingToolCall>,
    reasoning: &mut Option<String>,
    emitted_first: &mut bool,
    out: &mut Vec<WireMessage>,
) -> Result<(), ChatError> {
    if tools.is_empty() && text.trim().is_empty() {
        return Ok(());
    }
    let mut wire = WireMessage::assistant(message.id.clone(), message.source, std::mem::take(text));
    if !*emitted_first {
        wire.reasoning = reasoning.take();
        *emitted_first = true;
    }
    wire.tool_calls = std::mem::take(tools);
    if wire.tool_calls.is_empty() && wire.content.trim().is_empty() {
        return Ok(());
    }
    let calls = wire.tool_calls.clone();
    out.push(wire);

    for call in calls {
        let output = message
            .tool_calls()
            .into_iter()
            .find(|tool| tool.call_id == call.id)
            .map(|tool| tool.output.clone())
            .unwrap_or_default();
        out.push(WireMessage {
            id: message.id.clone(),
            role: Role::Tool,
            content: output,
            reasoning: None,
            tool_calls: Vec::new(),
            tool_call_id: Some(call.id),
            source: SourceTag::ToolResult,
        });
    }
    Ok(())
}

/// 一条工具块的结局（给调用方判断要不要报"还没跑完"）。
pub fn is_finished(tool: &crate::message::ToolPart) -> bool {
    tool.status != ToolStatus::Pending
}
