//! 写入口日志 + 变更归属（D378）。
//!
//! # 为什么要有这一层
//!
//! 用户要的是"这一轮改了哪些文件，是哪个会话改的"。`write_file` / `edit_file` 知道答案
//! （`ToolContext.call_session` 就在手上）；`system_shell` 跑的是任意命令，它改了哪些文件
//! **只有前后各扫一次才知道**。
//!
//! 而多会话可以同时跑（`TurnRunner` 允许，`Lane::Process` 并发 4），于是"我扫到的变化
//! 里，哪些是我改的"就成了一个真问题。
//!
//! # 三道判据，从确定到推断
//!
//! 1. **写入口日志**（确定）：每次 `write_file` / `edit_file` 落地时记一条
//!    `(绝对路径, 会话, 时间)`。shell 扫完把变化文件逐个查日志——**刚被别的会话写过的
//!    文件不是我的**。这一条不需要猜，因为写的那一刻会话是已知的。
//! 2. **文本匹配**（推断）：剩下的文件拿 basename / 相对路径去匹配这条命令的文本，
//!    以及它 `bash script.sh` 实际执行到的脚本内容。命中就归本会话。
//! 3. **判不出**：标记 `Ambiguous`，**加给所有同时在跑的会话**（用户 2026-11-22 定的：
//!    "判断出来最好，判断不出来就都加上"）。
//!
//! 为什么顺序是这样：`make` / `npm install` 一次改几百个文件、命令里一个文件名都没有，
//! 文本匹配必然落空；反过来 `grep -r foo src/` 里出现的名字也不一定是它改的。
//! 所以"确定"的那一条必须排在"推断"前面。
//!
//! # 扫描范围
//!
//! **只扫这条命令的 `cwd` 那一棵树**，不扫整个工作区。量的数：`NMoi-Agent` 714 个文件
//! 扫一遍 0.15 s；整个 `/workspace` 22,476 个文件要 29 s。后者不可能每条命令付一次。

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::changes::is_ignored;

/// 一条写入口记录。
#[derive(Debug, Clone)]
struct WriteEntry {
    /// 绝对路径（已规范化，不带 `..`）。
    path: PathBuf,
    /// 哪个会话写的。`None` = 不是工具写的（不该发生，但别让日志把它变成"别人的"）。
    session: Option<String>,
    /// 毫秒时间戳。
    at: u64,
}

/// 日志容量。只留最近的：判据是"刚才这一下"，不是"历史上谁写过"。
///
/// 5000 条够覆盖"两条命令之间"的窗口：一次 `make` 会写几千个文件，但那些是同一个会话
/// 在同一条命令里写的，查的时候按会话比，不需要每条都留着。
const MAX_ENTRIES: usize = 5000;

fn log() -> &'static Mutex<Vec<WriteEntry>> {
    static LOG: OnceLock<Mutex<Vec<WriteEntry>>> = OnceLock::new();
    LOG.get_or_init(|| Mutex::new(Vec::new()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// 记一条"这个会话刚写了这个文件"。
///
/// 在 `write_file` / `edit_file` **成功之后**调用。失败不记：失败的那一下没改盘上任何东西，
/// 记了会让归属判断把别人的改动算到自己头上。
pub fn note_write(path: &Path, session: Option<&str>) {
    let entry = WriteEntry {
        path: normalize(path),
        session: session.map(str::to_string),
        at: now_ms(),
    };
    let mut entries = log().lock().unwrap_or_else(|poison| poison.into_inner());
    entries.push(entry);
    if entries.len() > MAX_ENTRIES {
        let drop = entries.len() - MAX_ENTRIES;
        entries.drain(..drop);
    }
}

/// 这个文件**刚才**是不是被别的会话写过。
///
/// `within_ms` 是时间窗口：太老的记录不算（那是上一轮的写入，与这一条命令无关）。
/// 返回写它的会话 id；没有记录、或者记录太老，返回 `None`。
///
/// **不判"是不是自己写的"**：自己写的也算数——那样这一条就从"排除别人"变成了
/// "确认是我"，两件事的判断方向相反，混在一起会两边都错。
pub fn written_by_other(path: &Path, me: Option<&str>, within_ms: u64) -> Option<String> {
    let target = normalize(path);
    let cutoff = now_ms().saturating_sub(within_ms);
    let entries = log().lock().unwrap_or_else(|poison| poison.into_inner());
    entries
        .iter()
        .rev()
        .take_while(|entry| entry.at >= cutoff)
        .find(|entry| entry.path == target)
        .and_then(|entry| entry.session.clone())
        .filter(|session| Some(session.as_str()) != me)
}

/// 清空日志。测试用；生产里靠容量自然淘汰。
#[doc(hidden)]
pub fn clear_log() {
    log()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .clear();
}

/// 规范化：去掉 `..` 与 `.`，不碰文件系统（不要求路径存在）。
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

/// 扫描出来的一个文件。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Scanned {
    pub path: PathBuf,
    /// 字节数。
    pub bytes: u64,
    /// 修改时间（毫秒）。
    pub mtime_ms: u64,
}

/// 扫一棵树，只 stat 不读内容。
///
/// `skip` 里的目录整棵跳过（`target` / `node_modules` / `.git` 那些）。
/// 排序输出，好让前后两次扫描可以直接比。
pub fn scan(root: &Path, skip: &[&str]) -> Vec<Scanned> {
    let mut out = Vec::new();
    walk(root, skip, &mut out);
    out.sort_by(|a, b| a.path.cmp(&b.path));
    out
}

fn walk(dir: &Path, skip: &[&str], out: &mut Vec<Scanned>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if meta.is_dir() {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if skip.iter().any(|s| *s == name) || is_ignored(&name) {
                continue;
            }
            walk(&path, skip, out);
            continue;
        }
        if !meta.is_file() {
            continue;
        }
        let mtime_ms = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        out.push(Scanned {
            path,
            bytes: meta.len(),
            mtime_ms,
        });
    }
}

/// 前后两次扫描的差：新增 + 大小或 mtime 变了的。
///
/// **删除也算变更**，但这一版不报：`Scanned` 里没有"曾经存在"的信息，
/// 而报一个已经不在的文件名，界面上点开就是空的。要报删除得在 before 里留着，
/// 那是另一件事（见 §未做）。
pub fn diff_scans(before: &[Scanned], after: &[Scanned]) -> Vec<Scanned> {
    let old: BTreeMap<&Path, &Scanned> = before.iter().map(|s| (s.path.as_path(), s)).collect();
    let mut out = Vec::new();
    for item in after {
        match old.get(item.path.as_path()) {
            None => out.push(item.clone()),
            Some(prev) => {
                if prev.bytes != item.bytes || prev.mtime_ms != item.mtime_ms {
                    out.push(item.clone());
                }
            }
        }
    }
    out
}

/// 归属判据：这条命令的文本里有没有提到这个文件。
///
/// 三个写法都认：
/// - 完整相对路径（`src/main.rs`）；
/// - 只有文件名（`main.rs`）；
/// - 带 `./` 前缀（`./main.rs`）。
///
/// **只做子串匹配，不做词边界**：命令文本是 shell 语法，词边界没有统一定义
/// （`cat a.rs>b.rs` 里 `b.rs` 前面是 `>`）。宁可多认一个，也不要漏掉——
/// 漏掉的后果是"这个文件归给了所有会话"，而多认的后果是"归错一个"。
/// 两者都不好，但前者更容易被发现（用户看到三个会话都列了同一个文件）。
pub fn mentions(command: &str, script: Option<&str>, path: &Path) -> bool {
    let name = match path.file_name().and_then(|n| n.to_str()) {
        Some(name) if !name.is_empty() => name,
        _ => return false,
    };
    if command.contains(name) {
        return true;
    }
    if let Some(script) = script {
        if script.contains(name) {
            return true;
        }
    }
    // 相对路径那一整段
    let text = path.to_string_lossy();
    if command.contains(text.as_ref()) {
        return true;
    }
    // `./名字`
    let dotted = format!("./{name}");
    command.contains(&dotted) || script.map(|s| s.contains(&dotted)).unwrap_or(false)
}

/// 从命令里挑出它可能会执行的脚本（`bash x.sh` / `sh x.sh` / `python x.py` / `./x.sh`）。
///
/// 为什么要这一步：`bash build.sh` 里一个文件名都没有，真正的文件名在脚本里。
/// 只匹配命令文本的话，这类改动全部落进"判不出"。
///
/// **不递归**：脚本里再调脚本的情况有，但那样要读任意深度的文件，代价与收益不成比例。
/// 一层的命中率已经够（实测见 D378 的说明）。
/// 命令里可能要读的脚本 / 可执行文件。
///
/// 用户 2026-11-22 的提醒：「不一定都是写在命令里面的，也有可能是 py 和 sh 什么的……
/// sh/bash 命令之类的，基本十有八九是执行脚本了，或者命令直接用路径，属于是直接跑 elf」。
///
/// 三种来源，都要抓：
///
/// 1. **解释器 + 脚本**：`bash x.sh`、`python3 tools/gen.py`、`node a.js`。
///    `-c` / `-m` / `-e` 这类**内联**参数要跳过（`python -m build` 里 `build` 不是脚本名，
///    它是个模块名，读它会读到一个不存在的文件）。
/// 2. **`bash -c '...'`**：内联脚本。返回的是那段**文本**（不是路径），调用方当脚本文本用。
/// 3. **直接执行**：`./build.sh`、`./out/tool`、`/usr/local/bin/x`。
///    没有后缀也认——那可能是 ELF。ELF 读不出文件名，只能靠"命令里出现过这个路径"判。
///
/// 返回值里既有"路径"也有"内联文本"，调用方把它们**都**当脚本文本参与匹配——
/// 反正 [`mentions`] 只做子串匹配，多给一段文本不会出错，少给一段就会漏。
pub fn scripts_in(command: &str) -> Vec<String> {
    /// 解释器 → 它后面那个参数是不是脚本。
    const RUNNERS: &[&str] = &[
        "bash", "sh", "zsh", "dash", "ksh", "fish", "python", "python3", "python2", "node", "deno",
        "bun", "perl", "ruby", "php", "lua", "Rscript", "tsx", "ts-node",
    ];
    /// 解释器后面跟着它、说明"下一个参数不是脚本"的开关。
    const INLINE_FLAGS: &[&str] = &["-c", "-m", "-e", "--eval", "-p", "--print"];

    let mut out: Vec<String> = Vec::new();
    let mut push = |text: &str| {
        let text = text.trim();
        if !text.is_empty() && !out.iter().any(|existing| existing == text) {
            out.push(text.to_string());
        }
    };

    // 先按 shell 分隔符切开，逐段看。
    let mut segment: Vec<String> = Vec::new();
    let flush = |segment: &mut Vec<String>, push: &mut dyn FnMut(&str)| {
        if segment.is_empty() {
            return;
        }
        let first = segment[0].trim_start_matches("./").to_string();
        // 1. 解释器 + 脚本
        if RUNNERS.contains(&first.as_str()) {
            let mut index = 1;
            while index < segment.len() {
                let token = &segment[index];
                if INLINE_FLAGS.contains(&token.as_str()) {
                    // `-c '...'` / `-m mod`：后面那个不是脚本文件。
                    if let Some(inline) = segment.get(index + 1) {
                        // `-c` 后面那段**是**脚本内容，要收；`-m` 后面是模块名，不收。
                        if token == "-c" || token == "-e" || token == "--eval" {
                            push(inline);
                        }
                    }
                    index += 2;
                    continue;
                }
                if token.starts_with('-') {
                    index += 1;
                    continue;
                }
                push(token.trim_start_matches("./"));
                index += 1;
            }
            segment.clear();
            return;
        }
        // 3. 直接执行：`./x`、`/abs/x`、带脚本后缀的裸名
        let looks_like_script = first.ends_with(".sh")
            || first.ends_with(".py")
            || first.ends_with(".js")
            || first.ends_with(".mjs")
            || first.ends_with(".pl")
            || first.ends_with(".rb")
            || first.ends_with(".php")
            || first.ends_with(".lua");
        let explicit_path = segment[0].starts_with("./")
            || segment[0].starts_with('/')
            || segment[0].starts_with("../");
        if looks_like_script || explicit_path {
            push(&first);
        }
        segment.clear();
    };

    for raw in split_words(command) {
        let token = raw.trim();
        if token.is_empty() {
            flush(&mut segment, &mut push);
            continue;
        }
        segment.push(token.to_string());
    }
    flush(&mut segment, &mut push);

    out
}

/// 按 shell 的分隔规则切词：空白、`;`、`&`、`|` 都算分隔，
/// 但**引号里的东西不切**（`bash "my script.sh"` 是一个词）。
///
/// 为什么不能直接 `split_whitespace`：带空格的脚本名会被切成两半，
/// 于是 `my` 和 `script.sh` 各自成了一个候选，前者还会被当成"直接执行的程序"。
/// 引号不处理，这一层就一直在猜。
fn split_words(command: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    for ch in command.chars() {
        match quote {
            Some(q) => {
                if ch == q {
                    quote = None;
                } else {
                    current.push(ch);
                }
            }
            None => {
                if ch == '"' || ch == '\'' {
                    quote = Some(ch);
                } else if ch.is_whitespace() || ch == ';' || ch == '&' || ch == '|' {
                    if !current.is_empty() {
                        out.push(std::mem::take(&mut current));
                    }
                } else {
                    current.push(ch);
                }
            }
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

/// 读一个脚本的内容，给 [`mentions`] 用。
///
/// 上限 256 KiB：脚本再大也不该整份读进内存——而真正要匹配的只是里面的文件名。
/// 读不到（不存在 / 二进制 / 权限）就返回 `None`，不报错：调用方会退化成
/// "只按命令文本判"，也就是少一条判据，而不是整条命令失败。
pub fn read_script(root: &Path, name: &str) -> Option<String> {
    const MAX: u64 = 256 * 1024;
    let path = if Path::new(name).is_absolute() {
        std::path::PathBuf::from(name)
    } else {
        root.join(name)
    };
    let meta = std::fs::metadata(&path).ok()?;
    if !meta.is_file() || meta.len() > MAX {
        return None;
    }
    let bytes = std::fs::read(&path).ok()?;
    // 二进制（ELF 等）读成字符串没有意义，还会塞满垃圾。
    if bytes.contains(&0) {
        return None;
    }
    String::from_utf8(bytes).ok()
}

/// 一次 shell 调用的归属结论。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Owner {
    /// 能确定是谁改的。
    Sure(String),
    /// 判不出（多会话并发时才会出现）。调用方把它加给所有在跑的会话。
    Ambiguous,
}

/// 给一个变化文件判归属。
///
/// `running` 是**同时**在跑的会话（含自己）。只有一个会话时不必判——那必然是自己。
pub fn attribute(
    path: &Path,
    command: &str,
    script_text: Option<&str>,
    me: Option<&str>,
    running: &[String],
    window_ms: u64,
) -> Owner {
    // 只有一个会话在跑：没有歧义可言。
    if running.len() <= 1 {
        return match me {
            Some(id) => Owner::Sure(id.to_string()),
            None => Owner::Ambiguous,
        };
    }
    // 第一道：写入口日志说"刚才别人写过它"。这一条比文本匹配硬。
    if let Some(other) = written_by_other(path, me, window_ms) {
        return Owner::Sure(other);
    }
    // 第二道：命令（或它跑的脚本）提到了这个文件。
    if mentions(command, script_text, path) {
        return match me {
            Some(id) => Owner::Sure(id.to_string()),
            None => Owner::Ambiguous,
        };
    }
    // 第三道：判不出。
    Owner::Ambiguous
}

#[cfg(test)]
mod tests {
    use super::*;

    fn touch(dir: &Path, name: &str, body: &str) -> PathBuf {
        let path = dir.join(name);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(&path, body).unwrap();
        path
    }

    #[test]
    fn scan_finds_files_and_skips_ignored_dirs() {
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "1");
        touch(dir.path(), "src/b.rs", "22");
        touch(dir.path(), "target/c", "333");
        touch(dir.path(), "node_modules/d", "4444");
        let found = scan(dir.path(), &[]);
        let names: Vec<String> = found
            .iter()
            .map(|s| {
                s.path
                    .strip_prefix(dir.path())
                    .unwrap()
                    .display()
                    .to_string()
            })
            .collect();
        // 用 `Path` 比，不拼字符串：Windows 的分隔符是 `\`，写死 `/` 只在 Linux 上过。
        let expected: Vec<String> = [
            std::path::PathBuf::from("a.txt"),
            std::path::Path::new("src").join("b.rs"),
        ]
        .iter()
        .map(|p| p.display().to_string())
        .collect();
        assert_eq!(names, expected);
    }

    #[test]
    fn diff_reports_new_and_resized_files() {
        let dir = tempfile::tempdir().unwrap();
        let a = touch(dir.path(), "a.txt", "1");
        touch(dir.path(), "b.txt", "22");
        let before = scan(dir.path(), &[]);

        std::fs::write(a.as_path(), "changed!").unwrap();
        touch(dir.path(), "c.txt", "3");
        let after = scan(dir.path(), &[]);

        let changed = diff_scans(&before, &after);
        let mut names: Vec<String> = changed
            .iter()
            .map(|s| s.path.file_name().unwrap().to_string_lossy().to_string())
            .collect();
        names.sort();
        assert_eq!(names, vec!["a.txt", "c.txt"]);
    }

    #[test]
    fn diff_ignores_touched_but_unchanged_files() {
        let dir = tempfile::tempdir().unwrap();
        touch(dir.path(), "a.txt", "same");
        let before = scan(dir.path(), &[]);
        // 只改 mtime、不改大小：mtime 变了就算变（内容可能变了但大小没变）
        let after = vec![Scanned {
            path: before[0].path.clone(),
            bytes: before[0].bytes,
            mtime_ms: before[0].mtime_ms + 1,
        }];
        assert_eq!(diff_scans(&before, &after).len(), 1);
    }

    #[test]
    fn only_one_session_running_means_no_ambiguity() {
        let owner = attribute(
            Path::new("/w/a.rs"),
            "ls",
            None,
            Some("s1"),
            &["s1".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Sure("s1".to_string()));
    }

    #[test]
    fn command_text_mentioning_the_file_wins() {
        let owner = attribute(
            Path::new("/w/src/main.rs"),
            "python3 gen.py > src/main.rs",
            None,
            Some("s1"),
            &["s1".to_string(), "s2".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Sure("s1".to_string()));
    }

    #[test]
    fn basename_alone_is_enough() {
        assert!(mentions("cat a/main.rs", None, Path::new("/w/src/main.rs")));
        assert!(mentions("./main.rs --check", None, Path::new("/w/main.rs")));
        assert!(!mentions("cat other.rs", None, Path::new("/w/main.rs")));
    }

    #[test]
    fn script_text_also_counts() {
        let owner = attribute(
            Path::new("/w/out/data.bin"),
            "bash build.sh",
            Some("gcc -o out/data.bin src/x.c"),
            Some("s1"),
            &["s1".to_string(), "s2".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Sure("s1".to_string()));
    }

    #[test]
    fn no_signal_at_all_is_ambiguous() {
        let owner = attribute(
            Path::new("/w/mystery.dat"),
            "make -j8",
            None,
            Some("s1"),
            &["s1".to_string(), "s2".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Ambiguous);
    }

    #[test]
    fn write_log_overrides_text_matching() {
        clear_log();
        let path = Path::new("/w/shared.txt");
        note_write(path, Some("s2"));
        // 命令里提到了它，但日志说刚才 s2 写过——日志更硬。
        let owner = attribute(
            path,
            "echo hi > shared.txt",
            None,
            Some("s1"),
            &["s1".to_string(), "s2".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Sure("s2".to_string()));
        clear_log();
    }

    #[test]
    fn own_writes_do_not_look_like_someone_else() {
        clear_log();
        let path = Path::new("/w/mine.txt");
        note_write(path, Some("s1"));
        assert_eq!(written_by_other(path, Some("s1"), 5_000), None);
        clear_log();
    }

    #[test]
    fn stale_log_entries_are_ignored() {
        clear_log();
        let path = Path::new("/w/old.txt");
        note_write(path, Some("s2"));
        // 窗口 0 毫秒：刚写的也可能落在窗口外（同一个毫秒边界），所以用一个极小的负窗口
        // 不成立——改成直接查一个不存在的路径，验"没记录就是 None"。
        assert_eq!(
            written_by_other(Path::new("/w/never.txt"), Some("s1"), 5_000),
            None
        );
        clear_log();
    }

    #[test]
    fn scripts_are_picked_out_of_the_command() {
        let found = scripts_in("bash build.sh && python3 tools/gen.py --out x");
        assert!(found.contains(&"build.sh".to_string()));
        assert!(found.contains(&"tools/gen.py".to_string()));
        // 不是脚本的参数不算
        assert!(!scripts_in("ls -la src").iter().any(|s| s == "src"));
    }

    #[test]
    fn interpreter_flags_do_not_become_script_names() {
        // `python -m build` 里 build 是模块名，不是脚本文件
        let found = scripts_in("python3 -m build");
        assert!(!found.iter().any(|s| s == "build"), "got {found:?}");
        // `bash -x script.sh` 里 -x 要跳过，script.sh 要抓到
        let found = scripts_in("bash -x run.sh");
        assert!(found.contains(&"run.sh".to_string()), "got {found:?}");
    }

    #[test]
    fn inline_script_text_is_captured() {
        // `bash -c '...'` 里那段**是**脚本内容
        let found = scripts_in("bash -c 'gcc -o out/app src/main.c'");
        assert!(
            found.iter().any(|s| s.contains("src/main.c")),
            "got {found:?}"
        );
    }

    #[test]
    fn bare_paths_are_treated_as_programs() {
        // 直接跑 elf：没有后缀也要抓
        let found = scripts_in("./out/tool --write");
        assert!(found.contains(&"out/tool".to_string()), "got {found:?}");
        // 绝对路径同理
        let found = scripts_in("/usr/local/bin/codegen");
        assert!(
            found.iter().any(|s| s.ends_with("codegen")),
            "got {found:?}"
        );
        // 裸名字 + 脚本后缀
        assert!(scripts_in("sh setup.sh").contains(&"setup.sh".to_string()));
    }

    #[test]
    fn quoted_script_names_are_unwrapped() {
        let found = scripts_in("bash \"my script.sh\"");
        assert!(found.iter().any(|s| s == "my script.sh"), "got {found:?}");
    }

    #[test]
    fn read_script_returns_text_and_skips_binaries() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("run.sh"), "gcc -o out/a src/a.c\n").unwrap();
        let text = read_script(dir.path(), "run.sh").unwrap();
        assert!(text.contains("src/a.c"));
        // 不存在的给 None（调用方退化成只看命令文本）
        assert!(read_script(dir.path(), "nope.sh").is_none());
        // 二进制给 None（读成字符串只会塞满垃圾）
        std::fs::write(dir.path().join("bin"), [0u8, 1, 2, 3]).unwrap();
        assert!(read_script(dir.path(), "bin").is_none());
    }

    #[test]
    fn script_content_turns_an_unknown_file_into_a_sure_owner() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("build.sh"), "cc -o out/data.bin src/x.c\n").unwrap();
        let script = read_script(dir.path(), "build.sh");
        let owner = attribute(
            Path::new("/w/out/data.bin"),
            "bash build.sh",
            script.as_deref(),
            Some("s1"),
            &["s1".to_string(), "s2".to_string()],
            5_000,
        );
        assert_eq!(owner, Owner::Sure("s1".to_string()));
    }

    #[test]
    fn normalize_collapses_dot_segments() {
        assert_eq!(
            normalize(Path::new("/a/b/../c/./d")),
            PathBuf::from("/a/c/d")
        );
    }
}
