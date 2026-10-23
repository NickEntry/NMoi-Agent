//! 五类模型角色（P3）。
//!
//! 标题总结 / 压缩 / 翻译 / 视觉 / 聊天建议——它们共用一条"小请求"通道：
//! 非流式、一次性、**不进会话上下文、不进预算**，失败就静默降级。
//!
//! 三条设计口径（D42）：
//!
//! 1. **不并进主预算**：`RoleMeter` 是独立账本。主对话的 `Budget` 只算会话自己的 token，
//!    否则"起了个标题"会让上下文提前触发压缩——荒唐但很容易发生。
//! 2. **失败不外抛**：`run` 只返回 [`RoleOutcome`]，永远不 `Err`。
//!    聊天建议拿不到是小事，主对话不该因为收尾工作挂掉。
//! 3. **能不能用要说清楚**：视觉角色没指定模型就是不能用（`docs/PLAN.md` §3.3 的"置灰"），
//!    但"不能用"必须带人话原因，不能只是没反应。
//!
//! 没做：图片通道（P3.1）。视觉角色现在只有门控与请求形状，发不出图——
//! 那需要 `SourceTag::VisionBridge`、四种端口的图片块、工作区的二进制读。

use std::collections::BTreeMap;

use crate::budget::Usage;
use crate::config::{default_thinking_field, ModelFlavor, ThinkingChoice, ThinkingContext};
use crate::engine::now_ms;
use crate::message::Role;
use crate::provider::{ProviderConfig, ThinkingEffort};
use crate::runtime::{ApiKey, ChatClient, ChatRequest};
use crate::stream::Completion;
use crate::transport::Transport;

/// 五类角色。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ModelRole {
    /// 会话标题（首次生成结束后）。
    TitleSummary,
    /// 压缩（自动安全阀或手动）。
    Compression,
    /// 消息翻译（按钮触发）。
    Translation,
    /// 视觉替换（图片 → 文字描述）。
    Vision,
    /// 聊天建议（每轮结束后）。
    ChatSuggestion,
}

impl ModelRole {
    pub const ALL: [ModelRole; 5] = [
        ModelRole::TitleSummary,
        ModelRole::Compression,
        ModelRole::Translation,
        ModelRole::Vision,
        ModelRole::ChatSuggestion,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            ModelRole::TitleSummary => "title_summary",
            ModelRole::Compression => "compression",
            ModelRole::Translation => "translation",
            ModelRole::Vision => "vision",
            ModelRole::ChatSuggestion => "chat_suggestion",
        }
    }

    /// 给界面看的名字（中文）。日志里仍用 `as_str`。
    pub fn label(self) -> &'static str {
        match self {
            ModelRole::TitleSummary => "标题总结",
            ModelRole::Compression => "压缩",
            ModelRole::Translation => "翻译",
            ModelRole::Vision => "视觉替换",
            ModelRole::ChatSuggestion => "聊天建议",
        }
    }

    /// 能不能在界面上关掉。五个都能关，这里留着是为了以后有例外时不必改调用方。
    pub fn toggleable(self) -> bool {
        true
    }
}

/// 一个角色的配置。
///
/// `follow_chat` 与 `provider`/`model` 是互斥的两条路：跟随当前对话，或者自己指定一个。
/// 同时给两样时以 `follow_chat` 为准（界面也不会让你同时填）。
#[derive(Debug, Clone, PartialEq)]
pub struct RoleConfig {
    pub role: ModelRole,
    pub enabled: bool,
    /// 用当前聊天模型（provider 与 model 都跟随主对话）。
    pub follow_chat: bool,
    /// provider 的 id（不是 URL）。`follow_chat` 为真时忽略。
    pub provider: Option<String>,
    pub model: Option<String>,
    /// 思考设置。视觉/标题这类角色默认**显式关闭**（D37：关闭必须显式）。
    pub thinking: ThinkingChoice,
    pub max_tokens: Option<u32>,
    pub temperature: Option<f32>,
    /// 自定义提示词；`None` 用内置模板。
    pub prompt: Option<String>,
}

impl RoleConfig {
    /// 按 `docs/PLAN.md` §3.3 的表给默认值。
    pub fn new(role: ModelRole) -> Self {
        let (enabled, follow_chat, thinking, max_tokens, temperature) = match role {
            // 便宜小模型；没配就跟着当前模型。标题短、且**不该让它思考**。
            ModelRole::TitleSummary => (true, true, ThinkingChoice::Off, 1024, Some(0.3)),
            // 压缩默认用当前聊天模型：理解上下文需要同等能力。
            ModelRole::Compression => (true, true, ThinkingChoice::Auto, 8192, None),
            // 翻译要忠实，温度低。
            ModelRole::Translation => (true, true, ThinkingChoice::Off, 4096, Some(0.2)),
            // 视觉必须显式指定：没指定 = 功能置灰（plan §3.3）。
            ModelRole::Vision => (false, false, ThinkingChoice::Off, 1024, None),
            // 每轮都跑，默认关更省。
            ModelRole::ChatSuggestion => (false, true, ThinkingChoice::Off, 1024, Some(0.7)),
        };
        Self {
            role,
            enabled,
            follow_chat,
            provider: None,
            model: None,
            thinking,
            max_tokens: Some(max_tokens),
            temperature,
            prompt: None,
        }
    }

    /// 配不全时给出人话原因。`None` = 配置完整。
    pub fn missing_config(&self) -> Option<&'static str> {
        if self.follow_chat {
            return None;
        }
        if self
            .model
            .as_deref()
            .map(str::trim)
            .unwrap_or("")
            .is_empty()
        {
            return Some("没指定模型：视觉这类角色不跟随当前对话，得自己配一个");
        }
        None
    }

    /// 实际能不能用。`usable = false` 时 `reason` 一定有值。
    pub fn status(&self) -> RoleStatus {
        if !self.enabled {
            return RoleStatus {
                usable: false,
                reason: Some("已禁用"),
            };
        }
        match self.missing_config() {
            Some(reason) => RoleStatus {
                usable: false,
                reason: Some(reason),
            },
            None => RoleStatus {
                usable: true,
                reason: None,
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RoleStatus {
    pub usable: bool,
    pub reason: Option<&'static str>,
}

/// 五个角色的一套配置。
#[derive(Debug, Clone, PartialEq)]
pub struct RoleSet {
    configs: BTreeMap<ModelRole, RoleConfig>,
}

impl Default for RoleSet {
    fn default() -> Self {
        Self::new()
    }
}

impl RoleSet {
    pub fn new() -> Self {
        Self {
            configs: ModelRole::ALL
                .into_iter()
                .map(|role| (role, RoleConfig::new(role)))
                .collect(),
        }
    }

    pub fn get(&self, role: ModelRole) -> &RoleConfig {
        &self.configs[&role]
    }

    pub fn get_mut(&mut self, role: ModelRole) -> &mut RoleConfig {
        self.configs
            .entry(role)
            .or_insert_with(|| RoleConfig::new(role))
    }

    pub fn set(&mut self, config: RoleConfig) {
        self.configs.insert(config.role, config);
    }

    /// 按固定顺序遍历（界面渲染顺序）。
    pub fn iter(&self) -> impl Iterator<Item = &RoleConfig> {
        ModelRole::ALL
            .into_iter()
            .filter_map(|role| self.configs.get(&role))
    }

    pub fn status(&self, role: ModelRole) -> RoleStatus {
        self.get(role).status()
    }
}

/// 一次角色请求要用哪套提示词。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RolePrompt {
    pub system: String,
    pub user: String,
}

impl RolePrompt {
    pub fn custom(system: impl Into<String>, user: impl Into<String>) -> Self {
        Self {
            system: system.into(),
            user: user.into(),
        }
    }

    /// 起标题。给的是**转写**（已经拼好的对话片段），不是整个会话。
    pub fn title(transcript: &str) -> Self {
        Self::custom(
            "你在给一段对话起标题。只输出标题本身：一行、不超过 20 个字、不要引号、\
             不要句末标点、不要任何解释。",
            format!("对话片段：\n{transcript}\n\n标题："),
        )
    }

    /// 翻译一条消息。结果只给界面看，不进上下文。
    pub fn translation(text: &str, target_language: &str) -> Self {
        Self::custom(
            format!(
                "把下面这段内容翻译成{target_language}。只输出译文，保留原有的代码块、\
                 变量名、路径与数字，不要解释、不要总结。"
            ),
            text.to_string(),
        )
    }

    /// 聊天建议：三条短句，供输入框上一排按钮用。
    pub fn suggestion(transcript: &str) -> Self {
        Self::custom(
            "根据这段对话，给出三条用户接下来可能想说的话。每条不超过 15 个字，\
             单独一行，不要编号、不要解释。",
            format!("对话片段：\n{transcript}"),
        )
    }
}

/// 把模型吐出来的东西收拾成能当标题的样子。
///
/// 模型很爱回 `"标题：xxx。"`、`**xxx**`、或者干脆两行。这里只做确定性的清理，
/// 不做"语义上更好"的改写——那种事一旦做，用户就没法预测标题长什么样了。
pub fn sanitize_title(raw: &str) -> String {
    const MAX_CHARS: usize = 24;

    let line = raw
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or_default();

    // 去掉 markdown 前缀（`#`、`*`、`-`、`>`）
    let mut text = line.trim_start_matches(['#', '*', '-', '>', ' ']).trim();

    for prefix in ["标题：", "标题:", "Title:", "title:", "标题 "] {
        if let Some(rest) = text.strip_prefix(prefix) {
            text = rest.trim();
            break;
        }
    }

    let text = text.trim_matches(|character: char| {
        matches!(
            character,
            '"' | '\''
                | '「'
                | '」'
                | '『'
                | '』'
                | '《'
                | '》'
                | '“'
                | '”'
                | '【'
                | '】'
                | '（'
                | '）'
                | '。'
                | '！'
                | '？'
                | '!'
                | '?'
                | '.'
                | '，'
                | '、'
        )
    });

    let collapsed = text.split_whitespace().collect::<Vec<_>>().join(" ");
    truncate_chars(&collapsed, MAX_CHARS)
}

fn truncate_chars(text: &str, limit: usize) -> String {
    if text.chars().count() <= limit {
        return text.to_string();
    }
    text.chars().take(limit).collect()
}

/// 一次角色调用的全部输入。
pub struct RoleCall<'a> {
    pub config: &'a RoleConfig,
    pub provider: &'a ProviderConfig,
    pub model: &'a str,
    pub api_key: Option<&'a ApiKey>,
    pub prompt: RolePrompt,
    /// 模型支持的思考档位（来自模型元数据）。空 = 不知道，`关闭` 会被标成"尽力而为"。
    pub supported_efforts: &'a [ThinkingEffort],
}

impl<'a> RoleCall<'a> {
    pub fn new(
        config: &'a RoleConfig,
        provider: &'a ProviderConfig,
        model: &'a str,
        prompt: RolePrompt,
    ) -> Self {
        Self {
            config,
            provider,
            model,
            api_key: None,
            prompt,
            supported_efforts: &[],
        }
    }

    pub fn with_api_key(mut self, key: &'a ApiKey) -> Self {
        self.api_key = Some(key);
        self
    }
}

/// 一次调用的结果。**没有 `Err`**：失败也是这个结构的一部分（D42 第 2 条）。
#[derive(Debug, Clone, PartialEq)]
pub enum RoleOutcome {
    Ok(Box<Completion>),
    /// 没发请求，或者发出去没拿到能用的东西——都算跳过，不算错。
    Skipped(String),
    /// 真出错了（网络、上游拒绝）。只记日志，不影响主流程。
    Failed(String),
}

impl RoleOutcome {
    pub fn is_ok(&self) -> bool {
        matches!(self, RoleOutcome::Ok(_))
    }

    pub fn text(&self) -> Option<&str> {
        match self {
            RoleOutcome::Ok(completion) => Some(&completion.text),
            _ => None,
        }
    }

    pub fn describe(&self) -> String {
        match self {
            RoleOutcome::Ok(completion) => {
                format!("ok（{} 字）", completion.text.chars().count())
            }
            RoleOutcome::Skipped(reason) => format!("跳过：{reason}"),
            RoleOutcome::Failed(reason) => format!("失败：{reason}"),
        }
    }
}

/// 跑一次角色请求。
pub struct RoleRun {
    pub role: ModelRole,
    pub model: String,
    pub outcome: RoleOutcome,
    pub usage: Usage,
}

/// 发请求。任何失败都收敛成 [`RoleOutcome`]，不会把 `Err` 递给调用方。
pub fn run<T: Transport>(client: &ChatClient<T>, call: RoleCall<'_>) -> RoleRun {
    let role = call.config.role;
    let mut run = RoleRun {
        role,
        model: call.model.to_string(),
        outcome: RoleOutcome::Skipped("没有发起请求".into()),
        usage: Usage::default(),
    };

    let status = call.config.status();
    if !status.usable {
        run.outcome = RoleOutcome::Skipped(status.reason.unwrap_or("不可用").to_string());
        return run;
    }

    let prompt = match &call.config.prompt {
        // 自定义提示词替换的是 system 部分；user 部分仍是从会话里取来的材料
        Some(system) => RolePrompt {
            system: system.clone(),
            user: call.prompt.user.clone(),
        },
        None => call.prompt.clone(),
    };

    let mut request = ChatRequest::from_turns(
        call.provider.clone(),
        call.model,
        &[
            (Role::System, prompt.system.as_str()),
            (Role::User, prompt.user.as_str()),
        ],
    )
    .without_stream();
    request.max_tokens = call.config.max_tokens;
    request.temperature = call.config.temperature;
    if let Some(key) = call.api_key {
        request.api_key = Some(key.clone());
    }

    // 思考字段按 provider + 模型族决定，再按角色的选择解析（D37 的"关闭必须显式"）
    let field = default_thinking_field(&ThinkingContext::new(
        call.provider.kind,
        ModelFlavor::of(call.model),
    ));
    request.thinking = call
        .config
        .thinking
        .resolve(field, call.supported_efforts)
        .setting;

    match client.complete(&request) {
        Ok(completion) => {
            run.usage = completion.usage;
            if completion.text.trim().is_empty() {
                // 实测：思考能吃掉一万多 token，max_tokens 给小了就是"思考吃完、正文为空"。
                // 这种情况按跳过处理——标题保留原值就好，不该当成错误弹给用户。
                run.outcome =
                    RoleOutcome::Skipped("模型没产出正文（多半是思考把 max_tokens 吃光了）".into());
            } else {
                run.outcome = RoleOutcome::Ok(Box::new(completion));
            }
        }
        Err(error) => {
            run.outcome = RoleOutcome::Failed(error.to_string());
        }
    }

    run
}

/// 一条角色用量记录。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RoleUsage {
    pub role: ModelRole,
    pub model: String,
    pub usage: Usage,
    pub ok: bool,
    pub at_ms: i64,
}

/// 角色的独立账本。
///
/// **不进 `Budget`**：主对话的预算只算会话自己的 token。把标题、翻译的开销并进去，
/// 会让上下文提前触发压缩——那是个很难复现、也很难解释的 bug。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RoleMeter {
    entries: Vec<RoleUsage>,
}

impl RoleMeter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record(&mut self, usage: RoleUsage) {
        self.entries.push(usage);
    }

    pub fn record_run(&mut self, run: &RoleRun) {
        self.record(RoleUsage {
            role: run.role,
            model: run.model.clone(),
            usage: run.usage,
            ok: run.outcome.is_ok(),
            at_ms: now_ms(),
        });
    }

    pub fn entries(&self) -> &[RoleUsage] {
        &self.entries
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn total(&self) -> Usage {
        self.entries
            .iter()
            .fold(Usage::default(), |mut sum, entry| {
                sum.prompt_tokens = sum.prompt_tokens.saturating_add(entry.usage.prompt_tokens);
                sum.completion_tokens = sum
                    .completion_tokens
                    .saturating_add(entry.usage.completion_tokens);
                sum.cached_tokens = sum.cached_tokens.saturating_add(entry.usage.cached_tokens);
                sum.reasoning_tokens = sum
                    .reasoning_tokens
                    .saturating_add(entry.usage.reasoning_tokens);
                sum
            })
    }

    pub fn per_role(&self, role: ModelRole) -> Usage {
        self.entries.iter().filter(|entry| entry.role == role).fold(
            Usage::default(),
            |mut sum, entry| {
                sum.prompt_tokens = sum.prompt_tokens.saturating_add(entry.usage.prompt_tokens);
                sum.completion_tokens = sum
                    .completion_tokens
                    .saturating_add(entry.usage.completion_tokens);
                sum.cached_tokens = sum.cached_tokens.saturating_add(entry.usage.cached_tokens);
                sum.reasoning_tokens = sum
                    .reasoning_tokens
                    .saturating_add(entry.usage.reasoning_tokens);
                sum
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::provider::{ProviderCapabilities, ProviderKind};
    use crate::runtime::ReconnectPolicy;
    use crate::transport::{HttpRequest, HttpResponse, TransportError};
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::sync::Mutex;

    struct MockTransport {
        responses: Mutex<VecDeque<Result<HttpResponse, TransportError>>>,
        seen: Mutex<Vec<HttpRequest>>,
    }

    impl MockTransport {
        fn new(responses: Vec<Result<HttpResponse, TransportError>>) -> Self {
            Self {
                responses: Mutex::new(responses.into()),
                seen: Mutex::new(Vec::new()),
            }
        }

        fn request_count(&self) -> usize {
            self.seen.lock().unwrap().len()
        }

        fn last_body(&self) -> serde_json::Value {
            let request = self.seen.lock().unwrap().last().cloned().expect("没发请求");
            serde_json::from_slice(&request.body).unwrap()
        }
    }

    impl Transport for MockTransport {
        fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
            self.seen.lock().unwrap().push(request);
            self.responses
                .lock()
                .unwrap()
                .pop_front()
                .unwrap_or_else(|| Err(TransportError::Io("预置响应已用尽".into())))
        }
    }

    fn json_response(raw: &str) -> HttpResponse {
        HttpResponse {
            status: 200,
            headers: vec![("content-type".into(), "application/json".into())],
            body: Box::new(Cursor::new(raw.as_bytes().to_vec())),
        }
    }

    fn provider() -> ProviderConfig {
        let mut provider = ProviderConfig::new(
            "local",
            ProviderKind::OpenAiCompatible,
            "http://127.0.0.1:8790/v1",
        );
        provider.capabilities = ProviderCapabilities::openai_like();
        provider
    }

    fn completion_body(text: &str) -> String {
        format!(
            r#"{{"id":"c1","model":"m","choices":[{{"index":0,"message":{{"role":"assistant","content":"{text}"}},"finish_reason":"stop"}}],"usage":{{"prompt_tokens":10,"completion_tokens":5}}}}"#
        )
    }

    #[test]
    fn defaults_match_the_plan_table() {
        let set = RoleSet::new();
        // 标题/压缩/翻译默认开，视觉/建议默认关
        assert!(set.get(ModelRole::TitleSummary).enabled);
        assert!(set.get(ModelRole::Compression).enabled);
        assert!(set.get(ModelRole::Translation).enabled);
        assert!(!set.get(ModelRole::Vision).enabled);
        assert!(!set.get(ModelRole::ChatSuggestion).enabled);

        // 压缩默认跟随当前聊天模型，且不强制关思考
        let compression = set.get(ModelRole::Compression);
        assert!(compression.follow_chat);
        assert_eq!(compression.thinking, ThinkingChoice::Auto);

        // 其余角色默认显式关思考（D37：关闭必须显式）
        for role in [
            ModelRole::TitleSummary,
            ModelRole::Translation,
            ModelRole::Vision,
            ModelRole::ChatSuggestion,
        ] {
            assert_eq!(
                set.get(role).thinking,
                ThinkingChoice::Off,
                "{role:?} 默认该关思考"
            );
        }
    }

    #[test]
    fn vision_without_a_model_is_greyed_out_with_a_reason() {
        let mut set = RoleSet::new();
        // 默认：关 + 不跟随 → 先开开关，仍然不可用
        set.get_mut(ModelRole::Vision).enabled = true;
        let status = set.status(ModelRole::Vision);
        assert!(!status.usable, "没指定模型的视觉角色不能算可用");
        assert!(status.reason.unwrap().contains("没指定模型"));

        // 指定了模型才可用
        set.get_mut(ModelRole::Vision).model = Some("qwen-image-3.0-pro".into());
        assert!(set.status(ModelRole::Vision).usable);
    }

    #[test]
    fn disabled_role_sends_nothing() {
        let transport = MockTransport::new(vec![]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let mut config = RoleConfig::new(ModelRole::ChatSuggestion);
        config.enabled = false;

        let run = super::run(
            &client,
            RoleCall::new(&config, &provider(), "m", RolePrompt::suggestion("u: 你好")),
        );
        assert!(matches!(run.outcome, RoleOutcome::Skipped(_)));
        // 关键：一个请求都不该发出去
        assert_eq!(client.transport().request_count(), 0);
        assert_eq!(run.usage, Usage::default());
    }

    #[test]
    fn ok_path_builds_a_non_streaming_request_with_the_role_settings() {
        let transport = MockTransport::new(vec![Ok(json_response(&completion_body("喵言喵语")))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let config = RoleConfig::new(ModelRole::TitleSummary);

        let run = super::run(
            &client,
            RoleCall::new(
                &config,
                &provider(),
                "cheap-model",
                RolePrompt::title("u: 你好\nassistant: 喵"),
            ),
        );

        assert!(run.outcome.is_ok(), "{}", run.outcome.describe());
        assert_eq!(run.outcome.text(), Some("喵言喵语"));
        assert_eq!(run.usage.prompt_tokens, 10);

        let body = client.transport().last_body();
        assert_eq!(body["stream"], serde_json::json!(false), "角色请求是非流式");
        assert_eq!(body["max_tokens"], serde_json::json!(1024));
        assert_eq!(body["model"], serde_json::json!("cheap-model"));
        assert_eq!(body["messages"][0]["role"], serde_json::json!("system"));
        assert!(body["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("标题"));
        // 默认显式关思考
        assert_eq!(body["reasoning_effort"], serde_json::json!("none"));
    }

    #[test]
    fn custom_prompt_replaces_only_the_system_part() {
        let transport = MockTransport::new(vec![Ok(json_response(&completion_body("ok")))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let mut config = RoleConfig::new(ModelRole::Translation);
        config.prompt = Some("你是我的专属翻译官".into());

        super::run(
            &client,
            RoleCall::new(
                &config,
                &provider(),
                "m",
                RolePrompt::translation("hello", "中文"),
            ),
        );

        let body = client.transport().last_body();
        assert_eq!(
            body["messages"][0]["content"],
            serde_json::json!("你是我的专属翻译官")
        );
        // 材料还在：自定义的是 system，不是把 user 也换掉
        assert_eq!(body["messages"][1]["content"], serde_json::json!("hello"));
    }

    #[test]
    fn empty_text_is_a_skip_not_a_failure() {
        // 实测：思考能把 max_tokens 吃光，正文回空串。标题保留原值就好，不该弹错。
        let transport = MockTransport::new(vec![Ok(json_response(&completion_body("")))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let config = RoleConfig::new(ModelRole::TitleSummary);

        let run = super::run(
            &client,
            RoleCall::new(&config, &provider(), "m", RolePrompt::title("u: hi")),
        );
        assert!(matches!(run.outcome, RoleOutcome::Skipped(_)));
        assert!(!run.outcome.is_ok());
        assert!(run.outcome.describe().contains("思考"));
    }

    #[test]
    fn upstream_failure_is_reported_but_never_unwinds() {
        let transport = MockTransport::new(vec![Ok(HttpResponse {
            status: 500,
            headers: vec![("content-type".into(), "application/json".into())],
            body: Box::new(Cursor::new(b"{\"error\":{\"message\":\"boom\"}}".to_vec())),
        })]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        // 聊天建议默认是关的（省成本），这里要显式打开才轮得到"失败"这条路
        let mut config = RoleConfig::new(ModelRole::ChatSuggestion);
        config.enabled = true;

        let run = super::run(
            &client,
            RoleCall::new(&config, &provider(), "m", RolePrompt::suggestion("u: hi")),
        );
        assert!(
            matches!(run.outcome, RoleOutcome::Failed(_)),
            "{}",
            run.outcome.describe()
        );
        assert!(run.outcome.describe().contains("失败"));
    }

    #[test]
    fn meter_is_a_separate_ledger() {
        let mut meter = RoleMeter::new();
        meter.record(RoleUsage {
            role: ModelRole::TitleSummary,
            model: "cheap".into(),
            usage: Usage {
                prompt_tokens: 10,
                completion_tokens: 5,
                cached_tokens: 2,
                reasoning_tokens: 0,
            },
            ok: true,
            at_ms: 1,
        });
        meter.record(RoleUsage {
            role: ModelRole::TitleSummary,
            model: "cheap".into(),
            usage: Usage {
                prompt_tokens: 20,
                completion_tokens: 8,
                cached_tokens: 0,
                reasoning_tokens: 0,
            },
            ok: true,
            at_ms: 2,
        });
        meter.record(RoleUsage {
            role: ModelRole::Translation,
            model: "cheap".into(),
            usage: Usage {
                prompt_tokens: 100,
                completion_tokens: 50,
                cached_tokens: 0,
                reasoning_tokens: 0,
            },
            ok: false,
            at_ms: 3,
        });

        assert_eq!(meter.len(), 3);
        assert_eq!(meter.per_role(ModelRole::TitleSummary).prompt_tokens, 30);
        assert_eq!(meter.per_role(ModelRole::Translation).prompt_tokens, 100);
        // 失败的调用也照样记账（花了就是花了）
        assert_eq!(meter.total().prompt_tokens, 130);
        assert_eq!(meter.total().completion_tokens, 63);
        // 空账本不报错
        assert_eq!(RoleMeter::new().total(), Usage::default());
    }

    #[test]
    fn sanitize_title_strips_noise_and_caps_length() {
        assert_eq!(sanitize_title("标题：Rust 与猫"), "Rust 与猫");
        assert_eq!(sanitize_title("「聊聊流式解析」"), "聊聊流式解析");
        assert_eq!(
            sanitize_title("   \n  第一行才对\n第二行扔掉  "),
            "第一行才对"
        );
        assert_eq!(sanitize_title("## 有点长但还好"), "有点长但还好");
        assert_eq!(
            sanitize_title("为什么流式解析总在帧边界上出问题。"),
            "为什么流式解析总在帧边界上出问题"
        );
        // 超过 24 个字符要截断（不是按字节）
        let long = "一".repeat(40);
        assert_eq!(sanitize_title(&long).chars().count(), 24);
        assert_eq!(sanitize_title(""), "");
    }

    #[test]
    fn builtin_prompts_say_what_they_want() {
        let title = RolePrompt::title("u: 你好\nassistant: 喵");
        assert!(title.system.contains("只输出标题"));
        assert!(title.user.contains('喵'));

        let translation = RolePrompt::translation("hello", "中文");
        assert!(translation.system.contains("中文"));
        assert_eq!(translation.user, "hello");

        let suggestion = RolePrompt::suggestion("u: 帮我看看这段代码");
        assert!(suggestion.system.contains("三条"));
        assert!(suggestion.user.contains("代码"));
    }
}
