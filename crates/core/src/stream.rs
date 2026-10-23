//! 流式事件适配：把四种 provider 的 SSE 帧翻译成同一组事件（P2 运行时）。
//!
//! 分工：帧的切分在 [`crate::sse`]，这里只解释 `data:` 里的 JSON。
//! 四个端口（`openai_compatible` / `openai_responses` / `anthropic` / `gemini`）
//! 各有各的字段名，UI 不该知道这些差别——所以差异全部收在这一层。
//!
//! 字段名不是凭记忆写的：`docs/provider-runtime.md` 记了每个字段的来源（官方文档或实测）。
//!
//! ## usage 的口径统一（重要）
//!
//! 各家的 `prompt_tokens` 口径不一样，直接混用会让预算算错：
//!
//! | 端口 | 原样给的 | 我们归一成 |
//! |---|---|---|
//! | OpenAI 兼容 | `prompt_tokens` 含缓存命中 | 原样；`cached_tokens` 取 `prompt_tokens_details.cached_tokens` |
//! | Anthropic | `input_tokens` **不含**缓存 | `prompt_tokens = input + cache_read + cache_creation`，`cached = cache_read` |
//! | Responses | `input_tokens` 含缓存 | 原样；`cached = input_tokens_details.cached_tokens` |
//! | Gemini | `promptTokenCount` 含缓存 | 原样；`cached = cachedContentTokenCount` |
//!
//! 归一之后 `prompt_tokens` 一律是"这次请求实际吃掉的输入量"，可以和本地 token 估算直接比。
//! 缓存命中率要用 `cached_tokens / prompt_tokens`，**不能**拿未归一的值算。

use std::collections::BTreeMap;

use serde_json::Value;

use crate::budget::Usage;
use crate::provider::ProviderKind;
use crate::sse::SseFrame;

/// 上游给的停止原因，归一成我们能判断的几类。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FinishReason {
    /// 自然结束。
    Stop,
    /// 撞到输出上限——D21 的"被截断"就是它。
    Length,
    /// 模型要调工具，这一轮没说完。
    ToolCalls,
    /// 被安全策略拦下。
    ContentFilter,
    /// 上游给了个我们不认识的词。原样留着，别假装懂。
    Unknown(String),
}

impl FinishReason {
    pub fn as_str(&self) -> &str {
        match self {
            FinishReason::Stop => "stop",
            FinishReason::Length => "length",
            FinishReason::ToolCalls => "tool_calls",
            FinishReason::ContentFilter => "content_filter",
            FinishReason::Unknown(value) => value,
        }
    }
}

/// 一个流式事件。UI 只认这几样。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamEvent {
    /// 流刚开始时给一次：上游的消息 id 与实际使用的模型名（可能和我们请求的不一样）。
    Meta {
        id: Option<String>,
        model: Option<String>,
    },
    Text(String),
    Reasoning(String),
    /// 工具调用的分片。参数是**片段**，拼起来才是 JSON（Anthropic / OpenAI 都这样）。
    ToolCallDelta {
        index: u32,
        id: Option<String>,
        name: Option<String>,
        arguments: String,
    },
    /// 累积用量（每次变化给一次，是快照不是增量）。
    Usage(Usage),
    Finish(FinishReason),
    /// 干净结束：收到了 `[DONE]` / `message_stop` / `response.completed`。
    Done,
    /// 流结束了但**没等到终止信号**（断线、被掐断）。D21 的 `incomplete` 由它触发。
    Interrupted(String),
    /// 网关在流内报错。注意这和 HTTP 层报错是两回事：状态码是 200，错误在 body 里。
    ProviderError {
        code: Option<String>,
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum StreamParseError {
    #[error("{kind:?} 流里有一段 data 不是 JSON: {message}（片段: {sample}）")]
    Malformed {
        kind: ProviderKind,
        message: String,
        sample: String,
    },
    /// JSON 合法，但结构不是这个端口该有的形状。非流式回复最常见的翻车点。
    #[error("{kind:?} 的响应形状不认识: {message}（片段: {sample}）")]
    Shape {
        kind: ProviderKind,
        message: String,
        sample: String,
    },
}

/// 一次工具调用。`arguments` 是**拼好的** JSON 字符串（可能是空串 = 无参）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ToolCall {
    /// 会话内定位用的序号（Anthropic 是 content block 序号，Responses 是 output_index）。
    pub index: u32,
    pub id: Option<String>,
    pub name: String,
    pub arguments: String,
}

impl ToolCall {
    /// 解析参数。空串当 `{}`——很多模型的零参调用就是这么给的。
    ///
    /// 解析失败**不猜**：半截 JSON 拿去执行是灾难。
    pub fn arguments_json(&self) -> Result<Value, String> {
        if self.arguments.trim().is_empty() {
            return Ok(Value::Object(Default::default()));
        }
        serde_json::from_str(&self.arguments)
            .map_err(|error| format!("工具 {} 的参数不是合法 JSON: {error}", self.name))
    }
}

/// 一次**非流式**回复的完整结果。
///
/// 和流式的 [`StreamEvent`] 是两条路：流式是"边到边显示"，这里是"拿到整段再处理"。
/// 五类模型角色（P3）要的是这个形状：标题、摘要、翻译都只需要最终文本。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Completion {
    pub text: String,
    /// 思考内容（没有就是空串）。**不混进 `text`**。
    pub reasoning: String,
    pub tool_calls: Vec<ToolCall>,
    pub finish: FinishReason,
    pub usage: Usage,
    pub model: Option<String>,
    pub id: Option<String>,
}

/// 每种 provider 的解析器。跨帧带状态（用量累积、是否见过终止信号）。
#[derive(Debug, Clone)]
pub struct StreamParser {
    kind: ProviderKind,
    usage: Usage,
    sent_meta: bool,
    /// 见到过终止事件或 `[DONE]`。
    terminated: bool,
    /// 按 index 攒工具调用分片。四家都以 `index`（Anthropic 是 content block 序号、
    /// Responses 是 output_index）作为稳定分组键，所以拼装逻辑只需要一份。
    calls: BTreeMap<u32, ToolCall>,
}

impl StreamParser {
    pub fn new(kind: ProviderKind) -> Self {
        Self {
            kind,
            usage: Usage::default(),
            sent_meta: false,
            terminated: false,
            calls: BTreeMap::new(),
        }
    }

    pub fn kind(&self) -> ProviderKind {
        self.kind
    }

    /// 累积用量。是快照，不是增量——直接喂给 `finish_turn`。
    pub fn usage(&self) -> Usage {
        self.usage
    }

    /// 已经拼好的工具调用，按 index 排序。
    ///
    /// 分片是给 UI 看的（边流边显示参数），这里是给**执行**用的：
    /// 只有拼完整才可能解析成 JSON。中途调用只会拿到半截参数。
    pub fn tool_calls(&self) -> Vec<ToolCall> {
        self.calls.values().cloned().collect()
    }

    pub fn push_frame(&mut self, frame: &SseFrame) -> Result<Vec<StreamEvent>, StreamParseError> {
        if frame.is_done() {
            self.terminated = true;
            return Ok(vec![StreamEvent::Done]);
        }
        if frame.data.trim().is_empty() {
            return Ok(Vec::new());
        }

        let value: Value =
            serde_json::from_str(&frame.data).map_err(|error| StreamParseError::Malformed {
                kind: self.kind,
                message: error.to_string(),
                sample: truncate(&frame.data, 200),
            })?;

        // Gemini 的非 SSE 模式会一次给一个 JSON 数组；顺手支持，不额外要求用户开 alt=sse。
        if let Value::Array(items) = &value {
            let mut events = Vec::new();
            for item in items {
                let batch = self.push_value(item, frame.event_name())?;
                self.absorb(&batch);
                events.extend(batch);
            }
            return Ok(events);
        }

        let events = self.push_value(&value, frame.event_name())?;
        self.absorb(&events);
        Ok(events)
    }

    /// 把这一批事件里的工具分片并进拼装表。
    fn absorb(&mut self, events: &[StreamEvent]) {
        for event in events {
            let StreamEvent::ToolCallDelta {
                index,
                id,
                name,
                arguments,
            } = event
            else {
                continue;
            };
            let call = self.calls.entry(*index).or_default();
            // entry 的键是 index，但结构体自己的字段也得写进去——否则调用方拿到的是
            // 一堆 index 全是 0 的调用（map 里排得对，读出来全错）。
            call.index = *index;
            if let Some(id) = id {
                if !id.is_empty() {
                    call.id = Some(id.clone());
                }
            }
            if let Some(name) = name {
                if !name.is_empty() {
                    call.name = name.clone();
                }
            }
            call.arguments.push_str(arguments);
        }
    }

    /// 流已经结束（EOF / 连接断了）时调用一次。
    ///
    /// 没见到终止信号的，补一条 [`StreamEvent::Interrupted`]：**不能**当成正常结束，
    /// 否则被掐断的回复会以"完整答案"的样子进历史（D21 要求标 `incomplete`）。
    pub fn end_of_stream(&mut self) -> Vec<StreamEvent> {
        if self.terminated {
            return Vec::new();
        }
        self.terminated = true;
        vec![StreamEvent::Interrupted(
            "流在没有终止信号的情况下结束了".into(),
        )]
    }

    fn push_value(
        &mut self,
        value: &Value,
        event_name: Option<&str>,
    ) -> Result<Vec<StreamEvent>, StreamParseError> {
        match self.kind {
            ProviderKind::OpenAiCompatible => Ok(self.openai_chunk(value)),
            ProviderKind::OpenAiResponses => Ok(self.responses_event(value, event_name)),
            ProviderKind::Anthropic => Ok(self.anthropic_event(value, event_name)),
            ProviderKind::Gemini => Ok(self.gemini_chunk(value)),
        }
    }

    // ---- OpenAI 兼容：POST /v1/chat/completions ----

    fn openai_chunk(&mut self, value: &Value) -> Vec<StreamEvent> {
        let mut events = Vec::new();

        if let Some(error) = value.get("error").filter(|error| !error.is_null()) {
            events.push(provider_error(error, Some("error")));
            return events;
        }

        self.emit_meta(
            value.get("id").and_then(Value::as_str),
            value.get("model").and_then(Value::as_str),
            &mut events,
        );

        if let Some(usage) = value.get("usage").filter(|usage| !usage.is_null()) {
            let parsed = openai_usage(usage);
            if self.merge_usage(parsed) {
                events.push(StreamEvent::Usage(self.usage));
            }
        }

        if let Some(choices) = value.get("choices").and_then(Value::as_array) {
            for choice in choices {
                let index = choice.get("index").and_then(Value::as_u64).unwrap_or(0) as u32;
                if let Some(delta) = choice.get("delta").filter(|delta| !delta.is_null()) {
                    self.openai_delta(index, delta, &mut events);
                }
                if let Some(reason) = choice.get("finish_reason").and_then(Value::as_str) {
                    self.terminated = true;
                    events.push(StreamEvent::Finish(openai_finish_reason(reason)));
                }
            }
        }

        events
    }

    fn openai_delta(&mut self, index: u32, delta: &Value, events: &mut Vec<StreamEvent>) {
        if let Some(text) = delta_text(delta.get("content")) {
            if !text.is_empty() {
                events.push(StreamEvent::Text(text));
            }
        }

        // 实测：DeepSeek 系用 reasoning_content，别的网关用 reasoning / thinking。
        // 三个都认，按优先级取第一个非空的字符串。
        for key in ["reasoning_content", "reasoning", "thinking"] {
            if let Some(text) = delta.get(key).and_then(Value::as_str) {
                if !text.is_empty() {
                    events.push(StreamEvent::Reasoning(text.to_string()));
                    break;
                }
            }
        }

        if let Some(calls) = delta.get("tool_calls").and_then(Value::as_array) {
            for call in calls {
                let call_index = call
                    .get("index")
                    .and_then(Value::as_u64)
                    .map(|value| value as u32)
                    .unwrap_or(index);
                let function = call.get("function");
                let arguments = function
                    .and_then(|function| function.get("arguments"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                let name = function
                    .and_then(|function| function.get("name"))
                    .and_then(Value::as_str)
                    .map(str::to_string);
                let id = call.get("id").and_then(Value::as_str).map(str::to_string);

                if id.is_some() || name.is_some() || !arguments.is_empty() {
                    events.push(StreamEvent::ToolCallDelta {
                        index: call_index,
                        id,
                        name,
                        arguments,
                    });
                }
            }
        }
    }

    // ---- OpenAI Responses：POST /v1/responses ----

    fn responses_event(&mut self, value: &Value, event_name: Option<&str>) -> Vec<StreamEvent> {
        let mut events = Vec::new();
        let event = value
            .get("type")
            .and_then(Value::as_str)
            .or(event_name)
            .unwrap_or_default();

        match event {
            "response.created" | "response.in_progress" => {
                let response = value.get("response").unwrap_or(value);
                self.emit_meta(
                    response.get("id").and_then(Value::as_str),
                    response.get("model").and_then(Value::as_str),
                    &mut events,
                );
            }
            "response.output_text.delta" | "response.refusal.delta" => {
                if let Some(delta) = value.get("delta").and_then(Value::as_str) {
                    if !delta.is_empty() {
                        events.push(StreamEvent::Text(delta.to_string()));
                    }
                }
            }
            "response.reasoning_summary_text.delta"
            | "response.reasoning_text.delta"
            | "response.reasoning_summary.delta"
            | "response.reasoning.delta" => {
                if let Some(delta) = value.get("delta").and_then(Value::as_str) {
                    if !delta.is_empty() {
                        events.push(StreamEvent::Reasoning(delta.to_string()));
                    }
                }
            }
            "response.function_call_arguments.delta" | "response.custom_tool_call_input.delta" => {
                let index = value
                    .get("output_index")
                    .and_then(Value::as_u64)
                    .unwrap_or(0) as u32;
                events.push(StreamEvent::ToolCallDelta {
                    index,
                    id: value
                        .get("item_id")
                        .and_then(Value::as_str)
                        .map(str::to_string),
                    name: None,
                    arguments: value
                        .get("delta")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_string(),
                });
            }
            "response.output_item.added" => {
                if let Some(item) = value.get("item") {
                    if item.get("type").and_then(Value::as_str) == Some("function_call") {
                        events.push(StreamEvent::ToolCallDelta {
                            index: value
                                .get("output_index")
                                .and_then(Value::as_u64)
                                .unwrap_or(0) as u32,
                            id: item
                                .get("call_id")
                                .or_else(|| item.get("id"))
                                .and_then(Value::as_str)
                                .map(str::to_string),
                            name: item.get("name").and_then(Value::as_str).map(str::to_string),
                            arguments: String::new(),
                        });
                    }
                }
            }
            "response.completed" | "response.incomplete" => {
                let response = value.get("response").unwrap_or(value);
                self.emit_meta(
                    response.get("id").and_then(Value::as_str),
                    response.get("model").and_then(Value::as_str),
                    &mut events,
                );
                if let Some(usage) = response.get("usage") {
                    let parsed = responses_usage(usage);
                    if self.merge_usage(parsed) {
                        events.push(StreamEvent::Usage(self.usage));
                    }
                }
                let reason = match response
                    .get("incomplete_details")
                    .and_then(|details| details.get("reason"))
                    .and_then(Value::as_str)
                {
                    Some("max_output_tokens") => FinishReason::Length,
                    Some("content_filter") => FinishReason::ContentFilter,
                    Some(other) => FinishReason::Unknown(other.to_string()),
                    None if event == "response.completed" => FinishReason::Stop,
                    None => FinishReason::Unknown("incomplete".into()),
                };
                self.terminated = true;
                events.push(StreamEvent::Finish(reason));
                events.push(StreamEvent::Done);
            }
            "response.failed" => {
                let response = value.get("response").unwrap_or(value);
                let error = response.get("error").unwrap_or(response);
                events.push(provider_error(error, Some("response_failed")));
            }
            "error" => {
                let error = value.get("error").unwrap_or(value);
                events.push(provider_error(error, Some("error")));
            }
            _ => {}
        }

        events
    }

    // ---- Anthropic：POST /v1/messages ----

    fn anthropic_event(&mut self, value: &Value, event_name: Option<&str>) -> Vec<StreamEvent> {
        let mut events = Vec::new();
        let event = value
            .get("type")
            .and_then(Value::as_str)
            .or(event_name)
            .unwrap_or_default();

        match event {
            "message_start" => {
                let message = value.get("message").unwrap_or(value);
                self.emit_meta(
                    message.get("id").and_then(Value::as_str),
                    message.get("model").and_then(Value::as_str),
                    &mut events,
                );
                if let Some(usage) = message.get("usage") {
                    let parsed = anthropic_usage(usage);
                    if self.merge_usage(parsed) {
                        events.push(StreamEvent::Usage(self.usage));
                    }
                }
            }
            "content_block_start" => {
                if let Some(block) = value.get("content_block") {
                    let kind = block
                        .get("type")
                        .and_then(Value::as_str)
                        .unwrap_or_default();
                    if kind == "tool_use" || kind == "server_tool_use" {
                        events.push(StreamEvent::ToolCallDelta {
                            index: value.get("index").and_then(Value::as_u64).unwrap_or(0) as u32,
                            id: block.get("id").and_then(Value::as_str).map(str::to_string),
                            name: block
                                .get("name")
                                .and_then(Value::as_str)
                                .map(str::to_string),
                            arguments: String::new(),
                        });
                    }
                }
            }
            "content_block_delta" => {
                let index = value.get("index").and_then(Value::as_u64).unwrap_or(0) as u32;
                if let Some(delta) = value.get("delta") {
                    match delta
                        .get("type")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                    {
                        "text_delta" => {
                            if let Some(text) = delta.get("text").and_then(Value::as_str) {
                                if !text.is_empty() {
                                    events.push(StreamEvent::Text(text.to_string()));
                                }
                            }
                        }
                        "thinking_delta" => {
                            if let Some(text) = delta.get("thinking").and_then(Value::as_str) {
                                if !text.is_empty() {
                                    events.push(StreamEvent::Reasoning(text.to_string()));
                                }
                            }
                        }
                        "input_json_delta" => {
                            events.push(StreamEvent::ToolCallDelta {
                                index,
                                id: None,
                                name: None,
                                arguments: delta
                                    .get("partial_json")
                                    .and_then(Value::as_str)
                                    .unwrap_or_default()
                                    .to_string(),
                            });
                        }
                        // signature_delta / citations_delta：D20 要回放的签名与引用，
                        // 这里先只丢弃；真要支持回放得进消息结构，属于 P2 之后的事。
                        _ => {}
                    }
                }
            }
            "message_delta" => {
                if let Some(usage) = value.get("usage") {
                    let parsed = anthropic_usage(usage);
                    if self.merge_usage(parsed) {
                        events.push(StreamEvent::Usage(self.usage));
                    }
                }
                if let Some(reason) = value
                    .get("delta")
                    .and_then(|delta| delta.get("stop_reason"))
                    .and_then(Value::as_str)
                {
                    events.push(StreamEvent::Finish(anthropic_finish_reason(reason)));
                }
            }
            "message_stop" => {
                self.terminated = true;
                events.push(StreamEvent::Done);
            }
            "ping" => {}
            "error" => {
                let error = value.get("error").unwrap_or(value);
                events.push(provider_error(error, Some("error")));
            }
            _ => {}
        }

        events
    }

    // ---- Gemini：POST /v1beta/models/{model}:streamGenerateContent?alt=sse ----

    fn gemini_chunk(&mut self, value: &Value) -> Vec<StreamEvent> {
        let mut events = Vec::new();

        // 有的网关把错误也用 HTTP 200 回，body 是 {"code":..,"msg":..}，没有 candidates。
        if value.get("candidates").is_none() {
            if let Some(message) = value.get("msg").and_then(Value::as_str) {
                let code = value
                    .get("code")
                    .map(|code| code.to_string())
                    .or_else(|| Some("gateway_error".into()));
                // code 200 + 没有 candidates 是最坑的一种：假装成功却什么都给不出
                events.push(StreamEvent::ProviderError {
                    code,
                    message: message.to_string(),
                });
                return events;
            }
        }

        if !self.sent_meta {
            let model = value.get("modelVersion").and_then(Value::as_str);
            let id = value.get("responseId").and_then(Value::as_str);
            if model.is_some() || id.is_some() {
                self.emit_meta(id, model, &mut events);
            }
        }

        if let Some(reason) = value
            .get("promptFeedback")
            .and_then(|feedback| feedback.get("blockReason"))
            .and_then(Value::as_str)
        {
            events.push(StreamEvent::ProviderError {
                code: Some("prompt_blocked".into()),
                message: format!("请求被内容策略拦下: {reason}"),
            });
        }

        if let Some(candidates) = value.get("candidates").and_then(Value::as_array) {
            for candidate in candidates {
                if let Some(parts) = candidate
                    .get("content")
                    .and_then(|content| content.get("parts"))
                    .and_then(Value::as_array)
                {
                    for (index, part) in parts.iter().enumerate() {
                        // thought: true 的 part 是思考内容（实测与官方文档都这么标）
                        if let Some(text) = part.get("text").and_then(Value::as_str) {
                            if !text.is_empty() {
                                let is_thought = part
                                    .get("thought")
                                    .and_then(Value::as_bool)
                                    .unwrap_or(false);
                                if is_thought {
                                    events.push(StreamEvent::Reasoning(text.to_string()));
                                } else {
                                    events.push(StreamEvent::Text(text.to_string()));
                                }
                            }
                        }
                        if let Some(call) = part.get("functionCall") {
                            let arguments = call
                                .get("args")
                                .map(|args| args.to_string())
                                .unwrap_or_else(|| "{}".into());
                            events.push(StreamEvent::ToolCallDelta {
                                index: index as u32,
                                id: call.get("id").and_then(Value::as_str).map(str::to_string),
                                name: call.get("name").and_then(Value::as_str).map(str::to_string),
                                arguments,
                            });
                        }
                    }
                }

                if let Some(reason) = candidate.get("finishReason").and_then(Value::as_str) {
                    events.push(StreamEvent::Finish(gemini_finish_reason(reason)));
                }
            }
        }

        if let Some(usage) = value.get("usageMetadata") {
            let parsed = gemini_usage(usage);
            if self.merge_usage(parsed) {
                events.push(StreamEvent::Usage(self.usage));
            }
        }

        events
    }

    // ---- 公共 ----

    fn emit_meta(&mut self, id: Option<&str>, model: Option<&str>, events: &mut Vec<StreamEvent>) {
        if self.sent_meta || (id.is_none() && model.is_none()) {
            return;
        }
        self.sent_meta = true;
        events.push(StreamEvent::Meta {
            id: id.map(str::to_string),
            model: model.map(str::to_string),
        });
    }

    /// 逐字段取大值合并。返回是否有变化。
    ///
    /// 为什么是取大不是相加：四家的用法都是"给当前累计值"，中途还会重发
    /// （Anthropic 的 `message_delta` 会再报一次 `input_tokens`）。
    /// 相加会把同一批 token 数两遍；取大是单调的，重发无害。
    fn merge_usage(&mut self, incoming: Usage) -> bool {
        let merged = Usage {
            prompt_tokens: self.usage.prompt_tokens.max(incoming.prompt_tokens),
            completion_tokens: self.usage.completion_tokens.max(incoming.completion_tokens),
            cached_tokens: self.usage.cached_tokens.max(incoming.cached_tokens),
            reasoning_tokens: self.usage.reasoning_tokens.max(incoming.reasoning_tokens),
        };
        if merged == self.usage {
            return false;
        }
        self.usage = merged;
        true
    }
}

/// 解析一整段**非流式**回复。
///
/// 四家的响应形状差别比流式还大：Anthropic 把文本塞进 `content[]` 的不同块里，
/// Responses 把文本、思考、工具调用拆成 `output[]` 的不同 item。
/// 读不到就报 [`StreamParseError::Shape`]，**不返回一个"空回复"糊过去**——
/// 静默的空答案比报错难查十倍。
pub fn parse_complete(kind: ProviderKind, value: &Value) -> Result<Completion, StreamParseError> {
    match kind {
        ProviderKind::OpenAiCompatible => openai_complete(value),
        ProviderKind::OpenAiResponses => responses_complete(value),
        ProviderKind::Anthropic => anthropic_complete(value),
        ProviderKind::Gemini => gemini_complete(value),
    }
}

fn shape(kind: ProviderKind, message: impl Into<String>, value: &Value) -> StreamParseError {
    StreamParseError::Shape {
        kind,
        message: message.into(),
        sample: truncate(&value.to_string(), 300),
    }
}

fn openai_complete(value: &Value) -> Result<Completion, StreamParseError> {
    let kind = ProviderKind::OpenAiCompatible;
    if let Some(error) = value.get("error").filter(|error| !error.is_null()) {
        return Err(shape(
            kind,
            format!("状态码 200，但 body 里是错误: {}", error_text(error)),
            value,
        ));
    }

    let choice = value
        .get("choices")
        .and_then(Value::as_array)
        .and_then(|choices| choices.first())
        .ok_or_else(|| shape(kind, "choices 是空的", value))?;
    let message = choice
        .get("message")
        .filter(|m| !m.is_null())
        .unwrap_or(choice);

    let text = delta_text(message.get("content")).unwrap_or_default();
    let reasoning = ["reasoning_content", "reasoning", "thinking"]
        .iter()
        .find_map(|key| message.get(*key).and_then(Value::as_str))
        .unwrap_or_default()
        .to_string();

    let mut tool_calls = Vec::new();
    if let Some(calls) = message.get("tool_calls").and_then(Value::as_array) {
        for (position, call) in calls.iter().enumerate() {
            let function = call.get("function");
            tool_calls.push(ToolCall {
                index: call
                    .get("index")
                    .and_then(Value::as_u64)
                    .map(|index| index as u32)
                    .unwrap_or(position as u32),
                id: call.get("id").and_then(Value::as_str).map(str::to_string),
                name: function
                    .and_then(|function| function.get("name"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                arguments: function
                    .and_then(|function| function.get("arguments"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
            });
        }
    }

    let mut finish = choice
        .get("finish_reason")
        .and_then(Value::as_str)
        .map(openai_finish_reason)
        .unwrap_or(FinishReason::Stop);
    // 有的网关给工具调用但不给 finish_reason。空着会被当成"说完了"，
    // 上层就会把一条该继续的回复收尾。
    if !tool_calls.is_empty() && finish == FinishReason::Stop {
        finish = FinishReason::ToolCalls;
    }

    Ok(Completion {
        text,
        reasoning,
        tool_calls,
        finish,
        usage: value
            .get("usage")
            .filter(|usage| !usage.is_null())
            .map(openai_usage)
            .unwrap_or_default(),
        id: value.get("id").and_then(Value::as_str).map(str::to_string),
        model: value
            .get("model")
            .and_then(Value::as_str)
            .map(str::to_string),
    })
}

fn anthropic_complete(value: &Value) -> Result<Completion, StreamParseError> {
    let kind = ProviderKind::Anthropic;
    if value.get("type").and_then(Value::as_str) == Some("error") {
        let error = value.get("error").unwrap_or(value);
        return Err(shape(
            kind,
            format!("状态码 200，但 body 是 error 事件: {}", error_text(error)),
            value,
        ));
    }

    let blocks = value
        .get("content")
        .and_then(Value::as_array)
        .ok_or_else(|| shape(kind, "没有 content 数组", value))?;

    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    for (index, block) in blocks.iter().enumerate() {
        match block
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or_default()
        {
            "text" => {
                if let Some(part) = block.get("text").and_then(Value::as_str) {
                    text.push_str(part);
                }
            }
            "thinking" => {
                if let Some(part) = block.get("thinking").and_then(Value::as_str) {
                    reasoning.push_str(part);
                }
            }
            "tool_use" => tool_calls.push(ToolCall {
                index: index as u32,
                id: block.get("id").and_then(Value::as_str).map(str::to_string),
                name: block
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                // `input` 在这边是**对象**，不是字符串；统一成字符串给上层
                arguments: block
                    .get("input")
                    .map(|input| input.to_string())
                    .unwrap_or_else(|| "{}".into()),
            }),
            _ => {}
        }
    }

    Ok(Completion {
        text,
        reasoning,
        tool_calls,
        finish: value
            .get("stop_reason")
            .and_then(Value::as_str)
            .map(anthropic_finish_reason)
            .unwrap_or(FinishReason::Stop),
        usage: value
            .get("usage")
            .filter(|usage| !usage.is_null())
            .map(anthropic_usage)
            .unwrap_or_default(),
        id: value.get("id").and_then(Value::as_str).map(str::to_string),
        model: value
            .get("model")
            .and_then(Value::as_str)
            .map(str::to_string),
    })
}

fn responses_complete(value: &Value) -> Result<Completion, StreamParseError> {
    let kind = ProviderKind::OpenAiResponses;
    if let Some(error) = value.get("error").filter(|error| !error.is_null()) {
        return Err(shape(
            kind,
            format!("响应里带错误: {}", error_text(error)),
            value,
        ));
    }

    let output = value
        .get("output")
        .and_then(Value::as_array)
        .ok_or_else(|| shape(kind, "没有 output 数组", value))?;

    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    for (position, item) in output.iter().enumerate() {
        match item.get("type").and_then(Value::as_str).unwrap_or_default() {
            "message" => {
                if let Some(parts) = item.get("content").and_then(Value::as_array) {
                    for part in parts {
                        if part.get("type").and_then(Value::as_str) == Some("output_text") {
                            if let Some(chunk) = part.get("text").and_then(Value::as_str) {
                                text.push_str(chunk);
                            }
                        }
                    }
                }
            }
            // 思考有两处：`summary[]`（摘要）与 `content[]`（正文）。两处都收。
            "reasoning" => {
                if let Some(summary) = item.get("summary").and_then(Value::as_array) {
                    for part in summary {
                        if let Some(chunk) = part.get("text").and_then(Value::as_str) {
                            reasoning.push_str(chunk);
                        }
                    }
                }
                if let Some(parts) = item.get("content").and_then(Value::as_array) {
                    for part in parts {
                        if let Some(chunk) = part.get("text").and_then(Value::as_str) {
                            reasoning.push_str(chunk);
                        }
                    }
                }
            }
            "function_call" => tool_calls.push(ToolCall {
                index: item
                    .get("output_index")
                    .and_then(Value::as_u64)
                    .map(|index| index as u32)
                    .unwrap_or(position as u32),
                // 结果要按 call_id 关联，item 的 id 是另一个东西——这里优先 call_id
                id: item
                    .get("call_id")
                    .or_else(|| item.get("id"))
                    .and_then(Value::as_str)
                    .map(str::to_string),
                name: item
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                arguments: item
                    .get("arguments")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
            }),
            _ => {}
        }
    }

    let finish = match value.get("status").and_then(Value::as_str) {
        Some("incomplete") => match value
            .get("incomplete_details")
            .and_then(|details| details.get("reason"))
            .and_then(Value::as_str)
        {
            Some("max_output_tokens") => FinishReason::Length,
            Some("content_filter") => FinishReason::ContentFilter,
            Some(other) => FinishReason::Unknown(other.to_string()),
            None => FinishReason::Unknown("incomplete".into()),
        },
        _ if !tool_calls.is_empty() => FinishReason::ToolCalls,
        _ => FinishReason::Stop,
    };

    Ok(Completion {
        text,
        reasoning,
        tool_calls,
        finish,
        usage: value
            .get("usage")
            .filter(|usage| !usage.is_null())
            .map(responses_usage)
            .unwrap_or_default(),
        id: value.get("id").and_then(Value::as_str).map(str::to_string),
        model: value
            .get("model")
            .and_then(Value::as_str)
            .map(str::to_string),
    })
}

fn gemini_complete(value: &Value) -> Result<Completion, StreamParseError> {
    let kind = ProviderKind::Gemini;
    if let Some(reason) = value
        .get("promptFeedback")
        .and_then(|feedback| feedback.get("blockReason"))
        .and_then(Value::as_str)
    {
        return Err(shape(kind, format!("请求被内容策略拦下: {reason}"), value));
    }

    let candidate = value
        .get("candidates")
        .and_then(Value::as_array)
        .and_then(|candidates| candidates.first())
        .ok_or_else(|| shape(kind, "没有 candidates", value))?;

    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    if let Some(parts) = candidate
        .get("content")
        .and_then(|content| content.get("parts"))
        .and_then(Value::as_array)
    {
        for (index, part) in parts.iter().enumerate() {
            if let Some(chunk) = part.get("text").and_then(Value::as_str) {
                if part
                    .get("thought")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
                {
                    reasoning.push_str(chunk);
                } else {
                    text.push_str(chunk);
                }
            }
            if let Some(call) = part.get("functionCall") {
                tool_calls.push(ToolCall {
                    index: index as u32,
                    id: call.get("id").and_then(Value::as_str).map(str::to_string),
                    name: call
                        .get("name")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_string(),
                    arguments: call
                        .get("args")
                        .map(|args| args.to_string())
                        .unwrap_or_else(|| "{}".into()),
                });
            }
        }
    }

    let mut finish = candidate
        .get("finishReason")
        .and_then(Value::as_str)
        .map(gemini_finish_reason)
        .unwrap_or(FinishReason::Stop);
    if !tool_calls.is_empty() && finish == FinishReason::Stop {
        finish = FinishReason::ToolCalls;
    }

    Ok(Completion {
        text,
        reasoning,
        tool_calls,
        finish,
        usage: value
            .get("usageMetadata")
            .map(gemini_usage)
            .unwrap_or_default(),
        id: value
            .get("responseId")
            .and_then(Value::as_str)
            .map(str::to_string),
        model: value
            .get("modelVersion")
            .and_then(Value::as_str)
            .map(str::to_string),
    })
}

fn error_text(error: &Value) -> String {
    error
        .get("message")
        .and_then(Value::as_str)
        .or_else(|| error.as_str())
        .unwrap_or("上游没给说明")
        .to_string()
}

fn truncate(text: &str, limit: usize) -> String {
    if text.chars().count() <= limit {
        return text.to_string();
    }
    let head: String = text.chars().take(limit).collect();
    format!("{head}…")
}

fn provider_error(error: &Value, fallback_code: Option<&str>) -> StreamEvent {
    let message = error
        .get("message")
        .and_then(Value::as_str)
        .or_else(|| error.as_str())
        .unwrap_or("上游没有给错误信息")
        .to_string();
    let code = error
        .get("code")
        .or_else(|| error.get("type"))
        .map(|code| match code {
            Value::String(text) => text.clone(),
            other => other.to_string(),
        })
        .or_else(|| fallback_code.map(str::to_string));
    StreamEvent::ProviderError { code, message }
}

/// OpenAI 兼容的 `content`：正常是字符串，也有网关给 parts 数组。
fn delta_text(content: Option<&Value>) -> Option<String> {
    match content? {
        Value::String(text) => Some(text.clone()),
        Value::Array(parts) => {
            let mut out = String::new();
            for part in parts {
                match part {
                    Value::String(text) => out.push_str(text),
                    Value::Object(object) => {
                        if let Some(text) = object.get("text").and_then(Value::as_str) {
                            out.push_str(text);
                        }
                    }
                    _ => {}
                }
            }
            Some(out)
        }
        _ => None,
    }
}

fn openai_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "stop" | "end_turn" => FinishReason::Stop,
        "length" | "max_tokens" => FinishReason::Length,
        "tool_calls" | "function_call" => FinishReason::ToolCalls,
        "content_filter" => FinishReason::ContentFilter,
        other => FinishReason::Unknown(other.to_string()),
    }
}

fn anthropic_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "end_turn" | "stop_sequence" => FinishReason::Stop,
        "max_tokens" => FinishReason::Length,
        "tool_use" => FinishReason::ToolCalls,
        "refusal" => FinishReason::ContentFilter,
        // pause_turn：服务端工具没跑完，把响应原样接回去还能继续
        other => FinishReason::Unknown(other.to_string()),
    }
}

fn gemini_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "STOP" => FinishReason::Stop,
        "MAX_TOKENS" => FinishReason::Length,
        "SAFETY" | "PROHIBITED_CONTENT" | "RECITATION" | "BLOCKLIST" => FinishReason::ContentFilter,
        // CONTINUATION：Gemini 4 单次输出到顶了，要带 continuationToken 再来一发
        other => FinishReason::Unknown(other.to_string()),
    }
}

fn int_field(value: &Value, key: &str) -> u32 {
    value
        .get(key)
        .and_then(Value::as_u64)
        .map(|number| number.min(u32::MAX as u64) as u32)
        .unwrap_or(0)
}

fn openai_usage(usage: &Value) -> Usage {
    // 有的中转在 chat/completions 上回的是 Anthropic 风格字段
    // （cache_read_input_tokens / reasoning_tokens），实测见 docs/provider-runtime.md。
    let cached = usage
        .get("prompt_tokens_details")
        .map(|details| int_field(details, "cached_tokens"))
        .unwrap_or(0)
        .max(int_field(usage, "cache_read_input_tokens"));
    let reasoning = usage
        .get("completion_tokens_details")
        .map(|details| int_field(details, "reasoning_tokens"))
        .unwrap_or(0)
        .max(int_field(usage, "reasoning_tokens"));
    Usage {
        prompt_tokens: int_field(usage, "prompt_tokens"),
        completion_tokens: int_field(usage, "completion_tokens"),
        cached_tokens: cached,
        reasoning_tokens: reasoning,
    }
}

fn anthropic_usage(usage: &Value) -> Usage {
    let uncached = int_field(usage, "input_tokens");
    let cache_read = int_field(usage, "cache_read_input_tokens");
    let cache_creation = int_field(usage, "cache_creation_input_tokens");
    Usage {
        // Anthropic 的 input_tokens 不含缓存部分，归一成全量输入
        prompt_tokens: uncached
            .saturating_add(cache_read)
            .saturating_add(cache_creation),
        completion_tokens: int_field(usage, "output_tokens"),
        cached_tokens: cache_read,
        // thinking 的 token 算在 output_tokens 里，单独字段没给
        reasoning_tokens: 0,
    }
}

fn responses_usage(usage: &Value) -> Usage {
    Usage {
        prompt_tokens: int_field(usage, "input_tokens"),
        completion_tokens: int_field(usage, "output_tokens"),
        cached_tokens: usage
            .get("input_tokens_details")
            .map(|details| int_field(details, "cached_tokens"))
            .unwrap_or(0),
        reasoning_tokens: usage
            .get("output_tokens_details")
            .map(|details| int_field(details, "reasoning_tokens"))
            .unwrap_or(0),
    }
}

fn gemini_usage(usage: &Value) -> Usage {
    Usage {
        prompt_tokens: int_field(usage, "promptTokenCount"),
        completion_tokens: int_field(usage, "candidatesTokenCount"),
        cached_tokens: int_field(usage, "cachedContentTokenCount"),
        // 官方字段是 thoughtsTokenCount；kie.ai 这类网关写成 thinkingTokenCount，两个都收
        reasoning_tokens: int_field(usage, "thoughtsTokenCount")
            .max(int_field(usage, "thinkingTokenCount")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn frame(data: Value) -> SseFrame {
        SseFrame::new(data.to_string())
    }

    /// 喂完所有帧，再问一次"流结束了吗"。`end_of_stream` 只在最后调一次——
    /// 它一旦被调用就认为流结束了，中途调会把后面的帧全变成"断流之后的帧"。
    fn drain(parser: &mut StreamParser, frames: &[SseFrame]) -> Vec<StreamEvent> {
        let mut events = Vec::new();
        for frame in frames {
            events.extend(parser.push_frame(frame).unwrap());
        }
        events.extend(parser.end_of_stream());
        events
    }

    fn texts(events: &[StreamEvent]) -> String {
        events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::Text(text) => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    fn reasonings(events: &[StreamEvent]) -> String {
        events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::Reasoning(text) => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    // ---- OpenAI 兼容 ----

    #[test]
    fn openai_delta_carries_text_and_reasoning_separately() {
        // 真实抓到的帧（某中转端点，本机代理）
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"id":"chatcmpl-804992c8746b4ad0b22f6aaa","object":"chat.completion.chunk","created":1792504437,"model":"m-1","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"reasoning_content":"We","content":""}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"reasoning_content":" need answer in","content":""}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"content":"2","reasoning_content":null}}]}),
                ),
                frame(json!({"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]})),
                SseFrame::new("[DONE]"),
            ],
        );

        assert_eq!(reasonings(&events), "We need answer in");
        assert_eq!(texts(&events), "2");
        assert!(events.iter().any(
            |event| matches!(event, StreamEvent::Meta { model: Some(model), .. } if model == "m-1")
        ));
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Stop)));
        assert!(events.contains(&StreamEvent::Done));
        // 空 content 不该产出空 Text 事件
        assert!(!events.contains(&StreamEvent::Text(String::new())));
    }

    #[test]
    fn openai_tool_call_fragments_are_forwarded_with_index() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.md\"}"}}]}}]}),
                ),
                frame(json!({"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]})),
            ],
        );

        let calls: Vec<&StreamEvent> = events
            .iter()
            .filter(|event| matches!(event, StreamEvent::ToolCallDelta { .. }))
            .collect();
        assert_eq!(calls.len(), 3);
        match calls[0] {
            StreamEvent::ToolCallDelta {
                index, id, name, ..
            } => {
                assert_eq!(*index, 0);
                assert_eq!(id.as_deref(), Some("call_1"));
                assert_eq!(name.as_deref(), Some("read_file"));
            }
            other => panic!("{other:?}"),
        }
        // 参数是片段，拼起来才是 JSON —— 解析器不做拼接，那是上层的事
        let arguments: String = calls
            .iter()
            .map(|event| match event {
                StreamEvent::ToolCallDelta { arguments, .. } => arguments.clone(),
                _ => unreachable!(),
            })
            .collect();
        assert_eq!(arguments, "{\"path\":\"a.md\"}");
        assert!(events.contains(&StreamEvent::Finish(FinishReason::ToolCalls)));
    }

    #[test]
    fn openai_usage_is_read_from_the_final_chunk_including_anthropic_style_fields() {
        // 真实抓到的 usage（某端点）：prompt_tokens_details 是空对象，
        // 且带一个非标准的 points_consumed —— 缺字段不能当成 0 以外的意思。
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[frame(json!({
                "choices": [],
                "usage": {
                    "prompt_tokens": 31,
                    "completion_tokens": 8,
                    "total_tokens": 39,
                    "prompt_tokens_details": {},
                    "completion_tokens_details": {"reasoning_tokens": 8},
                    "points_consumed": 1
                }
            }))],
        );
        assert_eq!(
            parser.usage(),
            Usage {
                prompt_tokens: 31,
                completion_tokens: 8,
                cached_tokens: 0,
                reasoning_tokens: 8,
            }
        );
        assert!(events
            .iter()
            .any(|event| matches!(event, StreamEvent::Usage(_))));

        // Anthropic 风格：缓存字段用 Anthropic 的命名
        let style = openai_usage(&json!({
            "prompt_tokens": 100,
            "completion_tokens": 5,
            "cache_read_input_tokens": 90,
            "reasoning_tokens": 3
        }));
        assert_eq!(style.cached_tokens, 90);
        assert_eq!(style.reasoning_tokens, 3);
    }

    #[test]
    fn openai_reasoning_shaped_gateways_are_all_accepted() {
        for key in ["reasoning_content", "reasoning", "thinking"] {
            let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
            let mut delta = serde_json::Map::new();
            delta.insert(key.to_string(), json!("想一下"));
            let events = drain(
                &mut parser,
                &[frame(json!({"choices":[{"index":0,"delta":delta}]}))],
            );
            assert_eq!(reasonings(&events), "想一下", "key={key}");
        }
    }

    #[test]
    fn openai_stream_without_terminal_signal_is_marked_interrupted() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[frame(
                json!({"choices":[{"index":0,"delta":{"content":"说到一半"}}]}),
            )],
        );
        assert_eq!(texts(&events), "说到一半");
        assert!(matches!(events.last(), Some(StreamEvent::Interrupted(_))));
    }

    #[test]
    fn openai_error_chunk_becomes_a_provider_error_not_a_json_panic() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[frame(
                json!({"error":{"message":"model overloaded","code":"overloaded"}}),
            )],
        );
        assert!(matches!(
            events.first(),
            Some(StreamEvent::ProviderError { code: Some(code), message })
                if code == "overloaded" && message == "model overloaded"
        ));
    }

    // ---- Anthropic ----

    #[test]
    fn anthropic_lifecycle_reports_usage_from_both_ends() {
        // 事件顺序与字段取自官方文档的示例（platform.claude.com/docs/en/build-with-claude/streaming）
        let mut parser = StreamParser::new(ProviderKind::Anthropic);
        let events = drain(
            &mut parser,
            &[
                SseFrame::with_event(
                    "message_start",
                    json!({"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"usage":{"input_tokens":25,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":1}}}).to_string(),
                ),
                SseFrame::with_event("ping", json!({"type":"ping"}).to_string()),
                SseFrame::with_event(
                    "content_block_start",
                    json!({"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_delta",
                    json!({"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_delta",
                    json!({"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"让我想想"}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_stop",
                    json!({"type":"content_block_stop","index":0}).to_string(),
                ),
                SseFrame::with_event(
                    "message_delta",
                    json!({"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}).to_string(),
                ),
                SseFrame::with_event("message_stop", json!({"type":"message_stop"}).to_string()),
            ],
        );

        assert_eq!(texts(&events), "Hello");
        assert_eq!(reasonings(&events), "让我想想");
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Stop)));
        assert!(events.contains(&StreamEvent::Done));
        assert!(!events
            .iter()
            .any(|event| matches!(event, StreamEvent::Interrupted(_))));

        // input_tokens 不含缓存 → 归一成 25+10+100；output 取 max(1, 15)
        assert_eq!(
            parser.usage(),
            Usage {
                prompt_tokens: 135,
                completion_tokens: 15,
                cached_tokens: 100,
                reasoning_tokens: 0,
            }
        );
    }

    #[test]
    fn anthropic_tool_input_json_fragments_keep_their_block_index() {
        let mut parser = StreamParser::new(ProviderKind::Anthropic);
        let events = drain(
            &mut parser,
            &[
                SseFrame::with_event(
                    "content_block_start",
                    json!({"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_delta",
                    json!({"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"loc"}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_delta",
                    json!({"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ation\": \"New York\"}"}}).to_string(),
                ),
                SseFrame::with_event(
                    "message_delta",
                    json!({"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":342}}).to_string(),
                ),
                SseFrame::with_event("message_stop", json!({"type":"message_stop"}).to_string()),
            ],
        );

        let arguments: String = events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::ToolCallDelta { arguments, .. } => Some(arguments.as_str()),
                _ => None,
            })
            .collect();
        assert_eq!(arguments, "{\"location\": \"New York\"}");
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::ToolCallDelta { index: 1, name: Some(name), .. } if name == "get_weather"
        )));
        assert!(events.contains(&StreamEvent::Finish(FinishReason::ToolCalls)));
    }

    #[test]
    fn anthropic_mid_stream_error_event_is_surfaced() {
        let mut parser = StreamParser::new(ProviderKind::Anthropic);
        let events = parser
            .push_frame(&SseFrame::with_event(
                "error",
                json!({"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}})
                    .to_string(),
            ))
            .unwrap();
        assert!(matches!(
            events.first(),
            Some(StreamEvent::ProviderError { code: Some(code), .. }) if code == "overloaded_error"
        ));
    }

    // ---- OpenAI Responses ----

    #[test]
    fn responses_lifecycle_maps_text_reasoning_tools_and_usage() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiResponses);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"type":"response.created","response":{"id":"resp_1","model":"gpt-5.5"}}),
                ),
                frame(
                    json!({"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}),
                ),
                frame(
                    json!({"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"先想一下"}),
                ),
                frame(
                    json!({"type":"response.output_item.added","output_index":1,"item":{"type":"message","id":"msg_1"}}),
                ),
                frame(
                    json!({"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":"答案"}),
                ),
                frame(
                    json!({"type":"response.output_text.done","item_id":"msg_1","output_index":1,"text":"答案"}),
                ),
                frame(
                    json!({"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.5","usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":50},"output_tokens":200,"output_tokens_details":{"reasoning_tokens":150},"total_tokens":300}}}),
                ),
            ],
        );

        assert_eq!(reasonings(&events), "先想一下");
        assert_eq!(texts(&events), "答案");
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Stop)));
        assert!(events.contains(&StreamEvent::Done));
        assert_eq!(
            parser.usage(),
            Usage {
                prompt_tokens: 100,
                completion_tokens: 200,
                cached_tokens: 50,
                reasoning_tokens: 150,
            }
        );
    }

    #[test]
    fn responses_function_call_arguments_are_attributed_to_their_output_index() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiResponses);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_weather","arguments":""}}),
                ),
                frame(
                    json!({"type":"response.function_call_arguments.delta","item_id":"call_1","output_index":1,"delta":"{\"location\": \"San"}),
                ),
                frame(
                    json!({"type":"response.function_call_arguments.delta","item_id":"call_1","output_index":1,"delta":" Francisco\"}"}),
                ),
                frame(
                    json!({"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":10,"output_tokens":2}}}),
                ),
            ],
        );

        let arguments: String = events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::ToolCallDelta { arguments, .. } => Some(arguments.as_str()),
                _ => None,
            })
            .collect();
        assert_eq!(arguments, "{\"location\": \"San Francisco\"}");
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::ToolCallDelta { index: 1, name: Some(name), .. } if name == "get_weather"
        )));
    }

    #[test]
    fn responses_incomplete_is_length_not_a_clean_stop() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiResponses);
        let events = drain(
            &mut parser,
            &[frame(
                json!({"type":"response.incomplete","response":{"id":"resp_1","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":100,"output_tokens":4000}}}),
            )],
        );
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Length)));
        assert!(events.contains(&StreamEvent::Done));
    }

    #[test]
    fn responses_failure_is_reported_as_provider_error() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiResponses);
        let events = parser
            .push_frame(&frame(json!({"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}})))
            .unwrap();
        assert!(matches!(
            events.first(),
            Some(StreamEvent::ProviderError { code: Some(code), .. }) if code == "server_error"
        ));
    }

    // ---- Gemini ----

    #[test]
    fn gemini_parts_split_thoughts_from_answers() {
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"candidates":[{"content":{"role":"model","parts":[{"text":"心里想的","thought":true}]}}]}),
                ),
                frame(
                    json!({"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]}}]}),
                ),
                frame(
                    json!({"candidates":[{"content":{"role":"model","parts":[{"text":"! 有什么可以帮你"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":9,"thoughtsTokenCount":30,"cachedContentTokenCount":1,"totalTokenCount":41},"modelVersion":"gemini-3.8-flash"}),
                ),
            ],
        );

        assert_eq!(reasonings(&events), "心里想的");
        assert_eq!(texts(&events), "Hello! 有什么可以帮你");
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Stop)));
        assert_eq!(
            parser.usage(),
            Usage {
                prompt_tokens: 2,
                completion_tokens: 9,
                cached_tokens: 1,
                reasoning_tokens: 30,
            }
        );
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::Meta { model: Some(model), .. } if model == "gemini-3.8-flash"
        )));
    }

    #[test]
    fn gemini_safety_stop_is_content_filter() {
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = parser
            .push_frame(&frame(
                json!({"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}),
            ))
            .unwrap();
        assert!(events.contains(&StreamEvent::Finish(FinishReason::ContentFilter)));
    }

    #[test]
    fn gemini_blocked_prompt_reports_why_it_was_blocked() {
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = parser
            .push_frame(&frame(
                json!({"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}),
            ))
            .unwrap();
        assert!(matches!(
            events.first(),
            Some(StreamEvent::ProviderError { code: Some(code), message })
                if code == "prompt_blocked" && message.contains("PROHIBITED_CONTENT")
        ));
    }

    #[test]
    fn gemini_gateway_that_errors_with_http_200_is_not_silently_empty() {
        // kie.ai 这类网关：HTTP 200，body 是 {"code":..,"msg":..}
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = parser
            .push_frame(&frame(json!({"code":200,"msg":"model not found"})))
            .unwrap();
        assert!(matches!(
            events.first(),
            Some(StreamEvent::ProviderError { message, .. }) if message == "model not found"
        ));
    }

    #[test]
    fn gemini_json_array_chunks_are_accepted() {
        // 非 SSE 模式会一次给一个数组
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = parser
            .push_frame(&frame(json!([
                {"candidates":[{"content":{"parts":[{"text":"a"}]}}]},
                {"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}]}
            ])))
            .unwrap();
        assert_eq!(texts(&events), "ab");
        assert!(events.contains(&StreamEvent::Finish(FinishReason::Stop)));
    }

    #[test]
    fn gemini_function_call_args_come_in_as_one_json_object() {
        let mut parser = StreamParser::new(ProviderKind::Gemini);
        let events = parser
            .push_frame(&frame(json!({"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather_forecast","args":{"location":"Beijing"},"id":"gp737npz"}}]},"finishReason":"STOP"}]})))
            .unwrap();
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::ToolCallDelta { name: Some(name), arguments, .. }
                if name == "get_weather_forecast" && arguments.contains("Beijing")
        )));
    }

    #[test]
    fn streaming_tool_fragments_assemble_by_index() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let events = drain(
            &mut parser,
            &[
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"list_dir","arguments":"{}"}}]}}]}),
                ),
                frame(
                    json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.md\"}"}}]}}]}),
                ),
            ],
        );
        // 事件是分片（给 UI 用），拼好的在这里
        assert!(events
            .iter()
            .any(|event| matches!(event, StreamEvent::ToolCallDelta { .. })));

        let calls = parser.tool_calls();
        assert_eq!(calls.len(), 2, "两个 index 就是两个调用");
        assert_eq!(calls[0].name, "read_file");
        assert_eq!(calls[0].id.as_deref(), Some("call_1"));
        assert_eq!(calls[0].arguments, "{\"path\":\"a.md\"}");
        assert_eq!(calls[1].name, "list_dir");
        // 拼完才可能解析成 JSON
        assert_eq!(calls[0].arguments_json().unwrap()["path"], json!("a.md"));
    }

    #[test]
    fn anthropic_thinking_and_tool_blocks_keep_their_indexes() {
        let mut parser = StreamParser::new(ProviderKind::Anthropic);
        drain(
            &mut parser,
            &[
                SseFrame::with_event(
                    "content_block_start",
                    json!({"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"search","input":{}}}).to_string(),
                ),
                SseFrame::with_event(
                    "content_block_delta",
                    json!({"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"猫\"}"}}).to_string(),
                ),
                SseFrame::with_event(
                    "message_delta",
                    json!({"type":"message_delta","delta":{"stop_reason":"tool_use"}}).to_string(),
                ),
                SseFrame::with_event("message_stop", json!({"type":"message_stop"}).to_string()),
            ],
        );
        let calls = parser.tool_calls();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].index, 1);
        assert_eq!(calls[0].name, "search");
        assert_eq!(calls[0].arguments, "{\"q\":\"猫\"}");
    }

    // ---- 非流式 ----

    #[test]
    fn complete_openai_reads_message_content_and_tool_calls() {
        let value = json!({
            "id": "chatcmpl-1",
            "model": "DeepSeek-V4.1-Flash",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "喵喵",
                    "reasoning_content": "用户只想听两个字",
                    "tool_calls": [{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.md\"}"}}]
                },
                "finish_reason": "tool_calls"
            }],
            // 真实 usage（某端点）：prompt_tokens_details 是空对象
            "usage": {"prompt_tokens":31,"completion_tokens":8,"total_tokens":39,"prompt_tokens_details":{},"completion_tokens_details":{"reasoning_tokens":8}}
        });
        let completion = parse_complete(ProviderKind::OpenAiCompatible, &value).unwrap();
        assert_eq!(completion.text, "喵喵");
        assert_eq!(completion.reasoning, "用户只想听两个字");
        assert_eq!(completion.finish, FinishReason::ToolCalls);
        assert_eq!(completion.model.as_deref(), Some("DeepSeek-V4.1-Flash"));
        assert_eq!(completion.usage.reasoning_tokens, 8);
        assert_eq!(completion.tool_calls.len(), 1);
        assert_eq!(completion.tool_calls[0].name, "read_file");
    }

    #[test]
    fn complete_with_tools_but_no_finish_reason_is_not_treated_as_done() {
        // 有的网关给工具调用却不给 finish_reason；当成 stop 会让上层把该继续的回复收尾
        let value = json!({"choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","function":{"name":"x","arguments":"{}"}}]}}]});
        let completion = parse_complete(ProviderKind::OpenAiCompatible, &value).unwrap();
        assert_eq!(completion.finish, FinishReason::ToolCalls);
    }

    #[test]
    fn complete_anthropic_separates_text_thinking_and_tool_use() {
        let value = json!({
            "id": "msg_01",
            "type": "message",
            "role": "assistant",
            "model": "claude-opus-5-5",
            "content": [
                {"type": "thinking", "thinking": "先想一下"},
                {"type": "text", "text": "答案是"},
                {"type": "text", "text": "42"},
                {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"location": "Boston"}}
            ],
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 25, "cache_read_input_tokens": 100, "cache_creation_input_tokens": 10, "output_tokens": 15}
        });
        let completion = parse_complete(ProviderKind::Anthropic, &value).unwrap();
        assert_eq!(completion.text, "答案是42");
        assert_eq!(completion.reasoning, "先想一下");
        assert_eq!(completion.finish, FinishReason::ToolCalls);
        // input 是对象 → 统一成 JSON 字符串
        assert_eq!(
            completion.tool_calls[0].arguments,
            "{\"location\":\"Boston\"}"
        );
        assert_eq!(completion.tool_calls[0].id.as_deref(), Some("toolu_1"));
        // usage 归一：input 不含缓存
        assert_eq!(completion.usage.prompt_tokens, 135);
        assert_eq!(completion.usage.cached_tokens, 100);
    }

    #[test]
    fn complete_responses_walks_output_items() {
        let value = json!({
            "id": "resp_1",
            "status": "completed",
            "model": "gpt-5.5",
            "output": [
                {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"先想"}]},
                {"type":"message","id":"msg_1","content":[{"type":"output_text","text":"答案"}]},
                {"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"Paris\"}"}
            ],
            "usage": {"input_tokens": 100, "input_tokens_details": {"cached_tokens": 50}, "output_tokens": 200, "output_tokens_details": {"reasoning_tokens": 150}}
        });
        let completion = parse_complete(ProviderKind::OpenAiResponses, &value).unwrap();
        assert_eq!(completion.text, "答案");
        assert_eq!(completion.reasoning, "先想");
        assert_eq!(completion.finish, FinishReason::ToolCalls);
        assert_eq!(completion.tool_calls[0].id.as_deref(), Some("call_1"));
        assert_eq!(completion.usage.cached_tokens, 50);
    }

    #[test]
    fn complete_gemini_reads_parts_and_usage() {
        let value = json!({
            "candidates": [{
                "content": {"role":"model","parts":[{"text":"心里想的","thought":true},{"text":"你好"},{"functionCall":{"name":"get_weather","args":{"location":"Beijing"}}}]},
                "finishReason": "STOP"
            }],
            "usageMetadata": {"promptTokenCount":2,"candidatesTokenCount":9,"thoughtsTokenCount":30,"cachedContentTokenCount":1},
            "modelVersion": "gemini-3.8-flash",
            "responseId": "resp_x"
        });
        let completion = parse_complete(ProviderKind::Gemini, &value).unwrap();
        assert_eq!(completion.text, "你好");
        assert_eq!(completion.reasoning, "心里想的");
        assert_eq!(completion.tool_calls[0].name, "get_weather");
        assert_eq!(completion.usage.reasoning_tokens, 30);
        assert_eq!(completion.model.as_deref(), Some("gemini-3.8-flash"));
    }

    #[test]
    fn complete_reports_shape_problems_instead_of_returning_empty_text() {
        let empty = json!({"choices": []});
        let error = parse_complete(ProviderKind::OpenAiCompatible, &empty).unwrap_err();
        assert!(matches!(error, StreamParseError::Shape { .. }));
        assert!(error.to_string().contains("choices"));

        // 状态码 200 但 body 里是错误：不能当成"模型什么都没说"
        let errorful = json!({"error": {"message": "model overloaded"}});
        let error = parse_complete(ProviderKind::OpenAiCompatible, &errorful).unwrap_err();
        assert!(error.to_string().contains("overloaded"));

        let blocked = json!({"promptFeedback": {"blockReason": "SAFETY"}});
        let error = parse_complete(ProviderKind::Gemini, &blocked).unwrap_err();
        assert!(error.to_string().contains("SAFETY"));
    }

    #[test]
    fn tool_arguments_json_is_strict_but_tolerates_empty() {
        let mut call = ToolCall {
            name: "no_args".into(),
            ..ToolCall::default()
        };
        assert_eq!(call.arguments_json().unwrap(), json!({}));

        call.arguments = "{\"a\":1}".into();
        assert_eq!(call.arguments_json().unwrap()["a"], json!(1));

        // 半截 JSON 不能拿去执行
        call.arguments = "{\"a\":".into();
        let error = call.arguments_json().unwrap_err();
        assert!(error.contains("不是合法 JSON"), "{error}");
        assert!(error.contains("no_args"), "报错要带上工具名: {error}");
    }

    // ---- 通用 ----

    #[test]
    fn malformed_json_is_an_error_not_a_panic() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        let error = parser
            .push_frame(&SseFrame::new("<html>502 Bad Gateway</html>"))
            .unwrap_err();
        assert!(matches!(error, StreamParseError::Malformed { .. }));
        assert!(error.to_string().contains("502"));
    }

    #[test]
    fn end_of_stream_only_fires_once_and_only_when_unterminated() {
        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        parser
            .push_frame(&SseFrame::new("[DONE]"))
            .expect("done frame");
        assert!(parser.end_of_stream().is_empty());

        let mut parser = StreamParser::new(ProviderKind::OpenAiCompatible);
        assert_eq!(parser.end_of_stream().len(), 1);
        assert!(parser.end_of_stream().is_empty(), "只报一次");
    }

    #[test]
    fn usage_never_moves_backwards_across_resends() {
        let mut parser = StreamParser::new(ProviderKind::Anthropic);
        parser
            .push_frame(&SseFrame::with_event(
                "message_start",
                json!({"type":"message_start","message":{"usage":{"input_tokens":50,"output_tokens":0}}}).to_string(),
            ))
            .unwrap();
        parser
            .push_frame(&SseFrame::with_event(
                "message_delta",
                // 有的网关在 message_delta 里重发 input_tokens，还比第一次小 —— 不能回退
                json!({"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":40,"output_tokens":90}}).to_string(),
            ))
            .unwrap();
        assert_eq!(parser.usage().prompt_tokens, 50);
        assert_eq!(parser.usage().completion_tokens, 90);
    }
}
