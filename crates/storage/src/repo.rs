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
use nmoi_core::message::{AtomicUnit, ContentForm, Message, Role, UnitError, UnitKind};
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

/// 单张图片的上限（D208）。挡的是"手滑选了原图"：8 MB 的字节编成 base64 是 11 MB，
/// 一轮请求体再大就没有意义了（上游普遍 20 MB 起，但那是整包上限）。
pub const MAX_ATTACHMENT_BYTES: usize = 8 * 1024 * 1024;

/// 图片扩展名：只用于文件名，读的时候不靠它判断类型（类型看 `mime` 列）。
fn extension_for(mime: &str) -> &'static str {
    match mime {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/png" => "png",
        "image/webp" => "webp",
        "image/gif" => "gif",
        "image/heic" => "heic",
        "image/heif" => "heif",
        "image/bmp" => "bmp",
        _ => "bin",
    }
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

/// 一条附件的元数据（D208）：**不带字节**。列表用它，别把几十 MB 拖进界面。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentInfo {
    pub id: String,
    pub message: MessageId,
    pub mime: String,
    pub size: u64,
}

/// 存储管理页用的一条附件（D216）：带会话标题与盘上路径。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredAttachment {
    pub id: String,
    pub conversation: String,
    pub conversation_title: String,
    pub message: String,
    pub mime: String,
    pub size: u64,
    pub path: Option<String>,
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
    /// 归属的助手（D174）。列表按它过滤，界面上也要能说出来"这句是谁的会话"。
    pub assistant_id: String,
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
    /// 新建会话。`device` 写进 `device_origin`，同步时用来判断来源设备；
    /// `assistant` 写进 `assistant_id`（D174）——列表按它过滤，所以不能省。
    ///
    /// 参数顺序是\"会话是什么 → 属于谁\"：`title`、`device` 是描述会话本身的，
    /// `assistant` 是归属。调用方几乎总是先有标题再想起归属，反着放容易传反。
    pub fn create_conversation(
        &mut self,
        title: &str,
        device: &str,
        assistant: &str,
    ) -> Result<SessionId, StorageError> {
        let session = SessionId::new();
        let now = crate::validate::now_ms();
        self.conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version,
                                       device_origin, assistant_id)
             VALUES (?1, ?2, ?3, ?3, ?4, ?5, ?6)",
            params![
                session.to_string(),
                title,
                now,
                crate::migrate::SCHEMA_VERSION,
                device,
                assistant
            ],
        )?;
        Ok(session)
    }

    /// 一条消息**现在显示的正文**（`messages.content`）。
    ///
    /// 给续话的流式垫字用（0.15av）：落库那份是"原正文 + 续文"，而流里只有续文那一截，
    /// 所以要在开跑前拿到原正文。走 `content` 而不是某一版：用户接的是他看见的那一段
    /// （铁律①），和 `regenerate_turn` 里 `append` 取的是同一处。
    pub fn message_content(&self, message: &MessageId) -> Result<String, StorageError> {
        self.conn
            .query_row(
                "SELECT COALESCE(
                     (SELECT json_extract(p.body, '$.text') FROM message_parts p
                       WHERE p.message_id = m.id AND p.kind = 'text' ORDER BY p.ord LIMIT 1),
                     '')
                   FROM messages m WHERE m.id = ?1",
                [message.to_string()],
                |row| row.get(0),
            )
            .map_err(|_| StorageError::Inconsistent(format!("消息 {message} 不存在")))
    }

    /// 改会话标题（0.15d）。**不动 `updated_at`**：改名不是"会话有活动"，
    /// 让它在列表里跳到最上面，用户会以为多了新消息。
    pub fn rename_conversation(&self, session: SessionId, title: &str) -> Result<(), StorageError> {
        let title = title.trim();
        if title.is_empty() {
            return Err(StorageError::Inconsistent("标题不能是空的".to_string()));
        }
        let changed = self.conn.execute(
            "UPDATE conversations SET title = ?1 WHERE id = ?2",
            params![title, session.to_string()],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!("会话 {session} 不存在")));
        }
        Ok(())
    }

    /// 删一个会话（0.15d）。
    ///
    /// 消息、版本、分界线、编辑审计都靠外键 `ON DELETE CASCADE` 跟着走；
    /// **`archive` 与 `events` 没有外键**，得自己删——不然库里会留下
    /// "指不出来的东西"（铁律 ②），备份也会越带越大。
    ///
    /// 返回 `false` = 本来就没有这个会话（不算错：两个入口同时点的情况真实存在）。
    pub fn delete_conversation(&mut self, session: SessionId) -> Result<bool, StorageError> {
        let tx = self.conn.transaction()?;
        let id = session.to_string();
        // 归档按 ref 全局唯一（内容寻址），副本会继续指着原会话写下的那一行。
        // 所以只删"没有别人还在引用"的：否则删掉原件，副本里的 `archived_ref` 就悬空了。
        tx.execute(
            "DELETE FROM archive WHERE conv_id = ?1
               AND ref NOT IN (
                   SELECT archived_ref FROM messages
                   WHERE conv_id <> ?1 AND archived_ref IS NOT NULL
               )",
            params![id],
        )?;
        tx.execute("DELETE FROM events WHERE conv_id = ?1", params![id])?;
        let changed = tx.execute("DELETE FROM conversations WHERE id = ?1", params![id])?;
        tx.commit()?;
        if changed > 0 {
            // 附件目录与暂存目录跟着会话走（D216 / D294）：库里删干净了，盘上也不该留孤儿。
            let dir = self.session_dir(session);
            let _ = std::fs::remove_dir_all(dir);
        }
        Ok(changed > 0)
    }

    /// 复制一个会话（0.15d / D207）：新建一个会话，把消息按原顺序整份拷过去。
    ///
    /// 为什么走 `load_units` → 按新会话重赋 id → `append_unit`，而不是一条
    /// `INSERT ... SELECT`：单元是"调用 + 结果成对闭合"的整体，直接抄 SQL 会绕过
    /// `validate()` 与 `append_unit` 自带的写后读回比对；抄漏一行就是铁律 ② 里的
    /// "不可见消息"。`seq` 原样保留，所以副本里的消息顺序与原件一一对应。
    ///
    /// **不复制**的东西，理由各自不同：
    /// - 分界线：`summary_ref` 指向原会话压出来的摘要，搬过去就悬空（`load_window` 会直接报错）；
    /// - 置顶位：那是"我怎么看这个列表"，不是会话本身的事实；
    /// - 草稿：根本不在库里（`DraftStore` 走 SharedPreferences）。
    ///
    /// 标题：调用方给了非空就用它，否则「原标题 副本」。给这个默认值是因为
    /// 列表里两条同名会话根本分不出哪条是新的。
    pub fn copy_conversation(
        &mut self,
        session: SessionId,
        title: Option<&str>,
    ) -> Result<SessionId, StorageError> {
        let found: Option<(String, String, String, Option<String>)> = self
            .conn
            .query_row(
                "SELECT title, device_origin, assistant_id, model FROM conversations WHERE id = ?1",
                [session.to_string()],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()?;
        let (source_title, device, assistant, model) =
            found.ok_or_else(|| StorageError::Inconsistent(format!("会话 {session} 不存在")))?;

        let units = self.load_units(session)?;
        let copy_title = match title.map(str::trim) {
            Some(given) if !given.is_empty() => given.to_string(),
            _ => format!("{source_title} 副本"),
        };
        let target = self.create_conversation(&copy_title, &device, &assistant)?;
        for unit in &units {
            let new_unit = UnitId::new(target, unit.id.seq());
            let mut copy = unit.clone();
            copy.id = new_unit.clone();
            for message in &mut copy.messages {
                message.id = MessageId::new(target, message.id.seq());
                message.unit_id = new_unit.clone();
            }
            self.append_unit(target, &copy)?;
        }
        // 归档（D317）：副本里的标记行写着 `/archive/<hex>.txt`，那条文件在这个会话的
        // 目录下也得有一份。ref 是内容寻址的全局主键，内容本来就还在库里，照抄一份即可——
        // 不抄的话，副本里每一处"已归档"都会变成"取不到了"。
        let refs: Vec<String> = {
            let mut stmt = self.conn.prepare(
                "SELECT DISTINCT archived_ref FROM messages
                 WHERE conv_id = ?1 AND archived_ref IS NOT NULL",
            )?;
            let rows = stmt.query_map([target.to_string()], |row| row.get::<_, String>(0))?;
            let mut out = Vec::new();
            for row in rows {
                out.push(row?);
            }
            out
        };
        for text in refs {
            let Ok(reference) = text.parse::<ArchiveRef>() else {
                continue;
            };
            if let Ok(entry) = self.fetch_archive(&reference) {
                self.materialize_archive(target, &entry)?;
            }
        }
        // 模型跟着走：副本第一次发消息时用的是"原来那个"，而不是供应商的第一个模型（D205）。
        if let Some(model) = model {
            self.set_conversation_model(target, &model)?;
        }
        Ok(target)
    }

    /// 存一张图片（D208 / D216）。返回附件 id。
    ///
    /// **字节落到会话私有目录**（`<库目录>/sessions/<会话>/images/<id>.<ext>`），
    /// 库里只留路径：工作区的文件工具能直接读它，备份也只需要带上这个目录。
    ///
    /// 校验三样：`mime` 必须是 `image/…`、字节非空且不超上限、消息真的属于这个会话。
    /// 第三条不能省——写进去一条挂在不存在的消息上的附件，就是"界面上指不出来的数据"（铁律 ②）。
    pub fn put_attachment(
        &mut self,
        session: SessionId,
        message: &MessageId,
        mime: &str,
        bytes: &[u8],
    ) -> Result<String, StorageError> {
        let mime = mime.trim();
        if !mime.starts_with("image/") {
            return Err(StorageError::Inconsistent(format!(
                "附件只收图片，给的是 {mime:?}"
            )));
        }
        if bytes.is_empty() {
            return Err(StorageError::Inconsistent("图片是空的".to_string()));
        }
        if bytes.len() > MAX_ATTACHMENT_BYTES {
            return Err(StorageError::Inconsistent(format!(
                "图片 {} 字节，超过上限 {}",
                bytes.len(),
                MAX_ATTACHMENT_BYTES
            )));
        }
        if message.session() != session {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message} 不属于会话 {session}"
            )));
        }
        let owned: Option<i64> = self
            .conn
            .query_row(
                "SELECT 1 FROM messages WHERE id = ?1 AND conv_id = ?2",
                params![message.to_string(), session.to_string()],
                |row| row.get(0),
            )
            .optional()?;
        if owned.is_none() {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message} 不在会话 {session} 里，图片没地方挂"
            )));
        }

        let id = Uuid::now_v7().to_string();
        let dir = self.attachment_dir(session);
        std::fs::create_dir_all(&dir)?;
        let file = dir.join(format!("{id}.{}", extension_for(mime)));
        std::fs::write(&file, bytes)?;
        let path = file.to_string_lossy().to_string();

        let inserted = self.conn.execute(
            "INSERT INTO attachments(id, conv_id, message_id, mime, bytes, path, size, created_at)
             VALUES (?1, ?2, ?3, ?4, NULL, ?5, ?6, ?7)",
            params![
                id,
                session.to_string(),
                message.to_string(),
                mime,
                path,
                bytes.len() as i64,
                crate::validate::now_ms(),
            ],
        );
        if let Err(error) = inserted {
            // 库里没写进去就把盘上那份删掉，别留一个没人认领的文件。
            let _ = std::fs::remove_file(&file);
            return Err(error.into());
        }
        Ok(id)
    }

    /// 一个会话里所有附件的元数据（不带字节）：消息区画缩略图前先知道"哪条有几个"。
    pub fn attachments_of(&self, session: SessionId) -> Result<Vec<AttachmentInfo>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, message_id, mime, size FROM attachments
             WHERE conv_id = ?1 ORDER BY created_at ASC, id ASC",
        )?;
        let rows = stmt.query_map([session.to_string()], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (id, message, mime, size) = row?;
            out.push(AttachmentInfo {
                id,
                message: MessageId::from_str_value(&message).map_err(StorageError::Inconsistent)?,
                mime,
                size: size as u64,
            });
        }
        Ok(out)
    }

    /// 全部附件（带会话标题）：存储管理页用（D216）。
    pub fn all_attachments(&self) -> Result<Vec<StoredAttachment>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT a.id, a.conv_id, a.message_id, a.mime, a.size, a.path,
                    COALESCE(c.title, '')
             FROM attachments a
             LEFT JOIN conversations c ON c.id = a.conv_id
             ORDER BY a.created_at DESC, a.id DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(StoredAttachment {
                id: row.get(0)?,
                conversation: row.get(1)?,
                conversation_title: row.get(6)?,
                message: row.get(2)?,
                mime: row.get(3)?,
                size: row.get::<_, i64>(4)? as u64,
                path: row.get(5)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    /// 取一张图片的字节（D208）。找不到就报错，不返回空图——
    /// 空图在界面上是一块灰，比报错更难查。
    ///
    /// 老数据（v10 及以前）把字节存在库里：这里顺手搬到盘上并清空 BLOB（D216），
    /// 搬不动也照常返回——读图不该因为"整理"失败而失败。
    /// 这条附件在不在（D248）。`View-Image` 的清单里可能留着一张已经被删掉的图——
    /// 那不是错误，跳过就行；用一句话去匹配 `Inconsistent` 的文案才是错误。
    pub fn has_attachment(&self, id: &str) -> Result<bool, StorageError> {
        let found: Option<i64> = self
            .conn
            .query_row("SELECT 1 FROM attachments WHERE id = ?1", [id], |row| {
                row.get(0)
            })
            .optional()?;
        Ok(found.is_some())
    }

    pub fn attachment(&self, id: &str) -> Result<(String, Vec<u8>), StorageError> {
        type Row = (String, Option<Vec<u8>>, Option<String>, String);
        let found: Option<Row> = self
            .conn
            .query_row(
                "SELECT mime, bytes, path, conv_id FROM attachments WHERE id = ?1",
                [id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()?;
        let (mime, bytes, path, conv) =
            found.ok_or_else(|| StorageError::Inconsistent(format!("附件 {id} 不存在")))?;
        if let Some(path) = path {
            let data = std::fs::read(&path).map_err(|error| {
                StorageError::Inconsistent(format!("附件 {id} 的文件读不出来（{path}）：{error}"))
            })?;
            return Ok((mime, data));
        }
        let data = bytes
            .ok_or_else(|| StorageError::Inconsistent(format!("附件 {id} 既没有路径也没有字节")))?;
        self.adopt_blob(id, &conv, &mime, &data);
        Ok((mime, data))
    }

    /// 把一条老数据从 BLOB 搬到盘上（D216）。失败就算了——下一次读还会再试。
    fn adopt_blob(&self, id: &str, conv: &str, mime: &str, data: &[u8]) {
        let Ok(session) = conv.parse::<SessionId>() else {
            return;
        };
        let dir = self.attachment_dir(session);
        if std::fs::create_dir_all(&dir).is_err() {
            return;
        }
        let file = dir.join(format!("{id}.{}", extension_for(mime)));
        if std::fs::write(&file, data).is_err() {
            return;
        }
        let path = file.to_string_lossy().to_string();
        let _ = self.conn.execute(
            "UPDATE attachments SET path = ?1, bytes = NULL WHERE id = ?2",
            params![path, id],
        );
    }

    /// 删一张附件（存储管理页）：行与文件一起删。
    pub fn delete_attachment(&mut self, id: &str) -> Result<bool, StorageError> {
        let found: Option<Option<String>> = self
            .conn
            .query_row("SELECT path FROM attachments WHERE id = ?1", [id], |row| {
                row.get(0)
            })
            .optional()?;
        let Some(path) = found else {
            return Ok(false);
        };
        self.conn
            .execute("DELETE FROM attachments WHERE id = ?1", [id])?;
        if let Some(path) = path {
            let _ = std::fs::remove_file(path);
        }
        Ok(true)
    }

    /// 一条消息的图片（base64），发请求时用。
    pub fn attachments_for(
        &self,
        message: &MessageId,
    ) -> Result<Vec<(String, String)>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, mime FROM attachments WHERE message_id = ?1
             ORDER BY created_at ASC, id ASC",
        )?;
        let rows = stmt.query_map([message.to_string()], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        let mut ids = Vec::new();
        for row in rows {
            ids.push(row?);
        }
        let mut out = Vec::new();
        for (id, _) in ids {
            let (mime, bytes) = self.attachment(&id)?;
            out.push((mime, nmoi_core::base64::encode(&bytes)));
        }
        Ok(out)
    }

    /// 会话私有目录：`<库目录>/sessions/<会话 id>/images/`。
    ///
    /// 与库同级是刻意的：备份/导出只要带上库和这个目录就齐了；
    /// 会话删掉时整目录一起删（见 `delete_conversation`）。
    pub fn attachment_dir(&self, session: SessionId) -> std::path::PathBuf {
        self.session_dir(session).join("images")
    }

    /// 会话自己的暂存目录：`<库目录>/sessions/<会话 id>/tmp/`（D294）。
    ///
    /// 文件工具里的 `/tmp` 落到这儿，容器里的 `/tmp` 挂的也是它；与 `images` 同级，
    /// 删会话时整目录一起删。
    pub fn session_tmp_dir(&self, session: SessionId) -> std::path::PathBuf {
        self.session_dir(session).join("tmp")
    }

    /// 会话的归档目录：`<库目录>/sessions/<会话 id>/archive/`（D317）。
    ///
    /// 文件工具里的 `/archive` 与容器里的 `/archive` 都是它；删会话时整目录一起删。
    pub fn session_archive_dir(&self, session: SessionId) -> std::path::PathBuf {
        self.session_dir(session).join("archive")
    }

    /// 一条归档物化之后落在哪儿：`…/archive/<hex>.txt`。
    ///
    /// 名字由 `ArchiveRef::file_name` 定（去掉 `ref#`），与标记行里那个路径是同一份拼法。
    pub fn archive_file(&self, session: SessionId, reference: &ArchiveRef) -> std::path::PathBuf {
        self.session_archive_dir(session)
            .join(reference.file_name())
    }

    fn session_dir(&self, session: SessionId) -> std::path::PathBuf {
        self.data_root().join("sessions").join(session.to_string())
    }

    /// 跨会话只读（D295）要的索引：全部会话的 id 与名字，外加 `sessions/` 这个根。
    ///
    /// 只查两列，不碰消息表——这份索引只在那项开关打开时用一次，几十条会话足够轻。
    pub fn session_index(&self) -> Result<nmoi_core::session::SessionIndex, StorageError> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, COALESCE(title, '') FROM conversations")?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        let mut sessions = Vec::new();
        for row in rows {
            sessions.push(row?);
        }
        Ok(nmoi_core::session::SessionIndex::new(
            self.data_root().join("sessions"),
            sessions,
        ))
    }

    fn data_root(&self) -> std::path::PathBuf {
        self.path
            .parent()
            .map(|parent| parent.to_path_buf())
            .unwrap_or_else(|| std::path::PathBuf::from("."))
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

    /// 这条会话自己绑的工作区（D271）。`None` = 跟助手。
    pub fn conversation_workspace(
        &self,
        session: SessionId,
    ) -> Result<Option<String>, StorageError> {
        let found: Option<Option<String>> = self
            .conn
            .query_row(
                "SELECT workspace_id FROM conversations WHERE id = ?1",
                [session.to_string()],
                |row| row.get(0),
            )
            .optional()?;
        found.ok_or_else(|| StorageError::Inconsistent(format!("会话 {session} 不存在")))
    }

    /// 改这条会话自己的工作区（D271）。`None` = 改回跟助手。
    ///
    /// 空串按参数错误拒绝：这一列只有"有"和"没有"两种状态，空串会把它们混成第三种。
    pub fn set_conversation_workspace(
        &self,
        session: SessionId,
        workspace: Option<&str>,
    ) -> Result<(), StorageError> {
        if let Some(id) = workspace {
            if id.trim().is_empty() {
                return Err(StorageError::Inconsistent(
                    "工作区 id 不能是空串：要跟助手就传 None".to_string(),
                ));
            }
        }
        let changed = self.conn.execute(
            "UPDATE conversations SET workspace_id = ?1 WHERE id = ?2",
            params![workspace, session.to_string()],
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
    ///
    /// `assistant`（D174）：`Some(id)` = 只看这个助手的会话；`None` = 全都要
    /// （导出、备份、迁移核对这类"不属于某一个助手"的读取用）。
    /// **不给默认值、不偷偷返回全部**：界面忘了传就会当场看见所有会话，
    /// 而不是安静地只显示一半。
    pub fn list_conversations(
        &self,
        assistant: Option<&str>,
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
                    (SELECT json_extract(p.body, '$.text') FROM message_parts p
                      WHERE p.message_id = (SELECT id FROM messages WHERE conv_id = c.id
                                             ORDER BY seq DESC LIMIT 1)
                      ORDER BY p.ord LIMIT 1) AS last_text,
                    c.model,
                    c.assistant_id
             FROM conversations c
             WHERE (?3 IS NULL OR c.assistant_id = ?3)
             ORDER BY c.updated_at DESC, c.id DESC
             LIMIT ?1 OFFSET ?2",
        )?;
        let rows = stmt.query_map(
            params![(want + 1) as i64, offset as i64, assistant],
            |row| {
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
                    row.get::<_, String>(11)?,
                ))
            },
        )?;

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
                assistant_id,
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
                assistant_id,
            });
        }

        let has_more = items.len() > want;
        items.truncate(want);
        Ok(ConversationPage { items, has_more })
    }

    /// 移动会话到另一个助手（D174）：**改归属，不复制**。
    ///
    /// 复制会立刻出现两份"同一个会话"，之后用户改哪一份都说不清哪份才算数；
    /// 而且消息、版本、摘要全要跟着搬，搬漏一条就是"不可见消息"。
    ///
    /// **不动 `updated_at`**：那是"最近说过话"的时间，挪个文件夹不该让它跳到现在。
    /// 代价是到了新助手那边它按原来的时间排在列表里——这正是想要的效果
    /// （用户找的是那条会话，不是"刚挪过来的那条"）。
    pub fn move_conversation(
        &mut self,
        session: SessionId,
        assistant: &str,
    ) -> Result<(), StorageError> {
        if assistant.trim().is_empty() {
            return Err(StorageError::Inconsistent("目标助手的 id 是空的".into()));
        }
        let changed = self.conn.execute(
            "UPDATE conversations SET assistant_id = ?1 WHERE id = ?2",
            params![assistant, session.to_string()],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!("会话 {session} 不存在")));
        }
        Ok(())
    }

    /// 这个会话属于哪个助手（D174）。会话不在就报错，不返回 `"default"`——
    /// "查不到"和"归在 default"是两件事，混起来会让移动/删除的判断悄悄走错一边。
    pub fn conversation_assistant(&self, session: SessionId) -> Result<String, StorageError> {
        self.conn
            .query_row(
                "SELECT assistant_id FROM conversations WHERE id = ?1",
                [session.to_string()],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .ok_or_else(|| StorageError::Inconsistent(format!("会话 {session} 不存在")))
    }

    /// 某个助手底下有多少个会话（D174：删助手前的闸）。
    ///
    /// 用 `COUNT(*)` 而不是 `list_conversations(Some(id), 1, 0).has_more`：
    /// 界面上要说的是"还有 3 个会话"，不是"不止一个"。
    pub fn count_conversations_of(&self, assistant: &str) -> Result<u64, StorageError> {
        let count: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM conversations WHERE assistant_id = ?1",
            [assistant],
            |row| row.get(0),
        )?;
        Ok(count.max(0) as u64)
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
        // 只存结束（D252）：带着没结果的调用进库，等于把"进行时"存下来了。
        if let Some(call) = unit.pending_calls().first() {
            return Err(StorageError::Inconsistent(format!(
                "单元 {} 的调用 {call} 还没有结果：只存结束，不存进行时",
                unit.id
            )));
        }
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

        insert_unit_rows(&tx, session, unit)?;
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
            "SELECT id, unit_id, unit_kind, unit_closed, seq, role, source,
                    form, tokens, created_at, logical_clock, archived_ref, protected, pinned,
                    locked, revision, tool_call_id, incomplete
             FROM messages WHERE conv_id = ?1 ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map([session.to_string()], row_to_message)?;
        let mut messages: Vec<Message> = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        drop(stmt);
        let parts = crate::parts::read_parts(
            &mut self.conn.prepare(
                "SELECT p.message_id, p.part_id, p.kind, p.body
                   FROM message_parts p JOIN messages m ON m.id = p.message_id
                  WHERE m.conv_id = ?1 ORDER BY p.message_id, p.ord",
            )?,
            &session.to_string(),
        )?;
        for message in &mut messages {
            if let Some(list) = parts.get(&message.id.to_string()) {
                message.set_parts(list.clone());
            }
        }
        let mut units: Vec<AtomicUnit> = Vec::new();
        for message in messages {
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
                // 库里已经有了，文件可能被删过（清盘、迁移）——补回来再走。
                // 顺序是"先落文件再认成功"：物化失败就不算这条归档可用，
                // 调用方（`apply_forms`）会退回全文，而不是把内容换成一句取不回来的话。
                self.materialize_archive(session, &entry)?;
                return Ok(entry.rf);
            }
            Err(StorageError::Archive(nmoi_core::archive::ArchiveError::NotFound { .. })) => {}
            Err(err) => return Err(err),
        }

        self.materialize_archive(session, &entry)?;
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

    /// 把一条归档写成文件（D317）：`sessions/<id>/archive/<hex>.txt`，内容逐字节照抄。
    ///
    /// 为什么要有这一步：库里那份只有 core 内部读得到，模型手上只有一行标记。
    /// 物化之后 `read_file` / `grep` / 容器里的 `cat` 都能把它取回来——
    /// 取回不通，F3 的占位符就是"内容被删了，只留一个编号"。
    ///
    /// 失败往上抛：物化失败而库里写着"已归档"，是最坏的一种不一致。
    fn materialize_archive(
        &self,
        session: SessionId,
        entry: &ArchiveEntry,
    ) -> Result<(), StorageError> {
        let path = self.archive_file(session, &entry.rf);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, entry.content.as_bytes())?;
        Ok(())
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
        let tx = self.conn.transaction()?;
        let changed = tx.execute(
            "UPDATE messages
             SET tokens = ?1, revision = ?2
             WHERE id = ?3 AND conv_id = ?4",
            params![
                message.tokens,
                message.revision,
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
        crate::parts::write_parts(&tx, message)?;
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

pub(crate) fn conversation_exists_in(
    conn: &Connection,
    session: SessionId,
) -> Result<bool, rusqlite::Error> {
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

/// 写一个单元的全部行（消息 + 块）。调用方负责事务与"写后读回比对"。
///
/// 抽出来是为了让整轮替换（D263）与追加走同一条写入路径：两份 INSERT 迟早有一份忘了
/// 写块、或者忘了同步 `unit_closed`。
pub(crate) fn insert_unit_rows(
    tx: &rusqlite::Transaction<'_>,
    session: SessionId,
    unit: &AtomicUnit,
) -> Result<(), StorageError> {
    for message in &unit.messages {
        tx.execute(
            "INSERT INTO messages(
                 id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                 form, tokens, created_at, logical_clock, archived_ref,
                 protected, pinned, locked, revision, incomplete)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)
             ON CONFLICT(id) DO UPDATE SET
                 form = excluded.form,
                 tokens = excluded.tokens,
                 logical_clock = excluded.logical_clock,
                 archived_ref = excluded.archived_ref,
                 protected = excluded.protected,
                 pinned = excluded.pinned,
                 locked = excluded.locked,
                 revision = excluded.revision,
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
                message.form.to_string(),
                message.tokens,
                message.created_at,
                message.logical_clock.value() as i64,
                message.archived_ref.as_ref().map(|r| r.to_string()),
                if message.protected { 1 } else { 0 },
                if message.pinned { 1 } else { 0 },
                if message.locked { 1 } else { 0 },
                message.revision,
                if message.incomplete { 1 } else { 0 },
            ],
        )?;
        crate::parts::write_parts(tx, message)?;
    }
    // 同一单元的所有行统一闭合状态。
    tx.execute(
        "UPDATE messages SET unit_closed = ?1 WHERE unit_id = ?2",
        params![if unit.is_closed() { 1 } else { 0 }, unit.id.to_string()],
    )?;
    Ok(())
}

/// `after_seq` 之后的全部消息（含块），按 seq 升序。
///
/// 整轮替换（D263）与版本快照都用它：一轮回答在库里就是"最后一条用户消息之后的全部行"。
pub(crate) fn load_tail_messages(
    tx: &rusqlite::Transaction<'_>,
    session: SessionId,
    after_seq: u64,
) -> Result<Vec<Message>, StorageError> {
    let mut stmt = tx.prepare(
        "SELECT id, unit_id, unit_kind, unit_closed, seq, role, source,
                form, tokens, created_at, logical_clock, archived_ref, protected, pinned,
                locked, revision, tool_call_id, incomplete
         FROM messages WHERE conv_id = ?1 AND seq > ?2 ORDER BY seq ASC",
    )?;
    let rows = stmt.query_map(
        params![session.to_string(), after_seq as i64],
        row_to_message,
    )?;
    let mut messages = Vec::new();
    for row in rows {
        messages.push(row?);
    }
    drop(stmt);

    let mut parts: std::collections::HashMap<String, Vec<nmoi_core::message::Part>> =
        std::collections::HashMap::new();
    let mut parts_stmt = tx.prepare(
        "SELECT p.message_id, p.part_id, p.kind, p.body
           FROM message_parts p JOIN messages m ON m.id = p.message_id
          WHERE m.conv_id = ?1 AND m.seq > ?2 ORDER BY p.message_id, p.ord",
    )?;
    let part_rows =
        parts_stmt.query_map(params![session.to_string(), after_seq as i64], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
            ))
        })?;
    for row in part_rows {
        let (message, part_id, kind, body) = row?;
        parts.entry(message).or_default().push(
            nmoi_core::message::Part::from_body(part_id, &kind, &body)
                .map_err(StorageError::Inconsistent)?,
        );
    }
    for message in &mut messages {
        if let Some(list) = parts.get(&message.id.to_string()) {
            message.set_parts(list.clone());
        }
    }
    Ok(messages)
}

fn row_to_message(row: &rusqlite::Row<'_>) -> Result<Message, rusqlite::Error> {
    let id_text: String = row.get(0)?;
    let unit_text: String = row.get(1)?;
    let role_text: String = row.get(5)?;
    let source_text: String = row.get(6)?;
    let form_text: String = row.get(7)?;
    let ref_text: Option<String> = row.get(11)?;

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
    // parts 由调用方一次批量查出来再挂上（`parts_of_*`）：一条消息一次查询就是 N+1，
    // 滚动会话时会一顿一顿的。
    let mut message = Message::text(
        id,
        role,
        source,
        String::new(),
        row.get::<_, i64>(8)? as u32,
        row.get(9)?,
        LogicalClock::new(row.get::<_, i64>(10)? as u64),
    )
    .with_unit(unit_id);
    message.form = form;
    message.archived_ref = archived_ref;
    message.protected = row.get::<_, i64>(12)? != 0;
    message.pinned = row.get::<_, i64>(13)? != 0;
    message.locked = row.get::<_, i64>(14)? != 0;
    message.revision = row.get::<_, i64>(15)? as u32;
    // 第 16 列是 `tool_call_id`：折行之后不再有 `role=tool` 的行，这一列只留给旧库。
    message.incomplete = row.get::<_, i64>(17)? != 0;
    Ok(message)
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
        "SELECT id, unit_id, unit_kind, unit_closed, seq, role, source,
                form, tokens, created_at, logical_clock, archived_ref, protected, pinned,
                locked, revision, tool_call_id, incomplete
         FROM messages WHERE unit_id = ?1 ORDER BY seq ASC",
    )?;
    let rows = stmt.query_map([unit_id.to_string()], row_to_message)?;
    let mut messages = Vec::new();
    for row in rows {
        messages.push(row?);
    }
    drop(stmt);
    let parts = crate::parts::read_parts(
        &mut conn.prepare(
            "SELECT p.message_id, p.part_id, p.kind, p.body
               FROM message_parts p JOIN messages m ON m.id = p.message_id
              WHERE m.unit_id = ?1 ORDER BY p.message_id, p.ord",
        )?,
        &unit_id.to_string(),
    )?;
    for message in &mut messages {
        if let Some(list) = parts.get(&message.id.to_string()) {
            message.set_parts(list.clone());
        }
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

    /// 图片（D208）：发请求前按消息 id 取 base64。取不出来就报错——
    /// 静默少一张图，用户会以为模型没看懂。
    fn attachments(
        &self,
        message: &MessageId,
    ) -> Result<Vec<nmoi_core::message::ImagePart>, String> {
        let parts = Storage::attachments_for(self, message).map_err(|error| error.to_string())?;
        Ok(parts
            .into_iter()
            .map(|(mime, data)| nmoi_core::message::ImagePart { mime, data })
            .collect())
    }

    /// 按附件 id 读一张图（D248）：`View-Image` 拉进上下文的那张不属于任何消息。
    /// 不在了就返回 `None`——清单里留着一个已经不存在的 id 不是错误，跳过它就好。
    fn attachment(&self, id: &str) -> Result<Option<nmoi_core::message::ImagePart>, String> {
        if !Storage::has_attachment(self, id).map_err(|error| error.to_string())? {
            return Ok(None);
        }
        let (mime, bytes) = Storage::attachment(self, id).map_err(|error| error.to_string())?;
        Ok(Some(nmoi_core::message::ImagePart {
            mime,
            data: nmoi_core::base64::encode(&bytes),
        }))
    }

    /// 这个会话的附件目录（D248）：`View-Image` 只认这里面的文件。
    fn attachment_dir(&self, session: SessionId) -> Option<std::path::PathBuf> {
        Some(Storage::attachment_dir(self, session))
    }

    /// 这个会话自己的暂存目录（D294）：文件工具的 `/tmp` 落到它。
    fn session_tmp_dir(&self, session: SessionId) -> Option<std::path::PathBuf> {
        Some(Storage::session_tmp_dir(self, session))
    }

    fn session_archive_dir(&self, session: SessionId) -> Option<std::path::PathBuf> {
        Some(Storage::session_archive_dir(self, session))
    }

    /// 跨会话只读（D295）：别的会话的索引。查不出来就当"看不到"，不编。
    fn session_index(&self) -> Option<nmoi_core::session::SessionIndex> {
        Storage::session_index(self).ok()
    }

    /// 这一轮刚发的图片（D208）：解码后逐张落库。解不开就报错——
    /// 悄悄少存一张，下一轮重试时那张图就没了。
    fn save_attachments(
        &mut self,
        message: &MessageId,
        parts: &[nmoi_core::message::ImagePart],
    ) -> Result<(), String> {
        for part in parts {
            let bytes = nmoi_core::base64::decode(&part.data)
                .ok_or_else(|| format!("附件 base64 解不开（消息 {message}）"))?;
            Storage::put_attachment(self, message.session(), message, &part.mime, &bytes)
                .map_err(|error| error.to_string())?;
        }
        Ok(())
    }

    /// 写归档（D275）：F2–F4 换掉的原文都在这儿，模型要能取回来。
    fn archive_put(
        &mut self,
        entry: nmoi_core::archive::ArchiveEntry,
    ) -> Result<nmoi_core::ids::ArchiveRef, String> {
        let session = entry.origin.session;
        Storage::put_archive(self, session, entry).map_err(|error| error.to_string())
    }

    /// 一条消息带的图片文件名（D270）：盘上的名字就是 `<附件 id>.<扩展名>`。
    fn attachment_files(&self, message: &MessageId) -> Result<Vec<String>, String> {
        let mut statement = self
            .conn
            .prepare("SELECT id, mime FROM attachments WHERE message_id = ?1 ORDER BY rowid")
            .map_err(|error| error.to_string())?;
        let rows = statement
            .query_map([message.to_string()], |row| {
                let id: String = row.get(0)?;
                let mime: String = row.get(1)?;
                Ok(format!("{id}.{}", extension_for(&mime)))
            })
            .map_err(|error| error.to_string())?;
        let mut files = Vec::new();
        for row in rows {
            files.push(row.map_err(|error| error.to_string())?);
        }
        Ok(files)
    }

    /// 认分界线的实现走 SQLite 那份（D149）：编排层只看到"窗口"，
    /// 不用自己知道 `archive` 里躺着摘要。
    fn load_window(&self, session: SessionId) -> Result<ContextWindow, String> {
        Storage::load_window(self, session).map_err(|error| error.to_string())
    }

    /// 模型改消息（D177 / 0.14y）。落库走的还是用户手动编辑那条路（`apply_block_edit`）：
    /// 审计、revision、token 重算、`tooledit_message` 的存在性全在同一处处理。
    fn apply_block_edit(
        &mut self,
        message_id: &MessageId,
        block: BlockSelector,
        content: &str,
    ) -> Result<EditOutcome, String> {
        Storage::apply_block_edit(self, message_id, block, content)
            .map_err(|error| error.to_string())
    }

    /// 重试：整轮替换（D263）。
    fn replace_turn(
        &mut self,
        session: SessionId,
        after: &MessageId,
        units: &[AtomicUnit],
        keep_version: bool,
    ) -> Result<Option<MessageId>, String> {
        Storage::replace_turn(self, session, after, units, keep_version)
            .map_err(|error| error.to_string())
    }

    /// 只换一条消息（D284）：删一条、写一条，后面的行一个都不动。
    fn replace_message(
        &mut self,
        session: SessionId,
        id: &MessageId,
        unit: &AtomicUnit,
        keep_version: bool,
    ) -> Result<Option<MessageId>, String> {
        Storage::replace_message(self, session, id, unit, keep_version)
            .map_err(|error| error.to_string())
    }

    /// 单元级编辑（0.15f / D188）：落红、成对删、undo 日志都在 `blockedit` 那边。
    ///
    /// 与 `apply_block_edit` 的分工：那条是"用户手动改一块"的老路（就地改列），
    /// 这条是"模型一次改几块"的新路（正文落红 + 全程记账 + 每会话封顶）。
    fn apply_unit_edits(
        &mut self,
        message_id: &MessageId,
        ops: &[nmoi_core::edit::BlockOp],
    ) -> Result<nmoi_core::session::UnitEditResult, String> {
        Storage::apply_unit_edits(self, message_id, ops)
            .map(|outcome| nmoi_core::session::UnitEditResult {
                blocks: outcome.blocks,
                unchanged: outcome.unchanged,
                removed: outcome.removed,
            })
            .map_err(|error| error.to_string())
    }
}

#[cfg(test)]
mod conversation_admin_tests {
    use super::*;

    /// 改标题 / 删会话（0.15d）：这两条都不动消息，容易在"删了但列表还在"上翻车。
    #[test]
    fn rename_then_delete_is_idempotent() {
        let mut storage = Storage::open_in_memory().expect("内存库");
        let session = storage
            .create_conversation("旧标题", "dev", "default")
            .expect("建会话");

        storage
            .rename_conversation(session, " 新标题 ")
            .expect("改名");
        assert!(storage.conversation_exists(session).expect("查"));

        // 空标题当场拒绝：列表里一行没有名字，用户就再也认不出它。
        assert!(storage.rename_conversation(session, "   ").is_err());

        assert!(storage.delete_conversation(session).expect("删"));
        assert!(!storage.conversation_exists(session).expect("查"));
        // 再删一次：返回 false，不报错（两个入口同时点到是真会发生的）。
        assert!(!storage.delete_conversation(session).expect("再删"));
    }
}
