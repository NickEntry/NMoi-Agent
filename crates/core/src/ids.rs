//! 标识符（设计文档 §5.1）。
//!
//! ```text
//! session_id  = 每次 fork 新生成（UUIDv7，天然按时间有序）
//! message_id  = {session_id}:{seq}
//! unit_id     = u:{session_id}:{seq}
//! ref         = ref#<16 hex>，内容寻址，跨会话 / 跨 fork 稳定
//! ```
//!
//! `MessageId` 与 `UnitId` 是不同类型，且解析时前缀不同——编辑类接口只接受
//! `UnitId`，从类型层面消除"拿消息 ID 当单元 ID"的误用（§4.2）。

use std::fmt;
use std::str::FromStr;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

/// 一次会话 / 一次 fork 的标识。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SessionId(Uuid);

impl SessionId {
    /// 新会话 ID。UUIDv7 前缀是毫秒时间戳，排序近似创建顺序。
    pub fn new() -> Self {
        Self(Uuid::now_v7())
    }

    pub fn from_uuid(uuid: Uuid) -> Self {
        Self(uuid)
    }

    pub fn as_uuid(&self) -> Uuid {
        self.0
    }

    /// 短形式，用于指针描述符与日志（8 位十六进制）。
    pub fn short(&self) -> String {
        let simple = self.0.simple().to_string();
        simple[..8].to_string()
    }
}

impl Default for SessionId {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl FromStr for SessionId {
    type Err = IdError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Uuid::parse_str(s)
            .map(Self)
            .map_err(|_| IdError::Malformed {
                kind: "session",
                value: s.to_string(),
            })
    }
}

/// `{session_id}:{seq}`。`seq` 在会话内单调递增，从 1 开始。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct MessageId {
    session: SessionId,
    seq: u64,
}

impl MessageId {
    pub fn new(session: SessionId, seq: u64) -> Self {
        Self { session, seq }
    }

    pub fn session(&self) -> SessionId {
        self.session
    }

    pub fn seq(&self) -> u64 {
        self.seq
    }

    /// 原子单元 ID 取首条消息的 (session, seq)。
    pub fn unit(&self) -> UnitId {
        UnitId {
            session: self.session,
            seq: self.seq,
        }
    }
}

impl fmt::Display for MessageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.session, self.seq)
    }
}

impl FromStr for MessageId {
    type Err = IdError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let (session, seq) = s.rsplit_once(':').ok_or_else(|| IdError::Malformed {
            kind: "message",
            value: s.to_string(),
        })?;
        let session = SessionId::from_str(session)?;
        let seq: u64 = seq.parse().map_err(|_| IdError::Malformed {
            kind: "message",
            value: s.to_string(),
        })?;
        if seq == 0 {
            return Err(IdError::Malformed {
                kind: "message",
                value: s.to_string(),
            });
        }
        Ok(Self { session, seq })
    }
}

impl TryFrom<String> for MessageId {
    type Error = IdError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::from_str(&value)
    }
}

impl From<MessageId> for String {
    fn from(value: MessageId) -> Self {
        value.to_string()
    }
}

/// 原子单元 ID，形如 `u:{session_id}:{seq}`。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct UnitId {
    session: SessionId,
    seq: u64,
}

impl UnitId {
    pub fn new(session: SessionId, seq: u64) -> Self {
        Self { session, seq }
    }

    pub fn session(&self) -> SessionId {
        self.session
    }

    pub fn seq(&self) -> u64 {
        self.seq
    }

    /// 该单元首条消息的 ID。
    pub fn first_message(&self) -> MessageId {
        MessageId {
            session: self.session,
            seq: self.seq,
        }
    }
}

impl fmt::Display for UnitId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "u:{}:{}", self.session, self.seq)
    }
}

impl FromStr for UnitId {
    type Err = IdError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let rest = s.strip_prefix("u:").ok_or_else(|| IdError::Malformed {
            kind: "unit",
            value: s.to_string(),
        })?;
        let (session, seq) = rest.rsplit_once(':').ok_or_else(|| IdError::Malformed {
            kind: "unit",
            value: s.to_string(),
        })?;
        let session = SessionId::from_str(session)?;
        let seq: u64 = seq.parse().map_err(|_| IdError::Malformed {
            kind: "unit",
            value: s.to_string(),
        })?;
        if seq == 0 {
            return Err(IdError::Malformed {
                kind: "unit",
                value: s.to_string(),
            });
        }
        Ok(Self { session, seq })
    }
}

impl TryFrom<String> for UnitId {
    type Error = IdError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::from_str(&value)
    }
}

impl From<UnitId> for String {
    fn from(value: UnitId) -> Self {
        value.to_string()
    }
}

/// 归档引用。内容寻址：同一段原文得到同一个 ref，跨会话 / 跨 fork 稳定（§5.1）。
///
/// 展示形式 `ref#a3f9c1d2e4b5a6c7`；库内即此字符串。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct ArchiveRef(String);

impl ArchiveRef {
    const PREFIX: &'static str = "ref#";
    /// 8 字节 = 16 位十六进制。按生日界，十亿级条目下碰撞概率仍可忽略。
    const HEX_LEN: usize = 16;

    /// 由原文计算引用。调用方负责先脱敏——归档里存的是脱敏后的文本（§5.6）。
    pub fn from_content(content: &str) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(content.as_bytes());
        let digest = hasher.finalize();
        let mut hex = String::with_capacity(Self::HEX_LEN);
        for byte in digest.iter().take(Self::HEX_LEN / 2) {
            hex.push_str(&format!("{byte:02x}"));
        }
        Self(format!("{}{}", Self::PREFIX, hex))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn parse(s: &str) -> Result<Self, IdError> {
        Self::from_str(s)
    }
}

impl fmt::Display for ArchiveRef {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl FromStr for ArchiveRef {
    type Err = IdError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let hex = s
            .strip_prefix(Self::PREFIX)
            .ok_or_else(|| IdError::Malformed {
                kind: "archive_ref",
                value: s.to_string(),
            })?;
        if hex.len() != Self::HEX_LEN || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(IdError::Malformed {
                kind: "archive_ref",
                value: s.to_string(),
            });
        }
        Ok(Self(format!(
            "{}{}",
            Self::PREFIX,
            hex.to_ascii_lowercase()
        )))
    }
}

impl TryFrom<String> for ArchiveRef {
    type Error = IdError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::from_str(&value)
    }
}

impl From<ArchiveRef> for String {
    fn from(value: ArchiveRef) -> Self {
        value.0
    }
}

/// 上下文快照 ID（模式 4 的 undo 锚点）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SnapshotId(Uuid);

impl SnapshotId {
    pub fn new() -> Self {
        Self(Uuid::now_v7())
    }
}

impl Default for SnapshotId {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for SnapshotId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// ID 解析错误。
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum IdError {
    #[error("{kind} id 格式非法: {value}")]
    Malformed { kind: &'static str, value: String },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_id_roundtrip() {
        let session = SessionId::new();
        let id = MessageId::new(session, 42);
        let text = id.to_string();
        let parsed: MessageId = text.parse().unwrap();
        assert_eq!(parsed, id);
        assert_eq!(parsed.seq(), 42);
        assert_eq!(parsed.unit().first_message(), id);
    }

    #[test]
    fn unit_prefix_is_required() {
        let session = SessionId::new();
        let id = MessageId::new(session, 7);
        assert!(id.to_string().parse::<UnitId>().is_err());
        assert!(id.unit().to_string().parse::<UnitId>().is_ok());
    }

    #[test]
    fn seq_zero_rejected() {
        let session = SessionId::new();
        assert!(format!("{session}:0").parse::<MessageId>().is_err());
        assert!(format!("u:{session}:0").parse::<UnitId>().is_err());
    }

    #[test]
    fn archive_ref_is_content_addressed() {
        let a = ArchiveRef::from_content("hello");
        let b = ArchiveRef::from_content("hello");
        let c = ArchiveRef::from_content("hello!");
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.as_str().starts_with("ref#"));
        assert_eq!(a.as_str().len(), 4 + 16);
        assert_eq!(a.to_string().parse::<ArchiveRef>().unwrap(), a);
    }

    #[test]
    fn archive_ref_rejects_garbage() {
        assert!("a3f9".parse::<ArchiveRef>().is_err());
        assert!("ref#zzzzzzzzzzzzzzzz".parse::<ArchiveRef>().is_err());
        assert!("ref#a3f9".parse::<ArchiveRef>().is_err());
    }
}
