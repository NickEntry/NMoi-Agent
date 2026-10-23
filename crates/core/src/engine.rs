//! 无头引擎（计划 §3.2）。
//!
//! core 只负责：请求构造、流式事件、上下文管理、存储。
//! 它不知道 UI 长什么样，也不知道工具怎么执行——工具实现在平台侧，core 只定接口。
//!
//! 这里的 [`MemoryEngine`] 不接 provider，是事件流与原子单元的参考实现，
//! 也是 stage 0 的验收对象：事件顺序、逻辑时钟、快照 / 回滚、单元闭合。

use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::budget::{Budget, CompressionTrigger, TriggerPolicy};
use crate::clock::{ensure_monotonic, ClockError, LogicalClock};
use crate::events::{Event, EventBus, EventStream};
use crate::ids::{MessageId, SessionId, SnapshotId};
use crate::message::{AtomicUnit, Message, Role, UnitError};
use crate::source::SourceTag;
use crate::tokens::estimate_tokens;

/// 一次流式回复的标识。UI 用它把 Delta 归到同一轮。
pub type StreamId = String;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserInput {
    pub text: String,
    pub source: SourceTag,
}

impl UserInput {
    pub fn new(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            source: SourceTag::History,
        }
    }
}

/// 引擎接口。UI 侧只需要这四个动作。
pub trait Engine {
    fn submit(&mut self, input: UserInput) -> Result<StreamId, EngineError>;
    fn subscribe(&self) -> EventStream;
    /// 计划 §3.2 的草案签名是 `&self`；实现需要在引擎内保留快照登记表，
    /// 这里收紧成 `&mut self`（UI 本来就独占 engine，不影响调用方）。
    fn snapshot(&mut self) -> Result<SnapshotId, EngineError>;
    fn restore(&mut self, id: SnapshotId) -> Result<(), EngineError>;
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum EngineError {
    #[error("上一轮尚未结束")]
    Busy,
    #[error("当前没有进行中的轮次")]
    NoActiveTurn,
    #[error("本轮没有任何输出（被中断或空回复）；要丢弃本轮请用 abort_turn，不要用 finish_turn")]
    EmptyTurn,
    #[error(transparent)]
    Unit(#[from] UnitError),
    #[error(transparent)]
    Clock(#[from] ClockError),
    #[error("快照不存在: {0}")]
    SnapshotNotFound(SnapshotId),
    #[error("单元属于其他会话: {0}")]
    ForeignUnit(String),
    #[error("存储错误: {0}")]
    Storage(String),
}

#[derive(Debug, Clone)]
struct ActiveTurn {
    assistant_id: MessageId,
    text: String,
    reasoning: String,
}

#[derive(Debug, Clone)]
struct Snapshot {
    units: Vec<AtomicUnit>,
    clock: LogicalClock,
    next_seq: u64,
}

/// 内存引擎：单会话、单活动轮次。
#[derive(Debug)]
pub struct MemoryEngine {
    session: SessionId,
    next_seq: u64,
    clock: LogicalClock,
    units: Vec<AtomicUnit>,
    active: Option<ActiveTurn>,
    snapshots: HashMap<SnapshotId, Snapshot>,
    bus: EventBus,
}

impl MemoryEngine {
    pub fn new() -> Self {
        Self::with_session(SessionId::new())
    }

    pub fn with_session(session: SessionId) -> Self {
        Self {
            session,
            next_seq: 1,
            clock: LogicalClock::ZERO,
            units: Vec::new(),
            active: None,
            snapshots: HashMap::new(),
            bus: EventBus::new(),
        }
    }

    pub fn session(&self) -> SessionId {
        self.session
    }

    pub fn history(&self) -> &[AtomicUnit] {
        &self.units
    }

    pub fn used_tokens(&self) -> u64 {
        self.units
            .iter()
            .map(|unit| u64::from(unit.token_total()))
            .sum()
    }

    /// 当前上下文是否该压缩（§6.1）。UI 拿这个显示安全阀状态。
    pub fn trigger(
        &self,
        budget: &Budget,
        avg_per_turn: u64,
        payback_turns: f64,
        policy: &TriggerPolicy,
    ) -> CompressionTrigger {
        let mut budget = *budget;
        budget.used = self.used_tokens();
        crate::budget::evaluate_trigger(&budget, avg_per_turn, payback_turns, policy)
    }

    fn alloc_message(&mut self) -> MessageId {
        let id = MessageId::new(self.session, self.next_seq);
        self.next_seq += 1;
        id
    }

    fn push_unit(&mut self, unit: AtomicUnit) -> Result<(), EngineError> {
        unit.validate()?;
        if unit.id.session() != self.session {
            return Err(EngineError::ForeignUnit(unit.id.to_string()));
        }
        let previous = self.units.last().map(|last| last.logical_clock);
        ensure_monotonic(previous, unit.logical_clock, &unit.first_message().id)?;
        self.units.push(unit);
        Ok(())
    }

    /// 流式追加一段文本。前端可以逐字调用，也可以按块调用。
    pub fn push_delta(&mut self, text: &str) -> Result<(), EngineError> {
        let active = self.active.as_mut().ok_or(EngineError::NoActiveTurn)?;
        active.text.push_str(text);
        let id = active.assistant_id.clone();
        self.bus.publish(Event::Delta {
            id,
            text: text.to_string(),
            reasoning: None,
        });
        Ok(())
    }

    /// 流式追加思考内容（不进入正文）。
    pub fn push_reasoning(&mut self, text: &str) -> Result<(), EngineError> {
        let active = self.active.as_mut().ok_or(EngineError::NoActiveTurn)?;
        active.reasoning.push_str(text);
        let id = active.assistant_id.clone();
        self.bus.publish(Event::Delta {
            id,
            text: String::new(),
            reasoning: Some(text.to_string()),
        });
        Ok(())
    }

    /// 丢弃当前轮次：一个 token 都没产出就被中断的情况（用户点了停止、连接断了）。
    ///
    /// 不写历史、不发事件——空 assistant 消息是非法状态，不该进上下文。
    pub fn abort_turn(&mut self) -> Result<(), EngineError> {
        self.active.take().ok_or(EngineError::NoActiveTurn)?;
        Ok(())
    }

    /// 结束当前轮次，把 assistant 消息落成一个原子单元。
    pub fn finish_turn(&mut self, usage: crate::budget::Usage) -> Result<MessageId, EngineError> {
        self.finish_turn_with(usage, false)
    }

    /// 结束当前轮次，并标记"这轮没说完"（断流 / 被掐断 / 用户点停止，D21）。
    ///
    /// 和 [`MemoryEngine::abort_turn`] 的分工：那个是**一个 token 都没产出**时整轮丢弃；
    /// 这个是已经吐了半句话、必须留在历史里，但要说清楚它不完整——
    /// 否则断流的回复和正常回复在数据里长得一模一样，事后谁也看不出它少了半句。
    pub fn finish_turn_incomplete(
        &mut self,
        usage: crate::budget::Usage,
    ) -> Result<MessageId, EngineError> {
        self.finish_turn_with(usage, true)
    }

    pub fn finish_turn_with(
        &mut self,
        usage: crate::budget::Usage,
        incomplete: bool,
    ) -> Result<MessageId, EngineError> {
        let active = self.active.take().ok_or(EngineError::NoActiveTurn)?;
        if active.text.trim().is_empty() && active.reasoning.trim().is_empty() {
            // 没有正文也没有思考：不落库。调用方应当用 abort_turn（这里已把 active 清掉）。
            return Err(EngineError::EmptyTurn);
        }
        let clock = self.clock.tick();
        let mut message = Message::text(
            active.assistant_id.clone(),
            Role::Assistant,
            SourceTag::History,
            active.text,
            usage.completion_tokens,
            now_ms(),
            clock,
        );
        if !active.reasoning.is_empty() {
            message = message.with_reasoning(active.reasoning);
        }
        message = message.mark_incomplete(incomplete);
        let unit = AtomicUnit::turn(message)?;
        self.push_unit(unit)?;
        self.bus.publish(Event::TurnCompleted {
            id: active.assistant_id.clone(),
            usage,
            incomplete,
        });
        Ok(active.assistant_id)
    }

    /// 追加一个已经构造好的单元（工具对、导入的历史）。工具对未闭合时允许写入。
    pub fn append_unit(&mut self, unit: AtomicUnit) -> Result<(), EngineError> {
        if unit.kind == crate::message::UnitKind::ToolPair && !unit.is_closed() {
            // 未闭合单元可以落库（崩溃恢复需要），但不能被编辑；引擎照样接受。
            unit.validate()?;
            if unit.id.session() != self.session {
                return Err(EngineError::ForeignUnit(unit.id.to_string()));
            }
            let previous = self.units.last().map(|last| last.logical_clock);
            ensure_monotonic(previous, unit.logical_clock, &unit.first_message().id)?;
            self.units.push(unit);
            return Ok(());
        }
        self.push_unit(unit)
    }

    pub fn bus(&self) -> &EventBus {
        &self.bus
    }
}

impl Default for MemoryEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl Engine for MemoryEngine {
    fn submit(&mut self, input: UserInput) -> Result<StreamId, EngineError> {
        if self.active.is_some() {
            return Err(EngineError::Busy);
        }
        let clock = self.clock.tick();
        let id = self.alloc_message();
        let tokens = estimate_tokens(&input.text);
        let message = Message::text(
            id,
            Role::User,
            input.source,
            input.text,
            tokens,
            now_ms(),
            clock,
        );
        let unit = AtomicUnit::turn(message)?;
        self.push_unit(unit)?;

        let assistant_id = self.alloc_message();
        self.active = Some(ActiveTurn {
            assistant_id: assistant_id.clone(),
            text: String::new(),
            reasoning: String::new(),
        });
        self.bus.publish(Event::TurnStarted {
            id: assistant_id.clone(),
            at: self.clock,
        });
        Ok(assistant_id.to_string())
    }

    fn subscribe(&self) -> EventStream {
        self.bus.subscribe()
    }

    fn snapshot(&mut self) -> Result<SnapshotId, EngineError> {
        let id = SnapshotId::new();
        self.snapshots.insert(
            id,
            Snapshot {
                units: self.units.clone(),
                clock: self.clock,
                next_seq: self.next_seq,
            },
        );
        Ok(id)
    }

    fn restore(&mut self, id: SnapshotId) -> Result<(), EngineError> {
        let snapshot = self
            .snapshots
            .get(&id)
            .cloned()
            .ok_or(EngineError::SnapshotNotFound(id))?;
        self.units = snapshot.units;
        self.clock = snapshot.clock;
        self.next_seq = snapshot.next_seq;
        self.active = None;
        Ok(())
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::budget::Usage;
    use crate::events::Event;
    use crate::message::OutgoingToolCall;

    fn usage(prompt: u32, completion: u32) -> Usage {
        Usage {
            prompt_tokens: prompt,
            completion_tokens: completion,
            cached_tokens: 0,
            reasoning_tokens: 0,
        }
    }

    #[test]
    fn submit_emits_started_then_deltas_then_completed() {
        let mut engine = MemoryEngine::new();
        let stream = engine.subscribe();

        let stream_id = engine.submit(UserInput::new("写配置代码")).unwrap();
        engine.push_delta("fn ").unwrap();
        engine.push_delta("main() {}").unwrap();
        let finished = engine.finish_turn(usage(10, 5)).unwrap();
        assert_eq!(finished.to_string(), stream_id);

        let events = stream.drain();
        assert_eq!(events.len(), 4);
        assert!(matches!(events[0].event, Event::TurnStarted { .. }));
        assert!(matches!(events[1].event, Event::Delta { .. }));
        assert!(matches!(events[3].event, Event::TurnCompleted { .. }));
        assert_eq!(
            events.iter().map(|e| e.seq).collect::<Vec<_>>(),
            vec![1, 2, 3, 4]
        );
    }

    #[test]
    fn units_keep_monotonic_logical_clock() {
        let mut engine = MemoryEngine::new();
        engine.submit(UserInput::new("一")).unwrap();
        engine.push_delta("答案一").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();
        engine.submit(UserInput::new("二")).unwrap();
        engine.push_delta("答案二").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();

        let clocks: Vec<u64> = engine
            .history()
            .iter()
            .map(|unit| unit.logical_clock.value())
            .collect();
        assert_eq!(clocks, vec![1, 2, 3, 4]);
        assert_eq!(engine.history().len(), 4);
    }

    #[test]
    fn double_submit_is_rejected_until_finish() {
        let mut engine = MemoryEngine::new();
        engine.submit(UserInput::new("一")).unwrap();
        assert_eq!(engine.submit(UserInput::new("二")), Err(EngineError::Busy));
        engine.push_delta("ok").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();
        assert!(engine.submit(UserInput::new("三")).is_ok());
    }

    #[test]
    fn delta_without_turn_is_rejected() {
        let mut engine = MemoryEngine::new();
        assert_eq!(engine.push_delta("x"), Err(EngineError::NoActiveTurn));
        assert_eq!(
            engine.finish_turn(usage(0, 0)),
            Err(EngineError::NoActiveTurn)
        );
    }

    #[test]
    fn snapshot_and_restore_roll_back_history() {
        let mut engine = MemoryEngine::new();
        engine.submit(UserInput::new("一")).unwrap();
        engine.push_delta("答案一").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();

        let snapshot = engine.snapshot().unwrap();
        let before = engine.used_tokens();

        engine.submit(UserInput::new("二")).unwrap();
        engine.push_delta("答案二").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();
        assert!(engine.used_tokens() > before);

        engine.restore(snapshot).unwrap();
        assert_eq!(engine.used_tokens(), before);
        assert_eq!(engine.history().len(), 2);

        // 回滚后还能继续写，且时钟不回退。
        engine.submit(UserInput::new("三")).unwrap();
        engine.push_delta("答案三").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();
        let last = engine.history().last().unwrap();
        assert!(last.logical_clock > engine.history()[1].logical_clock);
    }

    fn assistant_units(engine: &MemoryEngine) -> usize {
        engine
            .history()
            .iter()
            .filter(|unit| unit.messages.iter().any(|m| m.role == Role::Assistant))
            .count()
    }

    #[test]
    fn empty_turn_is_rejected_and_abort_drops_it() {
        let mut engine = MemoryEngine::new();
        // submit 会先落一条 user 消息（它自己就是一个原子单元），所以历史从 1 开始。
        engine.submit(UserInput::new("说点什么")).unwrap();
        assert_eq!(engine.history().len(), 1);
        assert_eq!(engine.finish_turn(usage(0, 0)), Err(EngineError::EmptyTurn));
        assert_eq!(engine.history().len(), 1, "空 assistant 不能进历史");
        assert_eq!(assistant_units(&engine), 0);

        // 中断路径（用户点停止 / 断线）：丢弃本轮，同样不写历史，但不算错误。
        engine.submit(UserInput::new("再来一次")).unwrap();
        engine.abort_turn().unwrap();
        assert_eq!(engine.history().len(), 2, "两次提交 = 两条用户消息");
        assert_eq!(assistant_units(&engine), 0);
        assert_eq!(engine.abort_turn(), Err(EngineError::NoActiveTurn));
    }

    #[test]
    fn interrupted_turn_is_kept_but_flagged() {
        // 已经吐了半句话的轮次不能丢（用户看得见它），但必须标成不完整。
        let mut engine = MemoryEngine::new();
        let stream = engine.subscribe();
        engine.submit(UserInput::new("说点什么")).unwrap();
        engine.push_delta("说到一半").unwrap();
        let id = engine.finish_turn_incomplete(usage(10, 3)).unwrap();

        let last = engine.history().last().unwrap();
        assert_eq!(last.messages[0].content, "说到一半");
        assert!(last.messages[0].incomplete, "断流必须留标记");
        assert_eq!(last.messages[0].id, id);

        // 事件也要说清楚，不然实时订阅的界面只能等下次载入才知道
        let events = stream.drain();
        assert!(events.iter().any(|envelope| matches!(
            &envelope.event,
            Event::TurnCompleted {
                incomplete: true,
                ..
            }
        )));

        // 正常结束的那一轮标记是 false
        engine.submit(UserInput::new("再来")).unwrap();
        engine.push_delta("说完了").unwrap();
        engine.finish_turn(usage(1, 1)).unwrap();
        assert!(!engine.history().last().unwrap().messages[0].incomplete);
    }

    #[test]
    fn restore_unknown_snapshot_fails() {
        let mut engine = MemoryEngine::new();
        let err = engine.restore(SnapshotId::new()).unwrap_err();
        assert!(matches!(err, EngineError::SnapshotNotFound(_)));
    }

    #[test]
    fn tool_pair_unit_can_be_appended_open() {
        let mut engine = MemoryEngine::new();
        let session = engine.session();
        let head_id = MessageId::new(session, 1);
        let head = Message::tool_call_message(
            head_id,
            vec![OutgoingToolCall::new("c1", "read_file", "{}")],
            5,
            now_ms(),
            LogicalClock::new(1),
        );
        let unit = AtomicUnit::tool_pair(head).unwrap();
        assert!(!unit.is_closed());
        engine.append_unit(unit).unwrap();
        assert_eq!(engine.history().len(), 1);
        assert_eq!(engine.used_tokens(), 5);
    }

    #[test]
    fn foreign_session_unit_is_rejected() {
        let mut engine = MemoryEngine::new();
        let other = SessionId::new();
        let id = MessageId::new(other, 1);
        let message = Message::text(
            id,
            Role::User,
            SourceTag::History,
            "x",
            1,
            now_ms(),
            LogicalClock::new(1),
        );
        let unit = AtomicUnit::turn(message).unwrap();
        assert!(matches!(
            engine.append_unit(unit),
            Err(EngineError::ForeignUnit(_))
        ));
    }

    #[test]
    fn trigger_counts_existing_tokens() {
        let mut engine = MemoryEngine::new();
        engine.submit(UserInput::new("字".repeat(9_000))).unwrap();
        engine.push_delta("ok").unwrap();
        engine.finish_turn(usage(9_000, 10)).unwrap();

        let budget = Budget::new(10_000, 10_000, 0);
        let trigger = engine.trigger(&budget, 2_000, 24.0, &TriggerPolicy::default());
        assert!(trigger.should_compact());
    }
}
