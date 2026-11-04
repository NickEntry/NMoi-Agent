//! 行内思考标签（D212）。
//!
//! 有些端点不在 `reasoning_content` 里给思考，而是把思考**塞进正文**：
//!
//! ```text
//! 你好<think>它在想</think></think>你好，需要什么？
//! ```
//!
//! 真机上见到的两种坏写法：
//! - 收尾标签重复：`</think></think>`（有的还带三个）；
//! - 空块：`<think></think>`，中间一个字都没有。
//!
//! 这一层只做**一件事**：把正文里的思考段切出来，交给 `TurnReport.reasoning()`。
//! 认的标签按常见程度排：`think`（DeepSeek / Qwen 系）、`thinking`、`reasoning`。
//! 认不出的标签原样留在正文里——宁可显示一段怪字，也不要悄悄吞掉用户看得见的内容。

/// 切出来的两半。`thinking` 为空 = 正文里没有思考标签。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Split {
    pub text: String,
    pub thinking: String,
}

const OPEN: [&str; 3] = ["<think>", "<thinking>", "<reasoning>"];
const CLOSE: [&str; 3] = ["</think>", "</thinking>", "</reasoning>"];

fn find_open(haystack: &str, from: usize) -> Option<(usize, usize)> {
    let lower = haystack.to_ascii_lowercase();
    let mut best: Option<(usize, usize)> = None;
    for tag in OPEN {
        if let Some(offset) = lower[from..].find(tag) {
            let at = from + offset;
            if best.map_or(true, |(pos, _)| at < pos) {
                best = Some((at, tag.len()));
            }
        }
    }
    best
}

fn find_close(haystack: &str, from: usize) -> Option<(usize, usize)> {
    let lower = haystack.to_ascii_lowercase();
    let mut best: Option<(usize, usize)> = None;
    for tag in CLOSE {
        if let Some(offset) = lower[from..].find(tag) {
            let at = from + offset;
            if best.map_or(true, |(pos, _)| at < pos) {
                best = Some((at, tag.len()));
            }
        }
    }
    best
}

/// 跳过紧跟在收尾标签后面的**重复收尾标签**（`</think></think>`）。
fn skip_repeated_closes(haystack: &str, mut cursor: usize) -> usize {
    loop {
        let rest = &haystack[cursor..];
        let trimmed = rest.trim_start();
        let skipped = rest.len() - trimmed.len();
        match find_close(trimmed, 0) {
            Some((0, len)) => cursor += skipped + len,
            _ => return cursor,
        }
    }
}

pub fn split(input: &str) -> Split {
    // 没有开标签就原样返回：绝大多数消息走这条，别为它们复制字符串。
    if find_open(input, 0).is_none() {
        return Split {
            text: input.to_string(),
            thinking: String::new(),
        };
    }

    let mut text = String::with_capacity(input.len());
    let mut thinking = String::new();
    let mut cursor = 0usize;

    while let Some((open_at, open_len)) = find_open(input, cursor) {
        text.push_str(&input[cursor..open_at]);
        let body_start = open_at + open_len;
        match find_close(input, body_start) {
            Some((close_at, close_len)) => {
                let body = &input[body_start..close_at];
                if !body.trim().is_empty() {
                    if !thinking.is_empty() {
                        thinking.push('\n');
                    }
                    thinking.push_str(body.trim());
                }
                cursor = skip_repeated_closes(input, close_at + close_len);
            }
            None => {
                // 没闭合：剩下的都算思考。真机上断流时就是这样。
                let body = &input[body_start..];
                if !body.trim().is_empty() {
                    if !thinking.is_empty() {
                        thinking.push('\n');
                    }
                    thinking.push_str(body.trim());
                }
                cursor = input.len();
                break;
            }
        }
    }
    if cursor < input.len() {
        text.push_str(&input[cursor..]);
    }

    // 切完只剩空白（整条都是思考）：正文留空，由调用方决定落不落。
    Split {
        text: text.trim().to_string(),
        thinking,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_is_untouched() {
        let split = split("你好，有什么可以帮你的吗？");
        assert_eq!(split.text, "你好，有什么可以帮你的吗？");
        assert!(split.thinking.is_empty());
    }

    /// 真机上那种：重复收尾 + 空块。
    #[test]
    fn duplicated_close_and_empty_block() {
        let split = split("你好！<think></think></think>你好！需要我做什么？");
        assert_eq!(split.text, "你好！你好！需要我做什么？");
        assert!(split.thinking.is_empty());
    }

    #[test]
    fn thinking_before_the_answer() {
        let split = split("<think>用户在打招呼</think></think>你好！");
        assert_eq!(split.text, "你好！");
        assert_eq!(split.thinking, "用户在打招呼");
    }

    /// 断流：只有一个开标签，剩下的都该算思考，而不是留在正文里当怪字。
    #[test]
    fn unclosed_tag_swallows_the_rest() {
        let split = split("先说一句<think>然后断了");
        assert_eq!(split.text, "先说一句");
        assert_eq!(split.thinking, "然后断了");
    }

    #[test]
    fn accepts_thinking_and_reasoning_spellings() {
        assert_eq!(split("<thinking>a</thinking>答案").thinking, "a");
        assert_eq!(split("<reasoning>b</reasoning>答案").thinking, "b");
    }

    #[test]
    fn multiple_blocks_are_joined() {
        let split = split("<think>一</think>中间<think>二</think>结尾");
        assert_eq!(split.text, "中间结尾");
        assert_eq!(split.thinking, "一\n二");
    }

    /// 只认这三种标签；别的标签原样留着（宁可显示怪字，也不吞内容）。
    #[test]
    fn unknown_tags_stay_in_the_text() {
        let split = split("<tool>别动</tool>");
        assert_eq!(split.text, "<tool>别动</tool>");
        assert!(split.thinking.is_empty());
    }
}
