//! 工具定义的覆写（0.17f）：世界书里 `tool_call_<工具名>` 的条目。
//!
//! 为什么走世界书而不是设置页：工具说明是**提示词的一部分**，用户已经在世界书里管提示词，
//! 多开一页设置就多一个"我改的那句算不算数"的问题。这里只加一条约定：
//! 名字以 `tool_call_` 开头的条目，管的是**请求体里 `tools` 那一份定义**。
//!
//! 三条边界：
//!
//! - **只对存在的工具有效**：没有那个名字就什么也不做（MCP 工具、以后接的搜索都一样）。
//! - **空条目 = 用应用自带的那一份**：恢复默认不需要另存一份原文，把正文清掉就行。
//! - **这类条目不进上下文**：它不是给人看的设定，是给模型看的工具说明——
//!   注入逻辑在 `lorebook::plan` 里显式跳过它们（否则一条空条目也会变成一个空消息）。

use serde_json::{Map, Value};

use crate::lorebook::LorebookData;
use crate::runtime::ToolSpec;

/// 条目名里管工具定义的那一段前缀。
pub const PREFIX: &str = "tool_call_";

/// 一条覆写。`description` 与 `parameters` 各自独立：只改说明的条目不必带 schema。
#[derive(Debug, Clone, PartialEq)]
pub struct ToolOverride {
    pub tool: String,
    pub description: Option<String>,
    pub parameters: Option<Value>,
}

/// 从一册（已合并的）世界书里取出所有工具覆写。
///
/// 同一个工具写了多条时用**第一条**：它们是并列的条目，后写一条不该悄悄盖掉前一条。
pub fn overrides_from(book: &LorebookData) -> Vec<ToolOverride> {
    let mut out: Vec<ToolOverride> = Vec::new();
    for entry in &book.entries {
        if !entry.enabled {
            continue;
        }
        let Some(tool) = entry.name.trim().strip_prefix(PREFIX) else {
            continue;
        };
        let tool = tool.trim();
        if tool.is_empty() || out.iter().any(|existing| existing.tool == tool) {
            continue;
        }
        if let Some(over) = parse(tool, &entry.content) {
            out.push(over);
        }
    }
    out
}

/// 正文两种写法：
///
/// - 一段纯文本 → 只换 `description`；
/// - 一个 JSON 对象 → `description` / `parameters` 各取各的（缺哪个就不换哪个）。
///
/// `parameters` 不是对象时**整条不认**：把一个坏 schema 发给上游，整轮请求都会 400，
/// 而报错只会指向"tools 非法"，没人能追回这条世界书。
fn parse(tool: &str, content: &str) -> Option<ToolOverride> {
    let text = content.trim();
    if text.is_empty() {
        return None;
    }
    let parsed: Option<Map<String, Value>> = if text.starts_with('{') {
        match serde_json::from_str::<Value>(text) {
            Ok(Value::Object(object)) => Some(object),
            _ => None,
        }
    } else {
        None
    };
    let Some(object) = parsed else {
        return Some(ToolOverride {
            tool: tool.to_string(),
            description: Some(text.to_string()),
            parameters: None,
        });
    };
    let description = object
        .get("description")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string);
    let parameters = match object.get("parameters") {
        None | Some(Value::Null) => None,
        Some(Value::Object(_)) => object.get("parameters").cloned(),
        Some(_) => return None,
    };
    if description.is_none() && parameters.is_none() {
        return None;
    }
    Some(ToolOverride {
        tool: tool.to_string(),
        description,
        parameters,
    })
}

/// 把覆写贴到这一轮的声明表上。名字对不上的覆写**原样留着**——下一个工具接上时它自然生效。
pub fn apply(mut specs: Vec<ToolSpec>, overrides: &[ToolOverride]) -> Vec<ToolSpec> {
    for spec in &mut specs {
        let Some(over) = overrides.iter().find(|over| over.tool == spec.name) else {
            continue;
        };
        if let Some(description) = &over.description {
            spec.description = Some(description.clone());
        }
        if let Some(parameters) = &over.parameters {
            spec.parameters = parameters.clone();
        }
    }
    specs
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lorebook::LorebookEntry;
    use crate::runtime::ToolSpec;
    use serde_json::json;

    fn entry(name: &str, content: &str, enabled: bool) -> LorebookEntry {
        let mut entry = LorebookEntry::blank();
        entry.name = name.into();
        entry.content = content.into();
        entry.enabled = enabled;
        entry
    }

    fn book_of(entries: Vec<LorebookEntry>) -> LorebookData {
        let mut book = LorebookData::blank();
        book.entries = entries;
        book
    }

    fn specs() -> Vec<ToolSpec> {
        vec![
            ToolSpec::new("get_time_info", json!({"type": "object", "properties": {}}))
                .describe("Get the current local date and time info from the device."),
            ToolSpec::new("read_file", json!({"type": "object"})).describe("读一个文件"),
        ]
    }

    #[test]
    fn a_plain_text_entry_replaces_only_the_description() {
        let book = book_of(vec![entry(
            "tool_call_get_time_info",
            "先看手机时间，再回答。",
            true,
        )]);
        let overrides = overrides_from(&book);
        assert_eq!(overrides.len(), 1);
        let applied = apply(specs(), &overrides);
        let time = applied
            .iter()
            .find(|spec| spec.name == "get_time_info")
            .unwrap();
        assert_eq!(time.description.as_deref(), Some("先看手机时间，再回答。"));
        assert_eq!(time.parameters["type"], json!("object"));
        // 别的工具一个字都没动
        let read = applied
            .iter()
            .find(|spec| spec.name == "read_file")
            .unwrap();
        assert_eq!(read.description.as_deref(), Some("读一个文件"));
    }

    #[test]
    fn a_json_entry_can_replace_the_schema_too() {
        let content = json!({
            "description": "按名字查时间",
            "parameters": {"type": "object", "properties": {"tz": {"type": "string"}}, "required": ["tz"]}
        })
        .to_string();
        let overrides = overrides_from(&book_of(vec![entry(
            "tool_call_get_time_info",
            &content,
            true,
        )]));
        let applied = apply(specs(), &overrides);
        let time = applied
            .iter()
            .find(|spec| spec.name == "get_time_info")
            .unwrap();
        assert_eq!(time.description.as_deref(), Some("按名字查时间"));
        assert_eq!(time.parameters["required"][0], json!("tz"));
    }

    #[test]
    fn an_empty_or_disabled_entry_means_use_the_built_in_definition() {
        let empty = book_of(vec![entry("tool_call_get_time_info", "   ", true)]);
        assert!(overrides_from(&empty).is_empty());
        let disabled = book_of(vec![entry("tool_call_get_time_info", "改了", false)]);
        assert!(overrides_from(&disabled).is_empty());
    }

    #[test]
    fn a_definition_for_a_tool_that_is_not_here_does_nothing() {
        let overrides = overrides_from(&book_of(vec![entry(
            "tool_call_mcp__exa__search",
            "x",
            true,
        )]));
        assert_eq!(overrides.len(), 1);
        let applied = apply(specs(), &overrides);
        assert_eq!(applied.len(), 2);
        assert!(applied.iter().all(|spec| spec.name != "mcp__exa__search"));
    }

    #[test]
    fn the_first_entry_for_a_tool_wins() {
        let book = book_of(vec![
            entry("tool_call_read_file", "第一句", true),
            entry("tool_call_read_file", "第二句", true),
        ]);
        let overrides = overrides_from(&book);
        assert_eq!(overrides.len(), 1);
        assert_eq!(overrides[0].description.as_deref(), Some("第一句"));
    }

    #[test]
    fn a_broken_schema_is_ignored_instead_of_sent() {
        let content = json!({"description": "改说明", "parameters": "不是对象"}).to_string();
        let book = book_of(vec![entry("tool_call_read_file", &content, true)]);
        assert!(overrides_from(&book).is_empty(), "坏 schema 整条不认");
        // 但只有 description 时照样能改
        let content = json!({"description": "只改说明"}).to_string();
        let overrides =
            overrides_from(&book_of(vec![entry("tool_call_read_file", &content, true)]));
        assert_eq!(overrides[0].description.as_deref(), Some("只改说明"));
        assert!(overrides[0].parameters.is_none());
    }

    #[test]
    fn entries_without_the_prefix_are_not_definitions() {
        let book = book_of(vec![entry("get_time_info", "这不是覆写", true)]);
        assert!(overrides_from(&book).is_empty());
        // 带前缀但名字为空也不行
        let book = book_of(vec![entry("tool_call_", "x", true)]);
        assert!(overrides_from(&book).is_empty());
    }
}
