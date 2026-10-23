//! 设置面的 C ABI（D86）。
//!
//! 形状只有五个动词：`put / get / list / delete / repairs`。四类设置文档
//! （`model_overrides` / `assistant` / `conversation` / `roles`）走同一组函数，
//! scope 是参数而不是函数名——否则每加一类设置就要在头文件里长四个函数，
//! 客户端也得跟着加一层包装。
//!
//! # `get` 有三种结局，都写在 JSON 里
//!
//! `{"state":"ready","doc":{…}}` / `{"state":"missing"}` / `{"state":"reset","reason":"…"}`。
//! 第三种**不是错误**：数据在那儿但没法用，已经按默认处理并记了一笔（D79）。
//! 把它做成 `error` 会让客户端在"读设置"这条必经之路上写一遍兜底，而兜底也会写错。
//!
//! # 写进去的必须是对象
//!
//! 用 `Map<String, Value>` 反序列化，而不是 `Value`：非对象（数字、字符串、数组）
//! 会在写入口被挡掉，读的时候也会落进 `reset`。宽松地接受什么都能写的后果是，
//! 以后读的人要在每个字段上判断"这东西到底是不是我以为的那样"。

use std::ffi::c_char;

use nmoi_core::settings::DOC_VERSION;
use nmoi_storage::settings::{Doc, Loaded, Scope};
use serde_json::{Map, Value};

use crate::handles::with_storage;
use crate::json::{read_str, run_json, FfiError, FfiResult};

/// 一次列表最多给多少条。UI 传 1e9 不该把手机拖死（同 D61 的口径）。
const LIST_HARD_CAP: usize = 200;

fn storage_error(error: impl std::fmt::Display) -> FfiError {
    FfiError::new("storage", error.to_string())
}

fn parse_scope(text: &str) -> FfiResult<Scope> {
    Scope::parse(text).ok_or_else(|| {
        FfiError::new(
            "bad_argument",
            format!(
                "scope 只能是 model_overrides / assistant / conversation / roles，收到 `{text}`"
            ),
        )
    })
}

/// 解析一份文档的 payload。**只接受对象**。
fn parse_object(payload: &str, field: &str) -> FfiResult<Map<String, Value>> {
    match serde_json::from_str::<Map<String, Value>>(payload) {
        Ok(map) => Ok(map),
        Err(error) => Err(FfiError::new(
            "bad_argument",
            format!("参数 `{field}` 必须是一个 JSON 对象：{error}"),
        )),
    }
}

/// 写一份设置文档。
///
/// 入参：`scope`、`key`、`doc`（JSON 对象文本）、`now_ms`。
/// 出参：`{"scope":"assistant","key":"as_1","doc_version":1}`
///
/// `doc` 不是对象、或 `key` 为空 → `bad_argument`。**不覆盖写坏的东西**是本函数的职责：
/// 让它写进去，就等于把"读不出来的数据"交给用户自己承担。
#[no_mangle]
pub extern "C" fn nmoi_settings_put_json(
    handle: u64,
    scope: *const c_char,
    key: *const c_char,
    doc: *const c_char,
    now_ms: i64,
) -> *mut c_char {
    run_json(|| {
        let scope = parse_scope(read_str(scope, "scope")?)?;
        let key = read_str(key, "key")?;
        if key.is_empty() {
            return Err(FfiError::new("bad_argument", "参数 `key` 不能为空"));
        }
        let payload = read_str(doc, "doc")?;
        // 先按对象校验一遍：不校验就把半截 JSON 写进库，读的时候才发现，那时候
        // 用户已经在另一个界面里看到"设置丢了"。
        parse_object(payload, "doc")?;
        with_storage(handle, |storage| {
            storage
                .put_doc(scope, key, payload, now_ms)
                .map_err(storage_error)?;
            Ok(serde_json::json!({
                "scope": scope.as_str(),
                "key": key,
                "doc_version": DOC_VERSION,
            }))
        })
    })
}

/// 读一份设置文档。
///
/// 出参三态：`ready`（带 `doc`）/ `missing` / `reset`（带 `reason`，并已记修复）。
#[no_mangle]
pub extern "C" fn nmoi_settings_get_json(
    handle: u64,
    scope: *const c_char,
    key: *const c_char,
    now_ms: i64,
) -> *mut c_char {
    run_json(|| {
        let scope = parse_scope(read_str(scope, "scope")?)?;
        let key = read_str(key, "key")?;
        with_storage(handle, |storage| {
            let loaded = storage
                .load_setting::<Map<String, Value>>(scope, key, now_ms)
                .map_err(storage_error)?;
            Ok(match loaded {
                Loaded::Missing => serde_json::json!({ "state": "missing" }),
                Loaded::Reset { reason } => {
                    serde_json::json!({ "state": "reset", "reason": reason })
                }
                Loaded::Ready(doc) => serde_json::json!({
                    "state": "ready",
                    "scope": scope.as_str(),
                    "key": key,
                    "doc": Value::Object(doc),
                }),
            })
        })
    })
}

/// 列一个 scope 下的全部文档，最近更新的在前。
///
/// 出参：`{"items":[{"key","updated_at","doc_version","readable","doc"}],"returned":n}`
///
/// **这里不记修复**：列表会被反复刷，每刷一次记一条会把真正的那一条淹掉。
/// 读不出来就给 `readable:false` 并且不给 `doc` 字段（给 `null` 会让人以为"有个空文档"）。
#[no_mangle]
pub extern "C" fn nmoi_settings_list_json(
    handle: u64,
    scope: *const c_char,
    limit: i64,
) -> *mut c_char {
    run_json(|| {
        let scope = parse_scope(read_str(scope, "scope")?)?;
        let limit = if limit <= 0 {
            LIST_HARD_CAP
        } else {
            (limit as usize).min(LIST_HARD_CAP)
        };
        with_storage(handle, |storage| {
            let docs = storage.list_docs(scope).map_err(storage_error)?;
            let total = docs.len();
            let items: Vec<Value> = docs
                .iter()
                .take(limit)
                .map(|(key, doc): &(String, Doc)| {
                    let parsed = serde_json::from_str::<Map<String, Value>>(&doc.payload);
                    let mut item = serde_json::json!({
                        "key": key,
                        "updated_at": doc.updated_at,
                        "doc_version": doc.doc_version,
                        "readable": parsed.is_ok(),
                    });
                    if let Ok(map) = parsed {
                        item["doc"] = Value::Object(map);
                    }
                    item
                })
                .collect();
            Ok(serde_json::json!({
                "scope": scope.as_str(),
                "items": items,
                "returned": items.len(),
                "truncated": total > items.len(),
            }))
        })
    })
}

/// 删一份文档。幂等：删不存在的返回 `{"deleted":false}`，不是错误。
#[no_mangle]
pub extern "C" fn nmoi_settings_delete_json(
    handle: u64,
    scope: *const c_char,
    key: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let scope = parse_scope(read_str(scope, "scope")?)?;
        let key = read_str(key, "key")?;
        with_storage(handle, |storage| {
            let deleted = storage.delete_doc(scope, key).map_err(storage_error)?;
            Ok(serde_json::json!({ "deleted": deleted }))
        })
    })
}

/// 设置被重置的记录（最近在前）。
///
/// 出参：`{"items":[{"at","scope","key","reason","digest"}],"returned":n}`
///
/// `digest` 是原值的哈希前 16 位，**不是原值**：文档里可能夹着自定义 header 的密钥，
/// 修复记录不是存原值的地方。
#[no_mangle]
pub extern "C" fn nmoi_settings_repairs_json(handle: u64, limit: i64) -> *mut c_char {
    run_json(|| {
        let limit = if limit <= 0 {
            20
        } else {
            (limit as usize).min(LIST_HARD_CAP)
        };
        with_storage(handle, |storage| {
            let notes = storage.repairs(limit).map_err(storage_error)?;
            let items: Vec<Value> = notes
                .iter()
                .map(|note| {
                    serde_json::json!({
                        "at": note.at,
                        "scope": note.scope.as_str(),
                        "key": note.key,
                        "reason": note.reason,
                        "digest": note.old_digest,
                    })
                })
                .collect();
            Ok(serde_json::json!({ "items": items, "returned": items.len() }))
        })
    })
}

/// 只留最近 `keep` 条修复记录。返回被删掉多少条。
#[no_mangle]
pub extern "C" fn nmoi_settings_trim_repairs_json(handle: u64, keep: i64) -> *mut c_char {
    run_json(|| {
        let keep = keep.max(0) as usize;
        with_storage(handle, |storage| {
            let removed = storage.trim_repairs(keep).map_err(storage_error)?;
            Ok(serde_json::json!({ "removed": removed, "kept": keep }))
        })
    })
}
