//! 整轮替换（D263）：重试换掉的是一整轮回答，不是最后那一条。
//!
//! 折行（D258）之后，一轮回答在库里是 N 条消息（每轮一条）。重试如果只换最后一条，
//! 前面几轮的工具块留在库里——屏幕上就是"新正文 + 上一轮的工具"（2026-11-06 真机报的）。
//! 所以重试落库走这里：**最后一条用户消息之后的全部行换掉**，同时把旧的那一轮留成一版。
//!
//! 一版的快照因此也是**整轮**（`{"turn":[...]}`），切版本时整轮换回来。
//! 老库里的一版只有一条消息的块（`[{id,kind,body}]`），两种形状靠有没有 `turn` 键分开。

use rusqlite::{params, Transaction};
use serde_json::{json, Value};

use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Part, Role};
use nmoi_core::source::SourceTag;

use crate::Storage;
use crate::StorageError;

/// 一版 = 一整轮：一条一条消息存 `role / source / created_at / incomplete / parts`。
///
/// 为什么不直接 `serde_json::to_string(&messages)`：`Message` 里带着 id / seq / 单元 id，
/// 那些是**这一刻的位置**，切版本时位置会重排（轮数可能不一样）。存的只该是内容。
pub(crate) fn snapshot_turn(messages: &[Message]) -> Result<String, StorageError> {
    let list: Vec<Value> = messages
        .iter()
        .map(|message| {
            json!({
                "role": message.role.as_str(),
                "source": message.source.as_str(),
                "created_at": message.created_at,
                "incomplete": message.incomplete,
                "parts": message.parts(),
            })
        })
        .collect();
    serde_json::to_string(&json!({ "turn": list }))
        .map_err(|error| StorageError::Inconsistent(error.to_string()))
}

/// 从快照拼回这一轮的消息。`Ok(None)` = 这份快照不是整轮形状（老的一版，只管一条消息）。
pub(crate) fn restore_turn(json: &str) -> Result<Option<Vec<Message>>, StorageError> {
    let value: Value = serde_json::from_str(json)
        .map_err(|error| StorageError::Inconsistent(format!("版本里的块不是合法 JSON：{error}")))?;
    let Some(list) = value.get("turn").and_then(|slot| slot.as_array()) else {
        return Ok(None);
    };
    let mut messages = Vec::with_capacity(list.len());
    for item in list {
        let role_text = item
            .get("role")
            .and_then(|slot| slot.as_str())
            .unwrap_or("assistant");
        let role = role_text
            .parse::<Role>()
            .map_err(|_| StorageError::Inconsistent(format!("版本里的角色不认识：{role_text}")))?;
        let source_text = item
            .get("source")
            .and_then(|slot| slot.as_str())
            .unwrap_or("history");
        let source = source_text.parse::<SourceTag>().map_err(|_| {
            StorageError::Inconsistent(format!("版本里的来源不认识：{source_text}"))
        })?;
        let created_at = item
            .get("created_at")
            .and_then(|slot| slot.as_i64())
            .unwrap_or(0);
        let incomplete = item
            .get("incomplete")
            .and_then(|slot| slot.as_bool())
            .unwrap_or(false);
        let parts: Vec<Part> = serde_json::from_value(
            item.get("parts")
                .cloned()
                .unwrap_or_else(|| Value::Array(Vec::new())),
        )
        .map_err(|error| StorageError::Inconsistent(format!("版本里的块读不出来：{error}")))?;
        // id / seq 由落库那一步按新位置给，这里先占一个空位。
        let mut message = Message::text(
            MessageId::new(SessionId::new(), 0),
            role,
            source,
            String::new(),
            0,
            created_at,
            nmoi_core::clock::LogicalClock::new(0),
        );
        message.set_parts(parts);
        message.incomplete = incomplete;
        messages.push(message);
    }
    Ok(Some(messages))
}

/// 版本行在内存里的样子：搬来搬去只经过它，不经过"再查一次库"。
struct VersionRow {
    no: i64,
    origin: String,
    edited: Option<String>,
    edited_by_tool: i64,
    view: String,
    created_at: i64,
    reasoning: Option<String>,
    parts_json: Option<String>,
}

fn read_versions(tx: &Transaction<'_>, message: &str) -> Result<Vec<VersionRow>, StorageError> {
    let mut stmt = tx.prepare(
        "SELECT version_no, origin_message, tooledit_message, edited_by_tool, active_view,
                created_at, reasoning, parts_json
           FROM message_versions WHERE message_id = ?1 ORDER BY version_no",
    )?;
    let rows = stmt.query_map([message], |row| {
        Ok(VersionRow {
            no: row.get(0)?,
            origin: row.get(1)?,
            edited: row.get(2)?,
            edited_by_tool: row.get(3)?,
            view: row.get(4)?,
            created_at: row.get(5)?,
            reasoning: row.get(6)?,
            parts_json: row.get(7)?,
        })
    })?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row?);
    }
    Ok(out)
}

fn write_version(
    tx: &Transaction<'_>,
    message: &str,
    row: &VersionRow,
) -> Result<(), StorageError> {
    tx.execute(
        "INSERT INTO message_versions(message_id, version_no, origin_message, tooledit_message,
                                      edited_by_tool, active_view, created_at, reasoning, parts_json)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            message,
            row.no,
            row.origin,
            row.edited,
            row.edited_by_tool,
            row.view,
            row.created_at,
            row.reasoning,
            row.parts_json
        ],
    )?;
    Ok(())
}

/// 一条消息的 `(正文, 思考)`：版本行的两列跟着它走，版本列表里的"显示这一份"才不跑偏。
fn text_of(message: &Message) -> (String, Option<String>) {
    let reasoning = message.reasoning().filter(|text| !text.trim().is_empty());
    (message.content(), reasoning)
}

fn version_of(messages: &[Message], no: i64) -> Result<VersionRow, StorageError> {
    let anchor = messages
        .last()
        .ok_or_else(|| StorageError::Inconsistent("这一轮没有消息，不能记版本".into()))?;
    let (origin, reasoning) = text_of(anchor);
    Ok(VersionRow {
        no,
        origin,
        edited: None,
        edited_by_tool: 0,
        view: "raw".into(),
        created_at: crate::validate::now_ms(),
        reasoning,
        parts_json: Some(snapshot_turn(messages)?),
    })
}

impl Storage {
    /// 把 `after` 之后的**整轮**换成 `units`（D263）。
    ///
    /// 一个事务里做完：先快照旧的那一轮（要留版本时），再删、再写、再记版。
    /// 中途被杀就是"这一轮没发生过"，旧的那一轮原样还在——不会出现半新半旧的库。
    pub fn replace_turn(
        &mut self,
        session: SessionId,
        after: &MessageId,
        units: &[AtomicUnit],
        keep_version: bool,
    ) -> Result<Option<MessageId>, StorageError> {
        for unit in units {
            unit.validate()?;
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
        }

        let tx = self.conn.transaction()?;
        if !crate::repo::conversation_exists_in(&tx, session)? {
            return Err(StorageError::Inconsistent(format!("会话 {session} 不存在")));
        }

        // 1. 旧的那一轮：整轮快照 + 它身上已有的版本行（第 2 次之后的重试）。
        let old = crate::repo::load_tail_messages(&tx, session, after.seq())?;
        let old_anchor = old.last().map(|message| message.id.clone());
        let carried = match (&old_anchor, keep_version) {
            (Some(anchor), true) => read_versions(&tx, &anchor.to_string())?,
            _ => Vec::new(),
        };
        let old_version = if keep_version && !old.is_empty() {
            Some(version_of(&old, 1)?)
        } else {
            None
        };

        // 2. 换掉。块随外键级联；版本行跟着消息一起走（ON DELETE CASCADE）。
        tx.execute(
            "DELETE FROM messages WHERE conv_id = ?1 AND seq > ?2",
            params![session.to_string(), after.seq() as i64],
        )?;
        for unit in units {
            crate::repo::insert_unit_rows(&tx, session, unit)?;
        }
        // 分界线画在这一轮末尾的话，跟着搬到新的末尾——不搬就是一条指向不存在 seq 的线，
        // 窗口会静默退回整条历史（压缩白做了）。
        if let Some(last) = units.last().and_then(|unit| unit.messages.last()) {
            tx.execute(
                "UPDATE dividers SET after_seq = ?1 WHERE conv_id = ?2 AND after_seq > ?3",
                params![
                    last.id.seq() as i64,
                    session.to_string(),
                    after.seq() as i64
                ],
            )?;
        }

        // 3. 版本：旧那一轮是第 1 版，这一轮是下一版。
        let anchor = units
            .last()
            .and_then(|unit| unit.messages.last())
            .map(|message| message.id.clone());
        if keep_version {
            if let Some(anchor) = anchor.as_ref() {
                let fresh = crate::repo::load_tail_messages(&tx, session, after.seq())?;
                let mut rows = carried;
                if rows.is_empty() {
                    if let Some(version) = old_version {
                        rows.push(version);
                    }
                }
                let next = rows.last().map(|row| row.no + 1).unwrap_or(1);
                rows.push(version_of(&fresh, next)?);
                // 超上限：丢最旧的一版（当前这一版刚进来，永远不会是被丢的那个）。
                while rows.len() as i64 > crate::versions::MAX_VERSIONS {
                    rows.remove(0);
                }
                for (index, row) in rows.iter_mut().enumerate() {
                    row.no = index as i64 + 1;
                }
                tx.execute(
                    "DELETE FROM message_versions WHERE message_id = ?1",
                    [anchor.to_string()],
                )?;
                for row in &rows {
                    write_version(&tx, &anchor.to_string(), row)?;
                }
                tx.execute(
                    "UPDATE messages SET version_count = ?1, version_active = ?2 WHERE id = ?3",
                    params![
                        rows.len() as i64,
                        rows.last().map(|row| row.no).unwrap_or(0),
                        anchor.to_string()
                    ],
                )?;
            }
        }

        tx.execute(
            "UPDATE conversations SET updated_at = ?1 WHERE id = ?2",
            params![crate::validate::now_ms(), session.to_string()],
        )?;
        tx.commit()?;
        Ok(anchor)
    }
}

/// 切版本时把**整轮**换回来：删掉现在这一轮，再按快照写回去。
///
/// 位置会重排（两版的轮数可能不一样），所以消息 id 也跟着重排——版本行要搬到新的落点上，
/// 否则切完之后界面上那颗 `<n/n>` 会消失。
pub(crate) fn restore_turn_rows(
    tx: &Transaction<'_>,
    anchor: &str,
    version_no: i64,
    messages: Vec<Message>,
) -> Result<MessageId, StorageError> {
    let parsed = anchor.parse::<MessageId>().map_err(|error| {
        StorageError::Inconsistent(format!("落点 {anchor} 不是消息 id：{error}"))
    })?;
    let session = parsed.session();
    // 这一轮从哪儿开始：最后一条用户消息之后。
    let start: i64 = tx.query_row(
        "SELECT COALESCE(MAX(seq), 0) FROM messages
          WHERE conv_id = ?1 AND role = 'user' AND seq < ?2",
        params![session.to_string(), parsed.seq() as i64],
        |row| row.get(0),
    )?;
    // 版本行先读出来：删旧落点时它们会跟着消息一起级联删掉。
    let rows = read_versions(tx, anchor)?;
    tx.execute(
        "DELETE FROM messages WHERE conv_id = ?1 AND seq > ?2",
        params![session.to_string(), start],
    )?;

    let mut last = None;
    for (index, mut message) in messages.into_iter().enumerate() {
        let seq = start as u64 + 1 + index as u64;
        message.id = MessageId::new(session, seq);
        message.unit_id = message.id.unit();
        message.logical_clock = nmoi_core::clock::LogicalClock::new(seq);
        let unit = if message.tool_calls().is_empty() {
            AtomicUnit::turn(message.clone())
        } else {
            AtomicUnit::tool_pair(message.clone())
        }
        .map_err(StorageError::Unit)?;
        crate::repo::insert_unit_rows(tx, session, &unit)?;
        last = Some(message.id);
    }
    let landed = last.ok_or_else(|| StorageError::Inconsistent("这一版里一条消息都没有".into()))?;
    tx.execute(
        "UPDATE dividers SET after_seq = ?1 WHERE conv_id = ?2 AND after_seq > ?3",
        params![landed.seq() as i64, session.to_string(), start],
    )?;
    for row in &rows {
        write_version(tx, &landed.to_string(), row)?;
    }
    tx.execute(
        "UPDATE messages SET version_count = ?1, version_active = ?2 WHERE id = ?3",
        params![rows.len() as i64, version_no, landed.to_string()],
    )?;
    Ok(landed)
}
