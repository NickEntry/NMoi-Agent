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
pub mod parts;
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
        // 迁移前先留一份副本（用户 2026-11-04 的要求）：迁移改的是库的形状，
        // 改坏了没有第二份可回退。副本只留一份（`<库>.v<旧版本>.bak`），
        // 同一版本反复打开不会越攒越多。
        if found > 0 && found < SCHEMA_VERSION {
            let backup = path.with_extension(format!("v{found}.bak"));
            if !backup.exists() {
                std::fs::copy(&path, &backup)?;
            }
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
                                      source, form, tokens, created_at, logical_clock,
                                      archived_ref)
                 VALUES (?1, ?2, ?3, 'turn', 1, 99, 'assistant', 'history', 'F4', 1,
                         1700000000000, 99, 'ref#aaaaaaaaaaaaaaaa')",
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
    /// 迁移前留一份副本（D256）：v14 的库打开一次之后，旁边应该多一个 `.v14.bak`。
    #[test]
    fn migrating_leaves_one_backup_next_to_the_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nmoi.db");
        {
            // 造一个 v14 的库：只迁到 14 就撒手。
            let mut conn = rusqlite::Connection::open(&path).unwrap();
            crate::migrate::migrate(&mut conn, 14).unwrap();
        }

        let storage = match Storage::open(&path).unwrap() {
            OpenOutcome::Ready(storage) => storage,
            OpenOutcome::Blocked(_) => panic!("不该被拦：语义扫描报了阻塞"),
        };
        drop(storage);

        let backup = dir.path().join("nmoi.v14.bak");
        assert!(backup.exists(), "迁移前要先留一份副本");
        // 再打开一次不会多出一份（同一版本只留一份）。
        drop(Storage::open(&path).unwrap());
        let count = std::fs::read_dir(dir.path())
            .unwrap()
            .filter_map(|entry| entry.ok())
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".bak"))
            .count();
        assert_eq!(count, 1, "同一版本反复打开不该越攒越多");
    }

    #[test]
    fn backup_of_empty_database_still_verifies() {
        let dir = tempfile::tempdir().unwrap();
        let storage = open_ready(&dir.path().join("app.db"));
        let dest = dir.path().join("backup/app.db");
        let report = storage.backup(dest).unwrap();
        assert!(report.verified, "{:?}", report.problems);
    }

    /// 复制会话（D207）：消息整份过去、id 换成新会话、原件一个字节不动。
    #[test]
    fn copy_conversation_keeps_every_message_under_a_new_session() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);

        let copy = storage.copy_conversation(session, None).unwrap();
        assert_ne!(copy, session);

        let original = storage.load_units(session).unwrap();
        let duplicated = storage.load_units(copy).unwrap();
        assert_eq!(original.len(), 1);
        assert_eq!(duplicated.len(), 1);
        assert_eq!(original[0].messages.len(), duplicated[0].messages.len());

        // 内容与 seq 一致，但 id 属于新会话——抄漏 id 会让两条会话抢同一行主键。
        for (before, after) in original[0].messages.iter().zip(&duplicated[0].messages) {
            assert_eq!(before.content(), after.content());
            assert_eq!(before.id.seq(), after.id.seq());
            assert_eq!(after.id.session(), copy);
            assert_eq!(after.unit_id.session(), copy);
        }
        // 原件没被动过：单元 id 还在老会话里。
        assert_eq!(original[0].id.session(), session);
        assert_eq!(unit.id, original[0].id);

        // 标题默认带"副本"，否则列表里两条同名根本分不出哪条是新的。
        let page = storage.list_conversations(Some("default"), 10, 0).unwrap();
        let titles: Vec<&str> = page.items.iter().map(|c| c.title.as_str()).collect();
        assert!(titles.contains(&"体检 副本"), "{titles:?}");
    }

    /// 工具对也要成对搬过去：只搬调用不搬结果，副本里那个单元就是开口的。
    #[test]
    fn copy_conversation_keeps_tool_pairs_closed() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let session = storage
            .create_conversation("工具对", "device-a", "default")
            .unwrap();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![nmoi_core::message::OutgoingToolCall::new(
                "call-1",
                "read_file",
                "{\"path\":\"a\"}",
            )],
            5,
            1_700_000_000_000,
            LogicalClock::new(1),
        );
        let mut unit = AtomicUnit::tool_pair(head).unwrap();
        unit.set_output("call-1", "文件内容 a", nmoi_core::message::ToolStatus::Ok)
            .unwrap();
        storage.append_unit(session, &unit).unwrap();

        let copy = storage
            .copy_conversation(session, Some("工具对 2"))
            .unwrap();
        let duplicated = storage.load_units(copy).unwrap();
        assert_eq!(duplicated.len(), 1);
        assert!(duplicated[0].is_closed(), "副本的工具对必须是闭合的");
        assert_eq!(duplicated[0].messages.len(), 1, "一轮 = 一条消息");
        assert_eq!(duplicated[0].messages[0].tool_output(0), Some("文件内容 a"));
    }

    /// 图片附件（D208）：存进去、取出来、跟着会话一起走。
    #[test]
    fn attachments_roundtrip_and_follow_the_conversation() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();
        let bytes = vec![0xffu8, 0xd8, 0xff, 0x00, 0x10, 0x20];

        let id = storage
            .put_attachment(session, &message, "image/jpeg", &bytes)
            .unwrap();

        let listed = storage.attachments_of(session).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, id);
        assert_eq!(listed[0].message, message);
        assert_eq!(listed[0].size, bytes.len() as u64);

        let (mime, read_back) = storage.attachment(&id).unwrap();
        assert_eq!(mime, "image/jpeg");
        assert_eq!(read_back, bytes);

        // 发请求那条路：base64 编出来的必须能解回原字节。
        let parts = storage.attachments_for(&message).unwrap();
        assert_eq!(parts.len(), 1);
        assert_eq!(
            nmoi_core::base64::decode(&parts[0].1).unwrap(),
            bytes,
            "base64 往返不能丢字节"
        );

        // 删会话：外键级联把附件一起带走，不留孤儿。
        assert!(storage.delete_conversation(session).unwrap());
        let left: i64 = storage
            .conn
            .query_row("SELECT COUNT(*) FROM attachments", [], |row| row.get(0))
            .unwrap();
        assert_eq!(left, 0);
    }

    /// D216：字节落到会话私有目录，库里只留路径；删会话时目录一起清。
    #[test]
    fn attachments_live_on_disk_under_the_session() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();
        let bytes = vec![1u8, 2, 3, 4];

        let id = storage
            .put_attachment(session, &message, "image/png", &bytes)
            .unwrap();

        // 盘上有一份，库里 bytes 是 NULL、path 指过去。
        let path: Option<String> = storage
            .conn
            .query_row("SELECT path FROM attachments WHERE id = ?1", [&id], |row| {
                row.get(0)
            })
            .unwrap();
        let path = path.expect("新附件必须有路径");
        assert_eq!(std::fs::read(&path).unwrap(), bytes);
        let blob: Option<Vec<u8>> = storage
            .conn
            .query_row(
                "SELECT bytes FROM attachments WHERE id = ?1",
                [&id],
                |row| row.get(0),
            )
            .unwrap();
        assert!(blob.is_none(), "新附件不该再往库里塞字节");
        assert!(path.contains(&session.to_string()), "{path}");

        // 存储管理：一条，带会话标题。
        let all = storage.all_attachments().unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].id, id);
        assert_eq!(all[0].conversation_title, "体检");

        // 删附件：行与文件都没了。
        assert!(storage.delete_attachment(&id).unwrap());
        assert!(!std::path::Path::new(&path).exists());
        assert!(storage.all_attachments().unwrap().is_empty());
    }

    /// 老库里的 BLOB 附件：第一次读的时候搬到盘上，之后走文件。
    #[test]
    fn legacy_blob_attachments_are_adopted_on_first_read() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();

        // 手工塞一条 v10 形状的记录（有 bytes、没有 path）。
        storage
            .conn
            .execute(
                "INSERT INTO attachments(id, conv_id, message_id, mime, bytes, path, size, created_at)
                 VALUES ('legacy-1', ?1, ?2, 'image/jpeg', ?3, NULL, 3, 1)",
                rusqlite::params![session.to_string(), message.to_string(), vec![9u8, 8, 7]],
            )
            .unwrap();

        let (mime, bytes) = storage.attachment("legacy-1").unwrap();
        assert_eq!(mime, "image/jpeg");
        assert_eq!(bytes, vec![9u8, 8, 7]);

        let path: Option<String> = storage
            .conn
            .query_row(
                "SELECT path FROM attachments WHERE id = 'legacy-1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        let path = path.expect("读过一次之后应当搬到了盘上");
        assert_eq!(std::fs::read(path).unwrap(), vec![9u8, 8, 7]);
    }

    /// 删会话：`sessions/<id>/` 整目录一起清。
    #[test]
    fn deleting_a_conversation_removes_its_attachment_directory() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();
        storage
            .put_attachment(session, &message, "image/png", b"abc")
            .unwrap();
        let folder = storage.attachment_dir(session);
        assert!(folder.exists());

        assert!(storage.delete_conversation(session).unwrap());
        assert!(!folder.exists(), "会话目录应当被清掉");
    }

    /// 四种拒绝：非图片、空字节、超上限、消息不属于这个会话。
    #[test]
    fn attachments_refuse_what_cannot_be_shown() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();
        let other = storage
            .create_conversation("另一条", "device-a", "default")
            .unwrap();

        assert!(storage
            .put_attachment(session, &message, "text/plain", b"hi")
            .is_err());
        assert!(storage
            .put_attachment(session, &message, "image/png", b"")
            .is_err());
        let huge = vec![0u8; repo::MAX_ATTACHMENT_BYTES + 1];
        assert!(storage
            .put_attachment(session, &message, "image/png", &huge)
            .is_err());
        assert!(storage
            .put_attachment(other, &message, "image/png", b"x")
            .is_err());
    }

    /// 发请求时的取图路径（`HistoryStore::attachments`）也要通。
    #[test]
    fn history_store_exposes_attachments_for_the_wire() {
        use nmoi_core::session::HistoryStore;
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let (session, unit) = seed(&mut storage);
        let message = unit.messages[0].id.clone();
        storage
            .put_attachment(session, &message, "image/png", b"\x89PNG")
            .unwrap();

        let parts = HistoryStore::attachments(&storage, &message).unwrap();
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].mime, "image/png");
        assert_eq!(
            nmoi_core::base64::decode(&parts[0].data).unwrap(),
            b"\x89PNG"
        );
    }

    /// 副本还指着的归档，删原件时不能删掉：`ref` 是内容寻址的全局主键，
    /// 删了那一行，副本里的 `archived_ref` 就悬空，L3 下次会说这个库坏了。
    #[test]
    fn deleting_the_original_keeps_archive_rows_a_copy_still_points_at() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let session = storage
            .create_conversation("带归档", "device-a", "default")
            .unwrap();
        let entry = nmoi_core::archive::ArchiveEntry::new_redacted(
            "很长的原文".to_string(),
            SourceTag::History,
            nmoi_core::archive::Origin::new(session, MessageId::new(session, 1), 1),
            9,
            1_700_000_000_000,
        );
        let reference = entry.rf.clone();
        storage.put_archive(session, entry).unwrap();

        let message = Message::text(
            MessageId::new(session, 1),
            Role::User,
            SourceTag::History,
            "原文在归档里",
            9,
            1_700_000_000_000,
            LogicalClock::new(1),
        )
        .with_archive(nmoi_core::message::ContentForm::Pointer, reference.clone());
        let unit = AtomicUnit::turn(message).unwrap();
        storage.append_unit(session, &unit).unwrap();

        let copy = storage.copy_conversation(session, None).unwrap();
        assert!(storage.delete_conversation(session).unwrap());

        // 副本还在，它的归档引用也还解得开。
        let duplicated = storage.load_units(copy).unwrap();
        assert_eq!(
            duplicated[0].messages[0].archived_ref.as_ref(),
            Some(&reference)
        );
        assert!(storage.fetch_archive(&reference).is_ok());
    }

    /// 不存在的会话复制不了：静默建出一个空会话比报错更难查。
    #[test]
    fn copy_conversation_refuses_unknown_session() {
        let dir = tempfile::tempdir().unwrap();
        let mut storage = open_ready(&dir.path().join("app.db"));
        let stranger = nmoi_core::ids::SessionId::new();
        let refused = storage.copy_conversation(stranger, None);
        assert!(refused.is_err());
    }
}
