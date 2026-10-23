//! 图标规则的匹配与排序（P6.5 / D105）。
//!
//! 界面纹理在别处，这里只有"给一个模型名/供应商，选出该用哪个图标 key"这件事。
//! 拆成纯函数是因为它是全部逻辑里唯一会"悄悄选错"的地方——选错了界面看起来仍然正常，
//! 只是图标不对，没人会去查。
//!
//! # 排序键（全序，写死在这一个地方）
//!
//! ```text
//! (优先级 升序, 来源 user< preset, 锚定 true 优先, 有效字面量长度 降序, 通配符数 升序, pattern, id)
//! ```
//!
//! 为什么不是"正则字符串长度"：`(?i)`、转义、`(?<name>)`、`[Cc]` 都会虚增长度，
//! 而 `^(alibaba/|dashscope/)?qwen` 比 `^claude-opus` 长得多却远不如后者具体。
//! 所以算"有效字面量长度"：剥掉内联标志与元字符、转义还原成一个字面量、多分支取最长的那支。
//!
//! 为什么最后要落到 `id`：加载顺序（文件遍历、导入包、UI 拖动）不稳定，
//! 拿它兜底会让同一份配置在两台设备上给出不同图标。全序之后任何顺序结果都一样。
//!
//! # 大小写
//!
//! - `Keyword`：看 `case_sensitive`。
//! - `Regex`：**以正则写法为准**（`case_sensitive` 忽略，UI 上该开关置灰）。
//!   `(?i)` 就该写在 pattern 里——同一条规则在两种模式下行为一致才好解释。

use regex::{Regex, RegexBuilder};
use serde::{Deserialize, Serialize};

/// 单条 pattern 的字节上限。定在 200 而不是 256：真实的规则几乎都在 40 字节以内，
/// 超过 200 的多半是把"三条规则"塞进了一条（见 [`RuleError::TooLong`] 的建议）。
pub const MAX_PATTERN_BYTES: usize = 200;

/// 规则条数上限。500 是按"每个模型家族平均 2 条、覆盖 200+ 家族"估的。
pub const MAX_RULES: usize = 500;

/// icon key 的长度上限（只允许字母、数字、`-`、`_`；它同时是文件名）。
pub const MAX_KEY_CHARS: usize = 64;

/// 规则作用在哪一类对象上。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum IconKind {
    Action,
    Provider,
    Model,
}

impl IconKind {
    pub fn as_str(self) -> &'static str {
        match self {
            IconKind::Action => "action",
            IconKind::Provider => "provider",
            IconKind::Model => "model",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "action" => Some(IconKind::Action),
            "provider" => Some(IconKind::Provider),
            "model" => Some(IconKind::Model),
            _ => None,
        }
    }
}

/// 匹配哪个字段。分开是必须的：一条 `openai` 不该同时吃掉
/// "供应商叫 openai"和"模型名里含 openai"这两件不同的事。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RuleField {
    /// 归一化后的 `provider/model` 全名。
    Model,
    /// provider 的 kind：`openai` / `gemini` / `anthropic` / `ollama` / `compat`。
    ProviderKind,
    /// `base_url` 的域名（小写，去端口）。
    ProviderHost,
    /// 用户给这个供应商起的显示名。
    ProviderName,
}

impl RuleField {
    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "model" => Some(RuleField::Model),
            "provider_kind" => Some(RuleField::ProviderKind),
            "provider_host" => Some(RuleField::ProviderHost),
            "provider_name" => Some(RuleField::ProviderName),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RuleMode {
    /// 子串匹配（大小写由 `case_sensitive` 决定）。给不想写正则的人用。
    Keyword,
    /// 正则，写法即行为（`(?i)` 自己写）。
    Regex,
}

impl RuleMode {
    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "keyword" => Some(RuleMode::Keyword),
            "regex" => Some(RuleMode::Regex),
            _ => None,
        }
    }
}

/// 规则来自哪儿。同优先级时**用户规则赢预设**——用户是主动加的，得让他一加就生效。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RuleSource {
    User,
    Preset,
}

impl RuleSource {
    fn rank(self) -> u8 {
        match self {
            RuleSource::User => 0,
            RuleSource::Preset => 1,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IconRule {
    /// 稳定标识。排序最后用它兜底，所以发布后不许复用。
    pub id: String,
    pub pattern: String,
    pub mode: RuleMode,
    pub field: RuleField,
    pub kind: IconKind,
    /// 目标图标 key（包内 entry 的 key，或用户自建图标的 id）。
    pub key: String,
    /// 0–1000，**数字越小越优先**。默认 500。
    pub priority: i32,
    /// 只对 `Keyword` 有意义；`Regex` 下忽略（写法即行为）。
    #[serde(default)]
    pub case_sensitive: bool,
    #[serde(default = "RuleSource::default_value")]
    pub source: RuleSource,
}

impl RuleSource {
    fn default_value() -> RuleSource {
        RuleSource::Preset
    }
}

/// 规则的静态属性：用来算"谁更具体"。**不是语义精确度**，是一个可解释的近似。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Specificity {
    /// 是否锚定开头（`^`）。锚定 > 不锚定。
    pub anchored: bool,
    /// 有效字面量长度：剥掉内联标志与元字符、转义还原成 1 个字面量、
    /// 多分支取**最长的那一支**。
    pub literal: usize,
    /// 通配符数量（`.` `*` `+` `?` `[`）。越少越具体，用来破长度并列。
    pub wildcards: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuleError {
    EmptyPattern,
    TooLong { bytes: usize, max: usize },
    BadRegex { message: String },
    BadPriority { value: i32 },
    BadKey { reason: &'static str },
    TooManyRules { count: usize, max: usize },
}

impl std::fmt::Display for RuleError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RuleError::EmptyPattern => write!(f, "pattern 不能为空"),
            RuleError::TooLong { bytes, max } => {
                write!(f, "pattern 太长：{bytes} 字节（上限 {max}）")
            }
            RuleError::BadRegex { message } => write!(f, "正则编译不过：{message}"),
            RuleError::BadPriority { value } => write!(f, "优先级越界：{value}（0–1000）"),
            RuleError::BadKey { reason } => write!(f, "图标 key 不合法：{reason}"),
            RuleError::TooManyRules { count, max } => {
                write!(f, "规则太多：{count} 条（上限 {max}）")
            }
        }
    }
}

impl std::error::Error for RuleError {}

impl RuleError {
    /// 给用户看的一句话，带可执行的改法。UI 直接显示这个字符串。
    pub fn suggestion(&self, pattern: &str) -> String {
        match self {
            RuleError::EmptyPattern => "pattern 不能为空".to_string(),
            RuleError::TooLong { bytes, max } => {
                let mut hint = format!("pattern 有 {bytes} 字节，上限 {max}。");
                if pattern.contains('|') {
                    hint.push_str("看起来塞了分支：把 `A|B` 拆成两条规则，各自更短也更可控。");
                } else if pattern.contains(".*") {
                    hint.push_str("`.*` 能删就删，用实际分隔符更准（`claude-opus-5` 比 `claude.*opus.*5` 好）。");
                } else {
                    hint.push_str("只保留能区分的开头部分，例如 `^claude-opus`。");
                }
                hint
            }
            RuleError::BadRegex { message } => format!("正则编译不过：{message}"),
            RuleError::BadPriority { value } => {
                format!("优先级要在 0–1000 之间，当前是 {value}")
            }
            RuleError::BadKey { reason } => format!("图标 key 不合法：{reason}"),
            RuleError::TooManyRules { count, max } => {
                format!("规则有 {count} 条，上限 {max}。先删掉不再命中的那几条。")
            }
        }
    }
}

/// 一条编译好的规则。`Regex` 只编译一次（[`RuleSet::new`] 里做）。
#[derive(Debug)]
struct Compiled {
    rule: IconRule,
    regex: Option<Regex>,
    spec: Specificity,
}

impl Compiled {
    fn matches(&self, subject: &str) -> bool {
        match (&self.regex, self.rule.mode) {
            (Some(regex), _) => regex.is_match(subject),
            (None, RuleMode::Keyword) => {
                if self.rule.case_sensitive {
                    subject.contains(&self.rule.pattern)
                } else {
                    subject
                        .to_lowercase()
                        .contains(&self.rule.pattern.to_lowercase())
                }
            }
            // 不该发生：Regex 模式没有 regex 说明 new 里漏了编译，当不命中处理。
            (None, RuleMode::Regex) => false,
        }
    }
}

/// 一组规则。构造时排序一次，之后查询是线性扫描（规则上限 500，够快）。
#[derive(Debug, Default)]
pub struct RuleSet {
    compiled: Vec<Compiled>,
}

impl RuleSet {
    /// 编译并排序。坏规则**跳过并回报**，不让一条烂正则把整个界面搞挂。
    pub fn new(rules: &[IconRule]) -> (Self, Vec<(String, RuleError)>) {
        let mut problems = Vec::new();
        let mut compiled = Vec::new();
        for rule in rules {
            match compile(rule) {
                Ok(item) => compiled.push(item),
                Err(error) => problems.push((rule.id.clone(), error)),
            }
        }
        compiled.sort_by(|a, b| rank_key(a).cmp(&rank_key(b)));
        (Self { compiled }, problems)
    }

    pub fn len(&self) -> usize {
        self.compiled.len()
    }

    pub fn is_empty(&self) -> bool {
        self.compiled.is_empty()
    }

    /// 在给定字段上找最优先的那条。`subject` 由调用方按字段归一化好。
    pub fn best_match(&self, field: RuleField, subject: &str) -> Option<&IconRule> {
        self.compiled
            .iter()
            .find(|item| item.rule.field == field && item.matches(subject))
            .map(|item| &item.rule)
    }

    /// 一次查询里所有命中的规则，按顺序返回。给设置页的"命中预览"用。
    pub fn matches(&self, field: RuleField, subject: &str) -> Vec<&IconRule> {
        self.compiled
            .iter()
            .filter(|item| item.rule.field == field && item.matches(subject))
            .map(|item| &item.rule)
            .collect()
    }
}

/// 供应商侧的一次查询需要三个字段。字段之间也有顺序：
/// `kind` 是结构化的（`openai` / `gemini` / `anthropic` / `ollama` / `compat`），比域名可靠；
/// 域名比用户随口起的名字可靠。所以按 kind → host → name 依次问，第一个有命中就用它。
#[derive(Debug, Clone, Copy)]
pub struct ProviderSubject<'a> {
    pub kind: &'a str,
    pub host: &'a str,
    pub name: &'a str,
}

impl<'a> ProviderSubject<'a> {
    pub fn new(kind: &'a str, host: &'a str, name: &'a str) -> Self {
        Self { kind, host, name }
    }
}

/// 供应商图标的规则查询：三个字段依次问，第一个有命中的返回。
pub fn best_provider_rule<'a>(
    set: &'a RuleSet,
    subject: &ProviderSubject<'_>,
) -> Option<&'a IconRule> {
    let queries = [
        (RuleField::ProviderKind, subject.kind),
        (RuleField::ProviderHost, subject.host),
        (RuleField::ProviderName, subject.name),
    ];
    queries
        .iter()
        .find_map(|(field, value)| set.best_match(*field, value))
}

/// 排序键。**改这里等于改全局行为**，所以只允许在这一处决定顺序。
fn rank_key(item: &Compiled) -> (i32, u8, u8, usize, usize, &str, &str) {
    (
        item.rule.priority,
        item.rule.source.rank(),
        // 锚定的排前面：`^claude` 比 `claude` 具体
        if item.spec.anchored { 0 } else { 1 },
        // 有效字面量长的更具体（降序：用 usize 取反不直观，这里用 Reverse 的等价写法）
        usize::MAX - item.spec.literal,
        item.spec.wildcards,
        item.rule.pattern.as_str(),
        item.rule.id.as_str(),
    )
}

fn compile(rule: &IconRule) -> Result<Compiled, RuleError> {
    if rule.pattern.trim().is_empty() {
        return Err(RuleError::EmptyPattern);
    }
    let bytes = rule.pattern.len();
    if bytes > MAX_PATTERN_BYTES {
        return Err(RuleError::TooLong {
            bytes,
            max: MAX_PATTERN_BYTES,
        });
    }
    if !(0..=1000).contains(&rule.priority) {
        return Err(RuleError::BadPriority {
            value: rule.priority,
        });
    }
    validate_key(&rule.key)?;

    let regex = match rule.mode {
        RuleMode::Keyword => None,
        RuleMode::Regex => {
            // 正则以写法为准：不加额外的 case-insensitive 包装。
            let built = RegexBuilder::new(&rule.pattern)
                .size_limit(1 << 20)
                .build()
                .map_err(|error| RuleError::BadRegex {
                    message: error.to_string(),
                })?;
            Some(built)
        }
    };
    let spec = specificity(&rule.pattern, rule.mode);
    Ok(Compiled {
        rule: rule.clone(),
        regex,
        spec,
    })
}

/// key 同时是文件名与目录名，只允许安全字符——省掉后面所有的路径校验。
pub fn validate_key(key: &str) -> Result<(), RuleError> {
    if key.is_empty() {
        return Err(RuleError::BadKey {
            reason: "不能为空"
        });
    }
    if key.chars().count() > MAX_KEY_CHARS {
        return Err(RuleError::BadKey {
            reason: "太长（上限 64 个字符）",
        });
    }
    if !key
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
    {
        // 它同时是文件名与目录名，只允许安全字符——省掉后面所有的路径校验
        return Err(RuleError::BadKey {
            reason: "只允许字母、数字、`-`、`_`",
        });
    }
    Ok(())
}

/// 算"这条规则有多具体"。`Keyword` 就是字符数；`Regex` 走
/// [`specificity_regex`]。结果只用于排序与展示，不参与命中判定。
pub fn specificity(pattern: &str, mode: RuleMode) -> Specificity {
    match mode {
        RuleMode::Keyword => Specificity {
            anchored: false,
            literal: pattern.chars().count(),
            wildcards: 0,
        },
        RuleMode::Regex => specificity_regex(pattern),
    }
}

fn specificity_regex(pattern: &str) -> Specificity {
    let branches = split_alternatives(pattern);
    let mut best = Specificity {
        anchored: false,
        literal: 0,
        wildcards: 0,
    };
    for branch in branches {
        let current = branch_stats(&branch);
        if current.literal > best.literal
            || (current.literal == best.literal && current.anchored && !best.anchored)
        {
            best = current;
        }
    }
    best
}

/// 按**顶层** `|` 拆分支：括号里的选择属于那条分支内部的细节，不算顶层分支。
/// 字符类里的 `|` 与转义的 `\|` 也不是分支。
fn split_alternatives(pattern: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut depth = 0usize;
    let mut in_class = false;
    let mut chars = pattern.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '\\' => {
                current.push(ch);
                if let Some(next) = chars.next() {
                    current.push(next);
                }
            }
            '[' if !in_class => {
                in_class = true;
                current.push(ch);
            }
            ']' if in_class => {
                in_class = false;
                current.push(ch);
            }
            '(' if !in_class => {
                depth += 1;
                current.push(ch);
            }
            ')' if !in_class => {
                depth = depth.saturating_sub(1);
                current.push(ch);
            }
            '|' if !in_class && depth == 0 => out.push(std::mem::take(&mut current)),
            _ => current.push(ch),
        }
    }
    out.push(current);
    out
}

/// 一条分支的静态属性。
///
/// 只数**必须有**的字面量：可选组 `(…)?`、`(…)*` 和带 `|` 的组都不算——
/// `^(alibaba/|dashscope/)?qwen` 的具体度应该等于 `qwen`，而不是把三个词加起来。
/// 这样排出来的顺序才符合直觉：越"窄"的规则越靠前。
fn branch_stats(branch: &str) -> Specificity {
    let chars: Vec<char> = branch.chars().collect();
    let mut anchored = false;
    let mut literal = 0usize;
    let mut wildcards = 0usize;
    let mut index = 0usize;
    let mut first = true;

    while index < chars.len() {
        let ch = chars[index];
        match ch {
            '\\' => {
                index += 1;
                if let Some(next) = chars.get(index) {
                    if matches!(next, 'd' | 'w' | 's' | 'b' | 'D' | 'W' | 'S' | 'B') {
                        wildcards += 1;
                    } else {
                        literal += 1;
                    }
                }
            }
            '^' if first => anchored = true,
            '[' => {
                // 字符类整段算一个通配符
                wildcards += 1;
                index += 1;
                while index < chars.len() {
                    if chars[index] == '\\' {
                        index += 2;
                        continue;
                    }
                    if chars[index] == ']' {
                        break;
                    }
                    index += 1;
                }
            }
            '(' => {
                let (inner, close) = match matching_paren(&chars, index) {
                    Some(found) => found,
                    None => {
                        // 括号没闭合：正则编译那一步会报错，这里不重复判
                        index += 1;
                        first = false;
                        continue;
                    }
                };
                let inner_text: String = inner.iter().collect();
                let after = chars.get(close + 1).copied();
                let optional = matches!(after, Some('?') | Some('*') | Some('{'));
                let choice = has_top_level_alt(&inner);
                let is_flag = inner_text.starts_with('?');

                if is_flag {
                    // (?i) (?is) 内联标志：不是具体度
                } else if optional || choice {
                    // 可选或是"多选一"：不是必须出现的，不计入具体度
                    wildcards += 1;
                } else {
                    let sub = branch_stats(&inner_text);
                    literal += sub.literal;
                    wildcards += sub.wildcards;
                }
                index = close + 1;
                first = false;
                continue;
            }
            '.' | '*' | '+' | '?' | '{' | '}' => wildcards += 1,
            ')' | '|' => {}
            _ => literal += 1,
        }
        index += 1;
        first = false;
    }

    Specificity {
        anchored,
        literal,
        wildcards,
    }
}

/// 找到与 `open` 位置的 `(` 配对的 `)`，返回括号内字符与 `)` 的下标。
fn matching_paren(chars: &[char], open: usize) -> Option<(Vec<char>, usize)> {
    let mut depth = 0usize;
    let mut in_class = false;
    let mut index = open;
    let mut inner = Vec::new();
    while index < chars.len() {
        let ch = chars[index];
        match ch {
            '\\' => {
                if index + 1 < chars.len() {
                    if index > open {
                        inner.push(ch);
                        inner.push(chars[index + 1]);
                    }
                    index += 2;
                    continue;
                }
            }
            '[' => {
                in_class = true;
                if index > open {
                    inner.push(ch);
                }
            }
            ']' => {
                in_class = false;
                if index > open {
                    inner.push(ch);
                }
            }
            '(' if !in_class => {
                depth += 1;
                if index > open {
                    inner.push(ch);
                }
            }
            ')' if !in_class => {
                depth -= 1;
                if depth == 0 {
                    return Some((inner, index));
                }
                inner.push(ch);
            }
            _ => {
                if index > open {
                    inner.push(ch);
                }
            }
        }
        index += 1;
    }
    None
}

/// 组内是否有顶层 `|`（即"多选一"）。
fn has_top_level_alt(chars: &[char]) -> bool {
    let mut depth = 0usize;
    let mut in_class = false;
    let mut index = 0usize;
    while index < chars.len() {
        match chars[index] {
            '\\' => index += 1,
            '[' => in_class = true,
            ']' => in_class = false,
            '(' if !in_class => depth += 1,
            ')' if !in_class => depth = depth.saturating_sub(1),
            '|' if !in_class && depth == 0 => return true,
            _ => {}
        }
        index += 1;
    }
    false
}

/// 匹配前的归一化：小写、`.`/`_` 视同 `-`、去掉 `[分组]` 前缀。
///
/// **不要**去掉 `/`：`provider/model` 的分段信息是供应商规则要用的。
pub fn normalize_subject(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    // 只吃开头的 [..]；中间的方括号是名字的一部分
    if chars.peek() == Some(&'[') {
        let mut probe = chars.clone();
        probe.next();
        let mut closed = false;
        for next in probe {
            if next == ']' {
                closed = true;
                break;
            }
        }
        if closed {
            for next in chars.by_ref() {
                if next == ']' {
                    break;
                }
            }
        }
    }
    for ch in chars {
        if ch.is_uppercase() {
            out.extend(ch.to_lowercase());
        } else if ch == '.' || ch == '_' {
            out.push('-');
        } else if !ch.is_whitespace() {
            out.push(ch);
        }
    }
    out
}

/// 取模型名里"最后一段"（去掉 `provider/`），给供应商匹配与首字母用。
pub fn model_tail(subject: &str) -> &str {
    match subject.rsplit_once('/') {
        Some((_, tail)) if !tail.is_empty() => tail,
        _ => subject,
    }
}

/// 首字母退化用的方块内容（D104）：字母 + 色相。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Monogram {
    /// 显示用的字符：拉丁字母已大写，CJK 原样。
    pub letter: char,
    /// HSL 的 hue（0..360，15° 一档共 24 档）。
    pub hue: u16,
}

/// 色相步长与饱和度/亮度，与 `docs/ui/icons/README.md` §4 保持一致。
pub const MONOGRAM_HUE_STEP: u16 = 15;
pub const MONOGRAM_SATURATION: f32 = 0.30;
pub const MONOGRAM_LIGHTNESS: f32 = 0.36;

/// FNV-1a 32 位。用它而不是 DefaultHasher：跨版本稳定，两个平台的同名字一定同色。
pub fn fnv1a(text: &str) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in text.as_bytes() {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// 名字 → 首字母方块。空名字给 `?`，不返回 `Option`——界面永远有话可说。
pub fn monogram(name: &str) -> Monogram {
    let normalized = normalize_subject(name);
    let tail = model_tail(&normalized);
    let letter = tail
        .chars()
        .find(|ch| ch.is_alphanumeric())
        .map(|ch| {
            // 拉丁字母大写；CJK 原样（"豆包-seed" 是"豆"，不是拼音 D）
            if ch.is_ascii_lowercase() {
                ch.to_ascii_uppercase()
            } else {
                ch
            }
        })
        .unwrap_or('?');
    let hue = ((fnv1a(tail) % 24) as u16) * MONOGRAM_HUE_STEP;
    Monogram { letter, hue }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(id: &str, pattern: &str, key: &str, priority: i32, source: RuleSource) -> IconRule {
        IconRule {
            id: id.to_string(),
            pattern: pattern.to_string(),
            mode: RuleMode::Regex,
            field: RuleField::Model,
            kind: IconKind::Model,
            key: key.to_string(),
            priority,
            case_sensitive: false,
            source,
        }
    }

    fn preset(id: &str, pattern: &str, key: &str) -> IconRule {
        rule(id, pattern, key, 500, RuleSource::Preset)
    }

    fn user(id: &str, pattern: &str, key: &str) -> IconRule {
        rule(id, pattern, key, 500, RuleSource::User)
    }

    #[test]
    fn longer_literal_wins_over_shorter_at_same_priority() {
        // `claude.*opus.*5\.5` 比 `claude` 具体：前者才该命中 5.5
        let (set, problems) = RuleSet::new(&[
            preset("r1", "claude", "claude"),
            preset("r2", r"claude.*opus.*5\.5", "claude-opus-55"),
        ]);
        assert!(problems.is_empty(), "{problems:?}");
        let hit = set
            .best_match(RuleField::Model, "anthropic/claude-opus-5.5")
            .expect("should match");
        assert_eq!(hit.key, "claude-opus-55");
    }

    #[test]
    fn inline_flags_do_not_inflate_specificity() {
        // (?i) 不该让一条规则"变具体"
        let plain = specificity("claude", RuleMode::Regex);
        let with_flag = specificity("(?i)claude", RuleMode::Regex);
        assert_eq!(plain.literal, with_flag.literal, "(?i) 不是字面量");
        assert_eq!(with_flag.literal, 6);
    }

    #[test]
    fn alternation_ranks_by_its_longest_branch_not_by_total_length() {
        // 这条字符串很长，但它只是想表达"阿里家的 qwen"
        let wide = specificity("^(alibaba/|dashscope/)?qwen", RuleMode::Regex);
        let narrow = specificity("^claude-opus", RuleMode::Regex);
        assert_eq!(
            wide.literal, 4,
            "可选组里的 alibaba/dashscope 不算：必须部分只有 qwen"
        );
        assert!(narrow.literal > wide.literal);
        assert!(narrow.anchored && wide.anchored);
    }

    #[test]
    fn anchor_breaks_a_literal_length_tie() {
        let (set, _) = RuleSet::new(&[
            preset("r1", "opus", "unanchored"),
            preset("r2", "^opus", "anchored"),
        ]);
        let hit = set.best_match(RuleField::Model, "opus-4").unwrap();
        assert_eq!(hit.key, "anchored");
    }

    #[test]
    fn fewer_wildcards_breaks_a_length_tie() {
        // 同样 4 个字面量，`.*` 更少的那条更具体
        let (set, _) = RuleSet::new(&[
            preset("r1", "a.*b.*c.*d", "many-wildcards"),
            preset("r2", "a-b-c-d", "few-wildcards"),
        ]);
        let hit = set.best_match(RuleField::Model, "a-b-c-d").unwrap();
        assert_eq!(hit.key, "few-wildcards");
    }

    #[test]
    fn user_rule_beats_preset_at_the_same_priority() {
        let (set, _) = RuleSet::new(&[
            preset("r1", "kimi", "preset-kimi"),
            user("r2", "kimi", "user-kimi"),
        ]);
        let hit = set
            .best_match(RuleField::Model, "moonshot/kimi-k2")
            .unwrap();
        assert_eq!(hit.key, "user-kimi", "用户主动加的应该赢");
    }

    #[test]
    fn lower_priority_number_wins_even_against_more_specific_rule() {
        // 优先级是人的意图，具体度只是兜底
        let (set, _) = RuleSet::new(&[
            rule(
                "r1",
                "^claude-opus-5.5",
                "specific",
                600,
                RuleSource::Preset,
            ),
            rule("r2", "^claude", "pinned", 100, RuleSource::Preset),
        ]);
        let hit = set.best_match(RuleField::Model, "claude-opus-5.5").unwrap();
        assert_eq!(hit.key, "pinned");
    }

    #[test]
    fn result_does_not_depend_on_input_order() {
        let rules = vec![
            preset("b", "^claude", "k1"),
            preset("a", "^claude", "k2"),
            preset("c", "claude", "k3"),
        ];
        let mut reversed = rules.clone();
        reversed.reverse();
        let (first, _) = RuleSet::new(&rules);
        let (second, _) = RuleSet::new(&reversed);
        let a = first
            .best_match(RuleField::Model, "claude-3")
            .unwrap()
            .key
            .clone();
        let b = second
            .best_match(RuleField::Model, "claude-3")
            .unwrap()
            .key
            .clone();
        assert_eq!(a, b, "同一份配置，加载顺序不同也必须给出同一个结果");
    }

    #[test]
    fn keyword_mode_respects_the_case_flag() {
        let mut insensitive = rule("r1", "Claude", "kimi-insensitive", 500, RuleSource::Preset);
        insensitive.mode = RuleMode::Keyword;
        insensitive.case_sensitive = false;
        let mut sensitive = insensitive.clone();
        sensitive.id = "r2".to_string();
        sensitive.case_sensitive = true;
        sensitive.pattern = "Claude".to_string();

        let (set, problems) = RuleSet::new(&[insensitive]);
        assert!(problems.is_empty());
        assert!(set.best_match(RuleField::Model, "claude-3").is_some());
        let (set2, _) = RuleSet::new(&[sensitive]);
        assert!(set2.best_match(RuleField::Model, "claude-3").is_none());
        assert!(set2.best_match(RuleField::Model, "x/Claude-3").is_some());
    }

    #[test]
    fn regex_mode_ignores_the_case_flag() {
        // 正则以写法为准：pattern 没写 (?i) 就是大小写敏感
        let mut item = rule("r1", "Claude", "k", 500, RuleSource::Preset);
        item.case_sensitive = false; // 明确设为 false，也不该生效
        let (set, _) = RuleSet::new(&[item]);
        assert!(set.best_match(RuleField::Model, "claude-3").is_none());

        let mut insensitive = rule("r2", "(?i)claude", "k", 500, RuleSource::Preset);
        insensitive.case_sensitive = true;
        let (set2, _) = RuleSet::new(&[insensitive]);
        assert!(set2.best_match(RuleField::Model, "claude-3").is_some());
    }

    #[test]
    fn fields_do_not_bleed_into_each_other() {
        let mut provider_rule = preset("r1", "openai", "openai");
        provider_rule.field = RuleField::ProviderName;
        provider_rule.kind = IconKind::Provider;
        let (set, _) = RuleSet::new(&[provider_rule]);
        // 供应商名叫 openai
        assert!(set.best_match(RuleField::ProviderName, "openai").is_some());
        // 但模型名里的 openai 不该被它吃掉
        assert!(set.best_match(RuleField::Model, "openai/gpt-4o").is_none());
    }

    #[test]
    fn overlong_pattern_is_rejected_with_an_actionable_hint() {
        let long = format!("^{}", "a".repeat(MAX_PATTERN_BYTES));
        let (set, problems) = RuleSet::new(&[preset("r1", &long, "k")]);
        assert!(set.is_empty());
        assert_eq!(problems.len(), 1);
        assert!(matches!(problems[0].1, RuleError::TooLong { .. }));
        let hint = problems[0].1.suggestion(&long);
        assert!(hint.contains("上限"), "{hint}");
    }

    #[test]
    fn too_long_with_alternation_suggests_splitting() {
        let long = format!(
            "^(openai|anthropic|google|{}",
            "x".repeat(MAX_PATTERN_BYTES)
        );
        let hint = RuleError::TooLong {
            bytes: long.len(),
            max: MAX_PATTERN_BYTES,
        }
        .suggestion(&long);
        assert!(hint.contains("拆成两条规则"), "{hint}");
    }

    #[test]
    fn bad_regex_is_skipped_not_fatal() {
        let (set, problems) = RuleSet::new(&[
            preset("broken", "claude(", "k1"),
            preset("good", "claude", "k2"),
        ]);
        assert_eq!(problems.len(), 1);
        assert_eq!(problems[0].0, "broken");
        assert_eq!(set.len(), 1);
        assert_eq!(
            set.best_match(RuleField::Model, "claude").unwrap().key,
            "k2"
        );
    }

    #[test]
    fn key_must_be_a_safe_filename() {
        for bad in ["", "../etc/passwd", "a/b", "有中文", "a b"] {
            let (_, problems) = RuleSet::new(&[preset("r1", "x", bad)]);
            assert_eq!(problems.len(), 1, "key={bad:?} 应被拒绝");
        }
        assert!(RuleSet::new(&[preset("r1", "x", "claude-opus_5")])
            .1
            .is_empty());
    }

    #[test]
    fn provider_query_prefers_kind_over_host_over_name() {
        let mut by_kind = preset("k", "^openai", "by-kind");
        by_kind.field = RuleField::ProviderKind;
        by_kind.kind = IconKind::Provider;
        let mut by_name = preset("n", "^my", "by-name");
        by_name.field = RuleField::ProviderName;
        by_name.kind = IconKind::Provider;
        let (set, problems) = RuleSet::new(&[by_name, by_kind]);
        assert!(problems.is_empty());

        // kind 命中就赢，哪怕名字也命中
        let subject = ProviderSubject::new("openai", "api.deepseek.com", "my-endpoint");
        let hit = best_provider_rule(&set, &subject).unwrap();
        assert_eq!(hit.key, "by-kind");

        // kind 不中时退到名字
        let subject = ProviderSubject::new("compat", "api.deepseek.com", "my-endpoint");
        let hit = best_provider_rule(&set, &subject).unwrap();
        assert_eq!(hit.key, "by-name");

        // 都不中就是没有
        let subject = ProviderSubject::new("compat", "api.other.com", "other");
        assert!(best_provider_rule(&set, &subject).is_none());
    }

    #[test]
    fn priority_out_of_range_is_rejected() {
        let (_, problems) = RuleSet::new(&[
            rule("a", "x", "k", -1, RuleSource::Preset),
            rule("b", "x", "k", 1001, RuleSource::Preset),
        ]);
        assert_eq!(problems.len(), 2);
    }

    #[test]
    fn normalize_subject_strips_group_prefix_and_folds_separators() {
        assert_eq!(normalize_subject("[分组]Qwen3-Max"), "qwen3-max");
        assert_eq!(normalize_subject("DeepSeek_V3.1"), "deepseek-v3-1");
        assert_eq!(
            normalize_subject("anthropic/Claude-3.5"),
            "anthropic/claude-3-5"
        );
        // 中间的方括号是名字的一部分，不该被吃掉
        assert_eq!(normalize_subject("a[B]c"), "a[b]c");
    }

    #[test]
    fn monogram_takes_first_alnum_of_the_tail() {
        let moon = monogram("anthropic/claude-sonnet-4.5");
        assert_eq!(moon.letter, 'C');
        assert_eq!(monogram("[分组]Qwen3-Max").letter, 'Q');
        // CJK 原样，不转拼音
        assert_eq!(monogram("豆包-seed").letter, '豆');
        // 符号开头就往后找
        assert_eq!(monogram("--o1-preview").letter, 'O');
        assert_eq!(monogram("").letter, '?');
    }

    #[test]
    fn monogram_hue_is_stable_and_quantized() {
        let a = monogram("deepseek-chat");
        let b = monogram("deepseek-chat");
        assert_eq!(a.hue, b.hue, "同样的名字必须同样的颜色");
        assert_eq!(a.hue % MONOGRAM_HUE_STEP, 0);
        assert!(a.hue < 360);
        // 不同名字大概率不同色（不是必然，但至少不该永远相同）
        assert_ne!(a.hue, monogram("kimi-k2").hue);
    }
}
