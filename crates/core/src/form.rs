//! 内容形态的渲染（设计文档 §2.2 F2–F5）。
//!
//! 标记格式固定，指针必须自带判别信息：来源、位置、token 数、一句话内容。
//! 模型只看指针就能决定要不要 `fetch_archive`，这是 §5.5 的第一层检索。

use crate::archive::{guest_path, ArchiveEntry};
use crate::ids::ArchiveRef;
use crate::tokens::{estimate_tokens, format_k, head_prefix, tail_suffix};

/// 一段索引（D322）：没有就什么都不加。
///
/// 摘要只当**索引**用——"这段里有没有我要的东西、跟旁边那段有什么区别"。
/// 它不进上下文的其他任何地方，就挂在这一行标记里。
fn with_summary(marker: String, summary: Option<&str>) -> String {
    match summary.map(str::trim).filter(|text| !text.is_empty()) {
        Some(text) => format!("{} | {text}", marker.trim_end_matches(']')),
        None => marker,
    }
    .trim_end()
    .to_string()
        + "]"
}

/// F3：masking 占位符，约 10 token。
pub fn mask_marker(tokens: u32, reference: &ArchiveRef, summary: Option<&str>) -> String {
    with_summary(
        format!(
            "[output masked: {} tok, {} → {}",
            format_k(tokens),
            reference,
            guest_path(reference)
        ),
        summary,
    )
}

/// F2 尾注：`[… 8.2k tok 已归档 ref#a3f9… → /archive/a3f9….txt | 失败那次的构建日志]`。
pub fn truncation_marker(tokens: u32, reference: &ArchiveRef, summary: Option<&str>) -> String {
    with_summary(
        format!(
            "[… {} tok 已归档 {} → {}",
            format_k(tokens),
            reference,
            guest_path(reference)
        ),
        summary,
    )
}

/// 渲染 F2：头 N token + 尾注 + 尾 M token。
///
/// 头尾预算之和已覆盖全文时，直接返回原文——截断没有意义，还多付一个标记。
pub fn render_truncated(
    content: &str,
    head_tokens: u32,
    tail_tokens: u32,
    reference: &ArchiveRef,
    total_tokens: u32,
    summary: Option<&str>,
) -> String {
    let head = head_prefix(content, head_tokens);
    let tail = tail_suffix(content, tail_tokens);
    if head.len() + tail.len() >= content.len() {
        return content.to_string();
    }
    let mut out = String::with_capacity(head.len() + tail.len() + 80);
    out.push_str(head.trim_end());
    out.push('\n');
    out.push_str(&truncation_marker(total_tokens, reference, summary));
    out.push('\n');
    out.push_str(tail.trim_start());
    out
}

/// 渲染 F4：指针行。
pub fn render_pointer(entry: &ArchiveEntry) -> String {
    entry.pointer_line()
}

/// 渲染 F3。
pub fn render_masked(entry: &ArchiveEntry) -> String {
    mask_marker(entry.tokens, &entry.rf, entry.summary())
}

/// 按形态渲染一段内容。F1 原样返回，F5 只留描述符。
pub fn render_for_form(entry: &ArchiveEntry, form: crate::message::ContentForm) -> String {
    use crate::message::ContentForm;
    match form {
        ContentForm::Full => entry.content.clone(),
        ContentForm::Truncated {
            head_tokens,
            tail_tokens,
        } => render_truncated(
            &entry.content,
            head_tokens,
            tail_tokens,
            &entry.rf,
            entry.tokens,
            entry.summary(),
        ),
        ContentForm::Masked => render_masked(entry),
        ContentForm::Pointer => render_pointer(entry),
        ContentForm::IndexOnly => entry.descriptor.clone(),
    }
}

/// 渲染后重新计 token，用于计量与预算。F1 直接走估算。
pub fn rendered_tokens(rendered: &str) -> u32 {
    estimate_tokens(rendered)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::archive::{ArchiveEntry, Origin};
    use crate::ids::{MessageId, SessionId};
    use crate::source::SourceTag;

    fn entry(content: &str, tokens: u32) -> ArchiveEntry {
        let session = SessionId::new();
        ArchiveEntry::new_redacted(
            content.to_string(),
            SourceTag::ToolResult,
            Origin::new(session, MessageId::new(session, 1), 1),
            tokens,
            0,
        )
    }

    #[test]
    fn markers_carry_ref_and_tokens() {
        let entry = entry("x", 8_200);
        let path = guest_path(&entry.rf);
        assert_eq!(
            truncation_marker(entry.tokens, &entry.rf, None),
            format!("[… 8.2k tok 已归档 {} → {path}]", entry.rf)
        );
        assert_eq!(
            mask_marker(entry.tokens, &entry.rf, None),
            format!("[output masked: 8.2k tok, {} → {path}]", entry.rf)
        );
    }

    /// 标记行要能让模型**直接去读**：路径与文件名必须是同一份 ref 拼出来的。
    #[test]
    fn markers_carry_a_readable_path() {
        let entry = entry("x", 10);
        let marker = truncation_marker(entry.tokens, &entry.rf, None);
        let path = guest_path(&entry.rf);
        assert!(marker.contains(&path), "{marker}");
        assert!(path.starts_with("/archive/"), "{path}");
        assert!(path.ends_with(".txt"), "{path}");
        assert!(!path.contains('#'), "路径里不该带 ref# 前缀：{path}");
    }

    #[test]
    fn truncated_keeps_head_tail_and_marker() {
        let content = format!("HEAD-{}-TAIL", "x".repeat(400));
        let entry = entry(&content, 200);
        let rendered = render_truncated(&content, 4, 4, &entry.rf, entry.tokens, None);
        assert!(rendered.starts_with("HEAD-"));
        assert!(rendered.ends_with("TAIL"));
        assert!(rendered.contains(&entry.rf.to_string()));
    }

    #[test]
    fn truncation_is_noop_when_budget_covers_content() {
        let entry = entry("short", 2);
        let rendered = render_truncated("short", 100, 100, &entry.rf, 2, None);
        assert_eq!(rendered, "short");
    }

    #[test]
    fn pointer_has_source_position_tokens_and_summary() {
        let entry = entry("退款状态机实现，3 个 TODO", 42);
        let line = render_pointer(&entry);
        assert!(line.contains("42 tok"));
        assert!(line.contains("tool_result"));
        assert!(line.contains("s:"));
        assert!(line.contains("退款状态机实现"));
    }
}
