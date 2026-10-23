//! 启动收口（P10 / D45）：把"进程被杀留下的中间态"找出来。
//!
//! 为什么需要这个模块：安卓上划掉卡片就是清进程，任何时刻都可能被杀。被杀之后库里
//! 的状态是**合法的 SQLite 状态，但不是合法的一轮对话**：
//!
//! - 用户消息已经落库，助手的回复还没写（杀在流式中间）→ 会话以 `user` 结尾；
//! - 工具单元写了一半（`tool_call` 有、`tool_result` 没有）→ 未闭合单元。
//!
//! 这两种状态 `PRAGMA integrity_check` 一个字都不会说，L0–L3 也查不到——它们是"结构完好、
//! 语义缺失"。所以启动时单独扫一遍。
//!
//! 三条纪律：
//!
//! 1. **只报告，不猜**。没有 `tool_result` 就是没有，不会伪造一个"工具失败了"填进去；
//!    用户消息后面没有回复就是没有，不会自动补一条空的助手消息。
//! 2. **修由一个显式动作完成**：`abandon_open_unit` 丢弃那个半截单元（这是唯一能让库重新
//!    自洽的办法——结果已经丢了，留着只会让下一轮请求带着一对不闭合的调用出去）。
//! 3. **不动 `incomplete`**。那个字段是断流时由引擎写的（D43），收口只统计它，不改它。

use nmoi_core::ids::{SessionId, UnitId};
use nmoi_core::message::{Role, UnitKind};
use serde::{Deserialize, Serialize};

use crate::repo::StorageError;
use crate::Storage;

/// 一个"没等到助手回复"的会话。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PendingTurn {
    pub session: SessionId,
    /// 最后一条消息的 seq（就是那个"问完没答"的位置）。
    pub last_seq: u64,
    /// 最后一条消息的角色：`user`（助手从没开始）或 `tool`（工具结果之后断的）。
    pub last_role: Role,
    pub last_at: i64,
}

/// 一个未闭合的工具单元：`tool_calls` 有，对应的 `tool_result` 少了。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OpenUnit {
    pub session: SessionId,
    pub unit_id: UnitId,
    pub first_seq: u64,
    /// 没等到结果的调用 id 与工具名。
    pub pending_calls: Vec<(String, String)>,
    pub last_at: i64,
}

/// 收口报告。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReconcileReport {
    pub conversations: usize,
    pub units: usize,
    /// 以 user / tool 结尾、没有助手回复的会话。
    pub pending_turns: Vec<PendingTurn>,
    /// 未闭合的工具单元。
    pub open_units: Vec<OpenUnit>,
    /// 已经标了 `incomplete` 的助手消息条数（统计，不动）。
    pub incomplete_messages: usize,
}

impl ReconcileReport {
    /// 三种都空才算干净。
    pub fn is_clean(&self) -> bool {
        self.pending_turns.is_empty() && self.open_units.is_empty() && self.incomplete_messages == 0
    }

    /// 只有真正的"待处理"才算阻塞（`incomplete` 是历史事实，不需要处理）。
    pub fn needs_attention(&self) -> bool {
        !self.pending_turns.is_empty() || !self.open_units.is_empty()
    }

    /// 给人看的一句话。
    pub fn summary(&self) -> String {
        if !self.needs_attention() && self.incomplete_messages == 0 {
            return format!(
                "{} 个会话 / {} 个单元，没有半截状态",
                self.conversations, self.units
            );
        }
        let mut parts = Vec::new();
        if !self.pending_turns.is_empty() {
            parts.push(format!("{} 轮没等到回复", self.pending_turns.len()));
        }
        if !self.open_units.is_empty() {
            parts.push(format!("{} 个工具单元没闭合", self.open_units.len()));
        }
        if self.incomplete_messages > 0 {
            parts.push(format!("{} 条回复被标记为不完整", self.incomplete_messages));
        }
        parts.join("；")
    }
}

impl Storage {
    /// 扫一遍全库，找出被杀留下的中间态。**只读**，不改任何数据。
    ///
    /// 成本：每个会话一次 `load_units`。会话数与总单元数都小（单机应用），启动时扫一遍
    /// 是毫秒级；真到了几万条再改成一条 SQL 聚合。
    pub fn reconcile(&self) -> Result<ReconcileReport, StorageError> {
        let mut report = ReconcileReport::default();
        for session in self.conversation_ids()? {
            report.conversations += 1;
            let units = self.load_units(session)?;
            report.units += units.len();

            for unit in &units {
                for message in &unit.messages {
                    if message.incomplete {
                        report.incomplete_messages += 1;
                    }
                }
                if unit.kind == UnitKind::ToolPair && !unit.is_closed() {
                    let pending = unit.pending_calls();
                    let calls = unit
                        .messages
                        .iter()
                        .filter(|message| message.role == Role::Assistant)
                        .flat_map(|message| message.tool_calls.iter())
                        .filter(|call| pending.iter().any(|id| *id == call.id))
                        .map(|call| (call.id.clone(), call.name.clone()))
                        .collect();
                    report.open_units.push(OpenUnit {
                        session,
                        unit_id: unit.id.clone(),
                        first_seq: unit.first_message().id.seq(),
                        pending_calls: calls,
                        last_at: unit.last_message().created_at,
                    });
                }
            }

            if let Some(last) = units.last().map(|unit| unit.last_message()) {
                if last.role != Role::Assistant {
                    report.pending_turns.push(PendingTurn {
                        session,
                        last_seq: last.id.seq(),
                        last_role: last.role,
                        last_at: last.created_at,
                    });
                }
            }
        }
        // 顺序稳定（按 seq），不依赖 SQL 的返回顺序
        report.open_units.sort_by_key(|unit| unit.first_seq);
        report
            .pending_turns
            .sort_by_key(|turn| (turn.session.to_string(), turn.last_seq));
        Ok(report)
    }

    /// 丢弃一个未闭合的工具单元（用户显式选择"这一单元不要了"）。
    ///
    /// 为什么是删而不是补：`tool_result` 的内容已经丢了，补一个假结果会让模型
    /// 基于不存在的事实继续推理——那比缺一轮糟得多。
    pub fn abandon_open_unit(&mut self, unit_id: &UnitId) -> Result<usize, StorageError> {
        let tx = self.conn.transaction()?;
        let closed: i64 = tx
            .query_row(
                "SELECT unit_closed FROM messages WHERE unit_id = ?1 LIMIT 1",
                [unit_id.to_string()],
                |row| row.get(0),
            )
            .map_err(|error| match error {
                rusqlite::Error::QueryReturnedNoRows => {
                    StorageError::Inconsistent(format!("单元 {unit_id} 不存在"))
                }
                other => StorageError::Sqlite(other),
            })?;
        if closed != 0 {
            return Err(StorageError::Inconsistent(format!(
                "单元 {unit_id} 是闭合的，不该走「丢弃」这条路"
            )));
        }
        let removed = tx.execute(
            "DELETE FROM messages WHERE unit_id = ?1",
            [unit_id.to_string()],
        )?;
        tx.commit()?;
        Ok(removed)
    }

    /// 当前 `synchronous` 档位（测试与 UI 用）。`2` = FULL，`1` = NORMAL。
    pub fn synchronous_level(&self) -> Result<i64, StorageError> {
        Ok(self
            .conn
            .query_row("PRAGMA synchronous", [], |row| row.get(0))?)
    }

    /// 会话 ID 列表（收口与诊断用）。
    fn conversation_ids(&self) -> Result<Vec<SessionId>, StorageError> {
        let mut stmt = self
            .conn
            .prepare("SELECT id FROM conversations ORDER BY created_at, id")?;
        let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
        let mut sessions = Vec::new();
        for row in rows {
            let raw = row?;
            match raw.parse::<SessionId>() {
                Ok(session) => sessions.push(session),
                // 一个坏 id 不该让启动收口整体失败；交给 L3 去报
                Err(_) => continue,
            }
        }
        Ok(sessions)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nmoi_core::clock::LogicalClock;
    use nmoi_core::ids::MessageId;
    use nmoi_core::message::{AtomicUnit, OutgoingToolCall};
    use nmoi_core::source::SourceTag;

    fn storage() -> Storage {
        Storage::open_in_memory().unwrap()
    }

    fn turn(session: SessionId, seq: u64, role: Role, text: &str) -> AtomicUnit {
        AtomicUnit::turn(Message::text(
            MessageId::new(session, seq),
            role,
            SourceTag::History,
            text,
            10,
            0,
            LogicalClock::new(seq),
        ))
        .unwrap()
    }

    use nmoi_core::message::Message;

    #[test]
    fn clean_store_reports_nothing() {
        let mut store = storage();
        let session = store.create_conversation("干净会话", "device").unwrap();
        store
            .append_unit(session, &turn(session, 1, Role::User, "在吗"))
            .unwrap();
        store
            .append_unit(session, &turn(session, 2, Role::Assistant, "在"))
            .unwrap();

        let report = store.reconcile().unwrap();
        assert!(report.is_clean(), "{report:?}");
        assert_eq!(report.conversations, 1);
        assert_eq!(report.units, 2);
        assert!(report.summary().contains("没有半截状态"));
    }

    #[test]
    fn conversation_ending_with_user_message_is_reported() {
        let mut store = storage();
        let session = store
            .create_conversation("被杀在流式中间", "device")
            .unwrap();
        store
            .append_unit(session, &turn(session, 1, Role::User, "在吗"))
            .unwrap();
        store
            .append_unit(session, &turn(session, 2, Role::Assistant, "在"))
            .unwrap();
        // 第二轮只落了用户消息，助手回复还没写就被杀了
        store
            .append_unit(session, &turn(session, 3, Role::User, "帮我看下这个 bug"))
            .unwrap();

        let report = store.reconcile().unwrap();
        assert_eq!(report.pending_turns.len(), 1);
        let pending = &report.pending_turns[0];
        assert_eq!(pending.session, session);
        assert_eq!(pending.last_seq, 3);
        assert_eq!(pending.last_role, Role::User);
        assert!(report.summary().contains("1 轮没等到回复"));
    }

    #[test]
    fn open_tool_unit_is_reported_with_pending_calls() {
        let mut store = storage();
        let session = store.create_conversation("工具单元", "device").unwrap();
        store
            .append_unit(session, &turn(session, 1, Role::User, "读一下文件"))
            .unwrap();
        // 只有 tool_call，结果还没写
        let open = AtomicUnit::tool_pair(Message::tool_call_message(
            MessageId::new(session, 2),
            vec![
                OutgoingToolCall::new("c1", "read_file", "{}"),
                OutgoingToolCall::new("c2", "list_dir", "{}"),
            ],
            10,
            0,
            LogicalClock::new(2),
        ))
        .unwrap();
        store.append_unit(session, &open).unwrap();

        let report = store.reconcile().unwrap();
        assert_eq!(report.open_units.len(), 1);
        let unit = &report.open_units[0];
        assert_eq!(unit.first_seq, 2);
        assert_eq!(unit.pending_calls.len(), 2);
        assert_eq!(unit.pending_calls[0].1, "read_file");
        assert!(report.needs_attention());
    }

    #[test]
    fn abandoning_open_unit_makes_the_report_clean() {
        let mut store = storage();
        let session = store.create_conversation("丢弃半截单元", "device").unwrap();
        store
            .append_unit(session, &turn(session, 1, Role::User, "读一下"))
            .unwrap();
        let open = AtomicUnit::tool_pair(Message::tool_call_message(
            MessageId::new(session, 2),
            vec![OutgoingToolCall::new("c1", "read_file", "{}")],
            10,
            0,
            LogicalClock::new(2),
        ))
        .unwrap();
        let unit_id = open.id.clone();
        store.append_unit(session, &open).unwrap();

        assert_eq!(store.abandon_open_unit(&unit_id).unwrap(), 1);
        let report = store.reconcile().unwrap();
        assert!(report.open_units.is_empty());
        // 丢弃之后会话以 user 结尾，这是"没等到回复"，仍然要被报出来
        assert_eq!(report.pending_turns.len(), 1);
        assert_eq!(store.message_count(session).unwrap(), 1);
    }

    #[test]
    fn abandoning_a_closed_unit_is_refused() {
        let mut store = storage();
        let session = store.create_conversation("已闭合", "device").unwrap();
        let unit = turn(session, 1, Role::User, "在吗");
        let unit_id = unit.id.clone();
        store.append_unit(session, &unit).unwrap();
        let err = store.abandon_open_unit(&unit_id).unwrap_err();
        assert!(matches!(err, StorageError::Inconsistent(_)), "{err:?}");
    }

    #[test]
    fn durable_option_switches_synchronous_to_full() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("durable.db");

        let normal = match Storage::open(&path).unwrap() {
            crate::OpenOutcome::Ready(store) => store,
            crate::OpenOutcome::Blocked(blocked) => panic!("不该被拦：{blocked:?}"),
        };
        assert_eq!(normal.synchronous_level().unwrap(), 1, "默认 NORMAL");
        drop(normal);

        let durable = match Storage::open_with(&path, crate::OpenOptions { durable: true }).unwrap()
        {
            crate::OpenOutcome::Ready(store) => store,
            crate::OpenOutcome::Blocked(blocked) => panic!("不该被拦：{blocked:?}"),
        };
        assert_eq!(durable.synchronous_level().unwrap(), 2, "durable 要 FULL");
    }

    #[test]
    fn uncommitted_transaction_disappears_when_the_process_dies() {
        // 进程被杀的语义：未提交的事务当没发生过。
        // 这里用"丢掉连接不提交"来模拟——和 SIGKILL 在 SQLite 眼里是同一件事。
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("kill.db");
        let session;
        {
            let mut store = match Storage::open(&path).unwrap() {
                crate::OpenOutcome::Ready(store) => store,
                crate::OpenOutcome::Blocked(blocked) => panic!("不该被拦：{blocked:?}"),
            };
            session = store.create_conversation("被杀", "device").unwrap();
            store
                .append_unit(session, &turn(session, 1, Role::User, "已提交的一条"))
                .unwrap();

            let tx = store.conn.transaction().unwrap();
            tx.execute(
                "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role,
                     source, content, form, tokens, created_at, logical_clock)
                 VALUES ('half', ?1, 'u', 'turn', 1, 99, 'assistant', 'history', '半条', 'F1',
                     1, 0, 99)",
                [session.to_string()],
            )
            .unwrap();
            // 不 commit，直接丢连接
            drop(tx);
            drop(store);
        }

        let reopened = match Storage::open(&path).unwrap() {
            crate::OpenOutcome::Ready(store) => store,
            crate::OpenOutcome::Blocked(blocked) => panic!("不该被拦：{blocked:?}"),
        };
        assert_eq!(reopened.message_count(session).unwrap(), 1, "半条不该留下");
        let report = reopened.reconcile().unwrap();
        // 只有那一条已提交的用户消息 → 会话以 user 结尾 → 报"没等到回复"
        assert_eq!(report.pending_turns.len(), 1);
        assert!(report.open_units.is_empty());
    }
}
