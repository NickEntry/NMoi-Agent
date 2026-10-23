//! token 估算与按 token 预算切分。
//!
//! 三级（D91、D94–D96）：
//!
//! | 级别 | 是什么 | 用在哪 |
//! |---|---|---|
//! | L0 | [`estimate_tokens`]：ASCII 4 字符 ≈ 1、CJK 1 字符 ≈ 1 | 流式增量、兜底 |
//! | L1 | [`bpe`]：本地 BPE（tiktoken 词表），按模型家族挑词表 | 造请求前、压缩判据 |
//! | L2 | [`calibrate`]：用端点回的 `usage.prompt_tokens` 反推比例因子 | 每轮结束 |
//!
//! 上层只认 [`Estimator`]：它决定走 L1 还是 L0、拿到哪个级别的校准、要不要乘未校准的安全系数。
//! 直接调 L0 的地方（流式增量）不需要 L2——那里要的是"大概涨了多少"，不是"窗口还剩多少"。
//!
//! 路由与预算逻辑不依赖具体分词方式，只依赖"这个字符串要花多少 token"。

mod bpe;
mod calibrate;
mod class;
mod estimate;

pub use bpe::{available, vocab_for_model, warm, Vocab};
pub use calibrate::{build_key, parse_key, Calibration};
pub use class::{classify, Class, Classifier};
pub use estimate::{Estimate, Estimator, Level};

/// 启发式估算：ASCII 字母数字按 4 字符 ≈ 1 token，CJK 与符号按 1 字符 ≈ 1 token。
///
/// 中日韩文本的 BPE 合并率低，按字符计比按字节计更接近真实值；纯英文偏保守
/// （估高约 10–20%），对"安全阀"这个用途来说，宁可高估。
pub fn estimate_tokens(text: &str) -> u32 {
    let mut tokens: f64 = 0.0;
    let mut ascii_run: u32 = 0;

    let flush = |run: &mut u32, tokens: &mut f64| {
        if *run > 0 {
            *tokens += (f64::from(*run) / 4.0).ceil();
            *run = 0;
        }
    };

    for ch in text.chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' {
            ascii_run += 1;
        } else if ch.is_whitespace() {
            flush(&mut ascii_run, &mut tokens);
        } else if ch.is_ascii() {
            flush(&mut ascii_run, &mut tokens);
            tokens += 0.5;
        } else {
            flush(&mut ascii_run, &mut tokens);
            tokens += 1.0;
        }
    }
    flush(&mut ascii_run, &mut tokens);
    tokens.ceil() as u32
}

/// 取前 `token_budget` 个 token 对应的前缀，落在字符边界上。
pub fn head_prefix(text: &str, token_budget: u32) -> &str {
    if token_budget == 0 {
        return "";
    }
    let mut spent: f64 = 0.0;
    let mut ascii_run: u32 = 0;
    let budget = f64::from(token_budget);

    for (offset, ch) in text.char_indices() {
        let cost = if ch.is_ascii_alphanumeric() || ch == '_' {
            ascii_run += 1;
            if ascii_run % 4 == 0 {
                1.0
            } else {
                0.0
            }
        } else if ch.is_whitespace() {
            ascii_run = 0;
            0.0
        } else {
            ascii_run = 0;
            if ch.is_ascii() {
                0.5
            } else {
                1.0
            }
        };
        spent += cost;
        if spent > budget {
            return &text[..offset];
        }
    }
    text
}

/// 取后 `token_budget` 个 token 对应的后缀，落在字符边界上。
pub fn tail_suffix(text: &str, token_budget: u32) -> &str {
    if token_budget == 0 {
        return "";
    }
    let mut spent: f64 = 0.0;
    let mut ascii_run: u32 = 0;
    let budget = f64::from(token_budget);
    let mut start = text.len();

    for (offset, ch) in text.char_indices().rev() {
        let cost = if ch.is_ascii_alphanumeric() || ch == '_' {
            ascii_run += 1;
            if ascii_run % 4 == 0 {
                1.0
            } else {
                0.0
            }
        } else if ch.is_whitespace() {
            ascii_run = 0;
            0.0
        } else {
            ascii_run = 0;
            if ch.is_ascii() {
                0.5
            } else {
                1.0
            }
        };
        spent += cost;
        start = offset;
        if spent > budget {
            return &text[start + ch.len_utf8()..];
        }
    }
    &text[start..]
}

/// token 数的展示形式：`950` / `8.2k` / `1.4M`。
pub fn format_k(tokens: u32) -> String {
    if tokens < 1_000 {
        return tokens.to_string();
    }
    if tokens < 1_000_000 {
        return format!("{:.1}k", f64::from(tokens) / 1_000.0);
    }
    format!("{:.1}M", f64::from(tokens) / 1_000_000.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn estimates_ascii_and_cjk() {
        assert_eq!(estimate_tokens(""), 0);
        assert_eq!(estimate_tokens("abcd"), 1);
        assert_eq!(estimate_tokens("abcdefgh"), 2);
        // 3 个汉字各 1 token；"7411" 是一个 4 字符 ASCII run，按 4 字符 ≈ 1 token 计 → 1
        assert_eq!(estimate_tokens("端口是7411"), 4);
        assert!(estimate_tokens("hello world") >= 2);
    }

    #[test]
    fn prefix_and_suffix_land_on_char_boundaries() {
        let text = "第一段内容\n第二段内容";
        let head = head_prefix(text, 3);
        assert!(text.starts_with(head));
        assert!(head.chars().count() <= 3);

        let tail = tail_suffix(text, 3);
        assert!(text.ends_with(tail));
        assert!(tail.chars().count() <= 4);
    }

    #[test]
    fn format_scales() {
        assert_eq!(format_k(950), "950");
        assert_eq!(format_k(8_200), "8.2k");
        assert_eq!(format_k(1_400_000), "1.4M");
    }
}
