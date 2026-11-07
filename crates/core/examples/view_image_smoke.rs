//! 冒烟：`View-Image` 加进来的图，**同一条回答里的下一次请求**带上了没有（D269）。
//!
//! ```bash
//! NMOI_SMOKE_BASE="<你的端点>/v1" NMOI_SMOKE_MODEL="<你的模型>" \
//!   NMOI_SMOKE_IMAGE=/path/to/a.jpg \
//!   cargo run -q -p nmoi-core --example view_image_smoke
//! ```
//!
//! 判据看的是**发出去的东西**，不是模型说了什么：包一层 transport 记下每次请求体，
//! 然后看第 2 次及以后的请求里有没有 `data:image/`。
//!
//! 为什么不靠模型的回答：端点是不是多模态、模型会不会描述图，都不是这条要验的事。
//! 这条只验"我们自己有没有把它发出去"——那是代码的事，能一眼看死。
//!
//! `NMOI_SMOKE_IMAGE` 不给就用一张内置的 1×1 PNG：图小到看不清内容，但足够验线。

use std::process::ExitCode;
use std::sync::{Arc, Mutex};

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
use nmoi_core::transport::{
    HttpRequest, HttpResponse, PlainHttpTransport, Transport, TransportError,
};
use nmoi_core::workspace::Workspace;

/// 1×1 的 PNG（红点）：够验"这一轮请求里有没有图"，不指望模型看出什么。
const TINY_PNG: &str =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

/// 记下每次请求体，再原样交给真的传输。
struct Recording {
    inner: PlainHttpTransport,
    seen: Arc<Mutex<Vec<String>>>,
}

impl Transport for Recording {
    fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
        self.seen
            .lock()
            .unwrap()
            .push(String::from_utf8_lossy(&request.body).to_string());
        self.inner.execute(request)
    }
}

fn main() -> ExitCode {
    let base = match std::env::var("NMOI_SMOKE_BASE") {
        Ok(base) if !base.trim().is_empty() => base,
        _ => {
            eprintln!("缺少 NMOI_SMOKE_BASE（仓库里不写端点地址）");
            return ExitCode::from(2);
        }
    };
    let model = match std::env::var("NMOI_SMOKE_MODEL") {
        Ok(model) if !model.trim().is_empty() => model,
        _ => {
            eprintln!("缺少 NMOI_SMOKE_MODEL（故意不给默认值）");
            return ExitCode::from(2);
        }
    };

    let root = std::env::temp_dir().join(format!("nmoi-image-smoke-{}", std::process::id()));
    let images = root.join("images");
    if let Err(error) = std::fs::create_dir_all(&images) {
        eprintln!("建目录失败: {error}");
        return ExitCode::FAILURE;
    }

    // 图：给了路径就用真的那张，没给就用内置的小 PNG。
    let (file, bytes) = match std::env::var("NMOI_SMOKE_IMAGE") {
        Ok(path) if !path.trim().is_empty() => {
            let bytes = match std::fs::read(&path) {
                Ok(bytes) => bytes,
                Err(error) => {
                    eprintln!("读不到 NMOI_SMOKE_IMAGE={path}: {error}");
                    return ExitCode::from(2);
                }
            };
            let name = std::path::Path::new(&path)
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_else(|| "image.png".to_string());
            (name, bytes)
        }
        _ => (
            "tiny.png".to_string(),
            nmoi_core::base64::decode(TINY_PNG).unwrap_or_default(),
        ),
    };
    if let Err(error) = std::fs::write(images.join(&file), bytes) {
        eprintln!("写图失败: {error}");
        return ExitCode::FAILURE;
    }

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
    let session_key = session.to_string();
    let history = vec![
        system_message(DEFAULT_SYSTEM, 0),
        Message::text(
            MessageId::new(session, 1),
            Role::User,
            SourceTag::History,
            format!(
                "先调 View-Image 把 /chat/images/{file} 加进上下文，\
                 然后在**同一条回答里**告诉我这张图里是什么。"
            ),
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

    let provider = ProviderConfig::new("smoke", ProviderKind::OpenAiCompatible, &base);
    let mut request = ChatRequest::new(provider, &model);
    if let Ok(key) = std::env::var("NMOI_SMOKE_KEY") {
        if !key.trim().is_empty() {
            request = request.api_key(key);
        }
    }

    let seen = Arc::new(Mutex::new(Vec::new()));
    let client = ChatClient::new(Recording {
        inner: PlainHttpTransport::default(),
        seen: seen.clone(),
    });
    // 上限 3 轮：这只是冒烟，不陪模型打转。真到顶了线上证据仍然有效。
    let config = TurnConfig {
        max_rounds: 3,
        ..TurnConfig::default()
    };
    let ctx = ToolContext::new(&policy, Some(&workspace))
        .with_images(Some(&images), 20)
        .with_call_session(&session_key);

    println!("→ {base} {model} 图 {}", images.join(&file).display());
    let report = match run_turn(
        &client,
        &request,
        &history,
        &registry,
        &ctx,
        &config,
        &NeverCancel,
        None,
    ) {
        Ok(report) => report,
        Err(error) => {
            eprintln!("失败: {error}");
            return ExitCode::FAILURE;
        }
    };

    let bodies = seen.lock().unwrap().clone();
    println!(
        "--- {} 轮 / {} 次工具调用（成功 {} / 失败 {}）/ 收尾 {}",
        report.rounds,
        report.tool_calls.len(),
        report.ok_count(),
        report.failed_count(),
        report.finished
    );
    for record in &report.tool_calls {
        println!("[tool] {} {}", record.name, record.arguments);
    }
    println!("[text] {}", report.text);

    let carried: Vec<usize> = bodies
        .iter()
        .enumerate()
        .filter(|(_, body)| body.contains("data:image/"))
        .map(|(index, _)| index)
        .collect();
    println!(
        "--- 一共发出 {} 次请求；带图的是第 {:?} 次（0 起）",
        bodies.len(),
        carried
    );

    let called = report
        .tool_calls
        .iter()
        .any(|record| record.name == "View-Image");
    if !called {
        println!("✗ 模型没调 View-Image：这一跑没验到东西（换个模型或换句话再说一遍）");
        return ExitCode::FAILURE;
    }
    if carried.is_empty() {
        println!("✗ 图从来没进过请求：同轮生效没做成");
        return ExitCode::FAILURE;
    }
    if carried.contains(&0) {
        println!("✗ 第 1 次请求就带图了：那是「还没看就先发了」，判据不对");
        return ExitCode::FAILURE;
    }
    println!("✓ View-Image 之后的请求带上了图（同一条回答里）");
    ExitCode::SUCCESS
}
