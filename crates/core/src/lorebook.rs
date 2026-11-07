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
    /// 副标题（Kelivo 那边叫 `note`，两个名字都收）。
    #[serde(default, alias = "note")]
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

/// 导入世界书时出的事（D278）：**点名到条**，不猜。
///
/// 为什么不用 `serde_json::Error`：它只说"第 12 行第 30 列"，用户手上是一份
/// 几百条的 JSON，那一行对他没有意义。这里说"第几条、哪个字段、填的是什么、能填什么"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportError {
    /// 哪一册（`第 2 册（主 AGENT）`）；空 = 单册文件或整个文件的问题。
    pub book: String,
    /// 第几条（1 起；0 = 整个文件的问题）。
    pub index: usize,
    pub entry: String,
    pub field: &'static str,
    pub value: String,
    pub message: String,
}

impl std::fmt::Display for ImportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if !self.book.is_empty() {
            write!(f, "{} · ", self.book)?;
        }
        if self.index == 0 {
            write!(f, "{}", self.message)
        } else {
            write!(
                f,
                "第 {} 条（{}）的 {} 是「{}」：{}",
                self.index, self.entry, self.field, self.value, self.message
            )
        }
    }
}

impl std::error::Error for ImportError {}

impl Lorebook {
    /// 导入一份世界书（D278）：**大小写不敏感**，认不出的值点名报错。
    ///
    /// 为什么要这一层：kelivo 导出的 `position` / `role` 是 `BEFORE_SYSTEM_PROMPT` 这种
    /// 大写蛇形，RikkaHub 是 `before_system_prompt` 小写。直接 `serde_json::from_str`
    /// 只认前者，后者会报一句"unknown variant"，用户看不出是哪一条。
    ///
    /// 只改这两个字段的**写法**，不猜语义：认不出的值一律报错，绝不默默落到默认值
    /// （`AFTER_SYSTEM_PROMPT` 是最常见的默认，猜错了等于把设定塞进错的地方）。
    pub fn import(text: &str) -> Result<Self, ImportError> {
        let mut root: serde_json::Value =
            serde_json::from_str(text).map_err(|error| ImportError {
                book: String::new(),
                index: 0,
                entry: String::new(),
                field: "文件",
                value: String::new(),
                message: format!("不是合法的 JSON：{error}"),
            })?;

        // 形状是 `{"version":1,"type":"lorebook","data":{"entries":[…]}}`。
        if let Some(entries) = root
            .get_mut("data")
            .and_then(|data| data.get_mut("entries"))
            .and_then(|value| value.as_array_mut())
        {
            normalize_entries(entries, "")?;
        }

        serde_json::from_value(root).map_err(|error| ImportError {
            book: String::new(),
            index: 0,
            entry: String::new(),
            field: "文件",
            value: String::new(),
            message: format!("结构不对：{error}"),
        })
    }
}

/// 把一份 `entries` 里的 `position` / `role` 统一成大写蛇形；认不出的点名报错。
///
/// `book` 是"哪一册"的标签（单册文件传空串）：多册之后同一个错误得说清是哪一册的，
/// 否则用户手上三册、几十条，只看到"第 3 条的 position 认不出"，还得自己翻。
fn normalize_entries(entries: &mut [serde_json::Value], book: &str) -> Result<(), ImportError> {
    for (index, entry) in entries.iter_mut().enumerate() {
        let name = entry
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();
        for (field, allowed) in [("position", POSITION_NAMES), ("role", ROLE_NAMES)] {
            let Some(raw) = entry.get(field).and_then(|v| v.as_str()) else {
                continue;
            };
            let upper = raw.trim().to_ascii_uppercase();
            if !allowed.contains(&upper.as_str()) {
                return Err(ImportError {
                    book: book.to_string(),
                    index: index + 1,
                    entry: name,
                    field,
                    value: raw.to_string(),
                    message: format!("认不出；只收 {}", allowed.join(" / ")),
                });
            }
            entry[field] = serde_json::Value::String(upper);
        }
    }
    Ok(())
}

const POSITION_NAMES: &[&str] = &[
    "BEFORE_SYSTEM_PROMPT",
    "AFTER_SYSTEM_PROMPT",
    "TOP_OF_CHAT",
    "AT_DEPTH",
    "BOTTOM_OF_CHAT",
];

const ROLE_NAMES: &[&str] = &["SYSTEM", "USER", "ASSISTANT"];

/// 多册世界书（D281 第 1 批）：`{"version":2,"type":"lorebooks","books":[…]}`。
///
/// 为什么要多册：Kelivo 那边一册 = 一组设定（主 AGENT / 子 AGENT / 界面），
/// 界面上是一行一册、点开是条目。单册装不下这个形状，换一组设定就得整份重导。
///
/// 老的单册文件（`{"type":"lorebook","data":{…}}`）读进来当**一册**，不丢数据——
/// 上一版把整份 JSON 存在 `files/lorebook.json` 里，用户手上可能就那一份。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Library {
    #[serde(default = "default_library_version")]
    pub version: u32,
    /// 固定为 `lorebooks`（单册是 `lorebook`）。
    #[serde(default = "default_library_kind", rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub books: Vec<LorebookData>,
}

fn default_library_version() -> u32 {
    2
}

fn default_library_kind() -> String {
    "lorebooks".to_string()
}

impl Library {
    /// 读一份文件：有 `books` 就当多册，没有就按单册读进来当一册。
    ///
    /// 判据是**形状**，不是 `type` 字段——用户手改过 `type` 的文件也该读得出来。
    pub fn import(text: &str) -> Result<Self, ImportError> {
        let mut root: serde_json::Value =
            serde_json::from_str(text).map_err(|error| ImportError {
                book: String::new(),
                index: 0,
                entry: String::new(),
                field: "文件",
                value: String::new(),
                message: format!("不是合法的 JSON：{error}"),
            })?;

        let Some(books) = root.get_mut("books").and_then(|value| value.as_array_mut()) else {
            return Ok(Self::from_one(Lorebook::import(text)?.data));
        };

        for (index, book) in books.iter_mut().enumerate() {
            let name = book
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string();
            let label = if name.trim().is_empty() {
                format!("第 {} 册", index + 1)
            } else {
                format!("第 {} 册（{}）", index + 1, name)
            };
            if let Some(entries) = book
                .get_mut("entries")
                .and_then(|value| value.as_array_mut())
            {
                normalize_entries(entries, &label)?;
            }
        }

        serde_json::from_value(root).map_err(|error| ImportError {
            book: String::new(),
            index: 0,
            entry: String::new(),
            field: "文件",
            value: String::new(),
            message: format!("结构不对：{error}"),
        })
    }

    /// 单册包成一份一册的库；名字与 id 空着就补一个（界面上要有个能点的行）。
    pub fn from_one(mut data: LorebookData) -> Self {
        if data.name.trim().is_empty() {
            data.name = "导入的世界书".to_string();
        }
        if data.id.trim().is_empty() {
            data.id = "b1".to_string();
        }
        Self {
            version: default_library_version(),
            kind: default_library_kind(),
            books: vec![data],
        }
    }

    /// 合并**所有启用册**里的条目，交给 `plan`。
    ///
    /// 册级 `enabled=false` = 整册不参与（条目自己的开关仍由 `plan` 判）。
    /// 这一层只做合并，不改匹配行为。
    pub fn merged(&self) -> LorebookData {
        let entries = self
            .books
            .iter()
            .filter(|book| book.enabled)
            .flat_map(|book| book.entries.iter().cloned())
            .collect();
        LorebookData {
            id: String::new(),
            name: String::new(),
            description: None,
            enabled: true,
            entries,
        }
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

#[cfg(test)]
mod import_tests {
    use super::*;

    fn book(position: &str, role: &str) -> String {
        format!(
            r#"{{"version":1,"type":"lorebook","data":{{"enabled":true,"entries":[{{"id":"e1","name":"猫娘设定","position":"{position}","role":"{role}","content":"她是猫"}}]}}}}"#
        )
    }

    /// 两种写法都收：kelivo 是大写蛇形，RikkaHub 是小写。
    #[test]
    fn both_spellings_import() {
        for (position, role) in [
            ("AFTER_SYSTEM_PROMPT", "SYSTEM"),
            ("after_system_prompt", "system"),
        ] {
            let book = Lorebook::import(&book(position, role)).expect("两种写法都该过");
            assert_eq!(book.data.entries.len(), 1);
            assert_eq!(book.data.entries[0].position, Position::AfterSystemPrompt);
            assert_eq!(book.data.entries[0].role, InjectionRole::System);
        }
    }

    /// 认不出的值**点名报错**，不猜默认值。
    #[test]
    fn an_unknown_position_names_the_entry() {
        let error = Lorebook::import(&book("BEFORE_CHAR", "USER")).unwrap_err();
        assert_eq!(error.index, 1);
        assert_eq!(error.field, "position");
        let text = error.to_string();
        assert!(text.contains("猫娘设定"), "{text}");
        assert!(text.contains("BEFORE_CHAR"), "{text}");
        assert!(
            text.contains("AFTER_SYSTEM_PROMPT"),
            "要把能填的列出来：{text}"
        );
    }

    #[test]
    fn a_broken_file_says_so_without_an_entry_number() {
        let error = Lorebook::import("{oops").unwrap_err();
        assert_eq!(error.index, 0);
        assert!(error.to_string().contains("不是合法的 JSON"));
    }
}

#[cfg(test)]
mod library_tests {
    use super::*;

    fn two_books(second_enabled: bool, second_position: &str) -> String {
        format!(
            r#"{{"version":2,"type":"lorebooks","books":[
                {{"id":"b1","name":"主 AGENT","note":"槽位 200 起","enabled":true,"entries":[
                    {{"id":"e1","name":"猫娘","position":"AFTER_SYSTEM_PROMPT","content":"她是猫"}}]}},
                {{"id":"b2","name":"界面","enabled":{second_enabled},"entries":[
                    {{"id":"e2","name":"布局","position":"{second_position}","content":"左边是抽屉"}}]}}]}}"#
        )
    }

    /// 老的单册文件（上一版存的就是这个形状）读进来当一册，条目一条不少。
    #[test]
    fn a_single_book_file_reads_as_one_book() {
        let text = r#"{"version":1,"type":"lorebook","data":{"name":"主 AGENT","enabled":true,
            "entries":[
                {"id":"e1","name":"猫娘","position":"AFTER_SYSTEM_PROMPT","content":"她是猫"},
                {"id":"e2","name":"酒馆","position":"top_of_chat","content":"酒馆在北街"}]}}"#;
        let library = Library::import(text).expect("单册该读得出来");
        assert_eq!(library.books.len(), 1);
        assert_eq!(library.books[0].name, "主 AGENT");
        assert_eq!(library.books[0].entries.len(), 2);
        assert_eq!(
            library.books[0].entries[1].position,
            Position::TopOfChat,
            "小写 position 也要收"
        );
    }

    /// 册级停用 = 整册不参与；条目自己的开关仍由 `plan` 判。
    #[test]
    fn a_disabled_book_stays_out_of_the_merge() {
        let on = Library::import(&two_books(true, "TOP_OF_CHAT")).expect("two books");
        assert_eq!(on.merged().entries.len(), 2);

        let off = Library::import(&two_books(false, "TOP_OF_CHAT")).expect("two books");
        let merged = off.merged();
        assert_eq!(merged.entries.len(), 1);
        assert_eq!(merged.entries[0].id, "e1");
    }

    /// 报错要点名**哪一册、第几条**：三册几十条的时候，"第 3 条"没有意义。
    #[test]
    fn an_unknown_position_names_the_book_and_the_entry() {
        let error = Library::import(&two_books(true, "BEFORE_CHAR")).unwrap_err();
        assert_eq!(error.index, 1, "册内的第几条");
        assert_eq!(error.field, "position");
        let text = error.to_string();
        assert!(text.contains("第 2 册（界面）"), "{text}");
        assert!(text.contains("布局"), "{text}");
        assert!(text.contains("BEFORE_CHAR"), "{text}");
    }

    /// 导出要用这条路：写出去再读回来，册数、名字、条目、位置都对得上。
    #[test]
    fn a_library_round_trips_through_json() {
        let library = Library::import(&two_books(true, "AT_DEPTH")).expect("two books");
        let text = library.to_json_pretty().expect("serialize");
        let again = Library::import(&text).expect("re-import");
        assert_eq!(again, library);
    }

    /// `note` 与 `description` 是两个名字一件事（Kelivo 的副标题、我们的 description）。
    #[test]
    fn note_is_the_same_field_as_description() {
        let library = Library::import(&two_books(true, "TOP_OF_CHAT")).expect("two books");
        assert_eq!(library.books[0].description.as_deref(), Some("槽位 200 起"));
    }

    /// 单册包成一册时补名字与 id：界面上要有能点的行。
    #[test]
    fn a_nameless_single_book_gets_a_name() {
        let text = r#"{"type":"lorebook","data":{"entries":[]}}"#;
        let library = Library::import(text).expect("empty book");
        assert_eq!(library.books[0].name, "导入的世界书");
        assert_eq!(library.books[0].id, "b1");
    }
}
