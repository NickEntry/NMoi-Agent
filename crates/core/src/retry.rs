//! 自动重试（§3.19 / D70、D71）。
//!
//! 这一层的职责只有一个：**判断这次失败值不值得再来一轮，以及等多久**。
//! 它不发请求、不打日志、不 sleep——所以能穷举测试。
//! 重试逻辑最怕的就是"只有真出故障时才知道它对不对"。
//!
//! # 判据顺序（第一条命中就停）
//!
//! 1. 状态码在"停止表" → 不重试
//! 2. 错误文本命中"停止关键词" → 不重试
//! 3. 状态码在"可重试表" → 重试
//! 4. 错误文本命中"重试关键词" → 重试
//! 5. 其余 → 不重试
//!
//! **停止项永远排在可重试项前面**：`429` 的响应体里经常同时写着 `quota`。
//! 顺序反了，就会在一张余额已空的卡上重试到天亮——每次都"失败但可重试"，每次都等。
//!
//! **状态码排在关键词前面**：状态码是协议给的判断，关键词是给人写的判断
//! （"限流"两个字可能出现在任何一段说明里）。协议能判的时候，不要让文本去猜。

use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::agent::Cancellation;

/// 关键词只扫错误文本的前这么多字节。
///
/// 上游的 5xx 有时会把整个请求体回显在错误里，几十 KB；扫全文既慢，又容易
/// 在一段无关的正文里命中"rate limit"这种词，然后重试一个根本不会成功的请求。
pub const KEYWORD_SCAN_BYTES: usize = 2048;

/// 等的时候每片多长。太粗了用户点停止要等半秒才有反应，太细了白烧 CPU。
const WAIT_SLICE: Duration = Duration::from_millis(50);

/// 阈值表：一张"当前生效"的清单 + 一份"出厂值"。
///
/// 两份是必须的：用户在设置里删掉几条之后，要能一键恢复默认，
/// 而默认值不该写死在 UI 里（那就会有两份默认值，改一处忘一处）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RetryList<T> {
    pub items: Vec<T>,
    pub default: Vec<T>,
}

impl<T: Clone> RetryList<T> {
    pub fn new(items: Vec<T>) -> Self {
        Self {
            default: items.clone(),
            items,
        }
    }

    /// 用出厂值覆盖当前清单。
    pub fn restore(&mut self) {
        self.items = self.default.clone();
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn contains(&self, value: &T) -> bool
    where
        T: PartialEq,
    {
        self.items.contains(value)
    }
}

pub type CodeList = RetryList<u16>;
pub type KeywordList = RetryList<String>;

/// 一个等待区间。**时间点是范围，不是数**（D71）：用户填的是"1 到 3 秒"。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WaitBand {
    pub min_s: f64,
    pub max_s: f64,
}

impl WaitBand {
    pub fn new(min_s: f64, max_s: f64) -> Self {
        // 写反了就换过来，别让一个手滑让人等 min 秒以上的东西变成等 0 秒。
        let (min_s, max_s) = if min_s <= max_s {
            (min_s, max_s)
        } else {
            (max_s, min_s)
        };
        Self {
            min_s: min_s.max(0.0),
            max_s: max_s.max(0.0),
        }
    }

    /// 在区间里取一个点。`rng` 返回 `[0, 1)`。
    ///
    /// 保留两位小数并夹回区间：UI 显示的是"1.37s"，重试时也真的等 1.37s，
    /// 不是 1.3700000000000001 也不是被舍入到区间外的 1.38。
    pub fn roll(&self, rng: &mut dyn FnMut() -> f64) -> f64 {
        let raw = self.min_s + (self.max_s - self.min_s) * rng().clamp(0.0, 0.999_999);
        let rounded = (raw * 100.0).round() / 100.0;
        rounded.clamp(self.min_s, self.max_s)
    }
}

/// 为什么不再试了。每一种都对应 UI 上一句不同的话。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GiveUpReason {
    /// 用户在设置里关掉了自动重试，或者清单是空的。
    Disabled,
    /// 状态码在停止表里。
    StopCode(u16),
    /// 命中了停止关键词（原样带回来，UI 能说"因为响应里有 quota"）。
    StopKeyword(String),
    /// 状态码与文本都没给出任何信号。
    Unknown,
    /// 时间点用完了。
    Exhausted,
    /// 总时长的预算用完了。
    BudgetExhausted,
    /// 已经收到字节了：流开始了就不能重发，否则会把两段拼成一条不存在的回复（D40）。
    StreamStarted,
    /// 等的时候用户点了停止。**不算失败**：没发出去就是没发生。
    Cancelled,
}

/// 判定结果（纯函数产物，不含副作用）。
#[derive(Debug, Clone, PartialEq)]
pub enum Decision {
    Retry { wait_s: f64 },
    GiveUp { reason: GiveUpReason },
}

/// 一次重试的完整输入。攒成一个结构体是因为参数一多，顺序错了两边都不报错。
#[derive(Debug, Clone, Copy)]
pub struct Attempt<'a> {
    pub status: Option<u16>,
    pub error_text: &'a str,
    /// 这是第几次重试（从 0 开始）。决定用哪个时间点。
    pub index: usize,
    /// 这一轮里是否已经收到过任何一个字节。
    pub written_any: bool,
    /// 从第一次失败到现在已经等掉多少秒。
    pub spent_s: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RetryPolicy {
    pub enabled: bool,
    pub retry_codes: CodeList,
    pub stop_codes: CodeList,
    pub retry_keywords: KeywordList,
    pub stop_keywords: KeywordList,
    /// 第 n 次重试等多久。按 `min_s` 升序使用。
    pub bands: Vec<WaitBand>,
    /// 所有等待加起来不超过这么久（默认 180s）。
    pub total_budget_s: f64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            retry_codes: CodeList::new(vec![408, 409, 425, 429, 500, 502, 503, 504]),
            // 400/422 是"你发的东西不对"，重发一次还是不对；401/403/404 同理。
            // 413 是超窗——重试只会再超一次，该走压缩。
            stop_codes: CodeList::new(vec![400, 401, 403, 404, 413, 422]),
            retry_keywords: KeywordList::new(
                [
                    "rate limit",
                    "overloaded",
                    "temporarily unavailable",
                    "try again",
                    "timeout",
                    "timed out",
                    "connection reset",
                    "服务繁忙",
                    "限流",
                    "超时",
                ]
                .iter()
                .map(|word| (*word).to_string())
                .collect(),
            ),
            // 这些词出现在 429 的 body 里极其常见，而它们意味着"再试也没有用"。
            stop_keywords: KeywordList::new(
                [
                    "quota",
                    "insufficient",
                    "billing",
                    "credit",
                    "payment",
                    "invalid api key",
                    "unauthorized",
                    "余额",
                    "额度",
                    "欠费",
                ]
                .iter()
                .map(|word| (*word).to_string())
                .collect(),
            ),
            bands: vec![
                WaitBand::new(1.0, 2.0),
                WaitBand::new(2.0, 4.0),
                WaitBand::new(4.0, 8.0),
            ],
            total_budget_s: 180.0,
        }
    }
}

impl RetryPolicy {
    /// 关掉。`bands` 为空也算关（D70：列表为空 = 关闭，UI 要写明）。
    pub fn disabled() -> Self {
        Self {
            enabled: false,
            ..Self::default()
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled && !self.bands.is_empty()
    }

    /// 按 `min_s` 升序、`min_s` 相同按 `max_s` 升序排好的一份时间点。
    ///
    /// 不信任调用方传进来的顺序：用户在设置里加一条 0.5–1s，它就该排在 1–2s 前面，
    /// 而不是排在数组末尾，让人等到第 4 次重试才用上最短的间隔。
    pub fn ordered_bands(&self) -> Vec<WaitBand> {
        let mut bands = self.bands.clone();
        bands.sort_by(|a, b| {
            a.min_s
                .partial_cmp(&b.min_s)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(
                    a.max_s
                        .partial_cmp(&b.max_s)
                        .unwrap_or(std::cmp::Ordering::Equal),
                )
        });
        bands
    }

    /// 纯判定：这一处该不该重试。**只看状态码与文本**，不看次数与预算。
    pub fn classify(&self, status: Option<u16>, error_text: &str) -> PartialDecision {
        if !self.enabled {
            return PartialDecision::GiveUp(GiveUpReason::Disabled);
        }
        // 1. 停止表
        if let Some(code) = status {
            if self.stop_codes.contains(&code) {
                return PartialDecision::GiveUp(GiveUpReason::StopCode(code));
            }
        }
        // 2. 停止关键词
        if let Some(word) = self.first_keyword(&self.stop_keywords, error_text) {
            return PartialDecision::GiveUp(GiveUpReason::StopKeyword(word));
        }
        // 3. 可重试表
        if let Some(code) = status {
            if self.retry_codes.contains(&code) {
                return PartialDecision::MayRetry;
            }
        }
        // 4. 重试关键词
        if self
            .first_keyword(&self.retry_keywords, error_text)
            .is_some()
        {
            return PartialDecision::MayRetry;
        }
        // 5. 没有信号
        PartialDecision::GiveUp(GiveUpReason::Unknown)
    }

    /// 完整判定：把次数、预算、以及"流已经开始"一起算进去。
    ///
    /// `rng` 是**注入**的（D71）：生产给系统熵，测试给固定序列。
    /// 不自带随机源，是因为"这次等了 1.87 秒"这种结论必须能被复现。
    pub fn plan(&self, attempt: Attempt<'_>, rng: &mut dyn FnMut() -> f64) -> Decision {
        if !self.is_enabled() {
            return Decision::GiveUp {
                reason: GiveUpReason::Disabled,
            };
        }
        // 流已经开始就不能重发。这一条放在最前面：它跟"该不该重试"无关，
        // 是"能不能重发"——已经吐出去的正文收不回来。
        if attempt.written_any {
            return Decision::GiveUp {
                reason: GiveUpReason::StreamStarted,
            };
        }
        match self.classify(attempt.status, attempt.error_text) {
            PartialDecision::GiveUp(reason) => Decision::GiveUp { reason },
            PartialDecision::MayRetry => {
                let bands = self.ordered_bands();
                let Some(band) = bands.get(attempt.index) else {
                    return Decision::GiveUp {
                        reason: GiveUpReason::Exhausted,
                    };
                };
                let wait_s = band.roll(rng);
                if attempt.spent_s + wait_s > self.total_budget_s {
                    return Decision::GiveUp {
                        reason: GiveUpReason::BudgetExhausted,
                    };
                }
                Decision::Retry { wait_s }
            }
        }
    }

    /// 第一个命中的关键词。**只扫前 2 KiB**，并且两边都小写化——
    /// 上游写 `Rate Limit` 还是 `RATE LIMIT` 取决于谁写的，不该由我们猜。
    fn first_keyword(&self, list: &KeywordList, text: &str) -> Option<String> {
        if list.is_empty() {
            return None;
        }
        let head = head_bytes(text, KEYWORD_SCAN_BYTES).to_lowercase();
        for word in &list.items {
            let needle = word.to_lowercase();
            if !needle.is_empty() && head.contains(&needle) {
                return Some(word.clone());
            }
        }
        None
    }
}

/// `classify` 的中间结果：到这一步还不知道次数与预算，所以还不能给最终结论。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PartialDecision {
    MayRetry,
    GiveUp(GiveUpReason),
}

/// 截到不超过 `max` 字节，且落在字符边界上。
///
/// 直接按字节切会把一个汉字劈成两半，`to_lowercase` 拿到半个 UTF-8 序列就该 panic 了。
fn head_bytes(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let mut end = max;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// 可中断的等待：50ms 一片，每片问一次取消。
///
/// 用 `sleep` 一整段的话，用户点了停止要等到时间点走完——最坏 8 秒，
/// 那时候他已经在想"这软件是不是卡死了"。
pub fn wait(duration: Duration, cancel: &dyn Cancellation) -> WaitOutcome {
    let deadline = Instant::now() + duration;
    loop {
        if cancel.is_cancelled() {
            return WaitOutcome::Cancelled;
        }
        let now = Instant::now();
        if now >= deadline {
            return WaitOutcome::Completed;
        }
        let slice = (deadline - now).min(WAIT_SLICE);
        std::thread::sleep(slice);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WaitOutcome {
    Completed,
    /// 用户点了停止。**不算失败**——没发出去就是没发生。
    Cancelled,
}

/// 生产用的随机源。不引依赖，也不追求统计质量：
/// 这里要的是"两次重试的间隔别一模一样"，不是密码学随机。
#[derive(Debug, Clone)]
pub struct Entropy {
    state: u64,
}

impl Default for Entropy {
    fn default() -> Self {
        Self::seeded(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|since| since.as_nanos() as u64)
                .unwrap_or(0x9E37_79B9_7F4A_7C15),
        )
    }
}

impl Entropy {
    pub fn seeded(seed: u64) -> Self {
        Self {
            state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
        }
    }

    /// xorshift64*，返回 `[0, 1)`。
    pub fn next_f64(&mut self) -> f64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        let value = x.wrapping_mul(0x2545_F491_4F6C_DD1D);
        // 取高 53 位：那是 f64 尾数能精确表示的部分。
        ((value >> 11) as f64) / ((1u64 << 53) as f64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed(values: Vec<f64>) -> impl FnMut() -> f64 {
        let mut queue = values.into_iter();
        move || queue.next().unwrap_or(0.0)
    }

    fn policy() -> RetryPolicy {
        RetryPolicy::default()
    }

    fn attempt(status: Option<u16>, text: &str, index: usize) -> Attempt<'_> {
        Attempt {
            status,
            error_text: text,
            index,
            written_any: false,
            spent_s: 0.0,
        }
    }

    #[test]
    fn stop_beats_retry_even_on_429() {
        // 这条是整个模块存在的理由：429 的 body 里写着 quota。
        let decision = policy().classify(Some(429), "429 rate limit exceeded: quota exhausted");
        assert_eq!(
            decision,
            PartialDecision::GiveUp(GiveUpReason::StopKeyword("quota".into()))
        );
    }

    #[test]
    fn status_codes_are_read_before_keywords() {
        // 没有停止关键词时，429 走状态码重试；文本里那句 "try again" 不是判据。
        assert_eq!(
            policy().classify(Some(429), "too many requests"),
            PartialDecision::MayRetry
        );
        // 400 在停止表里，哪怕文本里写着 rate limit 也不重试。
        assert_eq!(
            policy().classify(Some(400), "rate limit?"),
            PartialDecision::GiveUp(GiveUpReason::StopCode(400))
        );
    }

    #[test]
    fn keyword_only_errors_still_work() {
        // 有的代理不写状态码，只把一句话塞进错误里。
        assert_eq!(
            policy().classify(None, "upstream overloaded, please retry"),
            PartialDecision::MayRetry
        );
        assert_eq!(
            policy().classify(None, "something nobody has seen before"),
            PartialDecision::GiveUp(GiveUpReason::Unknown)
        );
    }

    #[test]
    fn keyword_scan_stops_at_two_kib() {
        let mut text = "x".repeat(KEYWORD_SCAN_BYTES);
        text.push_str(" quota ");
        // 2 KiB 之后的东西不看：那里多半是回显的请求体，不是错误本身。
        assert_eq!(
            policy().classify(Some(503), &text),
            PartialDecision::MayRetry,
            "超窗之后的 quota 不该被算成停止信号"
        );

        let mut head = String::from("quota ");
        head.push_str(&"x".repeat(KEYWORD_SCAN_BYTES));
        assert!(matches!(
            policy().classify(Some(503), &head),
            PartialDecision::GiveUp(GiveUpReason::StopKeyword(_))
        ));
    }

    #[test]
    fn scan_limit_lands_on_char_boundary() {
        // 全是汉字：按字节硬切会切出半个字，下一次 to_lowercase 就该炸了。
        let text = "限流".repeat(KEYWORD_SCAN_BYTES);
        let head = head_bytes(&text, KEYWORD_SCAN_BYTES);
        assert!(text.is_char_boundary(head.len()));
        assert!(head.len() <= KEYWORD_SCAN_BYTES);
        assert_eq!(
            policy().classify(Some(503), &text),
            PartialDecision::MayRetry
        );
    }

    #[test]
    fn keywords_are_case_insensitive() {
        assert_eq!(
            policy().classify(None, "Upstream says: RATE LIMIT"),
            PartialDecision::MayRetry
        );
    }

    #[test]
    fn empty_band_list_means_off() {
        let mut policy = policy();
        policy.bands.clear();
        assert!(!policy.is_enabled(), "空清单就是关，UI 也要这么说");
        assert_eq!(
            policy.plan(attempt(Some(503), "", 0), &mut fixed(vec![0.5])),
            Decision::GiveUp {
                reason: GiveUpReason::Disabled
            }
        );
    }

    #[test]
    fn stream_started_never_retries() {
        let mut attempt = attempt(Some(503), "overloaded", 0);
        attempt.written_any = true;
        assert_eq!(
            policy().plan(attempt, &mut fixed(vec![0.5])),
            Decision::GiveUp {
                reason: GiveUpReason::StreamStarted
            }
        );
    }

    #[test]
    fn bands_are_used_in_order_and_then_run_out() {
        let policy = policy();
        let mut rng = fixed(vec![0.0, 0.0, 0.0, 0.0]);
        // 三次重试分别拿到 1.0 / 2.0 / 4.0（rng=0 → 取区间下界）。
        for (index, expected) in [(0, 1.0), (1, 2.0), (2, 4.0)] {
            match policy.plan(attempt(Some(503), "", index), &mut rng) {
                Decision::Retry { wait_s } => assert_eq!(wait_s, expected),
                other => panic!("第 {index} 次应当重试，实际 {other:?}"),
            }
        }
        assert_eq!(
            policy.plan(attempt(Some(503), "", 3), &mut rng),
            Decision::GiveUp {
                reason: GiveUpReason::Exhausted
            }
        );
    }

    #[test]
    fn unordered_bands_get_sorted_before_use() {
        let mut policy = policy();
        policy.bands = vec![
            WaitBand::new(4.0, 8.0),
            WaitBand::new(0.5, 1.0),
            WaitBand::new(2.0, 4.0),
        ];
        let ordered: Vec<f64> = policy.ordered_bands().iter().map(|b| b.min_s).collect();
        assert_eq!(ordered, vec![0.5, 2.0, 4.0]);
        match policy.plan(attempt(Some(503), "", 0), &mut fixed(vec![0.0])) {
            Decision::Retry { wait_s } => assert_eq!(wait_s, 0.5, "最短的那条要先用上"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn roll_stays_inside_the_band_and_rounds_to_two_decimals() {
        let band = WaitBand::new(1.0, 2.0);
        assert_eq!(band.roll(&mut fixed(vec![0.0])), 1.0);
        assert_eq!(band.roll(&mut fixed(vec![0.5])), 1.5);
        // rng 给到 1.0（实现上取不到，但契约上不许越界）
        assert_eq!(band.roll(&mut fixed(vec![1.0])), 2.0);
        let rolled = band.roll(&mut fixed(vec![0.123_456]));
        assert_eq!(rolled, 1.12);
    }

    #[test]
    fn reversed_bands_are_fixed_instead_of_waiting_zero() {
        let band = WaitBand::new(3.0, 1.0);
        assert_eq!((band.min_s, band.max_s), (1.0, 3.0));
    }

    #[test]
    fn total_budget_cuts_off_the_last_wait() {
        let mut policy = policy();
        policy.total_budget_s = 5.0;
        let mut attempt = attempt(Some(503), "", 2);
        attempt.spent_s = 4.0;
        // 第三个区间下界是 4.0，4.0 + 4.0 > 5.0
        assert_eq!(
            policy.plan(attempt, &mut fixed(vec![0.0])),
            Decision::GiveUp {
                reason: GiveUpReason::BudgetExhausted
            }
        );
    }

    #[test]
    fn restore_brings_back_the_factory_list() {
        let mut list = CodeList::new(vec![429, 503]);
        list.items.clear();
        assert!(list.is_empty());
        list.restore();
        assert_eq!(list.items, vec![429, 503]);
    }

    #[test]
    fn entropy_stays_in_unit_interval_and_moves() {
        let mut entropy = Entropy::seeded(42);
        let mut seen = Vec::new();
        for _ in 0..64 {
            let value = entropy.next_f64();
            assert!((0.0..1.0).contains(&value), "{value}");
            seen.push(value);
        }
        assert!(
            seen.windows(2).any(|pair| pair[0] != pair[1]),
            "别一直吐同一个数"
        );
    }

    struct CancelAfter(std::cell::Cell<u32>);

    impl Cancellation for CancelAfter {
        fn is_cancelled(&self) -> bool {
            let left = self.0.get();
            if left == 0 {
                return true;
            }
            self.0.set(left - 1);
            false
        }
    }

    #[test]
    fn wait_is_interruptible() {
        let cancel = CancelAfter(std::cell::Cell::new(2));
        let started = Instant::now();
        let outcome = wait(Duration::from_secs(5), &cancel);
        assert_eq!(outcome, WaitOutcome::Cancelled);
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "取消之后不该把时间点睡完：{:?}",
            started.elapsed()
        );
    }

    #[test]
    fn short_waits_complete() {
        let cancel = CancelAfter(std::cell::Cell::new(1_000));
        let outcome = wait(Duration::from_millis(60), &cancel);
        assert_eq!(outcome, WaitOutcome::Completed);
    }
}
