//! 退化检测（设计文档 §3.4.1.1）。
//!
//! 清理退化思考不是省 token，是**打断一个自我强化的回路**：上下文里的模式会成为
//! 下一次生成的先验。留着一轮不清理，下一轮就多一分继续空转的概率。
//!
//! 这里只实现机械可测的那部分：n-gram 连续重复、词汇多样性、跨轮相似度代理。
//! 语义卡死需要语义信号（连续几轮说同一件事、无新实体），属于策略层输入，
//! 不由这一层断言。

use std::collections::{HashMap, HashSet, VecDeque};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DegenerateConfig {
    /// n-gram 长度（词计）。
    pub n: usize,
    /// 同一 n-gram 连续出现多少次算退化（§3.9 默认 20）。
    pub min_repeats: usize,
    /// 文本短于此长度不做判定（§3.9 默认 2k token，这里用词数近似）。
    pub min_words: usize,
}

impl Default for DegenerateConfig {
    fn default() -> Self {
        Self {
            n: 5,
            min_repeats: 20,
            min_words: 2_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Repeat {
    /// 起始词下标。
    pub start: usize,
    /// n-gram 长度。
    pub ngram_len: usize,
    /// 连续重复次数（2 表示同一串出现两次）。
    pub repeats: usize,
    pub ngram: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DegenerateSignal {
    pub repeat: Repeat,
    pub words: usize,
    /// 不同 n-gram 数 / 总 n-gram 数。熵骤降的廉价代理。
    pub diversity: f64,
}

fn tokenize(text: &str) -> Vec<String> {
    text.split_whitespace().map(|w| w.to_string()).collect()
}

/// 在整段文本里找第一处连续重复的 n-gram。
pub fn find_repeat(words: &[String], n: usize, min_repeats: usize) -> Option<Repeat> {
    if n == 0 || min_repeats < 2 || words.len() < n * 2 {
        return None;
    }
    let mut i = 0;
    while i + n * 2 <= words.len() {
        let mut repeats = 1;
        while i + (repeats + 1) * n <= words.len()
            && words[i..i + n] == words[i + repeats * n..i + (repeats + 1) * n]
        {
            repeats += 1;
        }
        if repeats >= min_repeats {
            return Some(Repeat {
                start: i,
                ngram_len: n,
                repeats,
                ngram: words[i..i + n].to_vec(),
            });
        }
        i += 1;
    }
    None
}

/// n-gram 多样性：不同 n-gram / 总数。
pub fn diversity(words: &[String], n: usize) -> f64 {
    if n == 0 || words.len() < n {
        return 1.0;
    }
    let total = words.len() - n + 1;
    let mut seen: HashSet<&[String]> = HashSet::new();
    for i in 0..total {
        seen.insert(&words[i..i + n]);
    }
    seen.len() as f64 / total as f64
}

/// 整段文本的退化判定。
pub fn inspect(text: &str, config: &DegenerateConfig) -> Option<DegenerateSignal> {
    let words = tokenize(text);
    if words.len() < config.min_words {
        return None;
    }
    let repeat = find_repeat(&words, config.n, config.min_repeats)?;
    Some(DegenerateSignal {
        repeat,
        words: words.len(),
        diversity: diversity(&words, config.n),
    })
}

/// 流式检测器：边生成边喂词，命中即返回信号（对应"事中止损"那一层）。
///
/// 只保留最近 n 个词与连续相同 n-gram 的计数，内存 O(n)。
#[derive(Debug, Clone)]
pub struct StreamMonitor {
    config: DegenerateConfig,
    window: VecDeque<String>,
    last_ngram: Option<Vec<String>>,
    streak: usize,
    words: usize,
    emitted: usize,
}

impl StreamMonitor {
    pub fn new(config: DegenerateConfig) -> Self {
        Self {
            config,
            window: VecDeque::with_capacity(config.n.max(1)),
            last_ngram: None,
            streak: 0,
            words: 0,
            emitted: 0,
        }
    }

    /// 喂入一个词。命中退化时返回信号；同一次连续重复只报一次。
    pub fn push(&mut self, word: &str) -> Option<DegenerateSignal> {
        self.window.push_back(word.to_string());
        while self.window.len() > self.config.n.max(1) {
            self.window.pop_front();
        }
        self.words += 1;
        if self.config.n == 0 || self.window.len() < self.config.n {
            return None;
        }

        let current: Vec<String> = self.window.iter().cloned().collect();
        match &self.last_ngram {
            Some(previous) if previous == &current => {
                self.streak += 1;
            }
            _ => {
                self.streak = 1;
                self.emitted = 0;
            }
        }
        self.last_ngram = Some(current.clone());

        if self.streak >= self.config.min_repeats && self.emitted == 0 {
            self.emitted = 1;
            return Some(DegenerateSignal {
                repeat: Repeat {
                    start: self.words.saturating_sub(self.streak * self.config.n),
                    ngram_len: self.config.n,
                    repeats: self.streak,
                    ngram: current,
                },
                words: self.words,
                diversity: 0.0,
            });
        }
        None
    }

    pub fn words_seen(&self) -> usize {
        self.words
    }
}

/// 两个词袋的 Jaccard 相似度，用作"连续轮次语义相似度"的廉价代理。
///
/// 它能抓住"用词不同但内容原地打转"的一部分情况，但抓不住真正的语义卡死——
/// 那需要实体抽取或 embedding，见 §3.4.1.2。
pub fn word_overlap(a: &str, b: &str) -> f64 {
    let set_a: HashSet<String> = tokenize(a).into_iter().collect();
    let set_b: HashSet<String> = tokenize(b).into_iter().collect();
    if set_a.is_empty() && set_b.is_empty() {
        return 1.0;
    }
    let inter = set_a.intersection(&set_b).count();
    let union = set_a.union(&set_b).count();
    if union == 0 {
        0.0
    } else {
        inter as f64 / union as f64
    }
}

/// 词频表，供"新实体引入速率"这类指标使用。
pub fn word_frequencies(text: &str) -> HashMap<String, usize> {
    let mut map = HashMap::new();
    for word in tokenize(text) {
        *map.entry(word).or_insert(0) += 1;
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(min_words: usize) -> DegenerateConfig {
        DegenerateConfig {
            n: 2,
            min_repeats: 3,
            min_words,
        }
    }

    #[test]
    fn detects_consecutive_ngram_repeat() {
        let words: Vec<String> = "a b a b a b a b"
            .split_whitespace()
            .map(String::from)
            .collect();
        let repeat = find_repeat(&words, 2, 3).unwrap();
        assert_eq!(repeat.ngram, vec!["a", "b"]);
        assert_eq!(repeat.repeats, 4);
    }

    #[test]
    fn ignores_short_text() {
        assert!(inspect("hmm hmm hmm", &cfg(100)).is_none());
    }

    #[test]
    fn flags_degenerate_thinking() {
        let short = "hmm hmm hmm";
        assert!(inspect(short, &cfg(3)).is_none());

        // 单词级退化：同一 n-gram（n=1）连续重复。
        let stuck = "Hmm ".repeat(40);
        let signal = inspect(
            &stuck,
            &DegenerateConfig {
                n: 1,
                min_repeats: 20,
                min_words: 10,
            },
        )
        .expect("连续 Hmm 必须被判为退化");
        assert_eq!(signal.repeat.ngram, vec!["Hmm"]);

        // 正常段落不应命中。
        let varied = "先读配置，再跑测试，最后把结果写进报告。端口 7411，日志前缀 ZX-。";
        assert!(inspect(varied, &cfg(3)).is_none());
    }

    #[test]
    fn stream_monitor_emits_once_per_run() {
        let mut monitor = StreamMonitor::new(cfg(0));
        let mut hits = 0;
        for _ in 0..10 {
            for word in ["hmm", "hmm"] {
                if monitor.push(word).is_some() {
                    hits += 1;
                }
            }
        }
        assert_eq!(hits, 1);
        assert!(monitor.words_seen() >= 10);
    }

    #[test]
    fn diversity_drops_for_repetition() {
        let repeated: Vec<String> = "a b a b a b a b a b"
            .split_whitespace()
            .map(String::from)
            .collect();
        let varied: Vec<String> = "a b c d e f g h i j"
            .split_whitespace()
            .map(String::from)
            .collect();
        assert!(diversity(&repeated, 2) < diversity(&varied, 2));
    }

    #[test]
    fn word_overlap_is_a_proxy_not_semantics() {
        assert!((word_overlap("a b c", "a b c") - 1.0).abs() < 1e-9);
        assert_eq!(word_overlap("a b", "c d"), 0.0);
        assert!(word_overlap("read the file", "read file the") > 0.9);
    }
}
