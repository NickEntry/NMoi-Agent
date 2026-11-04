//! 上下文图片清单的 C ABI（D248）。
//!
//! 三个动词：看、移出一张、清空。**没有"加入"**——加入只有 `View-Image` 工具一条路：
//! 界面能加的话，模型就不知道上下文里多了什么，那正是"看不见的上下文"。

use std::ffi::c_char;

use nmoi_core::images::{self, MAX_LIMIT};
use serde_json::{json, Value};

use crate::json::{read_str, run_json, FfiError};

fn parse(raw: &str) -> Result<Value, FfiError> {
    serde_json::from_str(raw).map_err(|error| FfiError::new("bad_json", error.to_string()))
}

fn session_of(value: &Value) -> Result<&str, FfiError> {
    value
        .get("session")
        .and_then(Value::as_str)
        .ok_or_else(|| FfiError::new("bad_argument", "缺 session"))
}

fn images_json(session: &str) -> Value {
    let rows: Vec<Value> = images::list(session)
        .into_iter()
        .map(|image| {
            json!({
                "id": image.id,
                "mime": image.mime,
                "bytes": image.bytes,
            })
        })
        .collect();
    json!({
        "session": session,
        "images": rows,
        "count": rows.len(),
        "max": MAX_LIMIT,
        "default_limit": images::DEFAULT_LIMIT,
    })
}

/// `{"session"}` → `{"images":[{id,mime,bytes}], "count", "max":100, "default_limit":20}`。
///
/// `max` 是硬上限（三家端点里最弱的 Anthropic 就是 100 张/请求）；界面上那一格
/// 不该让人填到 500——换了端点就 400。
#[no_mangle]
pub extern "C" fn nmoi_images_json(input: *const c_char) -> *mut c_char {
    run_json(|| {
        let raw = read_str(input, "input")?;
        let value = parse(raw)?;
        let session = session_of(&value)?;
        Ok(images_json(session))
    })
}

/// `{"session","id"}` → `{"removed":bool, ...}`。不在清单里返回 `false`，不是错误。
#[no_mangle]
pub extern "C" fn nmoi_images_remove_json(input: *const c_char) -> *mut c_char {
    run_json(|| {
        let raw = read_str(input, "input")?;
        let value = parse(raw)?;
        let session = session_of(&value)?;
        let id = value
            .get("id")
            .and_then(Value::as_str)
            .ok_or_else(|| FfiError::new("bad_argument", "缺 id"))?;
        let removed = images::remove(session, id);
        let mut body = images_json(session);
        body["removed"] = json!(removed);
        Ok(body)
    })
}

/// `{"session"}` → `{"cleared":N, ...}`。
#[no_mangle]
pub extern "C" fn nmoi_images_clear_json(input: *const c_char) -> *mut c_char {
    run_json(|| {
        let raw = read_str(input, "input")?;
        let value = parse(raw)?;
        let session = session_of(&value)?;
        let cleared = images::clear(session);
        let mut body = images_json(session);
        body["cleared"] = json!(cleared);
        Ok(body)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::{CStr, CString};

    fn read(pointer: *mut c_char) -> Value {
        assert!(!pointer.is_null());
        // SAFETY: 指针来自 `run_json` 的 `CString::into_raw`，读一次再还回去。
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned();
        unsafe { drop(CString::from_raw(pointer)) };
        serde_json::from_str(&text).expect("必须是合法 JSON")
    }

    #[test]
    fn listing_removing_and_clearing_round_trip() {
        let session = "s-ffi-images";
        let _ = images::clear(session);
        images::add(
            session,
            images::ContextImage {
                id: "a".into(),
                mime: "image/jpeg".into(),
                bytes: 10,
            },
            20,
        );

        let config = CString::new(r#"{"session":"s-ffi-images"}"#).unwrap();
        let listed = read(nmoi_images_json(config.as_ptr()));
        assert_eq!(listed["count"], json!(1), "{listed}");
        assert_eq!(listed["images"][0]["id"], json!("a"), "{listed}");
        assert_eq!(listed["max"], json!(100), "硬上限取最弱的端点：{listed}");

        let remove = CString::new(r#"{"session":"s-ffi-images","id":"a"}"#).unwrap();
        let removed = read(nmoi_images_remove_json(remove.as_ptr()));
        assert_eq!(removed["removed"], json!(true), "{removed}");
        assert_eq!(removed["count"], json!(0), "{removed}");

        // 再移一次：false，不是错误。
        let again = read(nmoi_images_remove_json(remove.as_ptr()));
        assert_eq!(again["ok"], json!(true), "{again}");
        assert_eq!(again["removed"], json!(false), "{again}");

        let cleared = read(nmoi_images_clear_json(config.as_ptr()));
        assert_eq!(cleared["cleared"], json!(0), "{cleared}");
    }

    #[test]
    fn a_missing_session_is_named() {
        let config = CString::new(r#"{"id":"a"}"#).unwrap();
        let value = read(nmoi_images_json(config.as_ptr()));
        assert_eq!(value["ok"], json!(false), "{value}");
        assert!(
            value["error"]["message"]
                .as_str()
                .unwrap()
                .contains("session"),
            "报错要点名缺的是哪一格：{value}"
        );
    }
}
