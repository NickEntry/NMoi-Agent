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

use crate::clock::LogicalClock;
use crate::ids::{ArchiveRef, MessageId, SessionId};
use crate::message::{AtomicUnit, Message, Role};
use crate::source::SourceTag;
use crate::tokens::estimate_tokens;

/// 压缩线的 `note` 里，摘要引用与说明之间的分隔符（D143 定的格式）。
pub const SUMMARY_NOTE_SEP: &str = " | ";

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

    /// 这条线对应的摘要归档引用（D149）。
    ///
    /// 格式是 D143 定的：`note` 的首段就是 `ArchiveRef`，后面才接人话。
    /// 用户手插的线没有 note，返回 `None`——"线下是窗口"照样成立，只是没有摘要可注入。
    pub fn summary_ref(&self) -> Option<ArchiveRef> {
        if self.kind != DividerKind::Compression {
            return None;
        }
        let note = self.note.as_deref()?;
        let head = note.split(SUMMARY_NOTE_SEP).next()?.trim();
        ArchiveRef::parse(head).ok()
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

/// 窗口最小形状（D143）：窗口的第一条必须是**用户消息**。
///
/// 纯预算切点只保证"落在单元边界上"，于是窗口可能以 assistant 开头——它回答的那条用户消息
/// 已经在线上；最坏是窗口以 `assistant(tool_calls)` 开头，而它的 `tool_result` 也在线上
/// （实测 9 次切点里 7 次这样，其中 2 次是悬空工具对）。
///
/// 处理顺序：
///
/// 1. 拿到预算切点后，先**丢掉**窗口开头那些残轮（第一条不是用户消息的单元）；
/// 2. 丢完若完整轮数不足 `min_turns`，反过来**往前扩**，把那个残轮的用户消息包进来；
/// 3. 两条路都要满足"线下面至少一个单元"，否则退回原切点。
///
/// 返回的永远是单元边界上的 `after_seq`，所以不会切开 tool_call / tool_result。
pub fn choose_cut_for_window_shape(
    units: &[AtomicUnit],
    keep_recent_tokens: u32,
    min_turns: usize,
) -> Option<u64> {
    let base = choose_cut_for_budget(units, keep_recent_tokens)?;
    let want = min_turns.max(1);
    let Ok(start) = split_index(units, base) else {
        return Some(base);
    };
    if units[start].first_message().role == Role::User && full_turns(&units[start..]) >= want {
        return Some(base);
    }

    // 1. 先试**丢掉**残轮：把线挪到最后一个残轮单元之后。
    let mut best = base;
    let mut cursor = start;
    if let Some(offset) = units[start..]
        .iter()
        .position(|unit| unit.first_message().role == Role::User)
    {
        if offset > 0 {
            let candidate = last_seq_of(&units[start + offset - 1]);
            if let Ok(index) = split_index(units, candidate) {
                if full_turns(&units[index..]) >= want {
                    return Some(candidate);
                }
                // 丢完轮数不够，先记下这个候选，再去往前扩。
                best = candidate;
                cursor = index;
            }
        }
    }

    // 2. 往前扩：一轮一轮把残缺轮的用户消息包进来，直到轮数够或扩不动。
    while let Some(previous) = extend_to_turn_start(units, cursor) {
        let Ok(index) = split_index(units, previous) else {
            break;
        };
        best = previous;
        if full_turns(&units[index..]) >= want || index == 0 {
            break;
        }
        cursor = index;
    }
    Some(best)
}

/// 完整轮数：以用户消息开头的单元个数。
fn full_turns(units: &[AtomicUnit]) -> usize {
    units
        .iter()
        .filter(|unit| unit.first_message().role == Role::User)
        .count()
}

/// 从 `start` 往前找最近一个用户轮，返回"它之前"的切点。
fn extend_to_turn_start(units: &[AtomicUnit], start: usize) -> Option<u64> {
    let mut index = start;
    while index > 0 {
        index -= 1;
        if units[index].first_message().role == Role::User {
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

// ---------------------------------------------------------------------------
// 当前窗口（D149）：线下单元 + 线上那段的摘要
// ---------------------------------------------------------------------------

/// 当前窗口：最新一条分界线**以下**的单元，加上那条线压出来的摘要正文。
///
/// 摘要**不在** `messages` 表里（D149 说了为什么），所以它是这个结构的一部分而不是一条单元：
/// 谁把窗口拼成请求，谁就得显式调用 [`ContextWindow::summary_message`] 把它放到最前面。
/// 只有这一个入口——第二个入口迟早会被漏掉一个调用点，而漏掉的表现是"模型突然不记得前面的事了"。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ContextWindow {
    /// 生效的那条线（合法线里 `after_seq` 最大的那条）。`None` = 没画过线。
    pub divider: Option<Divider>,
    /// 线上那段的摘要（第一人称、含摘录块）。只有压缩线才有。
    pub summary: Option<String>,
    /// 窗口里的单元，仍是 `seq` 升序。
    pub units: Vec<AtomicUnit>,
}

impl ContextWindow {
    /// 没画过线的会话：整条历史就是窗口。行为与 D149 之前一致。
    pub fn of_all(units: Vec<AtomicUnit>) -> Self {
        Self {
            divider: None,
            summary: None,
            units,
        }
    }

    pub fn is_compacted(&self) -> bool {
        self.summary.is_some()
    }

    /// 摘要占多少 token。计预算时**必须**加进去：它是每轮都要发的固定成本，
    /// 不加就是在低估（D144 的口径）。
    pub fn summary_tokens(&self) -> u64 {
        self.summary
            .as_deref()
            .map(|text| u64::from(estimate_tokens(text)))
            .unwrap_or(0)
    }

    /// 摘要装成一条注入消息，放在请求的最前面。
    ///
    /// `seq = 0`：它不是历史里的第几条，谁也别拿它去算下一条的号
    /// （推 `seq` 会连带撞上窗口以下的消息）。
    pub fn summary_message(&self, session: SessionId, now: i64) -> Option<Message> {
        let text = self.summary.as_deref()?;
        Some(Message::text(
            MessageId::new(session, 0),
            Role::User,
            SourceTag::InstructionInjection,
            text,
            estimate_tokens(text),
            now,
            LogicalClock::new(0),
        ))
    }

    /// 压缩的输入：**把上一次的摘要当成第一段原文**，接在窗口前面。
    ///
    /// 二次压缩必须带上它，否则这一次只压到"还在窗口里"的那些单元，
    /// 上一次压出来的摘要整段丢掉——用户看到的就是"内容丢了"。
    pub fn units_with_summary(&self, session: SessionId, now: i64) -> Vec<AtomicUnit> {
        let mut units = Vec::with_capacity(self.units.len() + 1);
        if let Some(message) = self.summary_message(session, now) {
            if let Ok(unit) = AtomicUnit::turn(message) {
                units.push(unit);
            }
        }
        units.extend(self.units.iter().cloned());
        units
    }
}

/// 从一批分界线里挑出**生效的那一条**（合法线里切点最靠后的），连同它切出来的起点。
///
/// 失效的线（位置被删、落在单元内部）静默跳过，理由和 [`window_start`] 一样：
/// 一条坏线不该让会话打不开，L3 校验会单独把它报出来。
pub fn effective_divider<'a>(
    units: &[AtomicUnit],
    dividers: &'a [Divider],
) -> Option<(&'a Divider, usize)> {
    let mut best: Option<(&Divider, usize)> = None;
    for divider in dividers {
        let Ok(index) = split_index(units, divider.after_seq) else {
            continue;
        };
        if best.map_or(true, |(_, start)| index > start) {
            best = Some((divider, index));
        }
    }
    best
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

    // ---- 窗口形状（D143）----

    struct Shape {
        session: SessionId,
        seq: u64,
        units: Vec<AtomicUnit>,
    }

    impl Shape {
        fn new() -> Self {
            Self {
                session: SessionId::new(),
                seq: 0,
                units: Vec::new(),
            }
        }

        fn bump(&mut self) -> u64 {
            self.seq += 1;
            self.seq
        }

        fn text(&mut self, role: Role, tokens: u32) {
            let seq = self.bump();
            let message = Message::text(
                MessageId::new(self.session, seq),
                role,
                SourceTag::History,
                format!("m{seq}"),
                tokens,
                0,
                LogicalClock::new(seq),
            );
            self.units.push(AtomicUnit::turn(message).unwrap());
        }

        fn user(&mut self, tokens: u32) {
            self.text(Role::User, tokens);
        }

        fn assistant(&mut self, tokens: u32) {
            self.text(Role::Assistant, tokens);
        }

        fn tool_round(&mut self, tokens: u32) {
            let call_seq = self.bump();
            let head = Message::tool_call_message(
                MessageId::new(self.session, call_seq),
                vec![crate::message::OutgoingToolCall::new("c1", "read", "{}")],
                tokens,
                0,
                LogicalClock::new(call_seq),
            );
            let mut unit = AtomicUnit::tool_pair(head).unwrap();
            let result_seq = self.bump();
            unit.add_result(
                Message::tool_result(
                    MessageId::new(self.session, result_seq),
                    "c1",
                    "x",
                    tokens,
                    0,
                    LogicalClock::new(result_seq),
                )
                .with_unit(unit.id.clone()),
            )
            .unwrap();
            self.units.push(unit);
        }
    }

    /// 窗口第一条必须是用户消息：纯预算切点落在这里的残轮上时要被纠正。
    #[test]
    fn window_shape_starts_on_user_turn() {
        let mut shape = Shape::new();
        shape.user(1);
        shape.assistant(1);
        shape.user(1);
        shape.assistant(1);
        shape.user(1);
        shape.assistant(1);
        let units = shape.units;

        // 预算只够 1 个单元时，纯预算切点会让窗口从 assistant 开始。
        let raw = choose_cut_for_budget(&units, 1).unwrap();
        let (_, raw_below) = split(&units, raw).unwrap();
        assert_ne!(raw_below[0].first_message().role, Role::User);

        let shaped = choose_cut_for_window_shape(&units, 1, 2).unwrap();
        let (_, below) = split(&units, shaped).unwrap();
        assert_eq!(below[0].first_message().role, Role::User);
        assert!(validate_position(&units, shaped).is_ok());
    }

    /// 丢掉残轮会把轮数抬起来，但绝不允许把工具对切开。
    #[test]
    fn window_shape_never_splits_tool_pair() {
        let mut shape = Shape::new();
        shape.user(1);
        shape.assistant(1);
        shape.user(1);
        shape.tool_round(1);
        shape.assistant(1);
        let units = shape.units;
        for keep in 1..=4u32 {
            if let Some(cut) = choose_cut_for_window_shape(&units, keep, 2) {
                assert_eq!(
                    validate_position(&units, cut),
                    Ok(()),
                    "切点 {cut} 必须是单元边界"
                );
            }
        }
    }

    /// 残轮丢完不足 min_turns 时要往前扩，把那个残轮的用户消息包进来。
    #[test]
    fn window_shape_extends_when_turns_too_few() {
        let mut shape = Shape::new();
        for _ in 0..5 {
            shape.user(1);
            shape.assistant(1);
        }
        let units = shape.units;
        let cut = choose_cut_for_window_shape(&units, 1, 3).unwrap();
        let (_, below) = split(&units, cut).unwrap();
        let turns = below
            .iter()
            .filter(|u| u.first_message().role == Role::User)
            .count();
        assert!(turns >= 3, "要求 3 轮，实际 {turns}");
    }

    /// 满预算时行为与纯预算切点一致（不无故多压）。
    #[test]
    fn window_shape_falls_back_to_budget_when_all_fits() {
        let mut shape = Shape::new();
        shape.user(1);
        shape.assistant(1);
        let units = shape.units;
        assert!(choose_cut_for_window_shape(&units, 100, 2).is_none());
    }

    /// 整段没有用户消息时不 panic，仍返回合法切点。
    #[test]
    fn window_shape_handles_all_assistant_history() {
        let mut shape = Shape::new();
        shape.assistant(1);
        shape.assistant(1);
        shape.assistant(1);
        let units = shape.units;
        if let Some(cut) = choose_cut_for_window_shape(&units, 1, 2) {
            assert!(validate_position(&units, cut).is_ok());
        }
    }
}
