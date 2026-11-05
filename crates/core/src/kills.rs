//! 工具调用的"手动关闭"登记表（0.15ak / D246）。
//!
//! 为什么是一张进程内的表，而不是挂在某一轮的对象上：点「关闭」的那一下来自界面，
//! 和正在跑工具的那条线程没有共同的父作用域。两边都够得着的东西只有 `(session, call_id)`，
//! 所以按这两个键登记。
//!
//! 三条规矩：
//!
//! 1. **登记用 Drop 收尾**。工具结果一回来，那条调用就不该再出现在"正在跑"里；
//!    靠人手 `remove` 迟早漏一处，而漏了的表现是界面上永远有一条关不掉的灰行。
//! 2. **没在跑时 `cancel` 返回 `false`，不是错误**。用户点晚了一拍不该看到红字。
//! 3. **"被用户关闭"与"超时"是两件事**。这里只置标志，怎么杀、结果怎么写，
//!    由 [`crate::shell`] 那边的等待循环决定——它同时管着超时，两件事在一个地方才分得清。

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

/// 一条正在跑的工具调用（给界面画"哪一条在跑、跑了多久"）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunningCall {
    pub call_id: String,
    pub name: String,
    /// 这次调用的参数原文（截断过）。弹窗里要能看见"它到底在跑什么"——
    /// 一行名字加一个秒数，用户没法判断该不该关掉它。
    pub arguments: String,
    pub elapsed_ms: u64,
}

struct Entry {
    flag: Arc<AtomicBool>,
    name: String,
    arguments: String,
    started: Instant,
}

type Table = HashMap<String, HashMap<String, Entry>>;

fn table() -> &'static Mutex<Table> {
    static TABLE: OnceLock<Mutex<Table>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 一次工具调用的登记凭据：`Drop` 时把自己从表里摘掉。
#[derive(Debug)]
pub struct CallHandle {
    session: String,
    call_id: String,
    flag: Arc<AtomicBool>,
}

impl CallHandle {
    /// 用户点过「关闭」没有。
    pub fn cancelled(&self) -> bool {
        self.flag.load(Ordering::SeqCst)
    }
}

impl Drop for CallHandle {
    fn drop(&mut self) {
        let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
        let Some(calls) = table.get_mut(&self.session) else {
            return;
        };
        // 只摘自己那一条：同一个 call_id 被重新登记过（模型重发同一个 id）时，
        // 老的 Drop 不该把新的那条一起带走。
        let mine = calls
            .get(&self.call_id)
            .map(|entry| Arc::ptr_eq(&entry.flag, &self.flag))
            .unwrap_or(false);
        if mine {
            calls.remove(&self.call_id);
        }
        if calls.is_empty() {
            table.remove(&self.session);
        }
    }
}

/// 参数最多留这么多字符。界面每 100 ms 问一次 `turn_busy`，一条几 KB 的命令
/// 乘上轮询频率就是白烧带宽；弹窗里看得清前 2 000 个字就够了。
const ARGUMENTS_CAP: usize = 2_000;

/// 登记一条调用开始跑。返回的凭据活到结果回来为止。
pub fn begin(session: &str, call_id: &str, name: &str, arguments: &str) -> CallHandle {
    let flag = Arc::new(AtomicBool::new(false));
    let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    table.entry(session.to_string()).or_default().insert(
        call_id.to_string(),
        Entry {
            flag: Arc::clone(&flag),
            name: name.to_string(),
            arguments: arguments.chars().take(ARGUMENTS_CAP).collect(),
            started: Instant::now(),
        },
    );
    CallHandle {
        session: session.to_string(),
        call_id: call_id.to_string(),
        flag,
    }
}

/// 置上关闭标志。找到返回 `true`；没在跑返回 `false`（**不是错误**）。
pub fn cancel(session: &str, call_id: &str) -> bool {
    let table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    match table.get(session).and_then(|calls| calls.get(call_id)) {
        Some(entry) => {
            entry.flag.store(true, Ordering::SeqCst);
            true
        }
        None => false,
    }
}

/// 把这个会话里所有在跑的调用一起置上（整轮停止时用）。返回置上了几条。
///
/// 为什么整轮停止要管它们：只停模型不停命令的话，一条 `"inf"` 的命令会在用户按了停止
/// 之后继续跑下去——屏幕上那一行还写着"正在跑"，而它确实还在跑。
pub fn cancel_all(session: &str) -> usize {
    let table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    let Some(calls) = table.get(session) else {
        return 0;
    };
    for entry in calls.values() {
        entry.flag.store(true, Ordering::SeqCst);
    }
    calls.len()
}

/// 工具结果的一句话摘要：`exit 0 · 121ms`。
///
/// 只认两种形状：shell 的 `{exit_code, duration_ms}`，以及别的工具（没有这两个字段时返回空）。
/// 解析失败就返回空——编一句"成功"比不写更糟。
pub fn summarize(outcome_text: &str) -> String {
    let trimmed = outcome_text.trim();
    if !trimmed.starts_with('{') {
        return String::new();
    }
    let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
        return String::new();
    };
    let mut parts = Vec::new();
    match value.get("exit_code") {
        Some(serde_json::Value::Number(number)) => {
            parts.push(format!("exit {number}"));
        }
        Some(serde_json::Value::Null) => parts.push("被信号结束".to_string()),
        _ => {}
    }
    if let Some(ms) = value.get("duration_ms").and_then(|v| v.as_u64()) {
        parts.push(format!("{ms}ms"));
    }
    if value.get("timed_out") == Some(&serde_json::Value::Bool(true)) {
        parts.push("超时".to_string());
    }
    if value.get("killed_by_user") == Some(&serde_json::Value::Bool(true)) {
        parts.push("被用户关闭".to_string());
    }
    parts.join(" · ")
}

/// 这一轮**已经跑完**的调用（D251）：生成期间界面上要能看见"它跑过什么"。
///
/// 为什么单独一张表：`CallHandle` 的 Drop 一发生，那条调用就从"在跑"里消失了——
/// 而它跑出来的东西要等整轮收尾才落库。中间这一段，屏幕上会一片空白，
/// 用户只看到模型在思考，以为工具没在动（2026-11-04 真机就是这么报的）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DoneCall {
    pub call_id: String,
    pub name: String,
    /// 参数原文（截断过）。跑完的这一行也要能点开看"它到底做了什么"。
    pub arguments: String,
    /// 结果原文（截断过）。整轮收尾前，这里是唯一能看的地方。
    pub output: String,
    pub ok: bool,
    /// 一眼能认出来的摘要：`exit 0 · 121ms` / `失败` / 空。
    pub summary: String,
}

fn done_table() -> &'static Mutex<HashMap<String, Vec<DoneCall>>> {
    static TABLE: OnceLock<Mutex<HashMap<String, Vec<DoneCall>>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 结果原文最多留这么多字符。它只活到整轮收尾（之后库里那份才是准的），
/// 但一轮里几十条调用乘上大输出会一直占着内存，给个上限。
const OUTPUT_CAP: usize = 64 * 1024;

/// 记一条跑完的调用（按发生顺序追加）。
pub fn push_done(
    session: &str,
    call_id: &str,
    name: &str,
    arguments: &str,
    output: &str,
    ok: bool,
) {
    let mut table = done_table()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    let mut text: String = output.chars().take(OUTPUT_CAP).collect();
    if output.chars().count() > OUTPUT_CAP {
        text.push_str("\n…（截断：完整输出在整轮收尾后落库）");
    }
    table
        .entry(session.to_string())
        .or_default()
        .push(DoneCall {
            call_id: call_id.to_string(),
            name: name.to_string(),
            arguments: arguments.chars().take(ARGUMENTS_CAP).collect(),
            output: text,
            ok,
            summary: summarize(output),
        });
}

/// 这一轮跑完的那些，按发生顺序。
pub fn done(session: &str) -> Vec<DoneCall> {
    let table = done_table()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    table.get(session).cloned().unwrap_or_default()
}

/// 开新一轮时清空（这一轮的"跑过什么"只属于这一轮）。
pub fn clear_done(session: &str) {
    let mut table = done_table()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    table.remove(session);
}

/// 这个会话现在有哪些调用在跑，按开始的先后排。
pub fn running(session: &str) -> Vec<RunningCall> {
    let table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    let Some(calls) = table.get(session) else {
        return Vec::new();
    };
    let mut rows: Vec<(Instant, RunningCall)> = calls
        .iter()
        .map(|(call_id, entry)| {
            (
                entry.started,
                RunningCall {
                    call_id: call_id.clone(),
                    name: entry.name.clone(),
                    arguments: entry.arguments.clone(),
                    elapsed_ms: entry.started.elapsed().as_millis() as u64,
                },
            )
        })
        .collect();
    rows.sort_by_key(|(started, _)| *started);
    rows.into_iter().map(|(_, row)| row).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 每个用例用自己的会话名：这张表是进程级的，并行的用例会互相看见。
    #[test]
    fn a_registered_call_shows_up_and_disappears_when_it_returns() {
        let session = "s-register";
        let handle = begin(session, "call_1", "read_file", "{\"path\":\"a.txt\"}");
        let rows = running(session);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].call_id, "call_1");
        assert_eq!(rows[0].name, "read_file");
        assert!(!handle.cancelled());

        drop(handle);
        assert!(running(session).is_empty(), "结果回来就不该再算在跑");
    }

    #[test]
    fn cancelling_sets_the_flag_and_an_unknown_id_is_not_an_error() {
        let session = "s-cancel";
        let handle = begin(session, "call_1", "System-Shell", "");
        assert!(cancel(session, "call_1"));
        assert!(handle.cancelled());
        // 再点一次：还在跑，照样 true（幂等）。界面上那颗按钮不会因为点过就变成错误。
        assert!(cancel(session, "call_1"));
        drop(handle);
        // 结果回来之后才轮到"没在跑"：false，不是错误。
        assert!(!cancel(session, "call_1"));
    }

    #[test]
    fn two_sessions_do_not_see_each_other() {
        let a = begin("s-a", "call_1", "read_file", "");
        let b = begin("s-b", "call_1", "read_file", "");
        assert_eq!(running("s-a").len(), 1);
        assert!(cancel("s-a", "call_1"));
        assert!(a.cancelled());
        assert!(!b.cancelled(), "关了一个会话的调用，另一个会话的不该跟着停");
        drop(a);
        drop(b);
        assert!(running("s-a").is_empty());
        assert!(running("s-b").is_empty());
    }

    #[test]
    fn re_registering_the_same_id_does_not_let_the_old_guard_remove_the_new_one() {
        let session = "s-reuse";
        let old = begin(session, "call_1", "read_file", "{\"path\":\"a.txt\"}");
        let fresh = begin(session, "call_1", "read_file", "{\"path\":\"a.txt\"}");
        drop(old);
        assert_eq!(running(session).len(), 1, "新的那条还在跑");
        assert!(cancel(session, "call_1"));
        assert!(fresh.cancelled());
        drop(fresh);
        assert!(running(session).is_empty());
    }

    /// 整轮停止：这个会话里在跑的调用全置上，别的会话一条都不动。
    #[test]
    fn cancel_all_stops_every_call_of_one_session_only() {
        let a1 = begin("s-all", "call_1", "System-Shell", "");
        let a2 = begin("s-all", "call_2", "WorkSpace-EnvirShell", "");
        let other = begin("s-other", "call_1", "System-Shell", "");
        assert_eq!(cancel_all("s-all"), 2);
        assert!(a1.cancelled());
        assert!(a2.cancelled());
        assert!(!other.cancelled(), "别的会话不该跟着停");
        assert_eq!(cancel_all("s-nobody"), 0);
        drop((a1, a2, other));
    }
}
