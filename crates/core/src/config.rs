//! 逐参数的"默认 / 已修改"覆盖机制。
//!
//! 用户要求（原话归纳）：
//!
//! 1. **每个参数都能手动配**；手动值与原默认值不同 → 标记 `已修改`；
//! 2. **每个参数都有恢复按钮**，恢复后值回到默认、标记回到 `默认`；
//! 3. **已修改的内容不跟随自动变化**；只有 `默认` 状态的字段会跟着新默认走。
//!
//! 第三条是关键。举例：思考字段族会随 provider 变化（OpenAI 兼容 → `reasoning_effort`，
//! 开了 Responses → `reasoning.effort`）。这时：
//!
//! ```text
//! 用户没动过 → 跟着变（reasoning_effort ⇄ reasoning.effort）
//! 用户改过   → 一个字都不改，等他自己点"恢复"
//! ```
//!
//! 另外"模型血统优先于 provider"：Claude 模型无论挂在哪个供应商后面，都用 Anthropic 的字段族；
//! Gemini 同理。这条写在 [`default_thinking_field`] 里，是本模块唯一的策略函数。

use serde::{Deserialize, Serialize};

use crate::model::ModelProfile;
use crate::provider::{
    ProviderCapabilities, ProviderKind, ThinkingEffort, ThinkingField, ThinkingSetting,
};

/// UI 上显示的那个小标记。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Mark {
    /// 用默认值。
    Default,
    /// 用户改过，不跟随自动变化。
    Modified,
}

impl Mark {
    pub fn as_str(self) -> &'static str {
        match self {
            Mark::Default => "default",
            Mark::Modified => "modified",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Mark::Default => "默认",
            Mark::Modified => "已修改",
        }
    }
}

/// 一个参数的状态：值 + 它是不是用户改的。值本身不区分"来源"，标记才是。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum OverrideState<T> {
    Default(T),
    Modified(T),
}

impl<T> OverrideState<T> {
    pub fn default_value(value: T) -> Self {
        OverrideState::Default(value)
    }

    pub fn modified_value(value: T) -> Self {
        OverrideState::Modified(value)
    }

    pub fn value(&self) -> &T {
        match self {
            OverrideState::Default(value) | OverrideState::Modified(value) => value,
        }
    }

    pub fn into_value(self) -> T {
        match self {
            OverrideState::Default(value) | OverrideState::Modified(value) => value,
        }
    }

    pub fn mark(&self) -> Mark {
        match self {
            OverrideState::Default(_) => Mark::Default,
            OverrideState::Modified(_) => Mark::Modified,
        }
    }

    pub fn is_modified(&self) -> bool {
        matches!(self, OverrideState::Modified(_))
    }
}

impl<T: PartialEq + Clone> OverrideState<T> {
    /// 用户手动设值。**与当前默认值相同就不算改过**——这样"改回默认值"会自然丢掉标记。
    pub fn set_by_user(&mut self, value: T, default: &T) {
        if &value == default {
            *self = OverrideState::Default(value);
        } else {
            *self = OverrideState::Modified(value);
        }
    }

    /// 跟随新的默认值：只有处于 `默认` 状态才动，`已修改` 保持原值。
    pub fn follow(&mut self, new_default: T) {
        if let OverrideState::Default(_) = self {
            *self = OverrideState::Default(new_default);
        }
    }

    /// 恢复按钮：值回到默认，标记回 `默认`。
    pub fn restore(&mut self, default: T) {
        *self = OverrideState::Default(default);
    }
}

/// 模型血统。决定用哪一族思考字段——**模型说了算，provider 说了不算**。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelFlavor {
    Anthropic,
    Gemini,
    /// 其余（OpenAI 系、DeepSeek、Qwen…）交给 provider 类型决定。
    Other,
}

impl ModelFlavor {
    /// 从归一化后的家族名推断血统。
    pub fn of(family: &str) -> Self {
        let family = family.to_lowercase();
        if family.starts_with("claude") {
            ModelFlavor::Anthropic
        } else if family.starts_with("gemini") {
            ModelFlavor::Gemini
        } else {
            ModelFlavor::Other
        }
    }
}

/// 决定"默认该用哪一族思考字段"的全部输入。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThinkingContext {
    pub provider_kind: ProviderKind,
    pub flavor: ModelFlavor,
}

impl ThinkingContext {
    pub fn new(provider_kind: ProviderKind, flavor: ModelFlavor) -> Self {
        Self {
            provider_kind,
            flavor,
        }
    }
}

/// 默认思考字段族。
///
/// - Claude 模型 → Anthropic 族（无论挂在哪个供应商后面）
/// - Gemini 模型 → Gemini 族（同理）
/// - 其余 → 看 provider 类型：Responses 用 `reasoning.effort`，其余 OpenAI 兼容用 `reasoning_effort`
pub fn default_thinking_field(context: &ThinkingContext) -> ThinkingField {
    match context.flavor {
        ModelFlavor::Anthropic => ThinkingField::AnthropicEffort,
        ModelFlavor::Gemini => ThinkingField::GeminiThinkingConfig,
        ModelFlavor::Other => match context.provider_kind {
            ProviderKind::OpenAiResponses => ThinkingField::ResponsesReasoning,
            ProviderKind::Anthropic => ThinkingField::AnthropicEffort,
            ProviderKind::Gemini => ThinkingField::GeminiThinkingConfig,
            ProviderKind::OpenAiCompatible => ThinkingField::OpenAiEffort,
        },
    }
}

/// 用户看到的思考强度选择。滑块从右往左是：关闭 → 自动 → 低 → … → 高。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingChoice {
    /// 最左：关闭。能真关就真关，关不掉会给出提示（见 [`ThinkingChoice::resolve`]）。
    Off,
    /// 默认值：什么都不发，用 provider / 模型自己的默认档。
    #[default]
    Auto,
    /// 指定档位。
    Level(ThinkingEffort),
}

/// `ThinkingChoice` 解析成实际要发的设置。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedThinking {
    pub setting: ThinkingSetting,
    /// 想关但模型不支持真的关（只能"不指定"，provider 默认仍会思考）。
    pub off_is_best_effort: bool,
}

impl ThinkingChoice {
    /// 滑块上的完整档位列表。**`关闭` 与 `自动` 永远存在**，与模型有没有档位数据无关：
    /// 没有档位数据的模型，滑块就是"关闭 / 自动"两格。
    ///
    /// 顺序即用户要的：关闭在最左，自动紧随，然后从低到高。
    /// `supported` 里的 `none` 不再单列——它就是"关闭"。
    pub fn slider(supported: &[ThinkingEffort]) -> Vec<ThinkingChoice> {
        use ThinkingEffort::*;
        let mut levels: Vec<ThinkingEffort> = ThinkingEffort::sorted(
            supported
                .iter()
                .copied()
                .filter(|effort| *effort != None)
                .collect(),
        );
        levels.retain(|effort| !matches!(effort, Minimal) || supported.contains(&Minimal));

        let mut out = vec![ThinkingChoice::Off, ThinkingChoice::Auto];
        out.extend(levels.into_iter().map(ThinkingChoice::Level));
        out
    }

    /// `supported` 来自模型元数据（`ModelProfile::supported_efforts`）。
    pub fn resolve(self, field: ThinkingField, supported: &[ThinkingEffort]) -> ResolvedThinking {
        match self {
            ThinkingChoice::Off => {
                // 硬规则：**关闭必须显式**。
                // 早期版本在"档位里没有 none"时改成不注字段——那其实退化成了自动：
                // 线上请求长得一模一样，用户以为关了，其实用的是 provider 默认（通常是开）。
                //
                // 所以：一律显式发 `none`。能不能真关由模型/端点决定，我们只负责
                // 把"这是尽力而为"这件事标出来给 UI（metadata 里没有 none 就是尽力而为）。
                //
                // 例外只有 Anthropic 的**旧版预算式**字段（3.7–4.5）：它的关闭表达是
                // `thinking: {type: "disabled"}`，而不是 effort 值。
                match field {
                    ThinkingField::AnthropicThinking => ResolvedThinking {
                        setting: ThinkingSetting::with_field(field),
                        off_is_best_effort: false,
                    },
                    _ => ResolvedThinking {
                        setting: ThinkingSetting {
                            field,
                            effort: Some(ThinkingEffort::None),
                            budget_tokens: None,
                            custom: None,
                        },
                        off_is_best_effort: !supported.contains(&ThinkingEffort::None),
                    },
                }
            }
            ThinkingChoice::Auto => ResolvedThinking {
                setting: ThinkingSetting {
                    field,
                    effort: None,
                    budget_tokens: None,
                    custom: None,
                },
                off_is_best_effort: false,
            },
            ThinkingChoice::Level(effort) => ResolvedThinking {
                setting: ThinkingSetting {
                    field,
                    effort: Some(effort),
                    budget_tokens: None,
                    custom: None,
                },
                off_is_best_effort: false,
            },
        }
    }
}

/// 这一份请求里，思考字段最终怎么发。
///
/// 关键规矩（D79）：**裁剪只发生在发出去的那一份上，永远不写回设置。**
///
/// 用户存的 `max` 在切到一个没有 `max` 档的模型时是什么？不是 `null`，也不是降级成 `high`，
/// 而是"这次不发"，存着的 `max` 一个字不动。切回来还是 `max`。
/// 别的做法（存成 null 再等用户重设）就是"切过去切回来丢设置"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ThinkingEmission {
    /// 照这个设置发（`effort: None` 表示不注字段）。
    Setting(ResolvedThinking),
    /// 这次一个思考字段都不发，原因在 `TrimReason` 里。
    Dropped {
        reason: TrimReason,
        /// 用户存着的是什么——UI 用它说清"你选的是 max，这个模型没有，本次没发"。
        stored: ThinkingChoice,
    },
}

impl ThinkingEmission {
    /// 实际要写进请求体的设置；`None` 表示一个字都不写。
    pub fn setting(&self) -> Option<&ThinkingSetting> {
        match self {
            ThinkingEmission::Setting(resolved) => Some(&resolved.setting),
            ThinkingEmission::Dropped { .. } => None,
        }
    }

    pub fn is_dropped(&self) -> bool {
        matches!(self, ThinkingEmission::Dropped { .. })
    }
}

/// 这次为什么没发思考字段。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrimReason {
    /// 这个模型根本不做推理（元数据 `reasoning: false`）。
    /// "不发"本身就是关，再显式写 `none` 反而是瞎猜（可能 400）。
    ModelHasNoReasoning,
    /// 模型能推理，但没有这一档。
    LevelNotSupported,
    /// 只能开 / 关，没有档位。
    ToggleOnly,
}

impl TrimReason {
    pub fn label(self) -> &'static str {
        match self {
            TrimReason::ModelHasNoReasoning => "此模型不支持推理",
            TrimReason::LevelNotSupported => "此模型没有这一档",
            TrimReason::ToggleOnly => "此模型只能开关，没有档位",
        }
    }
}

/// 能力位的手动覆盖（每个布尔一个标记）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CapabilityOverrides {
    pub streaming: OverrideState<bool>,
    pub streaming_usage: OverrideState<bool>,
    pub images: OverrideState<bool>,
    pub tools: OverrideState<bool>,
    pub structured_output: OverrideState<bool>,
    pub assistant_prefill: OverrideState<bool>,
    pub reasoning_replay_required: OverrideState<bool>,
}

impl CapabilityOverrides {
    pub fn from_capabilities(capabilities: &ProviderCapabilities) -> Self {
        Self {
            streaming: OverrideState::default_value(capabilities.streaming),
            streaming_usage: OverrideState::default_value(capabilities.streaming_usage),
            images: OverrideState::default_value(capabilities.images),
            tools: OverrideState::default_value(capabilities.tools),
            structured_output: OverrideState::default_value(capabilities.structured_output),
            assistant_prefill: OverrideState::default_value(capabilities.assistant_prefill),
            reasoning_replay_required: OverrideState::default_value(
                capabilities.reasoning_replay_required,
            ),
        }
    }

    pub fn follow(&mut self, capabilities: &ProviderCapabilities) {
        self.streaming.follow(capabilities.streaming);
        self.streaming_usage.follow(capabilities.streaming_usage);
        self.images.follow(capabilities.images);
        self.tools.follow(capabilities.tools);
        self.structured_output
            .follow(capabilities.structured_output);
        self.assistant_prefill
            .follow(capabilities.assistant_prefill);
        self.reasoning_replay_required
            .follow(capabilities.reasoning_replay_required);
    }

    pub fn effective(&self) -> ProviderCapabilities {
        ProviderCapabilities {
            streaming: *self.streaming.value(),
            streaming_usage: *self.streaming_usage.value(),
            images: *self.images.value(),
            tools: *self.tools.value(),
            structured_output: *self.structured_output.value(),
            assistant_prefill: *self.assistant_prefill.value(),
            reasoning_replay_required: *self.reasoning_replay_required.value(),
        }
    }

    pub fn modified_fields(&self) -> Vec<&'static str> {
        let mut out = Vec::new();
        for (name, state) in [
            ("streaming", &self.streaming),
            ("streaming_usage", &self.streaming_usage),
            ("images", &self.images),
            ("tools", &self.tools),
            ("structured_output", &self.structured_output),
            ("assistant_prefill", &self.assistant_prefill),
            ("reasoning_replay_required", &self.reasoning_replay_required),
        ] {
            if state.is_modified() {
                out.push(name);
            }
        }
        out
    }
}

/// 一个模型的全部可覆盖参数。新建时全为 `默认`。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelOverrides {
    pub context_window: OverrideState<u64>,
    pub max_output: OverrideState<u64>,
    pub capabilities: CapabilityOverrides,
    /// 思考字段族。**默认态会随 provider / 模型血统自动切换**，改过就不动。
    pub thinking_field: OverrideState<ThinkingField>,
    /// 思考强度。每个模型独立记忆，默认 `Auto`。
    pub thinking_effort: OverrideState<ThinkingChoice>,
}

impl ModelOverrides {
    /// 用模型默认值初始化：全部标 `默认`。
    pub fn from_profile(profile: &ModelProfile, context: &ThinkingContext) -> Self {
        Self {
            context_window: OverrideState::default_value(profile.context_window),
            max_output: OverrideState::default_value(profile.max_output),
            capabilities: CapabilityOverrides::from_capabilities(&profile.capabilities),
            thinking_field: OverrideState::default_value(default_thinking_field(context)),
            thinking_effort: OverrideState::default_value(ThinkingChoice::Auto),
        }
    }

    /// 跟随新的模型 / provider 默认值。**已修改的字段一个字都不改。**
    pub fn follow_defaults(&mut self, profile: &ModelProfile, context: &ThinkingContext) {
        self.context_window.follow(profile.context_window);
        self.max_output.follow(profile.max_output);
        self.capabilities.follow(&profile.capabilities);
        self.thinking_field.follow(default_thinking_field(context));
        // 强度默认永远是 Auto（用户没动过就一直是 Auto）
        self.thinking_effort.follow(ThinkingChoice::Auto);
    }

    /// 这一份请求实际怎么发思考字段（D79）。**只读，不改 overrides。**
    ///
    /// 判据顺序：
    /// 1. 模型不做推理 → 什么都不发（"不发"就是关，写 `none` 属于瞎猜）；
    /// 2. 用户选了 `Auto` / `Off` → 照 `resolve` 走；
    /// 3. 用户选了某一档 → 模型只有开/关、或者没有这一档 → 这次不发；
    /// 4. 其余照发。
    ///
    /// `supported_efforts` 为空表示**没数据**，不是"不支持"——这时候照发，
    /// 别把"我们不知道"变成"你不能用"。
    pub fn emission(&self, profile: &ModelProfile) -> ThinkingEmission {
        let field = *self.thinking_field.value();
        let stored = *self.thinking_effort.value();

        if !profile.reasoning {
            return ThinkingEmission::Dropped {
                reason: TrimReason::ModelHasNoReasoning,
                stored,
            };
        }

        if let ThinkingChoice::Level(effort) = stored {
            if profile.supports_toggle {
                return ThinkingEmission::Dropped {
                    reason: TrimReason::ToggleOnly,
                    stored,
                };
            }
            if !profile.supported_efforts.is_empty() && !profile.supported_efforts.contains(&effort)
            {
                return ThinkingEmission::Dropped {
                    reason: TrimReason::LevelNotSupported,
                    stored,
                };
            }
        }

        ThinkingEmission::Setting(stored.resolve(field, &profile.supported_efforts))
    }

    /// 合成生效值。返回 (profile, 思考设置, 关闭是否只是尽力而为)。
    ///
    /// 思考那一项走 [`ModelOverrides::emission`]：被裁掉的时候这里是 `disabled()`，
    /// 与"请求体里真的没有思考字段"保持一致。两条路各算各的，迟早会分叉。
    pub fn effective(&self, profile: &ModelProfile) -> (ModelProfile, ThinkingSetting, bool) {
        let (setting, off_is_best_effort) = match self.emission(profile) {
            ThinkingEmission::Setting(resolved) => (resolved.setting, resolved.off_is_best_effort),
            ThinkingEmission::Dropped { .. } => (ThinkingSetting::disabled(), false),
        };
        let effective = ModelProfile {
            capabilities: self.capabilities.effective(),
            thinking: setting.clone(),
            reasoning: profile.reasoning,
            supported_efforts: profile.supported_efforts.clone(),
            supports_toggle: profile.supports_toggle,
            context_window: *self.context_window.value(),
            max_output: *self.max_output.value(),
            cost: profile.cost,
        };
        (effective, setting, off_is_best_effort)
    }

    /// 被改过的字段名（UI 用来渲染"已修改"标记与"全部恢复"）。
    pub fn modified_fields(&self) -> Vec<&'static str> {
        let mut out = Vec::new();
        if self.context_window.is_modified() {
            out.push("context_window");
        }
        if self.max_output.is_modified() {
            out.push("max_output");
        }
        if self.thinking_field.is_modified() {
            out.push("thinking_field");
        }
        if self.thinking_effort.is_modified() {
            out.push("thinking_effort");
        }
        out.extend(self.capabilities.modified_fields());
        out
    }

    pub fn has_modifications(&self) -> bool {
        !self.modified_fields().is_empty()
    }

    /// "全部恢复默认"。
    pub fn restore_all(&mut self, profile: &ModelProfile, context: &ThinkingContext) {
        self.context_window.restore(profile.context_window);
        self.max_output.restore(profile.max_output);
        self.capabilities = CapabilityOverrides::from_capabilities(&profile.capabilities);
        self.thinking_field.restore(default_thinking_field(context));
        self.thinking_effort.restore(ThinkingChoice::Auto);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::provider::ProviderCapabilities;

    fn profile() -> ModelProfile {
        ModelProfile {
            capabilities: ProviderCapabilities::openai_like(),
            thinking: ThinkingSetting::disabled(),
            reasoning: true,
            supported_efforts: vec![
                ThinkingEffort::Low,
                ThinkingEffort::High,
                ThinkingEffort::Max,
            ],
            supports_toggle: true,
            context_window: 128_000,
            max_output: 8_192,
            cost: None,
        }
    }

    fn openai(context_provider: ProviderKind) -> ThinkingContext {
        ThinkingContext::new(context_provider, ModelFlavor::Other)
    }

    #[test]
    fn setting_the_default_value_keeps_the_default_mark() {
        let mut state = OverrideState::default_value(128_000u64);
        state.set_by_user(128_000, &128_000);
        assert_eq!(state.mark(), Mark::Default, "改回默认值不该留标记");

        state.set_by_user(200_000, &128_000);
        assert_eq!(state.mark(), Mark::Modified);
        assert_eq!(*state.value(), 200_000);
    }

    #[test]
    fn follow_only_touches_default_state() {
        let mut untouched = OverrideState::default_value(100u64);
        let mut touched = OverrideState::default_value(100u64);
        touched.set_by_user(999, &100);

        untouched.follow(200);
        touched.follow(200);

        assert_eq!(*untouched.value(), 200, "没改过的跟着走");
        assert_eq!(*touched.value(), 999, "改过的一个字都不动");
        assert_eq!(touched.mark(), Mark::Modified);
    }

    #[test]
    fn restore_resets_value_and_mark() {
        let mut state = OverrideState::default_value(100u64);
        state.set_by_user(999, &100);
        state.restore(100);
        assert_eq!(*state.value(), 100);
        assert_eq!(state.mark(), Mark::Default);
    }

    #[test]
    fn thinking_field_follows_provider_switch() {
        let model = profile();
        let mut overrides =
            ModelOverrides::from_profile(&model, &openai(ProviderKind::OpenAiCompatible));
        assert_eq!(
            *overrides.thinking_field.value(),
            ThinkingField::OpenAiEffort
        );

        // provider 换成 Responses：默认态跟着切
        overrides.follow_defaults(&model, &openai(ProviderKind::OpenAiResponses));
        assert_eq!(
            *overrides.thinking_field.value(),
            ThinkingField::ResponsesReasoning
        );

        // 用户改过之后，再怎么切都不动
        overrides.thinking_field.set_by_user(
            ThinkingField::OpenAiEffort,
            &ThinkingField::ResponsesReasoning,
        );
        overrides.follow_defaults(&model, &openai(ProviderKind::OpenAiCompatible));
        overrides.follow_defaults(&model, &openai(ProviderKind::OpenAiResponses));
        assert_eq!(
            *overrides.thinking_field.value(),
            ThinkingField::OpenAiEffort
        );
        assert!(overrides.thinking_field.is_modified());
        assert!(overrides.modified_fields().contains(&"thinking_field"));
    }

    #[test]
    fn anthropic_and_gemini_models_win_over_provider() {
        // Claude 挂在 OpenAI 兼容的聚合站后面，仍然用 Anthropic 字段族
        let claude = ThinkingContext::new(
            ProviderKind::OpenAiCompatible,
            ModelFlavor::of("claude-opus"),
        );
        assert_eq!(
            default_thinking_field(&claude),
            ThinkingField::AnthropicEffort
        );
        // 开着 Responses 也一样
        let claude_responses = ThinkingContext::new(
            ProviderKind::OpenAiResponses,
            ModelFlavor::of("claude-opus"),
        );
        assert_eq!(
            default_thinking_field(&claude_responses),
            ThinkingField::AnthropicEffort
        );

        let gemini = ThinkingContext::new(
            ProviderKind::OpenAiCompatible,
            ModelFlavor::of("gemini-3-pro"),
        );
        assert_eq!(
            default_thinking_field(&gemini),
            ThinkingField::GeminiThinkingConfig
        );

        // 非 Anthropic / Gemini 的模型才看 provider
        let deepseek = ThinkingContext::new(
            ProviderKind::OpenAiResponses,
            ModelFlavor::of("deepseek-v4"),
        );
        assert_eq!(
            default_thinking_field(&deepseek),
            ThinkingField::ResponsesReasoning
        );
        assert_eq!(ModelFlavor::of("GLM-5"), ModelFlavor::Other);
    }

    #[test]
    fn effort_defaults_to_auto_and_is_per_model() {
        let model = profile();
        let a = ModelOverrides::from_profile(&model, &openai(ProviderKind::OpenAiCompatible));
        let b = ModelOverrides::from_profile(&model, &openai(ProviderKind::OpenAiCompatible));
        assert_eq!(*a.thinking_effort.value(), ThinkingChoice::Auto);
        // 每个模型各自一份，改一个不影响另一个
        assert_eq!(a, b);
    }

    #[test]
    fn slider_order_is_off_auto_then_levels() {
        let supported = ThinkingEffort::sorted(vec![
            ThinkingEffort::Max,
            ThinkingEffort::None,
            ThinkingEffort::High,
            ThinkingEffort::Low,
        ]);
        assert_eq!(
            supported,
            vec![
                ThinkingEffort::None,
                ThinkingEffort::Low,
                ThinkingEffort::High,
                ThinkingEffort::Max
            ],
            "滑块从低到高；关闭在最左，自动紧随其后（由 UI 拼接）"
        );
    }

    #[test]
    fn deepseek_style_levels_map_to_reasoning_effort() {
        // 实测：DeepSeek V4 系是 low / high / max
        let resolved = ThinkingChoice::Level(ThinkingEffort::Max).resolve(
            ThinkingField::OpenAiEffort,
            &[
                ThinkingEffort::Low,
                ThinkingEffort::High,
                ThinkingEffort::Max,
            ],
        );
        let mut body = serde_json::json!({});
        resolved.setting.apply(&mut body);
        assert_eq!(body["reasoning_effort"], serde_json::json!("max"));
        assert!(!resolved.off_is_best_effort);
    }

    #[test]
    fn auto_sends_nothing() {
        let resolved = ThinkingChoice::Auto.resolve(ThinkingField::OpenAiEffort, &[]);
        let mut body = serde_json::json!({ "model": "m" });
        resolved.setting.apply(&mut body);
        assert_eq!(
            body,
            serde_json::json!({ "model": "m" }),
            "自动 = 不指定，用 provider 默认"
        );
    }

    #[test]
    fn anthropic_effort_field_writes_output_config() {
        let resolved = ThinkingChoice::Level(ThinkingEffort::High)
            .resolve(ThinkingField::AnthropicEffort, &[ThinkingEffort::High]);
        let mut body = serde_json::json!({});
        resolved.setting.apply(&mut body);
        assert_eq!(body["output_config"]["effort"], serde_json::json!("high"));
    }

    #[test]
    fn capability_overrides_are_independent() {
        let model = profile();
        let mut overrides =
            ModelOverrides::from_profile(&model, &openai(ProviderKind::OpenAiCompatible));
        overrides
            .capabilities
            .images
            .set_by_user(false, &model.capabilities.images);
        assert!(overrides.capabilities.modified_fields().contains(&"images"));

        // 新 provider 默认给 images=true，也不该把这个改过的打开
        let mut newer = model.clone();
        newer.capabilities.images = true;
        overrides.follow_defaults(&newer, &openai(ProviderKind::OpenAiCompatible));
        assert!(!overrides.capabilities.effective().images);

        let (effective, _, _) = overrides.effective(&model);
        assert!(!effective.capabilities.images);
        assert!(effective.capabilities.tools, "没改过的仍是默认值");
    }

    #[test]
    fn restore_all_clears_every_mark() {
        let model = profile();
        let context = openai(ProviderKind::OpenAiCompatible);
        let mut overrides = ModelOverrides::from_profile(&model, &context);
        overrides
            .context_window
            .set_by_user(1, &model.context_window);
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::Max),
            &ThinkingChoice::Auto,
        );
        assert!(overrides.has_modifications());

        overrides.restore_all(&model, &context);
        assert!(!overrides.has_modifications());
        assert_eq!(*overrides.thinking_effort.value(), ThinkingChoice::Auto);
        assert_eq!(*overrides.context_window.value(), model.context_window);
    }
    // --- 切模型不丢设置（D79）-------------------------------------------

    #[test]
    fn switching_to_a_model_without_that_level_drops_the_field_and_keeps_the_setting() {
        let mut overrides =
            ModelOverrides::from_profile(&profile(), &openai(ProviderKind::OpenAiCompatible));
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::Max),
            &ThinkingChoice::Auto,
        );

        // 切到一个只有 low / high 的模型
        let mut weaker = profile();
        weaker.supports_toggle = false;
        weaker.supported_efforts = vec![ThinkingEffort::Low, ThinkingEffort::High];
        let emission = overrides.emission(&weaker);
        assert!(
            matches!(
                emission,
                ThinkingEmission::Dropped {
                    reason: TrimReason::LevelNotSupported,
                    ..
                }
            ),
            "没有 max 档就这次不发：{emission:?}"
        );
        assert!(emission.setting().is_none());

        // 存着的还是 max —— 这一步是整条规矩的重点
        assert_eq!(
            *overrides.thinking_effort.value(),
            ThinkingChoice::Level(ThinkingEffort::Max),
            "切模型不许改写用户存的值"
        );
        assert!(overrides.thinking_effort.is_modified());

        // 切回来照发（有档位的模型：能推理 + 有 max）
        let mut capable = profile();
        capable.supports_toggle = false;
        assert!(matches!(
            overrides.emission(&capable),
            ThinkingEmission::Setting(_)
        ));
    }

    #[test]
    fn a_model_without_reasoning_gets_no_thinking_field_at_all() {
        let mut overrides =
            ModelOverrides::from_profile(&profile(), &openai(ProviderKind::OpenAiCompatible));
        overrides
            .thinking_effort
            .set_by_user(ThinkingChoice::Off, &ThinkingChoice::Auto);

        let mut plain = profile();
        plain.reasoning = false;

        let emission = overrides.emission(&plain);
        assert_eq!(
            emission,
            ThinkingEmission::Dropped {
                reason: TrimReason::ModelHasNoReasoning,
                stored: ThinkingChoice::Off,
            },
            "不做推理的模型上写 `none` 是瞎猜，可能直接 400"
        );

        // effective 与 emission 必须是同一个结论
        let (_, setting, _) = overrides.effective(&plain);
        let mut body = serde_json::json!({ "model": "m" });
        setting.apply(&mut body);
        assert_eq!(body, serde_json::json!({ "model": "m" }));
    }

    #[test]
    fn toggle_only_model_drops_levels_but_still_honours_auto_and_off() {
        let mut toggle = profile();
        toggle.supports_toggle = true;
        toggle.supported_efforts = vec![ThinkingEffort::None];

        let mut overrides =
            ModelOverrides::from_profile(&profile(), &openai(ProviderKind::OpenAiCompatible));
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::High),
            &ThinkingChoice::Auto,
        );
        assert!(matches!(
            overrides.emission(&toggle),
            ThinkingEmission::Dropped {
                reason: TrimReason::ToggleOnly,
                ..
            }
        ));

        // 开 / 关在这种模型上是合法的
        overrides
            .thinking_effort
            .set_by_user(ThinkingChoice::Off, &ThinkingChoice::Auto);
        assert!(matches!(
            overrides.emission(&toggle),
            ThinkingEmission::Setting(_)
        ));
        overrides
            .thinking_effort
            .set_by_user(ThinkingChoice::Auto, &ThinkingChoice::Auto);
        assert!(matches!(
            overrides.emission(&toggle),
            ThinkingEmission::Setting(_)
        ));
    }

    #[test]
    fn empty_metadata_means_unknown_not_unsupported() {
        let mut unknown = profile();
        unknown.supports_toggle = false;
        unknown.supported_efforts.clear();

        let mut overrides =
            ModelOverrides::from_profile(&profile(), &openai(ProviderKind::OpenAiCompatible));
        overrides.thinking_effort.set_by_user(
            ThinkingChoice::Level(ThinkingEffort::High),
            &ThinkingChoice::Auto,
        );
        assert!(
            !overrides.emission(&unknown).is_dropped(),
            "没有档位数据不等于没有这一档，照发"
        );
    }
}
