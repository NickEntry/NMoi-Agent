//! 共享扫描器（D379）：**一棵树只扫一次，多会话共用一份结果**。
//!
//! # 为什么不是"每条命令前后各扫一次"
//!
//! 量出来的数（`crates/core/examples/bench_scan.rs`，本机 `/workspace/NMoi-Agent`，714 个文件）：
//!
//! ```text
//! 扫一次        137 ms
//! 1 个会话扫    135 ms
//! 2 个会话扫    284 ms
//! 4 个会话扫    554 ms
//! 8 个会话扫   1128 ms      ← 线性增长，并发越多越糟
//! 共享一次      141 ms      ← 与会话数无关
//! ```
//!
//! 多会话并发时**恰恰最需要归属判断**，而那时各自扫会把磁盘打满。共用一份是唯一能
//! 同时满足"准"和"不贵"的做法。
//!
//! # 四棵树，两类
//!
//! 用户 2026-11-22 定的扫描范围是 `/workspace`、`/chat`、`/skills`、`/tmp`。它们不是一类：
//!
//! | 范围 | 实际路径 | 类别 |
//! |---|---|---|
//! | `/workspace` | 绑定的工作区根 | **共享**：谁都能写 |
//! | `/skills` | 技能根 | **共享**：谁都能写 |
//! | `/chat` | `sessions/<id>/chat` | **私有**：只有这条会话能写 |
//! | `/tmp` | `sessions/<id>/tmp` | **私有**：只有这条会话能写 |
//!
//! 私有那两棵**不需要判归属**——别的会话写不进去（`tools::target()` 把 `/tmp` 映射到
//! `ctx.session_tmp`，那是本会话的目录）。所以"判不出"只会出现在 `/workspace` 与 `/skills`
//! 上，而这两处有写入口日志与命令文本匹配兜着。
//!
//! # 延迟与合并
//!
//! 命令跑完不立刻扫：并发会话的写入可能在毫秒级之内交错，等一小段再扫，
//! 期间发生的写入会被日志与文本匹配两道判据一起消化掉——这就是"延迟期间如果有其他命令，
//! 也进行匹配"。它本质上是**把多次扫描合并成一次**，不是周期扫描：
//! **没有命令在跑就不扫**，空闲时零开销。
//!
//! 反过来，周期扫描做不到"这条变化属于哪条命令"——窗口里发生的一切混在一起，
//! 归属只会更糊。所以这里只合并、不定时。
//!
//! # 不确定性是接受的
//!
//! 用户 2026-11-22 明说：「如果两个会话同时写入然后扫描的话，也会有不确定性的，
//! 所以说这种共享是可以接受的，反正是尽量判断归属，不是必须完全判断出」。
//! 所以这里不追求线性一致：共享缓存可能把"刚发生的写入"算进基线（新鲜期内不重扫），
//! 代价是那一条变化漏报。**漏报比误报便宜**——误报会让用户在两条会话里看到同一个文件，
//! 而漏报只是少列一个，且下一次命令会补上。

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::changes::is_ignored;

/// 一个文件的指纹。只留判"变没变"需要的两样。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Stamp {
    pub bytes: u64,
    pub mtime_ms: u64,
}

/// 一棵树属于谁。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RootKind {
    /// 只有这条会话能写。落在里面的变化**不用判**。
    Private(String),
    /// 多条会话共用。落在里面的变化要判归属。
    Shared,
}

/// 一棵被扫描的树。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Root {
    pub path: PathBuf,
    pub kind: RootKind,
}

impl Root {
    pub fn shared(path: impl Into<PathBuf>) -> Self {
        Self {
            path: path.into(),
            kind: RootKind::Shared,
        }
    }

    pub fn private(path: impl Into<PathBuf>, session: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            kind: RootKind::Private(session.into()),
        }
    }
}

/// 一棵树的快照。
#[derive(Debug, Clone)]
pub struct Snapshot {
    pub root: PathBuf,
    pub files: BTreeMap<PathBuf, Stamp>,
    /// 什么时候扫的。调用方拿它算"这份有多旧"。
    pub taken: Instant,
    /// 扫到上限就停了。这时差异**不全**，要如实说。
    pub truncated: bool,
}

/// 一次多根扫描的结果。
#[derive(Debug, Clone, Default)]
pub struct Baseline {
    /// 按根分。缺的根（目录不存在）不在表里。
    pub trees: BTreeMap<PathBuf, Snapshot>,
    /// 这次基线里有没有哪棵树被截断。
    pub truncated: bool,
}

/// 一个变化文件。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Changed {
    pub path: PathBuf,
    /// 之前不存在。
    pub created: bool,
    /// 落在一个会话私有的树里 → 归属**确定**，不必再判。
    pub private_to: Option<String>,
}

/// 单棵树最多记多少条。超过就停——记十万条要几十 MB，而真正要看的改动就那几十个。
pub const DEFAULT_LIMIT: usize = 20_000;

/// 缓存默认新鲜度。
///
/// 250 ms 的来源：一次扫描本身 137 ms，取两倍左右。再小就等于每次都真扫（缓存没意义），
/// 再大就会把"上一个命令刚改完"看成基线，差异里少掉那一段。
pub const DEFAULT_MAX_AGE: Duration = Duration::from_millis(250);

/// 命令结束后等多久再扫（合并窗口）。
///
/// 150 ms：够让并发的另一条命令把自己的写入口日志记完，又不至于让用户等。
/// 这个值只影响"合并多少"，不影响正确性——漏掉的下一次命令会补上。
pub const DEFAULT_SETTLE: Duration = Duration::from_millis(150);

fn cache() -> &'static Mutex<BTreeMap<PathBuf, Snapshot>> {
    static CACHE: OnceLock<Mutex<BTreeMap<PathBuf, Snapshot>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(BTreeMap::new()))
}

/// 拿一组根的基线。每棵树各自看缓存够不够新，不够新的扫一次。
///
/// **扫在锁外做**：一次 137 ms，持锁扫会把所有会话堵住。锁只用来读写缓存，
/// 所以同一棵树在"缓存过期 + 两个会话同时要"时可能扫两次——这是有意的取舍：
/// 多扫一次的代价（137 ms 磁盘）小于让 N 个会话排队的代价（N × 137 ms 全串行）。
pub fn baseline(roots: &[Root], max_age: Duration, limit: usize) -> Baseline {
    let mut out = Baseline::default();
    for root in roots {
        let key = normalize(&root.path);
        if !key.is_dir() {
            continue;
        }
        let cached = {
            let cache = cache().lock().unwrap_or_else(|p| p.into_inner());
            cache
                .get(&key)
                .filter(|snapshot| snapshot.taken.elapsed() <= max_age)
                .cloned()
        };
        let snapshot = match cached {
            Some(snapshot) => snapshot,
            None => {
                let fresh = scan(&key, limit);
                let mut cache = cache().lock().unwrap_or_else(|p| p.into_inner());
                cache.insert(key.clone(), fresh.clone());
                fresh
            }
        };
        if snapshot.truncated {
            out.truncated = true;
        }
        out.trees.insert(key, snapshot);
    }
    out
}

/// 无条件扫一遍并更新缓存。命令结束时用它拿"现在"。
pub fn refresh(roots: &[Root], limit: usize) -> Baseline {
    let mut out = Baseline::default();
    for root in roots {
        let key = normalize(&root.path);
        if !key.is_dir() {
            continue;
        }
        let snapshot = scan(&key, limit);
        if snapshot.truncated {
            out.truncated = true;
        }
        let mut cache = cache().lock().unwrap_or_else(|p| p.into_inner());
        cache.insert(key.clone(), snapshot.clone());
        out.trees.insert(key, snapshot);
    }
    out
}

/// 丢掉一棵树的缓存。删除会话、换工作区之后调一次。
pub fn forget(root: &Path) {
    let key = normalize(root);
    cache()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .remove(&key);
}

/// 清空（测试用）。
#[doc(hidden)]
pub fn clear() {
    cache().lock().unwrap_or_else(|p| p.into_inner()).clear();
}

/// 扫一棵树。跳过点目录与 [`is_ignored`] 命中的段。
///
/// **根目录自己永远不跳**：`is_ignored` 会把点开头的段当目录忽略，
/// 而用户的工作区完全可能就叫 `.config`（测试里的 `tempfile` 更是 `/.tmpXXXX`）。
/// 只对**根之下的**条目做忽略判断。
pub fn scan(root: &Path, limit: usize) -> Snapshot {
    let mut files = BTreeMap::new();
    let mut truncated = false;
    let mut stack = vec![root.to_path_buf()];
    'outer: while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let Ok(meta) = entry.metadata() else {
                continue;
            };
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if meta.is_dir() {
                if name.starts_with('.') || is_ignored(&name) {
                    continue;
                }
                stack.push(entry.path());
                continue;
            }
            if !meta.is_file() {
                continue;
            }
            // 只看**名字**，不看整条绝对路径：绝对路径里含有工作区根自己那几段
            // （可能是 `/.config/…`），拿它去判会整棵树被忽略。
            if name.starts_with(".l2s.") || is_ignored(&name) {
                continue;
            }
            let path = entry.path();
            let mtime_ms = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            files.insert(
                path,
                Stamp {
                    bytes: meta.len(),
                    mtime_ms,
                },
            );
            if files.len() >= limit {
                truncated = true;
                break 'outer;
            }
        }
    }
    Snapshot {
        root: root.to_path_buf(),
        files,
        taken: Instant::now(),
        truncated,
    }
}

/// 两份多根基线的差。
///
/// `roots` 用来查"这一棵树是不是某条会话私有的"——私有树里的变化直接带上归属。
pub fn changed(before: &Baseline, after: &Baseline, roots: &[Root]) -> Vec<Changed> {
    // 归一化后的路径 → 会话。**持有所有权**，不 `Box::leak`：
    // 这个函数每条命令都会被调用，泄漏一次就是一条永久记录。
    let mut private: BTreeMap<PathBuf, String> = BTreeMap::new();
    for root in roots {
        if let RootKind::Private(session) = &root.kind {
            private.insert(normalize(&root.path), session.clone());
        }
    }
    let mut out = Vec::new();
    for (key, after_tree) in &after.trees {
        let empty = Snapshot {
            root: key.clone(),
            files: BTreeMap::new(),
            taken: after_tree.taken,
            truncated: false,
        };
        let before_tree = before.trees.get(key).unwrap_or(&empty);
        let owner = private.get(key);
        for (path, stamp) in &after_tree.files {
            let created = match before_tree.files.get(path) {
                None => true,
                Some(prev) if prev != stamp => false,
                Some(_) => continue,
            };
            out.push(Changed {
                path: path.clone(),
                created,
                private_to: owner.cloned(),
            });
        }
    }
    out
}

fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    /// 与 `settle` 共用一把锁：两边都动 `scanner` 的进程级缓存。
    use crate::test_lock::serial;

    fn touch(dir: &Path, name: &str, body: &str) -> PathBuf {
        let path = dir.join(name);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(&path, body).unwrap();
        path
    }

    #[test]
    fn scan_skips_dot_dirs_and_build_outputs() {
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "1");
        touch(dir.path(), "src/b.rs", "22");
        touch(dir.path(), "target/c", "333");
        touch(dir.path(), ".git/d", "4444");
        touch(dir.path(), "node_modules/e", "5");
        let snapshot = scan(dir.path(), DEFAULT_LIMIT);
        let names: Vec<String> = snapshot
            .files
            .keys()
            .map(|p| p.strip_prefix(dir.path()).unwrap().display().to_string())
            .collect();
        assert_eq!(names, vec!["a.txt", "src/b.rs"]);
        assert!(!snapshot.truncated);
    }

    #[test]
    fn changed_reports_new_and_modified_only() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        let a = touch(dir.path(), "a.txt", "1");
        touch(dir.path(), "b.txt", "22");
        let roots = [Root::shared(dir.path())];
        let before = refresh(roots.as_slice(), DEFAULT_LIMIT);

        std::fs::write(a.as_path(), "changed").unwrap();
        touch(dir.path(), "c.txt", "3");
        let after = refresh(roots.as_slice(), DEFAULT_LIMIT);

        let mut names: Vec<String> = changed(&before, &after, roots.as_slice())
            .into_iter()
            .map(|c| c.path.file_name().unwrap().to_string_lossy().to_string())
            .collect();
        names.sort();
        assert_eq!(names, vec!["a.txt", "c.txt"]);
    }

    #[test]
    fn private_roots_carry_their_owner() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        let chat = dir.path().join("sessions/s1/chat");
        let tmp = dir.path().join("sessions/s1/tmp");
        std::fs::create_dir_all(&chat).unwrap();
        std::fs::create_dir_all(&tmp).unwrap();
        let roots = vec![
            Root::private(&chat, "s1"),
            Root::private(&tmp, "s1"),
            Root::shared(dir.path().join("workspace")),
        ];
        std::fs::create_dir_all(dir.path().join("workspace")).unwrap();
        let before = refresh(roots.as_slice(), DEFAULT_LIMIT);

        touch(&chat, "in.txt", "user file");
        touch(&tmp, "scratch.txt", "scratch");
        touch(&dir.path().join("workspace"), "shared.txt", "shared");
        let after = refresh(roots.as_slice(), DEFAULT_LIMIT);

        let rows = changed(&before, &after, roots.as_slice());
        let owner_of = |name: &str| {
            rows.iter()
                .find(|c| c.path.ends_with(name))
                .and_then(|c| c.private_to.clone())
        };
        // 私有树：归属直接给出来，不必再判
        assert_eq!(owner_of("in.txt"), Some("s1".to_string()));
        assert_eq!(owner_of("scratch.txt"), Some("s1".to_string()));
        // 共享树：没有归属，留给上层去判
        assert_eq!(owner_of("shared.txt"), None);
    }

    #[test]
    fn private_and_shared_are_separated_by_root_not_by_path_prefix() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        let shared = dir.path().join("workspace");
        let private = dir.path().join("workspace/sub");
        std::fs::create_dir_all(&private).unwrap();
        let roots = vec![
            Root::shared(shared.clone()),
            Root::private(private.clone(), "s2"),
        ];
        let before = refresh(roots.as_slice(), DEFAULT_LIMIT);
        touch(&private, "x.txt", "1");
        let after = refresh(roots.as_slice(), DEFAULT_LIMIT);
        let rows = changed(&before, &after, roots.as_slice());
        // 两棵树都扫到了同一个文件，但私有那棵说得更准：它给出归属
        let owners: Vec<Option<String>> = rows.iter().map(|c| c.private_to.clone()).collect();
        assert!(owners.contains(&Some("s2".to_string())));
    }

    #[test]
    fn missing_roots_are_skipped() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        let roots = vec![
            Root::shared(dir.path().join("nope")),
            Root::shared(dir.path()),
        ];
        let baseline = refresh(roots.as_slice(), DEFAULT_LIMIT);
        assert_eq!(baseline.trees.len(), 1);
    }

    #[test]
    fn baseline_is_reused_within_max_age() {
        let _guard = serial();
        clear();
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "1");
        let roots = vec![Root::shared(dir.path())];
        let first = baseline(roots.as_slice(), DEFAULT_MAX_AGE, DEFAULT_LIMIT);
        // 新鲜期内加的文件不该出现——这是"共用一份"的代价，也是它的收益
        touch(dir.path(), "b.txt", "2");
        let second = baseline(roots.as_slice(), DEFAULT_MAX_AGE, DEFAULT_LIMIT);
        assert_eq!(first.trees.len(), second.trees.len());
        let count = |b: &Baseline| b.trees.values().next().unwrap().files.len();
        assert_eq!(count(&first), count(&second));
        // 强制刷新之后才看得到
        let third = refresh(roots.as_slice(), DEFAULT_LIMIT);
        assert_eq!(count(&third), 2);
        clear();
    }

    #[test]
    fn expired_baseline_rescans() {
        let _guard = serial();
        clear();
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "1");
        let roots = vec![Root::shared(dir.path())];
        baseline(roots.as_slice(), Duration::from_millis(0), DEFAULT_LIMIT);
        touch(dir.path(), "b.txt", "2");
        let again = baseline(roots.as_slice(), Duration::from_millis(0), DEFAULT_LIMIT);
        assert_eq!(again.trees.values().next().unwrap().files.len(), 2);
        clear();
    }

    #[test]
    fn limit_marks_snapshot_truncated() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        for i in 0..10 {
            touch(dir.path(), &format!("f{i}.txt"), "x");
        }
        let snapshot = scan(dir.path(), 4);
        assert_eq!(snapshot.files.len(), 4);
        assert!(snapshot.truncated);
        // 通过 baseline 也带得出来
        let roots = vec![Root::shared(dir.path())];
        let baseline = refresh(&roots, 4);
        assert!(baseline.truncated);
    }

    #[test]
    fn forget_drops_the_cached_snapshot() {
        let _guard = serial();
        clear();
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "1");
        let roots = vec![Root::shared(dir.path())];
        baseline(roots.as_slice(), DEFAULT_MAX_AGE, DEFAULT_LIMIT);
        touch(dir.path(), "b.txt", "2");
        forget(dir.path());
        let again = baseline(roots.as_slice(), DEFAULT_MAX_AGE, DEFAULT_LIMIT);
        assert_eq!(again.trees.values().next().unwrap().files.len(), 2);
        clear();
    }

    #[test]
    fn created_flag_distinguishes_new_from_modified() {
        let _guard = serial();
        let dir = tempfile::tempdir().unwrap();
        let a = touch(dir.path(), "a.txt", "1");
        let roots = [Root::shared(dir.path())];
        let before = refresh(roots.as_slice(), DEFAULT_LIMIT);
        std::fs::write(a.as_path(), "longer body").unwrap();
        touch(dir.path(), "b.txt", "2");
        let after = refresh(roots.as_slice(), DEFAULT_LIMIT);
        let rows = changed(&before, &after, roots.as_slice());
        let created = |name: &str| {
            rows.iter()
                .find(|c| c.path.ends_with(name))
                .map(|c| c.created)
        };
        assert_eq!(created("a.txt"), Some(false));
        assert_eq!(created("b.txt"), Some(true));
    }
}
