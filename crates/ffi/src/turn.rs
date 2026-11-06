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
//! - **不替用户挑档位**：助手页给了就用它，没给（`auto`）才落到模型编辑页的
//!   「默认级别」，两边都没有就一个字段都不注。模型勾了「支持的级别」时，
//!   别的档位**当场报错并点名**——悄悄改成最近的一档等于替用户做决定（D37 / D201）。
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
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::Role;
use nmoi_core::policy::{AssistantPolicy, WorkspaceBinding};
use nmoi_core::provider::{ProviderConfig, ProviderKind, ThinkingEffort, ThinkingField};
use nmoi_core::runtime::ChatRequest;
use nmoi_core::session::{
    regenerate_turn, run_session_turn_with_images, SessionConfig, SessionError, SessionTurn,
};
use nmoi_core::shell::EnvirSpec;
use nmoi_core::tools::ToolRegistry;
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

/// 这一轮**正在说的话**（0.15n）。和取消标志分开一张表：
/// 那张表只放 `Arc<AtomicBool>`，混进来的话每处都要多解一层。
///
/// 上限 deliberately 粗：一轮正文最多留 50 000 字（再多也不是"边写边看"了），
/// 取出时再截到**尾部 8 KiB**——界面要的是"现在写到哪儿"，不是整篇。
fn partials() -> &'static Mutex<BTreeMap<String, PartialText>> {
    static TABLE: OnceLock<Mutex<BTreeMap<String, PartialText>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(BTreeMap::new()))
}

fn partials_lock() -> MutexGuard<'static, BTreeMap<String, PartialText>> {
    partials()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 一轮正在生成的正文/思考（尾部）。`text_len` 是**总量**，界面用它判断有没有被截。
#[derive(Debug, Default, Clone)]
struct PartialText {
    text: String,
    reasoning: String,
    /// 第几次模型请求（从 1 起）。工具往返之后会 +1，界面据此清掉上一轮那截。
    round: u32,
}

const PARTIAL_KEEP: usize = 50_000;
const PARTIAL_TAIL: usize = 8_192;

fn partial_reset(session: &str) {
    partials_lock().remove(session);
}

/// 换轮：清空正文/思考，但**留住轮号**（界面靠它判断该不该重画）。
fn partial_new_round(session: &str) {
    let mut table = partials_lock();
    let slot = table.entry(session.to_string()).or_default();
    slot.round += 1;
    slot.text.clear();
    slot.reasoning.clear();
}

fn partial_append(session: &str, text: &str, reasoning: &str) {
    if text.is_empty() && reasoning.is_empty() {
        return;
    }
    let mut table = partials_lock();
    let slot = table.entry(session.to_string()).or_default();
    slot.text.push_str(text);
    slot.reasoning.push_str(reasoning);
    // 太长就砍头：留着后面那截，界面反正只看尾部。
    for buffer in [&mut slot.text, &mut slot.reasoning] {
        if buffer.len() > PARTIAL_KEEP {
            let mut cut = buffer.len() - PARTIAL_KEEP;
            while cut < buffer.len() && !buffer.is_char_boundary(cut) {
                cut += 1;
            }
            *buffer = buffer[cut..].to_string();
        }
    }
}

/// 尾部快照：`(text_for_ui, text_len, reasoning_for_ui, reasoning_len)`。
fn partial_tail(session: &str) -> (String, usize, String, usize) {
    let table = partials_lock();
    let Some(slot) = table.get(session) else {
        return (String::new(), 0, String::new(), 0);
    };
    (
        tail(&slot.text),
        slot.text.chars().count(),
        tail(&slot.reasoning),
        slot.reasoning.chars().count(),
    )
}

/// 现在是第几轮（没开跑就是 0）。
fn partial_round(session: &str) -> u32 {
    partials_lock()
        .get(session)
        .map(|slot| slot.round)
        .unwrap_or(0)
}

/// 续话的第一段增量：把「原正文 + 连接符」垫在前面（0.15av）。
///
/// 与落库用的是**同一个** [`nmoi_core::session::join_continuation`]——界面拿到的流
/// 和最后写进库里的那份，连接符必然一致。不这么做就得在 Kotlin 里再实现一遍
/// "中文不加空格、英文加一个"，两份规则迟早分叉。
fn seeded_delta(prefix: &str, text: &str) -> String {
    if prefix.is_empty() {
        text.to_string()
    } else {
        nmoi_core::session::join_continuation(prefix, text)
    }
}

fn tail(text: &str) -> String {
    if text.len() <= PARTIAL_TAIL {
        return text.to_string();
    }
    let mut cut = text.len() - PARTIAL_TAIL;
    while cut < text.len() && !text.is_char_boundary(cut) {
        cut += 1;
    }
    text[cut..].to_string()
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
        // 新一轮开始：把上一轮留在缓冲里的残字清掉，否则界面会先闪一下旧内容。
        partial_reset(session);
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
    /// 上下文图片清单的上限（D248）。`0` = 默认 20。
    image_limit: u32,
    /// 图片模式（D270）：`inline`（默认，直发）/ `path`（只给路径，让模型自己挑）。
    image_mode: nmoi_core::agent::ImageMode,
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
    /// 模型编辑页「高级」里的自定义 Header（D201 第四片）。鉴权头仍由核心拼，
    /// 这里只加**额外的**那些；保留字（host / content-length 之类）在解析处就拒。
    headers: Vec<(String, String)>,
    /// 模型级的 body 覆盖：一个 JSON 对象，排在软件默认值之后、屏蔽之前
    /// （`build_body` 里那条顺序）。几把"改了就没法解析"的键在解析处拒掉。
    body: Option<Value>,
    /// 推理方言：思考这一档注到哪个字段族。`None` = 按供应商类型推（老行为）。
    thinking_field: Option<ThinkingField>,
    /// 这个模型接受的档位。空 = 没数据（不限制）——**不要**拿空表当成"一档都不支持"。
    thinking_efforts: Vec<ThinkingEffort>,
    /// 能不能显式关闭思考。`None` = 不知道（沿用老行为：可以）。
    thinking_allow_disable: Option<bool>,
    /// 助手页没指定档位（`auto`）时用这一档。
    thinking_default: Option<ThinkingEffort>,
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
    // 会话自己的 `/chat`（D234）：同样是可选的。给了 `chat` 才看 `chat_images`——
    // 没有 `/chat` 的话，`/chat/images` 这条绑定在 guest 里没有落脚点。
    let chat = match value.get("chat") {
        None | Some(Value::Null) => None,
        Some(_) => Some(PathBuf::from(envir_path(value, "chat")?)),
    };
    let chat_images = match value.get("chat_images") {
        None | Some(Value::Null) => None,
        Some(_) if chat.is_none() => None,
        Some(_) => Some(PathBuf::from(envir_path(value, "chat_images")?)),
    };
    Ok(Some(EnvirSpec {
        proot: paths[0].clone(),
        loader: paths[1].clone(),
        lib_dir: paths[2].clone(),
        rootfs: paths[3].clone(),
        tmp: paths[4].clone(),
        resolv,
        chat,
        chat_images,
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
    // `user_text` 只有 `turn_run` 需要（重试/续话走 `up_to`，不发新消息）。
    // 以前这里一律必填，于是"重试"在真机上永远是
    // `bad_argument：配置字段 user_text 是必填的`——两条 FFI 共用一份解析的代价。
    let user_text = optional_str(&root, "user_text")?.unwrap_or_default();
    let now_ms = optional_i64(&root, "now_ms")?.unwrap_or(0);

    // 0 = 不限制（D245）。以前这里把 0 当非法值、默认 12——那个 12 是早期拍的安全阀，
    // 撞上会把话截断。真正的边界是绝对线（`budget`）与取消。
    let max_rounds = optional_u64(&root, "max_rounds")?.unwrap_or(0);

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
        image_limit: optional_u64(&root, "image_limit")?.unwrap_or(0) as u32,
        image_mode: nmoi_core::agent::ImageMode::parse(
            &optional_str(&root, "image_mode")?.unwrap_or_default(),
        ),
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
        headers: parse_headers(&root)?,
        body: parse_body(&root)?,
        thinking_field: parse_thinking_field(&root)?,
        thinking_efforts: parse_thinking_efforts(&root)?,
        thinking_allow_disable: optional_bool(&root, "thinking_allow_disable")?,
        thinking_default: parse_thinking_default(&root)?,
    })
}

/// 自定义 Header（D201 第四片）：`[{"name":"X-Foo","value":"bar"}]`。
///
/// 三条界限都在这个解析里定死：
/// - **保留字不许覆盖**：`host` / `content-length` / `content-type` / `accept` 这些是核心
///   自己拼的，让用户改等于让他把协议改坏（症状是"上游回了一段读不懂的话"）。
/// - **名字里不许有冒号/空格/换行**：那是头部注入的形状，不是"用户手滑"。
/// - **值里不许有换行**：同上。
fn parse_headers(root: &Value) -> FfiResult<Vec<(String, String)>> {
    let Some(value) = field(root, "headers") else {
        return Ok(Vec::new());
    };
    let Some(items) = value.as_array() else {
        return Err(bad("headers", "必须是数组"));
    };
    if items.len() > 32 {
        return Err(bad("headers", "最多 32 条：再多就该查是不是填错了"));
    }
    let mut out: Vec<(String, String)> = Vec::with_capacity(items.len());
    for (index, item) in items.iter().enumerate() {
        if !item.is_object() {
            return Err(bad(&format!("headers[{index}]"), "必须是对象"));
        }
        let name = optional_str(item, "name")?
            .unwrap_or_default()
            .trim()
            .to_string();
        // 空名 = 用户点了「添加一行」还没填。**忽略，不报错**：那不是配置错误，
        // 是他正在打字（保存时界面已经拦过一次，这里只是兜底）。
        if name.is_empty() {
            continue;
        }
        let name_field = format!("headers[{index}].name");
        if name.contains(':') || name.contains(' ') || name.contains('\n') || name.contains('\r') {
            return Err(bad(&name_field, "里不能有冒号、空格或换行"));
        }
        if name.len() > 64 {
            return Err(bad(&name_field, "太长了（上限 64）"));
        }
        let lower = name.to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "host"
                | "content-length"
                | "content-type"
                | "accept"
                | "transfer-encoding"
                | "connection"
        ) {
            return Err(bad(
                &name_field,
                &format!("`{name}` 由核心自己写，自定义 Header 不能覆盖它"),
            ));
        }
        if out
            .iter()
            .any(|(existing, _)| existing.eq_ignore_ascii_case(&name))
        {
            return Err(bad(&name_field, &format!("`{name}` 重复了")));
        }
        let header_value = optional_str(item, "value")?.unwrap_or_default();
        if header_value.contains('\n') || header_value.contains('\r') {
            return Err(bad(&format!("headers[{index}].value"), "里不能有换行"));
        }
        out.push((name, header_value));
    }
    Ok(out)
}

/// 模型级 body 覆盖（D201 第四片）：一个 JSON 对象。
///
/// 四把键由核心写，这里拒掉：`messages`（内容是会话）、`model`（内容是模型 id）、
/// `stream`（核心按这次调用的方式定，改成 `false` 会让流式解析拿到一整个 JSON）、
/// `tools`（声明表来自这一轮的工具门控）。放开任何一把，症状都是"请求发出去了、
/// 回的东西对不上"，而原因离现场很远。
fn parse_body(root: &Value) -> FfiResult<Option<Value>> {
    let Some(text) = optional_str(root, "body")?.filter(|text| !text.trim().is_empty()) else {
        return Ok(None);
    };
    let value: Value = serde_json::from_str(&text)
        .map_err(|error| bad("body", &format!("不是合法 JSON：{error}")))?;
    let Some(object) = value.as_object() else {
        return Err(bad("body", "必须是一个 JSON 对象"));
    };
    for key in ["messages", "model", "stream", "tools"] {
        if object.contains_key(key) {
            return Err(bad("body", &format!("`{key}` 由核心写，不能在这里覆盖")));
        }
    }
    Ok(Some(value))
}

/// 推理方言（D201 第四片）：`thinking` 这一档注到哪个字段族上。
///
/// wire 名与 `ThinkingField` 的 serde 序列化结果逐字一致（`open_ai_effort` 不是
/// `openai_effort`）——两份名字漂移的话，这里会静默落回"按供应商类型推"。
fn parse_thinking_field(root: &Value) -> FfiResult<Option<ThinkingField>> {
    let Some(text) = optional_str(root, "thinking_field")?.filter(|text| !text.trim().is_empty())
    else {
        return Ok(None);
    };
    let normalized = text.trim().to_ascii_lowercase();
    let field = match normalized.as_str() {
        "none" => ThinkingField::None,
        "open_ai_effort" => ThinkingField::OpenAiEffort,
        "responses_reasoning" => ThinkingField::ResponsesReasoning,
        "anthropic_effort" => ThinkingField::AnthropicEffort,
        "anthropic_thinking" => ThinkingField::AnthropicThinking,
        "gemini_thinking_config" => ThinkingField::GeminiThinkingConfig,
        other => {
            return Err(bad(
                "thinking_field",
                &format!(
                    "只能是 none / open_ai_effort / responses_reasoning / anthropic_effort / \
                     anthropic_thinking / gemini_thinking_config，收到 `{other}`（自定义 JSON 请写进 body）"
                ),
            ))
        }
    };
    Ok(Some(field))
}

/// 这个模型支持的档位。空数组 = **没数据**（不限制），不是"一档都不支持"。
fn parse_thinking_efforts(root: &Value) -> FfiResult<Vec<ThinkingEffort>> {
    let Some(value) = field(root, "thinking_efforts") else {
        return Ok(Vec::new());
    };
    let Some(items) = value.as_array() else {
        return Err(bad("thinking_efforts", "必须是数组"));
    };
    let mut out: Vec<ThinkingEffort> = Vec::with_capacity(items.len());
    for (index, item) in items.iter().enumerate() {
        let Some(text) = item.as_str() else {
            return Err(bad(&format!("thinking_efforts[{index}]"), "必须是字符串"));
        };
        let effort = ThinkingEffort::parse(text).ok_or_else(|| {
            bad(
                &format!("thinking_efforts[{index}]"),
                &format!(
                    "只能是 none / minimal / low / medium / high / xhigh / max，收到 `{text}`"
                ),
            )
        })?;
        if !out.contains(&effort) {
            out.push(effort);
        }
    }
    Ok(out)
}

/// 助手页没指定档位时用的那一档。
fn parse_thinking_default(root: &Value) -> FfiResult<Option<ThinkingEffort>> {
    let Some(text) = optional_str(root, "thinking_default")?.filter(|text| !text.trim().is_empty())
    else {
        return Ok(None);
    };
    ThinkingEffort::parse(&text).map(Some).ok_or_else(|| {
        bad(
            "thinking_default",
            &format!("只能是 minimal / low / medium / high / xhigh / max，收到 `{text}`"),
        )
    })
}

/// 这一轮的供应商配置：把「模型编辑页」里那三样接进来（D201 第四片）。
///
/// 位置固定在这里而不是散在两个入口里：`turn_run` 与 `turn_regen` 各拼一遍的话，
/// 迟早一边加了 header、另一边忘了——而症状是"重试时行为不一样"，最难查。
/// 这一轮带的图片（D208）：`[{"mime":"image/png","data":"<base64>"}]`。
///
/// 在这里把能挡的都挡掉（不是图片、base64 坏了、太大、太多）：等上游 400 的时候，
/// 用户看到的是"请求失败"，而真正的原因是他选了一张 HEIC。
fn parse_images(text: &str) -> FfiResult<Vec<nmoi_core::message::ImagePart>> {
    let root: Value = serde_json::from_str(text)
        .map_err(|error| FfiError::new("bad_argument", format!("配置不是合法 JSON: {error}")))?;
    let Some(list) = root.get("images") else {
        return Ok(Vec::new());
    };
    let list = list
        .as_array()
        .ok_or_else(|| FfiError::new("bad_argument", "`images` 必须是数组"))?;
    if list.len() > 4 {
        return Err(FfiError::new(
            "bad_argument",
            format!("一轮最多带 4 张图，给了 {} 张", list.len()),
        ));
    }
    let mut out = Vec::with_capacity(list.len());
    for (index, item) in list.iter().enumerate() {
        let mime = item
            .get("mime")
            .and_then(Value::as_str)
            .ok_or_else(|| FfiError::new("bad_argument", format!("第 {index} 张图缺 `mime`")))?;
        if !mime.starts_with("image/") {
            return Err(FfiError::new(
                "bad_argument",
                format!("第 {index} 张图的 mime 不是图片：{mime}"),
            ));
        }
        let data = item
            .get("data")
            .and_then(Value::as_str)
            .ok_or_else(|| FfiError::new("bad_argument", format!("第 {index} 张图缺 `data`")))?;
        let bytes = nmoi_core::base64::decode(data).ok_or_else(|| {
            FfiError::new("bad_argument", format!("第 {index} 张图的 base64 解不开"))
        })?;
        if bytes.is_empty() {
            return Err(FfiError::new(
                "bad_argument",
                format!("第 {index} 张图是空的"),
            ));
        }
        if bytes.len() > nmoi_storage::repo::MAX_ATTACHMENT_BYTES {
            return Err(FfiError::new(
                "bad_argument",
                format!(
                    "第 {index} 张图 {} 字节，超过上限 {}",
                    bytes.len(),
                    nmoi_storage::repo::MAX_ATTACHMENT_BYTES
                ),
            ));
        }
        out.push(nmoi_core::message::ImagePart {
            mime: mime.to_string(),
            data: data.to_string(),
        });
    }
    Ok(out)
}

fn provider_config(input: &TurnInput) -> ProviderConfig {
    let mut provider =
        ProviderConfig::new(&input.provider_id, input.provider_kind, &input.base_url);
    if !input.headers.is_empty() {
        provider.extra_headers = input.headers.clone();
    }
    if let Some(body) = input.body.as_ref() {
        provider.overrides.model = Some(body.clone());
    }
    provider
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
fn apply_thinking(request: ChatRequest, input: &TurnInput) -> FfiResult<ChatRequest> {
    use nmoi_core::config::{default_thinking_field, ModelFlavor, ThinkingContext};
    use nmoi_core::provider::ThinkingSetting;

    // 档位的来源有两处，优先级写死在这里：**助手页这一次要的** > 模型编辑页的「默认级别」>
    // 什么都不注。`auto` 等于"助手没说"，所以它也落到默认级别上。
    let requested = input
        .thinking
        .as_deref()
        .map(str::trim)
        .filter(|text| !text.is_empty() && !text.eq_ignore_ascii_case("auto"));
    let level = match requested {
        Some(level) => level.to_ascii_lowercase(),
        None => match input.thinking_default {
            Some(effort) => effort.as_str().to_string(),
            None => return Ok(request),
        },
    };

    if level == "off" {
        // 「允许关闭」明确是 `false` 时才拦：`None`（不知道）沿用老行为，否则老配置
        // 会突然发不出去——那不是"更严谨"，是"没打招呼就改了规矩"。
        if input.thinking_allow_disable == Some(false) {
            return Err(FfiError::new(
                "bad_argument",
                "这个模型不允许关闭思考（模型编辑页 → 推理 → 允许关闭 = 不能关）；\n请换一档，或把那一项改成「不知道 / 可以关」",
            ));
        }
        // 关掉 = 一个字段都不注（老行为，别改成 `type: disabled`：那在 OpenAI 兼容系是未知字段）。
        return Ok(request.thinking(ThinkingSetting::disabled()));
    }

    let effort = ThinkingEffort::parse(&level).ok_or_else(|| {
        FfiError::new(
            "bad_argument",
            format!("thinking 只能是 auto / off / minimal / low / medium / high / xhigh / max，收到 `{level}`"),
        )
    })?;
    if !input.thinking_efforts.is_empty() && !input.thinking_efforts.contains(&effort) {
        let allowed = input
            .thinking_efforts
            .iter()
            .map(|effort| effort.as_str())
            .collect::<Vec<_>>()
            .join(" / ");
        return Err(FfiError::new(
            "bad_argument",
            format!("这个模型不支持 `{level}` 这一档；模型编辑页里勾的支持档位是 {allowed}",),
        ));
    }

    let field = match input.thinking_field {
        Some(field) => field,
        None => default_thinking_field(&ThinkingContext::new(
            input.provider_kind,
            ModelFlavor::Other,
        )),
    };
    Ok(request.thinking(ThinkingSetting {
        field,
        effort: Some(effort),
        ..ThinkingSetting::default()
    }))
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
            "content": message.content(),
            "incomplete": message.incomplete,
            "tokens": message.tokens,
            // 思考也带出来（D175）：界面不必为了"这一轮有没有思考"再读一次库。
            "reasoning": message.reasoning(),
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
        // 这一条只有 `turn_run` 要：重试/续话不发新消息，所以那边不填也合法。
        if input.user_text.trim().is_empty() {
            return Err(FfiError::new(
                "bad_argument",
                "配置字段 `user_text` 不能是空串：要发新消息就得有内容",
            ));
        }
        // 流式（0.15n）：默认关。开着时这一轮真的走 SSE，增量经 progress 钩子进 partial 表。
        // `parse_config` 内部自己解析那份 JSON，`root` 在这一层没有；
        // 为了一个布尔再解一遍不值当，但也不改 `parse_config` 的签名（它被 regen 共用）。
        // 图片（D208）：和 `stream` 一样从原始 JSON 里取——`parse_config` 被 regen 共用，
        // 为了一个可选数组改它的签名不值当。
        let images = parse_images(text)?;
        let stream = serde_json::from_str::<Value>(text)
            .ok()
            .and_then(|root| root.get("stream").and_then(Value::as_bool))
            .unwrap_or(false);

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

        let provider = provider_config(&input);
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
        // 思考档位**总是**走一遍：助手页的档位、模型的默认档位、方言与允许关闭
        // 都在 apply_thinking 里合起来判定（省掉这一步 = 那些格子只是摆设）。
        request = apply_thinking(request, &input)?;

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
                stream,
                image_limit: input.image_limit,
                image_mode: input.image_mode,
                ..TurnConfig::default()
            },
            // 这条路只跑新的一轮，不涉及"重试落成哪一版"；重试走 `regenerate_turn`。
            allow_new_version: true,
            budget: input.budget.as_ref(),
            trigger: &trigger(),
            avg_per_turn: input.avg_per_turn,
            cancel: &cancel,
            // FFI 这条路径还没有设置面，冻结等 `nmoi_turn_*` 接上设置之后再做。
            plan: None,
            envir: input.envir.as_ref(),
        };

        let client = crate::proxy::client();
        // 会话记住这一轮用的模型（D101）：上下文面板靠它选词表、按模型取校准。
        // 写失败不让这一轮失败——为了一个显示用的列把用户刚说的话丢掉不值（同 D98 的校准写回）。
        let _ = storage.set_conversation_model(input.session, &input.model);
        let session_key = input.session.to_string();
        // 流式：把增量写进 partial 表（界面轮询 `turn_busy` 拿尾部）。
        // 钩子是作用域内的——这一轮结束（含出错）自动撤掉，不会串到下一轮。
        // 换轮（0.15ah）：工具往返之后清空缓冲并 +1，界面据此重画而不是把两轮拼起来。
        let (sink, round_hook): (
            Option<nmoi_core::progress::Sink>,
            Option<nmoi_core::progress::RoundHook>,
        ) = if stream {
            let key = session_key.clone();
            let round_key = session_key.clone();
            (
                Some(std::rc::Rc::new(move |text: &str, reasoning: &str| {
                    partial_append(&key, text, reasoning);
                })),
                Some(std::rc::Rc::new(move || partial_new_round(&round_key))),
            )
        } else {
            (None, None)
        };

        let mut call = || {
            run_session_turn_with_images(
                &client,
                &request,
                &mut storage,
                input.session,
                &input.user_text,
                &images,
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
            })
        };
        let turn = match (sink, round_hook) {
            (Some(sink), Some(round)) => nmoi_core::progress::scope_with_round(sink, round, call)?,
            _ => call()?,
        };

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
///
/// **顺带把这一轮正在跑的工具调用一起置上**（0.15ak）：只停模型不停命令的话，
/// 一条 `timeout_ms: "inf"` 的命令会在"用户已经按了停止"之后继续跑下去，
/// 而屏幕上那一行还写着"正在跑"。`calls_cancelled` 是这条顺手做的事的计数。
#[no_mangle]
pub extern "C" fn nmoi_turn_cancel_json(session: *const c_char) -> *mut c_char {
    run_json(|| {
        let session = read_str(session, "session")?;
        let table = registry_lock();
        match table.get(session) {
            Some(flag) => {
                flag.store(true, Ordering::SeqCst);
                drop(table);
                let calls = nmoi_core::kills::cancel_all(session);
                Ok(json!({
                    "cancelled": true,
                    "running": true,
                    "calls_cancelled": calls,
                }))
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
        drop(table);
        // 增量（0.15n）：只给**尾部**（≤8 KiB）+ 总量，界面拿它逐字画。
        // 轮号（0.15ah）：工具往返之后 +1，界面看到它变了就清掉上一轮那截。
        //
        // **没在跑就一律给空**（D236）：缓冲在上一轮结束后还留着上一份正文，
        // 而界面是在 `Started` 事件里就开始轮询的——那一瞬 native 这边还没登记，
        // 于是它读到的是上一轮的残字，屏幕上就成了"新消息里写着上一条的回答"。
        // 没有登记 = 没有"这一轮"，那几个字段就没有意义。
        let (text, text_len, reasoning, reasoning_len, round) = if running {
            let (text, text_len, reasoning, reasoning_len) = partial_tail(session);
            (
                text,
                text_len,
                reasoning,
                reasoning_len,
                partial_round(session),
            )
        } else {
            (String::new(), 0, String::new(), 0, 0)
        };
        // 正在跑的工具调用（0.15ak）：界面照这一份画"哪一条在跑、跑了多久"，
        // 「关闭」按钮点的就是这里的 `call_id`。没在跑就一律空——同 D236 那条口径。
        // 这一轮**已经跑完**的调用（D251）：结果要等收尾才落库，中间这一段
        // 界面上要能看见"它跑过什么"，否则用户只看到模型在思考。
        let done: Vec<Value> = if running {
            nmoi_core::kills::done(session)
                .into_iter()
                .map(|call| {
                    json!({
                        "call_id": call.call_id,
                        "name": call.name,
                        "arguments": call.arguments,
                        "output": call.output,
                        "ok": call.ok,
                        "summary": call.summary,
                    })
                })
                .collect()
        } else {
            Vec::new()
        };
        let calls: Vec<Value> = if running {
            nmoi_core::kills::running(session)
                .into_iter()
                .map(|call| {
                    json!({
                        "call_id": call.call_id,
                        "name": call.name,
                        "arguments": call.arguments,
                        "elapsed_ms": call.elapsed_ms,
                        // 实时输出（D264）：弹窗里一边跑一边看，不用等结果回来。
                        // 两路**分开**：报错走 stderr，混在一起就分不清了。
                        "stdout": call.stdout,
                        "stderr": call.stderr,
                        "stdout_cut": call.stdout_cut,
                        "stderr_cut": call.stderr_cut,
                    })
                })
                .collect()
        } else {
            Vec::new()
        };
        Ok(json!({
            "session": session,
            "running": running,
            "cancelled": cancelled,
            "round": round,
            "text": text,
            "text_len": text_len,
            "reasoning": reasoning,
            "reasoning_len": reasoning_len,
            "calls": calls,
            "done": done,
        }))
    })
}

/// 重试 / 续话：前缀重发（0.15h / D185）。
///
/// 配置与 [`nmoi_turn_run_json`] 是**同一份**（同一个 `parse_config`），多四个字段：
///
/// - `up_to`（必填）：发出去的最后一条消息 id（含）。用户消息重试就是它自己，
///   助手消息重试是它**之前**那条，续话是段内最后一条。
/// - `target`（可选）：结果落成哪条助手消息的新版本。给了就落版本；不给就追加一条新的
///   （只有"那条消息下面本来没有助手消息"时才该这么用）。
/// - `allow_new_version`（可选，默认 `true`）：关着时覆盖那一版，不新增。
/// - `append`（可选，默认 `false`）：**续话**（D193）——落库的正文接在 `target` 现在的正文后面。
/// - `stream`（可选，默认 `false`）：这一轮走 SSE，增量进 partial 表，界面轮询 `turn_busy`
///   把正文**就地**画在目标消息上（D227）。关着就是"空白到结果回来"。
///   续话（`append`）也走这条路：partial 缓冲里先垫上「原正文 + 连接符」，
///   所以界面看到的自始至终是"那一条消息的全文"，不必在 Kotlin 里重写拼接规则（0.15av）。
///
/// 出参：`{text, reasoning, target, appended, rounds, tool_calls, finished}`
///
/// 准备逻辑与 `nmoi_turn_run_json` 是重复的一段（storage / workspace / policy / 预设 /
/// 请求构建）。改动这两处时**要一起改**——抽公共函数会把 turn_run 那个已经很长的函数
/// 再拆一次，风险比这 40 行重复大。
#[no_mangle]
pub extern "C" fn nmoi_turn_regen_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let input = parse_config(text)?;
        let root: Value = serde_json::from_str(text).map_err(|error| {
            FfiError::new("bad_argument", format!("配置不是合法 JSON：{error}"))
        })?;
        let up_to_text = required_str(&root, "up_to")?;
        let up_to = up_to_text
            .parse::<MessageId>()
            .map_err(|_| FfiError::new("id", format!("up_to `{up_to_text}` 不是消息 id")))?;
        let target = match optional_str(&root, "target")? {
            Some(value) if !value.trim().is_empty() => Some(
                value
                    .parse::<MessageId>()
                    .map_err(|_| FfiError::new("id", format!("target `{value}` 不是消息 id")))?,
            ),
            _ => None,
        };
        let allow_new_version = root
            .get("allow_new_version")
            .and_then(Value::as_bool)
            .unwrap_or(true);
        // 续话（D193）：`true` = 落库的正文接在原正文后面；默认 `false` = 重试（整段换掉）。
        let append = root.get("append").and_then(Value::as_bool).unwrap_or(false);
        // 流式（D227）：重试也要"就地长"。默认关——老调用方（脚本、测试）行为一个字都不变；
        // 界面显式传 `true`，这样"边上屏边看"与"结果回来再换"是同一条路，不是两条。
        let stream = root.get("stream").and_then(Value::as_bool).unwrap_or(false);

        let (_registration, cancel) = Registration::new(&input.session.to_string())?;
        let mut storage = Storage::attach(storage_path(input.storage)?)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;
        let workspace: Option<Workspace> = match input.workspace {
            None => None,
            Some(id) => Some(with_workspace(id, |workspace| Ok(workspace.clone()))?),
        };
        let policy = policy_for(&input);
        let registry = ToolRegistry::workspace_defaults();

        let provider = provider_config(&input);
        let system = input.system.as_deref().map(|text| {
            nmoi_core::vars::replace(
                text,
                &nmoi_core::vars::VarTable::new(input.sub_agents.clone()),
            )
        });
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
        // 思考档位**总是**走一遍：助手页的档位、模型的默认档位、方言与允许关闭
        // 都在 apply_thinking 里合起来判定（省掉这一步 = 那些格子只是摆设）。
        request = apply_thinking(request, &input)?;

        let calibrations = storage
            .calibrations(&input.model, input.now_ms)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;
        let session_config = SessionConfig {
            calibrations: &calibrations,
            retry: None,
            allow_new_version,
            suppress_compression: false,
            turn: TurnConfig {
                max_rounds: input.max_rounds,
                stream,
                image_limit: input.image_limit,
                image_mode: input.image_mode,
                ..TurnConfig::default()
            },
            budget: input.budget.as_ref(),
            trigger: &trigger(),
            avg_per_turn: input.avg_per_turn,
            cancel: &cancel,
            plan: None,
            envir: input.envir.as_ref(),
        };

        let client = crate::proxy::client();
        let _ = storage.set_conversation_model(input.session, &input.model);
        // 增量进 partial 表（同 `turn_run`）：界面拿 `turn_busy` 的尾部 + 总量逐字画。
        // 钩子是作用域内的——这一轮结束（含出错）自动撤掉，不会串到下一轮。
        //
        // 续话（0.15av）：落库那份是"原正文 + 续文"，而流里只有续文那一截。所以这里把
        // **原正文 + 连接符**垫进缓冲（`seeded_delta`），界面那边把 `live.text` 预置成原正文，
        // 两边的字数才对得上（`growText` 靠总量差算新长出来多少）。
        // 连接符用的是 core 的 `join_continuation`——拼接规则只有一份，Kotlin 不重写。
        let append_prefix = if append && stream {
            match target.as_ref() {
                Some(id) => storage.message_content(id).unwrap_or_default(),
                None => String::new(),
            }
        } else {
            String::new()
        };
        let session_key = input.session.to_string();
        let (sink, round_hook) = if stream {
            let key = session_key.clone();
            let round_key = session_key;
            // 每轮的第一段增量要垫一次：工具往返之后缓冲被清掉，这一轮同样是从"原正文"接着写。
            let fresh = std::rc::Rc::new(std::cell::Cell::new(true));
            let fresh_sink = fresh.clone();
            let prefix = append_prefix.clone();
            (
                Some(std::rc::Rc::new(move |text: &str, reasoning: &str| {
                    if !text.is_empty() && fresh_sink.replace(false) {
                        partial_append(&key, &seeded_delta(&prefix, text), reasoning);
                    } else {
                        partial_append(&key, text, reasoning);
                    }
                }) as nmoi_core::progress::Sink),
                Some(std::rc::Rc::new(move || {
                    fresh.replace(true);
                    partial_new_round(&round_key);
                }) as nmoi_core::progress::RoundHook),
            )
        } else {
            (None, None)
        };
        let mut call = || {
            regenerate_turn(
                &client,
                &request,
                &mut storage,
                input.session,
                &up_to,
                target.as_ref(),
                append,
                &registry,
                &policy,
                workspace.as_ref(),
                &session_config,
                input.now_ms,
            )
        };
        let outcome = match (sink, round_hook) {
            (Some(sink), Some(round)) => nmoi_core::progress::scope_with_round(sink, round, call),
            _ => call(),
        }
        .map_err(|error| match error {
            SessionError::CompactionRequired { used, usable } => FfiError::new(
                "compaction_required",
                format!("上下文已达绝对线（{used}/{usable} token），先压缩再发"),
            ),
            other => FfiError::new("turn", other.to_string()),
        })?;

        Ok(json!({
            "text": outcome.text,
            "reasoning": outcome.reasoning,
            "target": outcome.target.map(|id| id.to_string()),
            "appended": outcome.appended.map(|id| id.to_string()),
            "rounds": outcome.report.rounds,
            "tool_calls": outcome.report.tool_calls.len(),
            "finished": outcome.report.finished,
            // 两种"没做完"要分开说（D245）：用户按了停止 / 上下文到了绝对线 / （设了）轮数上限。
            // 以前这两个字段没进包，界面只能把 `finished=false` 一律读成"到了轮数上限"——
            // 用户明明是自己停的，却被告知"到了上限"（2026-11-04 真机就是这么报的）。
            "cancelled": outcome.report.cancelled,
            "truncated": outcome.report.truncated,
        }))
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

        let client = crate::proxy::client();
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

    /// 在最小配置上打几个字段，得到一个 TurnInput。
    fn with_extra(patch: Value) -> TurnInput {
        let mut value: Value = serde_json::from_str(&minimal()).unwrap();
        if let Some(object) = patch.as_object() {
            for (key, item) in object {
                value[key] = item.clone();
            }
        }
        parse(&value.to_string()).unwrap()
    }

    fn thinking_of(input: &TurnInput) -> FfiResult<ChatRequest> {
        apply_thinking(
            ChatRequest::new(
                ProviderConfig::new("p", input.provider_kind, "http://127.0.0.1:1/v1"),
                "m",
            ),
            input,
        )
    }

    /// `auto` = 一个字段都不注；给了档位 = 真的落到 `thinking.effort` 上。
    #[test]
    fn thinking_levels_land_on_the_request() {
        let auto = with_extra(json!({ "thinking": "auto" }));
        assert_eq!(thinking_of(&auto).unwrap().thinking.effort, None);

        let high = with_extra(json!({ "thinking": "high" }));
        let request = thinking_of(&high).unwrap();
        assert_eq!(request.thinking.effort, Some(ThinkingEffort::High));
        // 没填方言：按供应商类型推（兼容系 = `reasoning_effort`）。
        assert_eq!(request.thinking.field, ThinkingField::OpenAiEffort);

        let off = with_extra(json!({ "thinking": "off" }));
        assert_eq!(thinking_of(&off).unwrap().thinking.effort, None);

        let broken = with_extra(json!({ "thinking": "超高" }));
        assert!(thinking_of(&broken).is_err());
    }

    /// 方言与默认档（D201 第四片）：方言改字段族，`auto` 落到模型的默认档上。
    #[test]
    fn dialect_and_default_effort_are_wired() {
        let custom = with_extra(json!({
            "thinking": "auto",
            "thinking_field": "anthropic_effort",
            "thinking_default": "medium",
        }));
        let request = thinking_of(&custom).unwrap();
        assert_eq!(request.thinking.field, ThinkingField::AnthropicEffort);
        assert_eq!(request.thinking.effort, Some(ThinkingEffort::Medium));

        // 助手页明确要的那一档优先于模型默认档。
        let explicit = with_extra(json!({
            "thinking": "low",
            "thinking_default": "medium",
        }));
        assert_eq!(
            thinking_of(&explicit).unwrap().thinking.effort,
            Some(ThinkingEffort::Low)
        );
    }

    /// 模型勾了"支持的级别"之后，别的档位要**当场报错并点名**，不许悄悄改成最近的一档。
    #[test]
    fn unsupported_effort_names_the_allowed_set() {
        let input = with_extra(json!({
            "thinking": "high",
            "thinking_efforts": ["low", "medium"],
        }));
        let error = thinking_of(&input).unwrap_err();
        assert!(error.message.contains("high"), "{}", error.message);
        assert!(error.message.contains("low"), "{}", error.message);
        assert!(error.message.contains("medium"), "{}", error.message);

        let ok = with_extra(json!({
            "thinking": "medium",
            "thinking_efforts": ["low", "medium"],
        }));
        assert!(thinking_of(&ok).is_ok());

        // 空数组 = 没数据 = 不限制（不是"一档都不支持"）。
        let unconstrained = with_extra(json!({ "thinking": "max", "thinking_efforts": [] }));
        assert_eq!(
            thinking_of(&unconstrained).unwrap().thinking.effort,
            Some(ThinkingEffort::Max)
        );
    }

    /// 「允许关闭 = 不能关」时，显式 `off` 要拦住；`null`（不知道）沿用老行为。
    #[test]
    fn closing_thinking_can_be_refused_by_the_model() {
        let refused = with_extra(json!({
            "thinking": "off",
            "thinking_allow_disable": false,
        }));
        let error = thinking_of(&refused).unwrap_err();
        assert!(error.message.contains("不允许关闭"), "{}", error.message);

        let unknown = with_extra(json!({ "thinking": "off" }));
        assert!(thinking_of(&unknown).is_ok());
    }

    /// 自定义 Header：保留字、重名、换行都在解析处拦住；空名当"还没填"直接忽略。
    #[test]
    fn headers_are_guarded() {
        let good = parse_headers(&json!({
            "headers": [
                {"name": "X-Foo", "value": "bar"},
                {"name": "", "value": "还没填名字"},
            ]
        }))
        .unwrap();
        assert_eq!(good, vec![("X-Foo".to_string(), "bar".to_string())]);

        for broken in [
            json!({"headers": [{"name": "Host", "value": "evil"}]}),
            json!({"headers": [{"name": "X-A", "value": "1"}, {"name": "x-a", "value": "2"}]}),
            json!({"headers": [{"name": "X A", "value": "1"}]}),
            json!({"headers": [{"name": "X-A", "value": "1\nX-B: 2"}]}),
            json!({"headers": "X-A"}),
        ] {
            assert!(parse_headers(&broken).is_err(), "这条应当被拦住：{broken}");
        }
    }

    /// 自定义 Body：核心自己写的那几把键不许覆盖（覆盖了症状会离现场很远）。
    #[test]
    fn body_override_refuses_the_keys_core_owns() {
        assert!(parse_body(&json!({ "body": "{\"top_k\": 42}" }))
            .unwrap()
            .is_some());
        assert!(parse_body(&json!({ "body": "   " })).unwrap().is_none());

        for broken in [
            json!({ "body": "[1,2,3]" }),
            json!({ "body": "不是 JSON" }),
            json!({ "body": "{\"stream\": false}" }),
            json!({ "body": "{\"messages\": []}" }),
            json!({ "body": "{\"model\": \"x\"}" }),
            json!({ "body": "{\"tools\": []}" }),
        ] {
            assert!(parse_body(&broken).is_err(), "这条应当被拦住：{broken}");
        }
    }

    /// 端到端（不到网络）：配置里的 header 与 body 真的落到这次请求上。
    ///
    /// 这条是两个入口共用的证据：`provider_config` 只有一份，`turn_run` 与
    /// `turn_regen` 都从它拿——所以"重试时行为不一样"这种毛病不会从这里长出来。
    #[test]
    fn model_headers_and_body_reach_the_request() {
        let input = with_extra(json!({
            "headers": [{"name": "X-Relay-Tag", "value": "nmoi"}],
            "body": "{\"top_k\": 42}",
        }));

        let provider = provider_config(&input);
        assert_eq!(
            provider.extra_headers,
            vec![("X-Relay-Tag".to_string(), "nmoi".to_string())]
        );
        assert_eq!(
            provider.overrides.model,
            Some(json!({ "top_k": 42 })),
            "模型级 body 要进 overrides.model"
        );

        let request = ChatRequest::from_turns(provider, "m", &[(Role::User, "在吗")]);
        let body = nmoi_core::runtime::build_body(&request).unwrap();
        assert_eq!(body["top_k"], json!(42), "自定义 body 没并进请求体");
        assert_eq!(body["model"], json!("m"), "核心自己的字段要留着");
        assert_eq!(body["stream"], json!(true), "stream 由核心定");
    }

    #[test]
    fn unknown_provider_kind_lists_the_options() {
        let broken = minimal().replace("openai_compatible", "openai_but_wrong");
        let error = parse(&broken).unwrap_err();
        assert!(error.message.contains("provider.kind"), "{}", error.message);
        assert!(error.message.contains("anthropic"), "{}", error.message);
    }

    /// 续话垫字（0.15av）：第一段增量必须带着原正文 + **与落库同一个**连接符。
    ///
    /// 这三条正好盖住三种接法：中文直接接、英文加一个空格、原文以空白结尾就不再加。
    /// 判据是"界面上看到的那份"与"最后写进库里的那份"逐字一致。
    #[test]
    fn the_first_continuation_delta_carries_the_original_text() {
        assert_eq!(seeded_delta("今天天气", "很好"), "今天天气很好");
        assert_eq!(seeded_delta("hello", "world"), "hello world");
        assert_eq!(seeded_delta("hello ", "world"), "hello world");
        // 没有原正文（不是续话）：一个字都不该多。
        assert_eq!(seeded_delta("", "world"), "world");
    }

    /// `user_text` 只在 `turn_run` 必填：重试/续话走 `up_to`，解析层不能再拦它。
    /// 真机上那句 `重试失败：bad_argument：配置字段 user_text 是必填的` 就是这里来的。
    #[test]
    fn user_text_is_optional_for_regen_and_checked_by_turn_run() {
        let blank = minimal().replace("\"在吗\"", "\"   \"");
        let parsed = parse(&blank).expect("解析层不该因为 user_text 为空就拒");
        assert_eq!(parsed.user_text.trim(), "");

        // 少了这个键也一样能解析（重试那条根本不带它）。
        let mut value: Value = serde_json::from_str(&minimal()).unwrap();
        value.as_object_mut().unwrap().remove("user_text");
        assert!(parse(&value.to_string()).is_ok());
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
        assert_eq!(input.max_rounds, 0, "默认不限制轮数（D245）");
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

    /// 正在跑的工具调用（0.15ak）：`turn_busy` 要能说清"哪一条在跑、跑了多久"，
    /// 界面上的「关闭」才有东西可指。
    ///
    /// 没在跑时这一项必须是空数组——同 D236 那条口径：没有这一轮就没有"这一轮在调什么"。
    #[test]
    fn busy_lists_the_calls_that_are_running() {
        let name = "018f0000-0000-7000-8000-0000000000cc";
        let session = CString::new(name).unwrap();
        let (registration, _flag) = Registration::new(name).unwrap();

        let idle = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(idle["calls"], json!([]), "{idle}");

        let handle =
            nmoi_core::kills::begin(name, "call_1", "System-Shell", r#"{"command":"sleep 600"}"#);
        let running = read_json(nmoi_turn_busy_json(session.as_ptr()));
        let calls = running["calls"].as_array().expect("calls 是数组");
        assert_eq!(calls.len(), 1, "{running}");
        assert_eq!(calls[0]["call_id"], json!("call_1"), "{running}");
        assert_eq!(calls[0]["name"], json!("System-Shell"), "{running}");
        // 参数要跟着来：弹窗里得说清"它到底在跑什么"，光有名字没法判断该不该关。
        assert_eq!(
            calls[0]["arguments"],
            json!(r#"{"command":"sleep 600"}"#),
            "{running}"
        );
        assert!(calls[0]["elapsed_ms"].is_u64(), "{running}");

        drop(handle);
        let after = read_json(nmoi_turn_busy_json(session.as_ptr()));
        assert_eq!(
            after["calls"],
            json!([]),
            "结果回来了就不该再算在跑：{after}"
        );
        drop(registration);
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

        /// 读完整条请求（头 + `content-length` 指定的正文）。
        ///
        /// 只读到 `\r\n\r\n` 就回包、然后关连接，在 Windows 上会让还在写正文的客户端
        /// 撞上 RST——第一轮就此失败，第二轮永远不会来（2026-11-02 CI 上就是这么红的）。
        fn read_request(stream: &mut std::net::TcpStream) {
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 1024];
            while !buffer.windows(4).any(|window| window == b"\r\n\r\n") {
                match stream.read(&mut chunk) {
                    Ok(0) => return,
                    Ok(read) => buffer.extend_from_slice(&chunk[..read]),
                    Err(_) => return,
                }
            }
            let head_end = buffer
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .map(|at| at + 4)
                .unwrap_or(buffer.len());
            let head = String::from_utf8_lossy(&buffer[..head_end]).to_ascii_lowercase();
            let length = head
                .lines()
                .find_map(|line| line.strip_prefix("content-length:"))
                .and_then(|value| value.trim().parse::<usize>().ok())
                .unwrap_or(0);
            let mut body = buffer.len().saturating_sub(head_end);
            while body < length {
                match stream.read(&mut chunk) {
                    Ok(0) => return,
                    Ok(read) => body += read,
                    Err(_) => return,
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

#[cfg(test)]
mod partial_tests {
    use super::*;

    #[test]
    fn append_and_tail_keep_the_end_and_count_the_whole() {
        let key = "partial-test-a";
        partial_reset(key);
        partial_append(key, "你", "");
        partial_append(key, "好", "想");
        let (text, len, reasoning, rlen) = partial_tail(key);
        assert_eq!(text, "你好");
        assert_eq!(len, 2);
        assert_eq!(reasoning, "想");
        assert_eq!(rlen, 1);

        // 超过 8 KiB 只给尾部，但 `text_len` 是**总量**——界面靠它判断被截了没有。
        let chunk = "字".repeat(3_000);
        partial_append(key, &chunk, "");
        let (tail_text, total, _, _) = partial_tail(key);
        assert!(
            tail_text.len() <= PARTIAL_TAIL,
            "尾部窗口 {}B",
            tail_text.len()
        );
        assert_eq!(total, 3_002);
        assert!(tail_text.ends_with('字'), "砍掉的应该是头不是尾");
        partial_reset(key);
        assert_eq!(partial_tail(key), (String::new(), 0, String::new(), 0));
    }

    #[test]
    fn opening_a_turn_clears_the_previous_leftovers() {
        let key = "partial-test-b";
        partial_append(key, "上一轮留下的", "");
        // `Registration::new` 里会 reset，这里直接验那个函数的行为。
        partial_reset(key);
        let (text, len, _, _) = partial_tail(key);
        assert!(text.is_empty() && len == 0);
    }

    #[test]
    fn a_new_round_clears_the_text_but_keeps_counting() {
        let key = "partial-test-c";
        partial_reset(key);
        assert_eq!(partial_round(key), 0, "没开跑就没有轮号");
        partial_new_round(key);
        partial_append(key, "第一轮", "");
        assert_eq!(partial_round(key), 1);
        // 工具往返之后换轮：正文清掉，轮号 +1（界面靠它重画）。
        partial_new_round(key);
        assert_eq!(partial_round(key), 2);
        let (text, len, _, _) = partial_tail(key);
        assert!(text.is_empty() && len == 0, "上一轮那段话不该留在这一轮里");
        partial_append(key, "第二轮", "");
        assert_eq!(partial_tail(key).0, "第二轮");
        partial_reset(key);
        assert_eq!(partial_round(key), 0);
    }

    /// 图片参数（D208）：能挡的都在这里挡掉，别等上游 400 才让用户猜。
    #[test]
    fn parse_images_checks_mime_base64_and_size() {
        let ok = parse_images(r#"{"images":[{"mime":"image/png","data":"aGVsbG8="}]}"#)
            .expect("合法图片应当通过");
        assert_eq!(ok.len(), 1);
        assert_eq!(ok[0].mime, "image/png");

        // 没写 images：空，不是错。
        assert!(parse_images(r#"{"user_text":"hi"}"#).unwrap().is_empty());

        let not_image = parse_images(r#"{"images":[{"mime":"text/plain","data":"aGVsbG8="}]}"#);
        assert!(not_image.is_err());

        let broken = parse_images(r#"{"images":[{"mime":"image/png","data":"aGVsbG8"}]}"#);
        assert!(broken.is_err(), "长度不是 4 的倍数");

        let empty = parse_images(r#"{"images":[{"mime":"image/png","data":""}]}"#);
        assert!(empty.is_err());

        let too_many = format!(
            r#"{{"images":[{}]}}"#,
            (0..5)
                .map(|_| r#"{"mime":"image/png","data":"aGVsbG8="}"#)
                .collect::<Vec<_>>()
                .join(",")
        );
        assert!(parse_images(&too_many).is_err(), "一轮最多 4 张");
    }
}
