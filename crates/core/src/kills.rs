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
    /// 到目前为止吐出来的东西（stdout / stderr 按到达顺序合在一起）。
    ///
    /// 只留尾部（[`LIVE_CAP`]）：长任务里用户要看的是"现在在吐什么"，
    /// 不是十分钟前的第一行。`output_cut` 说明前面被裁过。
    pub output: String,
    pub output_cut: bool,
}

/// 一条调用到现在为止的输出。两个读线程共用一份（谁先读到谁写进去）。
#[derive(Debug, Default)]
struct Live {
    text: String,
    cut: bool,
}

/// 实时输出最多留这么多**字节**（尾部）。它每 100 ms 被读一次给界面，
/// 留太多等于每拍搬一次几十 KB；弹窗里看最近几屏就够。
const LIVE_CAP: usize = 8 * 1024;

/// 写实时输出的把手：`CallHandle::live()` 拿一份，交给读管道的线程。
#[derive(Debug, Clone)]
pub struct LiveBuffer {
    live: Arc<Mutex<Live>>,
}

impl LiveBuffer {
    /// 现在攒到哪儿了（`(文本, 前面裁过没有)`）。跑完之后再看一眼也用它。
    pub fn snapshot(&self) -> (String, bool) {
        let live = self
            .live
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        (live.text.clone(), live.cut)
    }

    /// 追加一段。超上限就**从头部裁**（留尾部）——见 [`LIVE_CAP`]。
    pub fn push(&self, chunk: &str) {
        if chunk.is_empty() {
            return;
        }
        let mut live = self
            .live
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        live.text.push_str(chunk);
        if live.text.len() > LIVE_CAP {
            let mut cut = live.text.len() - LIVE_CAP;
            while !live.text.is_char_boundary(cut) {
                cut += 1;
            }
            live.text.drain(..cut);
            live.cut = true;
        }
    }
}

struct Entry {
    flag: Arc<AtomicBool>,
    name: String,
    arguments: String,
    started: Instant,
    live: Arc<Mutex<Live>>,
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
    live: Arc<Mutex<Live>>,
}

impl CallHandle {
    /// 用户点过「关闭」没有。
    pub fn cancelled(&self) -> bool {
        self.flag.load(Ordering::SeqCst)
    }

    /// 实时输出的把手。读管道的线程拿它往里写，界面每 100 ms 读一次。
    pub fn live(&self) -> LiveBuffer {
        LiveBuffer {
            live: Arc::clone(&self.live),
        }
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
    let live = Arc::new(Mutex::new(Live::default()));
    let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    table.entry(session.to_string()).or_default().insert(
        call_id.to_string(),
        Entry {
            flag: Arc::clone(&flag),
            name: name.to_string(),
            arguments: arguments.chars().take(ARGUMENTS_CAP).collect(),
            started: Instant::now(),
            live: Arc::clone(&live),
        },
    );
    CallHandle {
        session: session.to_string(),
        call_id: call_id.to_string(),
        flag,
        live,
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
                    output: {
                        let live = entry
                            .live
                            .lock()
                            .unwrap_or_else(|poison| poison.into_inner());
                        live.text.clone()
                    },
                    output_cut: entry
                        .live
                        .lock()
                        .unwrap_or_else(|poison| poison.into_inner())
                        .cut,
                },
            )
        })
        .collect();
    rows.sort_by_key(|(started, _)| *started);
    rows.into_iter().map(|(_, row)| row).collect()
}

#[cfg(test)]
mod tests {
    /// 实时输出只留尾部：长任务里用户要看的是"现在在吐什么"，不是十分钟前的第一行。
    #[test]
    fn the_live_buffer_keeps_the_tail_and_says_it_was_cut() {
        let handle = begin("s", "c", "System-Shell", "{}");
        let live = handle.live();
        live.push("111\n");
        assert_eq!(live.snapshot(), ("111\n".to_string(), false));
        live.push(&"长".repeat(LIVE_CAP));
        let (text, cut) = live.snapshot();
        assert!(cut, "裁过就要说裁过");
        assert!(
            text.len() <= LIVE_CAP,
            "留在缓冲里的不能超过上限：{}",
            text.len()
        );
        assert!(text.ends_with('长'), "留下的是尾部：{text:?}");
        // 裁的位置必须落在字符边界上，否则界面上会闪一个乱码。
        assert!(text.chars().all(|c| c == '长'), "裁出了半个字符：{text:?}");
    }

    /// 一条调用登记之后，`running()` 里要带着它的实时输出。
    #[test]
    fn running_calls_carry_their_live_output() {
        let handle = begin("s2", "c2", "System-Shell", "{}");
        handle.live().push("hello");
        let rows = running("s2");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].output, "hello");
        assert!(!rows[0].output_cut);
        drop(handle);
        assert!(running("s2").is_empty(), "结果回来之后就不该还在跑");
    }

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
