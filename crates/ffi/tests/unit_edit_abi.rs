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
