//! 工具生态（P7）：core 只定形状，执行发生在这一层或平台侧。
//!
//! 三层分工，别混：
//!
//! - [`Tool`]：**形状**。名字、一句话描述、JSON schema、怎么调。平台侧要加工具就实现它。
//! - [`ToolRegistry`]：**调度**。名字 → 工具，外加一份可以直接塞进 `ChatRequest::tools`
//!   的声明表。
//! - [`ToolContext`]：**权限**。每一个调用先过 [`crate::policy`] 的能力门，再碰工作区。
//!
//! 三条硬规矩：
//!
//! 1. **先门控再动手**。不是"执行完发现没权限"，是连路径都不解析。没绑定工作区的助手
//!    调 `read_file`，得到的是"没有工作区"，而不是一个越界错误——两者的原因不一样，
//!    用户看到的提示也不该一样。
//! 2. **失败也是一种结果**。工具报错不结束这一轮对话，而是变成一条工具结果喂回去
//!    （和压缩的草稿工具一致）。模型需要看到"越界了""找不到""有两处匹配"才能自己改。
//! 3. **不在 core 里跑 shell**。`native_shell` / `container_exec` 是平台侧的事，
//!    这里只留能力位和拒绝理由。
//!
//! 这一层**不实现**多轮工具调用循环——那是 [`crate::compress::runner`] 已经在做的事，
//! 通用循环等有第二个用户时再抽（过早抽象会抽出错误的形状）。

use std::collections::BTreeMap;

use serde_json::{json, Value};

use crate::edit::{BlockOp, BlockSelector, MAX_BLOCK_BYTES};
use crate::ids::{MessageId, SessionId};
use crate::message::{Message, Role};
use crate::policy::{AssistantPolicy, Capability, DenyReason};
use crate::runtime::ToolSpec;
use crate::workspace::{ListOptions, ReadOptions, SearchOptions, Workspace, WorkspaceError};

/// 一次工具调用的结果。**永远**是结果，`Err` 不跨越这一层。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToolOutcome {
    /// 成功。`text` 就是喂回模型的内容。
    Ok { text: String, truncated: bool },
    /// 能力门没开。
    Denied {
        capability: Capability,
        reason: DenyReason,
    },
    /// 工具自己失败（路径越界、找不到、匹配不唯一……）。
    Failed { message: String },
}

impl ToolOutcome {
    pub fn ok(text: impl Into<String>) -> Self {
        ToolOutcome::Ok {
            text: text.into(),
            truncated: false,
        }
    }

    pub fn ok_truncated(text: impl Into<String>) -> Self {
        ToolOutcome::Ok {
            text: text.into(),
            truncated: true,
        }
    }

    pub fn failed(message: impl Into<String>) -> Self {
        ToolOutcome::Failed {
            message: message.into(),
        }
    }

    pub fn denied(capability: Capability, reason: DenyReason) -> Self {
        ToolOutcome::Denied { capability, reason }
    }

    pub fn is_ok(&self) -> bool {
        matches!(self, ToolOutcome::Ok { .. })
    }

    pub fn is_truncated(&self) -> bool {
        matches!(
            self,
            ToolOutcome::Ok {
                truncated: true,
                ..
            }
        )
    }

    /// 喂回模型的那段文字。拒绝也要说人话——模型看到"没有工作区"才知道该求用户绑一个。
    pub fn text(&self) -> String {
        match self {
            ToolOutcome::Ok { text, .. } => text.clone(),
            ToolOutcome::Denied { capability, reason } => format!(
                "拒绝执行：{}（{}）。{}",
                capability.as_str(),
                reason.as_str(),
                reason.explain()
            ),
            ToolOutcome::Failed { message } => format!("失败：{message}"),
        }
    }
}

/// 工具调用时的上下文：能碰什么、碰不碰得到。
///
/// `workspace` 为 `None` 是正常状态（助手没绑工作区），不是错误——
/// 这种时候读写类工具统一回 [`DenyReason::NoWorkspace`]。
#[derive(Clone, Copy)]
pub struct ToolContext<'a> {
    pub policy: &'a AssistantPolicy,
    pub workspace: Option<&'a Workspace>,
    /// 这一轮冻结好的工具声明名单（D76）。
    ///
    /// `None` = 没冻结过，按 `policy` 现算（旧行为）。`Some(空)` 与 `None` 不一样：
    /// 前者是"这一轮明确一个都不声明"，后者是"没算过"。混起来会出现
    /// "模型不支持工具，却还是把声明表发出去了"。
    pub declared: Option<&'a [String]>,
    /// 容器运行时的坐标（D172）。`None` = 隔离环境没就绪，
    /// `WorkSpace-EnvirShell` 就算被声明了也只会回一句"没就绪"。
    pub envir: Option<&'a crate::shell::EnvirSpec>,
    /// 这一轮的会话历史（含这一轮用户说的话）。`edit_message` 靠它找目标消息：
    /// 模型看得见内容、看不见 id，`last_assistant` 这种写法只能在这里解析。
    ///
    /// 与铁律①不冲突：这里的"现读"发生在**这一轮开始时**（`load_window` 一次），
    /// 而且真正落库时还会再读一次库——快照只用来解析目标与提前拦住明显的错。
    pub history: Option<&'a [Message]>,
    /// 这一轮属于哪条会话。手动关闭要按 `(session, call_id)` 登记，
    /// 而 `run_turn` 拿不到会话 id——它由调用方（`session.rs`）填进来。
    pub call_session: Option<&'a str>,
    /// 正在跑的这一条调用（0.15ak）。`None` = 不是从工具循环里进来的（直接 `invoke`），
    /// 这种调用没有"关闭"可点，也不需要等标志。
    pub call: Option<&'a crate::kills::CallHandle>,
    /// 这个会话的附件目录（`sessions/<id>/images`）。`View-Image` 只认这里的文件（D248）。
    pub chat_images: Option<&'a std::path::Path>,
    /// 跨会话只读（D295）：别的会话在哪、叫什么。`None` = 这一轮不提供这个能力
    /// （开关关着、或者历史实现不认别的会话）。
    pub sessions: Option<&'a crate::session::SessionIndex>,
    /// 这个会话自己的暂存目录（`sessions/<id>/tmp`）。文件工具里的 `/tmp` 落到这儿，
    /// 容器起的时候同一份挂到 guest 的 `/tmp`（D294）。
    ///
    /// `None` = 这一轮没有会话上下文（直接 `invoke`、内存历史实现）：那时 `/tmp` 报错，
    /// 不退回宿主的 `/tmp`——退回等于让两个会话共用一份，正是要隔离掉的东西。
    pub session_tmp: Option<&'a std::path::Path>,
    /// 这个会话的归档目录（`sessions/<id>/archive`，D317）。文件工具里的 `/archive/...`
    /// 落到这儿；容器里同一份挂到 `/archive`。
    ///
    /// F2–F4 把大块工具结果换成一行标记时，标记里写的就是 `/archive/<hex>.txt`。
    /// 没有这一格，那条标记就是一句"东西在别处"——模型读不到，等于内容被扔了。
    pub session_archive: Option<&'a std::path::Path>,
    /// 上下文图片清单的上限（0 = 用默认 20）。见 [`crate::images`]。
    pub image_limit: u32,
    /// 满了之后还能临时多留几张（D272）：`0` = 关。见 [`crate::images::room_of`]。
    pub image_temp_limit: u32,
    /// 这一轮的语音现状（0.17c）。`None` = 没配、或者语音总开关关着——
    /// 两种情况下 `Voice-Caps` 都不声明（`Capability::Voice` 也开不起来）。
    pub voice: Option<&'a crate::voicecaps::VoiceCaps>,
    /// 发请求用的传输层（0.17d）。`None` = 这个入口没有网络：`Speak` 会如实说一句。
    ///
    /// 与 `ToolContext` 里别的东西一样，是**借用**而不是所有权：谁建的谁留着，
    /// 工具只在这一次调用里用它。
    pub transport: Option<&'a dyn crate::transport::Transport>,
    /// 这条会话的音频目录（`sessions/<id>/audio`）。`Speak` 的产物落这儿。
    pub session_audio: Option<&'a std::path::Path>,
}

/// 手写而不是 derive：这里面有一个 `&dyn SessionCatalog`，它不该被打印出来，
/// 也不该为了能被打印而多一个约束。
impl std::fmt::Debug for ToolContext<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ToolContext")
            .field("declared", &self.declared)
            .field("call_session", &self.call_session)
            .field("session_tmp", &self.session_tmp)
            .field("sessions", &self.sessions)
            .finish_non_exhaustive()
    }
}

impl<'a> ToolContext<'a> {
    pub fn new(policy: &'a AssistantPolicy, workspace: Option<&'a Workspace>) -> Self {
        Self {
            policy,
            workspace,
            declared: None,
            envir: None,
            history: None,
            call_session: None,
            call: None,
            chat_images: None,
            sessions: None,
            session_tmp: None,
            session_archive: None,
            image_limit: 0,
            image_temp_limit: 0,
            voice: None,
            transport: None,
            session_audio: None,
        }
    }

    /// 这一轮的语音现状（0.17c）：选中的 TTS / ASR 与唱歌能不能用。
    pub fn with_voice(mut self, voice: Option<&'a crate::voicecaps::VoiceCaps>) -> Self {
        self.voice = voice;
        self
    }

    /// 发请求的传输层（0.17d）。`Speak` 靠它；别的工具用不着。
    pub fn with_transport(
        mut self,
        transport: Option<&'a dyn crate::transport::Transport>,
    ) -> Self {
        self.transport = transport;
        self
    }

    /// 这条会话的音频目录（0.17d）。
    pub fn with_audio(mut self, dir: Option<&'a std::path::Path>) -> Self {
        self.session_audio = dir;
        self
    }

    /// 这个会话的附件目录与图片清单上限（D248）。
    pub fn with_images(mut self, dir: Option<&'a std::path::Path>, limit: u32) -> Self {
        self.chat_images = dir;
        self.image_limit = limit;
        self
    }

    /// 临时多留几张图（D272）：满了之后还能多放几张，那几张活到下一次思考结束。
    pub fn with_image_room(mut self, temp: u32) -> Self {
        self.image_temp_limit = temp;
        self
    }

    /// 这个会话自己的暂存目录（D294）：文件工具里的 `/tmp` 落到它。
    pub fn with_session_tmp(mut self, dir: Option<&'a std::path::Path>) -> Self {
        self.session_tmp = dir;
        self
    }

    /// 这个会话的归档目录（D317）：文件工具里的 `/archive/...` 落到它。
    pub fn with_session_archive(mut self, dir: Option<&'a std::path::Path>) -> Self {
        self.session_archive = dir;
        self
    }

    /// 别的会话查得到的目录（D295）。不给就是"看不到任何别的会话"。
    pub fn with_sessions(mut self, sessions: Option<&'a crate::session::SessionIndex>) -> Self {
        self.sessions = sessions;
        self
    }

    /// 这一轮属于哪条会话（手动关闭的登记键）。
    pub fn with_call_session(mut self, session: &'a str) -> Self {
        self.call_session = Some(session);
        self
    }

    /// 这一轮的会话历史（`edit_message` 解析目标消息用）。
    pub fn with_history(mut self, history: &'a [Message]) -> Self {
        self.history = Some(history);
        self
    }

    /// 这一轮有容器可用。
    pub fn with_envir(mut self, envir: &'a crate::shell::EnvirSpec) -> Self {
        self.envir = Some(envir);
        self
    }

    /// 用冻结好的名单声明工具。
    pub fn with_declared(mut self, declared: &'a [String]) -> Self {
        self.declared = Some(declared);
        self
    }

    /// 过门控。过了才返回工作区。
    pub(crate) fn gate(&self, capability: Capability) -> Result<&Workspace, ToolOutcome> {
        if let Err(reason) = self.policy.check(capability) {
            return Err(ToolOutcome::denied(capability, reason));
        }
        self.workspace
            .ok_or_else(|| ToolOutcome::denied(capability, DenyReason::NoWorkspace))
    }
}

/// 一次批次里这条工具能不能和别的调用一起跑（D254）。
///
/// 判据是"碰不碰共享状态"，不是"快不快"：读文件的慢和写文件的慢不是一回事，
/// 前者并行只是快一点，后者并行会把两个写撞在一起。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Lane {
    /// 只读：读文件、列目录、找文件、搜内容、把图拉进上下文。
    Read,
    /// 改东西（文件 / 消息）。**默认车道**——新工具不声明就串行，宁可慢也不要两个写撞上。
    Workspace,
    /// 各自独立的进程：两条 shell 一起跑。
    Process,
}

impl Lane {
    /// 这条车道最多几个一起跑。
    pub fn concurrency(self) -> usize {
        match self {
            Lane::Read => 4,
            Lane::Workspace => 1,
            Lane::Process => 4,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Lane::Read => "read",
            Lane::Workspace => "workspace",
            Lane::Process => "process",
        }
    }
}

/// 一个工具。
pub trait Tool: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn schema(&self) -> Value;
    /// 需要哪一项能力。门控在 [`Tool::invoke`] 的最前面用掉它。
    fn capability(&self) -> Capability;
    fn invoke(&self, arguments: &Value, ctx: &ToolContext<'_>) -> ToolOutcome;

    /// 这一批里它跟谁一起跑（D254）。默认 [`Lane::Workspace`]：不声明就串行。
    fn lane(&self) -> Lane {
        Lane::Workspace
    }

    /// 塞进 `ChatRequest::tools` 的声明。
    fn spec(&self) -> ToolSpec {
        ToolSpec::new(self.name(), self.schema()).describe(self.description())
    }
}

/// 名字 → 工具。
#[derive(Default)]
pub struct ToolRegistry {
    tools: BTreeMap<String, Box<dyn Tool>>,
}

impl ToolRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// 内置的六个工作区工具（读、写、改、列、找、搜）。
    pub fn workspace_defaults() -> Self {
        let mut registry = Self::new();
        registry.register(Box::new(ReadFileTool));
        registry.register(Box::new(WriteFileTool));
        registry.register(Box::new(EditFileTool));
        registry.register(Box::new(ListDirTool));
        registry.register(Box::new(FindFilesTool));
        registry.register(Box::new(SearchTool));
        // 原生命令（D159）：声明另外由 `Capability::NativeShell` 门控，默认不出现。
        registry.register(Box::new(crate::shell::SystemShellTool));
        // 把图拉进上下文（D248）：读权限门控，不碰工作区。
        registry.register(Box::new(crate::view_image::ViewImageTool));
        // 容器里的命令（D172）：由 `Capability::ContainerExec` 门控，同样默认不出现。
        registry.register(Box::new(crate::shell::EnvirShellTool));
        // 模型改消息（D177 / 0.14y）：与 `edit_file` 同档能力，但改的是会话不是磁盘。
        registry.register(Box::new(EditUnitTool));
        // 别的会话的 `/tmp` 与 `/chat`（D295）：`Capability::CrossSessionRead` 门控，
        // 默认关——关着时**不声明**，模型连它会去试的机会都没有。
        registry.register(Box::new(SessionFilesTool));
        // 语音能力表（0.17c）：只读、不联网，`Capability::Voice` 门控，默认关。
        registry.register(Box::new(crate::voicecaps::VoiceCapsTool));
        // 合成一段话（0.17d）：同一道门；只接 OpenAI 形状，别的明确回"还没接"。
        registry.register(Box::new(crate::speak::SpeakTool));
        registry
    }

    pub fn register(&mut self, tool: Box<dyn Tool>) {
        self.tools.insert(tool.name().to_string(), tool);
    }

    pub fn names(&self) -> Vec<&str> {
        self.tools.keys().map(String::as_str).collect()
    }

    pub fn len(&self) -> usize {
        self.tools.len()
    }

    pub fn is_empty(&self) -> bool {
        self.tools.is_empty()
    }

    pub fn remove(&mut self, name: &str) -> bool {
        self.tools.remove(name).is_some()
    }

    /// 按名单取声明表，顺序沿用注册顺序（不是名单顺序）。
    ///
    /// 名字在名单里但注册表里没有的，直接跳过——不能凭空造一条声明，
    /// 那会让模型去调一个必然失败的工具。
    pub fn specs_named(&self, names: &[String]) -> Vec<ToolSpec> {
        self.tools
            .iter()
            .filter(|(name, _)| names.iter().any(|picked| picked == *name))
            .map(|(_, tool)| tool.spec())
            .collect()
    }

    pub fn specs(&self) -> Vec<ToolSpec> {
        self.tools.values().map(|tool| tool.spec()).collect()
    }

    /// 按能力过滤后的声明表。
    ///
    /// 为什么要在发请求前过滤：模型看到 `write_file` 就会去写，而没开写权限时它拿到的是
    /// 一连串"拒绝执行"——白烧好几轮。不如一开始就不告诉它有这个工具。
    pub fn specs_for(&self, policy: &AssistantPolicy) -> Vec<ToolSpec> {
        self.tools
            .values()
            .filter(|tool| policy.allows(tool.capability()))
            .map(|tool| tool.spec())
            .collect()
    }

    /// 某个工具在哪条车道上（D254）。`None`（不认识的名字）按 `workspace` 算——
    /// 不认识的工具本来就只会回一句"未知工具"，让它串行不会有任何损失。
    pub fn lane_of(&self, name: &str) -> Lane {
        self.tools
            .get(name)
            .map(|tool| tool.lane())
            .unwrap_or(Lane::Workspace)
    }

    /// 某个工具需要哪一项能力。`None` = 没这个工具。
    ///
    /// 生效判定（`core::gate`）要按"开关在哪儿"把工具分到三轴，只有能力位能回答；
    /// UI 也要靠它显示"这个工具需要写权限"。
    pub fn capability_of(&self, name: &str) -> Option<Capability> {
        self.tools.get(name).map(|tool| tool.capability())
    }

    /// 调一个工具。名字不认识、参数不是合法 JSON、工具内部失败，都变成 [`ToolOutcome`]。
    pub fn invoke(&self, name: &str, arguments: &str, ctx: &ToolContext<'_>) -> ToolOutcome {
        let Some(tool) = self.tools.get(name) else {
            // 名字是空串时**把参数原文带上**：这是"上游把工具名丢了"的现场，
            // 只报一句 `未知工具 ``` 什么也说明不了（2026-11-03 真机就卡在这儿）。
            // 参数原文能看出它想调什么、也就知道是哪个字段没过来。
            if name.trim().is_empty() {
                let args = if arguments.trim().is_empty() {
                    "（空）".to_string()
                } else {
                    let mut text = arguments.trim().to_string();
                    if text.chars().count() > 200 {
                        text = text.chars().take(200).collect::<String>() + "…";
                    }
                    text
                };
                return ToolOutcome::failed(format!(
                    "工具名是空的（上游没给 name），参数是：{args}。可用的是：{}",
                    self.names().join(" / ")
                ));
            }
            return ToolOutcome::failed(format!(
                "未知工具 `{name}`。可用的是：{}",
                self.names().join(" / ")
            ));
        };
        let parsed: Value = if arguments.trim().is_empty() {
            json!({})
        } else {
            match serde_json::from_str(arguments) {
                Ok(value) => value,
                Err(error) => {
                    return ToolOutcome::failed(format!("参数不是合法 JSON：{error}"));
                }
            }
        };
        tool.invoke(&parsed, ctx)
    }
}

impl std::fmt::Debug for ToolRegistry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ToolRegistry")
            .field("tools", &self.names())
            .finish()
    }
}

// ---------------------------------------------------------------------------
// 参数小工具
// ---------------------------------------------------------------------------

pub(crate) fn required_str(args: &Value, key: &str) -> Result<String, ToolOutcome> {
    args.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| ToolOutcome::failed(format!("缺少字符串参数 `{key}`")))
}

fn optional_str(args: &Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn optional_usize(args: &Value, key: &str, default: usize) -> usize {
    args.get(key)
        .and_then(Value::as_u64)
        .map(|value| value as usize)
        .unwrap_or(default)
}

fn optional_bool(args: &Value, key: &str, default: bool) -> bool {
    args.get(key).and_then(Value::as_bool).unwrap_or(default)
}

/// 一次文件工具调用真正落到哪儿（D294 / D317）。
///
/// 文件工具只收 **工作区相对路径**，另有两个固定前缀：
///
/// - `/tmp` —— 这条会话自己的暂存目录（`sessions/<id>/tmp`），与容器里的 `/tmp` 是同一份；
/// - `/archive` —— 这条会话的归档（`sessions/<id>/archive`），与容器里的 `/archive` 是同一份。
///   **只读**：归档是库的那一份投影，改文件不改库，下一轮照样按库里的内容替换它。
///
/// 为什么不干脆接受更多绝对路径：绝对路径一旦放行，`Workspace::resolve` 那道
/// "只许落在根目录里"的检查就形同虚设。这里只映射**两个固定前缀**，别的一律照旧拒绝。
struct Target<'a> {
    workspace: std::borrow::Cow<'a, Workspace>,
    /// 相对 `workspace` 的路径；前缀本身给的是 `.`（等于那个根）。
    path: String,
    /// 这一处不许写（归档是库的投影）。
    read_only: bool,
}

/// `path` 是不是落在 `prefix` 这个虚拟根下；是的话给出它后面那一段（前缀本身给空串）。
fn under<'p>(path: &'p str, prefix: &str) -> Option<&'p str> {
    if path == prefix {
        return Some("");
    }
    path.strip_prefix(prefix)?.strip_prefix('/')
}

/// 把 `path` 解析成"哪个根 + 哪个相对路径"。
///
/// `/tmp` 与 `/archive` 没有会话上下文时**报错，不回落**：回落到宿主 `/tmp`
/// 等于让两个会话共用一份，回落到工作区等于让"取回"变成"读不到却说文件不存在"。
fn target<'a>(
    ctx: &ToolContext<'_>,
    workspace: &'a Workspace,
    path: &str,
) -> Result<Target<'a>, ToolOutcome> {
    let trimmed = path.trim();
    // 归档（D317）：先判它。文件不在就明说"取不到了"——
    // 这句话是给模型看的：那一行标记还在，但它手里已经没有原文了。
    if let Some(rest) = under(trimmed, crate::archive::ARCHIVE_DIR) {
        let Some(root) = ctx.session_archive else {
            return Err(ToolOutcome::failed(
                "`/archive` 是会话私有的，这一轮没有会话上下文（或这个历史实现不存归档）；\
                 用工作区相对路径，或先发一条消息让这一轮有会话。"
                    .to_string(),
            ));
        };
        if !rest.is_empty() && !root.join(rest).exists() {
            return Err(ToolOutcome::failed(format!(
                "取不到了：`{trimmed}` 不在。这一轮的那段归档已经没了\
                 （会话删掉时整目录一起删，重新生成的历史也不会补回来）；\
                 只能按标记那一行里的 token 数与描述处理，别再假设原文还在。"
            )));
        }
        let opened = Workspace::open(root).map_err(|error| {
            ToolOutcome::failed(format!(
                "会话归档目录建不出来（{}）：{error}",
                root.display()
            ))
        })?;
        return Ok(Target {
            workspace: std::borrow::Cow::Owned(opened),
            path: if rest.is_empty() {
                ".".to_string()
            } else {
                rest.to_string()
            },
            read_only: true,
        });
    }
    let Some(rest) = under(trimmed, "/tmp") else {
        return Ok(Target {
            workspace: std::borrow::Cow::Borrowed(workspace),
            path: trimmed.to_string(),
            read_only: false,
        });
    };
    let Some(root) = ctx.session_tmp else {
        return Err(ToolOutcome::failed(
            "`/tmp` 是会话私有的，这一轮没有会话上下文（或这个历史实现不存会话目录）；\
             用工作区相对路径，或先发一条消息让这一轮有会话。"
                .to_string(),
        ));
    };
    let scratch = Workspace::open(root).map_err(|error| {
        ToolOutcome::failed(format!(
            "会话暂存目录建不出来（{}）：{error}",
            root.display()
        ))
    })?;
    Ok(Target {
        workspace: std::borrow::Cow::Owned(scratch),
        path: if rest.is_empty() {
            ".".to_string()
        } else {
            rest.to_string()
        },
        read_only: false,
    })
}

/// 工作区错误 → 给模型看的话。带上"怎么办"，不只是"错了"。
fn workspace_error(error: WorkspaceError) -> ToolOutcome {
    let hint = match &error {
        WorkspaceError::OutsideRoot { .. } => "只能访问工作区内的路径；会话暂存写 `/tmp/...`。",
        WorkspaceError::NotFound { .. } => "路径不存在；先用 list_dir 或 find_files 看一眼。",
        WorkspaceError::AmbiguousMatch { .. } => {
            "原文片段出现了多次；多带几行上下文，或把 replace_all 设为 true。"
        }
        WorkspaceError::NoMatch { .. } => "原文片段没找到；先 read_file 读一遍再改。",
        WorkspaceError::FileTooLarge { .. } => "文件太大；用 force_full 或分段读。",
        _ => "",
    };
    if hint.is_empty() {
        ToolOutcome::failed(error.to_string())
    } else {
        ToolOutcome::failed(format!("{error}（{hint}）"))
    }
}

// ---------------------------------------------------------------------------
// 六个内置工具
// ---------------------------------------------------------------------------

/// 跨会话只读（D295）：读**别的会话**的暂存目录（`tmp`）或会话目录（`chat`）。
///
/// 存在的理由只有一个：上下文里不会自动带任何会话 id（用户 2026-11-08 明确
/// 「需要用户去给出会话和 ID」），所以"接着上一条会话里的东西干"这件事，
/// 只能由用户把 id 递进来。开关关着时这个工具**不声明**——不声明，模型就不会去试。
pub struct SessionFilesTool;

/// 单次读的上限。跨会话读的是"别人的东西"，一次读爆内存没有道理。
const SESSION_FILES_MAX_BYTES: u64 = 256 * 1024;

impl SessionFilesTool {
    /// `area` 只认这两个词：会话私有的那两块。
    fn area_of(raw: &str) -> Option<&'static str> {
        match raw.trim() {
            "tmp" => Some("tmp"),
            "chat" => Some("chat"),
            _ => None,
        }
    }
}

impl Tool for SessionFilesTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "Session-Files"
    }

    fn description(&self) -> &str {
        concat!(
            "读**别的会话**的暂存目录（area=tmp）或会话目录（area=chat）。只读。\n",
            "session_id 必须由用户给：侧边栏长按会话 → 详细信息 → 长按「会话 ID」复制；",
            "上下文里不会有任何会话 id，也不要去猜。\n",
            "path 留空 = 列这一层；mode=read 读一个文件（单次上限 256 KB）。",
            "结果会标出这是哪个会话的东西，别把它当成本会话的设定。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "目标会话 id（用户复制给你的那一串）"
                },
                "area": {
                    "type": "string",
                    "description": "tmp（会话暂存）或 chat（会话目录）"
                },
                "path": {
                    "type": "string",
                    "description": "相对 area 的路径；留空 = 列这一层"
                },
                "mode": {
                    "type": "string",
                    "description": "list（默认）或 read"
                }
            },
            "required": ["session_id", "area"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::CrossSessionRead
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        // 门控在最前面（三条硬规矩之一）：没开这一项，连 id 都不解析。
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let Some(index) = ctx.sessions else {
            return ToolOutcome::failed(
                "这一轮读不了别的会话：跨会话读取没开，或者这个历史实现没有会话目录。",
            );
        };
        let raw_id = match required_str(args, "session_id") {
            Ok(id) => id,
            Err(outcome) => return outcome,
        };
        let raw_area = match required_str(args, "area") {
            Ok(area) => area,
            Err(outcome) => return outcome,
        };

        // 1 形状：拿真解析器验，不手搓正则——手搓的那套迟早和 id 的真形状不一致。
        let id = match raw_id.trim().parse::<SessionId>() {
            Ok(id) => id,
            Err(_) => {
                return ToolOutcome::failed(format!(
                    "`session_id` 不是会话 id：{}。形如 \
                     0199f0c3-1b2a-7c4d-8e5f-0a1b2c3d4e5f（36 位，带连字符）；\
                     从侧边栏长按会话 → 详细信息 → 长按「会话 ID」复制。",
                    raw_id.trim()
                ))
            }
        };
        let id = id.to_string();

        // 2 存在性：没有就点名报错，**不猜一个最像的**。
        if !index.exists(&id) {
            return ToolOutcome::failed(format!(
                "没有这个会话：{id} 可能已经被删了（找不到就不猜，宁可空着）。"
            ));
        }
        let Some(area) = SessionFilesTool::area_of(&raw_area) else {
            return ToolOutcome::failed(format!(
                "`area` 只认 `tmp` 或 `chat`，收到的是 `{}`。",
                raw_area.trim()
            ));
        };
        let Some(base) = index.area_dir(&id, area) else {
            return ToolOutcome::failed(format!("这个会话没有 `{area}` 目录。"));
        };

        // 3 路径：先按字面拼，`..` 与绝对路径一律拒；再问一次真实路径有没有跑出去
        //   （符号链接可能指到别处）。
        let relative = optional_str(args, "path").unwrap_or_default();
        let target = match join_area(&base, &relative) {
            Ok(path) => path,
            Err(message) => return ToolOutcome::failed(message),
        };
        let mode = optional_str(args, "mode").unwrap_or_else(|| "list".to_string());
        let from = match ctx.call_session {
            Some(current) if current == id => "这个会话自己".to_string(),
            _ => format!(
                "另一个助手「{}」",
                index
                    .title(&id)
                    .filter(|title| !title.is_empty())
                    .unwrap_or("（没有标题）")
            ),
        };

        match mode.as_str() {
            "list" => match list_area(&base, &target, &relative) {
                Ok(text) => ToolOutcome::ok(format!("[来自{from} · {area}/{relative}]\n{text}")),
                Err(message) => ToolOutcome::failed(message),
            },
            "read" => match read_area(&base, &target, &relative) {
                Ok(text) => ToolOutcome::ok(format!("[来自{from} · {area}/{relative}]\n{text}")),
                Err(message) => ToolOutcome::failed(message),
            },
            other => ToolOutcome::failed(format!(
                "`mode` 只认 `list` 或 `read`，收到的是 `{other}`。"
            )),
        }
    }
}

/// 按字面拼出一条落在 `base` 里的路径。`..`、绝对路径、盘符一律拒（先把错说清楚，
/// 再谈能不能读）。
fn join_area(base: &std::path::Path, relative: &str) -> Result<std::path::PathBuf, String> {
    let mut out = base.to_path_buf();
    for part in relative.split('/') {
        match part {
            "" | "." => continue,
            ".." => {
                return Err(format!(
                    "`path` 越界：`{relative}` 里有 `..`，只能往 `{}` 里面看。",
                    base.display()
                ))
            }
            other if other.contains('\\') || other.contains(':') => {
                return Err(format!("`path` 里不该有 `\\` 或驱动器号：`{relative}`。"))
            }
            other => out.push(other),
        }
    }
    Ok(out)
}

/// 真实路径必须还在 `base` 里（符号链接、`..` 都已经过一道，这里再验一次）。
fn inside(base: &std::path::Path, target: &std::path::Path) -> Result<(), String> {
    let real_base = std::fs::canonicalize(base).unwrap_or_else(|_| base.to_path_buf());
    let real_target = std::fs::canonicalize(target).map_err(|_| {
        format!(
            "没有这个路径：{}（`path` 留空可以列这一层）",
            target.strip_prefix(base).unwrap_or(target).display()
        )
    })?;
    if !real_target.starts_with(real_base) {
        return Err(format!("`path` 跑出了这个会话的目录：{}", target.display()));
    }
    Ok(())
}

fn list_area(
    base: &std::path::Path,
    target: &std::path::Path,
    relative: &str,
) -> Result<String, String> {
    if !target.exists() {
        return Err(format!(
            "这个位置还没有东西：`{}`（第一次用到才会建）。",
            if relative.is_empty() { "根" } else { relative }
        ));
    }
    inside(base, target)?;
    if !target.is_dir() {
        return Err(format!(
            "`{}` 是个文件；用 mode=read 读它。",
            if relative.is_empty() { "根" } else { relative }
        ));
    }
    let mut entries: Vec<_> = std::fs::read_dir(target)
        .map_err(|error| format!("列不出来（{}）：{error}", target.display()))?
        .flatten()
        .collect();
    entries.sort_by_key(|entry| entry.file_name());
    if entries.is_empty() {
        return Ok("（空的）\n".to_string());
    }
    let mut text = String::new();
    for entry in entries {
        let name = entry.file_name().to_string_lossy().to_string();
        let meta = entry.metadata().ok();
        let is_dir = meta.as_ref().map(|meta| meta.is_dir()).unwrap_or(false);
        let size = meta.as_ref().map(|meta| meta.len()).unwrap_or(0);
        text.push_str(&format!(
            "{name}{}  {size} 字节\n",
            if is_dir { "/" } else { "" }
        ));
    }
    Ok(text)
}

fn read_area(
    base: &std::path::Path,
    target: &std::path::Path,
    relative: &str,
) -> Result<String, String> {
    if relative.is_empty() {
        return Err("mode=read 要给 `path`；留空是列目录（mode=list）。".to_string());
    }
    inside(base, target)?;
    let meta = std::fs::metadata(target)
        .map_err(|_| format!("没有这个文件：`{relative}`（先用 mode=list 看一眼这一层有什么）"))?;
    if meta.is_dir() {
        return Err(format!("`{relative}` 是目录；用 mode=list 列它。"));
    }
    if meta.len() > SESSION_FILES_MAX_BYTES {
        return Err(format!(
            "这个文件 {} KB，超过 {} KB 上限；换个小一点的，或者只取其中一部分（分段参数还没做）。",
            meta.len() / 1024,
            SESSION_FILES_MAX_BYTES / 1024
        ));
    }
    let bytes = std::fs::read(target).map_err(|error| format!("读不出来：{error}"))?;
    if bytes.contains(&0) {
        return Err(format!("`{relative}` 是二进制文件，这里只读文本。"));
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

pub struct ReadFileTool;
impl Tool for ReadFileTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "read_file"
    }

    fn description(&self) -> &str {
        "读工作区里的一个文本文件。默认按三级截断返回（行数 / 字节 / 单行长度），\
         带行号。要整篇就把 force_full 设成 true。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": { "type": "string", "description": "相对工作区根目录的路径" },
                "start_line": { "type": "integer", "description": "起始行，1-based，默认 1" },
                "max_lines": { "type": "integer", "description": "最多读多少行，默认 2000" },
                "force_full": { "type": "boolean", "description": "忽略所有上限（谨慎）" },
                "numbered": { "type": "boolean", "description": "是否带行号，默认 true" }
            },
            "required": ["path"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceRead
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let path = match required_str(args, "path") {
            Ok(path) => path,
            Err(outcome) => return outcome,
        };
        let spot = match target(ctx, workspace, &path) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        let defaults = ReadOptions::default();
        let options = ReadOptions {
            start_line: optional_usize(args, "start_line", 1).max(1),
            max_lines: optional_usize(args, "max_lines", defaults.max_lines),
            numbered: optional_bool(args, "numbered", true),
            force_full: optional_bool(args, "force_full", false),
            ..defaults
        };
        match spot.workspace.read_file(&spot.path, &options) {
            Ok(result) => {
                let mut text = format!(
                    "[{} | {} 行 / {} 字节",
                    result.path, result.total_lines, result.total_bytes
                );
                if let Some(truncation) = &result.truncation {
                    text.push_str(&format!(
                        " | 被截断（{:?}），还有 {} 行 / {} 字节",
                        truncation.reason, truncation.remaining_lines, truncation.remaining_bytes
                    ));
                }
                text.push_str("]\n");
                text.push_str(&result.content);
                if let Some(hint) = &result.hint {
                    text.push_str(&format!("\n[{hint}]"));
                }
                if result.truncated {
                    ToolOutcome::ok_truncated(text)
                } else {
                    ToolOutcome::ok(text)
                }
            }
            Err(error) => workspace_error(error),
        }
    }
}

pub struct WriteFileTool;

impl Tool for WriteFileTool {
    fn name(&self) -> &str {
        "write_file"
    }

    fn description(&self) -> &str {
        "整篇覆盖写一个文件（目录会自动建）。要改局部请用 edit_file——覆盖会丢掉没读到的东西。\
         `path` 相对工作区；写 `/tmp/...` 是这个会话自己的暂存目录，别的会话看不见。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": { "type": "string", "description": "相对工作区根目录的路径" },
                "content": { "type": "string", "description": "完整文件内容" }
            },
            "required": ["path", "content"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let path = match required_str(args, "path") {
            Ok(path) => path,
            Err(outcome) => return outcome,
        };
        let content = match required_str(args, "content") {
            Ok(content) => content,
            Err(outcome) => return outcome,
        };
        let spot = match target(ctx, workspace, &path) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        if spot.read_only {
            return ToolOutcome::failed(format!(
                "`{}` 是归档，只读：它是库里那一份的投影，写进去下一次照样被库里的内容覆盖。\n\
                 要改那一段内容，用 edit_message 改对应的那条消息。",
                path.trim()
            ));
        }
        match spot.workspace.write_file(&spot.path, &content) {
            Ok(result) => {
                ToolOutcome::ok(format!("已写入 {}（{} 字节）", result.path, result.bytes))
            }
            Err(error) => workspace_error(error),
        }
    }
}

pub struct EditFileTool;

impl Tool for EditFileTool {
    fn name(&self) -> &str {
        "edit_file"
    }

    fn description(&self) -> &str {
        "把文件里的一段原文换成新文本。默认要求**唯一匹配**；出现多处会报错，不会猜。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": { "type": "string", "description": "相对工作区根目录的路径" },
                "old": { "type": "string", "description": "要被替换的原文，必须唯一匹配" },
                "new": { "type": "string", "description": "替换成什么" },
                "replace_all": { "type": "boolean", "description": "是否替换全部匹配，默认 false" }
            },
            "required": ["path", "old", "new"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let path = match required_str(args, "path") {
            Ok(path) => path,
            Err(outcome) => return outcome,
        };
        let old = match required_str(args, "old") {
            Ok(old) => old,
            Err(outcome) => return outcome,
        };
        let new = match required_str(args, "new") {
            Ok(new) => new,
            Err(outcome) => return outcome,
        };
        let replace_all = optional_bool(args, "replace_all", false);
        let spot = match target(ctx, workspace, &path) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        if spot.read_only {
            return ToolOutcome::failed(format!(
                "`{}` 是归档，只读：它是库里那一份的投影，改了不生效。要改内容用 edit_message。",
                path.trim()
            ));
        }
        match spot
            .workspace
            .edit_file(&spot.path, &old, &new, replace_all)
        {
            Ok(result) => ToolOutcome::ok(format!(
                "已改 {}：{} 处，{} → {} 字节",
                result.path, result.replacements, result.bytes_before, result.bytes_after
            )),
            Err(error) => workspace_error(error),
        }
    }
}

pub struct ListDirTool;

impl Tool for ListDirTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "list_dir"
    }

    fn description(&self) -> &str {
        "列一个目录（默认工作区根）。带大小，目录以 `/` 结尾。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": { "type": "string", "description": "相对路径，留空表示根目录" },
                "depth": { "type": "integer", "description": "递归深度，默认 1" },
                "include_hidden": { "type": "boolean", "description": "是否包含隐藏文件，默认 false" },
                "max_entries": { "type": "integer", "description": "最多返回多少条，默认 500" }
            }
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceRead
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let path = optional_str(args, "path").unwrap_or_default();
        let spot = match target(ctx, workspace, &path) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        let defaults = ListOptions::default();
        let options = ListOptions {
            depth: optional_usize(args, "depth", defaults.depth).max(1),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            max_entries: optional_usize(args, "max_entries", defaults.max_entries),
        };
        match spot.workspace.list_dir(&spot.path, &options) {
            Ok(result) => {
                let mut text = format!("[{} | {} 条]\n", result.path, result.entries.len());
                for entry in &result.entries {
                    text.push_str(&format!(
                        "{}{}  {} 字节\n",
                        entry.path,
                        if entry.is_dir { "/" } else { "" },
                        entry.size_bytes
                    ));
                }
                if result.entries.len() >= options.max_entries {
                    text.push_str("[已截断：条目数达到 max_entries]\n");
                    ToolOutcome::ok_truncated(text)
                } else {
                    ToolOutcome::ok(text)
                }
            }
            Err(error) => workspace_error(error),
        }
    }
}

pub struct FindFilesTool;

impl Tool for FindFilesTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "find_files"
    }

    fn description(&self) -> &str {
        "按通配符找文件（`*` 不跨目录，`**` 跨目录），例如 `**/*.rs`。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "pattern": { "type": "string", "description": "通配符模式" },
                "path": { "type": "string", "description": "从哪个目录开始，留空表示根" },
                "max_results": { "type": "integer", "description": "最多返回多少条，默认 200" },
                "include_hidden": { "type": "boolean", "description": "是否包含隐藏文件，默认 false" }
            },
            "required": ["pattern"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceSearch
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let pattern = match required_str(args, "pattern") {
            Ok(pattern) => pattern,
            Err(outcome) => return outcome,
        };
        let defaults = crate::workspace::FindOptions::default();
        let wanted = optional_str(args, "path").unwrap_or_default();
        let spot = match target(ctx, workspace, &wanted) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        let options = crate::workspace::FindOptions {
            path: spot.path.clone(),
            max_results: optional_usize(args, "max_results", defaults.max_results),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            ..defaults
        };
        match spot.workspace.find(&pattern, &options) {
            Ok(result) => {
                let mut text = format!("[{} | {} 个匹配]\n", result.pattern, result.matches.len());
                for path in &result.matches {
                    text.push_str(path);
                    text.push('\n');
                }
                if result.matches.len() >= options.max_results {
                    text.push_str("[已截断：达到 max_results]\n");
                    ToolOutcome::ok_truncated(text)
                } else {
                    ToolOutcome::ok(text)
                }
            }
            Err(error) => workspace_error(error),
        }
    }
}

pub struct SearchTool;

impl Tool for SearchTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "search"
    }

    fn description(&self) -> &str {
        "在工作区里搜文本，返回 `路径:行号: 内容`。默认区分大小写、跳过二进制与超大文件。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "query": { "type": "string", "description": "要找的文本" },
                "path": { "type": "string", "description": "从哪个目录开始，留空表示根" },
                "ignore_case": { "type": "boolean", "description": "是否忽略大小写，默认 false" },
                "max_results": { "type": "integer", "description": "最多返回多少条，默认 100" },
                "include_hidden": { "type": "boolean", "description": "是否包含隐藏文件，默认 false" }
            },
            "required": ["query"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceSearch
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let query = match required_str(args, "query") {
            Ok(query) => query,
            Err(outcome) => return outcome,
        };
        let defaults = SearchOptions::default();
        let wanted = optional_str(args, "path").unwrap_or_default();
        let spot = match target(ctx, workspace, &wanted) {
            Ok(spot) => spot,
            Err(outcome) => return outcome,
        };
        let options = SearchOptions {
            path: spot.path.clone(),
            ignore_case: optional_bool(args, "ignore_case", defaults.ignore_case),
            max_results: optional_usize(args, "max_results", defaults.max_results),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            ..defaults
        };
        match spot.workspace.search(&query, &options) {
            Ok(result) => {
                let mut text = format!("[{} | {} 处命中]\n", result.query, result.hits.len());
                for hit in &result.hits {
                    text.push_str(&format!("{}:{}: {}\n", hit.path, hit.line, hit.text));
                }
                if result.hits.len() >= options.max_results {
                    text.push_str("[已截断：达到 max_results]\n");
                    ToolOutcome::ok_truncated(text)
                } else {
                    ToolOutcome::ok(text)
                }
            }
            Err(error) => workspace_error(error),
        }
    }
}

// ---------------------------------------------------------------------------
// edit_message：模型改历史消息（D177 / 0.14y）
// ---------------------------------------------------------------------------

/// 一次调用最多改几块。多了模型自己也绕不清，而八块已经够长。
pub const MAX_EDITS_PER_CALL: usize = 8;

/// 解析好但**还没落到具体消息上**的一次 `edit_message` 调用。
///
/// 工具（说"已排队"）与收尾落库（真写进去）共用这一份解析结果：
/// 两处各解析一遍，迟早会出现"工具说改了三块、库里只写了两块"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditCall {
    /// `last` / `last_assistant` / `last_user` / `<session>:<seq>`。
    pub target: String,
    pub edits: Vec<(BlockSelector, String)>,
}

/// 块的中文名。**只有一处实现**（`edit::block_label`）：工具说的话和报告里的字必须同源。
pub fn block_label(block: BlockSelector) -> String {
    crate::edit::block_label(block)
}

/// 模型给的参数 → 一组块级改动。形状不对就点名，**不猜**。
///
/// 这一层只管"参数本身站不站得住"（块名、必填项、JSON、重复），
/// 目标消息存不存在、有没有被锁定，要等拿到历史才说得清（[`resolve_edit_target`]）。
pub fn parse_edit_message(args: &Value) -> Result<EditCall, String> {
    let target = args
        .get("message_id")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|target| !target.is_empty())
        .ok_or_else(|| {
            "缺少字符串参数 `message_id`（具体 id，或 last_assistant / last_user / last）"
                .to_string()
        })?
        .to_string();

    let items = args
        .get("blocks")
        .and_then(Value::as_array)
        .ok_or_else(|| "缺少数组参数 `blocks`（每一项 {kind, index?, content}）".to_string())?;
    if items.is_empty() {
        return Err("`blocks` 是空的：要改就指明改哪一块".into());
    }
    if items.len() > MAX_EDITS_PER_CALL {
        return Err(format!(
            "一次最多改 {MAX_EDITS_PER_CALL} 块，这次给了 {} 块",
            items.len()
        ));
    }

    let mut edits: Vec<(BlockSelector, String)> = Vec::with_capacity(items.len());
    for (position, item) in items.iter().enumerate() {
        let which = position + 1;
        let kind = item
            .get("kind")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("第 {which} 块缺少字符串 `kind`（output / thinking / tool）"))?;
        let block = match kind {
            "output" => BlockSelector::Output,
            "thinking" => BlockSelector::Thinking,
            "tool" => {
                let index = item
                    .get("index")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| format!("第 {which} 块是工具参数，必须给 `index`（0 起）"))?
                    as usize;
                if index >= BlockSelector::MAX_TOOL_INDEX {
                    return Err(format!(
                        "第 {which} 块的 `index` 必须是 0–{} 的整数，收到 {index}",
                        BlockSelector::MAX_TOOL_INDEX - 1
                    ));
                }
                BlockSelector::ToolCall { index }
            }
            other => {
                return Err(format!(
                    "第 {which} 块的 kind `{other}` 不认识：只能是 output / thinking / tool"
                ))
            }
        };

        let content = item
            .get("content")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("第 {which} 块缺少字符串 `content`"))?;
        if content.len() > MAX_BLOCK_BYTES {
            return Err(format!(
                "第 {which} 块太长（{} 字节，上限 {MAX_BLOCK_BYTES}）",
                content.len()
            ));
        }
        match block {
            // 正文是一轮回复的本体，清空它等于把那条消息变成空壳（`Message::validate` 也拒）。
            BlockSelector::Output if content.trim().is_empty() => {
                return Err(format!("第 {which} 块是正文，不能改成空的"));
            }
            // 工具参数决定下一次调用怎么发出去，坏 JSON 会被上游 400（D158）。
            BlockSelector::ToolCall { index } => {
                let parsed = serde_json::from_str::<Value>(content).ok();
                if !parsed.is_some_and(|value| value.is_object()) {
                    return Err(format!(
                        "第 {which} 块（工具参数 #{index}）不是 JSON 对象：参数会原样发给上游，坏了下一次请求直接 400"
                    ));
                }
            }
            _ => {}
        }

        if edits.iter().any(|(existing, _)| *existing == block) {
            return Err(format!(
                "同一次调用里把「{}」写了两遍：落库按顺序来，后一次会把前一次盖掉",
                block_label(block)
            ));
        }
        edits.push((block, content.to_string()));
    }

    Ok(EditCall { target, edits })
}

/// 一次 `edit_unit` 调用：目标消息 + 一组块级动作（0.15f / D188）。
///
/// 与 0.14y 的 [`parse_edit_message`] 的关系：那个是"只改不删"的旧形状，
/// 这里多一个 `delete`，也多一个 `tool_result`（结果在另一条消息上）。
/// 旧形状（`blocks`）照样收——模型记着的写法不该在升级当天就失效。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditUnitCall {
    pub target: String,
    pub ops: Vec<BlockOp>,
}

/// 模型给的参数 → 一组块级动作。
///
/// **这一层只管"参数本身站不站得住"**（动作名、块名、重复、JSON 形状）；
/// 目标消息在不在、单元闭没闭合、工具块成不成对，要等拿到单元才说得清（`edit::plan_ops`）。
pub fn parse_edit_unit(args: &Value) -> Result<EditUnitCall, String> {
    // 旧形状：没有 `ops` 就按 0.14y 的 `blocks` 读，全部当作 set。
    if args.get("ops").is_none() {
        let call = parse_edit_message(args)?;
        return Ok(EditUnitCall {
            target: call.target,
            ops: call
                .edits
                .into_iter()
                .map(|(block, content)| BlockOp::Set { block, content })
                .collect(),
        });
    }

    let target = args
        .get("message_id")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|target| !target.is_empty())
        .ok_or_else(|| {
            "缺少字符串参数 `message_id`（具体 id，或 last_assistant / last_user / last）"
                .to_string()
        })?
        .to_string();

    let items = args.get("ops").and_then(Value::as_array).ok_or_else(|| {
        "缺少数组参数 `ops`（每一项 {action, block, index?, content?}）".to_string()
    })?;
    if items.is_empty() {
        return Err("`ops` 是空的：要改就指明改哪一块".into());
    }
    if items.len() > MAX_EDITS_PER_CALL {
        return Err(format!(
            "一次最多 {MAX_EDITS_PER_CALL} 项，这次给了 {} 项",
            items.len()
        ));
    }

    let mut ops: Vec<BlockOp> = Vec::with_capacity(items.len());
    for (position, item) in items.iter().enumerate() {
        let which = position + 1;
        let action = item
            .get("action")
            .and_then(Value::as_str)
            .unwrap_or("set")
            .trim();
        let name = item.get("block").and_then(Value::as_str).ok_or_else(|| {
            format!("第 {which} 项缺少字符串 `block`（output / thinking / tool / tool_result）")
        })?;
        let block = match name {
            "output" => BlockSelector::Output,
            "thinking" => BlockSelector::Thinking,
            "tool" | "tool_call" | "tool_result" => {
                let index = item
                    .get("index")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| {
                        format!("第 {which} 项是工具块（{name}），必须给 `index`（0 起）")
                    })? as usize;
                if index >= BlockSelector::MAX_TOOL_INDEX {
                    return Err(format!(
                        "第 {which} 项的 `index` 必须是 0–{} 的整数，收到 {index}",
                        BlockSelector::MAX_TOOL_INDEX - 1
                    ));
                }
                if name == "tool_result" {
                    BlockSelector::ToolResult { index }
                } else {
                    BlockSelector::ToolCall { index }
                }
            }
            "part" => {
                let id = item
                    .get("id")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|id| !id.is_empty())
                    .ok_or_else(|| {
                        format!("第 {which} 项按 part 定位，必须给 `id`（例如 \"p3\"）")
                    })?;
                if id.len() > 32 || !id.starts_with('p') {
                    return Err(format!("第 {which} 项的 `id` 不像 part id：{id}"));
                }
                let output = item
                    .get("output")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                BlockSelector::Part {
                    id: id.to_string(),
                    output,
                }
            }
            other => {
                return Err(format!(
                    "第 {which} 项的 block `{other}` 不认识：只能是 output / thinking / tool / tool_result / part"
                ))
            }
        };

        let op = match action {
            "set" => {
                let content = item
                    .get("content")
                    .and_then(Value::as_str)
                    .ok_or_else(|| format!("第 {which} 项是 set，缺少字符串 `content`"))?;
                if content.len() > MAX_BLOCK_BYTES {
                    return Err(format!(
                        "第 {which} 项太长（{} 字节，上限 {MAX_BLOCK_BYTES}）",
                        content.len()
                    ));
                }
                match block {
                    // 正文是一轮回复的本体，清空它等于把那条消息变成空壳。
                    BlockSelector::Output if content.trim().is_empty() => {
                        return Err(format!("第 {which} 项是正文，不能改成空的"));
                    }
                    // 工具参数决定下一次调用怎么发出去，坏 JSON 会被上游 400（D158）。
                    BlockSelector::ToolCall { index } => {
                        let parsed = serde_json::from_str::<Value>(content).ok();
                        if !parsed.is_some_and(|value| value.is_object()) {
                            return Err(format!(
                                "第 {which} 项（工具参数 #{index}）不是 JSON 对象：参数会原样发给上游，坏了下一次请求直接 400"
                            ));
                        }
                    }
                    _ => {}
                }
                BlockOp::Set {
                    block: block.clone(),
                    content: content.to_string(),
                }
            }
            "delete" => BlockOp::Delete {
                block: block.clone(),
            },
            other => {
                return Err(format!(
                    "第 {which} 项的 action `{other}` 不认识：只能是 set / delete"
                ))
            }
        };

        // set 与 delete 同一块也算重复：一个块在一次调用里只能有一个结果。
        if ops.iter().any(|existing| existing.block() == block) {
            return Err(format!(
                "同一次调用里把「{}」写了两遍：落库按顺序来，后一次会把前一次盖掉",
                crate::edit::block_label(block)
            ));
        }
        ops.push(op);
    }

    Ok(EditUnitCall { target, ops })
}

/// `message_id` → 具体一条消息。
///
/// `last*` 不是偷懒：模型看得见消息内容，看不见 id（id 不随请求发出去），
/// 只给"每条都必须是 `<session>:<seq>`"等于让模型去猜一串 uuid。
pub fn resolve_edit_target(target: &str, history: &[Message]) -> Result<MessageId, String> {
    let last_with = |role: Role| {
        history
            .iter()
            .rev()
            .find(|message| message.role == role)
            .map(|message| message.id.clone())
    };
    match target {
        "last" | "last_assistant" => {
            last_with(Role::Assistant).ok_or_else(|| "会话里还没有助手消息可改".to_string())
        }
        "last_user" => last_with(Role::User).ok_or_else(|| "会话里还没有用户消息可改".to_string()),
        other => {
            let wanted: MessageId = other.parse().map_err(|_| {
                format!(
                    "message_id `{other}` 不是一条消息的 id（形如 <session>:<seq>），\
                     也不是 last / last_assistant / last_user"
                )
            })?;
            if history.iter().any(|message| message.id == wanted) {
                Ok(wanted)
            } else {
                Err(format!(
                    "这次会话里没有消息 {wanted}（只能改已经存在的消息）"
                ))
            }
        }
    }
}

/// 模型改消息（0.15f 起叫 `edit_unit`，0.14y 叫 `edit_message`）。**只排队，不落库**——
/// core 不碰存储，真正写进去发生在这一轮收尾（`session::run_session_turn` 第 3.5 步）。
///
/// 也就是说，那句话里必须写"这一轮结束时落库"，而不是"已修改"：
/// 模型看到"改好了"就会对用户复述改好了，而用户此时还没看到任何变化。
pub struct EditUnitTool;

impl Tool for EditUnitTool {
    fn name(&self) -> &str {
        "edit_unit"
    }

    fn description(&self) -> &str {
        "改一条已经存在的消息里的块（正文 / 思考 / 工具参数 / 工具结果），这一轮结束时生效。\
         改哪条用 message_id：给具体 id，或 last_assistant（最近一条助手消息）、\
         last_user（最近一条用户消息）。action=set 写内容（思考块给空串等于删掉它）；\
         action=delete 删块——只有思考块与工具对能删，而且工具块**必须成对删**\
         （第 N 个调用与它的结果一起删，单独删一边会让下一轮请求直接 400）。\
         一次最多 8 项，同一个块不能写两遍；单元还没闭合（有调用没拿到结果）时不许改。\
         正文改写会留下原文：界面上出现红/蓝两份，蓝的那份是改之前。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "message_id": {
                    "type": "string",
                    "description": "要改的消息：具体 id，或 last_assistant / last_user / last"
                },
                "ops": {
                    "type": "array",
                    "description": "改哪几块（一次最多 8 项）",
                    "items": {
                        "type": "object",
                        "properties": {
                            "action": {
                                "type": "string",
                                "enum": ["set", "delete"],
                                "description": "set=写内容（默认），delete=删块（只有思考块与工具对能删）"
                            },
                            "block": {
                                "type": "string",
                                "enum": ["output", "thinking", "tool", "tool_result"],
                                "description": "output=正文，thinking=思考块，tool=某个工具调用的参数，tool_result=某个工具调用的结果"
                            },
                            "index": {
                                "type": "integer",
                                "description": "block=tool / tool_result 时必填，第几个工具调用（0 起）"
                            },
                            "content": {
                                "type": "string",
                                "description": "action=set 时的新内容；thinking 给空串表示删掉这一块"
                            }
                        },
                        "required": ["block"]
                    }
                }
            },
            "required": ["message_id", "ops"]
        })
    }

    /// 与 `edit_file` 同档（D177）：改内容这件事归"写"那一类，不新增开关。
    /// 注意**不要求工作区**：这里改的是会话里的消息，不是磁盘。
    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let call = match parse_edit_unit(args) {
            Ok(call) => call,
            Err(message) => return ToolOutcome::failed(message),
        };
        let Some(history) = ctx.history else {
            return ToolOutcome::failed("这一轮没拿到会话历史，改不了消息");
        };
        let message_id = match resolve_edit_target(&call.target, history) {
            Ok(id) => id,
            Err(message) => return ToolOutcome::failed(message),
        };
        let Some(message) = history.iter().find(|message| message.id == message_id) else {
            return ToolOutcome::failed(format!("这次会话里没有消息 {message_id}"));
        };
        if message.locked {
            return ToolOutcome::failed(format!(
                "消息 {message_id} 被用户锁定，内容不能改（压缩不受影响）"
            ));
        }

        let call_count = message.tool_calls().len();
        for op in &call.ops {
            let block = op.block();
            // 正文没有"删掉"这一说：删了这条消息就是空壳。
            if op.is_delete() && block == BlockSelector::Output && message.role != Role::Tool {
                return ToolOutcome::failed(
                    "正文不能删（删掉正文等于把这条消息变成空壳）：要换内容就用 action=set",
                );
            }
            let Some(index) = block.tool_index() else {
                continue;
            };
            if index >= call_count {
                return ToolOutcome::failed(format!(
                    "消息 {message_id} 只有 {call_count} 个工具调用（0 起），没有第 {index} 个；\
                     工具块不能新增"
                ));
            }
            // 结果在不在：还没有结果就是这一轮没跑完，改完也发不出去。
            if !message.tool_calls()[index].status.is_finished() {
                return ToolOutcome::failed(format!(
                    "第 {index} 个工具调用还没有结果：单元没闭合，改不了（先让那一轮跑完）"
                ));
            }
        }

        let mut text = format!(
            "已排队 {} 项改动，这一轮结束时落库（消息 {message_id}）：\n",
            call.ops.len()
        );
        for op in &call.ops {
            let block = op.block();
            let what = match op {
                BlockOp::Delete { .. } => "删掉这一块".to_string(),
                BlockOp::Set { content, .. } => {
                    if block == BlockSelector::Thinking && content.trim().is_empty() {
                        "删掉这一块".to_string()
                    } else {
                        format!("{} 字", content.chars().count())
                    }
                }
            };
            text.push_str(&format!(
                "- {}：{}\n",
                crate::edit::block_label(block),
                what
            ));
        }
        ToolOutcome::ok(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::WorkspaceBinding;
    use std::fs;

    fn sandbox() -> (tempfile::TempDir, Workspace, AssistantPolicy) {
        let dir = tempfile::tempdir().unwrap();
        fs::write(
            dir.path().join("hello.rs"),
            "fn main() {\n    println!(\"hi\");\n}\n",
        )
        .unwrap();
        fs::create_dir_all(dir.path().join("sub")).unwrap();
        fs::write(dir.path().join("sub/note.md"), "# 笔记\n").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws-1"))
            .enable_network(false);
        (dir, workspace, policy)
    }

    fn invoke(
        registry: &ToolRegistry,
        policy: &AssistantPolicy,
        workspace: &Workspace,
        name: &str,
        args: &str,
    ) -> ToolOutcome {
        let ctx = ToolContext::new(policy, Some(workspace));
        registry.invoke(name, args, &ctx)
    }

    /// 文件工具里的 `/tmp` 落到这个会话自己的暂存目录（D294）：
    /// 不是工作区里的 `tmp/`，也不是宿主的 `/tmp`。
    #[test]
    fn tmp_in_a_file_tool_lands_in_the_session_scratch() {
        let (work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let scratch = store.path().join("sessions").join("s_1").join("tmp");
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_tmp(Some(&scratch));

        let written = registry.invoke(
            "write_file",
            r#"{"path":"/tmp/a.txt","content":"hi"}"#,
            &ctx,
        );
        assert!(matches!(written, ToolOutcome::Ok { .. }), "{written:?}");
        assert_eq!(
            fs::read_to_string(scratch.join("a.txt")).unwrap(),
            "hi",
            "应当落在会话暂存里：{}",
            scratch.display()
        );
        assert!(!work.path().join("tmp").exists(), "不该在工作区里建 tmp/");

        let read = registry.invoke("read_file", r#"{"path":"/tmp/a.txt"}"#, &ctx);
        match read {
            ToolOutcome::Ok { text, .. } => assert!(text.contains("hi"), "{text}"),
            other => panic!("{other:?}"),
        }

        // `/tmp` 本身是目录：列它不该报"路径为空"。
        let listed = registry.invoke("list_dir", r#"{"path":"/tmp"}"#, &ctx);
        match listed {
            ToolOutcome::Ok { text, .. } => assert!(text.contains("a.txt"), "{text}"),
            other => panic!("{other:?}"),
        }
    }

    /// 标记行里的 `/archive/<hex>.txt` 真的读得到（D317）：这是"取回"的全部意义。
    #[test]
    fn archive_marker_path_is_readable() {
        let (_work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let archive = store.path().join("sessions").join("s_1").join("archive");
        fs::create_dir_all(&archive).unwrap();
        let entry = crate::archive::ArchiveEntry::new_redacted(
            "原始工具输出：37 行日志".to_string(),
            crate::source::SourceTag::ToolResult,
            crate::archive::Origin::new(
                crate::ids::SessionId::new(),
                crate::ids::MessageId::new(crate::ids::SessionId::new(), 1),
                0,
            ),
            12,
            0,
        );
        let file = archive.join(entry.rf.file_name());
        fs::write(file, entry.content.as_bytes()).unwrap();
        let marker = crate::form::truncation_marker(entry.tokens, &entry.rf, None);
        let path = crate::archive::guest_path(&entry.rf);
        assert!(marker.contains(&path), "{marker}");

        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_archive(Some(&archive));
        let read = registry.invoke("read_file", &format!(r#"{{"path":"{}"}}"#, path), &ctx);
        match read {
            ToolOutcome::Ok { text, .. } => {
                assert!(text.contains("原始工具输出"), "{text}");
            }
            other => panic!("{other:?}"),
        }
    }

    /// 归档被删掉之后，读它要说"取不到了"，不能说"路径不存在，去 list_dir 看看"——
    /// 后者会把模型引去寻找一个本来就不会再出现的东西。
    #[test]
    fn a_missing_archive_says_it_is_gone() {
        let (_work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let archive = store.path().join("sessions").join("s_1").join("archive");
        fs::create_dir_all(&archive).unwrap();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_archive(Some(&archive));
        let read = registry.invoke("read_file", r#"{"path":"/archive/0000.txt"}"#, &ctx);
        match read {
            ToolOutcome::Failed { .. } => {
                let text = read.text();
                assert!(text.contains("取不到了"), "{text}");
            }
            other => panic!("{other:?}"),
        }
    }

    /// 归档是只读的：写进去等于写一份下一轮就被库覆盖的副本。
    #[test]
    fn archive_is_read_only() {
        let (_work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let archive = store.path().join("sessions").join("s_1").join("archive");
        fs::create_dir_all(&archive).unwrap();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_archive(Some(&archive));
        let written = registry.invoke(
            "write_file",
            r#"{"path":"/archive/a.txt","content":"x"}"#,
            &ctx,
        );
        assert!(matches!(written, ToolOutcome::Failed { .. }), "{written:?}");
        assert!(!archive.join("a.txt").exists());
        let edited = registry.invoke(
            "edit_file",
            r#"{"path":"/archive/a.txt","old":"x","new":"y"}"#,
            &ctx,
        );
        assert!(matches!(edited, ToolOutcome::Failed { .. }), "{edited:?}");
    }

    /// 没有会话上下文时不回落：`/archive` 直接报错，不当成工作区里的一个空文件。
    #[test]
    fn archive_without_a_session_does_not_fall_back() {
        let (_work, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let read = registry.invoke("read_file", r#"{"path":"/archive/a.txt"}"#, &ctx);
        assert!(matches!(read, ToolOutcome::Failed { .. }), "{read:?}");
        assert!(read.text().contains("/archive"), "{}", read.text());
    }

    /// 两个会话的 `/tmp` 不是同一份：一个写的，另一个读不到。
    #[test]
    fn the_scratch_of_one_session_is_invisible_to_another() {
        let (_work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let one = store.path().join("sessions").join("s_1").join("tmp");
        let two = store.path().join("sessions").join("s_2").join("tmp");
        let registry = ToolRegistry::workspace_defaults();

        let first = ToolContext::new(&policy, Some(&workspace)).with_session_tmp(Some(&one));
        let written = registry.invoke(
            "write_file",
            r#"{"path":"/tmp/secret.txt","content":"x"}"#,
            &first,
        );
        assert!(matches!(written, ToolOutcome::Ok { .. }), "{written:?}");

        let second = ToolContext::new(&policy, Some(&workspace)).with_session_tmp(Some(&two));
        let read = registry.invoke("read_file", r#"{"path":"/tmp/secret.txt"}"#, &second);
        assert!(matches!(read, ToolOutcome::Failed { .. }), "{read:?}");
    }

    /// 没有会话上下文时 `/tmp` **报错**，不退回宿主 `/tmp`。
    #[test]
    fn tmp_without_a_session_is_an_error_not_a_fallback() {
        let (_work, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let outcome = registry.invoke("write_file", r#"{"path":"/tmp/a.txt","content":"x"}"#, &ctx);
        match outcome {
            ToolOutcome::Failed { message } => {
                assert!(message.contains("/tmp"), "{message}");
                assert!(message.contains("会话"), "{message}");
            }
            other => panic!("{other:?}"),
        }
    }

    /// `/tmp` 里面也不许用 `..` 爬出去。
    #[test]
    fn the_scratch_still_refuses_to_escape() {
        let (_work, workspace, policy) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let scratch = store.path().join("sessions").join("s_1").join("tmp");
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_tmp(Some(&scratch));
        let outcome = registry.invoke(
            "write_file",
            r#"{"path":"/tmp/../escape.txt","content":"x"}"#,
            &ctx,
        );
        assert!(matches!(outcome, ToolOutcome::Failed { .. }), "{outcome:?}");
        assert!(!store
            .path()
            .join("sessions")
            .join("s_1")
            .join("escape.txt")
            .exists());
    }

    /// 跨会话读取（D295）：开关关着时**不声明**，打开才出现在声明表里。
    #[test]
    fn cross_session_tool_is_not_declared_until_the_switch_is_on() {
        let registry = ToolRegistry::workspace_defaults();
        let off = AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let names: Vec<String> = registry
            .specs_for(&off)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(
            !names.contains(&"Session-Files".to_string()),
            "默认不该声明：{names:?}"
        );

        let on = off.clone().enable_cross_session(true);
        let names: Vec<String> = registry
            .specs_for(&on)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(names.contains(&"Session-Files".to_string()), "{names:?}");
    }

    /// 跨会话读取：读得到别的会话的 `/tmp`，结果里点名来源。
    #[test]
    fn session_files_reads_another_sessions_tmp_and_names_the_source() {
        let (_work, workspace, _) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let other = SessionId::new().to_string();
        let me = SessionId::new().to_string();
        let dir = store.path().join(&other).join("tmp");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("note.txt"), "别人的东西").unwrap();
        let index = crate::session::SessionIndex::new(
            store.path(),
            vec![(other.clone(), "上一条会话".to_string())],
        );
        let policy = AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws-1"))
            .enable_cross_session(true);
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace))
            .with_call_session(&me)
            .with_sessions(Some(&index));

        let outcome = registry.invoke(
            "Session-Files",
            &json!({"session_id": other, "area": "tmp", "path": "note.txt", "mode": "read"})
                .to_string(),
            &ctx,
        );
        match outcome {
            ToolOutcome::Ok { text, .. } => {
                assert!(text.contains("上一条会话"), "{text}");
                assert!(text.contains("另一个助手"), "{text}");
                assert!(text.contains("别人的东西"), "{text}");
            }
            other => panic!("{other:?}"),
        }
    }

    /// 形状不对 / 会话不存在：两句不同的错，都要能照着改。
    #[test]
    fn session_files_names_a_malformed_id_and_a_missing_session() {
        let (_work, workspace, _) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let policy = AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws-1"))
            .enable_cross_session(true);
        let index = crate::session::SessionIndex::new(store.path(), Vec::new());
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_sessions(Some(&index));

        let malformed = registry.invoke(
            "Session-Files",
            r#"{"session_id":"s_1","area":"tmp"}"#,
            &ctx,
        );
        match malformed {
            ToolOutcome::Failed { message } => {
                assert!(message.contains("不是会话 id"), "{message}");
                assert!(message.contains("详细信息"), "要说清去哪复制：{message}");
            }
            other => panic!("{other:?}"),
        }

        let missing = registry.invoke(
            "Session-Files",
            &json!({"session_id": SessionId::new().to_string(), "area": "tmp"}).to_string(),
            &ctx,
        );
        match missing {
            ToolOutcome::Failed { message } => {
                assert!(message.contains("没有这个会话"), "{message}");
                assert!(message.contains("可能已经被删"), "{message}");
            }
            other => panic!("{other:?}"),
        }
    }

    /// 跨会话读取：`..` 不许爬出去；超过 256 KB 要说清多大。
    #[test]
    fn session_files_refuses_escapes_and_oversized_files() {
        let (_work, workspace, _) = sandbox();
        let store = tempfile::tempdir().unwrap();
        let other = SessionId::new().to_string();
        let dir = store.path().join(&other).join("chat");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("big.txt"), "x".repeat(300 * 1024)).unwrap();
        let index =
            crate::session::SessionIndex::new(store.path(), vec![(other.clone(), String::new())]);
        let policy = AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws-1"))
            .enable_cross_session(true);
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_sessions(Some(&index));

        let escape = registry.invoke(
            "Session-Files",
            &json!({"session_id": other, "area": "chat", "path": "../../etc/passwd"}).to_string(),
            &ctx,
        );
        match escape {
            ToolOutcome::Failed { message } => assert!(message.contains("越界"), "{message}"),
            other => panic!("{other:?}"),
        }

        let big = registry.invoke(
            "Session-Files",
            &json!({"session_id": other, "area": "chat", "path": "big.txt", "mode": "read"})
                .to_string(),
            &ctx,
        );
        match big {
            ToolOutcome::Failed { message } => {
                assert!(message.contains("256 KB"), "{message}");
                assert!(message.contains("300"), "要说清多大：{message}");
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn registry_declares_the_workspace_tools_and_both_shells() {
        let registry = ToolRegistry::workspace_defaults();
        let names = registry.names();
        // 六个文件工具 + 两把 shell（`System-Shell` D159、`WorkSpace-EnvirShell` D172）
        // + 模型改消息（`edit_unit` D177）+ 把图拉进上下文（`View-Image` D248）。
        // 两把 shell 的**声明**另有门控，这里只验"注册表里有、描述齐全"。
        assert_eq!(names.len(), 13, "{names:?}");
        for name in [
            "System-Shell",
            "WorkSpace-EnvirShell",
            "View-Image",
            "Voice-Caps",
            "Speak",
            "edit_unit",
        ] {
            assert!(names.contains(&name), "缺少 {name}");
        }
        for name in [
            "edit_file",
            "find_files",
            "list_dir",
            "read_file",
            "search",
            "write_file",
        ] {
            assert!(names.contains(&name), "缺少 {name}");
        }
        let specs = registry.specs();
        assert_eq!(specs.len(), 13);
        assert!(specs.iter().all(|spec| spec.description.is_some()));
        assert_eq!(specs[0].parameters["type"], json!("object"));
    }

    #[test]
    fn specs_are_filtered_by_policy() {
        let registry = ToolRegistry::workspace_defaults();
        // 只读工作区：不该把 write_file / edit_file 告诉模型
        let readonly =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        let names: Vec<String> = registry
            .specs_for(&readonly)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(names.contains(&"read_file".to_string()));
        assert!(!names.contains(&"write_file".to_string()), "{names:?}");

        // 完全没绑工作区：一个都不要给
        let empty = AssistantPolicy::locked_down();
        assert!(registry.specs_for(&empty).is_empty());
    }

    #[test]
    fn read_file_returns_numbered_content_with_header() {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let outcome = invoke(
            &registry,
            &policy,
            &workspace,
            "read_file",
            r#"{"path":"hello.rs"}"#,
        );
        let text = outcome.text();
        assert!(outcome.is_ok(), "{text}");
        assert!(text.contains("hello.rs"), "{text}");
        assert!(text.contains("1|"), "带行号：{text}");
        assert!(text.contains("3|"), "三行都在：{text}");
        assert!(text.contains("println"), "{text}");
    }

    #[test]
    fn write_then_read_roundtrips() {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let outcome = invoke(
            &registry,
            &policy,
            &workspace,
            "write_file",
            r#"{"path":"new/out.txt","content":"喵"}"#,
        );
        assert!(outcome.is_ok(), "{}", outcome.text());
        let read = invoke(
            &registry,
            &policy,
            &workspace,
            "read_file",
            r#"{"path":"new/out.txt","numbered":false}"#,
        );
        assert!(read.text().contains('喵'));
    }

    #[test]
    fn edit_file_refuses_ambiguous_match() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("dup.txt"), "a\na\n").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let registry = ToolRegistry::workspace_defaults();
        let outcome = invoke(
            &registry,
            &policy,
            &workspace,
            "edit_file",
            r#"{"path":"dup.txt","old":"a","new":"b"}"#,
        );
        assert!(!outcome.is_ok());
        let text = outcome.text();
        assert!(text.contains("多次"), "要说清是匹配多处：{text}");
        assert!(text.contains("replace_all"), "要告诉它怎么办：{text}");
    }

    #[test]
    fn readonly_binding_denies_write_before_touching_disk() {
        let (dir, workspace, _) = sandbox();
        let readonly =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        let registry = ToolRegistry::workspace_defaults();
        let outcome = invoke(
            &registry,
            &readonly,
            &workspace,
            "write_file",
            r#"{"path":"x.txt","content":"不该写进去"}"#,
        );
        match &outcome {
            ToolOutcome::Denied { capability, reason } => {
                assert_eq!(*capability, Capability::WorkspaceWrite);
                assert_eq!(*reason, DenyReason::ReadOnlyWorkspace);
            }
            other => panic!("期望被拒绝，实际 {other:?}"),
        }
        assert!(!dir.path().join("x.txt").exists(), "拒绝时不该碰磁盘");
    }

    #[test]
    fn no_workspace_reports_no_workspace_not_traversal() {
        let registry = ToolRegistry::workspace_defaults();
        let policy = AssistantPolicy::locked_down();
        let ctx = ToolContext::new(&policy, None);
        let outcome = registry.invoke("read_file", r#"{"path":"a.rs"}"#, &ctx);
        match &outcome {
            ToolOutcome::Denied { reason, .. } => assert_eq!(*reason, DenyReason::NoWorkspace),
            other => panic!("期望 NoWorkspace，实际 {other:?}"),
        }
        assert!(outcome.text().contains("工作区"), "{}", outcome.text());
    }

    #[test]
    fn path_traversal_is_reported_as_failure_not_panic() {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let outcome = invoke(
            &registry,
            &policy,
            &workspace,
            "read_file",
            r#"{"path":"../../etc/passwd"}"#,
        );
        assert!(!outcome.is_ok());
        assert!(outcome.text().contains("越界"), "{}", outcome.text());
    }

    #[test]
    fn unknown_tool_and_bad_json_are_tool_results() {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let unknown = invoke(&registry, &policy, &workspace, "rm_rf", "{}");
        assert!(matches!(unknown, ToolOutcome::Failed { .. }));
        assert!(unknown.text().contains("未知工具"));
        assert!(unknown.text().contains("read_file"), "要把可用工具列出来");

        let bad = invoke(&registry, &policy, &workspace, "read_file", "{not json}");
        assert!(matches!(bad, ToolOutcome::Failed { .. }));
        assert!(bad.text().contains("JSON"));
    }

    #[test]
    fn list_and_find_and_search_work() {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();

        let listed = invoke(&registry, &policy, &workspace, "list_dir", "{}");
        assert!(listed.text().contains("hello.rs"), "{}", listed.text());
        assert!(
            listed.text().contains("sub/"),
            "目录要带斜杠：{}",
            listed.text()
        );

        let found = invoke(
            &registry,
            &policy,
            &workspace,
            "find_files",
            r#"{"pattern":"**/*.md"}"#,
        );
        assert!(found.text().contains("sub/note.md"), "{}", found.text());

        let searched = invoke(
            &registry,
            &policy,
            &workspace,
            "search",
            r#"{"query":"println"}"#,
        );
        assert!(
            searched.text().contains("hello.rs:2:"),
            "{}",
            searched.text()
        );
    }

    /// 一轮会话的样子：一条助手消息带一个工具调用 + 那条结果，再加一条用户消息。
    fn history() -> Vec<Message> {
        use crate::clock::LogicalClock;
        use crate::message::OutgoingToolCall;
        use crate::source::SourceTag;

        let session = SessionId::new();
        let mut head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("call_1", "read_file", "{}")],
            4,
            1_000,
            LogicalClock::new(1),
        );
        head.set_tool_output("call_1", "ok", crate::message::ToolStatus::Ok);
        let mut user = Message::text(
            MessageId::new(session, 3),
            Role::User,
            SourceTag::History,
            "把上一条改一下",
            6,
            1_000,
            LogicalClock::new(3),
        );
        user.unit_id = MessageId::new(session, 3).unit();
        vec![head, user]
    }

    fn edit_outcome(history: &[Message], args: &str) -> ToolOutcome {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_history(history);
        registry.invoke("edit_unit", args, &ctx)
    }

    #[test]
    fn edit_message_queues_the_blocks_it_will_write() {
        let history = history();
        let outcome = edit_outcome(
            &history,
            r#"{"message_id":"last_assistant","blocks":[
                {"kind":"output","content":"改过的正文"},
                {"kind":"thinking","content":""},
                {"kind":"tool","index":0,"content":"{\"path\":\"b.txt\"}"}]}"#,
        );
        let text = outcome.text();
        assert!(outcome.is_ok(), "{text}");
        // 说"排队"而不是"改好了"：落库在这一轮收尾，用户此刻还没看到变化。
        assert!(text.contains("已排队 3 项"), "{text}");
        assert!(text.contains("这一轮结束"), "{text}");
        assert!(text.contains("正文"), "{text}");
        // 空串思考 = 删掉这一块，这句话得说清楚，不然模型以为写入了一个空块。
        assert!(text.contains("删掉这一块"), "{text}");
        assert!(text.contains("工具参数 #0"), "{text}");
    }

    #[test]
    fn edit_message_explains_what_is_wrong_instead_of_guessing() {
        let history = history();
        let cases = [
            // 块名不认识：要点名正确的那三种
            (
                r#"{"message_id":"last_assistant","blocks":[{"kind":"reasoning","content":"x"}]}"#,
                "reasoning",
            ),
            // tool 少了 index
            (
                r#"{"message_id":"last_assistant","blocks":[{"kind":"tool","content":"{}"}]}"#,
                "index",
            ),
            // 工具序号越界：工具块不能新增
            (
                r#"{"message_id":"last_assistant","blocks":[{"kind":"tool","index":3,"content":"{}"}]}"#,
                "不能新增",
            ),
            // 工具参数不是 JSON 对象
            (
                r#"{"message_id":"last_assistant","blocks":[{"kind":"tool","index":0,"content":"not json"}]}"#,
                "JSON",
            ),
            // 正文不许清空
            (
                r#"{"message_id":"last_assistant","blocks":[{"kind":"output","content":"  "}]}"#,
                "不能改成空的",
            ),
            // 同一块写两遍：落库会互相覆盖
            (
                r#"{"message_id":"last_assistant","blocks":[
                    {"kind":"output","content":"a"},{"kind":"output","content":"b"}]}"#,
                "两遍",
            ),
            // 目标不存在：不许凭空造一条
            (
                r#"{"message_id":"abcdef00-0000-0000-0000-000000000000:9","blocks":[{"kind":"output","content":"a"}]}"#,
                "没有消息",
            ),
            // 完全不是 id
            (
                r#"{"message_id":"第二条","blocks":[{"kind":"output","content":"a"}]}"#,
                "last_assistant",
            ),
        ];
        for (args, expect) in cases {
            let outcome = edit_outcome(&history, args);
            assert!(!outcome.is_ok(), "这条该被拒：{args}");
            let text = outcome.text();
            assert!(text.contains(expect), "要说到 `{expect}`：{args} → {text}");
        }
    }

    #[test]
    fn edit_message_refuses_a_locked_message() {
        let mut history = history();
        history[0].locked = true;
        let outcome = edit_outcome(
            &history,
            r#"{"message_id":"last_assistant","blocks":[{"kind":"output","content":"改"}]}"#,
        );
        assert!(!outcome.is_ok(), "{}", outcome.text());
        assert!(outcome.text().contains("锁定"), "{}", outcome.text());
    }

    #[test]
    fn edit_message_is_not_declared_for_a_read_only_workspace() {
        let registry = ToolRegistry::workspace_defaults();
        let readonly =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_only("ws-1"));
        let names: Vec<String> = registry
            .specs_for(&readonly)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(!names.contains(&"edit_unit".to_string()), "{names:?}");

        // 读写得开：它出现（与 `edit_file` 同档，D177）。
        let writable =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let names: Vec<String> = registry
            .specs_for(&writable)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(names.contains(&"edit_unit".to_string()), "{names:?}");
    }
}
