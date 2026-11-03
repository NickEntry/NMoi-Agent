//! 每个会话自己的工作区（D231 / 0.15ao）。
//!
//! 两件事要钉住：新会话默认**隔离**；老会话升级上来是**共享**（文件不会突然"全没了"）。

use nmoi_storage::migrate::{self, SCHEMA_VERSION};
use nmoi_storage::repo::WorkspaceMode;
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

#[test]
fn a_new_conversation_gets_its_own_chat() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage
        .create_conversation("新会话", "device-a", "default")
        .unwrap();
    assert_eq!(
        storage.workspace_mode(session).unwrap(),
        WorkspaceMode::Chat
    );
}

#[test]
fn switching_to_shared_is_remembered() {
    let dir = tempfile::tempdir().unwrap();
    let mut storage = ready(&dir.path().join("app.db"));
    let session = storage
        .create_conversation("绑定共享", "device-a", "default")
        .unwrap();
    storage
        .set_workspace_mode(session, WorkspaceMode::Shared)
        .unwrap();
    assert_eq!(
        storage.workspace_mode(session).unwrap(),
        WorkspaceMode::Shared
    );

    // 重开一次还在（存的是库，不是内存）。
    drop(storage);
    let storage = ready(&dir.path().join("app.db"));
    assert_eq!(
        storage.workspace_mode(session).unwrap(),
        WorkspaceMode::Shared
    );
}

/// 老库（v11）升级上来：**已有会话一律 `shared`**。
///
/// 判据是"升级之后文件还在老地方"：迁成 `chat` 的话，用户打开老会话会发现
/// 之前让模型写的文件全不见了——那不是隔离，那是丢东西。
#[test]
fn old_conversations_stay_on_the_shared_workspace() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        migrate::migrate(&mut conn, 11).unwrap();
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version, device_origin)
             VALUES ('c1', '老会话', 1, 1, 11, 'device-a')",
            [],
        )
        .unwrap();
    }
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        assert_eq!(
            migrate::migrate(&mut conn, SCHEMA_VERSION).unwrap(),
            SCHEMA_VERSION
        );
        let mode: String = conn
            .query_row(
                "SELECT workspace_mode FROM conversations WHERE id = 'c1'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(mode, "shared", "老会话必须留在共享工作区");
    }

    // 新会话照旧是隔离的——同一份库，两种模式并存。
    let mut storage = ready(&path);
    let fresh = storage
        .create_conversation("升级后新建", "device-a", "default")
        .unwrap();
    assert_eq!(storage.workspace_mode(fresh).unwrap(), WorkspaceMode::Chat);
}

/// 认不出的值落到 `shared`：那是最保守的一档（文件都在老地方）。
#[test]
fn an_unknown_mode_falls_back_to_shared() {
    assert_eq!(WorkspaceMode::parse("chat"), WorkspaceMode::Chat);
    assert_eq!(WorkspaceMode::parse("shared"), WorkspaceMode::Shared);
    assert_eq!(WorkspaceMode::parse(""), WorkspaceMode::Shared);
    assert_eq!(WorkspaceMode::parse("whatever"), WorkspaceMode::Shared);
}
