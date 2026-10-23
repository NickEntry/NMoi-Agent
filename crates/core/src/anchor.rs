//! 任务锚点（设计文档 §2.3.0，v1.1 强制项）。
//!
//! 实测：纯位置路由在 20k 上下文即失效——模型取回正确原文，却转头去回答历史里的旧问题。
//! 修复只需要一句话，且**唯一有效载荷是任务陈述本身**：角色不重要、位置不重要、
//! 措辞强弱不重要，"历史已结清"单独存在时无效（0/4）。
//!
//! 因此这里刻意不做概括：直接从最后一条 user 消息里取，避免引入偏差。

use crate::archive::truncate_chars;

/// 锚点前缀。固定字符串，便于测试与后续 prompt 版本管理。
pub const ANCHOR_PREFIX: &str = "历史已结清。当前任务：";

/// 一句话任务陈述的最大长度（字符）。
pub const DEFAULT_ANCHOR_TASK_MAX: usize = 200;

/// 由最后一条用户消息构造锚点文本。
///
/// - 取第一段非空内容，压缩空白；
/// - 不概括、不改写，超长时按字符截断（截断本身也是保真操作，不引入新语义）；
/// - 输出不含 system 权威，直接作为一条 user 消息插入压缩块之后（§2.3.0 实现约束）。
pub fn build_task_anchor(last_user_message: &str) -> String {
    build_task_anchor_with_limit(last_user_message, DEFAULT_ANCHOR_TASK_MAX)
}

pub fn build_task_anchor_with_limit(last_user_message: &str, max_chars: usize) -> String {
    let one_line = last_user_message
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or_default()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if one_line.is_empty() {
        return format!("{ANCHOR_PREFIX}(当前消息无文本内容)");
    }
    format!("{ANCHOR_PREFIX}{}", truncate_chars(&one_line, max_chars))
}

/// 锚点是否真的是"从原文摘出来的"——测试用，防止后续有人改成概括。
pub fn anchor_preserves_source(anchor: &str, source: &str) -> bool {
    let task = anchor.strip_prefix(ANCHOR_PREFIX).unwrap_or(anchor);
    let head: String = task.chars().take(20).collect();
    !head.is_empty() && source.contains(head.trim_end_matches('…'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn anchor_is_built_from_last_user_line() {
        let source = "先看这个文件。\n\n按项目规范写 Python 配置代码（端口、余额类型、日志函数），只输出代码。";
        let anchor = build_task_anchor(source);
        assert_eq!(
            anchor,
            format!("{ANCHOR_PREFIX}先看这个文件。"),
            "只取第一段非空内容，不概括"
        );

        let second = build_task_anchor("按项目规范写 Python 配置代码，只输出代码");
        assert!(second.contains("按项目规范写 Python 配置代码"));
    }

    #[test]
    fn anchor_truncates_long_task_by_chars() {
        let long = "写".repeat(500);
        let anchor = build_task_anchor_with_limit(&long, 20);
        let task = anchor.strip_prefix(ANCHOR_PREFIX).unwrap();
        assert_eq!(task.chars().count(), 20);
        assert!(task.ends_with('…'));
    }

    #[test]
    fn empty_user_message_gets_explicit_placeholder() {
        let anchor = build_task_anchor("   \n\n");
        assert!(anchor.contains("无文本内容"));
    }

    #[test]
    fn anchor_keeps_source_text_verbatim() {
        let source = "实现 OAuth 回调的幂等处理";
        let anchor = build_task_anchor(source);
        assert!(anchor_preserves_source(&anchor, source));
    }
}
