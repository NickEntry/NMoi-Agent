//! 窗口读回（D149）：分界线以下的单元 + 那条线压出来的摘要。
//!
//! 这一层测的是"库里的事实能不能拼成请求该用的那一份"。拼错的表现都很安静：
//! 多给一段原文 = 压缩没发生；少给摘要 = 模型失忆；把用户手插的线当压缩线 = 凭空冒出摘要。

use nmoi_core::archive::{ArchiveEntry, ArchiveStore, Origin};
use nmoi_core::clock::LogicalClock;
use nmoi_core::divider::{Divider, SUMMARY_NOTE_SEP};
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::session::HistoryStore;
use nmoi_core::source::SourceTag;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn unit(session: SessionId, seq: u64, role: Role, text: &str) -> AtomicUnit {
    let message = Message::text(
        MessageId::new(session, seq),
        role,
        SourceTag::History,
        text,
        text.chars().count() as u32,
        1_700_000_000_000 + seq as i64,
        LogicalClock::new(seq),
    );
    AtomicUnit::turn(message).unwrap()
}

/// 四条历史：u1 / a2 / u3 / a4。
fn seed(storage: &mut Storage) -> SessionId {
    let session = storage.create_conversation("窗口", "device-a").unwrap();
    for (seq, role, text) in [
        (1, Role::User, "第一句"),
        (2, Role::Assistant, "第一答"),
        (3, Role::User, "第二句"),
        (4, Role::Assistant, "第二答"),
    ] {
        storage
            .append_unit(session, &unit(session, seq, role, text))
            .unwrap();
    }
    session
}

/// 把摘要写进归档，返回引用。真实路径是 `compress::commit` 干的，这里只造事实。
fn archive_summary(storage: &mut Storage, session: SessionId, text: &str) -> String {
    let anchor = MessageId::new(session, 2);
    let (entry, _) = ArchiveEntry::capture(
        text,
        SourceTag::InstructionInjection,
        Origin::new(session, anchor, 0),
        text.chars().count() as u32,
        1_700_000_000_500,
    );
    storage.put(entry).unwrap().to_string()
}

#[test]
fn no_divider_means_the_whole_history_is_the_window() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);

    let window = storage.load_window(session).unwrap();
    assert_eq!(window.units.len(), 4, "没画线就该全给");
    assert!(window.divider.is_none());
    assert!(window.summary.is_none());
    assert!(!window.is_compacted());
    assert_eq!(window.summary_tokens(), 0);
}

#[test]
fn compression_divider_hides_the_above_units_and_brings_the_summary_back() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);

    let summary = "我在这个会话里先说了一句，然后答了一句。";
    let reference = archive_summary(&mut storage, session, summary);
    let note = format!("{reference}{SUMMARY_NOTE_SEP}压缩 2 单元 / 摘录 1 条 → 20 字");
    storage
        .insert_divider(&Divider::compression(session, 2, note, 1_700_000_000_600))
        .unwrap();

    let window = storage.load_window(session).unwrap();
    assert_eq!(window.units.len(), 2, "线上两单元不该再进窗口");
    assert_eq!(window.units[0].messages[0].content, "第二句");
    assert_eq!(window.summary.as_deref(), Some(summary));
    assert!(window.is_compacted());
    assert_eq!(window.summary_tokens(), summary.chars().count() as u64);
    // 注入消息排在窗口最前面，且不是历史里的一条（seq = 0）。
    let injection = window.summary_message(session, 1_700_000_000_700).unwrap();
    assert_eq!(injection.content, summary);
    assert_eq!(injection.role, Role::User);
    assert_eq!(injection.source, SourceTag::InstructionInjection);
    assert_eq!(injection.id.seq(), 0);
}

/// 二次压缩的输入必须带上一次的摘要，否则这一次只压到窗口里剩下的部分。
#[test]
fn units_with_summary_puts_the_previous_summary_first() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);

    let summary = "上一轮压出来的摘要。";
    let reference = archive_summary(&mut storage, session, summary);
    storage
        .insert_divider(&Divider::compression(
            session,
            2,
            reference,
            1_700_000_000_600,
        ))
        .unwrap();

    let window = storage.load_window(session).unwrap();
    let input = window.units_with_summary(session, 1_700_000_000_700);
    assert_eq!(input.len(), 3, "摘要 + 线下两个单元");
    assert_eq!(input[0].messages[0].content, summary);
    assert_eq!(input[1].messages[0].content, "第二句");
}

/// 手动画的线也把上面挡在窗口外，只是没有摘要可注入。
#[test]
fn a_user_divider_hides_units_without_inventing_a_summary() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);
    storage
        .insert_divider(&Divider::user(session, 2, 1_700_000_000_600))
        .unwrap();

    let window = storage.load_window(session).unwrap();
    assert_eq!(window.units.len(), 2);
    assert!(window.summary.is_none(), "手插的线没有摘要，不许凭空造");
    assert!(window.summary_message(session, 1).is_none());
}

/// 多条线取最靠后的那条。
#[test]
fn the_effective_divider_is_the_latest_one() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);
    let first = archive_summary(&mut storage, session, "第一次压缩的摘要。");
    let second = archive_summary(&mut storage, session, "第二次压缩的摘要。");
    storage
        .insert_divider(&Divider::compression(session, 1, first, 1_700_000_000_500))
        .unwrap();
    storage
        .insert_divider(&Divider::compression(session, 3, second, 1_700_000_000_700))
        .unwrap();

    let window = storage.load_window(session).unwrap();
    assert_eq!(window.units.len(), 1, "只剩 a4");
    assert_eq!(window.summary.as_deref(), Some("第二次压缩的摘要。"));
}

/// 摘要丢了要报错：当成"没压过"就会把已经压掉的原文整段发出去。
#[test]
fn a_dangling_summary_ref_is_an_error_not_an_empty_window() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);
    storage
        .insert_divider(&Divider::compression(
            session,
            2,
            "ref#0000000000000000 | 压缩 2 单元",
            1_700_000_000_600,
        ))
        .unwrap();

    let error = storage.load_window(session).unwrap_err();
    let text = error.to_string();
    assert!(
        text.contains("ref#0000000000000000"),
        "错误里要指名是哪条引用：{text}"
    );
}

/// 经 trait 走的那份也要认线——编排层只认 `HistoryStore`。
#[test]
fn the_trait_method_sees_the_same_window() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = seed(&mut storage);
    let reference = archive_summary(&mut storage, session, "摘要正文。");
    storage
        .insert_divider(&Divider::compression(
            session,
            2,
            reference,
            1_700_000_000_600,
        ))
        .unwrap();

    let window = HistoryStore::load_window(&storage, session).unwrap();
    assert_eq!(window.units.len(), 2);
    assert!(window.is_compacted());
}
