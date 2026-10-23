//! 真机冒烟：让模型真的用工具读写一个临时工作区（P7）。
//!
//! ```bash
//! NMOI_SMOKE_MODEL="relay-b/DeepSeek-V4.1-Flash" \
//!   cargo run -q -p nmoi-core --example tool_smoke
//! ```
//!
//! 环境变量同 `provider_smoke`：`NMOI_SMOKE_BASE` / `NMOI_SMOKE_MODEL`（必填）/
//! `NMOI_SMOKE_KIND` / `NMOI_SMOKE_KEY`。
//!
//! 流程是：读 → 改 → 再读确证。三步都能在磁盘上核对，所以这不只是"模型说了什么"，
//! 而是"文件真的变了没有"。

use std::process::ExitCode;

use nmoi_core::agent::{
    ensure_non_empty, run_turn, system_message, NeverCancel, TurnConfig, DEFAULT_SYSTEM,
};
use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{Message, Role};
use nmoi_core::policy::{AssistantPolicy, WorkspaceBinding};
use nmoi_core::provider::{ProviderConfig, ProviderKind};
use nmoi_core::runtime::{ChatClient, ChatRequest};
use nmoi_core::source::SourceTag;
use nmoi_core::tools::{ToolContext, ToolRegistry};
use nmoi_core::transport::PlainHttpTransport;
use nmoi_core::workspace::Workspace;

fn kind_from_env() -> Result<ProviderKind, String> {
    match std::env::var("NMOI_SMOKE_KIND")
        .unwrap_or_else(|_| "openai_compatible".into())
        .as_str()
    {
        "openai_compatible" => Ok(ProviderKind::OpenAiCompatible),
        "openai_responses" => Ok(ProviderKind::OpenAiResponses),
        "anthropic" => Ok(ProviderKind::Anthropic),
        other => Err(format!("不认识的 NMOI_SMOKE_KIND: {other}")),
    }
}

fn main() -> ExitCode {
    let base =
        std::env::var("NMOI_SMOKE_BASE").unwrap_or_else(|_| "http://127.0.0.1:8790/v1".into());
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

    // 临时工作区：跑完不删，路径打出来方便人工核对
    let root = std::env::temp_dir().join(format!("nmoi-tool-smoke-{}", std::process::id()));
    if let Err(error) = std::fs::create_dir_all(&root) {
        eprintln!("建工作区失败: {error}");
        return ExitCode::FAILURE;
    }
    let path = root.join("a.txt");
    let _ = std::fs::write(&path, "第一行\n第二行\n第三行\n");

    let workspace = match Workspace::open(&root) {
        Ok(workspace) => workspace,
        Err(error) => {
            eprintln!("打开工作区失败: {error}");
            return ExitCode::FAILURE;
        }
    };
    let policy =
        AssistantPolicy::locked_down().with_binding(WorkspaceBinding::read_write("smoke-ws"));
    let registry = ToolRegistry::workspace_defaults();

    let session = SessionId::new();
    let history = vec![
        system_message(DEFAULT_SYSTEM, 0),
        Message::text(
            MessageId::new(session, 1),
            Role::User,
            SourceTag::History,
            "先读 a.txt，确认第二行的内容；然后把第二行改成「改过了」，再用读回来的方式确证改成功；\
             最后用一句话汇报，并把你实际调用过的工具名列出来。",
            60,
            0,
            LogicalClock::new(1),
        ),
    ];
    let history = match ensure_non_empty(&history) {
        Ok(history) => history,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };

    let provider = ProviderConfig::new("smoke", kind, &base);
    let mut request = ChatRequest::new(provider, &model);
    if let Ok(key) = std::env::var("NMOI_SMOKE_KEY") {
        if !key.trim().is_empty() {
            request = request.api_key(key);
        }
    }

    println!("→ {base} {model} 工作区 {}", root.display());
    let client = ChatClient::new(PlainHttpTransport::default());
    let ctx = ToolContext::new(&policy, Some(&workspace));
    let started = std::time::Instant::now();
    let report = match run_turn(
        &client,
        &request,
        &history,
        &registry,
        &ctx,
        &TurnConfig::default(),
        &NeverCancel,
        // 真端点冒烟走默认：不重试，出问题就把错误原样打出来。
        None,
    ) {
        Ok(report) => report,
        Err(error) => {
            eprintln!("失败: {error}");
            return ExitCode::FAILURE;
        }
    };

    println!(
        "--- {:.2}s 往返 {} 轮 / 工具调用 {} 次（成功 {} / 失败 {}）/ 收尾 {}",
        started.elapsed().as_secs_f64(),
        report.rounds,
        report.tool_calls.len(),
        report.ok_count(),
        report.failed_count(),
        report.finished
    );
    for record in &report.tool_calls {
        println!("[tool] {} {}", record.name, record.arguments);
        let first = record
            .outcome
            .lines()
            .next()
            .unwrap_or_default()
            .chars()
            .take(80)
            .collect::<String>();
        println!(
            "       → {} {}",
            if record.ok { "ok" } else { "fail" },
            first
        );
    }
    println!("[text] {}", report.text);

    let after = std::fs::read_to_string(&path).unwrap_or_default();
    println!("[disk] {:?}", after);
    if after.contains("改过了") {
        println!("✓ 文件确实被改了（这是磁盘上的事实，不是模型的话）");
        ExitCode::SUCCESS
    } else {
        println!("✗ 文件没变——模型说了什么不重要，磁盘说了算");
        ExitCode::FAILURE
    }
}
