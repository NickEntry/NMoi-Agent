//! 预设（0.14t / D178）的 C ABI。
//!
//! 两个动作，一个来源：
//!
//! - `nmoi_presets_json(handle, assets)` → 列表页要的那份（四态 + 生效正文 + 汇总）；
//! - `nmoi_presets_inject_json(handle, assets, declared)` → 这一轮该塞进 system 后面的一段。
//!
//! **预设原文在 APK 的 assets 里**，core 不认识文件系统，所以 assets 由平台侧读出来、
//! 以 JSON 传进来；用户改过的那一份在 `settings(scope=preset)` 里，由这一层自己读。
//! 两边都在这里合流，四态与拼装只走 core 的纯函数（`nmoi_core::preset`）——
//! 界面上显示的那个态与请求里真的注入了哪几条，必须是同一份算法算出来的。
//!
//! # 为什么不做成"读一份、写一份"的通用接口
//!
//! 预设的读取需要**同时**看见 assets 与用户改动，还要按 `declared` 过滤。
//! 把这些拆成三个原语交给客户端拼，错法会很多（比如拿上一轮的声明表去过滤这一轮），
//! 而且错起来只是"某条提示词偶尔不见了"，几乎没法复盘。

use std::ffi::c_char;

use nmoi_core::preset::{self, AssetPreset, EntryDoc, EntryView, Summary};
use nmoi_storage::settings::Scope;
use serde_json::{json, Value};

use crate::handles::with_storage;
use crate::json::{read_str, run_json, FfiError, FfiResult};

/// 预设词条在 settings 里的 key。`<预设 id>/<词条 id>`，与 assets 的目录结构一致。
pub fn doc_key(preset_id: &str, entry_id: &str) -> String {
    format!("{preset_id}/{entry_id}")
}

/// 把平台侧读出来的 assets 解析成 [`AssetPreset`]。
///
/// 形状不对就 `bad_argument` 并点名是哪个文件——静默跳过等于"预设悄悄少了一条"，
/// 而用户看到的只是"我明明打开着它，模型却不知道"。
pub(crate) fn parse_assets(text: &str) -> FfiResult<Vec<AssetPreset>> {
    let value: Value = serde_json::from_str(text)
        .map_err(|error| FfiError::new("bad_argument", format!("assets 不是合法 JSON：{error}")))?;
    let items = value
        .get("presets")
        .and_then(Value::as_array)
        .ok_or_else(|| FfiError::new("bad_argument", "assets 缺少 `presets` 数组"))?;
    let mut out = Vec::with_capacity(items.len());
    for (index, item) in items.iter().enumerate() {
        // 直接反序列化：`#[serde(default)]` 已经让缺字段的情况不至于整条失败，
        // 但类型不对（version 是字符串之类）必须当场说清楚，不能猜。
        let preset: AssetPreset = serde_json::from_value(item.clone()).map_err(|error| {
            FfiError::new(
                "bad_argument",
                format!("第 {} 份预设的字段不合法：{error}", index + 1),
            )
        })?;
        if preset.id.is_empty() {
            return Err(FfiError::new(
                "bad_argument",
                format!("第 {} 份预设没有 id", index + 1),
            ));
        }
        if preset.entries.iter().any(|entry| entry.id.is_empty()) {
            return Err(FfiError::new(
                "bad_argument",
                format!("预设 `{}` 里有词条没有 id", preset.id),
            ));
        }
        out.push(preset);
    }
    Ok(out)
}

/// 读一条词条用户改过的那一份。读不出来（含"文档坏了被重置"）就当没改过——
/// **不能让一份坏文档把整张列表打掉**：那样用户连"恢复跟随"都点不到。
fn load_doc(storage: &nmoi_storage::Storage, preset_id: &str, entry_id: &str) -> Option<EntryDoc> {
    let key = doc_key(preset_id, entry_id);
    match storage.get_doc(Scope::Preset, &key) {
        Ok(Some(doc)) => serde_json::from_str::<EntryDoc>(&doc.payload).ok(),
        _ => None,
    }
}

fn view_json(view: &EntryView) -> Value {
    json!({
        "id": view.id,
        "title": view.title,
        "state": view.state.as_str(),
        "label": view.state.label(),
        "needs_attention": view.state.needs_attention(),
        "content": view.content,
        "upstream": view.upstream,
        // 上游那一版的摘要：Kotlin 保存本地改动时把它当 `base_hash` 存下来。
        // 在这里给而不是让 Kotlin 自己算 sha256——两处各算一份，
        // 差一个大小写就会让"没改过"变成"已改"。
        "upstream_hash": preset::hash(&view.upstream),
        "version": view.version,
        "disabled": view.disabled,
        "injectable": view.injectable(),
        // 用户上次对齐的那一版。界面保存正文时原样写回——见 `EntryView::base` 的注释。
        "base": view.base.as_ref().map(|base| json!({
            "id": base.id,
            "version": base.version,
            "hash": base.hash,
        })),
    })
}

/// 列表：每份预设 + 每条词条的四态。
///
/// 入参：`assets` = `{"presets":[{"id","version","title","summary","applies_to":[…],
/// "entries":[{"id","title","content"}]}]}`（平台侧从 APK 的 `assets/presets/` 读出来）。
/// 出参：`{"items":[…],"summary":{…},"summary_line":"…","total":n}`
///
/// `state` / `label` / `needs_attention` 都由 core 算：界面**不自己推**四态，
/// 否则"要不要更新"这件事会有两个答案。
#[no_mangle]
pub extern "C" fn nmoi_presets_json(handle: u64, assets: *const c_char) -> *mut c_char {
    run_json(|| {
        // `read_str` 借的是 C 那边的缓冲，生命周期到本次调用为止（`<'a>` 是给它的便利）。
        let presets = parse_assets(read_str(assets, "assets")?)?;
        with_storage(handle, |storage| {
            let mut items = Vec::with_capacity(presets.len());
            let mut summary = Summary::default();
            let mut ordered: Vec<&AssetPreset> = presets.iter().collect();
            ordered.sort_by(|a, b| a.id.cmp(&b.id));
            for preset in ordered {
                let views: Vec<EntryView> = preset
                    .entries
                    .iter()
                    .map(|entry| {
                        let doc = load_doc(storage, &preset.id, &entry.id);
                        preset::entry_view(preset, entry, doc.as_ref())
                    })
                    .collect();
                for view in &views {
                    summary.add(view.state);
                }
                let state = preset::preset_state(&views);
                items.push(json!({
                    "id": preset.id,
                    "title": preset.title,
                    "summary": preset.summary,
                    "version": preset.version,
                    "applies_to": preset.applies_to,
                    "state": state.as_str(),
                    "label": state.label(),
                    "needs_attention": state.needs_attention(),
                    "entries": views.iter().map(view_json).collect::<Vec<_>>(),
                }));
            }
            Ok(json!({
                "items": items,
                "summary": {
                    "follow": summary.follow,
                    "local_edited": summary.local_edited,
                    "upstream_newer": summary.upstream_newer,
                    "conflict": summary.conflict,
                    "total": summary.total(),
                    "needs_attention": summary.needs_attention(),
                },
                // 一句话由 core 给（`Summary::describe`）：界面上那句"3 条可更新、1 条冲突"
                // 与列表里的徽标必须是同一份计数算出来的。
                "summary_line": summary.describe(),
                "total": summary.total(),
            }))
        })
    })
}

/// 这一轮该注入的那段正文。
///
/// 入参：`assets`（同上）、`declared` = 这一轮真正声明给模型的工具名数组。
/// 出参：`{"text":"…","chars":n,"injected":[{"preset","entry","version"}]}`
///
/// `injected` 是给请求日志的（用户要的位置），不是给模型看的：
/// 事后复盘"这句话是谁塞进去的"只能靠它。
#[no_mangle]
pub extern "C" fn nmoi_presets_inject_json(
    handle: u64,
    assets: *const c_char,
    declared: *const c_char,
) -> *mut c_char {
    run_json(|| {
        // `read_str` 借的是 C 那边的缓冲，生命周期到本次调用为止（`<'a>` 是给它的便利）。
        let presets = parse_assets(read_str(assets, "assets")?)?;
        let declared_text = read_str(declared, "declared")?;
        let declared: Vec<String> = serde_json::from_str(declared_text).map_err(|error| {
            FfiError::new(
                "bad_argument",
                format!("declared 必须是工具名数组：{error}"),
            )
        })?;
        with_storage(handle, |storage| {
            Ok(inject_json(storage, &presets, &declared))
        })
    })
}

/// 拼装那一段（`nmoi_presets_inject_json` 与 `turn_run` 共用）。
pub fn inject_json(
    storage: &nmoi_storage::Storage,
    presets: &[AssetPreset],
    declared: &[String],
) -> Value {
    let views = preset::select(
        presets,
        &|preset_id, entry_id| load_doc(storage, preset_id, entry_id),
        declared,
    );
    let text = preset::render(&views);
    json!({
        "text": text,
        "chars": text.chars().count(),
        "injected": views.iter().map(|view| json!({
            "preset": view.preset_id,
            "entry": view.id,
            "version": view.version,
        })).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use nmoi_storage::settings::Scope;
    use nmoi_storage::{OpenOutcome, Storage};

    const ASSETS: &str = r#"{"presets":[{"id":"tool-usage","version":3,"title":"工具用法",
        "summary":"读写规矩","applies_to":["read_file"],
        "entries":[{"id":"read-first","title":"先读再改","content":"改文件前先读一遍"}]}]}"#;

    fn storage() -> (tempfile::TempDir, Storage) {
        let dir = tempfile::tempdir().unwrap();
        let storage = match Storage::open(dir.path().join("nmoi.db")).expect("open") {
            OpenOutcome::Ready(storage) => storage,
            OpenOutcome::Blocked(blocked) => {
                panic!("被语义校验拦下: {:?}", blocked.report().issues)
            }
        };
        (dir, storage)
    }

    fn upstream_hash() -> String {
        preset::hash("改文件前先读一遍")
    }

    /// 真的按"上游那一版"记 base，然后改正文：这是 `local_edited` 的正路。
    #[test]
    fn a_local_edit_against_the_real_base_is_local_edited() {
        let (_dir, storage) = storage();
        let doc = EntryDoc {
            base_id: "tool-usage/read-first".into(),
            base_version: 3,
            base_hash: upstream_hash(),
            content: "我自己的写法".into(),
            disabled: false,
        };
        let key = doc_key("tool-usage", "read-first");
        storage
            .put_setting(Scope::Preset, &key, &doc, 1_000)
            .expect("写得进去");

        let presets = parse_assets(ASSETS).expect("assets 合法");
        let view = preset::entry_view(&presets[0], &presets[0].entries[0], Some(&doc));
        assert_eq!(view.state, nmoi_core::preset::PresetState::LocalEdited);
        assert_eq!(view.content, "我自己的写法");

        // 注入用的也是本地那份（用户改了就该生效，不用再点一次"启用"）。
        let injected = inject_json(&storage, &presets, &["read_file".to_string()]);
        assert!(injected["text"].as_str().unwrap().contains("我自己的写法"));
        assert_eq!(injected["injected"].as_array().unwrap().len(), 1);
    }

    /// 「留本地」= 只把 base 前进一步（记住"我看过 v4 了"），正文不动。
    /// 之后不该再是冲突——用户的改动没丢，也不用每次来都要他再选一遍。
    #[test]
    fn keeping_the_local_copy_advances_the_base_and_settles_the_conflict() {
        let (_dir, storage) = storage();
        let key = doc_key("tool-usage", "read-first");
        // 用户改过、上游也推过（base 记的是 v1 的摘要）：
        let stale = EntryDoc {
            base_id: "tool-usage/read-first".into(),
            base_version: 1,
            base_hash: "deadbeef".into(),
            content: "我自己的写法".into(),
            disabled: false,
        };
        storage
            .put_setting(Scope::Preset, &key, &stale, 1_000)
            .expect("写得进去");
        let assets = parse_assets(ASSETS).expect("assets 合法");
        let before = preset::entry_view(&assets[0], &assets[0].entries[0], Some(&stale));
        assert_eq!(before.state, nmoi_core::preset::PresetState::Conflict);

        // 界面上点「留本地」：base 换成上游此刻那一版，正文保留。
        let settled = EntryDoc {
            base_version: assets[0].version,
            base_hash: upstream_hash(),
            ..stale
        };
        storage
            .put_setting(Scope::Preset, &key, &settled, 2_000)
            .expect("写得进去");
        let after = preset::entry_view(&assets[0], &assets[0].entries[0], Some(&settled));
        assert_eq!(after.state, nmoi_core::preset::PresetState::LocalEdited);
        assert_eq!(after.content, "我自己的写法");
    }

    /// 库里那份文档坏了 → 当没改过（跟随），**不能把整张列表打掉**：
    /// 否则用户连"恢复跟随"都点不到。
    #[test]
    fn a_broken_doc_falls_back_to_follow() {
        let (_dir, storage) = storage();
        let key = doc_key("tool-usage", "read-first");
        storage
            .put_doc(Scope::Preset, &key, r#"{"base_version":"三"}"#, 1_000)
            .expect("写得进去");
        // `load_doc` 解析失败 → None；列表照常。
        assert!(load_doc(&storage, "tool-usage", "read-first").is_none());
    }

    /// 形状不对要能点名：静默跳过等于"预设悄悄少了一条"。
    #[test]
    fn bad_assets_are_rejected_with_a_reason() {
        // `FfiError` 只实现 Debug（它不跨 FFI、不需要 Display）：断在 debug 文本上。
        let error = format!("{:?}", parse_assets(r#"{"presets":{}}"#).unwrap_err());
        assert!(error.contains("presets"), "{error}");

        let error = format!(
            "{:?}",
            parse_assets(r#"{"presets":[{"version":1}]}"#).unwrap_err()
        );
        assert!(error.contains("没有 id"), "{error}");
    }
}
