//! 生效判定：把"存着的设置"和"模型能做什么"求交，算出这一轮真正要发的东西。
//!
//! 这一层是 D79 / D80 / D81 / D85 的落点。它**只读不写**：算完不回头改任何一个输入。
//! 三个轴（模型能力 / 用户设置 / 这一份请求）在这里相遇，但谁也不会被谁改写——
//! 这正是"切过去切回来丢设置"不会发生的原因。
//!
//! 四类东西的语义不一样，函数也就分开写，不硬凑一个"大而全"的入口：
//!
//! | 东西 | 函数 | 语义 |
//! |---|---|---|
//! | 技能 | [`visible_skills`] | 启用 = 名字 + 描述进上下文；禁用 = 模型不知道，但文件照样能读（D80） |
//! | 指令注入 | [`gate_instructions`] | 真启用：关了就是一个字都不进（D82） |
//! | 世界书 | [`gate_world_books`] | 两级门：设置级关连加载都不做；会话级没勾是加载但不注入（D81） |
//! | 工具 | [`plan_tools`] | 三轴求交 + MCP 两级门（D85） |

use crate::budget::is_mcp_tool;
use crate::policy::{AssistantPolicy, Capability, DenyReason};
use crate::provider::ProviderCapabilities;
use crate::settings::{SkillSelection, ToolSwitches};
use crate::skills::SkillMeta;
use crate::tools::ToolRegistry;

// ---------------------------------------------------------------------------
// 技能
// ---------------------------------------------------------------------------

/// 这一轮模型**看得见**的技能（顺序沿用发现顺序，保证每次请求的 prompt 一样）。
///
/// 只看"要不要告诉模型它存在"；文件能不能读是另一件事——有容器就走 `/skills` 映射，
/// 没容器就用工作区工具读。禁用只让模型**不知道**，不让它**够不着**（D80）。
pub fn visible_skills<'a>(
    skills: impl IntoIterator<Item = &'a SkillMeta>,
    selection: &SkillSelection,
) -> Vec<&'a SkillMeta> {
    skills
        .into_iter()
        .filter(|skill| {
            if selection.use_all {
                true
            } else {
                selection.names.iter().any(|name| name == &skill.name)
            }
        })
        .collect()
}

// ---------------------------------------------------------------------------
// 指令注入
// ---------------------------------------------------------------------------

/// 指令注入的文档列表。
///
/// 与技能不同，这里没有"文件还在不在"的旁路：指令是**命令**，技能是**能力**。
/// 命令类开关关了还能生效，用户就永远不知道该信哪个（D82）。
pub fn gate_instructions(enabled: bool, docs: &[String]) -> &[String] {
    if enabled {
        docs
    } else {
        &[]
    }
}

// ---------------------------------------------------------------------------
// 世界书
// ---------------------------------------------------------------------------

/// 世界书的两级门结果（D81）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorldBookGate {
    /// 设置级总闸关着：**连加载都不做**，连解析都省掉。
    NotLoaded,
    /// 加载了，但这次只注入这些（按加载顺序，不是按勾选顺序——注入顺序要稳定）。
    Active(Vec<String>),
}

/// `loaded` 是"设置级开着时真正解析出来的书目"，`selected` 是会话里勾的那些。
pub fn gate_world_books(
    global_enabled: bool,
    loaded: &[String],
    selected: &[String],
) -> WorldBookGate {
    if !global_enabled {
        return WorldBookGate::NotLoaded;
    }
    WorldBookGate::Active(
        loaded
            .iter()
            .filter(|name| selected.iter().any(|pick| pick == *name))
            .cloned()
            .collect(),
    )
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/// 一个工具没被声明出去的原因。UI 上要能回答"为什么没有 `write_file`"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToolDrop {
    /// 模型不支持工具调用。不是策略挡的，改设置也没用。
    ModelHasNoTools,
    /// 能力门控（安全边界）挡的。
    Policy(DenyReason),
    /// 本地工具开关关着（聊天面板上那个）。
    LocalToolsOff,
    /// MCP 服务器启用了，但没连上——两级门的第一级没过。
    McpNotConnected { server: String },
    /// 名字不是 `mcp__<server>__<tool>` 的形状，认不出属于哪个服务器。
    McpNameNotAttributable,
}

impl ToolDrop {
    pub fn label(&self) -> &'static str {
        match self {
            ToolDrop::ModelHasNoTools => "此模型不支持工具调用",
            ToolDrop::Policy(reason) => reason.explain(),
            ToolDrop::LocalToolsOff => "本地工具已关闭",
            ToolDrop::McpNotConnected { .. } => "MCP 服务器未连接",
            ToolDrop::McpNameNotAttributable => "MCP 工具名认不出属于哪个服务器",
        }
    }
}

/// 这一轮的工具声明计划。`dropped` 不是"错误"，是给人看的解释。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ToolPlan {
    pub declared: Vec<String>,
    pub dropped: Vec<(String, ToolDrop)>,
    /// 启用了但没连上的 MCP 服务器（去重、有序）。UI 上该在设置页提示重连。
    pub mcp_not_connected: Vec<String>,
}

impl ToolPlan {
    pub fn declares(&self, name: &str) -> bool {
        self.declared.iter().any(|item| item == name)
    }

    pub fn drop_reason(&self, name: &str) -> Option<&ToolDrop> {
        self.dropped
            .iter()
            .find(|(dropped, _)| dropped == name)
            .map(|(_, reason)| reason)
    }
}

/// 工具按"开关在哪儿"分成三轴（D85 修订）：
///
/// - 工作区类：**跟工作区绑定走**，没有独立开关；`policy` 判"绑没绑、只读还是可写"。
/// - 本地类：聊天面板上的开关。
/// - MCP：两级门，第一级是"连没连上"。
fn axis_of(capability: Capability) -> ToolAxis {
    match capability {
        // 读写与检索：工作区在就有，工作区不在就没有。
        Capability::WorkspaceRead | Capability::WorkspaceWrite | Capability::WorkspaceSearch => {
            ToolAxis::Workspace
        }
        // 执行、联网、要凭据：这些是"这台机器上能干的事"，属于本地开关。
        Capability::ContainerExec
        | Capability::NativeShell
        | Capability::Network
        | Capability::SecretsBroker => ToolAxis::Local,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ToolAxis {
    Workspace,
    Local,
}

/// MCP 工具名 → 服务器 id。约定是 `mcp__<server>__<tool>`。
pub fn mcp_server_of(name: &str) -> Option<&str> {
    let rest = name.strip_prefix("mcp__")?;
    let (server, _tool) = rest.split_once("__")?;
    if server.is_empty() {
        return None;
    }
    Some(server)
}

/// 算出这一轮要声明的工具。
///
/// `connected_mcp` 是**连接成功**的服务器 id。没连上的不会进声明，只会进
/// `mcp_not_connected` 与 `dropped`：先进表再报错的话，模型会一次次调用一个
/// 不存在的工具，每次失败都花掉一轮上下文。
pub fn plan_tools(
    registry: &ToolRegistry,
    switches: &ToolSwitches,
    policy: &AssistantPolicy,
    capabilities: &ProviderCapabilities,
    connected_mcp: &[String],
) -> ToolPlan {
    let mut plan = ToolPlan::default();

    if !capabilities.tools {
        // 模型不支持工具：一个都不声明。这里不判别的条件——判了也没用，
        // 请求里根本不该出现 tools 字段。
        for name in registry.names() {
            plan.dropped
                .push((name.to_string(), ToolDrop::ModelHasNoTools));
        }
        return plan;
    }

    let mut missing: Vec<String> = Vec::new();

    for name in registry.names() {
        let Some(capability) = registry.capability_of(name) else {
            continue;
        };

        if is_mcp_tool(name) {
            match mcp_server_of(name) {
                None => plan
                    .dropped
                    .push((name.to_string(), ToolDrop::McpNameNotAttributable)),
                Some(server) if !connected_mcp.iter().any(|up| up == server) => {
                    if !missing.iter().any(|item| item == server) {
                        missing.push(server.to_string());
                    }
                    plan.dropped.push((
                        name.to_string(),
                        ToolDrop::McpNotConnected {
                            server: server.to_string(),
                        },
                    ));
                }
                Some(_) => plan.declared.push(name.to_string()),
            }
            continue;
        }

        // 先看用户开关，再看门控。顺序反过来的话，"没开权限"与"用户关了"
        // 会报成同一个原因，用户会去改错地方。
        if axis_of(capability) == ToolAxis::Local && !switches.local {
            plan.dropped
                .push((name.to_string(), ToolDrop::LocalToolsOff));
            continue;
        }

        match policy.check(capability) {
            Ok(()) => plan.declared.push(name.to_string()),
            Err(reason) => plan
                .dropped
                .push((name.to_string(), ToolDrop::Policy(reason))),
        }
    }

    missing.sort();
    plan.mcp_not_connected = missing;
    plan
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::WorkspaceBinding;
    use crate::tools::{Tool, ToolContext, ToolOutcome};
    use serde_json::{json, Value};

    fn skill(name: &str) -> SkillMeta {
        SkillMeta {
            name: name.to_string(),
            description: format!("{name} 的描述"),
            version: None,
            relative_path: format!("{name}/SKILL.md"),
            enabled: true,
            inferred: false,
        }
    }

    struct FakeTool {
        name: &'static str,
        capability: Capability,
    }

    impl Tool for FakeTool {
        fn name(&self) -> &str {
            self.name
        }
        fn description(&self) -> &str {
            "测试用"
        }
        fn schema(&self) -> Value {
            json!({ "type": "object" })
        }
        fn capability(&self) -> Capability {
            self.capability
        }
        fn invoke(&self, _arguments: &Value, _ctx: &ToolContext<'_>) -> ToolOutcome {
            ToolOutcome::ok("ok")
        }
    }

    fn registry() -> ToolRegistry {
        let mut registry = ToolRegistry::new();
        registry.register(Box::new(FakeTool {
            name: "read_file",
            capability: Capability::WorkspaceRead,
        }));
        registry.register(Box::new(FakeTool {
            name: "run_shell",
            capability: Capability::ContainerExec,
        }));
        registry.register(Box::new(FakeTool {
            name: "mcp__fs__read",
            capability: Capability::WorkspaceRead,
        }));
        registry
    }

    fn permissive() -> AssistantPolicy {
        AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws_1"))
            .enable_container(true)
    }

    // --- 技能 -----------------------------------------------------------

    #[test]
    fn skills_are_visible_by_selection_only() {
        let all = [skill("a"), skill("b")];
        let picked = SkillSelection {
            use_all: false,
            names: vec!["b".into()],
        };
        let visible = visible_skills(all.iter(), &picked);
        assert_eq!(visible.len(), 1);
        assert_eq!(visible[0].name, "b");

        let every = SkillSelection {
            use_all: true,
            names: vec![],
        };
        assert_eq!(visible_skills(all.iter(), &every).len(), 2);
    }

    #[test]
    fn disabled_skills_stay_in_discovery_order() {
        let all = [skill("z"), skill("a")];
        let picked = SkillSelection {
            use_all: true,
            names: vec![],
        };
        let visible = visible_skills(all.iter(), &picked);
        assert_eq!(visible[0].name, "z", "顺序跟发现顺序走，不重新排");
    }

    // --- 指令 -----------------------------------------------------------

    #[test]
    fn instructions_really_switch_off() {
        let docs = vec!["a.md".to_string()];
        assert_eq!(gate_instructions(false, &docs).len(), 0);
        assert_eq!(gate_instructions(true, &docs).len(), 1);
    }

    // --- 世界书 ---------------------------------------------------------

    #[test]
    fn world_books_are_gated_twice() {
        let loaded = vec!["主".to_string(), "副".to_string()];

        // 设置级关：连加载都不做
        assert_eq!(
            gate_world_books(false, &loaded, &["主".to_string()]),
            WorldBookGate::NotLoaded
        );

        // 设置级开、会话没勾：加载了但不注入
        assert_eq!(
            gate_world_books(true, &loaded, &[]),
            WorldBookGate::Active(vec![])
        );

        // 只注入勾了的那本，顺序按加载顺序
        assert_eq!(
            gate_world_books(true, &loaded, &["副".to_string(), "主".to_string()]),
            WorldBookGate::Active(vec!["主".to_string(), "副".to_string()])
        );
    }

    // --- 工具 -----------------------------------------------------------

    #[test]
    fn mcp_tools_need_a_connected_server() {
        let switches = ToolSwitches {
            local: true,
            ..ToolSwitches::default()
        };
        let no_mcp = plan_tools(
            &registry(),
            &switches,
            &permissive(),
            &ProviderCapabilities::openai_like(),
            &[],
        );
        assert!(!no_mcp.declares("mcp__fs__read"));
        assert_eq!(
            no_mcp.drop_reason("mcp__fs__read"),
            Some(&ToolDrop::McpNotConnected {
                server: "fs".to_string()
            })
        );
        assert_eq!(no_mcp.mcp_not_connected, vec!["fs".to_string()]);

        let with_mcp = plan_tools(
            &registry(),
            &switches,
            &permissive(),
            &ProviderCapabilities::openai_like(),
            &["fs".to_string()],
        );
        assert!(with_mcp.declares("mcp__fs__read"));
        assert!(with_mcp.mcp_not_connected.is_empty());
    }

    #[test]
    fn workspace_tools_follow_the_binding_not_a_switch() {
        let no_local = ToolSwitches {
            local: false,
            ..ToolSwitches::default()
        };
        // 本地开关关着：工作区工具照样在
        let plan = plan_tools(
            &registry(),
            &no_local,
            &permissive(),
            &ProviderCapabilities::openai_like(),
            &[],
        );
        assert!(plan.declares("read_file"), "工作区工具没有独立开关");
        assert_eq!(
            plan.drop_reason("run_shell"),
            Some(&ToolDrop::LocalToolsOff)
        );

        // 没绑工作区：读不了
        let unbound = AssistantPolicy::locked_down();
        let plan = plan_tools(
            &registry(),
            &no_local,
            &unbound,
            &ProviderCapabilities::openai_like(),
            &[],
        );
        assert!(!plan.declares("read_file"));
        assert!(matches!(
            plan.drop_reason("read_file"),
            Some(ToolDrop::Policy(_))
        ));
    }

    #[test]
    fn a_model_without_tools_declares_nothing_and_says_why() {
        let mut caps = ProviderCapabilities::openai_like();
        caps.tools = false;
        let plan = plan_tools(
            &registry(),
            &ToolSwitches {
                local: true,
                ..ToolSwitches::default()
            },
            &permissive(),
            &caps,
            &["fs".to_string()],
        );
        assert!(plan.declared.is_empty());
        assert_eq!(plan.dropped.len(), registry().len());
        assert!(plan
            .dropped
            .iter()
            .all(|(_, reason)| *reason == ToolDrop::ModelHasNoTools));
    }

    /// 换模型只影响这一份计划，`ToolSwitches` 一个字节都不动（D79）。
    #[test]
    fn switching_models_never_rewrites_the_switches() {
        let switches = ToolSwitches {
            local: true,
            ..ToolSwitches::default()
        };
        let before = switches.clone();

        let mut caps = ProviderCapabilities::openai_like();
        caps.tools = false;
        let _weak = plan_tools(&registry(), &switches, &permissive(), &caps, &[]);
        let _strong = plan_tools(
            &registry(),
            &switches,
            &permissive(),
            &ProviderCapabilities::openai_like(),
            &[],
        );

        assert_eq!(switches, before, "计划变了，设置不能变");
    }

    #[test]
    fn an_unattributable_mcp_name_is_dropped_not_guessed() {
        let mut registry = ToolRegistry::new();
        registry.register(Box::new(FakeTool {
            name: "mcp.fs.read",
            capability: Capability::WorkspaceRead,
        }));
        let plan = plan_tools(
            &registry,
            &ToolSwitches {
                local: true,
                ..ToolSwitches::default()
            },
            &permissive(),
            &ProviderCapabilities::openai_like(),
            &["fs".to_string()],
        );
        assert_eq!(
            plan.drop_reason("mcp.fs.read"),
            Some(&ToolDrop::McpNameNotAttributable),
            "认不出服务器就不要声明，猜错了模型会去打一个不存在的工具"
        );
    }

    #[test]
    fn mcp_server_parsing() {
        assert_eq!(mcp_server_of("mcp__fs__read_file"), Some("fs"));
        assert_eq!(mcp_server_of("mcp__fs"), None);
        assert_eq!(mcp_server_of("mcp____x"), None);
        assert_eq!(mcp_server_of("read_file"), None);
    }
}
