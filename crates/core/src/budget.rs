//! 预算、缓存代价与压缩触发器（设计文档 §0.2、§6.1、附录 A）。
//!
//! 两个量纲不能混：缓存代价是确定性支出，上下文污染是概率性损失。
//! 这里把前者算准，后者留给质量指标。

use serde::{Deserialize, Serialize};

use crate::message::AtomicUnit;
use crate::source::SourceTag;
use crate::tokens::{estimate_tokens, Estimator};

/// 一次请求的用量。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub cached_tokens: u32,
    pub reasoning_tokens: u32,
}

impl Usage {
    pub fn total(&self) -> u32 {
        self.prompt_tokens.saturating_add(self.completion_tokens)
    }

    /// 缓存命中率 `hit / prompt`。prompt 为 0 时返回 0。
    pub fn cache_hit_ratio(&self) -> f64 {
        if self.prompt_tokens == 0 {
            return 0.0;
        }
        f64::from(self.cached_tokens) / f64::from(self.prompt_tokens)
    }
}

/// 缓存作用域（§5.8）。跨设备共享与否直接决定新会话第一轮是否全冷。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CacheScope {
    Account,
    Organization,
    Session,
}

/// 把易变参数集中到一处（§5.8）。`measured_at` 超过 90 天标记"待复核"。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CacheProfile {
    pub provider: String,
    pub model: String,
    pub window_declared: u64,
    pub window_effective: u64,
    pub max_output: u64,
    pub min_cacheable_tokens: u64,
    /// 64 / 128 / block
    pub cache_granularity: u64,
    /// 0 = 自动前缀式。
    pub breakpoint_max: u32,
    pub ttl_seconds: u64,
    /// 写价倍数 w。
    pub write_multiplier: f64,
    /// 读价倍数 r。
    pub read_multiplier: f64,
    pub scope: CacheScope,
    pub supports_kv_reuse: bool,
    /// Unix 秒。
    pub measured_at: i64,
    pub source_url: String,
}

impl CacheProfile {
    /// 有效窗口 = min(声明, 实测)。第三方托管商常把同一模型降级（1M → 200K）。
    pub fn effective_window(&self) -> u64 {
        self.window_declared.min(self.window_effective)
    }

    pub fn measured_age_days(&self, now: i64) -> i64 {
        (now - self.measured_at).max(0) / 86_400
    }

    /// 超过 90 天没有复核，参数就不能当作事实用。
    pub fn needs_review(&self, now: i64) -> bool {
        self.measured_age_days(now) > 90
    }
}

/// 上下文预算。`used` 含 system + tools + memory + 世界书 + skills + history（§5.7）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Budget {
    pub window_declared: u64,
    pub window_effective: u64,
    pub reserve_output: u64,
    pub used: u64,
}

impl Budget {
    pub fn new(window_declared: u64, window_effective: u64, reserve_output: u64) -> Self {
        Self {
            window_declared,
            window_effective,
            reserve_output,
            used: 0,
        }
    }

    pub fn from_profile(profile: &CacheProfile, reserve_output: u64) -> Self {
        Self::new(
            profile.window_declared,
            profile.window_effective,
            reserve_output,
        )
    }

    /// 可用的输入预算：min(声明窗口, 实测上限) − 预留输出。
    pub fn usable(&self) -> u64 {
        self.window_declared
            .min(self.window_effective)
            .saturating_sub(self.reserve_output)
    }

    pub fn remaining(&self) -> u64 {
        self.usable().saturating_sub(self.used)
    }

    pub fn ratio(&self) -> f64 {
        let usable = self.usable();
        if usable == 0 {
            return 1.0;
        }
        self.used as f64 / usable as f64
    }

    /// 不压缩还能跑几轮（§6.1 的 R）。`avg_per_turn` 有下限，防止除零。
    pub fn remaining_turns(&self, avg_per_turn: u64, min_avg_per_turn: u64) -> f64 {
        let avg = avg_per_turn.max(min_avg_per_turn).max(1);
        self.remaining() as f64 / avg as f64
    }
}

/// 阈值单位。**百分比只是录入与显示的形式**，判定一律折成 token（D90）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThresholdMode {
    Percent,
    Tokens,
}

/// 一条压缩线。
///
/// 两个单位都存着，由 `mode` 决定谁是权威：
///
/// - `Percent`：换模型（窗口从 200k 变 32k）时按新窗口重算，不然 150k 的绝对阈值
///   在 32k 的模型上会**立刻触发**，用户会以为设置坏了；
/// - `Tokens`：用户就是要一个绝对数，窗口怎么变都不动，只在超过窗口时被夹住。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Threshold {
    pub mode: ThresholdMode,
    /// 0–1。`Tokens` 模式下是按最近一次已知窗口算出来的派生值。
    pub percent: f64,
    /// `Percent` 模式下是按最近一次已知窗口算出来的派生值。
    pub tokens: u64,
}

impl Threshold {
    /// 按窗口百分比给。超出 0–1 的值直接夹住——宁可用户改的是"夹过的那个 0.99"，
    /// 也不要存一个 1.4 然后在判定时永远触发。
    pub fn percent(value: f64) -> Self {
        let percent = if value.is_finite() { value } else { 1.0 };
        Self {
            mode: ThresholdMode::Percent,
            percent: percent.clamp(0.0, 1.0),
            tokens: 0,
        }
    }

    pub fn tokens(value: u64) -> Self {
        Self {
            mode: ThresholdMode::Tokens,
            percent: 0.0,
            tokens: value,
        }
    }

    /// 折成 token。`window` 用**可用预算**（已扣掉预留输出），不是声明窗口。
    pub fn resolve(&self, window: u64) -> u64 {
        match self.mode {
            ThresholdMode::Percent => ((window as f64) * self.percent).round() as u64,
            // 夹到窗口：超过窗口的阈值等于"永远触发"，那不是用户的意图。
            ThresholdMode::Tokens => self.tokens.min(window),
        }
    }

    /// 占窗口多少（UI 显示用）。
    pub fn percent_of(&self, window: u64) -> f64 {
        match self.mode {
            ThresholdMode::Percent => self.percent,
            ThresholdMode::Tokens => {
                if window == 0 {
                    0.0
                } else {
                    (self.tokens.min(window) as f64) / window as f64
                }
            }
        }
    }

    /// 换窗口之后把另一个单位同步过来（D90 的"内部统一存 token"）。
    pub fn retarget(&mut self, window: u64) {
        let percent = self.percent_of(window);
        let tokens = self.resolve(window);
        self.percent = percent;
        self.tokens = tokens;
    }
}

/// 压缩触发结论。
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CompressionTrigger {
    None,
    /// 预算还能跑，但回本周期已经不划算——按 §6.1 的成本判据提前压。
    Soft {
        remaining_turns: f64,
        payback_turns: f64,
    },
    /// 越过建议线（旧名 `force`）：本轮照常发，结束后压。
    Suggest {
        ratio: f64,
    },
    /// 越过绝对线（旧名 `emergency`）：不能再等。
    Absolute {
        ratio: f64,
    },
}

impl CompressionTrigger {
    pub fn should_compact(self) -> bool {
        !matches!(self, CompressionTrigger::None)
    }
}

/// 判定发生在哪一处（D90 只认这三处，流中间不判定）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriggerPoint {
    /// 造请求之前。
    BeforeRequest,
    /// 生成过程中（工具循环里每轮之间）。这里只按字符增量算。
    DuringGeneration,
    /// 生成结束、落库之后。
    AfterGeneration,
}

/// 该做什么。**动作不是"建议"，是这一处唯一正确的走法**。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompressAction {
    /// 照常，什么都不做。
    Proceed,
    /// 这次照发，生成结束后再压。
    ProceedThenCompact,
    /// 先压再发。core 自己压不了（要 provider + archive），所以这一档会让
    /// `run_session_turn` 直接返回 `CompactionRequired`。
    CompactThenProceed,
    /// 立刻截断（落库时标 `incomplete`），然后压。
    TruncateThenCompact,
    /// 已经落库了，去压。
    CompactAfterPersist,
}

impl CompressAction {
    /// 这一档是不是"先把请求按住"。
    pub fn blocks(self) -> bool {
        matches!(self, CompressAction::CompactThenProceed)
    }
}

/// D90 的决策表，两列三行写死在一处。
pub fn decide(trigger: CompressionTrigger, point: TriggerPoint) -> CompressAction {
    use CompressAction as A;
    use CompressionTrigger as T;
    use TriggerPoint as P;
    match (trigger, point) {
        (T::None, _) => A::Proceed,

        (T::Absolute { .. }, P::BeforeRequest) => A::CompactThenProceed,
        (T::Absolute { .. }, P::DuringGeneration) => A::TruncateThenCompact,
        (T::Absolute { .. }, P::AfterGeneration) => A::CompactAfterPersist,

        // 建议线不拦请求：等这一轮说完再压，比打断用户正在看的那段更划算。
        (T::Suggest { .. }, P::BeforeRequest) => A::ProceedThenCompact,
        (T::Suggest { .. }, P::DuringGeneration) => A::Proceed,
        (T::Suggest { .. }, P::AfterGeneration) => A::CompactAfterPersist,

        // 成本判据永远只是"顺手压一下"，不截断、不拦。
        (T::Soft { .. }, P::DuringGeneration) => A::Proceed,
        (T::Soft { .. }, P::BeforeRequest) => A::ProceedThenCompact,
        (T::Soft { .. }, P::AfterGeneration) => A::CompactAfterPersist,
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TriggerPolicy {
    /// 建议线（默认 0.75）。
    pub suggest: Threshold,
    /// 绝对线（默认 0.92）。必须 ≥ 建议线，否则建议线永远轮不到。
    pub absolute: Threshold,
    /// 成本判据的安全系数。
    pub margin: f64,
    pub min_avg_per_turn: u64,
}

impl Default for TriggerPolicy {
    fn default() -> Self {
        Self {
            suggest: Threshold::percent(0.75),
            absolute: Threshold::percent(0.92),
            margin: 1.5,
            min_avg_per_turn: 2_000,
        }
    }
}

impl TriggerPolicy {
    /// 换窗口后同步两条线的 token 值。调用方在拿到真实窗口时调一次。
    pub fn retarget(&mut self, window: u64) {
        self.suggest.retarget(window);
        self.absolute.retarget(window);
    }

    /// 两条线的顺序是否说得通。`false` 时 UI 该拦住用户。
    pub fn sane(&self, window: u64) -> bool {
        self.absolute.resolve(window) >= self.suggest.resolve(window)
    }
}

/// 触发判据（§6.1 + D90）。**比较的是 token，不是比例**。
///
/// ```text
/// if used >= absolute(window)  → 绝对线
/// elif used >= suggest(window) → 建议线
/// elif R < margin × N*         → 按成本压
/// else                         → 不压缩
/// ```
pub fn evaluate_trigger(
    budget: &Budget,
    avg_per_turn: u64,
    payback_turns: f64,
    policy: &TriggerPolicy,
) -> CompressionTrigger {
    let window = budget.usable();
    let ratio = budget.ratio();
    let absolute = policy.absolute.resolve(window);
    if absolute > 0 && budget.used >= absolute {
        return CompressionTrigger::Absolute { ratio };
    }
    let suggest = policy.suggest.resolve(window);
    if suggest > 0 && budget.used >= suggest {
        return CompressionTrigger::Suggest { ratio };
    }
    let remaining_turns = budget.remaining_turns(avg_per_turn, policy.min_avg_per_turn);
    if remaining_turns < policy.margin * payback_turns {
        return CompressionTrigger::Soft {
            remaining_turns,
            payback_turns,
        };
    }
    CompressionTrigger::None
}

/// 一次性缓存代价（附录 A）：`(L − i) × (w − r)`，单位是 token 当量。
pub fn cache_cost(
    prefix_len: u64,
    change_point: u64,
    write_multiplier: f64,
    read_multiplier: f64,
) -> f64 {
    let rewritten = prefix_len.saturating_sub(change_point);
    rewritten as f64 * (write_multiplier - read_multiplier)
}

/// 回本周期 `N* = (w − r)/r × (L − i)/d`。
///
/// `deleted == 0` 时返回 `f64::INFINITY`：不删内容就没有持续节省，永远回不了本。
pub fn payback_turns(
    prefix_len: u64,
    change_point: u64,
    deleted: u64,
    write_multiplier: f64,
    read_multiplier: f64,
) -> f64 {
    if deleted == 0 || read_multiplier <= 0.0 {
        return f64::INFINITY;
    }
    let rewritten = prefix_len.saturating_sub(change_point);
    (write_multiplier - read_multiplier) / read_multiplier * rewritten as f64 / deleted as f64
}

/// 截断式压缩（d = L − i）的常数回本周期 `N* = (w − r)/r`。
pub fn payback_turns_truncating(write_multiplier: f64, read_multiplier: f64) -> f64 {
    if read_multiplier <= 0.0 {
        return f64::INFINITY;
    }
    (write_multiplier - read_multiplier) / read_multiplier
}

/// 净节省（附录 A.2，含回灌项）。
///
/// ```text
/// 净节省 = d × r × 剩余轮数
///        − (L − i) × (w − r)
///        − Σ(回灌量 × 剩余轮数)
/// ```
///
/// 回灌项是关键修正：指针留在上下文、原文又追加，同一内容付两次。
pub fn net_saving(
    deleted_tokens: u64,
    read_multiplier: f64,
    remaining_turns: u64,
    prefix_len: u64,
    change_point: u64,
    write_multiplier: f64,
    refeed_tokens: u64,
) -> f64 {
    let saved = deleted_tokens as f64 * read_multiplier * remaining_turns as f64;
    let cost = cache_cost(prefix_len, change_point, write_multiplier, read_multiplier);
    let refeed = refeed_tokens as f64 * remaining_turns as f64;
    saved - cost - refeed
}

// ---------------------------------------------------------------------------
// 上下文窗口面板的分段用量（P6.4 / §3.12）
// ---------------------------------------------------------------------------

/// 上下文窗口面板的一行。
///
/// **行数固定是 8，某行为 0 也照样给**：色块、顺序、图例位置都不该随数据跳动。
/// 用户第二次打开面板时，同一个位置上必须还是同一样东西——否则那块色块是"历史"
/// 还是"世界书"要靠猜，图就白画了。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Segment {
    History,
    BuiltinTools,
    McpTools,
    Skills,
    SystemPrompt,
    WorldBook,
    Workspace,
    Draft,
}

impl Segment {
    /// 顺序 = 面板上的顺序，也是分段进度条的堆叠顺序。改这里就是改 UI，别随手调。
    pub const ALL: [Segment; 8] = [
        Segment::History,
        Segment::BuiltinTools,
        Segment::McpTools,
        Segment::Skills,
        Segment::SystemPrompt,
        Segment::WorldBook,
        Segment::Workspace,
        Segment::Draft,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Segment::History => "history",
            Segment::BuiltinTools => "builtin_tools",
            Segment::McpTools => "mcp_tools",
            Segment::Skills => "skills",
            Segment::SystemPrompt => "system_prompt",
            Segment::WorldBook => "world_book",
            Segment::Workspace => "workspace",
            Segment::Draft => "draft",
        }
    }

    /// 中文名直接给出去。
    ///
    /// 理由不是省事：同一张词表在 Rust 和 C 里各维护一份，迟早一边改了另一边没改，
    /// 界面上就会出现"内置工具"和"工具"两个名字指同一行。
    pub fn label(self) -> &'static str {
        match self {
            Segment::History => "历史消息",
            Segment::BuiltinTools => "内置工具",
            Segment::McpTools => "MCP 工具",
            Segment::Skills => "技能",
            Segment::SystemPrompt => "系统提示词",
            Segment::WorldBook => "世界书",
            Segment::Workspace => "工作区",
            Segment::Draft => "草稿",
        }
    }

    pub fn index(self) -> usize {
        match self {
            Segment::History => 0,
            Segment::BuiltinTools => 1,
            Segment::McpTools => 2,
            Segment::Skills => 3,
            Segment::SystemPrompt => 4,
            Segment::WorldBook => 5,
            Segment::Workspace => 6,
            Segment::Draft => 7,
        }
    }
}

/// 来源标签落在哪一行。
///
/// 归类依据是**谁把它放进上下文**，不是"看起来像什么"：
///
/// | 标签 | 行 | 理由 |
/// |---|---|---|
/// | `System` / `InstructionInjection` / `Memory` | 系统提示词 | 都是常驻注入的指令，随每轮一起发 |
/// | `History` / `ModelThinking` / `BrokenBlock` / `VisionBridge` | 历史消息 | 都是历史里某条消息的一部分，拆开算会让"历史"这一行对不上用户看到的条数 |
/// | `ToolDefinition` / `ToolCall` / `ToolResult` / `Search` | 内置工具 | 声明、调用、结果、外部检索都是工具这一层的开销；MCP 由名字前缀再分出去 |
/// | `WorldBook` / `Skills` / `Workspace` | 各自的行 | 一一对应 |
///
/// 故意不写 `_ =>` 兜底：以后加 `SourceTag`，编译器会在这里拦一次，
/// 而不是悄悄把它算进"历史消息"里，让面板从此少一行。
pub fn segment_of(tag: SourceTag) -> Segment {
    match tag {
        SourceTag::System | SourceTag::InstructionInjection | SourceTag::Memory => {
            Segment::SystemPrompt
        }
        SourceTag::History
        | SourceTag::ModelThinking
        | SourceTag::BrokenBlock
        | SourceTag::VisionBridge => Segment::History,
        SourceTag::ToolDefinition
        | SourceTag::ToolCall
        | SourceTag::ToolResult
        | SourceTag::Search => Segment::BuiltinTools,
        SourceTag::WorldBook => Segment::WorldBook,
        SourceTag::Skills => Segment::Skills,
        SourceTag::Workspace => Segment::Workspace,
    }
}

/// MCP 工具的判定依据：**名字前缀**。
///
/// 这是约定，不是查表——P7 还没有接入 MCP，库里没地方记"这个工具是谁提供的"。
/// 等真有了 origin 字段，把这里换成读字段即可，调用点不用动。
pub const MCP_TOOL_PREFIXES: [&str; 2] = ["mcp__", "mcp."];

pub fn is_mcp_tool(name: &str) -> bool {
    MCP_TOOL_PREFIXES
        .iter()
        .any(|prefix| name.starts_with(prefix))
}

/// 面板的一行算出来的结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct BreakdownRow {
    pub segment: Segment,
    pub tokens: u64,
    /// 这一行由几条东西凑成：消息条数 / 工具声明条数。
    /// 面板上不一定显示，但"0 token 却有 3 条"这种矛盾，只能靠它看出来。
    pub items: u64,
}

/// 分段用量累加器。
///
/// 用 `[u64; 8]` 而不是 `HashMap`：行是固定的，数组保证顺序也保证不会多出第 9 行。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Breakdown {
    tokens: [u64; Segment::ALL.len()],
    items: [u64; Segment::ALL.len()],
}

impl Breakdown {
    pub fn new() -> Self {
        Self::default()
    }

    /// 记一条内容。
    pub fn add(&mut self, tag: SourceTag, tokens: u64) {
        self.add_segment(segment_of(tag), tokens, 1);
    }

    /// 记一条工具相关的内容，按名字前缀分到"内置工具"或"MCP 工具"。
    pub fn add_tool(&mut self, name: &str, tokens: u64) {
        let segment = if is_mcp_tool(name) {
            Segment::McpTools
        } else {
            Segment::BuiltinTools
        };
        self.add_segment(segment, tokens, 1);
    }

    pub fn add_segment(&mut self, segment: Segment, tokens: u64, items: u64) {
        let index = segment.index();
        self.tokens[index] = self.tokens[index].saturating_add(tokens);
        self.items[index] = self.items[index].saturating_add(items);
    }

    pub fn tokens_of(&self, segment: Segment) -> u64 {
        self.tokens[segment.index()]
    }

    pub fn items_of(&self, segment: Segment) -> u64 {
        self.items[segment.index()]
    }

    pub fn total(&self) -> u64 {
        self.tokens.iter().copied().sum()
    }

    pub fn is_empty(&self) -> bool {
        self.total() == 0
    }

    /// 一行的占比。总量为 0 时给 0，不返回 NaN——NaN 进了 JSON 就是 `null`，
    /// 前端画进度条时会得到一条长度为零的奇怪东西。
    pub fn share(&self, segment: Segment) -> f64 {
        let total = self.total();
        if total == 0 {
            return 0.0;
        }
        self.tokens_of(segment) as f64 / total as f64
    }

    /// 八行全给，含 0 行。
    pub fn rows(&self) -> Vec<BreakdownRow> {
        Segment::ALL
            .iter()
            .map(|segment| BreakdownRow {
                segment: *segment,
                tokens: self.tokens_of(*segment),
                items: self.items_of(*segment),
            })
            .collect()
    }
}

/// 把库里的一串原子单元折成八行。
///
/// 一条消息占多少 token 用 `Message.tokens`；**思考内容在这里补上**：
/// 落库时 `tokens` 只算了正文与工具参数，而面板要回答的是"这次请求实际送出去多少"，
/// 思考也是送出去的（它跟着历史一起回灌）。不补的话，思考密集的会话会稳定低估。
/// 分段用量。
///
/// `estimator` 给 `None` 时信库里存着的 `message.tokens`（那是 L0 口径的落库值）；
/// 给 `Some` 时按内容重新估（L1 + 校准）。**判定与面板必须走同一档**，
/// 否则"面板说还剩 20k，判据说已经过线"这种矛盾会反复出现。
pub fn breakdown_of_units(units: &[AtomicUnit], estimator: Option<&Estimator<'_>>) -> Breakdown {
    let mut out = Breakdown::new();
    for unit in units {
        // 单元级判定：一个 MCP 单元里，结果那条没有工具名，只能跟着调用一起归类。
        let mcp = unit
            .messages
            .iter()
            .flat_map(|message| message.tool_calls.iter())
            .any(|call| is_mcp_tool(&call.name));
        for message in &unit.messages {
            let tool_side = matches!(
                message.source,
                SourceTag::ToolCall | SourceTag::ToolResult | SourceTag::ToolDefinition
            );
            let segment = if mcp && tool_side {
                Segment::McpTools
            } else {
                segment_of(message.source)
            };
            let tokens = match estimator {
                Some(estimator) => u64::from(
                    estimator
                        .estimate_all(
                            std::iter::once(message.content.as_str())
                                // 思考内容也是要付钱的输入。
                                .chain(message.reasoning.as_deref())
                                // 工具调用参数在 `tool_calls` 里，不在 `content` 里。
                                .chain(
                                    message
                                        .tool_calls
                                        .iter()
                                        .map(|call| call.arguments.as_str()),
                                ),
                        )
                        .tokens,
                ),
                None => {
                    u64::from(message.tokens)
                        + message
                            .reasoning
                            .as_deref()
                            .map_or(0, |text| u64::from(estimate_tokens(text)))
                }
            };
            out.add_segment(segment, tokens, 1);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile() -> CacheProfile {
        CacheProfile {
            provider: "local".into(),
            model: "DeepSeek-V4.1-Flash".into(),
            window_declared: 131_072,
            window_effective: 949_485,
            max_output: 1_048_576,
            min_cacheable_tokens: 64,
            cache_granularity: 64,
            breakpoint_max: 0,
            ttl_seconds: 3_600,
            write_multiplier: 1.00,
            read_multiplier: 0.04,
            scope: CacheScope::Account,
            supports_kv_reuse: true,
            measured_at: 1_760_000_000,
            source_url: "local".into(),
        }
    }

    #[test]
    fn effective_window_takes_minimum() {
        assert_eq!(profile().effective_window(), 131_072);
        let mut p = profile();
        p.window_effective = 200_000;
        p.window_declared = 1_000_000;
        assert_eq!(p.effective_window(), 200_000);
    }

    #[test]
    fn profile_needs_review_after_90_days() {
        let p = profile();
        assert!(!p.needs_review(p.measured_at + 89 * 86_400));
        assert!(p.needs_review(p.measured_at + 91 * 86_400));
    }

    #[test]
    fn budget_ratio_and_remaining() {
        let mut budget = Budget::new(100_000, 100_000, 10_000);
        assert_eq!(budget.usable(), 90_000);
        budget.used = 45_000;
        assert_eq!(budget.remaining(), 45_000);
        assert!((budget.ratio() - 0.5).abs() < 1e-9);
        assert!((budget.remaining_turns(4_500, 2_000) - 10.0).abs() < 1e-9);
    }

    #[test]
    fn trigger_escalates_in_order() {
        let policy = TriggerPolicy::default();
        let mut budget = Budget::new(100_000, 100_000, 0);

        budget.used = 10_000;
        assert_eq!(
            evaluate_trigger(&budget, 2_000, 2.0, &policy),
            CompressionTrigger::None
        );

        budget.used = 76_000;
        assert!(matches!(
            evaluate_trigger(&budget, 2_000, 2.0, &policy),
            CompressionTrigger::Suggest { .. }
        ));

        budget.used = 93_000;
        assert!(matches!(
            evaluate_trigger(&budget, 2_000, 2.0, &policy),
            CompressionTrigger::Absolute { .. }
        ));
    }

    #[test]
    fn thresholds_resolve_in_tokens_not_floats() {
        let percent = Threshold::percent(0.75);
        assert_eq!(percent.resolve(100_000), 75_000);
        // 夹到窗口：一个 500k 的绝对阈值在 100k 的窗口上没有意义，让它永远触发更荒谬。
        assert_eq!(Threshold::tokens(500_000).resolve(100_000), 100_000);
        assert_eq!(Threshold::tokens(40_000).resolve(100_000), 40_000);
        // 百分比给越界值也别存成 1.4。
        assert_eq!(Threshold::percent(1.4).percent, 1.0);
        assert_eq!(Threshold::percent(f64::NAN).percent, 1.0);
    }

    #[test]
    fn retarget_follows_the_window() {
        // 百分比模式：换模型时按新窗口重算，不然 200k 上的 150k 阈值会在 32k 上立刻触发。
        let mut line = Threshold::percent(0.75);
        line.retarget(200_000);
        assert_eq!(line.tokens, 150_000);
        line.retarget(32_000);
        assert_eq!(line.tokens, 24_000);

        // token 模式：数字不动，只把显示用的百分比更新掉。
        let mut fixed = Threshold::tokens(24_000);
        fixed.retarget(32_000);
        assert_eq!(fixed.tokens, 24_000);
        assert!((fixed.percent - 0.75).abs() < 1e-9);
    }

    #[test]
    fn policy_reports_insane_line_order() {
        let mut policy = TriggerPolicy::default();
        assert!(policy.sane(100_000));
        // 绝对线压到建议线下面：建议线永远轮不到，UI 该拦住。
        policy.absolute = Threshold::percent(0.5);
        assert!(!policy.sane(100_000));
    }

    #[test]
    fn decision_table_matches_d90() {
        use CompressAction as A;
        use CompressionTrigger as T;
        use TriggerPoint as P;
        let suggest = T::Suggest { ratio: 0.8 };
        let absolute = T::Absolute { ratio: 0.95 };
        let soft = T::Soft {
            remaining_turns: 3.0,
            payback_turns: 11.5,
        };

        // 建议线：照发，说完再压；途中不动。
        assert_eq!(decide(suggest, P::BeforeRequest), A::ProceedThenCompact);
        assert_eq!(decide(suggest, P::DuringGeneration), A::Proceed);
        assert_eq!(decide(suggest, P::AfterGeneration), A::CompactAfterPersist);

        // 绝对线：先压、截断、落库后压。
        assert_eq!(decide(absolute, P::BeforeRequest), A::CompactThenProceed);
        assert_eq!(
            decide(absolute, P::DuringGeneration),
            A::TruncateThenCompact
        );
        assert_eq!(decide(absolute, P::AfterGeneration), A::CompactAfterPersist);

        // 成本判据只顺手压，不拦不截。
        assert_eq!(decide(soft, P::BeforeRequest), A::ProceedThenCompact);
        assert_eq!(decide(soft, P::DuringGeneration), A::Proceed);

        assert_eq!(decide(T::None, P::BeforeRequest), A::Proceed);
        assert!(decide(absolute, P::BeforeRequest).blocks());
        assert!(!decide(suggest, P::BeforeRequest).blocks());
    }

    #[test]
    fn cost_based_trigger_fires_when_runway_short() {
        let policy = TriggerPolicy::default();
        let mut budget = Budget::new(100_000, 100_000, 0);
        budget.used = 40_000;
        // 还剩 30 轮，但回本周期 24 轮 × 1.5 = 36 轮 → 提前压缩。
        let trigger = evaluate_trigger(&budget, 2_000, 24.0, &policy);
        assert!(matches!(trigger, CompressionTrigger::Soft { .. }));
    }

    #[test]
    fn payback_matches_appendix_a() {
        // 显式断点式 1.25 / 0.10 → 11.5 轮
        assert!((payback_turns_truncating(1.25, 0.10) - 11.5).abs() < 1e-9);
        // 基准平台 1.00 / 0.04 → 24 轮
        assert!((payback_turns_truncating(1.00, 0.04) - 24.0).abs() < 1e-9);
        assert!(payback_turns(200_000, 100_000, 0, 1.0, 0.04).is_infinite());
    }

    #[test]
    fn refeed_cost_can_turn_saving_negative() {
        // 30 轮、删除 20k、回灌 20k：节省被回灌吃掉，再减去一次性代价 → 负。
        let saving = net_saving(20_000, 0.04, 30, 200_000, 180_000, 1.0, 20_000);
        assert!(saving < 0.0, "回灌双份成本必须体现为负收益，实际 {saving}");

        // 不回灌时同一条件下为正。
        let saving = net_saving(20_000, 0.04, 30, 200_000, 180_000, 1.0, 0);
        assert!(saving > 0.0);
    }

    #[test]
    fn usage_cache_hit() {
        let usage = Usage {
            prompt_tokens: 1_000,
            completion_tokens: 200,
            cached_tokens: 900,
            reasoning_tokens: 150,
        };
        assert!((usage.cache_hit_ratio() - 0.9).abs() < 1e-9);
        assert_eq!(usage.total(), 1_200);
    }

    // --- 分段用量 ---------------------------------------------------------

    fn session() -> crate::ids::SessionId {
        "0199c0de-0000-7000-8000-000000000001"
            .parse()
            .expect("示例 uuid 合法")
    }

    fn text_unit(
        seq: u64,
        role: crate::message::Role,
        source: SourceTag,
        text: &str,
    ) -> AtomicUnit {
        let message = crate::message::Message::text(
            crate::ids::MessageId::new(session(), seq),
            role,
            source,
            text,
            estimate_tokens(text),
            1_000,
            crate::clock::LogicalClock::new(seq),
        );
        AtomicUnit::turn(message).expect("单条文本单元必然闭合")
    }

    /// 以后再加一个 `SourceTag` 却忘了归类，这条会红。
    #[test]
    fn every_source_tag_lands_on_exactly_one_row() {
        let mut breakdown = Breakdown::new();
        for tag in SourceTag::ALL {
            breakdown.add(tag, 100);
        }
        let total_items: u64 = breakdown.rows().iter().map(|row| row.items).sum();
        assert_eq!(total_items, SourceTag::ALL.len() as u64);
        assert_eq!(breakdown.total(), 100 * SourceTag::ALL.len() as u64);
    }

    #[test]
    fn rows_are_eight_fixed_and_in_order() {
        let rows = Breakdown::new().rows();
        assert_eq!(rows.len(), 8);
        for (index, row) in rows.iter().enumerate() {
            assert_eq!(row.segment, Segment::ALL[index]);
            assert_eq!(row.tokens, 0);
            assert!(
                !row.segment.label().is_empty(),
                "每一行都要有名字，UI 不该自己维护词表"
            );
        }
        // 顺序是面板的约定，不是"碰巧这个顺序"。
        assert_eq!(Segment::ALL[0], Segment::History);
        assert_eq!(Segment::ALL[7], Segment::Draft);
    }

    #[test]
    fn shares_sum_to_one_and_empty_is_finite() {
        let mut breakdown = Breakdown::new();
        breakdown.add(SourceTag::History, 300);
        breakdown.add(SourceTag::Skills, 100);
        let sum: f64 = Segment::ALL.iter().map(|s| breakdown.share(*s)).sum();
        assert!((sum - 1.0).abs() < 1e-9);

        let empty = Breakdown::new();
        assert_eq!(empty.share(Segment::History), 0.0);
        assert!(empty.share(Segment::History).is_finite());
        assert!(empty.is_empty());
    }

    #[test]
    fn mcp_tools_get_their_own_row_by_name_prefix() {
        let mut breakdown = Breakdown::new();
        breakdown.add_tool("read_file", 100);
        breakdown.add_tool("mcp__filesystem__read_file", 250);
        assert_eq!(breakdown.tokens_of(Segment::BuiltinTools), 100);
        assert_eq!(breakdown.tokens_of(Segment::McpTools), 250);
        assert_eq!(breakdown.total(), 350);

        assert!(is_mcp_tool("mcp__x"));
        assert!(is_mcp_tool("mcp.x"));
        assert!(
            !is_mcp_tool("mcpx"),
            "前缀必须带分隔符，不能靠 mcp 开头就算"
        );
        assert!(!is_mcp_tool(""));
    }

    #[test]
    fn breakdown_with_estimator_recounts_instead_of_trusting_stored_tokens() {
        // 落库时写的是 L0 数（这里刻意填一个离谱的值），带估算器读出来必须按内容重算。
        let mut message = crate::message::Message::text(
            crate::ids::MessageId::new(session(), 1),
            crate::message::Role::Assistant,
            SourceTag::History,
            "the quick brown fox jumps over a lazy dog",
            9_999,
            0,
            crate::clock::LogicalClock::new(1),
        );
        message.reasoning = Some("先想一想".to_string());
        let unit = AtomicUnit::turn(message).expect("文本身份");

        let stored = breakdown_of_units(std::slice::from_ref(&unit), None);
        assert!(
            stored.tokens_of(Segment::History) >= 9_999,
            "None 就该信库里的数"
        );

        let estimator = Estimator::new("vendor/model-a", &[]);
        let counted = breakdown_of_units(std::slice::from_ref(&unit), Some(&estimator));
        let expected = estimator
            .estimate_all(["the quick brown fox jumps over a lazy dog", "先想一想"])
            .tokens as u64;
        assert_eq!(counted.tokens_of(Segment::History), expected);
        assert!(counted.total() < 9_999, "重算过就不该还是那个落库值");
    }

    #[test]
    fn breakdown_counts_tool_call_arguments() {
        let arguments = r#"{"path":"a-very-long-file-name.txt"}"#;
        let call = crate::message::OutgoingToolCall::new("c1", "read_file", arguments);
        let head = crate::message::Message::tool_call_message(
            crate::ids::MessageId::new(session(), 1),
            vec![call],
            1,
            // content 为空：参数在 `tool_calls` 里，不在 `content` 里。
            0,
            crate::clock::LogicalClock::new(1),
        );
        let unit = AtomicUnit::tool_pair(head).expect("工具头单元");
        let estimator = Estimator::new("test-model", &[]);
        let breakdown = breakdown_of_units(std::slice::from_ref(&unit), Some(&estimator));
        // 漏掉参数的话这一行的数会一直是 1（空 content），也就是一直偏小。
        assert!(breakdown.total() > 1, "{}", breakdown.total());
    }

    /// 工具结果那条没有工具名，只能跟着同一个单元里的调用走。
    #[test]
    fn mcp_pair_counts_both_messages_into_mcp_row() {
        let arguments = r#"{"path":"a.txt"}"#;
        let call = crate::message::OutgoingToolCall::new("call_1", "mcp__fs__read", arguments);
        let head = crate::message::Message::tool_call_message(
            crate::ids::MessageId::new(session(), 1),
            vec![call],
            estimate_tokens(arguments),
            1_000,
            crate::clock::LogicalClock::new(1),
        );
        let mut unit = AtomicUnit::tool_pair(head).expect("工具头单元");
        let result = crate::message::Message::tool_result(
            crate::ids::MessageId::new(session(), 2),
            "call_1",
            "文件内容",
            10,
            1_000,
            crate::clock::LogicalClock::new(2),
        )
        .with_unit(unit.id.clone());
        unit.add_result(result).expect("结果挂得上");

        let breakdown = breakdown_of_units(&[unit], None);
        assert_eq!(breakdown.items_of(Segment::McpTools), 2);
        assert_eq!(breakdown.items_of(Segment::BuiltinTools), 0);
    }

    #[test]
    fn reasoning_is_counted_though_tokens_field_does_not_hold_it() {
        let mut message = crate::message::Message::text(
            crate::ids::MessageId::new(session(), 1),
            crate::message::Role::Assistant,
            SourceTag::History,
            "在吗",
            estimate_tokens("在吗"),
            1_000,
            crate::clock::LogicalClock::new(1),
        );
        message.reasoning = Some("先看看".to_string());
        let unit = AtomicUnit::turn(message).expect("单条文本单元");

        let breakdown = breakdown_of_units(&[unit], None);
        let expected = u64::from(estimate_tokens("在吗")) + u64::from(estimate_tokens("先看看"));
        assert_eq!(breakdown.tokens_of(Segment::History), expected);
        assert!(
            breakdown.tokens_of(Segment::History) > u64::from(estimate_tokens("在吗")),
            "思考也是随历史一起送出去的，不能不算"
        );
    }

    #[test]
    fn mixed_units_land_in_the_right_rows() {
        let units = vec![
            text_unit(1, crate::message::Role::User, SourceTag::History, "你好"),
            text_unit(
                2,
                crate::message::Role::System,
                SourceTag::System,
                "你是助手",
            ),
            text_unit(3, crate::message::Role::User, SourceTag::WorldBook, "设定"),
            text_unit(4, crate::message::Role::System, SourceTag::Skills, "skill"),
        ];
        let breakdown = breakdown_of_units(&units, None);
        assert_eq!(breakdown.items_of(Segment::History), 1);
        assert_eq!(breakdown.items_of(Segment::SystemPrompt), 1);
        assert_eq!(breakdown.items_of(Segment::WorldBook), 1);
        assert_eq!(breakdown.items_of(Segment::Skills), 1);
        let row_total: u64 = breakdown.rows().iter().map(|row| row.tokens).sum();
        assert_eq!(row_total, breakdown.total(), "总量必须等于各行之和");
    }
}
