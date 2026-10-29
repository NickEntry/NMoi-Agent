//! 单元级块编辑的落库（0.15f / D188）。
//!
//! 与 0.14y 的 [`Storage::apply_block_edit`] 的四处差别：
//!
//! 1. **一次调用可以改一条消息上的几块**，而且和删行、写 undo 日志在同一个事务里；
//! 2. **正文改写落红**：写 `tooledit_message` 并把这一版切到 `edited`，原文留在 `origin_message`；
//! 3. **工具对成对删**：调用消息与结果消息一起消失（单独删一边 = 孤儿，下一次请求直接 400）；
//! 4. **每条改动留一条 undo 日志**（`settings(scope="undo")`，存 before/after 原文，可导出），
//!    并按会话计数封顶（[`MAX_OPS_PER_SESSION`]）。
//!
//! 纯逻辑（校验、成对、算 before/after）在 `nmoi_core::edit::plan_ops`，这里只负责"写"。
//! 分层的理由：core 不依赖 storage，而事务、版本表、设置表都在这边。

use nmoi_core::edit::{message_tokens, plan_ops, BlockOp, BlockSelector, PlannedOp};
use nmoi_core::ids::{MessageId, SessionId, UnitId};
use nmoi_core::settings::DOC_VERSION;
use rusqlite::params;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::repo::StorageError;
use crate::settings::Scope;
use crate::Storage;

/// 一个会话最多改几次（D184）。到顶了就停：模型无限改历史，用户最后会看不懂哪一版是真的。
pub const MAX_OPS_PER_SESSION: usize = 50;

/// 一条 undo 记录。
///
/// 存**原文**而不是哈希（用户选 3A）：界面上的"导出"要能拿到被改掉的那段字，
/// 只存哈希的话，这条日志除了证明"改过"什么也做不了。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UndoRecord {
    pub at: i64,
    pub session: String,
    pub message: String,
    pub unit: String,
    /// 中文块名（正文 / 思考块 / 工具参数 #N / 工具结果 #N）。
    pub block: String,
    pub before: String,
    /// 删除时是空串（`deleted = true`）。
    pub after: String,
    pub deleted: bool,
}

/// 一次单元级编辑的账本。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct UnitEditOutcome {
    /// 真的写进去的块数（不含"和原文一样"的那些）。
    pub written: usize,
    /// 删掉的消息行数（工具对 = 2）。
    pub removed: usize,
    /// 内容和现状一样、因此没写的块名。**要报出来**，否则"改了 3 处"和实际对不上。
    pub unchanged: Vec<String>,
    /// 写成功的块名，顺序与计划一致（界面提示行直接用）。
    pub blocks: Vec<String>,
}

impl Storage {
    /// 一次单元级编辑：改块 / 删块 / 落红 / 写 undo，全在一个事务里。
    ///
    /// 任何一条校验不过（单元未闭合、块重复、工具块单边删、覆盖率不够）都**整批不动**：
    /// 半批落库之后，界面上是一半新一半旧，而模型那边已经报过"改好了"。
    pub fn apply_unit_edits(
        &mut self,
        message_id: &MessageId,
        ops: &[BlockOp],
    ) -> Result<UnitEditOutcome, StorageError> {
        let (unit_text, session_text): (String, String) = self
            .conn
            .query_row(
                "SELECT unit_id, conv_id FROM messages WHERE id = ?1",
                [message_id.to_string()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|_| {
                StorageError::Inconsistent(format!("消息 {message_id} 不存在，没法编辑"))
            })?;
        let session = session_text
            .parse::<SessionId>()
            .map_err(|error| StorageError::Inconsistent(format!("会话 id 不合法：{error}")))?;
        let unit_id = unit_text
            .parse::<UnitId>()
            .map_err(|error| StorageError::Inconsistent(format!("单元 id 不合法：{error}")))?;

        // 上限在**动库之前**查：改到一半才发现超了，库里已经多了半批。
        let used = self.undo_count(session)?;
        if used + ops.len() > MAX_OPS_PER_SESSION {
            return Err(StorageError::Inconsistent(format!(
                "这个会话已经改过 {used} 处（上限 {MAX_OPS_PER_SESSION} 处）：再改就先让用户确认"
            )));
        }

        let unit = self.load_unit(&unit_id)?;
        let plan = plan_ops(&unit, message_id, ops)
            .map_err(|error| StorageError::Inconsistent(error.to_string()))?;

        let mut outcome = UnitEditOutcome {
            unchanged: plan.unchanged.clone(),
            ..Default::default()
        };
        // 全部是"和原文一样"：不算错误，但没有任何东西要写。
        if plan.ops.is_empty() && plan.remove_messages.is_empty() {
            return Ok(outcome);
        }

        let at = crate::validate::now_ms();
        let tx = self.conn.transaction()?;

        // 1) 按消息分组：同一条消息上的几块一次写完，避免同一行在一个事务里被写两遍。
        //    要删行的（工具对）不进这一组。
        let mut groups: Vec<(MessageId, Vec<&PlannedOp>)> = Vec::new();
        for op in &plan.ops {
            let deletes_row = op.after.is_none() && op.block.tool_index().is_some();
            if deletes_row {
                continue;
            }
            match groups.iter_mut().find(|(id, _)| id == &op.message) {
                Some((_, list)) => list.push(op),
                None => groups.push((op.message.clone(), vec![op])),
            }
        }

        for (id, list) in &groups {
            let mut message = unit
                .messages
                .iter()
                .find(|message| &message.id == id)
                .cloned()
                .ok_or_else(|| StorageError::Inconsistent(format!("消息 {id} 不在单元里")))?;
            let tokens_before = message.tokens;
            let mut red: Option<String> = None;

            for op in list {
                match (op.block, op.after.as_deref()) {
                    (BlockSelector::Output, Some(text)) => {
                        message.content = text.to_string();
                        if op.red {
                            red = Some(text.to_string());
                        }
                    }
                    (BlockSelector::Thinking, Some(text)) => {
                        message.reasoning = Some(text.to_string());
                    }
                    (BlockSelector::Thinking, None) => message.reasoning = None,
                    (BlockSelector::ToolCall { index }, Some(text)) => {
                        let call = message.tool_calls.get_mut(index).ok_or_else(|| {
                            StorageError::Inconsistent(format!(
                                "消息 {id} 没有第 {index} 个工具调用"
                            ))
                        })?;
                        call.arguments = text.to_string();
                    }
                    (BlockSelector::ToolResult { .. }, Some(text)) => {
                        message.content = text.to_string();
                    }
                    (_, None) => {
                        return Err(StorageError::Inconsistent(format!(
                            "「{}」是删除，不该走改列这条路",
                            op.label
                        )))
                    }
                }
            }
            message.revision = message.revision.saturating_add(1);
            message.tokens = message_tokens(&message);

            // 2) 落红：**先**按原文 seed 第 1 版，再写 `tooledit_message`，
            //    最后才更新 `messages.content`。顺序反了原文就没了（seed 读的就是 content）。
            if let Some(text) = &red {
                crate::versions::seed_first_version(&tx, &id.to_string())?;
                let (count, active): (i64, i64) = tx.query_row(
                    "SELECT version_count, version_active FROM messages WHERE id = ?1",
                    [id.to_string()],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )?;
                if count == 0 || active == 0 {
                    return Err(StorageError::Inconsistent(format!(
                        "消息 {id} 的版本账本没建起来，正文不能落红"
                    )));
                }
                tx.execute(
                    "UPDATE message_versions
                     SET tooledit_message = ?1, edited_by_tool = 1, active_view = 'edited'
                     WHERE message_id = ?2 AND version_no = ?3",
                    params![text, id.to_string(), active],
                )?;
            }

            let calls = serde_json::to_string(&message.tool_calls)
                .map_err(|error| StorageError::Inconsistent(error.to_string()))?;
            let changed = tx.execute(
                "UPDATE messages
                 SET content = ?1, reasoning = ?2, tokens = ?3, revision = ?4, tool_calls = ?5
                 WHERE id = ?6 AND conv_id = ?7",
                params![
                    message.content,
                    message.reasoning,
                    message.tokens,
                    message.revision,
                    calls,
                    id.to_string(),
                    session.to_string(),
                ],
            )?;
            if changed == 0 {
                return Err(StorageError::Inconsistent(format!(
                    "消息 {id} 不存在或不属于会话 {session}"
                )));
            }

            // 3) 审计（`edits` 表）与 undo 日志：一块一行。
            for op in list {
                tx.execute(
                    "INSERT INTO edits(id, conv_id, unit_id, message_id, block, before_hash,
                                       after_hash, tokens_before, tokens_after, revision, at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                    params![
                        Uuid::now_v7().to_string(),
                        session.to_string(),
                        unit_id.to_string(),
                        id.to_string(),
                        op.label,
                        op.before_hash,
                        op.after_hash,
                        tokens_before,
                        message.tokens,
                        message.revision,
                        at,
                    ],
                )?;
                write_undo(&tx, session, &unit_id, op, at)?;
                outcome.blocks.push(op.label.clone());
            }
            outcome.written += list.len();
        }

        // 4) 成对删：调用消息与结果消息一起删，版本行与审计行跟着清掉。
        //    原文还在 undo 日志里（`deleted = true`），所以这一刀不是"无声无息地没了"。
        for id in &plan.remove_messages {
            let id_text = id.to_string();
            tx.execute(
                "DELETE FROM message_versions WHERE message_id = ?1",
                [&id_text],
            )?;
            tx.execute("DELETE FROM edits WHERE message_id = ?1", [&id_text])?;
            outcome.removed += tx.execute(
                "DELETE FROM messages WHERE id = ?1 AND conv_id = ?2",
                params![id_text, session.to_string()],
            )?;
        }
        for op in plan
            .ops
            .iter()
            .filter(|op| op.after.is_none() && op.block.tool_index().is_some())
        {
            write_undo(&tx, session, &unit_id, op, at)?;
            outcome.blocks.push(op.label.clone());
        }

        tx.commit()?;
        Ok(outcome)
    }

    /// 这个会话已经记了多少条 undo（= 改过多少处）。每会话上限就靠它数。
    pub fn undo_count(&self, session: SessionId) -> Result<usize, StorageError> {
        let prefix = format!("{session}/");
        Ok(self
            .list_docs(Scope::Undo)?
            .into_iter()
            .filter(|(key, _)| key.starts_with(&prefix))
            .count())
    }

    /// 这个会话的 undo 日志，**最近的在前**。界面上的"导出"与"看看改了什么"都用它。
    pub fn undo_log(&self, session: SessionId) -> Result<Vec<(String, UndoRecord)>, StorageError> {
        let prefix = format!("{session}/");
        let mut out = Vec::new();
        for (key, doc) in self.list_docs(Scope::Undo)? {
            if !key.starts_with(&prefix) {
                continue;
            }
            match serde_json::from_str::<UndoRecord>(&doc.payload) {
                Ok(record) => out.push((key, record)),
                // 坏掉的一条不该让整页打不开：宁可少一条，也不把下载按钮变成报错。
                Err(_) => continue,
            }
        }
        Ok(out)
    }
}

/// 写一条 undo 日志（走事务里的 `tx`，不能回头用 `self.conn`：那会把事务卡死）。
fn write_undo(
    tx: &rusqlite::Transaction<'_>,
    session: SessionId,
    unit: &UnitId,
    op: &PlannedOp,
    at: i64,
) -> Result<(), StorageError> {
    let key = format!("{session}/{}", Uuid::now_v7());
    let record = UndoRecord {
        at,
        session: session.to_string(),
        message: op.message.to_string(),
        unit: unit.to_string(),
        block: op.label.clone(),
        before: op.before.clone(),
        after: op.after.clone().unwrap_or_default(),
        deleted: op.after.is_none(),
    };
    let payload = serde_json::to_string(&record)
        .map_err(|error| StorageError::Inconsistent(format!("undo 日志序列化失败: {error}")))?;
    tx.execute(
        "INSERT INTO settings (scope, doc_key, doc_version, payload, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(scope, doc_key) DO UPDATE SET
             doc_version = excluded.doc_version,
             payload = excluded.payload,
             updated_at = excluded.updated_at",
        params![Scope::Undo.as_str(), key, DOC_VERSION, payload, at],
    )?;
    Ok(())
}
