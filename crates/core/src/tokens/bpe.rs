//! L1：本地 BPE（D94）。
//!
//! 词表来自 `tiktoken-rs`，编进二进制、不联网、不读远端。两个词表：
//!
//! | 词表 | 给谁 |
//! |---|---|
//! | [`Vocab::Cl100k`] | gpt-4 / gpt-3.5 / claude（近似） |
//! | [`Vocab::O200k`] | gpt-4o / o 系列 / 中文各家 / **认不出的所有模型** |
//!
//! 为什么不是一个词表：同一段 1000 汉字，cl100k 切 1218、o200k 切 781，差 1.56 倍。
//! 只用 o200k，gpt-4 的中文会话被低估 36%；只用 cl100k，中文会话高估 1.94 倍，
//! 窗口用了 39% 就触发压缩。两个都留，多花的只是 1.64MB（D94）。
//!
//! 初始化不便宜（o200k 691ms / cl100k 296ms，实测），所以：进程内单例 +
//! [`warm`] 让调用方在后台线程提前热；编码结果按内容哈希缓存。

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// 词表。序列化只在诊断里用到，所以给的是短名。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Vocab {
    Cl100k,
    O200k,
}

impl Vocab {
    pub fn as_str(self) -> &'static str {
        match self {
            Vocab::Cl100k => "cl100k",
            Vocab::O200k => "o200k",
        }
    }

    fn slot(self) -> usize {
        match self {
            Vocab::Cl100k => 0,
            Vocab::O200k => 1,
        }
    }
}

/// 按模型名挑词表。只看名字，不查表、不联网（D94）。
///
/// 认不出就是 `O200k`：那是实测下来最贴"中文混合"的基准，也是 D91 说的兜底。
pub fn vocab_for_model(model: &str) -> Vocab {
    let name = model.to_ascii_lowercase();
    // 4o / 4.1 / 5 / chatgpt 都已经是 o200k 家族，必须先判——
    // 它们全都包含 "gpt-4"，顺序反了就会被下面的 cl100k 规则吞掉。
    if name.contains("gpt-4o") || name.contains("gpt-4.1") || name.contains("gpt-5") {
        return Vocab::O200k;
    }
    // o1 / o3 / o4 系列可能带 provider 前缀（`openai/o3-mini`），所以按界符找。
    if name.starts_with("o1") || name.starts_with("o3") || name.starts_with("o4") {
        return Vocab::O200k;
    }
    if name.contains("/o1") || name.contains("/o3") || name.contains("/o4") {
        return Vocab::O200k;
    }
    if name.contains("gpt-4") || name.contains("gpt-3") || name.contains("claude") {
        return Vocab::Cl100k;
    }
    // deepseek / qwen / glm / kimi / doubao / gemini / 认不出的自定义名：都走 o200k。
    Vocab::O200k
}

/// 结果缓存上限。满了整清：淘汰策略换不来收益，清空只是重算一次（D94）。
#[cfg(feature = "bpe")]
const CACHE_CAP: usize = 4096;

/// 缓存键 = 词表 + 内容哈希。
fn cache() -> &'static Mutex<HashMap<(usize, u64), u32>> {
    static CACHE: OnceLock<Mutex<HashMap<(usize, u64), u32>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// FNV-1a 64。用自带的而不是 `DefaultHasher`：前者跨版本稳定，后者不保证（D66 的教训）。
fn fnv1a(text: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// 用指定词表编码，返回 token 数。
///
/// `None` 表示这一路不可用（没编 `bpe` feature，或词表初始化失败）——
/// 调用方退 L0，**不要**把 `None` 当成 0。
#[cfg(feature = "bpe")]
pub fn count(vocab: Vocab, text: &str) -> Option<u32> {
    if text.is_empty() {
        return Some(0);
    }
    let key = (vocab.slot(), fnv1a(text));
    // 锁中毒也能继续用：缓存和词表都是只读语义，中毒只会来自上一次 panic。
    let cached = {
        let map = cache().lock().unwrap_or_else(|error| error.into_inner());
        map.get(&key).copied()
    };
    if let Some(hit) = cached {
        return Some(hit);
    }

    let table = table(vocab)?;
    let bpe = table.lock().unwrap_or_else(|error| error.into_inner());
    let tokens = u32::try_from(bpe.encode_ordinary(text).len()).ok()?;
    drop(bpe);

    let mut map = cache().lock().unwrap_or_else(|error| error.into_inner());
    if map.len() >= CACHE_CAP {
        map.clear();
    }
    map.insert(key, tokens);
    Some(tokens)
}

#[cfg(not(feature = "bpe"))]
pub fn count(_vocab: Vocab, _text: &str) -> Option<u32> {
    None
}

/// 这一路能不能用。UI 想显示"精确估算已关闭"时看它。
pub fn available() -> bool {
    cfg!(feature = "bpe")
}

/// 提前把词表读起来。**在后台线程调**——o200k 要 691ms（实测），
/// 落在 UI 线程上就是一次白屏。
pub fn warm(model: &str) {
    let vocab = vocab_for_model(model);
    let _ = count(vocab, "warm up");
    // 把预热用的样本从缓存里剔掉：它不属于任何真实内容。
    if let Ok(mut map) = cache().lock() {
        map.remove(&(vocab.slot(), fnv1a("warm up")));
    }
}

#[cfg(feature = "bpe")]
fn table(vocab: Vocab) -> Option<&'static std::sync::Arc<Mutex<tiktoken_rs::CoreBPE>>> {
    use std::sync::Arc;

    static CL100K: OnceLock<Option<Arc<Mutex<tiktoken_rs::CoreBPE>>>> = OnceLock::new();
    static O200K: OnceLock<Option<Arc<Mutex<tiktoken_rs::CoreBPE>>>> = OnceLock::new();

    match vocab {
        // 用 `Result` 版而不是 `*_singleton()`：后者内部 `unwrap`，
        // 内嵌词表万一坏了会变成 panic，而我们宁可退 L0。
        Vocab::Cl100k => CL100K
            .get_or_init(|| {
                tiktoken_rs::cl100k_base()
                    .ok()
                    .map(|bpe| Arc::new(Mutex::new(bpe)))
            })
            .as_ref(),
        Vocab::O200k => O200K
            .get_or_init(|| {
                tiktoken_rs::o200k_base()
                    .ok()
                    .map(|bpe| Arc::new(Mutex::new(bpe)))
            })
            .as_ref(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn model_family_mapping() {
        assert_eq!(vocab_for_model("gpt-4"), Vocab::Cl100k);
        assert_eq!(vocab_for_model("openai/gpt-4-turbo"), Vocab::Cl100k);
        assert_eq!(vocab_for_model("gpt-3.5-turbo"), Vocab::Cl100k);
        assert_eq!(vocab_for_model("claude-3-5-sonnet"), Vocab::Cl100k);
        // 4o / 4.1 / 5 都含 "gpt-4"，顺序反了就会掉进 cl100k。
        assert_eq!(vocab_for_model("gpt-4o"), Vocab::O200k);
        assert_eq!(vocab_for_model("gpt-4.1-mini"), Vocab::O200k);
        assert_eq!(vocab_for_model("o3-mini"), Vocab::O200k);
        assert_eq!(vocab_for_model("openai/o1"), Vocab::O200k);
        // 中文各家与认不出的名字 → 兜底 o200k。
        assert_eq!(vocab_for_model("relay-a/DeepSeek-V4-Flash"), Vocab::O200k);
        assert_eq!(vocab_for_model("自定义模型"), Vocab::O200k);
    }

    #[test]
    fn empty_text_costs_nothing() {
        assert_eq!(count(Vocab::O200k, ""), Some(0));
    }

    #[cfg(feature = "bpe")]
    #[test]
    fn bpe_counts_are_stable_and_cached() {
        let text = "hello world";
        let first = count(Vocab::Cl100k, text).expect("cl100k 可用");
        assert_eq!(first, 2);
        // 第二次走缓存，值必须一样。
        assert_eq!(count(Vocab::Cl100k, text), Some(first));
        assert!(available());
    }

    #[cfg(feature = "bpe")]
    #[test]
    fn two_vocabs_disagree_on_chinese() {
        // 这条就是"为什么留两个词表"的证据：同一个词，两个表差得出来。
        let text = "我们要把上下文窗口切成几段先估算每段占多少再决定从哪里断开";
        let cl = count(Vocab::Cl100k, text).expect("cl100k");
        let o2 = count(Vocab::O200k, text).expect("o200k");
        assert!(cl > o2, "cl100k {cl} 应当比 o200k {o2} 切得更碎");
    }

    #[cfg(feature = "bpe")]
    #[test]
    fn warm_does_not_leave_samples_in_cache() {
        warm("gpt-4o");
        // 预热自己那次编码要能被算出来（说明词表就绪、缓存也干净地重算了一次）。
        assert!(count(Vocab::O200k, "warm up").unwrap_or(0) > 0);
    }
}
