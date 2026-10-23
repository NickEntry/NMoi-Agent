//! 句柄表：整数 id → `Storage` / `Workspace`（D54）。
//!
//! 为什么不是裸指针：UI 是多线程的，"后台任务拿着已经关掉的句柄回来"必然发生。
//! 裸指针在那一步是 use-after-free（整个 App 崩）；整数 id 查一次表，返回
//! `invalid_handle`，UI 弹一句"这个会话已经关了"就够了。
//!
//! id **单调递增、永不复用**：复用会把"旧 id 指向新对象"变成静默错误——拿着旧句柄去写，
//! 写进了别的会话，而且一切看起来正常。这种 bug 只能靠翻日志查。
//!
//! # 一把全局锁
//!
//! 表是 `Mutex<BTreeMap<u64, Entry>>`，并且**闭包在持锁期间执行**。这不是偷懒：
//! 它顺带给"同一进程里同时只有一个存储/工作区操作"这件事上了保险，正好配合 D45 的
//! "不做异步批量写"。代价是 FFI 调用之间完全串行——这些调用是"打开会话 / 读文件"级别，
//! 不在渲染循环里。
//!
//! **不要在 `with_*` 的闭包里再调 `with_*`**，会自己等自己。

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};

use nmoi_core::workspace::Workspace;
use nmoi_storage::Storage;

use crate::icons::IconPacks;
use crate::json::{FfiError, FfiResult};

pub(crate) enum Entry {
    /// 存储句柄**永远不会被取走**（D89）。一轮要跑自己的连接，就再开一条（WAL），
    /// 而不是把这一格腾空——腾空会让同一个库上的所有调用吃 `busy`，
    /// 于是 A 会话在跑，B 会话连列表都刷不了。
    Storage(Box<Storage>),
    Workspace(Box<Workspace>),
    /// 图标包根目录 + 已加载的包（D105）。它**不持有**存储句柄：
    /// 句柄表是一把全局锁，跨类型嵌套调用会自己等自己。
    Icons(Box<IconPacks>),
}

fn table() -> &'static Mutex<BTreeMap<u64, Entry>> {
    static TABLE: OnceLock<Mutex<BTreeMap<u64, Entry>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(BTreeMap::new()))
}

/// 中毒的锁照用：表里的不变量只有"某个 id 存不存在"，而插入/删除是 `BTreeMap` 的单次操作，
/// 半截状态不存在。把中毒当成"以后都不能用"反而更糟。
fn lock() -> MutexGuard<'static, BTreeMap<u64, Entry>> {
    table()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn next_id() -> u64 {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    NEXT.fetch_add(1, Ordering::Relaxed)
}

pub(crate) fn insert_storage(storage: Storage) -> u64 {
    let id = next_id();
    lock().insert(id, Entry::Storage(Box::new(storage)));
    id
}

pub(crate) fn insert_icons(packs: IconPacks) -> u64 {
    let id = next_id();
    lock().insert(id, Entry::Icons(Box::new(packs)));
    id
}

pub(crate) fn insert_workspace(workspace: Workspace) -> u64 {
    let id = next_id();
    lock().insert(id, Entry::Workspace(Box::new(workspace)));
    id
}

/// 关掉一个存储句柄。类型不对或已经关过都返回 `false`——**关闭是幂等的**，
/// 清理路径（App 退出、会话删除）不该因为重复关闭而报错。
pub(crate) fn remove_icons(id: u64) -> bool {
    let mut guard = lock();
    if matches!(guard.get(&id), Some(Entry::Icons(_))) {
        guard.remove(&id);
        true
    } else {
        false
    }
}

pub(crate) fn remove_storage(id: u64) -> bool {
    let mut guard = lock();
    // 一轮不再"借走"句柄（D89）：它握着自己的连接，删句柄只是"以后查不到"，
    // 不影响那一轮继续跑完。
    if matches!(guard.get(&id), Some(Entry::Storage(_))) {
        guard.remove(&id);
        true
    } else {
        false
    }
}

pub(crate) fn remove_workspace(id: u64) -> bool {
    let mut guard = lock();
    if matches!(guard.get(&id), Some(Entry::Workspace(_))) {
        guard.remove(&id);
        true
    } else {
        false
    }
}

pub(crate) fn with_storage<R>(
    id: u64,
    body: impl FnOnce(&mut Storage) -> FfiResult<R>,
) -> FfiResult<R> {
    let mut guard = lock();
    match guard.get_mut(&id) {
        Some(Entry::Storage(storage)) => body(storage.as_mut()),
        Some(_) => Err(FfiError::new(
            "invalid_handle",
            format!("句柄 {id} 不是存储句柄"),
        )),
        None => Err(invalid(id)),
    }
}

/// 存储句柄对应的库文件路径。
///
/// 一轮要开自己的独立连接（D89），就得知道库在哪儿；路径只有 `Storage` 知道，
/// 而它住在句柄表里。这个调用很短（只读一个 `PathBuf`），不违反"不要在
/// `with_*` 闭包里再调 `with_*`"。
pub(crate) fn storage_path(id: u64) -> FfiResult<PathBuf> {
    with_storage(id, |storage| Ok(storage.path().to_path_buf()))
}

pub(crate) fn with_workspace<R>(
    id: u64,
    body: impl FnOnce(&Workspace) -> FfiResult<R>,
) -> FfiResult<R> {
    let mut guard = lock();
    match guard.get_mut(&id) {
        Some(Entry::Workspace(workspace)) => body(workspace.as_ref()),
        Some(_) => Err(FfiError::new(
            "invalid_handle",
            format!("句柄 {id} 不是工作区句柄"),
        )),
        None => Err(invalid(id)),
    }
}

/// 在持锁期间操作图标包。规则同 `with_storage`：**不要在闭包里再调 `with_*`**。
pub(crate) fn with_icons<T>(
    id: u64,
    body: impl FnOnce(&mut IconPacks) -> FfiResult<T>,
) -> FfiResult<T> {
    let mut guard = lock();
    match guard.get_mut(&id) {
        Some(Entry::Icons(packs)) => body(packs),
        _ => Err(FfiError::new(
            "invalid_handle",
            format!("图标句柄 {id} 不存在"),
        )),
    }
}

fn invalid(id: u64) -> FfiError {
    FfiError::new(
        "invalid_handle",
        format!("句柄 {id} 不存在或已释放（句柄不复用，拿到旧的只能重新打开）"),
    )
}

/// 给 `nmoi_capabilities_json` 与测试用：当前活着的句柄数，按类型分开。
pub(crate) fn live_counts() -> (usize, usize) {
    let guard = lock();
    let mut storage = 0;
    let mut workspace = 0;
    for entry in guard.values() {
        match entry {
            Entry::Storage(_) => storage += 1,
            Entry::Workspace(_) => workspace += 1,
            // 图标句柄不计入这个二元组：它的用途是"存储/工作区还活着几个"，
            // 改签名会把调用点全拖下水，而这里没有要用图标计数的场景
            Entry::Icons(_) => {}
        }
    }
    (storage, workspace)
}
