//! L3 语义校验（设计文档 §4.4）。
//!
//! L1 是数据库级损坏，L3 是**应用级语义损坏**：库能打开、SQL 能跑，但记录之间的
//! 不变量已经坏了。原则：
//!
//! 1. 隔离而非删除；
//! 2. 阻塞而非静默修复；
//! 3. 恢复默认是显式选择，替换前先把原值写入隔离区；
//! 4. 可回滚。

use std::collections::BTreeMap;
use std::time::Instant;

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

/// 2000-01-01T00:00:00Z，早于它的时间戳一律视为时钟异常。
pub const MIN_SANE_MS: i64 = 946_684_800_000;
/// 允许的未来偏移（1 天），跨时区与轻微时钟漂移。
pub const FUTURE_TOLERANCE_MS: i64 = 86_400_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Severity {
    /// 阻塞启动，等用户决定。
    Blocking,
    /// 记录但不阻塞。
    Warning,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum IssueCode {
    OrphanMessage,
    MalformedToolCalls,
    ToolResultInTurnUnit,
    UnpairedToolCall,
    UnpairedToolResult,
    MissingArchiveRef,
    NonMonotonicClock,
    DuplicateMessageSeq,
    ForeignUnitId,
    SuspiciousTimestamp,
    ArchiveHashMismatch,
    DividerOutOfRange,
    DividerSplitsUnit,
    DividerNothingBelow,
}

impl IssueCode {
    pub fn as_str(self) -> &'static str {
        match self {
            IssueCode::OrphanMessage => "orphan_message",
            IssueCode::MalformedToolCalls => "malformed_tool_calls",
            IssueCode::ToolResultInTurnUnit => "tool_result_in_turn_unit",
            IssueCode::UnpairedToolCall => "unpaired_tool_call",
            IssueCode::UnpairedToolResult => "unpaired_tool_result",
            IssueCode::MissingArchiveRef => "missing_archive_ref",
            IssueCode::NonMonotonicClock => "non_monotonic_clock",
            IssueCode::DuplicateMessageSeq => "duplicate_message_seq",
            IssueCode::ForeignUnitId => "foreign_unit_id",
            IssueCode::SuspiciousTimestamp => "suspicious_timestamp",
            IssueCode::ArchiveHashMismatch => "archive_hash_mismatch",
            IssueCode::DividerOutOfRange => "divider_out_of_range",
            IssueCode::DividerSplitsUnit => "divider_splits_unit",
            IssueCode::DividerNothingBelow => "divider_nothing_below",
        }
    }
}

/// 恢复默认时对该条记录做什么。动作是机械的，不做语义猜测。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Repair {
    /// 整行移入隔离区后删除。
    QuarantineRow,
    /// 整个原子单元（所有同 unit_id 的消息）移入隔离区后删除。
    QuarantineUnit,
    /// 重排逻辑时钟（原值进隔离区）。
    RewriteClock,
    /// 把时间戳夹到合理区间（原值进隔离区）。
    ClampTimestamp,
    /// 无法自动修复，只能人工处理。
    None,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Issue {
    /// 稳定 ID：`{code}:{table}:{row_key}`，恢复流程与 undo 都用它。
    pub id: String,
    pub code: IssueCode,
    pub severity: Severity,
    pub table: &'static str,
    pub row_key: String,
    pub unit_id: Option<String>,
    pub detail: String,
    pub repair: Repair,
}

impl Issue {
    fn new(
        code: IssueCode,
        severity: Severity,
        table: &'static str,
        row_key: String,
        unit_id: Option<String>,
        detail: String,
        repair: Repair,
    ) -> Self {
        Self {
            id: format!("{}:{}:{}", code.as_str(), table, row_key),
            code,
            severity,
            table,
            row_key,
            unit_id,
            detail,
            repair,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct SemanticReport {
    pub issues: Vec<Issue>,
    pub scanned: usize,
    pub elapsed_ms: u128,
}

impl SemanticReport {
    pub fn blocking(&self) -> Vec<&Issue> {
        self.issues
            .iter()
            .filter(|issue| issue.severity == Severity::Blocking)
            .collect()
    }

    pub fn has_blocking(&self) -> bool {
        self.issues
            .iter()
            .any(|issue| issue.severity == Severity::Blocking)
    }

    pub fn is_clean(&self) -> bool {
        self.issues.is_empty()
    }

    /// 按错误码计数，给 UI 显示"坏在哪"。
    pub fn summary(&self) -> BTreeMap<&'static str, usize> {
        let mut map = BTreeMap::new();
        for issue in &self.issues {
            *map.entry(issue.code.as_str()).or_insert(0) += 1;
        }
        map
    }

    pub fn find(&self, id: &str) -> Option<&Issue> {
        self.issues.iter().find(|issue| issue.id == id)
    }
}

/// 逐记录校验不变量。顺序固定，便于测试与复现。
pub fn scan(conn: &Connection) -> Result<SemanticReport, rusqlite::Error> {
    let started = Instant::now();
    let mut issues = Vec::new();

    issues.extend(check_orphans(conn)?);
    issues.extend(check_malformed_tool_calls(conn)?);
    issues.extend(check_tool_result_in_turn_unit(conn)?);
    issues.extend(check_unpaired_results(conn)?);
    issues.extend(check_unpaired_calls(conn)?);
    issues.extend(check_missing_refs(conn)?);
    issues.extend(check_duplicate_seq(conn)?);
    issues.extend(check_foreign_unit_ids(conn)?);
    issues.extend(check_clock_monotonic(conn)?);
    issues.extend(check_timestamps(conn, now_ms())?);
    issues.extend(check_dividers(conn)?);

    let scanned: usize = conn.query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))?;

    Ok(SemanticReport {
        issues,
        scanned,
        elapsed_ms: started.elapsed().as_millis(),
    })
}

fn check_orphans(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT m.id, m.unit_id, m.conv_id
         FROM messages m
         LEFT JOIN conversations c ON c.id = m.conv_id
         WHERE c.id IS NULL",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, conv_id) = row?;
        issues.push(Issue::new(
            IssueCode::OrphanMessage,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!("消息 {id} 指向不存在的会话 {conv_id}"),
            Repair::QuarantineRow,
        ));
    }
    Ok(issues)
}

fn check_malformed_tool_calls(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn
        .prepare("SELECT id, unit_id, tool_calls FROM messages WHERE json_valid(tool_calls) = 0")?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, calls) = row?;
        issues.push(Issue::new(
            IssueCode::MalformedToolCalls,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!("消息 {id} 的 tool_calls 不是合法 JSON: {calls}"),
            Repair::QuarantineUnit,
        ));
    }
    Ok(issues)
}

fn check_tool_result_in_turn_unit(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT id, unit_id FROM messages WHERE role = 'tool' AND unit_kind <> 'tool_pair'",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id) = row?;
        issues.push(Issue::new(
            IssueCode::ToolResultInTurnUnit,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!("消息 {id} 是工具结果，却归属 turn 单元"),
            Repair::QuarantineUnit,
        ));
    }
    Ok(issues)
}

fn check_unpaired_results(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT r.id, r.unit_id, r.tool_call_id
         FROM messages r
         WHERE r.role = 'tool' AND NOT EXISTS (
             SELECT 1
             FROM messages a, json_each(CASE WHEN json_valid(a.tool_calls) THEN a.tool_calls ELSE '[]' END) j
             WHERE a.unit_id = r.unit_id
               AND a.role = 'assistant'
               AND json_extract(j.value, '$.id') = r.tool_call_id
         )",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, Option<String>>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, call_id) = row?;
        issues.push(Issue::new(
            IssueCode::UnpairedToolResult,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!(
                "工具结果 {id} 找不到对应调用（tool_call_id={}）",
                call_id.unwrap_or_else(|| "null".into())
            ),
            Repair::QuarantineUnit,
        ));
    }
    Ok(issues)
}

fn check_unpaired_calls(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT a.id, a.unit_id, json_extract(j.value, '$.id')
         FROM messages a, json_each(CASE WHEN json_valid(a.tool_calls) THEN a.tool_calls ELSE '[]' END) j
         WHERE a.role = 'assistant'
           AND a.unit_kind = 'tool_pair'
           AND NOT EXISTS (
               SELECT 1 FROM messages r
               WHERE r.unit_id = a.unit_id
                 AND r.tool_call_id = json_extract(j.value, '$.id')
           )",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, Option<String>>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, call_id) = row?;
        issues.push(Issue::new(
            IssueCode::UnpairedToolCall,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!(
                "工具调用 {id} 缺少结果（call_id={}），单元未闭合",
                call_id.unwrap_or_else(|| "null".into())
            ),
            Repair::QuarantineUnit,
        ));
    }
    Ok(issues)
}

fn check_missing_refs(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT m.id, m.unit_id, m.archived_ref
         FROM messages m
         WHERE m.archived_ref IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM archive a WHERE a.ref = m.archived_ref)",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, rf) = row?;
        issues.push(Issue::new(
            IssueCode::MissingArchiveRef,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id),
            format!("消息 {id} 的 archived_ref={rf} 在归档里不存在"),
            Repair::QuarantineRow,
        ));
    }
    Ok(issues)
}

fn check_duplicate_seq(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT conv_id, seq, COUNT(*) FROM messages GROUP BY conv_id, seq HAVING COUNT(*) > 1",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, i64>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (conv, seq, count) = row?;
        issues.push(Issue::new(
            IssueCode::DuplicateMessageSeq,
            Severity::Blocking,
            "messages",
            format!("{conv}:{seq}"),
            None,
            format!("会话 {conv} 的 seq={seq} 出现 {count} 次，消息 ID 命名空间被破坏"),
            Repair::None,
        ));
    }
    Ok(issues)
}

fn check_foreign_unit_ids(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT id, unit_id, conv_id FROM messages
         WHERE unit_id NOT LIKE 'u:' || conv_id || ':%'",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, unit_id, conv) = row?;
        issues.push(Issue::new(
            IssueCode::ForeignUnitId,
            Severity::Blocking,
            "messages",
            id.clone(),
            Some(unit_id.clone()),
            format!("消息 {id} 的 unit_id={unit_id} 不属于会话 {conv}"),
            Repair::QuarantineRow,
        ));
    }
    Ok(issues)
}

fn check_clock_monotonic(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT conv_id, id, seq, logical_clock FROM messages ORDER BY conv_id, seq, id",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, i64>(2)?,
            row.get::<_, i64>(3)?,
        ))
    })?;
    let mut issues = Vec::new();
    let mut current_conv: Option<String> = None;
    let mut previous: i64 = 0;
    for row in rows {
        let (conv, id, _seq, clock) = row?;
        if current_conv.as_deref() != Some(conv.as_str()) {
            current_conv = Some(conv);
            previous = 0;
        }
        if clock <= previous {
            issues.push(Issue::new(
                IssueCode::NonMonotonicClock,
                Severity::Blocking,
                "messages",
                id.clone(),
                None,
                format!("消息 {id} 的逻辑时钟 {clock} 不大于上一条的 {previous}"),
                Repair::RewriteClock,
            ));
        }
        previous = previous.max(clock);
    }
    Ok(issues)
}

fn check_timestamps(conn: &Connection, now: i64) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn
        .prepare("SELECT id, created_at FROM messages WHERE created_at < ?1 OR created_at > ?2")?;
    let rows = stmt.query_map([MIN_SANE_MS, now + FUTURE_TOLERANCE_MS], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, created_at) = row?;
        issues.push(Issue::new(
            IssueCode::SuspiciousTimestamp,
            Severity::Warning,
            "messages",
            id.clone(),
            None,
            format!("消息 {id} 的时间戳 {created_at} 超出合理范围"),
            Repair::ClampTimestamp,
        ));
    }
    Ok(issues)
}

/// 分界线三查：位置有消息、落在单元边界、下面还有内容。
fn check_dividers(conn: &Connection) -> Result<Vec<Issue>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT d.id, d.conv_id, d.after_seq,
                EXISTS(SELECT 1 FROM messages m WHERE m.conv_id = d.conv_id AND m.seq = d.after_seq),
                EXISTS(SELECT 1 FROM messages m WHERE m.conv_id = d.conv_id AND m.seq = d.after_seq
                         AND EXISTS(SELECT 1 FROM messages n WHERE n.conv_id = d.conv_id
                                    AND n.unit_id = m.unit_id AND n.seq > d.after_seq)),
                EXISTS(SELECT 1 FROM messages m WHERE m.conv_id = d.conv_id AND m.seq > d.after_seq)
         FROM dividers d",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, i64>(2)?,
            row.get::<_, i64>(3)?,
            row.get::<_, i64>(4)?,
            row.get::<_, i64>(5)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (id, conv, after_seq, exists, splits, has_below) = row?;
        let make = |code: IssueCode, detail: String| {
            Issue::new(
                code,
                Severity::Blocking,
                "dividers",
                id.clone(),
                None,
                detail,
                Repair::QuarantineRow,
            )
        };
        if exists == 0 {
            issues.push(make(
                IssueCode::DividerOutOfRange,
                format!("分界线 {id} 的位置 seq={after_seq} 在会话 {conv} 里没有对应消息"),
            ));
        } else if splits != 0 {
            issues.push(make(
                IssueCode::DividerSplitsUnit,
                format!(
                    "分界线 {id} 落在原子单元内部（seq={after_seq}），会切开 tool_call/result 对"
                ),
            ));
        } else if has_below == 0 {
            issues.push(make(
                IssueCode::DividerNothingBelow,
                format!("分界线 {id} 下面没有内容，窗口会为空（seq={after_seq}）"),
            ));
        }
    }
    Ok(issues)
}

/// 归档内容哈希校验。默认不在启动路径跑：大归档全量哈希会很慢。
pub fn verify_archive_hashes(
    conn: &Connection,
    limit: usize,
) -> Result<Vec<Issue>, rusqlite::Error> {
    use crate::repo::sha256_hex;

    let mut stmt = conn.prepare("SELECT ref, content, content_hash FROM archive LIMIT ?1")?;
    let rows = stmt.query_map([limit as i64], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut issues = Vec::new();
    for row in rows {
        let (rf, content, hash) = row?;
        if sha256_hex(&content) != hash {
            issues.push(Issue::new(
                IssueCode::ArchiveHashMismatch,
                Severity::Blocking,
                "archive",
                rf.clone(),
                None,
                format!("归档 {rf} 的内容哈希与记录不符（内容被改写或截断）"),
                Repair::None,
            ));
        }
    }
    Ok(issues)
}

pub fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema;

    fn fresh() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(schema::SCHEMA_V1).unwrap();
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 1700000000000, 1700000000000, 1, 'test')",
            [],
        )
        .unwrap();
        conn
    }

    fn insert_message(conn: &Connection, id: &str, seq: i64, clock: i64) {
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock, tool_calls)
             VALUES (?1, 'c1', ?2, 'turn', 1, ?3, 'user', 'history', 'hi', 'F1', 1, 1700000000000, ?4, '[]')",
            rusqlite::params![id, format!("u:c1:{seq}"), seq, clock],
        )
        .unwrap();
    }

    #[test]
    fn clean_database_has_no_issues() {
        let conn = fresh();
        insert_message(&conn, "c1:1", 1, 1);
        let report = scan(&conn).unwrap();
        assert!(report.is_clean(), "{:?}", report.issues);
        assert_eq!(report.scanned, 1);
    }

    /// bundled SQLite 默认就开着外键（libsqlite3-sys 编译时给了 SQLITE_DEFAULT_FOREIGN_KEYS=1），
    /// 所以"删会话留下消息"这种造孤儿的方式会被级联删除干掉。这条测试把这个前提钉住——
    /// 夹具必须按真实损坏的样子来造：关掉外键，插一条指向不存在会话的消息。
    #[test]
    fn foreign_keys_are_enforced_by_default() {
        let conn = fresh();
        let err = conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock, tool_calls)
             VALUES ('ghost:1', 'ghost', 'u:ghost:1', 'turn', 1, 1, 'user', 'history', 'hi', 'F1',
                     1, 1700000000000, 1, '[]')",
            [],
        );
        assert!(err.is_err(), "指向不存在会话的消息必须被外键挡住");
    }

    #[test]
    fn orphan_message_is_blocking() {
        let conn = fresh();
        conn.pragma_update(None, "foreign_keys", false).unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock, tool_calls)
             VALUES ('ghost:1', 'ghost', 'u:ghost:1', 'turn', 1, 1, 'user', 'history', 'hi', 'F1',
                     1, 1700000000000, 1, '[]')",
            [],
        )
        .unwrap();

        let report = scan(&conn).unwrap();
        assert!(report.has_blocking());
        let issue = report
            .issues
            .iter()
            .find(|issue| issue.code == IssueCode::OrphanMessage)
            .expect("孤儿消息必须被报出来");
        assert_eq!(issue.repair, Repair::QuarantineRow);
        assert_eq!(issue.severity, Severity::Blocking);
    }

    #[test]
    fn non_monotonic_clock_is_detected() {
        let conn = fresh();
        insert_message(&conn, "c1:1", 1, 5);
        insert_message(&conn, "c1:2", 2, 5);
        let report = scan(&conn).unwrap();
        assert!(report
            .issues
            .iter()
            .any(|issue| issue.code == IssueCode::NonMonotonicClock));
    }

    #[test]
    fn missing_archive_ref_is_detected() {
        let conn = fresh();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock, archived_ref, tool_calls)
             VALUES ('c1:1', 'c1', 'u:c1:1', 'turn', 1, 1, 'assistant', 'history', 'ptr', 'F4', 1,
                     1700000000000, 1, 'ref#aaaaaaaaaaaaaaaa', '[]')",
            [],
        )
        .unwrap();
        let report = scan(&conn).unwrap();
        assert!(report
            .issues
            .iter()
            .any(|issue| issue.code == IssueCode::MissingArchiveRef));
    }

    #[test]
    fn suspicious_timestamp_is_only_a_warning() {
        let conn = fresh();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock, tool_calls)
             VALUES ('c1:1', 'c1', 'u:c1:1', 'turn', 1, 1, 'user', 'history', 'hi', 'F1', 1,
                     0, 1, '[]')",
            [],
        )
        .unwrap();
        let report = scan(&conn).unwrap();
        assert!(!report.has_blocking());
        assert_eq!(report.issues[0].code, IssueCode::SuspiciousTimestamp);
    }
}
