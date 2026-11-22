//! 延迟合并（D380）：**多条命令收尾时合成一次扫描**。
//!
//! # 用户的原话与它意味着什么
//!
//! 「执行命令如果有写入相关的关键要素的话，延迟进行扫描，延迟期间如果有其他命令，也进行匹配」
//! ——这句话描述的不是"周期扫描"，而是一个**去抖（debounce）+ 批处理**：
//!
//! - 只有"可能写了东西"的命令才排进队列（`writes_maybe`），只读命令不排；
//! - 排队之后等 `settle` 那么久，期间来的命令**并进同一批**；
//! - 一批扫一次，结果分给这一批里的每条命令，各自按自己的命令文本判归属。
//!
//! 好处正是用户要的：延迟窗口里发生的别的命令，其写入也落进同一份差异里，
//! 于是"谁改的"可以靠文本匹配一起判，而不是各自扫一遍各看各的。
//!
//! # 为什么不是定时扫描
//!
//! 定时扫描不知道"这条变化属于哪条命令"——一个窗口里可能有十条命令，混在一起只能全标"判不出"。
//! 而且空闲时白扫。去抖版**没有命令就不扫**，空闲零开销。
//!
//! # 一批扫几次
//!
//! 一次。基线用 [`crate::scanner::baseline`]（可能命中缓存，便宜），
//! 结束用 [`crate::scanner::refresh`]（强制真扫）。所以一批 N 条命令的代价是
//! `缓存命中 0~137ms + 137ms`，与 N 无关。
//!
//! 上一批的 `refresh` 结果就是下一批的基线（缓存本来就是这么用的），所以连续跑命令时
//! 每条命令平摊下来远小于 137 ms。

use std::collections::BTreeMap;
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::scanner::{self, Changed, Root};

/// 一条等着被算进差异的命令。
#[derive(Debug, Clone)]
pub struct Pending {
    /// 谁发的（会话 id）。
    pub session: String,
    /// 这条调用在会话里的 id，用来把结果挂回那一块。
    pub call_id: String,
    /// 命令原文。归属判断要用它。
    pub command: String,
    /// 命令实际执行到的脚本内容（`bash x.sh` 里的 `x.sh`）。`None` = 没读。
    pub script: Option<String>,
    /// 命令的 cwd，用来算相对路径。
    pub cwd: std::path::PathBuf,
    /// **命令开始前**那一刻的快照（[`mark`] 的返回值）。
    ///
    /// 这一格是这一层能不能成立的关键：baseline 必须在命令**之前**取。
    /// 早先的写法是在命令结束后才 `baseline()`，那样 diff 的永远是"现在 vs 现在"，
    /// 差异恒为空——而空差异看起来跟"这条命令没改文件"一模一样，最难发现。
    pub before: std::sync::Arc<crate::scanner::Baseline>,
}

/// 一条命令的收尾结论。
#[derive(Debug, Clone)]
pub struct Outcome {
    pub call_id: String,
    pub session: String,
    /// 这一批里落在这个会话名下的变化。
    pub changed: Vec<Changed>,
    /// 这一批里判不出归属的变化。按用户的口径，它们要**加给所有同时在跑的会话**。
    pub ambiguous: Vec<Changed>,
    /// 扫描被上限截断了（列表不全）。
    pub truncated: bool,
    /// 这一批一共合并了几条命令。>1 说明去抖真的起了作用。
    pub batched: usize,
}

/// 一批的共享结果：先扫一次，再分给这一批里每条命令。
#[derive(Debug, Clone)]
pub struct BatchResult {
    pub changed: Vec<Changed>,
    pub truncated: bool,
    /// 参与这一批的 call_id，用来判断"我要的结果到了没有"。
    pub members: Vec<String>,
    /// 每个变化文件的**现读内容**（路径 → 内容）。
    ///
    /// 在这里读而不是在 `split` 里读：`split` 是纯函数（有单测），
    /// 而读盘会让它变成"跑一次一个结果"。读的内容是扫描那一刻的，
    /// 与 `after` 快照对得上——晚读一步，文件可能又被改了。
    ///
    /// 二进制 / 读不了的文件不在表里（`None` 语义），界面标「无法预览」。
    pub texts: std::collections::BTreeMap<std::path::PathBuf, String>,
}

struct State {
    pending: Vec<Pending>,
    /// 排好的那一批什么时候该扫。`None` = 没有待扫的。
    ready: Option<Instant>,
    /// 上一批的结论，按 call_id 存。取走即删。
    results: BTreeMap<String, BatchResult>,
    /// 正在扫（另一个线程拿着）。
    scanning: bool,
}

fn state() -> &'static Mutex<State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(|| {
        Mutex::new(State {
            pending: Vec::new(),
            ready: None,
            results: BTreeMap::new(),
            scanning: false,
        })
    })
}

fn wake() -> &'static Condvar {
    static WAKE: OnceLock<Condvar> = OnceLock::new();
    WAKE.get_or_init(Condvar::new)
}

/// 命令跑完了：排进下一批，等 `settle` 之后一起扫。
///
/// **不阻塞调用方**：调用方拿到的是一个"将来去取"的凭据（`call_id`），
/// 结果通过 [`take`] 取。这样一条命令的收尾不会因为等去抖窗口而拖住那一轮对话。
///
/// 只在**第一次**入队时定"什么时候扫"，后面来的并进去——这就是去抖：
/// 窗口内源源不断的命令不会一直把时间点往后推（那是 debounce 的另一种写法，
/// 会让一批永远不开始扫）。
pub fn enqueue(pending: Pending, settle: Duration) {
    let mut state = state().lock().unwrap_or_else(|p| p.into_inner());
    // 已经排好的那批如果是空成员（还没开始扫），把新来的并进去，不另定时间。
    // 成员只从 `pending` 取，`ready` 只存"什么时候扫"——两处都存会让同一批算两遍。
    state.pending.push(pending);
    if state.ready.is_none() {
        state.ready = Some(Instant::now() + settle);
    }
    wake().notify_all();
}

/// 去取这一条命令的结果。还没算好返回 `None`。
pub fn take(call_id: &str) -> Option<BatchResult> {
    let mut state = state().lock().unwrap_or_else(|p| p.into_inner());
    state.results.remove(call_id)
}

/// 该不该排进队列：命令里有没有"可能写文件"的迹象。
///
/// **先判只读、再判写**。顺序不能反：只读命令里也常常带 `>` 这类字符（`grep "a>b"`）、
/// 也常常以 `ls` 开头却带着重定向（`ls > out.txt`），所以两条规则必须分开判，
/// 而且"明确只读"要能压过"看着像写"。这里按**命令的第一个词**判只读，
/// 不用子串——子串匹配会让 `cat` 命中 `cat`、也让 `ls` 命中 `false`。
///
/// **判错的代价不对称**：判成"可能写"（其实没写）→ 多扫一次，137 ms；
/// 判成"不会写"（其实写了）→ 那一批变化漏报。所以只排除**第一个词明确只读、
/// 且整条命令里没有重定向**的，其余一律排。
pub fn writes_maybe(command: &str) -> bool {
    /// 明确只读的命令（按**第一个词**比）。
    const READ_ONLY: &[&str] = &[
        "ls",
        "cat",
        "head",
        "tail",
        "grep",
        "rg",
        "find",
        "wc",
        "stat",
        "file",
        "which",
        "type",
        "pwd",
        "env",
        "uname",
        "ps",
        "df",
        "du",
        "tree",
        "less",
        "more",
        "diff",
        "cmp",
        "md5sum",
        "sha256sum",
        "echo",
        "printf",
    ];
    /// 写在**任何位置**出现都说明可能在写。
    const WRITE_HINTS: &[&str] = &[
        ">", ">>", "tee", "cp ", "mv ", "rm ", "mkdir", "rmdir", "touch", "chmod", "chown", "ln ",
        "sed -i", "patch", "npm ", "yarn ", "pnpm ", "pip ", "cargo ", "make", "cmake", "gradle",
        "docker", "tar ", "unzip", "zip ", "curl -o", "wget", "dd ", "truncate", "install ",
        "rsync", "python", "python3", "node ", "perl", "ruby", "bash ", "sh ", "zsh ", "eval",
        "exec", "xargs", "git ",
    ];
    let trimmed = command.trim();
    if trimmed.is_empty() {
        return false;
    }
    // git 单独判：只读子命令很常用（`git status` 会跑很多次），而 `git ` 整词命中会
    // 把它们全判成写。只读的那几个子命令放行，其余 git 都当写。
    if trimmed.starts_with("git ") {
        const READ_ONLY_GIT: &[&str] = &[
            "status",
            "log",
            "diff",
            "show",
            "branch",
            "remote",
            "config --get",
            "rev-parse",
            "describe",
            "blame",
            "shortlog",
            "tag",
            "ls-files",
            "cat-file",
            "stash list",
        ];
        let rest = trimmed.trim_start_matches("git ").trim_start();
        return !READ_ONLY_GIT
            .iter()
            .any(|sub| rest == *sub || rest.starts_with(&format!("{sub} ")));
    }
    // 重定向 / 写命令：任何位置出现都算（`ls > out` 也是写）。
    if WRITE_HINTS.iter().any(|hint| trimmed.contains(hint)) {
        return true;
    }
    // 只读：看第一个词。
    let first = trimmed
        .split(|c: char| c.is_whitespace() || c == ';' || c == '|' || c == '&')
        .find(|token| !token.is_empty())
        .unwrap_or("");
    if READ_ONLY.contains(&first) {
        return false;
    }
    // 认不出的命令：按"可能写"处理（宁可多扫一次）。
    true
}

/// 命令**开始之前**调一次：拿这一刻的快照，命令结束后交给 [`Pending::before`]。
///
/// 放在开始之前是这一层唯一正确的顺序。命令跑完再取，diff 就是"现在 vs 现在"。
pub fn mark(roots: &[Root], limit: usize) -> std::sync::Arc<crate::scanner::Baseline> {
    std::sync::Arc::new(scanner::baseline(roots, scanner::DEFAULT_MAX_AGE, limit))
}

/// 扫一批。**由后台线程调用**，`settle` 到期之后。
///
/// `roots` 由调用方按"这条命令能看到哪几棵树"给：
/// 共享的（工作区 / 技能）+ 这条会话私有的（chat / tmp）。
///
/// baseline 用**第一条命令**开始前那一份（`members[0].before`）。
/// 为什么不是每条各用各的：一批里的命令是**故意**合并的，用同一份基线才谈得上
/// "这一批一共变了什么"；各用各的基线会让同一份差异被算两遍，而两遍之间还夹着别人的写入。
pub fn run_batch(members: &[Pending], roots: &[Root], limit: usize) -> BatchResult {
    let before = members
        .first()
        .map(|pending| pending.before.clone())
        .unwrap_or_else(|| mark(roots, limit));
    let after = scanner::refresh(roots, limit);
    let changed = scanner::changed(&before, &after, roots);
    // 顺手把内容读出来：差异要它，而这一刻读到的与 `after` 快照对得上。
    let mut texts = std::collections::BTreeMap::new();
    for change in &changed {
        if let Ok(text) = std::fs::read_to_string(&change.path) {
            texts.insert(change.path.clone(), text);
        }
    }
    BatchResult {
        changed,
        truncated: before.truncated || after.truncated,
        members: members.iter().map(|p| p.call_id.clone()).collect(),
        texts,
    }
}

/// 从一批里挑出属于某条会话的变更，**已经算好差异**。
///
/// 与 [`split`] 的区别：那个只回答"归谁"，这个把 `FileChange` 一并造好
/// （差异、行数、`created`）。上层不必再读一次盘，也不必自己判 created/modified。
pub fn records(
    batch: &BatchResult,
    me: &Pending,
    running: &[String],
    window_ms: u64,
) -> Vec<crate::changes::FileChange> {
    let outcome = split(batch, me, running, window_ms);
    let mut out = Vec::new();
    for change in outcome.changed.iter().chain(outcome.ambiguous.iter()) {
        let path = change.path.to_string_lossy().to_string();
        let Some(after) = batch.texts.get(&change.path) else {
            // 读不出来（二进制 / 权限）：给一条没有差异的记录，
            // 界面据此说"文件变了、但看不了内容"。
            out.push(crate::changes::FileChange {
                path,
                kind: if change.created {
                    crate::changes::ChangeKind::Created
                } else {
                    crate::changes::ChangeKind::Modified
                },
                added: 0,
                removed: 0,
                diff: None,
                lines: 0,
                bytes: 0,
            });
            continue;
        };
        // `before` 给 `None` 表示"之前不存在"：这里只有 `created` 一个信号，
        // 而 `record` 拿它判 `Created` / `Modified`。旧内容读不到（扫描只存指纹），
        // 所以**修改类**的差异只能给出"整份替换"的形状——这是这一层的已知代价，
        // 写进 D378 了。
        let before_text = if change.created { None } else { Some("") };
        // **忽略判断按相对路径做**：绝对路径里含有根目录名，而根完全可能叫
        // `.config` / `/.tmpXXXX`（测试的 `tempfile` 就是这样）。拿绝对路径去判，
        // 整棵树会被当成"点目录"吞掉——`record` 返回 `None`，界面上一条都没有。
        let relative = change
            .path
            .strip_prefix(&me.cwd)
            .map(|rest| rest.to_string_lossy().to_string())
            .unwrap_or_else(|_| path.clone());
        let ignored = crate::changes::is_ignored(&relative);
        if let Some(record) = crate::changes::record(&path, before_text, after, ignored) {
            out.push(record);
        }
    }
    out.sort_by(|a, b| a.path.cmp(&b.path));
    out
}

/// 把一批的变化分给每条命令。
///
/// 三道判据（见 [`crate::attribution`]）：
/// 1. 落在**本会话私有**的树里 → 直接归本会话；
/// 2. 落在**别人私有**的树里 → 归那个人（本会话看不见，正常也不会出现在这儿）；
/// 3. 共享树 → 查写入口日志，再查命令文本；都不中就是 `ambiguous`。
///
/// `running` 是这一批里**同时在跑**的会话。只有一个会话时不存在歧义。
pub fn split(batch: &BatchResult, me: &Pending, running: &[String], window_ms: u64) -> Outcome {
    let mut mine = Vec::new();
    let mut ambiguous = Vec::new();
    for change in &batch.changed {
        // 私有树：归属是确定的
        if let Some(owner) = &change.private_to {
            if owner == &me.session {
                mine.push(change.clone());
            }
            // 别人的私有树：不是我的，也不是"判不出"
            continue;
        }
        match crate::attribution::attribute(
            &change.path,
            &me.command,
            me.script.as_deref(),
            Some(&me.session),
            running,
            window_ms,
        ) {
            crate::attribution::Owner::Sure(owner) => {
                if owner == me.session {
                    mine.push(change.clone());
                }
            }
            crate::attribution::Owner::Ambiguous => ambiguous.push(change.clone()),
        }
    }
    Outcome {
        call_id: me.call_id.clone(),
        session: me.session.clone(),
        changed: mine,
        ambiguous,
        truncated: batch.truncated,
        batched: batch.members.len(),
    }
}

/// 后台线程该不该醒：有排好的批、并且到点了。
///
/// 给驱动方（Kotlin 侧的一个定时器，或者 core 里一个线程）用：
/// 返回 `Some(成员)` = 现在该扫这一批了；`None` = 还没到点，等 `wait_for`。
pub fn claim_ready(now: Instant) -> Option<Vec<Pending>> {
    let mut state = state().lock().unwrap_or_else(|p| p.into_inner());
    if state.scanning {
        return None;
    }
    if now < *state.ready.as_ref()? {
        return None;
    }
    state.ready = None;
    let members = std::mem::take(&mut state.pending);
    if members.is_empty() {
        return None;
    }
    state.scanning = true;
    Some(members)
}

/// 一批扫完了：把结论分发给成员，放开 `scanning`。
pub fn finish(batch: BatchResult) {
    let mut state = state().lock().unwrap_or_else(|p| p.into_inner());
    for call_id in &batch.members {
        state.results.insert(call_id.clone(), batch.clone());
    }
    state.scanning = false;
    // 这一批扫的过程中又来了新命令：立刻给下一批定个时间点，别让它一直等着。
    if !state.pending.is_empty() && state.ready.is_none() {
        state.ready = Some(Instant::now());
    }
    wake().notify_all();
}

/// 距离下一批该扫还有多久。`None` = 没有待扫的。
pub fn next_deadline(now: Instant) -> Option<Duration> {
    let state = state().lock().unwrap_or_else(|p| p.into_inner());
    if state.scanning {
        return None;
    }
    Some(state.ready.as_ref()?.saturating_duration_since(now))
}

/// 清空（测试用）。
#[doc(hidden)]
pub fn clear() {
    let mut state = state().lock().unwrap_or_else(|p| p.into_inner());
    state.pending.clear();
    state.ready = None;
    state.results.clear();
    state.scanning = false;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    /// 这一组测试**必须串行**：`settle` 的状态是进程级的（生产里多条会话共用一份）。
    /// 锁在 `crate::test_lock`，与 `scanner` 的测试共用一把。
    use crate::test_lock::serial;

    fn pending(session: &str, call: &str, command: &str) -> Pending {
        Pending {
            session: session.to_string(),
            call_id: call.to_string(),
            command: command.to_string(),
            script: None,
            cwd: PathBuf::from("/workspace"),
            before: std::sync::Arc::new(crate::scanner::Baseline::default()),
        }
    }

    #[test]
    fn read_only_commands_are_not_queued() {
        assert!(!writes_maybe("ls -la"));
        assert!(!writes_maybe("cat src/main.rs"));
        assert!(!writes_maybe("grep -rn foo src/"));
        assert!(!writes_maybe("git status"));
        assert!(!writes_maybe("git log --oneline -5"));
        assert!(!writes_maybe("git diff HEAD~1"));
        // git 的写子命令照常算写
        assert!(writes_maybe("git commit -m x"));
        assert!(writes_maybe("git checkout -- ."));
        assert!(!writes_maybe(""));
    }

    #[test]
    fn write_commands_are_queued() {
        assert!(writes_maybe("echo hi > a.txt"));
        assert!(writes_maybe("cp a b"));
        assert!(writes_maybe("rm -rf out"));
        assert!(writes_maybe("sed -i 's/a/b/' x.rs"));
        assert!(writes_maybe("python3 gen.py"));
        assert!(writes_maybe("bash build.sh"));
        assert!(writes_maybe("npm install"));
        // 认不出的按"可能写"处理
        assert!(writes_maybe("some-custom-tool --flag"));
    }

    #[test]
    fn queue_merges_commands_arriving_within_the_window() {
        let _guard = serial();
        clear();
        enqueue(
            pending("s1", "c1", "echo a > a.txt"),
            Duration::from_millis(50),
        );
        // 窗口内来的第二条：应该并进同一批
        enqueue(
            pending("s2", "c2", "echo b > b.txt"),
            Duration::from_millis(50),
        );
        let members = claim_ready(Instant::now() + Duration::from_millis(60)).unwrap();
        assert_eq!(members.len(), 2);
        clear();
    }

    #[test]
    fn nothing_is_claimed_before_the_deadline() {
        let _guard = serial();
        clear();
        enqueue(
            pending("s1", "c1", "echo a > a.txt"),
            Duration::from_millis(50),
        );
        assert!(claim_ready(Instant::now()).is_none());
        // 没到点：claim 不该动到队列（否则那条命令的结果永远回不来）
        assert!(next_deadline(Instant::now()).is_some());
        // 也不该有 deadline 之外的忙等
        assert!(next_deadline(Instant::now()).is_some());
        clear();
    }

    #[test]
    fn only_one_claimer_at_a_time() {
        let _guard = serial();
        clear();
        enqueue(
            pending("s1", "c1", "echo a > a.txt"),
            Duration::from_millis(0),
        );
        let now = Instant::now() + Duration::from_millis(1);
        assert!(claim_ready(now).is_some());
        // 第二个线程拿不到（正在扫）
        assert!(claim_ready(now).is_none());
        clear();
    }

    #[test]
    fn results_are_handed_back_per_call() {
        let _guard = serial();
        clear();
        enqueue(
            pending("s1", "c1", "echo a > a.txt"),
            Duration::from_millis(0),
        );
        let members = claim_ready(Instant::now() + Duration::from_millis(1)).unwrap();
        finish(BatchResult {
            changed: Vec::new(),
            truncated: false,
            members: members.iter().map(|p| p.call_id.clone()).collect(),
            texts: Default::default(),
        });
        assert!(take("c1").is_some());
        // 取走即删
        assert!(take("c1").is_none());
        clear();
    }

    #[test]
    fn commands_during_scanning_get_a_new_deadline() {
        let _guard = serial();
        clear();
        enqueue(
            pending("s1", "c1", "echo a > a.txt"),
            Duration::from_millis(0),
        );
        let now = Instant::now() + Duration::from_millis(1);
        let members = claim_ready(now).unwrap();
        // 扫的过程中又来一条
        enqueue(
            pending("s2", "c2", "echo b > b.txt"),
            Duration::from_millis(50),
        );
        // 扫描期间不该有第二个线程来取：`next_deadline` 给 None 是**对的**。
        assert!(next_deadline(Instant::now()).is_none());
        finish(BatchResult {
            changed: Vec::new(),
            truncated: false,
            members: members.iter().map(|p| p.call_id.clone()).collect(),
            texts: Default::default(),
        });
        // 扫完之后，新的那条必须能等到一个 deadline，不能永远挂着。
        assert!(next_deadline(Instant::now()).is_some());
        clear();
    }

    /// 端到端：真的跑一条命令改文件，`records` 要把它报出来。
    ///
    /// 这一条钉的是整条链：`mark` → 命令 → `enqueue` → `run_batch` → `records`。
    /// 任何一环断了它都会红，而单看某一个模块的测试全绿。
    #[cfg(unix)]
    #[test]
    fn a_real_shell_write_shows_up_in_records() {
        let _guard = serial();
        clear();
        // `mark` 走的是 `scanner` 的缓存（250 ms），不清它可能拿到上一个测试的快照。
        crate::scanner::clear();
        let dir = tempfile::tempdir().unwrap();
        let roots = [crate::scanner::Root::shared(dir.path())];

        // 命令开始前取基线（这一步是整条链的关键，缺了差异恒为空）
        let before = mark(&roots, crate::scanner::DEFAULT_LIMIT);
        std::fs::write(dir.path().join("made.txt"), "hello\nworld\n").unwrap();

        let me = Pending {
            session: "s1".to_string(),
            call_id: "c1".to_string(),
            command: "printf hello > made.txt".to_string(),
            script: None,
            cwd: dir.path().to_path_buf(),
            before,
        };
        let batch = run_batch(&[me.clone()], &roots, crate::scanner::DEFAULT_LIMIT);
        assert_eq!(batch.changed.len(), 1, "应当扫到那一个文件");
        assert!(batch.changed[0].created);

        let rows = records(&batch, &me, &["s1".to_string()], 5_000);
        assert_eq!(rows.len(), 1);
        assert!(rows[0].path.ends_with("made.txt"));
        assert_eq!(rows[0].kind, crate::changes::ChangeKind::Created);
        assert_eq!(rows[0].added, 2, "两行都是新增");
        assert!(rows[0].diff.as_ref().unwrap().contains("+hello"));
        clear();
    }

    /// 命令没改东西 → 一条记录都不该有（空差异不能变成"改了 0 行"的记录）。
    #[cfg(unix)]
    #[test]
    fn a_command_that_changes_nothing_yields_no_records() {
        let _guard = serial();
        clear();
        crate::scanner::clear();
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("existing.txt"), "same\n").unwrap();
        let roots = [crate::scanner::Root::shared(dir.path())];
        let before = mark(&roots, crate::scanner::DEFAULT_LIMIT);
        // 什么也不写
        let me = Pending {
            session: "s1".to_string(),
            call_id: "c1".to_string(),
            command: "true".to_string(),
            script: None,
            cwd: dir.path().to_path_buf(),
            before,
        };
        let batch = run_batch(&[me.clone()], &roots, crate::scanner::DEFAULT_LIMIT);
        assert!(batch.changed.is_empty());
        assert!(records(&batch, &me, &["s1".to_string()], 5_000).is_empty());
        clear();
    }

    /// 二进制文件（读不成字符串）也给一条记录，但没有差异——界面据此标「无法预览」。
    #[cfg(unix)]
    #[test]
    fn binary_output_gets_a_record_without_a_diff() {
        let _guard = serial();
        clear();
        crate::scanner::clear();
        let dir = tempfile::tempdir().unwrap();
        let roots = [crate::scanner::Root::shared(dir.path())];
        let before = mark(&roots, crate::scanner::DEFAULT_LIMIT);
        std::fs::write(dir.path().join("a.bin"), [0u8, 1, 2, 3, 0xff]).unwrap();
        let me = Pending {
            session: "s1".to_string(),
            call_id: "c1".to_string(),
            command: "cc -o a.bin x.c".to_string(),
            script: None,
            cwd: dir.path().to_path_buf(),
            before,
        };
        let batch = run_batch(&[me.clone()], &roots, crate::scanner::DEFAULT_LIMIT);
        let rows = records(&batch, &me, &["s1".to_string()], 5_000);
        assert_eq!(rows.len(), 1);
        assert!(rows[0].diff.is_none(), "二进制不该给差异");
    }

    #[test]
    fn split_gives_private_files_to_their_owner() {
        let me = pending("s1", "c1", "ls");
        let batch = BatchResult {
            changed: vec![Changed {
                path: PathBuf::from("/sessions/s1/tmp/x.txt"),
                created: true,
                private_to: Some("s1".to_string()),
            }],
            truncated: false,
            members: vec!["c1".to_string()],
            texts: Default::default(),
        };
        let outcome = split(&batch, &me, &["s1".to_string()], 5_000);
        assert_eq!(outcome.changed.len(), 1);
        assert!(outcome.ambiguous.is_empty());
    }

    #[test]
    fn split_does_not_claim_another_sessions_private_files() {
        let me = pending("s1", "c1", "ls");
        let batch = BatchResult {
            changed: vec![Changed {
                path: PathBuf::from("/sessions/s2/tmp/x.txt"),
                created: true,
                private_to: Some("s2".to_string()),
            }],
            truncated: false,
            members: vec!["c1".to_string()],
            texts: Default::default(),
        };
        let outcome = split(&batch, &me, &["s1".to_string(), "s2".to_string()], 5_000);
        // 既不是我的，也不该算"判不出"——它明确是 s2 的
        assert!(outcome.changed.is_empty());
        assert!(outcome.ambiguous.is_empty());
    }

    #[test]
    fn shared_files_are_judged_by_command_text() {
        let me = pending("s1", "c1", "echo x > out/data.bin");
        let batch = BatchResult {
            changed: vec![
                Changed {
                    path: PathBuf::from("/workspace/out/data.bin"),
                    created: true,
                    private_to: None,
                },
                Changed {
                    path: PathBuf::from("/workspace/other/thing.txt"),
                    created: true,
                    private_to: None,
                },
            ],
            truncated: false,
            members: vec!["c1".to_string(), "c2".to_string()],
            texts: Default::default(),
        };
        let outcome = split(&batch, &me, &["s1".to_string(), "s2".to_string()], 5_000);
        // 命令里写了 data.bin → 归我
        assert!(outcome.changed.iter().any(|c| c.path.ends_with("data.bin")));
        // thing.txt 判不出 → ambiguous（由上层加给所有在跑的会话）
        assert!(outcome
            .ambiguous
            .iter()
            .any(|c| c.path.ends_with("thing.txt")));
    }

    #[test]
    fn single_session_means_no_ambiguity() {
        let me = pending("s1", "c1", "make -j8");
        let batch = BatchResult {
            changed: vec![Changed {
                path: PathBuf::from("/workspace/whatever.bin"),
                created: true,
                private_to: None,
            }],
            truncated: false,
            members: vec!["c1".to_string()],
            texts: Default::default(),
        };
        let outcome = split(&batch, &me, &["s1".to_string()], 5_000);
        assert_eq!(outcome.changed.len(), 1);
        assert!(outcome.ambiguous.is_empty());
    }
}
