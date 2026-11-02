//! 正文里的工具调用（D212）。
//!
//! 有些端点（尤其本地跑的 Qwen / Hermes 系）不发结构化的 `tool_calls`，
//! 而是把调用**写在正文里**：
//!
//! ```text
//! <tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>
//! ```
//!
//! 这一层只认这一种最常见、最没有歧义的写法：`<tool_call>` 里是一个 JSON 对象，
//! 带 `name` 与 `arguments`（对象或字符串都行）。**认不出就原样留着**——
//! 把一段看不懂的正文悄悄吞掉，比不执行这次调用糟得多。
//!
//! 不认的写法（列在这里，免得以后有人以为"漏了"）：
//! - `<function=name><parameter=k>v</parameter></function>`（Anthropic 的 XML 风格）
//! - `<|tool_call|>…<|/tool_call|>`（部分模板的特殊 token）
//! - ` ```json {"name":…} ``` ` 裸 JSON 代码块（跟普通示例分不开）
//!
//! 这几种要么少见，要么与正文里的示例无法区分；真遇上再加，别在这里猜。

use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InlineCall {
    pub name: String,
    pub arguments: String,
}

const OPEN: &str = "<tool_call>";
const CLOSE: &str = "</tool_call>";

/// 切出正文里的调用。返回（剩下的正文，调用列表）。
pub fn extract(input: &str) -> (String, Vec<InlineCall>) {
    let lower = input.to_ascii_lowercase();
    if !lower.contains(OPEN) {
        return (input.to_string(), Vec::new());
    }

    let mut text = String::with_capacity(input.len());
    let mut calls = Vec::new();
    let mut cursor = 0usize;

    while let Some(offset) = lower[cursor..].find(OPEN) {
        let open_at = cursor + offset;
        text.push_str(&input[cursor..open_at]);
        let body_start = open_at + OPEN.len();
        let Some(close_offset) = lower[body_start..].find(CLOSE) else {
            // 没闭合：整段原样留着，不猜。
            text.push_str(&input[open_at..]);
            cursor = input.len();
            break;
        };
        let close_at = body_start + close_offset;
        let body = input[body_start..close_at].trim();
        match parse_call(body) {
            Some(call) => calls.push(call),
            None => {
                // 不是能认的调用：整段原样留回去。
                text.push_str(&input[open_at..close_at + CLOSE.len()]);
            }
        }
        cursor = close_at + CLOSE.len();
    }
    if cursor < input.len() {
        text.push_str(&input[cursor..]);
    }

    (text.trim().to_string(), calls)
}

fn parse_call(body: &str) -> Option<InlineCall> {
    let value: Value = serde_json::from_str(body).ok()?;
    let object = value.as_object()?;
    let name = object.get("name")?.as_str()?.trim();
    if name.is_empty() {
        return None;
    }
    let arguments = match object
        .get("arguments")
        .or_else(|| object.get("parameters"))?
    {
        // 对象：原样序列化回去（工具层只认 JSON 文本）。
        Value::Object(_) => object
            .get("arguments")
            .or_else(|| object.get("parameters"))
            .map(|v| v.to_string())?,
        // 字符串：必须是合法 JSON 对象，否则工具层会报"参数不是对象"。
        Value::String(text) => {
            let parsed: Value = serde_json::from_str(text).ok()?;
            if !parsed.is_object() {
                return None;
            }
            text.clone()
        }
        _ => return None,
    };
    Some(InlineCall {
        name: name.to_string(),
        arguments,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_is_untouched() {
        let (text, calls) = extract("你好，需要什么？");
        assert_eq!(text, "你好，需要什么？");
        assert!(calls.is_empty());
    }

    #[test]
    fn object_arguments_are_kept() {
        let (text, calls) = extract(
            r#"先读一下<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>"#,
        );
        assert_eq!(text, "先读一下");
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "read_file");
        assert!(calls[0].arguments.contains("\"path\""));
    }

    #[test]
    fn string_arguments_are_accepted_when_they_are_json_objects() {
        let (_, calls) =
            extract(r#"<tool_call>{"name":"ls","arguments":"{\"path\":\".\"}"}</tool_call>"#);
        assert_eq!(calls[0].name, "ls");
        assert_eq!(calls[0].arguments, "{\"path\":\".\"}");
    }

    /// 认不出就原样留着：不吞正文。
    #[test]
    fn unknown_shapes_stay_in_the_text() {
        let raw = r#"<tool_call>{"name":"x","arguments":"不是 JSON"}</tool_call>"#;
        let (text, calls) = extract(raw);
        assert_eq!(text, raw);
        assert!(calls.is_empty());

        let no_close = r#"<tool_call>{"name":"x","arguments":{}}"#;
        let (text, calls) = extract(no_close);
        assert_eq!(text, no_close);
        assert!(calls.is_empty());
    }

    #[test]
    fn multiple_calls_and_surrounding_text() {
        let (text, calls) = extract(
            r#"<tool_call>{"name":"a","arguments":{}}</tool_call>中间<tool_call>{"name":"b","arguments":{}}</tool_call>尾巴"#,
        );
        assert_eq!(text, "中间尾巴");
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[1].name, "b");
    }
}
