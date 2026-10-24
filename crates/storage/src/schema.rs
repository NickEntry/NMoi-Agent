//! SQLite schema v1（设计文档 §4.3）。
//!
//! `PRAGMA` 在 `open` 时设置，不写进 DDL：WAL 是连接级行为，放 DDL 里会产生
//! "迁移脚本改了库的日志模式"这种副作用。

pub const SCHEMA_V1: &str = r#"
CREATE TABLE conversations (
    id             TEXT PRIMARY KEY,
    title          TEXT NOT NULL,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    device_origin  TEXT NOT NULL
);

CREATE TABLE messages (
    id           TEXT PRIMARY KEY,
    conv_id      TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    unit_id      TEXT NOT NULL,
    unit_kind    TEXT NOT NULL,
    unit_closed  INTEGER NOT NULL,
    seq          INTEGER NOT NULL,
    role         TEXT NOT NULL,
    source       TEXT NOT NULL,
    content      TEXT NOT NULL,
    reasoning    TEXT,
    form         TEXT NOT NULL,
    tokens       INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    logical_clock INTEGER NOT NULL,
    archived_ref TEXT,
    protected    INTEGER NOT NULL DEFAULT 0,
    pinned       INTEGER NOT NULL DEFAULT 0,
    -- 用户锁定：禁止内容编辑，但允许压缩（D11）
    locked       INTEGER NOT NULL DEFAULT 0,
    -- 编辑版本号：每次块级编辑 +1
    revision     INTEGER NOT NULL DEFAULT 0,
    tool_calls   TEXT NOT NULL DEFAULT '[]',
    tool_call_id TEXT,
    CHECK (unit_kind IN ('turn', 'tool_pair')),
    CHECK (role IN ('system', 'user', 'assistant', 'tool'))
);

CREATE INDEX messages_by_conv_clock ON messages(conv_id, logical_clock);
CREATE INDEX messages_by_conv_seq ON messages(conv_id, seq);
CREATE INDEX messages_by_unit ON messages(unit_id);
CREATE INDEX messages_by_ref ON messages(archived_ref);

CREATE TABLE archive (
    ref            TEXT PRIMARY KEY,
    conv_id        TEXT NOT NULL,
    content        TEXT NOT NULL,
    tokens         INTEGER NOT NULL,
    source         TEXT NOT NULL,
    descriptor     TEXT NOT NULL,
    protected      INTEGER NOT NULL DEFAULT 0,
    origin_session TEXT NOT NULL,
    origin_message TEXT NOT NULL,
    origin_turn    INTEGER NOT NULL,
    content_hash   TEXT NOT NULL,
    created_at     INTEGER NOT NULL,
    note           TEXT
);

CREATE INDEX archive_by_conv ON archive(conv_id, created_at);
CREATE INDEX archive_by_created ON archive(created_at);

-- 上下文分界线：线上不进上下文，线下是当前窗口（D12）
CREATE TABLE dividers (
    id         TEXT PRIMARY KEY,
    conv_id    TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    after_seq  INTEGER NOT NULL,
    kind       TEXT NOT NULL,
    note       TEXT,
    created_at INTEGER NOT NULL,
    CHECK (kind IN ('user', 'compression'))
);

CREATE INDEX dividers_by_conv ON dividers(conv_id, after_seq);

-- 编辑审计：块级编辑的前后哈希（D13）
CREATE TABLE edits (
    id          TEXT PRIMARY KEY,
    conv_id     TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    unit_id     TEXT NOT NULL,
    message_id  TEXT NOT NULL,
    block       TEXT NOT NULL,
    before_hash TEXT NOT NULL,
    after_hash  TEXT NOT NULL,
    tokens_before INTEGER NOT NULL,
    tokens_after  INTEGER NOT NULL,
    revision    INTEGER NOT NULL,
    at          INTEGER NOT NULL
);

CREATE INDEX edits_by_conv ON edits(conv_id, at);

CREATE TABLE events (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    conv_id TEXT NOT NULL,
    kind    TEXT NOT NULL,
    payload BLOB NOT NULL,
    at      INTEGER NOT NULL
);

CREATE INDEX events_by_conv ON events(conv_id, at);

CREATE TABLE quarantine (
    id           TEXT PRIMARY KEY,
    source_table TEXT NOT NULL,
    row_key      TEXT NOT NULL,
    reason       TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   INTEGER NOT NULL
);

CREATE INDEX quarantine_by_time ON quarantine(created_at);

CREATE TABLE recovery (
    id            TEXT PRIMARY KEY,
    batch_id      TEXT NOT NULL,
    issue_code    TEXT NOT NULL,
    source_table  TEXT NOT NULL,
    row_key       TEXT NOT NULL,
    action        TEXT NOT NULL,
    quarantine_id TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    undone_at     INTEGER
);

CREATE INDEX recovery_by_time ON recovery(created_at);
CREATE INDEX recovery_by_batch ON recovery(batch_id);

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"#;

/// v2：`messages.incomplete`（断流标记，D21 的数据侧）。
///
/// 只加列、不重建表：`ALTER TABLE ADD COLUMN` 是常数时间，老行的默认值 0
/// 正好是"完整"的语义，不需要回填。
pub const SCHEMA_V2: &str = r#"
ALTER TABLE messages ADD COLUMN incomplete INTEGER NOT NULL DEFAULT 0;
"#;

/// v3：设置文档 + 设置修复记录（D86）。
///
/// 一张 `settings` 表装四类文档（模型覆盖 / 助手 / 会话 / 角色），
/// 用 `(scope, doc_key)` 定位。分成四张表的话，UI 每加一个开关就要迁移一次 schema，
/// 而迁移脚本只增不减，旧库打开会越来越慢。
pub const SCHEMA_V3: &str = r#"
CREATE TABLE settings (
    scope        TEXT NOT NULL,
    doc_key      TEXT NOT NULL,
    doc_version  INTEGER NOT NULL,
    payload      TEXT NOT NULL,
    updated_at   INTEGER NOT NULL,
    PRIMARY KEY (scope, doc_key),
    CHECK (length(doc_key) > 0),
    CHECK (length(payload) > 0),
    CHECK (doc_version >= 1)
);

CREATE INDEX settings_by_scope ON settings(scope, updated_at DESC);

-- 设置被重置的记录（D79：回退必须是事件，不能静默）。
-- old_digest 是原值的哈希前 16 位，不是原值——设置里可能夹着自定义 header 的密钥。
CREATE TABLE settings_repair (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    at         INTEGER NOT NULL,
    scope      TEXT NOT NULL,
    doc_key    TEXT NOT NULL,
    reason     TEXT NOT NULL,
    old_digest TEXT NOT NULL
);

CREATE INDEX settings_repair_by_time ON settings_repair(at DESC);
"#;

/// v4：会话记住"最近一次用的模型"（D101）。
pub const SCHEMA_V4: &str = r#"
ALTER TABLE conversations ADD COLUMN model TEXT;
"#;

/// v5：消息版本（D137）。
///
/// 每版存两组正文：`raw_text`（模型原始输出）与 `edited_text`（工具/编辑改过的那份）；
/// `active_view` 记这个版本上次看的是哪一组——**按版本记，不按消息记**。
///
/// `messages` 上只加两个计数列：总共几版、当前第几版。正文仍然读 `messages.content`
/// （它是"当前显示的那一组"），所以 `load_units` 的读路径不用改。
pub const SCHEMA_V5: &str = r#"
CREATE TABLE message_versions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id     TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    version_no     INTEGER NOT NULL CHECK (version_no >= 1),
    raw_text       TEXT NOT NULL,
    edited_text    TEXT,
    edited_by_tool INTEGER NOT NULL DEFAULT 0 CHECK (edited_by_tool IN (0, 1)),
    active_view    TEXT NOT NULL DEFAULT 'raw' CHECK (active_view IN ('raw', 'edited')),
    created_at     INTEGER NOT NULL,
    -- 没被编辑过的版本不许有 edited_text；被编辑过的必须有
    CHECK ((edited_by_tool = 0 AND edited_text IS NULL)
        OR (edited_by_tool = 1 AND edited_text IS NOT NULL)),
    UNIQUE (message_id, version_no)
);

CREATE INDEX message_versions_by_message ON message_versions(message_id, version_no);

ALTER TABLE messages ADD COLUMN version_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN version_active INTEGER NOT NULL DEFAULT 0;
"#;
