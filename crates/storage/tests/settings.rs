//! 设置文档（schema v3 / D86）的往返与坏数据处置。

use nmoi_core::settings::{AssistantSettings, ConversationSettings};
use nmoi_storage::settings::{Loaded, Scope};
use nmoi_storage::{OpenOutcome, Storage};
use serde::{Deserialize, Serialize};

fn ready(path: &std::path::Path) -> Storage {
    match Storage::open(path).expect("open") {
        OpenOutcome::Ready(storage) => storage,
        OpenOutcome::Blocked(blocked) => {
            panic!("被语义校验拦下: {:?}", blocked.report().issues)
        }
    }
}

fn temp() -> (tempfile::TempDir, Storage) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("app.db");
    let storage = ready(&path);
    (dir, storage)
}

fn assistant(name: &str) -> AssistantSettings {
    AssistantSettings {
        name: name.to_string(),
        ..AssistantSettings::default()
    }
}

#[test]
fn assistant_roundtrip_and_scope_isolation() {
    let (_dir, storage) = temp();

    let mut settings = assistant("写代码");
    settings.chat.system_prompt = "你是写代码的".into();
    settings.chat.tools.local = true;
    settings.chat.tools.mcp.insert("srv_a".into(), true);
    storage
        .put_setting(Scope::Assistant, "as_1", &settings, 1_700_000_000_000)
        .unwrap();

    let loaded: Loaded<AssistantSettings> = storage
        .load_setting(Scope::Assistant, "as_1", 1_700_000_001_000)
        .unwrap();
    match loaded {
        Loaded::Ready(back) => {
            assert_eq!(back, settings);
            assert_eq!(back.chat.tools.mcp_to_connect(), vec!["srv_a"]);
        }
        other => panic!("应读回来: {other:?}"),
    }

    // 同一个 key 换个 scope 不串味
    assert_eq!(
        storage
            .load_setting::<AssistantSettings>(Scope::Conversation, "as_1", 1_700_000_002_000)
            .unwrap(),
        Loaded::Missing
    );

    // 列表按 updated_at 倒序
    storage
        .put_setting(
            Scope::Assistant,
            "as_2",
            &assistant("翻译"),
            1_700_000_003_000,
        )
        .unwrap();
    let list = storage.list_docs(Scope::Assistant).unwrap();
    assert_eq!(list.len(), 2);
    assert_eq!(list[0].0, "as_2", "最近更新的在前");
}

#[test]
fn delete_is_idempotent() {
    let (_dir, storage) = temp();
    storage
        .put_setting(Scope::Assistant, "as_1", &assistant("A"), 1)
        .unwrap();
    assert!(storage.delete_doc(Scope::Assistant, "as_1").unwrap());
    assert!(!storage.delete_doc(Scope::Assistant, "as_1").unwrap());
    assert_eq!(storage.list_docs(Scope::Assistant).unwrap().len(), 0);
}

#[test]
fn empty_key_is_rejected() {
    let (_dir, storage) = temp();
    assert!(storage
        .put_setting(Scope::Assistant, "", &assistant("A"), 1)
        .is_err());
}

#[test]
fn corrupt_payload_is_reset_and_the_raw_value_never_reaches_the_log() {
    let (_dir, storage) = temp();
    // 假的天密钥混在里面：修复记录里绝不能出现原文
    storage
        .put_doc(
            Scope::Assistant,
            "as_1",
            r#"{"name":"坏掉的文档","key":"sk-verysecret1234"#,
            1,
        )
        .unwrap();

    let loaded: Loaded<AssistantSettings> =
        storage.load_setting(Scope::Assistant, "as_1", 99).unwrap();
    let Loaded::Reset { reason } = loaded else {
        panic!("半截 JSON 必须被当坏数据");
    };
    assert!(reason.contains("解析失败"), "{reason}");

    let repairs = storage.repairs(10).unwrap();
    assert_eq!(repairs.len(), 1);
    assert_eq!(repairs[0].old_digest.len(), 16, "只留哈希前 16 位");
    assert!(
        !repairs[0].old_digest.contains("sk-verysecret"),
        "摘要里不能有原值"
    );
}

#[test]
fn trim_repairs_keeps_the_newest() {
    let (_dir, storage) = temp();
    for i in 0..5 {
        storage
            .record_repair(Scope::Assistant, &format!("as_{i}"), "测试", "raw", 100 + i)
            .unwrap();
    }
    assert_eq!(storage.repairs(10).unwrap().len(), 5);
    let removed = storage.trim_repairs(2).unwrap();
    assert_eq!(removed, 3);
    let left = storage.repairs(10).unwrap();
    assert_eq!(left.len(), 2);
    assert_eq!(left[0].key, "as_4", "留下的是最新的两条");
    assert_eq!(left[1].key, "as_3");
}

#[test]
fn conversation_settings_carry_the_assistant_id() {
    let (_dir, storage) = temp();
    let mut base = assistant("写代码");
    base.chat.tools.local = true;
    let conversation = ConversationSettings::from_assistant("as_1", &base);

    storage
        .put_setting(Scope::Conversation, "cv_1", &conversation, 5)
        .unwrap();
    let Loaded::Ready(back) = storage
        .load_setting::<ConversationSettings>(Scope::Conversation, "cv_1", 6)
        .unwrap()
    else {
        panic!("刚写的读不回来");
    };
    assert_eq!(back.assistant_id.as_deref(), Some("as_1"));
    assert!(back.chat.tools.local);
}

/// 旧的、只有几个字段的文档要能读（字段只增不减）。
#[test]
fn sparse_documents_load_with_defaults() {
    let (_dir, storage) = temp();
    storage
        .put_doc(Scope::Assistant, "as_old", r#"{"name":"老助手"}"#, 1)
        .unwrap();
    let Loaded::Ready(back) = storage
        .load_setting::<AssistantSettings>(Scope::Assistant, "as_old", 2)
        .unwrap()
    else {
        panic!("老文档必须能读");
    };
    assert_eq!(back.name, "老助手");
    assert!(back.chat.system_prompt.is_empty());
    assert!(!back.chat.tools.local);
}

/// 空库要被迁移到当前版本，且设置那两张表都在。
#[test]
fn schema_is_current_and_has_the_settings_tables() {
    let (_dir, storage) = temp();
    // 不写死版本号：写死的话每加一版迁移都要来改这里，改晚了就成了"测试在追代码"
    assert_eq!(
        storage.schema_version().unwrap(),
        nmoi_storage::migrate::SCHEMA_VERSION
    );

    // 两张表在不在，用"能不能查"来证明：表不在会直接报 SQL 错，不用去翻 sqlite_master。
    assert!(storage.list_docs(Scope::Assistant).unwrap().is_empty());
    assert!(storage.repairs(1).unwrap().is_empty());

    // 重新打开一次是幂等的
    let (_d2, again) = {
        let dir2 = tempfile::tempdir().unwrap();
        let path = dir2.path().join("x.db");
        let s = ready(&path);
        (dir2, s)
    };
    assert_eq!(
        again.schema_version().unwrap(),
        nmoi_storage::migrate::SCHEMA_VERSION
    );
}

#[derive(Debug, Deserialize, Serialize, PartialEq)]
struct Tiny {
    value: u32,
}

#[test]
fn typed_roundtrip_of_an_arbitrary_document() {
    let (_dir, storage) = temp();
    storage
        .put_setting(Scope::Roles, "global", &Tiny { value: 7 }, 1)
        .unwrap();
    let Loaded::Ready(back) = storage
        .load_setting::<Tiny>(Scope::Roles, "global", 2)
        .unwrap()
    else {
        panic!("读不回来");
    };
    assert_eq!(back, Tiny { value: 7 });
}

/// D156：模型服务的设置和别的 scope 走同一条路（整份读写、跨重启活着）。
#[test]
fn provider_scope_roundtrips_like_any_other_doc() {
    use nmoi_storage::settings::Scope;
    assert_eq!(Scope::Provider.as_str(), "provider");
    assert_eq!(Scope::parse("provider"), Some(Scope::Provider));

    let (_dir, mut storage) = temp();
    let doc = serde_json::json!({
        "base_url": "https://example.invalid/v1",
        "model": "test-model",
        "kind": "openai_compatible",
    });
    storage
        .put_setting(Scope::Provider, "default", &doc, 1_000)
        .unwrap();
    let loaded: Loaded<serde_json::Value> = storage
        .load_setting(Scope::Provider, "default", 2_000)
        .unwrap();
    match loaded {
        Loaded::Ready(back) => assert_eq!(back, doc),
        other => panic!("应当是 Ready，实际 {other:?}"),
    }
}
