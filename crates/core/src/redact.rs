//! 写入时刻脱敏（设计文档 §5.6）。
//!
//! 归档层是新的泄露面：工具输出会混入凭据，一旦进归档就同时存在于上下文、磁盘、
//! 索引、undo 日志与导出包。事后清理一定漏，所以脱敏必须在写入时刻做。
//!
//! 导出时再扫一次（`contains_secret`），默认剔除归档原文，只留指针与描述。

use std::sync::OnceLock;

use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RedactionKind {
    GithubToken,
    ApiKey,
    AwsAccessKeyId,
    PrivateKeyBlock,
    UrlCredentials,
    Jwt,
}

impl RedactionKind {
    pub fn placeholder(self) -> &'static str {
        match self {
            RedactionKind::GithubToken => "[REDACTED:github_token]",
            RedactionKind::ApiKey => "[REDACTED:api_key]",
            RedactionKind::AwsAccessKeyId => "[REDACTED:aws_access_key_id]",
            RedactionKind::PrivateKeyBlock => "[REDACTED:private_key]",
            RedactionKind::UrlCredentials => "[REDACTED:url_credentials]",
            RedactionKind::Jwt => "[REDACTED:jwt]",
        }
    }
}

/// 一次脱敏的结果。`hits` 只记录类型与命中长度，不保留原文片段。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Redaction {
    pub text: String,
    pub hits: Vec<RedactionHit>,
}

impl Redaction {
    pub fn is_clean(&self) -> bool {
        self.hits.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RedactionHit {
    pub kind: RedactionKind,
    pub matched_len: usize,
}

struct Patterns {
    github: Regex,
    api_key: Regex,
    aws: Regex,
    pem: Regex,
    url_creds: Regex,
    jwt: Regex,
}

fn patterns() -> &'static Patterns {
    static PATTERNS: OnceLock<Patterns> = OnceLock::new();
    PATTERNS.get_or_init(|| Patterns {
        github: Regex::new(r"ghp_[A-Za-z0-9]{20,}").expect("github token regex"),
        api_key: Regex::new(r"sk-[A-Za-z0-9_\-]{16,}").expect("api key regex"),
        aws: Regex::new(r"AKIA[0-9A-Z]{16}").expect("aws key regex"),
        pem: Regex::new(
            r"(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        )
        .expect("pem regex"),
        url_creds: Regex::new(r"([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/\s:@]+:[^/\s@]*@")
            .expect("url credentials regex"),
        jwt: Regex::new(r"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}")
            .expect("jwt regex"),
    })
}

/// 脱敏。按固定顺序逐类替换，返回替换后的文本与命中记录。
pub fn redact(input: &str) -> Redaction {
    let patterns = patterns();
    let mut text = input.to_string();
    let mut hits = Vec::new();

    // URL 凭据先处理：它的 userinfo 里可能又含 token，先摘掉整段更稳。
    let mut hit_len = 0;
    text = patterns
        .url_creds
        .replace_all(&text, |caps: &regex::Captures<'_>| {
            hit_len += caps.get(0).map(|m| m.as_str().len()).unwrap_or(0);
            format!("{}[REDACTED:url_credentials]@", &caps[1])
        })
        .into_owned();
    if hit_len > 0 {
        hits.push(RedactionHit {
            kind: RedactionKind::UrlCredentials,
            matched_len: hit_len,
        });
    }

    for (kind, regex) in [
        (RedactionKind::PrivateKeyBlock, &patterns.pem),
        (RedactionKind::GithubToken, &patterns.github),
        (RedactionKind::AwsAccessKeyId, &patterns.aws),
        (RedactionKind::ApiKey, &patterns.api_key),
        (RedactionKind::Jwt, &patterns.jwt),
    ] {
        let mut matched = 0usize;
        let replaced = regex.replace_all(&text, |caps: &regex::Captures<'_>| {
            matched += caps.get(0).map(|m| m.as_str().len()).unwrap_or(0);
            kind.placeholder().to_string()
        });
        if matched > 0 {
            hits.push(RedactionHit {
                kind,
                matched_len: matched,
            });
            text = replaced.into_owned();
        }
    }

    Redaction { text, hits }
}

/// 导出前的二次扫描：文本里是否还残留凭据特征。
pub fn contains_secret(text: &str) -> bool {
    let patterns = patterns();
    patterns.github.is_match(text)
        || patterns.api_key.is_match(text)
        || patterns.aws.is_match(text)
        || patterns.pem.is_match(text)
        || patterns.url_creds.is_match(text)
        || patterns.jwt.is_match(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redacts_github_token() {
        let result = redact("Authorization: token ghp_0123456789abcdefghijABCDEFGHIJ");
        assert!(result.text.contains("[REDACTED:github_token]"));
        assert!(!result.text.contains("ghp_"));
        assert_eq!(result.hits[0].kind, RedactionKind::GithubToken);
    }

    #[test]
    fn redacts_api_key_and_aws() {
        let result = redact("key=sk-abcdefghijklmnop1234567890 aws=AKIAIOSFODNN7EXAMPLE");
        assert!(result.text.contains("[REDACTED:api_key]"));
        assert!(result.text.contains("[REDACTED:aws_access_key_id]"));
        assert!(!contains_secret(&result.text));
    }

    #[test]
    fn redacts_pem_block() {
        let pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----";
        let result = redact(pem);
        assert_eq!(result.text, "[REDACTED:private_key]");
    }

    #[test]
    fn redacts_url_credentials_but_keeps_host() {
        let result = redact("clone https://user:s3cret@github.com/x/y.git");
        assert!(result
            .text
            .contains("https://[REDACTED:url_credentials]@github.com"));
        assert!(!result.text.contains("s3cret"));
    }

    #[test]
    fn redacts_jwt() {
        let jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let result = redact(jwt);
        assert!(result.text.contains("[REDACTED:jwt]"));
        assert!(!result.text.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    #[test]
    fn leaves_ordinary_text_alone() {
        let text = "端口是 7411，金额用整数分，日志前缀 ZX-。";
        let result = redact(text);
        assert_eq!(result.text, text);
        assert!(result.is_clean());
        assert!(!contains_secret(text));
    }

    #[test]
    fn redaction_is_idempotent() {
        let first = redact("sk-abcdefghijklmnop1234567890");
        let second = redact(&first.text);
        assert_eq!(first.text, second.text);
        assert!(second.is_clean());
    }
}
