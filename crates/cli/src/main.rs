#![allow(non_snake_case)]
// 二进制名按产品名 NMoi-Agent 保留大小写，Cargo 会把 crate 名转成 NMoi_Agent 并报 lint。

//! NMoi-Agent 命令行诊断入口。
//!
//! stage 0 的 UI 还不存在，这个二进制承担两件事：
//! 1. CI 里跑真实的"打开 → 体检 → 备份"链路；
//! 2. 用户遇到"打不开"时，能拿到一份可粘贴的 JSON 报告。
//!
//! 退出码：0 正常 / 2 打不开 / 3 被语义校验拦下（需要用户决定）。

use std::path::PathBuf;
use std::process::ExitCode;

use nmoi_storage::{OpenError, OpenOutcome, Storage};
use serde_json::{json, Value};

const USAGE: &str = "\
NMoi-Agent 0.1.0 — 本地上下文客户端（stage 0：核心 + 存储）

用法:
  NMoi-Agent version
  NMoi-Agent init <db-path>
  NMoi-Agent doctor <db-path>
  NMoi-Agent backup <db-path> <dest-path>
";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.as_slice() {
        [cmd] if cmd == "version" || cmd == "--version" || cmd == "-V" => {
            println!("{} {}", nmoi_core::PRODUCT_NAME, nmoi_core::VERSION);
            ExitCode::SUCCESS
        }
        [cmd] if cmd == "help" || cmd == "--help" || cmd == "-h" => {
            print!("{USAGE}");
            ExitCode::SUCCESS
        }
        [cmd, path] if cmd == "init" => init(path),
        [cmd, path] if cmd == "doctor" => doctor(path),
        [cmd, path, dest] if cmd == "backup" => backup(path, dest),
        _ => {
            eprint!("{USAGE}");
            ExitCode::from(64)
        }
    }
}

fn init(path: &str) -> ExitCode {
    match Storage::open(path) {
        Ok(OpenOutcome::Ready(storage)) => {
            print_json(&json!({
                "ok": true,
                "path": storage.path().display().to_string(),
                "device_id": storage.device_id(),
                "schema_version": nmoi_storage::migrate::SCHEMA_VERSION,
            }));
            ExitCode::SUCCESS
        }
        Ok(OpenOutcome::Blocked(blocked)) => report_blocked(blocked.report()),
        Err(err) => report_error(&err),
    }
}

fn doctor(path: &str) -> ExitCode {
    let db = PathBuf::from(path);
    if !db.exists() {
        let report = json!({
            "ok": false,
            "path": path,
            "error": "文件不存在",
        });
        print_json(&report);
        return ExitCode::from(2);
    }

    match Storage::open(path) {
        Ok(OpenOutcome::Ready(storage)) => match storage.health() {
            Ok(health) => {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&health).unwrap_or_default()
                );
                if health.integrity_ok && !health.semantic.has_blocking() {
                    ExitCode::SUCCESS
                } else {
                    ExitCode::from(3)
                }
            }
            Err(err) => report_error(&err),
        },
        Ok(OpenOutcome::Blocked(blocked)) => {
            let report = json!({
                "ok": false,
                "path": path,
                "kind": "semantic_block",
                "summary": blocked.report().summary(),
                "issues": blocked.report().issues,
                "next": "确认后用 restore_defaults() 隔离坏记录并重跑校验",
            });
            print_json(&report);
            ExitCode::from(3)
        }
        Err(err) => report_error(&err),
    }
}

fn backup(path: &str, dest: &str) -> ExitCode {
    match Storage::open(path) {
        Ok(OpenOutcome::Ready(storage)) => match storage.backup(dest) {
            Ok(report) => {
                let payload = json!({
                    "ok": report.verified,
                    "path": report.path.display().to_string(),
                    "verified": report.verified,
                    "problems": report.problems,
                });
                print_json(&payload);
                if report.verified {
                    ExitCode::SUCCESS
                } else {
                    ExitCode::from(3)
                }
            }
            Err(err) => report_error(&err.into()),
        },
        Ok(OpenOutcome::Blocked(blocked)) => {
            eprintln!("库被语义校验拦下，先处理坏记录再备份");
            report_blocked(blocked.report())
        }
        Err(err) => report_error(&err),
    }
}

fn print_json(value: &Value) {
    println!(
        "{}",
        serde_json::to_string_pretty(value).unwrap_or_else(|_| "{}".into())
    );
}

fn report_blocked(report: &nmoi_storage::validate::SemanticReport) -> ExitCode {
    let payload = json!({
        "ok": false,
        "kind": "semantic_block",
        "summary": report.summary(),
        "issues": report.issues,
    });
    print_json(&payload);
    ExitCode::from(3)
}

fn report_error(err: &OpenError) -> ExitCode {
    let payload = json!({
        "ok": false,
        "kind": "open_error",
        "error": err.to_string(),
    });
    print_json(&payload);
    ExitCode::from(2)
}
