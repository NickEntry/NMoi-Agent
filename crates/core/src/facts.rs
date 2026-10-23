//! 硬事实抽取（计划 §3.6）：压缩摘要必须"答得上来"的那些东西。
//!
//! 压缩是整套里唯一**会静默毁数据**的步骤：摘要写得好看但丢了路径、丢了错误码，
//! 事后再也补不回来（原文虽然在归档里，但没人会去翻）。所以提交之前拿事实清单比一遍。
//!
//! # 为什么分两级
//!
//! 抽取抽得越狠，误判越多；误判的代价不对称：
//!
//! - **漏抽** → 放行一份劣质摘要，悄悄丢信息；
//! - **过抽** → 摘要永远"不合格"，压缩卡死、只能走兜底（摘要难看）。
//!
//! 两个都坏，但"卡死"是立刻能看见的，而"丢信息"要等几天后才发作。所以：
//!
//! - [`Severity::Hard`]（缺失 → 拒绝提交）：路径、命令、错误码、`protected` 消息里的一切；
//! - [`Severity::Soft`]（缺失 → 只记录、只提示）：普通数字、未完成标记。
//!
//! # 三条防"过抽"的闸
//!
//! 1. **每条消息最多 3 条事实**（取最长的，长的更有辨识度）；
//! 2. **总数上限**（硬 48 / 软 64），超了就截断——宁可少查，不可卡死；
//! 3. **包含关系去重**：`runtime.rs` 被 `crates/core/src/runtime.rs` 盖住时丢掉短的。
//!
//! # 明确做不到的
//!
//! 中文里的"未完成项"没法用字面匹配验证。像"启动收口还没写"这种句子，抽不出可比的
//! 键（没有标识符），只能记一条 `Soft` 提示，不阻塞提交。要做语义级的检查得靠模型，
//! 而"让模型判断自己写得对不对"这件事不成立。

use std::sync::OnceLock;

use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::ids::MessageId;
use crate::message::{AtomicUnit, Message, Role};

/// 事实类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FactKind {
    /// 文件 / 目录路径。
    Path,
    /// 命令行工具名（`cargo` / `git` / ...）。
    Command,
    /// 错误码、常量名、协议词（`E0282` / `INVALID_ARGUMENT`）。
    Code,
    /// 数字（版本、行数、端口、token 数）。默认是软项。
    Number,
    /// 蛇形标识符（`check_coverage`）。
    Identifier,
    /// 未完成标记（`TODO` / `还没` / `未完成`）。
    Unfinished,
}

impl FactKind {
    pub fn as_str(self) -> &'static str {
        match self {
            FactKind::Path => "path",
            FactKind::Command => "command",
            FactKind::Code => "code",
            FactKind::Number => "number",
            FactKind::Identifier => "identifier",
            FactKind::Unfinished => "unfinished",
        }
    }
}

/// 严重度决定"缺失时会不会拦提交"。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Severity {
    Hard,
    Soft,
}

/// 一条从原文里抽出来的事实。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Fact {
    pub message: MessageId,
    /// 归一化之后的比较键（小写、去尾部标点、路径去掉 `./`）。
    pub key: String,
    pub kind: FactKind,
    pub severity: Severity,
}

impl Fact {
    /// 给人看的一行：`path crates/core/src/runtime.rs`。
    pub fn describe(&self) -> String {
        format!("{} {}", self.kind.as_str(), self.key)
    }
}

/// 还没有消息坐标的事实。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawFact {
    pub key: String,
    pub kind: FactKind,
    pub severity: Severity,
}

/// 每条消息最多抽几条（防过抽）。
pub const MAX_PER_MESSAGE: usize = 3;
/// 硬项总数上限。
pub const MAX_HARD: usize = 48;
/// 软项总数上限。
pub const MAX_SOFT: usize = 64;

fn path_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(
            r"(?x)
            (
              (?:\.{1,2}/|/)?[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+      # 带斜杠的路径
              |
              [A-Za-z0-9_.\-]+\.(?:rs|toml|md|json|ya?ml|py|sh|tsx?|kt|c|h|cpp|hpp|gradle|sql|txt)
            )
            (?::\d+(?::\d+)?)?                                          # 尾部的 :行:列
            ",
        )
        .expect("路径正则写得出来")
    })
}

fn code_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"\b(?:E\d{4}|[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|HTTP\s?\d{3})\b")
            .expect("错误码正则写得出来")
    })
}

fn number_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"\b\d{2,}\b").expect("数字正则写得出来"))
}

const COMMANDS: [&str; 18] = [
    "cargo",
    "rustc",
    "git",
    "gh",
    "npm",
    "pnpm",
    "python",
    "python3",
    "pip",
    "make",
    "docker",
    "podman",
    "apt",
    "systemctl",
    "curl",
    "bash",
    "ssh",
    "grep",
];

const UNFINISHED: [&str; 8] = [
    "TODO",
    "FIXME",
    "未完成",
    "还没",
    "未做",
    "没做",
    "待补",
    "下一步",
];

/// 归一化：小写、去尾部标点、路径去掉 `./` 与行号、反斜杠转正斜杠。
pub fn normalize(raw: &str) -> String {
    let mut key = raw.trim().to_string();
    // 路径可能带 `:12:3` 行号后缀
    if let Some(index) = key.rfind(':') {
        let tail = &key[index + 1..];
        if !tail.is_empty() && tail.chars().all(|ch| ch.is_ascii_digit()) {
            key.truncate(index);
            if let Some(index) = key.rfind(':') {
                let tail = &key[index + 1..];
                if !tail.is_empty() && tail.chars().all(|ch| ch.is_ascii_digit()) {
                    key.truncate(index);
                }
            }
        }
    }
    while key.ends_with('.') || key.ends_with(',') || key.ends_with(';') || key.ends_with('"') {
        key.pop();
    }
    let key = key.replace('\\', "/");
    let key = key.trim_start_matches("./").to_string();
    key.to_lowercase()
}

fn is_command_token(token: &str) -> bool {
    let lower = token.to_ascii_lowercase();
    COMMANDS.iter().any(|command| *command == lower)
}

fn is_identifier(token: &str) -> bool {
    token.len() >= 6
        && token.contains('_')
        && token
            .chars()
            .all(|ch| ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '_')
        && token.chars().any(|ch| ch.is_ascii_lowercase())
}

fn push(facts: &mut Vec<RawFact>, raw: &str, kind: FactKind, severity: Severity) {
    let key = normalize(raw);
    // 数字只要两位：`12` 是事实，`3` 不是（一行三个也算事实的话，抽不完）
    let min_chars = if kind == FactKind::Number { 2 } else { 3 };
    if key.chars().count() < min_chars {
        return;
    }
    if facts
        .iter()
        .any(|fact| fact.key == key && fact.kind == kind)
    {
        return;
    }
    facts.push(RawFact {
        key,
        kind,
        severity,
    });
}

/// 抽一条消息里的事实，并带上消息坐标。
pub fn extract(message: &Message) -> Vec<Fact> {
    let strict = message.protected || message.role == Role::User;
    extract_text(&message.content, strict)
        .into_iter()
        .map(|raw| Fact {
            message: message.id.clone(),
            key: raw.key,
            kind: raw.kind,
            severity: raw.severity,
        })
        .collect()
}

/// 一个文本里最有辨识度的那条**硬**事实。给"只看一眼"的场合用。
pub fn best_key(text: &str) -> Option<(String, FactKind)> {
    // 这里按"严格"抽：调用方要的是最有辨识度的那一条，不是最宽松的那一条
    extract_text(text, true)
        .into_iter()
        .find(|fact| fact.severity == Severity::Hard)
        .map(|fact| (fact.key, fact.kind))
}

/// 从纯文本抽事实（不带消息坐标）。排序与截断规则跟 [`extract`] 一样。
///
/// `strict` = 这条消息是 `protected` 或用户说的。用户说过的话（和系统标了必须保的）
/// 一字都不能丢；助手自己写的措辞可以概括——所以标识符在 `strict` 下才是硬项。
pub fn extract_text(text: &str, strict: bool) -> Vec<RawFact> {
    let mut facts: Vec<RawFact> = Vec::new();

    // 路径在两种消息里都是硬项：丢了路径，摘要就没法拿来接着干活
    for found in path_re().find_iter(text) {
        push(&mut facts, found.as_str(), FactKind::Path, Severity::Hard);
    }
    for found in code_re().find_iter(text) {
        push(&mut facts, found.as_str(), FactKind::Code, Severity::Hard);
    }
    for token in text.split(|ch: char| {
        ch.is_whitespace()
            || matches!(
                ch,
                ',' | ';' | ':' | '(' | ')' | '[' | ']' | '{' | '}' | '"' | '\'' | '`' | '|' | '、'
            )
    }) {
        if is_command_token(token) {
            push(&mut facts, token, FactKind::Command, Severity::Hard);
        } else if is_identifier(token) {
            push(
                &mut facts,
                token,
                FactKind::Identifier,
                if strict {
                    Severity::Hard
                } else {
                    Severity::Soft
                },
            );
        }
    }
    // 数字默认软：摘要里写"两项"还是"2 项"没人管，但 protected 消息里的数字算硬。
    for found in number_re().find_iter(text) {
        push(
            &mut facts,
            found.as_str(),
            FactKind::Number,
            if strict {
                Severity::Hard
            } else {
                Severity::Soft
            },
        );
    }
    for marker in UNFINISHED {
        if text.contains(marker) {
            facts.push(RawFact {
                key: marker.to_lowercase(),
                kind: FactKind::Unfinished,
                severity: Severity::Soft,
            });
            break;
        }
    }

    // 每条消息截断：先按严重度（硬在前），再按键长（长的更有辨识度）
    facts.sort_by(|left, right| {
        (left.severity == Severity::Soft)
            .cmp(&(right.severity == Severity::Soft))
            .then(right.key.chars().count().cmp(&left.key.chars().count()))
    });
    facts.truncate(MAX_PER_MESSAGE);
    facts
}

/// 扫一个窗口（线上单元），去重并限量。
pub fn scan(units: &[AtomicUnit]) -> Vec<Fact> {
    let mut facts: Vec<Fact> = Vec::new();
    for unit in units {
        for message in &unit.messages {
            // 助手回复里的路径常常是"我改了哪个文件"，比用户的转述更准，一并抽；
            // 但工具结果原文太长，抽出来的事实密度低，跳过。
            if message.role == Role::Tool {
                continue;
            }
            for fact in extract(message) {
                if facts
                    .iter()
                    .any(|existing| existing.key == fact.key && existing.kind == fact.kind)
                {
                    continue;
                }
                facts.push(fact);
            }
        }
    }
    // 包含关系去重：`runtime.rs` 被 `crates/core/src/runtime.rs` 盖住时丢掉短的
    let keys: Vec<(String, FactKind)> = facts.iter().map(|f| (f.key.clone(), f.kind)).collect();
    let mut kept: Vec<Fact> = Vec::new();
    for fact in facts {
        let covered = keys.iter().any(|(other, kind)| {
            *kind == fact.kind && *other != fact.key && other.contains(&fact.key)
        });
        if !covered {
            kept.push(fact);
        }
    }
    // 限量：硬的先留
    let mut hard: Vec<Fact> = kept
        .iter()
        .filter(|fact| fact.severity == Severity::Hard)
        .cloned()
        .collect();
    let mut soft: Vec<Fact> = kept
        .into_iter()
        .filter(|fact| fact.severity == Severity::Soft)
        .collect();
    hard.truncate(MAX_HARD);
    soft.truncate(MAX_SOFT);
    hard.extend(soft);
    hard
}

/// 校验结果：哪些缺失。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Verdict {
    /// 硬项缺失（会让提交被拒绝）。
    pub hard_missing: Vec<Fact>,
    /// 软项缺失（只记一笔）。
    pub soft_missing: Vec<Fact>,
}

impl Verdict {
    pub fn is_clean(&self) -> bool {
        self.hard_missing.is_empty()
    }

    /// 给模型看的一句话，说清缺什么。
    pub fn describe(&self) -> String {
        if self.hard_missing.is_empty() && self.soft_missing.is_empty() {
            return "没有缺失项".to_string();
        }
        let mut parts = Vec::new();
        if !self.hard_missing.is_empty() {
            let items: Vec<String> = self
                .hard_missing
                .iter()
                .take(8)
                .map(|fact| fact.describe())
                .collect();
            parts.push(format!("必须补：{}", items.join("、")));
        }
        if !self.soft_missing.is_empty() {
            let items: Vec<String> = self
                .soft_missing
                .iter()
                .take(6)
                .map(|fact| fact.key.clone())
                .collect();
            parts.push(format!("可补（不阻塞）：{}", items.join("、")));
        }
        parts.join("；")
    }
}

/// 拿草稿比一遍事实清单。
pub fn verify(facts: &[Fact], draft: &str) -> Verdict {
    let haystack = normalize(draft);
    let mut verdict = Verdict::default();
    for fact in facts {
        if haystack.contains(&fact.key) {
            continue;
        }
        match fact.severity {
            Severity::Hard => verdict.hard_missing.push(fact.clone()),
            Severity::Soft => verdict.soft_missing.push(fact.clone()),
        }
    }
    verdict
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::SessionId;
    use crate::source::SourceTag;

    fn message(text: &str) -> Message {
        let session = SessionId::new();
        Message::text(
            MessageId::new(session, 1),
            Role::Assistant,
            SourceTag::History,
            text,
            10,
            0,
            LogicalClock::new(1),
        )
    }

    fn keys(facts: &[Fact]) -> Vec<String> {
        facts.iter().map(|fact| fact.key.clone()).collect()
    }

    #[test]
    fn normalize_strips_line_suffix_and_leading_dot_slash() {
        assert_eq!(
            normalize("./crates/core/src/lib.rs:42:7"),
            "crates/core/src/lib.rs"
        );
        assert_eq!(normalize("Crates\\Core\\Lib.rs,"), "crates/core/lib.rs");
        assert_eq!(normalize("HTTP 400"), "http 400");
        assert_eq!(normalize("cargo"), "cargo");
    }

    #[test]
    fn extracts_paths_commands_codes() {
        let facts = extract(&message("改 crates/core/src/runtime.rs"));
        assert_eq!(keys(&facts), vec!["crates/core/src/runtime.rs"]);

        let facts = extract(&message("跑 cargo test"));
        assert!(
            keys(&facts).contains(&"cargo".to_string()),
            "{:?}",
            keys(&facts)
        );

        let facts = extract(&message("报 E0282 和 INVALID_ARGUMENT"));
        let keys = keys(&facts);
        assert!(keys.contains(&"e0282".to_string()), "{keys:?}");
        assert!(keys.contains(&"invalid_argument".to_string()), "{keys:?}");
        assert!(
            facts.iter().all(|fact| fact.severity == Severity::Hard),
            "路径 / 命令 / 错误码都该是硬项"
        );
    }

    #[test]
    fn one_message_is_capped_so_extraction_cannot_demand_everything() {
        // 一条消息里塞四件事：抽出来只留 3 条（长的优先），否则摘要永远凑不齐
        let facts = extract(&message(
            "改 crates/core/src/runtime.rs 之后跑 cargo test，报 E0282 和 INVALID_ARGUMENT",
        ));
        assert_eq!(facts.len(), MAX_PER_MESSAGE);
        assert!(facts.iter().all(|fact| fact.severity == Severity::Hard));
    }

    #[test]
    fn numbers_are_soft_unless_protected() {
        let soft = extract(&message("改了 12 个文件，跑了 3 次"));
        let numbers: Vec<&Fact> = soft
            .iter()
            .filter(|fact| fact.kind == FactKind::Number)
            .collect();
        assert!(!numbers.is_empty());
        assert!(numbers.iter().all(|f| f.severity == Severity::Soft));

        let hard = extract(&message("改了 12 个文件").protect());
        assert!(hard
            .iter()
            .filter(|f| f.kind == FactKind::Number)
            .all(|f| f.severity == Severity::Hard));
    }

    #[test]
    fn per_message_cap_keeps_the_hard_and_the_longest() {
        let text = "a.rs b.rs c.rs d.rs e.rs f.rs g.rs h.rs";
        let facts = extract(&message(text));
        assert_eq!(facts.len(), MAX_PER_MESSAGE);
    }

    #[test]
    fn unfinished_markers_are_soft() {
        let facts = extract(&message("启动收口还没写"));
        assert!(facts
            .iter()
            .any(|fact| fact.kind == FactKind::Unfinished && fact.severity == Severity::Soft));
        // 中文句子抽不出可比键，所以只有这一条软项
        assert!(facts.iter().all(|fact| fact.severity == Severity::Soft));
    }

    #[test]
    fn scan_dedupes_and_drops_shorter_paths() {
        let session = SessionId::new();
        let units: Vec<AtomicUnit> = ["改 crates/core/src/runtime.rs", "runtime.rs 里还有个函数"]
            .iter()
            .enumerate()
            .map(|(index, text)| {
                let seq = index as u64 + 1;
                AtomicUnit::turn(Message::text(
                    MessageId::new(session, seq),
                    Role::Assistant,
                    SourceTag::History,
                    *text,
                    10,
                    0,
                    LogicalClock::new(seq),
                ))
                .unwrap()
            })
            .collect();
        let facts = scan(&units);
        let keys = keys(&facts);
        assert!(keys.contains(&"crates/core/src/runtime.rs".to_string()));
        assert!(
            !keys.contains(&"runtime.rs".to_string()),
            "被更长的路径盖住的短路径要丢掉：{keys:?}"
        );
    }

    #[test]
    fn verify_reports_hard_and_soft_separately() {
        let facts = vec![
            Fact {
                message: MessageId::new(SessionId::new(), 1),
                key: "crates/core/src/runtime.rs".into(),
                kind: FactKind::Path,
                severity: Severity::Hard,
            },
            Fact {
                message: MessageId::new(SessionId::new(), 2),
                key: "42".into(),
                kind: FactKind::Number,
                severity: Severity::Soft,
            },
        ];
        let verdict = verify(&facts, "改了 CRATES/CORE/SRC/RUNTIME.RS");
        assert!(verdict.is_clean(), "大小写不该影响匹配");
        assert_eq!(verdict.soft_missing.len(), 1);
        assert!(verdict.describe().contains("可补"));

        let verdict = verify(&facts, "改了重试逻辑");
        assert!(!verdict.is_clean());
        assert_eq!(verdict.hard_missing.len(), 1);
        assert!(verdict.describe().contains("必须补"));
    }
}
