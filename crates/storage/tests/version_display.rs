//! 「显示的那一组」是不是真的那组：2026-10-26 真机报的三条，做成断言钉住。
//!
//! 三条报障（用户原话）：
//! 1. 只有一版时被工具改过，也应该出现 `1/1`（现在被 `count <= 1` 一刀切掉）；
//! 2. 屏幕上显示的是**AI 改后**的那一份时，点开「编辑」应当预填这一份，
//!    存的时候也应当落到这一份上——现在预填的是原文，存完正文却换成了他打的那份；
//! 3. 「删除所有版本」没能正常删掉。
//!
//! 这一层测的是**库里给的答案**：预填文本、落点、删完之后显示的正文。
//! 界面层的分支（`count <= 1` 就不画控件）在 Kotlin 那边，这里只用 `version_index`
//! 的事实说明它该画。

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

/// 一条助手消息 + 它的第 1 版（原文）。真机上的顺序：先落消息，再记版本。
fn seed_assistant(storage: &mut Storage, raw: &str) -> (SessionId, MessageId) {
    let session = storage.create_conversation("版本", "device-a").unwrap();
    let message = Message::text(
        MessageId::new(session, 1),
        Role::Assistant,
        SourceTag::History,
        raw,
        raw.chars().count() as u32,
        1_700_000_000_000,
        LogicalClock::new(1),
    );
    storage
        .append_unit(session, &AtomicUnit::turn(message.clone()).unwrap())
        .unwrap();
    storage.save_generated_version(&message.id, raw).unwrap();
    (session, message.id)
}

fn shown(storage: &Storage, session: SessionId, id: &MessageId) -> String {
    storage
        .load_units(session)
        .unwrap()
        .iter()
        .flat_map(|unit| unit.messages.iter())
        .find(|message| &message.id == id)
        .map(|message| message.content.clone())
        .expect("消息还在")
}

/// 报障 1：一版、被工具改过 —— 概要里必须能看出"这一版有 AI 标记"，
/// 界面才有东西可画（现在被 `count <= 1` 一刀切掉，`1/1` 永远不出现）。
#[test]
fn a_single_version_edited_by_tool_still_reports_the_marker() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let (session, id) = seed_assistant(&mut storage, "原文");
    storage.mark_version_edited(&id, 1, "改后").unwrap();

    let brief = storage.version_index(session).unwrap();
    let brief = brief.get(&id.to_string()).expect("有一版就该有条目");
    assert_eq!(brief.count, 1);
    assert!(brief.edited_by_tool, "被工具改过这件事必须带出来");
    assert_eq!(
        brief.view.as_str(),
        "raw",
        "工具改完仍显示原文，颜色应当是蓝"
    );
    // 云端正文仍是原文——这是设计：工具改完不自动切视图。
    assert_eq!(shown(&storage, session, &id), "原文");
}

/// 报障 2 的第一半：切到"改后"之后，**库给的显示正文**必须是改后那一份。
/// 界面预填如果读的是这份，就不会出现"屏幕上写着改后、编辑框里是原文"。
#[test]
fn switching_to_the_edited_group_makes_the_edited_text_the_shown_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let (session, id) = seed_assistant(&mut storage, "原文");
    storage.mark_version_edited(&id, 1, "改后").unwrap();

    let text = storage
        .switch_message_version(&id, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(text, "改后");
    assert_eq!(shown(&storage, session, &id), "改后");
}

/// 报障 2 的第二半：显示着"改后"的时候改它，**改的是这一份**（不是原文）。
/// 改完显示的是他打的那份；切回原文还能看到原文，两份互不串。
#[test]
fn editing_while_the_edited_group_is_shown_edits_that_group() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let (session, id) = seed_assistant(&mut storage, "原文");
    storage.mark_version_edited(&id, 1, "改后").unwrap();
    storage
        .switch_message_version(&id, 1, VersionView::Edited)
        .unwrap();

    let typed = "他打的那份（基于改后）";
    storage.user_edit_version(&id, 1, typed).unwrap();
    assert_eq!(shown(&storage, session, &id), typed);

    // 切回原文：不该看到他打的那份（那是他对"改后"的改）。
    let raw = storage
        .switch_message_version(&id, 1, VersionView::Raw)
        .unwrap();
    assert_eq!(raw, "原文", "改的是改后那一组，不该污染原文");

    // 再切回去：他打的那份还在。
    let edited = storage
        .switch_message_version(&id, 1, VersionView::Edited)
        .unwrap();
    assert_eq!(edited, typed);
}

/// 报障 3：删除所有版本之后，**只剩一版**，而且显示的是他最后看到的那一份。
///
/// 这一条现在会红：`delete_message_version` 自己拼了一遍"显示哪一份"，
/// 只认 `edited_text`，把他改过的那份丢了。
#[test]
fn delete_all_versions_keeps_the_shown_text_not_the_raw_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let (session, id) = seed_assistant(&mut storage, "原文");

    // 两版：第 2 版是另一份生成结果。
    storage.save_generated_version(&id, "第二版").unwrap();
    // 第 2 版被工具改过，并且现在显示的正是改后那份。
    storage.mark_version_edited(&id, 2, "第二版的改后").unwrap();
    storage
        .switch_message_version(&id, 2, VersionView::Edited)
        .unwrap();
    let typed = "最后我自己又改了一遍";
    storage.user_edit_version(&id, 2, typed).unwrap();
    assert_eq!(shown(&storage, session, &id), typed);

    // D151：0 = 清空，连当前版一起删；正文保持"他最后看到的那一份"。
    let (count, active, displayed) = storage.delete_message_version(&id, 0).unwrap();
    assert_eq!((count, active), (0, 0), "版本记录清空");
    assert_eq!(displayed, typed, "删完显示的必须是他最后看到的那份");
    assert_eq!(shown(&storage, session, &id), typed);
    assert_eq!(
        storage.list_message_versions(&id).unwrap().versions.len(),
        0
    );
}

/// 真机报障：`工具编辑` 对着一条**从没记过版本**的消息点下去，
/// 回的是 `storage: 数据不一致: 消息 xxx 没有第 1 版`（截图）。
/// 现在写入路径自己补第 1 版：原文取当前正文，然后才标"被 AI 改过"。
#[test]
fn editing_a_message_that_never_had_versions_seeds_the_first_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let (session, id) = seed_assistant(&mut storage, "原文");
    // 真的没有版本行：清掉 seed_assistant 建的那一版，模拟"普通发出来的一条消息"。
    storage.delete_message_version(&id, 0).unwrap();
    assert_eq!(storage.list_message_versions(&id).unwrap().count, 0);

    storage.mark_version_edited(&id, 1, "工具改的").unwrap();
    let brief = storage.version_index(session).unwrap();
    let brief = brief.get(&id.to_string()).expect("补出了第 1 版");
    assert_eq!(brief.count, 1, "一条消息补成一版，不是两版");
    assert!(brief.edited_by_tool);
    assert_eq!(shown(&storage, session, &id), "原文", "工具改完仍显示原文");

    let typed = "我自己改的";
    storage.user_edit_version(&id, 1, typed).unwrap();
    assert_eq!(shown(&storage, session, &id), typed);
    let list = storage.list_message_versions(&id).unwrap();
    assert_eq!(list.count, 1, "编辑不该凭空多出版本");
    assert_eq!(list.shown, typed, "`shown` 要跟着改");
}
