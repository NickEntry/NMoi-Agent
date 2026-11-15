//! 「上游说上下文超了」的判定（D335）。
//!
//! 为什么单独一个模块：`OverflowHint` 只是给用户看的一句线索，而**撤回一条工具结果**是不可逆的界面动作。
//! 动手之前必须先分得清"确定"与"也许"。所以这里只回答三档：
//! `No` / `Sure(为什么)` / `Maybe(为什么)`，而且只拿 `Sure` 去动手。
//!
//! 三处防误检：
//!
//! - **状态码先行**：只有 400 / 413 / 422 才可能，401/403/404/429/5xx 一律 `No`
//!   （429 是限流、401 是钥匙，把它们当成超窗会去撤一条无辜的结果）。
//! - **只看 `error.message`，不看原始 body**：中转常把整个请求回显进 body，
//!   提示词里出现过的"context length"会立刻变成假阳性。
//! - **要成对**：光有 `context` 或光有 `limit` 都不够；`context` + `too long/exceed` 才算数。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Overflow {
    No,
    /// 措辞/代码直接指名上下文超限。**只有它才能触发撤回。**
    Sure(&'static str),
    /// 与超窗可能有关，也可能是别的参数错。只记录，不动手。
    Maybe(&'static str),
}

impl Overflow {
    pub fn is_sure(&self) -> bool {
        matches!(self, Overflow::Sure(_))
    }

    pub fn is_some(&self) -> bool {
        !matches!(self, Overflow::No)
    }

    pub fn matched(&self) -> Option<&'static str> {
        match self {
            Overflow::No => None,
            Overflow::Sure(why) | Overflow::Maybe(why) => Some(why),
        }
    }
}

const STATUSES: &[u16] = &[400, 413, 422];

const CODES: &[&str] = &[
    "context_length_exceeded",
    "context_window_exceeded",
    "prompt_too_long",
    "request_too_large",
    "string_above_max_length",
    "tokens_exceeded",
    "too_many_tokens",
    "input_too_long",
];

const PHRASES: &[&str] = &[
    "maximum context length",
    "context length exceeded",
    "context length has exceeded",
    "exceeds the maximum context",
    "context window exceeded",
    "exceeds the context window",
    "prompt is too long",
    "input is too long",
    "too many tokens",
    "reduce the length of the messages",
    "reduce the length of your messages",
    "上下文长度",
    "超过最大上下文",
    "请求内容过长",
];

/// 只收"确实在说上下文"的词。`tokens` 不收：`max_tokens: must be >= 1` 这类参数错会被它误伤。
const SUBJECTS: &[&str] = &["context", "prompt", "input", "message", "上下文", "提示词"];

const HARD: &[&str] = &[
    "too long",
    "exceed",
    "over the limit",
    "greater than the max",
    "超过",
    "超出",
];

const SOFT: &[&str] = &["limit", "window", "max", "上限", "限制"];

/// 实测过、但措辞里根本没提上下文的两种失败（`docs/design/context-experiments-report.md` §1.4）。
/// 它们只能是 `Maybe`：拿它们当判据会误伤所有参数错误。
const WEAK: &[&str] = &["the param is invalid", "all models failed"];

pub fn classify(
    status: u16,
    code: Option<&str>,
    error_type: Option<&str>,
    message: &str,
) -> Overflow {
    if !STATUSES.contains(&status) {
        return Overflow::No;
    }
    for id in [code, error_type].into_iter().flatten() {
        let id = id.to_lowercase();
        if CODES
            .iter()
            .any(|known| id == *known || id.ends_with(known))
        {
            return Overflow::Sure("error.code");
        }
    }
    let text: String = message
        .chars()
        .take(2000)
        .collect::<String>()
        .to_lowercase();
    if text.trim().is_empty() {
        return if status == 413 {
            Overflow::Sure("413")
        } else {
            Overflow::No
        };
    }
    for phrase in PHRASES {
        if text.contains(phrase) {
            return Overflow::Sure(phrase);
        }
    }
    let subject = SUBJECTS.iter().any(|word| text.contains(word));
    let hard = HARD.iter().any(|word| text.contains(word));
    if subject && hard {
        return Overflow::Sure("上下文 + 超限");
    }
    if subject && SOFT.iter().any(|word| text.contains(word)) {
        return Overflow::Maybe("上下文 + 上限（没说是超了）");
    }
    if status == 413 && hard {
        return Overflow::Sure("413 + 超限");
    }
    for weak in WEAK {
        if text.contains(weak) {
            return Overflow::Maybe(weak);
        }
    }
    Overflow::No
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(status: u16, message: &str) -> Overflow {
        classify(status, None, None, message)
    }

    #[test]
    fn 明说上下文太长就算确定() {
        assert!(at(400, "your context length has exceeded the maximum limit").is_sure());
        assert!(at(400, "prompt is too long: 210000 tokens > 200000 maximum").is_sure());
        assert!(at(400, "This model's maximum context length is 8192 tokens").is_sure());
        assert!(
            !at(413, "request too large").is_sure(),
            "413 + 没有超限字样：交给空正文那条判据"
        );
        assert!(at(413, "").is_sure());
    }

    #[test]
    fn 错误代码点名就算确定() {
        let hit = classify(400, Some("context_length_exceeded"), None, "bad request");
        assert!(hit.is_sure());
        let hit = classify(422, Some("prompt_too_long"), None, "bad request");
        assert!(hit.is_sure());
    }

    #[test]
    fn 只有一半线索的只算也许() {
        assert_eq!(
            at(400, "context window is 128000"),
            Overflow::Maybe("上下文 + 上限（没说是超了）")
        );
        assert!(!at(400, "context window is 128000").is_sure());
    }

    #[test]
    fn 参数错与钥匙错不算超窗() {
        assert_eq!(at(400, "invalid model id"), Overflow::No);
        assert_eq!(at(400, "max_tokens: must be >= 1"), Overflow::No);
        assert_eq!(
            at(401, "context length exceeded"),
            Overflow::No,
            "钥匙错就是钥匙错"
        );
        assert_eq!(at(429, "too many tokens"), Overflow::No, "限流不是超窗");
        assert_eq!(at(500, "maximum context length"), Overflow::No);
        assert_eq!(
            at(200, "context length exceeded"),
            Overflow::No,
            "成功回包不看这段"
        );
    }

    #[test]
    fn 回显进来的提示词不算证据() {
        // 中转把请求回显进 message，提示词里恰好有这句话：状态码对、措辞也对，
        // 但那是**我们自己的字**，不是上游在说超窗。只有成对规则能挡住纯 context 的单字。
        assert_eq!(at(400, "invalid value for temperature"), Overflow::No);
        assert!(!at(400, "context").is_sure());
    }

    #[test]
    fn 实测那两条没提上下文的只能算也许() {
        assert!(matches!(
            at(400, "the param is invalid. Please try with a valid param."),
            Overflow::Maybe(_)
        ));
        assert!(matches!(at(400, "all models failed"), Overflow::Maybe(_)));
    }

    #[test]
    fn 中文措辞也认() {
        assert!(at(400, "请求内容过长：上下文长度超过上限").is_sure());
    }
}
