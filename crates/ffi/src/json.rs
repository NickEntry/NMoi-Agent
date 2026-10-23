//! JSON 信封与参数读取（D53 / D55）。
//!
//! 三条规矩，绑定层可以依赖：
//!
//! 1. **返回数据的函数永不返回 null**，失败也是 JSON——所以调用点只有一个分支。
//! 2. `ok` 永远在第一个键（手写拼接出来的，不靠 serde 的字段顺序）。
//! 3. 失败时 `error.kind` 是稳定词表，`error.message` 是给人看的（允许改）。

use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use serde_json::Value;

/// ABI 内部的错误。
///
/// `kind` 是契约的一部分（绑定层可以 switch），`message` 不是——改文案不该算破坏兼容。
#[derive(Debug)]
pub(crate) struct FfiError {
    pub(crate) kind: &'static str,
    pub(crate) message: String,
}

impl FfiError {
    pub(crate) fn new(kind: &'static str, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

pub(crate) type FfiResult<T> = Result<T, FfiError>;

/// 把一段 JSON 文本交给调用方。
///
/// `CString` 在遇到内部 NUL 时失败：`serde_json` 会把控制字符转义成 `\u0000`，
/// 所以正常路径不会走到兜底分支；万一走到了，也要还给调用方一个**合法**的 JSON，
/// 不能返回 null——null 会让 D55 的那条规矩出现例外。
fn emit(text: String) -> *mut c_char {
    match CString::new(text) {
        Ok(cstring) => cstring.into_raw(),
        Err(_) => CString::new(
            r#"{"ok":false,"error":{"kind":"bad_argument","message":"内部错误：结果里含有 NUL 字节"}}"#,
        )
        .expect("静态 JSON 不含 NUL")
        .into_raw(),
    }
}

/// 成功信封：`{"ok":true, ...}`。`body` 必须是对象。
pub(crate) fn ok_json(body: Value) -> *mut c_char {
    let rendered = match body {
        Value::Object(map) => serde_json::to_string(&Value::Object(map)).unwrap_or_default(),
        other => other.to_string(),
    };
    let text = if rendered == "{}" || rendered.is_empty() {
        r#"{"ok":true}"#.to_string()
    } else {
        // `rendered` 形如 `{"a":1}`：砍掉开头的 `{`，接到 `{"ok":true,` 后面。
        // 下标 1 落在 ASCII 边界上，安全。
        format!(r#"{{"ok":true,{}"#, &rendered[1..])
    };
    emit(text)
}

/// 失败信封：`{"ok":false,"error":{"kind":..,"message":..}}`。
pub(crate) fn err_json(kind: &str, message: &str) -> *mut c_char {
    let error = serde_json::json!({ "kind": kind, "message": message });
    emit(format!(r#"{{"ok":false,"error":{}}}"#, error))
}

/// 导出函数的统一外壳：跑闭包、拦 panic、把结果装进信封。
///
/// `extern "C"` 里让 panic 穿过去是未定义行为，这条不是防御性编程（见模块文档）。
pub(crate) fn run_json<F: FnOnce() -> FfiResult<Value>>(body: F) -> *mut c_char {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(value)) => ok_json(value),
        Ok(Err(error)) => err_json(error.kind, &error.message),
        Err(payload) => err_json("panic", &panic_text(payload)),
    }
}

fn panic_text(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(text) = payload.downcast_ref::<&str>() {
        format!("边界内发生 panic，已拦截：{text}")
    } else if let Some(text) = payload.downcast_ref::<String>() {
        format!("边界内发生 panic，已拦截：{text}")
    } else {
        "边界内发生 panic，已拦截".to_string()
    }
}

/// 读一个必填字符串参数。**不接管所有权**，只借用调用方的缓冲区。
///
/// # Safety
///
/// `ptr` 要么是 null，要么是 NUL 结尾的合法指针（调用方的契约）。
pub(crate) fn read_str<'a>(ptr: *const c_char, field: &str) -> FfiResult<&'a str> {
    match read_opt_str(ptr, field)? {
        Some(text) => Ok(text),
        None => Err(FfiError::new(
            "null_argument",
            format!("参数 `{field}` 是空指针"),
        )),
    }
}

/// 读一个可选字符串参数：null 表示"没给"，空串表示"给了空"。
///
/// 这两件事在 UI 上不一样：`title = null` 是"用默认标题"，`title = ""` 是"用户清空了"。
pub(crate) fn read_opt_str<'a>(ptr: *const c_char, field: &str) -> FfiResult<Option<&'a str>> {
    if ptr.is_null() {
        return Ok(None);
    }
    // SAFETY: 调用方保证非 null 指针是 NUL 结尾的合法字符串。
    let bytes = unsafe { CStr::from_ptr(ptr) };
    match bytes.to_str() {
        Ok(text) => Ok(Some(text)),
        Err(error) => Err(FfiError::new(
            "invalid_utf8",
            format!("参数 `{field}` 不是合法 UTF-8：{error}"),
        )),
    }
}
