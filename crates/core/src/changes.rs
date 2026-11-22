//! 文件变更（D372 / D377）：**在写入口记录**，不做文件系统监听。
//!
//! 为什么不是 inotify / 目录监听：那东西只知道"某个路径变了"，说不出是**哪个会话**改的
//! ——它手里只有 inode 和目录，没有调用栈。要判断会话只能靠时间窗口去猜，而并发会话
//! （`TurnRunner` 允许不同会话同时跑）一撞就猜错。而用户要的恰恰是"这次改动属于哪条会话"。
//!
//! 写入口（[`crate::workspace::Workspace::write_file`] / `edit_file`）知道一切：
//! 路径、旧内容、新内容，以及 `ToolContext.call_session`。会话归属是结构性的，不是推断的。
//!
//! 第二条理由：`/workspace` 现在八万多个文件，inotify 的 watch 是按目录注册的、不递归，
//! `max_user_watches` 通常 8192，在 proot 里还要再过一层路径翻译。真开起来先撞配额。
//!
//! # 只认工具的写操作
//!
//! `system_shell` / `envir_shell` 跑的是任意命令，它改了哪些文件**这一层不知道**。
//! 想统计只能前后各扫一遍目录（mtime + 大小），代价是两次全树遍历，而且拿不到内容、
//! 也就没有差异。这一版**不统计 shell**，界面上如实写"命令可能改过文件，未逐个列出"。
//! 从命令行猜"它改了哪些文件"是猜，猜错比不列更糟。

use serde::{Deserialize, Serialize};

/// 一次写操作的结果类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChangeKind {
    /// 之前不存在。
    Created,
    /// 之前存在，内容变了。
    Modified,
}

impl ChangeKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ChangeKind::Created => "created",
            ChangeKind::Modified => "modified",
        }
    }

    pub fn parse(raw: &str) -> Option<Self> {
        match raw {
            "created" => Some(ChangeKind::Created),
            "modified" => Some(ChangeKind::Modified),
            _ => None,
        }
    }
}

/// 一个文件在这一轮里的变更。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileChange {
    /// 给人看的路径（`/workspace/a/b.rs` 或 `/tmp/x.txt`）。
    pub path: String,
    pub kind: ChangeKind,
    /// 新增的行数（`+` 行）。
    pub added: u32,
    /// 删掉的行数（`-` 行）。
    pub removed: u32,
    /// 统一差异。`None` = 没算（文件太大 / 二进制 / 没开这一项）。
    pub diff: Option<String>,
    /// 文件本身的行数（改完之后）。界面拿它说"这是第 1–120 行里的一段"。
    pub lines: u32,
    /// 字节数（改完之后）。
    pub bytes: u64,
}

impl FileChange {
    /// 界面上那个 `+6 −13`。两边都是 0 时不给（纯改名之类）。
    pub fn counts(&self) -> Option<(u32, u32)> {
        if self.added == 0 && self.removed == 0 {
            None
        } else {
            Some((self.added, self.removed))
        }
    }
}

/// 差异的上限：超过这么多行就不算了，只给行数变化。
///
/// 为什么要有：一个 3 万行的文件改两行，DP 求 LCS 要 9 亿个格子。而界面上真正能读的
/// 差异也就是几十行，剩下的滚十屏也没人看。
pub const MAX_DIFF_LINES: usize = 4000;

/// 差异里最多保留多少个 hunk。超了就截断并说明——留一半比留假的强。
pub const MAX_HUNKS: usize = 40;

/// 上下文行数（差异里 `-`/`+` 前后各留几行）。
pub const CONTEXT_LINES: usize = 3;

/// 目录名黑名单：命中任一段就不记、也不列。
///
/// 这些目录里的东西**不是用户写的**，是构建工具和包管理器写的。列出来只会把真正的改动淹掉。
/// 用户 2026-11-22 给的那张截图就是活证据：一整屏 `target/debug/deps/…`、`incremental/…`、
/// `.l2s.n…`，而真正改过的 `scanner.rs` / `lib.rs` 被挤到最上面两行。
///
/// **按生态列，不按"看起来像临时目录"列**：每个生态的产物目录名字是固定的，
/// 猜一个漏一个，漏的那个就会淹掉整屏。
pub const IGNORED_SEGMENTS: &[&str] = &[
    // 版本控制
    ".git",
    ".hg",
    ".svn",
    // Rust
    "target",
    // Node / 前端
    "node_modules",
    "dist",
    ".next",
    ".nuxt",
    ".svelte-kit",
    ".turbo",
    ".parcel-cache",
    // JVM / Android
    "build",
    ".gradle",
    ".idea",
    ".kotlin",
    // Python
    "__pycache__",
    ".venv",
    "venv",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    "site-packages",
    // C / C++ / CMake
    "CMakeFiles",
    ".cmake",
    // 通用缓存
    ".cache",
    ".cargo",
    ".npm",
    ".yarn",
    "coverage",
    ".nyc_output",
];

/// 路径里有没有该忽略的目录段。
///
/// 只按**整段**比：`target` 命中 `a/target/b`，不命中 `a/mytarget/b`。
/// 前缀匹配会让 `my_target_dir` 这种名字被误伤，而误伤是静默的（那个文件就是不在列表里）。
///
/// 点开头的段一律当目录跳过（`.git` / `.gradle` / `.venv` 这类构建中间层）——
/// kelivo 也是这么做的（`conversation_files.dart` 里 `name.startsWith('.')`），
/// 理由一样：点目录里几乎全是工具自己的状态，不是用户写的。
/// **最后一段不算目录**：`.env`、`.gitignore` 这种是文件名，照常认。
///
/// 另有一条**文件名前缀**规则：`.l2s.` 开头的文件是 rustc 的增量编译中间产物
/// （`/workspace` 里一个 target 目录就能产出上万个）。它跟"点目录"不是一回事，
/// 所以单独写一行，别塞进上面那个循环里。
pub fn is_ignored(path: &str) -> bool {
    let segments: Vec<&str> = path.split(['/', '\\']).filter(|s| !s.is_empty()).collect();
    let last = segments.len().saturating_sub(1);
    for (index, segment) in segments.iter().enumerate() {
        if index < last && segment.starts_with('.') {
            return true;
        }
        if IGNORED_SEGMENTS.contains(segment) {
            return true;
        }
    }
    // rustc 增量编译的中间文件（与 kelivo 的同名规则一致）。
    segments
        .last()
        .map(|name| name.starts_with(".l2s."))
        .unwrap_or(false)
}

/// 算一份统一差异。
///
/// 形状照 `git diff`：`--- a/…`、`+++ b/…`、`@@ -起,数 +起,数 @@`，`-`/`+`/` ` 前缀。
/// 先剥掉公共前后缀再求 LCS——绝大多数改动是"中间改几行"，剥完要算的那一段很小。
///
/// 返回 `None` = 不算（任一边超过 [`MAX_DIFF_LINES`]）。**不返回半份**：
/// 半份差异看起来像"只改了这些"，比不给更糟。
pub fn unified_diff(old: &str, new: &str, path: &str) -> Option<String> {
    let old_lines: Vec<&str> = old.split_inclusive('\n').collect();
    let new_lines: Vec<&str> = new.split_inclusive('\n').collect();
    if old_lines.len() > MAX_DIFF_LINES || new_lines.len() > MAX_DIFF_LINES {
        return None;
    }

    // 公共前缀 / 后缀：剥掉之后通常只剩几行。
    let mut head = 0;
    while head < old_lines.len() && head < new_lines.len() && old_lines[head] == new_lines[head] {
        head += 1;
    }
    let mut tail = 0;
    while tail < old_lines.len() - head
        && tail < new_lines.len() - head
        && old_lines[old_lines.len() - 1 - tail] == new_lines[new_lines.len() - 1 - tail]
    {
        tail += 1;
    }

    let old_mid = &old_lines[head..old_lines.len() - tail];
    let new_mid = &new_lines[head..new_lines.len() - tail];

    // 中间那一段的编辑脚本：`true` = 保留，`false` = 删 / 增。
    let (old_keep, new_keep) = lcs_keep(old_mid, new_mid);

    // 拼成"每个旧行去哪、每个新行从哪来"的成对序列。
    //
    // **前后缀要一起拼回来**：只拼中段的话，差异里一行上下文都没有，
    // 读的人看不到"改的是哪个函数里的第几行"。切 hunk 时的 `CONTEXT_LINES`
    // 也是从这个完整序列里取的。
    let mut rows: Vec<(Option<&str>, Option<&str>)> = Vec::new();
    for line in &old_lines[..head] {
        rows.push((Some(line), Some(line)));
    }
    let mut j = 0;
    for index in 0..old_mid.len() {
        if old_keep[index] {
            while j < new_mid.len() && !new_keep[j] {
                rows.push((None, Some(new_mid[j])));
                j += 1;
            }
            rows.push((Some(old_mid[index]), Some(new_mid[j])));
            j += 1;
        } else {
            rows.push((Some(old_mid[index]), None));
        }
    }
    while j < new_mid.len() {
        rows.push((None, Some(new_mid[j])));
        j += 1;
    }
    for line in &old_lines[old_lines.len() - tail..] {
        rows.push((Some(line), Some(line)));
    }

    if rows.is_empty() {
        // 内容一模一样。调用方本来不该走到这儿，但别返回一份空差异。
        return None;
    }
    // 前后缀拼回来之后，"没有一行被增删"就等于没改。这一步必须判：
    // 不判的话会返回一份只有 `---` / `+++` 两行的差异，界面上就是"改了一个空文件"。
    if rows
        .iter()
        .all(|(left, right)| left.is_some() && right.is_some())
    {
        return None;
    }

    let mut out = String::new();
    out.push_str(&format!("--- a/{path}\n+++ b/{path}\n"));

    // 按"连续改动 + 前后 CONTEXT_LINES 行"切 hunk。
    //
    // 用**完整序列的下标**做游标，不另外维护 `old_no` / `new_no` 两套计数器：
    // 两套计数器一错位，hunk 头里的行号就是错的，而错的行号看着仍然"像那么回事"。
    // 行号由下标现算——下标只有一个来源。
    let mut hunks = 0;
    let mut cursor = 0;
    while cursor < rows.len() {
        // 跳过前面没改的那一段。
        while cursor < rows.len() && rows[cursor].0.is_some() && rows[cursor].1.is_some() {
            cursor += 1;
        }
        if cursor >= rows.len() {
            break;
        }
        if hunks >= MAX_HUNKS {
            out.push_str("@@ … 差异太长，后面的没列 @@\n");
            break;
        }

        let back = cursor.saturating_sub(CONTEXT_LINES);
        // 往后走，直到"连续 CONTEXT_LINES * 2 行没动静"。
        let mut end = cursor;
        let mut quiet = 0;
        while end < rows.len() {
            let (left, right) = rows[end];
            if left.is_some() && right.is_some() {
                quiet += 1;
                if quiet > CONTEXT_LINES * 2 {
                    break;
                }
            } else {
                quiet = 0;
            }
            end += 1;
        }

        let (at_old, removed) = line_span(&rows[..back], &rows[back..end], true);
        let (at_new, added) = line_span(&rows[..back], &rows[back..end], false);
        out.push_str(&format!("@@ -{at_old},{removed} +{at_new},{added} @@\n"));
        for (left, right) in &rows[back..end] {
            match (left, right) {
                (Some(a), Some(_)) => {
                    out.push(' ');
                    out.push_str(a);
                    if !a.ends_with('\n') {
                        out.push('\n');
                    }
                }
                (Some(a), None) => {
                    out.push('-');
                    out.push_str(a);
                    if !a.ends_with('\n') {
                        out.push('\n');
                    }
                }
                (None, Some(b)) => {
                    out.push('+');
                    out.push_str(b);
                    if !b.ends_with('\n') {
                        out.push('\n');
                    }
                }
                (None, None) => {}
            }
        }
        cursor = end;
        hunks += 1;
    }

    Some(out)
}

/// 一个 hunk 在旧文件 / 新文件里的起始行号与行数。
///
/// `before` 是这个 hunk 之前的所有行（只用来数起始行号），`inside` 是 hunk 自己。
/// `old_side` 为真数左边，否则数右边。行号 **1 基**，与 `git diff` 一致。
fn line_span(
    before: &[(Option<&str>, Option<&str>)],
    inside: &[(Option<&str>, Option<&str>)],
    old_side: bool,
) -> (usize, usize) {
    let count = |rows: &[(Option<&str>, Option<&str>)]| {
        rows.iter()
            .filter(|(left, right)| {
                if old_side {
                    left.is_some()
                } else {
                    right.is_some()
                }
            })
            .count()
    };
    // 起始行号 = 前面有几行 + 1。hunk 里一行都没有时，指向"上一行之后"，
    // 与 git 的写法一致（`-3,0` 表示在第 3 行之后插入）。
    (count(before) + 1, count(inside))
}

/// 最长公共子序列：返回两边的"保留"掩码。
///
/// 只在中段（剥掉公共前后缀之后）调用，长度已经很小；超过 [`MAX_DIFF_LINES`] 的情况
/// 在 [`unified_diff`] 里就被挡住了。仍然加一道保险：中段太大就退化成"全删全增"，
/// 那样差异难看但**正确**，而卡住不动是错的。
fn lcs_keep(old: &[&str], new: &[&str]) -> (Vec<bool>, Vec<bool>) {
    const GUARD: usize = 1500;
    if old.len() > GUARD || new.len() > GUARD {
        return (vec![false; old.len()], vec![false; new.len()]);
    }
    let n = old.len();
    let m = new.len();
    let mut table = vec![vec![0u32; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            table[i][j] = if old[i] == new[j] {
                table[i + 1][j + 1] + 1
            } else {
                table[i + 1][j].max(table[i][j + 1])
            };
        }
    }
    let mut old_keep = vec![false; n];
    let mut new_keep = vec![false; m];
    let mut i = 0;
    let mut j = 0;
    while i < n && j < m {
        if old[i] == new[j] {
            old_keep[i] = true;
            new_keep[j] = true;
            i += 1;
            j += 1;
        } else if table[i + 1][j] >= table[i][j + 1] {
            i += 1;
        } else {
            j += 1;
        }
    }
    (old_keep, new_keep)
}

/// 从一份差异里数出 `+` / `-` 的行数（`+++` / `---` 那两行不算）。
///
/// 为什么不另算一遍：界面上的 `+6 −13` 必须和它下面那份差异对得上。两处各数一次，
/// 迟早出现"标着 +6，展开只有 5 行"。
pub fn count_changes(diff: &str) -> (u32, u32) {
    let mut added = 0;
    let mut removed = 0;
    for line in diff.lines() {
        if line.starts_with("+++") || line.starts_with("---") || line.starts_with("@@") {
            continue;
        }
        if line.starts_with('+') {
            added += 1;
        } else if line.starts_with('-') {
            removed += 1;
        }
    }
    (added, removed)
}

/// 造一条变更记录。
///
/// `before` 为 `None` = 这个文件之前不存在（`Created`）。
///
/// 两件事在这一处收口，调用方不必各写一遍：
/// 1. `added` / `removed` **从差异里数**（没有差异时退回"整体替换"的行数）；
/// 2. 二进制 / 太大时 `diff` 给 `None`，界面据此只说"文件变了"，不假装有差异。
pub fn record(path: &str, before: Option<&str>, after: &str, ignored: bool) -> Option<FileChange> {
    if ignored || is_ignored(path) {
        return None;
    }
    let kind = if before.is_some() {
        ChangeKind::Modified
    } else {
        ChangeKind::Created
    };
    let lines = after.lines().count() as u32;
    let bytes = after.len() as u64;

    let diff = match before {
        Some(old) => unified_diff(old, after, path),
        // 新建：整份都是新增，差异就是把每一行前面加 `+`。
        None => {
            let new_lines: Vec<&str> = after.split_inclusive('\n').collect();
            if new_lines.len() > MAX_DIFF_LINES {
                None
            } else {
                let mut text = format!("--- /dev/null\n+++ b/{path}\n");
                text.push_str(&format!("@@ -0,0 +1,{} @@\n", new_lines.len()));
                for line in new_lines {
                    text.push('+');
                    text.push_str(line);
                    if !line.ends_with('\n') {
                        text.push('\n');
                    }
                }
                Some(text)
            }
        }
    };

    let (added, removed) = match &diff {
        Some(text) => count_changes(text),
        // 差异没算（太大 / 二进制）。这时**不能报 0**：报 0 等于说"什么都没变"，
        // 而调用方刚刚才写了一个文件。退回"整份替换"的行数——难看，但不说假话。
        None => {
            let removed = before.map(|old| old.lines().count() as u32).unwrap_or(0);
            (lines, removed)
        }
    };

    Some(FileChange {
        path: path.to_string(),
        kind,
        added,
        removed,
        diff,
        lines,
        bytes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ignored_segments_match_whole_parts_only() {
        assert!(is_ignored("/workspace/node_modules/x.js"));
        assert!(is_ignored("/workspace/NMoi-Agent/target/debug/x"));
        assert!(is_ignored(".git/config"));
        // 整段比，不是前缀比
        assert!(!is_ignored("/workspace/mytarget/x.rs"));
        assert!(!is_ignored("/workspace/targets/x.rs"));
        assert!(!is_ignored("/workspace/src/main.rs"));
    }

    #[test]
    fn dot_directories_are_skipped_but_dot_files_are_not() {
        assert!(is_ignored("/w/.gradle/x"));
        assert!(is_ignored("/w/.l2s.query-cache.bin0001.0002"));
        assert!(is_ignored("/w/a/.venv/lib/x.py"));
        // 点开头的**文件**是用户的东西，不该被吞掉
        assert!(!is_ignored("/w/.env"));
        assert!(!is_ignored("/w/.gitignore"));
        assert!(!is_ignored("/w/config/.env.local"));
    }

    #[test]
    fn counts_match_the_diff_it_ships() {
        let old = "a\nb\nc\n";
        let new = "a\nB\nc\n";
        let change = record("/workspace/x.txt", Some(old), new, false).unwrap();
        let text = change.diff.as_ref().unwrap();
        let (added, removed) = count_changes(text);
        assert_eq!((change.added, change.removed), (added, removed));
        assert_eq!(change.added, 1);
        assert_eq!(change.removed, 1);
    }

    #[test]
    fn created_file_is_all_additions() {
        let change = record("/workspace/new.txt", None, "x\ny\n", false).unwrap();
        assert_eq!(change.kind, ChangeKind::Created);
        assert_eq!(change.added, 2);
        assert_eq!(change.removed, 0);
        assert!(change.diff.as_ref().unwrap().contains("--- /dev/null"));
    }

    #[test]
    fn diff_has_git_shaped_headers_and_hunks() {
        let old = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        let new = "1\n2\n3\n4\nfive\n6\n7\n8\n9\n";
        let text = unified_diff(old, new, "/workspace/x.txt").unwrap();
        assert!(text.starts_with("--- a//workspace/x.txt\n+++ b//workspace/x.txt\n"));
        assert!(text.contains("@@ -"));
        assert!(text.contains("-5\n"));
        assert!(text.contains("+five\n"));
        // 上下文带上
        assert!(text.contains(" 4\n"));
    }

    #[test]
    fn unchanged_content_yields_no_diff() {
        assert!(unified_diff("a\nb\n", "a\nb\n", "x").is_none());
    }

    #[test]
    fn huge_files_are_refused_instead_of_half_diffed() {
        let old = "a\n".repeat(MAX_DIFF_LINES + 1);
        let new = "b\n".repeat(MAX_DIFF_LINES + 1);
        assert!(unified_diff(&old, &new, "x").is_none());
        // 但记录仍然产生：行数变化还是要知道的
        let change = record("/workspace/big.txt", Some(&old), &new, false).unwrap();
        assert!(change.diff.is_none());
        assert_eq!(change.added, (MAX_DIFF_LINES + 1) as u32);
        assert_eq!(change.removed, (MAX_DIFF_LINES + 1) as u32);
    }

    #[test]
    fn ignored_paths_produce_no_record() {
        assert!(record("/workspace/target/debug/x", Some("a"), "b", false).is_none());
        assert!(record("/workspace/src/x.rs", Some("a"), "b", true).is_none());
    }

    #[test]
    fn appended_lines_are_counted_as_additions_only() {
        let change = record("/workspace/x.txt", Some("a\nb\n"), "a\nb\nc\nd\n", false).unwrap();
        assert_eq!(change.kind, ChangeKind::Modified);
        assert_eq!(change.added, 2);
        assert_eq!(change.removed, 0);
        assert_eq!(change.lines, 4);
    }

    #[test]
    fn multiline_insert_keeps_context_bounded() {
        let old: String = (0..40)
            .map(|i| format!("line{i}\n"))
            .collect::<Vec<_>>()
            .join("");
        let mut new = old.clone();
        new.push_str("tail\n");
        let text = unified_diff(&old, &new, "x").unwrap();
        // 只有一处改动，hunk 数不该爆炸
        assert_eq!(text.matches("@@").count(), 2); // 一对 -n,m +n,m
        assert!(text.contains("+tail\n"));
    }
}
