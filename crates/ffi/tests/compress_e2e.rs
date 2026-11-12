//! 压缩的端到端：跑完一轮，**分界线必须真的在库里**。
//!
//! 为什么要在这一层写：`core` 有压缩的单测（草稿、覆盖校验、兜底），`storage` 有
//! `commit_compression` 的事务测试——两边都绿，中间那一跳（`nmoi_compress_run_json`
//! 拿到 outcome 之后把它交给谁）没人验过，于是分界线整整一个批次没落过库：
//! 归档进了表、会话一点没变、界面照样报"归档 N 条"。
//!
//! 判据只能是**另开一个连接从库里读**。返回值里那条线是内存里拼出来的，
//! 它和"落没落库"是两件事——这一条正好是 `pitfalls.md` 里"拼好了 ≠ 发出去了"的同一个形状。
//!
//! 这个假服务只活在测试二进制里（`tests/`），不进 APK、不进任何产物。

use std::ffi::{CStr, CString};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::str::FromStr;

use serde_json::{json, Value};

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::Storage;

/// 草稿正文。不含路径与命令之外的"受保护"信息，够过覆盖率就够。
const DRAFT: &str =
    "用户让我在 /workspace/a.txt 里写一个数字，写完又让我删掉；删掉这一步是他自己做的。";

/// 线上有几轮。
const TURNS: u64 = 10;

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
/// 只读到 `\r\n\r\n` 就回包、然后关连接，在 Windows 上会让还在写正文的客户端撞上 RST。
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

fn reply_json(stream: &mut TcpStream, body: &Value) {
    let body = body.to_string();
    let head = format!(
        "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\n\
         content-length: {}\r\nconnection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes()).expect("写响应头");
    stream.write_all(body.as_bytes()).expect("写响应体");
    stream.flush().expect("flush");
}

/// 一次工具调用的完整回包（`stream = false` 那条路，压缩走的就是它）。
fn tool_call(name: &str, arguments: Value) -> Value {
    json!({
        "id": "chatcmpl-compress",
        "object": "chat.completion",
        "model": "test-model",
        "choices": [{
            "index": 0,
            "finish_reason": "tool_calls",
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "id": format!("call_{name}"),
                    "type": "function",
                    "function": { "name": name, "arguments": arguments.to_string() },
                }],
            },
        }],
    })
}

/// 开一个库 + 一条会话，返回 `(handle, session)`。
fn open_session(dir: &std::path::Path) -> (u64, String) {
    let db = dir.join("compress.db");
    let db_text = CString::new(db.to_string_lossy().to_string()).unwrap();
    let opened = take(nmoi_ffi::storage::nmoi_storage_open_json(db_text.as_ptr()));
    let handle = opened["handle"].as_u64().expect("storage handle");
    let title = CString::new("压缩").unwrap();
    let assistant = CString::new("default").unwrap();
    let created = take(nmoi_ffi::storage::nmoi_storage_create_conversation_json(
        handle,
        title.as_ptr(),
        assistant.as_ptr(),
    ));
    let session = created["session"].as_str().expect("session").to_string();
    (handle, session)
}

/// 往线上塞几轮普通发言。走的是库自己的 `append_unit`，不经模型。
fn seed(db: &std::path::Path, session: SessionId) {
    let mut storage = Storage::attach(db).expect("开库");
    for index in 0..TURNS {
        // `seq` 从 1 开始（`MessageId` 的解析器拒收 0）。
        let seq = index + 1;
        let role = if index % 2 == 0 {
            Role::User
        } else {
            Role::Assistant
        };
        let message = Message::text(
            MessageId::new(session, seq),
            role,
            SourceTag::History,
            format!("第 {seq} 条发言，聊的是要不要改那一行。"),
            20,
            1_700_000_000_000 + seq as i64,
            LogicalClock::new(seq),
        );
        let unit = AtomicUnit::turn(message).expect("一轮一条消息");
        storage.append_unit(session, &unit).expect("落单元");
    }
}

#[test]
fn compress_lands_the_divider_and_keeps_the_originals() {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("compress.db");
    let (handle, session_text) = open_session(dir.path());
    let session = SessionId::from_str(&session_text).expect("会话 id");
    seed(&db, session);

    // 两趟往返：先追加草稿，再喊 done。
    let listener = TcpListener::bind("127.0.0.1:0").expect("起监听");
    let port = listener.local_addr().expect("取端口").port();
    let server = std::thread::spawn(move || {
        for step in 0..2 {
            let (mut stream, _) = listener.accept().expect("accept");
            read_request(&mut stream);
            let reply = if step == 0 {
                tool_call("compress_draft_append", json!({ "text": DRAFT }))
            } else {
                tool_call("compress_draft_done", json!({}))
            };
            reply_json(&mut stream, &reply);
        }
    });

    let config = json!({
        "storage": handle,
        "session": session_text,
        // 十几轮、每轮 20 token：窗口里只留 10 token，切点必然落在倒数第二、三条之前。
        "keep_recent": 10,
        "model": "test-model",
        "provider": {
            "id": "test",
            "kind": "openai_compatible",
            "base_url": format!("http://127.0.0.1:{port}/v1"),
        },
    })
    .to_string();
    let config = CString::new(config).unwrap();
    let answer = take(nmoi_ffi::compress::nmoi_compress_run_json(config.as_ptr()));
    server.join().expect("假服务线程");

    assert!(answer.get("error").is_none(), "压缩不该失败：{answer}");
    assert_eq!(
        answer["forced"],
        json!(false),
        "模型交出了草稿，不该走确定性兜底：{answer}"
    );
    assert!(
        answer["archived"].as_u64().unwrap_or(0) > 0,
        "线上应该有几条进归档：{answer}"
    );
    assert!(
        !answer["divider"].as_str().unwrap_or("").is_empty(),
        "返回值里要给分界线 id（界面/验收靠它对账）：{answer}"
    );

    // 关键的那一条：另开一个连接，从库里读。
    let storage = Storage::attach(&db).expect("重开库");
    let dividers = storage.load_dividers(session).expect("读分界线");
    assert_eq!(
        dividers.len(),
        1,
        "压缩跑完必须留下**一条**分界线（只写归档不画线 = 什么都没压）：{dividers:?}"
    );
    assert!(
        nmoi_core::compress::is_compression_divider(&dividers[0]),
        "这条线该是压缩画的：{dividers:?}"
    );

    let window = storage.load_window(session).expect("读窗口");
    assert!(
        window.is_compacted(),
        "分界线在库里，窗口就该是压过的——请求才是按窗口组装的"
    );
    assert!(
        window.summary.is_some(),
        "窗口开头要有摘要：分界线带 `summary_ref`，取不到就该报错而不是当没压过"
    );

    let units = storage.load_units(session).expect("读全量");
    assert_eq!(
        units.len() as u64,
        TURNS,
        "线上原文一条都不许删（压缩只画线，删线即还原）"
    );

    // 界面读的那一份（D324）：线上那几条要标出来，摘要要拿得到，线要能删。
    let session_c = CString::new(session.to_string()).unwrap();
    let loaded = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    assert_eq!(loaded["compacted"], json!(true), "压过就该报压过：{loaded}");
    let window_start = loaded["window_start"].as_u64().expect("window_start");
    assert!(
        window_start > 0 && window_start < TURNS,
        "窗口起点该落在中间：{loaded}"
    );
    assert!(
        loaded["summary"].as_str().unwrap_or("").contains("a.txt"),
        "摘要正文要能显示出来（铁律②：进了上下文的东西界面上要指得出）：{loaded}"
    );
    let divider_id = loaded["divider"]["id"]
        .as_str()
        .expect("divider id")
        .to_string();
    let items = loaded["items"].as_array().expect("items");
    assert_eq!(items.len() as u64, TURNS);
    assert_eq!(items[0]["compacted"], json!(true), "第一条在线上：{loaded}");
    assert_eq!(
        items[TURNS as usize - 1]["compacted"],
        json!(false),
        "最后一条在线下、仍进请求：{loaded}"
    );

    // 删线即还原：这是"压缩可撤销"的唯一入口。
    let divider_c = CString::new(divider_id).unwrap();
    let removed = take(nmoi_ffi::storage::nmoi_storage_delete_divider_json(
        handle,
        divider_c.as_ptr(),
    ));
    assert_eq!(removed["deleted"], json!(true), "{removed}");
    let after = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    assert_eq!(
        after["compacted"],
        json!(false),
        "线删了就该回到没压过：{after}"
    );
    assert!(after["divider"].is_null(), "{after}");
    assert!(
        !Storage::attach(&db)
            .expect("重开库")
            .load_window(session)
            .expect("读窗口")
            .is_compacted(),
        "删线之后请求要重新带上整段历史"
    );
}
