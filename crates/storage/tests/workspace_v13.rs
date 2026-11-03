//! v13：`workspace_mode` 这一列退场（D234）。
//!
//! 工作区改成"用户建的目录 + 助手绑定"之后，这条列与它回答的是同一个问题。
//! 两份答案摆在一起，下一个读代码的人得先猜哪一份算数——所以要删，而不是留着不用。

use nmoi_storage::migrate::{self, SCHEMA_VERSION};
use nmoi_storage::{OpenOutcome, Storage};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => panic!("被语义校验拦下: {:?}", blocked.report().issues),
    }
}

fn columns(conn: &rusqlite::Connection, table: &str) -> Vec<String> {
    let mut stmt = conn
        .prepare(&format!("PRAGMA table_info({table})"))
        .unwrap();
    let rows = stmt
        .query_map([], |row| row.get::<_, String>(1))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    rows
}

/// 老库（v12）升上来：列没了，**会话本身一条都不少**（标题、归属都还在）。
#[test]
fn v13_drops_the_column_but_keeps_the_rows() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        migrate::migrate(&mut conn, 12).unwrap();
        conn.execute(
            "INSERT INTO conversations(id, title, created_at, updated_at, schema_version,
                                       device_origin, assistant_id, workspace_mode)
             VALUES ('c1', '老会话', 1, 1, 12, 'device-a', 'default', 'shared')",
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
        assert!(
            !columns(&conn, "conversations")
                .iter()
                .any(|name| name == "workspace_mode"),
            "v13 之后不该还有 workspace_mode"
        );
        let (title, assistant): (String, String) = conn
            .query_row(
                "SELECT title, assistant_id FROM conversations WHERE id = 'c1'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(title, "老会话");
        assert_eq!(assistant, "default");
    }
}

/// 全新的库直接到 v13：没有那一列，建会话照旧能用。
#[test]
fn a_fresh_database_has_no_workspace_mode() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let mut storage = ready(&path);
    let session = storage
        .create_conversation("新会话", "device-a", "default")
        .unwrap();
    assert!(storage.load_units(session).unwrap().is_empty());

    let conn = rusqlite::Connection::open(&path).unwrap();
    assert!(
        !columns(&conn, "conversations")
            .iter()
            .any(|name| name == "workspace_mode"),
        "新库也不该有 workspace_mode"
    );
}
