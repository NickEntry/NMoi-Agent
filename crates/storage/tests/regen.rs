//! 重试落库（0.15h / D185）：**新版本**与**覆盖**是两件不同的事。
//!
//! 这一层钉两条：
//!
//! 1. 重试默认**追加**一版，旧回答还在——`<2/2>` 往回切必须能切到它；
//! 2. 开关关着时覆盖当前那一版，版数不变（用户要的是"现在这个答案"，不是"多一份"）。

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::versions::VersionView;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn seed_answer(storage: &mut Storage, body: &str) -> (SessionId, MessageId) {
    let session = storage
        .create_conversation("重试", "device-a", "default")
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

#[test]
fn retry_appends_a_version_and_keeps_the_old_answer() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (_session, id) = seed_answer(&mut storage, "旧的回答");

    let (active, count) = storage
        .append_generated_version(&id, "新的回答")
        .expect("重试要能落库");
    assert_eq!(count, 2, "旧回答没有被顶掉，所以是两版");
    assert_eq!(active, 2);

    let versions = storage.list_message_versions(&id).unwrap();
    assert_eq!(versions.count, 2);
    assert_eq!(versions.shown, "新的回答", "当前显示的是新回答");

    // 往回切：旧回答必须还在。
    let old = storage
        .switch_message_version(&id, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(old, "旧的回答", "旧回答不能被新回答顶掉");
}

#[test]
fn overwrite_replaces_the_current_version_without_adding_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (_session, id) = seed_answer(&mut storage, "旧的回答");

    storage
        .overwrite_message_text(&id, "覆盖后的回答")
        .expect("覆盖要能落库");

    let versions = storage.list_message_versions(&id).unwrap();
    assert_eq!(versions.count, 1, "覆盖不加版");
    assert_eq!(versions.shown, "覆盖后的回答");
}

#[test]
fn overwrite_touches_the_visible_group_only() {
    // 被 AI 改过（红蓝两份）的消息：覆盖时改的是**当前显示的那一组**，
    // 另一组不受影响——和用户手动编辑同一套规则。
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("nmoi.db"));
    let (_session, id) = seed_answer(&mut storage, "原始正文");

    storage
        .apply_unit_edits(
            &id,
            &[nmoi_core::edit::BlockOp::Set {
                block: nmoi_core::edit::BlockSelector::Output,
                content: "AI 改的那份".into(),
            }],
        )
        .expect("先落一次红");

    // 落红之后当前显示的是"红"那一组（apply_unit_edits 会切到 edited）。
    storage.overwrite_message_text(&id, "再覆盖一次").unwrap();
    let versions = storage.list_message_versions(&id).unwrap();
    assert_eq!(versions.shown, "再覆盖一次");

    // 蓝的那份还是原文。
    let raw = storage
        .switch_message_version(&id, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(raw, "原始正文", "覆盖红字不该动蓝字");
}
