//! C ABI 边界（计划 §3.11，D53–D57）。
//!
//! **panic 不能跨 FFI。** 所有 JSON 函数都过 [`json::run_json`]（`catch_unwind`），
//! panic 变成 `{"ok":false,"error":{"kind":"panic"}}`；标量函数变成 `FfiStatus::Panic`。
//! 这不是防御性编程，是 `extern "C"` 的未定义行为边界。
//!
//! # 三种调用形状
//!
//! | 形状 | 签名 | 失败 |
//! |---|---|---|
//! | 数据 / 句柄 | `fn(..) -> *mut c_char` | 永远返回堆上的 JSON，**不返回 null** |
//! | 标量 | `fn(..) -> i32` / `u32` | 负数 = [`FfiStatus`]（只用于 ABI 级问题） |
//! | 输入串 | `*const c_char` | 只读、不接管，调用期间必须有效 |
//!
//! # 释放
//!
//! 所有 `*_json` 的返回值**必须**用 [`nmoi_string_free`] 归还。不还只是泄漏，不会崩。
//!
//! # 版本
//!
//! [`ABI_VERSION`]：加函数不递增；改参数含义 / 返回语义必须递增。
//! 绑定层加载动态库后先比对一次，不匹配就别用——比运行时收到看不懂的 JSON 便宜。

use std::ffi::{c_char, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use serde_json::json;

mod handles;
mod icons;
#[cfg(feature = "jni")]
mod jni_bridge;
mod json;
mod settings;
mod storage;
mod text;
mod tools;
mod turn;
mod vision;
mod workspace;

/// ABI 版本。加函数不改语义时不递增；改语义（参数含义、返回码）必须递增。
///
/// 1 = stage 0 的最小面；stage F1（storage / workspace / tools / text）只加函数，所以还是 1。
pub const ABI_VERSION: u32 = 1;

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FfiStatus {
    Ok = 0,
    /// 传入的空指针。
    NullPointer = -1,
    /// 缓冲区太小；返回值是所需字节数（含结尾 NUL）。
    BufferTooSmall = -2,
    /// 边界内发生了 panic，已被拦截。
    Panic = -3,
}

/// 把字符串写入调用方缓冲区。成功返回写入的字节数（不含 NUL）；
/// 缓冲区不足时返回所需长度（含 NUL）并置 `BufferTooSmall`。
fn write_c_string(text: &str, buf: *mut c_char, len: usize) -> i32 {
    if buf.is_null() {
        return FfiStatus::NullPointer as i32;
    }
    let bytes = text.as_bytes();
    let needed = bytes.len() + 1;
    if len < needed {
        return needed as i32;
    }
    // SAFETY: 调用方保证 buf 至少有 len 字节可写；len >= needed 已检查。
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf.cast::<u8>(), bytes.len());
        *buf.add(bytes.len()) = 0;
    }
    bytes.len() as i32
}

fn guarded<F: FnOnce() -> i32>(f: F) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => FfiStatus::Panic as i32,
    }
}

#[no_mangle]
pub extern "C" fn nmoi_abi_version() -> u32 {
    ABI_VERSION
}

/// 固定字符串走"调用方给缓冲区"这条路：版本号这类值每次都要，不值得为它分配。
///
/// 缓冲区不足时返回所需长度（含 NUL），调用方按这个数重试——不需要两次握手。
#[no_mangle]
pub extern "C" fn nmoi_product_name(buf: *mut c_char, len: usize) -> i32 {
    guarded(|| write_c_string(nmoi_core::PRODUCT_NAME, buf, len))
}

#[no_mangle]
pub extern "C" fn nmoi_version(buf: *mut c_char, len: usize) -> i32 {
    guarded(|| write_c_string(nmoi_core::VERSION, buf, len))
}

/// 自检：跑一遍核心的纯函数（不碰磁盘、不碰网络）。
///
/// 返回 0 表示 OK。Kotlin / C# 侧在加载动态库后调用一次，确认绑定与 ABI 对齐。
#[no_mangle]
pub extern "C" fn nmoi_selftest() -> i32 {
    guarded(|| {
        let tokens = nmoi_core::estimate_tokens("NMoi-Agent");
        let form = nmoi_core::ContentForm::Pointer;
        if tokens == 0 || !form.requires_archive() {
            return -10;
        }
        FfiStatus::Ok as i32
    })
}

/// 故意 panic 的探针：验证 `catch_unwind` 真的把 panic 挡在边界内。
///
/// 生产代码不会调用它；CI 里有一条测试专门调用，返回 -3 才算通过。
#[no_mangle]
pub extern "C" fn nmoi_panic_probe() -> i32 {
    guarded(|| panic!("panic probe: 这个 panic 不能穿过 FFI 边界"))
}

/// 归还一个由 Rust 分配的字符串。**唯一**的释放路径。
///
/// 传 null 是合法的空操作（清理路径不用判空）。
///
/// # Safety
///
/// 指针必须来自本库的某个 `*_json` 返回值，且只能归还一次。
#[no_mangle]
pub unsafe extern "C" fn nmoi_string_free(text: *mut c_char) {
    if text.is_null() {
        return;
    }
    // SAFETY: 契约要求它来自 `CString::into_raw`。
    drop(CString::from_raw(text));
}

/// 这一层到底给了什么——绑定层 init 时打一行日志，出问题时第一眼看这里。
///
/// `features.turn` 是 `false`：真网络往返（`nmoi_turn_run`）还没接，见 PLAN §5 第 5 条。
/// 把"没有的"也写出来，比让调用方去猜 `nmoi_turn_run` 为什么链接不到好。
#[no_mangle]
pub extern "C" fn nmoi_capabilities_json() -> *mut c_char {
    json::run_json(|| {
        let registry = nmoi_core::tools::ToolRegistry::workspace_defaults();
        let (storage_handles, workspace_handles) = handles::live_counts();
        Ok(json!({
            "abi_version": ABI_VERSION,
            "product": nmoi_core::PRODUCT_NAME,
            "version": nmoi_core::VERSION,
            "schema_version": nmoi_storage::migrate::SCHEMA_VERSION,
            "transport": {
                // D38：不升 MSRV、TLS 交平台侧。这里说实话，让 Android/Windows 侧
                // 自己去接系统 TLS，而不是以为核心能发 https。
                "https": false,
                "plain_http": true,
                "note": "https 由平台侧提供 TLS 后在调用方拼装；核心只跑明文 HTTP/1.1",
            },
            "features": {
                "storage": true,
                "workspace": true,
                "tools": true,
                "redact": true,
                "turn": true,
                "cancel": true,
                "vision_defaults": true,
                "icons": true,
                "events": false,
            },
            "tools": registry.names(),
            "live_handles": {
                "storage": storage_handles,
                "workspace": workspace_handles,
            },
        }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;
    use std::ptr;

    /// 把 `*mut c_char` 收回来：既取值又释放，测试里不手写 `from_raw`。
    fn take(pointer: *mut c_char) -> serde_json::Value {
        assert!(!pointer.is_null(), "JSON 函数不允许返回 null（D55）");
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .expect("JSON 是 UTF-8")
            .to_string();
        unsafe { nmoi_string_free(pointer) };
        serde_json::from_str(&text).expect("返回值必须是合法 JSON")
    }

    fn with_c_str<T>(text: &str, body: impl FnOnce(*const c_char) -> T) -> T {
        let cstring = CString::new(text).expect("测试文本不含 NUL");
        body(cstring.as_ptr())
    }

    #[test]
    fn version_roundtrip() {
        let mut buf = [0 as c_char; 64];
        let written = nmoi_version(buf.as_mut_ptr(), buf.len());
        assert!(written > 0);
        let text = unsafe { CStr::from_ptr(buf.as_ptr()) }.to_str().unwrap();
        assert_eq!(text, nmoi_core::VERSION);
        assert_eq!(nmoi_abi_version(), ABI_VERSION);
        assert_eq!(nmoi_selftest(), 0);
    }

    #[test]
    fn buffer_too_small_reports_needed_length() {
        let mut tiny = [0 as c_char; 2];
        let needed = nmoi_version(tiny.as_mut_ptr(), tiny.len());
        assert!(needed > 2, "应该返回所需长度（含 NUL），拿到 {needed}");
        let mut exact = vec![0 as c_char; needed as usize];
        let written = nmoi_version(exact.as_mut_ptr(), exact.len());
        assert_eq!(written as usize, needed as usize - 1);
    }

    #[test]
    fn null_buffer_is_reported_not_crashing() {
        assert_eq!(
            nmoi_product_name(ptr::null_mut(), 8),
            FfiStatus::NullPointer as i32
        );
    }

    #[test]
    fn panic_does_not_cross_the_boundary() {
        assert_eq!(nmoi_panic_probe(), FfiStatus::Panic as i32);
    }

    #[test]
    fn null_argument_becomes_json_error() {
        let value = take(crate::text::nmoi_estimate_tokens_json(ptr::null()));
        assert_eq!(value["ok"], serde_json::json!(false));
        assert_eq!(value["error"]["kind"], serde_json::json!("null_argument"));
    }

    #[test]
    fn envelope_puts_ok_first() {
        // 绑定层可以先只看第一个键；这条规矩靠手写拼接保证，所以得钉住。
        let pointer = crate::json::ok_json(serde_json::json!({"a": 1}));
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .unwrap()
            .to_string();
        unsafe { nmoi_string_free(pointer) };
        assert!(text.starts_with(r#"{"ok":true,"#), "实际是 {text}");
    }

    /// `capabilities` 是绑定层 init 时唯一该读的东西，所以"没有的"也要写出来：
    /// 事件回调还没接、https 还不支持。这两条不能等到调用方链接不到符号才发现。
    #[test]
    fn capabilities_lists_what_is_missing_too() {
        let value = take(nmoi_capabilities_json());
        assert_eq!(value["ok"], serde_json::json!(true));
        assert_eq!(value["features"]["turn"], serde_json::json!(true));
        assert_eq!(value["features"]["cancel"], serde_json::json!(true));
        assert_eq!(value["features"]["events"], serde_json::json!(false));
        assert_eq!(value["transport"]["https"], serde_json::json!(false));
    }

    #[test]
    fn invalid_handle_is_a_clean_error() {
        let value = take(crate::storage::nmoi_storage_close_json(999_999));
        // 关闭是幂等的：不存在也 ok，只是 closed=false。
        assert_eq!(value["ok"], serde_json::json!(true));
        assert_eq!(value["closed"], serde_json::json!(false));

        let health = take(crate::storage::nmoi_storage_health_json(999_999));
        assert_eq!(health["ok"], serde_json::json!(false));
        assert_eq!(health["error"]["kind"], serde_json::json!("invalid_handle"));
    }

    #[test]
    fn redact_reports_kind_without_echoing_secret() {
        let secret = "sk-abcdefghijklmnopqrstuvwxyz";
        let value = with_c_str(secret, |pointer| {
            take(crate::text::nmoi_redact_json(pointer))
        });
        assert_eq!(value["ok"], serde_json::json!(true));
        assert_eq!(value["clean"], serde_json::json!(false));
        assert_eq!(value["hits"][0]["kind"], serde_json::json!("api_key"));
        let rendered = value.to_string();
        assert!(
            !rendered.contains(secret),
            "命中记录里不能回显原文：{rendered}"
        );
    }
}
