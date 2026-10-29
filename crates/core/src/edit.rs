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
use crate::message::{AtomicUnit, Message, Role, UnitError};
use crate::tokens::estimate_tokens;

/// 一块内容的上限。**这是防呆，不是防攻击**：真被塞进 200 KB 的思考块，
/// 下一次请求的预算会被它吃掉一大半，而用户在界面上根本翻不到底。
///
/// 放在 core 而不是 FFI：手动编辑（`nmoi_storage_apply_block_edit_json`）与模型改消息
/// （`edit_message`）都受这一条约束，两处各写一个数字迟早对不上。
pub const MAX_BLOCK_BYTES: usize = 200_000;

/// 可选中的块。长按 UI 上的哪个区域，就落到哪个选择器。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockSelector {
    /// 正文：assistant 回复、用户输入、工具结果原文。
    Output,
    /// 思考块（`reasoning`）。
    Thinking,
    /// 第 `index` 个工具调用的参数（tool_calls[index].arguments）。
    ToolCall { index: usize },
    /// 第 `index` 个工具调用的**结果**（`role = Tool` 那条消息的 `content`）。
    ///
    /// 与 [`BlockSelector::ToolCall`] 分开命名（0.15f / D188）：结果在**另一条消息**上，
    /// 把两者混成一个 `output`，模型说"改第 0 个工具的结果"就会落到调用消息的正文上。
    ToolResult { index: usize },
}

impl BlockSelector {
    /// 一条消息最多能有几个工具调用块（与 FFI 手动编辑那套 `tool:<index>` 同一上限）。
    pub const MAX_TOOL_INDEX: usize = 64;

    /// 从 `output` / `thinking` / `tool:<index>` 解析。
    ///
    /// **不猜**：猜错就是把内容写到另一列上，而"点思考卡、结果正文变了"这种故障
    /// 在界面上看着像灵异事件。手动编辑的 FFI 入口与模型的 `edit_message` 都走这一份。
    pub fn parse(text: &str) -> Result<Self, String> {
        match text {
            "output" => Ok(BlockSelector::Output),
            "thinking" => Ok(BlockSelector::Thinking),
            other => {
                // `tool:` = 调用参数，`tool_result:` = 结果正文（0.15f）。两者前缀不同，
                // 不能共用一个 strip：`tool_result:0` 会被 `tool:` 那条抢先认成"参数第 0 个"。
                let (which, tail) = if let Some(tail) = other.strip_prefix("tool_result:") {
                    ("工具结果", tail)
                } else if let Some(tail) = other.strip_prefix("tool:") {
                    ("工具参数", tail)
                } else {
                    return Err(format!(
                        "块只能是 output / thinking / tool:<index> / tool_result:<index>，收到 `{other}`"
                    ));
                };
                let index = tail.parse::<usize>().map_err(|_| {
                    format!(
                        "`{other}` 的 index 必须是 0–{} 的整数",
                        Self::MAX_TOOL_INDEX - 1
                    )
                })?;
                if index >= Self::MAX_TOOL_INDEX {
                    return Err(format!(
                        "`{other}` 的 index 必须是 0–{} 的整数",
                        Self::MAX_TOOL_INDEX - 1
                    ));
                }
                if which == "工具结果" {
                    Ok(BlockSelector::ToolResult { index })
                } else {
                    Ok(BlockSelector::ToolCall { index })
                }
            }
        }
    }

    pub fn as_str(self) -> String {
        match self {
            BlockSelector::Output => "output".into(),
            BlockSelector::Thinking => "thinking".into(),
            BlockSelector::ToolCall { index } => format!("tool_call:{index}"),
            BlockSelector::ToolResult { index } => format!("tool_result:{index}"),
        }
    }

    /// 工具调用序号（参数块与结果块共用同一个序号轴）。
    pub fn tool_index(self) -> Option<usize> {
        match self {
            BlockSelector::ToolCall { index } | BlockSelector::ToolResult { index } => Some(index),
            _ => None,
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
    /// 单消息的 `apply_edit` 到不了工具结果（它在另一条消息上）。
    #[error("工具结果块要用单元级编辑（edit_unit）：结果在另一条消息里，单块编辑到不了")]
    NeedsUnit,
    /// 未闭合的单元不许编辑（D184 的编辑纪律第一条）：改完也没法验证配对。
    #[error("单元 {0} 还没闭合（有工具调用没拿到结果），不能编辑")]
    UnitOpen(UnitId),
    /// 工具块只能成对删。
    #[error("工具块是成对的（第 {index} 个调用 + 它的结果）：删除要两边一起删，这次只删了{side}")]
    UnpairedDelete { index: usize, side: &'static str },
    /// 一条消息带多个调用时，删单个会把它跟结果的关系撕开。
    #[error("消息 {message} 带了 {count} 个工具调用：不能只删其中一个（要删就整条删，或者别删）")]
    ManyCalls { message: MessageId, count: usize },
    #[error("同一次调用里把「{0}」写了两遍：落库按顺序来，后一遍会盖掉前一遍")]
    Duplicate(String),
    #[error("正文不能删：删掉正文等于把这条消息变成空壳（要清空就改用 set，别用 delete）")]
    CannotDeleteOutput,
    #[error("第 {index} 项是工具结果，但消息 {message} 没有第 {index} 个工具调用")]
    NoSuchToolResult { message: MessageId, index: usize },
    #[error("没有给任何改动：要改就指明改哪一块")]
    NoOps,
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
            // 工具结果在**另一条消息**上：单消息的 `apply_edit` 到不了那儿。
            // 报出来而不是悄悄改成调用参数——"点结果卡、参数变了"是最难查的一种。
            BlockSelector::ToolResult { .. } => return Err(EditError::NeedsUnit),
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

// ---------------------------------------------------------------------------
// 0.15f / D188：一次调用里改多块（`edit_unit` 的纯逻辑）
// ---------------------------------------------------------------------------

/// 块的中文名。给模型看的那句话、给用户看的报告都用它，一处一个说法就够了。
pub fn block_label(block: BlockSelector) -> String {
    match block {
        BlockSelector::Output => "正文".into(),
        BlockSelector::Thinking => "思考块".into(),
        BlockSelector::ToolCall { index } => format!("工具参数 #{index}"),
        BlockSelector::ToolResult { index } => format!("工具结果 #{index}"),
    }
}

/// 一次调用里的一个动作。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlockOp {
    /// 写入 / 替换一块内容。思考块给空串等于删掉它（沿用 0.14x 的语义）。
    Set {
        block: BlockSelector,
        content: String,
    },
    /// 删掉一块（或一对工具块）。只有思考块与工具对允许删。
    Delete { block: BlockSelector },
}

impl BlockOp {
    pub fn block(&self) -> BlockSelector {
        match self {
            BlockOp::Set { block, .. } | BlockOp::Delete { block } => *block,
        }
    }

    pub fn is_delete(&self) -> bool {
        matches!(self, BlockOp::Delete { .. })
    }
}

/// 计划里的一条：**已经落到具体消息上**，storage 照着写就行。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedOp {
    pub message: MessageId,
    pub block: BlockSelector,
    /// 中文名（正文 / 思考块 / 工具参数 #N / 工具结果 #N）。
    pub label: String,
    pub before: String,
    /// `None` = 这一块被删掉。
    pub after: Option<String>,
    pub before_hash: String,
    pub after_hash: String,
    /// 正文改写要落红（写 `tooledit_message`，原文留蓝）。
    pub red: bool,
}

/// 一次单元级编辑的执行计划。
///
/// core 只算不写：`(scope, key) → JSON` 的 undo 日志、版本表那两列、删行时的级联，
/// 都在 storage 那边一个事务里做。这一份是"要写什么"的唯一说法。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnitPlan {
    pub unit: UnitId,
    pub ops: Vec<PlannedOp>,
    /// 内容和现状一样、因此没有写进去的块（中文名）。**不是错误**：模型原样重发很常见，
    /// 但工具必须把它报出来，否则"改了 3 处"和"实际写了 2 处"对不上。
    pub unchanged: Vec<String>,
    /// 要整条删掉的消息（成对删工具块）。删的是行，不是列。
    pub remove_messages: Vec<MessageId>,
}

/// 把一个块落到具体消息上。
///
/// 工具结果**按 `call_id` 配对**，不按"下一条消息"：并行调用时结果的先后不保证，
/// 按下标去猜就会把第 0 个调用的结果认成第 1 个的。
fn block_message(
    unit: &AtomicUnit,
    anchor: &MessageId,
    block: BlockSelector,
) -> Result<MessageId, EditError> {
    let message = unit
        .messages
        .iter()
        .find(|message| &message.id == anchor)
        .ok_or_else(|| EditError::MessageNotFound(anchor.clone()))?;
    let BlockSelector::ToolResult { index } = block else {
        return Ok(anchor.clone());
    };
    // index 说的是"第几个调用"（与 `tool:<index>` 同一根轴），结果只是它的对家。
    let call = message
        .tool_calls
        .get(index)
        .ok_or_else(|| EditError::NoSuchToolResult {
            message: anchor.clone(),
            index,
        })?;
    unit.messages
        .iter()
        .find(|candidate| candidate.tool_call_id.as_deref() == Some(call.id.as_str()))
        .map(|candidate| candidate.id.clone())
        .ok_or_else(|| EditError::NoSuchToolResult {
            message: anchor.clone(),
            index,
        })
}

/// 校验一组块级动作，并算出一份可执行的计划。**不改 `unit`。**
///
/// 校验顺序是有意的：先"这一组动作本身站不站得住"（单元闭合、目标存在、锁定、重复、成对），
/// 再算内容。任何一条不过就整组不动——半批落库比整批拒绝难查得多。
pub fn plan_ops(
    unit: &AtomicUnit,
    anchor: &MessageId,
    ops: &[BlockOp],
) -> Result<UnitPlan, EditError> {
    if !unit.messages.iter().any(|message| &message.id == anchor) {
        return Err(EditError::MessageNotFound(anchor.clone()));
    }
    if !unit.is_closed() {
        return Err(EditError::UnitOpen(unit.id.clone()));
    }
    if ops.is_empty() {
        return Err(EditError::NoOps);
    }

    // 1. 逐个落到具体消息上；重复块与锁定在这里拦。
    let mut resolved: Vec<(MessageId, BlockSelector, &BlockOp)> = Vec::with_capacity(ops.len());
    for op in ops {
        let block = op.block();
        let target = block_message(unit, anchor, block)?;
        if resolved
            .iter()
            .any(|(message, existing, _)| message == &target && *existing == block)
        {
            return Err(EditError::Duplicate(block_label(block)));
        }
        let locked = unit
            .messages
            .iter()
            .find(|message| message.id == target)
            .map(|message| message.locked)
            .unwrap_or(false);
        if locked {
            return Err(EditError::Locked(target));
        }
        // 正文没有"删掉"这一说：删了这条消息就是空壳（`Message::validate` 也拒）。
        if op.is_delete()
            && matches!(block, BlockSelector::Output)
            && unit
                .messages
                .iter()
                .find(|message| message.id == target)
                .map(|message| message.role != Role::Tool)
                .unwrap_or(false)
        {
            return Err(EditError::CannotDeleteOutput);
        }
        resolved.push((target, block, op));
    }

    // 2. 工具块只有"成对删"这一种删除（D184）：单独删一边 = 孤儿调用 / 孤儿结果，
    //    下一次请求会被上游直接 400。改不要求成对：改参数不影响配对。
    let anchor_message = unit
        .messages
        .iter()
        .find(|message| &message.id == anchor)
        .expect("上面已经确认锚点消息存在");
    for (_, block, op) in &resolved {
        if !op.is_delete() {
            continue;
        }
        let Some(index) = block.tool_index() else {
            continue;
        };
        // 同一序号上的另一半也必须删。`ToolCall{0}` 与 `ToolResult{0}` 是两个不同的块，
        // 所以这一步不能用重复检查代替（那一步拦的是同一块写两遍）。
        let partner_deleted = ops.iter().any(|other| {
            other.is_delete()
                && other.block().tool_index() == Some(index)
                && other.block() != *block
        });
        if !partner_deleted {
            let side = match block {
                BlockSelector::ToolCall { .. } => "调用",
                _ => "结果",
            };
            return Err(EditError::UnpairedDelete { index, side });
        }
        // 删掉的是一条消息；它要是带着别的调用，那些调用会一起消失。
        if anchor_message.tool_calls.len() > 1 {
            return Err(EditError::ManyCalls {
                message: anchor_message.id.clone(),
                count: anchor_message.tool_calls.len(),
            });
        }
    }

    // 3. 算 before / after。
    let mut plan = UnitPlan {
        unit: unit.id.clone(),
        ops: Vec::with_capacity(resolved.len()),
        unchanged: Vec::new(),
        remove_messages: Vec::new(),
    };
    for (target, block, op) in resolved {
        let message = unit
            .messages
            .iter()
            .find(|message| message.id == target)
            .expect("block_message 已经确认过这条消息存在");
        let before = match block {
            BlockSelector::Thinking => message.reasoning.clone().unwrap_or_default(),
            BlockSelector::ToolCall { index } => message
                .tool_calls
                .get(index)
                .map(|call| call.arguments.clone())
                .unwrap_or_default(),
            BlockSelector::Output | BlockSelector::ToolResult { .. } => message.content.clone(),
        };
        let after = match op {
            BlockOp::Set { content, .. } => {
                // 思考块：空串等于删掉块（与 0.14x 一致）。
                if block == BlockSelector::Thinking && content.trim().is_empty() {
                    None
                } else {
                    Some(content.clone())
                }
            }
            BlockOp::Delete { .. } => None,
        };
        if before == after.clone().unwrap_or_default() {
            plan.unchanged.push(block_label(block));
            continue;
        }
        // 正文落红：工具结果不是正文（它有自己的卡），所以排除 `role = Tool`。
        let red = block == BlockSelector::Output && message.role != Role::Tool;
        plan.ops.push(PlannedOp {
            message: target,
            block,
            label: block_label(block),
            before_hash: digest(&before),
            after_hash: digest(after.as_deref().unwrap_or_default()),
            before,
            after,
            red,
        });
    }

    // 4. 成对删：调用消息与它的结果消息都要从库里删（storage 删行，连同版本行）。
    //    `ManyCalls` 已经在上面拦过"多调用消息"，所以这里只会有一次配对。
    if plan
        .ops
        .iter()
        .any(|op| op.after.is_none() && op.block.tool_index().is_some())
    {
        plan.remove_messages.push(anchor_message.id.clone());
        for call in &anchor_message.tool_calls {
            if let Some(result) = unit
                .messages
                .iter()
                .find(|message| message.tool_call_id.as_deref() == Some(call.id.as_str()))
            {
                plan.remove_messages.push(result.id.clone());
            }
        }
    }

    Ok(plan)
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

    /// `output` / `thinking` / `tool:<index>` 这一套词只有一处解析（手动编辑与模型改消息共用）。
    #[test]
    fn block_selector_parses_the_three_kinds_and_nothing_else() {
        assert_eq!(BlockSelector::parse("output"), Ok(BlockSelector::Output));
        assert_eq!(
            BlockSelector::parse("thinking"),
            Ok(BlockSelector::Thinking)
        );
        assert_eq!(
            BlockSelector::parse("tool:0"),
            Ok(BlockSelector::ToolCall { index: 0 })
        );
        assert_eq!(
            BlockSelector::parse("tool:63"),
            Ok(BlockSelector::ToolCall { index: 63 })
        );

        // 顺序反了、越界、负数、不带序号——一律点名，不猜。
        for bad in [
            "tool:64",
            "tool_result:64",
            "tool:",
            "tool:-1",
            "tool",
            "tool_result",
            "reasoning",
            "Output",
            "",
        ] {
            let error = BlockSelector::parse(bad).unwrap_err();
            assert!(
                error.contains("output") || error.contains("0–63"),
                "`{bad}` 的报错要指出合法值：{error}"
            );
        }
    }

    // ---- 0.15f / D188：一次调用改多块 ----

    /// 一条调用 + 它的结果（成对，已闭合）。
    fn pair_with_result() -> AtomicUnit {
        let session = SessionId::new();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("c1", "read_file", "{\"path\":\"a\"}")],
            5,
            0,
            LogicalClock::new(1),
        );
        let mut unit = AtomicUnit::tool_pair(head).unwrap();
        let unit_id = unit.id.clone();
        let result = Message::tool_result(
            MessageId::new(session, 2),
            "c1",
            "内容 a",
            3,
            0,
            LogicalClock::new(2),
        )
        .with_unit(unit_id);
        unit.add_result(result).unwrap();
        unit
    }

    #[test]
    fn plan_ops_maps_each_block_onto_the_message_that_holds_it() {
        let unit = pair_with_result();
        let anchor = unit.messages[0].id.clone();
        let plan = plan_ops(
            &unit,
            &anchor,
            &[
                BlockOp::Set {
                    block: BlockSelector::ToolCall { index: 0 },
                    content: "{\"path\":\"b\"}".into(),
                },
                BlockOp::Set {
                    block: BlockSelector::ToolResult { index: 0 },
                    content: "新的结果".into(),
                },
            ],
        )
        .unwrap();

        assert_eq!(plan.ops.len(), 2);
        // 结果块落在**结果消息**上：认错了就会"改结果、参数变了"。
        assert_eq!(plan.ops[0].message, unit.messages[0].id);
        assert_eq!(plan.ops[1].message, unit.messages[1].id);
        assert_eq!(plan.ops[1].before, "内容 a");
        assert!(plan.ops.iter().all(|op| !op.red), "工具块就地改，不落红");
    }

    #[test]
    fn plan_ops_marks_output_rewrites_for_red() {
        let unit = turn();
        let anchor = unit.messages[0].id.clone();
        let plan = plan_ops(
            &unit,
            &anchor,
            &[BlockOp::Set {
                block: BlockSelector::Output,
                content: "world".into(),
            }],
        )
        .unwrap();

        assert_eq!(plan.ops.len(), 1);
        assert!(plan.ops[0].red, "正文改写要落红（原文留蓝）");
        assert_eq!(plan.ops[0].after.as_deref(), Some("world"));
        assert_ne!(plan.ops[0].before_hash, plan.ops[0].after_hash);
    }

    #[test]
    fn plan_ops_rejects_a_half_deleted_tool_block() {
        let unit = pair_with_result();
        let anchor = unit.messages[0].id.clone();
        let error = plan_ops(
            &unit,
            &anchor,
            &[BlockOp::Delete {
                block: BlockSelector::ToolCall { index: 0 },
            }],
        )
        .unwrap_err();
        assert!(
            matches!(error, EditError::UnpairedDelete { index: 0, .. }),
            "{error}"
        );
    }

    #[test]
    fn plan_ops_deletes_both_messages_of_a_tool_pair() {
        let unit = pair_with_result();
        let anchor = unit.messages[0].id.clone();
        let plan = plan_ops(
            &unit,
            &anchor,
            &[
                BlockOp::Delete {
                    block: BlockSelector::ToolCall { index: 0 },
                },
                BlockOp::Delete {
                    block: BlockSelector::ToolResult { index: 0 },
                },
            ],
        )
        .unwrap();

        assert_eq!(plan.ops.len(), 2);
        assert!(plan.ops.iter().all(|op| op.after.is_none()));
        assert_eq!(plan.remove_messages.len(), 2);
        assert!(plan.remove_messages.contains(&unit.messages[0].id));
        assert!(plan.remove_messages.contains(&unit.messages[1].id));
    }

    #[test]
    fn plan_ops_refuses_duplicate_blocks_before_writing_anything() {
        let unit = turn();
        let anchor = unit.messages[0].id.clone();
        let error = plan_ops(
            &unit,
            &anchor,
            &[
                BlockOp::Set {
                    block: BlockSelector::Output,
                    content: "a".into(),
                },
                BlockOp::Set {
                    block: BlockSelector::Output,
                    content: "b".into(),
                },
            ],
        )
        .unwrap_err();
        assert!(matches!(error, EditError::Duplicate(_)), "{error}");
    }

    #[test]
    fn plan_ops_reports_unchanged_blocks_instead_of_silently_skipping() {
        let unit = turn();
        let anchor = unit.messages[0].id.clone();
        let plan = plan_ops(
            &unit,
            &anchor,
            &[BlockOp::Set {
                block: BlockSelector::Output,
                content: "hello".into(),
            }],
        )
        .unwrap();

        assert!(plan.ops.is_empty(), "一样的正文不该写");
        assert_eq!(plan.unchanged, vec!["正文".to_string()], "但要说出来");
    }

    #[test]
    fn plan_ops_refuses_an_open_unit() {
        let session = SessionId::new();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("c1", "read_file", "{}")],
            1,
            0,
            LogicalClock::new(1),
        );
        let unit = AtomicUnit::tool_pair(head).unwrap();
        let anchor = unit.messages[0].id.clone();
        let error = plan_ops(
            &unit,
            &anchor,
            &[BlockOp::Set {
                block: BlockSelector::ToolCall { index: 0 },
                content: "{\"x\":1}".into(),
            }],
        )
        .unwrap_err();
        assert!(matches!(error, EditError::UnitOpen(_)), "{error}");
    }

    #[test]
    fn plan_ops_refuses_to_delete_output() {
        let unit = turn();
        let anchor = unit.messages[0].id.clone();
        let error = plan_ops(
            &unit,
            &anchor,
            &[BlockOp::Delete {
                block: BlockSelector::Output,
            }],
        )
        .unwrap_err();
        assert!(matches!(error, EditError::CannotDeleteOutput), "{error}");
    }

    #[test]
    fn tool_result_selector_is_parsed_and_never_confused_with_a_call() {
        assert_eq!(
            BlockSelector::parse("tool_result:0"),
            Ok(BlockSelector::ToolResult { index: 0 })
        );
        assert_eq!(
            BlockSelector::parse("tool:0"),
            Ok(BlockSelector::ToolCall { index: 0 })
        );
        assert_eq!(BlockSelector::ToolResult { index: 3 }.tool_index(), Some(3));
        assert_eq!(BlockSelector::Output.tool_index(), None);
    }
}
