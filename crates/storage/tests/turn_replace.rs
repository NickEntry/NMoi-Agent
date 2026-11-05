//! 整轮替换（D263）：重试换掉的是一整轮，不是最后那一条。
//!
//! 真机上的毛病是：重试完屏幕上"工具块是上一轮的、最后的思考是这一轮的"。
//! 原因不在渲染，在库：折行之后一轮回答是 N 条消息，重试只换了最后一条。
//!
//! 这一层钉四件事：
//!
//! 1. 换完之后 `load_units` 里只剩这一轮的 N 条，旧的一轮**一条都不留**；
//! 2. 旧的那一轮成为第 1 版，新的是第 2 版，`<2/2>` 能切回去；
//! 3. 切回去换的是**整轮**（旧的两条都回来），不是只换最后一条；
//! 4. 「允许生成新版本」关着时不记版——旧的那一轮直接丢。

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role, ToolStatus};
use nmoi_core::source::SourceTag;
use nmoi_core::OutgoingToolCall;
use nmoi_storage::versions::VersionView;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn user_unit(session: SessionId, seq: u64, text: &str) -> AtomicUnit {
    let message = Message::text(
        MessageId::new(session, seq),
        Role::User,
        SourceTag::History,
        text,
        text.len() as u32,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    AtomicUnit::turn(message).unwrap()
}

fn text_unit(session: SessionId, seq: u64, text: &str) -> AtomicUnit {
    let message = Message::text(
        MessageId::new(session, seq),
        Role::Assistant,
        SourceTag::History,
        text,
        text.len() as u32,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    AtomicUnit::turn(message).unwrap()
}

/// 一轮"调了工具"的消息：调用与结果都在同一条里（D258 折行）。
fn tool_unit(session: SessionId, seq: u64, call_id: &str, output: &str) -> AtomicUnit {
    let message = Message::tool_call_message(
        MessageId::new(session, seq),
        vec![OutgoingToolCall::new(call_id, "read_file", "{}")],
        1,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    let mut unit = AtomicUnit::tool_pair(message).unwrap();
    unit.set_output(call_id, output, ToolStatus::Ok).unwrap();
    unit
}

/// 库里现在这一轮长什么样：`(seq, 正文, 第一个调用的 id)`。
fn shown(storage: &Storage, session: SessionId) -> Vec<(u64, String, Option<String>)> {
    storage
        .load_units(session)
        .unwrap()
        .into_iter()
        .flat_map(|unit| unit.messages)
        .map(|message| {
            let call = message
                .tool_calls()
                .first()
                .map(|call| call.call_id.clone());
            (message.id.seq(), message.content(), call)
        })
        .collect()
}

#[test]
fn retry_replaces_the_whole_turn_and_keeps_the_old_one_as_a_version() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let session = storage
        .create_conversation("重试", "device-a", "default")
        .unwrap();
    let user = user_unit(session, 1, "跑十次");
    storage.append_unit(session, &user).unwrap();
    storage
        .append_unit(session, &tool_unit(session, 2, "c1", "第一次的结果"))
        .unwrap();
    storage
        .append_unit(session, &text_unit(session, 3, "旧的回答"))
        .unwrap();

    let fresh = vec![
        tool_unit(session, 2, "c9", "重试后的结果"),
        text_unit(session, 3, "新的回答"),
    ];
    let anchor = storage
        .replace_turn(session, &MessageId::new(session, 1), &fresh, true)
        .expect("整轮替换要能落库")
        .expect("有落点");
    assert_eq!(anchor, MessageId::new(session, 3));

    let now = shown(&storage, session);
    assert_eq!(now.len(), 3, "用户 1 条 + 这一轮 2 条：{now:?}");
    assert_eq!(now[1].2.as_deref(), Some("c9"), "工具块必须换成这一轮的");
    assert_eq!(now[1].1, "", "这一轮的工具消息本身没有正文");
    assert_eq!(now[2].1, "新的回答");

    let versions = storage.list_message_versions(&anchor).unwrap();
    assert_eq!(versions.count, 2, "旧那一轮留成第 1 版");
    assert_eq!(versions.active, 2);
    assert_eq!(versions.shown, "新的回答");
}

#[test]
fn switching_back_restores_the_whole_old_turn() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let session = storage
        .create_conversation("重试", "device-a", "default")
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "跑十次"))
        .unwrap();
    storage
        .append_unit(session, &tool_unit(session, 2, "c1", "第一次的结果"))
        .unwrap();
    storage
        .append_unit(session, &text_unit(session, 3, "旧的回答"))
        .unwrap();

    let fresh = vec![
        tool_unit(session, 2, "c9", "重试后的结果"),
        text_unit(session, 3, "新的回答"),
    ];
    let anchor = storage
        .replace_turn(session, &MessageId::new(session, 1), &fresh, true)
        .unwrap()
        .unwrap();

    // 切回第 1 版：工具块也要回去——只换正文的话，屏幕上就是"旧正文 + 新的工具"。
    let old = storage
        .switch_message_version(&anchor, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(old, "旧的回答");
    let back = shown(&storage, session);
    assert_eq!(back.len(), 3, "{back:?}");
    assert_eq!(back[1].2.as_deref(), Some("c1"), "工具块跟着版本走");
    assert_eq!(back[2].1, "旧的回答");

    // 再切回第 2 版：又变回重试后的那一轮。
    let again = storage
        .switch_message_version(&anchor, 2, VersionView::Raw)
        .unwrap();
    assert_eq!(again, "新的回答");
    let forward = shown(&storage, session);
    assert_eq!(forward[1].2.as_deref(), Some("c9"));
    assert_eq!(forward[2].1, "新的回答");
}

#[test]
fn a_shorter_retry_moves_the_version_control_to_the_new_last_message() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let session = storage
        .create_conversation("重试", "device-a", "default")
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "跑两次"))
        .unwrap();
    storage
        .append_unit(session, &tool_unit(session, 2, "c1", "第一次的结果"))
        .unwrap();
    storage
        .append_unit(session, &text_unit(session, 3, "旧的回答"))
        .unwrap();

    // 重试之后只跑了一轮：落点从 seq 3 变成 seq 2。
    let fresh = vec![text_unit(session, 2, "新的回答")];
    let anchor = storage
        .replace_turn(session, &MessageId::new(session, 1), &fresh, true)
        .unwrap()
        .expect("有落点");
    assert_eq!(anchor, MessageId::new(session, 2), "落点跟着轮数走");
    assert_eq!(shown(&storage, session).len(), 2, "用户 + 一条");

    let versions = storage.list_message_versions(&anchor).unwrap();
    assert_eq!(versions.count, 2, "版本控件搬到新的落点上");
    assert_eq!(versions.shown, "新的回答");

    // 切回旧那一轮：两条都回来，落点又回到 seq 3。
    storage
        .switch_message_version(&anchor, 1, VersionView::Raw)
        .unwrap();
    let back = shown(&storage, session);
    assert_eq!(back.len(), 3, "{back:?}");
    assert_eq!(back[1].2.as_deref(), Some("c1"));
    assert_eq!(back[2].1, "旧的回答");
    let moved = MessageId::new(session, 3);
    let versions = storage.list_message_versions(&moved).unwrap();
    assert_eq!(versions.count, 2, "切完之后版本控件还在（在新的落点上）");
}

#[test]
fn without_new_versions_the_old_turn_is_simply_gone() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let session = storage
        .create_conversation("重试", "device-a", "default")
        .unwrap();
    storage
        .append_unit(session, &user_unit(session, 1, "跑两次"))
        .unwrap();
    storage
        .append_unit(session, &tool_unit(session, 2, "c1", "第一次的结果"))
        .unwrap();
    storage
        .append_unit(session, &text_unit(session, 3, "旧的回答"))
        .unwrap();

    let fresh = vec![text_unit(session, 2, "覆盖后的回答")];
    let anchor = storage
        .replace_turn(session, &MessageId::new(session, 1), &fresh, false)
        .unwrap()
        .unwrap();
    let versions = storage.list_message_versions(&anchor).unwrap();
    assert_eq!(versions.count, 0, "开关关着：不留上一版");
    let now = shown(&storage, session);
    assert_eq!(now.len(), 2, "用户 + 这一轮一条：{now:?}");
    assert_eq!(now[1].1, "覆盖后的回答");
}
