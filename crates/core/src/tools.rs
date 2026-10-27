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
}

impl<'a> ToolContext<'a> {
    pub fn new(policy: &'a AssistantPolicy, workspace: Option<&'a Workspace>) -> Self {
        Self {
            policy,
            workspace,
            declared: None,
            envir: None,
        }
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
        // 六个文件工具 + 两把 shell（`System-Shell` D159、`WorkSpace-EnvirShell` D172）。
        // 两把 shell 的**声明**另有门控，这里只验"注册表里有、描述齐全"。
        assert_eq!(names.len(), 8, "{names:?}");
        for name in ["System-Shell", "WorkSpace-EnvirShell"] {
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
        assert_eq!(specs.len(), 8);
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
}
