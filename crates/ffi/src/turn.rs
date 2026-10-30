//! turn 面：跑一整轮对话（P6.1 F2，D58–D60）。
//!
//! 一次调用做完整条链：用户消息落库 → 模型（可能要调几轮工具）→ 工具对成对落库 →
//! 正文落库 → 算压缩判据。**同步阻塞**，调用方自己在工作线程上调。
//!
//! # 入参是一个 JSON 对象
//!
//! 字段多，摊成十个标量参数之后调用方会开始记位置（然后就记错）。形状见
//! `nmoi.h` 与 `docs/PLAN.md` §3.11 的 F2 小节；解析失败会指明**哪个字段**。
//!
//! # 这条边界上故意不做的事（D59）
//!
//! - **不注入系统提示词**：系统提示是会话的一部分，要能看见、能改、能被压缩带走。
//!   调用方先 `nmoi_storage_append_text_json(role="system")` 落库。
//! - **不发思考档位**：D37 要求"关闭必须显式"，这一层不知道模型支持哪一档，猜一个
//!   等于替用户做决定。等 P3.2 的角色配置把档位接上。
//! - **`api_key` 只进不出**：返回体、错误信息里都没有它。
//!
//! # 取消（D58）
//!
//! `nmoi_turn_cancel_json(session)` 置一个标志，`run_turn` 在每次模型调用之前、
//! 每个工具调用之前看它一眼。所以：正在飞的那次往返不会被打断（最坏等一次往返），
//! 已经执行完的工具照常落库，这一轮标 `cancelled` + 库里那条消息带 `incomplete`。
//!
//! 取消**只对正在跑的轮次生效**：没在跑就是空操作。不做"预存一个取消请求等着下一轮"——
//! UI 上误触一次会静默吃掉后面某一轮，而用户看到的是"我发了消息它没反应"。

use std::collections::BTreeMap;
use std::ffi::c_char;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use nmoi_core::agent::{Cancellation, TurnConfig};
use nmoi_core::budget::{Budget, CompressAction, CompressionTrigger, TriggerPolicy};
use nmoi_core::ids::SessionId;
use nmoi_core::message::Role;
use nmoi_core::policy::{AssistantPolicy, WorkspaceBinding};
use nmoi_core::provider::{ProviderConfig, ProviderKind};
use nmoi_core::runtime::{ChatClient, ChatRequest};
use nmoi_core::session::{run_session_turn, SessionConfig, SessionError, SessionTurn};
use nmoi_core::shell::EnvirSpec;
use nmoi_core::tools::ToolRegistry;
use nmoi_core::transport::HttpsTransport;
use nmoi_core::vars::SubAgentEntry;
use nmoi_core::workspace::Workspace;
use nmoi_storage::Storage;
use serde_json::{json, Value};
use std::path::PathBuf;

use crate::handles::{storage_path, with_workspace};
use crate::json::{read_str, run_json, FfiError, FfiResult};

/// 压缩触发线（D90：0.75 / 0.92）。**不在这儿抄数字**——直接用 core 的默认值，
/// 抄一份的结果是改了默认值之后 FFI 那条路还按旧数走。
fn trigger() -> TriggerPolicy {
    TriggerPolicy::default()
}

// ---------------------------------------------------------------------------
// 取消登记表：按会话 id 存一个可被别的线程置位的标志
// ---------------------------------------------------------------------------

fn registry() -> &'static Mutex<BTreeMap<String, Arc<AtomicBool>>> {
    static TABLE: OnceLock<Mutex<BTreeMap<String, Arc<AtomicBool>>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(BTreeMap::new()))
}

fn registry_lock() -> MutexGuard<'static, BTreeMap<String, Arc<AtomicBool>>> {
    registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[derive(Debug)]
struct CancelFlag(Arc<AtomicBool>);

impl Cancellation for CancelFlag {
    fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

/// 开跑前登记。同一会话同时只允许一轮——`busy` 那一层也挡，但这里是更早、更准的一道：
/// 两个 turn 抢同一个 flag 的话，取消会取消到别人头上。
#[derive(Debug)]
struct Registration {
    session: String,
}

impl Registration {
    fn new(session: &str) -> FfiResult<(Self, CancelFlag)> {
        let mut table = registry_lock();
        if table.contains_key(session) {
            return Err(FfiError::new(
                "busy",
                format!("会话 {session} 已经有一轮在跑了"),
            ));
        }
        let flag = Arc::new(AtomicBool::new(false));
        table.insert(session.to_string(), flag.clone());
        Ok((
            Registration {
                session: session.to_string(),
            },
            CancelFlag(flag),
        ))
    }
}

impl Drop for Registration {
    fn drop(&mut self) {
        registry_lock().remove(&self.session);
    }
}

// ---------------------------------------------------------------------------
// 配置解析：每个错都指明字段
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct TurnInput {
    storage: u64,
    session: SessionId,
    workspace: Option<u64>,
    read_only: bool,
    provider_id: String,
    provider_kind: ProviderKind,
    base_url: String,
    api_key: Option<String>,
    model: String,
    user_text: String,
    now_ms: i64,
    max_rounds: u32,
    max_tokens: Option<u32>,
    temperature: Option<f32>,
    budget: Option<Budget>,
    avg_per_turn: Option<u64>,
    /// 原生命令（`System-Shell`）这一轮开不开。**默认关**：它是这个 App 能跑的最危险的一把。
    native_shell: bool,
    /// 思考强度：`auto`（不注字段）/ `off` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`。
    thinking: Option<String>,
    /// 隔离环境的坐标（D172）。给了这一块 = 这一轮可以用 `WorkSpace-EnvirShell`。
    /// 五个路径全是**宿主机**上的绝对路径，由调用方（Kotlin）现取现传。
    envir: Option<EnvirSpec>,
    /// 这一轮的系统提示词。**已经在库里存过的那种不算**——这条是刚组装出来的，
    /// 所以占位符替换在这里做（D176）。
    system: Option<String>,
    /// 可被调用的子助手名单（D176）。空 = 没有，占位符替换成一句人话。
    sub_agents: Vec<SubAgentEntry>,
    /// 随包的预设原文（D178 / 0.14t）。平台侧从 APK 的 `assets/presets/` 读出来。
    /// 空 = 这一轮不注入任何预设（老客户端、或者还没接线）。
    presets: Vec<nmoi_core::preset::AssetPreset>,
}

/// 解析 `envir`（D172）。**五个字段缺一不可**：少一个就是一条拼不起来的命令行，
/// 与其让它在 proot 那边变成一个看不懂的报错，不如在这里点名。
fn parse_envir(root: &Value) -> FfiResult<Option<EnvirSpec>> {
    let Some(value) = field(root, "envir") else {
        return Ok(None);
    };
    if !value.is_object() {
        return Err(bad(
            "envir",
            "必须是对象（proot / loader / lib_dir / rootfs / tmp）",
        ));
    }
    let mut paths = Vec::with_capacity(5);
    for name in ["proot", "loader", "lib_dir", "rootfs", "tmp"] {
        paths.push(PathBuf::from(envir_path(value, name)?));
    }
    // `resolv` 是**可选**的（D173）：设备当前没有 DNS 时调用方就不给，
    // 这时容器照跑，只是解析不出域名。给了就得是个非空字符串——空串会让 proot
    // 收到 `-b :/etc/resolv.conf`，报错信息里看不出是谁的问题。
    let resolv = match value.get("resolv") {
        None | Some(Value::Null) => None,
        Some(_) => Some(PathBuf::from(envir_path(value, "resolv")?)),
    };
    Ok(Some(EnvirSpec {
        proot: paths[0].clone(),
        loader: paths[1].clone(),
        lib_dir: paths[2].clone(),
        rootfs: paths[3].clone(),
        tmp: paths[4].clone(),
        resolv,
    }))
}

/// `envir` 里的一个路径。**报错带上前缀**（`envir.loader`）：
/// 只说 `loader` 的话，日志里看不出是顶层字段还是这一块里的。
fn envir_path(parent: &Value, name: &str) -> FfiResult<String> {
    match parent.get(name) {
        Some(Value::String(text)) if !text.trim().is_empty() => Ok(text.clone()),
        Some(Value::String(_)) => Err(bad(&format!("envir.{name}"), "不能是空字符串")),
        Some(_) => Err(bad(&format!("envir.{name}"), "必须是字符串")),
        None => Err(bad(&format!("envir.{name}"), "是必填的")),
    }
}

fn bad(field: &str, expected: &str) -> FfiError {
    FfiError::new("bad_argument", format!("配置字段 `{field}` {expected}"))
}

fn field<'a>(root: &'a Value, key: &str) -> Option<&'a Value> {
    root.get(key).filter(|value| !value.is_null())
}

fn required_str(root: &Value, field_name: &str) -> FfiResult<String> {
    match field(root, field_name) {
        Some(Value::String(text)) => Ok(text.clone()),
        Some(_) => Err(bad(field_name, "必须是字符串")),
        None => Err(bad(field_name, "是必填的")),
    }
}

fn optional_str(root: &Value, field_name: &str) -> FfiResult<Option<String>> {
    match field(root, field_name) {
        Some(Value::String(text)) => Ok(Some(text.clone())),
        Some(_) => Err(bad(field_name, "必须是字符串")),
        None => Ok(None),
    }
}

fn optional_u64(root: &Value, field_name: &str) -> FfiResult<Option<u64>> {
    match field(root, field_name) {
        Some(Value::Number(number)) => number
            .as_u64()
            .map(Some)
            .ok_or_else(|| bad(field_name, "必须是非负整数")),
        Some(_) => Err(bad(field_name, "必须是非负整数")),
        None => Ok(None),
    }
}

fn optional_i64(root: &Value, field_name: &str) -> FfiResult<Option<i64>> {
    match field(root, field_name) {
        Some(Value::Number(number)) => number
            .as_i64()
            .map(Some)
            .ok_or_else(|| bad(field_name, "必须是整数")),
        Some(_) => Err(bad(field_name, "必须是整数")),
        None => Ok(None),
    }
}

fn optional_f32(root: &Value, field_name: &str) -> FfiResult<Option<f32>> {
    match field(root, field_name) {
        Some(Value::Number(number)) => number
            .as_f64()
            .map(|value| Some(value as f32))
            .ok_or_else(|| bad(field_name, "必须是数字")),
        Some(_) => Err(bad(field_name, "必须是数字")),
        None => Ok(None),
    }
}

fn optional_bool(root: &Value, field_name: &str) -> FfiResult<Option<bool>> {
    match field(root, field_name) {
        Some(Value::Bool(value)) => Ok(Some(*value)),
        Some(_) => Err(bad(field_name, "必须是布尔值")),
        None => Ok(None),
    }
}

fn parse_provider_kind(text: &str) -> FfiResult<ProviderKind> {
    match text {
        "openai_compatible" => Ok(ProviderKind::OpenAiCompatible),
        "openai_responses" => Ok(ProviderKind::OpenAiResponses),
        "anthropic" => Ok(ProviderKind::Anthropic),
        "gemini" => Ok(ProviderKind::Gemini),
        other => Err(bad(
            "provider.kind",
            &format!(
                "只能是 openai_compatible / openai_responses / anthropic / gemini，收到 `{other}`"
            ),
        )),
    }
}

fn parse_config(text: &str) -> FfiResult<TurnInput> {
    let root: Value = serde_json::from_str(text)
        .map_err(|error| FfiError::new("bad_argument", format!("配置不是合法 JSON：{error}")))?;
    if !root.is_object() {
        return Err(bad("config", "必须是一个 JSON 对象"));
    }

    let storage = optional_u64(&root, "storage")?.ok_or_else(|| bad("storage", "是必填的"))?;
    let session_text = required_str(&root, "session")?;
    let session = session_text
        .parse::<SessionId>()
        .map_err(|error| FfiError::new("id", format!("session 不合法：{error}")))?;

    let provider = field(&root, "provider").ok_or_else(|| bad("provider", "是必填的"))?;
    if !provider.is_object() {
        return Err(bad("provider", "必须是对象"));
    }
    let provider_id = optional_str(provider, "id")?.unwrap_or_else(|| "ffi".to_string());
    let kind_text = required_str(provider, "kind")?;
    let provider_kind = parse_provider_kind(&kind_text)?;
    let base_url = required_str(provider, "base_url")?;
    let api_key = optional_str(provider, "api_key")?;

    let model = required_str(&root, "model")?;
    let user_text = required_str(&root, "user_text")?;
    if user_text.trim().is_empty() {
        return Err(bad("user_text", "不能是空串"));
    }
    let now_ms = optional_i64(&root, "now_ms")?.unwrap_or(0);

    let max_rounds = optional_u64(&root, "max_rounds")?.unwrap_or(12);
    if max_rounds == 0 {
        return Err(bad("max_rounds", "至少是 1"));
    }

    let budget = match field(&root, "budget") {
        None => None,
        Some(value) => {
            if !value.is_object() {
                return Err(bad("budget", "必须是对象"));
            }
            let window =
                optional_u64(value, "window")?.ok_or_else(|| bad("budget.window", "是必填的"))?;
            let reserve = optional_u64(value, "reserve_output")?.unwrap_or(0);
            Some(Budget::new(window, window, reserve))
        }
    };

    // 系统提示词与子助手名单（D176）。**占位符替换放在这里解析完就做**：
    // 组装请求时只剩下"拿去用"，不再有一处能改它的地方。
    let system = optional_str(&root, "system")?.filter(|text| !text.trim().is_empty());
    let sub_agents = parse_sub_agents(&root)?;
    // 预设有自己那一份解析（`preset::parse_assets`）：形状不对要能点名是哪一份。
    let presets = match optional_str(&root, "presets")? {
        Some(text) if !text.trim().is_empty() => crate::preset::parse_assets(&text)?,
        _ => Vec::new(),
    };

    Ok(TurnInput {
        storage,
        session,
        workspace: optional_u64(&root, "workspace")?.filter(|id| *id != 0),
        read_only: optional_bool(&root, "read_only_workspace")?.unwrap_or(false),
        provider_id,
        provider_kind,
        base_url,
        api_key,
        model,
        user_text,
        now_ms,
        max_rounds: max_rounds as u32,
        max_tokens: optional_u64(&root, "max_tokens")?.map(|value| value as u32),
        temperature: optional_f32(&root, "temperature")?,
        budget,
        avg_per_turn: optional_u64(&root, "avg_per_turn")?,
        native_shell: optional_bool(&root, "native_shell")?.unwrap_or(false),
        thinking: optional_str(&root, "thinking")?.filter(|text| !text.trim().is_empty()),
        envir: parse_envir(&root)?,
        system,
        sub_agents,
        presets,
    })
}

/// 解析 `sub_agents`（D176）：`[{"id":"test_agent","description":"test"}, …]`。
///
/// 这里只做"这一轮能不能安全地用"这一档检查：形状对、不重名。**"和谁撞了"那种
/// 面向人的报错在保存时做**（`vars::validate` 带 owner），因为那一刻才知道是哪个助手。
/// 这一层没有 owner，只能点名 id 本身。
fn parse_sub_agents(root: &Value) -> FfiResult<Vec<SubAgentEntry>> {
    let Some(value) = field(root, "sub_agents") else {
        return Ok(Vec::new());
    };
    let Some(items) = value.as_array() else {
        return Err(bad("sub_agents", "必须是数组"));
    };
    let mut out: Vec<SubAgentEntry> = Vec::with_capacity(items.len());
    for (index, item) in items.iter().enumerate() {
        if !item.is_object() {
            return Err(bad(&format!("sub_agents[{index}]"), "必须是对象"));
        }
        let id = required_str(item, "id")?;
        if !nmoi_core::vars::is_valid_id(&id) {
            return Err(FfiError::new(
                "bad_argument",
                format!(
                    "sub_agents[{index}].id「{id}」不合法：只能用小写字母、数字和下划线，1–32 个字符"
                ),
            ));
        }
        if out.iter().any(|entry| entry.id == id) {
            return Err(FfiError::new(
                "bad_argument",
                format!("sub_agents 里 id「{id}」重复了：主 AGENT 拿到重名会随机调一个"),
            ));
        }
        out.push(SubAgentEntry {
            id,
            description: required_str(item, "description")?,
        });
    }
    Ok(out)
}

/// 思考强度（D162）。`auto` = **不注任何字段**，让模型自己决定。
///
/// 字段族由 `default_thinking_field` 按 (provider, 模型风味) 选：OpenAI 系是 `reasoning_effort`，
/// Anthropic / Gemini 是预算式字段。所以这里只填 `effort`，不自己拼 JSON——
/// 拼错一个键，模型会当没看见，用户看到的是"我选了 max 它没反应"。
fn apply_thinking(request: ChatRequest, kind: ProviderKind, level: &str) -> FfiResult<ChatRequest> {
    use nmoi_core::config::{default_thinking_field, ModelFlavor, ThinkingContext};
    use nmoi_core::provider::{ThinkingEffort, ThinkingSetting};

    let level = level.trim();
    if level.eq_ignore_ascii_case("auto") {
        return Ok(request);
    }
    let setting = if level.eq_ignore_ascii_case("off") {
        ThinkingSetting::disabled()
    } else {
        let effort = match level.to_ascii_lowercase().as_str() {
            "minimal" => ThinkingEffort::Minimal,
            "low" => ThinkingEffort::Low,
            "medium" => ThinkingEffort::Medium,
            "high" => ThinkingEffort::High,
            "xhigh" => ThinkingEffort::XHigh,
            "max" => ThinkingEffort::Max,
            other => {
                return Err(FfiError::new(
                    "bad_argument",
                    format!(
                        "thinking 只能是 auto / off / minimal / low / medium / high / xhigh / max，收到 `{other}`"
                    ),
                ))
            }
        };
        ThinkingSetting {
            field: default_thinking_field(&ThinkingContext::new(kind, ModelFlavor::Other)),
            effort: Some(effort),
            ..ThinkingSetting::default()
        }
    };
    Ok(request.thinking(setting))
}

fn policy_for(input: &TurnInput) -> AssistantPolicy {
    // 容器要有 rootfs 才能开（D172）：没有坐标就只是"界面看着能开"。
    let container = input.envir.is_some();
    match input.workspace {
        // 没有工作区就没有可跑的目录：连带原生命令也开不了（`check` 会回 no_workspace）。
        None => AssistantPolicy::locked_down(),
        Some(_) if input.read_only => AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_only("ffi-workspace"))
            .enable_native_shell(input.native_shell)
            .enable_container(container),
        Some(_) => AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ffi-workspace"))
            .enable_native_shell(input.native_shell)
            .enable_container(container),
    }
}

fn action_json(action: CompressAction) -> &'static str {
    match action {
        CompressAction::Proceed => "proceed",
        CompressAction::ProceedThenCompact => "proceed_then_compact",
        CompressAction::CompactThenProceed => "compact_then_proceed",
        CompressAction::TruncateThenCompact => "truncate_then_compact",
        CompressAction::CompactAfterPersist => "compact_after_persist",
    }
}

fn trigger_json(trigger: CompressionTrigger) -> Value {
    match trigger {
        CompressionTrigger::None => json!({ "kind": "none" }),
        CompressionTrigger::Soft {
            remaining_turns,
            payback_turns,
        } => json!({
            "kind": "soft",
            "remaining_turns": remaining_turns,
            "payback_turns": payback_turns,
        }),
        CompressionTrigger::Suggest { ratio } => json!({ "kind": "suggest", "ratio": ratio }),
        CompressionTrigger::Absolute { ratio } => json!({ "kind": "absolute", "ratio": ratio }),
    }
}

fn turn_json(turn: &SessionTurn, presets: &Value) -> Value {
    let tool_calls: Vec<Value> = turn
        .report
        .tool_calls
        .iter()
        .map(|record| {
            json!({
                "name": record.name,
                "call_id": record.call_id,
                "arguments": record.arguments,
                "ok": record.ok,
                "outcome": record.outcome,
            })
        })
        .collect();

    json!({
        "session": turn.user.id.session().to_string(),
        "user": {
            "id": turn.user.id.to_string(),
            "seq": turn.user.id.seq(),
            "tokens": turn.user.tokens,
        },
        "assistant": turn.assistant.as_ref().map(|message| json!({
            "id": message.id.to_string(),
            "seq": message.id.seq(),
            "content": message.content,
            "incomplete": message.incomplete,
            "tokens": message.tokens,
            // 思考也带出来（D175）：界面不必为了"这一轮有没有思考"再读一次库。
            "reasoning": message.reasoning,
        })),
        "units": turn.units.iter().map(|unit| unit.to_string()).collect::<Vec<_>>(),
        // 模型改消息（D177 / 0.14y）：已经落库的那几块。界面靠它刷新消息卡、
        // 并在提示行说一句"改了 N 处"——用户不必等下一轮才看见变化。
        "edits": turn.edits.iter().map(|edit| json!({
            "target": edit.target,
            "block": edit.block,
            "ok": edit.ok,
            "message": edit.message,
        })).collect::<Vec<_>>(),
        "report": {
            "text": turn.report.text,
            "rounds": turn.report.rounds,
            "finished": turn.report.finished,
            "cancelled": turn.report.cancelled,
            "truncated": turn.report.truncated,
            "ok_count": turn.report.ok_count(),
            "failed_count": turn.report.failed_count(),
            "finish": turn.report.finish.as_ref().map(|finish| finish.as_str().to_string()),
            "tool_calls": tool_calls,
        },
        "compression": turn.compression.map(|signal| json!({
            "trigger": trigger_json(signal.trigger),
            "action": action_json(signal.action),
            "used_tokens": signal.used_tokens,
            "ratio": signal.ratio,
        })),
        // 预设（D178）：这一轮真的注入了哪几条。用户要的位置是**请求日志**，
        // 所以这份数据只为日志而给——它不进消息区，也不进库。
        "presets": presets,
    })
}

/// 跑一轮会话。**同步阻塞**——在 UI 的工作线程上调，别在主线程。
///
/// 返回体里有 `report.cancelled` / `report.finished`：
/// `finished=false` + `cancelled=false` 是"轮数用尽，模型还没收尾"，
/// 两者都 `false` 之外的情况 UI 该说的话不一样，所以分开给。
#[no_mangle]
pub extern "C" fn nmoi_turn_run_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let input = parse_config(text)?;

        // 顺序有讲究：先登记取消（可能因为"同一会话已有一轮"直接失败），
        // 再取出 storage——反过来的话失败路径还得把 storage 放回去。
        let (_registration, cancel) = Registration::new(&input.session.to_string())?;
        // 这一轮开自己的连接（D89）：句柄留在表里，别的会话照常刷列表、读文件。
        // 同一个会话的第二轮仍然会被上面的 Registration 挡成 `busy`。
        let mut storage = Storage::attach(storage_path(input.storage)?)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;

        let workspace: Option<Workspace> = match input.workspace {
            None => None,
            Some(id) => Some(with_workspace(id, |workspace| Ok(workspace.clone()))?),
        };
        let policy = policy_for(&input);
        let registry = ToolRegistry::workspace_defaults();

        let provider =
            ProviderConfig::new(&input.provider_id, input.provider_kind, &input.base_url);
        // 系统提示词里的占位符在这里替换（D176）：名单是**这一轮**的，
        // 所以不能在建会话时替换一次了事。
        let system = input.system.as_deref().map(|text| {
            nmoi_core::vars::replace(
                text,
                &nmoi_core::vars::VarTable::new(input.sub_agents.clone()),
            )
        });
        // 预设（D178 / 0.14t）：按**这一轮真的声明出去的工具**过滤。
        // 声明表用 `specs_for(policy)` 现算——与 `run_turn` 里没冻结时的那一条同源，
        // 所以"预设里提到 read_file、这一轮却没声明它"这件事不可能发生。
        let declared: Vec<String> = registry
            .specs_for(&policy)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        let injected = crate::preset::inject_json(&storage, &input.presets, &declared);
        let preset_text = injected
            .get("text")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        // 接在**替换完占位符之后**：预设正文里可能会出现 `<var>` 字样，
        // 那不该被当成占位符再替换一次（它属于用户自己写的内容）。
        let system = if preset_text.is_empty() {
            system
        } else {
            Some(match system {
                Some(base) => format!("{base}\n\n{preset_text}"),
                None => preset_text,
            })
        };
        let mut request = match system.as_deref() {
            Some(system) => {
                ChatRequest::from_turns(provider, &input.model, &[(Role::System, system)])
            }
            None => ChatRequest::new(provider, &input.model),
        };
        if let Some(key) = input.api_key.as_deref() {
            if !key.trim().is_empty() {
                request = request.api_key(key);
            }
        }
        if let Some(tokens) = input.max_tokens {
            request = request.max_tokens(tokens);
        }
        if let Some(temperature) = input.temperature {
            request = request.temperature(temperature);
        }
        if let Some(level) = input.thinking.as_deref() {
            request = apply_thinking(request, input.provider_kind, level)?;
        }

        // 校准按模型取：换模型就是另一套口径（D95 的键里有模型）。
        let calibrations = storage
            .calibrations(&input.model, input.now_ms)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;

        let session_config = SessionConfig {
            calibrations: &calibrations,
            // 自动重试还没接到设置面（D70 的 UI 还不存在），所以 FFI 这条路先不重发：
            // 一个用户关不掉的开关，比没有这个开关更糟。
            retry: None,
            suppress_compression: false,
            turn: TurnConfig {
                max_rounds: input.max_rounds,
                max_tokens: None,
                truncate_at: None,
            },
            budget: input.budget.as_ref(),
            trigger: &trigger(),
            avg_per_turn: input.avg_per_turn,
            cancel: &cancel,
            // FFI 这条路径还没有设置面，冻结等 `nmoi_turn_*` 接上设置之后再做。
            plan: None,
            envir: input.envir.as_ref(),
        };

        let client = ChatClient::new(HttpsTransport::default());
        // 会话记住这一轮用的模型（D101）：上下文面板靠它选词表、按模型取校准。
        // 写失败不让这一轮失败——为了一个显示用的列把用户刚说的话丢掉不值（同 D98 的校准写回）。
        let _ = storage.set_conversation_model(input.session, &input.model);
        let turn = run_session_turn(
            &client,
            &request,
            &mut storage,
            input.session,
            &input.user_text,
            &registry,
            &policy,
            workspace.as_ref(),
            &session_config,
            input.now_ms,
        )
        .map_err(|error| match error {
            // 过绝对线不是"失败"，是"先压再发"（D90）。给一个能分支的 code，
            // 让 UI 不至于要在错误文案里认字符串。
            SessionError::CompactionRequired { used, usable } => FfiError::new(
                "compaction_required",
                format!("上下文已达绝对线（{used}/{usable} token），先压缩再发"),
            ),
            other => FfiError::new("turn", other.to_string()),
        })?;

        // L2 校准：这一轮的真实用量反推出来的因子，落库给下一轮用（D95）。
        // 写不进去也不该让已经跑完的这一轮失败——用户的话已经落库了。
        if let Some(calibration) = turn.calibration.as_ref() {
            storage
                .put_calibration(calibration, input.now_ms)
                .map_err(|error| FfiError::new("storage", error.to_string()))?;
        }

        // 连接随作用域结束自动关；句柄一直在表里，不用还。
        drop(storage);
        Ok(turn_json(&turn, &injected))
    })
}

/// 取消正在跑的那一轮。
///
/// 没在跑就是空操作：`{"cancelled":false,"running":false}`。
/// `cancelled=true` 只表示"标志已经置上"，不代表模型已经停下——见 D58 那条代价。
#[no_mangle]
pub extern "C" fn nmoi_turn_cancel_json(session: *const c_char) -> *mut c_char {
    run_json(|| {
        let session = read_str(session, "session")?;
        let table = registry_lock();
        match table.get(session) {
            Some(flag) => {
                flag.store(true, Ordering::SeqCst);
                Ok(json!({ "cancelled": true, "running": true }))
            }
            None => Ok(json!({ "cancelled": false, "running": false })),
        }
    })
}

/// 这个会话现在有没有一轮在跑。
///
/// 出参：`{"session":"…","running":true,"cancelled":false}`。
///
/// 界面上的「正在生成…」不能只活在页面 state 里（0.15c）：切到别的会话再切回来，
/// 那一轮其实还在跑，而页面已经重组过一次。所以界面记一份、这里问一份，
/// 两边不一致时**以这里为准**——表是随 `Registration` 的 Drop 一起清掉的。
#[no_mangle]
pub extern "C" fn nmoi_turn_busy_json(session: *const c_char) -> *mut c_char {
    run_json(|| {
        let session = read_str(session, "session")?;
        let table = registry_lock();
        let running = table.contains_key(session);
        let cancelled = table
            .get(session)
            .map(|flag| flag.load(Ordering::SeqCst))
            .unwrap_or(false);
        Ok(json!({ "session": session, "running": running, "cancelled": cancelled }))
    })
}

/// 一次性补全（D158）：**不发进会话历史，也不落库**。
///
/// 增强编辑要的是"拿一段文字回来"。用 `turn_run` 做这件事，会在历史里留下
/// "用户让我改一条消息"和"模型回了新正文"这两条假对话——下一次压缩、下一次上下文
/// 都会把它们当成真的。
///
/// 入参：`{provider{id,kind,base_url,api_key?}, model, prompt, system?, max_tokens?, temperature?}`
/// 出参：`{text, reasoning, finish, usage{prompt, completion}}`
///
/// 思考内容单独给（`reasoning`），不混进 `text`：围栏解析在 `text` 上做，
/// 掺进去的思考会被当成正文的一部分。
#[no_mangle]
pub extern "C" fn nmoi_complete_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let root: Value = serde_json::from_str(text).map_err(|error| {
            FfiError::new("bad_argument", format!("配置不是合法 JSON：{error}"))
        })?;
        if !root.is_object() {
            return Err(bad("config", "必须是一个 JSON 对象"));
        }
        let provider = field(&root, "provider").ok_or_else(|| bad("provider", "是必填的"))?;
        if !provider.is_object() {
            return Err(bad("provider", "必须是对象"));
        }
        let provider_id = optional_str(provider, "id")?.unwrap_or_else(|| "ffi".to_string());
        let kind = parse_provider_kind(&required_str(provider, "kind")?)?;
        let base_url = required_str(provider, "base_url")?;
        let api_key = optional_str(provider, "api_key")?;
        let model = required_str(&root, "model")?;
        let prompt = required_str(&root, "prompt")?;
        if prompt.trim().is_empty() {
            return Err(bad("prompt", "不能是空串"));
        }
        let system = optional_str(&root, "system")?;
        let max_tokens = optional_u64(&root, "max_tokens")?.map(|value| value as u32);
        let temperature = optional_f32(&root, "temperature")?;

        let mut turns: Vec<(Role, &str)> = Vec::with_capacity(2);
        if let Some(system) = system.as_deref() {
            turns.push((Role::System, system));
        }
        turns.push((Role::User, prompt.as_str()));

        let mut request = ChatRequest::from_turns(
            ProviderConfig::new(provider_id, kind, base_url),
            model,
            &turns,
        )
        .without_stream();
        if let Some(key) = api_key.as_deref() {
            if !key.trim().is_empty() {
                request = request.api_key(key);
            }
        }
        if let Some(tokens) = max_tokens {
            request = request.max_tokens(tokens);
        }
        if let Some(temperature) = temperature {
            request = request.temperature(temperature);
        }

        let client = ChatClient::new(HttpsTransport::default());
        let done = client
            .complete(&request)
            .map_err(|error| FfiError::new("complete", error.to_string()))?;
        Ok(json!({
            "text": done.text,
            "reasoning": done.reasoning,
            "finish": done.finish.as_str(),
            "usage": {
                "prompt": done.usage.prompt_tokens,
                "completion": done.usage.completion_tokens,
            },
        }))
    })
}

/// 校验一份子助手名单（D176）：`[{owner, id, description}, …]` → `{ok, problems:[{kind, message}]}`。
///
/// 为什么单独给一条而不是塞进 `turn_run` 的解析里：**保存时就要拦住重名**，
/// 而保存那一刻没有请求、也没有工作区，只需要一条纯检查。
/// `owner` 是填了这个 ID 的助手 id，用来把"和谁撞了"说出来——
/// 只说"id 重复"的话，用户得自己一条条翻。
#[no_mangle]
pub extern "C" fn nmoi_vars_validate_json(list: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(list, "list")?;
        let value: Value = serde_json::from_str(text).map_err(|error| {
            FfiError::new("bad_argument", format!("名单不是合法 JSON：{error}"))
        })?;
        let Some(items) = value.as_array() else {
            return Err(FfiError::new("bad_argument", "名单必须是数组"));
        };
        let mut entries: Vec<(String, nmoi_core::vars::SubAgentEntry)> =
            Vec::with_capacity(items.len());
        for (index, item) in items.iter().enumerate() {
            if !item.is_object() {
                return Err(bad(&format!("list[{index}]"), "必须是对象"));
            }
            entries.push((
                required_str(item, "owner")?,
                nmoi_core::vars::SubAgentEntry {
                    id: required_str(item, "id")?,
                    description: required_str(item, "description")?,
                },
            ));
        }
        let problems = nmoi_core::vars::validate(&entries);
        Ok(json!({
            "ok": problems.is_empty(),
            "problems": problems
                .iter()
                .map(|problem| json!({
                    "kind": match problem {
                        nmoi_core::vars::Problem::BadId(_) => "bad_id",
                        nmoi_core::vars::Problem::Duplicate { .. } => "duplicate",
                        nmoi_core::vars::Problem::EmptyDescription(_) => "empty_description",
                    },
                    "message": problem.describe(),
                }))
                .collect::<Vec<Value>>(),
        }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::{c_char, CStr, CString};
    use std::ptr;

    /// 取回 `*mut c_char`：断言非 null、解析成 Value、释放。
    fn read_json(pointer: *mut c_char) -> Value {
        assert!(!pointer.is_null(), "JSON 函数不允许返回 null");
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .expect("JSON 是 UTF-8")
            .to_string();
        unsafe { crate::nmoi_string_free(pointer) };
        serde_json::from_str(&text).expect("返回值必须是合法 JSON")
    }

    fn parse(text: &str) -> FfiResult<TurnInput> {
        parse_config(text)
    }

    /// `envir` 要五个路径，缺一个就点名（D172）。这条不是洁癖：
    /// 少一个字段拼出来的命令行会让 proot 在真机上给一句看不懂的错。
    #[test]
    fn envir_needs_all_five_paths() {
        let full = json!({"envir": {
            "proot": "/a/proot", "loader": "/a/loader", "lib_dir": "/a/lib",
            "rootfs": "/a/rootfs", "tmp": "/a/tmp"
        }});
        let spec = parse_envir(&full)
            .expect("五个都在应当解析出来")
            .expect("Some");
        assert_eq!(spec.proot.to_string_lossy(), "/a/proot");
        assert_eq!(spec.rootfs.to_string_lossy(), "/a/rootfs");

        assert!(parse_envir(&json!({})).expect("没有就不管").is_none());

        let missing = json!({"envir": {"proot": "/a/proot"}});
        let error = parse_envir(&missing).expect_err("缺字段要报错");
        assert!(
            error.message.contains("envir.loader"),
            "报错要点名缺的是哪个：{}",
            error.message
        );
        assert!(spec.resolv.is_none(), "没写 resolv 就是没有（D173）");
    }

    /// `resolv` 可选，但给了就不能是空串或非字符串（D173）：
    /// 空串会让 proot 收到 `-b :/etc/resolv.conf`，那条报错里看不出是谁的问题。
    #[test]
    fn resolv_is_optional_but_not_garbage() {
        let base = json!({
            "proot": "/a/proot", "loader": "/a/loader", "lib_dir": "/a/lib",
            "rootfs": "/a/rootfs", "tmp": "/a/tmp",
            "resolv": "/a/envir/resolv.conf"
        });
        let spec = parse_envir(&json!({ "envir": base }))
            .expect("给了就解析")
            .expect("Some");
        assert_eq!(
            spec.resolv
                .as_ref()
                .map(|p| p.to_string_lossy().into_owned()),
            Some("/a/envir/resolv.conf".to_string())
        );

        for broken in [json!(""), json!(42)] {
            let mut value = json!({
                "proot": "/a/proot", "loader": "/a/loader", "lib_dir": "/a/lib",
                "rootfs": "/a/rootfs", "tmp": "/a/tmp"
            });
            value["resolv"] = broken.clone();
            let error = parse_envir(&json!({ "envir": value })).expect_err("要报错");
            assert!(
                error.message.contains("envir.resolv"),
                "报错要点名 envir.resolv：{}",
                error.message
            );
        }
    }

    /// 系统提示词里的名单占位符（D176）：解析完就该能替出来，而且不需要联网。
    #[test]
    fn sub_agents_fill_the_placeholder_in_the_system_prompt() {
        let mut config: Value = serde_json::from_str(&minimal()).unwrap();
        config["system"] = json!("你可以调用这些子助手：\n<var><sub_agent_info></var>");
        config["sub_agents"] = json!([
            {"id": "test_agent", "description": "test"},
            {"id": "test_agent2", "description": "test"}
        ]);
        let input = parse(&config.to_string()).expect("应当解析出来");
        assert_eq!(input.sub_agents.len(), 2);
        let table = nmoi_core::vars::VarTable::new(input.sub_agents.clone());
        let text = nmoi_core::vars::replace(input.system.as_deref().expect("system 在"), &table);
        assert!(
            text.starts_with("你可以调用这些子助手：\n{\n---\nid:test_agent"),
            "{text}"
        );
        assert!(text.contains("id:test_agent2"), "{text}");
        // 没有登记过占位符的文本原样保留（这是绝大多数情况）。
        assert_eq!(nmoi_core::vars::replace("普通一句", &table), "普通一句");
    }

    /// 名单本身的两类错要在解析时就拦：形状不对、重名（重名 = 主 AGENT 会随机调一个）。
    #[test]
    fn bad_sub_agent_entries_are_rejected_at_parse_time() {
        let mut bad_shape: Value = serde_json::from_str(&minimal()).unwrap();
        bad_shape["sub_agents"] = json!([{"id": "Test_Agent", "description": "x"}]);
        let error = parse(&bad_shape.to_string()).expect_err("大写 id 不行");
        assert!(error.message.contains("Test_Agent"), "{}", error.message);

        let mut duplicated: Value = serde_json::from_str(&minimal()).unwrap();
        duplicated["sub_agents"] = json!([
            {"id": "writer", "description": "a"},
            {"id": "writer", "description": "b"}
        ]);
        let error = parse(&duplicated.to_string()).expect_err("重名要报错");
        assert!(error.message.contains("重复"), "{}", error.message);

        let mut not_array: Value = serde_json::from_str(&minimal()).unwrap();
        not_array["sub_agents"] = json!("writer");
        let error = parse(&not_array.to_string()).expect_err("必须是数组");
        assert!(error.message.contains("数组"), "{}", error.message);
    }

    fn minimal() -> String {
        json!({
            "storage": 1,
            "session": "018f0000-0000-7000-8000-000000000000",
            "provider": {"kind": "openai_compatible", "base_url": "http://127.0.0.1:1/v1"},
            "model": "test-model",
            "user_text": "在吗",
        })
        .to_string()
    }

    /// 配置解析的错必须**指明字段**：UI 那边拿到的是一句人话，不是"配置不对"。
    #[test]
    fn missing_fields_name_themselves() {
        let broken = minimal().replace("\"model\":\"test-model\",", "");
        let error = parse(&broken).unwrap_err();
        assert_eq!(error.kind, "bad_argument");
        assert!(error.message.contains("`model`"), "{}", error.message);
    }

    /// `auto` = 一个字段都不注；给了档位 = 真的落到 `thinking.effort` 上。
    #[test]
    fn thinking_levels_land_on_the_request() {
        use nmoi_core::provider::{ProviderConfig, ThinkingEffort};
        let base = || {
            ChatRequest::new(
                ProviderConfig::new("p", ProviderKind::OpenAiCompatible, "http://127.0.0.1:1/v1"),
                "m",
            )
        };

        let auto = apply_thinking(base(), ProviderKind::OpenAiCompatible, "auto").unwrap();
        assert_eq!(auto.thinking.effort, None, "auto 不该注任何字段");

        let high = apply_thinking(base(), ProviderKind::OpenAiCompatible, "high").unwrap();
        assert_eq!(high.thinking.effort, Some(ThinkingEffort::High));

        let off = apply_thinking(base(), ProviderKind::OpenAiCompatible, "off").unwrap();
        assert_eq!(off.thinking.effort, None);

        assert!(apply_thinking(base(), ProviderKind::OpenAiCompatible, "超高").is_err());
    }

    #[test]
    fn unknown_provider_kind_lists_the_options() {
        let broken = minimal().replace("openai_compatible", "openai_but_wrong");
        let error = parse(&broken).unwrap_err();
        assert!(error.message.contains("provider.kind"), "{}", error.message);
        assert!(error.message.contains("anthropic"), "{}", error.message);
    }

    #[test]
    fn empty_user_text_is_rejected_here_not_upstream() {
        let broken = minimal().replace("\"在吗\"", "\"   \"");
        let error = parse(&broken).unwrap_err();
        assert!(error.message.contains("user_text"), "{}", error.message);
    }

    #[test]
    fn budget_needs_a_window() {
        let mut value: Value = serde_json::from_str(&minimal()).unwrap();
        value["budget"] = json!({"reserve_output": 1024});
        let error = parse(&value.to_string()).unwrap_err();
        assert!(error.message.contains("budget.window"), "{}", error.message);
    }

    #[test]
    fn no_budget_means_no_compression_signal() {
        let input = parse(&minimal()).unwrap();
        assert!(input.budget.is_none());
        assert_eq!(input.max_rounds, 12, "默认轮数和 TurnConfig::default 一致");
        assert!(!input.read_only);
    }

    /// 同一会话同时只允许一轮：两个 turn 抢同一个取消标志的话，取消会取消到别人头上。
    #[test]
    fn busy_reports_the_registration_table() {
        let session = CString::new("018f0000-0000-7000-8000-0000000000aa").unwrap();

        // 没跑：running=false。
        let idle = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(idle["running"], json!(false), "{idle}");

        let (registration, _flag) =
            Registration::new("018f0000-0000-7000-8000-0000000000aa").unwrap();
        let running = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(running["running"], json!(true), "{running}");
        assert_eq!(running["cancelled"], json!(false), "{running}");

        // 取消之后仍然是"在跑"，但 cancelled 为真：界面据此把按钮换成"正在停下"。
        let cancelled = read_json(nmoi_turn_cancel_json(session.as_ptr()));
        assert_eq!(cancelled["running"], json!(true), "{cancelled}");
        let stopping = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(stopping["cancelled"], json!(true), "{stopping}");

        // drop 之后回到 false。
        drop(registration);
        let again = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(again["running"], json!(false), "{again}");
    }

    #[test]
    fn a_second_turn_for_the_same_session_is_busy() {
        let (_first, _flag) = Registration::new("session-a").unwrap();
        let error = Registration::new("session-a").unwrap_err();
        assert_eq!(error.kind, "busy");
        // 换个会话不受影响
        let (_other, _) = Registration::new("session-b").unwrap();
        // 第一轮 drop 之后又能开
        drop(_first);
        let (_again, _) = Registration::new("session-a").unwrap();
    }

    #[test]
    fn cancel_while_nothing_runs_is_a_no_op() {
        let session = CString::new("018f0000-0000-7000-8000-0000000000ff").unwrap();
        let pointer = nmoi_turn_cancel_json(session.as_ptr());
        assert!(!pointer.is_null());
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .unwrap()
            .to_string();
        unsafe { crate::nmoi_string_free(pointer) };
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["ok"], json!(true));
        assert_eq!(value["cancelled"], json!(false));
        assert_eq!(value["running"], json!(false));
    }

    #[test]
    fn null_config_is_a_clean_error() {
        let pointer = nmoi_turn_run_json(ptr::null());
        assert!(!pointer.is_null(), "JSON 函数不允许返回 null");
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .unwrap()
            .to_string();
        unsafe { crate::nmoi_string_free(pointer) };
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["error"]["kind"], json!("null_argument"));
    }

    /// 端到端取消：真 socket、真线程、真第二次往返。
    ///
    /// 假服务器第一轮给 `tool_calls`，第二轮**拖 1 秒**再回；主线程在这段窗口里按停止。
    /// 期望：第二轮回来后、执行它的工具之前就停下，所以 `tool_calls` 最多 1 条。
    ///
    /// 时间留得很松（拖 1s / 200ms 后按）：这不是测性能，是测那条检查点真的在。
    #[test]
    fn cancel_stops_the_turn_at_a_round_boundary_over_a_real_socket() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::time::Duration;

        fn read_request(stream: &mut std::net::TcpStream) {
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 1024];
            while !buffer.windows(4).any(|window| window == b"\r\n\r\n") {
                match stream.read(&mut chunk) {
                    Ok(0) => break,
                    Ok(read) => buffer.extend_from_slice(&chunk[..read]),
                    Err(_) => break,
                }
            }
        }

        fn write_body(stream: &mut std::net::TcpStream, body: &str) {
            let response = format!(
                "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        }

        fn tool_call_body() -> String {
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\",\
             \"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\
             \"function\":{\"name\":\"list_dir\",\"arguments\":\"{}\"}}]},\
             \"finish_reason\":\"tool_calls\"}]}"
                .to_string()
        }

        // 时序不靠 sleep 猜：第二轮请求一到就举手（`2`），然后**卡在这里**等主线程放行（`3`）。
        // 于是"有一轮在跑"这件事必然成立，慢 runner 上也不会自己先跑完。
        //
        // 原来这里是 `sleep(200ms)` 然后断言 running：Windows 上 200ms 可能还没走进登记（断言太早），
        // 也可能整轮已经跑完（断言太晚）。两头都是竞态，只有让被测方自己报进度才稳。
        use std::sync::{Arc, Condvar, Mutex};
        let gate: Arc<(Mutex<u8>, Condvar)> = Arc::new((Mutex::new(0), Condvar::new()));
        let server_gate = gate.clone();

        let listener = TcpListener::bind("127.0.0.1:0").expect("起监听");
        let port = listener.local_addr().expect("取端口").port();
        let server = std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                read_request(&mut stream);
                write_body(&mut stream, &tool_call_body());
            }
            if let Ok((mut stream, _)) = listener.accept() {
                read_request(&mut stream);
                let (lock, ready) = &*server_gate;
                let mut step = lock.lock().expect("gate");
                *step = 2;
                ready.notify_all();
                while *step < 3 {
                    step = ready.wait(step).expect("gate");
                }
                drop(step);
                write_body(&mut stream, &tool_call_body());
            }
        });

        // 用一个真的库：这一层要验的就是"整条链一起动"。
        let dir = tempfile::tempdir().unwrap();
        let db = dir.path().join("turn.db");
        let db_text = CString::new(db.to_string_lossy().to_string()).unwrap();
        let opened = read_json(crate::storage::nmoi_storage_open_json(db_text.as_ptr()));
        let handle = opened["handle"].as_u64().expect("storage handle");

        let title = CString::new("cancel").unwrap();
        let assistant = CString::new("default").unwrap();
        let created = read_json(crate::storage::nmoi_storage_create_conversation_json(
            handle,
            title.as_ptr(),
            assistant.as_ptr(),
        ));
        let session = created["session"].as_str().expect("session").to_string();

        let config = json!({
            "storage": handle,
            "session": session,
            "provider": {"kind": "openai_compatible",
                          "base_url": format!("http://127.0.0.1:{port}/v1")},
            "model": "test-model",
            "user_text": "跑一轮",
            "now_ms": 1_700_000_000_000i64,
        })
        .to_string();
        let config = CString::new(config).unwrap();
        let config_address = config.as_ptr() as usize;

        // turn 在自己的线程上跑（UI 就是这么用的：主线程不能被它挡住）
        let runner = std::thread::spawn(move || {
            let pointer = nmoi_turn_run_json(config_address as *const c_char);
            read_json(pointer)
        });

        // 等"第二轮已经在飞"这个事实，最多 10 秒（不看墙钟、不猜）。
        {
            let (lock, ready) = &*gate;
            let mut step = lock.lock().expect("gate");
            while *step < 2 {
                let (again, waited) = ready
                    .wait_timeout(step, Duration::from_secs(10))
                    .expect("gate");
                step = again;
                if waited.timed_out() && *step < 2 {
                    panic!("10 秒内没等到第二轮请求，说明这一轮根本没跑起来");
                }
            }
        }

        let session_c = CString::new(session.clone()).unwrap();
        let cancelled = read_json(nmoi_turn_cancel_json(session_c.as_ptr()));
        assert_eq!(cancelled["running"], json!(true), "这会儿应该真有一轮在跑");

        // 放行第二轮，让工具循环走到取消检查点。
        {
            let (lock, ready) = &*gate;
            let mut step = lock.lock().expect("gate");
            *step = 3;
            ready.notify_all();
        }

        let value = runner.join().expect("turn 线程");
        server.join().expect("服务器线程");

        assert_eq!(value["ok"], json!(true), "{value}");
        assert_eq!(value["report"]["cancelled"], json!(true), "{value}");
        assert_eq!(value["report"]["finished"], json!(false));
        let calls = value["report"]["tool_calls"]
            .as_array()
            .expect("tool_calls");
        assert!(calls.len() <= 1, "第二轮的工具不该被执行：{value}");
    }

    /// 取消标志真的能被别的线程置上，并且 `Cancellation` 看得到（UI 就是这么用的）。
    #[test]
    fn cancel_flag_is_visible_to_the_core() {
        let (_registration, cancel) = Registration::new("session-c").unwrap();
        assert!(!cancel.is_cancelled());
        let text = CString::new("session-c").unwrap();
        let pointer: *mut c_char = nmoi_turn_cancel_json(text.as_ptr());
        unsafe { crate::nmoi_string_free(pointer) };
        assert!(cancel.is_cancelled());
    }

    /// 保存时的查重（D176）：撞名要说出是哪两个助手，不用联网。
    #[test]
    fn validate_reports_duplicates_with_owners() {
        let list = CString::new(
            json!([
                {"owner": "assistant-a", "id": "writer", "description": "改文案"},
                {"owner": "assistant-b", "id": "writer", "description": "也是改文案"},
                {"owner": "assistant-c", "id": "Bad Id", "description": "  "}
            ])
            .to_string(),
        )
        .unwrap();
        let out = read_json(nmoi_vars_validate_json(list.as_ptr()));
        assert_eq!(out["ok"], Value::Bool(false), "{out}");
        let problems = out["problems"].as_array().unwrap();
        // 重名一条 + 形状一条 + 空描述一条
        assert_eq!(problems.len(), 3, "{out}");
        let kinds: Vec<&str> = problems
            .iter()
            .map(|p| p["kind"].as_str().unwrap())
            .collect();
        assert!(kinds.contains(&"duplicate"), "{kinds:?}");
        assert!(kinds.contains(&"bad_id"), "{kinds:?}");
        assert!(kinds.contains(&"empty_description"), "{kinds:?}");
        let duplicate = problems.iter().find(|p| p["kind"] == "duplicate").unwrap();
        let text = duplicate["message"].as_str().unwrap();
        assert!(
            text.contains("assistant-a") && text.contains("assistant-b"),
            "{text}"
        );
    }

    #[test]
    fn validate_passes_a_clean_list() {
        let list = CString::new(
            json!([{"owner": "a", "id": "writer", "description": "改文案"}]).to_string(),
        )
        .unwrap();
        let out = read_json(nmoi_vars_validate_json(list.as_ptr()));
        assert_eq!(out["ok"], Value::Bool(true), "{out}");
        assert_eq!(out["problems"].as_array().unwrap().len(), 0);
    }
}
