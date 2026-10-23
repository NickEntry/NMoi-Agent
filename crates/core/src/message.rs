//! 消息与原子单元（设计文档 §4.2、§5.2）。
//!
//! 原子单元是所有编辑操作的最小单位，也是触发器检查点的最小单位：
//!
//! ```text
//! 单元 A：assistant(tool_calls[n]) + 紧随的全部 tool_result[n]
//! 单元 B：一条完整的 user 或 assistant 文本消息（无 tool_call）
//! ```
//!
//! 并行工具调用必须等全部 result 到齐才算单元闭合；未闭合的单元禁止编辑。

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::str::FromStr;

use serde::{Deserialize, Serialize};

use crate::clock::LogicalClock;
use crate::ids::{ArchiveRef, MessageId, UnitId};
use crate::source::SourceTag;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    System,
    User,
    Assistant,
    Tool,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Role::System => "system",
            Role::User => "user",
            Role::Assistant => "assistant",
            Role::Tool => "tool",
        }
    }
}

impl fmt::Display for Role {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for Role {
    type Err = UnitError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "system" => Ok(Role::System),
            "user" => Ok(Role::User),
            "assistant" => Ok(Role::Assistant),
            "tool" => Ok(Role::Tool),
            other => Err(UnitError::UnknownRole(other.to_string())),
        }
    }
}

/// 内容形态 F1–F5（§2.2）。库里存成 `F1` / `F2:1000:1000` 这样的短串。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub enum ContentForm {
    /// F1 全文。
    Full,
    /// F2 截断 + 尾注：头 N token + 尾 M token + 归档标记。
    Truncated { head_tokens: u32, tail_tokens: u32 },
    /// F3 masking 占位符，约 10 token。
    Masked,
    /// F4 仅指针，约 40 token。
    Pointer,
    /// F5 索引摘要，约 20 token；不承担内容职责。
    IndexOnly,
}

impl ContentForm {
    /// 是否仍是"原文形态"。F1/F2 保留了原文片段，F3–F5 只留指针。
    pub fn retains_content(self) -> bool {
        matches!(self, ContentForm::Full | ContentForm::Truncated { .. })
    }

    /// 是否必须带归档引用。除全文外的形态都指向归档里的原文。
    pub fn requires_archive(self) -> bool {
        !matches!(self, ContentForm::Full)
    }

    pub fn code(self) -> &'static str {
        match self {
            ContentForm::Full => "F1",
            ContentForm::Truncated { .. } => "F2",
            ContentForm::Masked => "F3",
            ContentForm::Pointer => "F4",
            ContentForm::IndexOnly => "F5",
        }
    }
}

impl fmt::Display for ContentForm {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ContentForm::Full => f.write_str("F1"),
            ContentForm::Truncated {
                head_tokens,
                tail_tokens,
            } => write!(f, "F2:{head_tokens}:{tail_tokens}"),
            ContentForm::Masked => f.write_str("F3"),
            ContentForm::Pointer => f.write_str("F4"),
            ContentForm::IndexOnly => f.write_str("F5"),
        }
    }
}

impl FromStr for ContentForm {
    type Err = UnitError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let mut parts = value.split(':');
        let code = parts.next().unwrap_or_default();
        match code {
            "F1" => Ok(ContentForm::Full),
            "F2" => {
                let head = parts
                    .next()
                    .and_then(|v| v.parse().ok())
                    .ok_or_else(|| UnitError::MalformedForm(value.to_string()))?;
                let tail = parts
                    .next()
                    .and_then(|v| v.parse().ok())
                    .ok_or_else(|| UnitError::MalformedForm(value.to_string()))?;
                Ok(ContentForm::Truncated {
                    head_tokens: head,
                    tail_tokens: tail,
                })
            }
            "F3" => Ok(ContentForm::Masked),
            "F4" => Ok(ContentForm::Pointer),
            "F5" => Ok(ContentForm::IndexOnly),
            _ => Err(UnitError::MalformedForm(value.to_string())),
        }
    }
}

impl TryFrom<String> for ContentForm {
    type Error = UnitError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::from_str(&value)
    }
}

impl From<ContentForm> for String {
    fn from(value: ContentForm) -> Self {
        value.to_string()
    }
}

/// 助手发起的一次工具调用。`arguments` 保留原始 JSON 字符串，不在这里解析。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OutgoingToolCall {
    pub id: String,
    pub name: String,
    pub arguments: String,
}

impl OutgoingToolCall {
    pub fn new(
        id: impl Into<String>,
        name: impl Into<String>,
        arguments: impl Into<String>,
    ) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            arguments: arguments.into(),
        }
    }
}

/// 一条消息。归属某个原子单元，`seq` 在会话内递增。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Message {
    pub id: MessageId,
    pub unit_id: UnitId,
    pub role: Role,
    pub source: SourceTag,
    pub content: String,
    pub reasoning: Option<String>,
    pub form: ContentForm,
    pub tokens: u32,
    pub created_at: i64,
    pub logical_clock: LogicalClock,
    pub archived_ref: Option<ArchiveRef>,
    /// 内容必须保留原文（失败/错误/异常），禁止任何压缩（§2.3.2）。
    pub protected: bool,
    /// 位置锁定：不参与重排（§4.3 ctx_pin）。
    pub pinned: bool,
    /// 用户锁定：禁止内容编辑，也禁止重排（Q7），但**允许压缩**（新的正交标记）。
    pub locked: bool,
    /// 编辑版本号。每次内容 / 思考 / 工具块被改写加一，用于审计与同步冲突判断。
    pub revision: u32,
    /// 这一轮**没说完整**：断流、被掐断、用户点了停止（D21）。
    ///
    /// 为什么要有这个字段：断流的回复和正常回复在数据里长得一模一样，
    /// 事后谁也看不出它少了半句。`#[serde(default)]` 是为了让没有这个字段的旧数据还能读。
    #[serde(default)]
    pub incomplete: bool,
    pub tool_calls: Vec<OutgoingToolCall>,
    pub tool_call_id: Option<String>,
}

impl Message {
    /// 构造一条全文消息（F1）。
    pub fn text(
        id: MessageId,
        role: Role,
        source: SourceTag,
        content: impl Into<String>,
        tokens: u32,
        created_at: i64,
        logical_clock: LogicalClock,
    ) -> Self {
        let unit_id = id.unit();
        Self {
            id,
            unit_id,
            role,
            source,
            content: content.into(),
            reasoning: None,
            form: ContentForm::Full,
            tokens,
            created_at,
            logical_clock,
            archived_ref: None,
            protected: false,
            pinned: false,
            locked: false,
            revision: 0,
            incomplete: false,
            tool_calls: Vec::new(),
            tool_call_id: None,
        }
    }

    /// 构造带工具调用的 assistant 消息。
    pub fn tool_call_message(
        id: MessageId,
        calls: Vec<OutgoingToolCall>,
        tokens: u32,
        created_at: i64,
        logical_clock: LogicalClock,
    ) -> Self {
        let mut message = Self::text(
            id,
            Role::Assistant,
            SourceTag::ToolCall,
            String::new(),
            tokens,
            created_at,
            logical_clock,
        );
        message.tool_calls = calls;
        message
    }

    /// 构造工具结果。
    pub fn tool_result(
        id: MessageId,
        tool_call_id: impl Into<String>,
        content: impl Into<String>,
        tokens: u32,
        created_at: i64,
        logical_clock: LogicalClock,
    ) -> Self {
        let mut message = Self::text(
            id,
            Role::Tool,
            SourceTag::ToolResult,
            content,
            tokens,
            created_at,
            logical_clock,
        );
        message.tool_call_id = Some(tool_call_id.into());
        message
    }

    pub fn with_reasoning(mut self, reasoning: impl Into<String>) -> Self {
        self.reasoning = Some(reasoning.into());
        self
    }

    /// 归属到指定单元。工具对单元里，第 2..n 条消息的 `seq` 与单元 `seq` 不同，
    /// 但 `unit_id` 必须统一指向单元本身，所以显式覆盖。
    pub fn with_unit(mut self, unit_id: UnitId) -> Self {
        self.unit_id = unit_id;
        self
    }

    pub fn with_archive(mut self, form: ContentForm, reference: ArchiveRef) -> Self {
        self.form = form;
        self.archived_ref = Some(reference);
        self
    }

    pub fn protect(mut self) -> Self {
        self.protected = true;
        self
    }

    /// 用户锁定。语义见 docs/decisions.md D11：锁内容，不锁压缩。
    /// 标记这一轮不完整（断流 / 被停止）。
    pub fn mark_incomplete(mut self, incomplete: bool) -> Self {
        self.incomplete = incomplete;
        self
    }

    pub fn with_lock(mut self, locked: bool) -> Self {
        self.locked = locked;
        self
    }

    /// 单条消息的结构校验。
    pub fn validate(&self) -> Result<(), UnitError> {
        if self.role == Role::Tool {
            if self.tool_call_id.as_deref().unwrap_or_default().is_empty() {
                return Err(UnitError::MissingToolCallId(self.id.clone()));
            }
            if !self.tool_calls.is_empty() {
                return Err(UnitError::ToolResultHasCalls(self.id.clone()));
            }
        } else {
            if self.tool_call_id.is_some() {
                return Err(UnitError::ToolCallIdOnNonTool(self.id.clone()));
            }
            if !self.tool_calls.is_empty() && self.role != Role::Assistant {
                return Err(UnitError::CallsOnNonAssistant(self.id.clone()));
            }
        }

        let mut seen = BTreeSet::new();
        for call in &self.tool_calls {
            if call.id.is_empty() {
                return Err(UnitError::EmptyToolCallId(self.id.clone()));
            }
            if !seen.insert(call.id.clone()) {
                return Err(UnitError::DuplicateToolCallId {
                    message: self.id.clone(),
                    call: call.id.clone(),
                });
            }
        }

        if self.form.requires_archive() && self.archived_ref.is_none() {
            return Err(UnitError::FormWithoutArchive {
                message: self.id.clone(),
                form: self.form,
            });
        }

        // 工具输出可以合法为空（命令没输出）；其他角色发空消息说明构造路径有问题。
        if self.content.trim().is_empty()
            && self.reasoning.is_none()
            && self.tool_calls.is_empty()
            && self.role != Role::Tool
        {
            return Err(UnitError::EmptyMessage(self.id.clone()));
        }

        Ok(())
    }
}

/// 单元类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UnitKind {
    /// 单元 B：一条完整文本消息。
    Turn,
    /// 单元 A：assistant(tool_calls) + 全部 tool_result。
    ToolPair,
}

impl UnitKind {
    pub fn as_str(self) -> &'static str {
        match self {
            UnitKind::Turn => "turn",
            UnitKind::ToolPair => "tool_pair",
        }
    }

    pub fn parse(value: &str) -> Result<Self, UnitError> {
        match value {
            "turn" => Ok(UnitKind::Turn),
            "tool_pair" => Ok(UnitKind::ToolPair),
            other => Err(UnitError::UnknownUnitKind(other.to_string())),
        }
    }
}

impl fmt::Display for UnitKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// 原子单元。编辑接口只接受它的 ID。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AtomicUnit {
    pub id: UnitId,
    pub kind: UnitKind,
    pub messages: Vec<Message>,
    pub created_at: i64,
    pub logical_clock: LogicalClock,
}

impl AtomicUnit {
    /// 单元 B。要求消息本身无工具调用。
    pub fn turn(message: Message) -> Result<Self, UnitError> {
        if !message.tool_calls.is_empty() || message.role == Role::Tool {
            return Err(UnitError::NotATurnMessage(message.id.clone()));
        }
        message.validate()?;
        Ok(Self {
            id: message.unit_id.clone(),
            kind: UnitKind::Turn,
            created_at: message.created_at,
            logical_clock: message.logical_clock,
            messages: vec![message],
        })
    }

    /// 单元 A。初始为未闭合，收到全部 result 后自动闭合。
    pub fn tool_pair(call_message: Message) -> Result<Self, UnitError> {
        if call_message.role != Role::Assistant {
            return Err(UnitError::ToolPairNotAssistant(call_message.id.clone()));
        }
        if call_message.tool_calls.is_empty() {
            return Err(UnitError::ToolPairWithoutCalls(call_message.id.clone()));
        }
        call_message.validate()?;
        Ok(Self {
            id: call_message.unit_id.clone(),
            kind: UnitKind::ToolPair,
            created_at: call_message.created_at,
            logical_clock: call_message.logical_clock,
            messages: vec![call_message],
        })
    }

    /// 追加一条工具结果。重复、未知、错单元的结果一律拒绝。
    pub fn add_result(&mut self, result: Message) -> Result<(), UnitError> {
        if self.is_closed() {
            return Err(UnitError::UnitAlreadyClosed(self.id.clone()));
        }
        if self.kind != UnitKind::ToolPair {
            return Err(UnitError::NotAToolPair(self.id.clone()));
        }
        if result.role != Role::Tool {
            return Err(UnitError::ResultNotTool(result.id.clone()));
        }
        if result.unit_id != self.id {
            return Err(UnitError::WrongUnit {
                expected: self.id.clone(),
                actual: result.unit_id.clone(),
            });
        }
        let call_id = result
            .tool_call_id
            .clone()
            .ok_or_else(|| UnitError::MissingToolCallId(result.id.clone()))?;
        let calls = self.call_ids();
        if !calls.contains_key(call_id.as_str()) {
            return Err(UnitError::UnknownToolCall {
                unit: self.id.clone(),
                call: call_id,
            });
        }
        if self.result_ids().contains_key(call_id.as_str()) {
            return Err(UnitError::DuplicateToolResult {
                unit: self.id.clone(),
                call: call_id,
            });
        }
        result.validate()?;
        if result.logical_clock
            <= self
                .messages
                .last()
                .map(|m| m.logical_clock)
                .unwrap_or(self.logical_clock)
        {
            return Err(UnitError::UnitClockNotMonotonic {
                unit: self.id.clone(),
                message: result.id.clone(),
            });
        }
        self.messages.push(result);
        Ok(())
    }

    /// 未闭合的调用 ID（按声明顺序）。
    pub fn pending_calls(&self) -> Vec<&str> {
        let results = self.result_ids();
        self.call_ids()
            .into_keys()
            .filter(|id| !results.contains_key(*id))
            .collect()
    }

    /// 所有调用是否都已到齐。
    pub fn is_closed(&self) -> bool {
        match self.kind {
            UnitKind::Turn => true,
            UnitKind::ToolPair => self.pending_calls().is_empty() && !self.call_ids().is_empty(),
        }
    }

    pub fn token_total(&self) -> u32 {
        self.messages.iter().map(|m| m.tokens).sum()
    }

    pub fn first_message(&self) -> &Message {
        // 构造时保证非空；这里用 expect 而不是 panic 路径的 Option 返回，
        // 因为空单元是类型系统的非法状态，构造入口全部拒绝。
        self.messages.first().expect("atomic unit 至少有一条消息")
    }

    pub fn last_message(&self) -> &Message {
        self.messages.last().expect("atomic unit 至少有一条消息")
    }

    /// 整个单元的结构校验。可对未闭合单元调用（校验已有部分）。
    pub fn validate(&self) -> Result<(), UnitError> {
        if self.messages.is_empty() {
            return Err(UnitError::EmptyUnit(self.id.clone()));
        }
        let mut previous_clock = self.logical_clock;
        for (index, message) in self.messages.iter().enumerate() {
            message.validate()?;
            if message.unit_id != self.id {
                return Err(UnitError::WrongUnit {
                    expected: self.id.clone(),
                    actual: message.unit_id.clone(),
                });
            }
            if index > 0 && message.logical_clock <= previous_clock {
                return Err(UnitError::UnitClockNotMonotonic {
                    unit: self.id.clone(),
                    message: message.id.clone(),
                });
            }
            previous_clock = message.logical_clock;
        }

        match self.kind {
            UnitKind::Turn => {
                if self.messages.len() != 1 {
                    return Err(UnitError::TurnUnitHasExtraMessages(self.id.clone()));
                }
                let message = &self.messages[0];
                if message.role == Role::Tool || !message.tool_calls.is_empty() {
                    return Err(UnitError::NotATurnMessage(message.id.clone()));
                }
            }
            UnitKind::ToolPair => {
                let head = &self.messages[0];
                if head.role != Role::Assistant || head.tool_calls.is_empty() {
                    return Err(UnitError::ToolPairNotAssistant(head.id.clone()));
                }
                let calls = self.call_ids();
                let mut results: BTreeMap<&str, usize> = BTreeMap::new();
                for message in &self.messages[1..] {
                    if message.role != Role::Tool {
                        return Err(UnitError::ResultNotTool(message.id.clone()));
                    }
                    let call_id = message
                        .tool_call_id
                        .as_deref()
                        .ok_or_else(|| UnitError::MissingToolCallId(message.id.clone()))?;
                    if !calls.contains_key(call_id) {
                        return Err(UnitError::UnknownToolCall {
                            unit: self.id.clone(),
                            call: call_id.to_string(),
                        });
                    }
                    *results.entry(call_id).or_insert(0) += 1;
                }
                for (call_id, count) in results {
                    if count > 1 {
                        return Err(UnitError::DuplicateToolResult {
                            unit: self.id.clone(),
                            call: call_id.to_string(),
                        });
                    }
                }
            }
        }
        Ok(())
    }

    fn call_ids(&self) -> BTreeMap<&str, &OutgoingToolCall> {
        let mut map = BTreeMap::new();
        for message in &self.messages {
            for call in &message.tool_calls {
                map.insert(call.id.as_str(), call);
            }
        }
        map
    }

    fn result_ids(&self) -> BTreeMap<&str, &Message> {
        let mut map = BTreeMap::new();
        for message in &self.messages {
            if let Some(call_id) = message.tool_call_id.as_deref() {
                map.insert(call_id, message);
            }
        }
        map
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum UnitError {
    #[error("未知角色: {0}")]
    UnknownRole(String),
    #[error("未知单元类型: {0}")]
    UnknownUnitKind(String),
    #[error("非法内容形态: {0}")]
    MalformedForm(String),
    #[error("消息 {0} 内容为空")]
    EmptyMessage(MessageId),
    #[error("消息 {0} 是工具结果但缺少 tool_call_id")]
    MissingToolCallId(MessageId),
    #[error("消息 {0} 带 tool_call_id 但不是工具结果")]
    ToolCallIdOnNonTool(MessageId),
    #[error("消息 {0} 是工具结果却带 tool_calls")]
    ToolResultHasCalls(MessageId),
    #[error("非 assistant 消息 {0} 带 tool_calls")]
    CallsOnNonAssistant(MessageId),
    #[error("消息 {0} 的工具调用 id 为空")]
    EmptyToolCallId(MessageId),
    #[error("消息 {message} 里工具调用 id 重复: {call}")]
    DuplicateToolCallId { message: MessageId, call: String },
    #[error("消息 {message} 的形态 {form} 需要归档引用")]
    FormWithoutArchive {
        message: MessageId,
        form: ContentForm,
    },
    #[error("单元 {0} 为空")]
    EmptyUnit(UnitId),
    #[error("单元 {0} 要求单条消息")]
    TurnUnitHasExtraMessages(UnitId),
    #[error("消息 {0} 不构成独立回合")]
    NotATurnMessage(MessageId),
    #[error("单元 {0} 不是工具对")]
    NotAToolPair(UnitId),
    #[error("工具对单元 {0} 的首条消息必须是带 tool_calls 的 assistant")]
    ToolPairNotAssistant(MessageId),
    #[error("消息 {0} 没有工具调用，不能开工具对单元")]
    ToolPairWithoutCalls(MessageId),
    #[error("单元 {0} 已闭合")]
    UnitAlreadyClosed(UnitId),
    #[error("消息 {0} 不是工具结果")]
    ResultNotTool(MessageId),
    #[error("单元不匹配：期望 {expected}，实际 {actual}")]
    WrongUnit { expected: UnitId, actual: UnitId },
    #[error("单元 {unit} 里没有调用 {call}")]
    UnknownToolCall { unit: UnitId, call: String },
    #[error("单元 {unit} 的调用 {call} 已有结果")]
    DuplicateToolResult { unit: UnitId, call: String },
    #[error("单元 {unit} 的消息 {message} 逻辑时钟未递增")]
    UnitClockNotMonotonic { unit: UnitId, message: MessageId },
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids::SessionId;

    fn setup(seq: u64) -> (MessageId, LogicalClock) {
        let session = SessionId::new();
        (MessageId::new(session, seq), LogicalClock::new(seq))
    }

    #[test]
    fn content_form_roundtrip() {
        for form in [
            ContentForm::Full,
            ContentForm::Truncated {
                head_tokens: 1000,
                tail_tokens: 512,
            },
            ContentForm::Masked,
            ContentForm::Pointer,
            ContentForm::IndexOnly,
        ] {
            assert_eq!(form.to_string().parse::<ContentForm>().unwrap(), form);
        }
        assert!("F6".parse::<ContentForm>().is_err());
        assert!("F2:1".parse::<ContentForm>().is_err());
    }

    #[test]
    fn pointer_form_requires_archive_ref() {
        let (id, clock) = setup(1);
        let mut message = Message::text(
            id.clone(),
            Role::Assistant,
            SourceTag::History,
            "x",
            1,
            0,
            clock,
        );
        message.form = ContentForm::Pointer;
        assert!(message.validate().is_err());
        message.archived_ref = Some(ArchiveRef::from_content("x"));
        assert!(message.validate().is_ok());
    }

    #[test]
    fn tool_pair_closes_when_all_results_arrive() {
        let (id, clock) = setup(1);
        let calls = vec![
            OutgoingToolCall::new("c1", "read_file", "{}"),
            OutgoingToolCall::new("c2", "bash", "{}"),
        ];
        let head = Message::tool_call_message(id.clone(), calls, 10, 0, clock);
        let mut unit = AtomicUnit::tool_pair(head).unwrap();
        assert!(!unit.is_closed());
        let uid = unit.id.clone();

        let (rid, rclock) = setup(2);
        unit.add_result(
            Message::tool_result(rid.clone(), "c1", "a", 1, 0, rclock).with_unit(uid.clone()),
        )
        .unwrap();
        assert_eq!(unit.pending_calls(), vec!["c2"]);

        let (rid2, rclock2) = setup(3);
        unit.add_result(
            Message::tool_result(rid2, "c2", "b", 1, 0, rclock2).with_unit(uid.clone()),
        )
        .unwrap();
        assert!(unit.is_closed());
        unit.validate().unwrap();

        let (rid3, rclock3) = setup(4);
        assert!(unit
            .add_result(Message::tool_result(rid3, "c1", "dup", 1, 0, rclock3).with_unit(uid))
            .is_err());
    }

    #[test]
    fn unknown_tool_result_is_rejected() {
        let (id, clock) = setup(1);
        let head = Message::tool_call_message(
            id,
            vec![OutgoingToolCall::new("c1", "read_file", "{}")],
            1,
            0,
            clock,
        );
        let mut unit = AtomicUnit::tool_pair(head).unwrap();
        let uid = unit.id.clone();
        let (rid, rclock) = setup(2);
        let err = unit
            .add_result(Message::tool_result(rid, "nope", "x", 1, 0, rclock).with_unit(uid))
            .unwrap_err();
        assert!(matches!(err, UnitError::UnknownToolCall { .. }));
    }

    #[test]
    fn turn_unit_rejects_tool_messages() {
        let (id, clock) = setup(1);
        let result = Message::tool_result(id, "c1", "x", 1, 0, clock);
        assert!(AtomicUnit::turn(result).is_err());
    }

    #[test]
    fn tool_call_message_is_invalid_without_unit_context() {
        let (id, clock) = setup(1);
        let head = Message::tool_call_message(
            id,
            vec![OutgoingToolCall::new("c1", "bash", "{}")],
            1,
            0,
            clock,
        );
        assert!(head.validate().is_ok());
    }

    #[test]
    fn incomplete_flag_is_optional_in_json() {
        // 旧数据 / 导入数据里没有这个字段，读的时候不能炸（`#[serde(default)]`）
        let session = SessionId::new();
        let message = Message::text(
            MessageId::new(session, 1),
            Role::Assistant,
            SourceTag::History,
            "半句",
            2,
            0,
            LogicalClock::new(1),
        );
        let mut value = serde_json::to_value(&message).unwrap();
        value.as_object_mut().unwrap().remove("incomplete");
        let parsed: Message = serde_json::from_value(value).expect("没有 incomplete 字段也该能读");
        assert!(!parsed.incomplete);

        // 有标记能往返
        let flagged = message.mark_incomplete(true);
        let round: Message =
            serde_json::from_value(serde_json::to_value(flagged).unwrap()).unwrap();
        assert!(round.incomplete);
    }
}
