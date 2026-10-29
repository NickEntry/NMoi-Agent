//! 块级编辑（D158）：增强编辑最后一步落地的地方。
//!
//! 这一层钉四件事：
//!
//! 1. **改哪一块就是哪一块**——改思考不动正文，改工具参数不动名字与 `call_id`；
//! 2. 改动落库之后**读得回来**（存了但读不到，界面上就等于没改）；
//! 3. 用户锁定过的消息**拒绝编辑**（D11 的那条锁）；
//! 4. 每次成功编辑在 `edits` 审计表里留一行（没有它，undo 与同步冲突都没有依据）。

use nmoi_core::clock::LogicalClock;
use nmoi_core::edit::BlockSelector;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, OutgoingToolCall, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

/// 一条带思考块的助手消息。
fn seed_thinking(storage: &mut Storage, body: &str, thinking: &str) -> (SessionId, MessageId) {
    let session = storage
        .create_conversation("块编辑", "device-a", "default")
        .unwrap();
    let message = Message::text(
        MessageId::new(session, 1),
        Role::Assistant,
        SourceTag::History,
        body,
        1,
        1_700_000_000_000,
        LogicalClock::new(1),
    )
    .with_reasoning(thinking);
    storage
        .append_unit(session, &AtomicUnit::turn(message.clone()).unwrap())
        .unwrap();
    (session, message.id)
}

/// 一个工具往返单元：`assistant(tool_calls) + tool_result`。
fn seed_tool_pair(storage: &mut Storage) -> (SessionId, MessageId) {
    let session = storage
        .create_conversation("工具块", "device-a", "default")
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
    storage
        .append_unit(session, &AtomicUnit::tool_pair(head.clone()).unwrap())
        .unwrap();
    (session, head.id)
}

fn last_message(storage: &Storage, session: SessionId, id: &MessageId) -> Message {
    storage
        .load_units(session)
        .unwrap()
        .iter()
        .flat_map(|unit| unit.messages.iter())
        .find(|message| &message.id == id)
        .cloned()
        .expect("消息还在")
}

#[test]
fn editing_thinking_leaves_the_body_alone() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_thinking(&mut storage, "正文", "原来的思考");

    let outcome = storage
        .apply_block_edit(&id, BlockSelector::Thinking, "换一个思路")
        .unwrap();
    assert_eq!(outcome.block, "thinking");
    assert_eq!(outcome.revision, 1);

    let message = last_message(&storage, session, &id);
    assert_eq!(message.reasoning.as_deref(), Some("换一个思路"));
    assert_eq!(message.content, "正文", "改思考不该动正文");
    assert_eq!(message.revision, 1);
    // 审计表里留了一行，否则 undo 与同步冲突都没有依据。
    assert_eq!(storage.edit_count(session).unwrap(), 1);
}

#[test]
fn editing_tool_arguments_keeps_the_name_and_the_call_id() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_tool_pair(&mut storage);

    storage
        .apply_block_edit(
            &id,
            BlockSelector::ToolCall { index: 0 },
            "{\"path\":\"b\"}",
        )
        .unwrap();

    let message = last_message(&storage, session, &id);
    assert_eq!(message.tool_calls.len(), 1);
    assert_eq!(message.tool_calls[0].arguments, "{\"path\":\"b\"}");
    // 名字决定下一轮怎么解释这一块，call_id 决定它跟哪条结果配对——都不许被编辑改掉。
    assert_eq!(message.tool_calls[0].name, "read_file");
    assert_eq!(message.tool_calls[0].id, "call-1");
}

#[test]
fn a_locked_message_refuses_content_edits() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_thinking(&mut storage, "正文", "思考");
    storage.set_message_locked(&id, true).unwrap();

    let error = storage
        .apply_block_edit(&id, BlockSelector::Thinking, "改不动")
        .unwrap_err();
    assert!(
        error.to_string().contains("锁定"),
        "错误信息要能让人看出是被锁了：{error}"
    );
    assert_eq!(storage.edit_count(session).unwrap(), 0, "拒绝的编辑不记账");
}

#[test]
fn unknown_message_and_unknown_tool_index_are_errors() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (_, id) = seed_tool_pair(&mut storage);

    let missing = MessageId::new(SessionId::new(), 99);
    assert!(storage
        .apply_block_edit(&missing, BlockSelector::Output, "x")
        .is_err());

    // 索引越界：不能"悄悄什么都没改"。
    assert!(storage
        .apply_block_edit(&id, BlockSelector::ToolCall { index: 3 }, "x")
        .is_err());
}

#[test]
fn an_edit_that_changes_nothing_is_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (_, id) = seed_thinking(&mut storage, "正文", "同样的思考");

    let error = storage
        .apply_block_edit(&id, BlockSelector::Thinking, "同样的思考")
        .unwrap_err();
    assert!(error.to_string().contains("没有变化"), "{error}");
}

/// 模型改消息（D177 / 0.14y）走的是**同一条**落库路：`HistoryStore::apply_block_edit`。
///
/// 这一条钉的是"编排层能不能真的改到库"：core 不依赖 storage，
/// 它只认 trait；trait 在 SQLite 这边必须有实现，默认实现是拒绝的。
#[test]
fn the_history_store_trait_can_write_a_block() {
    use nmoi_core::session::HistoryStore;

    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (session, id) = seed_thinking(&mut storage, "正文", "原来的思考");

    let outcome =
        HistoryStore::apply_block_edit(&mut storage, &id, BlockSelector::Output, "模型改过的正文")
            .expect("trait 这条路必须能写");
    assert_eq!(outcome.block, "output");
    assert_eq!(outcome.revision, 1);

    let message = last_message(&storage, session, &id);
    assert_eq!(message.content, "模型改过的正文");
    assert_eq!(message.reasoning.as_deref(), Some("原来的思考"), "只动正文");
    assert_eq!(storage.edit_count(session).unwrap(), 1, "审计表也要有一行");

    // 界面重读走的是 `load_units`：模型改完那一轮，消息区拿到的必须已经是新正文（铁律①）。
}
