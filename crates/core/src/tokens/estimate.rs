//! 估算器的对外门面：决定走 L1 还是 L0、套哪一级的校准、要不要带安全系数。
//!
//! 上层只该看到 [`Estimate`] 和 [`Estimator`]，不该自己去调 `estimate_tokens` 再手算系数——
//! 那样每个调用点都会自己决定"要不要乘 1.3"，而这种事一旦分叉，窗口判定就再也对不上了。

use serde::{Deserialize, Serialize};

use super::bpe::{self, Vocab};
use super::calibrate::{bucket, Calibration, MIN_ESTIMATE};
use super::class::{classify, Class, Classifier};
use super::estimate_tokens;

/// 估算级别。存进校准文档，所以要有稳定的字符串形式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Level {
    /// 字符近似。零成本、随内容种类摆动 ±2 倍。
    L0,
    /// 本地 BPE。
    L1,
}

impl Level {
    pub fn as_str(self) -> &'static str {
        match self {
            Level::L0 => "l0",
            Level::L1 => "l1",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "l0" => Some(Level::L0),
            "l1" => Some(Level::L1),
            _ => None,
        }
    }

    /// 未校准时的安全系数（D96）。只该作用在**绝对线判定**上，不该写进展示值。
    ///
    /// 数字来自对账实测的最坏低估幅度：L0 1.32（中英混排）、L1 1.056（同一批文本）。
    pub fn safety(self) -> f64 {
        match self {
            Level::L0 => 1.3,
            Level::L1 => 1.1,
        }
    }
}

/// 一次估算的结果。三个数各有用处，别混：
///
/// - [`raw`](Self::raw)：这一级算出来的原始值（校准前）；
/// - [`tokens`](Self::tokens)：乘过校准因子，给用户看、给 breakdown 用；
/// - [`budgeted`](Self::budgeted)：再乘未校准安全系数，**只给绝对线判定用**。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Estimate {
    pub raw: u32,
    pub tokens: u32,
    pub level: Level,
    /// 内容类。也是校准桶（D95：两级都按类分）。
    pub class: Class,
    pub vocab: Vocab,
    /// 实际乘上去的因子。没校准过就是 1.0（安全系数不在这里）。
    pub factor: f64,
    pub calibrated: bool,
}

impl Estimate {
    /// 判定窗口用。未校准时带安全系数，校准之后系数归 1.0（D96）。
    pub fn budgeted(&self) -> u64 {
        let safety = if self.calibrated {
            1.0
        } else {
            self.level.safety()
        };
        ((f64::from(self.raw) * self.factor * safety).round()) as u64
    }
}

/// 估算器。借模型名与一批校准——它自己不持有、不落库，写回是调用方的事。
#[derive(Debug, Clone, Copy)]
pub struct Estimator<'a> {
    model: &'a str,
    calibration: &'a [Calibration],
    vocab: Vocab,
}

impl<'a> Estimator<'a> {
    /// 没有任何校准时的估算器（冷启动、新模型）。
    pub fn new(model: &'a str, calibration: &'a [Calibration]) -> Self {
        Self {
            model,
            calibration,
            vocab: bpe::vocab_for_model(model),
        }
    }

    pub fn model(&self) -> &str {
        self.model
    }

    pub fn vocab(&self) -> Vocab {
        self.vocab
    }

    /// 这个估算器**实际**会用哪一级。
    ///
    /// `bpe` 没编进来时 `count` 一律给 `None`，于是整条链路静默退 L0；
    /// 这条接口存在的意义是让调用方（面板、报告）能把"退化了"说出来，
    /// 而不是把 L0 的数当 L1 展示。
    pub fn level(&self) -> Level {
        match bpe::count(self.vocab, "") {
            Some(_) => Level::L1,
            None => Level::L0,
        }
    }

    /// 单段文本。
    pub fn estimate(&self, text: &str) -> Estimate {
        let (level, raw) = self.raw_of(text);
        self.finish(u64::from(raw), level, classify(text))
    }

    /// 多段拼成的一次请求。类按整体判、原始值直接相加——
    /// 不能对每段各乘一个因子再相加，那样加法会放大舍入误差，而且类被切碎了。
    pub fn estimate_all<'t, I>(&self, texts: I) -> Estimate
    where
        I: IntoIterator<Item = &'t str>,
    {
        let mut total: u64 = 0;
        let mut level = Level::L0;
        let mut classifier = Classifier::new();
        for text in texts {
            let (this_level, raw) = self.raw_of(text);
            level = this_level;
            total += u64::from(raw);
            classifier.push(text);
        }
        self.finish(total, level, classifier.class())
    }

    /// 预算判定用（带安全系数）。
    pub fn budgeted(&self, text: &str) -> u64 {
        self.estimate(text).budgeted()
    }

    /// 收一次真实用量。返回**该写回库**的校准；`None` 表示这个样本被拒
    /// （请求太小 / 端点没回 usage）。
    pub fn observe_usage(&self, real: u64, whole: &Estimate, now: i64) -> Option<Calibration> {
        if real == 0 || u64::from(whole.raw) < MIN_ESTIMATE {
            return None;
        }
        let class = bucket(whole.level, whole.class);
        let mut calibration = self
            .find(whole.level, class)
            .cloned()
            .unwrap_or_else(|| Calibration::new(self.model, whole.level, class, now));
        if calibration.observe(real, u64::from(whole.raw), now) {
            Some(calibration)
        } else {
            None
        }
    }

    fn raw_of(&self, text: &str) -> (Level, u32) {
        match bpe::count(self.vocab, text) {
            Some(tokens) => (Level::L1, tokens),
            // 没编 `bpe` feature，或词表初始化失败：退 L0，并且这个退让是看得见的（level）。
            None => (Level::L0, estimate_tokens(text)),
        }
    }

    fn find(&self, level: Level, class: Class) -> Option<&Calibration> {
        self.calibration
            .iter()
            .find(|cal| cal.model == self.model && cal.level == level && cal.class == class)
    }

    fn finish(&self, raw: u64, level: Level, class: Class) -> Estimate {
        let raw = u32::try_from(raw).unwrap_or(u32::MAX);
        let found = self.find(level, bucket(level, class));
        let calibrated = found.is_some_and(Calibration::is_calibrated);
        let factor = if calibrated {
            found.map_or(1.0, |cal| cal.ratio)
        } else {
            1.0
        };
        let tokens = if calibrated {
            (f64::from(raw) * factor).round() as u32
        } else {
            raw
        };
        Estimate {
            raw,
            tokens,
            level,
            class,
            vocab: self.vocab,
            factor,
            calibrated,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cal(model: &str, level: Level, class: Class, ratio: f64, samples: u32) -> Calibration {
        Calibration {
            model: model.to_string(),
            level,
            class,
            ratio,
            samples,
            updated_at: 0,
        }
    }

    #[test]
    fn level_strings_round_trip() {
        for level in [Level::L0, Level::L1] {
            assert_eq!(Level::parse(level.as_str()), Some(level));
        }
        assert_eq!(Level::parse("l2"), None);
    }

    #[test]
    fn safety_matches_measured_worst_case() {
        assert!((Level::L0.safety() - 1.3).abs() < 1e-9);
        assert!((Level::L1.safety() - 1.1).abs() < 1e-9);
    }

    #[test]
    fn uncalibrated_estimate_carries_the_safety_factor_only_in_budgeted() {
        let estimator = Estimator::new("relay-a/DeepSeek-V4-Flash", &[]);
        let estimate = estimator.estimate("hello world");
        assert!(!estimate.calibrated);
        assert!((estimate.factor - 1.0).abs() < 1e-9);
        // 展示值就是原始值：用户看到的应当是"我估多少"，不是"我怕成多少"。
        assert_eq!(estimate.tokens, estimate.raw);
        let expected = (f64::from(estimate.raw) * estimate.level.safety()).round() as u64;
        assert_eq!(estimate.budgeted(), expected);
    }

    #[test]
    fn calibrated_estimate_uses_the_ratio_and_drops_the_safety_factor() {
        let calibration = vec![cal("m", Level::L1, Class::Ascii, 1.4, 4)];
        let estimator = Estimator::new("m", &calibration);
        let estimate = estimator.estimate("hello world");
        assert_eq!(estimate.level, Level::L1, "默认 feature 下应当走 BPE");
        assert!(estimate.calibrated);
        assert_eq!(
            estimate.tokens,
            ((f64::from(estimate.raw) * 1.4).round()) as u32
        );
        assert_eq!(estimate.budgeted(), u64::from(estimate.tokens));
    }

    #[test]
    fn two_samples_are_not_enough_to_bite() {
        let calibration = vec![cal("m", Level::L1, Class::Cjk, 1.4, 2)];
        let estimator = Estimator::new("m", &calibration);
        let text = "我们先估算每段占多少，再决定从哪里断开。".repeat(50);
        let estimate = estimator.estimate(&text);
        assert!(!estimate.calibrated);
        assert_eq!(estimate.tokens, estimate.raw);
        // 没校准：显示值不带系数，判定值带安全系数。
        assert!(estimate.budgeted() > u64::from(estimate.raw));
    }

    #[test]
    fn calibration_for_another_content_class_is_ignored() {
        // 中文会话的因子不能套到英文请求上：L1 的类间偏差还有 1.3 倍（D95）。
        let calibration = vec![cal("m", Level::L1, Class::Cjk, 0.8, 9)];
        let estimator = Estimator::new("m", &calibration);
        let english = estimator.estimate("the quick brown fox jumps over a lazy dog");
        assert_eq!(english.class, Class::Ascii);
        assert!(!english.calibrated);
    }

    #[test]
    fn calibration_for_another_model_is_ignored() {
        let calibration = vec![cal("other", Level::L1, Class::Ascii, 1.4, 9)];
        let estimator = Estimator::new("m", &calibration);
        assert!(!estimator.estimate("hello world").calibrated);
    }

    #[test]
    fn estimate_all_sums_raws_and_classifies_the_whole_request() {
        let estimator = Estimator::new("m", &[]);
        let parts = [
            "the quick brown fox jumps over lazy dogs and counts tokens",
            "我们要把上下文窗口切成几段先估算每段占多少再决定从哪里断开",
        ];
        let whole = estimator.estimate_all(parts);
        let sum: u64 = parts
            .iter()
            .map(|p| u64::from(estimator.estimate(p).raw))
            .sum();
        assert_eq!(u64::from(whole.raw), sum);
        assert_eq!(whole.class, Class::Mixed);
    }

    #[test]
    fn empty_request_costs_nothing() {
        let estimator = Estimator::new("m", &[]);
        let estimate = estimator.estimate_all(std::iter::empty());
        assert_eq!(estimate.raw, 0);
        assert_eq!(estimate.budgeted(), 0);
    }

    #[test]
    fn observe_refuses_small_requests_and_returns_a_bucket_key() {
        let estimator = Estimator::new("m", &[]);
        let short = estimator.estimate("hi");
        assert_eq!(estimator.observe_usage(10, &short, 1), None);

        let long = "我们先估算每段占多少，再决定从哪里断开。".repeat(200);
        let whole = estimator.estimate(&long);
        assert!(u64::from(whole.raw) >= MIN_ESTIMATE);
        let updated = estimator
            .observe_usage(whole.raw as u64 * 2, &whole, 42)
            .expect("收下");
        assert_eq!(updated.samples, 1);
        assert_eq!(updated.class, bucket(whole.level, whole.class));
        assert!(updated.key().starts_with("m|"));
    }

    #[test]
    fn observe_accumulates_onto_the_existing_document() {
        let existing = vec![cal("m", Level::L1, Class::Cjk, 1.0, 3)];
        let estimator = Estimator::new("m", &existing);
        let text = "我们先估算每段占多少，再决定从哪里断开。".repeat(200);
        let whole = estimator.estimate(&text);
        let updated = estimator
            .observe_usage(whole.raw as u64 * 2, &whole, 7)
            .expect("收下");
        assert_eq!(updated.samples, 4, "要在旧文档上累加，不能另起一份");
        assert!(updated.ratio > 1.0);
    }

    /// `level()` 必须和 `estimate()` 真的走的那条路一致：
    /// 二进制没编 `bpe` 时它得说 L0，否则面板会把字符近似的数当 BPE 的数展示。
    #[test]
    fn level_matches_the_path_estimate_takes() {
        let none: [Calibration; 0] = [];
        let estimator = Estimator::new("gpt-4", &none);
        assert_eq!(estimator.level(), estimator.estimate("你好").level);
        if cfg!(feature = "bpe") {
            assert_eq!(estimator.level(), Level::L1);
        } else {
            assert_eq!(estimator.level(), Level::L0);
        }
    }
}
