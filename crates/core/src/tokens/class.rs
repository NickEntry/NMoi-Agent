//! 文本的内容类（D95）。
//!
//! 只为一件事存在：**估算的偏差随内容走**。L0 是 0.62–1.32（差 2.1 倍），
//! L1 压到 0.80–1.06（差 1.3 倍）——小了一半，但两级都得按类分桶（D95）。
//!
//! 分类只看字符分布，不做语法判断：它要在一份 32k token 的请求上跑，
//! 花的时间必须比"算错一次"便宜。

use serde::{Deserialize, Serialize};

/// 内容类。也是校准的桶（D95）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Class {
    /// 以 ASCII 字母数字为主（英文散文、日志）。
    Ascii,
    /// CJK 过半（中文、日文、韩文）。
    Cjk,
    /// 中英夹混。
    Mixed,
    /// 结构符号密集（JSON、代码、工具输出）。
    Code,
}

impl Class {
    pub fn as_str(self) -> &'static str {
        match self {
            Class::Ascii => "ascii",
            Class::Cjk => "cjk",
            Class::Mixed => "mixed",
            Class::Code => "code",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "ascii" => Some(Class::Ascii),
            "cjk" => Some(Class::Cjk),
            "mixed" => Some(Class::Mixed),
            "code" => Some(Class::Code),
            _ => None,
        }
    }
}

/// CJK 判定：汉字（含扩展 A、兼容区）、假名、谚文。
fn is_cjk(ch: char) -> bool {
    matches!(ch as u32,
        0x3400..=0x4DBF      // 扩展 A
        | 0x4E00..=0x9FFF    // 基本区
        | 0xF900..=0xFAFF    // 兼容汉字
        | 0x3040..=0x30FF    // 平假名 + 片假名
        | 0x31F0..=0x31FF
        | 0xAC00..=0xD7AF    // 谚文音节
        | 0x20000..=0x2FA1F  // 扩展 B 及以后
    )
}

/// 增量累加器：一次请求是多段文本拼起来的，类要按整体判，不是按段加权平均。
#[derive(Debug, Default, Clone, Copy)]
pub struct Classifier {
    cjk: u64,
    ascii_alnum: u64,
    punct: u64,
}

/// 结构符号占比超过这个数就算 `Code`（实测：JSON 样例 ~0.33，散文 <0.08）。
const CODE_PUNCT_PCT: u64 = 12;
/// ASCII 字母数字占非空白字符的比例超过它就当 `Ascii`。
const ASCII_PCT: u64 = 85;
/// CJK 过半才算 `Cjk`（不到一半时下面还要看符号密度）。
const CJK_PCT: u64 = 50;

impl Classifier {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, text: &str) {
        for ch in text.chars() {
            if ch.is_whitespace() {
                continue;
            }
            if is_cjk(ch) {
                self.cjk += 1;
            } else if ch.is_ascii_alphanumeric() || ch == '_' {
                self.ascii_alnum += 1;
            } else {
                // 全角标点、括号、引号、运算符都在这里：它们才是最像代码的信号。
                self.punct += 1;
            }
        }
    }

    pub fn class(&self) -> Class {
        let total = self.cjk + self.ascii_alnum + self.punct;
        if total == 0 {
            // 空文本 / 纯空白：随便给一个，反正估出来也是 0。
            return Class::Mixed;
        }
        if self.cjk * 100 >= total * CJK_PCT {
            return Class::Cjk;
        }
        if self.punct * 100 >= total * CODE_PUNCT_PCT {
            return Class::Code;
        }
        if self.ascii_alnum * 100 >= total * ASCII_PCT {
            return Class::Ascii;
        }
        Class::Mixed
    }
}

/// 单段文本的分类。
pub fn classify(text: &str) -> Class {
    let mut classifier = Classifier::new();
    classifier.push(text);
    classifier.class()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_and_whitespace_are_harmless() {
        assert_eq!(classify(""), Class::Mixed);
        assert_eq!(classify("   \n\t "), Class::Mixed);
    }

    #[test]
    fn english_prose_is_ascii() {
        let text = "the quick brown fox jumps over a lazy dog while parsing structured output";
        assert_eq!(classify(text), Class::Ascii);
    }

    #[test]
    fn chinese_is_cjk() {
        assert_eq!(
            classify("我们要把上下文窗口切成几段，先估算每段占多少。"),
            Class::Cjk
        );
    }

    #[test]
    fn mixed_script_is_mixed() {
        let text = "第 0 段：estimate_tokens 把 ASCII 按 4 字符折 1 个 token，CJK 按 1 字符 1 个。";
        assert_eq!(classify(text), Class::Mixed);
    }

    #[test]
    fn json_is_code() {
        let text = r#"{"index": 0, "role": "assistant", "content": "the quick brown fox"}"#;
        assert_eq!(classify(text), Class::Code);
    }

    #[test]
    fn accumulator_classifies_the_whole_request() {
        // 单看哪一段都不像混排，合起来才是——校准要按整体判，不是按段判。
        let mut classifier = Classifier::new();
        classifier.push("the quick brown fox jumps over lazy dogs and counts tokens");
        classifier.push("我们要把上下文窗口切成几段先估算每段占多少再决定从哪里断开");
        assert_eq!(classifier.class(), Class::Mixed);
    }

    #[test]
    fn class_strings_round_trip() {
        for class in [Class::Ascii, Class::Cjk, Class::Mixed, Class::Code] {
            assert_eq!(Class::parse(class.as_str()), Some(class));
        }
        assert_eq!(Class::parse("binary"), None);
    }
}
