//! 单元级编辑（0.15f / D188）落库的四个判据。
//!
//! 1. **正文改写落红**：`messages.content` 是改后那份，原文还在 `origin_message` 里，
//!    版本控件出现（`<1/1>` 的由来），切回蓝能看见原文；
//! 2. **工具对成对删**：调用与结果两条行一起消失，原文留在 undo 日志里；
//! 3. **单边删被拒**：库一点不动（不是"删了一半再报错"）；
//! 4. **每会话上限**：到 50 处就拒绝，别让模型把历史改成筛子。

use nmoi_core::clock::LogicalClock;
use nmoi_core::edit::{BlockOp, BlockSelector};
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, OutgoingToolCall, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::blockedit::MAX_OPS_PER_SESSION;
use nmoi_storage::versions::VersionView;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn seed_turn(storage: &mut Storage, body: &str) -> (SessionId, MessageId) {
    let session = storage
        .create_conversation("单元编辑", "device-a", "default")
        .unwrap();
    let message = Message::text(
        MessageId::new(session, 1),
        Role::Assistant,
        SourceTag::History,
        body,
        1,
        1_700_000_000_000,
        LogicalClock::new(1),
    );
    storage
        .append_unit(session, &AtomicUnit::turn(message.clone()).unwrap())
        .unwrap();
    (session, message.id)
}

/// 一条调用 + 它的结果（已闭合）。`append_unit` 一次写全，否则库里的单元是开口的。
fn seed_pair(storage: &mut Storage) -> (SessionId, MessageId) {
    let session = storage
        .create_conversation("工具对", "device-a", "default")
        .unwrap();
    let head = Message::tool_call_message(
        MessageId::new(session, 1),
        vec![OutgoingToolCall::new(
            "call-1",
            "read_file",
            "{\"path\":\"a\"}",
        )],
        5,
        1_700_000_000_000,
        LogicalClock::new(1),
    );
    let mut unit = AtomicUnit::tool_pair(head.clone()).unwrap();
    let unit_id = unit.id.clone();
    let result = Message::tool_result(
        MessageId::new(session, 2),
        "call-1",
        "文件内容 a",
        3,
        1_700_000_000_001,
        LogicalClock::new(2),
    )
    .with_unit(unit_id);
    unit.add_result(result).unwrap();
    storage.append_unit(session, &unit).unwrap();
    (session, head.id)
}

fn all_messages(storage: &Storage, session: SessionId) -> Vec<Message> {
    storage
        .load_units(session)
        .unwrap()
        .iter()
        .flat_map(|unit| unit.messages.iter().cloned())
        .collect()
}

#[test]
fn red_output_edit_keeps_the_original_and_shows_the_new_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_turn(&mut storage, "原来的正文");

    let outcome = storage
        .apply_unit_edits(
            &id,
            &[BlockOp::Set {
                block: BlockSelector::Output,
                content: "改成这样".into(),
            }],
        )
        .unwrap();
    assert_eq!(outcome.written, 1);
    assert_eq!(outcome.blocks, vec!["正文".to_string()]);
    assert!(outcome.unchanged.is_empty());

    // 屏幕上（= 库里 content）是改后那份。
    assert_eq!(all_messages(&storage, session)[0].content, "改成这样");

    // 版本控件出现，而且红的那一份就是改后：这就是 `<1/1>` 的由来。
    let versions = storage.list_message_versions(&id).unwrap();
    assert_eq!(versions.count, 1);
    assert_eq!(versions.shown, "改成这样");
    assert!(versions.versions[0].edited_by_tool);
    assert_eq!(versions.versions[0].view, VersionView::Edited);

    // 原文没丢：切回蓝看得见。
    let raw = storage
        .switch_message_version(&id, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(raw, "原来的正文");
    // 切回来又是改后那份。
    let edited = storage
        .switch_message_version(&id, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(edited, "改成这样");
}

#[test]
fn undo_log_records_the_original_text_per_change() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_turn(&mut storage, "改之前");

    storage
        .apply_unit_edits(
            &id,
            &[BlockOp::Set {
                block: BlockSelector::Output,
                content: "改之后".into(),
            }],
        )
        .unwrap();

    let log = storage.undo_log(session).unwrap();
    assert_eq!(log.len(), 1);
    let (_, record) = &log[0];
    assert_eq!(record.before, "改之前", "undo 存的是原文，不是哈希");
    assert_eq!(record.after, "改之后");
    assert_eq!(record.message, id.to_string());
    assert!(!record.deleted);
    assert_eq!(storage.undo_count(session).unwrap(), 1);
    // 审计表也留了一行：undo 日志和 edits 是两份账，不能只记一份。
    assert_eq!(storage.edit_count(session).unwrap(), 1);
}

#[test]
fn deleting_a_tool_pair_removes_both_rows_and_keeps_the_text() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_pair(&mut storage);
    assert_eq!(all_messages(&storage, session).len(), 2);

    let outcome = storage
        .apply_unit_edits(
            &id,
            &[
                BlockOp::Delete {
                    block: BlockSelector::ToolCall { index: 0 },
                },
                BlockOp::Delete {
                    block: BlockSelector::ToolResult { index: 0 },
                },
            ],
        )
        .unwrap();
    assert_eq!(outcome.removed, 2);

    assert!(
        all_messages(&storage, session).is_empty(),
        "调用与结果都要消失（留一条就是孤儿）"
    );

    let log = storage.undo_log(session).unwrap();
    assert_eq!(log.len(), 2);
    assert!(log.iter().all(|(_, record)| record.deleted));
    let texts: Vec<&str> = log
        .iter()
        .map(|(_, record)| record.before.as_str())
        .collect();
    assert!(texts.contains(&"文件内容 a"), "{texts:?}");
    assert!(texts.contains(&"{\"path\":\"a\"}"), "{texts:?}");
}

#[test]
fn half_deleted_tool_block_is_refused_and_nothing_changes() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_pair(&mut storage);

    let error = storage
        .apply_unit_edits(
            &id,
            &[BlockOp::Delete {
                block: BlockSelector::ToolCall { index: 0 },
            }],
        )
        .unwrap_err();
    assert!(
        error.to_string().contains("成对"),
        "报错要说清是成对的问题：{error}"
    );

    // 一条都没动：不是"删了一半再报错"。
    assert_eq!(all_messages(&storage, session).len(), 2);
    assert_eq!(storage.undo_count(session).unwrap(), 0);
    assert_eq!(storage.edit_count(session).unwrap(), 0);
}

#[test]
fn one_bad_op_leaves_the_whole_batch_unwritten() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_turn(&mut storage, "正文");

    let error = storage
        .apply_unit_edits(
            &id,
            &[
                BlockOp::Set {
                    block: BlockSelector::Output,
                    content: "新正文".into(),
                },
                // 这条消息没有思考块？删思考是合法的；换成"不存在的结果"来炸整批。
                BlockOp::Set {
                    block: BlockSelector::ToolResult { index: 0 },
                    content: "x".into(),
                },
            ],
        )
        .unwrap_err();
    assert!(error.to_string().contains("工具"), "{error}");

    assert_eq!(all_messages(&storage, session)[0].content, "正文");
    assert_eq!(storage.undo_count(session).unwrap(), 0);
}

#[test]
fn per_session_cap_stops_at_the_limit() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_turn(&mut storage, "起点");

    for index in 0..MAX_OPS_PER_SESSION {
        storage
            .apply_unit_edits(
                &id,
                &[BlockOp::Set {
                    block: BlockSelector::Output,
                    content: format!("第 {index} 次"),
                }],
            )
            .unwrap();
    }
    assert_eq!(storage.undo_count(session).unwrap(), MAX_OPS_PER_SESSION);

    let error = storage
        .apply_unit_edits(
            &id,
            &[BlockOp::Set {
                block: BlockSelector::Output,
                content: "第 51 次".into(),
            }],
        )
        .unwrap_err();
    assert!(error.to_string().contains("上限"), "{error}");
    assert_eq!(all_messages(&storage, session)[0].content, "第 49 次");
}
