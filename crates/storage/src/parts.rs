//! `message_parts` 的读写（D256）。
//!
//! 为什么单独一个文件：块是"库里存的就是界面上画的那几块"这件事的落点，
//! 写入路径有三条（追加单元、块级编辑、切版本），三条各写一份 INSERT 迟早对不上。

use rusqlite::{params, Connection, Transaction};

use nmoi_core::message::{Message, Part};

use crate::StorageError;

/// 把一条消息的全部块写进 `message_parts`（先删后写：数量与内容都可能变）。
pub fn write_parts(tx: &Transaction<'_>, message: &Message) -> Result<(), StorageError> {
    write_part_list(tx, &message.id.to_string(), message.parts())
}

/// 用一份块清单换掉某条消息的全部块（版本切回时用：块的数量与种类都可能变）。
pub fn write_part_list(
    tx: &Transaction<'_>,
    message: &str,
    parts: &[Part],
) -> Result<(), StorageError> {
    tx.execute("DELETE FROM message_parts WHERE message_id = ?1", [message])?;
    for (ord, part) in parts.iter().enumerate() {
        let body = serde_json::to_string(&part.body())
            .map_err(|error| StorageError::Inconsistent(error.to_string()))?;
        tx.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![message, part.id(), ord as i64, part.kind(), body],
        )?;
    }
    // 单元形状跟着块走（D258）：一条消息就是一个单元，`unit_kind` / `unit_closed`
    // 不该有第二个答案。切版本会把工具块摘掉或放回来，这一行必须跟着改。
    tx.execute(
        "UPDATE messages
            SET unit_kind = CASE
                    WHEN EXISTS (SELECT 1 FROM message_parts
                                  WHERE message_id = ?1 AND kind = 'tool') THEN 'tool_pair'
                    ELSE 'turn' END,
                unit_closed = CASE
                    WHEN EXISTS (SELECT 1 FROM message_parts
                                  WHERE message_id = ?1 AND kind = 'tool'
                                    AND COALESCE(json_extract(body, '$.status'), 'pending') = 'pending')
                    THEN 0 ELSE 1 END
          WHERE id = ?1",
        [message],
    )?;
    Ok(())
}

/// 一份块清单的快照（版本用）。存 `[{id, kind, body}]` 而不是 `Part` 的 serde 形状：
/// 前者与库里的列一一对应，读的人不用先猜 Rust 的枚举长什么样。
pub fn snapshot(parts: &[Part]) -> Result<String, StorageError> {
    let list: Vec<serde_json::Value> = parts
        .iter()
        .map(|part| {
            serde_json::json!({
                "id": part.id(),
                "kind": part.kind(),
                "body": part.body(),
            })
        })
        .collect();
    serde_json::to_string(&list).map_err(|error| StorageError::Inconsistent(error.to_string()))
}

/// 从快照拼回块清单。坏掉的一条直接报错——猜一种块比报错危险得多。
pub fn restore(json: &str) -> Result<Vec<Part>, StorageError> {
    let list: Vec<serde_json::Value> = serde_json::from_str(json)
        .map_err(|error| StorageError::Inconsistent(format!("版本里的块不是合法 JSON：{error}")))?;
    let mut parts = Vec::with_capacity(list.len());
    for item in list {
        let id = item
            .get("id")
            .and_then(|slot| slot.as_str())
            .ok_or_else(|| StorageError::Inconsistent("版本里的块缺少 id".into()))?;
        let kind = item
            .get("kind")
            .and_then(|slot| slot.as_str())
            .ok_or_else(|| StorageError::Inconsistent(format!("块 {id} 缺少 kind")))?;
        let body = item
            .get("body")
            .ok_or_else(|| StorageError::Inconsistent(format!("块 {id} 缺少 body")))?;
        parts.push(
            Part::from_body(id.to_string(), kind, &body.to_string())
                .map_err(StorageError::Inconsistent)?,
        );
    }
    Ok(parts)
}

/// 一条消息现在的块清单快照（写版本时用）。
pub fn snapshot_of(tx: &Transaction<'_>, message: &str) -> Result<String, StorageError> {
    let mut stmt = tx.prepare(
        "SELECT part_id, kind, body FROM message_parts
          WHERE message_id = ?1 ORDER BY ord",
    )?;
    let rows = stmt.query_map([message], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut parts = Vec::new();
    for row in rows {
        let (id, kind, body) = row?;
        parts.push(Part::from_body(id, &kind, &body).map_err(StorageError::Inconsistent)?);
    }
    drop(stmt);
    snapshot(&parts)
}

/// 下一个可用的 part id：取现有里最大的号 +1。**不复用被删掉的号**。
fn next_part_id(tx: &Transaction<'_>, message: &str) -> Result<String, StorageError> {
    let highest: i64 = tx.query_row(
        "SELECT COALESCE(MAX(CAST(substr(part_id, 2) AS INTEGER)), 0)
           FROM message_parts WHERE message_id = ?1 AND part_id LIKE 'p%'",
        [message],
        |row| row.get(0),
    )?;
    Ok(format!("p{}", highest + 1))
}

/// 换掉某一类块（`text` / `reasoning`）的文字。空串 = 删掉这一块。
///
/// 位置按"思考在前、正文在后"定：新建思考插 0，新建正文插在思考之后。
pub fn set_part_text(
    tx: &Transaction<'_>,
    message: &str,
    kind: &str,
    text: &str,
) -> Result<(), StorageError> {
    let id = message.to_string();
    let existing: Option<String> = tx
        .query_row(
            "SELECT part_id FROM message_parts
              WHERE message_id = ?1 AND kind = ?2 ORDER BY ord LIMIT 1",
            params![id, kind],
            |row| row.get(0),
        )
        .ok();

    if text.is_empty() {
        if let Some(part) = existing {
            tx.execute(
                "DELETE FROM message_parts WHERE message_id = ?1 AND part_id = ?2",
                params![id, part],
            )?;
        }
        return Ok(());
    }

    let body = serde_json::json!({ "text": text }).to_string();
    match existing {
        Some(part) => {
            tx.execute(
                "UPDATE message_parts SET body = ?1 WHERE message_id = ?2 AND part_id = ?3",
                params![body, id, part],
            )?;
        }
        None => {
            let part = next_part_id(tx, &id)?;
            let ord: i64 = if kind == "reasoning" {
                0
            } else {
                // 正文排在思考之后、工具之前。
                tx.query_row(
                    "SELECT COUNT(*) FROM message_parts WHERE message_id = ?1 AND kind = 'reasoning'",
                    [&id],
                    |row| row.get(0),
                )?
            };
            // 挤开同序号的块：`UNIQUE(message_id, ord)` 不许两个块占同一格。
            tx.execute(
                "UPDATE message_parts SET ord = ord + 1 WHERE message_id = ?1 AND ord >= ?2",
                params![id, ord],
            )?;
            tx.execute(
                "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                params![id, part, ord, kind, body],
            )?;
        }
    }
    Ok(())
}

/// 一条消息现在显示的正文（第一块 `text`）。没有就是空串。
pub fn text_of(conn: &Connection, message: &str) -> Result<String, StorageError> {
    let text: Option<String> = conn
        .query_row(
            "SELECT json_extract(body, '$.text') FROM message_parts
              WHERE message_id = ?1 AND kind = 'text' ORDER BY ord LIMIT 1",
            [message],
            |row| row.get(0),
        )
        .ok()
        .flatten();
    Ok(text.unwrap_or_default())
}

/// 一条消息的思考（第一块 `reasoning`）。
pub fn reasoning_of(conn: &Connection, message: &str) -> Result<Option<String>, StorageError> {
    let text: Option<String> = conn
        .query_row(
            "SELECT json_extract(body, '$.text') FROM message_parts
              WHERE message_id = ?1 AND kind = 'reasoning' ORDER BY ord LIMIT 1",
            [message],
            |row| row.get(0),
        )
        .ok()
        .flatten();
    Ok(text)
}

/// 读一组块（一次查完，不是每条消息一次）。
pub fn read_parts(
    stmt: &mut rusqlite::Statement<'_>,
    key: &str,
) -> Result<std::collections::HashMap<String, Vec<Part>>, StorageError> {
    let rows = stmt.query_map([key], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut out: std::collections::HashMap<String, Vec<Part>> = std::collections::HashMap::new();
    for row in rows {
        let (message, part_id, kind, body) = row?;
        let part = Part::from_body(part_id, &kind, &body).map_err(StorageError::Inconsistent)?;
        out.entry(message).or_default().push(part);
    }
    Ok(out)
}
