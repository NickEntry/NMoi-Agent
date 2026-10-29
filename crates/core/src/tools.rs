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

use crate::edit::{BlockSelector, MAX_BLOCK_BYTES};
use crate::ids::MessageId;
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
#[derive(Debug, Clone, Copy)]
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
}

impl<'a> ToolContext<'a> {
    pub fn new(policy: &'a AssistantPolicy, workspace: Option<&'a Workspace>) -> Self {
        Self {
            policy,
            workspace,
            declared: None,
            envir: None,
            history: None,
        }
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

/// 一个工具。
pub trait Tool: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn schema(&self) -> Value;
    /// 需要哪一项能力。门控在 [`Tool::invoke`] 的最前面用掉它。
    fn capability(&self) -> Capability;
    fn invoke(&self, arguments: &Value, ctx: &ToolContext<'_>) -> ToolOutcome;

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
        // 容器里的命令（D172）：由 `Capability::ContainerExec` 门控，同样默认不出现。
        registry.register(Box::new(crate::shell::EnvirShellTool));
        // 模型改消息（D177 / 0.14y）：与 `edit_file` 同档能力，但改的是会话不是磁盘。
        registry.register(Box::new(EditMessageTool));
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

/// 工作区错误 → 给模型看的话。带上"怎么办"，不只是"错了"。
fn workspace_error(error: WorkspaceError) -> ToolOutcome {
    let hint = match &error {
        WorkspaceError::OutsideRoot { .. } => "只能访问工作区内的路径。",
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

pub struct ReadFileTool;

impl Tool for ReadFileTool {
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
        let defaults = ReadOptions::default();
        let options = ReadOptions {
            start_line: optional_usize(args, "start_line", 1).max(1),
            max_lines: optional_usize(args, "max_lines", defaults.max_lines),
            numbered: optional_bool(args, "numbered", true),
            force_full: optional_bool(args, "force_full", false),
            ..defaults
        };
        match workspace.read_file(&path, &options) {
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
        "整篇覆盖写一个文件（目录会自动建）。要改局部请用 edit_file——覆盖会丢掉没读到的东西。"
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
        match workspace.write_file(&path, &content) {
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
        match workspace.edit_file(&path, &old, &new, replace_all) {
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
        let defaults = ListOptions::default();
        let options = ListOptions {
            depth: optional_usize(args, "depth", defaults.depth).max(1),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            max_entries: optional_usize(args, "max_entries", defaults.max_entries),
        };
        match workspace.list_dir(&path, &options) {
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
        let options = crate::workspace::FindOptions {
            path: optional_str(args, "path").unwrap_or_default(),
            max_results: optional_usize(args, "max_results", defaults.max_results),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            ..defaults
        };
        match workspace.find(&pattern, &options) {
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
        let options = SearchOptions {
            path: optional_str(args, "path").unwrap_or_default(),
            ignore_case: optional_bool(args, "ignore_case", defaults.ignore_case),
            max_results: optional_usize(args, "max_results", defaults.max_results),
            include_hidden: optional_bool(args, "include_hidden", defaults.include_hidden),
            ..defaults
        };
        match workspace.search(&query, &options) {
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

/// 块的中文名。给模型看的那句话、给用户看的报告都用它，别一处一个说法。
pub fn block_label(block: BlockSelector) -> String {
    match block {
        BlockSelector::Output => "正文".into(),
        BlockSelector::Thinking => "思考块".into(),
        BlockSelector::ToolCall { index } => format!("工具参数 #{index}"),
    }
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

/// 模型改消息。**只排队，不落库**——core 不碰存储，
/// 真正写进去发生在这一轮收尾（`session::run_session_turn` 第 3.5 步）。
///
/// 也就是说，那句话里必须写"这一轮结束时落库"，而不是"已修改"：
/// 模型看到"改好了"就会对用户复述改好了，而用户此时还没看到任何变化。
pub struct EditMessageTool;

impl Tool for EditMessageTool {
    fn name(&self) -> &str {
        "edit_message"
    }

    fn description(&self) -> &str {
        "改一条已经存在的消息里的块（正文 / 思考 / 工具参数），这一轮结束时生效。\
         改哪条用 message_id：给具体 id，或 last_assistant（最近一条助手消息）、\
         last_user（最近一条用户消息）。工具参数只能改、不能新增。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "message_id": {
                    "type": "string",
                    "description": "要改的消息：具体 id，或 last_assistant / last_user / last"
                },
                "blocks": {
                    "type": "array",
                    "description": "改哪几块（一次最多 8 块）",
                    "items": {
                        "type": "object",
                        "properties": {
                            "kind": {
                                "type": "string",
                                "enum": ["output", "thinking", "tool"],
                                "description": "output=正文，thinking=思考块，tool=某个工具调用的参数"
                            },
                            "index": {
                                "type": "integer",
                                "description": "kind=tool 时必填，第几个工具调用（0 起）"
                            },
                            "content": {
                                "type": "string",
                                "description": "新内容；thinking 给空串表示删掉这一块"
                            }
                        },
                        "required": ["kind", "content"]
                    }
                }
            },
            "required": ["message_id", "blocks"]
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
        let call = match parse_edit_message(args) {
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
        // 工具块的边界（D177）：一条调用必须与一条结果按 call_id 配对，
        // 凭空加一块会造出孤儿调用，下一次请求直接 400。
        let call_count = message.tool_calls.len();
        for (block, _) in &call.edits {
            if let BlockSelector::ToolCall { index } = block {
                if *index >= call_count {
                    return ToolOutcome::failed(format!(
                        "消息 {message_id} 只有 {call_count} 个工具调用（0 起），没有第 {index} 个；\
                         工具块不能新增"
                    ));
                }
            }
        }

        let mut text = format!(
            "已排队 {} 处改动，这一轮结束时落库（消息 {message_id}）：\n",
            call.edits.len()
        );
        for (block, content) in &call.edits {
            let what = if block == &BlockSelector::Thinking && content.trim().is_empty() {
                "删掉这一块".to_string()
            } else {
                format!("{} 字", content.chars().count())
            };
            text.push_str(&format!("- {}：{}\n", block_label(*block), what));
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

    #[test]
    fn registry_declares_the_workspace_tools_and_both_shells() {
        let registry = ToolRegistry::workspace_defaults();
        let names = registry.names();
        // 六个文件工具 + 两把 shell（`System-Shell` D159、`WorkSpace-EnvirShell` D172）
        // + 模型改消息（`edit_message` D177）。
        // 两把 shell 的**声明**另有门控，这里只验"注册表里有、描述齐全"。
        assert_eq!(names.len(), 9, "{names:?}");
        for name in ["System-Shell", "WorkSpace-EnvirShell", "edit_message"] {
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
        assert_eq!(specs.len(), 9);
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
        use crate::ids::SessionId;
        use crate::message::OutgoingToolCall;
        use crate::source::SourceTag;

        let session = SessionId::new();
        let head = Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("call_1", "read_file", "{}")],
            4,
            1_000,
            LogicalClock::new(1),
        );
        let mut result = Message::tool_result(
            MessageId::new(session, 2),
            "call_1",
            "ok",
            1,
            1_000,
            LogicalClock::new(2),
        );
        result.unit_id = head.unit_id.clone();
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
        vec![head, result, user]
    }

    fn edit_outcome(history: &[Message], args: &str) -> ToolOutcome {
        let (_dir, workspace, policy) = sandbox();
        let registry = ToolRegistry::workspace_defaults();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_history(history);
        registry.invoke("edit_message", args, &ctx)
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
        assert!(text.contains("已排队 3 处"), "{text}");
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
        assert!(!names.contains(&"edit_message".to_string()), "{names:?}");

        // 读写得开：它出现（与 `edit_file` 同档，D177）。
        let writable =
            AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("ws-1"));
        let names: Vec<String> = registry
            .specs_for(&writable)
            .into_iter()
            .map(|spec| spec.name)
            .collect();
        assert!(names.contains(&"edit_message".to_string()), "{names:?}");
    }
}
