//! 上下文分界线（用户需求：压缩与手动分隔共用一套机制）。
//!
//! 一条分界线把会话窗口切成两段：**线上不进上下文，线下才是当前窗口**。
//! 机制上等价于在会话内部划出两个会话段，但历史全部保留、随时可以删线还原。
//!
//! 三条硬规则：
//!
//! 1. 线只能落在**原子单元边界**上——切开 tool_call / tool_result 会产生非法消息序列（§4.2）；
//! 2. 线下面必须至少剩一个单元，否则窗口为空；
//! 3. 压缩工具插入的线与用户手插的线是同一类对象，只是 `kind` 不同，都可删除。

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::ids::{MessageId, SessionId};
use crate::message::AtomicUnit;

/// 分界线 ID（UUIDv7，可排序）。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct DividerId(String);

impl DividerId {
    pub fn new() -> Self {
        Self(Uuid::now_v7().to_string())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn parse(value: impl Into<String>) -> Self {
        Self(value.into())
    }
}

impl Default for DividerId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for DividerId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// 线是谁画的。删除时 UI 可以用它区分提示语。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DividerKind {
    /// 用户手动插入（长按两条消息之间）。
    User,
    /// 压缩工具生成。
    Compression,
}

impl DividerKind {
    pub fn as_str(self) -> &'static str {
        match self {
            DividerKind::User => "user",
            DividerKind::Compression => "compression",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "user" => Some(DividerKind::User),
            "compression" => Some(DividerKind::Compression),
            _ => None,
        }
    }
}

/// 一条分界线：位于 `after_seq` 之后、下一条消息之前。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Divider {
    pub id: DividerId,
    pub session: SessionId,
    pub after_seq: u64,
    pub kind: DividerKind,
    pub note: Option<String>,
    pub created_at: i64,
}

impl Divider {
    pub fn new(
        session: SessionId,
        after_seq: u64,
        kind: DividerKind,
        note: Option<String>,
        created_at: i64,
    ) -> Self {
        Self {
            id: DividerId::new(),
            session,
            after_seq,
            kind,
            note,
            created_at,
        }
    }

    /// 压缩工具画线：位置必须已经通过 [`validate_position`]。
    pub fn compression(
        session: SessionId,
        after_seq: u64,
        note: impl Into<String>,
        created_at: i64,
    ) -> Self {
        Self::new(
            session,
            after_seq,
            DividerKind::Compression,
            Some(note.into()),
            created_at,
        )
    }

    pub fn user(session: SessionId, after_seq: u64, created_at: i64) -> Self {
        Self::new(session, after_seq, DividerKind::User, None, created_at)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DividerError {
    #[error("位置上没有消息：seq={after_seq}")]
    OutOfRange { after_seq: u64 },
    #[error("位置 seq={after_seq} 落在原子单元内部，会切开 tool_call/result 对")]
    SplitsUnit { after_seq: u64 },
    #[error("线下面至少要留一个原子单元")]
    NothingBelow { after_seq: u64 },
    #[error("分界线属于其他会话: {0}")]
    ForeignSession(String),
}

/// 某个 seq 是否是单元的最后一条消息。
fn last_seq_of(unit: &AtomicUnit) -> u64 {
    unit.last_message().id.seq()
}

fn first_seq_of(unit: &AtomicUnit) -> u64 {
    unit.first_message().id.seq()
}

/// 校验"插在这里"是否合法。
pub fn validate_position(units: &[AtomicUnit], after_seq: u64) -> Result<(), DividerError> {
    if units.is_empty() {
        return Err(DividerError::OutOfRange { after_seq });
    }
    let mut found = None;
    for (index, unit) in units.iter().enumerate() {
        if after_seq >= first_seq_of(unit) && after_seq <= last_seq_of(unit) {
            if after_seq != last_seq_of(unit) {
                return Err(DividerError::SplitsUnit { after_seq });
            }
            found = Some(index);
            break;
        }
    }
    let index = found.ok_or(DividerError::OutOfRange { after_seq })?;
    if index + 1 >= units.len() {
        return Err(DividerError::NothingBelow { after_seq });
    }
    Ok(())
}

/// 切点后面第一个单元的索引。`validate_position` 通过后调用。
pub fn split_index(units: &[AtomicUnit], after_seq: u64) -> Result<usize, DividerError> {
    validate_position(units, after_seq)?;
    Ok(units
        .iter()
        .position(|unit| first_seq_of(unit) > after_seq)
        .unwrap_or(units.len()))
}

/// 把单元切成"线上 / 线下"两段，两段都不含被切开的单元。
pub fn split(
    units: &[AtomicUnit],
    after_seq: u64,
) -> Result<(&[AtomicUnit], &[AtomicUnit]), DividerError> {
    let index = split_index(units, after_seq)?;
    Ok((&units[..index], &units[index..]))
}

/// 当前生效窗口的起始索引：取所有合法分界线里 `after_seq` 最大的那条。
///
/// 失效的线（位置已被删除、落在单元内部）**静默忽略**——它们不该把一个正常会话
/// 变成打不开的库；L3 校验会把它们单独报出来。
pub fn window_start(units: &[AtomicUnit], dividers: &[Divider]) -> usize {
    let mut start = 0;
    for divider in dividers {
        if let Ok(index) = split_index(units, divider.after_seq) {
            if index > start {
                start = index;
            }
        }
    }
    start
}

/// 当前窗口。UI 只渲染这一段；线上内容仍然可以从归档 / 历史列表取回。
pub fn active_window<'a>(units: &'a [AtomicUnit], dividers: &[Divider]) -> &'a [AtomicUnit] {
    &units[window_start(units, dividers)..]
}

/// 压缩工具选切点：从末尾往前累加，直到超出 `keep_recent_tokens`。
///
/// 返回 `after_seq`（切口上方的最后一个单元的最后一条消息）。
/// `None` 表示全部内容都在预算内，不需要画线。
pub fn choose_cut_for_budget(units: &[AtomicUnit], keep_recent_tokens: u32) -> Option<u64> {
    if units.len() < 2 {
        return None;
    }
    let mut accumulated: u64 = 0;
    for (index, unit) in units.iter().enumerate().rev() {
        accumulated += u64::from(unit.token_total());
        if accumulated > u64::from(keep_recent_tokens) {
            // 从 index 开始的整个后缀都超预算，所以切在 index 之后，
            // 让窗口从 index + 1 开始（它的总量正好回到预算内）。
            if index + 1 < units.len() {
                return Some(last_seq_of(&units[index]));
            }
            // 最后一个单元自己就超预算：切在它前面，只保留这一个。
            if index == 0 {
                return None;
            }
            return Some(last_seq_of(&units[index - 1]));
        }
    }
    None
}

/// 线上内容（用于归档）。窗口之外的所有单元。
pub fn units_above(units: &[AtomicUnit], after_seq: u64) -> &[AtomicUnit] {
    match split_index(units, after_seq) {
        Ok(index) => &units[..index],
        Err(_) => &[],
    }
}

/// 分界线是否仍然有效（位置没被删、也没切开单元）。
pub fn is_valid(units: &[AtomicUnit], divider: &Divider) -> bool {
    validate_position(units, divider.after_seq).is_ok()
}

/// 该 seq 对应的消息 ID（用于 UI 定位与测试）。
pub fn boundary_message(units: &[AtomicUnit], after_seq: u64) -> Option<MessageId> {
    units
        .iter()
        .find(|unit| last_seq_of(unit) == after_seq)
        .map(|unit| unit.last_message().id.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::message::{Message, Role};
    use crate::source::SourceTag;

    fn unit(session: SessionId, seq: u64, tokens: u32) -> AtomicUnit {
        let id = MessageId::new(session, seq);
        let message = Message::text(
            id,
            Role::User,
            SourceTag::History,
            format!("m{seq}"),
            tokens,
            0,
            LogicalClock::new(seq),
        );
        AtomicUnit::turn(message).unwrap()
    }

    fn units(count: u64, tokens: u32) -> (SessionId, Vec<AtomicUnit>) {
        let session = SessionId::new();
        let units = (1..=count).map(|seq| unit(session, seq, tokens)).collect();
        (session, units)
    }

    #[test]
    fn position_must_be_unit_boundary_and_leave_something_below() {
        let (_, units) = units(3, 10);
        assert!(validate_position(&units, 1).is_ok());
        assert_eq!(
            validate_position(&units, 0),
            Err(DividerError::OutOfRange { after_seq: 0 })
        );
        assert_eq!(
            validate_position(&units, 3),
            Err(DividerError::NothingBelow { after_seq: 3 })
        );
    }

    #[test]
    fn position_inside_tool_pair_is_rejected() {
        let session = SessionId::new();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![crate::message::OutgoingToolCall::new("c1", "read", "{}")],
            1,
            0,
            LogicalClock::new(1),
        );
        let mut pair = AtomicUnit::tool_pair(head).unwrap();
        pair.add_result(
            Message::tool_result(
                MessageId::new(session, 2),
                "c1",
                "x",
                1,
                0,
                LogicalClock::new(2),
            )
            .with_unit(pair.id.clone()),
        )
        .unwrap();
        let tail = unit(session, 3, 1);
        let list = vec![pair, tail];
        assert_eq!(
            validate_position(&list, 1),
            Err(DividerError::SplitsUnit { after_seq: 1 })
        );
        assert!(validate_position(&list, 2).is_ok());
    }

    #[test]
    fn split_returns_two_non_empty_segments() {
        let (_, units) = units(4, 5);
        let (above, below) = split(&units, 2).unwrap();
        assert_eq!(above.len(), 2);
        assert_eq!(below.len(), 2);
        assert_eq!(below[0].first_message().id.seq(), 3);
    }

    #[test]
    fn active_window_uses_latest_valid_divider() {
        let (session, units) = units(5, 5);
        let dividers = vec![
            Divider::user(session, 1, 0),
            Divider::user(session, 3, 0),
            // 失效：落在单元内部之外的位置
            Divider::user(session, 99, 0),
        ];
        let window = active_window(&units, &dividers);
        assert_eq!(window.len(), 2);
        assert_eq!(window[0].first_message().id.seq(), 4);
    }

    #[test]
    fn deleting_divider_restores_full_window() {
        let (session, units) = units(4, 5);
        let mut dividers = vec![Divider::user(session, 2, 0)];
        assert_eq!(active_window(&units, &dividers).len(), 2);
        dividers.clear();
        assert_eq!(active_window(&units, &dividers).len(), 4);
    }

    #[test]
    fn choose_cut_keeps_recent_budget() {
        let (_, units) = units(10, 100);
        let cut = choose_cut_for_budget(&units, 300).unwrap();
        let (above, below) = split(&units, cut).unwrap();
        assert_eq!(below.len(), 3);
        assert!(below.iter().map(|u| u.token_total()).sum::<u32>() <= 300);
        assert_eq!(above.len(), 7);
    }

    #[test]
    fn choose_cut_returns_none_when_everything_fits() {
        let (_, units) = units(3, 10);
        assert!(choose_cut_for_budget(&units, 1_000).is_none());
    }

    #[test]
    fn units_above_is_empty_for_invalid_cut() {
        let (_, units) = units(3, 10);
        assert!(units_above(&units, 99).is_empty());
    }
}
