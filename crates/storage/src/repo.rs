//! 仓储层：消息 / 原子单元 / 归档的读写。
//!
//! 写入纪律：
//! - 单元写库后**重新读出来比对**，不一致就回滚——防止"写进去的是 A，读出来是 B"；
//! - 归档按 ref 幂等，重复写入不覆盖；
//! - 每次 append 检查逻辑时钟单调（§5.1、R7）。

use rusqlite::{params, Connection, OptionalExtension};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use nmoi_core::archive::{ArchiveEntry, ArchiveStat, ArchiveStore, Origin};
use nmoi_core::clock::LogicalClock;
use nmoi_core::compress::CompressionOutcome;
use nmoi_core::divider::{self, ContextWindow, Divider, DividerId, DividerKind};
use nmoi_core::edit::EditOutcome;
use nmoi_core::edit::{apply_edit, BlockSelector, EditRequest};
use nmoi_core::ids::{ArchiveRef, MessageId, SessionId, UnitId};
use nmoi_core::message::{
    AtomicUnit, ContentForm, Message, OutgoingToolCall, Role, UnitError, UnitKind,
};
use nmoi_core::source::SourceTag;

use crate::Storage;

#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("SQLite 错误: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("单元结构非法: {0}")]
    Unit(#[from] UnitError),
    #[error("归档错误: {0}")]
    Archive(#[from] nmoi_core::archive::ArchiveError),
    #[error("数据不一致: {0}")]
    Inconsistent(String),
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
}

pub fn sha256_hex(content: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(content.as_bytes());
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// 列表页里的一条会话（D61）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationSummary {
    pub id: SessionId,
    pub title: String,
    pub created_at: i64,
    /// 每次 `append_unit` 都会刷新，所以它能当"最近动过"用。
    pub updated_at: i64,
    pub messages: usize,
    /// 消息表里 `tokens` 的和（粗估），UI 拿来画一行"这轮吃掉多少上下文"。
    pub tokens: u64,
    /// 带 `incomplete` 的消息条数——列表上要能一眼看出"这个会话上次没说完"。
    pub incomplete: usize,
    pub last_seq: Option<u64>,
    pub last_role: Option<String>,
    /// 最后一条消息的预览，**已截断**；`last_text_truncated` 说明它不完整。
    pub last_text: Option<String>,
    pub last_text_truncated: bool,
    /// 最近一次用的模型（D101）。`None` = 这个会话还没跑过，不是"空模型名"。
    pub model: Option<String>,
}

/// 一页会话。`has_more` 比总数便宜，也正好是 UI 唯一用得上的东西。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationPage {
    pub items: Vec<ConversationSummary>,
    pub has_more: bool,
}

/// 预览截断：200 字符（不是字节——按字节切会把中文劈成半个字）。
fn preview_of(text: Option<String>) -> (Option<String>, bool) {
    const PREVIEW_CHARS: usize = 200;
    match text {
        None => (None, false),
        Some(text) => {
            let mut preview: String = text.chars().take(PREVIEW_CHARS).collect();
            let truncated = text.chars().count() > PREVIEW_CHARS;
            if truncated {
                preview.push('…');
            }
            (Some(preview), truncated)
        }
    }
}

impl Storage {
    /// 新建会话。`device` 写进 `device_origin`，同步时用来判断来源设备。
    pub fn create_conversation(
        &mut self,
        title: &str,
        device: &str,
    ) -> Result<SessionId, StorageError> {
        let session = SessionId::new();
        let now = crate::validate::now_ms();
        self.conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES (?1, ?2, ?3, ?3, ?4, ?5)",
            params![
                session.to_string(),
                title,
                now,
                crate::migrate::SCHEMA_VERSION,
                device
            ],
        )?;
        Ok(session)
    }

    /// 这个会话最近一次用的模型（D101）。`None` = 还没跑过一轮。
    ///
    /// 与"模型叫空串"分开：面板上"未知"和"用了一个没名字的模型"不该显示成一个样子。
    pub fn conversation_model(&self, session: SessionId) -> Result<Option<String>, StorageError> {
        let found: Option<Option<String>> = self
            .conn
            .query_row(
                "SELECT model FROM conversations WHERE id = ?1",
                [session.to_string()],
                |row| row.get(0),
            )
            .optional()?;
        match found {
            Some(model) => Ok(model),
            None => Err(StorageError::Inconsistent(format!("会话 {session} 不存在"))),
        }
    }

    /// 记下"这个会话现在用哪个模型"。
    ///
    /// 不改 `updated_at`：换模型不是会话活动，列表不该因为它跳到最上面。
    pub fn set_conversation_model(
        &self,
        session: SessionId,
        model: &str,
    ) -> Result<(), StorageError> {
        let changed = self.conn.execute(
            "UPDATE conversations SET model = ?1 WHERE id = ?2",
            params![model, session.to_string()],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!("会话 {session} 不存在")));
        }
        Ok(())
    }

    /// 会话列表（P6.2 / D61）：UI 第一屏要的就是这一页。
    ///
    /// 排序 `updated_at DESC, id DESC`——第二键让同一毫秒建的两个会话也有**确定**顺序
    /// （确定 ≠ 等于创建顺序：UUIDv7 的毫秒内部分是随机的）。
    /// 分页给 `has_more` 而不是总数：多取一行就够判断"还能不能翻"，不用为列表跑一次
    /// 全表 `COUNT(*)`。
    pub fn list_conversations(
        &self,
        limit: usize,
        offset: usize,
    ) -> Result<ConversationPage, StorageError> {
        let want = limit.max(1);
        let mut stmt = self.conn.prepare(
            "SELECT c.id, c.title, c.created_at, c.updated_at,
                    (SELECT COUNT(*) FROM messages WHERE conv_id = c.id) AS messages,
                    (SELECT COALESCE(SUM(tokens), 0) FROM messages WHERE conv_id = c.id) AS tokens,
                    (SELECT COUNT(*) FROM messages WHERE conv_id = c.id AND incomplete = 1) AS incomplete,
                    (SELECT seq FROM messages WHERE conv_id = c.id ORDER BY seq DESC LIMIT 1) AS last_seq,
                    (SELECT role FROM messages WHERE conv_id = c.id ORDER BY seq DESC LIMIT 1) AS last_role,
                    (SELECT content FROM messages WHERE conv_id = c.id ORDER BY seq DESC LIMIT 1) AS last_text,
                    c.model
             FROM conversations c
             ORDER BY c.updated_at DESC, c.id DESC
             LIMIT ?1 OFFSET ?2",
        )?;
        let rows = stmt.query_map(params![(want + 1) as i64, offset as i64], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, i64>(4)?,
                row.get::<_, i64>(5)?,
                row.get::<_, i64>(6)?,
                row.get::<_, Option<i64>>(7)?,
                row.get::<_, Option<String>>(8)?,
                row.get::<_, Option<String>>(9)?,
                row.get::<_, Option<String>>(10)?,
            ))
        })?;

        let mut items = Vec::new();
        for row in rows {
            let (
                id,
                title,
                created_at,
                updated_at,
                messages,
                tokens,
                incomplete,
                last_seq,
                last_role,
                last_text,
                model,
            ) = row?;
            let session = id.parse::<SessionId>().map_err(|error| {
                StorageError::Inconsistent(format!("会话 {id} id 非法：{error}"))
            })?;
            let (preview, truncated) = preview_of(last_text);
            items.push(ConversationSummary {
                id: session,
                title,
                created_at,
                updated_at,
                messages: messages as usize,
                tokens: tokens.max(0) as u64,
                incomplete: incomplete as usize,
                last_seq: last_seq.map(|seq| seq.max(0) as u64),
                last_role,
                last_text: preview,
                last_text_truncated: truncated,
                model,
            });
        }

        let has_more = items.len() > want;
        items.truncate(want);
        Ok(ConversationPage { items, has_more })
    }

    pub fn conversation_exists(&self, session: SessionId) -> Result<bool, StorageError> {
        let found: Option<i64> = self
            .conn
            .query_row(
                "SELECT 1 FROM conversations WHERE id = ?1",
                [session.to_string()],
                |row| row.get(0),
            )
            .optional()?;
        Ok(found.is_some())
    }

    /// 追加（或补全）一个原子单元。
    pub fn append_unit(
        &mut self,
        session: SessionId,
        unit: &AtomicUnit,
    ) -> Result<(), StorageError> {
        unit.validate()?;
        if unit.id.session() != session {
            return Err(StorageError::Inconsistent(format!(
                "单元 {} 不属于会话 {}",
                unit.id, session
            )));
        }

        let tx = self.conn.transaction()?;
        if !conversation_exists_in(&tx, session)? {
            return Err(StorageError::Inconsistent(format!("会话 {session} 不存在")));
        }

        for message in &unit.messages {
            let calls = serde_json::to_string(&message.tool_calls)
                .map_err(|e| StorageError::Inconsistent(e.to_string()))?;
            tx.execute(
                "INSERT INTO messages(
                     id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                     content, reasoning, form, tokens, created_at, logical_clock, archived_ref,
                     protected, pinned, locked, revision, tool_calls, tool_call_id, incomplete)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22)
                 ON CONFLICT(id) DO UPDATE SET
                     content = excluded.content,
                     reasoning = excluded.reasoning,
                     form = excluded.form,
                     tokens = excluded.tokens,
                     logical_clock = excluded.logical_clock,
                     archived_ref = excluded.archived_ref,
                     protected = excluded.protected,
                     pinned = excluded.pinned,
                     locked = excluded.locked,
                     revision = excluded.revision,
                     tool_calls = excluded.tool_calls,
                     tool_call_id = excluded.tool_call_id,
                     incomplete = excluded.incomplete,
                     unit_closed = excluded.unit_closed",
                params![
                    message.id.to_string(),
                    session.to_string(),
                    unit.id.to_string(),
                    unit.kind.as_str(),
                    if unit.is_closed() { 1 } else { 0 },
                    message.id.seq() as i64,
                    message.role.as_str(),
                    message.source.as_str(),
                    message.content,
                    message.reasoning,
                    message.form.to_string(),
                    message.tokens,
                    message.created_at,
                    message.logical_clock.value() as i64,
                    message.archived_ref.as_ref().map(|r| r.to_string()),
                    if message.protected { 1 } else { 0 },
                    if message.pinned { 1 } else { 0 },
                    if message.locked { 1 } else { 0 },
                    message.revision,
                    calls,
                    message.tool_call_id,
                    if message.incomplete { 1 } else { 0 },
                ],
            )?;
        }

        // 同一单元的所有行统一闭合状态。
        tx.execute(
            "UPDATE messages SET unit_closed = ?1 WHERE unit_id = ?2",
            params![if unit.is_closed() { 1 } else { 0 }, unit.id.to_string()],
        )?;
        tx.execute(
            "UPDATE conversations SET updated_at = ?1 WHERE id = ?2",
            params![crate::validate::now_ms(), session.to_string()],
        )?;

        // 写后读回比对：内容必须与调用方给的一致。
        let reloaded = load_unit_in(&tx, &unit.id)?;
        if &reloaded != unit {
            return Err(StorageError::Inconsistent(format!(
                "单元 {} 写入后读回不一致",
                unit.id
            )));
        }
        tx.commit()?;
        Ok(())
    }

    pub fn load_units(&self, session: SessionId) -> Result<Vec<AtomicUnit>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, unit_id, unit_kind, unit_closed, seq, role, source, content, reasoning,
                    form, tokens, created_at, logical_clock, archived_ref, protected, pinned,
                    locked, revision, tool_calls, tool_call_id, incomplete
             FROM messages WHERE conv_id = ?1 ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map([session.to_string()], row_to_message)?;
        let mut units: Vec<AtomicUnit> = Vec::new();
        for row in rows {
            let message = row?;
            match units.last_mut() {
                Some(unit) if unit.id == message.unit_id => unit.messages.push(message),
                _ => {
                    let kind = unit_kind_for(&self.conn, &message.unit_id)?;
                    let unit = AtomicUnit {
                        id: message.unit_id.clone(),
                        kind,
                        created_at: message.created_at,
                        logical_clock: message.logical_clock,
                        messages: vec![message],
                    };
                    units.push(unit);
                }
            }
        }
        for unit in &units {
            unit.validate()?;
        }
        Ok(units)
    }

    pub fn load_unit(&self, unit_id: &UnitId) -> Result<AtomicUnit, StorageError> {
        load_unit_in(&self.conn, unit_id)
    }

    pub fn message_count(&self, session: SessionId) -> Result<usize, StorageError> {
        let count: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE conv_id = ?1",
            [session.to_string()],
            |row| row.get(0),
        )?;
        Ok(count as usize)
    }

    /// 归档写入。按 ref 幂等；同一 ref 的内容若不同（哈希碰撞或被篡改）直接报错。
    pub fn put_archive(
        &mut self,
        session: SessionId,
        entry: ArchiveEntry,
    ) -> Result<ArchiveRef, StorageError> {
        if entry.origin.session != session {
            return Err(StorageError::Inconsistent(format!(
                "归档条目 {} 的来源会话 {} 与目标会话 {} 不一致",
                entry.rf, entry.origin.session, session
            )));
        }
        match self.fetch_archive(&entry.rf) {
            Ok(found) => {
                if found.content != entry.content {
                    return Err(StorageError::Inconsistent(format!(
                        "归档 {} 已存在且内容不同，拒绝覆盖",
                        entry.rf
                    )));
                }
                return Ok(entry.rf);
            }
            Err(StorageError::Archive(nmoi_core::archive::ArchiveError::NotFound { .. })) => {}
            Err(err) => return Err(err),
        }

        self.conn.execute(
            "INSERT INTO archive(ref, conv_id, content, tokens, source, descriptor, protected,
                                  origin_session, origin_message, origin_turn, content_hash,
                                  created_at, note)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            params![
                entry.rf.to_string(),
                session.to_string(),
                entry.content,
                entry.tokens,
                entry.source.as_str(),
                entry.descriptor,
                if entry.protected { 1 } else { 0 },
                entry.origin.session.to_string(),
                entry.origin.message.to_string(),
                entry.origin.turn,
                entry.content_hash,
                entry.created_at,
                entry.note,
            ],
        )?;
        Ok(entry.rf)
    }

    /// 确定性取回。不做模糊匹配。
    pub fn fetch_archive(&self, reference: &ArchiveRef) -> Result<ArchiveEntry, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT ref, content, tokens, source, descriptor, protected, origin_session,
                    origin_message, origin_turn, content_hash, created_at, note
             FROM archive WHERE ref = ?1",
        )?;
        let mut rows = stmt.query([reference.to_string()])?;
        match rows.next()? {
            Some(row) => Ok(row_to_archive(row)?),
            None => Err(nmoi_core::archive::ArchiveError::NotFound {
                reference: reference.clone(),
            }
            .into()),
        }
    }

    pub fn search_archive(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<ArchiveEntry>, StorageError> {
        let pattern = format!("%{}%", escape_like(query));
        let mut stmt = self.conn.prepare(
            "SELECT ref, content, tokens, source, descriptor, protected, origin_session,
                    origin_message, origin_turn, content_hash, created_at, note
             FROM archive
             WHERE descriptor LIKE ?1 ESCAPE '\\' OR content LIKE ?1 ESCAPE '\\'
             ORDER BY created_at DESC, ref ASC
             LIMIT ?2",
        )?;
        let rows = stmt.query_map(params![pattern, limit as i64], row_to_archive)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn archive_stat(&self) -> Result<ArchiveStat, StorageError> {
        let (entries, total): (i64, i64) = self.conn.query_row(
            "SELECT COUNT(*), COALESCE(SUM(tokens), 0) FROM archive",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let largest = self
            .conn
            .query_row(
                "SELECT ref, tokens FROM archive ORDER BY tokens DESC, ref ASC LIMIT 1",
                [],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
            )
            .optional()?;
        let (largest_ref, largest_tokens) = match largest {
            Some((rf, tokens)) => (
                Some(
                    ArchiveRef::parse(&rf)
                        .map_err(|e| StorageError::Inconsistent(e.to_string()))?,
                ),
                tokens as u32,
            ),
            None => (None, 0),
        };
        Ok(ArchiveStat {
            entries: entries as usize,
            total_tokens: total as u64,
            largest_ref,
            largest_tokens,
        })
    }

    pub fn archive_len(&self) -> Result<usize, StorageError> {
        let count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM archive", [], |row| row.get(0))?;
        Ok(count as usize)
    }

    pub fn log_event(
        &mut self,
        session: SessionId,
        kind: &str,
        payload: &[u8],
    ) -> Result<(), StorageError> {
        self.conn.execute(
            "INSERT INTO events(conv_id, kind, payload, at) VALUES (?1, ?2, ?3, ?4)",
            params![
                session.to_string(),
                kind,
                payload,
                crate::validate::now_ms()
            ],
        )?;
        Ok(())
    }

    /// 保存一次块级编辑：更新消息行 + 写审计记录，同一事务。
    pub fn save_message_edit(
        &mut self,
        session: SessionId,
        unit: &AtomicUnit,
        outcome: &EditOutcome,
    ) -> Result<(), StorageError> {
        let message = unit
            .messages
            .iter()
            .find(|message| message.id == outcome.message_id)
            .ok_or_else(|| StorageError::Inconsistent("编辑后的消息不在单元里".into()))?;
        let calls = serde_json::to_string(&message.tool_calls)
            .map_err(|e| StorageError::Inconsistent(e.to_string()))?;

        let tx = self.conn.transaction()?;
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
                message.id.to_string(),
                session.to_string(),
            ],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!(
                "消息 {} 不存在或不属于会话 {}",
                message.id, session
            )));
        }
        tx.execute(
            "INSERT INTO edits(id, conv_id, unit_id, message_id, block, before_hash, after_hash,
                               tokens_before, tokens_after, revision, at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                Uuid::now_v7().to_string(),
                session.to_string(),
                unit.id.to_string(),
                message.id.to_string(),
                outcome.block,
                outcome.before_hash,
                outcome.after_hash,
                outcome.tokens_before,
                outcome.tokens_after,
                outcome.revision,
                crate::validate::now_ms(),
            ],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// 持久化用户锁定状态。锁定不经 `apply_edit`，所以不写 edits 表。
    pub fn set_message_locked(
        &mut self,
        message_id: &MessageId,
        locked: bool,
    ) -> Result<(), StorageError> {
        let changed = self.conn.execute(
            "UPDATE messages SET locked = ?1 WHERE id = ?2",
            params![if locked { 1 } else { 0 }, message_id.to_string()],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message_id} 不存在"
            )));
        }
        Ok(())
    }

    /// 块级编辑（D158）：改某条消息的**正文 / 思考 / 第 N 个工具调用的参数**。
    ///
    /// 为什么不用"整段正文"这一条路把它做完：三块存在不同的列里（`content` /
    /// `reasoning` / `tool_calls`），"要改哪一块"必须由调用方说清。选错了不是报错，
    /// 是**改到别的地方去**——用户在界面上点的是思考卡，结果正文变了，最难查。
    ///
    /// 写回走 [`Storage::save_message_edit`]：那一行与 `edits` 审计表在同一个事务里，
    /// 所以"改了但没记账"这种半截状态不会出现。
    ///
    /// **工具调用只允许改参数**：名字与 `call_id` 不在 `apply_edit` 的射程里——
    /// 名字决定了下一轮怎么解释这一块，`call_id` 决定它跟哪条结果配对。
    /// 让编辑改这两样，等于让一条"历史"变成另一条历史。
    pub fn apply_block_edit(
        &mut self,
        message_id: &MessageId,
        block: BlockSelector,
        content: &str,
    ) -> Result<EditOutcome, StorageError> {
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

        let mut unit = self.load_unit(&unit_id)?;
        let request = EditRequest::new(unit_id, block, content).on_message(message_id.clone());
        let outcome = apply_edit(&mut unit, &request)
            .map_err(|error| StorageError::Inconsistent(error.to_string()))?;
        self.save_message_edit(session, &unit, &outcome)?;
        Ok(outcome)
    }

    /// 某个会话的编辑次数（审计面板与测试用）。
    pub fn edit_count(&self, session: SessionId) -> Result<usize, StorageError> {
        let count: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM edits WHERE conv_id = ?1",
            [session.to_string()],
            |row| row.get(0),
        )?;
        Ok(count as usize)
    }

    /// 一次压缩的落库：归档条目 + 摘要 + 分界线，**一个事务**（D45）。
    ///
    /// 为什么必须一个事务：这三样单独写会留下两种残局——
    /// 归档写了、线没插（下次启动看起来没压缩过，但归档已经占着 ref）；
    /// 线插了、摘要没落（窗口里凭空少一段，原文还有，但用户看到的是"内容丢了"）。
    /// 进程可能在任意两条 SQL 之间被杀，所以在 SQLite 里只有"全做"这一种安全答案。
    pub fn commit_compression(
        &mut self,
        session: SessionId,
        outcome: &CompressionOutcome,
    ) -> Result<(), StorageError> {
        if outcome.divider.session != session {
            return Err(StorageError::Inconsistent(format!(
                "分界线属于 {}，传入的是 {session}",
                outcome.divider.session
            )));
        }
        let tx = self.conn.transaction()?;
        for entry in outcome
            .entries
            .iter()
            .chain(std::iter::once(&outcome.summary_entry))
        {
            if entry.origin.session != session {
                return Err(StorageError::Inconsistent(format!(
                    "归档条目 {} 的来源会话与目标会话不一致",
                    entry.rf
                )));
            }
            // 幂等：同一 ref 已有内容就跳过，内容不同则是真冲突
            let existing: Option<(String, String)> = tx
                .query_row(
                    "SELECT content, content_hash FROM archive WHERE ref = ?1",
                    [entry.rf.to_string()],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .optional()?;
            if let Some((content, hash)) = existing {
                if content != entry.content || hash != entry.content_hash {
                    return Err(StorageError::Inconsistent(format!(
                        "归档 {} 已存在且内容不同，拒绝覆盖",
                        entry.rf
                    )));
                }
                continue;
            }
            tx.execute(
                "INSERT INTO archive(ref, conv_id, content, tokens, source, descriptor, protected,
                                      origin_session, origin_message, origin_turn, content_hash,
                                      created_at, note)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
                params![
                    entry.rf.to_string(),
                    session.to_string(),
                    entry.content,
                    entry.tokens,
                    entry.source.as_str(),
                    entry.descriptor,
                    if entry.protected { 1 } else { 0 },
                    entry.origin.session.to_string(),
                    entry.origin.message.to_string(),
                    entry.origin.turn,
                    entry.content_hash,
                    entry.created_at,
                    entry.note,
                ],
            )?;
        }
        // OR IGNORE：同一个 outcome 重放（崩溃后重试）必须是幂等的，
        // 分界线 id 是 UUIDv7、每次压缩唯一，重复出现只可能是同一次提交。
        tx.execute(
            "INSERT OR IGNORE INTO dividers(id, conv_id, after_seq, kind, note, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                outcome.divider.id.as_str(),
                session.to_string(),
                outcome.divider.after_seq as i64,
                outcome.divider.kind.as_str(),
                outcome.divider.note,
                outcome.divider.created_at,
            ],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn insert_divider(&mut self, divider: &Divider) -> Result<(), StorageError> {
        self.conn.execute(
            "INSERT INTO dividers(id, conv_id, after_seq, kind, note, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                divider.id.as_str(),
                divider.session.to_string(),
                divider.after_seq as i64,
                divider.kind.as_str(),
                divider.note,
                divider.created_at,
            ],
        )?;
        Ok(())
    }

    /// 删除分界线。返回是否真的删掉了一条。
    pub fn delete_divider(&mut self, id: &DividerId) -> Result<bool, StorageError> {
        let changed = self
            .conn
            .execute("DELETE FROM dividers WHERE id = ?1", [id.as_str()])?;
        Ok(changed > 0)
    }

    pub fn load_dividers(&self, session: SessionId) -> Result<Vec<Divider>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, after_seq, kind, note, created_at FROM dividers
             WHERE conv_id = ?1 ORDER BY after_seq ASC, created_at ASC",
        )?;
        let rows = stmt.query_map([session.to_string()], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, i64>(4)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (id, after_seq, kind, note, created_at) = row?;
            let kind = DividerKind::parse(&kind)
                .ok_or_else(|| StorageError::Inconsistent(format!("未知分界线类型: {kind}")))?;
            out.push(Divider {
                id: DividerId::parse(id),
                session,
                after_seq: after_seq as u64,
                kind,
                note,
                created_at,
            });
        }
        Ok(out)
    }

    /// 当前窗口（D149）：最新一条分界线**以下**的单元，加上那条线压出来的摘要。
    ///
    /// 摘要不在 `messages` 表里，所以它得从线的 `note` 首段拿 `ArchiveRef`，再到 `archive` 读。
    /// **读不到就报错**，不返回"没有摘要"：那会让调用方以为这个会话没压过，
    /// 然后把已经压掉的原文整段当历史发出去——用户看到的是"它突然失忆，还把我拉回旧话题"。
    pub fn load_window(&self, session: SessionId) -> Result<ContextWindow, StorageError> {
        let units = self.load_units(session)?;
        let dividers = self.load_dividers(session)?;
        let Some((divider, start)) = divider::effective_divider(&units, &dividers) else {
            return Ok(ContextWindow::of_all(units));
        };
        let summary = match divider.summary_ref() {
            None => None,
            Some(reference) => {
                let content: Option<String> = self
                    .conn
                    .query_row(
                        "SELECT content FROM archive WHERE ref = ?1 AND conv_id = ?2",
                        params![reference.as_str(), session.to_string()],
                        |row| row.get(0),
                    )
                    .optional()?;
                Some(content.ok_or_else(|| {
                    StorageError::Inconsistent(format!(
                        "压缩线 {} 指向的摘要 {reference} 不在归档里",
                        divider.id
                    ))
                })?)
            }
        };
        Ok(ContextWindow {
            divider: Some(divider.clone()),
            summary,
            units: units[start..].to_vec(),
        })
    }
}

/// 让 `ArchiveStore` 也能直接在存储层用。
impl ArchiveStore for Storage {
    fn put(&mut self, entry: ArchiveEntry) -> Result<ArchiveRef, nmoi_core::archive::ArchiveError> {
        let session = entry.origin.session;
        Storage::put_archive(self, session, entry).map_err(|e| match e {
            StorageError::Archive(err) => err,
            other => nmoi_core::archive::ArchiveError::Backend(other.to_string()),
        })
    }

    fn fetch(
        &self,
        reference: &ArchiveRef,
    ) -> Result<ArchiveEntry, nmoi_core::archive::ArchiveError> {
        Storage::fetch_archive(self, reference).map_err(|e| match e {
            StorageError::Archive(err) => err,
            other => nmoi_core::archive::ArchiveError::Backend(other.to_string()),
        })
    }

    fn search(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<ArchiveEntry>, nmoi_core::archive::ArchiveError> {
        Storage::search_archive(self, query, limit).map_err(|e| match e {
            StorageError::Archive(err) => err,
            other => nmoi_core::archive::ArchiveError::Backend(other.to_string()),
        })
    }

    fn stat(&self) -> Result<ArchiveStat, nmoi_core::archive::ArchiveError> {
        Storage::archive_stat(self).map_err(|e| match e {
            StorageError::Archive(err) => err,
            other => nmoi_core::archive::ArchiveError::Backend(other.to_string()),
        })
    }
}

fn conversation_exists_in(conn: &Connection, session: SessionId) -> Result<bool, rusqlite::Error> {
    let found: Option<i64> = conn
        .query_row(
            "SELECT 1 FROM conversations WHERE id = ?1",
            [session.to_string()],
            |row| row.get(0),
        )
        .optional()?;
    Ok(found.is_some())
}

fn unit_kind_for(conn: &Connection, unit_id: &UnitId) -> Result<UnitKind, StorageError> {
    let kind: String = conn.query_row(
        "SELECT unit_kind FROM messages WHERE unit_id = ?1 LIMIT 1",
        [unit_id.to_string()],
        |row| row.get(0),
    )?;
    UnitKind::parse(&kind).map_err(StorageError::Unit)
}

fn row_to_message(row: &rusqlite::Row<'_>) -> Result<Message, rusqlite::Error> {
    let id_text: String = row.get(0)?;
    let unit_text: String = row.get(1)?;
    let role_text: String = row.get(5)?;
    let source_text: String = row.get(6)?;
    let form_text: String = row.get(9)?;
    let ref_text: Option<String> = row.get(13)?;
    let calls_text: String = row.get(18)?;

    let parse_err = |what: &str, value: String| {
        rusqlite::Error::FromSqlConversionFailure(
            0,
            rusqlite::types::Type::Text,
            Box::new(StorageError::Inconsistent(format!(
                "{what} 解析失败: {value}"
            ))),
        )
    };

    let id = MessageId::from_str_value(&id_text).map_err(|_| parse_err("message_id", id_text))?;
    let unit_id =
        UnitId::from_str_value(&unit_text).map_err(|_| parse_err("unit_id", unit_text))?;
    let role = role_text
        .parse::<Role>()
        .map_err(|_| parse_err("role", role_text))?;
    let source = source_text
        .parse::<SourceTag>()
        .map_err(|_| parse_err("source", source_text))?;
    let form = form_text
        .parse::<ContentForm>()
        .map_err(|_| parse_err("form", form_text))?;
    let archived_ref = match ref_text {
        Some(text) => Some(ArchiveRef::parse(&text).map_err(|_| parse_err("archive_ref", text))?),
        None => None,
    };
    let tool_calls: Vec<OutgoingToolCall> =
        serde_json::from_str(&calls_text).map_err(|_| parse_err("tool_calls", calls_text))?;

    Ok(Message {
        id,
        unit_id,
        role,
        source,
        content: row.get(7)?,
        reasoning: row.get(8)?,
        form,
        tokens: row.get::<_, i64>(10)? as u32,
        created_at: row.get(11)?,
        logical_clock: LogicalClock::new(row.get::<_, i64>(12)? as u64),
        archived_ref,
        protected: row.get::<_, i64>(14)? != 0,
        pinned: row.get::<_, i64>(15)? != 0,
        locked: row.get::<_, i64>(16)? != 0,
        revision: row.get::<_, i64>(17)? as u32,
        tool_calls,
        tool_call_id: row.get(19)?,
        incomplete: row.get::<_, i64>(20)? != 0,
    })
}

fn row_to_archive(row: &rusqlite::Row<'_>) -> Result<ArchiveEntry, rusqlite::Error> {
    let ref_text: String = row.get(0)?;
    let source_text: String = row.get(3)?;
    let origin_session: String = row.get(6)?;
    let origin_message: String = row.get(7)?;

    let parse_err = |what: &str, value: String| {
        rusqlite::Error::FromSqlConversionFailure(
            0,
            rusqlite::types::Type::Text,
            Box::new(StorageError::Inconsistent(format!(
                "{what} 解析失败: {value}"
            ))),
        )
    };

    Ok(ArchiveEntry {
        rf: ArchiveRef::parse(&ref_text).map_err(|_| parse_err("archive_ref", ref_text))?,
        content: row.get(1)?,
        tokens: row.get::<_, i64>(2)? as u32,
        source: source_text
            .parse::<SourceTag>()
            .map_err(|_| parse_err("source", source_text))?,
        descriptor: row.get(4)?,
        protected: row.get::<_, i64>(5)? != 0,
        origin: Origin {
            session: SessionId::from_str_value(&origin_session)
                .map_err(|_| parse_err("origin_session", origin_session))?,
            message: MessageId::from_str_value(&origin_message)
                .map_err(|_| parse_err("origin_message", origin_message))?,
            turn: row.get::<_, i64>(8)? as u32,
        },
        content_hash: row.get(9)?,
        created_at: row.get(10)?,
        note: row.get(11)?,
    })
}

fn load_unit_in(conn: &Connection, unit_id: &UnitId) -> Result<AtomicUnit, StorageError> {
    let mut stmt = conn.prepare(
        "SELECT id, unit_id, unit_kind, unit_closed, seq, role, source, content, reasoning,
                form, tokens, created_at, logical_clock, archived_ref, protected, pinned,
                locked, revision, tool_calls, tool_call_id, incomplete
         FROM messages WHERE unit_id = ?1 ORDER BY seq ASC",
    )?;
    let rows = stmt.query_map([unit_id.to_string()], row_to_message)?;
    let mut messages = Vec::new();
    for row in rows {
        messages.push(row?);
    }
    if messages.is_empty() {
        return Err(StorageError::Inconsistent(format!("单元 {unit_id} 不存在")));
    }
    let kind = unit_kind_for(conn, unit_id)?;
    let unit = AtomicUnit {
        id: unit_id.clone(),
        kind,
        created_at: messages[0].created_at,
        logical_clock: messages[0].logical_clock,
        messages,
    };
    unit.validate()?;
    Ok(unit)
}

fn escape_like(query: &str) -> String {
    query
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
}

/// 小工具：把字符串解析成 ID，避免在调用点写 `use std::str::FromStr`。
trait FromStrValue: Sized {
    fn from_str_value(value: &str) -> Result<Self, String>;
}

impl FromStrValue for MessageId {
    fn from_str_value(value: &str) -> Result<Self, String> {
        value
            .parse()
            .map_err(|e: nmoi_core::ids::IdError| e.to_string())
    }
}

impl FromStrValue for UnitId {
    fn from_str_value(value: &str) -> Result<Self, String> {
        value
            .parse()
            .map_err(|e: nmoi_core::ids::IdError| e.to_string())
    }
}

impl FromStrValue for SessionId {
    fn from_str_value(value: &str) -> Result<Self, String> {
        value
            .parse()
            .map_err(|e: nmoi_core::ids::IdError| e.to_string())
    }
}

/// core 的会话编排只认 trait，SQLite 这边把现有的两个方法接上去。
///
/// 错误转成 `String` 是这一层的刻意选择：`HistoryStore` 的使用者是编排逻辑，
/// 它要的是"能不能继续"，不是 `StorageError` 的每一种变体。真正的错误类型留给
/// 直接调 `Storage` 的平台代码。
impl nmoi_core::session::HistoryStore for Storage {
    fn append_unit(&mut self, session: SessionId, unit: &AtomicUnit) -> Result<(), String> {
        Storage::append_unit(self, session, unit).map_err(|error| error.to_string())
    }

    fn load_units(&self, session: SessionId) -> Result<Vec<AtomicUnit>, String> {
        Storage::load_units(self, session).map_err(|error| error.to_string())
    }

    /// 认分界线的实现走 SQLite 那份（D149）：编排层只看到"窗口"，
    /// 不用自己知道 `archive` 里躺着摘要。
    fn load_window(&self, session: SessionId) -> Result<ContextWindow, String> {
        Storage::load_window(self, session).map_err(|error| error.to_string())
    }
}
