//! 一轮之内模型冻结（D76）。
//!
//! 用户 2026-10-21：每轮开始时选的模型，这一轮从头到尾都是它；触发自动压缩、
//! 上下文裁剪也用它，直到这一轮结束。助手各自还绑一个对话模型（D77），给以后的子 AGENT 用。
//!
//! 为什么必须"冻结"而不是"每次读一遍设置"：
//!
//! 1. 工具循环一轮里会发好几次请求。中途用户去设置页改了参数，第 3 次请求和第 1 次
//!    长得不一样——轻则断点缓存全废，重则模型看到两套自相矛盾的指令。
//! 2. 压缩是**本轮之内**触发的。它要是读了"现在的设置"，就会跟本轮对话用两个模型，
//!    摘要的口径也跟着变。
//!
//! 冻结的内容是**算完的生效值**，不是原始设置：`ModelOverrides::effective` 已经把
//! "能不能发"裁过了（D79），这里存的是"这一轮真的要发的东西"。存设置的话，
//! 每一处用它的人还得各自再裁一遍，迟早裁得不一样。

use crate::budget::is_mcp_tool;
use crate::config::{default_thinking_field, ModelOverrides, ThinkingContext, ThinkingEmission};
use crate::gate::{plan_tools, ToolPlan};
use crate::model::ModelProfile;
use crate::policy::AssistantPolicy;
use crate::provider::{ProviderCapabilities, ThinkingSetting};
use crate::runtime::ChatRequest;
use crate::settings::ToolSwitches;
use crate::tools::ToolRegistry;

/// 冻结好的一轮。
///
/// 它自己拥有全部数据（没有借用），所以"冻结之后设置又变了"这件事根本传不进来。
#[derive(Debug, Clone, PartialEq)]
pub struct TurnPlan {
    /// 这一轮从头到尾用的模型 id。
    pub model: String,
    /// 生效参数：能力、上下文窗口、最大输出、思考设置。
    pub profile: ModelProfile,
    /// 思考字段这次到底发不发、为什么没发。UI 要能解释"我选了 max 怎么没生效"。
    pub thinking: ThinkingEmission,
    /// 工具声明计划（三轴求交 + MCP 两级门的结果）。
    pub tools: ToolPlan,
}

/// 冻结时的全部输入。参数多，但都是"这一轮开始时手上有的东西"，打包成一个结构
/// 比八个位置参数好读——至少调用点看得见每个是什么。
pub struct Freeze<'a> {
    pub model: &'a str,
    pub profile: &'a ModelProfile,
    /// `None` = 没有用户覆盖，全用模型元数据。
    pub overrides: Option<&'a ModelOverrides>,
    pub context: ThinkingContext,
    pub registry: &'a ToolRegistry,
    pub switches: &'a ToolSwitches,
    pub policy: &'a AssistantPolicy,
    /// **连接成功**的 MCP 服务器。没连上的不会进声明（D85）。
    pub connected_mcp: &'a [String],
}

impl TurnPlan {
    pub fn freeze(input: Freeze<'_>) -> Self {
        let profile = match input.overrides {
            Some(overrides) => overrides.effective(input.profile).0,
            None => input.profile.clone(),
        };

        let thinking = match input.overrides {
            Some(overrides) => overrides.emission(input.profile),
            // 没有覆盖 = 用默认：`Auto` 不注字段，模型自己决定。
            None => ThinkingEmission::Setting(crate::config::ThinkingChoice::Auto.resolve(
                default_thinking_field(&input.context),
                &input.profile.supported_efforts,
            )),
        };

        let tools = plan_tools(
            input.registry,
            input.switches,
            input.policy,
            &profile.capabilities,
            input.connected_mcp,
        );

        Self {
            model: input.model.to_string(),
            profile,
            thinking,
            tools,
        }
    }

    pub fn capabilities(&self) -> &ProviderCapabilities {
        &self.profile.capabilities
    }

    /// 声明给模型的工具名（不含 MCP 里没连上的、被门控挡掉的）。
    pub fn declared_tools(&self) -> &[String] {
        &self.tools.declared
    }

    pub fn context_window(&self) -> u64 {
        self.profile.context_window
    }

    pub fn max_output(&self) -> u64 {
        self.profile.max_output
    }

    /// 把冻结值写进一份请求。
    ///
    /// **只写模型与思考字段**：`max_tokens` 归调用方定（D9/FALLBACK 那一套有自己的口径），
    /// 工具声明要靠 registry 现取（这里只有名字）。
    pub fn apply(&self, request: &mut ChatRequest) {
        request.model = self.model.clone();
        request.thinking = self
            .thinking
            .setting()
            .cloned()
            .unwrap_or_else(ThinkingSetting::disabled);
    }

    /// 这一轮用的 MCP 服务器里有谁没连上。设置页据此提示重连。
    pub fn mcp_not_connected(&self) -> &[String] {
        &self.tools.mcp_not_connected
    }

    /// 某个工具这次为什么没声明（`None` = 声明了或者压根没这个工具）。
    pub fn tool_drop_reason(&self, name: &str) -> Option<&crate::gate::ToolDrop> {
        self.tools.drop_reason(name)
    }

    /// 声明表里有没有 MCP 工具——压缩、标题这些旁路调用要据此决定带不带工具声明。
    pub fn declares_mcp(&self) -> bool {
        self.tools.declared.iter().any(|name| is_mcp_tool(name))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::ThinkingChoice;
    use crate::policy::WorkspaceBinding;
    use crate::provider::{ProviderKind, ThinkingEffort};
    use crate::settings::SkillSelection;

    fn model_profile() -> ModelProfile {
        ModelProfile {
            capabilities: ProviderCapabilities::openai_like(),
            thinking: ThinkingSetting::disabled(),
            reasoning: true,
            supported_efforts: vec![ThinkingEffort::Low, ThinkingEffort::High],
            supports_toggle: false,
            context_window: 131_072,
            max_output: 8_192,
            cost: None,
        }
    }

    fn policy() -> AssistantPolicy {
        AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_write("ws"))
            .enable_container(true)
    }

    fn plan(overrides: Option<&ModelOverrides>) -> TurnPlan {
        let registry = ToolRegistry::new();
        let switches = ToolSwitches {
            local: false,
            ..ToolSwitches::default()
        };
        let profile = model_profile();
        TurnPlan::freeze(Freeze {
            model: "vendor/model-b",
            profile: &profile,
            overrides,
            context: ThinkingContext::new(
                ProviderKind::OpenAiCompatible,
                crate::config::ModelFlavor::Other,
            ),
            registry: &registry,
            switches: &switches,
            policy: &policy(),
            connected_mcp: &[],
        })
    }

    #[test]
    fn freezing_keeps_the_model_id_and_the_effective_window() {
        let frozen = plan(None);
        assert_eq!(frozen.model, "vendor/model-b");
        assert_eq!(frozen.context_window(), 131_072);
        assert_eq!(frozen.max_output(), 8_192);
        // 本地工具关着，所以内置工具一个都不声明（工作区工具照旧）
        assert!(frozen
            .declared_tools()
            .iter()
            .all(|name| !name.contains("run")));
    }

    /// 冻结之后设置再变，这一轮不受影响。
    #[test]
    fn a_later_settings_change_cannot_reach_a_frozen_turn() {
        let mut overrides = ModelOverrides::from_profile(
            &model_profile(),
            &ThinkingContext::new(
                ProviderKind::OpenAiCompatible,
                crate::config::ModelFlavor::Other,
            ),
        );
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::High),
            &ThinkingChoice::Auto,
        );

        let frozen = plan(Some(&overrides));
        assert!(matches!(frozen.thinking, ThinkingEmission::Setting(_)));

        // 冻结之后用户把档位改掉——本轮已经跑着的那一份不受影响
        overrides
            .thinking_effort
            .set_by_user(ThinkingChoice::Off, &ThinkingChoice::Auto);
        assert!(
            matches!(frozen.thinking, ThinkingEmission::Setting(_)),
            "冻结值不许跟着设置走"
        );
        let request = ChatRequest::new(
            crate::provider::ProviderConfig::new("p", ProviderKind::OpenAiCompatible, "http://x"),
            "x",
        );
        let mut request = request;
        frozen.apply(&mut request);
        assert_eq!(request.model, "vendor/model-b");
    }

    /// 档位这个模型没有：这次不发，但冻结里记着"为什么没发"。
    #[test]
    fn an_unsupported_level_is_recorded_not_silently_dropped() {
        let mut overrides = ModelOverrides::from_profile(
            &model_profile(),
            &ThinkingContext::new(
                ProviderKind::OpenAiCompatible,
                crate::config::ModelFlavor::Other,
            ),
        );
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::Max),
            &ThinkingChoice::Auto,
        );
        let frozen = plan(Some(&overrides));
        assert_eq!(
            frozen.thinking,
            ThinkingEmission::Dropped {
                reason: crate::config::TrimReason::LevelNotSupported,
                stored: ThinkingChoice::Level(ThinkingEffort::Max),
            }
        );
        assert!(frozen.thinking.setting().is_none());

        // 请求里真的什么都没有
        let mut request = ChatRequest::new(
            crate::provider::ProviderConfig::new("p", ProviderKind::OpenAiCompatible, "http://x"),
            "x",
        );
        frozen.apply(&mut request);
        assert_eq!(request.thinking.effort, None);
    }

    #[test]
    fn mcp_tools_are_absent_when_nothing_is_connected() {
        // 技能不进冻结（那是"给模型看什么"），MCP 进——因为"连没连上"决定声明表。
        let _ = SkillSelection::default();
        let frozen = plan(None);
        assert!(!frozen.declares_mcp());
        assert!(frozen.mcp_not_connected().is_empty());
    }
}
