//! 存储面的 C ABI：打开 / 体检 / 收口 / 落消息 / 读会话 / 备份。
//!
//! # `open` 的三种结局都写在 JSON 里（D57）
//!
//! `Ready` 给句柄；`Blocked`（L3 语义损坏，等用户选恢复方式）**不给句柄**，只给问题清单；
//! 真失败给 `error`。把前两种压成"成功/失败"会让 UI 少一条路——用户看到"打开失败"
//! 却不知道其实可以恢复。
//!
//! # 这里不做压缩、不做模型调用
//!
//! 这个文件里全是"用户/编排层说要存什么就存什么"。压缩的提交（`commit_compression`）
//! 是事务级操作，等 F2 的 turn 面一起接，免得现在开一个半截的口子。

use std::ffi::c_char;
use std::str::FromStr;

use nmoi_core::budget::Segment;
use nmoi_core::clock::LogicalClock;
use nmoi_core::estimate_tokens;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_core::{Estimator, Level};
use nmoi_storage::versions::VersionView;
use nmoi_storage::{OpenOutcome, Storage};
use serde_json::{json, Value};

use crate::handles::{insert_storage, remove_storage, with_storage};
use crate::json::{read_opt_str, read_str, run_json, FfiError, FfiResult};

fn storage_error(error: impl std::fmt::Display) -> FfiError {
    FfiError::new("storage", error.to_string())
}

fn parse_session(text: &str) -> FfiResult<SessionId> {
    SessionId::from_str(text)
        .map_err(|error| FfiError::new("id", format!("会话 id 不合法：{error}")))
}

fn parse_message(text: &str) -> FfiResult<MessageId> {
    MessageId::from_str(text)
        .map_err(|error| FfiError::new("id", format!("消息 id 不合法：{error}")))
}

fn parse_role(text: &str) -> FfiResult<Role> {
    match text {
        "user" => Ok(Role::User),
        "assistant" => Ok(Role::Assistant),
        "system" => Ok(Role::System),
        other => Err(FfiError::new(
            "bad_argument",
            format!("role 只能是 user / assistant / system，收到 `{other}`"),
        )),
    }
}

/// 打开（必要时新建并迁移）一个库。
///
/// 入参：`path` —— 库文件路径，父目录会被创建（SQLite 自己建文件）。
/// 出参：`{"ok":true,"state":"ready","handle":7,"path":"…","device_id":"…"}`
/// 或 `{"ok":true,"state":"blocked","issues":3,"report":{…}}`
/// 或 `{"ok":false,"error":{"kind":"storage",…}}`
#[no_mangle]
pub extern "C" fn nmoi_storage_open_json(path: *const c_char) -> *mut c_char {
    run_json(|| {
        let path = read_str(path, "path")?;
        match Storage::open(path) {
            Ok(OpenOutcome::Ready(storage)) => {
                let device = storage.device_id().to_string();
                let handle = insert_storage(storage);
                Ok(json!({
                    "state": "ready",
                    "handle": handle,
                    "path": path,
                    "device_id": device,
                }))
            }
            Ok(OpenOutcome::Blocked(blocked)) => Ok(json!({
                "state": "blocked",
                "path": blocked.path().display().to_string(),
                "issues": blocked.report().issues.len(),
                "report": serde_json::to_value(blocked.report()).unwrap_or(Value::Null),
                "hint": "库没通过 L3 语义校验，没有给出句柄；恢复方式由用户选，不自动修",
            })),
            Err(error) => Err(storage_error(error)),
        }
    })
}

/// 关掉句柄。幂等：重复关闭返回 `{"closed":false}` 而不是报错。
#[no_mangle]
pub extern "C" fn nmoi_storage_close_json(handle: u64) -> *mut c_char {
    run_json(|| Ok(json!({ "closed": remove_storage(handle) })))
}

/// 重新体检：完整性 + 语义 + 隔离区计数（`HealthReport` 原样摊平）。
#[no_mangle]
pub extern "C" fn nmoi_storage_health_json(handle: u64) -> *mut c_char {
    run_json(|| {
        let report = with_storage(handle, |storage| storage.health().map_err(storage_error))?;
        Ok(serde_json::to_value(report).unwrap_or(Value::Null))
    })
}

/// 启动收口：**只报告，不猜**（D47）。
///
/// 报三类：以 user/tool 结尾、没等到回复的会话；未闭合的工具单元；已有 `incomplete` 计数。
/// 不补假 `tool_result`、不自动删——修只由用户显式选择（`abandon`）来做。
#[no_mangle]
pub extern "C" fn nmoi_storage_reconcile_json(handle: u64) -> *mut c_char {
    run_json(|| {
        let report = with_storage(handle, |storage| storage.reconcile().map_err(storage_error))?;
        let pending: Vec<Value> = report
            .pending_turns
            .iter()
            .map(|turn| {
                json!({
                    "session": turn.session.to_string(),
                    "last_seq": turn.last_seq,
                    "last_role": turn.last_role.as_str(),
                    "last_at": turn.last_at,
                })
            })
            .collect();
        let open: Vec<Value> = report
            .open_units
            .iter()
            .map(|unit| {
                json!({
                    "session": unit.session.to_string(),
                    "unit": unit.unit_id.to_string(),
                    "first_seq": unit.first_seq,
                    "pending_calls": unit
                        .pending_calls
                        .iter()
                        .map(|(id, name)| json!({ "call_id": id, "name": name }))
                        .collect::<Vec<_>>(),
                    "last_at": unit.last_at,
                })
            })
            .collect();
        Ok(json!({
            "clean": report.is_clean(),
            "needs_attention": report.needs_attention(),
            "summary": report.summary(),
            "conversations": report.conversations,
            "units": report.units,
            "incomplete_messages": report.incomplete_messages,
            "pending_turns": pending,
            "open_units": open,
        }))
    })
}

/// 新建会话，返回 `{"session":"<uuidv7>"}`。
#[no_mangle]
pub extern "C" fn nmoi_storage_create_conversation_json(
    handle: u64,
    title: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let title = read_str(title, "title")?;
        with_storage(handle, |storage| {
            let device = storage.device_id().to_string();
            let session = storage
                .create_conversation(title, &device)
                .map_err(storage_error)?;
            Ok(json!({ "session": session.to_string() }))
        })
    })
}

/// 记下"这个会话现在用哪个模型"（D101）。
///
/// 单独一条而不是塞进 `create_conversation`：用户常常先建会话、再挑模型，
/// 而 `create` 的签名一动，C 侧所有调用点都得跟着改。
///
/// 空串按参数错误拒绝——`NULL` 才代表"不知道"，空串是调用方的 bug。
/// 返回 `{"ok":true,"session":"…","model":"…"}`。
#[no_mangle]
pub extern "C" fn nmoi_storage_set_conversation_model_json(
    handle: u64,
    session: *const c_char,
    model: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let session_id = parse_session(read_str(session, "session")?)?;
        let model = read_str(model, "model")?;
        if model.trim().is_empty() {
            return Err(FfiError::new(
                "bad_argument",
                "`model` 不能是空串：不知道就别调这条",
            ));
        }
        with_storage(handle, |storage| {
            storage
                .set_conversation_model(session_id, model)
                .map_err(storage_error)?;
            Ok(json!({ "session": session_id.to_string(), "model": model }))
        })
    })
}

/// 追加一条纯文本消息（单元 B）。
///
/// 这条路给"用户自己写一条 / 导入一段历史 / C 侧测试往返"用，**不经过模型**。
/// 模型跑一轮要走 F2 的 `nmoi_turn_run`——那边会按原子单元成对落库。
///
/// `role` 只收 `user` / `assistant` / `system`；`tool` 必须带 `tool_call_id`，
/// 走工具那一套形状，这里故意不给口子。
#[no_mangle]
pub extern "C" fn nmoi_storage_append_text_json(
    handle: u64,
    session: *const c_char,
    role: *const c_char,
    text: *const c_char,
    now_ms: i64,
) -> *mut c_char {
    run_json(|| {
        let session_text = read_str(session, "session")?;
        let role_text = read_str(role, "role")?;
        let text = read_str(text, "text")?;
        let session_id = parse_session(session_text)?;
        let role = parse_role(role_text)?;
        with_storage(handle, |storage| {
            let units = storage.load_units(session_id).map_err(storage_error)?;
            let next_seq = units
                .iter()
                .map(|unit| unit.last_message().id.seq())
                .max()
                .unwrap_or(0);
            let seq = next_seq + 1;
            let message = Message::text(
                MessageId::new(session_id, seq),
                role,
                SourceTag::History,
                text,
                estimate_tokens(text),
                now_ms,
                LogicalClock::new(seq),
            );
            let unit = AtomicUnit::turn(message)
                .map_err(|error| FfiError::new("bad_argument", error.to_string()))?;
            storage
                .append_unit(session_id, &unit)
                .map_err(storage_error)?;
            Ok(json!({
                "session": session_id.to_string(),
                "unit": unit.id.to_string(),
                "seq": seq,
                "tokens": estimate_tokens(text),
            }))
        })
    })
}

/// 记一版**新生成的**回复（重新生成时用；开关关着时不要调它）。
///
/// 出参：`{"ok":true,"message":"…","version_no":3,"count":3}`
///
/// 现在 UI 还没有真正的"重新生成"（`turn_run` 未接），所以这一条先被
/// 「更多 → 复制为新版本（临时探针）」用来验 D137 那一整套；接上 turn_run 之后，
/// 探针删掉，这个动词留着给它用。
#[no_mangle]
pub extern "C" fn nmoi_storage_save_version_json(
    handle: u64,
    message: *const c_char,
    text: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let message_text = read_str(message, "message")?;
        let text = read_str(text, "text")?;
        let message_id = parse_message(message_text)?;
        with_storage(handle, |storage| {
            let (version_no, count) = storage
                .save_generated_version(&message_id, text)
                .map_err(storage_error)?;
            Ok(json!({
                "message": message_id.to_string(),
                "version_no": version_no,
                "count": count,
            }))
        })
    })
}

/// 把某一版的"编辑后"正文写进去，并把视图切到它（UI 的「编辑」按钮走这一条）。
///
/// 两件事一次做完：`mark_version_edited` + `switch(view=edited)`。
/// 分成两次调用也行，但中间失败会留下"改了内容、视图还在原文"的半截状态，
/// 而用户点的是"编辑"，期待看到的就是编辑后的样子。
#[no_mangle]
pub extern "C" fn nmoi_storage_edit_version_json(
    handle: u64,
    message: *const c_char,
    version_no: i64,
    text: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let message_text = read_str(message, "message")?;
        let text = read_str(text, "text")?;
        let message_id = parse_message(message_text)?;
        with_storage(handle, |storage| {
            storage
                .mark_version_edited(&message_id, version_no, text)
                .map_err(storage_error)?;
            let content = storage
                .switch_message_version(&message_id, version_no, VersionView::Edited)
                .map_err(storage_error)?;
            Ok(json!({
                "message": message_id.to_string(),
                "version_no": version_no,
                "view": "edited",
                "content": content,
            }))
        })
    })
}

/// 列出某条消息的所有版本。
///
/// 出参：`{"ok":true,"message":"…","count":3,"active":2,"versions":[
///   {"no":1,"active":false,"edited":false,"view":"raw","created_at":1792825006739}, …]}`
///
/// `edited` 决定 UI 上那个 `n/n` 的颜色：false = 白，true + view raw = 蓝，true + view edited = 红。
#[no_mangle]
pub extern "C" fn nmoi_storage_versions_json(handle: u64, message: *const c_char) -> *mut c_char {
    run_json(|| {
        let message_text = read_str(message, "message")?;
        let message_id = parse_message(message_text)?;
        with_storage(handle, |storage| {
            let list = storage
                .list_message_versions(&message_id)
                .map_err(storage_error)?;
            Ok(json!({
                "message": message_id.to_string(),
                "count": list.count,
                "active": list.active,
                "versions": list
                    .versions
                    .iter()
                    .map(|version| json!({
                        "no": version.version_no,
                        "active": version.active,
                        "edited": version.edited_by_tool,
                        "view": version.view.as_str(),
                        "created_at": version.created_at,
                    }))
                    .collect::<Vec<_>>(),
            }))
        })
    })
}

/// 切到某一版的某一组正文，回**切换后应该显示的正文**。
///
/// 入参：`view` 只收 `raw` / `edited`；对没被编辑过的版本传 `edited` 会被降级成 `raw`
/// （界面上的"点数字切换"本来就只在蓝/红两态有效，为这个报错没意义）。
#[no_mangle]
pub extern "C" fn nmoi_storage_switch_version_json(
    handle: u64,
    message: *const c_char,
    version_no: i64,
    view: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let message_text = read_str(message, "message")?;
        let view_text = read_str(view, "view")?;
        let message_id = parse_message(message_text)?;
        with_storage(handle, |storage| {
            // `keep` = 沿用这一版自己记住的视图（UI 上按 < / > 翻版本时用）。
            if view_text == "keep" {
                let content = storage
                    .switch_message_version_remembered(&message_id, version_no)
                    .map_err(storage_error)?;
                return Ok(json!({
                    "message": message_id.to_string(),
                    "version_no": version_no,
                    "view": "keep",
                    "content": content,
                }));
            }
            let parsed = VersionView::parse(view_text).ok_or_else(|| {
                FfiError::new(
                    "bad_argument",
                    format!("view 只能是 raw / edited / keep，收到 `{view_text}`"),
                )
            })?;
            let content = storage
                .switch_message_version(&message_id, version_no, parsed)
                .map_err(storage_error)?;
            Ok(json!({
                "message": message_id.to_string(),
                "version_no": version_no,
                "view": parsed.as_str(),
                "content": content,
            }))
        })
    })
}

/// 删一版（`version_no = 0` 表示"删除所有版本、只留当前显示的那一版"）。
///
/// 只剩一版时拒绝删：删到零版等于删消息，而那组动词还没有。
#[no_mangle]
pub extern "C" fn nmoi_storage_delete_version_json(
    handle: u64,
    message: *const c_char,
    version_no: i64,
) -> *mut c_char {
    run_json(|| {
        let message_text = read_str(message, "message")?;
        let message_id = parse_message(message_text)?;
        with_storage(handle, |storage| {
            let (count, active, content) = storage
                .delete_message_version(&message_id, version_no)
                .map_err(storage_error)?;
            Ok(json!({
                "message": message_id.to_string(),
                "count": count,
                "active": active,
                "content": content,
            }))
        })
    })
}

/// 读一个会话的原子单元。
///
/// `limit`：> 0 时只取最后 N 个单元（UI 首屏），`<= 0` 全给。`truncated` 告诉调用方
/// "库里有更多"，别自己数。
#[no_mangle]
pub extern "C" fn nmoi_storage_load_units_json(
    handle: u64,
    session: *const c_char,
    limit: i64,
) -> *mut c_char {
    run_json(|| {
        let session_text = read_str(session, "session")?;
        let session_id = parse_session(session_text)?;
        with_storage(handle, |storage| {
            let units = storage.load_units(session_id).map_err(storage_error)?;
            // 版本概要一次查完（不是每条消息一次：那是 N+1，滚动会顿）。
            let versions = storage.version_index(session_id).map_err(storage_error)?;
            let total = units.len();
            let take = if limit <= 0 {
                total
            } else {
                (limit as usize).min(total)
            };
            let start = total - take;
            let items: Vec<Value> = units[start..]
                .iter()
                .map(|unit| {
                    let messages: Vec<Value> = unit
                        .messages
                        .iter()
                        .map(|message| {
                            let brief = versions.get(&message.id.to_string());
                            json!({
                                "id": message.id.to_string(),
                                "seq": message.id.seq(),
                                "role": message.role.as_str(),
                                "source": message.source.as_str(),
                                "content": message.content,
                                "tokens": message.tokens,
                                "created_at": message.created_at,
                                "incomplete": message.incomplete,
                                "locked": message.locked,
                                "pinned": message.pinned,
                                "protected": message.protected,
                                "revision": message.revision,
                                "tool_calls": message.tool_calls.len(),
                                "tool_call_id": message.tool_call_id,
                                // 版本（D137）：`version_count <= 1` 时 UI 不显示切换控件。
                                // `version_edited` 决定那个数字的颜色：白 / 蓝 / 红。
                                "version_count": brief.map(|b| b.count).unwrap_or(0),
                                "version_active": brief.map(|b| b.active).unwrap_or(0),
                                "version_view": brief.map(|b| b.view.as_str()).unwrap_or("raw"),
                                "version_edited": brief.map(|b| b.edited_by_tool).unwrap_or(false),
                            })
                        })
                        .collect();
                    json!({
                        "id": unit.id.to_string(),
                        "kind": unit.kind.as_str(),
                        "closed": unit.is_closed(),
                        "created_at": unit.created_at,
                        "messages": messages,
                        // 断块（P4.1 / D67）：UI 的折叠卡标题就用这个声明行，
                        // 不用自己去拼"ID+块类+原因"——那种拼法迟早某个平台少个字段。
                        "broken": nmoi_core::repair::diagnose(unit).map(|block| json!({
                            "id": block.id,
                            "kind": block.kind.label(),
                            "reason": block.reason.label(),
                            "declaration": block.declaration(),
                        })),
                    })
                })
                .collect();
            Ok(json!({
                "session": session_id.to_string(),
                "units_total": total,
                "returned": items.len(),
                "truncated": start > 0,
                "items": items,
            }))
        })
    })
}

/// 会话列表（P6.2 / D61）：UI 的第一屏。
///
/// `limit` 传 <= 0 用默认 50，硬上限 200；`offset` 传负数当 0。
/// 给 `has_more` 而不是总数——UI 只需要知道"还能不能往下翻"（D61）。
#[no_mangle]
pub extern "C" fn nmoi_storage_list_conversations_json(
    handle: u64,
    limit: i64,
    offset: i64,
) -> *mut c_char {
    run_json(|| {
        // 上限不是洁癖：UI 传个 1e9 不该把手机拖死。
        let limit = if limit <= 0 {
            50
        } else {
            (limit as usize).min(200)
        };
        let offset = offset.max(0) as usize;
        with_storage(handle, |storage| {
            let page = storage
                .list_conversations(limit, offset)
                .map_err(storage_error)?;
            let items: Vec<Value> = page
                .items
                .iter()
                .map(|conversation| {
                    json!({
                        "id": conversation.id.to_string(),
                        "title": conversation.title,
                        "created_at": conversation.created_at,
                        "updated_at": conversation.updated_at,
                        "messages": conversation.messages,
                        "tokens": conversation.tokens,
                        "incomplete": conversation.incomplete,
                        "last_seq": conversation.last_seq,
                        "last_role": conversation.last_role,
                        "last_text": conversation.last_text,
                        "last_text_truncated": conversation.last_text_truncated,
                        "model": conversation.model,
                    })
                })
                .collect();
            Ok(json!({
                "items": items,
                "returned": page.items.len(),
                "has_more": page.has_more,
                "limit": limit,
                "offset": offset,
            }))
        })
    })
}

// ---------------------------------------------------------------------------
// 上下文窗口面板的分段用量（P6.4 / §3.12）
// ---------------------------------------------------------------------------

/// 调用方额外告知的用量——**库里没有的那几行**。
///
/// 库只知道自己存了什么（历史、工具调用/结果）。系统提示词、技能、世界书、
/// 草稿是编排层在构造请求时拼进去的，库看不见。与其在这里猜一个数，
/// 不如让调用方把"我注入了多少"直接说出来。
///
/// `deny_unknown_fields` 是故意的：如果调用方把 `history` 也塞进来，
/// 那是它打算自己算——两边都算就会双倍。报错比默默多算一行好。
#[derive(Debug, Default, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct BreakdownExtra {
    system_prompt: Option<u64>,
    skills: Option<u64>,
    world_book: Option<u64>,
    workspace: Option<u64>,
    draft: Option<u64>,
    #[serde(default)]
    tool_definitions: Vec<ToolDefTokens>,
}

#[derive(Debug, serde::Deserialize)]
struct ToolDefTokens {
    name: String,
    tokens: u64,
}

fn parse_breakdown_extra(text: Option<&str>) -> FfiResult<BreakdownExtra> {
    match text {
        None | Some("") => Ok(BreakdownExtra::default()),
        Some(text) => serde_json::from_str(text)
            .map_err(|error| FfiError::new("bad_argument", format!("extra 不是合法对象：{error}"))),
    }
}

/// 上下文窗口面板的八行用量。
///
/// 入参：`session` 必填；`extra` 可选（NULL 或空串 = 只算库里有的）。
/// 出参：`{"ok":true,"session":"…","model":"…"|null,"level":"l1"|"l0","estimated":true,"total":1234,"rows":[…]}`，
/// `rows` 恒为 8 行，顺序固定，含 0 行——色块与图例位置不该随数据跳动。
///
/// `level` 是这一批线上数字的来源：`l1` = 本地 BPE + 校准过的因子，`l0` = 字符近似。
/// 会话没有模型名（还没跑过）或二进制没编 `bpe` 时都是 `l0`，面板该据此说"这是粗估"。
/// 两种口径的数**不能**混着比——同一段中文它们差 1.5 倍以上（D94）。
///
/// `tokens` 是 `estimate_tokens` 的估算，不是端点的真实 tokenizer，所以给
/// `estimated: true`：面板上那个数不该被当成账单。
#[no_mangle]
pub extern "C" fn nmoi_context_breakdown_json(
    handle: u64,
    session: *const c_char,
    extra: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let session_id = parse_session(read_str(session, "session")?)?;
        let extra = parse_breakdown_extra(read_opt_str(extra, "extra")?)?;
        with_storage(handle, |storage| {
            let units = storage.load_units(session_id).map_err(storage_error)?;
            // 会话最近用过的模型（D101）。有它才谈得上按词表 + 校准算 L1；
            // 没有（这个会话还没跑过一轮）就退回 L0，并且把这个事实写进 `level`，
            // 让面板能说"这是粗估"而不是把两个口径的数混着显示。
            let model = storage
                .conversation_model(session_id)
                .map_err(storage_error)?;
            let calibrations = match model.as_deref() {
                Some(model) => storage
                    .calibrations(model, nmoi_core::engine::now_ms())
                    .map_err(storage_error)?,
                None => Vec::new(),
            };
            let estimator = model
                .as_deref()
                .map(|model| Estimator::new(model, &calibrations));
            let level = estimator.as_ref().map_or(Level::L0, |it| it.level());
            let mut breakdown = nmoi_core::budget::breakdown_of_units(&units, estimator.as_ref());

            // 库里没有的那几行，只认调用方给的数。`Some(0)` 也算一条：
            // "注入了但因为开关关着是 0"和"没注入"在面板上不是一回事。
            let mut add = |segment: Segment, tokens: Option<u64>| {
                if let Some(tokens) = tokens {
                    breakdown.add_segment(segment, tokens, 1);
                }
            };
            add(Segment::SystemPrompt, extra.system_prompt);
            add(Segment::Skills, extra.skills);
            add(Segment::WorldBook, extra.world_book);
            add(Segment::Workspace, extra.workspace);
            add(Segment::Draft, extra.draft);
            for def in &extra.tool_definitions {
                breakdown.add_tool(&def.name, def.tokens);
            }

            let rows: Vec<Value> = breakdown
                .rows()
                .iter()
                .map(|row| {
                    json!({
                        "key": row.segment.as_str(),
                        "label": row.segment.label(),
                        "tokens": row.tokens,
                        "items": row.items,
                        "share": breakdown.share(row.segment),
                    })
                })
                .collect();
            Ok(json!({
                "session": session_id.to_string(),
                "model": model.as_deref(),
                "level": level.as_str(),
                "estimated": true,
                "total": breakdown.total(),
                "rows": rows,
            }))
        })
    })
}

/// 备份到 `dest`，验证通过才发布（D48：`.tmp` → 验证 → rename）。
#[no_mangle]
pub extern "C" fn nmoi_storage_backup_json(handle: u64, dest: *const c_char) -> *mut c_char {
    run_json(|| {
        let dest = read_str(dest, "dest")?;
        with_storage(handle, |storage| {
            let report = storage.backup(dest).map_err(storage_error)?;
            Ok(json!({
                "path": report.path.display().to_string(),
                "verified": report.verified,
                "problems": report.problems,
            }))
        })
    })
}

/// 只读诊断一个库（不动主文件），给"备份坏了先看看"用。
#[no_mangle]
pub extern "C" fn nmoi_storage_diagnose_json(path: *const c_char) -> *mut c_char {
    run_json(|| {
        let path = read_str(path, "path")?;
        let report = Storage::diagnose_readonly(path).map_err(storage_error)?;
        Ok(serde_json::to_value(report).unwrap_or(Value::Null))
    })
}
