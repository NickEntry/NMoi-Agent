//! 迁移框架（设计文档 §4.7）。
//!
//! 单向、只向前、失败回滚。跨平台打开时若库版本高于代码支持，**拒绝打开**
//! 并提示升级客户端——旧客户端写坏新结构是最难恢复的一类事故。

use rusqlite::{Connection, TransactionBehavior};

use crate::schema;

pub const SCHEMA_VERSION: i64 = 5;

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

    #[test]
    fn newer_database_is_rejected() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.pragma_update(None, "user_version", 99).unwrap();
        let err = migrate(&mut conn, SCHEMA_VERSION).unwrap_err();
        assert!(matches!(err, MigrateError::SchemaTooNew { found: 99, .. }));
    }
}
