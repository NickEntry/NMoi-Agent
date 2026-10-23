//! 文本面的 C ABI：脱敏 + token 估算。
//!
//! 这两个是纯函数，不碰盘不碰网，适合在 UI 的输入框上"边打边算"。
//!
//! # 脱敏只报类型与长度
//!
//! `redact` 的命中记录里有 `kind` 和 `matched_len`，**不含原文片段**。
//! 如果这里把命中的原文回显出来，日志/崩溃上报就会变成泄漏源——那正好是脱敏要防的事。

use std::ffi::c_char;

use nmoi_core::redact::{redact, RedactionKind};
use serde_json::json;

use crate::json::{read_str, run_json};

/// 稳定词表。**不用 `RedactionKind::placeholder()`**：那个是给文本用的
/// `[REDACTED:api_key]`，当字段值会误导绑定层以为要照着解析。
fn kind_name(kind: RedactionKind) -> &'static str {
    match kind {
        RedactionKind::GithubToken => "github_token",
        RedactionKind::ApiKey => "api_key",
        RedactionKind::AwsAccessKeyId => "aws_access_key_id",
        RedactionKind::PrivateKeyBlock => "private_key",
        RedactionKind::UrlCredentials => "url_credentials",
        RedactionKind::Jwt => "jwt",
    }
}

/// 写入时刻脱敏（6 类凭据）。
///
/// 出参：`{"text":"…","clean":true,"hits":[{"kind":"api_key","matched_len":35}]}`
#[no_mangle]
pub extern "C" fn nmoi_redact_json(text: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(text, "text")?;
        let result = redact(text);
        Ok(json!({
            "text": result.text,
            "clean": result.is_clean(),
            "hits": result
                .hits
                .iter()
                .map(|hit| json!({
                    "kind": kind_name(hit.kind),
                    "matched_len": hit.matched_len,
                }))
                .collect::<Vec<_>>(),
        }))
    })
}

/// 粗估 token（不联网、不分词器）。同一段文本结果稳定，适合做预算显示。
#[no_mangle]
pub extern "C" fn nmoi_estimate_tokens_json(text: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(text, "text")?;
        Ok(json!({
            "tokens": nmoi_core::estimate_tokens(text),
            "chars": text.chars().count(),
            "bytes": text.len(),
        }))
    })
}
