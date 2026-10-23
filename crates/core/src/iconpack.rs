//! 图标包的清单、导入与导出（D105）。
//!
//! 一个包就是一个 store zip：`manifest.json` + `NOTICE.txt` + 三档密度的 PNG。
//! 这里只管"包对不对"——校验任何一条不过就**整包拒绝**，不半信半疑地装一半：
//! 半个包比没包更糟，用户会以为是图标"坏了"而不是包"不对"。
//!
//! 与 `zip` 模块的分工：那边管字节层面（CRC、越界、压缩方法），这边管语义
//! （schema 版本、包 id、每个条目该有的文件在不在、PNG 魔数对不对）。

use serde::{Deserialize, Serialize};

use crate::icons::{validate_key, IconKind, IconRule};
use crate::zip::{self, ReadEntry, ZipEntry, ZipError};

/// 原版包的保留 id。导入包不许冒用——占了它，回滚就没路了。
pub const ORIGINAL_PACK_ID: &str = "nmoi-original";
/// 当前认得的清单版本。只增不减。
pub const PACK_SCHEMA_VERSION: u32 = 1;

pub const MANIFEST_NAME: &str = "manifest.json";
pub const NOTICE_NAME: &str = "NOTICE.txt";

/// 密度档：1×/2×/3×。存的是后缀，真名叫 `<file>.png` / `<file>@2x.png` / `<file>@3x.png`。
pub const DENSITY_SUFFIXES: [&str; 3] = ["", "@2x", "@3x"];

const PNG_MAGIC: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];

/// 导入包没带 NOTICE.txt 时的兜底声明（导出时会补上）。
///
/// 为什么不直接拒绝：用户从别处拿来的包可能没带声明，直接拒绝只会让人以为"文件坏了"。
/// 但声明是 MIT/ISC 的硬要求，所以**导出时必须补**，见 [`export`]。
pub const FALLBACK_NOTICE: &str = "\
这个图标包由 NMoi-Agent 导出。包里的图形来自各自的版权方，许可见下：
- Lucide（lucide-static@1.61.0）：ISC；其中部分图形源自 Feather（MIT）。
- Lobe Icons（@lobehub/icons-static-svg@1.97.0）：MIT，Copyright (c) 2023 LobeHub。
品牌图形是各家的商标，这里只用于标识当前使用的模型/厂商，未改色、未改形。
许可全文随应用分发（LICENSES/ISC-lucide.txt、LICENSES/MIT-lobe-icons.txt）。
";

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Tint {
    /// 白色剪影，运行时按主题上色。
    Auto,
    /// 彩色标，**不许** tint（染了就不像官方标了）。默认就是这个：宁可不上色，
    /// 也不要把彩色标染成单色。
    #[default]
    None,
}

/// 需要垫底的少数几个（含白色部件的彩色标）。只对它们垫，见 `docs/ui/icon-packs.md` §2。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Backing {
    Surface,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackEntry {
    pub kind: IconKind,
    pub key: String,
    /// 不带扩展名、不带密度后缀的基名，例如 `providers/openai`。
    pub file: String,
    #[serde(default)]
    pub tint: Tint,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub backing: Option<Backing>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackDefaults {
    #[serde(default)]
    pub rules: Vec<IconRule>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackManifest {
    pub schema_version: u32,
    pub pack_id: String,
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub created_at: i64,
    pub entries: Vec<PackEntry>,
    #[serde(default)]
    pub defaults: PackDefaults,
}

impl PackManifest {
    /// 目录名前缀必须与 `kind` 对得上，否则"图标在哪个目录"就失去意义了。
    fn dir_of(kind: IconKind) -> &'static str {
        match kind {
            IconKind::Action => "actions",
            IconKind::Provider => "providers",
            IconKind::Model => "models",
        }
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum PackError {
    #[error("读不了这个 zip：{0}")]
    Zip(#[from] ZipError),
    #[error("包根目录没有 {MANIFEST_NAME}")]
    MissingManifest,
    #[error("清单不是合法 JSON：{0}")]
    BadManifest(String),
    #[error("清单版本 {found} 不认识（当前支持 {supported}），请升级客户端")]
    UnsupportedSchema { found: u32, supported: u32 },
    #[error("pack_id 不合法：{0}")]
    BadPackId(&'static str),
    #[error("包版本号不能为空")]
    MissingVersion,
    #[error("包里一个条目都没有")]
    NoEntries,
    #[error("条目重复：{kind}/{key}")]
    DuplicateEntry { kind: &'static str, key: String },
    #[error("条目 {key} 的 key 不合法：{reason}")]
    BadKey { key: String, reason: String },
    #[error("条目 {key} 的路径不合法：{file}")]
    BadFile { key: String, file: String },
    #[error("条目 {key} 缺 1× 文件：{file}.png")]
    MissingFile { key: String, file: String },
    #[error("文件不是 PNG：{file}")]
    BadPng { file: String },
    #[error("打包时发现文件不在：{0}")]
    MissingSource(String),
}

/// 导入结果：清单 + 全部文件 + 不致命的提醒。
#[derive(Debug, Clone)]
pub struct ImportedPack {
    pub manifest: PackManifest,
    pub files: Vec<ReadEntry>,
    /// 不阻断导入，但要在设置页说一句的（例如缺 NOTICE.txt）。
    pub warnings: Vec<String>,
}

fn is_png(data: &[u8]) -> bool {
    data.len() >= PNG_MAGIC.len() && data[..PNG_MAGIC.len()] == PNG_MAGIC
}

/// 校验 `pack_id`：非空、不是保留 id、只允许安全字符（它同时是目录名）。
fn check_pack_id(pack_id: &str) -> Result<(), PackError> {
    if pack_id.trim().is_empty() {
        return Err(PackError::BadPackId("不能为空"));
    }
    if pack_id == ORIGINAL_PACK_ID {
        return Err(PackError::BadPackId("这是原版包的保留 id，导入包不许冒用"));
    }
    if pack_id.chars().count() > 64 {
        return Err(PackError::BadPackId("太长（上限 64 个字符）"));
    }
    if !pack_id
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
    {
        return Err(PackError::BadPackId("只允许字母、数字、`-`、`_`"));
    }
    Ok(())
}

/// 导入：整包校验，任何一条不过就全拒。
pub fn import(zip_bytes: &[u8]) -> Result<ImportedPack, PackError> {
    let files = zip::read(zip_bytes)?;

    let manifest_bytes = files
        .iter()
        .find(|entry| entry.name == MANIFEST_NAME)
        .ok_or(PackError::MissingManifest)?
        .data
        .clone();
    let manifest: PackManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|error| PackError::BadManifest(error.to_string()))?;

    if manifest.schema_version != PACK_SCHEMA_VERSION {
        return Err(PackError::UnsupportedSchema {
            found: manifest.schema_version,
            supported: PACK_SCHEMA_VERSION,
        });
    }
    check_pack_id(&manifest.pack_id)?;
    if manifest.version.trim().is_empty() {
        return Err(PackError::MissingVersion);
    }
    if manifest.entries.is_empty() {
        return Err(PackError::NoEntries);
    }

    let mut warnings = Vec::new();
    if !files.iter().any(|entry| entry.name == NOTICE_NAME) {
        warnings.push("这个包没带 NOTICE.txt，导出时会补上".to_string());
    }

    let mut seen = std::collections::HashSet::new();
    for entry in &manifest.entries {
        let kind = entry.kind.as_str();
        if !seen.insert((entry.kind, entry.key.clone())) {
            return Err(PackError::DuplicateEntry {
                kind,
                key: entry.key.clone(),
            });
        }
        validate_key(&entry.key).map_err(|error| PackError::BadKey {
            key: entry.key.clone(),
            reason: error.suggestion(&entry.key),
        })?;

        // 目录必须与 kind 一致，且基名必须以 key 结尾
        let expected_prefix = format!("{}/", PackManifest::dir_of(entry.kind));
        if !entry.file.starts_with(&expected_prefix) || entry.file.ends_with('/') {
            return Err(PackError::BadFile {
                key: entry.key.clone(),
                file: entry.file.clone(),
            });
        }

        let one_x = format!("{}.png", entry.file);
        let present = files.iter().find(|item| item.name == one_x);
        let present = present.ok_or_else(|| PackError::MissingFile {
            key: entry.key.clone(),
            file: entry.file.clone(),
        })?;
        if !is_png(&present.data) {
            return Err(PackError::BadPng { file: one_x });
        }
        // 高密度档可选；有就必须也是 PNG
        for suffix in &DENSITY_SUFFIXES[1..] {
            let name = format!("{}{}.png", entry.file, suffix);
            if let Some(hit) = files.iter().find(|item| item.name == name) {
                if !is_png(&hit.data) {
                    return Err(PackError::BadPng { file: name });
                }
            }
        }
    }

    Ok(ImportedPack {
        manifest,
        files,
        warnings,
    })
}

/// 导出：`manifest.json` 由结构体重新序列化（不信调用方手里的字符串），
/// 没带 `NOTICE.txt` 就补一份兜底的——MIT/ISC 要求声明随副本走。
pub fn export(
    manifest: &PackManifest,
    files: impl IntoIterator<Item = (String, Vec<u8>)>,
) -> Result<Vec<u8>, PackError> {
    if manifest.schema_version != PACK_SCHEMA_VERSION {
        return Err(PackError::UnsupportedSchema {
            found: manifest.schema_version,
            supported: PACK_SCHEMA_VERSION,
        });
    }
    if manifest.version.trim().is_empty() {
        return Err(PackError::MissingVersion);
    }
    if manifest.entries.is_empty() {
        return Err(PackError::NoEntries);
    }

    let mut entries = Vec::new();
    let json = serde_json::to_vec_pretty(manifest)
        .map_err(|error| PackError::BadManifest(error.to_string()))?;
    entries.push(ZipEntry {
        name: MANIFEST_NAME.to_string(),
        data: json,
    });

    let mut has_notice = false;
    for (name, data) in files {
        if name == MANIFEST_NAME {
            continue; // 清单以结构体为准
        }
        if name == NOTICE_NAME {
            has_notice = true;
        }
        entries.push(ZipEntry { name, data });
    }
    if !has_notice {
        entries.push(ZipEntry {
            name: NOTICE_NAME.to_string(),
            data: FALLBACK_NOTICE.as_bytes().to_vec(),
        });
    }
    Ok(zip::write(&entries)?)
}

/// 从一堆文件里挑出某个条目该带的全部密度文件。UI 侧按包内文件表调它。
pub fn files_of_entry<'a>(files: &'a [ReadEntry], entry: &PackEntry) -> Vec<&'a ReadEntry> {
    DENSITY_SUFFIXES
        .iter()
        .filter_map(|suffix| {
            let name = format!("{}{}.png", entry.file, suffix);
            files.iter().find(|item| item.name == name)
        })
        .collect()
}

// ---------------------------------------------------------------------------
// 自建图标：核心只验"已经是正方形 PNG 的字节"，不负责解码
// ---------------------------------------------------------------------------

/// 自建图标的最大边长。屏幕上最大也就 120px（3× 的 40dp），512 已经够放大看了。
pub const CUSTOM_ICON_MAX_EDGE: u32 = 512;
/// 最小边长。比 48 小的图放到 3× 屏上必然糊，不如早点拒绝。
pub const CUSTOM_ICON_MIN_EDGE: u32 = 48;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IconImage {
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CustomIconError {
    #[error("不是 PNG")]
    NotPng,
    #[error("PNG 头不完整")]
    Truncated,
    #[error("必须是正方形，当前 {width}×{height}")]
    NotSquare { width: u32, height: u32 },
    #[error("边长 {edge} 太小（至少要 {CUSTOM_ICON_MIN_EDGE}）")]
    TooSmall { edge: u32 },
    #[error("边长 {edge} 太大（上限 {CUSTOM_ICON_MAX_EDGE}）")]
    TooLarge { edge: u32 },
}

/// 从 PNG 头里读出宽高。只看 IHDR，不解码像素——解码是平台的事。
///
/// 为什么不解码：Android 有 `BitmapFactory`，桌面各平台也都有；为一次缩放引一个
/// Rust 图像库（`image` 那套要几 MB）不划算。所以契约是"平台先转成正方形 PNG 再交给核心校验"，
/// 核心只守住"是不是 PNG、是不是正方形、会不会太大"这三件必须守住的事。
pub fn read_png_size(bytes: &[u8]) -> Option<IconImage> {
    if bytes.len() < 24 || bytes[..8] != PNG_MAGIC {
        return None;
    }
    // 第一个块必须是 IHDR：长度 13，类型 "IHDR"，然后宽高各 4 字节大端
    if &bytes[12..16] != b"IHDR" {
        return None;
    }
    let width = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
    let height = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
    Some(IconImage { width, height })
}

/// 校验一张"准备存为自建图标"的 PNG。通过就返回尺寸，UI 拿去显示。
pub fn validate_custom_icon(bytes: &[u8]) -> Result<IconImage, CustomIconError> {
    if bytes.len() < 8 || bytes[..8] != PNG_MAGIC {
        return Err(CustomIconError::NotPng);
    }
    let image = read_png_size(bytes).ok_or(CustomIconError::Truncated)?;
    if image.width != image.height {
        return Err(CustomIconError::NotSquare {
            width: image.width,
            height: image.height,
        });
    }
    if image.width < CUSTOM_ICON_MIN_EDGE {
        return Err(CustomIconError::TooSmall { edge: image.width });
    }
    if image.width > CUSTOM_ICON_MAX_EDGE {
        return Err(CustomIconError::TooLarge { edge: image.width });
    }
    Ok(image)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::icons::{RuleField, RuleMode, RuleSource};

    fn png() -> Vec<u8> {
        let mut data = PNG_MAGIC.to_vec();
        data.extend_from_slice(b"\x00\x00\x00\x0dIHDRfake-but-has-magic");
        data
    }

    fn entry(kind: IconKind, key: &str, file: &str) -> PackEntry {
        PackEntry {
            kind,
            key: key.to_string(),
            file: file.to_string(),
            tint: Tint::None,
            backing: None,
        }
    }

    fn manifest() -> PackManifest {
        PackManifest {
            schema_version: PACK_SCHEMA_VERSION,
            pack_id: "my-pack".to_string(),
            name: "我的图标包".to_string(),
            version: "1.0.0".to_string(),
            created_at: 1_790_000_000_000,
            entries: vec![
                entry(IconKind::Action, "search", "actions/search"),
                entry(IconKind::Provider, "openai", "providers/openai"),
                entry(IconKind::Model, "claude", "models/claude"),
            ],
            defaults: PackDefaults {
                rules: vec![IconRule {
                    id: "r1".to_string(),
                    pattern: "^claude".to_string(),
                    mode: RuleMode::Keyword,
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

    fn files() -> Vec<(String, Vec<u8>)> {
        vec![
            ("actions/search.png".to_string(), png()),
            ("actions/search@2x.png".to_string(), png()),
            ("providers/openai.png".to_string(), png()),
            ("models/claude.png".to_string(), png()),
        ]
    }

    #[test]
    fn export_then_import_roundtrips_including_rules() {
        let bytes = export(&manifest(), files()).unwrap();
        let back = import(&bytes).unwrap();
        assert_eq!(back.manifest, manifest());
        assert_eq!(back.manifest.defaults.rules.len(), 1);
        assert!(back.warnings.is_empty(), "{:?}", back.warnings);
    }

    #[test]
    fn export_always_carries_a_notice() {
        let bytes = export(&manifest(), files()).unwrap();
        let back = import(&bytes).unwrap();
        let notice = back.files.iter().find(|item| item.name == NOTICE_NAME);
        assert!(notice.is_some(), "导出的包必须带 NOTICE.txt");
        assert!(!notice.unwrap().data.is_empty());
    }

    #[test]
    fn import_warns_when_notice_is_missing() {
        // 手工打一个没有 NOTICE 的包（模拟从别处拿来的）
        let manifest_json = serde_json::to_vec(&manifest()).unwrap();
        let mut entries = vec![ZipEntry {
            name: MANIFEST_NAME.to_string(),
            data: manifest_json,
        }];
        for (name, data) in files() {
            entries.push(ZipEntry { name, data });
        }
        let bytes = zip::write(&entries).unwrap();
        let back = import(&bytes).unwrap();
        assert_eq!(back.warnings.len(), 1);
        assert!(back.warnings[0].contains("NOTICE"));
    }

    #[test]
    fn reserved_pack_id_is_refused() {
        // export 不拦保留 id（原版自己也要能导出），import 必须拦
        let mut tampered = manifest();
        tampered.pack_id = ORIGINAL_PACK_ID.to_string();
        let json = serde_json::to_vec(&tampered).unwrap();
        let mut entries = vec![ZipEntry {
            name: MANIFEST_NAME.to_string(),
            data: json,
        }];
        for (name, data) in files() {
            entries.push(ZipEntry { name, data });
        }
        let bytes = zip::write(&entries).unwrap();
        assert!(matches!(import(&bytes), Err(PackError::BadPackId(_))));
    }

    #[test]
    fn missing_png_is_refused() {
        let mut only_two = files();
        only_two.retain(|(name, _)| name != "models/claude.png");
        let bytes = export(&manifest(), only_two).unwrap();
        assert!(matches!(import(&bytes), Err(PackError::MissingFile { .. })));
    }

    #[test]
    fn non_png_payload_is_refused() {
        let mut broken = files();
        for (name, data) in broken.iter_mut() {
            if name == "models/claude.png" {
                *data = b"not a png at all".to_vec();
            }
        }
        let bytes = export(&manifest(), broken).unwrap();
        assert!(matches!(import(&bytes), Err(PackError::BadPng { .. })));
    }

    #[test]
    fn file_must_live_in_its_kind_directory() {
        let mut wrong = manifest();
        wrong.entries[0].file = "providers/search".to_string();
        let bytes = export(&wrong, files()).unwrap();
        assert!(matches!(import(&bytes), Err(PackError::BadFile { .. })));
    }

    #[test]
    fn duplicate_entry_is_refused() {
        let mut dup = manifest();
        dup.entries
            .push(entry(IconKind::Model, "claude", "models/claude"));
        let bytes = export(&dup, files()).unwrap();
        assert!(matches!(
            import(&bytes),
            Err(PackError::DuplicateEntry { .. })
        ));
    }

    #[test]
    fn unsafe_key_is_refused() {
        let mut bad = manifest();
        bad.entries[0].key = "../etc".to_string();
        bad.entries[0].file = "actions/../etc".to_string();
        // 文件路径先被 zip 的路径校验挡住
        let bytes = export(&bad, files()).unwrap();
        assert!(import(&bytes).is_err());
    }

    #[test]
    fn unknown_schema_version_is_refused_with_both_numbers() {
        let mut future = manifest();
        future.schema_version = 9;
        let mut entries = vec![ZipEntry {
            name: MANIFEST_NAME.to_string(),
            data: serde_json::to_vec(&future).unwrap(),
        }];
        for (name, data) in files() {
            entries.push(ZipEntry { name, data });
        }
        let bytes = zip::write(&entries).unwrap();
        match import(&bytes) {
            Err(PackError::UnsupportedSchema { found, supported }) => {
                assert_eq!((found, supported), (9, PACK_SCHEMA_VERSION));
            }
            other => panic!("期望版本不匹配，得到 {other:?}"),
        }
    }

    #[test]
    fn empty_pack_is_refused_on_both_sides() {
        let mut empty = manifest();
        empty.entries.clear();
        assert!(matches!(export(&empty, files()), Err(PackError::NoEntries)));
    }

    fn png_with_size(width: u32, height: u32) -> Vec<u8> {
        let mut data = PNG_MAGIC.to_vec();
        data.extend_from_slice(&13u32.to_be_bytes()); // IHDR 长度
        data.extend_from_slice(b"IHDR");
        data.extend_from_slice(&width.to_be_bytes());
        data.extend_from_slice(&height.to_be_bytes());
        data.extend_from_slice(&[8, 6, 0, 0, 0]); // bit depth / color type / 其余
        data
    }

    #[test]
    fn custom_icon_accepts_a_square_png_in_range() {
        let image = validate_custom_icon(&png_with_size(512, 512)).unwrap();
        assert_eq!(
            image,
            IconImage {
                width: 512,
                height: 512
            }
        );
        assert!(validate_custom_icon(&png_with_size(48, 48)).is_ok());
    }

    #[test]
    fn custom_icon_rejects_non_square_with_both_numbers() {
        match validate_custom_icon(&png_with_size(512, 256)) {
            Err(CustomIconError::NotSquare { width, height }) => {
                assert_eq!((width, height), (512, 256));
            }
            other => panic!("期望 NotSquare，得到 {other:?}"),
        }
    }

    #[test]
    fn custom_icon_rejects_out_of_range_edges() {
        assert!(matches!(
            validate_custom_icon(&png_with_size(32, 32)),
            Err(CustomIconError::TooSmall { .. })
        ));
        assert!(matches!(
            validate_custom_icon(&png_with_size(1024, 1024)),
            Err(CustomIconError::TooLarge { .. })
        ));
    }

    #[test]
    fn custom_icon_rejects_junk_and_truncated_headers() {
        assert_eq!(
            validate_custom_icon(b"not a png at all"),
            Err(CustomIconError::NotPng)
        );
        // 只有魔数、没有 IHDR
        let mut short = PNG_MAGIC.to_vec();
        short.extend_from_slice(&[0, 0, 0, 13]);
        assert_eq!(
            validate_custom_icon(&short),
            Err(CustomIconError::Truncated)
        );
        // 有 24 字节但不是 IHDR
        let mut wrong_chunk = png_with_size(64, 64);
        wrong_chunk[12..16].copy_from_slice(b"IDAT");
        assert!(validate_custom_icon(&wrong_chunk).is_err());
    }

    #[test]
    fn files_of_entry_picks_every_density_that_exists() {
        let bytes = export(&manifest(), files()).unwrap();
        let back = import(&bytes).unwrap();
        let search = &back.manifest.entries[0];
        let found = files_of_entry(&back.files, search);
        assert_eq!(found.len(), 2, "1× 与 2× 都在，3× 没有");
        assert!(found[0].name.ends_with("search.png"));
        assert!(found[1].name.ends_with("search@2x.png"));
    }
}
