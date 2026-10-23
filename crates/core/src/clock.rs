//! 逻辑时钟（设计文档 §5.1，风险 R7）。
//!
//! 不用墙上时钟排序：三台设备时钟不一致、会回拨，而逻辑时钟只保证"同一会话内单调"。
//! 墙上时间仍然存，但只用于展示与 L3 合理性检查。

use serde::{Deserialize, Serialize};

use crate::ids::MessageId;

/// 会话内单调递增的计数器。从 1 开始，0 表示"未开始"。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct LogicalClock(u64);

impl LogicalClock {
    pub const ZERO: Self = Self(0);

    pub fn new(value: u64) -> Self {
        Self(value)
    }

    pub fn value(self) -> u64 {
        self.0
    }

    /// 本地事件：自增一格并返回新值。
    pub fn tick(&mut self) -> Self {
        self.0 = self.0.saturating_add(1);
        *self
    }

    /// 收到远端时钟：本地取两者较大者再自增（Lamport 规则的简化版）。
    ///
    /// 后续本地事件的时钟一定大于远端事件，从而在合并时保持因果顺序。
    pub fn observe(&mut self, remote: Self) -> Self {
        self.0 = self.0.max(remote.0).saturating_add(1);
        *self
    }
}

/// 写入前的单调性检查。
///
/// 存储层每次 append 都跑一次：同一会话里新消息的 `logical_clock` 必须严格大于
/// 上一条。违反说明写入路径有并发 bug，或数据来自坏掉的设备。
pub fn ensure_monotonic(
    previous: Option<LogicalClock>,
    next: LogicalClock,
    message: &MessageId,
) -> Result<(), ClockError> {
    if next == LogicalClock::ZERO {
        return Err(ClockError::ZeroClock {
            message: message.clone(),
        });
    }
    if let Some(previous) = previous {
        if next <= previous {
            return Err(ClockError::NonMonotonic {
                message: message.clone(),
                previous,
                next,
            });
        }
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ClockError {
    #[error("消息 {message} 的逻辑时钟为 0")]
    ZeroClock { message: MessageId },
    #[error("消息 {message} 的逻辑时钟回退：上一条 {previous:?}，本条 {next:?}")]
    NonMonotonic {
        message: MessageId,
        previous: LogicalClock,
        next: LogicalClock,
    },
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids::SessionId;

    #[test]
    fn tick_is_monotonic() {
        let mut clock = LogicalClock::ZERO;
        assert_eq!(clock.tick().value(), 1);
        assert_eq!(clock.tick().value(), 2);
        assert_eq!(clock.value(), 2);
    }

    #[test]
    fn observe_dominates_both_sides() {
        let mut local = LogicalClock::new(5);
        let observed = local.observe(LogicalClock::new(9));
        assert_eq!(observed.value(), 10);
        assert!(observed > LogicalClock::new(9));
    }

    #[test]
    fn non_monotonic_write_is_rejected() {
        let id = MessageId::new(SessionId::new(), 2);
        let err =
            ensure_monotonic(Some(LogicalClock::new(5)), LogicalClock::new(5), &id).unwrap_err();
        assert!(matches!(err, ClockError::NonMonotonic { .. }));

        let err =
            ensure_monotonic(Some(LogicalClock::new(5)), LogicalClock::ZERO, &id).unwrap_err();
        assert!(matches!(err, ClockError::ZeroClock { .. }));

        assert!(ensure_monotonic(None, LogicalClock::new(1), &id).is_ok());
        assert!(ensure_monotonic(Some(LogicalClock::new(5)), LogicalClock::new(6), &id).is_ok());
    }
}
