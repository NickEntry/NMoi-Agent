//! 块级编辑（用户需求：长按思考块 / 工具调用块直接改内容）。
//!
//! 三种锁是正交的（docs/decisions.md D11）：
//!
//! | 标记 | 谁设 | 挡住什么 | 不挡什么 |
//! |---|---|---|---|
//! | `protected` | 系统（失败/错误/异常） | 一切压缩 | — |
//! | `pinned` | 用户/策略 | 重排 | 编辑、压缩 |
//! | `locked` | 用户 | **内容编辑、重排** | **压缩、丢弃、回灌** |
//!
//! Q7 结论（D11）：`locked` 连位置一起锁。要动位置先问 `pinned || locked`。
//!
//! 编辑不是"直接改字符串"：每次编辑都要重算 token、递增 revision、
//! 留下前后哈希，否则 undo 与同步冲突判断都没有依据。

use sha2::{Digest, Sha256};

use crate::ids::{MessageId, UnitId};
use crate::message::{AtomicUnit, Message, UnitError};
use crate::tokens::estimate_tokens;

/// 可选中的块。长按 UI 上的哪个区域，就落到哪个选择器。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockSelector {
    /// 正文：assistant 回复、用户输入、工具结果原文。
    Output,
    /// 思考块（`reasoning`）。
    Thinking,
    /// 第 `index` 个工具调用的参数（tool_calls[index].arguments）。
    ToolCall { index: usize },
}

impl BlockSelector {
    pub fn as_str(self) -> String {
        match self {
            BlockSelector::Output => "output".into(),
            BlockSelector::Thinking => "thinking".into(),
            BlockSelector::ToolCall { index } => format!("tool_call:{index}"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditRequest {
    pub unit_id: UnitId,
    /// 单元里有多条消息时（工具对）用它指定目标；`None` 表示首条。
    pub message_id: Option<MessageId>,
    pub block: BlockSelector,
    pub content: String,
}

impl EditRequest {
    pub fn new(unit_id: UnitId, block: BlockSelector, content: impl Into<String>) -> Self {
        Self {
            unit_id,
            message_id: None,
            block,
            content: content.into(),
        }
    }

    pub fn on_message(mut self, message_id: MessageId) -> Self {
        self.message_id = Some(message_id);
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditOutcome {
    pub message_id: MessageId,
    pub block: String,
    pub before_hash: String,
    pub after_hash: String,
    pub tokens_before: u32,
    pub tokens_after: u32,
    pub revision: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum EditError {
    #[error("请求的单元 {requested} 与实际单元 {actual} 不一致")]
    UnitMismatch { requested: UnitId, actual: UnitId },
    #[error("消息 {0} 不在该单元里")]
    MessageNotFound(MessageId),
    #[error("消息 {0} 被用户锁定，不能编辑内容（压缩不受影响）")]
    Locked(MessageId),
    #[error("消息 {0} 没有思考块")]
    NoThinking(MessageId),
    #[error("消息 {message} 没有第 {index} 个工具调用")]
    NoSuchToolCall { message: MessageId, index: usize },
    #[error("内容没有变化")]
    NoChange,
    #[error("编辑后单元结构非法: {0}")]
    Invalid(#[from] UnitError),
}

/// 应用一次块级编辑。
pub fn apply_edit(unit: &mut AtomicUnit, request: &EditRequest) -> Result<EditOutcome, EditError> {
    if unit.id != request.unit_id {
        return Err(EditError::UnitMismatch {
            requested: request.unit_id.clone(),
            actual: unit.id.clone(),
        });
    }

    let index = match &request.message_id {
        Some(message_id) => unit
            .messages
            .iter()
            .position(|message| &message.id == message_id)
            .ok_or_else(|| EditError::MessageNotFound(message_id.clone()))?,
        None => 0,
    };

    if unit.messages[index].locked {
        return Err(EditError::Locked(unit.messages[index].id.clone()));
    }

    let outcome = {
        let message = &mut unit.messages[index];
        let tokens_before = message.tokens;
        let (before, after) = match request.block {
            BlockSelector::Output => {
                let before = message.content.clone();
                message.content = request.content.clone();
                (before, message.content.clone())
            }
            BlockSelector::Thinking => {
                let before = message.reasoning.clone().unwrap_or_default();
                if request.content.trim().is_empty() {
                    message.reasoning = None;
                } else {
                    message.reasoning = Some(request.content.clone());
                }
                (before, message.reasoning.clone().unwrap_or_default())
            }
            BlockSelector::ToolCall { index: call_index } => {
                let call = message.tool_calls.get_mut(call_index).ok_or_else(|| {
                    EditError::NoSuchToolCall {
                        message: message.id.clone(),
                        index: call_index,
                    }
                })?;
                let before = call.arguments.clone();
                call.arguments = request.content.clone();
                (before, call.arguments.clone())
            }
        };

        if before == after {
            return Err(EditError::NoChange);
        }

        message.tokens = message_tokens(message);
        message.revision = message.revision.saturating_add(1);

        EditOutcome {
            message_id: message.id.clone(),
            block: request.block.as_str(),
            before_hash: digest(&before),
            after_hash: digest(&after),
            tokens_before,
            tokens_after: message.tokens,
            revision: message.revision,
        }
    };

    // 编辑可能把结构改坏（例如清空正文又删掉思考），校验一次再交给上层。
    unit.validate()?;
    Ok(outcome)
}

/// 用户锁定 / 解锁。锁定本身不算内容编辑，不加 revision。
///
/// 返回改动前的状态。
pub fn set_locked(
    unit: &mut AtomicUnit,
    message_id: &MessageId,
    locked: bool,
) -> Result<bool, EditError> {
    let message = unit
        .messages
        .iter_mut()
        .find(|message| &message.id == message_id)
        .ok_or_else(|| EditError::MessageNotFound(message_id.clone()))?;
    let previous = message.locked;
    message.locked = locked;
    Ok(previous)
}

/// 一条消息占用的 token：正文 + 思考 + 全部工具调用参数。
pub fn message_tokens(message: &Message) -> u32 {
    let mut total = estimate_tokens(&message.content);
    if let Some(reasoning) = &message.reasoning {
        total = total.saturating_add(estimate_tokens(reasoning));
    }
    for call in &message.tool_calls {
        total = total.saturating_add(estimate_tokens(&call.name));
        total = total.saturating_add(estimate_tokens(&call.arguments));
    }
    total
}

fn digest(text: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::SessionId;
    use crate::message::{OutgoingToolCall, Role};
    use crate::source::SourceTag;

    fn turn() -> AtomicUnit {
        let session = SessionId::new();
        let id = MessageId::new(session, 1);
        let message = Message::text(
            id,
            Role::Assistant,
            SourceTag::History,
            "hello",
            1,
            0,
            LogicalClock::new(1),
        )
        .with_reasoning("先想想");
        AtomicUnit::turn(message).unwrap()
    }

    #[test]
    fn edits_output_and_bumps_revision() {
        let mut unit = turn();
        let request = EditRequest::new(unit.id.clone(), BlockSelector::Output, "world");
        let outcome = apply_edit(&mut unit, &request).unwrap();
        assert_eq!(unit.messages[0].content, "world");
        assert_eq!(unit.messages[0].revision, 1);
        assert_eq!(outcome.revision, 1);
        assert_ne!(outcome.before_hash, outcome.after_hash);
    }

    #[test]
    fn edits_thinking_block() {
        let mut unit = turn();
        let request = EditRequest::new(unit.id.clone(), BlockSelector::Thinking, "换个思路");
        apply_edit(&mut unit, &request).unwrap();
        assert_eq!(unit.messages[0].reasoning.as_deref(), Some("换个思路"));
    }

    #[test]
    fn empty_thinking_removes_block() {
        let mut unit = turn();
        let request = EditRequest::new(unit.id.clone(), BlockSelector::Thinking, "   ");
        apply_edit(&mut unit, &request).unwrap();
        assert!(unit.messages[0].reasoning.is_none());
    }

    #[test]
    fn locked_message_rejects_edit() {
        let mut unit = turn();
        let id = unit.messages[0].id.clone();
        assert!(!set_locked(&mut unit, &id, true).unwrap());
        let request = EditRequest::new(unit.id.clone(), BlockSelector::Output, "x");
        let err = apply_edit(&mut unit, &request).unwrap_err();
        assert!(matches!(err, EditError::Locked(_)));

        // 解锁后可以编辑；锁定不需要"解锁权限"以外的条件。
        set_locked(&mut unit, &id, false).unwrap();
        apply_edit(&mut unit, &request).unwrap();
    }

    #[test]
    fn edits_tool_call_arguments() {
        let session = SessionId::new();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("c1", "read_file", "{\"path\":\"a\"}")],
            5,
            0,
            LogicalClock::new(1),
        );
        let mut unit = AtomicUnit::tool_pair(head).unwrap();
        let request = EditRequest::new(
            unit.id.clone(),
            BlockSelector::ToolCall { index: 0 },
            "{\"path\":\"b\"}",
        );
        apply_edit(&mut unit, &request).unwrap();
        assert_eq!(unit.messages[0].tool_calls[0].arguments, "{\"path\":\"b\"}");

        let missing = EditRequest::new(unit.id.clone(), BlockSelector::ToolCall { index: 3 }, "x");
        assert!(matches!(
            apply_edit(&mut unit, &missing),
            Err(EditError::NoSuchToolCall { .. })
        ));
    }

    #[test]
    fn no_change_is_rejected() {
        let mut unit = turn();
        let request = EditRequest::new(unit.id.clone(), BlockSelector::Output, "hello");
        assert_eq!(apply_edit(&mut unit, &request), Err(EditError::NoChange));
    }
}
