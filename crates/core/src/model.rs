//! 模型目录与名字匹配（D18 的实现侧）。
//!
//! 现实问题：同一个模型在不同 provider 里叫法不同——
//! `claude-opus-5.5`、`Claude-Opus-5-5`、`opus5.5`、`opus5-5`、`[分组A]opus5-5`、
//! `[分组A]ClaudeAuto`、`[分组B]Claude`。
//!
//! 所以分三步：
//!
//! 1. **归一化**：剥掉 `[分组]` 前缀，`.`/`-`/`_`/空格视为同一种分隔符，转小写；
//! 2. **解析**：把尾部的版本号切出来（`5-5` → `5.5`）；
//! 3. **匹配**：精确别名 → 家族+版本 → 家族最新 → 宽松包含 → 未知默认。
//!
//! **匹配结果必须对用户可见**（D19）：UI 上要能显示"这些参数来自规则 X，可手动覆盖"。
//! 正则填出来的默认值不能装作是事实。

use std::fmt;

use serde::{Deserialize, Serialize};

use crate::provider::{
    ProviderCapabilities, ProviderKind, ThinkingEffort, ThinkingField, ThinkingSetting,
};

/// 可比较的版本号：`5.5 > 5.4`，`5.10 > 5.9`，`5.5.1 > 5.5`。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Version(pub Vec<u32>);

impl Version {
    pub fn new<const N: usize>(parts: [u32; N]) -> Self {
        Self(parts.to_vec())
    }

    pub fn major(&self) -> Option<u32> {
        self.0.first().copied()
    }
}

impl fmt::Display for Version {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = self
            .0
            .iter()
            .map(|part| part.to_string())
            .collect::<Vec<_>>()
            .join(".");
        f.write_str(&text)
    }
}

/// 归一化后的名字。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedName {
    /// 去掉分组前缀与版本号之后的全名（已归一化，分隔符统一为 `-`）。
    pub normalized: String,
    /// 家族名：从 normalized 里再剥掉尾部版本号。
    pub family: String,
    pub version: Option<Version>,
    /// 原始 id 里的 `[分组]` 前缀（如果存在）。
    pub group: Option<String>,
}

/// 归一化：剥分组前缀 → 转小写 → 统一分隔符。
pub fn normalize(raw: &str) -> (String, Option<String>) {
    let mut text = raw.trim();
    let mut group = None;

    // `[分组A]Claude` / `[分组 A] claude`：允许前缀里有空格
    while text.starts_with('[') {
        match text.find(']') {
            Some(end) => {
                if group.is_none() {
                    group = Some(text[1..end].trim().to_string());
                }
                text = text[end + 1..].trim_start();
            }
            None => break,
        }
    }

    let normalized = text
        .to_lowercase()
        .chars()
        .map(|ch| match ch {
            '.' | '_' | ' ' | '\t' | '/' | ':' => '-',
            other => other,
        })
        .collect::<String>()
        // 连续分隔符合并
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");

    (normalized, group)
}

fn split_trailing_version(normalized: &str) -> (String, Option<Version>) {
    let bytes = normalized.as_bytes();
    if !bytes.last().map(|b| b.is_ascii_digit()).unwrap_or(false) {
        return (normalized.to_string(), None);
    }
    let mut start = normalized.len();
    while start > 0 {
        let ch = bytes[start - 1] as char;
        if ch.is_ascii_digit() || ch == '.' || ch == '-' || ch == '_' {
            start -= 1;
        } else {
            break;
        }
    }
    let version_text = &normalized[start..];
    let parts: Vec<u32> = version_text
        .split(['.', '-', '_'])
        .filter(|part| !part.is_empty())
        .filter_map(|part| part.parse().ok())
        .collect();
    if parts.is_empty() {
        return (normalized.to_string(), None);
    }
    let family = normalized[..start]
        .trim_end_matches(['-', '_', '.'])
        .to_string();
    if family.is_empty() {
        // 纯数字的名字，别把它切成空家族
        return (normalized.to_string(), None);
    }
    (family, Some(Version(parts)))
}

/// 解析一个模型名。
pub fn parse_model_name(raw: &str) -> ParsedName {
    let (normalized, group) = normalize(raw);
    let (family, version) = split_trailing_version(&normalized);
    ParsedName {
        normalized,
        family,
        version,
        group,
    }
}

/// 参数从哪来。UI 必须显示这个（D19）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MatchSource {
    /// 精确命中目录里的别名。
    Alias,
    /// 家族 + 版本都命中。
    FamilyVersion,
    /// 只命中家族，按该家族最新版本处理。
    FamilyLatest,
    /// 家族是"名字里包含"命中的（如 `claudeauto` → `claude`）。
    FamilyFuzzy,
    /// 什么都没命中，用默认参数。
    Unknown,
}

impl MatchSource {
    pub fn as_str(self) -> &'static str {
        match self {
            MatchSource::Alias => "alias",
            MatchSource::FamilyVersion => "family_version",
            MatchSource::FamilyLatest => "family_latest",
            MatchSource::FamilyFuzzy => "family_fuzzy",
            MatchSource::Unknown => "unknown",
        }
    }

    /// 给用户看的一句话。未知来源要把"可以手动覆盖"说清楚。
    pub fn explain(self) -> &'static str {
        match self {
            MatchSource::Alias => "参数来自目录里的精确条目",
            MatchSource::FamilyVersion => "参数来自该家族的对应版本",
            MatchSource::FamilyLatest => "只认出家族，按该家族最新版本处理",
            MatchSource::FamilyFuzzy => "家族是按名字包含关系猜的，建议核对",
            MatchSource::Unknown => "没认出这个模型，参数是默认值，可手动覆盖",
        }
    }

    /// 是否需要 UI 提醒用户"这是猜的"。
    pub fn needs_review(self) -> bool {
        matches!(self, MatchSource::FamilyFuzzy | MatchSource::Unknown)
    }
}

/// 价格（每百万 token，美元）。只用于显示与预算估算，不参与路由决策。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct CostInfo {
    #[serde(default)]
    pub input_per_million: f64,
    #[serde(default)]
    pub output_per_million: f64,
}

/// 一个模型的能力与默认参数。
///
/// 注意 `reasoning` 与 `thinking` 的分工：前者是"这个模型支不支持思考"（模型事实），
/// 后者是"用哪个字段族去表达"（provider 事实）。快照里只存前者，
/// 字段族在 `resolve()` 时按 provider 类型补上。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelProfile {
    pub capabilities: ProviderCapabilities,
    pub thinking: ThinkingSetting,
    /// 该模型是否支持推理 / 思考。
    #[serde(default)]
    pub reasoning: bool,
    /// 支持哪些强度档位（来自元数据 `reasoning_options`）。
    /// **空表示"没数据"**，不是"不支持"——UI 这时只给"关闭/自动"两个位置。
    #[serde(default)]
    pub supported_efforts: Vec<ThinkingEffort>,
    /// 是否只能开/关、没有档位（元数据里形态是 `toggle`）。
    #[serde(default)]
    pub supports_toggle: bool,
    pub context_window: u64,
    pub max_output: u64,
    #[serde(default)]
    pub cost: Option<CostInfo>,
}

impl ModelProfile {
    /// 未知模型时的保守默认：能流式，别的都按"没有"处理。
    pub fn conservative(kind: ProviderKind) -> Self {
        let _ = kind;
        Self {
            capabilities: ProviderCapabilities::conservative(),
            // 未知模型不猜它会思考：注错字段可能直接 400。UI 上提示用户手动开。
            thinking: ThinkingSetting::disabled(),
            reasoning: false,
            supported_efforts: Vec::new(),
            supports_toggle: false,
            context_window: 32_768,
            max_output: 4_096,
            cost: None,
        }
    }
}

/// 目录条目。一个条目 = 一个（家族, 版本）组合，或者一个别名。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CatalogEntry {
    /// 归一化后的可匹配 id（别名）或 `家族-版本`。
    pub id: String,
    pub family: String,
    pub version: Option<Version>,
    pub profile: ModelProfile,
    /// 显式别名列表（比如 `deepseek-v4-1-flash` 这种版本在中间的名字）。
    #[serde(default)]
    pub aliases: Vec<String>,
}

impl CatalogEntry {
    pub fn new(
        id: impl Into<String>,
        family: impl Into<String>,
        version: Option<Version>,
        profile: ModelProfile,
    ) -> Self {
        Self {
            id: id.into(),
            family: family.into(),
            version,
            profile,
            aliases: Vec::new(),
        }
    }

    pub fn with_aliases(mut self, aliases: impl IntoIterator<Item = impl Into<String>>) -> Self {
        self.aliases = aliases.into_iter().map(Into::into).collect();
        self
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ResolvedModel {
    pub raw_id: String,
    pub parsed: SerializedParsedName,
    pub profile: ModelProfile,
    pub matched_by: MatchSource,
    /// 命中时记录目录里的 id，便于 UI 显示"来自哪个条目"。
    pub matched_entry: Option<String>,
}

/// `ParsedName` 的可序列化镜像（`ParsedName` 里有 `Version`，这里只为一处方便）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SerializedParsedName {
    pub normalized: String,
    pub family: String,
    pub version: Option<Version>,
    pub group: Option<String>,
}

/// 模型目录。默认空；由 vendored 快照 + 用户覆盖层填充（D19）。
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ModelRegistry {
    entries: Vec<CatalogEntry>,
}

impl ModelRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// 从快照装载（离线数据）。快照里的条目按顺序 upsert，
    /// 之后用户覆盖层再 upsert 同 id 就能盖掉。
    pub fn from_snapshot(snapshot: &ModelSnapshot) -> Self {
        snapshot.to_registry()
    }

    /// 从 JSON 文本装载快照。
    pub fn load_snapshot_json(text: &str) -> Result<Self, serde_json::Error> {
        Ok(ModelSnapshot::from_json(text)?.to_registry())
    }

    /// 加入或覆盖一个条目。用户覆盖层用同一个方法（同 id 覆盖）。
    pub fn upsert(&mut self, entry: CatalogEntry) {
        if let Some(existing) = self.entries.iter_mut().find(|item| item.id == entry.id) {
            *existing = entry;
        } else {
            self.entries.push(entry);
        }
    }

    pub fn entries(&self) -> &[CatalogEntry] {
        &self.entries
    }

    /// 某家族的最新版本。
    pub fn latest_version(&self, family: &str) -> Option<Version> {
        self.entries
            .iter()
            .filter(|entry| entry.family == family)
            .filter_map(|entry| entry.version.clone())
            .max()
    }

    /// 某个家族的代表条目：优先"伞形条目"（version 为 None，代表"这个家族本身"），
    /// 否则取该家族的最大版本。找不到返回 `None`。
    pub fn family_entry(&self, family: &str) -> Option<&CatalogEntry> {
        if let Some(entry) = self
            .entries
            .iter()
            .find(|entry| entry.family == family && entry.version.is_none())
        {
            return Some(entry);
        }
        let latest = self.latest_version(family)?;
        self.entries
            .iter()
            .find(|entry| entry.family == family && entry.version.as_ref() == Some(&latest))
    }

    /// 家族清单（去重、排序），用于 UI 展示与测试。
    pub fn families(&self) -> Vec<String> {
        let mut families: Vec<String> = self
            .entries
            .iter()
            .map(|entry| entry.family.clone())
            .collect();
        families.sort();
        families.dedup();
        families
    }

    /// 解析一个模型 id。
    ///
    /// 顺序固定：精确别名 → 家族+版本 → 家族最新 → 家族模糊（最长包含） → 未知默认。
    /// 每一步都返回 `MatchSource`，因为"为什么是这个参数"必须能解释。
    pub fn resolve(&self, raw_id: &str, kind: ProviderKind) -> ResolvedModel {
        let parsed = parse_model_name(raw_id);
        let serialized = SerializedParsedName {
            normalized: parsed.normalized.clone(),
            family: parsed.family.clone(),
            version: parsed.version.clone(),
            group: parsed.group.clone(),
        };

        let make =
            |mut profile: ModelProfile, matched_by: MatchSource, entry: Option<&CatalogEntry>| {
                // 模型说"我支持思考"，但用哪个字段族是 provider 的事：这里补上默认族，
                // 用户仍可在 provider / model 配置里覆盖（D18）。
                if profile.reasoning && profile.thinking.field == ThinkingField::None {
                    profile.thinking = ThinkingSetting::with_field(kind.default_thinking_field());
                }
                ResolvedModel {
                    raw_id: raw_id.to_string(),
                    parsed: serialized.clone(),
                    profile,
                    matched_by,
                    matched_entry: entry.map(|entry| entry.id.clone()),
                }
            };

        // 1. 精确：归一化后的全名等于条目 id 或它的别名。
        //
        //    伞形条目（id == family，例如 `claude`、`gemini-3-pro`）在这里**也参与匹配**：
        //    它的别名是 `deepseek-v4.1-flash` 这类"版本在中间"的名字唯一的兜底手段，
        //    把整类条目排除掉会让别名一起失效。
        //    只是来源标签要说实话：只有当用户输入的恰好是家族名本身时，才算"按最新版处理"。
        if let Some(entry) = self
            .entries
            .iter()
            .find(|entry| entry.id == parsed.normalized)
            .or_else(|| {
                self.entries.iter().find(|entry| {
                    entry
                        .aliases
                        .iter()
                        .map(|alias| normalize(alias).0)
                        .any(|alias| alias == parsed.normalized)
                })
            })
        {
            let typed_bare_family = entry.id == entry.family && parsed.normalized == entry.family;
            let source = if typed_bare_family {
                MatchSource::FamilyLatest
            } else {
                MatchSource::Alias
            };
            return make(entry.profile.clone(), source, Some(entry));
        }

        // 2. 家族 + 版本
        if let Some(version) = &parsed.version {
            if let Some(entry) = self.entries.iter().find(|entry| {
                entry.family == parsed.family && entry.version.as_ref() == Some(version)
            }) {
                return make(
                    entry.profile.clone(),
                    MatchSource::FamilyVersion,
                    Some(entry),
                );
            }
        }

        // 3. 只认出家族 → 优先用该家族的"伞形条目"（version 为 None），
        //    否则取该家族的最大版本。数据源经常只给到家族级（如 deepseek-v4-1-flash）。
        if let Some(entry) = self.family_entry(&parsed.family) {
            return make(
                entry.profile.clone(),
                MatchSource::FamilyLatest,
                Some(entry),
            );
        }

        // 4. 名字里包含某个家族（claudeauto → claude），取最长匹配
        let mut fuzzy: Option<&CatalogEntry> = None;
        let mut fuzzy_len = 0usize;
        for family in self.families() {
            if family.len() > fuzzy_len && parsed.normalized.contains(&family) {
                if let Some(entry) = self.family_entry(&family) {
                    fuzzy = Some(entry);
                    fuzzy_len = family.len();
                }
            }
        }
        if let Some(entry) = fuzzy {
            return make(entry.profile.clone(), MatchSource::FamilyFuzzy, Some(entry));
        }

        // 5. 未知：默认参数，且标记需要复核
        make(ModelProfile::conservative(kind), MatchSource::Unknown, None)
    }
}

/// 快照来源声明。刷新脚本会把出处与许可写在这里。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SnapshotSource {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub license: String,
    #[serde(default)]
    pub used_for: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SnapshotProvenance {
    #[serde(default)]
    pub generated_by: String,
    #[serde(default)]
    pub fetched_at: String,
    #[serde(default)]
    pub note: String,
    #[serde(default)]
    pub sources: Vec<SnapshotSource>,
}

/// 离线模型快照。由 `scripts/fetch_models.py` 生成（只含事实字段）。
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ModelSnapshot {
    #[serde(default)]
    pub provenance: SnapshotProvenance,
    #[serde(default)]
    pub entries: Vec<CatalogEntry>,
}

impl ModelSnapshot {
    pub fn from_json(text: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(text)
    }

    pub fn to_registry(&self) -> ModelRegistry {
        let mut registry = ModelRegistry::new();
        for entry in &self.entries {
            registry.upsert(entry.clone());
        }
        registry
    }
}

/// 内置的一小撮示例条目。真实数据来自 vendored 快照（models.dev / LiteLLM），
/// 这里只放足以让测试与 UI 跑起来的骨架。
pub fn seed_registry() -> ModelRegistry {
    let mut registry = ModelRegistry::new();

    let claude = |context: u64| ModelProfile {
        capabilities: ProviderCapabilities {
            streaming: true,
            streaming_usage: true,
            images: true,
            tools: true,
            structured_output: true,
            assistant_prefill: true, // Anthropic 原生支持以 assistant 结尾续写
            reasoning_replay_required: true, // 带工具调用的思考块必须回传
        },
        thinking: ThinkingSetting::disabled(),
        reasoning: true,
        supported_efforts: ThinkingEffort::sorted(vec![
            ThinkingEffort::Low,
            ThinkingEffort::Medium,
            ThinkingEffort::High,
            ThinkingEffort::XHigh,
            ThinkingEffort::Max,
        ]),
        supports_toggle: false,
        context_window: context,
        max_output: 32_000,
        cost: None,
    };

    registry.upsert(CatalogEntry::new(
        "claude-opus-5-5",
        "claude-opus",
        Some(Version::new([5, 5])),
        claude(200_000),
    ));
    registry.upsert(
        CatalogEntry::new(
            "claude-opus-5-4",
            "claude-opus",
            Some(Version::new([5, 4])),
            claude(200_000),
        )
        .with_aliases(["claude-opus-5-4"]),
    );
    // 伞形条目：id 与 family 同名，语义是"这个家族的最新版"。
    // 只在用户写裸家族名（`Claude`）时命中，且必须报 FamilyLatest 而不是 Alias。
    registry.upsert(CatalogEntry::new(
        "claude",
        "claude",
        Some(Version::new([5, 5])),
        claude(200_000),
    ));

    // 版本在中间的 id 靠别名兜住（正则切不出来）
    registry.upsert(
        CatalogEntry::new(
            "deepseek-v4-1-flash",
            "deepseek-v4",
            Some(Version::new([4, 1])),
            ModelProfile {
                capabilities: ProviderCapabilities::openai_like(),
                thinking: ThinkingSetting::disabled(),
                reasoning: true,
                // 实测：DeepSeek V4 系是 low / high / max，没有 medium
                supported_efforts: vec![
                    ThinkingEffort::Low,
                    ThinkingEffort::High,
                    ThinkingEffort::Max,
                ],
                supports_toggle: true,
                context_window: 1_000_000,
                max_output: 64_000,
                cost: None,
            },
        )
        .with_aliases(["deepseek-v4-1-flash", "DeepSeek-V4.1-Flash"]),
    );

    registry
}

#[cfg(test)]
mod tests {
    use super::*;

    fn registry() -> ModelRegistry {
        seed_registry()
    }

    #[test]
    fn norm_handles_groups_and_separators() {
        assert_eq!(normalize("Claude-Opus-5-5").0, "claude-opus-5-5");
        assert_eq!(normalize("opus5.5").0, "opus5-5");
        assert_eq!(normalize("[分组A]opus5-5").0, "opus5-5");
        assert_eq!(normalize("[分组A]opus5-5").1.as_deref(), Some("分组A"));
        assert_eq!(normalize("[分组 A] Claude").0, "claude");
    }

    #[test]
    fn version_splitting_covers_the_users_examples() {
        let cases = [
            ("claude-opus-5.5", "claude-opus", Some(Version::new([5, 5]))),
            ("Claude-Opus-5-5", "claude-opus", Some(Version::new([5, 5]))),
            ("opus5.5", "opus", Some(Version::new([5, 5]))),
            ("[分组A]opus5-5", "opus", Some(Version::new([5, 5]))),
            ("[分组A]ClaudeAuto", "claudeauto", None),
            ("[分组B]Claude", "claude", None),
            ("gpt-4o", "gpt-4o", None),
        ];
        for (raw, family, version) in cases {
            let parsed = parse_model_name(raw);
            assert_eq!(parsed.family, family, "{raw}");
            assert_eq!(parsed.version, version, "{raw}");
        }
    }

    #[test]
    fn version_ordering_is_numeric_per_segment() {
        assert!(Version::new([5, 10]) > Version::new([5, 9]));
        assert!(Version::new([5, 5, 1]) > Version::new([5, 5]));
        assert!(Version::new([5, 5]) > Version::new([5]));
    }

    #[test]
    fn family_without_version_takes_latest() {
        let resolved = registry().resolve("[分组B]Claude", ProviderKind::Anthropic);
        assert_eq!(resolved.matched_by, MatchSource::FamilyLatest);
        assert_eq!(resolved.parsed.family, "claude");
        assert!(resolved.profile.capabilities.assistant_prefill);
    }

    #[test]
    fn exact_alias_wins_over_fuzzy() {
        let resolved = registry().resolve("claude-opus-5.5", ProviderKind::Anthropic);
        assert_eq!(resolved.matched_by, MatchSource::Alias);
        assert_eq!(resolved.matched_entry.as_deref(), Some("claude-opus-5-5"));
    }

    #[test]
    fn family_version_match_works_for_other_spellings() {
        let resolved = registry().resolve("CLAUDE-opus-5-5", ProviderKind::Anthropic);
        assert_eq!(resolved.matched_by, MatchSource::Alias); // 归一化后与 id 相同
        assert_eq!(resolved.profile.context_window, 200_000);
    }

    #[test]
    fn fuzzy_match_is_flagged_for_review() {
        let resolved = registry().resolve("[分组A]ClaudeAuto", ProviderKind::Anthropic);
        assert_eq!(resolved.matched_by, MatchSource::FamilyFuzzy);
        assert!(resolved.matched_by.needs_review());
        assert!(resolved.matched_by.explain().contains('猜'));
    }

    #[test]
    fn unknown_model_gets_defaults_and_warning() {
        let resolved = registry().resolve("totally-made-up-9000", ProviderKind::OpenAiCompatible);
        assert_eq!(resolved.matched_by, MatchSource::Unknown);
        assert!(resolved.matched_by.needs_review());
        // 未知模型不猜它会思考（注错字段可能 400），UI 上提示手动开
        assert_eq!(resolved.profile.thinking.field, ThinkingField::None);
        assert!(!resolved.profile.reasoning);
        assert!(!resolved.profile.capabilities.images); // 保守默认
    }

    #[test]
    fn mid_string_version_is_covered_by_alias() {
        // provider 前缀（`vendor/` 这种）会参与归一化，所以别名要连前缀一起写
        let aliased = registry().resolve("DeepSeek-V4.1-Flash", ProviderKind::OpenAiCompatible);
        assert_eq!(aliased.matched_by, MatchSource::Alias);
        assert_eq!(aliased.profile.context_window, 1_000_000);
        let prefixed =
            registry().resolve("vendor/DeepSeek-V4.1-Flash", ProviderKind::OpenAiCompatible);
        assert!(prefixed.raw_id.contains("vendor/"));
    }

    #[test]
    fn reasoning_models_get_provider_field_family() {
        // 同一份模型事实，挂在不同 provider 上应得到不同的字段族
        let registry = registry();
        let anthropic = registry.resolve("claude-opus-5.5", ProviderKind::Anthropic);
        assert!(anthropic.profile.reasoning);
        assert_eq!(
            anthropic.profile.thinking.field,
            ThinkingField::AnthropicThinking
        );

        let deepseek = registry.resolve("deepseek-v4-1-flash", ProviderKind::OpenAiCompatible);
        assert_eq!(deepseek.profile.thinking.field, ThinkingField::OpenAiEffort);
    }

    #[test]
    fn snapshot_json_roundtrips_into_registry() {
        let text = r#"{
          "provenance": {"generated_by": "test", "fetched_at": "2026-01-01T00:00:00Z",
                         "sources": [{"name": "models.dev", "url": "https://models.dev/api.json", "license": "MIT"}]},
          "entries": [{
            "id": "gemini-3-pro", "family": "gemini-3-pro", "version": null, "aliases": ["gemini-3.0-pro"],
            "profile": {
              "capabilities": {"streaming": true, "streaming_usage": false, "images": true,
                               "tools": true, "structured_output": true,
                               "assistant_prefill": false, "reasoning_replay_required": false},
              "thinking": {"field": "none", "effort": null, "budget_tokens": null, "custom": null},
              "reasoning": true, "context_window": 1000000, "max_output": 65536,
              "cost": {"input_per_million": 1.25, "output_per_million": 10.0}
            }
          }]
        }"#;

        let snapshot = ModelSnapshot::from_json(text).unwrap();
        assert_eq!(snapshot.provenance.sources[0].license, "MIT");
        let registry = ModelRegistry::from_snapshot(&snapshot);

        // 用户输入的就是家族名本身 → 按"该家族最新"处理
        let resolved = registry.resolve("gemini-3-pro", ProviderKind::Gemini);
        assert_eq!(resolved.matched_by, MatchSource::FamilyLatest);
        assert_eq!(resolved.profile.context_window, 1_000_000);
        assert_eq!(
            resolved.profile.thinking.field,
            ThinkingField::GeminiThinkingConfig
        );
        assert_eq!(resolved.profile.cost.unwrap().output_per_million, 10.0);

        // 别名（归一化后与 id 不同）走 Alias 分支——这是"版本在中间"的名字的兜底
        let by_alias = registry.resolve("Gemini-3.0-Pro", ProviderKind::Gemini);
        assert_eq!(by_alias.matched_by, MatchSource::Alias);
        assert_eq!(by_alias.matched_entry.as_deref(), Some("gemini-3-pro"));
    }

    #[test]
    fn committed_snapshot_parses_and_resolves() {
        // 快照由 scripts/fetch_models.py 生成；这里只验证"能被解析 + 认得出几个知名模型"。
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../data/models.snapshot.json"
        );
        let Ok(text) = std::fs::read_to_string(path) else {
            eprintln!("跳过：{path} 不存在（还没跑抓取脚本）");
            return;
        };
        let registry = ModelRegistry::load_snapshot_json(&text).expect("快照必须能解析");
        assert!(
            registry.len() > 500,
            "快照条目太少，可能抓取失败：{}",
            registry.len()
        );

        let claude = registry.resolve("claude-opus-5.5", ProviderKind::Anthropic);
        assert!(
            claude.matched_by != MatchSource::Unknown,
            "claude-opus-5.5 该能认出"
        );
        assert!(claude.profile.context_window > 0);

        // 版本在中间的名字（正则切不出）必须靠别名兜住
        let deepseek = registry.resolve("DeepSeek-V4.1-Flash", ProviderKind::OpenAiCompatible);
        assert!(
            deepseek.matched_by != MatchSource::Unknown,
            "deepseek-v4.1-flash 该能认出，实际 {:?}",
            deepseek.matched_by
        );
    }

    #[test]
    fn user_override_replaces_entry() {
        let mut registry = registry();
        registry.upsert(CatalogEntry::new(
            "claude-opus-5-5",
            "claude-opus",
            Some(Version::new([5, 5])),
            ModelProfile::conservative(ProviderKind::Anthropic),
        ));
        let resolved = registry.resolve("claude-opus-5.5", ProviderKind::Anthropic);
        assert!(!resolved.profile.capabilities.images, "用户覆盖应生效");
    }
}
