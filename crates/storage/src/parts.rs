//! `message_parts` 的读写（D256）。
//!
//! 为什么单独一个文件：块是"库里存的就是界面上画的那几块"这件事的落点，
//! 写入路径有三条（追加单元、块级编辑、切版本），三条各写一份 INSERT 迟早对不上。

use rusqlite::{params, Connection, Transaction};

use nmoi_core::message::{Message, Part};

use crate::StorageError;

/// 把一条消息的全部块写进 `message_parts`（先删后写：数量与内容都可能变）。
pub fn write_parts(tx: &Transaction<'_>, message: &Message) -> Result<(), StorageError> {
    tx.execute(
        "DELETE FROM message_parts WHERE message_id = ?1",
        [message.id.to_string()],
    )?;
    for (ord, part) in message.parts().iter().enumerate() {
        let body = serde_json::to_string(&part.body())
            .map_err(|error| StorageError::Inconsistent(error.to_string()))?;
        tx.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                message.id.to_string(),
                part.id(),
                ord as i64,
                part.kind(),
                body
            ],
        )?;
    }
    Ok(())
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
