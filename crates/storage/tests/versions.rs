//! 消息版本（D137）的往返测试。
//!
//! 覆盖 D137 测试清单里的十条：迁移、插入、切视图、切版本、删非当前、删当前、
//! 只剩一版时拒绝删、删所有版本、上限丢最旧、以及"发出去的是当前显示的那一组"。

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::migrate::{self, SCHEMA_VERSION};
use nmoi_storage::versions::{VersionView, MAX_VERSIONS};
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn assistant_unit(session: SessionId, seq: u64, text: &str) -> AtomicUnit {
    let id = MessageId::new(session, seq);
    let message = Message::text(
        id,
        Role::Assistant,
        SourceTag::History,
        text,
        text.len() as u32,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    AtomicUnit::turn(message).unwrap()
}

/// 建一个会话、放一条助手消息，返回 (storage, session, 消息 id)。
fn fixture(dir: &std::path::Path) -> (Storage, SessionId, MessageId) {
    let mut storage = ready(&dir.join("app.db"));
    let session = storage.create_conversation("版本测试", "device-a").unwrap();
    storage
        .append_unit(session, &assistant_unit(session, 1, "原始回复"))
        .unwrap();
    (storage, session, MessageId::new(session, 1))
}

/// 界面上"现在显示的那一组正文"——走 `load_units` 的读路径，
/// 也就是发送时真正会拿到的东西（不是内部字段）。
fn shown_content(storage: &Storage, session: SessionId, message: &MessageId) -> String {
    storage
        .load_units(session)
        .unwrap()
        .into_iter()
        .flat_map(|unit| unit.messages)
        .find(|m| &m.id == message)
        .map(|m| m.content)
        .expect("消息不见了")
}

#[test]
fn migration_v4_to_v5_keeps_rows_and_zeroes_counts() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");

    // 造一个只迁到 v4 的库：不走 Storage::open，避免语义校验插手。
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        migrate::migrate(&mut conn, 4).unwrap();
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', '旧库', 1, 1, 4, 'device-a')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(id, conv_id, unit_id, unit_kind, unit_closed, seq, role, source,
                                  content, form, tokens, created_at, logical_clock)
             VALUES ('m1', 'c1', 'u1', 'turn', 1, 1, 'assistant', 'history', '旧内容', 'text', 4, 1, 1)",
            [],
        )
        .unwrap();
    }

    // 升到 v5。
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        assert_eq!(
            migrate::migrate(&mut conn, SCHEMA_VERSION).unwrap(),
            SCHEMA_VERSION,
            "迁移必须一步到位到当前版本"
        );
        let (content, count, active): (String, i64, i64) = conn
            .query_row(
                "SELECT content, version_count, version_active FROM messages WHERE id = 'm1'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(content, "旧内容", "迁移不许动老数据");
        assert_eq!((count, active), (0, 0), "新列默认 0（旧消息没有版本）");
        let versions: i64 = conn
            .query_row("SELECT COUNT(*) FROM message_versions", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(versions, 0);
        // v6 的用户编辑层（D148）：老版本行没有用户编辑，两列都是 NULL
        let user: (Option<String>, Option<String>) = conn
            .query_row(
                "SELECT user_text, user_edit_of FROM message_versions WHERE message_id = 'm1'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap_or((None, None));
        assert_eq!(user, (None, None));
    }
}

#[test]
fn first_version_becomes_the_displayed_content() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());

    let (no, count) = storage.save_generated_version(&message, "第一版").unwrap();
    assert_eq!((no, count), (1, 1));
    assert_eq!(shown_content(&storage, session, &message), "第一版");

    let list = storage.list_message_versions(&message).unwrap();
    assert_eq!((list.count, list.active), (1, 1));
    assert!(!list.versions[0].edited_by_tool);
    assert_eq!(list.versions[0].view, VersionView::Raw);
}

#[test]
fn switching_view_moves_content_between_raw_and_edited() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "原文 A").unwrap();
    storage.save_generated_version(&message, "原文 B").unwrap();

    // 只有第 2 版被工具改过。
    storage
        .mark_version_edited(&message, 2, "编辑后 B")
        .unwrap();
    let list = storage.list_message_versions(&message).unwrap();
    let second = list.versions.iter().find(|v| v.version_no == 2).unwrap();
    assert!(second.edited_by_tool);
    assert_eq!(second.view, VersionView::Raw, "编辑过 ≠ 正在看编辑版");

    let shown = storage
        .switch_message_version(&message, 2, VersionView::Edited)
        .unwrap();
    assert_eq!(shown, "编辑后 B");
    assert_eq!(shown_content(&storage, session, &message), "编辑后 B");

    let shown = storage
        .switch_message_version(&message, 2, VersionView::Raw)
        .unwrap();
    assert_eq!(shown, "原文 B");
    assert_eq!(shown_content(&storage, session, &message), "原文 B");

    // 没被编辑过的版本，请求"编辑后"应当降级成原文，而不是报错。
    let shown = storage
        .switch_message_version(&message, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(shown, "原文 A");
}

#[test]
fn switching_version_switches_content() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "一").unwrap();
    storage.save_generated_version(&message, "二").unwrap();
    storage.save_generated_version(&message, "三").unwrap();

    assert_eq!(
        storage
            .switch_message_version(&message, 1, VersionView::Raw)
            .unwrap(),
        "一"
    );
    assert_eq!(storage.list_message_versions(&message).unwrap().active, 1);

    assert_eq!(
        storage
            .switch_message_version(&message, 3, VersionView::Raw)
            .unwrap(),
        "三"
    );
    assert_eq!(shown_content(&storage, session, &message), "三");
}

#[test]
fn deleting_a_non_active_version_keeps_active_content() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "一").unwrap();
    storage.save_generated_version(&message, "二").unwrap();
    storage.save_generated_version(&message, "三").unwrap();

    let (count, active, shown) = storage.delete_message_version(&message, 1).unwrap();
    assert_eq!(count, 2);
    assert_eq!(active, 2, "删掉前面的版本，当前版号往前挪一位");
    assert_eq!(shown, "三");
    assert_eq!(shown_content(&storage, session, &message), "三");

    // 编号压成 1..=2，不留空洞（UI 的 n/n 不该看到空号）。
    let numbers: Vec<i64> = storage
        .list_message_versions(&message)
        .unwrap()
        .versions
        .iter()
        .map(|v| v.version_no)
        .collect();
    assert_eq!(numbers, vec![1, 2]);
}

#[test]
fn deleting_the_active_version_falls_back() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "一").unwrap();
    storage.save_generated_version(&message, "二").unwrap();
    storage.save_generated_version(&message, "三").unwrap();

    let (count, active, shown) = storage.delete_message_version(&message, 3).unwrap();
    assert_eq!((count, active), (2, 2));
    assert_eq!(shown, "二");
    assert_eq!(shown_content(&storage, session, &message), "二");
}

#[test]
fn refusing_to_delete_the_last_version() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, _, message) = fixture(dir.path());
    storage.save_generated_version(&message, "唯一").unwrap();

    let error = storage.delete_message_version(&message, 1).unwrap_err();
    assert!(format!("{error}").contains("只剩一版"), "实际错误：{error}");
    assert_eq!(storage.list_message_versions(&message).unwrap().count, 1);
}

/// D151：`0` 是**清空**，不是"留当前版"。
///
/// 用户 2026-10-26 的原话：「删除所有版本还是会保留当前版本，不对」——
/// 他要的是把"版本"这件事撤销掉（`n/n` 与颜色一起消失），正文一个字节不动。
#[test]
fn delete_all_versions_clears_every_row_and_keeps_the_displayed_text() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "一").unwrap();
    storage.save_generated_version(&message, "二").unwrap();
    storage.save_generated_version(&message, "三").unwrap();
    storage
        .switch_message_version(&message, 2, VersionView::Raw)
        .unwrap();

    let (count, active, shown) = storage.delete_message_version(&message, 0).unwrap();
    assert_eq!((count, active), (0, 0), "清空之后没有「当前版」这回事");
    assert_eq!(shown, "二");
    assert_eq!(shown_content(&storage, session, &message), "二", "正文不动");
    assert_eq!(
        storage
            .list_message_versions(&message)
            .unwrap()
            .versions
            .len(),
        0,
        "版本行一条不剩"
    );
    // 列表里不该再有版本条目：`n/n` 与颜色一起消失。
    assert!(storage
        .version_index(session)
        .unwrap()
        .get(&message.to_string())
        .is_none());
}

#[test]
fn version_cap_drops_the_oldest_but_never_the_current() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    for index in 1..=(MAX_VERSIONS + 1) {
        storage
            .save_generated_version(&message, &format!("第 {index} 版"))
            .unwrap();
    }
    let list = storage.list_message_versions(&message).unwrap();
    assert_eq!(list.count, MAX_VERSIONS);
    assert_eq!(list.active, MAX_VERSIONS);
    assert_eq!(
        shown_content(&storage, session, &message),
        format!("第 {} 版", MAX_VERSIONS + 1)
    );
    let numbers: Vec<i64> = list.versions.iter().map(|v| v.version_no).collect();
    assert_eq!(numbers.len() as i64, MAX_VERSIONS);
    assert_eq!(numbers.first(), Some(&1));
    assert_eq!(numbers.last(), Some(&MAX_VERSIONS));
}

#[test]
fn sending_uses_whatever_the_ui_shows() {
    // 防"永远发原文"这个最隐蔽的错：发出去的是**当前显示的那一组**。
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "原文").unwrap();
    storage.mark_version_edited(&message, 1, "编辑后").unwrap();

    // UI 上是"蓝"（显示原文）→ 发出去的应当是原文。
    storage
        .switch_message_version(&message, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(shown_content(&storage, session, &message), "原文");

    // 点一下数字变"红"（显示编辑后）→ 发出去的应当是编辑后。
    storage
        .switch_message_version(&message, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(shown_content(&storage, session, &message), "编辑后");
}

#[test]
fn reopens_with_versions_intact() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let session;
    let message;
    {
        let mut storage = ready(&path);
        session = storage.create_conversation("重启测试", "device-a").unwrap();
        storage
            .append_unit(session, &assistant_unit(session, 1, "原文"))
            .unwrap();
        message = MessageId::new(session, 1);
        storage.save_generated_version(&message, "第一版").unwrap();
        storage.save_generated_version(&message, "第二版").unwrap();
    }
    let storage = ready(&path);
    let list = storage.list_message_versions(&message).unwrap();
    assert_eq!((list.count, list.active), (2, 2));
    assert_eq!(shown_content(&storage, session, &message), "第二版");
}

#[test]
fn remembered_view_survives_switching_away_and_back() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, _, message) = fixture(dir.path());
    storage.save_generated_version(&message, "一").unwrap();
    storage.save_generated_version(&message, "二").unwrap();
    storage.mark_version_edited(&message, 2, "二·改").unwrap();

    // 第 2 版切到"编辑后"。
    storage
        .switch_message_version(&message, 2, VersionView::Edited)
        .unwrap();
    // 翻到第 1 版（沿用第 1 版记住的视图 = raw）。
    assert_eq!(
        storage
            .switch_message_version_remembered(&message, 1)
            .unwrap(),
        "一"
    );
    // 再翻回第 2 版：**应当还是编辑后**，不能被第 1 版的 raw 带跑。
    assert_eq!(
        storage
            .switch_message_version_remembered(&message, 2)
            .unwrap(),
        "二·改"
    );
}

#[test]
fn version_index_only_lists_messages_that_have_versions() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    // 再放一条没有版本的消息。
    storage
        .append_unit(session, &assistant_unit(session, 2, "第二条"))
        .unwrap();
    storage.save_generated_version(&message, "第一版").unwrap();
    storage.save_generated_version(&message, "第二版").unwrap();

    let index = storage.version_index(session).unwrap();
    assert_eq!(index.len(), 1, "没版本的消息不该出现在索引里");
    let brief = index.get(&message.to_string()).expect("应当有这条");
    assert_eq!(brief.count, 2);
    assert_eq!(brief.active, 2);
    assert_eq!(brief.view, VersionView::Raw);
    assert!(!brief.edited_by_tool);
}
/// 用户编辑（D148）：**改内容，不改颜色**。
#[test]
fn user_edit_does_not_mark_tool_edited() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, _session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "原文").unwrap();

    storage
        .user_edit_version(&message, 1, "用户改过的字")
        .unwrap();

    let list = storage.list_message_versions(&message).unwrap();
    assert!(
        !list.versions[0].edited_by_tool,
        "用户编辑不许把这一版标成工具改过"
    );
    assert!(list.versions[0].user_edited, "但要留下\"用户改过\"这个标记");
    assert_eq!(
        shown_content(&storage, _session, &message),
        "用户改过的字",
        "当前显示的就是用户改完的那份"
    );
    // 视图没被改成 edited——颜色语义靠它，动了就全乱
    assert_eq!(list.versions[0].view, VersionView::Raw);
}

/// 用户编辑与工具编辑各管一层：改原文不影响\"AI 改后\"，反之亦然。
#[test]
fn user_edit_and_tool_edit_live_in_different_layers() {
    let dir = tempfile::tempdir().unwrap();
    let (mut storage, session, message) = fixture(dir.path());
    storage.save_generated_version(&message, "原文").unwrap();

    // 工具改一次：这一版变成\"被 AI 改过\"，并且切到 edited
    storage.mark_version_edited(&message, 1, "AI 改后").unwrap();
    storage
        .switch_message_version(&message, 1, VersionView::Edited)
        .unwrap();

    // 用户改当前这一组（edited）
    storage
        .user_edit_version(&message, 1, "用户在 AI 版上改的")
        .unwrap();
    assert_eq!(
        shown_content(&storage, session, &message),
        "用户在 AI 版上改的"
    );

    // 切回原文：看到的是 raw，不该出现用户的改写
    let raw = storage
        .switch_message_version(&message, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(raw, "原文");

    // 再切回 edited：用户改的那份还在
    let edited = storage
        .switch_message_version(&message, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(edited, "用户在 AI 版上改的");

    let list = storage.list_message_versions(&message).unwrap();
    assert!(list.versions[0].edited_by_tool, "工具改过就是改过");
    assert!(list.versions[0].user_edited);
}
