//! L2：用端点的 `usage.prompt_tokens` 反推比例因子（D95）。
//!
//! 一次真实值校准后面 N 次估算。校准键是 **(模型, 级别, 内容类)**，两级都按类分桶：
//!
//! - L1 的类间偏差实测 0.80–1.06（差 1.3 倍）：比 L0 的 2.1 倍小一半，但**没消失**。
//!   合成一个桶的话，用中文校准出来的因子套到英文上就是低估 21%；
//! - L0 是 0.62–1.32（差 2.1 倍），不分桶必然错，而且错的方向还相反。
//!
//! 分桶的代价是每个桶要各自攒 3 个样本。可接受：一次会话的内容构成基本稳定，
//! 常用的那个桶三轮就收敛，其余桶本来也用不上。
//!
//! 三件事别做错：
//!
//! 1. **样本门槛**：[`MIN_ESTIMATE`] 以下的请求不收。模板与工具 schema 是固定开销，
//!    小请求里它占比一大，比例就虚高，收进来只会让校准变差。
//! 2. **样本数没到 [`MIN_SAMPLES`] 不算校准过**。一个样本碰上端点口径差异就废了。
//! 3. **比例要 clamp**。端点把 cached 也算进 prompt、或请求被截断，都会造出离谱比例。

use serde::{Deserialize, Serialize};

use super::class::Class;
use super::estimate::Level;

/// 少于这么多样本，比例只当参考，不参与估算。
pub const MIN_SAMPLES: u32 = 3;
/// EWMA 的 α。取 0.3：约 7 个样本把旧值的权重压到 10% 以下，够快也够稳。
pub const ALPHA: f64 = 0.3;
pub const RATIO_MIN: f64 = 0.7;
pub const RATIO_MAX: f64 = 1.8;
/// 估算低于这个 token 数就不收样本（固定开销占比 < 15%）。
pub const MIN_ESTIMATE: u64 = 1500;

/// 一份校准。存 `settings` 表的一个文档，`doc_key` 就是 [`Calibration::key`]。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Calibration {
    pub model: String,
    pub level: Level,
    /// 内容类。两级都按它分桶。
    pub class: Class,
    /// 真实 / 估算。
    pub ratio: f64,
    pub samples: u32,
    /// Unix 毫秒，由调用方给：这一层不读墙上时钟。
    pub updated_at: i64,
}

impl Calibration {
    /// 新建一份空校准（`samples = 0`，还没被用过）。
    pub fn new(model: &str, level: Level, class: Class, now: i64) -> Self {
        Self {
            model: model.to_string(),
            level,
            class,
            ratio: 1.0,
            samples: 0,
            updated_at: now,
        }
    }

    pub fn key(&self) -> String {
        build_key(&self.model, self.level, self.class)
    }

    /// 校准过没过门槛。
    pub fn is_calibrated(&self) -> bool {
        self.samples >= MIN_SAMPLES
    }

    /// 按比例换算估算值。没到样本门槛就原样返回——宁可准得保守，也别准得自信。
    pub fn apply(&self, raw: u64) -> u64 {
        if !self.is_calibrated() {
            return raw;
        }
        ((raw as f64) * self.ratio).round() as u64
    }

    /// 收一个样本。返回 `false` 表示这个样本被拒（太小 / 真实值为 0）。
    pub fn observe(&mut self, real: u64, estimated: u64, now: i64) -> bool {
        if real == 0 || estimated < MIN_ESTIMATE {
            return false;
        }
        let ratio = (real as f64 / estimated as f64).clamp(RATIO_MIN, RATIO_MAX);
        self.ratio = if self.samples == 0 {
            ratio
        } else {
            self.ratio * (1.0 - ALPHA) + ratio * ALPHA
        };
        self.samples = self.samples.saturating_add(1);
        self.updated_at = now;
        true
    }
}

/// 校准的桶。现在是恒等——留着这个函数是因为"要不要合并桶"是会再被实测推翻的决定，
/// 合并/拆分的规则只该写在一个地方。
pub fn bucket(_level: Level, class: Class) -> Class {
    class
}

/// `模型|级别|类`。模型名里带 `/`（`provider/model`）没关系，带 `|` 也能解析回来。
///
/// 先把桶算过再拼，免得将来 `bucket` 重新有了归并规则时，`put` 与 `get` 各走一条路。
pub fn build_key(model: &str, level: Level, class: Class) -> String {
    let class = bucket(level, class);
    format!("{model}|{}|{}", level.as_str(), class.as_str())
}

/// 从 `doc_key` 解析回 `(模型, 级别, 类)`。级别/类认不出就 `None`。
pub fn parse_key(key: &str) -> Option<(&str, Level, Class)> {
    let mut parts = key.rsplitn(3, '|');
    let class = Class::parse(parts.next()?)?;
    let level = Level::parse(parts.next()?)?;
    let model = parts.next()?;
    if model.is_empty() {
        return None;
    }
    Some((model, level, class))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn both_levels_keep_the_class() {
        // L1 也分桶：类间偏差 1.3 倍，合成一个桶会让中文的因子污染英文。
        assert_eq!(bucket(Level::L1, Class::Cjk), Class::Cjk);
        assert_eq!(bucket(Level::L0, Class::Cjk), Class::Cjk);
        assert_eq!(
            Calibration::new("m", Level::L1, Class::Code, 1).class,
            Class::Code
        );
    }

    #[test]
    fn key_round_trip() {
        let cal = Calibration::new("relay-a/DeepSeek-V4-Flash", Level::L0, Class::Mixed, 7);
        assert_eq!(cal.key(), "relay-a/DeepSeek-V4-Flash|l0|mixed");
        assert_eq!(
            parse_key(&cal.key()),
            Some(("relay-a/DeepSeek-V4-Flash", Level::L0, Class::Mixed))
        );
    }

    #[test]
    fn key_survives_a_pipe_in_the_model_name() {
        let key = build_key("proxy|legacy/model", Level::L1, Class::Code);
        assert_eq!(
            parse_key(&key),
            Some(("proxy|legacy/model", Level::L1, Class::Code))
        );
    }

    #[test]
    fn keys_carry_level_and_class() {
        assert_eq!(build_key("m", Level::L1, Class::Cjk), "m|l1|cjk");
        assert_eq!(build_key("m", Level::L0, Class::Cjk), "m|l0|cjk");
    }

    #[test]
    fn bad_keys_are_rejected() {
        assert_eq!(parse_key(""), None);
        assert_eq!(parse_key("model|l9|cjk"), None);
        assert_eq!(parse_key("model|l1|binary"), None);
        assert_eq!(parse_key("|l1|cjk"), None);
    }

    #[test]
    fn first_sample_is_taken_verbatim() {
        let mut cal = Calibration::new("m", Level::L1, Class::Mixed, 0);
        assert!(cal.observe(1800, 1500, 10));
        assert_eq!(cal.samples, 1);
        assert!((cal.ratio - 1.2).abs() < 1e-9);
        assert_eq!(cal.updated_at, 10);
    }

    #[test]
    fn later_samples_move_ratio_by_alpha() {
        let mut cal = Calibration::new("m", Level::L1, Class::Mixed, 0);
        cal.observe(1500, 1500, 1); // ratio = 1.0
        cal.observe(2100, 1500, 2); // 1.4 → 1.0*0.7 + 1.4*0.3 = 1.12
        assert!((cal.ratio - 1.12).abs() < 1e-9);
        assert_eq!(cal.samples, 2);
    }

    #[test]
    fn tiny_requests_and_zero_usage_are_refused() {
        let mut cal = Calibration::new("m", Level::L1, Class::Mixed, 0);
        assert!(!cal.observe(200, 300, 1), "估算 300 token 太小");
        assert!(!cal.observe(0, 5000, 1), "真实值 0 是端点没回 usage");
        assert_eq!(cal.samples, 0);
        assert!((cal.ratio - 1.0).abs() < 1e-9);
    }

    #[test]
    fn absurd_ratios_are_clamped() {
        let mut low = Calibration::new("m", Level::L1, Class::Mixed, 0);
        low.observe(1, 10_000, 1);
        assert!((low.ratio - RATIO_MIN).abs() < 1e-9);

        let mut high = Calibration::new("m", Level::L1, Class::Mixed, 0);
        high.observe(100_000, 10_000, 1);
        assert!((high.ratio - RATIO_MAX).abs() < 1e-9);
    }

    #[test]
    fn ratio_only_applies_after_the_sample_gate() {
        let mut cal = Calibration::new("m", Level::L1, Class::Mixed, 0);
        cal.observe(3000, 2000, 1);
        cal.observe(3000, 2000, 2);
        assert!(!cal.is_calibrated());
        assert_eq!(cal.apply(10_000), 10_000, "两个样本还不够，别拿它去卡窗口");

        cal.observe(3000, 2000, 3);
        assert!(cal.is_calibrated());
        assert_eq!(cal.apply(10_000), 15_000);
    }

    #[test]
    fn serde_round_trip() {
        let cal = Calibration::new("m", Level::L0, Class::Code, 42);
        let json = serde_json::to_string(&cal).expect("serialize");
        let back: Calibration = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, cal);
    }
}
