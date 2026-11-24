//! 供应商适配层（D18–D21 的地基）。
//!
//! 这一层只做"配置 → 请求体"的确定部分：类型、能力位、思考字段族、body 合并与屏蔽。
//! 真正的 HTTP 与流式解析属于实现层，接口形状由这里定死，避免各处自己拼 JSON。
//!
//! body 合并顺序（后者覆盖前者，最后才是屏蔽）：
//!
//! ```text
//! 软件默认值 → provider 自定义 → model 自定义 → body 屏蔽（按路径删除）
//! ```
//!
//! 屏蔽必须最后执行：否则前面刚设的值会被漏掉。

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// 供应商类型。决定默认路径、默认思考字段族与默认能力位。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProviderKind {
    /// OpenAI 兼容：`POST /v1/chat/completions`
    OpenAiCompatible,
    /// OpenAI Responses：`POST /v1/responses`
    OpenAiResponses,
    Anthropic,
    Gemini,
}

impl ProviderKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ProviderKind::OpenAiCompatible => "openai_compatible",
            ProviderKind::OpenAiResponses => "openai_responses",
            ProviderKind::Anthropic => "anthropic",
            ProviderKind::Gemini => "gemini",
        }
    }

    /// 请求后缀（`base_url` 之后的那一段）。
    ///
    /// **约定：`base_url` 自带版本段**（`https://api.openai.com/v1`、
    /// `https://api.anthropic.com/v1`、`https://generativelanguage.googleapis.com/v1beta`），
    /// 因为各家版本前缀不同（`/v1` vs `/v1beta`），写死在路径里会让用户重复填。
    /// Gemini 的模型名嵌在路径中，所以这里给的是模式。
    pub fn chat_path(self) -> &'static str {
        match self {
            ProviderKind::OpenAiCompatible => "/chat/completions",
            ProviderKind::OpenAiResponses => "/responses",
            ProviderKind::Anthropic => "/messages",
            ProviderKind::Gemini => "/models/{model}:generateContent",
        }
    }

    /// 新增模型时默认用哪一族思考字段（用户可覆盖）。
    pub fn default_thinking_field(self) -> ThinkingField {
        match self {
            ProviderKind::OpenAiCompatible => ThinkingField::OpenAiEffort,
            ProviderKind::OpenAiResponses => ThinkingField::ResponsesReasoning,
            ProviderKind::Anthropic => ThinkingField::AnthropicThinking,
            ProviderKind::Gemini => ThinkingField::GeminiThinkingConfig,
        }
    }

    /// 输出上限字段名。Responses 系不吃 `max_tokens`。
    pub fn max_tokens_field(self) -> &'static str {
        match self {
            ProviderKind::OpenAiResponses => "max_output_tokens",
            _ => "max_tokens",
        }
    }
}

/// 供应商 / 模型的能力位。UI 上没能力的功能直接置灰，不假装支持。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderCapabilities {
    pub streaming: bool,
    pub streaming_usage: bool,
    pub images: bool,
    pub tools: bool,
    pub structured_output: bool,
    /// 支持以 assistant 消息结尾续写（强制续接的硬前提，D21）。
    pub assistant_prefill: bool,
    /// 协议要求回传历史思考内容（D20）；为真时思考内容不允许删除。
    pub reasoning_replay_required: bool,
}

impl ProviderCapabilities {
    /// 保守默认：能流式，其余全关。由 provider 配置或模型元数据覆盖。
    pub fn conservative() -> Self {
        Self {
            streaming: true,
            streaming_usage: false,
            images: false,
            tools: false,
            structured_output: false,
            assistant_prefill: false,
            reasoning_replay_required: false,
        }
    }

    /// OpenAI 兼容系的常见组合。
    pub fn openai_like() -> Self {
        Self {
            streaming: true,
            streaming_usage: true,
            images: true,
            tools: true,
            structured_output: true,
            assistant_prefill: false,
            reasoning_replay_required: false,
        }
    }
}

/// 思考强度档位。
///
/// 档位不是我们发明的，是各家实际接受的字符串的并集（实测分布见 notes/model-data.md）：
/// `none`（显式关）/ `minimal` / `low` / `medium` / `high` / `xhigh` / `max`。
/// **某个模型支持哪几档由模型元数据说了算**（`ModelProfile::supported_efforts`），
/// UI 的滑块只列支持的那些。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingEffort {
    /// 显式关闭（部分模型接受 `reasoning_effort: "none"`）。
    None,
    Minimal,
    Low,
    Medium,
    High,
    /// 比 high 更高的一档，model-specific。
    ///
    /// 各家实际发的是 `xhigh`（没有下划线），而 `rename_all = "snake_case"` 会写成
    /// `x_high`——所以这里必须显式指定，否则元数据里的档位解析不出来。
    #[serde(rename = "xhigh")]
    XHigh,
    Max,
    /// 顶档之上：模型自己拉子 AGENT 并行推理（gpt-5.6 / gpt-6 家族才认）。
    Ultra,
}

impl ThinkingEffort {
    /// 各家通用的书写形式（OpenAI / DeepSeek / Anthropic 的 effort 值都是这套词）。
    pub fn as_str(self) -> &'static str {
        match self {
            ThinkingEffort::None => "none",
            ThinkingEffort::Minimal => "minimal",
            ThinkingEffort::Low => "low",
            ThinkingEffort::Medium => "medium",
            ThinkingEffort::High => "high",
            ThinkingEffort::XHigh => "xhigh",
            ThinkingEffort::Max => "max",
            ThinkingEffort::Ultra => "ultra",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_lowercase().as_str() {
            "none" | "off" | "disabled" => Some(ThinkingEffort::None),
            "minimal" | "min" => Some(ThinkingEffort::Minimal),
            "low" => Some(ThinkingEffort::Low),
            "medium" | "med" => Some(ThinkingEffort::Medium),
            "high" => Some(ThinkingEffort::High),
            "xhigh" | "extra_high" => Some(ThinkingEffort::XHigh),
            "max" | "maximum" => Some(ThinkingEffort::Max),
            "ultra" => Some(ThinkingEffort::Ultra),
            _ => None,
        }
    }

    /// 从低到高排序用的序号（`None` 排最前，便于滑块"关闭在最左"）。
    pub fn rank(self) -> u8 {
        match self {
            ThinkingEffort::None => 0,
            ThinkingEffort::Minimal => 1,
            ThinkingEffort::Low => 2,
            ThinkingEffort::Medium => 3,
            ThinkingEffort::High => 4,
            ThinkingEffort::XHigh => 5,
            ThinkingEffort::Max => 6,
            ThinkingEffort::Ultra => 7,
        }
    }

    /// 把一组档位按从低到高排好（滑块顺序）。
    pub fn sorted(mut values: Vec<Self>) -> Vec<Self> {
        values.sort_by_key(|value| value.rank());
        values.dedup();
        values
    }
}

/// 思考字段族。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingField {
    /// 不注入任何思考字段（模型不支持或用户关掉了）。
    None,
    /// `reasoning_effort: "medium"`
    OpenAiEffort,
    /// `reasoning: { effort: "medium" }`
    ResponsesReasoning,
    /// `output_config: { effort: "high" }` —— Claude 4.7+ / 5 的档位式字段。
    /// 注意：这些模型上 `budget_tokens` 已被移除，注错了会直接报错。
    AnthropicEffort,
    /// `thinking: { type: "enabled", budget_tokens: 4096 }` —— 旧版预算式，仍被 3.7–4.5 使用。
    AnthropicThinking,
    /// `generationConfig.thinkingConfig: { thinkingBudget: 4096 }`
    GeminiThinkingConfig,
    /// 用户自己写 JSON 片段。
    Custom,
}

/// 一个模型的思考设置。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThinkingSetting {
    pub field: ThinkingField,
    pub effort: Option<ThinkingEffort>,
    /// Anthropic / Gemini 的预算式字段。
    pub budget_tokens: Option<u32>,
    /// `field = Custom` 时使用，直接合并进 body。
    pub custom: Option<Value>,
}

impl Default for ThinkingSetting {
    fn default() -> Self {
        Self {
            field: ThinkingField::None,
            effort: None,
            budget_tokens: None,
            custom: None,
        }
    }
}

impl ThinkingSetting {
    pub fn disabled() -> Self {
        Self::default()
    }

    pub fn with_field(field: ThinkingField) -> Self {
        Self {
            field,
            ..Self::default()
        }
    }

    pub fn effort(mut self, effort: ThinkingEffort) -> Self {
        self.effort = Some(effort);
        self
    }

    pub fn budget(mut self, tokens: u32) -> Self {
        self.budget_tokens = Some(tokens);
        self
    }

    /// 按字段族把思考设置写进请求体。`None` 表示不写。
    pub fn apply(&self, body: &mut Value) {
        let Some(object) = body.as_object_mut() else {
            return;
        };
        match self.field {
            ThinkingField::None => {}
            ThinkingField::OpenAiEffort => {
                if let Some(effort) = self.effort {
                    object.insert(
                        "reasoning_effort".into(),
                        Value::String(effort.as_str().into()),
                    );
                }
            }
            ThinkingField::ResponsesReasoning => {
                let mut reasoning = Map::new();
                if let Some(effort) = self.effort {
                    reasoning.insert("effort".into(), Value::String(effort.as_str().into()));
                }
                if let Some(budget) = self.budget_tokens {
                    reasoning.insert("max_tokens".into(), Value::from(budget));
                }
                if !reasoning.is_empty() {
                    object.insert("reasoning".into(), Value::Object(reasoning));
                }
            }
            ThinkingField::AnthropicEffort => {
                let mut output_config = Map::new();
                if let Some(effort) = self.effort {
                    output_config.insert("effort".into(), Value::String(effort.as_str().into()));
                }
                if !output_config.is_empty() {
                    object.insert("output_config".into(), Value::Object(output_config));
                }
            }
            ThinkingField::AnthropicThinking => {
                let mut thinking = Map::new();
                match self.budget_tokens {
                    Some(budget) if budget > 0 => {
                        thinking.insert("type".into(), Value::String("enabled".into()));
                        thinking.insert("budget_tokens".into(), Value::from(budget));
                    }
                    _ => {
                        thinking.insert("type".into(), Value::String("disabled".into()));
                    }
                }
                object.insert("thinking".into(), Value::Object(thinking));
            }
            ThinkingField::GeminiThinkingConfig => {
                let mut config = Map::new();
                if let Some(budget) = self.budget_tokens {
                    config.insert("thinkingBudget".into(), Value::from(budget));
                } else if let Some(effort) = self.effort {
                    // Gemini 的档位式字段，映射到它自己的枚举名
                    // Gemini 3 只认 minimal/low/medium/high，更高的档位向下取整
                    let level = match effort {
                        ThinkingEffort::None | ThinkingEffort::Minimal => "minimal",
                        ThinkingEffort::Low => "low",
                        ThinkingEffort::Medium => "medium",
                        ThinkingEffort::High
                        | ThinkingEffort::XHigh
                        | ThinkingEffort::Max
                        | ThinkingEffort::Ultra => "high",
                    };
                    config.insert("thinkingLevel".into(), Value::String(level.into()));
                }
                if !config.is_empty() {
                    let generation = object
                        .entry("generationConfig")
                        .or_insert_with(|| Value::Object(Map::new()));
                    if let Some(generation) = generation.as_object_mut() {
                        generation.insert("thinkingConfig".into(), Value::Object(config));
                    }
                }
            }
            ThinkingField::Custom => {
                if let Some(custom) = &self.custom {
                    deep_merge(body, custom);
                }
            }
        }
    }
}

/// 自定义 body 与屏蔽。provider 级与 model 级各一份，model 优先。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BodyOverrides {
    #[serde(default)]
    pub provider: Option<Value>,
    #[serde(default)]
    pub model: Option<Value>,
    /// 要删除的路径，点号分隔，如 `reasoning.effort`、`thinking.budget_tokens`。
    #[serde(default)]
    pub mask: Vec<String>,
}

impl BodyOverrides {
    /// 按"默认 → provider → model → 屏蔽"合成最终 body。
    pub fn apply(&self, defaults: Value) -> Value {
        let mut body = defaults;
        if let Some(provider) = &self.provider {
            deep_merge(&mut body, provider);
        }
        if let Some(model) = &self.model {
            deep_merge(&mut body, model);
        }
        for path in &self.mask {
            remove_path(&mut body, path);
        }
        body
    }
}

/// 深合并：对象递归合并，其余类型后者整体覆盖（数组不拼接，避免"越并越长"）。
pub fn deep_merge(base: &mut Value, patch: &Value) {
    match (base, patch) {
        (Value::Object(base), Value::Object(patch)) => {
            for (key, value) in patch {
                match base.get_mut(key) {
                    Some(existing) => deep_merge(existing, value),
                    None => {
                        base.insert(key.clone(), value.clone());
                    }
                }
            }
        }
        (base, patch) => *base = patch.clone(),
    }
}

/// 按点号路径删除。返回是否删掉了东西。
pub fn remove_path(value: &mut Value, path: &str) -> bool {
    let mut parts = path.split('.').filter(|part| !part.is_empty()).peekable();
    let Some(first) = parts.next() else {
        return false;
    };
    if parts.peek().is_none() {
        return match value.as_object_mut() {
            Some(object) => object.remove(first).is_some(),
            None => false,
        };
    }
    match value
        .as_object_mut()
        .and_then(|object| object.get_mut(first))
    {
        Some(child) => remove_path(child, &parts.collect::<Vec<_>>().join(".")),
        None => false,
    }
}

/// 速度档位（D402）：同一档思考强度下，"多快出字"是另一根轴。
///
/// 两家的字段名不一样，所以这里只表达"要哪一档"，写进哪个键由 [`SpeedField`] 决定：
/// OpenAI 系是 `service_tier`（`flex` / `default` / `priority` / `ultrafast`），
/// Anthropic 系是 `speed`（`standard` / `fast`）。
///
/// **`Default` 不是"没设置"**：它明确要求常规容量。真"不设置"是 `None`（`ThinkingSetting`
/// 那一层不发这个键），两者在账单上是同一档，但在"用户有没有表过态"上不是同一件事。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpeedTier {
    /// 更便宜、排队更靠后（OpenAI `flex`，约 0.5×）。
    Flex,
    /// 常规容量（OpenAI `default` / Anthropic `standard`，1.0×）。
    Standard,
    /// 优先排队（OpenAI `priority` 或 `fast`，约 2×）。
    Priority,
    /// 比优先再上一档，按端点单独计价（OpenAI `ultrafast`）。
    Ultrafast,
    /// Anthropic 的快速模式：出字最多快 2.5 倍，价约 2×。
    Fast,
}

impl SpeedTier {
    /// 这一档在 **OpenAI 系** wire 上写什么（`service_tier` 的取值）。
    ///
    /// `Fast` 在这一族里是 `priority` 的同义词（第三方网关文档明确写了两种拼法等价），
    /// 所以折成 `priority`，不发明第五个值。
    pub fn service_tier(self) -> &'static str {
        match self {
            SpeedTier::Flex => "flex",
            SpeedTier::Standard => "default",
            SpeedTier::Priority | SpeedTier::Fast => "priority",
            SpeedTier::Ultrafast => "ultrafast",
        }
    }

    /// 这一档在 **Anthropic 系** wire 上写什么（`speed` 的取值）。
    ///
    /// `Flex` / `Ultrafast` 在 Anthropic 那边没有对应物：返回 `None` 表示**这一档不发**，
    /// 由调用方在界面上就不列它，而不是发一个对方不认识的词。
    pub fn anthropic_speed(self) -> Option<&'static str> {
        match self {
            SpeedTier::Standard => Some("standard"),
            SpeedTier::Priority | SpeedTier::Fast => Some("fast"),
            SpeedTier::Flex | SpeedTier::Ultrafast => None,
        }
    }

    /// 价格倍率（相对标准档）。`None` = 按端点单独计价，没有一个通用倍数。
    pub fn multiplier(self) -> Option<f32> {
        match self {
            SpeedTier::Flex => Some(0.5),
            SpeedTier::Standard => Some(1.0),
            SpeedTier::Priority | SpeedTier::Fast => Some(2.0),
            SpeedTier::Ultrafast => None,
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_lowercase().as_str() {
            "flex" | "cheap" | "slow" => Some(SpeedTier::Flex),
            "default" | "standard" | "auto" | "normal" => Some(SpeedTier::Standard),
            "priority" | "fast_tier" => Some(SpeedTier::Priority),
            "ultrafast" => Some(SpeedTier::Ultrafast),
            "fast" | "turbo" | "highspeed" | "prime" => Some(SpeedTier::Fast),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            SpeedTier::Flex => "flex",
            SpeedTier::Standard => "standard",
            SpeedTier::Priority => "priority",
            SpeedTier::Ultrafast => "ultrafast",
            SpeedTier::Fast => "fast",
        }
    }
}

/// 速度这一档注到哪个字段族。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpeedField {
    /// 不注任何速度字段。
    #[default]
    None,
    /// `service_tier: "flex" | "default" | "priority" | "ultrafast"`
    ServiceTier,
    /// `speed: "standard" | "fast"`
    AnthropicSpeed,
}

/// 一次请求的速度设置：字段族 + 档位。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct SpeedSetting {
    pub field: SpeedField,
    pub tier: Option<SpeedTier>,
}

impl SpeedSetting {
    pub fn disabled() -> Self {
        Self::default()
    }

    pub fn new(field: SpeedField, tier: SpeedTier) -> Self {
        Self {
            field,
            tier: Some(tier),
        }
    }

    /// 把速度设置写进请求体。写不进去（字段族与档位对不上）时**什么都不写**，
    /// 由 `unsupported()` 回答"为什么"，而不是悄悄发一个对方不认识的键。
    pub fn apply(&self, body: &mut Value) -> bool {
        let Some(tier) = self.tier else {
            return false;
        };
        let Some(object) = body.as_object_mut() else {
            return false;
        };
        match self.field {
            SpeedField::None => false,
            SpeedField::ServiceTier => {
                object.insert(
                    "service_tier".into(),
                    Value::String(tier.service_tier().into()),
                );
                true
            }
            SpeedField::AnthropicSpeed => match tier.anthropic_speed() {
                Some(speed) => {
                    object.insert("speed".into(), Value::String(speed.into()));
                    true
                }
                None => false,
            },
        }
    }

    /// 这一档在这个字段族上写不出去时的原因（界面上要能说清"为什么这一档灰着"）。
    pub fn unsupported(&self) -> Option<String> {
        let tier = self.tier?;
        if self.field == SpeedField::AnthropicSpeed && tier.anthropic_speed().is_none() {
            return Some(format!(
                "Anthropic 只有 standard / fast 两档，`{}` 没有对应值",
                tier.as_str()
            ));
        }
        None
    }
}

/// 供应商配置。真实密钥不存在这里——密钥走 vault / 凭据代理（见 notes/secrets.md）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub id: String,
    pub kind: ProviderKind,
    pub base_url: String,
    pub capabilities: ProviderCapabilities,
    #[serde(default)]
    pub overrides: BodyOverrides,
    /// 是否允许平台侧的敏感头部（自定义 header），默认空。
    #[serde(default)]
    pub extra_headers: Vec<(String, String)>,
}

impl ProviderConfig {
    pub fn new(id: impl Into<String>, kind: ProviderKind, base_url: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            kind,
            base_url: base_url.into(),
            capabilities: match kind {
                ProviderKind::OpenAiCompatible => ProviderCapabilities::openai_like(),
                _ => ProviderCapabilities::conservative(),
            },
            overrides: BodyOverrides::default(),
            extra_headers: Vec::new(),
        }
    }

    /// 拼接完整 URL。Gemini 把模型名嵌进路径。
    pub fn chat_url(&self, model: &str) -> String {
        self.chat_url_with(model, false)
    }

    /// 流式端点。
    ///
    /// 只有 Gemini 走不同的路径：`.../models/{model}:streamGenerateContent?alt=sse`。
    /// `alt=sse` 不是可选项——不带它，上游回的是一串 JSON 数组，帧边界和重试语义都不一样。
    pub fn chat_url_stream(&self, model: &str) -> String {
        self.chat_url_with(model, true)
    }

    fn chat_url_with(&self, model: &str, stream: bool) -> String {
        let base = self.base_url.trim_end_matches('/');
        match self.kind {
            ProviderKind::Gemini if stream => {
                format!("{base}/models/{model}:streamGenerateContent?alt=sse")
            }
            _ => format!("{base}{}", self.kind.chat_path().replace("{model}", model)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn merge_order_is_default_provider_model_then_mask() {
        let overrides = BodyOverrides {
            provider: Some(json!({"temperature": 0.9, "reasoning_effort": "low"})),
            model: Some(json!({"temperature": 0.2, "reasoning": {"effort": "high"}})),
            mask: vec!["reasoning.effort".into(), "reasoning_effort".into()],
        };
        let body = overrides.apply(json!({"temperature": 1.0, "stream": true}));

        assert_eq!(body["temperature"], json!(0.2)); // model 覆盖 provider
        assert_eq!(body["stream"], json!(true)); // 默认值保留
        assert!(body.get("reasoning_effort").is_none()); // 顶层被屏蔽
        assert!(body["reasoning"].get("effort").is_none()); // 嵌套被屏蔽
    }

    #[test]
    fn mask_runs_last_so_it_cannot_be_undone() {
        // 屏蔽的是 provider 设的、也被 model 覆盖过的同一个键
        let overrides = BodyOverrides {
            provider: Some(json!({"a": {"b": 1}})),
            model: Some(json!({"a": {"b": 2}})),
            mask: vec!["a.b".into()],
        };
        let body = overrides.apply(json!({"a": {"b": 0, "c": 3}}));
        assert!(body["a"].get("b").is_none());
        assert_eq!(body["a"]["c"], json!(3));
    }

    #[test]
    fn arrays_are_replaced_not_concatenated() {
        let overrides = BodyOverrides {
            provider: Some(json!({"stop": ["a", "b"]})),
            model: Some(json!({"stop": ["c"]})),
            mask: vec![],
        };
        let body = overrides.apply(json!({"stop": ["z"]}));
        assert_eq!(body["stop"], json!(["c"]));
    }

    #[test]
    fn remove_path_handles_missing_and_deep_paths() {
        let mut value = json!({"a": {"b": {"c": 1}}});
        assert!(remove_path(&mut value, "a.b.c"));
        assert!(value["a"]["b"].as_object().unwrap().is_empty());
        assert!(!remove_path(&mut value, "a.zzz.c"));
        assert!(!remove_path(&mut value, ""));
    }

    #[test]
    fn thinking_fields_are_written_per_family() {
        let body = json!({"model": "m"});

        let mut effort_body = body.clone();
        ThinkingSetting::with_field(ThinkingField::OpenAiEffort)
            .effort(ThinkingEffort::High)
            .apply(&mut effort_body);
        assert_eq!(effort_body["reasoning_effort"], json!("high"));

        let mut responses = body.clone();
        ThinkingSetting::with_field(ThinkingField::ResponsesReasoning)
            .effort(ThinkingEffort::Low)
            .budget(2048)
            .apply(&mut responses);
        assert_eq!(responses["reasoning"]["effort"], json!("low"));
        assert_eq!(responses["reasoning"]["max_tokens"], json!(2048));

        let mut anthropic = body.clone();
        ThinkingSetting::with_field(ThinkingField::AnthropicThinking)
            .budget(4096)
            .apply(&mut anthropic);
        assert_eq!(anthropic["thinking"]["type"], json!("enabled"));
        assert_eq!(anthropic["thinking"]["budget_tokens"], json!(4096));

        // 预算为 0 / 缺省时按关闭处理
        let mut off = body.clone();
        ThinkingSetting::with_field(ThinkingField::AnthropicThinking).apply(&mut off);
        assert_eq!(off["thinking"]["type"], json!("disabled"));

        let mut gemini = body.clone();
        ThinkingSetting::with_field(ThinkingField::GeminiThinkingConfig)
            .budget(1024)
            .apply(&mut gemini);
        assert_eq!(
            gemini["generationConfig"]["thinkingConfig"]["thinkingBudget"],
            json!(1024)
        );

        let mut none = body.clone();
        ThinkingSetting::disabled().apply(&mut none);
        assert_eq!(none, body);
    }

    #[test]
    fn provider_defaults_follow_kind() {
        // base_url 约定自带版本段（各家 /v1 与 /v1beta 不同），chat_path 只给后缀
        let openai = ProviderConfig::new(
            "p1",
            ProviderKind::OpenAiCompatible,
            "https://api.example/v1",
        );
        assert_eq!(
            openai.kind.default_thinking_field(),
            ThinkingField::OpenAiEffort
        );
        assert!(openai.capabilities.streaming_usage);
        assert_eq!(
            openai.chat_url("m"),
            "https://api.example/v1/chat/completions"
        );

        let responses = ProviderConfig::new(
            "p2",
            ProviderKind::OpenAiResponses,
            "https://api.example/v1",
        );
        assert_eq!(responses.kind.max_tokens_field(), "max_output_tokens");
        assert!(!responses.capabilities.assistant_prefill); // 保守默认，由模型元数据覆盖
        assert_eq!(responses.chat_url("m"), "https://api.example/v1/responses");

        assert_eq!(
            ProviderConfig::new(
                "p3",
                ProviderKind::Anthropic,
                "https://api.anthropic.com/v1"
            )
            .chat_url("claude-opus-5"),
            "https://api.anthropic.com/v1/messages"
        );
        assert_eq!(
            ProviderConfig::new(
                "p4",
                ProviderKind::Gemini,
                "https://generativelanguage.googleapis.com/v1beta"
            )
            .chat_url("gemini-3-pro"),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro:generateContent"
        );
        // 从文档里复制 base_url 常带末尾斜杠，不该拼出双斜杠
        assert_eq!(
            ProviderConfig::new(
                "p5",
                ProviderKind::OpenAiCompatible,
                "https://api.openai.com/v1/"
            )
            .chat_url("m"),
            "https://api.openai.com/v1/chat/completions"
        );
    }
    /// `ultra` 是 gpt-5.6 / gpt-6 家族才有的顶档：它得能被解析、能排到 max 之后。
    #[test]
    fn ultra_sits_above_max() {
        assert_eq!(ThinkingEffort::parse("ultra"), Some(ThinkingEffort::Ultra));
        assert_eq!(ThinkingEffort::Ultra.as_str(), "ultra");
        assert!(ThinkingEffort::Ultra.rank() > ThinkingEffort::Max.rank());
        // 排序要把它排在最后，否则滑块上会插在中间
        let sorted = ThinkingEffort::sorted(vec![
            ThinkingEffort::Ultra,
            ThinkingEffort::Low,
            ThinkingEffort::Max,
        ]);
        assert_eq!(
            sorted,
            vec![
                ThinkingEffort::Low,
                ThinkingEffort::Max,
                ThinkingEffort::Ultra
            ]
        );
    }

    /// 速度档：两家 wire 名不同，写不出去时要能说清原因。
    #[test]
    fn speed_tiers_write_the_right_field() {
        // OpenAI 系：service_tier，`fast` 折成 `priority`（网关文档说两种拼法等价）
        let mut body = json!({"model": "m"});
        assert!(SpeedSetting::new(SpeedField::ServiceTier, SpeedTier::Flex).apply(&mut body));
        assert_eq!(body["service_tier"], json!("flex"));

        let mut body = json!({"model": "m"});
        SpeedSetting::new(SpeedField::ServiceTier, SpeedTier::Fast).apply(&mut body);
        assert_eq!(body["service_tier"], json!("priority"));

        // Anthropic 系：speed，只有 standard / fast
        let mut body = json!({"model": "m"});
        assert!(SpeedSetting::new(SpeedField::AnthropicSpeed, SpeedTier::Fast).apply(&mut body));
        assert_eq!(body["speed"], json!("fast"));

        let mut body = json!({"model": "m"});
        SpeedSetting::new(SpeedField::AnthropicSpeed, SpeedTier::Standard).apply(&mut body);
        assert_eq!(body["speed"], json!("standard"));
    }

    /// Anthropic 没有 flex / ultrafast：不发，并且 `unsupported` 要给出原因。
    #[test]
    fn anthropic_has_no_flex_or_ultrafast() {
        for tier in [SpeedTier::Flex, SpeedTier::Ultrafast] {
            let setting = SpeedSetting::new(SpeedField::AnthropicSpeed, tier);
            let mut body = json!({"model": "m"});
            assert!(!setting.apply(&mut body), "{tier:?} 不该写进去");
            assert!(body.get("speed").is_none(), "{tier:?} 不该留字段");
            assert!(setting.unsupported().is_some(), "{tier:?} 要说清原因");
        }
        // 标准档没有 unsupported
        assert!(
            SpeedSetting::new(SpeedField::AnthropicSpeed, SpeedTier::Standard)
                .unsupported()
                .is_none()
        );
    }

    /// 倍率：ultrafast 按端点计价，没有通用倍数。
    #[test]
    fn speed_multipliers_are_known_where_they_exist() {
        assert_eq!(SpeedTier::Flex.multiplier(), Some(0.5));
        assert_eq!(SpeedTier::Standard.multiplier(), Some(1.0));
        assert_eq!(SpeedTier::Priority.multiplier(), Some(2.0));
        assert_eq!(SpeedTier::Fast.multiplier(), Some(2.0));
        assert_eq!(SpeedTier::Ultrafast.multiplier(), None);
    }

    /// 没设速度时一个字段都不写（老行为不变）。
    #[test]
    fn no_speed_setting_writes_nothing() {
        let mut body = json!({"model": "m"});
        assert!(!SpeedSetting::disabled().apply(&mut body));
        assert_eq!(body, json!({"model": "m"}));
    }
}
