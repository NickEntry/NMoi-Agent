//! 存储层往返测试：会话 / 单元 / 归档 / 分界线 / 编辑审计 / 备份。

use nmoi_core::archive::{ArchiveEntry, Origin};

use nmoi_core::clock::LogicalClock;
use nmoi_core::divider::{Divider, DividerKind};
use nmoi_core::edit::{apply_edit, BlockSelector, EditRequest};
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, OutgoingToolCall, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => {
            panic!("被语义校验拦下: {:?}", blocked.report().issues)
        }
    }
}

fn user_unit(session: SessionId, seq: u64, text: &str) -> AtomicUnit {
    let id = MessageId::new(session, seq);
    let message = Message::text(
        id,
        Role::User,
        SourceTag::History,
        text,
        text.len() as u32,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    AtomicUnit::turn(message).unwrap()
}

#[test]
fn conversation_and_units_roundtrip() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut storage = ready(&path);

    let session = storage.create_conversation("测试会话", "device-a").unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "你好"))
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 2, "第二条"))
        .unwrap();

    let units = storage.load_units(session).unwrap();
    assert_eq!(units.len(), 2);
    assert_eq!(units[0].messages[0].content, "你好");
    assert_eq!(storage.message_count(session).unwrap(), 2);

    // 重开后数据仍在。
    drop(storage);
    let storage = ready(&path);
    assert_eq!(storage.load_units(session).unwrap().len(), 2);
}

#[test]
fn incomplete_turns_keep_their_flag_across_a_roundtrip() {
    // 断流的回复必须能被认出来：否则它和正常回复在数据里长得一模一样。
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("断流", "device-a").unwrap();

    let id = MessageId::new(session, 1);
    let message = Message::text(
        id,
        Role::Assistant,
        SourceTag::History,
        "说到一半就没了",
        6,
        1_700_000_000_000,
        LogicalClock::new(1),
    )
    .mark_incomplete(true);
    let unit = AtomicUnit::turn(message).unwrap();
    storage.append_unit(session, &unit).unwrap();

    // 同一个连接内读回
    assert!(storage.load_units(session).unwrap()[0].messages[0].incomplete);
    // 重开之后再读（真的落盘了）
    drop(storage);
    let storage = ready(&path);
    let units = storage.load_units(session).unwrap();
    assert!(units[0].messages[0].incomplete, "重开库之后标记不能丢");
}

#[test]
fn tool_pair_with_results_roundtrips() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage.create_conversation("工具", "device-a").unwrap();

    let head = Message::tool_call_message(
        MessageId::new(session, 1),
        vec![OutgoingToolCall::new("c1", "read_file", "{\"path\":\"a\"}")],
        3,
        1_700_000_000_000,
        LogicalClock::new(1),
    );
    let mut unit = AtomicUnit::tool_pair(head).unwrap();
    unit.add_result(
        Message::tool_result(
            MessageId::new(session, 2),
            "c1",
            "contents",
            1,
            1_700_000_000_001,
            LogicalClock::new(2),
        )
        .with_unit(unit.id.clone()),
    )
    .unwrap();
    storage.append_unit(session, &unit).unwrap();

    let loaded = storage.load_unit(&unit.id).unwrap();
    assert!(loaded.is_closed());
    assert_eq!(loaded.messages.len(), 2);
    assert_eq!(loaded.messages[1].tool_call_id.as_deref(), Some("c1"));
}

#[test]
fn archive_is_idempotent_and_deterministic() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage.create_conversation("归档", "device-a").unwrap();
    let message = MessageId::new(session, 1);

    let (entry, redaction) = ArchiveEntry::capture(
        "token ghp_abcdefghijklmnopqrstuvwxyz0123456789",
        SourceTag::ToolResult,
        Origin::new(session, message, 1),
        12,
        1_700_000_000_000,
    );
    assert_eq!(redaction.hits.len(), 1);
    let rf = storage.put_archive(session, entry.clone()).unwrap();
    // 重复写入是幂等的。
    assert_eq!(storage.put_archive(session, entry.clone()).unwrap(), rf);

    let fetched = storage.fetch_archive(&rf).unwrap();
    assert!(!fetched.content.contains("ghp_"));
    assert!(fetched.verify_hash());

    let hits = storage.search_archive("token", 10).unwrap();
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].rf, rf);

    let stat = storage.archive_stat().unwrap();
    assert_eq!(stat.entries, 1);
    assert_eq!(stat.largest_ref, Some(rf));
}

#[test]
fn dividers_can_be_inserted_listed_and_deleted() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage.create_conversation("分界线", "device-a").unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "a"))
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 2, "b"))
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 3, "c"))
        .unwrap();

    let units = storage.load_units(session).unwrap();
    let first_last_seq = units[0].last_message().id.seq();
    let divider = Divider::user(session, first_last_seq, 1_700_000_000_000);
    storage.insert_divider(&divider).unwrap();

    let dividers = storage.load_dividers(session).unwrap();
    assert_eq!(dividers.len(), 1);
    assert_eq!(dividers[0].kind, DividerKind::User);
    assert_eq!(dividers[0].after_seq, 1);

    // 窗口只剩线下两段。
    let window = nmoi_core::divider::active_window(&units, &dividers);
    assert_eq!(window.len(), 2);

    assert!(storage.delete_divider(&divider.id).unwrap());
    assert!(storage.load_dividers(session).unwrap().is_empty());
    // 删线后窗口恢复完整。
    let restored = nmoi_core::divider::active_window(&units, &[]);
    assert_eq!(restored.len(), 3);
}

#[test]
fn edits_are_persisted_with_audit() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage.create_conversation("编辑", "device-a").unwrap();
    let mut unit = user_unit(session, 1, "原文");
    storage.append_unit(session, &unit).unwrap();

    let request = EditRequest::new(unit.id.clone(), BlockSelector::Output, "改过的正文");
    let outcome = apply_edit(&mut unit, &request).unwrap();
    storage.save_message_edit(session, &unit, &outcome).unwrap();

    let loaded = storage.load_unit(&unit.id).unwrap();
    assert_eq!(loaded.messages[0].content, "改过的正文");
    assert_eq!(loaded.messages[0].revision, 1);
    assert_eq!(storage.edit_count(session).unwrap(), 1);
}

#[test]
fn locked_flag_survives_and_blocks_editing() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage.create_conversation("锁定", "device-a").unwrap();
    let mut unit = user_unit(session, 1, "别动我");
    storage.append_unit(session, &unit).unwrap();

    let id = unit.messages[0].id.clone();
    nmoi_core::edit::set_locked(&mut unit, &id, true).unwrap();
    storage.set_message_locked(&id, true).unwrap();

    let loaded = storage.load_unit(&unit.id).unwrap();
    assert!(loaded.messages[0].locked);

    let request = EditRequest::new(unit.id.clone(), BlockSelector::Output, "试图修改");
    assert!(apply_edit(&mut unit, &request).is_err());
}

#[test]
fn backup_is_verified_and_openable() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("备份", "device-a").unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "data"))
        .unwrap();

    let dest = dir.path().join("backup/app.db");
    let report = storage.backup(&dest).unwrap();
    assert!(report.verified, "{:?}", report.problems);

    let restored = ready(&dest);
    assert_eq!(restored.load_units(session).unwrap().len(), 1);
}

/// 压缩落库：归档 + 摘要 + 分界线，一个事务（D45）。
#[test]
fn compression_commits_archive_and_divider_together() {
    use nmoi_core::archive::MemoryArchive;
    use nmoi_core::compress::{
        CompressionJob, CompressionLimits, DRAFT_APPEND_TOOL, DRAFT_DONE_TOOL,
    };

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("compress.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("压缩", "device").unwrap();

    let units = vec![
        user_unit(session, 1, "改 crates/core/src/runtime.rs 的重试"),
        user_unit(session, 2, "跑 cargo test"),
        user_unit(session, 3, "继续"),
        user_unit(session, 4, "再来"),
    ];
    for unit in &units {
        storage.append_unit(session, unit).unwrap();
    }
    let mut job = CompressionJob::start(session, &units, 10, 0, CompressionLimits::default())
        .expect("该有可压的内容");
    job.handle(
        DRAFT_APPEND_TOOL,
        "{\"text\":\"改了 crates/core/src/runtime.rs 的重试，跑了 cargo test\"}",
    )
    .unwrap();
    job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
    // 先提交到内存归档拿到 outcome（平台侧会换成 storage 自己的事务）
    let mut scratch = MemoryArchive::new();
    let outcome = job.commit(&mut scratch, 1_700_000_100_000).unwrap();

    storage.commit_compression(session, &outcome).unwrap();

    // 归档条目数 = 线上消息数 + 摘要
    assert_eq!(
        storage.archive_len().unwrap(),
        outcome.entries.len() + 1,
        "每条线上消息 + 摘要都要落库"
    );
    assert!(storage.fetch_archive(&outcome.summary_ref).is_ok());
    let dividers = storage.load_dividers(session).unwrap();
    assert_eq!(dividers.len(), 1);
    assert_eq!(dividers[0].kind, DividerKind::Compression);
    assert_eq!(dividers[0].after_seq, outcome.divider.after_seq);

    // 重复提交幂等（崩溃后重试就是这么走的）：不该多出条目，也不该多出一条线
    storage.commit_compression(session, &outcome).unwrap();
    assert_eq!(storage.archive_len().unwrap(), outcome.entries.len() + 1);
    assert_eq!(storage.load_dividers(session).unwrap().len(), 1);
}

/// 分界线写不进去时，归档也必须一起回滚——否则会留下"有归档没线"的残局。
#[test]
fn compression_commit_rolls_back_archive_when_divider_fails() {
    use nmoi_core::archive::MemoryArchive;
    use nmoi_core::compress::{
        CompressionJob, CompressionLimits, DRAFT_APPEND_TOOL, DRAFT_DONE_TOOL,
    };

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("rollback.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("回滚", "device").unwrap();
    let units = vec![
        user_unit(session, 1, "改 crates/core/src/runtime.rs"),
        user_unit(session, 2, "好"),
        user_unit(session, 3, "继续"),
        user_unit(session, 4, "嗯"),
    ];
    for unit in &units {
        storage.append_unit(session, unit).unwrap();
    }
    let mut job =
        CompressionJob::start(session, &units, 10, 0, CompressionLimits::default()).unwrap();
    job.handle(
        DRAFT_APPEND_TOOL,
        "{\"text\":\"改了 crates/core/src/runtime.rs\"}",
    )
    .unwrap();
    job.handle(DRAFT_DONE_TOOL, "{}").unwrap();
    let mut scratch = MemoryArchive::new();
    let outcome = job.commit(&mut scratch, 1_700_000_200_000).unwrap();

    // 换一个库里没有的会话：分界线写不进去（外键），整个事务必须回滚
    let ghost = SessionId::new();
    let err = storage.commit_compression(ghost, &outcome);
    assert!(err.is_err(), "不存在的会话应当失败");
    assert_eq!(storage.archive_len().unwrap(), 0, "归档必须跟着回滚");
    assert_eq!(storage.load_dividers(session).unwrap().len(), 0);
}

/// 备份替换旧文件：旧备份整体被换掉、不留临时文件、不留旁路文件（D48）。
#[test]
fn backup_replaces_old_file_and_clears_sidecars() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("备份", "device").unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "一条消息"))
        .unwrap();

    let dest = dir.path().join("backup.db");
    // 旧备份（内容不是合法数据库）+ 一个遗留的 -wal 旁路文件
    std::fs::write(&dest, "这不是数据库").unwrap();
    let sidecar = format!("{}-wal", dest.display());
    std::fs::write(&sidecar, "遗留 WAL").unwrap();

    let report = storage.backup(&dest).unwrap();
    assert!(report.verified, "{:?}", report.problems);
    assert!(!path.with_extension("db.tmp").exists());
    let tmp = format!("{}.tmp", dest.display());
    assert!(!std::path::Path::new(&tmp).exists(), "临时文件不该留下");
    assert!(!std::path::Path::new(&sidecar).exists(), "旧旁路文件该清掉");

    // 备份目录里不该剩任何 `.tmp` 相关的东西——包括临时库自己的 `-wal` / `-shm`。
    // 这一条是 C 侧 FFI 验收（`scripts/ffi_smoke.sh`）在列目录时抓到的：
    // rename 只搬主文件，`backup.db.tmp-wal` 会顶着一个已经不存在的主文件名留在原地。
    let leftovers: Vec<String> = std::fs::read_dir(dir.path())
        .unwrap()
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .filter(|name| name.contains(".tmp"))
        .collect();
    assert!(leftovers.is_empty(), "备份目录里留下残骸: {leftovers:?}");

    // 新备份是真的数据库，能打开、能读到那条消息
    let reopened = match Storage::open(&dest).unwrap() {
        OpenOutcome::Ready(store) => store,
        OpenOutcome::Blocked(blocked) => {
            panic!("备份被语义校验拦下: {:?}", blocked.report().issues)
        }
    };
    assert_eq!(reopened.message_count(session).unwrap(), 1);
}

/// 会话列表（D61）：排序稳、分页说实话、预览截断且标明它被截过。
#[test]
fn conversation_list_orders_by_recent_activity_and_pages_with_has_more() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("list.db");
    let mut storage = ready(&path);

    // 两次写之间留一点真实的毫秒：`updated_at` 来自墙上时钟，同一毫秒内的先后
    // 只能靠 id 兜底（UUIDv7 的毫秒内部分**是随机的**，不保证等于创建顺序）。
    // 想验"最近动过排最前"，就得让时间真的走过去——Windows 的 CI 抓到过这条。
    let older = storage.create_conversation("旧的", "device").unwrap();
    storage
        .append_unit(older, &user_unit(older, 1, "先聊一句"))
        .unwrap();
    std::thread::sleep(std::time::Duration::from_millis(5));
    let newer = storage.create_conversation("新的", "device").unwrap();
    let long = "长".repeat(300);
    storage
        .append_unit(newer, &user_unit(newer, 1, &long))
        .unwrap();

    let page = storage.list_conversations(10, 0).unwrap();
    assert_eq!(page.items.len(), 2);
    assert!(!page.has_more, "两条都在第一页，没有更多");
    assert_eq!(page.items[0].id, newer, "最近动过的排最前");
    assert_eq!(page.items[0].messages, 1);
    assert_eq!(page.items[0].last_seq, Some(1));
    assert_eq!(page.items[0].last_role.as_deref(), Some("user"));
    assert!(page.items[0].last_text_truncated);
    let preview = page.items[0].last_text.as_deref().unwrap();
    assert_eq!(preview.chars().count(), 201, "200 字符 + 省略号");
    assert_eq!(page.items[1].title, "旧的");
    assert!(!page.items[1].last_text_truncated);

    let first = storage.list_conversations(1, 0).unwrap();
    assert_eq!(first.items.len(), 1);
    assert!(first.has_more, "还有一页");
    let second = storage.list_conversations(1, 1).unwrap();
    assert_eq!(second.items.len(), 1);
    assert!(!second.has_more);
    assert_eq!(second.items[0].id, older);
}

/// 会话编排可以直接拿 `Storage` 当历史存储用（D52）。
#[test]
fn storage_implements_the_history_store_trait() {
    use nmoi_core::session::HistoryStore;

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("history.db");
    let mut storage = ready(&path);
    let session = storage.create_conversation("编排", "device").unwrap();

    let unit = user_unit(session, 1, "在吗");
    HistoryStore::append_unit(&mut storage, session, &unit).unwrap();
    let loaded = HistoryStore::load_units(&storage, session).unwrap();
    assert_eq!(loaded.len(), 1);
    assert_eq!(loaded[0].messages[0].content, "在吗");
}

/// D89：一轮跑自己的连接，两条连接同时开着也要互相看得见。
///
/// 这条替代了"整轮把句柄借走"的旧做法：借走之后同一个库上的所有调用都吃 `busy`，
/// 于是 A 会话在跑，B 会话连列表都刷不了。
#[test]
fn two_connections_see_each_others_writes() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut main = ready(&path);
    let session = main.create_conversation("并发", "device-a").unwrap();
    main.append_unit(session, &user_unit(session, 1, "一"))
        .unwrap();

    // 第二条连接 = "正在跑的那一轮"
    let mut second = Storage::attach(&path).expect("attach 成功");
    second
        .append_unit(session, &user_unit(session, 2, "二"))
        .unwrap();

    // 主连接立刻看得见（WAL：读不挡写，提交后可见）
    assert_eq!(main.load_units(session).unwrap().len(), 2);
    // 两个连接的 device_id 是同一个库的
    assert_eq!(main.device_id(), second.device_id());

    // 反过来也成立
    main.append_unit(session, &user_unit(session, 3, "三"))
        .unwrap();
    assert_eq!(second.load_units(session).unwrap().len(), 3);
}

/// attach 不做 L0/L1/L3：库不存在要直说，别造一个空库出来。
#[test]
fn attach_refuses_a_missing_file() {
    let dir = tempfile::tempdir().unwrap();
    let missing = dir.path().join("nope.db");
    assert!(Storage::attach(&missing).is_err());
    assert!(!missing.exists(), "attach 不该顺手建库");
}

#[test]
fn conversation_model_is_null_until_set_and_survives_reopen() {
    // D101：模型名是"这个会话现在用哪个"——没跑过就必须是 NULL，
    // 不能是空串，否则面板上"未知"和"模型叫空串"分不开。
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");

    let session = {
        let mut storage = ready(&path);
        let session = storage
            .create_conversation("模型的会话", "device-a")
            .unwrap();
        assert_eq!(storage.conversation_model(session).unwrap(), None);

        storage
            .set_conversation_model(session, "vendor/model-a")
            .unwrap();
        assert_eq!(
            storage.conversation_model(session).unwrap().as_deref(),
            Some("vendor/model-a")
        );

        let page = storage.list_conversations(10, 0).unwrap();
        assert_eq!(page.items.len(), 1);
        assert_eq!(
            page.items[0].model.as_deref(),
            Some("vendor/model-a"),
            "列表页要带上模型名，UI 会话头直接用"
        );
        session
    };

    // 重开：换模型不该被会话活动覆盖，也不该在重启后丢
    let storage = ready(&path);
    assert_eq!(
        storage.conversation_model(session).unwrap().as_deref(),
        Some("vendor/model-a")
    );
}

#[test]
fn setting_a_model_on_a_missing_conversation_is_an_error() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let storage = ready(&path);
    // 拿一个不存在的 id：不能静默地"顺手建一个会话"
    let missing = nmoi_core::ids::SessionId::new();
    assert!(storage.set_conversation_model(missing, "m").is_err());
    assert!(storage.conversation_model(missing).is_err());
}
