//! Provider 运行时（P2）：真正把一次对话发出去，并把回复流翻译成事件。
//!
//! 三块拼起来：请求构造（[`build_body`]）→ 传输（[`crate::transport`]）→ 流式解析
//! （[`crate::sse`] + [`crate::stream`]）。UI 只看到 [`StreamEvent`]。
//!
//! ## 三条硬约束（都是踩过的）
//!
//! 1. **不要相信上游的窗口和错误码。** 实测：标称 131072 的别名接受了 949,485 token，
//!    而三种不同的溢出失败返回同一个 HTTP 400 + `upstream_rejected`。
//!    所以这里只给"疑似超窗"的**线索**（[`OverflowHint`]），真正的预算由客户端自己维护。
//! 2. **断流不能当成正常结束。** 没等到终止事件的流会补一条 [`StreamEvent::Interrupted`]，
//!    上层据此把消息标 `incomplete`（D21）。
//! 3. **密钥不进 Debug。** [`ApiKey`] 的 `Debug`/`Display` 都是打码的，
//!    [`ChatRequest`] 的 `Debug` 也只印头部名字不印值——日志里漏 key 是低级事故。
//!
//! ## 本轮的范围
//!
//! - 只做**流式**。`stream: false` 会明确报 [`ChatError::Unsupported`]，不假装支持。
//! - 消息只支持纯文本 + 思考回放。带工具调用 / 工具结果 / 归档指针的消息会**报错**，
//!   不静默丢弃——静默丢工具块会把一轮工具循环改写成另一种对话。

use std::collections::{HashMap, VecDeque};
use std::fmt;
use std::thread::sleep;
use std::time::Duration;

use serde_json::{json, Value};

use crate::agent::Cancellation;
use crate::budget::Usage;
use crate::message::{Message, Role};
use crate::provider::{ProviderConfig, ProviderKind, ThinkingSetting};
use crate::retry::{wait, Attempt, Decision, Entropy, GiveUpReason, RetryPolicy, WaitOutcome};
use crate::sse::{SseError, SseReader};
use crate::stream::{Completion, StreamEvent, StreamParseError, StreamParser};
use crate::transport::{HttpRequest, HttpResponse, ResponseBody, Transport, TransportError};

/// Anthropic 的 `max_tokens` 是必填字段。没给的时候用它兜底。
///
/// 16k 是权衡：实测思考能吃掉一万多 token，给太小会"思考吃完、正文为空"
/// （`finish_reason: length`，见 `docs/design/context-experiments-report.md` §1.6）。
/// 这不是"推荐值"，是"别把回复饿死"的下限，UI 应当按模型覆盖它。
pub const FALLBACK_MAX_TOKENS: u32 = 16_384;

/// 默认的单次读超时（SSE 两帧之间的静默上限）。
const DEFAULT_READ_TIMEOUT: Duration = Duration::from_secs(120);

/// API 密钥。**故意不给 `Debug`/`Display` 印明文**。
#[derive(Clone, PartialEq, Eq)]
pub struct ApiKey(String);

impl ApiKey {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl fmt::Debug for ApiKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ApiKey(***)")
    }
}

impl fmt::Display for ApiKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("***")
    }
}

/// 思考内容回放策略（D20 的三态）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ReasoningReplay {
    /// 按 provider 能力位决定：要求回放的就必须回放，否则丢弃。
    #[default]
    Auto,
    Always,
    Never,
}

/// 一个可调用的工具（不绑定任何端口形状）。
///
/// `parameters` 用 JSON Schema 写；到各端口时按各家形状装进容器。
/// Gemini 要求的是 OpenAPI 子集（`type` 要大写），这一支还没做转换，见 D41。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolSpec {
    pub name: String,
    pub description: Option<String>,
    pub parameters: Value,
}

impl ToolSpec {
    pub fn new(name: impl Into<String>, parameters: Value) -> Self {
        Self {
            name: name.into(),
            description: None,
            parameters,
        }
    }

    pub fn describe(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }
}

/// 一次对话请求。
#[derive(Clone)]
pub struct ChatRequest {
    pub provider: ProviderConfig,
    pub model: String,
    pub messages: Vec<Message>,
    pub thinking: ThinkingSetting,
    /// 期望的回复形态。`chat()` 只吃 `true`，`complete()` 只吃 `false`——
    /// 模式由方法决定，不由这个字段偷偷决定。
    pub stream: bool,
    pub max_tokens: Option<u32>,
    pub temperature: Option<f32>,
    pub top_p: Option<f32>,
    pub stop: Vec<String>,
    pub tools: Vec<ToolSpec>,
    pub reasoning_replay: ReasoningReplay,
    pub api_key: Option<ApiKey>,
    /// 单次读超时。SSE 两帧之间的静默上限，不是整轮上限。
    pub read_timeout: Option<Duration>,
}

impl ChatRequest {
    pub fn new(provider: ProviderConfig, model: impl Into<String>) -> Self {
        Self {
            provider,
            model: model.into(),
            messages: Vec::new(),
            thinking: ThinkingSetting::disabled(),
            stream: true,
            max_tokens: None,
            temperature: None,
            top_p: None,
            stop: Vec::new(),
            tools: Vec::new(),
            reasoning_replay: ReasoningReplay::Auto,
            api_key: None,
            read_timeout: None,
        }
    }

    /// 切成非流式。`complete()` 要求这个开关是 `false`。
    pub fn without_stream(mut self) -> Self {
        self.stream = false;
        self
    }

    pub fn tools(mut self, tools: Vec<ToolSpec>) -> Self {
        self.tools = tools;
        self
    }

    /// 直接用"几轮文本"建请求，不经过会话历史。
    ///
    /// 五类模型角色（P3）走这条路：这些消息**不进历史、不占预算**，也不需要 id 有意义。
    /// 但 `build_body` 只认 [`Message`]，所以这里用一次性会话 id 造出形状合法的消息——
    /// 造假只发生在这一处，别的地方不用再拼。
    pub fn from_turns(
        provider: ProviderConfig,
        model: impl Into<String>,
        turns: &[(Role, &str)],
    ) -> Self {
        use crate::clock::LogicalClock;
        use crate::ids::{MessageId, SessionId};
        use crate::source::SourceTag;

        let session = SessionId::new();
        let created_at = crate::engine::now_ms();
        let messages = turns
            .iter()
            .enumerate()
            .map(|(index, (role, text))| {
                let seq = index as u64 + 1;
                Message::text(
                    MessageId::new(session, seq),
                    *role,
                    if *role == Role::System {
                        SourceTag::InstructionInjection
                    } else {
                        SourceTag::History
                    },
                    *text,
                    crate::tokens::estimate_tokens(text),
                    created_at,
                    LogicalClock::new(seq),
                )
            })
            .collect();

        let mut request = Self::new(provider, model);
        request.messages = messages;
        request
    }

    pub fn messages(mut self, messages: Vec<Message>) -> Self {
        self.messages = messages;
        self
    }

    pub fn thinking(mut self, thinking: ThinkingSetting) -> Self {
        self.thinking = thinking;
        self
    }

    pub fn api_key(mut self, key: impl Into<String>) -> Self {
        self.api_key = Some(ApiKey::new(key));
        self
    }

    pub fn max_tokens(mut self, tokens: u32) -> Self {
        self.max_tokens = Some(tokens);
        self
    }

    pub fn temperature(mut self, temperature: f32) -> Self {
        self.temperature = Some(temperature);
        self
    }

    /// 三态策略落到实际行为。`Auto` 看 provider 的能力位。
    pub fn effective_replay(&self) -> ReasoningReplay {
        match self.reasoning_replay {
            ReasoningReplay::Auto => {
                if self.provider.capabilities.reasoning_replay_required {
                    ReasoningReplay::Always
                } else {
                    ReasoningReplay::Never
                }
            }
            other => other,
        }
    }
}

impl fmt::Debug for ChatRequest {
    /// 只印头部名字不印值：`ProviderConfig.extra_headers` 里很容易混进密钥。
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ChatRequest")
            .field("provider", &self.provider.id)
            .field("kind", &self.provider.kind)
            .field("model", &self.model)
            .field("messages", &self.messages.len())
            .field("thinking", &self.thinking.field)
            .field("stream", &self.stream)
            .field("max_tokens", &self.max_tokens)
            .field("replay", &self.effective_replay())
            .field("api_key", &self.api_key)
            .finish()
    }
}

/// "上游说超窗了"的可信度。**只当线索，不要当判据。**
///
/// 实测三种不同的溢出失败返回的措辞毫不相干，其中两种完全没有"上下文"字样。
/// 客户端必须自己维护窗口预算（实验报告结论 4）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OverflowHint {
    None,
    /// 措辞直接指向上下文长度。
    Likely(&'static str),
    /// 措辞和超窗**可能**有关，但也完全可能是别的参数错。
    /// `"the param is invalid"` 这类就属于这种——实测它确实可能是溢出，
    /// 但拿它当判据会误伤所有参数错误。
    Unreliable(&'static str),
}

impl OverflowHint {
    pub fn is_some(&self) -> bool {
        !matches!(self, OverflowHint::None)
    }

    pub fn matched(&self) -> Option<&'static str> {
        match self {
            OverflowHint::None => None,
            OverflowHint::Likely(text) | OverflowHint::Unreliable(text) => Some(text),
        }
    }
}

const OVERFLOW_LIKELY: &[&str] = &[
    "context length",
    "context_length_exceeded",
    "maximum context",
    "max context",
    "too many tokens",
    "prompt is too long",
    "input is too long",
    "exceeds the maximum",
    "reduce the length",
    "token limit",
];

const OVERFLOW_UNRELIABLE: &[&str] = &["the param is invalid", "all models failed"];

fn classify_overflow(body: &str) -> OverflowHint {
    let lowered = body.to_lowercase();
    for pattern in OVERFLOW_LIKELY {
        if lowered.contains(pattern) {
            return OverflowHint::Likely(pattern);
        }
    }
    for pattern in OVERFLOW_UNRELIABLE {
        if lowered.contains(pattern) {
            return OverflowHint::Unreliable(pattern);
        }
    }
    OverflowHint::None
}

/// 上游拒绝的细节。字段名各家不同，能读到的都收进来。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpErrorBody {
    pub status: u16,
    /// `error.code`，例如 `upstream_rejected`、`invalid_api_key`。
    pub code: Option<String>,
    /// `error.type`，例如 `invalid_request_error`、`overloaded_error`。
    pub error_type: Option<String>,
    pub message: String,
    /// 原始 body（截断）。解析不出结构时它就是唯一的线索。
    pub raw: String,
    pub overflow: OverflowHint,
    pub retry_after: Option<Duration>,
}

impl fmt::Display for HttpErrorBody {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "HTTP {} ", self.status)?;
        if let Some(code) = &self.code {
            write!(f, "[{code}] ")?;
        }
        write!(f, "{}", self.message)?;
        if self.overflow.is_some() {
            write!(f, "（疑似超窗: {}）", self.overflow.matched().unwrap_or(""))?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum ChatError {
    #[error(transparent)]
    Transport(#[from] TransportError),
    #[error("{0}")]
    Http(Box<HttpErrorBody>),
    #[error(transparent)]
    Sse(#[from] SseError),
    #[error(transparent)]
    Stream(#[from] StreamParseError),
    /// 请求里出现了这一层还表示不了的东西。**明确报错**，不静默丢。
    #[error("暂不支持: {0}")]
    Unsupported(String),
}

impl ChatError {
    /// 整轮失败时值不值得重发一次。语义错误（400/401/404）不值得。
    pub fn is_retryable(&self) -> bool {
        match self {
            ChatError::Transport(error) => error.is_retryable(),
            ChatError::Http(body) => is_retryable_status(body.status),
            _ => false,
        }
    }
}

fn is_retryable_status(status: u16) -> bool {
    matches!(status, 408 | 409 | 425 | 429 | 500 | 502 | 503 | 504)
}

/// 立刻重连策略（D40）。只在**一个字节都还没收到**的阶段生效——流已经开始就不可能重发，
/// 那会把前半段和后半段拼成一条不存在的回复。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconnectPolicy {
    pub max_attempts: u32,
    pub base_delay: Duration,
    pub max_delay: Duration,
}

impl Default for ReconnectPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 3,
            base_delay: Duration::from_millis(600),
            max_delay: Duration::from_secs(8),
        }
    }
}

impl ReconnectPolicy {
    /// 不重试。
    pub fn off() -> Self {
        Self {
            max_attempts: 1,
            base_delay: Duration::ZERO,
            max_delay: Duration::ZERO,
        }
    }

    fn delay(&self, attempt: u32, retry_after: Option<Duration>) -> Duration {
        if let Some(after) = retry_after {
            return after.min(self.max_delay);
        }
        let shift = attempt.saturating_sub(1).min(16);
        let backoff = self.base_delay.saturating_mul(1u32 << shift);
        backoff.min(self.max_delay)
    }
}

/// 上层的重试把错误交给 UI 时要两样东西：原始错误（用户看懂发生了什么）
/// 和停止原因（UI 说清楚为什么不再试了）。
#[derive(Debug, Clone, PartialEq)]
pub struct RetryError {
    pub error: ChatError,
    pub reason: GiveUpReason,
}

impl fmt::Display for RetryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}（不再重试：{:?}）", self.error, self.reason)
    }
}

impl std::error::Error for RetryError {}

fn status_of(error: &ChatError) -> Option<u16> {
    match error {
        ChatError::Http(body) => Some(body.status),
        _ => None,
    }
}

/// 把请求发出去，拿到一条事件流。
pub struct ChatClient<T: Transport> {
    transport: T,
    retry: ReconnectPolicy,
}

impl<T: Transport> ChatClient<T> {
    pub fn new(transport: T) -> Self {
        Self {
            transport,
            retry: ReconnectPolicy::default(),
        }
    }

    pub fn with_retry(mut self, retry: ReconnectPolicy) -> Self {
        self.retry = retry;
        self
    }

    pub fn transport(&self) -> &T {
        &self.transport
    }

    /// 发起一次**流式**对话。
    ///
    /// 返回时**响应头已经拿到**：HTTP 层错误在这一步就报出来（带上"疑似超窗"的线索），
    /// 流内的错误则在迭代事件时才出现。
    pub fn chat(&self, request: &ChatRequest) -> Result<ChatStream, ChatError> {
        if !request.stream {
            return Err(ChatError::Unsupported(
                "chat() 只做流式；要非流式回复请用 complete()（request.without_stream()）".into(),
            ));
        }
        let response = self.send(request, "text/event-stream")?;
        Ok(ChatStream::new(request.provider.kind, response.body))
    }

    /// 发起一次**非流式**对话，拿到整段结果。
    ///
    /// 五类模型角色（P3）走这条：标题、摘要、翻译都只要最终文本。
    /// 一次就要把整段读完，所以响应体有 8 MiB 上限（流式那条路没有）。
    pub fn complete(&self, request: &ChatRequest) -> Result<Completion, ChatError> {
        if request.stream {
            return Err(ChatError::Unsupported(
                "complete() 只做非流式；要流式请用 chat()，或先 .without_stream()".into(),
            ));
        }
        let mut response = self.send(request, "application/json")?;
        let text = response.read_to_string()?;
        let value: Value = serde_json::from_str(&text).map_err(|error| {
            ChatError::Unsupported(format!(
                "非流式回复不是 JSON: {error}（前 200 字符: {}）",
                truncate(&text, 200)
            ))
        })?;
        Ok(crate::stream::parse_complete(
            request.provider.kind,
            &value,
        )?)
    }

    /// 带**时间点**重试的非流式调用（D70 / D71 / §3.19）。
    ///
    /// 和 [`ReconnectPolicy`](Self::with_retry) 不是一层：那一层是"一个字节都没收到时
    /// 立刻重连"，这一层是"隔 1.3 秒再来一轮"。两层不叠次数——底层那几次跑完之后
    /// 失败才会进到这里计时，否则用户填的"等 2 秒"和他实际等的时间对不上。
    pub fn complete_retrying(
        &self,
        request: &ChatRequest,
        policy: &RetryPolicy,
        cancel: &dyn Cancellation,
    ) -> Result<Completion, RetryError> {
        let mut entropy = Entropy::default();
        let mut index = 0usize;
        let mut spent_s = 0.0f64;
        loop {
            match self.complete(request) {
                Ok(done) => return Ok(done),
                Err(error) => {
                    // `complete()` 成功才会交出正文，所以到了这里一定是"零字节"。
                    let mut rng = || entropy.next_f64();
                    let decision = policy.plan(
                        Attempt {
                            status: status_of(&error),
                            error_text: &error.to_string(),
                            index,
                            written_any: false,
                            spent_s,
                        },
                        &mut rng,
                    );
                    match decision {
                        Decision::GiveUp { reason } => return Err(RetryError { error, reason }),
                        Decision::Retry { wait_s } => {
                            if wait(Duration::from_secs_f64(wait_s), cancel)
                                == WaitOutcome::Cancelled
                            {
                                return Err(RetryError {
                                    error,
                                    reason: GiveUpReason::Cancelled,
                                });
                            }
                            spent_s += wait_s;
                            index += 1;
                        }
                    }
                }
            }
        }
    }

    /// 建请求 + 重试，直到拿到一个 2xx 响应（或把重试次数用完）。
    ///
    /// 重试只在**一个字节都还没读**的阶段发生：流已经开始就不可能重发，
    /// 那会把前半段和后半段拼成一条不存在的回复。
    fn send(&self, request: &ChatRequest, accept: &str) -> Result<HttpResponse, ChatError> {
        let body = build_body(request)?;
        let url = endpoint(request);
        let mut http = HttpRequest::post_json(
            url,
            serde_json::to_vec(&body)
                .map_err(|error| ChatError::Unsupported(format!("请求体序列化失败: {error}")))?,
        )
        .header("accept", accept);
        for (name, value) in auth_headers(request).into_iter().chain(
            request
                .provider
                .extra_headers
                .iter()
                .map(|(name, value)| (name.clone(), value.clone())),
        ) {
            http = http.header(name, value);
        }
        if let Some(timeout) = request.read_timeout {
            http = http.with_read_timeout(timeout);
        } else {
            http = http.with_read_timeout(DEFAULT_READ_TIMEOUT);
        }

        let attempts = self.retry.max_attempts.max(1);
        let mut last_error = None;
        for attempt in 1..=attempts {
            match self.transport.execute(http.clone()) {
                Ok(response) if response.is_success() => {
                    return Ok(response);
                }
                Ok(response) => {
                    let error = http_error(response);
                    let retryable = error.is_retryable();
                    let retry_after = match &error {
                        ChatError::Http(body) => body.retry_after,
                        _ => None,
                    };
                    last_error = Some(error);
                    if !retryable || attempt == attempts {
                        break;
                    }
                    sleep(self.retry.delay(attempt, retry_after));
                }
                Err(error) => {
                    let retryable = error.is_retryable();
                    last_error = Some(ChatError::Transport(error));
                    if !retryable || attempt == attempts {
                        break;
                    }
                    sleep(self.retry.delay(attempt, None));
                }
            }
        }

        Err(last_error
            .unwrap_or_else(|| ChatError::Unsupported("重试策略没有允许任何一次尝试".into())))
    }
}

/// 流式回复。按事件迭代，事件已经是归一过的形状。
pub struct ChatStream {
    reader: SseReader<Box<dyn ResponseBody>>,
    parser: StreamParser,
    pending: VecDeque<StreamEvent>,
    done: bool,
}

impl ChatStream {
    fn new(kind: ProviderKind, body: Box<dyn ResponseBody>) -> Self {
        Self {
            reader: SseReader::new(body),
            parser: StreamParser::new(kind),
            pending: VecDeque::new(),
            done: false,
        }
    }

    pub fn kind(&self) -> ProviderKind {
        self.parser.kind()
    }

    /// 累积用量（快照）。流结束后拿去记账 / 落库。
    pub fn usage(&self) -> Usage {
        self.parser.usage()
    }

    /// 下一个事件。`Ok(None)` = 流结束（干净或不干净，看有没有出过
    /// [`StreamEvent::Interrupted`]）。
    pub fn next_event(&mut self) -> Result<Option<StreamEvent>, ChatError> {
        loop {
            if let Some(event) = self.pending.pop_front() {
                if matches!(event, StreamEvent::Done) {
                    // `[DONE]` 之后有的网关不关连接。等到读超时才发现"流结束"，
                    // 会让一轮正常的回复看起来像卡死，所以在这里就收工。
                    self.done = true;
                }
                return Ok(Some(event));
            }
            if self.done {
                return Ok(None);
            }

            match self.reader.next_frame() {
                Ok(Some(frame)) => match self.parser.push_frame(&frame) {
                    Ok(events) => self.pending.extend(events),
                    Err(error) => {
                        // 已经吐出去的正文留着（上层照常收），只是这一轮到此为止
                        self.done = true;
                        return Err(ChatError::Stream(error));
                    }
                },
                Ok(None) => {
                    let tail = self.parser.end_of_stream();
                    if tail.is_empty() {
                        self.done = true;
                        return Ok(None);
                    }
                    self.pending.extend(tail);
                }
                Err(error) => {
                    self.done = true;
                    return Err(ChatError::Sse(error));
                }
            }
        }
    }
}

impl fmt::Debug for ChatStream {
    /// 响应体是流，印不出来也没意义；印出身份和当前用量就够定位问题了。
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ChatStream")
            .field("kind", &self.parser.kind())
            .field("usage", &self.parser.usage())
            .field("pending", &self.pending.len())
            .field("done", &self.done)
            .finish()
    }
}

impl Iterator for ChatStream {
    type Item = Result<StreamEvent, ChatError>;

    fn next(&mut self) -> Option<Self::Item> {
        match self.next_event() {
            Ok(Some(event)) => Some(Ok(event)),
            Ok(None) => None,
            Err(error) => Some(Err(error)),
        }
    }
}

fn http_error(mut response: HttpResponse) -> ChatError {
    let status = response.status;
    let text = response.read_to_string().unwrap_or_default();
    let retry_after = response
        .header("retry-after")
        .and_then(|value| value.trim().parse::<u64>().ok())
        .map(Duration::from_secs);
    ChatError::Http(Box::new(parse_error_body(status, &text, retry_after)))
}

/// 各家错误体的形状不一样，能读到的字段都读，读不到就把原文留着。
pub fn parse_error_body(status: u16, text: &str, retry_after: Option<Duration>) -> HttpErrorBody {
    let parsed: Option<Value> = serde_json::from_str(text).ok();
    let mut code = None;
    let mut error_type = None;
    let mut message = None;

    if let Some(value) = &parsed {
        // OpenAI / Anthropic 风格：{"error": {...}}；Anthropic 外层还有 type=error
        let error = value.get("error").unwrap_or(value);
        code = error
            .get("code")
            .or_else(|| value.get("code"))
            .map(|code| match code {
                Value::String(text) => text.clone(),
                other => other.to_string(),
            });
        error_type = error
            .get("type")
            .and_then(|value| value.as_str())
            .map(str::to_string);
        message = error
            .get("message")
            .or_else(|| error.get("msg"))
            .or_else(|| value.get("msg"))
            .or_else(|| value.get("message"))
            .and_then(|value| value.as_str())
            .map(str::to_string);
    }

    let message = message.unwrap_or_else(|| {
        if text.trim().is_empty() {
            "上游没有给错误内容".to_string()
        } else {
            truncate(text.trim(), 400)
        }
    });

    HttpErrorBody {
        status,
        code,
        error_type,
        message,
        raw: truncate(text, 2000),
        overflow: classify_overflow(text),
        retry_after,
    }
}

/// 流式端点。只有 Gemini 走不同路径（`:streamGenerateContent?alt=sse`）。
fn endpoint(request: &ChatRequest) -> String {
    if request.stream {
        request.provider.chat_url_stream(&request.model)
    } else {
        request.provider.chat_url(&request.model)
    }
}

/// 鉴权头部。密钥为空时**一个都不发**：发个空 key 会被上游当成"认证失败"，
/// 而本地端点（llama.cpp / 本机代理）本来就什么都不需要。
fn auth_headers(request: &ChatRequest) -> Vec<(String, String)> {
    let Some(key) = request.api_key.as_ref().filter(|key| !key.is_empty()) else {
        return Vec::new();
    };
    match request.provider.kind {
        ProviderKind::OpenAiCompatible | ProviderKind::OpenAiResponses => {
            vec![("authorization".into(), format!("Bearer {}", key.as_str()))]
        }
        ProviderKind::Anthropic => vec![
            ("x-api-key".into(), key.as_str().to_string()),
            ("anthropic-version".into(), "2023-06-01".into()),
        ],
        ProviderKind::Gemini => vec![("x-goog-api-key".into(), key.as_str().to_string())],
    }
}

/// 请求体：软件默认值 →（provider / model 自定义）→ 屏蔽。
///
/// 思考字段先落进默认值，所以自定义 body 能覆盖它，最后的屏蔽也能删掉它
/// （D18 的顺序：屏蔽必须最后执行）。
pub fn build_body(request: &ChatRequest) -> Result<Value, ChatError> {
    if request.messages.is_empty() {
        // 空 messages 上游会拒；与其等一个语义不明的 400，不如在这里说清楚
        // （实测：本机代理把空 messages 包成 HTTP 400 / upstream_rejected）
        return Err(ChatError::Unsupported(
            "messages 是空的：至少要有一条消息，否则上游只会回一个看不出原因的 400".into(),
        ));
    }
    let mut body = match request.provider.kind {
        ProviderKind::OpenAiCompatible => openai_body(request)?,
        ProviderKind::OpenAiResponses => responses_body(request)?,
        ProviderKind::Anthropic => anthropic_body(request)?,
        ProviderKind::Gemini => gemini_body(request)?,
    };
    request.thinking.apply(&mut body);
    Ok(request.provider.overrides.apply(body))
}

fn apply_sampling(body: &mut Value, request: &ChatRequest) {
    let Some(object) = body.as_object_mut() else {
        return;
    };
    let max_field = request.provider.kind.max_tokens_field();
    if let Some(max_tokens) = request.max_tokens {
        object.insert(max_field.into(), Value::from(max_tokens));
    }
    if let Some(temperature) = request.temperature {
        object.insert("temperature".into(), Value::from(temperature));
    }
    if let Some(top_p) = request.top_p {
        object.insert("top_p".into(), Value::from(top_p));
    }
    // 停止词不在这里：Anthropic 叫 `stop_sequences`、Gemini 在 generationConfig 里、
    // Responses 压根没有这个参数。按端口各写各的，别用一个通用名糊过去。
}

/// 消息能不能被这一层表示。表示不了就报错——静默丢内容比报错糟得多。
fn ensure_representable(message: &Message) -> Result<(), ChatError> {
    if message.archived_ref.is_some() {
        return Err(ChatError::Unsupported(format!(
            "消息 {} 的内容在归档里（形态 {}），发请求前需要先取回原文",
            message.id, message.form
        )));
    }
    // 工具块本身现在能表示了，但配对关系得对：结果必须知道自己在回哪一次调用。
    if message.role == Role::Tool && message.tool_call_id.is_none() {
        return Err(ChatError::Unsupported(format!(
            "工具结果消息 {} 没有 tool_call_id，对不上是哪次调用",
            message.id
        )));
    }
    if !message.tool_calls.is_empty() && message.role != Role::Assistant {
        return Err(ChatError::Unsupported(format!(
            "只有助手消息能带 tool_calls，{} 是 {}",
            message.id, message.role
        )));
    }
    Ok(())
}

fn tool_call_id(message: &Message) -> Result<&str, ChatError> {
    message.tool_call_id.as_deref().ok_or_else(|| {
        ChatError::Unsupported(format!("工具结果消息 {} 没有 tool_call_id", message.id))
    })
}

/// OpenAI 系的工具参数是**字符串**；空串要给 `{}`，给 `""` 有个别网关不认。
fn json_arguments(call: &crate::message::OutgoingToolCall) -> String {
    if call.arguments.trim().is_empty() {
        "{}".to_string()
    } else {
        call.arguments.clone()
    }
}

fn tool_input(call: &crate::message::OutgoingToolCall) -> Result<Value, ChatError> {
    if call.arguments.trim().is_empty() {
        return Ok(json!({}));
    }
    match serde_json::from_str::<Value>(&call.arguments) {
        Ok(value @ Value::Object(_)) => Ok(value),
        Ok(_) => Err(ChatError::Unsupported(format!(
            "工具 {} 的参数必须是 JSON 对象，给的是非对象",
            call.name
        ))),
        Err(error) => Err(ChatError::Unsupported(format!(
            "工具 {} 的参数不是合法 JSON: {error}",
            call.name
        ))),
    }
}

fn openai_body(request: &ChatRequest) -> Result<Value, ChatError> {
    let replay = request.effective_replay();
    let mut messages = Vec::new();
    for message in &request.messages {
        ensure_representable(message)?;
        messages.push(openai_message(message, replay)?);
    }

    let mut body = json!({
        "model": request.model,
        "messages": messages,
        "stream": request.stream,
    });
    // 流式 usage 要显式要：OpenAI 系默认不发，实测有的端点给、有的怎么逼都不给。
    // 非流式不需要它（usage 无条件带回来）。
    if request.stream && request.provider.capabilities.streaming_usage {
        body["stream_options"] = json!({"include_usage": true});
    }
    if !request.tools.is_empty() {
        let tools: Vec<Value> = request
            .tools
            .iter()
            .map(|tool| {
                let mut function = json!({"name": tool.name, "parameters": tool.parameters});
                if let Some(description) = &tool.description {
                    function["description"] = Value::String(description.clone());
                }
                json!({"type": "function", "function": function})
            })
            .collect();
        body["tools"] = Value::Array(tools);
    }
    if !request.stop.is_empty() {
        body["stop"] = json!(request.stop);
    }
    apply_sampling(&mut body, request);
    Ok(body)
}

fn openai_message(message: &Message, replay: ReasoningReplay) -> Result<Value, ChatError> {
    if message.role == Role::Tool {
        let id = tool_call_id(message)?;
        return Ok(json!({
            "role": "tool",
            "tool_call_id": id,
            "content": message.content,
        }));
    }

    let mut item = json!({"role": message.role.as_str(), "content": message.content});
    if message.role == Role::Assistant {
        if replay == ReasoningReplay::Always {
            if let Some(reasoning) = &message.reasoning {
                item["reasoning_content"] = Value::String(reasoning.clone());
            }
        }
        if !message.tool_calls.is_empty() {
            let calls: Vec<Value> = message
                .tool_calls
                .iter()
                .map(|call| {
                    json!({
                        "id": call.id,
                        "type": "function",
                        "function": {"name": call.name, "arguments": json_arguments(call)},
                    })
                })
                .collect();
            item["tool_calls"] = Value::Array(calls);
        }
    }
    Ok(item)
}

fn responses_body(request: &ChatRequest) -> Result<Value, ChatError> {
    if request.effective_replay() == ReasoningReplay::Always {
        return Err(ChatError::Unsupported(
            "Responses 端口的思考回放要 `reasoning` item（还要 encrypted_content），还没做".into(),
        ));
    }

    let mut input = Vec::new();
    let mut instructions: Option<String> = None;
    for message in &request.messages {
        ensure_representable(message)?;
        match message.role {
            Role::System => push_paragraph(&mut instructions, &message.content),
            Role::User => input.push(json!({
                "role": "user",
                "content": [{"type": "input_text", "text": message.content}],
            })),
            Role::Assistant => {
                if !message.content.is_empty() {
                    input.push(json!({
                        "role": "assistant",
                        "content": [{"type": "output_text", "text": message.content}],
                    }));
                }
                // 工具调用在这里是**顶层 item**，不是 message 里的字段
                for call in &message.tool_calls {
                    input.push(json!({
                        "type": "function_call",
                        "call_id": call.id,
                        "name": call.name,
                        "arguments": json_arguments(call),
                    }));
                }
            }
            Role::Tool => {
                let id = tool_call_id(message)?;
                input.push(json!({
                    "type": "function_call_output",
                    "call_id": id,
                    "output": message.content,
                }));
            }
        }
    }

    let mut body = json!({
        "model": request.model,
        "input": input,
        "stream": request.stream,
    });
    if let Some(instructions) = instructions {
        body["instructions"] = Value::String(instructions);
    }
    if !request.tools.is_empty() {
        let tools: Vec<Value> = request
            .tools
            .iter()
            .map(|tool| {
                // Responses 的 function 是**扁平**的，没有 chat 那层 `function` 包裹
                let mut item =
                    json!({"type": "function", "name": tool.name, "parameters": tool.parameters});
                if let Some(description) = &tool.description {
                    item["description"] = Value::String(description.clone());
                }
                item
            })
            .collect();
        body["tools"] = Value::Array(tools);
    }
    // Responses 没有 stop 参数：给了也发不出去（记在 docs 的未做项里）
    apply_sampling(&mut body, request);
    Ok(body)
}

fn anthropic_body(request: &ChatRequest) -> Result<Value, ChatError> {
    if request.effective_replay() == ReasoningReplay::Always {
        // Anthropic 的 thinking 块必须带签名才回传得出去，而签名要先把
        // `signature_delta` 收进消息结构（还没做）。宁可报错也不要发一个无签名块去换 400。
        return Err(ChatError::Unsupported(
            "Anthropic 的思考回放需要保存 signature，消息结构还没接（P2 之后）".into(),
        ));
    }

    let mut system: Option<String> = None;
    let mut messages: Vec<Value> = Vec::new();
    for message in &request.messages {
        ensure_representable(message)?;
        match message.role {
            Role::System => push_paragraph(&mut system, &message.content),
            Role::User => push_anthropic_blocks(
                &mut messages,
                "user",
                vec![json!({"type": "text", "text": message.content})],
            ),
            Role::Assistant => {
                let mut blocks = Vec::new();
                if !message.content.is_empty() {
                    blocks.push(json!({"type": "text", "text": message.content}));
                }
                for call in &message.tool_calls {
                    // `input` 在这边必须是**对象**
                    blocks.push(json!({
                        "type": "tool_use",
                        "id": call.id,
                        "name": call.name,
                        "input": tool_input(call)?,
                    }));
                }
                if blocks.is_empty() {
                    return Err(ChatError::Unsupported(format!(
                        "助手消息 {} 既没有正文也没有工具调用，发出去是非法请求",
                        message.id
                    )));
                }
                push_anthropic_blocks(&mut messages, "assistant", blocks);
            }
            Role::Tool => {
                let id = tool_call_id(message)?;
                // 工具结果在 Anthropic 侧是 **user 轮里的 tool_result 块**，不是新角色
                push_anthropic_blocks(
                    &mut messages,
                    "user",
                    vec![json!({
                        "type": "tool_result",
                        "tool_use_id": id,
                        "content": message.content,
                    })],
                );
            }
        }
    }

    let mut body = json!({
        "model": request.model,
        // max_tokens 在 Anthropic 是必填，没给也得给一个
        "max_tokens": request.max_tokens.unwrap_or(FALLBACK_MAX_TOKENS),
        "messages": messages,
        "stream": request.stream,
    });
    if let Some(system) = system {
        body["system"] = Value::String(system);
    }
    if !request.tools.is_empty() {
        let tools: Vec<Value> = request
            .tools
            .iter()
            .map(|tool| {
                let mut item = json!({"name": tool.name, "input_schema": tool.parameters});
                if let Some(description) = &tool.description {
                    item["description"] = Value::String(description.clone());
                }
                item
            })
            .collect();
        body["tools"] = Value::Array(tools);
    }
    if !request.stop.is_empty() {
        body["stop_sequences"] = json!(request.stop);
    }
    apply_sampling(&mut body, request);
    Ok(body)
}

/// 连续同角色的块并进同一条消息。Anthropic 要求 user / assistant 交替，
/// 与其指望上游帮我合并，不如自己先合干净。
fn push_anthropic_blocks(messages: &mut Vec<Value>, role: &str, blocks: Vec<Value>) {
    if let Some(last) = messages.last_mut() {
        if last["role"] == json!(role) {
            if let Some(content) = last["content"].as_array_mut() {
                content.extend(blocks);
                return;
            }
        }
    }
    messages.push(json!({"role": role, "content": blocks}));
}

fn gemini_body(request: &ChatRequest) -> Result<Value, ChatError> {
    if request.effective_replay() == ReasoningReplay::Always {
        return Err(ChatError::Unsupported(
            "Gemini 的思考回放要带 thoughtSignature 的 part，还没做".into(),
        ));
    }
    if !request.tools.is_empty() {
        // 不做"看起来像那么回事"的转换：Gemini 要 OpenAPI 子集（type 大写、
        // 不支持 $ref/oneOf/anyOf），静默改写 schema 会让模型按错的形状出参数。
        return Err(ChatError::Unsupported(
            "Gemini 的工具声明要 OpenAPI 子集（type 大写、无 $ref/oneOf），转换还没做（D41）"
                .into(),
        ));
    }

    // Gemini 的 functionResponse 只认**函数名**，不认 call id。
    // 结果消息里存的是 id，所以先从历史里把 id→name 建起来。
    let mut names: HashMap<&str, &str> = HashMap::new();
    for message in &request.messages {
        for call in &message.tool_calls {
            names.insert(call.id.as_str(), call.name.as_str());
        }
    }

    let mut contents: Vec<Value> = Vec::new();
    let mut system: Option<String> = None;
    for message in &request.messages {
        ensure_representable(message)?;
        match message.role {
            Role::System => push_paragraph(&mut system, &message.content),
            Role::User => push_gemini_parts(
                &mut contents,
                "user",
                vec![json!({"text": message.content})],
            ),
            Role::Assistant => {
                let mut parts = Vec::new();
                if !message.content.is_empty() {
                    parts.push(json!({"text": message.content}));
                }
                for call in &message.tool_calls {
                    parts.push(json!({
                        "functionCall": {"name": call.name, "args": tool_input(call)?},
                    }));
                }
                if parts.is_empty() {
                    continue;
                }
                push_gemini_parts(&mut contents, "model", parts);
            }
            Role::Tool => {
                let id = tool_call_id(message)?;
                let name = names.get(id).copied().ok_or_else(|| {
                    ChatError::Unsupported(format!(
                        "Gemini 的工具结果要函数名，但历史里找不到 id={id} 的那次调用"
                    ))
                })?;
                // 文档里 role 有 `user` 与 `function` 两种写法（生成式语言 API 的示例
                // 用 function，Vertex 的 REST 示例用 user）。这里走 user，见 D41。
                push_gemini_parts(
                    &mut contents,
                    "user",
                    vec![json!({
                        "functionResponse": {
                            "name": name,
                            "response": tool_response(message),
                        },
                    })],
                );
            }
        }
    }

    let mut generation = serde_json::Map::new();
    if let Some(max_tokens) = request.max_tokens {
        generation.insert("maxOutputTokens".into(), Value::from(max_tokens));
    }
    if let Some(temperature) = request.temperature {
        generation.insert("temperature".into(), Value::from(temperature));
    }
    if let Some(top_p) = request.top_p {
        generation.insert("topP".into(), Value::from(top_p));
    }
    if !request.stop.is_empty() {
        generation.insert("stopSequences".into(), json!(request.stop));
    }

    let mut body = json!({
        "contents": contents,
    });
    if let Some(system) = system {
        body["systemInstruction"] = json!({"parts": [{"text": system}]});
    }
    // 即使为空也留着 generationConfig：思考字段要往里写（ThinkingSetting 会自己建，
    // 但显式给一个空对象能让"我们确实想设生成参数"这件事在日志里看得出来）
    body["generationConfig"] = Value::Object(generation);
    Ok(body)
}

/// 连续同角色的 part 并成一条 content。Gemini 要求"多个并行调用的结果放在同一轮里"。
fn push_gemini_parts(contents: &mut Vec<Value>, role: &str, parts: Vec<Value>) {
    if let Some(last) = contents.last_mut() {
        if last["role"] == json!(role) {
            if let Some(existing) = last["parts"].as_array_mut() {
                existing.extend(parts);
                return;
            }
        }
    }
    contents.push(json!({"role": role, "parts": parts}));
}

/// `functionResponse.response` 必须是**对象**。工具结果是字符串时：
/// 能解析成对象就用它，否则包一层 `{"result": ...}`——直接塞字符串会被上游拒。
fn tool_response(message: &Message) -> Value {
    match serde_json::from_str::<Value>(&message.content) {
        Ok(value @ Value::Object(_)) => value,
        Ok(other) => json!({"result": other}),
        Err(_) => json!({"result": message.content}),
    }
}

fn push_paragraph(slot: &mut Option<String>, text: &str) {
    match slot {
        Some(existing) => {
            existing.push_str("\n\n");
            existing.push_str(text);
        }
        None => *slot = Some(text.to_string()),
    }
}

fn truncate(text: &str, limit: usize) -> String {
    if text.chars().count() <= limit {
        return text.to_string();
    }
    let head: String = text.chars().take(limit).collect();
    format!("{head}…")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::{MessageId, SessionId};
    use crate::provider::{BodyOverrides, ProviderCapabilities};
    use std::io::Cursor;
    use std::sync::Mutex;

    fn message(session: SessionId, seq: u64, role: Role, text: &str) -> Message {
        Message::text(
            MessageId::new(session, seq),
            role,
            crate::source::SourceTag::History,
            text,
            1,
            crate::engine::now_ms(),
            LogicalClock::new(seq),
        )
    }

    fn tool_call_message(
        session: SessionId,
        seq: u64,
        id: &str,
        name: &str,
        arguments: &str,
    ) -> Message {
        Message::tool_call_message(
            MessageId::new(session, seq),
            vec![crate::message::OutgoingToolCall::new(id, name, arguments)],
            1,
            crate::engine::now_ms(),
            LogicalClock::new(seq),
        )
    }

    fn tool_result_message(session: SessionId, seq: u64, id: &str, text: &str) -> Message {
        Message::tool_result(
            MessageId::new(session, seq),
            id,
            text,
            1,
            crate::engine::now_ms(),
            LogicalClock::new(seq),
        )
    }

    fn request_with(kind: ProviderKind, base: &str) -> ChatRequest {
        let mut provider = ProviderConfig::new("p", kind, base);
        provider.capabilities = match kind {
            ProviderKind::OpenAiCompatible => ProviderCapabilities::openai_like(),
            _ => ProviderCapabilities::conservative(),
        };
        ChatRequest::new(provider, "test-model")
    }

    // ---- 请求体 ----

    #[test]
    fn empty_messages_are_refused_before_hitting_the_wire() {
        // 实测：本机代理对空 messages 回 HTTP 400 / upstream_rejected，
        // 那条错误里没有任何能定位到"你忘了放消息"的信息
        let request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1");
        let error = build_body(&request).unwrap_err();
        assert!(matches!(error, ChatError::Unsupported(_)));
        assert!(error.to_string().contains("messages 是空的"));
    }

    #[test]
    fn openai_body_asks_for_stream_and_streaming_usage() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1")
            .messages(vec![
                message(session, 1, Role::System, "你是猫娘"),
                message(session, 2, Role::User, "你好"),
            ])
            .max_tokens(1024)
            .temperature(0.7);

        let body = build_body(&request).unwrap();
        assert_eq!(body["model"], json!("test-model"));
        assert_eq!(body["stream"], json!(true));
        assert_eq!(body["stream_options"]["include_usage"], json!(true));
        assert_eq!(body["max_tokens"], json!(1024));
        assert_eq!(body["messages"][0]["role"], json!("system"));
        assert_eq!(body["messages"][1]["role"], json!("user"));
        assert_eq!(body["messages"][1]["content"], json!("你好"));
    }

    #[test]
    fn thinking_is_written_before_overrides_so_mask_can_remove_it() {
        use crate::provider::{ThinkingEffort, ThinkingField};

        let session = SessionId::new();
        let mut request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1")
            .messages(vec![message(session, 1, Role::User, "hi")])
            .thinking(
                ThinkingSetting::with_field(ThinkingField::OpenAiEffort)
                    .effort(ThinkingEffort::High),
            );
        assert_eq!(
            build_body(&request).unwrap()["reasoning_effort"],
            json!("high")
        );

        // 用户屏蔽掉这个字段（比如网关不认）→ 必须真的不见
        request.provider.overrides = BodyOverrides {
            provider: Some(json!({"reasoning_effort": "low"})),
            model: None,
            mask: vec!["reasoning_effort".into()],
        };
        let body = build_body(&request).unwrap();
        assert!(body.get("reasoning_effort").is_none(), "{body}");
    }

    #[test]
    fn anthropic_body_keeps_system_out_of_messages_and_fills_max_tokens() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::Anthropic, "https://api.anthropic.com/v1")
            .messages(vec![
                message(session, 1, Role::System, "规则一"),
                message(session, 2, Role::System, "规则二"),
                message(session, 3, Role::User, "问题"),
            ]);

        let body = build_body(&request).unwrap();
        assert_eq!(body["system"], json!("规则一\n\n规则二"));
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
        assert_eq!(body["messages"][0]["role"], json!("user"));
        assert_eq!(body["messages"][0]["content"][0]["type"], json!("text"));
        // max_tokens 是必填；没给就用兜底值，不能省
        assert_eq!(body["max_tokens"], json!(FALLBACK_MAX_TOKENS));
    }

    #[test]
    fn anthropic_merges_consecutive_same_role_messages() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::Anthropic, "https://api.anthropic.com/v1")
            .messages(vec![
                message(session, 1, Role::User, "一"),
                message(session, 2, Role::User, "二"),
                message(session, 3, Role::Assistant, "答"),
            ]);
        let body = build_body(&request).unwrap();
        let messages = body["messages"].as_array().unwrap();
        assert_eq!(messages.len(), 2, "{body}");
        assert_eq!(messages[0]["content"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn gemini_body_uses_model_role_and_system_instruction() {
        let session = SessionId::new();
        let mut request = request_with(
            ProviderKind::Gemini,
            "https://generativelanguage.googleapis.com/v1beta",
        )
        .messages(vec![
            message(session, 1, Role::System, "你是猫娘"),
            message(session, 2, Role::User, "你好"),
            message(session, 3, Role::Assistant, "喵"),
        ])
        .max_tokens(2048);
        request.thinking =
            ThinkingSetting::with_field(crate::provider::ThinkingField::GeminiThinkingConfig)
                .budget(1024);

        let body = build_body(&request).unwrap();
        assert_eq!(body["contents"][0]["role"], json!("user"));
        assert_eq!(body["contents"][1]["role"], json!("model"));
        assert_eq!(
            body["systemInstruction"]["parts"][0]["text"],
            json!("你是猫娘")
        );
        assert_eq!(body["generationConfig"]["maxOutputTokens"], json!(2048));
        assert_eq!(
            body["generationConfig"]["thinkingConfig"]["thinkingBudget"],
            json!(1024)
        );
    }

    #[test]
    fn responses_body_uses_input_items_and_instructions() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::OpenAiResponses, "https://api.example/v1")
            .messages(vec![
                message(session, 1, Role::System, "规则"),
                message(session, 2, Role::User, "问题"),
            ])
            .max_tokens(512);

        let body = build_body(&request).unwrap();
        assert_eq!(body["instructions"], json!("规则"));
        assert_eq!(body["input"][0]["content"][0]["type"], json!("input_text"));
        // Responses 不吃 max_tokens
        assert_eq!(body["max_output_tokens"], json!(512));
        assert!(body.get("max_tokens").is_none());
    }

    #[test]
    fn openai_wires_tool_calls_and_results() {
        let session = SessionId::new();
        let mut request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1")
            .messages(vec![
                message(session, 1, Role::User, "读一下 a.md"),
                tool_call_message(session, 2, "call_1", "read_file", "{\"path\":\"a.md\"}"),
                tool_result_message(session, 3, "call_1", "文件内容"),
            ]);
        request.stop = vec!["STOP".into()];

        let body = build_body(&request).unwrap();
        let messages = body["messages"].as_array().unwrap();
        assert_eq!(messages[1]["tool_calls"][0]["id"], json!("call_1"));
        assert_eq!(
            messages[1]["tool_calls"][0]["function"]["name"],
            json!("read_file")
        );
        // 参数在 OpenAI 系是**字符串**
        assert_eq!(
            messages[1]["tool_calls"][0]["function"]["arguments"],
            json!("{\"path\":\"a.md\"}")
        );
        assert_eq!(messages[2]["role"], json!("tool"));
        assert_eq!(messages[2]["tool_call_id"], json!("call_1"));
        assert_eq!(messages[2]["content"], json!("文件内容"));
        assert_eq!(body["stop"], json!(["STOP"]));
    }

    #[test]
    fn anthropic_puts_tool_result_in_a_user_turn_with_an_object_input() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::Anthropic, "https://api.anthropic.com/v1")
            .messages(vec![
                message(session, 1, Role::User, "读一下 a.md"),
                tool_call_message(session, 2, "toolu_1", "read_file", "{\"path\":\"a.md\"}"),
                tool_result_message(session, 3, "toolu_1", "文件内容"),
            ]);

        let body = build_body(&request).unwrap();
        let messages = body["messages"].as_array().unwrap();
        assert_eq!(messages.len(), 3, "user / assistant / user: {body}");
        let tool_use = &messages[1]["content"][0];
        assert_eq!(tool_use["type"], json!("tool_use"));
        assert_eq!(tool_use["id"], json!("toolu_1"));
        // `input` 必须是对象，不是字符串
        assert_eq!(tool_use["input"]["path"], json!("a.md"));
        let tool_result = &messages[2]["content"][0];
        assert_eq!(messages[2]["role"], json!("user"));
        assert_eq!(tool_result["type"], json!("tool_result"));
        assert_eq!(tool_result["tool_use_id"], json!("toolu_1"));
    }

    #[test]
    fn responses_uses_function_call_items_not_message_fields() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::OpenAiResponses, "https://api.example/v1")
            .messages(vec![
                message(session, 1, Role::User, "查一下"),
                tool_call_message(session, 2, "call_1", "get_weather", "{\"city\":\"Paris\"}"),
                tool_result_message(session, 3, "call_1", "{\"temp\":21}"),
            ]);

        let body = build_body(&request).unwrap();
        let input = body["input"].as_array().unwrap();
        let call = input
            .iter()
            .find(|item| item["type"] == json!("function_call"))
            .expect("缺 function_call item");
        assert_eq!(call["call_id"], json!("call_1"));
        assert_eq!(call["name"], json!("get_weather"));
        // 结果要能按 call_id 对上
        let output = input
            .iter()
            .find(|item| item["type"] == json!("function_call_output"))
            .expect("缺 function_call_output item");
        assert_eq!(output["call_id"], json!("call_1"));
        assert_eq!(output["output"], json!("{\"temp\":21}"));
    }

    #[test]
    fn gemini_resolves_the_function_name_from_history() {
        let session = SessionId::new();
        let request = request_with(
            ProviderKind::Gemini,
            "https://generativelanguage.googleapis.com/v1beta",
        )
        .messages(vec![
            message(session, 1, Role::User, "读一下 a.md"),
            tool_call_message(session, 2, "call_1", "read_file", "{\"path\":\"a.md\"}"),
            // 工具结果是纯文本，不是 JSON
            tool_result_message(session, 3, "call_1", "文件内容"),
        ]);

        let body = build_body(&request).unwrap();
        let contents = body["contents"].as_array().unwrap();
        assert_eq!(contents[1]["role"], json!("model"));
        assert_eq!(
            contents[1]["parts"][0]["functionCall"]["name"],
            json!("read_file")
        );
        assert_eq!(
            contents[1]["parts"][0]["functionCall"]["args"]["path"],
            json!("a.md")
        );
        // functionResponse 只认名字；名字是从历史里查出来的
        let response = &contents[2]["parts"][0]["functionResponse"];
        assert_eq!(response["name"], json!("read_file"));
        // response 必须是对象：纯文本要包一层
        assert_eq!(response["response"]["result"], json!("文件内容"));
    }

    #[test]
    fn gemini_refuses_a_tool_result_it_cannot_name() {
        let session = SessionId::new();
        let request = request_with(ProviderKind::Gemini, "https://x/v1beta").messages(vec![
            message(session, 1, Role::User, "x"),
            tool_result_message(session, 2, "call_unknown", "结果"),
        ]);
        let error = build_body(&request).unwrap_err();
        assert!(error.to_string().contains("call_unknown"), "{error}");
    }

    #[test]
    fn tool_result_without_an_id_is_refused_everywhere() {
        let session = SessionId::new();
        let mut orphan = message(session, 1, Role::Tool, "结果");
        orphan.tool_call_id = None;
        let request = request_with(ProviderKind::OpenAiCompatible, "https://x/v1")
            .messages(vec![message(session, 2, Role::User, "x"), orphan]);
        let error = build_body(&request).unwrap_err();
        assert!(error.to_string().contains("tool_call_id"), "{error}");
    }

    #[test]
    fn tool_declarations_are_shaped_per_port() {
        let tool = ToolSpec::new(
            "read_file",
            json!({"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}),
        )
        .describe("读文件");

        let session = SessionId::new();
        let base =
            || {
                request_with(ProviderKind::OpenAiCompatible, "https://x/v1")
                    .messages(vec![message(session, 1, Role::User, "x")])
            };

        // OpenAI 兼容：function 包一层
        let body = build_body(&base().tools(vec![tool.clone()])).unwrap();
        assert_eq!(body["tools"][0]["type"], json!("function"));
        assert_eq!(body["tools"][0]["function"]["name"], json!("read_file"));
        assert_eq!(body["tools"][0]["function"]["description"], json!("读文件"));

        // Responses：扁平
        let mut responses = base().tools(vec![tool.clone()]);
        responses.provider.kind = ProviderKind::OpenAiResponses;
        let body = build_body(&responses).unwrap();
        assert_eq!(body["tools"][0]["type"], json!("function"));
        assert_eq!(body["tools"][0]["name"], json!("read_file"));
        assert!(
            body["tools"][0].get("function").is_none(),
            "Responses 没有这层包裹"
        );

        // Anthropic：input_schema
        let mut anthropic = base().tools(vec![tool.clone()]);
        anthropic.provider.kind = ProviderKind::Anthropic;
        let body = build_body(&anthropic).unwrap();
        assert_eq!(body["tools"][0]["name"], json!("read_file"));
        assert_eq!(
            body["tools"][0]["input_schema"]["required"][0],
            json!("path")
        );

        // Gemini：还没做 schema 转换 → 明确报错，不硬发一个会被拒的 schema
        let mut gemini = base().tools(vec![tool]);
        gemini.provider.kind = ProviderKind::Gemini;
        let error = build_body(&gemini).unwrap_err();
        assert!(error.to_string().contains("OpenAPI"), "{error}");
    }

    #[test]
    fn body_stream_flag_follows_the_request() {
        let session = SessionId::new();
        let streaming = request_with(ProviderKind::OpenAiCompatible, "https://x/v1")
            .messages(vec![message(session, 1, Role::User, "x")]);
        let body = build_body(&streaming).unwrap();
        assert_eq!(body["stream"], json!(true));
        assert_eq!(body["stream_options"]["include_usage"], json!(true));

        let whole = streaming.clone().without_stream();
        let body = build_body(&whole).unwrap();
        assert_eq!(body["stream"], json!(false));
        assert!(
            body.get("stream_options").is_none(),
            "非流式不该要 stream_options"
        );
    }

    #[test]
    fn complete_parses_a_whole_body_end_to_end() {
        let transport = MockTransport::new(vec![Ok(json_response(
            r#"{"id":"chatcmpl-1","model":"DeepSeek-V4.1-Flash","choices":[{"index":0,"message":{"role":"assistant","content":"喵喵","reasoning_content":"想想"},"finish_reason":"stop"}],"usage":{"prompt_tokens":40,"completion_tokens":60,"completion_tokens_details":{"reasoning_tokens":57}}}"#,
        ))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let request = chat_request().without_stream();
        let completion = client.complete(&request).unwrap();

        assert_eq!(completion.text, "喵喵");
        assert_eq!(completion.reasoning, "想想");
        assert_eq!(completion.usage.reasoning_tokens, 57);
        assert_eq!(completion.finish, crate::stream::FinishReason::Stop);

        let sent = client.transport().last_request();
        let body: Value = serde_json::from_slice(&sent.body).unwrap();
        assert_eq!(body["stream"], json!(false));
        // 非流式要的是整段 JSON，不是事件流
        assert!(sent
            .headers
            .iter()
            .any(|(name, value)| name == "accept" && value == "application/json"));
    }

    #[test]
    fn complete_and_chat_refuse_the_wrong_mode() {
        let transport = MockTransport::new(vec![]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());

        let error = client.complete(&chat_request()).unwrap_err();
        assert!(error.to_string().contains("非流式"), "{error}");

        let error = client.chat(&chat_request().without_stream()).unwrap_err();
        assert!(error.to_string().contains("流式"), "{error}");
    }

    #[test]
    fn complete_rejects_a_body_that_is_not_json() {
        let transport = MockTransport::new(vec![Ok(HttpResponse {
            status: 200,
            headers: vec![("content-type".into(), "text/html".into())],
            body: Box::new(Cursor::new(b"<html>oops</html>".to_vec())),
        })]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let error = client
            .complete(&chat_request().without_stream())
            .unwrap_err();
        assert!(error.to_string().contains("不是 JSON"), "{error}");
        assert!(
            error.to_string().contains("oops"),
            "报错要带原文片段: {error}"
        );
    }

    #[test]
    fn archived_message_is_refused_with_a_pointer_to_the_reason() {
        let session = SessionId::new();
        let mut archived = message(session, 1, Role::User, "长文");
        archived = archived.with_archive(
            crate::message::ContentForm::Pointer,
            crate::ids::ArchiveRef::from_content("abcd"),
        );
        let request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1")
            .messages(vec![archived]);
        let error = build_body(&request).unwrap_err();
        assert!(error.to_string().contains("归档"), "{error}");
    }

    #[test]
    fn reasoning_replay_follows_capability_unless_forced() {
        let mut request = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1");
        assert_eq!(request.effective_replay(), ReasoningReplay::Never);
        request.provider.capabilities.reasoning_replay_required = true;
        assert_eq!(request.effective_replay(), ReasoningReplay::Always);
        request.reasoning_replay = ReasoningReplay::Never;
        assert_eq!(request.effective_replay(), ReasoningReplay::Never);
    }

    #[test]
    fn anthropic_replay_without_signature_is_refused_not_faked() {
        let session = SessionId::new();
        let mut request = request_with(ProviderKind::Anthropic, "https://api.anthropic.com/v1")
            .messages(vec![message(session, 1, Role::User, "hi")]);
        request.reasoning_replay = ReasoningReplay::Always;
        let error = build_body(&request).unwrap_err();
        assert!(error.to_string().contains("signature"), "{error}");
    }

    #[test]
    fn endpoints_differ_only_where_they_have_to() {
        let openai = request_with(ProviderKind::OpenAiCompatible, "https://api.example/v1");
        assert_eq!(endpoint(&openai), "https://api.example/v1/chat/completions");

        let gemini = request_with(
            ProviderKind::Gemini,
            "https://generativelanguage.googleapis.com/v1beta",
        );
        assert_eq!(
            endpoint(&gemini),
            "https://generativelanguage.googleapis.com/v1beta/models/test-model:streamGenerateContent?alt=sse"
        );
    }

    #[test]
    fn auth_headers_are_per_port_and_empty_keys_send_nothing() {
        let mut request = request_with(ProviderKind::Anthropic, "https://api.anthropic.com/v1");
        assert!(auth_headers(&request).is_empty(), "没给 key 就什么都不发");

        request.api_key = Some(ApiKey::new("sk-test"));
        let headers = auth_headers(&request);
        assert!(headers
            .iter()
            .any(|(name, value)| name == "x-api-key" && value == "sk-test"));
        assert!(headers
            .iter()
            .any(|(name, value)| name == "anthropic-version" && value == "2023-06-01"));

        let mut gemini = request_with(ProviderKind::Gemini, "https://x/v1beta");
        gemini.api_key = Some(ApiKey::new("k"));
        assert_eq!(auth_headers(&gemini)[0].0, "x-goog-api-key");

        let mut openai = request_with(ProviderKind::OpenAiCompatible, "https://x/v1");
        openai.api_key = Some(ApiKey::new("k"));
        assert_eq!(auth_headers(&openai)[0].1, "Bearer k");

        // 空串当成没给：本地端点不该收到一个空 key
        openai.api_key = Some(ApiKey::new(""));
        assert!(auth_headers(&openai).is_empty());
    }

    #[test]
    fn api_key_never_prints_itself() {
        let key = ApiKey::new("sk-super-secret");
        assert_eq!(format!("{key:?}"), "ApiKey(***)");
        assert_eq!(format!("{key}"), "***");
        let request =
            request_with(ProviderKind::OpenAiCompatible, "https://x/v1").api_key("sk-super-secret");
        assert!(!format!("{request:?}").contains("super-secret"));
    }

    // ---- 错误映射 ----

    #[test]
    fn the_three_measured_overflow_failures_all_leave_a_trace() {
        // 实测的三条（docs/design/context-experiments-report.md §1.4）：
        // 同一个 HTTP 400 + upstream_rejected，措辞毫不相干
        let likely = parse_error_body(
            400,
            r#"{"error":{"message":"your context length has exceeded the maximum limit","code":"upstream_rejected"}}"#,
            None,
        );
        assert!(matches!(likely.overflow, OverflowHint::Likely(_)));
        assert_eq!(likely.code.as_deref(), Some("upstream_rejected"));

        let unreliable = parse_error_body(
            400,
            r#"{"error":{"message":"the param is invalid. Please try with a valid param.","code":"upstream_rejected"}}"#,
            None,
        );
        assert!(
            matches!(unreliable.overflow, OverflowHint::Unreliable(_)),
            "这条措辞完全没提上下文，只能是弱信号"
        );

        let opaque = parse_error_body(
            400,
            r#"{"error":{"message":"all models failed","code":"upstream_rejected"}}"#,
            None,
        );
        assert!(matches!(opaque.overflow, OverflowHint::Unreliable(_)));
    }

    #[test]
    fn ordinary_bad_request_is_not_flagged_as_overflow() {
        let body = parse_error_body(
            400,
            r#"{"error":{"message":"invalid model id","type":"invalid_request_error"}}"#,
            None,
        );
        assert!(!body.overflow.is_some());
        assert_eq!(body.error_type.as_deref(), Some("invalid_request_error"));
    }

    #[test]
    fn error_body_survives_being_html_or_empty() {
        let html = parse_error_body(502, "<html>502 Bad Gateway</html>", None);
        assert_eq!(html.status, 502);
        assert!(html.message.contains("502 Bad Gateway"));
        assert!(html.code.is_none());

        let empty = parse_error_body(500, "", None);
        assert_eq!(empty.message, "上游没有给错误内容");
    }

    #[test]
    fn retry_after_header_is_respected_and_capped() {
        let policy = ReconnectPolicy::default();
        assert_eq!(
            policy.delay(1, Some(Duration::from_secs(3600))),
            policy.max_delay
        );
        assert_eq!(
            policy.delay(1, Some(Duration::from_secs(2))),
            Duration::from_secs(2)
        );
        assert_eq!(policy.delay(1, None), policy.base_delay);
        assert_eq!(policy.delay(2, None), policy.base_delay * 2);
        assert_eq!(policy.delay(30, None), policy.max_delay);
    }

    // ---- 带时间点的重试（D70/D71） ----

    /// 零等待的策略：测试里不该真的睡 1 到 2 秒。
    fn instant_retry() -> RetryPolicy {
        RetryPolicy {
            bands: vec![
                crate::retry::WaitBand::new(0.0, 0.0),
                crate::retry::WaitBand::new(0.0, 0.0),
            ],
            ..RetryPolicy::default()
        }
    }

    fn completion_body(text: &str) -> String {
        format!(
            "{{\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\
             \"content\":\"{text}\"}},\"finish_reason\":\"stop\"}}],\
             \"usage\":{{\"prompt_tokens\":12,\"completion_tokens\":2}}}}"
        )
    }

    #[test]
    fn retrying_survives_two_503s() {
        let transport = MockTransport::new(vec![
            Ok(error_response(
                503,
                "{\"error\":{\"message\":\"upstream unavailable\"}}",
            )),
            Ok(error_response(
                503,
                "{\"error\":{\"message\":\"upstream unavailable\"}}",
            )),
            Ok(json_response(&completion_body("终于好了"))),
        ]);
        // 底层立刻重连关掉：这一条要测的是上层的时间点重试，
        // 两层都开着的话，请求次数说不清是谁发的（D40 的"不叠次数"）。
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let done = client
            .complete_retrying(
                // `complete()` 只吃非流式：从流式请求上调用会被直接拒掉（那不是重试能救的）。
                &chat_request().without_stream(),
                &instant_retry(),
                &crate::agent::NeverCancel,
            )
            .expect("两次 503 之后应当拿到回复");
        assert_eq!(done.text, "终于好了");
        assert_eq!(client.transport().request_count(), 3);
    }

    #[test]
    fn quota_gives_up_without_sending_a_second_request() {
        // 429 + quota：停止关键词胜过可重试状态码。这条判据反了就是"余额空了还重试到天亮"。
        let transport = MockTransport::new(vec![Ok(error_response(
            429,
            "{\"error\":{\"message\":\"rate limit reached: quota exceeded\"}}",
        ))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let error = client
            .complete_retrying(
                &chat_request().without_stream(),
                &instant_retry(),
                &crate::agent::NeverCancel,
            )
            .expect_err("余额空了不该重试");
        assert!(
            matches!(error.reason, GiveUpReason::StopKeyword(ref word) if word == "quota"),
            "{:?}",
            error.reason
        );
        assert_eq!(
            client.transport().request_count(),
            1,
            "一次都不该重发（底层重连策略会自己重连，这里只验证上层没再发一轮）"
        );
    }

    #[test]
    fn cancellation_during_the_wait_stops_the_retry() {
        struct Cancelled;
        impl Cancellation for Cancelled {
            fn is_cancelled(&self) -> bool {
                true
            }
        }
        let transport = MockTransport::new(vec![
            Ok(error_response(503, "overloaded")),
            Ok(json_response(&completion_body("不该到这里"))),
        ]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let policy = RetryPolicy {
            // 一小时：要是没被取消，这个测试会挂住。
            bands: vec![crate::retry::WaitBand::new(3_600.0, 3_600.0)],
            // 预算也放宽：不然会在"等不等"这一步就被 BudgetExhausted 挡下来，
            // 测到的就不是"等的时候被取消"了。
            total_budget_s: 10_000.0,
            ..RetryPolicy::default()
        };
        let started = std::time::Instant::now();
        let error = client
            .complete_retrying(&chat_request().without_stream(), &policy, &Cancelled)
            .expect_err("取消之后不该继续");
        assert_eq!(error.reason, GiveUpReason::Cancelled);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    // ---- 端到端（假传输） ----

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

        fn last_request(&self) -> HttpRequest {
            self.seen
                .lock()
                .unwrap()
                .last()
                .cloned()
                .expect("没有发过请求")
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

    fn sse_response(raw: &str) -> HttpResponse {
        HttpResponse {
            status: 200,
            headers: vec![("content-type".into(), "text/event-stream".into())],
            body: Box::new(Cursor::new(raw.as_bytes().to_vec())),
        }
    }

    fn json_response(raw: &str) -> HttpResponse {
        HttpResponse {
            status: 200,
            headers: vec![("content-type".into(), "application/json".into())],
            body: Box::new(Cursor::new(raw.as_bytes().to_vec())),
        }
    }

    fn error_response(status: u16, body: &str) -> HttpResponse {
        HttpResponse {
            status,
            headers: vec![("content-type".into(), "application/json".into())],
            body: Box::new(Cursor::new(body.as_bytes().to_vec())),
        }
    }

    fn chat_request() -> ChatRequest {
        let session = SessionId::new();
        request_with(ProviderKind::OpenAiCompatible, "http://127.0.0.1:8080/v1")
            .messages(vec![message(session, 1, Role::User, "1+1=?")])
            .api_key("sk-test")
    }

    #[test]
    fn streaming_call_turns_raw_sse_into_normalized_events() {
        // 逐字对齐真实端点的帧形状（含 content:"" 与 reasoning_content 并存）
        let raw = concat!(
            "data: {\"id\":\"c1\",\"model\":\"m-1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"We\",\"content\":\"\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"2\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":8,\"completion_tokens_details\":{\"reasoning_tokens\":8}}}\n\n",
            "data: [DONE]\n\n",
        );
        let transport = MockTransport::new(vec![Ok(sse_response(raw))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let mut stream = client.chat(&chat_request()).unwrap();

        let mut events = Vec::new();
        while let Some(event) = stream.next_event().unwrap() {
            events.push(event);
        }

        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::Meta { model: Some(model), .. } if model == "m-1"
        )));
        assert_eq!(
            events
                .iter()
                .filter_map(|event| match event {
                    StreamEvent::Text(text) => Some(text.as_str()),
                    _ => None,
                })
                .collect::<String>(),
            "2"
        );
        assert_eq!(
            events
                .iter()
                .filter_map(|event| match event {
                    StreamEvent::Reasoning(text) => Some(text.as_str()),
                    _ => None,
                })
                .collect::<String>(),
            "We"
        );
        assert!(events.contains(&StreamEvent::Finish(crate::stream::FinishReason::Stop)));
        assert!(events.contains(&StreamEvent::Done));
        assert_eq!(stream.usage().reasoning_tokens, 8);
        assert_eq!(stream.usage().prompt_tokens, 31);
        // [DONE] 之后不再等 socket 关闭
        assert!(stream.next_event().unwrap().is_none());

        let sent = client.transport().last_request();
        assert_eq!(sent.url, "http://127.0.0.1:8080/v1/chat/completions");
        assert!(sent
            .headers
            .iter()
            .any(|(name, value)| name == "authorization" && value == "Bearer sk-test"));
        assert!(sent
            .headers
            .iter()
            .any(|(name, value)| name == "accept" && value == "text/event-stream"));
        let body: Value = serde_json::from_slice(&sent.body).unwrap();
        assert_eq!(body["messages"][0]["content"], json!("1+1=?"));
    }

    #[test]
    fn truncated_stream_is_reported_as_interrupted_not_completed() {
        let raw = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"说到一半\"}}]}\n\n";
        let transport = MockTransport::new(vec![Ok(sse_response(raw))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let events: Vec<StreamEvent> = client
            .chat(&chat_request())
            .unwrap()
            .filter_map(Result::ok)
            .collect();
        assert!(matches!(events.last(), Some(StreamEvent::Interrupted(_))));
        assert!(!events.contains(&StreamEvent::Done));
    }

    #[test]
    fn a_400_is_not_retried_but_a_429_is() {
        let transport = MockTransport::new(vec![
            Ok(error_response(
                429,
                r#"{"error":{"message":"rate limited","code":"rate_limit"}}"#,
            )),
            Ok(sse_response("data: [DONE]\n\n")),
        ]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy {
            max_attempts: 3,
            base_delay: Duration::ZERO,
            max_delay: Duration::ZERO,
        });
        let mut stream = client.chat(&chat_request()).unwrap();
        assert!(matches!(
            stream.next_event().unwrap(),
            Some(StreamEvent::Done)
        ));
        assert_eq!(client.transport().request_count(), 2, "429 该重试");

        let transport = MockTransport::new(vec![Ok(error_response(
            400,
            r#"{"error":{"message":"invalid model id"}}"#,
        ))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy {
            max_attempts: 3,
            base_delay: Duration::ZERO,
            max_delay: Duration::ZERO,
        });
        let error = client.chat(&chat_request()).unwrap_err();
        assert!(!error.is_retryable());
        assert_eq!(client.transport().request_count(), 1, "400 不该重试");
        assert!(error.to_string().contains("invalid model id"));
    }

    #[test]
    fn transport_failure_is_retried_until_attempts_run_out() {
        let transport = MockTransport::new(vec![
            Err(TransportError::Connect {
                host: "127.0.0.1:1".into(),
                message: "connection refused".into(),
            }),
            Err(TransportError::Timeout("读超时".into())),
        ]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy {
            max_attempts: 2,
            base_delay: Duration::ZERO,
            max_delay: Duration::ZERO,
        });
        let error = client.chat(&chat_request()).unwrap_err();
        assert!(matches!(
            error,
            ChatError::Transport(TransportError::Timeout(_))
        ));
        assert_eq!(client.transport().request_count(), 2);
    }

    #[test]
    fn non_streaming_is_refused_with_an_explicit_message() {
        let transport = MockTransport::new(vec![]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        let mut request = chat_request();
        request.stream = false;
        let error = client.chat(&request).unwrap_err();
        assert!(matches!(error, ChatError::Unsupported(_)));
        assert!(error.to_string().contains("流式"));
    }

    #[test]
    fn extra_headers_from_the_provider_are_sent() {
        let mut request = chat_request();
        request
            .provider
            .extra_headers
            .push(("x-trace".into(), "abc".into()));
        let transport = MockTransport::new(vec![Ok(sse_response("data: [DONE]\n\n"))]);
        let client = ChatClient::new(transport).with_retry(ReconnectPolicy::off());
        client.chat(&request).unwrap();
        assert!(client
            .transport()
            .last_request()
            .headers
            .iter()
            .any(|(name, value)| name == "x-trace" && value == "abc"));
    }
}
