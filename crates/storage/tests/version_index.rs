//! `version_index` 与 `load_units` 必须对得上（D137）。
//!
//! 这条守的是一个很容易悄悄坏掉的不变量：`load_units` 的版本字段是**另外查一次**贴上去的
//! （`version_index` 一次查全会话，避免 N+1），两边靠 `message.id.to_string()` 当键。
//! 键一旦对不上，界面上的 `n/n` 就永远不出现，而 `versions` 接口却是好的——
//! 真机上真发生过（列表 0/0、现读 2/2），所以单独钉一条。
//!
//! 走的是真机那条路：**每次调用开一份新的 Storage**（FFI 就是这么干的）。

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::MessageId;
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn temp_path() -> std::path::PathBuf {
    let mut path = std::env::temp_dir();
    path.push(format!("nmoi_version_index_{}.db", std::process::id()));
    let _ = std::fs::remove_file(&path);
    path
}

#[test]
fn version_index_keys_match_load_units_ids() {
    let path = temp_path();

    // 建会话 + 一条消息（跟真机一样：先 append，再存版本）
    let session = {
        let mut storage = ready(&path);
        let session = storage.create_conversation("索引探针", "device-a").unwrap();
        let message = Message::text(
            MessageId::new(session, 1),
            Role::Assistant,
            SourceTag::History,
            "原始正文",
            4,
            1_700_000_000_000,
            LogicalClock::new(1),
        );
        storage
            .append_unit(session, &AtomicUnit::turn(message).unwrap())
            .unwrap();
        session
    };

    let message_id = MessageId::new(session, 1);

    // 没版本时：索引里不该有它
    {
        let storage = ready(&path);
        let index = storage.version_index(session).unwrap();
        assert!(
            index.is_empty(),
            "没存过版本的消息不该出现在索引里，实际 {:?}",
            index.keys().collect::<Vec<_>>()
        );
    }

    // 存两版（每次都是新开的 Storage）
    for _ in 0..2 {
        let mut storage = ready(&path);
        storage
            .save_generated_version(&message_id, "原始正文")
            .unwrap();
    }

    let storage = ready(&path);
    let index = storage.version_index(session).unwrap();
    let brief = index
        .get(&message_id.to_string())
        .unwrap_or_else(|| {
            panic!(
                "索引里没有 {}，实际键 {:?}",
                message_id,
                index.keys().collect::<Vec<_>>()
            )
        });
    assert_eq!((brief.count, brief.active), (2, 2));

    // 关键：`load_units` 拿到的消息 id 必须能查到同一份 brief
    let units = storage.load_units(session).unwrap();
    let mut seen = 0usize;
    for unit in &units {
        for message in &unit.messages {
            let found = index.get(&message.id.to_string());
            assert!(
                found.is_some(),
                "load_units 的 message.id={} 在 version_index 里查不到——界面上的 n/n 会永远不出现",
                message.id
            );
            seen += 1;
        }
    }
    assert_eq!(seen, 1);

    let _ = std::fs::remove_file(&path);
}
