//! 世界书（lorebook）：格式对齐 kelivo，语义按"激活 → 按位置注入"两步。
//!
//! 文件形态（与 kelivo 的导出完全一致，用户的现成文件可直接导入）：
//!
//! ```json
//! {"version":1,"type":"lorebook","data":{
//!   "id":"…","name":"…","description":"…","enabled":true,
//!   "entries":[{
//!     "id":"…","name":"…","enabled":true,"priority":0,
//!     "position":"AFTER_SYSTEM_PROMPT","content":"…",
//!     "injectDepth":4,"role":"ASSISTANT",
//!     "keywords":["关键词"],"useRegex":false,"caseSensitive":false,
//!     "scanDepth":4,"constantActive":true}]}}
//! ```
//!
//! 两条设计约定：
//!
//! 1. **注入是"新增消息"，不是改写已有消息**：所以它天然追加、不破坏缓存前缀；
//!    位置只决定它插在哪一段（system 前 / system 后 / 对话顶 / 指定深度 / 对话底）。
//! 2. **激活判定是确定性的**：常量激活、关键词（可正则、可区分大小写）、扫描深度。
//!    没有任何模型参与——世界书进不进上下文，事后必须能复盘。

use std::collections::BTreeSet;

use regex::RegexBuilder;
use serde::{Deserialize, Serialize};

use crate::message::Role;

/// 注入位置。字符串形式与 kelivo 一致（大写蛇形）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Position {
    /// 系统提示词之前。
    BeforeSystemPrompt,
    /// 系统提示词之后（最常用）。
    AfterSystemPrompt,
    /// 对话顶部。
    TopOfChat,
    /// 从末尾往前数第 `inject_depth` 条消息之前。
    AtDepth,
    /// 对话底部。
    BottomOfChat,
}

impl Position {
    pub fn as_str(self) -> &'static str {
        match self {
            Position::BeforeSystemPrompt => "BEFORE_SYSTEM_PROMPT",
            Position::AfterSystemPrompt => "AFTER_SYSTEM_PROMPT",
            Position::TopOfChat => "TOP_OF_CHAT",
            Position::AtDepth => "AT_DEPTH",
            Position::BottomOfChat => "BOTTOM_OF_CHAT",
        }
    }

    /// 注入顺序（越小越靠前）。`AtDepth` 内部再按深度排。
    pub fn order(self) -> u8 {
        match self {
            Position::BeforeSystemPrompt => 0,
            Position::AfterSystemPrompt => 1,
            Position::TopOfChat => 2,
            Position::AtDepth => 3,
            Position::BottomOfChat => 4,
        }
    }
}

/// 注入时伪装成哪种角色。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum InjectionRole {
    System,
    /// 默认：大多数世界书条目是"以用户口吻塞进去的一段设定"。
    #[default]
    User,
    Assistant,
}

impl InjectionRole {
    pub fn as_role(self) -> Role {
        match self {
            InjectionRole::System => Role::System,
            InjectionRole::User => Role::User,
            InjectionRole::Assistant => Role::Assistant,
        }
    }
}

/// 一条世界书条目。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LorebookEntry {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// 同一位置内的先后顺序（数字小的先注入）。
    #[serde(default)]
    pub priority: u32,
    pub position: Position,
    #[serde(default)]
    pub content: String,
    /// `AtDepth` 用：从末尾往前数第几条消息之前。
    #[serde(default = "default_depth")]
    pub inject_depth: usize,
    #[serde(default)]
    pub role: InjectionRole,
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default)]
    pub use_regex: bool,
    #[serde(default)]
    pub case_sensitive: bool,
    /// 往回扫描多少条消息做关键词匹配。
    #[serde(default = "default_depth")]
    pub scan_depth: usize,
    /// 常驻激活：无条件注入，不看关键词。
    #[serde(default)]
    pub constant_active: bool,
}

fn default_true() -> bool {
    true
}

fn default_depth() -> usize {
    4
}

impl LorebookEntry {
    /// 这条条目该不该注入。`history` 按时间顺序（最后一条是最近的）。
    pub fn is_active(&self, history: &[HistoryLine<'_>]) -> bool {
        if !self.enabled {
            return false;
        }
        if self.constant_active || self.keywords.is_empty() {
            return self.constant_active;
        }
        let window = scan_window(history, self.scan_depth);
        window.iter().any(|line| self.matches(line.text))
    }

    /// 命中了哪个关键词（便于 UI 与日志解释"为什么它进来了"）。
    pub fn matched_keyword(&self, history: &[HistoryLine<'_>]) -> Option<String> {
        if !self.enabled || self.constant_active {
            return None;
        }
        let window = scan_window(history, self.scan_depth);
        for keyword in &self.keywords {
            for line in window {
                if self.matches_pattern(keyword, line.text) {
                    return Some(keyword.clone());
                }
            }
        }
        None
    }

    fn matches(&self, text: &str) -> bool {
        self.keywords
            .iter()
            .any(|keyword| self.matches_pattern(keyword, text))
    }

    fn matches_pattern(&self, keyword: &str, text: &str) -> bool {
        if self.use_regex {
            // 非法正则当成"不匹配"，不让一条写坏的正则把整本世界书带崩
            match RegexBuilder::new(keyword)
                .case_insensitive(!self.case_sensitive)
                .build()
            {
                Ok(regex) => regex.is_match(text),
                Err(_) => false,
            }
        } else if self.case_sensitive {
            text.contains(keyword)
        } else {
            text.to_lowercase().contains(&keyword.to_lowercase())
        }
    }
}

/// 参与关键词扫描的一行历史。`text` 只要包含可读文本即可（正文 + 思考都行）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoryLine<'a> {
    pub role: Role,
    pub text: &'a str,
}

impl<'a> HistoryLine<'a> {
    pub fn new(role: Role, text: &'a str) -> Self {
        Self { role, text }
    }
}

fn scan_window<'a>(history: &'a [HistoryLine<'a>], scan_depth: usize) -> &'a [HistoryLine<'a>] {
    if scan_depth == 0 || scan_depth >= history.len() {
        return history;
    }
    &history[history.len() - scan_depth..]
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LorebookData {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub entries: Vec<LorebookEntry>,
}

/// 一个世界书文件。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Lorebook {
    #[serde(default = "default_version")]
    pub version: u32,
    /// 固定为 `lorebook`，用来识别文件类型。
    #[serde(default = "default_kind", rename = "type")]
    pub kind: String,
    pub data: LorebookData,
}

fn default_version() -> u32 {
    1
}

fn default_kind() -> String {
    "lorebook".to_string()
}

impl Lorebook {
    pub fn from_json(text: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(text)
    }

    pub fn to_json_pretty(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string_pretty(self)
    }
}

/// 一条待注入的内容。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Injection {
    pub position: Position,
    pub role: InjectionRole,
    pub content: String,
    /// `AtDepth` 时的深度（从末尾往前数）。
    pub depth: Option<usize>,
    /// 来自哪条条目（便于 UI 高亮与排查）。
    pub entry_id: String,
    pub entry_name: String,
    pub priority: u32,
}

/// 算出要注入哪些内容、按什么顺序、放在哪。
///
/// 顺序：system 前 → system 后 → 对话顶 → 指定深度（深的在前）→ 对话底；
/// 同一位置内按 `priority` 升序，再按条目 id 排（保证确定性）。
pub fn plan(book: &LorebookData, history: &[HistoryLine<'_>]) -> Vec<Injection> {
    if !book.enabled {
        return Vec::new();
    }
    let mut active: Vec<&LorebookEntry> = book
        .entries
        .iter()
        .filter(|entry| entry.is_active(history))
        .collect();

    active.sort_by(|a, b| {
        a.position
            .order()
            .cmp(&b.position.order())
            .then_with(|| a.priority.cmp(&b.priority))
            .then_with(|| a.id.cmp(&b.id))
    });

    let mut out: Vec<Injection> = active
        .into_iter()
        .map(|entry| Injection {
            position: entry.position,
            role: entry.role,
            content: entry.content.clone(),
            depth: match entry.position {
                Position::AtDepth => Some(entry.inject_depth),
                _ => None,
            },
            entry_id: entry.id.clone(),
            entry_name: entry.name.clone(),
            priority: entry.priority,
        })
        .collect();

    // AtDepth 之间：深度大的先注入（离末尾更远 = 更靠前），保证最终顺序稳定
    out.sort_by(|a, b| {
        a.position
            .order()
            .cmp(&b.position.order())
            .then_with(|| b.depth.unwrap_or(0).cmp(&a.depth.unwrap_or(0)))
            .then_with(|| a.priority.cmp(&b.priority))
            .then_with(|| a.entry_id.cmp(&b.entry_id))
    });
    out
}

/// 激活统计，给 UI 显示"这本世界书这次生效了几条"。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ActivationSummary {
    pub total: usize,
    pub active: usize,
    pub constant: usize,
    pub by_keyword: usize,
}

impl ActivationSummary {
    pub fn explain(&self) -> String {
        format!(
            "共 {} 条，本次生效 {} 条（常驻 {}、关键词命中 {}）",
            self.total, self.active, self.constant, self.by_keyword
        )
    }
}

pub fn summarize(book: &LorebookData, history: &[HistoryLine<'_>]) -> ActivationSummary {
    let mut summary = ActivationSummary {
        total: book.entries.len(),
        ..ActivationSummary::default()
    };
    if !book.enabled {
        return summary;
    }
    for entry in &book.entries {
        if !entry.is_active(history) {
            continue;
        }
        summary.active += 1;
        if entry.constant_active {
            summary.constant += 1;
        } else {
            summary.by_keyword += 1;
        }
    }
    summary
}

/// 一本世界书里的全部关键词（去重），给 UI 做"哪些词会触发"的提示。
pub fn keyword_index(book: &LorebookData) -> Vec<String> {
    let mut set = BTreeSet::new();
    for entry in &book.entries {
        for keyword in &entry.keywords {
            set.insert(keyword.clone());
        }
    }
    set.into_iter().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = include_str!("../../../data/lorebook.example.json");

    fn entry(id: &str, position: Position, constant: bool, keywords: &[&str]) -> LorebookEntry {
        LorebookEntry {
            id: id.to_string(),
            name: id.to_string(),
            enabled: true,
            priority: 0,
            position,
            content: format!("内容-{id}"),
            inject_depth: 4,
            role: InjectionRole::User,
            keywords: keywords.iter().map(|k| k.to_string()).collect(),
            use_regex: false,
            case_sensitive: false,
            scan_depth: 4,
            constant_active: constant,
        }
    }

    fn book(entries: Vec<LorebookEntry>) -> LorebookData {
        LorebookData {
            id: "b1".into(),
            name: "测试".into(),
            description: None,
            enabled: true,
            entries,
        }
    }

    #[test]
    fn parses_the_kelivo_sample() {
        let lore = Lorebook::from_json(SAMPLE).expect("用户的示例文件必须能直接读");
        assert_eq!(lore.kind, "lorebook");
        assert_eq!(lore.version, 1);
        assert_eq!(lore.data.entries.len(), 5);

        let positions: Vec<Position> = lore.data.entries.iter().map(|e| e.position).collect();
        for expected in [
            Position::AfterSystemPrompt,
            Position::BeforeSystemPrompt,
            Position::TopOfChat,
            Position::BottomOfChat,
            Position::AtDepth,
        ] {
            assert!(positions.contains(&expected), "缺少位置 {expected:?}");
        }

        // 空名字的条目也要能读进来（示例里就有）
        assert!(lore.data.entries.iter().any(|e| e.name.is_empty()));
        // 正则与大小写开关都读对了
        let regex_entry = lore
            .data
            .entries
            .iter()
            .find(|e| e.use_regex)
            .expect("示例里有正则条目");
        assert!(regex_entry.case_sensitive);
        assert_eq!(regex_entry.inject_depth, 4);
    }

    #[test]
    fn roundtrips_through_json() {
        let lore = Lorebook::from_json(SAMPLE).unwrap();
        let text = lore.to_json_pretty().unwrap();
        let again = Lorebook::from_json(&text).unwrap();
        assert_eq!(lore, again);
        // 字段名必须保持 kelivo 那套 camelCase / 大写枚举
        assert!(text.contains("\"injectDepth\""));
        assert!(text.contains("\"useRegex\""));
        assert!(text.contains("\"constantActive\""));
        assert!(text.contains("\"AFTER_SYSTEM_PROMPT\""));
    }

    #[test]
    fn constant_entries_always_fire() {
        let book = book(vec![entry("e1", Position::AfterSystemPrompt, true, &[])]);
        let plan = plan(&book, &[HistoryLine::new(Role::User, "随便说点什么")]);
        assert_eq!(plan.len(), 1);
        assert_eq!(plan[0].content, "内容-e1");
    }

    #[test]
    fn keyword_matching_respects_case_and_regex() {
        let mut sensitive = entry("e1", Position::TopOfChat, false, &["Dragon"]);
        sensitive.case_sensitive = true;
        assert!(sensitive.is_active(&[HistoryLine::new(Role::User, "a Dragon here")]));
        assert!(!sensitive.is_active(&[HistoryLine::new(Role::User, "a dragon here")]));

        let insensitive = entry("e2", Position::TopOfChat, false, &["Dragon"]);
        assert!(insensitive.is_active(&[HistoryLine::new(Role::User, "a dragon here")]));

        let mut regex = entry("e3", Position::TopOfChat, false, &["龙.*卷轴"]);
        regex.use_regex = true;
        assert!(regex.is_active(&[HistoryLine::new(Role::User, "古龙遗失的卷轴")]));
        assert!(!regex.is_active(&[HistoryLine::new(Role::User, "卷轴与龙")]));
    }

    #[test]
    fn broken_regex_does_not_take_down_the_book() {
        let mut bad = entry("e1", Position::AfterSystemPrompt, false, &["([unclosed"]);
        bad.use_regex = true;
        let book = book(vec![
            bad,
            entry("e2", Position::AfterSystemPrompt, true, &[]),
        ]);
        let plan = plan(&book, &[HistoryLine::new(Role::User, "([unclosed")]);
        assert_eq!(plan.len(), 1, "坏正则只该让它自己不生效");
        assert_eq!(plan[0].entry_id, "e2");
    }

    #[test]
    fn scan_depth_limits_the_window() {
        let mut entry = entry("e1", Position::TopOfChat, false, &["旧事"]);
        entry.scan_depth = 1;
        let history = [
            HistoryLine::new(Role::User, "很久以前的旧事"),
            HistoryLine::new(Role::Assistant, "最近这条完全无关"),
        ];
        assert!(!entry.is_active(&history), "只扫最后 1 条，扫不到旧事");

        entry.scan_depth = 2;
        assert!(entry.is_active(&history));
    }

    #[test]
    fn disabled_entries_and_disabled_book_never_inject() {
        let mut off = entry("e1", Position::TopOfChat, true, &[]);
        off.enabled = false;
        let mut book = book(vec![
            off,
            entry("e2", Position::TopOfChat, false, &["关键词"]),
        ]);
        let history = [HistoryLine::new(Role::User, "关键词")];
        assert_eq!(plan(&book, &history).len(), 1);

        book.enabled = false;
        assert!(plan(&book, &history).is_empty(), "整本关掉就不该有任何注入");
    }

    #[test]
    fn plan_order_is_positional_then_priority() {
        let book = book(vec![
            entry("bottom", Position::BottomOfChat, true, &[]),
            entry("before", Position::BeforeSystemPrompt, true, &[]),
            entry("top", Position::TopOfChat, true, &[]),
            entry("after", Position::AfterSystemPrompt, true, &[]),
        ]);
        let plan = plan(&book, &[]);
        let order: Vec<&str> = plan.iter().map(|i| i.entry_id.as_str()).collect();
        assert_eq!(order, vec!["before", "after", "top", "bottom"]);
    }

    #[test]
    fn at_depth_orders_deeper_first() {
        let mut shallow = entry("shallow", Position::AtDepth, true, &[]);
        shallow.inject_depth = 1;
        let mut deep = entry("deep", Position::AtDepth, true, &[]);
        deep.inject_depth = 6;
        let book = book(vec![shallow, deep]);

        let plan = plan(&book, &[]);
        let order: Vec<&str> = plan.iter().map(|i| i.entry_id.as_str()).collect();
        assert_eq!(order, vec!["deep", "shallow"], "更靠前的深度先注入");
        assert_eq!(plan[0].depth, Some(6));
    }

    #[test]
    fn priority_breaks_ties_within_a_position() {
        let mut second = entry("b", Position::AfterSystemPrompt, true, &[]);
        second.priority = 5;
        let mut first = entry("a", Position::AfterSystemPrompt, true, &[]);
        first.priority = 1;
        let plan = plan(&book(vec![second, first]), &[]);
        let order: Vec<&str> = plan.iter().map(|i| i.entry_id.as_str()).collect();
        assert_eq!(order, vec!["a", "b"]);
    }

    #[test]
    fn summary_counts_active_entries() {
        let book = book(vec![
            entry("const", Position::AfterSystemPrompt, true, &[]),
            entry("kw", Position::TopOfChat, false, &["龙"]),
            entry("miss", Position::TopOfChat, false, &["凤凰"]),
        ]);
        let summary = summarize(&book, &[HistoryLine::new(Role::User, "我见到一条龙")]);
        assert_eq!(summary.total, 3);
        assert_eq!(summary.active, 2);
        assert_eq!(summary.constant, 1);
        assert_eq!(summary.by_keyword, 1);
        assert!(summary.explain().contains("本次生效 2 条"));
    }

    #[test]
    fn matched_keyword_explains_why_it_fired() {
        let entry = entry("e1", Position::TopOfChat, false, &["凤凰", "龙"]);
        let history = [HistoryLine::new(Role::User, "我见到一条龙")];
        assert_eq!(entry.matched_keyword(&history).as_deref(), Some("龙"));
        assert!(entry
            .matched_keyword(&[HistoryLine::new(Role::User, "无关")])
            .is_none());
    }

    #[test]
    fn keyword_index_is_deduped_and_sorted() {
        let book = book(vec![
            entry("a", Position::TopOfChat, false, &["b", "a"]),
            entry("b", Position::TopOfChat, false, &["a"]),
        ]);
        assert_eq!(keyword_index(&book), vec!["a", "b"]);
    }
}
