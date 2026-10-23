//! 归档层（设计文档 §2.4、§5.4–§5.6）。
//!
//! 三条硬规则：
//! 1. 默认永远存，没有"是否存储"的开关；
//! 2. 原文不删，只控制索引成本；
//! 3. 回灌是确定性的——ref 存在则返回完整原文，不存在则明确报错，不做相似度匹配。

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::ids::{ArchiveRef, MessageId, SessionId};
use crate::redact::{redact, Redaction};
use crate::source::SourceTag;

/// 归档条目的来源坐标。回滚到旧会话后，靠它判断 ref 属于哪一次上下文。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Origin {
    pub session: SessionId,
    pub message: MessageId,
    pub turn: u32,
}

impl Origin {
    pub fn new(session: SessionId, message: MessageId, turn: u32) -> Self {
        Self {
            session,
            message,
            turn,
        }
    }

    /// 指针里的位置部分：`s:0192a3f1 m:12`。
    pub fn short(&self) -> String {
        format!("s:{} m:{}", self.session.short(), self.message.seq())
    }
}

/// 归档条目。原文逐字节保存（脱敏后的原文，见 §5.6）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArchiveEntry {
    pub rf: ArchiveRef,
    /// 脱敏后的原文。`fetch` 返回的就是它。
    pub content: String,
    pub tokens: u32,
    pub source: SourceTag,
    pub origin: Origin,
    /// 一行描述（索引职责，≤ 60 字）。
    pub descriptor: String,
    pub protected: bool,
    pub created_at: i64,
    pub content_hash: String,
    pub note: Option<String>,
}

impl ArchiveEntry {
    /// 唯一推荐入口：写入时刻脱敏（§5.6），再算 ref 与哈希。
    pub fn capture(
        raw_content: &str,
        source: SourceTag,
        origin: Origin,
        tokens: u32,
        created_at: i64,
    ) -> (Self, Redaction) {
        let redaction = redact(raw_content);
        let entry = Self::new_redacted(redaction.text.clone(), source, origin, tokens, created_at);
        (entry, redaction)
    }

    /// 内容已经是脱敏后的文本时使用（重新导入、测试夹具）。
    pub fn new_redacted(
        content: String,
        source: SourceTag,
        origin: Origin,
        tokens: u32,
        created_at: i64,
    ) -> Self {
        let rf = ArchiveRef::from_content(&content);
        let content_hash = sha256_hex(&content);
        let descriptor = build_descriptor(source, &origin, &content, DEFAULT_DESCRIPTOR_MAX);
        Self {
            rf,
            content,
            tokens,
            source,
            origin,
            descriptor,
            protected: false,
            created_at,
            content_hash,
            note: None,
        }
    }

    pub fn with_protected(mut self, protected: bool) -> Self {
        self.protected = protected;
        self
    }

    pub fn with_note(mut self, note: impl Into<String>) -> Self {
        self.note = Some(note.into());
        self
    }

    /// 指针行（F4）：来源 + 位置 + token 数 + 一句话内容。
    pub fn pointer_line(&self) -> String {
        format!(
            "[archived {} | {} tok | {} | {} | {}]",
            self.rf,
            self.tokens,
            self.source,
            self.origin.short(),
            self.descriptor
        )
    }

    /// 自校验：哈希是否仍匹配内容。
    pub fn verify_hash(&self) -> bool {
        self.content_hash == sha256_hex(&self.content)
    }
}

pub const DEFAULT_DESCRIPTOR_MAX: usize = 60;

fn sha256_hex(content: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(content.as_bytes());
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// 生成一行描述：`来源 | 位置 | 一句话`，总长不超过 `max_chars` 个字符。
///
/// 按字符截断而不是按字节，中文不会被切坏。
pub fn build_descriptor(
    source: SourceTag,
    origin: &Origin,
    content: &str,
    max_chars: usize,
) -> String {
    let prefix = format!("{} | {}", source, origin.short());
    let budget = max_chars.saturating_sub(prefix.chars().count() + 3);
    let summary = one_line(content, budget);
    if summary.is_empty() {
        format!("{prefix} | (空)")
    } else {
        format!("{prefix} | {summary}")
    }
}

/// 取第一段有内容的行，压缩空白，按字符截断。
pub fn one_line(content: &str, max_chars: usize) -> String {
    let line = content
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or_default();
    let collapsed = line.split_whitespace().collect::<Vec<_>>().join(" ");
    truncate_chars(&collapsed, max_chars)
}

/// 按字符数截断，超出部分用 `…` 表示。
pub fn truncate_chars(text: &str, max_chars: usize) -> String {
    if max_chars == 0 {
        return String::new();
    }
    let mut out = String::new();
    for (count, ch) in text.chars().enumerate() {
        if count >= max_chars {
            // 保留 max_chars - 1 个字符再加省略号，总宽不超过 max_chars。
            let keep: String = text.chars().take(max_chars.saturating_sub(1)).collect();
            out = keep;
            out.push('…');
            return out;
        }
        out.push(ch);
    }
    out
}

/// 归档统计。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArchiveStat {
    pub entries: usize,
    pub total_tokens: u64,
    pub largest_ref: Option<ArchiveRef>,
    pub largest_tokens: u32,
}

/// 归档存储接口。core 只定义，SQLite 实现在 storage crate。
pub trait ArchiveStore {
    /// 幂等：同一 ref 重复写入返回同一引用，不覆盖已有条目。
    fn put(&mut self, entry: ArchiveEntry) -> Result<ArchiveRef, ArchiveError>;

    /// 确定性取回：不存在就报错，绝不返回"相似片段"。
    fn fetch(&self, reference: &ArchiveRef) -> Result<ArchiveEntry, ArchiveError>;

    fn search(&self, query: &str, limit: usize) -> Result<Vec<ArchiveEntry>, ArchiveError>;

    fn stat(&self) -> Result<ArchiveStat, ArchiveError>;
}

/// 内存实现：给 core 的单测与"压缩会话"的临时快照用。
#[derive(Debug, Default, Clone)]
pub struct MemoryArchive {
    entries: BTreeMap<ArchiveRef, ArchiveEntry>,
}

impl MemoryArchive {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

impl ArchiveStore for MemoryArchive {
    fn put(&mut self, entry: ArchiveEntry) -> Result<ArchiveRef, ArchiveError> {
        let rf = entry.rf.clone();
        self.entries.entry(rf.clone()).or_insert(entry);
        Ok(rf)
    }

    fn fetch(&self, reference: &ArchiveRef) -> Result<ArchiveEntry, ArchiveError> {
        self.entries
            .get(reference)
            .cloned()
            .ok_or_else(|| ArchiveError::NotFound {
                reference: reference.clone(),
            })
    }

    fn search(&self, query: &str, limit: usize) -> Result<Vec<ArchiveEntry>, ArchiveError> {
        let needle = query.to_lowercase();
        let mut hits: Vec<ArchiveEntry> = self
            .entries
            .values()
            .filter(|entry| {
                needle.is_empty()
                    || entry.descriptor.to_lowercase().contains(&needle)
                    || entry.content.to_lowercase().contains(&needle)
            })
            .cloned()
            .collect();
        hits.truncate(limit);
        Ok(hits)
    }

    fn stat(&self) -> Result<ArchiveStat, ArchiveError> {
        let mut stat = ArchiveStat {
            entries: self.entries.len(),
            ..ArchiveStat::default()
        };
        for entry in self.entries.values() {
            stat.total_tokens += u64::from(entry.tokens);
            if entry.tokens > stat.largest_tokens {
                stat.largest_tokens = entry.tokens;
                stat.largest_ref = Some(entry.rf.clone());
            }
        }
        Ok(stat)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ArchiveError {
    #[error("归档条目不存在: {reference}（引用式回灌不做模糊匹配）")]
    NotFound { reference: ArchiveRef },
    #[error("归档后端错误: {0}")]
    Backend(String),
}

impl fmt::Display for MemoryArchive {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "MemoryArchive({} entries)", self.entries.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids::{ArchiveRef, MessageId};

    fn origin() -> Origin {
        let session = SessionId::new();
        Origin::new(session, MessageId::new(session, 1), 1)
    }

    #[test]
    fn capture_redacts_and_ref_is_stable() {
        let raw = "token=ghp_abcdefghijklmnopqrstuvwxyz0123456789";
        let (entry, redaction) =
            ArchiveEntry::capture(raw, SourceTag::ToolResult, origin(), 10, 100);
        assert_eq!(redaction.hits.len(), 1);
        assert!(!entry.content.contains("ghp_"));
        assert!(entry.verify_hash());

        let (again, _) = ArchiveEntry::capture(raw, SourceTag::ToolResult, origin(), 10, 100);
        assert_eq!(entry.rf, again.rf);
    }

    #[test]
    fn fetch_is_deterministic_and_strict() {
        let mut archive = MemoryArchive::new();
        let (entry, _) =
            ArchiveEntry::capture("hello world", SourceTag::ToolResult, origin(), 2, 0);
        let rf = archive.put(entry.clone()).unwrap();
        assert_eq!(archive.fetch(&rf).unwrap().content, "hello world");

        let missing = ArchiveRef::from_content("never stored");
        assert!(matches!(
            archive.fetch(&missing),
            Err(ArchiveError::NotFound { .. })
        ));
    }

    #[test]
    fn put_is_idempotent() {
        let mut archive = MemoryArchive::new();
        let (entry, _) = ArchiveEntry::capture("same", SourceTag::ToolResult, origin(), 1, 0);
        archive.put(entry.clone()).unwrap();
        archive.put(entry.clone()).unwrap();
        assert_eq!(archive.len(), 1);
    }

    #[test]
    fn descriptor_is_capped_and_single_line() {
        let content = format!("第一行内容\n第二行 {}", "x".repeat(200));
        let text = build_descriptor(SourceTag::ToolResult, &origin(), &content, 60);
        assert!(text.chars().count() <= 60);
        assert!(!text.contains('\n'));
        assert!(text.contains("第一行内容"));
    }

    #[test]
    fn truncate_respects_char_boundaries() {
        let text = "一二三四五六七八九十";
        assert_eq!(truncate_chars(text, 5).chars().count(), 5);
        assert!(truncate_chars(text, 5).ends_with('…'));
        assert_eq!(truncate_chars("abc", 10), "abc");
    }
}
