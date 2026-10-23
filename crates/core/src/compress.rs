//! 上下文压缩（计划 §3.4）：草稿式多步压缩 + 确定性执行器。
//!
//! 分工是硬的，不能含糊：
//!
//! - **模型只写草稿**。四件工具：`compress_draft_append` / `compress_draft_edit` /
//!   `compress_draft_read` / `compress_draft_done`。草稿在内存里，**不进上下文、不进归档、
//!   零缓存代价**——它要是进了上下文，每追加一次就要把草稿本身再算一遍全量输入。
//! - **提交由代码决定**。模型说"我写完了"只是举手，`commit` 才是那个把历史换掉的地方：
//!   覆盖率校验不过就拒绝提交、把缺失项回给模型补；过了才归档线上内容、画分界线。
//!
//! 三条硬规则：
//!
//! 1. 线上原文**不删**：只写归档 + 画一条 `kind = compression` 的线，删线即还原。
//! 2. 草稿操作全部**确定性**：`edit` 要求唯一匹配，不做正则、不做模糊替换。
//! 3. 模型不守协议（超轮次、草稿空、迟迟不 `done`）时走 [`CompressionJob::commit_fallback`]：
//!    确定性的指针摘要 + 画线，宁可摘要难看，不能把会话卡死在压缩上。
//!
//! # 摘要放在哪
//!
//! 计划原话是"把草稿作为一条新消息放到线下"。实现上摘要**不是消息**，它挂在分界线上：
//! 分界线带一个内容寻址的 `summary_ref`，组上下文时窗口 = `[摘要] + 线下方单元`。
//! 原因是 `seq` 是整数：切点 `cut` 与它下面第一条消息之间没有编号空隙，硬插摘要要么改
//! 编号分配器、要么重排已有消息，两条都比"挂在线上"风险大。见 `docs/decisions.md` D44。
//!
//! # 覆盖率校验（本轮只做宽松版）
//!
//! 完整版是 §3.6 的硬事实抽取（路径 / 命令 / 错误码 / 数字 / 未完成项）。本轮先做关键字级：
//! 对线上每条 `protected` 消息和每条用户消息，挑一个最有辨识度的 token（路径 > 错误码 >
//! 命令 > 蛇形标识符），要求草稿里出现过。抽不出关键字的（纯中文闲聊、纯短句）算"跳过"，
//! **不判不合格**——宽松的意义是宁可放过，不可卡死。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::archive::{truncate_chars, ArchiveEntry, ArchiveError, ArchiveStore, Origin};
use crate::clock::LogicalClock;
use crate::divider::{self, Divider, DividerError, DividerKind};
use crate::ids::{ArchiveRef, MessageId, SessionId};
use crate::message::{AtomicUnit, Message, Role};
use crate::runtime::ToolSpec;
use crate::source::SourceTag;
use crate::tokens::estimate_tokens;

pub mod runner;

/// 追加草稿。
pub const DRAFT_APPEND_TOOL: &str = "compress_draft_append";
/// 局部改写草稿（唯一匹配）。
pub const DRAFT_EDIT_TOOL: &str = "compress_draft_edit";
/// 读回草稿（按字符区间）。
pub const DRAFT_READ_TOOL: &str = "compress_draft_read";
/// 声明草稿完成，交给执行器校验。
pub const DRAFT_DONE_TOOL: &str = "compress_draft_done";

/// 与工具名一一对应的常量表（UI 与测试共用）。
pub const DRAFT_TOOLS: [&str; 4] = [
    DRAFT_APPEND_TOOL,
    DRAFT_EDIT_TOOL,
    DRAFT_READ_TOOL,
    DRAFT_DONE_TOOL,
];

// ---------------------------------------------------------------------------
// 上限
// ---------------------------------------------------------------------------

/// 草稿上限。默认值按"压缩角色 `max_tokens = 8192`"折算（约 32 KiB）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DraftLimits {
    /// 草稿总字节上限。
    pub max_bytes: usize,
    /// 单次追加的上限。
    pub max_bytes_per_append: usize,
    /// 操作次数上限（`done` 不算）。
    pub max_ops: u32,
    /// `read` 不给 `limit` 时返回多少字。
    pub read_chars: usize,
}

impl Default for DraftLimits {
    fn default() -> Self {
        Self {
            max_bytes: 64 * 1024,
            max_bytes_per_append: 8 * 1024,
            max_ops: 64,
            read_chars: 4_000,
        }
    }
}

/// 一次压缩任务的预算。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CompressionLimits {
    pub draft: DraftLimits,
    /// 覆盖率不合格时允许退回几次（每退一次，模型可以继续补）。
    pub max_rounds: u32,
}

impl Default for CompressionLimits {
    fn default() -> Self {
        Self {
            draft: DraftLimits::default(),
            max_rounds: 2,
        }
    }
}

// ---------------------------------------------------------------------------
// 草稿
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DraftError {
    #[error("文本为空")]
    EmptyText,
    #[error("单次追加 {got} 字节超过上限 {max} 字节，请分多次 append")]
    AppendTooLarge { got: usize, max: usize },
    #[error("草稿已满：{current} + {added} 字节超过上限 {max} 字节，请先 edit 压缩掉冗余")]
    DraftFull {
        current: usize,
        added: usize,
        max: usize,
    },
    #[error("操作次数已达上限 {max}，请直接调用 compress_draft_done")]
    TooManyOps { max: u32 },
    #[error("草稿已定稿，不能再改；等执行器校验，或等它把缺失项退回来")]
    AlreadyDone,
    #[error("草稿里找不到要替换的片段：{needle}")]
    FindMissing { needle: String },
    #[error("要替换的片段出现 {count} 处，只接受唯一匹配；请多带几句上下文：{needle}")]
    FindAmbiguous { needle: String, count: usize },
    #[error("读取区间越界：offset={offset} total={total}")]
    ReadRange { offset: usize, total: usize },
    #[error("未知的草稿工具：{name}")]
    UnknownTool { name: String },
    #[error("工具参数不合法：{detail}")]
    BadArguments { detail: String },
}

/// 错误信息里只带一小段 needle，别把整段草稿抄回模型。
fn needle_preview(needle: &str) -> String {
    truncate_chars(needle, 40)
}

/// 追加结果（也是回给模型的工具返回值）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AppendReport {
    pub added_bytes: usize,
    pub bytes: usize,
    pub chars: usize,
    pub ops: u32,
}

/// 草稿本体。**只存在于内存**，不落库、不进上下文。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Draft {
    text: String,
    ops: u32,
    limits: DraftLimits,
}

impl Draft {
    pub fn new(limits: DraftLimits) -> Self {
        Self {
            text: String::new(),
            ops: 0,
            limits,
        }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn len_bytes(&self) -> usize {
        self.text.len()
    }

    pub fn len_chars(&self) -> usize {
        self.text.chars().count()
    }

    pub fn ops(&self) -> u32 {
        self.ops
    }

    pub fn limits(&self) -> DraftLimits {
        self.limits
    }

    pub fn is_empty(&self) -> bool {
        self.text.trim().is_empty()
    }

    fn bump_op(&mut self) -> Result<(), DraftError> {
        if self.ops >= self.limits.max_ops {
            return Err(DraftError::TooManyOps {
                max: self.limits.max_ops,
            });
        }
        self.ops += 1;
        Ok(())
    }

    /// 追加。草稿非空且不以换行结尾时，自动补一个换行——否则两段会粘成一行，
    /// 模型事后 `edit` 也找不回边界。
    pub fn append(&mut self, text: &str) -> Result<AppendReport, DraftError> {
        if text.trim().is_empty() {
            return Err(DraftError::EmptyText);
        }
        if text.len() > self.limits.max_bytes_per_append {
            return Err(DraftError::AppendTooLarge {
                got: text.len(),
                max: self.limits.max_bytes_per_append,
            });
        }
        let separator = !self.text.is_empty() && !self.text.ends_with('\n');
        let added_bytes = text.len() + usize::from(separator);
        if self.text.len() + added_bytes > self.limits.max_bytes {
            return Err(DraftError::DraftFull {
                current: self.text.len(),
                added: added_bytes,
                max: self.limits.max_bytes,
            });
        }
        self.bump_op()?;
        if separator {
            self.text.push('\n');
        }
        self.text.push_str(text);
        Ok(AppendReport {
            added_bytes,
            bytes: self.len_bytes(),
            chars: self.len_chars(),
            ops: self.ops,
        })
    }

    /// 唯一匹配替换。出现 0 次或多次都报错，由模型自己补上下文重新指定。
    pub fn edit(&mut self, find: &str, replace: &str) -> Result<AppendReport, DraftError> {
        if find.is_empty() {
            return Err(DraftError::EmptyText);
        }
        let count = self.text.matches(find).count();
        if count == 0 {
            return Err(DraftError::FindMissing {
                needle: needle_preview(find),
            });
        }
        if count > 1 {
            return Err(DraftError::FindAmbiguous {
                needle: needle_preview(find),
                count,
            });
        }
        let next_len = self.text.len() - find.len() + replace.len();
        if next_len > self.limits.max_bytes {
            return Err(DraftError::DraftFull {
                current: self.text.len(),
                added: replace.len().saturating_sub(find.len()),
                max: self.limits.max_bytes,
            });
        }
        self.bump_op()?;
        self.text = self.text.replacen(find, replace, 1);
        Ok(AppendReport {
            added_bytes: replace.len(),
            bytes: self.len_bytes(),
            chars: self.len_chars(),
            ops: self.ops,
        })
    }

    /// 按字符区间读回。`offset` 是字符下标（不是字节），给中文留余地。
    pub fn read(&self, offset: usize, limit: usize) -> Result<ReadSlice, DraftError> {
        let total = self.len_chars();
        if offset > total {
            return Err(DraftError::ReadRange { offset, total });
        }
        let slice: String = self.text.chars().skip(offset).take(limit).collect();
        let end = offset + slice.chars().count();
        Ok(ReadSlice {
            text: slice,
            start: offset,
            end,
            total,
        })
    }
}

/// 草稿的一段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadSlice {
    pub text: String,
    pub start: usize,
    pub end: usize,
    pub total: usize,
}

// ---------------------------------------------------------------------------
// 工具调用解析
// ---------------------------------------------------------------------------

/// 一次草稿操作。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DraftCommand {
    Append { text: String },
    Edit { find: String, replace: String },
    Read { offset: usize, limit: usize },
    Done,
}

impl DraftCommand {
    pub fn tool_name(&self) -> &'static str {
        match self {
            DraftCommand::Append { .. } => DRAFT_APPEND_TOOL,
            DraftCommand::Edit { .. } => DRAFT_EDIT_TOOL,
            DraftCommand::Read { .. } => DRAFT_READ_TOOL,
            DraftCommand::Done => DRAFT_DONE_TOOL,
        }
    }
}

fn field(value: &Value, key: &str) -> Result<String, DraftError> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| DraftError::BadArguments {
            detail: format!("缺少字符串字段 `{key}`"),
        })
}

fn number_field(value: &Value, key: &str, default: usize) -> usize {
    value
        .get(key)
        .and_then(Value::as_u64)
        .map(|v| v as usize)
        .unwrap_or(default)
}

/// 把工具名 + 原始 JSON 参数解析成命令。
pub fn parse_command(
    name: &str,
    arguments: &str,
    limits: DraftLimits,
) -> Result<DraftCommand, DraftError> {
    if !DRAFT_TOOLS.contains(&name) {
        return Err(DraftError::UnknownTool {
            name: name.to_string(),
        });
    }
    let value: Value = if arguments.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(arguments).map_err(|err| DraftError::BadArguments {
            detail: err.to_string(),
        })?
    };
    match name {
        DRAFT_APPEND_TOOL => Ok(DraftCommand::Append {
            text: field(&value, "text")?,
        }),
        DRAFT_EDIT_TOOL => Ok(DraftCommand::Edit {
            find: field(&value, "find")?,
            replace: field(&value, "replace")?,
        }),
        DRAFT_READ_TOOL => Ok(DraftCommand::Read {
            offset: number_field(&value, "offset", 0),
            limit: number_field(&value, "limit", limits.read_chars),
        }),
        DRAFT_DONE_TOOL => Ok(DraftCommand::Done),
        other => Err(DraftError::UnknownTool {
            name: other.to_string(),
        }),
    }
}

/// 四件草稿工具的声明（直接塞进 `ChatRequest::tools`）。
pub fn tool_specs(limits: DraftLimits) -> Vec<ToolSpec> {
    vec![
        ToolSpec::new(
            DRAFT_APPEND_TOOL,
            json!({
                "type": "object",
                "properties": {
                    "text": {
                        "type": "string",
                        "description": "要追加到压缩草稿末尾的文本"
                    }
                },
                "required": ["text"]
            }),
        )
        .describe(format!(
            "往压缩草稿末尾追加一段。草稿不进对话上下文。单次上限 {} 字节，总上限 {} 字节。",
            limits.max_bytes_per_append, limits.max_bytes
        )),
        ToolSpec::new(
            DRAFT_EDIT_TOOL,
            json!({
                "type": "object",
                "properties": {
                    "find": {
                        "type": "string",
                        "description": "要被替换的原文片段，必须在草稿里只出现一次"
                    },
                    "replace": {
                        "type": "string",
                        "description": "替换成什么"
                    }
                },
                "required": ["find", "replace"]
            }),
        )
        .describe("局部改写草稿。只接受唯一匹配；写了 0 处或多处都会报错，不会猜。"),
        ToolSpec::new(
            DRAFT_READ_TOOL,
            json!({
                "type": "object",
                "properties": {
                    "offset": {
                        "type": "integer",
                        "description": "起始字符下标，默认 0"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "最多读多少字，默认 4000"
                    }
                }
            }),
        )
        .describe("读回草稿的一段。改之前先读，不要凭印象改。"),
        ToolSpec::new(
            DRAFT_DONE_TOOL,
            json!({
                "type": "object",
                "properties": {},
                "description": "声明压缩草稿已写完。之后由执行器做覆盖率校验并决定是否提交。"
            }),
        )
        .describe("声明草稿完成。草稿没有定稿之前不要调用它。"),
    ]
}

// ---------------------------------------------------------------------------
// 覆盖率校验（宽松版）
// ---------------------------------------------------------------------------

pub use crate::facts::{FactKind as RequireKind, Severity};

/// 一条"摘要必须能回答"的要求（就是一条硬事实，见 [`crate::facts`]）。
pub type Requirement = crate::facts::Fact;

/// 覆盖率结果。
///
/// 硬项（`required` / `missing`）决定能不能提交；软项（`soft` / `soft_missing`）
/// 只记一笔、只提示，不拦。为什么这样分见 [`crate::facts`] 的模块注释。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoverageReport {
    /// 从线上内容里抽出来的硬事实（去重后）。
    pub required: Vec<Requirement>,
    /// 草稿里没出现的硬事实——**非空就拒绝提交**。
    pub missing: Vec<Requirement>,
    /// 软事实（普通数字、未完成标记）。
    pub soft: Vec<Requirement>,
    /// 草稿里没出现的软事实（不拦提交）。
    pub soft_missing: Vec<Requirement>,
    /// 参与校验的消息条数。
    pub checked: usize,
    /// 一条事实都抽不出来的消息条数。
    pub skipped: usize,
}

impl CoverageReport {
    pub fn is_complete(&self) -> bool {
        self.missing.is_empty()
    }

    /// 给模型看的一句话。
    pub fn summary(&self) -> String {
        let base = if self.required.is_empty() {
            format!("没有硬事实要校验（跳过 {} 条）", self.skipped)
        } else {
            format!(
                "硬事实 {}/{} 项覆盖，缺 {} 项；跳过 {} 条",
                self.required.len() - self.missing.len(),
                self.required.len(),
                self.missing.len(),
                self.skipped
            )
        };
        if self.soft.is_empty() {
            return base;
        }
        format!(
            "{base}；软事实 {} 项（缺 {} 项，不阻塞）",
            self.soft.len(),
            self.soft_missing.len()
        )
    }
}

/// 从一条消息里挑最有辨识度的硬事实。抽不出来返回 `None`。
///
/// 真正的规则在 [`crate::facts`]；这里留着是为了让调用方不用同时认两个模块。
pub fn pick_key(text: &str) -> Option<(String, RequireKind)> {
    crate::facts::best_key(text)
}

/// 覆盖率校验：抽线上窗口的硬事实，拿草稿比一遍。
///
/// 软项（普通数字、未完成标记）缺失不拦提交——理由见 [`crate::facts`] 的模块注释。
pub fn check_coverage(above: &[AtomicUnit], draft: &str) -> CoverageReport {
    let facts = crate::facts::scan(above);
    let verdict = crate::facts::verify(&facts, draft);

    let mut checked = 0usize;
    let mut skipped = 0usize;
    for unit in above {
        for message in &unit.messages {
            if message.role == Role::Tool {
                continue;
            }
            checked += 1;
            if crate::facts::extract(message).is_empty() {
                skipped += 1;
            }
        }
    }

    let hard = facts
        .iter()
        .filter(|fact| fact.severity == Severity::Hard)
        .cloned()
        .collect();
    let soft = facts
        .iter()
        .filter(|fact| fact.severity == Severity::Soft)
        .cloned()
        .collect();

    CoverageReport {
        required: hard,
        missing: verdict.hard_missing,
        soft,
        soft_missing: verdict.soft_missing,
        checked,
        skipped,
    }
}

// ---------------------------------------------------------------------------
// 执行器
// ---------------------------------------------------------------------------

/// 任务状态机。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum JobState {
    /// 模型正在写草稿。
    Collecting,
    /// 收到 `done`，等执行器校验。
    Done,
    /// 覆盖率不合格，已退回，模型可以继续补。
    Rejected,
    /// 提交完成。
    Committed,
    /// 走了确定性兜底；或者退回次数已用尽、只剩兜底这条路。
    Forced,
}

/// 一次草稿操作的记录。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OpRecord {
    pub index: u32,
    pub tool: String,
    pub ok: bool,
    pub chars: usize,
    pub note: Option<String>,
}

/// 压缩全过程留痕。事后判断"这次压缩好不好"全靠它。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CompressionTrace {
    pub cut_after_seq: u64,
    pub units_above: usize,
    pub messages_above: usize,
    pub ops: Vec<OpRecord>,
    pub draft_bytes: usize,
    pub draft_chars: usize,
    pub draft_tokens: u32,
    pub coverage: CoverageReport,
    pub rounds: u32,
    pub forced: bool,
    pub started_at: i64,
    pub finished_at: i64,
}

/// 提交结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompressionOutcome {
    pub divider: Divider,
    /// 摘要正文（已经写进归档，`summary_ref` 指向它）。
    pub summary: String,
    pub summary_ref: ArchiveRef,
    /// 线上每条被归档消息的引用（原文一条没删）。
    pub archived: Vec<ArchiveRef>,
    /// 与 `archived` 一一对应的条目本身。
    ///
    /// 为什么留一份：平台侧要把"归档 + 分界线 + 摘要"写进**一个事务**（D45），
    /// 那就得拿到条目，不能只拿 ref 再回头查一遍。
    pub entries: Vec<ArchiveEntry>,
    pub summary_entry: ArchiveEntry,
    pub archived_tokens: u64,
    pub coverage: CoverageReport,
    pub trace: CompressionTrace,
    /// 是否是确定性兜底的产物（`true` 时摘要没有经过模型）。
    pub forced: bool,
}

impl CompressionOutcome {
    /// 组上下文用：把摘要装成一条注入消息，放在窗口最前面。
    ///
    /// 全文形态（F1）——摘要本身就是内容，不需要再指向归档；
    /// `summary_ref` 单独留在 outcome 上，回灌与审计都用它。
    pub fn summary_injection(&self, id: MessageId, clock: LogicalClock) -> Message {
        Message::text(
            id,
            Role::User,
            SourceTag::InstructionInjection,
            self.summary.clone(),
            estimate_tokens(&self.summary),
            self.divider.created_at,
            clock,
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CompressError {
    #[error("全部内容都在保留预算内，不需要压缩")]
    NothingToCompress,
    #[error("切点不合法：{0}")]
    Divider(#[from] DividerError),
    #[error("线上没有可压缩的消息")]
    EmptyWindow,
    #[error("会话不一致：切点属于 {expected}，传入的是 {got}")]
    SessionMismatch { expected: SessionId, got: SessionId },
    #[error("草稿还没定稿：先调用 {DRAFT_DONE_TOOL}")]
    NotDone,
    #[error("覆盖率不足（还可退回 {rounds_left} 次）：{summary}")]
    Coverage { summary: String, rounds_left: u32 },
    #[error("退回次数已用尽（{max} 次），只能走确定性兜底")]
    TooManyRounds { max: u32 },
    #[error("归档失败：{0}")]
    Archive(#[from] ArchiveError),
    #[error("模型调用失败：{0}")]
    Model(String),
    #[error("工具往返次数用尽（{max} 次），这一轮没压缩成")]
    TooManyExchanges { max: u32 },
    #[error("{0}")]
    Draft(#[from] DraftError),
}

impl CompressError {
    /// 喂回模型的文字：说清哪里不对、下一步该做什么。
    pub fn tool_text(&self) -> String {
        match self {
            CompressError::Coverage { summary, rounds_left } => format!(
                "拒绝提交：{summary}。把缺的项补进草稿再调用 {DRAFT_DONE_TOOL}（还可退回 {rounds_left} 次）。"
            ),
            CompressError::TooManyRounds { max } => {
                format!("退回次数已用尽（{max} 次），执行器将走确定性兜底。")
            }
            CompressError::NotDone => {
                format!("草稿还没定稿：先调用 {DRAFT_DONE_TOOL}，再由执行器校验。")
            }
            other => other.to_string(),
        }
    }
}

/// 回给模型的工具返回。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolReply {
    pub text: String,
    /// `true` 表示草稿定稿（`compress_draft_done`）。
    pub done: bool,
}

/// 一次压缩任务。持有**冻结快照**：切点与线上单元在 `start` 那一刻定死，
/// 之后会话怎么长都不影响这次校验。
#[derive(Debug, Clone)]
pub struct CompressionJob {
    session: SessionId,
    cut_after_seq: u64,
    above: Vec<AtomicUnit>,
    draft: Draft,
    state: JobState,
    rounds: u32,
    max_rounds: u32,
    started_at: i64,
    ops: Vec<OpRecord>,
}

impl CompressionJob {
    /// 开一个压缩任务：按预算挑切点、冻结线上快照。
    pub fn start(
        session: SessionId,
        units: &[AtomicUnit],
        keep_recent_tokens: u32,
        now: i64,
        limits: CompressionLimits,
    ) -> Result<Self, CompressError> {
        if let Some(first) = units.first() {
            let owner = first.first_message().id.session();
            if owner != session {
                return Err(CompressError::SessionMismatch {
                    expected: owner,
                    got: session,
                });
            }
        }
        let cut = divider::choose_cut_for_budget(units, keep_recent_tokens)
            .ok_or(CompressError::NothingToCompress)?;
        divider::validate_position(units, cut)?;
        let above: Vec<AtomicUnit> = divider::units_above(units, cut).to_vec();
        if above.is_empty() {
            return Err(CompressError::EmptyWindow);
        }
        Ok(Self {
            session,
            cut_after_seq: cut,
            above,
            draft: Draft::new(limits.draft),
            state: JobState::Collecting,
            rounds: 0,
            max_rounds: limits.max_rounds,
            started_at: now,
            ops: Vec::new(),
        })
    }

    pub fn state(&self) -> JobState {
        self.state
    }

    pub fn cut_after_seq(&self) -> u64 {
        self.cut_after_seq
    }

    pub fn draft(&self) -> &Draft {
        &self.draft
    }

    pub fn rounds(&self) -> u32 {
        self.rounds
    }

    pub fn max_rounds(&self) -> u32 {
        self.max_rounds
    }

    pub fn above(&self) -> &[AtomicUnit] {
        &self.above
    }

    pub fn tool_specs(&self) -> Vec<ToolSpec> {
        tool_specs(self.draft.limits())
    }

    fn record(&mut self, tool: &str, ok: bool, chars: usize, note: Option<String>) {
        self.ops.push(OpRecord {
            index: self.ops.len() as u32,
            tool: tool.to_string(),
            ok,
            chars,
            note,
        });
    }

    /// 处理一次草稿工具调用。失败也用 `Err` 返回，由调用方用 [`CompressError::tool_text`]
    /// 变成工具结果喂回模型——**不要因此结束本轮对话**。
    pub fn handle(&mut self, name: &str, arguments: &str) -> Result<ToolReply, CompressError> {
        if matches!(
            self.state,
            JobState::Committed | JobState::Forced | JobState::Done
        ) {
            self.record(name, false, 0, Some("already_done".into()));
            return Err(DraftError::AlreadyDone.into());
        }
        let command = match parse_command(name, arguments, self.draft.limits()) {
            Ok(command) => command,
            Err(err) => {
                self.record(name, false, 0, Some(err.to_string()));
                return Err(err.into());
            }
        };
        match command {
            DraftCommand::Append { text } => match self.draft.append(&text) {
                Ok(report) => {
                    self.record(name, true, report.chars, None);
                    Ok(ToolReply {
                        text: format!(
                            "已追加 {} 字节｜草稿 {} 字节 / {} 字｜操作 {}/{}",
                            report.added_bytes,
                            report.bytes,
                            report.chars,
                            report.ops,
                            self.draft.limits().max_ops
                        ),
                        done: false,
                    })
                }
                Err(err) => {
                    self.record(name, false, text.chars().count(), Some(err.to_string()));
                    Err(err.into())
                }
            },
            DraftCommand::Edit { find, replace } => match self.draft.edit(&find, &replace) {
                Ok(report) => {
                    self.record(name, true, report.chars, None);
                    Ok(ToolReply {
                        text: format!(
                            "替换 1 处｜草稿 {} 字节 / {} 字｜操作 {}/{}",
                            report.bytes,
                            report.chars,
                            report.ops,
                            self.draft.limits().max_ops
                        ),
                        done: false,
                    })
                }
                Err(err) => {
                    self.record(name, false, self.draft.len_chars(), Some(err.to_string()));
                    Err(err.into())
                }
            },
            DraftCommand::Read { offset, limit } => match self.draft.read(offset, limit) {
                Ok(slice) => {
                    self.record(name, true, slice.text.chars().count(), None);
                    Ok(ToolReply {
                        text: format!(
                            "[草稿 第 {}–{} 字，共 {} 字]\n{}",
                            slice.start, slice.end, slice.total, slice.text
                        ),
                        done: false,
                    })
                }
                Err(err) => {
                    self.record(name, false, 0, Some(err.to_string()));
                    Err(err.into())
                }
            },
            DraftCommand::Done => {
                if self.draft.is_empty() {
                    self.record(name, false, 0, Some("empty_draft".into()));
                    return Err(DraftError::EmptyText.into());
                }
                self.state = JobState::Done;
                self.record(name, true, self.draft.len_chars(), None);
                Ok(ToolReply {
                    text: format!(
                        "草稿已定稿：{} 字 / {} 字节。等待执行器做覆盖率校验。",
                        self.draft.len_chars(),
                        self.draft.len_bytes()
                    ),
                    done: true,
                })
            }
        }
    }

    /// 提交：先校验，不合格就退回去。
    pub fn commit<S: ArchiveStore>(
        &mut self,
        archive: &mut S,
        now: i64,
    ) -> Result<CompressionOutcome, CompressError> {
        if self.state != JobState::Done {
            return Err(CompressError::NotDone);
        }
        let coverage = check_coverage(&self.above, self.draft.text());
        if !coverage.is_complete() {
            self.rounds += 1;
            if self.rounds > self.max_rounds {
                // 退回次数用尽：这个任务只剩确定性兜底一条路，工具调用一律拒绝。
                self.state = JobState::Forced;
                return Err(CompressError::TooManyRounds {
                    max: self.max_rounds,
                });
            }
            self.state = JobState::Rejected;
            return Err(CompressError::Coverage {
                summary: coverage.summary(),
                rounds_left: self.max_rounds - self.rounds + 1,
            });
        }
        let summary = self.draft.text().to_string();
        self.finish(archive, now, summary, coverage, false)
    }

    /// 确定性兜底：模型不守协议（超轮次 / 草稿空 / 主动放弃）时走这里。
    ///
    /// 摘要由每条线上消息的归档指针拼成——难看，但准确、可回灌、零模型参与。
    pub fn commit_fallback<S: ArchiveStore>(
        &mut self,
        archive: &mut S,
        now: i64,
    ) -> Result<CompressionOutcome, CompressError> {
        let mut lines = Vec::new();
        for unit in &self.above {
            for message in &unit.messages {
                if message.content.trim().is_empty() {
                    continue;
                }
                let entry = archive.put(self.archive_entry(message, 1, now))?;
                lines.push(format!(
                    "[{} | {} | {} tok]",
                    entry, message.source, message.tokens
                ));
            }
        }
        // 上面已经写过一遍归档（幂等），`finish` 会再写一次同样的内容拿到同一批 ref。
        let summary = format!(
            "[压缩兜底：以下 {} 条消息已归档，原文可回灌]\n{}",
            lines.len(),
            lines.join("\n")
        );
        let coverage = check_coverage(&self.above, &summary);
        self.finish(archive, now, summary, coverage, true)
    }

    fn archive_entry(&self, message: &Message, turn: u32, now: i64) -> ArchiveEntry {
        ArchiveEntry::capture(
            &message.content,
            message.source,
            Origin::new(self.session, message.id.clone(), turn),
            message.tokens,
            now,
        )
        .0
        .with_protected(message.protected)
    }

    fn finish<S: ArchiveStore>(
        &mut self,
        archive: &mut S,
        now: i64,
        summary: String,
        coverage: CoverageReport,
        forced: bool,
    ) -> Result<CompressionOutcome, CompressError> {
        let mut archived = Vec::new();
        let mut entries: Vec<ArchiveEntry> = Vec::new();
        let mut archived_tokens: u64 = 0;
        let mut messages_above = 0usize;
        for (index, unit) in self.above.iter().enumerate() {
            for message in &unit.messages {
                messages_above += 1;
                if message.content.trim().is_empty() {
                    continue;
                }
                let entry = self.archive_entry(message, index as u32 + 1, now);
                archived_tokens += u64::from(message.tokens);
                archived.push(archive.put(entry.clone())?);
                entries.push(entry);
            }
        }

        let summary_tokens = estimate_tokens(&summary);
        let anchor = self
            .above
            .last()
            .map(|unit| unit.last_message().id.clone())
            .unwrap_or_else(|| MessageId::new(self.session, self.cut_after_seq));
        let summary_entry = ArchiveEntry::capture(
            &summary,
            SourceTag::InstructionInjection,
            Origin::new(self.session, anchor, 0),
            summary_tokens,
            now,
        )
        .0;
        let summary_ref = archive.put(summary_entry.clone())?;

        let divider = Divider::compression(
            self.session,
            self.cut_after_seq,
            format!(
                "{summary_ref} | 压缩 {} 单元 → {} 字",
                self.above.len(),
                summary.chars().count()
            ),
            now,
        );

        let trace = CompressionTrace {
            cut_after_seq: self.cut_after_seq,
            units_above: self.above.len(),
            messages_above,
            ops: self.ops.clone(),
            draft_bytes: self.draft.len_bytes(),
            draft_chars: self.draft.len_chars(),
            draft_tokens: estimate_tokens(self.draft.text()),
            coverage: coverage.clone(),
            rounds: self.rounds,
            forced,
            started_at: self.started_at,
            finished_at: now,
        };

        self.state = if forced {
            JobState::Forced
        } else {
            JobState::Committed
        };

        Ok(CompressionOutcome {
            divider,
            summary,
            summary_ref,
            archived,
            entries,
            summary_entry,
            archived_tokens,
            coverage,
            trace,
            forced,
        })
    }
}

/// 分界线是不是压缩画的（UI 用来决定图标与人话）。
pub fn is_compression_divider(divider: &Divider) -> bool {
    divider.kind == DividerKind::Compression
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::archive::MemoryArchive;
    use crate::ids::UnitId;

    const PATH_KEY: &str = "crates/core/src/runtime.rs";

    fn turn(
        session: SessionId,
        seq: u64,
        role: Role,
        source: SourceTag,
        text: &str,
        tokens: u32,
    ) -> AtomicUnit {
        let id = MessageId::new(session, seq);
        let message = Message::text(id, role, source, text, tokens, 0, LogicalClock::new(seq));
        AtomicUnit::turn(message).unwrap()
    }

    fn history() -> (SessionId, Vec<AtomicUnit>) {
        let session = SessionId::new();
        let units = vec![
            turn(
                session,
                1,
                Role::User,
                SourceTag::History,
                &format!("帮我改一下 {PATH_KEY} 里的重试逻辑"),
                200,
            ),
            turn(
                session,
                2,
                Role::Assistant,
                SourceTag::History,
                "看完了，改成了先校验头部再开 socket。",
                200,
            ),
            turn(
                session,
                3,
                Role::User,
                SourceTag::History,
                "跑 cargo test 看看",
                200,
            ),
            turn(
                session,
                4,
                Role::Assistant,
                SourceTag::History,
                "全绿。",
                200,
            ),
        ];
        (session, units)
    }

    #[test]
    fn draft_append_counts_bytes_and_chars() {
        let mut draft = Draft::new(DraftLimits::default());
        let report = draft.append("第一段").unwrap();
        assert_eq!(report.chars, 3);
        let report = draft.append("第二段").unwrap();
        // 分隔换行只占字节，不占字
        assert_eq!(report.chars, 7);
        assert_eq!(report.bytes, "第一段\n第二段".len());
    }

    #[test]
    fn draft_rejects_oversized_append_and_too_many_ops() {
        let limits = DraftLimits {
            max_bytes: 64,
            max_bytes_per_append: 8,
            max_ops: 2,
            read_chars: 10,
        };
        let mut draft = Draft::new(limits);
        assert!(matches!(
            draft.append("一二三四五六七八九"),
            Err(DraftError::AppendTooLarge { .. })
        ));
        draft.append("aaa").unwrap();
        draft.append("bbb").unwrap();
        assert_eq!(draft.append("ccc"), Err(DraftError::TooManyOps { max: 2 }));
    }

    #[test]
    fn draft_edit_requires_unique_match() {
        let mut draft = Draft::new(DraftLimits::default());
        draft.append("a b a").unwrap();
        assert!(matches!(
            draft.edit("a", "z"),
            Err(DraftError::FindAmbiguous { count: 2, .. })
        ));
        assert!(matches!(
            draft.edit("nope", "z"),
            Err(DraftError::FindMissing { .. })
        ));
        draft.edit("a b", "z b").unwrap();
        assert_eq!(draft.text(), "z b a");
    }

    #[test]
    fn draft_read_uses_char_offsets() {
        let mut draft = Draft::new(DraftLimits::default());
        draft.append("喵喵喵喵喵").unwrap();
        let slice = draft.read(1, 2).unwrap();
        assert_eq!(slice.text, "喵喵");
        assert_eq!((slice.start, slice.end, slice.total), (1, 3, 5));
        assert!(matches!(
            draft.read(9, 2),
            Err(DraftError::ReadRange { .. })
        ));
    }

    #[test]
    fn parse_command_covers_all_four_tools() {
        let limits = DraftLimits::default();
        assert_eq!(
            parse_command(DRAFT_APPEND_TOOL, r#"{"text":"hi"}"#, limits).unwrap(),
            DraftCommand::Append { text: "hi".into() }
        );
        assert_eq!(
            parse_command(DRAFT_EDIT_TOOL, r#"{"find":"a","replace":"b"}"#, limits).unwrap(),
            DraftCommand::Edit {
                find: "a".into(),
                replace: "b".into()
            }
        );
        assert_eq!(
            parse_command(DRAFT_READ_TOOL, "{}", limits).unwrap(),
            DraftCommand::Read {
                offset: 0,
                limit: limits.read_chars
            }
        );
        assert_eq!(
            parse_command(DRAFT_DONE_TOOL, "", limits).unwrap(),
            DraftCommand::Done
        );
        assert!(matches!(
            parse_command("nope", "{}", limits),
            Err(DraftError::UnknownTool { .. })
        ));
        assert!(matches!(
            parse_command(DRAFT_APPEND_TOOL, r#"{"text":1}"#, limits),
            Err(DraftError::BadArguments { .. })
        ));
    }

    #[test]
    fn tool_specs_declare_four_tools_with_schemas() {
        let specs = tool_specs(DraftLimits::default());
        assert_eq!(specs.len(), 4);
        for name in DRAFT_TOOLS {
            assert!(specs.iter().any(|spec| spec.name == name), "{name}");
        }
        let append = &specs[0];
        assert_eq!(append.parameters["required"][0], "text");
        assert!(append.description.is_some());
    }

    #[test]
    fn pick_key_prefers_paths_over_identifiers() {
        assert_eq!(
            pick_key("改 crates/core/src/runtime.rs 里的 retry_policy 吧"),
            Some((PATH_KEY.to_string(), RequireKind::Path))
        );
        assert_eq!(pick_key("继续吧"), None);
        assert_eq!(
            pick_key("先 cargo test"),
            Some(("cargo".into(), RequireKind::Command))
        );
        assert_eq!(
            pick_key("报 INVALID_ARGUMENT 了"),
            Some(("invalid_argument".into(), RequireKind::Code))
        );
    }

    #[test]
    fn coverage_flags_missing_path_but_skips_chitchat() {
        let (_, units) = history();
        let above = &units[..2];
        let report = check_coverage(above, "改好了重试逻辑。");
        assert!(!report.is_complete());
        assert_eq!(report.missing.len(), 1);
        assert_eq!(report.missing[0].key, PATH_KEY);

        let report = check_coverage(above, &format!("改了 {PATH_KEY} 的重试逻辑"));
        assert!(report.is_complete());
        assert_eq!(report.required.len(), 1, "同一关键字去重");
    }

    #[test]
    fn start_picks_cut_and_freezes_snapshot() {
        let (session, units) = history();
        let job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        assert_eq!(job.cut_after_seq(), 2);
        assert_eq!(job.above().len(), 2);
        assert_eq!(job.state(), JobState::Collecting);
    }

    #[test]
    fn start_reports_nothing_to_compress_when_everything_fits() {
        let (session, units) = history();
        let err = CompressionJob::start(session, &units, 100_000, 0, CompressionLimits::default())
            .unwrap_err();
        assert_eq!(err, CompressError::NothingToCompress);
    }

    #[test]
    fn end_to_end_commit_archives_original_and_draws_divider() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 10, CompressionLimits::default()).unwrap();
        job.handle(
            DRAFT_APPEND_TOOL,
            &format!(r#"{{"text":"任务：改 {PATH_KEY} 的重试逻辑，已完成。"}}"#),
        )
        .unwrap();
        let reply = job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
        assert!(reply.done);

        let mut archive = MemoryArchive::new();
        let outcome = job.commit(&mut archive, 20).unwrap();
        assert_eq!(job.state(), JobState::Committed);
        assert_eq!(outcome.divider.kind, DividerKind::Compression);
        assert_eq!(outcome.divider.after_seq, 2);
        assert!(!outcome.forced);
        assert!(outcome.coverage.is_complete());
        // 线上两条消息原文都在归档里，一条没删
        assert_eq!(outcome.archived.len(), 2);
        for rf in &outcome.archived {
            assert!(!archive.fetch(rf).unwrap().content.is_empty());
        }
        // 摘要也进了归档，且能按 ref 取回原文
        assert_eq!(
            archive.fetch(&outcome.summary_ref).unwrap().content,
            outcome.summary
        );
        assert_eq!(outcome.trace.draft_chars, outcome.summary.chars().count());
        assert_eq!(outcome.trace.units_above, 2);
        // 注入消息：来源是运行时指令，内容就是摘要
        let injection = outcome.summary_injection(MessageId::new(session, 5), LogicalClock::new(5));
        assert_eq!(injection.source, SourceTag::InstructionInjection);
        assert_eq!(injection.content, outcome.summary);
    }

    #[test]
    fn commit_requires_done_first() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        let mut archive = MemoryArchive::new();
        assert_eq!(job.commit(&mut archive, 0), Err(CompressError::NotDone));
    }

    #[test]
    fn rejection_returns_missing_items_and_allows_fix() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        job.handle(DRAFT_APPEND_TOOL, r#"{"text":"改好了重试逻辑。"}"#)
            .unwrap();
        job.handle(DRAFT_DONE_TOOL, "{}").unwrap();

        let mut archive = MemoryArchive::new();
        let err = job.commit(&mut archive, 0).unwrap_err();
        match &err {
            CompressError::Coverage {
                summary,
                rounds_left,
            } => {
                assert!(summary.contains('1'), "说了缺几项：{summary}");
                assert_eq!(*rounds_left, 2);
            }
            other => panic!("期望覆盖率不足，实际 {other:?}"),
        }
        assert!(err.tool_text().contains(DRAFT_DONE_TOOL));
        assert_eq!(job.state(), JobState::Rejected);
        assert!(archive.is_empty(), "拒绝提交时不该落任何归档");

        // 模型补上缺失项，重新定稿
        job.handle(
            DRAFT_EDIT_TOOL,
            &format!(r#"{{"find":"改好了","replace":"已按 {PATH_KEY} 改好了"}}"#),
        )
        .unwrap();
        job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
        let outcome = job.commit(&mut archive, 5).unwrap();
        assert!(outcome.coverage.is_complete());
        assert_eq!(outcome.trace.rounds, 1);
    }

    #[test]
    fn too_many_rounds_ends_collection_and_allows_fallback() {
        let (session, units) = history();
        let limits = CompressionLimits {
            max_rounds: 1,
            ..CompressionLimits::default()
        };
        let mut job = CompressionJob::start(session, &units, 400, 0, limits).unwrap();
        let mut archive = MemoryArchive::new();

        job.handle(DRAFT_APPEND_TOOL, r#"{"text":"一句话。"}"#)
            .unwrap();
        job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
        assert!(matches!(
            job.commit(&mut archive, 0),
            Err(CompressError::Coverage { rounds_left: 1, .. })
        ));

        job.handle(DRAFT_APPEND_TOOL, r#"{"text":"一句话。"}"#)
            .unwrap();
        job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
        assert_eq!(
            job.commit(&mut archive, 0),
            Err(CompressError::TooManyRounds { max: 1 })
        );
        assert_eq!(job.state(), JobState::Forced);

        // 次数用尽后不再收草稿操作
        assert!(matches!(
            job.handle(DRAFT_APPEND_TOOL, r#"{"text":"再来。"}"#),
            Err(CompressError::Draft(DraftError::AlreadyDone))
        ));

        // 兜底照样能提交
        let outcome = job.commit_fallback(&mut archive, 1).unwrap();
        assert!(outcome.forced);
        assert_eq!(job.state(), JobState::Forced);
    }

    #[test]
    fn fallback_builds_pointer_summary_without_model() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        let mut archive = MemoryArchive::new();
        let outcome = job.commit_fallback(&mut archive, 7).unwrap();
        assert!(outcome.forced);
        assert_eq!(job.state(), JobState::Forced);
        assert_eq!(outcome.divider.kind, DividerKind::Compression);
        assert!(outcome.summary.contains("压缩兜底"));
        assert!(outcome.summary.contains(&outcome.archived[0].to_string()));
        assert!(outcome.trace.forced);
        // 兜底也要归档原文
        assert_eq!(archive.stat().unwrap().entries, 3, "两条消息 + 摘要");
    }

    #[test]
    fn tools_are_refused_after_commit() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        let mut archive = MemoryArchive::new();
        job.commit_fallback(&mut archive, 0).unwrap();
        assert!(matches!(
            job.handle(DRAFT_APPEND_TOOL, r#"{"text":"晚了"}"#),
            Err(CompressError::Draft(DraftError::AlreadyDone))
        ));
    }

    #[test]
    fn empty_draft_cannot_be_declared_done() {
        let (session, units) = history();
        let mut job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        assert!(matches!(
            job.handle(DRAFT_DONE_TOOL, "{}"),
            Err(CompressError::Draft(DraftError::EmptyText))
        ));
        assert_eq!(job.state(), JobState::Collecting);
    }

    #[test]
    fn session_mismatch_is_reported() {
        let (_, units) = history();
        let err = CompressionJob::start(
            SessionId::new(),
            &units,
            400,
            0,
            CompressionLimits::default(),
        )
        .unwrap_err();
        assert!(matches!(err, CompressError::SessionMismatch { .. }));
    }

    #[test]
    fn unit_id_of_frozen_snapshot_is_stable() {
        // 冻结快照不随外部改动变化：改外面的列表，job 里的单元数不变。
        let (session, mut units) = history();
        let job =
            CompressionJob::start(session, &units, 400, 0, CompressionLimits::default()).unwrap();
        let before = job.above().len();
        units.truncate(1);
        assert_eq!(job.above().len(), before);
        assert_eq!(job.above()[0].id, UnitId::new(session, 1));
    }
}
