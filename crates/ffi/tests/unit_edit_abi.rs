//! 单元级编辑与 undo 日志的 C ABI 端到端测试（0.15f / D188）。
//!
//! 这一层要钉的**不是 SQL**（storage 那边已经有 6 条），是信封：
//! `spec` 的形状、`ops` 的解析、返回的字段名。界面上"点了保存什么都没变"
//! 十次有九次是这里对不上，而不是库坏了。
//!
//! 走一遍 UI 真实的调用顺序：open → create_conversation → append_text
//! → apply_unit_edits（改正文 + 加思考）→ load_units（看到改后）→ undo_log（读到原文）。

use std::ffi::{CStr, CString};

use serde_json::{json, Value};

fn take(pointer: *mut std::os::raw::c_char) -> Value {
    assert!(!pointer.is_null(), "JSON 函数不允许返回 null（D55）");
    let text = unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .expect("返回的 JSON 是 UTF-8")
        .to_string();
    unsafe { nmoi_ffi::nmoi_string_free(pointer) };
    serde_json::from_str(&text).expect("返回值必须是合法 JSON")
}

fn c(text: &str) -> CString {
    CString::new(text).expect("测试文本不含 NUL")
}

/// open + 建会话 + 落一条助手消息，返回 `(handle, session, message)`。
fn seed() -> (u64, String, String, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let path_c = c(path.to_str().unwrap());
    let opened = take(nmoi_ffi::storage::nmoi_storage_open_json(path_c.as_ptr()));
    assert_eq!(opened["state"], "ready", "库没过校验：{opened}");
    let handle = opened["handle"].as_u64().unwrap();

    let title = c("单元编辑 ABI");
    let assistant = c("default");
    let created = take(nmoi_ffi::storage::nmoi_storage_create_conversation_json(
        handle,
        title.as_ptr(),
        assistant.as_ptr(),
    ));
    assert_eq!(created["ok"], Value::Bool(true), "{created}");
    let session = created["session"].as_str().unwrap().to_string();

    let session_c = c(&session);
    let role = c("assistant");
    let body = c("原来的正文");
    let appended = take(nmoi_ffi::storage::nmoi_storage_append_text_json(
        handle,
        session_c.as_ptr(),
        role.as_ptr(),
        body.as_ptr(),
        1_700_000_000_000,
    ));
    assert_eq!(appended["ok"], Value::Bool(true), "{appended}");
    // `append_text` 只回 seq：消息 id 是 `<session>:<seq>`（MessageId 的 Display）。
    let seq = appended["seq"].as_u64().unwrap();
    let message = format!("{session}:{seq}");
    (handle, session, message, dir)
}

#[test]
fn unit_edit_lands_red_and_shows_up_in_the_undo_log() {
    let (handle, session, message, _dir) = seed();
    let session_c = c(&session);

    let spec = c(&json!({
        "message_id": message,
        "ops": [
            {"action": "set", "block": "output", "content": "改后的正文"},
            {"action": "set", "block": "thinking", "content": "补上的思考"},
        ]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(true), "{applied}");
    assert_eq!(applied["written"], json!(2), "{applied}");
    assert_eq!(applied["removed"], json!(0), "{applied}");

    // 铁律 ①：改完以库里那份为准。
    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    let first = &units["items"][0]["messages"][0];
    assert_eq!(first["content"], "改后的正文", "{first}");
    assert_eq!(first["reasoning"], "补上的思考", "{first}");

    // 原文还在 undo 日志里（界面上的「导出」要拿它）。
    let log = take(nmoi_ffi::storage::nmoi_storage_undo_log_json(
        handle,
        session_c.as_ptr(),
    ));
    assert_eq!(log["count"], json!(2), "{log}");
    let entries = log["entries"].as_array().unwrap();
    assert!(
        entries
            .iter()
            .any(|entry| entry["before"] == "原来的正文" && entry["after"] == "改后的正文"),
        "正文那条要带原文：{log}"
    );

    let closed = take(nmoi_ffi::storage::nmoi_storage_close_json(handle));
    assert_eq!(closed["ok"], Value::Bool(true), "{closed}");
}

#[test]
fn unit_edit_refuses_a_block_that_is_not_there_through_the_envelope() {
    let (handle, session, message, _dir) = seed();
    let session_c = c(&session);

    // 这条消息没有工具调用：删第 0 个要被拒，而且错误要说清是工具块。
    let spec = c(&json!({
        "message_id": message,
        "ops": [{"action": "delete", "block": "tool", "index": 0}],
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(false), "{applied}");
    assert!(
        applied["error"]["message"]
            .as_str()
            .unwrap()
            .contains("工具"),
        "报错要点名是工具块：{applied}"
    );

    // 一条都没写。
    let log = take(nmoi_ffi::storage::nmoi_storage_undo_log_json(
        handle,
        session_c.as_ptr(),
    ));
    assert_eq!(log["count"], json!(0), "{log}");

    take(nmoi_ffi::storage::nmoi_storage_close_json(handle));
}

/// `{"edits":[…]}` 这条形状（2026-11-26）：一次改**两条消息**，一个信封。
///
/// 界面上「编辑整条消息」走的就是它。这一条钉的是信封：`edits` 收进来、
/// `messages` 回出去、两条都真的改了。形状对不上的话，界面上"点了保存什么都没变"。
#[test]
fn turn_edit_shape_touches_two_messages_at_once() {
    let (handle, session, first, _dir) = seed();
    let session_c = c(&session);
    let role = c("assistant");
    let body = c("第二条的正文");
    let appended = take(nmoi_ffi::storage::nmoi_storage_append_text_json(
        handle,
        session_c.as_ptr(),
        role.as_ptr(),
        body.as_ptr(),
        1_700_000_000_001,
    ));
    let second = format!("{session}:{}", appended["seq"].as_u64().unwrap());

    let spec = c(&json!({
        "edits": [
            { "message_id": first, "ops": [
                { "action": "set", "block": "output", "content": "第一条改过" }
            ]},
            { "message_id": second, "ops": [
                { "action": "set", "block": "output", "content": "第二条改过" }
            ]}
        ]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(true), "{applied}");
    assert_eq!(applied["written"], json!(2), "{applied}");
    let messages: Vec<String> = applied["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(|value| value.as_str().unwrap().to_string())
        .collect();
    assert_eq!(messages, vec![first.clone(), second.clone()], "{applied}");

    // 库里两条都变了（不是"回了 ok 但只写了一条"）。
    let limit = 50;
    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        limit,
    ));
    let text = units.to_string();
    assert!(text.contains("第一条改过"), "{text}");
    assert!(text.contains("第二条改过"), "{text}");
}

/// `edits` 里有一条解析不出来：整批拒，**一条都不写**。
#[test]
fn turn_edit_refuses_the_whole_batch_when_one_message_is_bad() {
    let (handle, _session, first, _dir) = seed();
    let spec = c(&json!({
        "edits": [
            { "message_id": first, "ops": [
                { "action": "set", "block": "output", "content": "本来能改" }
            ]},
            { "message_id": "不是消息 id", "ops": [
                { "action": "set", "block": "output", "content": "x" }
            ]}
        ]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(false), "{applied}");
    // 信封的形状是 `{"error":{"kind","message"}}`（D55）；坏 id 由 `parse_message` 判成 `id`。
    assert_eq!(applied["error"]["kind"], json!("id"), "{applied}");
    assert!(
        applied["error"]["message"]
            .as_str()
            .unwrap_or("")
            .contains("不是消息 id"),
        "{applied}"
    );
}

/// 同一条消息在 `edits` 里出现两遍：拒，并说清是哪一组。
#[test]
fn turn_edit_refuses_the_same_message_twice() {
    let (handle, _session, first, _dir) = seed();
    let spec = c(&json!({
        "edits": [
            { "message_id": first, "ops": [
                { "action": "set", "block": "output", "content": "一" }
            ]},
            { "message_id": first, "ops": [
                { "action": "set", "block": "output", "content": "二" }
            ]}
        ]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(false), "{applied}");
    assert!(
        applied["error"]["message"]
            .as_str()
            .unwrap_or("")
            .contains("写了一遍"),
        "{applied}"
    );
}

/// `tools` 那一栏（2026-11-26）：工具块可以**增删改名**。
///
/// 这一条钉的是信封：`tools` 收进来、`ops` 可以缺席、写完库真的有两条。
#[test]
fn the_tool_list_can_be_replaced_through_the_envelope() {
    let (handle, session, message, _dir) = seed();
    let session_c = c(&session);
    // 先给它一条工具块（库里原来只有正文）。
    let first = c(&json!({
        "message_id": message,
        "tools": [{ "name": "System-Shell", "arguments": "{\"command\":\"ls\"}", "output": "a.txt" }]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        first.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(true), "{applied}");
    assert_eq!(applied["written"], json!(1), "{applied}");

    // 再换成两条（加一条），`ops` 这一栏整个不给。
    let second = c(&json!({
        "message_id": message,
        "tools": [
            { "name": "System-Shell", "arguments": "{\"command\":\"ls\"}", "output": "a.txt" },
            { "name": "Read-File", "arguments": "{\"path\":\"b.txt\"}", "output": "内容" }
        ]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        second.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(true), "{applied}");
    assert_eq!(applied["written"], json!(1), "新增算一处：{applied}");

    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        50,
    ));
    let text = units.to_string();
    assert!(text.contains("Read-File"), "{text}");
    assert!(text.contains("a.txt"), "第一条还在：{text}");
}

/// 参数不是 JSON 对象：当场拒，库不动（它会原样发给上游，坏了下一次请求 400）。
#[test]
fn a_tool_with_broken_arguments_is_refused() {
    let (handle, _session, message, _dir) = seed();
    let spec = c(&json!({
        "message_id": message,
        "tools": [{ "name": "System-Shell", "arguments": "不是 json", "output": "" }]
    })
    .to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(false), "{applied}");
    assert!(
        applied["error"]["message"]
            .as_str()
            .unwrap_or("")
            .contains("不是 JSON 对象"),
        "{applied}"
    );
}

/// 既没有 `ops` 也没有 `tools`：拒，别写一条空事务。
#[test]
fn an_edit_with_nothing_to_do_is_refused() {
    let (handle, _session, message, _dir) = seed();
    let spec = c(&json!({ "message_id": message }).to_string());
    let applied = take(nmoi_ffi::storage::nmoi_storage_apply_unit_edits_json(
        handle,
        spec.as_ptr(),
    ));
    assert_eq!(applied["ok"], Value::Bool(false), "{applied}");
    assert!(
        applied["error"]["message"]
            .as_str()
            .unwrap_or("")
            .contains("没有东西要改"),
        "{applied}"
    );
}
