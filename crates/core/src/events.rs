//! 事件流（计划 §3.2）。
//!
//! UI 侧唯一入口是 [`EventStream`]：订阅、收事件、渲染。core 不知道 UI 长什么样。
//! 每个事件带一个总线分配的 `seq`，保证跨订阅者顺序一致，UI 可以据此去重与补帧。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::sync::Mutex;

use crate::budget::Usage;
use crate::clock::LogicalClock;
use crate::ids::{ArchiveRef, MessageId, SnapshotId};
use crate::message::OutgoingToolCall;

/// 错误分类。`retryable` 由策略层决定，不在这里猜。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Provider,
    Transport,
    Budget,
    Storage,
    Internal,
}

impl ErrorKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorKind::Provider => "provider",
            ErrorKind::Transport => "transport",
            ErrorKind::Budget => "budget",
            ErrorKind::Storage => "storage",
            ErrorKind::Internal => "internal",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    TurnStarted {
        id: MessageId,
        at: LogicalClock,
    },
    Delta {
        id: MessageId,
        text: String,
        reasoning: Option<String>,
    },
    ToolCall {
        id: MessageId,
        call: OutgoingToolCall,
    },
    TurnCompleted {
        id: MessageId,
        usage: Usage,
        /// 这一轮是不是**没说完**就结束了（断流 / 被停止，D21）。
        incomplete: bool,
    },
    ContextCompressed {
        from: SnapshotId,
        to: SnapshotId,
        archived: Vec<ArchiveRef>,
    },
    Error {
        kind: ErrorKind,
        message: String,
        retryable: bool,
    },
}

#[derive(Debug, Clone, PartialEq)]
pub struct EventEnvelope {
    /// 总线内单调递增，从 1 开始。
    pub seq: u64,
    pub event: Event,
}

/// 多订阅者事件总线。订阅者掉线自动剔除，不影响其他订阅者。
#[derive(Debug, Default)]
pub struct EventBus {
    subscribers: Mutex<Vec<Sender<EventEnvelope>>>,
    seq: AtomicU64,
}

impl EventBus {
    pub fn new() -> Self {
        Self::default()
    }

    /// 订阅。返回的 [`EventStream`] 会收到订阅之后发布的事件。
    pub fn subscribe(&self) -> EventStream {
        let (tx, rx) = mpsc::channel();
        let mut guard = self.subscribers.lock().unwrap_or_else(|e| e.into_inner());
        guard.push(tx);
        EventStream { rx }
    }

    /// 发布一条事件，返回带 `seq` 的信封。
    pub fn publish(&self, event: Event) -> EventEnvelope {
        let seq = self.seq.fetch_add(1, Ordering::SeqCst) + 1;
        let envelope = EventEnvelope { seq, event };
        let mut guard = self.subscribers.lock().unwrap_or_else(|e| e.into_inner());
        guard.retain(|tx| tx.send(envelope.clone()).is_ok());
        envelope
    }

    pub fn subscriber_count(&self) -> usize {
        let guard = self.subscribers.lock().unwrap_or_else(|e| e.into_inner());
        guard.len()
    }
}

/// 订阅端。`recv` 阻塞，`try_recv` 不阻塞。
#[derive(Debug)]
pub struct EventStream {
    rx: Receiver<EventEnvelope>,
}

impl EventStream {
    pub fn recv(&self) -> Result<EventEnvelope, StreamError> {
        self.rx.recv().map_err(|_| StreamError::Closed)
    }

    pub fn try_recv(&self) -> Result<EventEnvelope, StreamError> {
        match self.rx.try_recv() {
            Ok(envelope) => Ok(envelope),
            Err(TryRecvError::Empty) => Err(StreamError::Empty),
            Err(TryRecvError::Disconnected) => Err(StreamError::Closed),
        }
    }

    /// 取出当前已排队的事件，不阻塞。
    pub fn drain(&self) -> Vec<EventEnvelope> {
        let mut out = Vec::new();
        while let Ok(envelope) = self.try_recv() {
            out.push(envelope);
        }
        out
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum StreamError {
    #[error("事件流已关闭")]
    Closed,
    #[error("暂无事件")]
    Empty,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids::SessionId;

    #[test]
    fn subscribers_receive_in_publish_order() {
        let bus = EventBus::new();
        let a = bus.subscribe();
        let b = bus.subscribe();

        for i in 0..3 {
            bus.publish(Event::Error {
                kind: ErrorKind::Internal,
                message: format!("e{i}"),
                retryable: false,
            });
        }

        let seqs_a: Vec<u64> = a.drain().iter().map(|e| e.seq).collect();
        let seqs_b: Vec<u64> = b.drain().iter().map(|e| e.seq).collect();
        assert_eq!(seqs_a, vec![1, 2, 3]);
        assert_eq!(seqs_a, seqs_b);
    }

    #[test]
    fn dropped_subscriber_does_not_break_others() {
        let bus = EventBus::new();
        let alive = bus.subscribe();
        {
            let dead = bus.subscribe();
            drop(dead);
        }
        bus.publish(Event::Error {
            kind: ErrorKind::Transport,
            message: "x".into(),
            retryable: true,
        });
        assert_eq!(alive.drain().len(), 1);
        assert_eq!(bus.subscriber_count(), 1);
    }

    #[test]
    fn stream_reports_empty_and_closed() {
        let bus = EventBus::new();
        let stream = bus.subscribe();
        assert_eq!(stream.try_recv(), Err(StreamError::Empty));
        drop(bus);
        assert_eq!(stream.recv(), Err(StreamError::Closed));
    }

    #[test]
    fn turn_event_carries_usage() {
        let session = SessionId::new();
        let id = MessageId::new(session, 1);
        let event = Event::TurnCompleted {
            id,
            usage: Usage {
                prompt_tokens: 100,
                completion_tokens: 50,
                cached_tokens: 80,
                reasoning_tokens: 0,
            },
            incomplete: false,
        };
        assert!(matches!(
            event,
            Event::TurnCompleted {
                incomplete: false,
                ..
            }
        ));
    }
}
