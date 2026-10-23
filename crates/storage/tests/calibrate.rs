//! 校准的落库（D95）：往返、按模型取、坏数据不炸。

use nmoi_core::tokens::{Calibration, Class, Level};
use nmoi_storage::settings::{Loaded, Scope};
use nmoi_storage::{OpenOutcome, Storage};

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

/// 用 `new` 构造而不是结构体字面量：桶的归并规则（`calibrate::bucket`）在里面，
/// 绕过它就等于在测一个真实代码不会产生的形状。
fn calibration(model: &str, level: Level, class: Class, ratio: f64, samples: u32) -> Calibration {
    let mut cal = Calibration::new(model, level, class, 1_700_000_000_000);
    cal.ratio = ratio;
    cal.samples = samples;
    cal
}

#[test]
fn round_trip_by_model_and_bucket() {
    let (_dir, storage) = temp();
    let cal = calibration("vendor/model-a", Level::L1, Class::Cjk, 0.95, 4);
    storage.put_calibration(&cal, 10).expect("put");

    // L1 也按内容类分桶：中文的因子不能拿去套英文。
    let back = storage
        .get_calibration(&cal.model, Level::L1, Class::Cjk, 11)
        .unwrap()
        .expect("读回来");
    assert_eq!(back, cal);
    assert_eq!(back.key(), "vendor/model-a|l1|cjk");
    // 取别的类取不到——这正是分桶的意义。
    assert!(storage
        .get_calibration(&cal.model, Level::L1, Class::Ascii, 11)
        .unwrap()
        .is_none());
}

#[test]
fn missing_calibration_is_none_not_an_error() {
    let (_dir, storage) = temp();
    assert!(storage
        .get_calibration("nobody/knows", Level::L1, Class::Mixed, 1)
        .unwrap()
        .is_none());
    assert!(storage.calibrations("nobody/knows", 1).unwrap().is_empty());
}

#[test]
fn calibrations_are_scoped_to_one_model() {
    let (_dir, storage) = temp();
    storage
        .put_calibration(&calibration("a/m1", Level::L1, Class::Mixed, 1.1, 3), 1)
        .unwrap();
    storage
        .put_calibration(&calibration("a/m1", Level::L0, Class::Cjk, 0.8, 3), 2)
        .unwrap();
    storage
        .put_calibration(&calibration("a/m2", Level::L1, Class::Mixed, 1.3, 3), 3)
        .unwrap();

    let one = storage.calibrations("a/m1", 4).unwrap();
    assert_eq!(one.len(), 2, "只取这一个模型的");
    // L0 排在 L1 前（按 level 字符串排），类也稳定。
    assert_eq!(one[0].level, Level::L0);
    assert_eq!(one[1].level, Level::L1);

    // 前缀不能误伤：`a/m11` 不是 `a/m1`。
    storage
        .put_calibration(&calibration("a/m11", Level::L1, Class::Mixed, 1.9, 3), 5)
        .unwrap();
    assert_eq!(storage.calibrations("a/m1", 6).unwrap().len(), 2);
}

#[test]
fn corrupt_payload_records_a_repair_and_reads_as_none() {
    let (_dir, storage) = temp();
    let key = "a/m1|l1|mixed";
    storage
        .put_doc(Scope::Calibration, key, "{\"model\":", 1)
        .expect("写坏数据");

    assert!(storage
        .get_calibration("a/m1", Level::L1, Class::Mixed, 2)
        .unwrap()
        .is_none());
    assert!(
        matches!(
            storage
                .load_setting::<Calibration>(Scope::Calibration, key, 3)
                .unwrap(),
            Loaded::Reset { .. }
        ),
        "坏数据必须记一笔修复，不能静默"
    );
    let notes = storage.repairs(10).unwrap();
    assert!(notes.iter().any(|note| note.key == key));
}

#[test]
fn calibration_scope_is_its_own_string() {
    assert_eq!(Scope::Calibration.as_str(), "calibration");
    assert_eq!(Scope::parse("calibration"), Some(Scope::Calibration));
    // 与设置分开：模型覆盖里不会混进校准。
    let (_dir, storage) = temp();
    storage
        .put_calibration(&calibration("a/m1", Level::L1, Class::Mixed, 1.2, 3), 1)
        .unwrap();
    assert!(storage.list_docs(Scope::ModelOverrides).unwrap().is_empty());
}
