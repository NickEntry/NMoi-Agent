//! NMoi-Agent 存储层。
//!
//! 启动校验四层（设计文档 §4.4），由快到慢：
//!
//! ```text
//! L0 文件层：存在？可打开？大小合理？WAL/shm 残留？
//! L1 结构层：PRAGMA quick_check(1)，失败再跑 integrity_check
//! L2 模式层：schema_version 匹配？迁移完整？
//! L3 语义层：逐记录校验不变量 → 隔离 + 阻塞 + 询问
//! ```
//!
//! 损坏处置的铁律：绝不自动删除、在副本上诊断、恢复后重跑完整性 **和** 外键检查。

pub mod atomic;
pub mod backup;
pub mod blockedit;
pub mod calibrate;
pub mod heal;
pub mod migrate;
pub mod recover;
pub mod repo;
pub mod schema;
pub mod settings;
pub mod validate;
pub mod versions;

use std::fmt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::migrate::{MigrateError, SCHEMA_VERSION};
use crate::repo::StorageError;
use crate::validate::SemanticReport;

/// 打开后的存储句柄。
#[derive(Debug)]
pub struct Storage {
    pub(crate) conn: Connection,
    path: PathBuf,
    device: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct L0Report {
    pub path: String,
    pub file_exists: bool,
    pub size_bytes: u64,
    pub wal_present: bool,
    pub shm_present: bool,
    pub notes: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct HealthReport {
    pub path: String,
    pub l0: L0Report,
    pub integrity_ok: bool,
    pub integrity_messages: Vec<String>,
    pub integrity_elapsed_ms: u128,
    pub schema_version: i64,
    pub meta_version: Option<i64>,
    pub semantic: SemanticReport,
    pub quarantine_entries: usize,
    pub device_id: String,
}

#[derive(Debug, thiserror::Error)]
pub enum OpenError {
    #[error("存储不可用: {reason}")]
    Unavailable { reason: String },
    #[error("数据库结构损坏（{elapsed_ms}ms）：{details:?}")]
    Corrupt {
        details: Vec<String>,
        elapsed_ms: u128,
    },
    #[error("库 schema_version={found} 高于客户端支持的 {supported}")]
    SchemaTooNew { found: i64, supported: i64 },
    #[error(transparent)]
    Migration(#[from] MigrateError),
    #[error("语义损坏阻塞启动，有 {} 条待处理", .report.issues.len())]
    Semantic { report: SemanticReport },
    #[error("SQLite 错误: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Storage(#[from] StorageError),
}

/// 打开结果。阻塞不是错误——它是"等用户决定"的状态。
pub enum OpenOutcome {
    Ready(Storage),
    Blocked(Blocked),
}

/// 被 L3 拦下的库。只有显式选择恢复方式之后才会变成 [`Storage`]。
pub struct Blocked {
    conn: Connection,
    path: PathBuf,
    device: String,
    report: SemanticReport,
}

impl fmt::Debug for Blocked {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Blocked")
            .field("path", &self.path)
            .field("issues", &self.report.issues.len())
            .finish()
    }
}

impl Blocked {
    pub fn report(&self) -> &SemanticReport {
        &self.report
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// 对所有阻塞项执行"恢复默认"（先隔离原值，再修改）。
    pub fn restore_defaults(self) -> Result<(Storage, heal::RecoveryReport), OpenError> {
        self.restore_issues(None)
    }

    /// 只处理指定 issue。`None` 表示全部阻塞项。
    pub fn restore_issues(
        mut self,
        only: Option<&[String]>,
    ) -> Result<(Storage, heal::RecoveryReport), OpenError> {
        let outcome = heal::restore_defaults(&mut self.conn, &self.report, only)?;
        let after = validate::scan(&self.conn)?;
        if after.has_blocking() {
            return Err(OpenError::Semantic { report: after });
        }
        Ok((
            Storage {
                conn: self.conn,
                path: self.path,
                device: self.device,
            },
            outcome,
        ))
    }
}

/// 打开时的落盘策略（D45）。
///
/// - `durable: false`（默认）→ `synchronous = NORMAL`：抗**进程被杀**（划卡片、OOM、`kill -9`）。
///   已 `write()` 到内核的数据都在，这种事故一点都不会丢。
/// - `durable: true` → `synchronous = FULL`：每个事务多一次 fsync，抗**断电 / 内核崩溃**。
///   手机上一次几毫秒，但密集写（批量导入、压缩）会明显变慢，所以不默认开。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct OpenOptions {
    pub durable: bool,
}

impl Storage {
    /// 当前库的 `schema_version`。UI 上显示"这个库是哪一版"、测试里核对迁移，
    /// 都从这里取——不要让调用方去碰 `PRAGMA`。
    pub fn schema_version(&self) -> Result<i64, StorageError> {
        migrate::current_version(&self.conn)
            .map_err(|error| StorageError::Inconsistent(error.to_string()))
    }

    /// 给"正在跑的那一轮"开一条**独立连接**（D89）。
    ///
    /// 为什么要第二条连接：一轮可能跑几十秒，期间 UI 还要刷会话列表、读文件、
    /// 存设置。把句柄整个取走（D60 的旧做法）会让同一个库上的**所有**调用吃 `busy`，
    /// 于是 A 会话在跑，B 会话连列表都刷不了——而用户要的正是"ABC 三个会话同时跑"。
    ///
    /// 这里**不做** L0/L1/L3 校验：库刚被主连接打开过，再从头校验一遍只会让
    /// "开一轮"多花几百毫秒，且 L3 阻塞态在主连接那一步已经处理过了。
    /// 只做配置（WAL / busy_timeout / 外键）与读 device_id。
    ///
    /// 写并发交给 SQLite：WAL 下读不挡写，写与写之间按 `busy_timeout` 排队。
    pub fn attach(path: impl AsRef<Path>) -> Result<Self, OpenError> {
        let path = path.as_ref().to_path_buf();
        if !path.exists() {
            return Err(OpenError::Unavailable {
                reason: format!("库文件不存在：{}", path.display()),
            });
        }
        let conn = Connection::open(&path)?;
        configure(&conn, OpenOptions::default())?;
        let device = ensure_device_id(&conn)?;
        Ok(Self { conn, path, device })
    }

    /// 正常打开：L0 → L1 → L2 → L3。落盘策略用默认（抗杀进程，不抗断电）。
    pub fn open(path: impl AsRef<Path>) -> Result<OpenOutcome, OpenError> {
        Self::open_with(path, OpenOptions::default())
    }

    /// 带策略打开。
    pub fn open_with(
        path: impl AsRef<Path>,
        options: OpenOptions,
    ) -> Result<OpenOutcome, OpenError> {
        let path = path.as_ref().to_path_buf();
        check_l0(&path)?;

        let mut conn = Connection::open(&path)?;
        configure(&conn, options)?;

        let integrity = run_integrity(&conn)?;
        if !integrity.ok {
            return Err(OpenError::Corrupt {
                details: integrity.messages,
                elapsed_ms: integrity.elapsed_ms,
            });
        }

        let found = migrate::current_version(&conn)?;
        if found > SCHEMA_VERSION {
            return Err(OpenError::SchemaTooNew {
                found,
                supported: SCHEMA_VERSION,
            });
        }
        migrate::migrate(&mut conn, SCHEMA_VERSION)?;
        let device = ensure_device_id(&conn)?;

        let report = validate::scan(&conn)?;
        if report.has_blocking() {
            return Ok(OpenOutcome::Blocked(Blocked {
                conn,
                path,
                device,
                report,
            }));
        }

        Ok(OpenOutcome::Ready(Self { conn, path, device }))
    }

    /// 内存库，给单测与"压缩会话"的临时物化用。跳过 L0/L1。
    pub fn open_in_memory() -> Result<Self, OpenError> {
        let mut conn = Connection::open_in_memory()?;
        configure(&conn, OpenOptions::default())?;
        migrate::migrate(&mut conn, SCHEMA_VERSION)?;
        let device = ensure_device_id(&conn)?;
        Ok(Self {
            conn,
            path: PathBuf::from(":memory:"),
            device,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn device_id(&self) -> &str {
        &self.device
    }

    /// 重新体检：完整性 + 语义 + 隔离区计数。
    pub fn health(&self) -> Result<HealthReport, OpenError> {
        let integrity = run_integrity(&self.conn)?;
        let semantic = validate::scan(&self.conn)?;
        let found = migrate::current_version(&self.conn)?;
        let meta = migrate::meta_version(&self.conn)?;
        let quarantine_entries = heal::quarantine_count(&self.conn)?;
        Ok(HealthReport {
            path: self.path.display().to_string(),
            l0: inspect_l0(&self.path),
            integrity_ok: integrity.ok,
            integrity_messages: integrity.messages,
            integrity_elapsed_ms: integrity.elapsed_ms,
            schema_version: found,
            meta_version: meta,
            semantic,
            quarantine_entries,
            device_id: self.device.clone(),
        })
    }

    /// 备份并验证。
    pub fn backup(&self, dest: impl AsRef<Path>) -> Result<backup::BackupReport, StorageError> {
        backup::backup_to(&self.conn, dest.as_ref())
    }

    /// 只读打开一个库做诊断（不动主文件）。用于 §4.5 的"在副本上诊断"。
    pub fn diagnose_readonly(path: impl AsRef<Path>) -> Result<HealthReport, OpenError> {
        let path = path.as_ref();
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let integrity = run_integrity(&conn)?;
        let semantic = validate::scan(&conn)?;
        let found = migrate::current_version(&conn)?;
        let meta = migrate::meta_version(&conn)?;
        let quarantine_entries = heal::quarantine_count(&conn).unwrap_or(0);
        Ok(HealthReport {
            path: path.display().to_string(),
            l0: inspect_l0(path),
            integrity_ok: integrity.ok,
            integrity_messages: integrity.messages,
            integrity_elapsed_ms: integrity.elapsed_ms,
            schema_version: found,
            meta_version: meta,
            semantic,
            quarantine_entries,
            device_id: String::new(),
        })
    }
}

#[derive(Debug, Clone)]
struct Integrity {
    ok: bool,
    messages: Vec<String>,
    elapsed_ms: u128,
}

/// L1：先 `quick_check(1)`（100ms 预算），失败再全量 `integrity_check`。
fn run_integrity(conn: &Connection) -> Result<Integrity, OpenError> {
    let started = Instant::now();
    let mut quick_stmt = match conn.prepare("PRAGMA quick_check(1)") {
        Ok(stmt) => stmt,
        Err(err) => return Err(not_a_database(err)),
    };
    let quick: Vec<String> = quick_stmt
        .query_map([], |row| row.get(0))
        .map_err(not_a_database)?
        .collect::<Result<_, _>>()
        .map_err(not_a_database)?;
    drop(quick_stmt);
    let quick_ok = quick.len() == 1 && quick[0] == "ok";
    if quick_ok {
        return Ok(Integrity {
            ok: true,
            messages: vec!["ok".into()],
            elapsed_ms: started.elapsed().as_millis(),
        });
    }

    let mut stmt = match conn.prepare("PRAGMA integrity_check") {
        Ok(stmt) => stmt,
        Err(err) => return Err(not_a_database(err)),
    };
    let full: Vec<String> = stmt
        .query_map([], |row| row.get(0))
        .map_err(not_a_database)?
        .collect::<Result<_, _>>()
        .map_err(not_a_database)?;
    let ok = full.len() == 1 && full[0] == "ok";
    Ok(Integrity {
        ok,
        messages: if full.is_empty() { quick } else { full },
        elapsed_ms: started.elapsed().as_millis(),
    })
}

/// PRAGMA 直接报错（SQLITE_NOTADB / 文件截断）也归入"结构损坏"，
/// 这样 UI 只需要处理一种"库坏了"的状态。
fn not_a_database(err: rusqlite::Error) -> OpenError {
    OpenError::Corrupt {
        details: vec![err.to_string()],
        elapsed_ms: 0,
    }
}

fn configure(conn: &Connection, options: OpenOptions) -> Result<(), rusqlite::Error> {
    conn.busy_timeout(Duration::from_millis(5_000))?;
    conn.pragma_update(None, "foreign_keys", true)?;
    // WAL：进程被杀时已提交的事务都在；断电时由 synchronous 决定丢多少（D45）。
    let level = if options.durable { "FULL" } else { "NORMAL" };
    let _ = conn.pragma_update(None, "synchronous", level);
    // 内存库会返回 "memory"；只读文件系统会报错——两种都不算打开失败。
    let _: Result<String, _> = conn.query_row("PRAGMA journal_mode=WAL", [], |row| row.get(0));
    Ok(())
}

fn sibling(path: &Path, suffix: &str) -> PathBuf {
    let mut os = path.as_os_str().to_os_string();
    os.push(suffix);
    PathBuf::from(os)
}

fn inspect_l0(path: &Path) -> L0Report {
    let meta = std::fs::metadata(path).ok();
    let wal = sibling(path, "-wal");
    let shm = sibling(path, "-shm");
    L0Report {
        path: path.display().to_string(),
        file_exists: meta.is_some(),
        size_bytes: meta.map(|m| m.len()).unwrap_or(0),
        wal_present: wal.exists(),
        shm_present: shm.exists(),
        notes: Vec::new(),
    }
}

/// L0：文件层检查。失败直接 `Unavailable`，不做任何"自动修复"。
fn check_l0(path: &Path) -> Result<L0Report, OpenError> {
    if path.is_dir() {
        return Err(OpenError::Unavailable {
            reason: format!("{} 是目录，不是数据库文件", path.display()),
        });
    }
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }

    let wal = sibling(path, "-wal");
    let shm = sibling(path, "-shm");
    let meta = std::fs::metadata(path);

    // 主库不存在但有 WAL/shm：上一次写入被切断，直接判定不可用。
    if meta.is_err() && (wal.exists() || shm.exists()) {
        return Err(OpenError::Unavailable {
            reason: format!(
                "主库 {} 不存在，但残留 -wal/-shm；疑似文件级同步造成的撕裂",
                path.display()
            ),
        });
    }

    // 0 字节主库 + WAL：SQLite 会把它当作空库初始化，但 WAL 里的数据会被丢弃。
    if let Ok(meta) = &meta {
        if meta.len() == 0 && wal.exists() {
            return Err(OpenError::Unavailable {
                reason: format!(
                    "主库 {} 为 0 字节且存在 -wal，拒绝自动初始化（可能丢数据）",
                    path.display()
                ),
            });
        }
    }

    let mut report = inspect_l0(path);
    if !report.file_exists {
        report.notes.push("首次启动，将新建数据库".into());
    }
    if report.size_bytes == 0 && report.file_exists {
        report.notes.push("主库为 0 字节，按新库初始化".into());
    }
    Ok(report)
}

fn ensure_device_id(conn: &Connection) -> Result<String, rusqlite::Error> {
    let existing: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key = 'device_id'",
            [],
            |row| row.get(0),
        )
        .ok();
    if let Some(existing) = existing {
        return Ok(existing);
    }
    let id = Uuid::now_v7().to_string();
    conn.execute(
        "INSERT INTO meta(key, value) VALUES ('device_id', ?1)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        [&id],
    )?;
    Ok(id)
}
#[cfg(test)]
mod tests {
    use super::*;
    use nmoi_core::clock::LogicalClock;
    use nmoi_core::ids::MessageId;
    use nmoi_core::message::{AtomicUnit, Message, Role};
    use nmoi_core::source::SourceTag;

    fn open_ready(path: &Path) -> Storage {
        match Storage::open(path).expect("open 不应失败") {
            OpenOutcome::Ready(storage) => storage,
            OpenOutcome::Blocked(blocked) => {
                panic!("首次打开被拦下: {:?}", blocked.report().issues)
            }
        }
    }

    fn seed(storage: &mut Storage) -> (nmoi_core::ids::SessionId, AtomicUnit) {
        let session = storage
            .create_conversation("体检", "device-a", "default")
            .unwrap();
        let id = MessageId::new(session, 1);
        let message = Message::text(
            id,
            Role::User,
            SourceTag::History,
            "第一条",
            3,
            1_700_000_000_000,
            LogicalClock::new(1),
        );
        let unit = AtomicUnit::turn(message).unwrap();
        storage.append_unit(session, &unit).unwrap();
        (session, unit)
    }

    /// 语义损坏 → 阻塞 → 恢复默认（隔离）→ 撤销 → 坏记录回来。
    /// 这是设计文档 §4.4 那一条"阻塞而非静默修复"的端到端验收。
    #[test]
    fn semantic_corruption_blocks_then_heals_and_undoes() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("app.db");
        let mut storage = open_ready(&path);
        let (session, _unit) = seed(&mut storage);

        // 手工塞一条指向不存在归档的消息（L3 会判 blocking）。
        storage
            .conn
            .execute(
                "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role,
                                      source, content, form, tokens, created_at, logical_clock,
                                      archived_ref, tool_calls)
                 VALUES (?1, ?2, ?3, 'turn', 1, 99, 'assistant', 'history', 'ptr', 'F4', 1,
                         1700000000000, 99, 'ref#aaaaaaaaaaaaaaaa', '[]')",
                rusqlite::params![
                    format!("{session}:99"),
                    session.to_string(),
                    format!("u:{session}:99"),
                ],
            )
            .unwrap();

        let health = storage.health().unwrap();
        assert!(health.semantic.has_blocking(), "引用失效必须阻塞");
        drop(storage);

        // 重新打开 → 被拦下，而不是被静默修掉。
        let outcome = Storage::open(&path).expect("打开本身不该报错");
        let blocked = match outcome {
            OpenOutcome::Blocked(blocked) => blocked,
            OpenOutcome::Ready(_) => panic!("坏数据必须阻塞启动"),
        };
        assert!(blocked
            .report()
            .summary()
            .contains_key("missing_archive_ref"));

        // 显式恢复默认：原值先进隔离区，再删除坏行。
        let (mut storage, recovery) = blocked.restore_defaults().expect("修复后应可打开");
        assert_eq!(recovery.recovered_count(), 1);
        assert!(!storage.health().unwrap().semantic.has_blocking());
        assert_eq!(heal::quarantine_count(&storage.conn).unwrap(), 1);

        // 撤销：坏行原样回来（于是又变成阻塞状态——这是正确行为）。
        let undone = heal::undo_last(&mut storage.conn).unwrap();
        assert_eq!(undone.actions.len(), 1);
        assert!(storage.health().unwrap().semantic.has_blocking());
    }

    /// 分界线的 L3 三查：落在单元内部 / 下面没内容 / 位置不存在。
    #[test]
    fn broken_dividers_are_reported() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, _unit) = seed(&mut storage);

        storage
            .conn
            .execute(
                "INSERT INTO dividers(id, conv_id, after_seq, kind, note, created_at)
                 VALUES ('d1', ?1, 99, 'user', NULL, 0)",
                rusqlite::params![session.to_string()],
            )
            .unwrap();
        storage
            .conn
            .execute(
                "INSERT INTO dividers(id, conv_id, after_seq, kind, note, created_at)
                 VALUES ('d2', ?1, 1, 'user', NULL, 0)",
                rusqlite::params![session.to_string()],
            )
            .unwrap();

        let report = validate::scan(&storage.conn).unwrap();
        let codes = report.summary();
        assert!(codes.contains_key("divider_out_of_range"), "{codes:?}");
        assert!(codes.contains_key("divider_nothing_below"), "{codes:?}");
    }

    /// 备份必须"在副本上"验证：主库正常、副本损坏时要能看出来。
    #[test]
    fn backup_of_empty_database_still_verifies() {
        let dir = tempfile::tempdir().unwrap();
        let storage = open_ready(&dir.path().join("app.db"));
        let dest = dir.path().join("backup/app.db");
        let report = storage.backup(dest).unwrap();
        assert!(report.verified, "{:?}", report.problems);
    }
}
