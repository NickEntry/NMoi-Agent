//! 断块修复（P4.1 / D66 / D67）。
//!
//! 问题长这样：流断在半路，库里留下一个"调用发出去了、结果没回来"的工具单元。
//! 下一次把历史发给模型时，上游直接 400（`assistant.tool_calls` 有 id、却没有对应的 tool 消息），
//! 于是这个会话再也续不下去。
//!
//! 解法不是删历史，是**在构造请求时把这种单元翻译成一段文字块**：
//!
//! ```text
//! 1545374566161错误工具调用块原因:没等到工具结果
//! read_file({"path":"a.txt"})
//! ```
//!
//! 三条约束（D66）：
//!
//! 1. **声明行 = ID + 块类 + 原因，中间不隔符号**。以符号开头的重复行更容易把模型带进
//!    复读；纯数字 ID 开头每块长相都不同，中文块名跟在后面，整行唯一。
//! 2. **ID 稳定，不是每次随机**。同一块每次请求都得是同一个 ID——否则每次请求体都变，
//!    断点缓存全废；而且模型看到的是"无穷多个新块"。
//! 3. **信息要留下**。声明下面接原始内容（截断）。只写"这里坏了"，模型就不知道
//!    刚才想干什么，等于让人重猜。
//!
//! 库里的单元一个字节都不动：这是"翻译成另一种表示"，不是"变成另一条消息"。

use serde_json::Value;

use crate::message::{AtomicUnit, Message, Role, UnitKind};
use crate::source::SourceTag;
use crate::tokens::estimate_tokens;

/// ID 固定 13 位十进制：和用户给的样例一致，而且纯数字开头不会形成符号前缀。
pub const ID_DIGITS: usize = 13;

/// 原始内容进这段话的上限（字符，不是字节）。再长就截断——它已经坏过一次了，
/// 没必要为了"完整"把几 KB 的残骸塞进每一轮请求。
pub const PAYLOAD_MAX_CHARS: usize = 1_200;

/// 块类。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BrokenKind {
    /// 思考被截断（断流时最常见）。
    Thinking,
    /// 工具调用没闭合，或者参数不是合法 JSON。
    ToolCall,
}

impl BrokenKind {
    pub fn label(self) -> &'static str {
        match self {
            BrokenKind::Thinking => "错误思考块",
            BrokenKind::ToolCall => "错误工具调用块",
        }
    }
}

/// 原因。只分这三种：
///
/// 再细分对模型没有帮助（它只需要知道"这段不能用"），对排查有帮助的东西在库里
/// （`reconcile` 的报告 + 消息本身）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BrokenReason {
    InterruptedMidCall,
    MalformedArguments,
    MissingToolResult,
}

impl BrokenReason {
    pub fn label(self) -> &'static str {
        match self {
            BrokenReason::InterruptedMidCall => "调用中途断开",
            BrokenReason::MalformedArguments => "格式错误",
            BrokenReason::MissingToolResult => "没等到工具结果",
        }
    }
}

/// 一个待修复的块。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BrokenBlock {
    /// 13 位十进制，从单元 id 派生（稳定）。
    pub id: String,
    pub kind: BrokenKind,
    pub reason: BrokenReason,
    /// 原始内容（调用名 + 参数 / 思考尾巴），已经截断。
    pub detail: Option<String>,
}

impl BrokenBlock {
    /// 声明行：`1545374566161错误工具调用块原因:没等到工具结果`。
    ///
    /// 三段之间**没有分隔符**（D66）；唯一的冒号属于"原因:"这个词本身。
    pub fn declaration(&self) -> String {
        format!(
            "{}{}原因:{}",
            self.id,
            self.kind.label(),
            self.reason.label()
        )
    }

    /// 实际进上下文的那段文字：声明 + 原始内容。
    pub fn render(&self) -> String {
        match self.detail.as_deref().map(str::trim) {
            Some(detail) if !detail.is_empty() => format!("{}\n{}", self.declaration(), detail),
            _ => self.declaration(),
        }
    }
}

/// 从一段标识算出稳定的 13 位数字 ID。
///
/// 用自带的 FNV-1a 而不是 `DefaultHasher`：后者不保证跨 Rust 版本稳定，
/// 一次工具链升级就换一批 ID——看起来无害，实际是偷偷把断点缓存打掉一次（D66）。
pub fn stable_id(identity: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in identity.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    const MODULUS: u64 = 10_000_000_000_000; // 10^13
    format!("{:013}", hash % MODULUS)
}

fn truncate(text: &str) -> String {
    let mut out: String = text.chars().take(PAYLOAD_MAX_CHARS).collect();
    if text.chars().count() > PAYLOAD_MAX_CHARS {
        out.push_str("…（截断）");
    }
    out
}

/// 这个单元是不是坏了、坏在哪儿。
pub fn diagnose(unit: &AtomicUnit) -> Option<BrokenBlock> {
    let id = stable_id(&unit.id.to_string());

    // 1. 工具单元没闭合：调用出去了，结果没回来。这是上游 400 的直接原因。
    if unit.kind == UnitKind::ToolPair && !unit.is_closed() {
        let calls: Vec<String> = unit
            .messages
            .iter()
            .flat_map(|message| message.tool_calls.iter())
            .map(|call| format!("{}({})", call.name, call.arguments))
            .collect();
        // 参数不是合法 JSON 的，说"格式错误"更准确：用户看到这两个词反应不一样。
        let malformed = calls.iter().any(|text| {
            text.split_once('(')
                .map(|(_, rest)| rest.trim_end_matches(')'))
                .filter(|args| !args.trim().is_empty())
                .map(|args| serde_json::from_str::<Value>(args).is_err())
                .unwrap_or(false)
        });
        return Some(BrokenBlock {
            id,
            kind: BrokenKind::ToolCall,
            reason: if malformed {
                BrokenReason::MalformedArguments
            } else {
                BrokenReason::MissingToolResult
            },
            detail: Some(truncate(&calls.join("\n"))),
        });
    }

    // 2. 思考被截断：有思考、没正文、标了 incomplete。
    for message in &unit.messages {
        if message.incomplete && message.content.trim().is_empty() {
            if let Some(reasoning) = message.reasoning.as_deref() {
                if !reasoning.trim().is_empty() {
                    return Some(BrokenBlock {
                        id,
                        kind: BrokenKind::Thinking,
                        reason: BrokenReason::InterruptedMidCall,
                        detail: Some(truncate(reasoning)),
                    });
                }
            }
        }
    }

    None
}

/// 把历史单元翻译成"能发出去"的一组消息。
///
/// 坏的单元变成一个 `SourceTag::BrokenBlock` 的助手文本消息（**不带 tool_calls**）；
/// 好的单元原样摊平。库里不动。
pub fn normalize(units: &[AtomicUnit]) -> Vec<Message> {
    let mut history = Vec::new();
    for unit in units {
        match diagnose(unit) {
            Some(block) => {
                let anchor = unit
                    .messages
                    .first()
                    .expect("单元至少有一条消息（AtomicUnit 构造时就保证了）");
                let text = block.render();
                let mut message = Message::text(
                    anchor.id.clone(),
                    Role::Assistant,
                    SourceTag::BrokenBlock,
                    text.clone(),
                    estimate_tokens(&text),
                    anchor.created_at,
                    anchor.logical_clock,
                );
                // 断块不是"模型说的话"，但它在对话里占的就是那个位置：
                // 后面必须能接话，否则下一轮还是会 400。
                message.unit_id = unit.id.clone();
                history.push(message);
            }
            None => history.extend(unit.messages.iter().cloned()),
        }
    }
    history
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::{MessageId, SessionId};
    use crate::message::OutgoingToolCall;

    fn session() -> SessionId {
        SessionId::new()
    }

    fn user_unit(session: SessionId, seq: u64, text: &str) -> AtomicUnit {
        let message = Message::text(
            MessageId::new(session, seq),
            Role::User,
            SourceTag::History,
            text,
            estimate_tokens(text),
            1_000,
            LogicalClock::new(seq),
        );
        AtomicUnit::turn(message).unwrap()
    }

    /// 一个"调用发出去了、结果没回来"的单元。
    fn open_tool_unit(session: SessionId, seq: u64, arguments: &str) -> AtomicUnit {
        let call = OutgoingToolCall::new("call_1", "read_file", arguments);
        let head = Message::tool_call_message(
            MessageId::new(session, seq),
            vec![call],
            estimate_tokens(arguments),
            1_000,
            LogicalClock::new(seq),
        );
        AtomicUnit::tool_pair(head).unwrap()
    }

    #[test]
    fn stable_id_is_13_digits_and_stays_put() {
        let first = stable_id("unit:abc");
        assert_eq!(first.len(), ID_DIGITS);
        assert!(first.chars().all(|ch| ch.is_ascii_digit()), "{first}");
        assert_eq!(first, stable_id("unit:abc"), "同一标识必须永远同一个 ID");
        assert_ne!(first, stable_id("unit:abd"), "不同单元不能撞成一个 ID");
    }

    #[test]
    fn declaration_has_no_separators_between_the_three_parts() {
        let block = BrokenBlock {
            id: "1545374566161".to_string(),
            kind: BrokenKind::ToolCall,
            reason: BrokenReason::MissingToolResult,
            detail: None,
        };
        let declaration = block.declaration();
        assert_eq!(
            declaration,
            "1545374566161错误工具调用块原因:没等到工具结果"
        );
        assert!(!declaration.contains('|'), "三段之间不隔符号（D66）");
        assert!(!declaration.contains(" - "));
        assert!(!declaration.contains(' '), "空格也算分隔符");
    }

    #[test]
    fn unclosed_tool_unit_is_diagnosed_as_missing_result() {
        let session = session();
        let unit = open_tool_unit(session, 2, r#"{"path":"a.txt"}"#);
        let block = diagnose(&unit).expect("没闭合的工具单元就是坏的");
        assert_eq!(block.kind, BrokenKind::ToolCall);
        assert_eq!(block.reason, BrokenReason::MissingToolResult);
        let detail = block.detail.unwrap();
        assert!(detail.contains("read_file"), "{detail}");
        assert!(
            detail.contains("a.txt"),
            "原始参数要留下，不然模型不知道刚才想干嘛"
        );
    }

    #[test]
    fn malformed_arguments_get_their_own_reason() {
        let session = session();
        let unit = open_tool_unit(session, 2, r#"{"path":"a.tx"#);
        let block = diagnose(&unit).unwrap();
        assert_eq!(block.reason, BrokenReason::MalformedArguments);
        assert_eq!(block.reason.label(), "格式错误");
    }

    #[test]
    fn truncated_thinking_is_diagnosed_as_thinking_block() {
        let session = session();
        let mut message = Message::text(
            MessageId::new(session, 1),
            Role::Assistant,
            SourceTag::ModelThinking,
            "",
            1,
            1_000,
            LogicalClock::new(1),
        );
        message.reasoning = Some("让我先看看这个文件……".to_string());
        message.incomplete = true;
        let unit = AtomicUnit::turn(message).unwrap();
        let block = diagnose(&unit).unwrap();
        assert_eq!(block.kind, BrokenKind::Thinking);
        assert_eq!(block.reason, BrokenReason::InterruptedMidCall);
        assert!(block.detail.unwrap().contains("让我先看看"));
    }

    #[test]
    fn healthy_units_are_left_alone() {
        let session = session();
        let healthy = user_unit(session, 1, "在吗");
        assert!(diagnose(&healthy).is_none());
        let history = normalize(std::slice::from_ref(&healthy));
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].content, "在吗");
        assert!(!history
            .iter()
            .any(|message| message.source == SourceTag::BrokenBlock));
    }

    /// 修复的核心：坏单元变成**一条不带 tool_calls 的助手文本**，
    /// 否则下一次请求还是会因为"有调用没结果"被上游拒掉。
    #[test]
    fn normalize_turns_a_broken_unit_into_plain_text() {
        let session = session();
        let healthy = user_unit(session, 1, "读一下 a.txt");
        let broken = open_tool_unit(session, 2, r#"{"path":"a.txt"}"#);
        let history = normalize(&[healthy, broken]);

        assert_eq!(history.len(), 2);
        let tail = &history[1];
        assert_eq!(tail.role, Role::Assistant);
        assert_eq!(tail.source, SourceTag::BrokenBlock);
        assert!(
            tail.tool_calls.is_empty(),
            "这是修复的全部意义：请求里不能再出现没有结果的 tool_calls"
        );
        let head: String = tail.content.chars().take(ID_DIGITS).collect();
        assert!(
            head.chars().all(|ch| ch.is_ascii_digit()),
            "声明以 13 位数字 ID 开头：{head}"
        );
        assert!(tail.content.contains("错误工具调用块"), "{}", tail.content);
        assert!(tail.content.contains("没等到工具结果"), "{}", tail.content);
    }

    #[test]
    fn payload_is_truncated_but_never_empty() {
        let session = session();
        let long = "x".repeat(5_000);
        let unit = open_tool_unit(session, 2, &format!(r#"{{"path":"{long}"}}"#));
        let block = diagnose(&unit).unwrap();
        let rendered = block.render();
        let detail = block.detail.as_deref().unwrap();
        assert!(detail.chars().count() <= PAYLOAD_MAX_CHARS + 10);
        assert!(detail.ends_with("…（截断）"));
        assert!(!rendered.trim().is_empty());
    }
}
