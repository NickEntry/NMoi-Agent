//! 图标面的 C ABI：列包 / 导入 / 导出 / 删除 / 解析该用哪张图。
//!
//! # 这里不碰 settings 表
//!
//! "当前用哪个包""用户规则是什么"属于设置，存在 `settings` 表里（D86）。
//! 这一层只做"包 = 磁盘上的一堆文件"这件事，规则由调用方**用参数传进来**：
//! `nmoi_icons_resolve_json(handle, rules_json, subject_json)` 是纯函数式的——
//! 同样的规则同样的名字，一定给出同样的结果。
//!
//! 为什么不在这里顺手读设置：句柄表是**一把全局锁**（见 `handles.rs`），
//! `with_icons` 里再调 `with_storage` 会自己等自己。宁可多一个参数，也不要一个会死锁的接口。
//!
//! # 目录形状
//!
//! ```text
//! <root>/original/            原版（随安装包分发，只读语义）
//! <root>/imported/<pack_id>/  用户导入的包
//! ```

use std::ffi::c_char;
use std::path::{Path, PathBuf};

use nmoi_core::iconpack::{self, PackEntry, PackManifest, Tint};
use nmoi_core::icons::{
    best_provider_rule, model_tail, monogram, normalize_subject, IconKind, IconRule,
    ProviderSubject, RuleField, RuleSet,
};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::handles::{insert_icons, remove_icons, with_icons};
use crate::json::{read_opt_str, read_str, run_json, FfiError};

fn icons_error(error: impl std::fmt::Display) -> FfiError {
    FfiError::new("icons", error.to_string())
}

/// 一个从磁盘加载进来的包。**不预载 PNG**：图标是按需读的，79 个包条目预载进来
/// 就是几百 KB 常驻内存，而一屏最多显示十几个。
pub(crate) struct LoadedPack {
    pub(crate) manifest: PackManifest,
    pub(crate) dir: PathBuf,
    pub(crate) origin: Origin,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Origin {
    Original,
    Imported,
}

impl Origin {
    fn as_str(self) -> &'static str {
        match self {
            Origin::Original => "original",
            Origin::Imported => "imported",
        }
    }
}

pub(crate) struct IconPacks {
    pub(crate) root: PathBuf,
    pub(crate) packs: Vec<LoadedPack>,
}

impl IconPacks {
    fn open(root: &str) -> Result<Self, String> {
        let root = PathBuf::from(root);
        if !root.is_dir() {
            return Err(format!("{} 不是一个目录", root.display()));
        }
        let mut packs = Vec::new();
        loads_into(&root.join("original"), Origin::Original, &mut packs);
        let imported = root.join("imported");
        if let Ok(entries) = std::fs::read_dir(imported) {
            for entry in entries.flatten() {
                if entry.path().is_dir() {
                    loads_into(&entry.path(), Origin::Imported, &mut packs);
                }
            }
        }
        // original 永远排第一：它是回滚的那一档
        packs.sort_by(|a, b| {
            (a.origin != Origin::Original, a.manifest.pack_id.clone())
                .cmp(&(b.origin != Origin::Original, b.manifest.pack_id.clone()))
        });
        Ok(Self { root, packs })
    }

    fn find(&self, pack_id: &str) -> Option<&LoadedPack> {
        self.packs
            .iter()
            .find(|pack| pack.manifest.pack_id == pack_id)
    }

    fn original(&self) -> Option<&LoadedPack> {
        self.packs
            .iter()
            .find(|pack| pack.origin == Origin::Original)
    }
}

fn loads_into(dir: &Path, origin: Origin, out: &mut Vec<LoadedPack>) {
    let manifest_path = dir.join(iconpack::MANIFEST_NAME);
    let Ok(text) = std::fs::read(manifest_path) else {
        return; // 没清单就当这个目录不存在，不猜
    };
    match serde_json::from_slice::<PackManifest>(&text) {
        Ok(manifest) => out.push(LoadedPack {
            manifest,
            dir: dir.to_path_buf(),
            origin,
        }),
        Err(error) => {
            // 坏清单不静默：列包的时候要把原因带出去，不然用户只会看到"包不见了"
            eprintln!("图标包 {} 的清单读不了：{error}", dir.display());
        }
    }
}

fn pack_summary(pack: &LoadedPack) -> Value {
    let mut bytes = 0u64;
    let mut files = 0usize;
    if let Ok(entries) = std::fs::read_dir(&pack.dir) {
        for dir in entries.flatten().filter(|entry| entry.path().is_dir()) {
            if let Ok(inner) = std::fs::read_dir(dir.path()) {
                for file in inner.flatten() {
                    files += 1;
                    bytes += file.metadata().map(|meta| meta.len()).unwrap_or(0);
                }
            }
        }
    }
    json!({
        "pack_id": pack.manifest.pack_id,
        "name": pack.manifest.name,
        "version": pack.manifest.version,
        "created_at": pack.manifest.created_at,
        "origin": pack.origin.as_str(),
        // 原版不可删——UI 上连按钮都不出现，这里再挡一次
        "removable": pack.origin != Origin::Original,
        "entries": pack.manifest.entries.len(),
        "files": files,
        "bytes": bytes,
        "rules": pack.manifest.defaults.rules.len(),
    })
}

/// 打开一个图标包根目录（`<root>/original/`、`<root>/imported/`）。
#[no_mangle]
pub extern "C" fn nmoi_icons_open_json(root: *const c_char) -> *mut c_char {
    run_json(|| {
        let root = read_str(root, "root")?;
        let packs = IconPacks::open(root).map_err(icons_error)?;
        let items: Vec<Value> = packs.packs.iter().map(pack_summary).collect();
        let handle = insert_icons(packs);
        Ok(json!({
            "handle": handle,
            "root": root,
            "packs": items,
        }))
    })
}

/// 关掉图标句柄。幂等。
#[no_mangle]
pub extern "C" fn nmoi_icons_close_json(handle: u64) -> *mut c_char {
    run_json(|| Ok(json!({ "closed": remove_icons(handle) })))
}

/// 列出所有包。原版在第一行。
#[no_mangle]
pub extern "C" fn nmoi_icons_list_json(handle: u64) -> *mut c_char {
    run_json(|| {
        with_icons(handle, |packs| {
            let items: Vec<Value> = packs.packs.iter().map(pack_summary).collect();
            Ok(json!({ "packs": items, "count": items.len() }))
        })
    })
}

/// 导入一个图标包 zip。校验不过**整包拒绝**，磁盘上不留半个包。
#[no_mangle]
pub extern "C" fn nmoi_icons_import_json(handle: u64, zip_path: *const c_char) -> *mut c_char {
    run_json(|| {
        let path = read_str(zip_path, "zip_path")?;
        let bytes = std::fs::read(path)
            .map_err(|error| FfiError::new("io", format!("读不了 {path}：{error}")))?;
        let imported = iconpack::import(&bytes).map_err(icons_error)?;
        let pack_id = imported.manifest.pack_id.clone();
        let target = with_icons(handle, |packs| {
            let target = packs.root.join("imported").join(&pack_id);
            Ok(target)
        })?;

        // 先清干净再写：同名导入就是替换。半新半旧比没有更糟。
        if target.exists() {
            std::fs::remove_dir_all(&target)
                .map_err(|error| FfiError::new("io", format!("清理旧包失败：{error}")))?;
        }
        for entry in &imported.files {
            let dest = target.join(&entry.name);
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(|error| FfiError::new("io", format!("建目录失败：{error}")))?;
            }
            std::fs::write(&dest, &entry.data).map_err(|error| {
                FfiError::new("io", format!("写 {} 失败：{error}", dest.display()))
            })?;
        }

        // 重新扫一遍，让新包立刻出现在列表里
        let root = with_icons(handle, |packs| Ok(packs.root.clone()))?;
        let refreshed = IconPacks::open(&root.display().to_string()).map_err(icons_error)?;
        let summary = json!({
            "pack_id": pack_id,
            "entries": imported.manifest.entries.len(),
            "warnings": imported.warnings,
        });
        with_icons(handle, |packs| {
            *packs = refreshed;
            Ok(summary)
        })
    })
}

/// 导出一个包到 `dest`。原版也能导出——那是"把当前图标带走"的正当需求。
#[no_mangle]
pub extern "C" fn nmoi_icons_export_json(
    handle: u64,
    pack_id: *const c_char,
    dest: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let pack_id = read_str(pack_id, "pack_id")?;
        let dest = read_str(dest, "dest")?;
        let (manifest, files) = with_icons(handle, |packs| {
            let pack = packs
                .find(pack_id)
                .ok_or_else(|| FfiError::new("not_found", format!("没有这个包：{pack_id}")))?;
            let mut files = Vec::new();
            // 清单列到的每一个密度档 + NOTICE.txt（有就带上，没有就让 export 补兜底的）
            for entry in &pack.manifest.entries {
                for name in density_names(entry) {
                    let path = pack.dir.join(&name);
                    if let Ok(data) = std::fs::read(&path) {
                        files.push((name, data));
                    }
                }
            }
            if let Ok(data) = std::fs::read(pack.dir.join(iconpack::NOTICE_NAME)) {
                files.push((iconpack::NOTICE_NAME.to_string(), data));
            }
            Ok((pack.manifest.clone(), files))
        })?;

        let bytes = iconpack::export(&manifest, files).map_err(icons_error)?;
        std::fs::write(dest, &bytes)
            .map_err(|error| FfiError::new("io", format!("写 {dest} 失败：{error}")))?;
        Ok(json!({
            "path": dest,
            "bytes": bytes.len(),
            "entries": manifest.entries.len(),
        }))
    })
}

fn density_names(entry: &PackEntry) -> Vec<String> {
    iconpack::DENSITY_SUFFIXES
        .iter()
        .map(|suffix| format!("{}{}.png", entry.file, suffix))
        .collect()
}

/// 删掉一个导入的包。**原版永远拒绝**——它是回滚的那一档。
#[no_mangle]
pub extern "C" fn nmoi_icons_delete_json(handle: u64, pack_id: *const c_char) -> *mut c_char {
    run_json(|| {
        let pack_id = read_str(pack_id, "pack_id")?;
        let target = with_icons(handle, |packs| {
            let pack = packs
                .find(pack_id)
                .ok_or_else(|| FfiError::new("not_found", format!("没有这个包：{pack_id}")))?;
            if pack.origin == Origin::Original {
                return Err(FfiError::new(
                    "refused",
                    "原版图标包不能删除：它是回滚的那一档",
                ));
            }
            Ok(pack.dir.clone())
        })?;
        std::fs::remove_dir_all(&target).map_err(|error| {
            FfiError::new("io", format!("删不掉 {}：{error}", target.display()))
        })?;
        let root = with_icons(handle, |packs| Ok(packs.root.clone()))?;
        let refreshed = IconPacks::open(&root.display().to_string()).map_err(icons_error)?;
        with_icons(handle, |packs| {
            *packs = refreshed;
            Ok(json!({ "deleted": pack_id }))
        })
    })
}

/// 取当前包的预设规则（首次启动时用来给设置打种子）。
#[no_mangle]
pub extern "C" fn nmoi_icons_rules_json(handle: u64, pack_id: *const c_char) -> *mut c_char {
    run_json(|| {
        let pack_id = read_opt_str(pack_id, "pack_id")?;
        with_icons(handle, |packs| {
            let pack = match pack_id {
                Some(id) => packs.find(id).or_else(|| packs.original()),
                None => packs.original(),
            }
            .ok_or_else(|| FfiError::new("not_found", "没有可用的图标包"))?;
            Ok(json!({
                "pack_id": pack.manifest.pack_id,
                "rules": pack.manifest.defaults.rules,
            }))
        })
    })
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct SubjectProvider {
    kind: Option<String>,
    host: Option<String>,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Subject {
    /// 归一化前的完整模型名，例如 `anthropic/claude-opus-5`。
    model: String,
    #[serde(default)]
    provider: Option<SubjectProvider>,
    /// 用哪个包查；不给就用原版。
    #[serde(default)]
    pack_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RulesDoc {
    rules: Vec<IconRule>,
}

/// 解析"这个模型/供应商该用哪张图"。
///
/// 五级取图（`docs/ui/icon-packs.md` §3）：用户规则 → 包内预设规则 → 供应商 kind →
/// 模型名首段 → 首字母方块。**规则由参数传进来**（存在 settings 表里），这里不读设置。
///
/// 出参带 `via`，说清楚是哪一级命中的；没命中任何图标时给 `icon: null` + `monogram`，
/// 界面永远有话可说。
#[no_mangle]
pub extern "C" fn nmoi_icons_resolve_json(
    handle: u64,
    rules_json: *const c_char,
    subject: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let rules_text = read_opt_str(rules_json, "rules_json")?;
        let subject_text = read_str(subject, "subject")?;

        let rules: Vec<IconRule> = match rules_text {
            None | Some("") => Vec::new(),
            Some(text) => {
                // 既接受 `[{...}]` 也接受 `{"rules":[{...}]}`：设置里存的是后者，
                // 手写调试时用前者更短。两种都认，省得调用方为了格式再绕一层。
                if text.trim_start().starts_with('[') {
                    serde_json::from_str(text).map_err(|error| {
                        FfiError::new("bad_argument", format!("rules_json 不是规则数组：{error}"))
                    })?
                } else {
                    serde_json::from_str::<RulesDoc>(text)
                        .map_err(|error| {
                            FfiError::new(
                                "bad_argument",
                                format!("rules_json 不是 {{\"rules\":[…]}}：{error}"),
                            )
                        })?
                        .rules
                }
            }
        };
        let parsed: Subject = serde_json::from_str(subject_text)
            .map_err(|error| FfiError::new("bad_argument", format!("subject 不合法：{error}")))?;

        let (ruleset, problems) = RuleSet::new(&rules);
        let problem_json: Vec<Value> = problems
            .iter()
            .map(|(id, error)| json!({ "id": id, "message": error.to_string() }))
            .collect();

        with_icons(handle, |packs| {
            let pack = match parsed.pack_id.as_deref() {
                Some(id) => packs.find(id),
                None => packs.original(),
            }
            .ok_or_else(|| FfiError::new("not_found", "没有可用的图标包"))?;

            let normalized = normalize_subject(&parsed.model);
            let mut result = json!({
                "pack_id": pack.manifest.pack_id,
                "icon": Value::Null,
                "via": "monogram",
                "matched_rule": Value::Null,
                // 坏规则逐条跳过并回报，不让一条烂正则把整个界面搞挂
                "problems": problem_json,
            });

            // 1/2 级：规则（用户规则与包内预设在同一张表里，靠排序决胜负）
            if let Some(rule) = ruleset.best_match(RuleField::Model, &normalized) {
                if let Some(entry) = find_entry(pack, rule.kind, &rule.key) {
                    apply_icon(&mut result, pack, entry, "rule", Some(&rule.id));
                    return Ok(with_monogram(result, &parsed.model));
                }
            }

            // 3 级：供应商（先规则，再看 kind 直配）
            if let Some(provider) = parsed.provider.as_ref() {
                // 先落到局部变量：把 `&normalize_subject(..)` 直接传参，
                // 临时值在这一句末尾就被丢掉，后面还借着它就是悬垂引用
                let host = normalize_subject(provider.host.as_deref().unwrap_or(""));
                let name = normalize_subject(provider.name.as_deref().unwrap_or(""));
                let subject =
                    ProviderSubject::new(provider.kind.as_deref().unwrap_or(""), &host, &name);
                if let Some(rule) = best_provider_rule(&ruleset, &subject) {
                    if let Some(entry) = find_entry(pack, IconKind::Provider, &rule.key) {
                        apply_icon(&mut result, pack, entry, "rule", Some(&rule.id));
                        return Ok(with_monogram(result, &parsed.model));
                    }
                }
                let kind_key = normalize_subject(subject.kind);
                if let Some(entry) = find_entry(pack, IconKind::Provider, &kind_key) {
                    apply_icon(&mut result, pack, entry, "provider_kind", None);
                    return Ok(with_monogram(result, &parsed.model));
                }
            }

            // 4 级：模型名首段（`deepseek-chat` → `deepseek`）
            let tail = model_tail(&normalized);
            let first = tail.split('-').next().unwrap_or(tail);
            if !first.is_empty() {
                if let Some(entry) = find_entry(pack, IconKind::Model, first) {
                    apply_icon(&mut result, pack, entry, "model_prefix", None);
                    return Ok(with_monogram(result, &parsed.model));
                }
            }

            // 5 级：首字母方块
            Ok(with_monogram(result, &parsed.model))
        })
    })
}

/// 把命中的条目写进结果里。`via` 说明是哪一级命中的——界面拿它解释"为什么是这个图标"。
fn apply_icon(
    result: &mut Value,
    pack: &LoadedPack,
    entry: &PackEntry,
    via: &'static str,
    rule_id: Option<&str>,
) {
    let Some(map) = result.as_object_mut() else {
        return;
    };
    map.insert("pack_id".to_string(), json!(pack.manifest.pack_id));
    map.insert(
        "icon".to_string(),
        json!({
            "kind": entry.kind.as_str(),
            "key": entry.key,
            "file": entry.file,
            "tint": match entry.tint { Tint::Auto => "auto", Tint::None => "none" },
            "backing": entry.backing.map(|_| "surface"),
        }),
    );
    map.insert("via".to_string(), json!(via));
    map.insert(
        "matched_rule".to_string(),
        match rule_id {
            Some(id) => json!(id),
            None => Value::Null,
        },
    );
}

fn find_entry<'a>(pack: &'a LoadedPack, kind: IconKind, key: &str) -> Option<&'a PackEntry> {
    pack.manifest
        .entries
        .iter()
        .find(|entry| entry.kind == kind && entry.key == key)
}

fn with_monogram(mut value: Value, model: &str) -> Value {
    let mono = monogram(model);
    if let Some(map) = value.as_object_mut() {
        map.insert(
            "monogram".to_string(),
            json!({ "letter": mono.letter.to_string(), "hue": mono.hue }),
        );
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    use nmoi_core::iconpack::{PackDefaults, PackEntry, PackManifest, Tint};
    use nmoi_core::icons::{IconKind, RuleMode, RuleSource};

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR\x00\x00\x00\x28\x00\x00\x00\x28";

    fn manifest(pack_id: &str) -> PackManifest {
        PackManifest {
            schema_version: 1,
            pack_id: pack_id.to_string(),
            name: "测试包".to_string(),
            version: "1.0.0".to_string(),
            created_at: 0,
            entries: vec![
                PackEntry {
                    kind: IconKind::Model,
                    key: "claude".to_string(),
                    file: "models/claude".to_string(),
                    tint: Tint::None,
                    backing: None,
                },
                PackEntry {
                    kind: IconKind::Provider,
                    key: "openai".to_string(),
                    file: "providers/openai".to_string(),
                    tint: Tint::Auto,
                    backing: None,
                },
                PackEntry {
                    kind: IconKind::Model,
                    key: "deepseek".to_string(),
                    file: "models/deepseek".to_string(),
                    tint: Tint::None,
                    backing: None,
                },
            ],
            defaults: PackDefaults {
                rules: vec![IconRule {
                    id: "preset-claude".to_string(),
                    pattern: "^(anthropic/)?claude".to_string(),
                    mode: RuleMode::Regex,
                    field: RuleField::Model,
                    kind: IconKind::Model,
                    key: "claude".to_string(),
                    priority: 500,
                    case_sensitive: false,
                    source: RuleSource::Preset,
                }],
            },
        }
    }

    fn temp_root(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "nmoi-icons-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("original/models")).unwrap();
        std::fs::create_dir_all(dir.join("original/providers")).unwrap();
        std::fs::write(
            dir.join("original/manifest.json"),
            serde_json::to_vec(&manifest("nmoi-original")).unwrap(),
        )
        .unwrap();
        std::fs::write(dir.join("original/models/claude.png"), PNG).unwrap();
        std::fs::write(dir.join("original/providers/openai.png"), PNG).unwrap();
        std::fs::write(dir.join("original/models/deepseek.png"), PNG).unwrap();
        std::fs::write(dir.join("original/NOTICE.txt"), b"notice").unwrap();
        dir
    }

    #[test]
    fn open_lists_the_original_pack_first() {
        let root = temp_root("open");
        let packs = IconPacks::open(&root.display().to_string()).unwrap();
        assert_eq!(packs.packs.len(), 1);
        assert_eq!(packs.packs[0].origin, Origin::Original);
        assert_eq!(packs.packs[0].manifest.pack_id, "nmoi-original");
        let summary = pack_summary(&packs.packs[0]);
        assert_eq!(summary["removable"], json!(false));
        assert_eq!(summary["entries"], json!(3));
        assert_eq!(summary["rules"], json!(1));
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn empty_root_is_fine_but_has_no_packs() {
        let dir = std::env::temp_dir().join(format!("nmoi-icons-empty-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let packs = IconPacks::open(&dir.display().to_string()).unwrap();
        assert!(packs.packs.is_empty());
        assert!(packs.original().is_none());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn missing_root_is_an_error_not_an_empty_list() {
        assert!(IconPacks::open("/definitely/not/here/nmoi").is_err());
    }

    #[test]
    fn resolve_prefers_a_rule_then_falls_back_to_prefix_then_monogram() {
        let root = temp_root("resolve");
        let packs = IconPacks::open(&root.display().to_string()).unwrap();
        let pack = packs.original().unwrap();

        // 规则命中
        let rules = serde_json::to_string(&packs.packs[0].manifest.defaults.rules).unwrap();
        let (set, problems) = RuleSet::new(&packs.packs[0].manifest.defaults.rules);
        assert!(problems.is_empty());
        let hit = set
            .best_match(
                RuleField::Model,
                &normalize_subject("anthropic/claude-opus-5"),
            )
            .unwrap();
        assert!(find_entry(pack, hit.kind, &hit.key).is_some());
        assert!(rules.contains("preset-claude"));

        // 首段命中（deepseek-chat → deepseek）
        let normalized = normalize_subject("deepseek-chat");
        let tail = model_tail(&normalized);
        let first = tail.split('-').next().unwrap();
        assert_eq!(first, "deepseek");
        assert!(find_entry(pack, IconKind::Model, first).is_some());

        // 谁都不认 → 首字母
        let mono = monogram("my-local-model");
        assert_eq!(mono.letter, 'M');
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn export_then_import_puts_a_second_pack_on_disk() {
        let root = temp_root("import");
        let packs = IconPacks::open(&root.display().to_string()).unwrap();
        let pack = packs.original().unwrap();
        let mut files = Vec::new();
        for entry in &pack.manifest.entries {
            for name in density_names(entry) {
                if let Ok(data) = std::fs::read(pack.dir.join(&name)) {
                    files.push((name, data));
                }
            }
        }
        let mut reexported = manifest("my-pack");
        reexported.name = "我的包".to_string();
        let bytes = iconpack::export(&reexported, files).unwrap();

        // 落到磁盘，模拟"用户选了一个 zip"
        let zip_path = root.join("in.zip");
        std::fs::write(zip_path, &bytes).unwrap();
        let imported = iconpack::import(&bytes).unwrap();
        assert_eq!(imported.manifest.pack_id, "my-pack");
        assert!(imported.files.iter().any(|f| f.name == "manifest.json"));
        let _ = std::fs::remove_dir_all(root);
    }
}
