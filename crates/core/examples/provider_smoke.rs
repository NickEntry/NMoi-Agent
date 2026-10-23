//! 真机冒烟：拿真实端点跑一轮流式对话，顺手验证引擎接口。
//!
//! 这不是测试（项目约定：本地只 `cargo check`，测试在 CI 跑），而是一次**手动可复现**的
//! 端到端验证工具——CI 上没有可达的 LLM 端点，`docs/decisions.md` D16 说的就是这个。
//!
//! ```bash
//! # 本机代理（不需要 key）
//! NMOI_SMOKE_MODEL="relay-b/DeepSeek-V4.1-Flash" \
//!   cargo run -p nmoi-core --example provider_smoke
//!
//! # 云端端点
//! NMOI_SMOKE_BASE=https://api.openai.com/v1 \
//! NMOI_SMOKE_MODEL=gpt-5.5 NMOI_SMOKE_KEY=sk-... \
//!   cargo run -p nmoi-core --example provider_smoke
//! ```
//!
//! 环境变量：
//!
//! | 变量 | 默认 | 说明 |
//! |---|---|---|
//! | `NMOI_SMOKE_BASE` | `http://127.0.0.1:8790/v1` | 自带版本段（`/v1`、`/v1beta`） |
//! | `NMOI_SMOKE_MODEL` | 无，**必填** | 故意不给默认值：打到哪个模型必须是你选的 |
//! | `NMOI_SMOKE_KIND` | `openai_compatible` | `openai_compatible` / `openai_responses` / `anthropic` / `gemini` |
//! | `NMOI_SMOKE_KEY` | 空 | 留空则不发鉴权头（本地端点不需要） |
//! | `NMOI_SMOKE_PROMPT` | 一句话自我介绍 | 提问内容 |

use std::process::ExitCode;
use std::time::Instant;

use nmoi_core::engine::{Engine, MemoryEngine, UserInput};
use nmoi_core::provider::{ProviderConfig, ProviderKind};
use nmoi_core::runtime::{ChatClient, ChatRequest};
use nmoi_core::stream::StreamEvent;
use nmoi_core::transport::PlainHttpTransport;

fn kind_from_env() -> Result<ProviderKind, String> {
    match std::env::var("NMOI_SMOKE_KIND")
        .unwrap_or_else(|_| "openai_compatible".into())
        .as_str()
    {
        "openai_compatible" => Ok(ProviderKind::OpenAiCompatible),
        "openai_responses" => Ok(ProviderKind::OpenAiResponses),
        "anthropic" => Ok(ProviderKind::Anthropic),
        "gemini" => Ok(ProviderKind::Gemini),
        other => Err(format!("不认识的 NMOI_SMOKE_KIND: {other}")),
    }
}

fn main() -> ExitCode {
    let base =
        std::env::var("NMOI_SMOKE_BASE").unwrap_or_else(|_| "http://127.0.0.1:8790/v1".into());
    let model = match std::env::var("NMOI_SMOKE_MODEL") {
        Ok(model) if !model.trim().is_empty() => model,
        // 不给默认模型：这一步会花掉真实配额，得是你点名的那一个
        _ => {
            eprintln!("缺少 NMOI_SMOKE_MODEL（故意不给默认值：这一步会真的调用模型）");
            return ExitCode::from(2);
        }
    };
    let prompt =
        std::env::var("NMOI_SMOKE_PROMPT").unwrap_or_else(|_| "用一句话介绍你自己，别展开".into());

    let kind = match kind_from_env() {
        Ok(kind) => kind,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(2);
        }
    };

    let provider = ProviderConfig::new("smoke", kind, &base);
    // 思考关掉、上限给足：实测这类别名的思考能吃掉一万多 token，
    // 给 4000 会"思考吃完、正文为空"（见 context-experiments-report §1.6）
    let mut request = ChatRequest::new(provider, &model).max_tokens(65_536);
    if let Ok(key) = std::env::var("NMOI_SMOKE_KEY") {
        if !key.trim().is_empty() {
            request = request.api_key(key);
        }
    }

    println!("→ {} {} (kind={})", base, model, kind.as_str());

    let mut engine = MemoryEngine::new();
    let stream_id = match engine.submit(UserInput::new(prompt.clone())) {
        Ok(id) => id,
        Err(error) => {
            eprintln!("引擎拒绝提交: {error}");
            return ExitCode::FAILURE;
        }
    };

    // 提交之后才把历史装进请求：user 消息是 submit 落进去的，
    // 顺序反了就会发出一个空 messages 的请求（上游只会回一个看不出原因的 400）。
    request = request.messages(
        engine
            .history()
            .iter()
            .flat_map(|unit| unit.messages.iter().cloned())
            .collect(),
    );

    let client = ChatClient::new(PlainHttpTransport::default());
    let started = Instant::now();

    // 角色分支：五类角色（P3）里的标题总结，走完整条链
    if std::env::var("NMOI_SMOKE_MODE")
        .map(|mode| mode == "title")
        .unwrap_or(false)
    {
        use nmoi_core::roles::{
            run as run_role, sanitize_title, ModelRole, RoleCall, RoleConfig, RoleMeter, RolePrompt,
        };
        let config = RoleConfig::new(ModelRole::TitleSummary);
        let transcript = format!("user: {prompt}\nassistant: 喵喵");
        let run = run_role(
            &client,
            RoleCall::new(
                &config,
                &request.provider,
                &request.model,
                RolePrompt::title(&transcript),
            ),
        );
        println!("[role] {} {}", run.role.label(), run.outcome.describe());
        if let Some(text) = run.outcome.text() {
            println!("[raw]   {text:?}");
            println!("[title] {:?}", sanitize_title(text));
        }
        println!(
            "[usage] prompt={} completion={} reasoning={}",
            run.usage.prompt_tokens, run.usage.completion_tokens, run.usage.reasoning_tokens
        );
        let mut meter = RoleMeter::new();
        meter.record_run(&run);
        println!(
            "[meter] 独立账本 {} 条，合计 {} token（不进会话预算）",
            meter.len(),
            meter.total().total()
        );
        return if run.outcome.is_ok() {
            ExitCode::SUCCESS
        } else {
            ExitCode::FAILURE
        };
    }

    // 非流式分支：只验请求构造 + 整段解析（P3 的五类角色走的就是这条路）
    if std::env::var("NMOI_SMOKE_MODE")
        .map(|mode| mode == "complete")
        .unwrap_or(false)
    {
        let whole = request.clone().without_stream();
        match client.complete(&whole) {
            Ok(completion) => {
                println!(
                    "--- {:.2}s 正文 {} 字 思考 {} 字",
                    started.elapsed().as_secs_f64(),
                    completion.text.chars().count(),
                    completion.reasoning.chars().count()
                );
                println!("[text] {}", completion.text);
                if !completion.reasoning.is_empty() {
                    println!("[reasoning] {}", completion.reasoning);
                }
                println!(
                    "[usage] prompt={} completion={} cached={} reasoning={}",
                    completion.usage.prompt_tokens,
                    completion.usage.completion_tokens,
                    completion.usage.cached_tokens,
                    completion.usage.reasoning_tokens
                );
                println!("[finish] {}", completion.finish.as_str());
                println!("[model] {:?} / id {:?}", completion.model, completion.id);
                if !completion.tool_calls.is_empty() {
                    println!("[tool_calls] {}", completion.tool_calls.len());
                }

                let _ = engine.push_delta(&completion.text);
                if !completion.reasoning.is_empty() {
                    let _ = engine.push_reasoning(&completion.reasoning);
                }
                return match engine.finish_turn(completion.usage) {
                    Ok(id) => {
                        println!(
                            "已落库: assistant 消息 {id}，历史 {} 个原子单元",
                            engine.history().len()
                        );
                        ExitCode::SUCCESS
                    }
                    Err(error) => {
                        eprintln!("落库失败: {error}");
                        ExitCode::FAILURE
                    }
                };
            }
            Err(error) => {
                eprintln!("非流式请求失败: {error}");
                return ExitCode::FAILURE;
            }
        }
    }

    let mut stream = match client.chat(&request) {
        Ok(stream) => stream,
        Err(error) => {
            eprintln!("请求失败: {error}");
            if let nmoi_core::runtime::ChatError::Http(body) = &error {
                eprintln!("  状态 {} / code {:?}", body.status, body.code);
                eprintln!("  超窗线索: {:?}", body.overflow);
                eprintln!("  原文: {}", body.raw);
            }
            return ExitCode::FAILURE;
        }
    };

    let mut first_token_at = None;
    let mut text_events = 0u32;
    let mut reasoning_events = 0u32;
    let mut interrupted = None;

    loop {
        match stream.next_event() {
            Ok(Some(StreamEvent::Text(text))) => {
                if first_token_at.is_none() {
                    first_token_at = Some(started.elapsed());
                }
                text_events += 1;
                print!("{text}");
                let _ = engine.push_delta(&text);
            }
            Ok(Some(StreamEvent::Reasoning(text))) => {
                reasoning_events += 1;
                let _ = engine.push_reasoning(&text);
            }
            Ok(Some(StreamEvent::Meta { id, model })) => {
                println!("\n[meta] id={id:?} model={model:?}");
            }
            Ok(Some(StreamEvent::ToolCallDelta { index, name, .. })) => {
                println!("\n[tool] index={index} name={name:?}");
            }
            Ok(Some(StreamEvent::Usage(usage))) => {
                println!(
                    "\n[usage] prompt={} completion={} cached={} reasoning={}",
                    usage.prompt_tokens,
                    usage.completion_tokens,
                    usage.cached_tokens,
                    usage.reasoning_tokens
                );
            }
            Ok(Some(StreamEvent::Finish(reason))) => {
                println!("\n[finish] {}", reason.as_str());
            }
            Ok(Some(StreamEvent::Done)) => {
                println!("\n[done]");
                break;
            }
            Ok(Some(StreamEvent::Interrupted(detail))) => {
                interrupted = Some(detail.clone());
                println!("\n[interrupted] {detail}（这一轮不算完整回复，照 D21 要标 incomplete）");
                break;
            }
            Ok(Some(StreamEvent::ProviderError { code, message })) => {
                println!("\n[上游错误] {code:?} {message}");
            }
            Ok(None) => break,
            Err(error) => {
                eprintln!("\n流中断: {error}");
                interrupted = Some(error.to_string());
                break;
            }
        }
    }

    let usage = stream.usage();
    println!(
        "--- {:.2}s 首字 {:?} 正文 {} 段 思考 {} 段",
        started.elapsed().as_secs_f64(),
        first_token_at,
        text_events,
        reasoning_events
    );

    if interrupted.is_some() {
        // 断流：丢弃本轮，别把半句话当成完整回复写进历史
        let _ = engine.abort_turn();
        return ExitCode::FAILURE;
    }

    match engine.finish_turn(usage) {
        Ok(id) => {
            println!(
                "已落库: assistant 消息 {id}，历史 {} 个原子单元，用量 {}",
                engine.history().len(),
                engine.used_tokens()
            );
            println!("（stream_id={stream_id}）");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("落库失败: {error}");
            ExitCode::FAILURE
        }
    }
}
