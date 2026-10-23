//! 写入时刻的路由规则（设计文档 §2.3）。
//!
//! 规则是确定性的：同一输入两次调用必须给出同一形态。命中即停，优先级从上到下。
//! 这里不做任何 LLM 调用——模式 2 的价值就是零幻觉 + 可复现。

use crate::ids::ArchiveRef;
use crate::message::{ContentForm, Role};
use crate::source::SourceTag;

/// 内容类型（§2.3.1）。只用于规则 8 的形态细化。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContentKind {
    SourceCode,
    CompileOutput,
    Json,
    Log,
    Html,
    Binary,
    PlainText,
}

impl ContentKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ContentKind::SourceCode => "source_code",
            ContentKind::CompileOutput => "compile_output",
            ContentKind::Json => "json",
            ContentKind::Log => "log",
            ContentKind::Html => "html",
            ContentKind::Binary => "binary",
            ContentKind::PlainText => "plain_text",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuleId {
    /// 规则 1：user / system / 工具定义，永不自动路由。
    NeverRoute,
    /// 规则 2：protected（失败、错误、异常）。
    Protected,
    /// 规则 3：base64 / data URI / 高熵二进制。
    Binary,
    /// 规则 4：同一路径 / URL 的第二次读取。
    RepeatRead,
    /// 规则 5a：空输出。
    EmptyOutput,
    /// 规则 5b：纯重复行。
    RepeatedLines,
    /// 规则 6：超过 huge_threshold。
    Huge,
    /// 规则 7：超过 large_threshold。
    Large,
    /// 规则 8：超过 small_threshold。
    Medium,
    /// 规则 9：其他。
    Small,
}

impl RuleId {
    pub fn as_str(self) -> &'static str {
        match self {
            RuleId::NeverRoute => "R1_never_route",
            RuleId::Protected => "R2_protected",
            RuleId::Binary => "R3_binary",
            RuleId::RepeatRead => "R4_repeat_read",
            RuleId::EmptyOutput => "R5_empty",
            RuleId::RepeatedLines => "R5_repeated_lines",
            RuleId::Huge => "R6_huge",
            RuleId::Large => "R7_large",
            RuleId::Medium => "R8_medium",
            RuleId::Small => "R9_small",
        }
    }
}

/// 同一路径 / URL 的上一次读取结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PriorRead {
    pub reference: ArchiveRef,
    /// mtime + size + hash 全同。
    pub unchanged: bool,
    /// 变化的位置数（diff 行数），仅在 `unchanged == false` 时有意义。
    pub changed_parts: u32,
}

#[derive(Debug, Clone)]
pub struct RouteInput<'a> {
    pub role: Role,
    pub source: SourceTag,
    pub content: &'a str,
    pub tokens: u32,
    pub path: Option<&'a str>,
    pub tool_name: Option<&'a str>,
    pub prior_read: Option<PriorRead>,
    pub protected: bool,
}

impl<'a> RouteInput<'a> {
    pub fn new(role: Role, source: SourceTag, content: &'a str, tokens: u32) -> Self {
        Self {
            role,
            source,
            content,
            tokens,
            path: None,
            tool_name: None,
            prior_read: None,
            protected: false,
        }
    }

    pub fn with_path(mut self, path: &'a str) -> Self {
        self.path = Some(path);
        self
    }

    pub fn with_prior_read(mut self, prior: PriorRead) -> Self {
        self.prior_read = Some(prior);
        self
    }

    pub fn protected(mut self) -> Self {
        self.protected = true;
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteDecision {
    pub form: ContentForm,
    pub rule: RuleId,
    pub kind: ContentKind,
    pub reason: String,
}

impl RouteDecision {
    /// 这个形态是否需要写归档。
    pub fn needs_archive(&self) -> bool {
        self.form.requires_archive()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RouterConfig {
    pub small_threshold: u32,
    pub large_threshold: u32,
    pub huge_threshold: u32,
    pub truncate_head: u32,
    pub truncate_tail: u32,
}

impl Default for RouterConfig {
    fn default() -> Self {
        // §2.7 的默认值。全部是建议初值，必须实测调整。
        Self {
            small_threshold: 2_000,
            large_threshold: 8_000,
            huge_threshold: 32_000,
            truncate_head: 1_000,
            truncate_tail: 1_000,
        }
    }
}

/// 纯函数路由。相同输入必得相同输出。
pub fn route(input: &RouteInput<'_>, config: &RouterConfig) -> RouteDecision {
    let kind = detect_kind(input.content, input.path);

    // 规则 1：用户 / 系统 / 工具定义，永不自动路由。
    if input.role == Role::User || input.role == Role::System || input.source.never_routed() {
        return decision(
            ContentForm::Full,
            RuleId::NeverRoute,
            kind,
            "user/system/工具定义永不自动路由",
        );
    }

    // 规则 2：protected 只能 F1，无论多大。
    if input.protected {
        return decision(
            ContentForm::Full,
            RuleId::Protected,
            kind,
            "protected（失败/错误/异常）必须保留原文",
        );
    }

    // 规则 3：二进制 / base64 / data URI。
    if looks_like_binary(input.content) {
        return decision(
            ContentForm::Pointer,
            RuleId::Binary,
            ContentKind::Binary,
            "二进制或高熵内容，仅保留指针",
        );
    }

    // 规则 4：第二次读同一路径 / URL。
    if let Some(prior) = &input.prior_read {
        if prior.unchanged {
            return decision(
                ContentForm::Masked,
                RuleId::RepeatRead,
                kind,
                &format!("内容未变，见 {}", prior.reference),
            );
        }
        return decision(
            ContentForm::Truncated {
                head_tokens: config.truncate_head,
                tail_tokens: config.truncate_tail,
            },
            RuleId::RepeatRead,
            kind,
            &format!("相比 {} 有 {} 处变化", prior.reference, prior.changed_parts),
        );
    }

    // 规则 5a：空输出。
    if input.content.trim().is_empty() {
        return decision(
            ContentForm::Full,
            RuleId::EmptyOutput,
            kind,
            "空输出不占上下文，无需归档",
        );
    }

    // 规则 5b：纯重复行。
    if is_pure_repetition(input.content) {
        return decision(
            ContentForm::Masked,
            RuleId::RepeatedLines,
            kind,
            "纯重复行，无信息增量",
        );
    }

    // 规则 6：超大。
    if input.tokens > config.huge_threshold {
        return decision(
            ContentForm::Pointer,
            RuleId::Huge,
            kind,
            &format!(
                "{} tok 超过 huge 阈值 {}",
                input.tokens, config.huge_threshold
            ),
        );
    }

    // 规则 7：大。
    if input.tokens > config.large_threshold {
        return decision(
            ContentForm::Truncated {
                head_tokens: config.truncate_head,
                tail_tokens: config.truncate_tail,
            },
            RuleId::Large,
            kind,
            &format!(
                "{} tok 超过 large 阈值 {}",
                input.tokens, config.large_threshold
            ),
        );
    }

    // 规则 8：中等，按内容类型决定。
    if input.tokens > config.small_threshold {
        let truncate = matches!(
            kind,
            ContentKind::SourceCode
                | ContentKind::Json
                | ContentKind::CompileOutput
                | ContentKind::Log
                | ContentKind::Html
        );
        if truncate {
            return decision(
                ContentForm::Truncated {
                    head_tokens: config.truncate_head,
                    tail_tokens: config.truncate_tail,
                },
                RuleId::Medium,
                kind,
                &format!(
                    "{} tok 超过 small 阈值，按 {} 保留头尾",
                    input.tokens,
                    kind.as_str()
                ),
            );
        }
        return decision(
            ContentForm::Full,
            RuleId::Medium,
            kind,
            "超过 small 阈值但类型不可预判，保留全文",
        );
    }

    // 规则 9：其余。
    decision(ContentForm::Full, RuleId::Small, kind, "小于阈值，保留全文")
}

fn decision(form: ContentForm, rule: RuleId, kind: ContentKind, reason: &str) -> RouteDecision {
    RouteDecision {
        form,
        rule,
        kind,
        reason: reason.to_string(),
    }
}

/// 内容类型识别：先看扩展名，再看内容特征。
pub fn detect_kind(content: &str, path: Option<&str>) -> ContentKind {
    if let Some(path) = path {
        let lower = path.to_lowercase();
        if lower.ends_with(".html") || lower.ends_with(".htm") {
            return ContentKind::Html;
        }
        const CODE_EXT: &[&str] = &[
            ".rs", ".py", ".ts", ".tsx", ".js", ".jsx", ".go", ".java", ".c", ".cc", ".cpp", ".h",
            ".hpp", ".kt", ".kts", ".swift", ".cs", ".rb", ".php", ".sh", ".bash", ".sql", ".toml",
            ".yaml", ".yml", ".xml", ".jsonc", ".lua", ".scala", ".gradle",
        ];
        if CODE_EXT.iter().any(|ext| lower.ends_with(ext)) {
            return ContentKind::SourceCode;
        }
    }

    if looks_like_binary(content) {
        return ContentKind::Binary;
    }
    let trimmed = content.trim_start();
    if trimmed.starts_with("<!DOCTYPE") || trimmed.starts_with("<html") {
        return ContentKind::Html;
    }
    if (trimmed.starts_with('{') || trimmed.starts_with('['))
        && serde_json::from_str::<serde_json::Value>(trimmed).is_ok()
    {
        return ContentKind::Json;
    }
    if looks_like_compile_output(content) {
        return ContentKind::CompileOutput;
    }
    if looks_like_log(content) {
        return ContentKind::Log;
    }
    ContentKind::PlainText
}

fn looks_like_compile_output(content: &str) -> bool {
    const MARKERS: &[&str] = &[
        "error[E",
        "error:",
        "Traceback (most recent call last)",
        "panicked at",
        "FAILED",
        "--- FAIL",
        "npm ERR!",
        "make: ***",
        "Compilation failed",
    ];
    let head: String = content.chars().take(4_000).collect();
    MARKERS.iter().any(|marker| head.contains(marker))
}

fn looks_like_log(content: &str) -> bool {
    let mut timestamped = 0usize;
    let mut levels = 0usize;
    let mut lines = 0usize;
    for line in content.lines().take(200) {
        if line.trim().is_empty() {
            continue;
        }
        lines += 1;
        let head: String = line.chars().take(32).collect();
        if head.len() >= 10
            && head.as_bytes().get(4) == Some(&b'-')
            && head.as_bytes().get(7) == Some(&b'-')
        {
            timestamped += 1;
        }
        if head.contains("INFO") || head.contains("WARN") || head.contains("ERROR") {
            levels += 1;
        }
    }
    lines >= 5 && (timestamped * 2 >= lines || levels * 2 >= lines)
}

/// 二进制 / data URI / base64 的粗判。
pub fn looks_like_binary(content: &str) -> bool {
    if content.starts_with("data:") && content.contains(";base64,") {
        return true;
    }
    const MAGIC: &[&[u8]] = &[
        b"\x89PNG\r\n\x1a\n",
        b"\x1f\x8b",
        b"%PDF-",
        b"PK\x03\x04",
        b"\xff\xd8\xff",
        b"GIF89a",
        b"RIFF",
    ];
    if MAGIC
        .iter()
        .any(|magic| content.as_bytes().starts_with(magic))
    {
        return true;
    }

    let sample: String = content.chars().take(8_192).collect();
    if sample.is_empty() {
        return false;
    }
    let mut control = 0usize;
    let mut total = 0usize;
    for ch in sample.chars() {
        total += 1;
        if ch == '\u{0}' {
            return true;
        }
        if ch.is_control() && ch != '\n' && ch != '\r' && ch != '\t' {
            control += 1;
        }
    }
    if total > 0 && control * 20 >= total {
        return true;
    }

    // 长 base64 块：字符集单一 + 熵高 + 长度够。
    if sample.len() >= 512 {
        let b64_chars = sample
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=' | '-' | '_'))
            .count();
        if b64_chars * 100 >= total * 95 && shannon_entropy(&sample) >= 4.5 {
            return true;
        }
    }
    false
}

/// 香农熵（bits/byte），用字符近似字节。
pub fn shannon_entropy(text: &str) -> f64 {
    use std::collections::HashMap;
    let mut counts: HashMap<char, usize> = HashMap::new();
    let mut total = 0usize;
    for ch in text.chars() {
        *counts.entry(ch).or_insert(0) += 1;
        total += 1;
    }
    if total == 0 {
        return 0.0;
    }
    let total = total as f64;
    -counts
        .values()
        .map(|&count| {
            let p = count as f64 / total;
            p * p.log2()
        })
        .sum::<f64>()
}

/// 纯重复行：非空行 ≥ 8 且不同行 ≤ 2。
pub fn is_pure_repetition(content: &str) -> bool {
    let mut non_empty = 0usize;
    let mut distinct: Vec<&str> = Vec::new();
    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        non_empty += 1;
        if !distinct.contains(&line) {
            distinct.push(line);
            if distinct.len() > 2 {
                return false;
            }
        }
    }
    non_empty >= 8 && distinct.len() <= 2
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids::SessionId;

    fn input(source: SourceTag, content: &str, tokens: u32) -> RouteInput<'_> {
        RouteInput::new(Role::Tool, source, content, tokens)
    }

    #[test]
    fn rule1_user_and_system_never_route() {
        let config = RouterConfig::default();
        let big = "x".repeat(200_000);
        let decision = route(
            &RouteInput::new(Role::User, SourceTag::History, &big, 50_000),
            &config,
        );
        assert_eq!(decision.rule, RuleId::NeverRoute);
        assert_eq!(decision.form, ContentForm::Full);

        let decision = route(
            &RouteInput::new(Role::User, SourceTag::System, &big, 50_000),
            &config,
        );
        assert_eq!(decision.rule, RuleId::NeverRoute);
    }

    #[test]
    fn rule2_protected_stays_full() {
        let config = RouterConfig::default();
        let big = "line\n".repeat(10_000);
        let decision = route(
            &input(SourceTag::ToolResult, &big, 90_000).protected(),
            &config,
        );
        assert_eq!(decision.rule, RuleId::Protected);
        assert_eq!(decision.form, ContentForm::Full);
    }

    #[test]
    fn rule3_binary_becomes_pointer() {
        let config = RouterConfig::default();
        let decision = route(&input(SourceTag::ToolResult, "\u{0}\u{1}\u{2}", 3), &config);
        assert_eq!(decision.rule, RuleId::Binary);
        assert_eq!(decision.form, ContentForm::Pointer);
    }

    #[test]
    fn rule4_repeat_read_masks_when_unchanged() {
        let config = RouterConfig::default();
        let reference = ArchiveRef::from_content("x");
        let prior = PriorRead {
            reference: reference.clone(),
            unchanged: true,
            changed_parts: 0,
        };
        let decision = route(
            &input(SourceTag::ToolResult, "same", 1).with_prior_read(prior),
            &config,
        );
        assert_eq!(decision.rule, RuleId::RepeatRead);
        assert_eq!(decision.form, ContentForm::Masked);
        assert!(decision.reason.contains(&reference.to_string()));
    }

    #[test]
    fn rule4_repeat_read_truncates_when_changed() {
        let config = RouterConfig::default();
        let prior = PriorRead {
            reference: ArchiveRef::from_content("x"),
            unchanged: false,
            changed_parts: 3,
        };
        let decision = route(
            &input(SourceTag::ToolResult, "changed", 1).with_prior_read(prior),
            &config,
        );
        assert!(matches!(decision.form, ContentForm::Truncated { .. }));
        assert!(decision.reason.contains("3 处变化"));
    }

    #[test]
    fn rule5_empty_and_repeated() {
        let config = RouterConfig::default();
        assert_eq!(
            route(&input(SourceTag::ToolResult, "   \n", 0), &config).rule,
            RuleId::EmptyOutput
        );

        let repeated = "same line\n".repeat(20);
        let decision = route(&input(SourceTag::ToolResult, &repeated, 100), &config);
        assert_eq!(decision.rule, RuleId::RepeatedLines);
        assert_eq!(decision.form, ContentForm::Masked);
    }

    #[test]
    fn rule6_huge_pointer() {
        let config = RouterConfig::default();
        let decision = route(&input(SourceTag::ToolResult, "text", 32_001), &config);
        assert_eq!(decision.rule, RuleId::Huge);
        assert_eq!(decision.form, ContentForm::Pointer);
    }

    #[test]
    fn rule7_large_truncates() {
        let config = RouterConfig::default();
        let decision = route(&input(SourceTag::ToolResult, "text", 8_001), &config);
        assert_eq!(decision.rule, RuleId::Large);
        assert!(matches!(decision.form, ContentForm::Truncated { .. }));
    }

    #[test]
    fn rule8_depends_on_content_kind() {
        let config = RouterConfig::default();
        // 每行内容不同：否则会先命中"纯重复行"规则（R5），测不到规则 8
        let code = (0..200)
            .map(|i| format!("fn handler_{i}() -> u32 {{ {i} }}\n"))
            .collect::<Vec<_>>()
            .join("");
        let decision = route(
            &input(SourceTag::ToolResult, &code, 3_000).with_path("src/main.rs"),
            &config,
        );
        assert_eq!(decision.rule, RuleId::Medium);
        assert_eq!(decision.kind, ContentKind::SourceCode);
        assert!(matches!(decision.form, ContentForm::Truncated { .. }));

        let prose = "这是一段普通说明文字。".repeat(100);
        let decision = route(&input(SourceTag::ToolResult, &prose, 3_000), &config);
        assert_eq!(decision.rule, RuleId::Medium);
        assert_eq!(decision.form, ContentForm::Full);
    }

    #[test]
    fn rule9_small_stays_full() {
        let config = RouterConfig::default();
        let decision = route(&input(SourceTag::ToolResult, "hello", 2), &config);
        assert_eq!(decision.rule, RuleId::Small);
        assert_eq!(decision.form, ContentForm::Full);
    }

    #[test]
    fn routing_is_deterministic() {
        let config = RouterConfig::default();
        let content = "fn main() {}\n".repeat(200);
        let first = route(&input(SourceTag::ToolResult, &content, 3_000), &config);
        let second = route(&input(SourceTag::ToolResult, &content, 3_000), &config);
        assert_eq!(first, second);
    }

    #[test]
    fn detects_json_and_compile_output() {
        assert_eq!(detect_kind(r#"{"a": 1}"#, None), ContentKind::Json);
        assert_eq!(
            detect_kind("error[E0308]: mismatched types", None),
            ContentKind::CompileOutput
        );
        assert_eq!(
            detect_kind("<html><body></body></html>", None),
            ContentKind::Html
        );
        let session = SessionId::new();
        assert_eq!(session.short().len(), 8);
    }
}
