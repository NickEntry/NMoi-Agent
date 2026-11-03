//! 流式那一层的**端到端**（D239）。
//!
//! 为什么要在这一层写：`core` 里有 SSE 解析的测试，`ffi` 里有 partial 表的单元测试，
//! 但真机上出的两次事故都夹在两者之间——
//!
//! - 第二次发消息时屏幕上写着**上一条**的回答（`turn_busy` 在没登记时交了上一轮的残字）；
//! - 正文不是逐字长出来的（轮询在 `running:false` 那一下当场退出）。
//!
//! 这两条都不是"某个函数算错了"，而是**时序**：谁先谁后、中间那一瞬读到什么。
//! 所以这里搭一个"固定输出的模型服务"，用真的库、真的 FFI、真的 socket 走一遍，
//! 中途停下来看一眼 `turn_busy` 到底给了什么。
//!
//! 这个假服务只活在测试二进制里（`tests/`），不进 APK、不进任何产物。

use std::ffi::{CStr, CString};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};

/// 固定输出：三段，长度各不相同，中间态一眼能认出来。
const PIECES: [&str; 3] = ["第一段", "第二段", "第三段"];

fn take(pointer: *mut std::os::raw::c_char) -> Value {
    assert!(!pointer.is_null(), "JSON 函数不允许返回 null（D55）");
    let text = unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .expect("返回的 JSON 是 UTF-8")
        .to_string();
    unsafe { nmoi_ffi::nmoi_string_free(pointer) };
    serde_json::from_str(&text).unwrap_or_else(|error| panic!("不是 JSON：{text}（{error}）"))
}

/// 读完整条请求（头 + `content-length` 指定的正文）。
///
/// 只读到 `\r\n\r\n` 就回包、然后关连接，在 Windows 上会让还在写正文的客户端撞上 RST——
/// 那一条在 `turn.rs` 里红过一次，这里照抄同一份写法。
fn read_request(stream: &mut TcpStream) {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 1024];
    while !buffer.windows(4).any(|window| window == b"\r\n\r\n") {
        match stream.read(&mut chunk) {
            Ok(0) => return,
            Ok(read) => buffer.extend_from_slice(&chunk[..read]),
            Err(_) => return,
        }
    }
    let head_end = buffer
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|at| at + 4)
        .unwrap_or(buffer.len());
    let head = String::from_utf8_lossy(&buffer[..head_end]).to_ascii_lowercase();
    let length = head
        .lines()
        .find_map(|line| line.strip_prefix("content-length:"))
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(0);
    let mut body = buffer.len().saturating_sub(head_end);
    while body < length {
        match stream.read(&mut chunk) {
            Ok(0) => return,
            Ok(read) => body += read,
            Err(_) => return,
        }
    }
}

fn write_sse_head(stream: &mut TcpStream) {
    let head = "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\n\
                cache-control: no-cache\r\nconnection: close\r\n\r\n";
    stream.write_all(head.as_bytes()).expect("写响应头");
    stream.flush().expect("flush");
}

fn write_delta(stream: &mut TcpStream, text: &str) {
    let body = format!(
        "data: {}\n\n",
        json!({"choices": [{"index": 0, "delta": {"content": text}}]})
    );
    stream.write_all(body.as_bytes()).expect("写增量");
    stream.flush().expect("flush");
}

/// 开一个库 + 一条会话，返回 `(handle, session)`。
fn open_session(dir: &std::path::Path) -> (u64, String) {
    let db = dir.join("stream.db");
    let db_text = CString::new(db.to_string_lossy().to_string()).unwrap();
    let opened = take(nmoi_ffi::storage::nmoi_storage_open_json(db_text.as_ptr()));
    let handle = opened["handle"].as_u64().expect("storage handle");
    let title = CString::new("流式").unwrap();
    let assistant = CString::new("default").unwrap();
    let created = take(nmoi_ffi::storage::nmoi_storage_create_conversation_json(
        handle,
        title.as_ptr(),
        assistant.as_ptr(),
    ));
    let session = created["session"].as_str().expect("session").to_string();
    (handle, session)
}

fn busy(session: &str) -> Value {
    let session = CString::new(session).unwrap();
    take(nmoi_ffi::turn::nmoi_turn_busy_json(session.as_ptr()))
}

/// 一轮流式：**中途**必须能看到"只有第一段"的那个状态，收尾之后必须清干净。
///
/// 时序靠握手，不靠 sleep 猜：服务端发完第一段就举手，等测试看一眼再放行。
/// 靠 sleep 的话，慢 runner 上整轮可能在两次轮询之间就跑完了，断言就成了看运气。
#[test]
fn streaming_grows_while_it_runs_and_clears_after() {
    let gate: Arc<(Mutex<u8>, Condvar)> = Arc::new((Mutex::new(0), Condvar::new()));
    let server_gate = gate.clone();
    let listener = TcpListener::bind("127.0.0.1:0").expect("起监听");
    let port = listener.local_addr().expect("取端口").port();

    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept");
        read_request(&mut stream);
        write_sse_head(&mut stream);
        write_delta(&mut stream, PIECES[0]);

        // 第一段已经出去了：举手，然后**卡住**等测试放行。
        let (lock, ready) = &*server_gate;
        let mut step = lock.lock().expect("gate");
        *step = 1;
        ready.notify_all();
        while *step < 2 {
            step = ready.wait(step).expect("gate");
        }
        drop(step);

        write_delta(&mut stream, PIECES[1]);
        write_delta(&mut stream, PIECES[2]);
        let _ = stream.write_all(b"data: [DONE]\n\n");
        let _ = stream.flush();
    });

    let dir = tempfile::tempdir().unwrap();
    let (handle, session) = open_session(dir.path());

    let config = json!({
        "storage": handle,
        "session": session,
        "provider": {"kind": "openai_compatible",
                      "base_url": format!("http://127.0.0.1:{port}/v1")},
        "model": "test-model",
        "user_text": "说三段",
        "stream": true,
        "now_ms": 1_700_000_000_000i64,
    })
    .to_string();
    let config = CString::new(config).unwrap();
    let address = config.as_ptr() as usize;

    let runner =
        std::thread::spawn(move || take(nmoi_ffi::turn::nmoi_turn_run_json(address as *const _)));

    // 等第一段真的出去了（最多 10 秒，不看墙钟猜）。
    {
        let (lock, ready) = &*gate;
        let mut step = lock.lock().expect("gate");
        while *step < 1 {
            let (again, waited) = ready
                .wait_timeout(step, Duration::from_secs(10))
                .expect("gate");
            step = again;
            if waited.timed_out() && *step < 1 {
                panic!("10 秒内服务端没发出第一段：这一轮根本没连上来");
            }
        }
    }

    // 第一段已经在 native 的缓冲里了：这时候 `turn_busy` 必须**只**给这一段。
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut partial = None;
    while Instant::now() < deadline {
        let info = busy(&session);
        if info["text_len"].as_u64().unwrap_or(0) > 0 {
            partial = Some(info);
            break;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let partial = partial.expect("10 秒内没看到任何增量：流式没在长");
    let full: usize = PIECES.iter().map(|piece| piece.chars().count()).sum();
    let partial_len = partial["text_len"].as_u64().expect("text_len") as usize;
    assert!(
        partial["running"].as_bool().unwrap_or(false),
        "这时候这一轮还在跑：{partial}"
    );
    assert_eq!(
        PIECES[0].chars().count(),
        partial_len,
        "中间态应当只有第一段：{partial}"
    );
    assert!(partial_len < full, "中间态不该已经是全文：{partial}");
    assert_eq!(PIECES[0], partial["text"].as_str().unwrap_or(""));

    // 放行，让剩下的两段 + [DONE] 过去。
    {
        let (lock, ready) = &*gate;
        let mut step = lock.lock().expect("gate");
        *step = 2;
        ready.notify_all();
    }

    let outcome = runner.join().expect("turn 线程没 panic");
    assert!(outcome["ok"].as_bool().unwrap_or(false), "{outcome}");
    assert_eq!(
        PIECES.concat(),
        outcome["report"]["text"].as_str().unwrap_or(""),
        "落库正文应当等于固定输出"
    );

    // 收尾之后：`turn_busy` 一律给空（D236）。给残字的话，下一条消息会先闪一下这一条。
    let after = busy(&session);
    assert!(!after["running"].as_bool().unwrap_or(true), "{after}");
    assert_eq!(0, after["text_len"].as_u64().unwrap_or(1), "{after}");
    assert_eq!(0, after["round"].as_u64().unwrap_or(1), "{after}");
    assert_eq!("", after["text"].as_str().unwrap_or("x"));

    server.join().expect("服务端线程没 panic");
}

/// 没有一轮在跑时，`turn_busy` 不许交上一轮的残字（D236 的直接回归）。
///
/// 这一条不需要服务端：它钉的是"没有这个实体时该给什么"。
#[test]
fn busy_without_a_turn_is_empty() {
    let dir = tempfile::tempdir().unwrap();
    let (_handle, session) = open_session(dir.path());
    let info = busy(&session);
    assert!(info["ok"].as_bool().unwrap_or(false), "{info}");
    assert!(!info["running"].as_bool().unwrap_or(true), "{info}");
    assert_eq!(0, info["text_len"].as_u64().unwrap_or(1), "{info}");
    assert_eq!(0, info["reasoning_len"].as_u64().unwrap_or(1), "{info}");
    assert_eq!(0, info["round"].as_u64().unwrap_or(1), "{info}");
    assert_eq!("", info["text"].as_str().unwrap_or("x"));
}

/// 往会话里存一条消息，返回它的 id（`<session>:<seq>`）。
fn append_message(handle: u64, session: &str, role: &str, text: &str) -> String {
    let session_c = CString::new(session).unwrap();
    let role_c = CString::new(role).unwrap();
    let text_c = CString::new(text).unwrap();
    let appended = take(nmoi_ffi::storage::nmoi_storage_append_text_json(
        handle,
        session_c.as_ptr(),
        role_c.as_ptr(),
        text_c.as_ptr(),
        1_700_000_000_000,
    ));
    assert!(appended["ok"].as_bool().unwrap_or(false), "{appended}");
    format!("{}:{}", session, appended["seq"].as_u64().expect("seq"))
}

/// 现读库里那条消息的正文（**铁律①**：判据一律现读，不用手里的快照）。
fn message_text(handle: u64, session: &str, id: &str) -> String {
    let session_c = CString::new(session).unwrap();
    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    let items = units["items"].as_array().expect("items");
    for unit in items {
        for message in unit["messages"].as_array().into_iter().flatten() {
            if message["id"].as_str() == Some(id) {
                return message["content"].as_str().unwrap_or("").to_string();
            }
        }
    }
    panic!("库里找不到 {id}：{units}");
}

/// 续话（V55 的 native 那一半）：缓冲里**先垫上原正文**，落库是「原正文 + 续文」。
///
/// 这一条钉的是"界面不必在 Kotlin 里再实现一遍拼接规则"：屏幕上从头到尾是全文，
/// 而库里那份与流里那份用的是同一个 `join_continuation`。
#[test]
fn continuation_seeds_the_original_text_and_lands_joined() {
    const ORIGINAL: &str = "原来的回答";
    const NEXT: &str = "接着写";

    let gate: Arc<(Mutex<u8>, Condvar)> = Arc::new((Mutex::new(0), Condvar::new()));
    let server_gate = gate.clone();
    let listener = TcpListener::bind("127.0.0.1:0").expect("起监听");
    let port = listener.local_addr().expect("取端口").port();

    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept");
        read_request(&mut stream);
        write_sse_head(&mut stream);
        write_delta(&mut stream, NEXT);

        let (lock, ready) = &*server_gate;
        let mut step = lock.lock().expect("gate");
        *step = 1;
        ready.notify_all();
        while *step < 2 {
            step = ready.wait(step).expect("gate");
        }
        drop(step);

        let _ = stream.write_all(b"data: [DONE]\n\n");
        let _ = stream.flush();
    });

    let dir = tempfile::tempdir().unwrap();
    let (handle, session) = open_session(dir.path());
    let _user = append_message(handle, &session, "user", "开头");
    let target = append_message(handle, &session, "assistant", ORIGINAL);

    let config = json!({
        "storage": handle,
        "session": session,
        "provider": {"kind": "openai_compatible",
                      "base_url": format!("http://127.0.0.1:{port}/v1")},
        "model": "test-model",
        "up_to": target,
        "target": target,
        "append": true,
        "stream": true,
        "now_ms": 1_700_000_000_000i64,
    })
    .to_string();
    let config = CString::new(config).unwrap();
    let address = config.as_ptr() as usize;
    let runner =
        std::thread::spawn(move || take(nmoi_ffi::turn::nmoi_turn_regen_json(address as *const _)));

    {
        let (lock, ready) = &*gate;
        let mut step = lock.lock().expect("gate");
        while *step < 1 {
            let (again, waited) = ready
                .wait_timeout(step, Duration::from_secs(10))
                .expect("gate");
            step = again;
            if waited.timed_out() && *step < 1 {
                panic!("10 秒内没连上来：续话这一轮根本没发出去");
            }
        }
    }

    // 续文已经出去了：缓冲里必须是「原正文 + 续文」，不是只有续文那一截。
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut partial = None;
    while Instant::now() < deadline {
        let info = busy(&session);
        if info["text_len"].as_u64().unwrap_or(0) > 0 {
            partial = Some(info);
            break;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let partial = partial.expect("10 秒内没看到增量");
    let live_text = partial["text"].as_str().unwrap_or("");
    assert!(
        live_text.starts_with(ORIGINAL),
        "屏幕上那份应当从原正文开始（垫过 prefix），实际：{live_text:?}"
    );
    assert!(
        live_text.ends_with(NEXT),
        "续文应当接在原正文后面，实际：{live_text:?}"
    );

    {
        let (lock, ready) = &*gate;
        let mut step = lock.lock().expect("gate");
        *step = 2;
        ready.notify_all();
    }

    let outcome = runner.join().expect("regen 线程没 panic");
    assert!(outcome["ok"].as_bool().unwrap_or(false), "{outcome}");
    // 出参里 `text` 是**模型这一次给的续文**，拼接发生在落库那一步。
    assert_eq!(NEXT, outcome["text"].as_str().unwrap_or(""), "{outcome}");
    assert_eq!(
        target,
        outcome["target"].as_str().unwrap_or(""),
        "{outcome}"
    );
    // 中文接中文不加空格（`join_continuation`）：库里那份与流里那份是同一条规则。
    assert_eq!(
        format!("{ORIGINAL}{NEXT}"),
        message_text(handle, &session, &target),
        "库里那条消息应当是「原正文 + 续文」"
    );
    // 接之前那一版要留着（切回 `<1/2>` 能看见接之前长什么样）。
    let target_c = CString::new(target.clone()).unwrap();
    let versions = take(nmoi_ffi::storage::nmoi_storage_versions_json(
        handle,
        target_c.as_ptr(),
    ));
    assert_eq!(2, versions["count"].as_u64().unwrap_or(0), "{versions}");
    assert_eq!(
        format!("{ORIGINAL}{NEXT}"),
        versions["shown"].as_str().unwrap_or(""),
        "{versions}"
    );
    // 切回第 1 版：看见的是"接之前"那一份（续话不毁掉前一段，D193）。
    let view = CString::new("raw").unwrap();
    let switched = take(nmoi_ffi::storage::nmoi_storage_switch_version_json(
        handle,
        target_c.as_ptr(),
        1,
        view.as_ptr(),
    ));
    assert!(switched["ok"].as_bool().unwrap_or(false), "{switched}");
    assert_eq!(ORIGINAL, message_text(handle, &session, &target));
    // 再切回第 2 版：接完的那一份还在。
    let back = take(nmoi_ffi::storage::nmoi_storage_switch_version_json(
        handle,
        target_c.as_ptr(),
        2,
        view.as_ptr(),
    ));
    assert!(back["ok"].as_bool().unwrap_or(false), "{back}");
    assert_eq!(
        format!("{ORIGINAL}{NEXT}"),
        message_text(handle, &session, &target)
    );
    server.join().expect("服务端线程没 panic");
}
