//! 隔离区与"恢复默认"（设计文档 §4.4、§4.5）。
//!
//! 铁律：
//! - 绝不自动删除：先改名 / 先复制进隔离区，再动主库；
//! - 恢复默认是显式选择：用户确认后才替换；
//! - 可回滚：隔离区保留，提供"撤销上次恢复"。

use rusqlite::types::ValueRef;
use rusqlite::{Connection, OptionalExtension};
use serde_json::{Map, Value};
use uuid::Uuid;

use crate::repo::StorageError;
use crate::validate::{now_ms, Issue, IssueCode, Repair, SemanticReport, Severity, MIN_SANE_MS};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecoveredAction {
    pub issue_id: String,
    pub action: Repair,
    pub table: String,
    pub row_key: String,
    pub quarantine_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SkippedAction {
    pub issue_id: String,
    pub reason: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RecoveryReport {
    pub batch_id: Option<String>,
    pub actions: Vec<RecoveredAction>,
    pub skipped: Vec<SkippedAction>,
}

impl RecoveryReport {
    pub fn recovered_count(&self) -> usize {
        self.actions.len()
    }
}

/// 对报告里的（可修复）问题执行"恢复默认"。
///
/// `only` 为 `None` 时处理全部可修复项；否则只处理列出的 issue id。
/// 被跳过的项进 `skipped`，不会静默吞掉。
pub fn restore_defaults(
    conn: &mut Connection,
    report: &SemanticReport,
    only: Option<&[String]>,
) -> Result<RecoveryReport, StorageError> {
    let batch_id = Uuid::now_v7().to_string();
    let now = now_ms();
    let mut out = RecoveryReport {
        batch_id: Some(batch_id.clone()),
        ..RecoveryReport::default()
    };

    for issue in &report.issues {
        if let Some(only) = only {
            if !only.iter().any(|id| id == &issue.id) {
                continue;
            }
        }
        if issue.repair == Repair::None {
            out.skipped.push(SkippedAction {
                issue_id: issue.id.clone(),
                reason: "没有可自动执行的修复动作，需要人工处理".into(),
            });
            continue;
        }
        if issue.severity != Severity::Blocking {
            // 警告项也可以修，但默认不动：它们不阻塞启动。
            out.skipped.push(SkippedAction {
                issue_id: issue.id.clone(),
                reason: "警告项，不需要阻塞修复".into(),
            });
            continue;
        }

        match apply_repair(conn, issue, &batch_id, now)? {
            Some(action) => out.actions.push(action),
            None => out.skipped.push(SkippedAction {
                issue_id: issue.id.clone(),
                reason: "目标行已不存在".into(),
            }),
        }
    }

    if out.actions.is_empty() {
        out.batch_id = None;
    }
    Ok(out)
}

fn apply_repair(
    conn: &mut Connection,
    issue: &Issue,
    batch_id: &str,
    now: i64,
) -> Result<Option<RecoveredAction>, StorageError> {
    match issue.repair {
        Repair::QuarantineRow => {
            let Some(json) =
                read_row_json(conn, issue.table, key_column(issue.table), &issue.row_key)?
            else {
                return Ok(None);
            };
            let quarantine_id =
                quarantine_row(conn, issue.table, &issue.row_key, issue.code, &json, now)?;
            delete_row(conn, issue.table, &issue.row_key)?;
            log_recovery(conn, batch_id, issue, &quarantine_id, now)?;
            Ok(Some(RecoveredAction {
                issue_id: issue.id.clone(),
                action: issue.repair,
                table: issue.table.to_string(),
                row_key: issue.row_key.clone(),
                quarantine_id,
            }))
        }
        Repair::QuarantineUnit => {
            let unit_id = issue
                .unit_id
                .clone()
                .ok_or_else(|| StorageError::Inconsistent("QuarantineUnit 缺少 unit_id".into()))?;
            let mut stmt =
                conn.prepare("SELECT id FROM messages WHERE unit_id = ?1 ORDER BY seq")?;
            let ids: Vec<String> = stmt
                .query_map([unit_id.as_str()], |row| row.get(0))?
                .collect::<Result<_, _>>()?;
            drop(stmt);
            if ids.is_empty() {
                return Ok(None);
            }
            let mut last_quarantine = String::new();
            for id in &ids {
                let Some(json) = read_row_json(conn, "messages", "id", id)? else {
                    continue;
                };
                last_quarantine = quarantine_row(conn, "messages", id, issue.code, &json, now)?;
                log_recovery(conn, batch_id, issue, &last_quarantine, now)?;
            }
            conn.execute("DELETE FROM messages WHERE unit_id = ?1", [&unit_id])?;
            Ok(Some(RecoveredAction {
                issue_id: issue.id.clone(),
                action: issue.repair,
                table: "messages".into(),
                row_key: unit_id,
                quarantine_id: last_quarantine,
            }))
        }
        Repair::RewriteClock => {
            let Some(json) = read_row_json(conn, "messages", "id", &issue.row_key)? else {
                return Ok(None);
            };
            let quarantine_id =
                quarantine_row(conn, "messages", &issue.row_key, issue.code, &json, now)?;
            conn.execute(
                "UPDATE messages
                 SET logical_clock = (
                     SELECT COALESCE(MAX(m2.logical_clock), 0) + 1
                     FROM messages m2, messages m1
                     WHERE m1.id = ?1 AND m2.conv_id = m1.conv_id AND m2.seq < m1.seq
                 )
                 WHERE id = ?1",
                [&issue.row_key],
            )?;
            log_recovery(conn, batch_id, issue, &quarantine_id, now)?;
            Ok(Some(RecoveredAction {
                issue_id: issue.id.clone(),
                action: issue.repair,
                table: "messages".into(),
                row_key: issue.row_key.clone(),
                quarantine_id,
            }))
        }
        Repair::ClampTimestamp => {
            let Some(json) = read_row_json(conn, "messages", "id", &issue.row_key)? else {
                return Ok(None);
            };
            let quarantine_id =
                quarantine_row(conn, "messages", &issue.row_key, issue.code, &json, now)?;
            conn.execute(
                "UPDATE messages SET created_at = MIN(MAX(created_at, ?2), ?3) WHERE id = ?1",
                rusqlite::params![issue.row_key, MIN_SANE_MS, now],
            )?;
            log_recovery(conn, batch_id, issue, &quarantine_id, now)?;
            Ok(Some(RecoveredAction {
                issue_id: issue.id.clone(),
                action: issue.repair,
                table: "messages".into(),
                row_key: issue.row_key.clone(),
                quarantine_id,
            }))
        }
        Repair::None => Ok(None),
    }
}

/// 撤销最近一批恢复：把隔离区的原始 JSON 整行写回。
pub fn undo_last(conn: &mut Connection) -> Result<RecoveryReport, StorageError> {
    let batch: Option<String> = conn
        .query_row(
            "SELECT batch_id FROM recovery WHERE undone_at IS NULL
             ORDER BY created_at DESC, id DESC LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;
    let Some(batch) = batch else {
        return Ok(RecoveryReport::default());
    };

    let now = now_ms();
    let mut stmt = conn.prepare(
        "SELECT r.id, r.source_table, r.row_key, r.quarantine_id, q.payload
         FROM recovery r JOIN quarantine q ON q.id = r.quarantine_id
         WHERE r.batch_id = ?1 AND r.undone_at IS NULL
         ORDER BY r.created_at ASC, r.id ASC",
    )?;
    let rows: Vec<(String, String, String, String, String)> = stmt
        .query_map([&batch], |row| {
            Ok((
                row.get(0)?,
                row.get(1)?,
                row.get(2)?,
                row.get(3)?,
                row.get(4)?,
            ))
        })?
        .collect::<Result<_, _>>()?;
    drop(stmt);

    let mut out = RecoveryReport {
        batch_id: Some(batch.clone()),
        ..RecoveryReport::default()
    };
    for (recovery_id, table, row_key, quarantine_id, payload) in rows {
        let value: Value = serde_json::from_str(&payload).map_err(|e| {
            StorageError::Inconsistent(format!("隔离区 payload 不是合法 JSON: {e}"))
        })?;
        let object = value
            .as_object()
            .ok_or_else(|| StorageError::Inconsistent("隔离区 payload 不是对象".into()))?;
        insert_json_row(conn, &table, object)?;
        conn.execute(
            "UPDATE recovery SET undone_at = ?1 WHERE id = ?2",
            rusqlite::params![now, recovery_id],
        )?;
        out.actions.push(RecoveredAction {
            issue_id: recovery_id,
            action: Repair::None,
            table,
            row_key,
            quarantine_id,
        });
    }
    Ok(out)
}

/// 隔离区里的条目数。
pub fn quarantine_count(conn: &Connection) -> Result<usize, StorageError> {
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM quarantine", [], |row| row.get(0))?;
    Ok(count as usize)
}

fn key_column(table: &str) -> &'static str {
    match table {
        "archive" => "ref",
        _ => "id",
    }
}

fn delete_row(conn: &Connection, table: &str, key: &str) -> Result<(), StorageError> {
    let sql = match table {
        "archive" => "DELETE FROM archive WHERE ref = ?1",
        "messages" => "DELETE FROM messages WHERE id = ?1",
        "dividers" => "DELETE FROM dividers WHERE id = ?1",
        other => {
            return Err(StorageError::Inconsistent(format!(
                "不允许对表 {other} 执行删除"
            )))
        }
    };
    conn.execute(sql, [key])?;
    Ok(())
}

fn quarantine_row(
    conn: &Connection,
    table: &str,
    row_key: &str,
    code: IssueCode,
    payload: &str,
    now: i64,
) -> Result<String, StorageError> {
    let id = Uuid::now_v7().to_string();
    conn.execute(
        "INSERT INTO quarantine(id, source_table, row_key, reason, payload, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        rusqlite::params![id, table, row_key, code.as_str(), payload, now],
    )?;
    Ok(id)
}

fn log_recovery(
    conn: &Connection,
    batch_id: &str,
    issue: &Issue,
    quarantine_id: &str,
    now: i64,
) -> Result<(), StorageError> {
    conn.execute(
        "INSERT INTO recovery(id, batch_id, issue_code, source_table, row_key, action, quarantine_id, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        rusqlite::params![
            Uuid::now_v7().to_string(),
            batch_id,
            issue.code.as_str(),
            issue.table,
            issue.row_key,
            format!("{:?}", issue.repair),
            quarantine_id,
            now
        ],
    )?;
    Ok(())
}

/// 读一整行为 JSON 对象。列名即 key。
fn read_row_json(
    conn: &Connection,
    table: &str,
    key_column: &str,
    key: &str,
) -> Result<Option<String>, StorageError> {
    if !matches!(table, "messages" | "archive" | "dividers") {
        return Err(StorageError::Inconsistent(format!("不允许读取表 {table}")));
    }
    let sql = format!("SELECT * FROM {table} WHERE {key_column} = ?1");
    let mut stmt = conn.prepare(&sql)?;
    let columns: Vec<String> = stmt.column_names().iter().map(|c| c.to_string()).collect();
    let mut rows = stmt.query([key])?;
    let Some(row) = rows.next()? else {
        return Ok(None);
    };
    let mut object = Map::new();
    for (index, name) in columns.iter().enumerate() {
        let value = match row.get_ref(index)? {
            ValueRef::Null => Value::Null,
            ValueRef::Integer(v) => Value::from(v),
            ValueRef::Real(v) => serde_json::Number::from_f64(v)
                .map(Value::Number)
                .unwrap_or(Value::Null),
            ValueRef::Text(bytes) => Value::String(String::from_utf8_lossy(bytes).into_owned()),
            ValueRef::Blob(bytes) => Value::String(format!("__blob_hex__:{}", hex(bytes))),
        };
        object.insert(name.clone(), value);
    }
    let json = serde_json::to_string(&Value::Object(object))
        .map_err(|e| StorageError::Inconsistent(e.to_string()))?;
    Ok(Some(json))
}

fn insert_json_row(
    conn: &Connection,
    table: &str,
    object: &Map<String, Value>,
) -> Result<(), StorageError> {
    if !matches!(table, "messages" | "archive" | "dividers") {
        return Err(StorageError::Inconsistent(format!("不允许写回表 {table}")));
    }
    if object.is_empty() {
        return Err(StorageError::Inconsistent("空行无法写回".into()));
    }
    let mut columns = Vec::with_capacity(object.len());
    let mut placeholders = Vec::with_capacity(object.len());
    let mut values = Vec::with_capacity(object.len());
    for (index, (name, value)) in object.iter().enumerate() {
        if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return Err(StorageError::Inconsistent(format!("非法列名: {name}")));
        }
        columns.push(name.clone());
        placeholders.push(format!("?{}", index + 1));
        values.push(json_to_sql(value, name)?);
    }
    let sql = format!(
        "INSERT OR REPLACE INTO {table} ({}) VALUES ({})",
        columns.join(", "),
        placeholders.join(", ")
    );
    conn.execute(&sql, rusqlite::params_from_iter(values))?;
    Ok(())
}

fn json_to_sql(value: &Value, column: &str) -> Result<rusqlite::types::Value, StorageError> {
    use rusqlite::types::Value as SqlValue;
    Ok(match value {
        Value::Null => SqlValue::Null,
        Value::Bool(v) => SqlValue::Integer(i64::from(*v)),
        Value::Number(v) => {
            if let Some(i) = v.as_i64() {
                SqlValue::Integer(i)
            } else if let Some(f) = v.as_f64() {
                SqlValue::Real(f)
            } else {
                return Err(StorageError::Inconsistent(format!(
                    "列 {column} 的数值溢出"
                )));
            }
        }
        Value::String(s) => {
            if let Some(hex) = s.strip_prefix("__blob_hex__:") {
                SqlValue::Blob(hex_to_bytes(hex)?)
            } else {
                SqlValue::Text(s.clone())
            }
        }
        _ => {
            return Err(StorageError::Inconsistent(format!(
                "列 {column} 不支持嵌套 JSON 值"
            )))
        }
    })
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn hex_to_bytes(text: &str) -> Result<Vec<u8>, StorageError> {
    if text.len() % 2 != 0 {
        return Err(StorageError::Inconsistent("blob hex 长度为奇数".into()));
    }
    let mut out = Vec::with_capacity(text.len() / 2);
    let bytes = text.as_bytes();
    for pair in bytes.chunks(2) {
        let hi = (pair[0] as char)
            .to_digit(16)
            .ok_or_else(|| StorageError::Inconsistent("blob hex 非法".into()))?;
        let lo = (pair[1] as char)
            .to_digit(16)
            .ok_or_else(|| StorageError::Inconsistent("blob hex 非法".into()))?;
        out.push((hi * 16 + lo) as u8);
    }
    Ok(out)
}
