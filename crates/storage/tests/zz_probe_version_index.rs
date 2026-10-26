//! 临时探针（诊断用，测完删）：复现"`load_units` 里 version_count 恒为 0，而 `versions` 接口是对的"。
//!
//! 走的是真机那条路：**每次调用开一份新的 Storage**（FFI 就是这么干的）。

use nmoi_core::clock::LogicalClock;
use nmoi_core::ids::{MessageId, SessionId};
use nmoi_core::message::{AtomicUnit, Message, Role};
use nmoi_core::source::SourceTag;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被拦下: {:?}", blocked.report().issues),
    }
}

#[test]
fn version_index_matches_load_units() {
    let mut path = std::env::temp_dir();
    path.push(format!("nmoi_probe_{}.db", std::process::id()));
    let _ = std::fs::remove_file(&path);

    let session = {
        let mut storage = ready(&path);
        let session = storage.create_conversation("探针", "device-a").unwrap();
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
    for round in 1..=2 {
        // 真机：每次调用都新开一份
        let mut storage = ready(&path);
        let (active, count) = storage
            .save_generated_version(&message_id, "原始正文")
            .expect("save");
        println!("save#{round} → active={active} count={count}");
    }

    let storage = ready(&path);
    let index = storage.version_index(session).expect("version_index");
    println!("version_index 键：{:?}", index.keys().collect::<Vec<_>>());
    println!("期待的键：      {}", message_id);

    let units = storage.load_units(session).expect("load_units");
    for unit in &units {
        for message in &unit.messages {
            println!(
                "load_units id={} brief={:?}",
                message.id,
                index.get(&message.id.to_string()).map(|b| (b.count, b.active))
            );
        }
    }

    let list = storage.list_message_versions(&message_id).expect("list");
    println!("list_message_versions → count={} active={}", list.count, list.active);

    let _ = std::fs::remove_file(&path);
}
