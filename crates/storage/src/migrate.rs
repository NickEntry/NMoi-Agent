//! 迁移框架（设计文档 §4.7）。
//!
//! 单向、只向前、失败回滚。跨平台打开时若库版本高于代码支持，**拒绝打开**
//! 并提示升级客户端——旧客户端写坏新结构是最难恢复的一类事故。

use rusqlite::{Connection, TransactionBehavior};

use crate::schema;

pub const SCHEMA_VERSION: i64 = 17;

pub struct Migration {
    pub version: i64,
    pub sql: &'static str,
}

pub const MIGRATIONS: &[Migration] = &[
    Migration {
        version: 1,
        sql: schema::SCHEMA_V1,
    },
    Migration {
        version: 2,
        sql: schema::SCHEMA_V2,
    },
    Migration {
        version: 3,
        sql: schema::SCHEMA_V3,
    },
    Migration {
        version: 4,
        sql: schema::SCHEMA_V4,
    },
    Migration {
        version: 5,
        sql: schema::SCHEMA_V5,
    },
    Migration {
        version: 6,
        sql: schema::SCHEMA_V6,
    },
    Migration {
        version: 7,
        sql: schema::SCHEMA_V7,
    },
    Migration {
        version: 8,
        sql: schema::SCHEMA_V8,
    },
    Migration {
        version: 9,
        sql: schema::SCHEMA_V9,
    },
    Migration {
        version: 10,
        sql: schema::SCHEMA_V10,
    },
    Migration {
        version: 11,
        sql: schema::SCHEMA_V11,
    },
    Migration {
        version: 12,
        sql: schema::SCHEMA_V12,
    },
    Migration {
        version: 13,
        sql: schema::SCHEMA_V13,
    },
    Migration {
        version: 14,
        sql: schema::SCHEMA_V14,
    },
    Migration {
        version: 15,
        sql: schema::SCHEMA_V15,
    },
    Migration {
        version: 16,
        sql: schema::SCHEMA_V16,
    },
    Migration {
        version: 17,
        sql: schema::SCHEMA_V17,
    },
];

#[derive(Debug, thiserror::Error)]
pub enum MigrateError {
    #[error("库的 schema_version={found} 高于客户端支持的 {supported}，请升级客户端")]
    SchemaTooNew { found: i64, supported: i64 },
    #[error("没有版本 {version} 的迁移脚本")]
    MissingMigration { version: i64 },
    #[error("迁移到版本 {version} 失败: {source}")]
    Failed {
        version: i64,
        #[source]
        source: rusqlite::Error,
    },
    #[error("读取/写入 schema_version 失败: {0}")]
    Meta(#[from] rusqlite::Error),
}

/// 当前库的 `user_version`。
pub fn current_version(conn: &Connection) -> Result<i64, MigrateError> {
    Ok(conn.query_row("PRAGMA user_version", [], |row| row.get(0))?)
}

/// 迁移到 `target`。已经追上时直接返回。
pub fn migrate(conn: &mut Connection, target: i64) -> Result<i64, MigrateError> {
    let current = current_version(conn)?;
    if current > target {
        return Err(MigrateError::SchemaTooNew {
            found: current,
            supported: target,
        });
    }
    if current == target {
        return Ok(current);
    }

    for migration in MIGRATIONS.iter().filter(|m| m.version > current) {
        if migration.version > target {
            break;
        }
        // 每个版本一个事务：失败即整体回滚，不会留下"改了一半"的库。
        let tx = conn
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|source| MigrateError::Failed {
                version: migration.version,
                source,
            })?;
        tx.execute_batch(migration.sql)
            .map_err(|source| MigrateError::Failed {
                version: migration.version,
                source,
            })?;
        tx.pragma_update(None, "user_version", migration.version)
            .map_err(|source| MigrateError::Failed {
                version: migration.version,
                source,
            })?;
        tx.execute(
            "INSERT INTO meta(key, value) VALUES ('schema_version', ?1)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            [migration.version.to_string()],
        )
        .map_err(|source| MigrateError::Failed {
            version: migration.version,
            source,
        })?;
        tx.commit().map_err(|source| MigrateError::Failed {
            version: migration.version,
            source,
        })?;
    }

    let after = current_version(conn)?;
    if after < target {
        return Err(MigrateError::MissingMigration { version: target });
    }
    Ok(after)
}

/// `meta` 表里的 schema_version 与 `PRAGMA user_version` 是否一致。
pub fn meta_version(conn: &Connection) -> Result<Option<i64>, rusqlite::Error> {
    let exists: i64 = conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='meta'",
        [],
        |row| row.get(0),
    )?;
    if exists == 0 {
        return Ok(None);
    }
    let mut stmt = conn.prepare("SELECT value FROM meta WHERE key = 'schema_version'")?;
    let mut rows = stmt.query([])?;
    match rows.next()? {
        Some(row) => {
            let value: String = row.get(0)?;
            Ok(value.parse().ok())
        }
        None => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_database_migrates_to_target() {
        let mut conn = Connection::open_in_memory().unwrap();
        assert_eq!(current_version(&conn).unwrap(), 0);
        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);
        assert_eq!(meta_version(&conn).unwrap(), Some(SCHEMA_VERSION));
        // 幂等
        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);
    }

    /// v14 → v15：三列回填成 blocks，顺序是**思考 → 正文 → 工具**（D256）。
    #[test]
    fn v14_rows_become_parts_in_display_order() {
        let mut conn = Connection::open_in_memory().unwrap();
        assert_eq!(migrate(&mut conn, 14).unwrap(), 14);
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 0, 0, 14, 'dev')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, reasoning, form, tokens, created_at, logical_clock, tool_calls)
             VALUES ('m1', 'c1', 'u1', 'tool_pair', 1, 1, 'assistant', 'tool_call',
                     '我先看看', '想一想', 'F1', 1, 0, 1,
                     '[{\"id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}]')",
            [],
        )
        .unwrap();

        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);

        let mut stmt = conn
            .prepare("SELECT part_id, ord, kind, body FROM message_parts WHERE message_id = 'm1' ORDER BY ord")
            .unwrap();
        let rows: Vec<(String, i64, String, String)> = stmt
            .query_map([], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
            })
            .unwrap()
            .map(|row| row.unwrap())
            .collect();
        let kinds: Vec<&str> = rows.iter().map(|(_, _, kind, _)| kind.as_str()).collect();
        assert_eq!(
            kinds,
            vec!["reasoning", "text", "tool"],
            "顺序就是界面上从上到下"
        );
        assert_eq!(rows[1].3, r#"{"text":"我先看看"}"#);
        assert_eq!(rows[2].0, "p3", "工具块的 id 接在后面");
        assert!(rows[2].3.contains("read_file"));

        // 三列已经删掉：再插一条不带它们的消息应该成功。
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  form, tokens, created_at, logical_clock)
             VALUES ('m2', 'c1', 'u2', 'turn', 1, 2, 'user', 'history', 'F1', 1, 0, 2)",
            [],
        )
        .unwrap();
    }

    #[test]
    fn v1_database_upgrades_to_v2_without_losing_rows() {
        let mut conn = Connection::open_in_memory().unwrap();
        // 只迁到 v1：模拟"老客户端建的库"
        assert_eq!(migrate(&mut conn, 1).unwrap(), 1);
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 0, 0, 1, 'dev')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock)
             VALUES ('m1', 'c1', 'u1', 'turn', 1, 1, 'user', 'history', 'hi', 'full', 1, 0, 1)",
            [],
        )
        .unwrap();

        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);

        // 老行的新列默认 0（= 完整），这正是我们要的语义，不需要回填
        let incomplete: i64 = conn
            .query_row(
                "SELECT incomplete FROM messages WHERE id = 'm1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(incomplete, 0);
        let rows: i64 = conn
            .query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))
            .unwrap();
        assert_eq!(rows, 1, "迁移不能丢行");
        assert_eq!(meta_version(&conn).unwrap(), Some(SCHEMA_VERSION));
    }

    #[test]
    fn v3_database_gains_nullable_model_column() {
        let mut conn = Connection::open_in_memory().unwrap();
        assert_eq!(migrate(&mut conn, 3).unwrap(), 3);
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 0, 0, 3, 'dev')",
            [],
        )
        .unwrap();

        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);

        // 老行没有模型名，只能是 NULL——不能填成空串，否则"不知道"和"模型叫空串"分不开
        let model: Option<String> = conn
            .query_row(
                "SELECT model FROM conversations WHERE id = 'c1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(model, None);
        let rows: i64 = conn
            .query_row("SELECT COUNT(*) FROM conversations", [], |row| row.get(0))
            .unwrap();
        assert_eq!(rows, 1, "迁移不能丢行");
    }

    /// v15 → v16：工具结果并进调用块，`role=tool` 的行消失，分界线跟着搬（D258）。
    #[test]
    fn v15_tool_rows_become_output_of_the_call_part() {
        let mut conn = Connection::open_in_memory().unwrap();
        assert_eq!(migrate(&mut conn, 15).unwrap(), 15);
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 0, 0, 15, 'dev')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  form, tokens, created_at, logical_clock)
             VALUES ('m1', 'c1', 'u:01a1a7ed-0000-0000-0000-000000000000:1', 'tool_pair', 0, 1,
                     'assistant', 'tool_call', 'F1', 1, 0, 1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  form, tokens, created_at, logical_clock, tool_call_id)
             VALUES ('m2', 'c1', 'u:01a1a7ed-0000-0000-0000-000000000000:1', 'tool_pair', 0, 2,
                     'tool', 'tool_result', 'F1', 1, 0, 2, 'c1')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES ('m1', 'p1', 0, 'tool',
                     '{\"call_id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES ('m2', 'p1', 0, 'text', '{\"text\":\"文件内容\"}')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO dividers(id, conv_id, after_seq, kind, note, created_at)
             VALUES ('d1', 'c1', 2, 'user', NULL, 0)",
            [],
        )
        .unwrap();

        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);

        let body: String = conn
            .query_row(
                "SELECT body FROM message_parts WHERE message_id = 'm1' AND kind = 'tool'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert!(body.contains("文件内容"), "{body}");
        assert!(body.contains("\"status\":\"ok\""), "{body}");

        let rows: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM messages WHERE role = 'tool'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(rows, 0, "工具行整体并进块里");

        let after: i64 = conn
            .query_row(
                "SELECT after_seq FROM dividers WHERE id = 'd1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(after, 1, "分界线要跟着搬到调用那条消息上");

        let closed: i64 = conn
            .query_row(
                "SELECT unit_closed FROM messages WHERE id = 'm1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(closed, 1);
    }

    /// v16 → v17：版本带上整份块。当前版快照现在的块，其他版本只有正文与思考。
    #[test]
    fn v16_versions_gain_a_parts_snapshot() {
        let mut conn = Connection::open_in_memory().unwrap();
        assert_eq!(migrate(&mut conn, 16).unwrap(), 16);
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', 't', 0, 0, 16, 'dev')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  form, tokens, created_at, logical_clock, version_count, version_active)
             VALUES ('m1', 'c1', 'u:01a1a7ed-0000-0000-0000-000000000000:1', 'tool_pair', 1, 1,
                     'assistant', 'tool_call', 'F1', 1, 0, 1, 2, 1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES ('m1','p1',0,'text','{\"text\":\"我先看看\"}')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO message_parts(message_id, part_id, ord, kind, body)
             VALUES ('m1','p2',1,'tool',
                     '{\"call_id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\",\"output\":\"x\",\"status\":\"ok\"}')",
            [],
        )
        .unwrap();
        for (no, text) in [(1, "我先看看"), (2, "换个说法")] {
            conn.execute(
                "INSERT INTO message_versions(message_id, version_no, origin_message, tooledit_message,
                                              edited_by_tool, active_view, created_at)
                 VALUES ('m1', ?1, ?2, NULL, 0, 'raw', 0)",
                rusqlite::params![no, text],
            )
            .unwrap();
        }

        assert_eq!(migrate(&mut conn, SCHEMA_VERSION).unwrap(), SCHEMA_VERSION);

        let active: String = conn
            .query_row(
                "SELECT parts_json FROM message_versions WHERE message_id='m1' AND version_no=1",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert!(
            active.contains("\"tool\""),
            "当前版要快照现在的块：{active}"
        );

        let old: String = conn
            .query_row(
                "SELECT parts_json FROM message_versions WHERE message_id='m1' AND version_no=2",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert!(!old.contains("\"tool\""), "旧版本不回溯工具块：{old}");
        assert!(old.contains("换个说法"), "{old}");
    }

    #[test]
    fn newer_database_is_rejected() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.pragma_update(None, "user_version", 99).unwrap();
        let err = migrate(&mut conn, SCHEMA_VERSION).unwrap_err();
        assert!(matches!(err, MigrateError::SchemaTooNew { found: 99, .. }));
    }
}
