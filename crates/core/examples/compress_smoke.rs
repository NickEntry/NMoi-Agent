//! 真机冒烟：拿真实端点跑一整轮压缩（脚手架 → 多步草稿 → 校验 → 提交）。
//!
//! 这不是测试，是一次可复现的端到端验证——CI 上没有可达的 LLM 端点。
//!
//! ```bash
//! NMOI_SMOKE_BASE="<你的端点>/v1" NMOI_SMOKE_MODEL="<你的模型>" \
//!   cargo run -q -p nmoi-core --example compress_smoke
//! ```
//!
//! 环境变量：`NMOI_SMOKE_BASE`（必填）、`NMOI_SMOKE_MODEL`（必填）、
//! `NMOI_SMOKE_KIND`、`NMOI_SMOKE_KEY`、`NMOI_SMOKE_KEEP`（保留多少 token 在窗口里，默认 300）。

use std::process::ExitCode;
use std::time::Instant;

use nmoi_core::archive::{ArchiveStore, MemoryArchive};
use nmoi_core::clock::LogicalClock;
use nmoi_core::compress::runner::{drive, LoopConfig};
use nmoi_core::compress::{CompressionJob, CompressionLimits, JobState};
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::provider::{ProviderConfig, ProviderKind};
use nmoi_core::runtime::{ChatClient, ChatRequest};
use nmoi_core::source::SourceTag;
use nmoi_core::transport::{
    HttpRequest, HttpResponse, PlainHttpTransport, Transport, TransportError,
};

/// 把发出去的请求体裁到文件里，方便用 curl 复现同一条请求。
/// `NMOI_SMOKE_DUMP=/tmp/req.json` 打开。
struct Dump<T: Transport> {
    inner: T,
    path: Option<String>,
}

impl<T: Transport> Transport for Dump<T> {
    fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
        if let Some(path) = &self.path {
            let _ = std::fs::write(path, &request.body);
        }
        self.inner.execute(request)
    }
}

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

/// 一段有"硬事实"的假历史：路径、命令、错误码、未完成项都有。
/// 压缩摘要要是把这些丢了，覆盖率校验就该拦住——这是这条冒烟真正想验的事。
fn history(session: SessionId) -> Vec<AtomicUnit> {
    let turns: [(Role, &str); 8] = [
        (
            Role::User,
            "帮我看看 crates/core/src/transport.rs 里 Host 头的事，端口不是 80 的时候 IPv6 会拼坏。",
        ),
        (
            Role::Assistant,
            "定位到了：authority_for_host_header 在非默认端口那条分支直接拼 host:port，IPv6 要带方括号。",
        ),
        (
            Role::User,
            "改完跑 cargo test -p nmoi-core --lib -- transport:: 看一下。",
        ),
        (
            Role::Assistant,
            "跑过了，一开始挂了两个：E0282 类型推断还有一条 Host 头没加括号。改完 12 项全过。",
        ),
        (
            Role::User,
            "顺便把 execute 的顺序也改一下：现在是先 connect 再 build_head，头部校验永远走不到。",
        ),
        (
            Role::Assistant,
            "已改成先校验头部再开 socket。crates/core/src/transport.rs 里 execute 那条路径现在会先返回 InvalidHeader。",
        ),
        (
            Role::User,
            "还有一件事没做完：CompressionJob 的启动收口还没写，进程被杀之后没有 TurnCompleted 的轮次要标 incomplete。",
        ),
        (
            Role::Assistant,
            "记下了，这一项留在计划 P10 里。下一步先跑压缩，再补启动收口。",
        ),
    ];
    turns
        .iter()
        .enumerate()
        .map(|(index, (role, text))| {
            let seq = index as u64 + 1;
            let message = Message::text(
                MessageId::new(session, seq),
                *role,
                SourceTag::History,
                *text,
                600,
                0,
                LogicalClock::new(seq),
            );
            AtomicUnit::turn(message).unwrap()
        })
        .collect()
}

fn main() -> ExitCode {
    // 端点**不写死在仓库里**：谁要打哪个地址由调用时给（带 /v1 这种版本段）。
    let base = match std::env::var("NMOI_SMOKE_BASE") {
        Ok(base) if !base.trim().is_empty() => base,
        _ => {
            eprintln!(
                "缺少 NMOI_SMOKE_BASE（仓库里不写端点地址：填你实际要打的那个，带 /v1 这类版本段）"
            );
            return ExitCode::from(2);
        }
    };
    let model = match std::env::var("NMOI_SMOKE_MODEL") {
        Ok(model) if !model.trim().is_empty() => model,
        _ => {
            eprintln!("缺少 NMOI_SMOKE_MODEL（故意不给默认值：这一步会真的调用模型）");
            return ExitCode::from(2);
        }
    };
    let kind = match kind_from_env() {
        Ok(kind) => kind,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(2);
        }
    };
    let keep: u32 = std::env::var("NMOI_SMOKE_KEEP")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(300);

    let session = SessionId::new();
    let units = history(session);
    let mut job =
        match CompressionJob::start(session, &units, keep, 0, CompressionLimits::default()) {
            Ok(job) => job,
            Err(error) => {
                eprintln!("建任务失败: {error}");
                return ExitCode::FAILURE;
            }
        };
    println!(
        "→ {base} {model} (kind={}) 切点 seq={} 线上 {} 单元",
        kind.as_str(),
        job.cut_after_seq(),
        job.above().len()
    );

    let provider = ProviderConfig::new("smoke", kind, &base);
    let mut request = ChatRequest::new(provider, &model);
    if let Ok(key) = std::env::var("NMOI_SMOKE_KEY") {
        if !key.trim().is_empty() {
            request = request.api_key(key);
        }
    }

    let transport = Dump {
        inner: PlainHttpTransport::default(),
        path: std::env::var("NMOI_SMOKE_DUMP").ok(),
    };
    let client = ChatClient::new(transport);
    let mut archive = MemoryArchive::new();
    let started = Instant::now();

    let report = match drive(
        &client,
        &request,
        &mut job,
        &mut archive,
        &LoopConfig::default(),
        nmoi_core::engine::now_ms(),
    ) {
        Ok(report) => report,
        Err(error) => {
            eprintln!("压缩失败: {error}");
            println!(
                "[state] {:?} 退回 {} 次 草稿 {} 字",
                job.state(),
                job.rounds(),
                job.draft().len_chars()
            );
            return ExitCode::FAILURE;
        }
    };

    println!(
        "--- {:.2}s 往返 {} 次 / 工具调用 {} 次 / 覆盖率退回 {} 次 / 结束原因 {:?}",
        started.elapsed().as_secs_f64(),
        report.exchanges,
        report.tool_calls,
        report.rejections,
        report.finish
    );
    if !report.errors.is_empty() {
        println!("[errors] {} 条", report.errors.len());
        for line in report.errors.iter().take(8) {
            println!("  - {line}");
        }
    }

    match report.outcome {
        Some(outcome) => {
            println!(
                "[state] {:?}（{}）",
                job.state(),
                if outcome.forced {
                    "兜底"
                } else {
                    "模型提交"
                }
            );
            println!(
                "[divider] kind={} after_seq={} note={:?}",
                outcome.divider.kind.as_str(),
                outcome.divider.after_seq,
                outcome.divider.note
            );
            println!(
                "[archive] {} 条消息已归档，{} token；摘要 {} token",
                outcome.archived.len(),
                outcome.archived_tokens,
                outcome.trace.draft_tokens
            );
            println!("[coverage] {}", outcome.coverage.summary());
            println!("[summary]\n{}", outcome.summary);
            println!(
                "[draft] {} 字 / {} 字节 / {} 次操作",
                outcome.trace.draft_chars,
                outcome.trace.draft_bytes,
                outcome.trace.ops.len()
            );
            if let Ok(stat) = archive.stat() {
                println!(
                    "[archive stat] entries={} tokens={}",
                    stat.entries, stat.total_tokens
                );
            }
            ExitCode::SUCCESS
        }
        None => {
            println!("[state] {:?} —— 模型没交，走确定性兜底", job.state());
            match job.commit_fallback(&mut archive, nmoi_core::engine::now_ms()) {
                Ok(outcome) => {
                    assert_eq!(job.state(), JobState::Forced);
                    println!("[fallback]\n{}", outcome.summary);
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    eprintln!("兜底也失败了: {error}");
                    ExitCode::FAILURE
                }
            }
        }
    }
}
