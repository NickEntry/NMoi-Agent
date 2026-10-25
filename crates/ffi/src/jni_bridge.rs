//! JNI 桥（Android 侧，P6.8）。
//!
//! **默认关**：`Cargo.toml` 里的 `jni` feature 才是开关。原因只有一个——
//! `jni` / `jni-sys` 的 MSRV 都 ≥ 1.76，而工作区 MSRV 是 1.75（D10），
//! 默认开会把 `cargo check --workspace` 在 1.75 上直接打死。
//! 于是这一层只在 Android 构建（stable 工具链）里编译。
//!
//! 形状：**不另造一套契约**。每个函数转手去调本 crate 的 C ABI（D24），
//! 拿回来的 JSON 原样交给 Kotlin——`ok` 在第一个键、失败也是 JSON（D55），
//! 所以 Kotlin 侧只有一个分支要看。
//!
//! # 符号名
//!
//! 没有 `RegisterNatives`，靠 JVM 的默认名字查找：`Java_com_nmoi_agent_ui_NativeBridge_<方法>`，
//! 包里的 `.` 变 `_`。Kotlin 侧是 `object NativeBridge`，这些是**实例方法**，
//! 所以第二个参数是 `JObject`（不是 `JClass`）——类型写错了 JVM 不会提醒你，
//! 只会在调用时把 `this` 当成别的东西。
//!
//! **这一段是踩过的**：第一版符号名写成 `Java_com_nmoi_agent_Native_*`（少了 `ui_`、
//! 类名写成 `Native`），`.so` 打进包、`loadLibrary` 也成功，但一调用就
//! `No implementation found for ...`。class / package 一改，符号名就变——所以有
//! `scripts/check_jni_symbols.sh` 在 CI 里对账，不靠人记。
//!
//! # panic
//!
//! 边界内 panic 与 C ABI 一样不许外传：兜底成 `{"ok":false,...}`，
//! 而不是让 `extern "system"` 里 unwind 出去（那是未定义行为）。

use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

/// 兜底信封。与 `json::emit` 里的那一条同形：返回**合法 JSON**，绝不返回 null。
const FALLBACK: &str =
    r#"{"ok":false,"error":{"kind":"panic","message":"JNI 边界内 panic，已拦下"}}"#;

/// 把 C ABI 返回的字符串搬成 `String`，并把所有权还回去。
fn take_json(ptr: *mut c_char) -> String {
    if ptr.is_null() {
        return r#"{"ok":false,"error":{"kind":"null","message":"C ABI 返回了空指针"}}"#
            .to_string();
    }
    // SAFETY: 指针来自本 crate 的 `emit()`（`CString::into_raw`），
    // 在 `take_json` 里是它唯一的所有者，读完立刻归还——`nmoi_string_free` 的
    // 契约正是"只能归还一次、必须来自 *_json 的返回值"。
    let text = unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned();
    unsafe { crate::nmoi_string_free(ptr) };
    text
}

/// 读一个 Kotlin 传进来的 `String`。`None` 表示它不是合法字符串（JVM 那边已经报了错）。
fn read_string(env: &mut JNIEnv<'_>, arg: &JString<'_>) -> Option<String> {
    env.get_string(arg).ok().map(|text| text.into())
}

/// 把 `String` 交给 JVM。失败只能返回 null——此时 JVM 里已经挂着 OutOfMemoryError。
fn to_jstring(env: &mut JNIEnv<'_>, text: String) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 某个参数不是合法 JVM 字符串（JVM 那边已经挂了异常）。与 `openStorageJson` 里那条同形。
fn bad_string() -> String {
    take_json(crate::json::err_json("bad_argument", "参数不是合法字符串"))
}

/// `String` → `CString`。含 NUL 说明数据本身坏了：**报回去，不去掉 NUL 硬塞**。
/// 截断之后 `key` 会变成另一个 key，写进去的东西读的时候找不到，比失败更难查。
fn c_string(text: String, field: &str) -> Result<CString, String> {
    CString::new(text).map_err(|_| format!("参数 `{field}` 含 NUL 字节"))
}

/// panic 护栏。与 C ABI 的 `guarded` 同一个意思，只是返回 `String` 而不是错误码。
fn guarded<F: FnOnce() -> String>(body: F) -> String {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or_else(|_| FALLBACK.to_string())
}

fn open_storage(path: String) -> String {
    let Ok(c_path) = CString::new(path) else {
        // 路径里有 NUL：C ABI 也接不住，直接说清楚，别让它以"库打不开"的形式出现。
        return r#"{"ok":false,"error":{"kind":"bad_argument","message":"路径含 NUL 字节"}}"#
            .to_string();
    };
    take_json(crate::storage::nmoi_storage_open_json(c_path.as_ptr()))
}

/// `Native.versionJson()`：版本与 ABI，用来确认"装上的这包到底连没连上原生层"。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_versionJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let text = guarded(|| {
        take_json(crate::json::ok_json(serde_json::json!({
            "product": nmoi_core::PRODUCT_NAME,
            "version": nmoi_core::VERSION,
            "abi": crate::ABI_VERSION,
        })))
    });
    to_jstring(&mut env, text)
}

/// `Native.openStorageJson(path)`：开库，成功时给 `handle`（整数 id，永不复用，D54）。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_openStorageJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jstring {
    // 先读完再进护栏：`get_string` 要借 `env`，而 `to_jstring` 也要借，不能同时在闭包里。
    let path = read_string(&mut env, &path);
    let text = guarded(move || match path {
        Some(path) => open_storage(path),
        None => r#"{"ok":false,"error":{"kind":"bad_argument","message":"path 不是合法字符串"}}"#
            .to_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.closeStorageJson(handle)`：幂等，重复关闭返回 `closed:false` 而不是报错。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_closeStorageJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jstring {
    let text = guarded(move || take_json(crate::storage::nmoi_storage_close_json(handle as u64)));
    to_jstring(&mut env, text)
}

/// `Native.listConversationsJson(handle, limit, offset)`：UI 第一屏的数据源（P6.2 / D61）。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_listConversationsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    limit: jlong,
    offset: jlong,
) -> jstring {
    let text = guarded(move || {
        take_json(crate::storage::nmoi_storage_list_conversations_json(
            handle as u64,
            limit,
            offset,
        ))
    });
    to_jstring(&mut env, text)
}

// ---- 设置面（D86）--------------------------------------------------------
//
// 四个动词照着 C ABI 抄，**scope 是参数而不是函数名**：在 Kotlin 侧再包一层
// `settingsAssistantGet` 之类，等于把 core 的四类文档在客户端复制一遍，
// 以后加第五类时两边一定对不上。
//
// `doc` 是 Kotlin 那边 `JSONObject.toString()` 的结果，这里原样转手；
// 「必须是对象」由 C ABI 挡（`bad_argument`），这儿不重复判——判两遍就有两份规矩，
// 改一个忘一个的时候，写进去的和读出来的不一样。

fn settings_put(handle: u64, scope: String, key: String, doc: String, now_ms: i64) -> String {
    let (scope, key, doc) = match (
        c_string(scope, "scope"),
        c_string(key, "key"),
        c_string(doc, "doc"),
    ) {
        (Ok(scope), Ok(key), Ok(doc)) => (scope, key, doc),
        (Err(message), _, _) | (_, Err(message), _) | (_, _, Err(message)) => {
            return take_json(crate::json::err_json("bad_argument", &message))
        }
    };
    take_json(crate::settings::nmoi_settings_put_json(
        handle,
        scope.as_ptr(),
        key.as_ptr(),
        doc.as_ptr(),
        now_ms,
    ))
}

fn settings_get(handle: u64, scope: String, key: String, now_ms: i64) -> String {
    let (scope, key) = match (c_string(scope, "scope"), c_string(key, "key")) {
        (Ok(scope), Ok(key)) => (scope, key),
        (Err(message), _) | (_, Err(message)) => {
            return take_json(crate::json::err_json("bad_argument", &message))
        }
    };
    take_json(crate::settings::nmoi_settings_get_json(
        handle,
        scope.as_ptr(),
        key.as_ptr(),
        now_ms,
    ))
}

fn settings_list(handle: u64, scope: String, limit: i64) -> String {
    let scope = match c_string(scope, "scope") {
        Ok(scope) => scope,
        Err(message) => return take_json(crate::json::err_json("bad_argument", &message)),
    };
    take_json(crate::settings::nmoi_settings_list_json(
        handle,
        scope.as_ptr(),
        limit,
    ))
}

fn settings_delete(handle: u64, scope: String, key: String) -> String {
    let (scope, key) = match (c_string(scope, "scope"), c_string(key, "key")) {
        (Ok(scope), Ok(key)) => (scope, key),
        (Err(message), _) | (_, Err(message)) => {
            return take_json(crate::json::err_json("bad_argument", &message))
        }
    };
    take_json(crate::settings::nmoi_settings_delete_json(
        handle,
        scope.as_ptr(),
        key.as_ptr(),
    ))
}

/// `Native.putSettingsJson(handle, scope, key, doc, nowMs)`：写一份设置文档。
///
/// `nowMs` 由 Kotlin 传系统时钟——与 C ABI 一致，这里不自己取时间：
/// 客户端和核心各取一次，同一次写入就会有两个时间戳。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_putSettingsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    scope: JString<'local>,
    key: JString<'local>,
    doc: JString<'local>,
    now_ms: jlong,
) -> jstring {
    // 三个字符串**先**读完再进护栏：`read_string` 借 `env`，`to_jstring` 也借，
    // 不能同时待在闭包里。
    let scope = read_string(&mut env, &scope);
    let key = read_string(&mut env, &key);
    let doc = read_string(&mut env, &doc);
    let text = guarded(move || match (scope, key, doc) {
        (Some(scope), Some(key), Some(doc)) => settings_put(handle as u64, scope, key, doc, now_ms),
        _ => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.getSettingsJson(handle, scope, key, nowMs)`：三态在 JSON 里
/// （`ready` / `missing` / `reset`），**这里的 Kotlin 侧不要自己判"没有就是空文档"**。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_getSettingsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    scope: JString<'local>,
    key: JString<'local>,
    now_ms: jlong,
) -> jstring {
    let scope = read_string(&mut env, &scope);
    let key = read_string(&mut env, &key);
    let text = guarded(move || match (scope, key) {
        (Some(scope), Some(key)) => settings_get(handle as u64, scope, key, now_ms),
        _ => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.listSettingsJson(handle, scope, limit)`：limit ≤ 0 = 默认（C ABI 里上限 200）。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_listSettingsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    scope: JString<'local>,
    limit: jlong,
) -> jstring {
    let scope = read_string(&mut env, &scope);
    let text = guarded(move || match scope {
        Some(scope) => settings_list(handle as u64, scope, limit),
        None => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.deleteSettingsJson(handle, scope, key)`：幂等，删不存在的返回 `deleted:false`。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_deleteSettingsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    scope: JString<'local>,
    key: JString<'local>,
) -> jstring {
    let scope = read_string(&mut env, &scope);
    let key = read_string(&mut env, &key);
    let text = guarded(move || match (scope, key) {
        (Some(scope), Some(key)) => settings_delete(handle as u64, scope, key),
        _ => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.loadUnitsJson(handle, session, limit)`：读会话的原子单元（消息区的数据源）。
///
/// 这一层不解释单元里有什么：`kind`（`turn` / `tool_pair`）、`source`
/// （`model_thinking` / `tool_call` / `tool_result` …）、`broken` 都是 core 与 storage
/// 已经定好的词，Kotlin 侧照词渲染即可。**在客户端再判一次"什么算思考"只会多一套规矩。**
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_loadUnitsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    session: JString<'local>,
    limit: jlong,
) -> jstring {
    let session = read_string(&mut env, &session);
    let text = guarded(move || match session {
        Some(session) => {
            let Ok(c_session) = CString::new(session) else {
                return take_json(crate::json::err_json(
                    "bad_argument",
                    "参数 `session` 含 NUL 字节",
                ));
            };
            take_json(crate::storage::nmoi_storage_load_units_json(
                handle as u64,
                c_session.as_ptr(),
                limit,
            ))
        }
        None => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.appendTextJson(handle, session, role, text, nowMs)`：把一句话存成一条消息（不发模型）。
///
/// 这是 UI 唯一的**写入口**：在接入 `turn_run` 之前，没有它就造不出内容，
/// 消息区在真机上永远验不了（"验证步骤要先有桌面工具造库"是上一轮的真缺口）。
///
/// `role` 只收 `user` / `assistant` / `system`——词表由 C ABI 挡，这里不重复判。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_appendTextJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    session: JString<'local>,
    role: JString<'local>,
    text: JString<'local>,
    now_ms: jlong,
) -> jstring {
    let session = read_string(&mut env, &session);
    let role = read_string(&mut env, &role);
    let text = read_string(&mut env, &text);
    let guarded_text = guarded(move || match (session, role, text) {
        (Some(session), Some(role), Some(text)) => {
            let (Ok(c_session), Ok(c_role), Ok(c_text)) = (
                CString::new(session),
                CString::new(role),
                CString::new(text),
            ) else {
                return take_json(crate::json::err_json("bad_argument", "参数含 NUL 字节"));
            };
            take_json(crate::storage::nmoi_storage_append_text_json(
                handle as u64,
                c_session.as_ptr(),
                c_role.as_ptr(),
                c_text.as_ptr(),
                now_ms,
            ))
        }
        _ => bad_string(),
    });
    to_jstring(&mut env, guarded_text)
}

/// `Native.createConversationJson(handle, title)`：新建一个会话，回 `{"session":"<id>"}`。
///
/// 没有它，全新安装的列表永远是空的：没有会话就没有入口，消息区连"空会话"都进不去。
/// 标题由 UI 给（现在是"新会话"，以后可以按首句生成）。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_createConversationJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    title: JString<'local>,
) -> jstring {
    let title = read_string(&mut env, &title);
    let text = guarded(move || match title {
        Some(title) => {
            let Ok(c_title) = CString::new(title) else {
                return take_json(crate::json::err_json(
                    "bad_argument",
                    "参数 `title` 含 NUL 字节",
                ));
            };
            take_json(crate::storage::nmoi_storage_create_conversation_json(
                handle as u64,
                c_title.as_ptr(),
            ))
        }
        None => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.versionsJson(handle, message)`：列出一条消息的所有版本。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_versionsJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    message: JString<'local>,
) -> jstring {
    let message = read_string(&mut env, &message);
    let text = guarded(move || match message {
        Some(message) => {
            let Ok(c_message) = CString::new(message) else {
                return take_json(crate::json::err_json(
                    "bad_argument",
                    "参数 `message` 含 NUL 字节",
                ));
            };
            take_json(crate::storage::nmoi_storage_versions_json(
                handle as u64,
                c_message.as_ptr(),
            ))
        }
        None => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.switchVersionJson(handle, message, versionNo, view)`：切版本 / 切原文-编辑。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_switchVersionJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    message: JString<'local>,
    version_no: jlong,
    view: JString<'local>,
) -> jstring {
    let message = read_string(&mut env, &message);
    let view = read_string(&mut env, &view);
    let text = guarded(move || match (message, view) {
        (Some(message), Some(view)) => {
            let (Ok(c_message), Ok(c_view)) = (CString::new(message), CString::new(view)) else {
                return take_json(crate::json::err_json("bad_argument", "参数含 NUL 字节"));
            };
            take_json(crate::storage::nmoi_storage_switch_version_json(
                handle as u64,
                c_message.as_ptr(),
                version_no,
                c_view.as_ptr(),
            ))
        }
        _ => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.deleteVersionJson(handle, message, versionNo)`：删一版（0 = 删除所有版本）。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_deleteVersionJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    message: JString<'local>,
    version_no: jlong,
) -> jstring {
    let message = read_string(&mut env, &message);
    let text = guarded(move || match message {
        Some(message) => {
            let Ok(c_message) = CString::new(message) else {
                return take_json(crate::json::err_json(
                    "bad_argument",
                    "参数 `message` 含 NUL 字节",
                ));
            };
            take_json(crate::storage::nmoi_storage_delete_version_json(
                handle as u64,
                c_message.as_ptr(),
                version_no,
            ))
        }
        None => bad_string(),
    });
    to_jstring(&mut env, text)
}

/// `Native.saveVersionJson(handle, message, text)`：把这段文本记成该消息的新一版。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_saveVersionJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    message: JString<'local>,
    text: JString<'local>,
) -> jstring {
    let message = read_string(&mut env, &message);
    let text = read_string(&mut env, &text);
    let result = guarded(move || match (message, text) {
        (Some(message), Some(text)) => {
            let (Ok(c_message), Ok(c_text)) = (CString::new(message), CString::new(text)) else {
                return take_json(crate::json::err_json("bad_argument", "参数含 NUL 字节"));
            };
            take_json(crate::storage::nmoi_storage_save_version_json(
                handle as u64,
                c_message.as_ptr(),
                c_text.as_ptr(),
            ))
        }
        _ => bad_string(),
    });
    to_jstring(&mut env, result)
}

/// `Native.editVersionJson(handle, message, versionNo, text)`：写入"编辑后"正文并切到它。
#[no_mangle]
pub extern "system" fn Java_com_nmoi_agent_ui_NativeBridge_editVersionJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    message: JString<'local>,
    version_no: jlong,
    text: JString<'local>,
) -> jstring {
    let message = read_string(&mut env, &message);
    let text = read_string(&mut env, &text);
    let result = guarded(move || match (message, text) {
        (Some(message), Some(text)) => {
            let (Ok(c_message), Ok(c_text)) = (CString::new(message), CString::new(text)) else {
                return take_json(crate::json::err_json("bad_argument", "参数含 NUL 字节"));
            };
            take_json(crate::storage::nmoi_storage_edit_version_json(
                handle as u64,
                c_message.as_ptr(),
                version_no,
                c_text.as_ptr(),
            ))
        }
        _ => bad_string(),
    });
    to_jstring(&mut env, result)
}
