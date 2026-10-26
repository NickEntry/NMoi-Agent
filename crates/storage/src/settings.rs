//! 设置文档的读写（schema v3 / D86）。
//!
//! # 为什么是一张 `settings` 表，而不是五张
//!
//! 模型覆盖、助手、会话设置、角色配置……形状完全不同，但它们**用法完全一样**：
//! 按 `(scope, key)` 整份读出、整份写回。没人会去查"所有把 thinking 设成 max 的模型"。
//! 分成五张表的好处是「字段有类型约束」，代价是**每次 UI 加一个开关就要迁移一次 schema**。
//!
//! 这类迁移做一次丢一次：迁移脚本越堆越多，`validate` 要跟着改，旧库打开越来越慢。
//! 所以这里只约束四件事——scope 合法、key 非空、版本号在、payload 是合法 JSON；
//! 字段对不对由 core 的 `Deserialize` 管（`serde(default)` + 字段只增不减，D86）。
//!
//! # 版本号高的时候不要硬读
//!
//! 新版本客户端写的文档，旧版本读它会**丢掉不认识的字段**；再写回去，用户的设置就
//! 静默没了。所以 `doc_version > DOC_VERSION` 一律当坏数据：不读、记一条修复记录、用默认值。
//! 这是唯一允许"回退默认值"的场合（D79）。

use rusqlite::{params, OptionalExtension};
use serde::de::DeserializeOwned;
use serde::Serialize;

use nmoi_core::settings::DOC_VERSION;

use crate::repo::{sha256_hex, StorageError};
use crate::Storage;

/// 设置文档的作用域。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    /// 逐模型的参数覆盖（`ModelOverrides`）。key = `provider/model`。
    ModelOverrides,
    /// 助手（`AssistantSettings`）。key = 助手 id。
    Assistant,
    /// 会话（`ConversationSettings`）。key = 会话 id。
    Conversation,
    /// 五类模型角色（标题/压缩/翻译/视觉/建议）。key = `global`。
    Roles,
    /// 模型服务（D156）：端点、模型名、供应商类型。key = `default`。
    ///
    /// **密钥不在这份文档里**——它由平台侧用 Keystore 加密后单独落在 `vault/` 下
    /// （`docs/secrets.md` 的 L1）。这里存的是"能不能发请求"的那几个非敏感字段，
    /// 所以导出/备份带着它走也不会带走凭据。
    Provider,
    /// token 估算的校准（D95）。key = `模型|级别|内容类`。
    ///
    /// 严格说这是派生数据，不是设置；但它和设置一样要跨重启活着，
    /// 而 D86 的 `(scope, key)` 整份读写正好够用——**为它加一张表就得加一次迁移**。
    Calibration,
}

impl Scope {
    pub fn as_str(self) -> &'static str {
        match self {
            Scope::ModelOverrides => "model_overrides",
            Scope::Assistant => "assistant",
            Scope::Conversation => "conversation",
            Scope::Roles => "roles",
            Scope::Provider => "provider",
            Scope::Calibration => "calibration",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "model_overrides" => Some(Scope::ModelOverrides),
            "assistant" => Some(Scope::Assistant),
            "conversation" => Some(Scope::Conversation),
            "roles" => Some(Scope::Roles),
            "provider" => Some(Scope::Provider),
            "calibration" => Some(Scope::Calibration),
            _ => None,
        }
    }
}

/// 读出来的一份文档。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Doc {
    pub doc_version: i64,
    pub payload: String,
    pub updated_at: i64,
}

/// 带类型的读取结果。
///
/// `Reset` 不是错误：它是"有数据，但这份数据没法用，已经按默认处理并记了一笔"。
/// 把它做成 `Err` 会让调用方在每一个 `?` 上都写一遍同样的兜底。
#[derive(Debug, Clone, PartialEq)]
pub enum Loaded<T> {
    Missing,
    Ready(T),
    Reset { reason: String },
}

/// 一条"设置被重置"的记录。**回退必须是事件，不能静默**（D79）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepairNote {
    pub at: i64,
    pub scope: Scope,
    pub key: String,
    pub reason: String,
    /// 原值的哈希前 16 位，不是原值——设置里可能夹着自定义 header 的密钥。
    pub old_digest: String,
}

impl Storage {
    /// 写一份文档。`now` 由调用方给：这一层不读墙上时钟。
    pub fn put_doc(
        &self,
        scope: Scope,
        key: &str,
        payload: &str,
        now: i64,
    ) -> Result<(), StorageError> {
        if key.is_empty() {
            return Err(StorageError::Inconsistent("设置文档的 key 不能为空".into()));
        }
        self.conn.execute(
            "INSERT INTO settings (scope, doc_key, doc_version, payload, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(scope, doc_key) DO UPDATE SET
                 doc_version = excluded.doc_version,
                 payload = excluded.payload,
                 updated_at = excluded.updated_at",
            params![scope.as_str(), key, DOC_VERSION, payload, now],
        )?;
        Ok(())
    }

    /// 写一份**结构体**文档。序列化失败不该发生，所以那一条也算内部错误。
    pub fn put_setting<T: Serialize>(
        &self,
        scope: Scope,
        key: &str,
        value: &T,
        now: i64,
    ) -> Result<(), StorageError> {
        let payload = serde_json::to_string(value)
            .map_err(|error| StorageError::Inconsistent(format!("设置序列化失败: {error}")))?;
        self.put_doc(scope, key, &payload, now)
    }

    pub fn get_doc(&self, scope: Scope, key: &str) -> Result<Option<Doc>, StorageError> {
        Ok(self
            .conn
            .query_row(
                "SELECT doc_version, payload, updated_at FROM settings
                 WHERE scope = ?1 AND doc_key = ?2",
                params![scope.as_str(), key],
                |row| {
                    Ok(Doc {
                        doc_version: row.get(0)?,
                        payload: row.get(1)?,
                        updated_at: row.get(2)?,
                    })
                },
            )
            .optional()?)
    }

    /// 按类型读。坏数据会记一笔 `settings_repair` 并返回 `Reset`。
    pub fn load_setting<T: DeserializeOwned>(
        &self,
        scope: Scope,
        key: &str,
        now: i64,
    ) -> Result<Loaded<T>, StorageError> {
        let Some(doc) = self.get_doc(scope, key)? else {
            return Ok(Loaded::Missing);
        };

        if doc.doc_version > DOC_VERSION {
            let reason = format!(
                "文档版本 {} 高于程序支持的 {DOC_VERSION}，不硬读（会丢字段）",
                doc.doc_version
            );
            self.record_repair(scope, key, &reason, &doc.payload, now)?;
            return Ok(Loaded::Reset { reason });
        }

        match serde_json::from_str::<T>(&doc.payload) {
            Ok(value) => Ok(Loaded::Ready(value)),
            Err(error) => {
                let reason = format!("文档解析失败: {error}");
                self.record_repair(scope, key, &reason, &doc.payload, now)?;
                Ok(Loaded::Reset { reason })
            }
        }
    }

    /// 一个作用域下的全部文档，最近更新的在前。
    pub fn list_docs(&self, scope: Scope) -> Result<Vec<(String, Doc)>, StorageError> {
        let mut statement = self.conn.prepare(
            "SELECT doc_key, doc_version, payload, updated_at FROM settings
             WHERE scope = ?1 ORDER BY updated_at DESC, doc_key ASC",
        )?;
        let rows = statement.query_map(params![scope.as_str()], |row| {
            Ok((
                row.get::<_, String>(0)?,
                Doc {
                    doc_version: row.get(1)?,
                    payload: row.get(2)?,
                    updated_at: row.get(3)?,
                },
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    /// 删一份文档。返回"本来有没有"——重复删是幂等的，别报错。
    pub fn delete_doc(&self, scope: Scope, key: &str) -> Result<bool, StorageError> {
        let removed = self.conn.execute(
            "DELETE FROM settings WHERE scope = ?1 AND doc_key = ?2",
            params![scope.as_str(), key],
        )?;
        Ok(removed > 0)
    }

    /// 记一条"设置被重置"。`raw` 只用来算摘要，不入库。
    pub fn record_repair(
        &self,
        scope: Scope,
        key: &str,
        reason: &str,
        raw: &str,
        now: i64,
    ) -> Result<(), StorageError> {
        let digest: String = sha256_hex(raw).chars().take(16).collect();
        self.conn.execute(
            "INSERT INTO settings_repair (at, scope, doc_key, reason, old_digest)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![now, scope.as_str(), key, reason, digest],
        )?;
        Ok(())
    }

    pub fn repairs(&self, limit: usize) -> Result<Vec<RepairNote>, StorageError> {
        let mut statement = self.conn.prepare(
            "SELECT at, scope, doc_key, reason, old_digest FROM settings_repair
             ORDER BY at DESC, id DESC LIMIT ?1",
        )?;
        let rows = statement.query_map(params![limit as i64], |row| {
            let scope: String = row.get(1)?;
            Ok(RepairNote {
                at: row.get(0)?,
                scope: Scope::parse(&scope).unwrap_or(Scope::Conversation),
                key: row.get(2)?,
                reason: row.get(3)?,
                old_digest: row.get(4)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    /// 修复记录只留最近 `keep` 条：它是给人看最近出了什么事的，不是账本。
    pub fn trim_repairs(&self, keep: usize) -> Result<usize, StorageError> {
        let removed = self.conn.execute(
            "DELETE FROM settings_repair WHERE id NOT IN (
                 SELECT id FROM settings_repair ORDER BY at DESC, id DESC LIMIT ?1
             )",
            params![keep as i64],
        )?;
        Ok(removed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::OpenOutcome;
    use nmoi_core::settings::AssistantSettings;

    fn storage() -> (tempfile::TempDir, Storage) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("app.db");
        match Storage::open(path).unwrap() {
            OpenOutcome::Ready(storage) => (dir, storage),
            OpenOutcome::Blocked(blocked) => {
                panic!("被语义校验拦下: {:?}", blocked.report().issues)
            }
        }
    }

    /// 新版本客户端写的文档，旧版本**不许硬读**：硬读会丢字段，再写回去等于静默删设置。
    #[test]
    fn a_newer_document_version_is_reset_and_recorded() {
        let (_dir, storage) = storage();
        storage
            .put_doc(
                Scope::Assistant,
                "as_1",
                r#"{"name":"未来的助手","字段我们不认识":1}"#,
                1,
            )
            .unwrap();
        storage
            .conn
            .execute(
                "UPDATE settings SET doc_version = ?1
                 WHERE scope = 'assistant' AND doc_key = 'as_1'",
                params![DOC_VERSION + 1],
            )
            .unwrap();

        let loaded: Loaded<AssistantSettings> = storage
            .load_setting(Scope::Assistant, "as_1", 1_700_000_000_000)
            .unwrap();
        match loaded {
            Loaded::Reset { reason } => assert!(reason.contains("高于"), "{reason}"),
            other => panic!("版本过新必须当坏数据: {other:?}"),
        }

        let repairs = storage.repairs(10).unwrap();
        assert_eq!(repairs.len(), 1);
        assert_eq!(repairs[0].key, "as_1");
        assert_eq!(repairs[0].scope, Scope::Assistant);
        assert_eq!(repairs[0].at, 1_700_000_000_000);
    }

    /// 同版本的文档照读——别把"版本号存在"读成"版本号必须更大"。
    #[test]
    fn the_current_version_reads_normally() {
        let (_dir, storage) = storage();
        let doc = AssistantSettings {
            name: "现在".into(),
            ..AssistantSettings::default()
        };
        storage
            .put_setting(Scope::Assistant, "as_now", &doc, 1)
            .unwrap();
        assert!(matches!(
            storage
                .load_setting::<AssistantSettings>(Scope::Assistant, "as_now", 2)
                .unwrap(),
            Loaded::Ready(_)
        ));
        assert!(storage.repairs(5).unwrap().is_empty());
    }
}
