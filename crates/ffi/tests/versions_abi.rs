//! 版本那四个 C ABI 动词的**端到端**测试。
//!
//! 为什么要在这一层另写一遍（storage 里已经有 11 条）：storage 的测试绕开了 JSON 信封，
//! 而真机上出错的地方往往不是 SQL，是**信封/参数**——字段名少了、`version_no` 传成 0、
//! `ok` 之外多嵌一层。这里照着 UI 真实调用顺序走一遍：
//! open → create_conversation → append_text → save_version ×2 → load_units → versions
//! → delete_version → load_units → switch_version(keep)。

use std::ffi::{CStr, CString};

use serde_json::Value;

/// 把 `*mut c_char` 收回来：取值 + 释放。
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

#[test]
fn version_verbs_roundtrip_through_c_abi() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let path_c = c(path.to_str().unwrap());

    let opened = take(nmoi_ffi::storage::nmoi_storage_open_json(path_c.as_ptr()));
    assert_eq!(opened["ok"], Value::Bool(true), "open 失败：{opened}");
    assert_eq!(opened["state"], "ready", "库没过校验：{opened}");
    let handle = opened["handle"].as_u64().unwrap();

    // 建会话
    let title = c("版本 ABI 测试");
    let assistant = c("default");
    let created = take(nmoi_ffi::storage::nmoi_storage_create_conversation_json(
        handle,
        title.as_ptr(),
        assistant.as_ptr(),
    ));
    assert_eq!(created["ok"], Value::Bool(true), "建会话失败：{created}");
    let session = created["session"].as_str().unwrap().to_string();
    let session_c = c(&session);

    // 存一条助手消息（version_count 应当是 0，且不出现在版本索引里）
    let role = c("assistant");
    let text = c("原始回复");
    let appended = take(nmoi_ffi::storage::nmoi_storage_append_text_json(
        handle,
        session_c.as_ptr(),
        role.as_ptr(),
        text.as_ptr(),
        1_700_000_000_000,
    ));
    assert_eq!(appended["ok"], Value::Bool(true), "存消息失败：{appended}");
    let message_id = format!("{}:{}", session, appended["seq"].as_u64().unwrap());
    let message_c = c(&message_id);

    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    let first_message = &units["items"][0]["messages"][0];
    assert_eq!(first_message["version_count"], Value::from(0));
    assert_eq!(first_message["version_edited"], Value::Bool(false));

    // 记两版
    for body in ["第一版", "第二版"] {
        let body_c = c(body);
        let saved = take(nmoi_ffi::storage::nmoi_storage_save_version_json(
            handle,
            message_c.as_ptr(),
            body_c.as_ptr(),
        ));
        assert_eq!(saved["ok"], Value::Bool(true), "存版本失败：{saved}");
    }

    // 界面上能看到 2/2
    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    let message = &units["items"][0]["messages"][0];
    assert_eq!(
        message["version_count"],
        Value::from(2),
        "load_units：{units}"
    );
    assert_eq!(message["version_active"], Value::from(2));
    assert_eq!(message["version_view"], "raw");
    assert_eq!(message["content"], "第二版");

    // versions 动词
    let listed = take(nmoi_ffi::storage::nmoi_storage_versions_json(
        handle,
        message_c.as_ptr(),
    ));
    assert_eq!(listed["ok"], Value::Bool(true), "列版本失败：{listed}");
    assert_eq!(listed["count"], Value::from(2));
    assert_eq!(listed["active"], Value::from(2));
    assert_eq!(listed["versions"].as_array().unwrap().len(), 2);

    // 删当前版 → 回到 1/1，正文变成第一版
    let deleted = take(nmoi_ffi::storage::nmoi_storage_delete_version_json(
        handle,
        message_c.as_ptr(),
        2,
    ));
    assert_eq!(deleted["ok"], Value::Bool(true), "删版本失败：{deleted}");
    assert_eq!(deleted["count"], Value::from(1));
    assert_eq!(deleted["active"], Value::from(1));
    assert_eq!(deleted["content"], "第一版");

    let units = take(nmoi_ffi::storage::nmoi_storage_load_units_json(
        handle,
        session_c.as_ptr(),
        0,
    ));
    let message = &units["items"][0]["messages"][0];
    assert_eq!(
        message["version_count"],
        Value::from(1),
        "删完应当只剩一版：{units}"
    );
    assert_eq!(message["content"], "第一版");

    // 只剩一版再删 → ok:false，并且带一句人话
    let refused = take(nmoi_ffi::storage::nmoi_storage_delete_version_json(
        handle,
        message_c.as_ptr(),
        1,
    ));
    assert_eq!(refused["ok"], Value::Bool(false), "应当拒绝：{refused}");
    assert!(
        refused["error"]["message"]
            .as_str()
            .unwrap_or_default()
            .contains("只剩一版"),
        "拒绝原因要能看懂：{refused}"
    );

    // switch(keep) 动词在只剩一版时也能正常回
    let keep = c("keep");
    let switched = take(nmoi_ffi::storage::nmoi_storage_switch_version_json(
        handle,
        message_c.as_ptr(),
        1,
        keep.as_ptr(),
    ));
    assert_eq!(switched["ok"], Value::Bool(true), "切版本失败：{switched}");
    assert_eq!(switched["content"], "第一版");

    let closed = take(nmoi_ffi::storage::nmoi_storage_close_json(handle));
    assert_eq!(closed["ok"], Value::Bool(true), "关库失败：{closed}");
}
