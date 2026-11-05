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

/// v6：**用户编辑层**（D148）。
///
/// 用户改的是"他当时看到的那一份"，**不改变"被 AI 改过"这个状态**——
/// 颜色只看 `edited_by_tool`，用户改过只是另一个标记，不参与三色。
pub const SCHEMA_V6: &str = r#"
ALTER TABLE message_versions ADD COLUMN user_text TEXT;
ALTER TABLE message_versions ADD COLUMN user_edit_of TEXT;
"#;

/// v7：**用户编辑就地改正文，不再单开一层**（D152）。
///
/// v6 那一层是"另存一份覆盖上去"，一版只有一份 `user_text`——于是改过"原文"再改"改后"，
/// 前一次就被覆盖掉了（用户 2026-10-26 报的正是这个：改红字，蓝字那份回到最初的版本）。
/// 现在两组正文**各改各的列**，谁也不会顶掉谁；颜色仍然只看 `edited_by_tool`（D148 的结论不变）。
///
/// 回填规则：`user_edit_of = 'raw'` 的写进 `raw_text`，`'edited'` 的写进 `edited_text`。
/// 已经写进 `messages.content` 的那份不用动——它本来就是"当前显示的那一份"。
pub const SCHEMA_V7: &str = r#"
UPDATE message_versions SET raw_text = user_text
 WHERE user_edit_of = 'raw' AND user_text IS NOT NULL;
UPDATE message_versions SET edited_text = user_text
 WHERE user_edit_of = 'edited' AND user_text IS NOT NULL AND edited_by_tool = 1;
ALTER TABLE message_versions DROP COLUMN user_text;
ALTER TABLE message_versions DROP COLUMN user_edit_of;
"#;

/// v8：列名改成**他心里的那两个词**（D154）。
///
/// 一版只有两种正文，不许再有第三种：
/// - `origin_message`：原文（界面上那个蓝的）；
/// - `tooledit_message`：AI 改过的那份（红的那份）。**没有它就不显示红蓝状态**。
///
/// 原来叫 `raw_text` / `edited_text`，名字里带着"另存了一层"的味道，
/// 和 v6 那套覆盖层（已删）混在一起容易让人以为还有看不见的副本。
/// 改名字不动数据：`RENAME COLUMN` 一个字节都不搬，`CHECK` 里的引用由 SQLite 自己跟着改。
pub const SCHEMA_V8: &str = r#"
ALTER TABLE message_versions RENAME COLUMN raw_text TO origin_message;
ALTER TABLE message_versions RENAME COLUMN edited_text TO tooledit_message;
"#;

/// v9：会话按**助手**归属（D174）。
///
/// `assistant_id` 指向 `settings(assistant, <id>)` 那一份配置，默认 `'default'`——
/// 迁移时所有老会话都归 `'default'`，因为在此之前界面上只有一个助手。
/// `NOT NULL DEFAULT 'default'` 不只是给迁移用：以后新增的写入路径忘了给这个值时，
/// 会话会落进 `default`，而不是落进一个"界面上哪儿都看不见"的空归属
/// （铁律 ②：库里每样东西都要能在界面上指出来）。
///
/// 索引带上 `updated_at DESC`：列表页的查询就是"某个助手的会话按最近动过排"，
/// 索引与那条 ORDER BY 一致时才不用额外排序。
pub const SCHEMA_V9: &str = r#"
ALTER TABLE conversations ADD COLUMN assistant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX conversations_by_assistant ON conversations(assistant_id, updated_at DESC);
"#;

/// v10（D208）：图片附件。
///
/// 为什么存 BLOB 而不是文件路径：备份是一份 `.db`（`Storage::backup` 在副本上校验），
/// 路径会指着另一台机器上不存在的文件；而"库里有一条指不出来的记录"正是铁律 ② 要挡的。
/// 外键跟着消息走：删消息（或删会话级联）时图片一起走，不留孤儿。
pub const SCHEMA_V10: &str = r#"
CREATE TABLE attachments (
    id         TEXT PRIMARY KEY,
    conv_id    TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    mime       TEXT NOT NULL,
    bytes      BLOB NOT NULL,
    size       INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX attachments_by_conv ON attachments(conv_id, created_at);
CREATE INDEX attachments_by_message ON attachments(message_id);
"#;

/// v11（D216）：附件改成"文件在盘上、库里只留路径"。
///
/// SQLite 不能直接去掉 `NOT NULL`，所以重建一次表：`bytes` 允许为空（老数据还在里面，
/// 读的时候顺手搬到盘上），新数据只写 `path`。备份仍然是"一份 .db + 一个 sessions/ 目录"，
/// 存储管理页会把两样都算进去。
pub const SCHEMA_V11: &str = r#"
CREATE TABLE attachments_v11 (
    id         TEXT PRIMARY KEY,
    conv_id    TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    mime       TEXT NOT NULL,
    bytes      BLOB,
    path       TEXT,
    size       INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
INSERT INTO attachments_v11(id, conv_id, message_id, mime, bytes, path, size, created_at)
    SELECT id, conv_id, message_id, mime, bytes, NULL, size, created_at FROM attachments;
DROP TABLE attachments;
ALTER TABLE attachments_v11 RENAME TO attachments;
CREATE INDEX attachments_by_conv ON attachments(conv_id, created_at);
CREATE INDEX attachments_by_message ON attachments(message_id);
"#;

/// v12：每个会话自己的 `/chat`（D231 / 0.15ao）。
///
/// 只加一列，**默认 `shared`**：老会话升级上来还是原来那个全局工作区，
/// 文件不会"突然全没了"。新建会话由 `create_conversation` 显式写成 `chat`。
///
/// 为什么是列而不是一张表：一个会话只有一个工作区，没有第二份要存的东西。
pub const SCHEMA_V12: &str = r#"
ALTER TABLE conversations ADD COLUMN workspace_mode TEXT NOT NULL DEFAULT 'shared';
"#;

/// v13：工作区改成"用户建的目录 + 助手绑定"（D234），`workspace_mode` 这一列退场。
///
/// 为什么删而不是留着不用：它和 `workspaces` 回答的是同一个问题（"这一轮的工具根在哪"）。
/// 两份答案摆在一起，下一个读代码的人得先猜哪一份算数——那正是"存了但看不见"的另一种形状。
///
/// `DROP COLUMN` 在这个库上是安全的：这一列没有索引、没有视图、没有生成列依赖它，
/// 而且整条迁移在一个事务里，失败就整体回滚（`migrate` 那道闸）。
pub const SCHEMA_V13: &str = r#"
ALTER TABLE conversations DROP COLUMN workspace_mode;
"#;

/// v14（D250）：版本自己带思考。
///
/// 以前版本只存正文，思考留在 `messages.reasoning` 上——于是重试之后屏幕上显示的是
/// **新正文 + 旧思考**（2026-11-04 真机：正文 142 字，上面那块写着「深度思考 · 1373 字」）。
/// 思考属于那一版，切版本就该跟着换。
pub const SCHEMA_V14: &str = r#"
ALTER TABLE message_versions ADD COLUMN reasoning TEXT;
"#;

/// v15（D256）：**消息的正文 / 思考 / 工具调用改成一张 parts 表**。
///
/// 为什么不是三列继续躺着：part 是编辑与版本定位的最小单位（`p3` 这种 id 稳定、不复用），
/// 而"第几个工具调用"这种序号轴删一个就全错位。库里存的就是界面上画的那几块。
///
/// 迁移顺序：先建表 → 回填（思考 → 正文 → 工具，与内存里的顺序一致）→ 再删列。
/// 删列放在最后：回填读的就是这三列，先删就读不到了。
pub const SCHEMA_V15: &str = r#"
CREATE TABLE message_parts (
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    part_id    TEXT NOT NULL,
    ord        INTEGER NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('text','reasoning','tool','image','notice')),
    body       TEXT NOT NULL,
    PRIMARY KEY (message_id, part_id),
    UNIQUE (message_id, ord)
);

CREATE INDEX message_parts_by_message ON message_parts(message_id, ord);

INSERT INTO message_parts(message_id, part_id, ord, kind, body)
SELECT id, 'p1', 0, 'reasoning', json_object('text', reasoning)
  FROM messages WHERE reasoning IS NOT NULL AND trim(reasoning) <> '';

INSERT INTO message_parts(message_id, part_id, ord, kind, body)
SELECT id,
       CASE WHEN reasoning IS NOT NULL AND trim(reasoning) <> '' THEN 'p2' ELSE 'p1' END,
       CASE WHEN reasoning IS NOT NULL AND trim(reasoning) <> '' THEN 1 ELSE 0 END,
       'text', json_object('text', content)
  FROM messages WHERE trim(content) <> '';

INSERT INTO message_parts(message_id, part_id, ord, kind, body)
SELECT m.id,
       'p' || (b.n + j.key + 1),
       b.n + j.key,
       'tool',
       json_object('call_id', json_extract(j.value, '$.id'),
                   'name', json_extract(j.value, '$.name'),
                   'arguments', json_extract(j.value, '$.arguments'))
  FROM messages m
  JOIN (SELECT id,
               (CASE WHEN reasoning IS NOT NULL AND trim(reasoning) <> '' THEN 1 ELSE 0 END)
             + (CASE WHEN trim(content) <> '' THEN 1 ELSE 0 END) AS n
          FROM messages) b ON b.id = m.id
  JOIN json_each(CASE WHEN json_valid(m.tool_calls) THEN m.tool_calls ELSE '[]' END) j;

ALTER TABLE messages DROP COLUMN content;
ALTER TABLE messages DROP COLUMN reasoning;
ALTER TABLE messages DROP COLUMN tool_calls;
"#;

/// v16（D258）：**折行**——工具结果并进调用块，`role=tool` 的行退场。
///
/// 一条消息 = 一轮。以前一次调用是两条记录（assistant 带 `tool_calls` + 一条 `role=tool`），
/// 于是"改结果"要跨两条写、版本只跟着调用那条走、序号轴删一个就全错位。
///
/// 顺序不能反：先把结果读进块里，再删行；先删行就再也找不到结果了。
///
/// 三个顺带要处理的东西：
/// 1. `dividers.after_seq` 指向被并掉的那条行时，改成它所属那条 assistant 的 seq——
///    不改的话分界线指向一个不存在的 seq，窗口会静默退回整条历史。
/// 2. `attachments` 不该挂在工具行上（图片只挂 user）。真挂了就中止迁移，不猜。
/// 3. 旧的结果没有状态：文本以"失败："/"拒绝执行："开头的算 `error`，其余算 `ok`；
///    找不到对应结果行的调用是**旧的开口单元**，标 `killed`（它确实没跑完）。
pub const SCHEMA_V16: &str = r#"
UPDATE message_parts
   SET body = json_set(
        body,
        '$.output', COALESCE((
            SELECT json_extract(tp.body, '$.text')
              FROM message_parts tp
              JOIN messages tm ON tm.id = tp.message_id
              JOIN messages cm ON cm.id = message_parts.message_id
             WHERE tm.role = 'tool'
               AND tm.tool_call_id = json_extract(message_parts.body, '$.call_id')
               AND tm.unit_id = cm.unit_id
               AND tp.kind = 'text'
             LIMIT 1), ''),
        '$.status', COALESCE((
            SELECT CASE
                     WHEN json_extract(tp.body, '$.text') LIKE '失败：%' THEN 'error'
                     WHEN json_extract(tp.body, '$.text') LIKE '拒绝执行：%' THEN 'error'
                     ELSE 'ok'
                   END
              FROM message_parts tp
              JOIN messages tm ON tm.id = tp.message_id
              JOIN messages cm ON cm.id = message_parts.message_id
             WHERE tm.role = 'tool'
               AND tm.tool_call_id = json_extract(message_parts.body, '$.call_id')
               AND tm.unit_id = cm.unit_id
               AND tp.kind = 'text'
             LIMIT 1), 'killed'))
 WHERE kind = 'tool';

UPDATE dividers
   SET after_seq = COALESCE((
        SELECT a.seq FROM messages a
         WHERE a.role = 'assistant'
           AND a.unit_id = (SELECT t.unit_id FROM messages t
                             WHERE t.seq = dividers.after_seq AND t.role = 'tool'
                             LIMIT 1)
         LIMIT 1), after_seq)
 WHERE after_seq IN (SELECT seq FROM messages WHERE role = 'tool');

CREATE TEMP TABLE _abort_if_attachments_point_at_tool_rows(
    x INTEGER CHECK (x = 0)
);
INSERT INTO _abort_if_attachments_point_at_tool_rows
SELECT COUNT(*)
  FROM attachments a JOIN messages m ON m.id = a.message_id
 WHERE m.role = 'tool';
DROP TABLE _abort_if_attachments_point_at_tool_rows;

DELETE FROM message_parts
 WHERE message_id IN (SELECT id FROM messages WHERE role = 'tool');
DELETE FROM messages WHERE role = 'tool';

UPDATE messages SET unit_closed = 1 WHERE unit_kind = 'tool_pair';
"#;

/// v17（D258）：**一版 = 这条消息全部块**。
///
/// 折行之后一轮就是一条消息，工具块也在里面。版本只存正文的话，重试一轮带工具的回复会得到
/// "新正文 + 旧工具块"——切版本也换不掉它们。这一列把整份块存下来。
///
/// 加列那一步在 Rust 里（`migrate::add_version_parts_column`）：SQLite 没有
/// `ADD COLUMN IF NOT EXISTS`，而这一步必须能重复执行。
///
/// 回填分两步：
/// 1. **当前版且带工具块**的，快照现在的块——切走再切回来要回到眼前这一份；
/// 2. 其余版本只有正文与思考（`origin_message` / `reasoning`）。旧版本本来就没存过工具块，
///    回溯等于编造。
///
/// 正文与思考仍然以 `origin_message` / `reasoning` 两列为准（视图切换靠它们），
/// `parts_json` 负责的是**其余那几块**（工具 / 图片 / 通知）。
pub const SCHEMA_V17: &str = r#"
UPDATE message_versions
   SET parts_json = (
        SELECT json_group_array(json_object('id', part_id, 'kind', kind, 'body', json(body)))
          FROM (SELECT part_id, kind, body FROM message_parts
                 WHERE message_id = message_versions.message_id ORDER BY ord)
       )
 WHERE parts_json IS NULL
   AND version_no = (SELECT version_active FROM messages WHERE id = message_versions.message_id)
   AND EXISTS (SELECT 1 FROM message_parts
                WHERE message_id = message_versions.message_id AND kind = 'tool');

UPDATE message_versions
   SET parts_json = CASE
        WHEN reasoning IS NOT NULL AND trim(reasoning) <> ''
        THEN json_array(
               json_object('id','p1','kind','reasoning','body',json_object('text',reasoning)),
               json_object('id','p2','kind','text','body',json_object('text',origin_message)))
        ELSE json_array(
               json_object('id','p1','kind','text','body',json_object('text',origin_message)))
       END
 WHERE parts_json IS NULL;
"#;
