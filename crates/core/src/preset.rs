//! 预设的四态与"适用性"（D159 / PLAN 0.14t）。
//!
//! 用户点过名很在意这一块，而它最容易做成"看起来能用、其实永远说不清"：本地改过没有、
//! 上游更新了没有——这两件事必须由两个**能对得上**的判据算出来，不能靠时间戳、不能靠猜。
//!
//! 判据只有两条：
//! 1. **本地改过没有**：`local_hash != base_hash`。`base_hash` 是"上一次同步时看到的那一版"，
//!    跟着词条一起存（`base_id` / `base_version` / `base_hash` 三件，见 D159）。
//! 2. **上游更新了没有**：`upstream.version > base_version`。用版本号，不用时间——
//!    时间会被时区、构建机时钟、重新打包弄乱，版本号是人手动往前推的。
//!
//! 两条判据交叉出四态，界面只显示这四态，**不自己推**：
//!
//! | 本地改过 | 上游更新 | 状态 | 界面上给什么动作 |
//! |---|---|---|---|
//! | 否 | 否 | [`PresetState::Follow`] | 什么都不用做（跟着上游） |
//! | 是 | 否 | [`PresetState::LocalEdited`] | 「保留本地」/「用上游的」（覆盖本地的改动要确认） |
//! | 否 | 是 | [`PresetState::UpstreamNewer`] | 「更新」（一键，安全） |
//! | 是 | 是 | [`PresetState::Conflict`] | 三选一：保留本地 / 用新的 / 逐条选 |
//!
//! 四态之外只有一件：**上游已经没有这条了**。本地没改 → 就当同步完毕（[`PresetState::Follow`]，
//! 界面上另外标一句"上游已移除"）；本地改过 → 当 [`PresetState::Conflict`]，
//! 因为"用户改过的东西"不能因为上游删了就悄悄消失。

/// 一条词条（预设里可编辑的最小单位）。
///
/// 用户能改的**只有正文**：`title` 与 `applies_to` 里的工具名是 wire 契约，
/// 改了就等于让"这条预设为什么被注入"变得说不清（D159）。
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Deserialize)]
#[serde(default)]
pub struct AssetEntry {
    pub id: String,
    pub title: String,
    pub content: String,
}

/// 随包的一份预设（`assets/presets/<id>/preset.json`）。
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Deserialize)]
#[serde(default)]
pub struct AssetPreset {
    pub id: String,
    /// 人手动往前推的整数。**上游改了内容却没推它**也算新版（靠 `hash`，见 [`state`]）。
    pub version: u32,
    pub title: String,
    pub summary: String,
    /// 只在这些工具**都被声明**的那一轮里注入（[`applies`]）。
    pub applies_to: Vec<String>,
    pub entries: Vec<AssetEntry>,
}

/// 用户改过的一条词条（存在 `settings(scope=preset, key="<预设>/<词条>")` 里）。
///
/// 三个 `base_*` 是"上一次同步时看到的那一版"：编辑那一刻把上游的 id / version /
/// 正文摘要记下来，之后就是 [`state`] 的两条判据的输入。
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct EntryDoc {
    pub base_id: String,
    pub base_version: u32,
    pub base_hash: String,
    /// 用户此刻的正文。**它不覆盖上游**：上游那份永远在包里，这里只记改动。
    pub content: String,
    /// 基线条目不可删，只能禁用（D159）。
    pub disabled: bool,
}

/// 计算用的上游那一版，从 [`AssetEntry`] + 预设版本号拼出来。
pub fn upstream_of(preset: &AssetPreset, entry: &AssetEntry) -> Upstream {
    Upstream {
        id: format!("{}/{}", preset.id, entry.id),
        version: preset.version,
        hash: hash(&entry.content),
    }
}

/// 正文摘要（sha256 十六进制小写）。四态的两条判据都靠它，**不靠时间戳**。
pub fn hash(text: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// 一条词条此刻**该用哪份正文**，以及它处在四态里的哪一种。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryView {
    pub preset_id: String,
    pub id: String,
    pub title: String,
    pub state: PresetState,
    /// 现在生效的正文（本地改过就是本地那份）。
    pub content: String,
    /// 上游此刻那一份（冲突时界面上要能并排看）。
    pub upstream: String,
    /// 上游版本号。
    pub version: u32,
    pub disabled: bool,
    /// 用户上次对齐的那一版（没改过就是 `None`）。
    ///
    /// 界面保存正文时要把它**原样写回**：不写回就等于"我改的时候已经对齐新版了"，
    /// 而那是替用户做了一个他没有做的决定（"绝不自动覆盖"的反面）。
    pub base: Option<BaseVersion>,
}

impl EntryView {
    /// 这一条会不会进请求：禁用、或没有正文的都不进。
    pub fn injectable(&self) -> bool {
        !self.disabled && !self.content.trim().is_empty()
    }
}

/// 一条词条的四态与生效正文。
///
/// `doc` 为空 = 用户没改过（跟着上游）。
pub fn entry_view(preset: &AssetPreset, entry: &AssetEntry, doc: Option<&EntryDoc>) -> EntryView {
    let upstream = upstream_of(preset, entry);
    let Some(doc) = doc else {
        // 没改过：连 base 都没有，就是"跟随"。**不要在这里造一个假的 base**，
        // 否则下次比较会拿一个从没同步过的摘要去比，凭空生出一个"已改"。
        return EntryView {
            preset_id: preset.id.clone(),
            id: entry.id.clone(),
            title: entry.title.clone(),
            state: PresetState::Follow,
            content: entry.content.clone(),
            upstream: entry.content.clone(),
            version: preset.version,
            disabled: false,
            base: None,
        };
    };

    let base = BaseVersion {
        id: doc.base_id.clone(),
        version: doc.base_version,
        hash: doc.base_hash.clone(),
    };
    let state = state(&hash(&doc.content), &base, Some(&upstream));
    EntryView {
        preset_id: preset.id.clone(),
        id: entry.id.clone(),
        title: entry.title.clone(),
        state,
        content: doc.content.clone(),
        upstream: entry.content.clone(),
        version: preset.version,
        disabled: doc.disabled,
        base: Some(base),
    }
}

/// 一份预设的整体状态：**取最要紧的那一条**（冲突 > 有新 > 已改 > 跟随）。
///
/// 列表页只有一行位置放徽标，所以得有一个"这份预设现在要不要你管"的答案。
pub fn preset_state(entries: &[EntryView]) -> PresetState {
    if entries.iter().any(|e| e.state == PresetState::Conflict) {
        PresetState::Conflict
    } else if entries
        .iter()
        .any(|e| e.state == PresetState::UpstreamNewer)
    {
        PresetState::UpstreamNewer
    } else if entries.iter().any(|e| e.state == PresetState::LocalEdited) {
        PresetState::LocalEdited
    } else {
        PresetState::Follow
    }
}

/// 这一轮该注入哪些词条。
///
/// 过滤用的 `declared` 是**这一轮冻结出来的声明表**（`TurnPlan::declared_tools`）：
/// 工具没开，那几条提示词一起消失——否则模型会看到"用 read_file 时要这样"而它根本没有它。
///
/// 顺序固定（预设 id → 词条顺序）：注入段落进的是 system 后面的固定位置，
/// 顺序一抖，缓存前缀就整段作废。
pub fn select<'a>(
    presets: &'a [AssetPreset],
    docs: &dyn Fn(&str, &str) -> Option<EntryDoc>,
    declared: &[String],
) -> Vec<EntryView> {
    let mut ordered: Vec<&AssetPreset> = presets.iter().collect();
    ordered.sort_by(|a, b| a.id.cmp(&b.id));

    let mut out = Vec::new();
    for preset in ordered {
        if !applies(&preset.applies_to, declared) {
            continue;
        }
        for entry in &preset.entries {
            let doc = docs(&preset.id, &entry.id);
            let view = entry_view(preset, entry, doc.as_ref());
            if view.injectable() {
                out.push(view);
            }
        }
    }
    out
}

/// 注入那一段正文。空表给空串——调用方据此决定"要不要往 system 后面接一段"。
///
/// 形状是给模型看的，所以每条都带"这是哪条预设的哪一条"：
/// 事后复盘"这句话是谁塞进去的"只能靠它。
pub fn render(views: &[EntryView]) -> String {
    if views.is_empty() {
        return String::new();
    }
    let mut out = String::from("## 预设（工具相关规则）\n");
    let mut last_preset = "";
    for view in views {
        if view.preset_id != last_preset {
            out.push_str(&format!("\n### {}（v{}）\n", view.preset_id, view.version));
            last_preset = &view.preset_id;
        }
        out.push_str(&format!("- {}\n{}\n", view.title, view.content.trim()));
    }
    out
}

/// 一条词条在本地与上游之间的四种关系。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresetState {
    /// 本地没动过、上游也没更新：跟着上游即可。
    Follow,
    /// 本地动过，上游还是那一版。
    LocalEdited,
    /// 本地没动过，上游推了新版。
    UpstreamNewer,
    /// 两边都动了：这件事只能由人来定。
    Conflict,
}

impl PresetState {
    pub fn as_str(self) -> &'static str {
        match self {
            PresetState::Follow => "follow",
            PresetState::LocalEdited => "local_edited",
            PresetState::UpstreamNewer => "upstream_newer",
            PresetState::Conflict => "conflict",
        }
    }

    /// 界面上给的那句人话。**四种状态必须是四种不同的下一步**，
    /// 所以文案不能写成"有更新"这种能套在任意两种上的话。
    pub fn label(self) -> &'static str {
        match self {
            PresetState::Follow => "跟随",
            PresetState::LocalEdited => "已改",
            PresetState::UpstreamNewer => "有新",
            PresetState::Conflict => "冲突",
        }
    }

    /// 这条状态需要用户动手吗？（列表页据此决定要不要标"待处理"）
    pub fn needs_attention(self) -> bool {
        !matches!(self, PresetState::Follow)
    }
}

/// 词条身上记的"上一次同步看到的那一版"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BaseVersion {
    /// 上游的预设 id（改名也算换了一条，所以它跟着词条走）。
    pub id: String,
    /// 上游的版本号，人手动往前推的整数。
    pub version: u32,
    /// 那一版的正文摘要（sha256）。与 `version` 互相补足：
    /// 上游忘了推版本号但内容变了，靠它也能算出来。
    pub hash: String,
}

/// 上游此刻的一条。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Upstream {
    pub id: String,
    pub version: u32,
    pub hash: String,
}

/// 算一条词条的四态。
///
/// `local_hash` 是**本地此刻**的正文摘要；`base` 是上一次同步时记下的那一版；
/// `upstream` 为空 = 上游已经没有这条了。
pub fn state(local_hash: &str, base: &BaseVersion, upstream: Option<&Upstream>) -> PresetState {
    // 本地改过没有：**摘要比对**，不比版本号——用户改内容时版本号不会动。
    let local_changed = !local_hash.eq_ignore_ascii_case(&base.hash);

    let Some(upstream) = upstream else {
        // 上游没了。用户没动过就安静地"跟随"（界面另标"上游已移除"）；
        // 动过就必须叫人来看——不能让他的改动因为上游删了就消失。
        return if local_changed {
            PresetState::Conflict
        } else {
            PresetState::Follow
        };
    };

    // 上游更新没有：版本号变大，或者版本号没动但内容换了（上游忘了推版本号）。
    let upstream_newer =
        upstream.version > base.version || !upstream.hash.eq_ignore_ascii_case(&base.hash);

    match (local_changed, upstream_newer) {
        (false, false) => PresetState::Follow,
        (true, false) => PresetState::LocalEdited,
        (false, true) => PresetState::UpstreamNewer,
        (true, true) => PresetState::Conflict,
    }
}

/// 这条预设适不适用于这一轮（D159 的 `applies_to`）。
///
/// 判据是"它声明的工具是不是都在这一轮真正声明给模型了"。少一个就不适用——
/// 一条"用 `read_file` 读到的内容要这么处理"的预设，在没声明 `read_file` 的那一轮里
/// 只会教模型去调一个它看不见的工具。
///
/// `needs` 为空 = 不依赖任何工具，恒适用。
pub fn applies(needs: &[String], declared: &[String]) -> bool {
    needs
        .iter()
        .all(|need| declared.iter().any(|declared| declared == need))
}

/// 四态的汇总，给列表页那句"3 条可更新、2 条冲突"用。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Summary {
    pub follow: usize,
    pub local_edited: usize,
    pub upstream_newer: usize,
    pub conflict: usize,
}

impl Summary {
    pub fn add(&mut self, state: PresetState) {
        match state {
            PresetState::Follow => self.follow += 1,
            PresetState::LocalEdited => self.local_edited += 1,
            PresetState::UpstreamNewer => self.upstream_newer += 1,
            PresetState::Conflict => self.conflict += 1,
        }
    }

    pub fn total(&self) -> usize {
        self.follow + self.local_edited + self.upstream_newer + self.conflict
    }

    /// 要用户动手的条数。
    pub fn needs_attention(&self) -> usize {
        self.local_edited + self.upstream_newer + self.conflict
    }

    /// 一句人话。**没有待处理项时也要说清"一切都跟上游一致"**，别给一个空串。
    pub fn describe(&self) -> String {
        if self.total() == 0 {
            return "还没有预设".to_string();
        }
        if self.needs_attention() == 0 {
            return format!("{} 条预设都跟上游一致", self.total());
        }
        let mut parts = Vec::new();
        if self.upstream_newer > 0 {
            parts.push(format!("{} 条可更新", self.upstream_newer));
        }
        if self.conflict > 0 {
            parts.push(format!("{} 条冲突", self.conflict));
        }
        if self.local_edited > 0 {
            parts.push(format!("{} 条本地已改", self.local_edited));
        }
        format!("{} 条预设：{}", self.total(), parts.join(" · "))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> BaseVersion {
        BaseVersion {
            id: "tool-usage".to_string(),
            version: 3,
            hash: "aaa".to_string(),
        }
    }

    fn upstream(version: u32, hash: &str) -> Upstream {
        Upstream {
            id: "tool-usage".to_string(),
            version,
            hash: hash.to_string(),
        }
    }

    #[test]
    fn nothing_changed_is_follow() {
        assert_eq!(
            state("aaa", &base(), Some(&upstream(3, "aaa"))),
            PresetState::Follow
        );
    }

    #[test]
    fn local_edit_without_upstream_change_is_local_edited() {
        assert_eq!(
            state("bbb", &base(), Some(&upstream(3, "aaa"))),
            PresetState::LocalEdited
        );
    }

    #[test]
    fn upstream_bump_without_local_edit_is_upstream_newer() {
        assert_eq!(
            state("aaa", &base(), Some(&upstream(4, "ccc"))),
            PresetState::UpstreamNewer
        );
    }

    #[test]
    fn both_sides_changed_is_conflict() {
        assert_eq!(
            state("bbb", &base(), Some(&upstream(4, "ccc"))),
            PresetState::Conflict
        );
    }

    /// 上游忘了推版本号、只改了内容：也得算"有新"。版本号不是唯一判据。
    #[test]
    fn a_content_change_without_a_version_bump_still_counts() {
        assert_eq!(
            state("aaa", &base(), Some(&upstream(3, "ccc"))),
            PresetState::UpstreamNewer
        );
    }

    /// 上游撤了这条：本地没动 → 安静跟随；本地动过 → 必须叫人（不能随上游消失）。
    #[test]
    fn a_removed_upstream_entry_asks_only_when_local_changed() {
        assert_eq!(state("aaa", &base(), None), PresetState::Follow);
        assert_eq!(state("bbb", &base(), None), PresetState::Conflict);
    }

    /// 摘要比对不分大小写（`sha256_hex` 有两种写法，库里两种都可能有）。
    #[test]
    fn hash_comparison_ignores_case() {
        let base = BaseVersion {
            id: "x".to_string(),
            version: 1,
            hash: "AABB".to_string(),
        };
        assert_eq!(
            state("aabb", &base, Some(&upstream(1, "aabb"))),
            PresetState::Follow
        );
    }

    #[test]
    fn applies_requires_every_declared_tool() {
        let needs = vec!["read_file".to_string(), "grep".to_string()];
        let both = vec![
            "read_file".to_string(),
            "grep".to_string(),
            "list_dir".to_string(),
        ];
        assert!(applies(&needs, &both));
        assert!(!applies(&needs, &["read_file".to_string()]));
        // 不依赖工具的那类恒适用（即使一个工具都没声明）。
        assert!(applies(&[], &[]));
    }

    #[test]
    fn summary_counts_and_says_something_useful() {
        let mut summary = Summary::default();
        assert_eq!(summary.describe(), "还没有预设");

        summary.add(PresetState::Follow);
        summary.add(PresetState::Follow);
        assert_eq!(summary.describe(), "2 条预设都跟上游一致");
        assert_eq!(summary.needs_attention(), 0);

        summary.add(PresetState::UpstreamNewer);
        summary.add(PresetState::Conflict);
        summary.add(PresetState::Conflict);
        summary.add(PresetState::LocalEdited);
        assert_eq!(summary.total(), 6);
        assert_eq!(summary.needs_attention(), 4);
        assert_eq!(
            summary.describe(),
            "6 条预设：1 条可更新 · 2 条冲突 · 1 条本地已改"
        );
    }

    // ------------------------------------------------------------------
    // 0.14t：预设词条（asset + 用户改过的那一份 → 四态 / 生效正文 / 注入）
    // ------------------------------------------------------------------

    fn asset(version: u32, content: &str) -> AssetPreset {
        AssetPreset {
            id: "tool-usage".to_string(),
            version,
            title: "工具用法".to_string(),
            summary: "读写文件时的几条规矩".to_string(),
            applies_to: vec!["read_file".to_string()],
            entries: vec![AssetEntry {
                id: "read-first".to_string(),
                title: "先读再改".to_string(),
                content: content.to_string(),
            }],
        }
    }

    /// 用户在 v3 那一版上改过、并且记下了 base 三件。
    fn edited(preset: &AssetPreset, content: &str) -> EntryDoc {
        EntryDoc {
            base_id: format!("{}/read-first", preset.id),
            base_version: preset.version,
            base_hash: hash(&preset.entries[0].content),
            content: content.to_string(),
            disabled: false,
        }
    }

    #[test]
    fn no_doc_means_follow_with_the_upstream_body() {
        let preset = asset(3, "先读再改");
        let view = entry_view(&preset, &preset.entries[0], None);
        assert_eq!(view.state, PresetState::Follow);
        assert_eq!(view.content, "先读再改");
        assert_eq!(view.upstream, "先读再改");
        assert!(view.injectable());
    }

    #[test]
    fn an_edited_entry_is_local_edited_until_upstream_moves() {
        let preset = asset(3, "先读再改");
        let doc = edited(&preset, "先读两遍再改");
        let view = entry_view(&preset, &preset.entries[0], Some(&doc));
        assert_eq!(view.state, PresetState::LocalEdited);
        assert_eq!(view.content, "先读两遍再改", "生效的是本地那份");
        assert_eq!(view.upstream, "先读再改", "上游那份也要留着给人看");
        // base 必须原样带出来：界面保存正文时要写回它，否则等于替用户宣布"已对齐新版"。
        assert_eq!(view.base.as_ref().map(|base| base.version), Some(3));
        assert_eq!(
            view.base.as_ref().map(|base| base.hash.as_str()),
            Some(doc.base_hash.as_str())
        );

        // 上游推 v4：两边都动过 = 冲突。**不自动覆盖**。
        let newer = asset(4, "先读再改（新版）");
        let view = entry_view(&newer, &newer.entries[0], Some(&doc));
        assert_eq!(view.state, PresetState::Conflict);
    }

    #[test]
    fn an_upstream_bump_without_a_local_edit_is_upstream_newer() {
        let old = asset(3, "先读再改");
        let doc = edited(&old, "先读再改"); // 内容刚好没变，base 对得上
        let newer = asset(4, "先读再改（新版）");
        let view = entry_view(&newer, &newer.entries[0], Some(&doc));
        assert_eq!(view.state, PresetState::UpstreamNewer);
        assert_eq!(view.content, "先读再改", "没改过就还显示它自己那份");
    }

    /// 预设整体的徽标取最要紧的那一条：一份预设里只要有一条冲突，它就得是人来管的。
    #[test]
    fn a_preset_takes_its_most_urgent_entry_state() {
        let follow = EntryView {
            state: PresetState::Follow,
            ..entry_view(&asset(3, "a"), &asset(3, "a").entries[0], None)
        };
        let edited_view = EntryView {
            state: PresetState::LocalEdited,
            ..follow.clone()
        };
        let newer_view = EntryView {
            state: PresetState::UpstreamNewer,
            ..follow.clone()
        };
        let conflict_view = EntryView {
            state: PresetState::Conflict,
            ..follow.clone()
        };
        // 单元素切片用 `from_ref`，不要 `&[x.clone()]`：CI 的新 clippy 会报
        // `cloned_ref_to_slice_refs`（本机 1.75 的 clippy 还没有这条）。
        assert_eq!(
            preset_state(std::slice::from_ref(&follow)),
            PresetState::Follow
        );
        assert_eq!(
            preset_state(&[follow.clone(), edited_view.clone()]),
            PresetState::LocalEdited
        );
        assert_eq!(
            preset_state(&[edited_view, newer_view]),
            PresetState::UpstreamNewer
        );
        assert_eq!(preset_state(&[conflict_view]), PresetState::Conflict);
        // 空的一份预设：没有要紧事，别把"空"说成"冲突"。
        assert_eq!(preset_state(&[]), PresetState::Follow);
    }

    /// 注入只用声明过的工具那几条（D159）：没开 `read_file` 的那一轮，它的规矩一起消失。
    #[test]
    fn selection_follows_the_declared_tools_and_a_stable_order() {
        let read = asset(3, "先读再改");
        let mut shell = AssetPreset {
            id: "shell-safety".to_string(),
            version: 1,
            title: "命令安全".to_string(),
            summary: String::new(),
            applies_to: vec!["System-Shell".to_string()],
            entries: vec![AssetEntry {
                id: "explain".to_string(),
                title: "先说再跑".to_string(),
                content: "跑命令前说清要干什么".to_string(),
            }],
        };
        // 顺序故意放反：输出必须是 预设 id 升序（缓存前缀不能抖）。
        shell.id = "a-shell".to_string();
        let presets = vec![read, shell];
        let none = |_: &str, _: &str| None;

        let with_read: Vec<String> = select(&presets, &none, &["read_file".to_string()])
            .iter()
            .map(|v| format!("{}/{}", v.preset_id, v.id))
            .collect();
        assert_eq!(with_read, vec!["tool-usage/read-first"]);

        let both: Vec<String> = select(
            &presets,
            &none,
            &["read_file".to_string(), "System-Shell".to_string()],
        )
        .iter()
        .map(|v| format!("{}/{}", v.preset_id, v.id))
        .collect();
        assert_eq!(both, vec!["a-shell/explain", "tool-usage/read-first"]);

        // 一个工具都没声明：一条都不注入。
        assert!(select(&presets, &none, &[]).is_empty());
    }

    /// 禁用与空正文都不进请求；但"禁用"这件事不能把词条从列表里抹掉（还要能再打开）。
    #[test]
    fn disabled_or_empty_entries_are_not_injected() {
        let preset = asset(3, "先读再改");
        let mut doc = edited(&preset, "   ");
        let view = entry_view(&preset, &preset.entries[0], Some(&doc));
        assert!(!view.injectable(), "空白正文不该进请求");

        doc.content = "先读再改".to_string();
        doc.disabled = true;
        let view = entry_view(&preset, &preset.entries[0], Some(&doc));
        assert!(!view.injectable());
        assert!(view.disabled);
    }

    #[test]
    fn render_shape_says_which_preset_and_which_entry() {
        let preset = asset(3, "先读再改");
        let views = select(
            &[preset],
            &|_: &str, _: &str| None,
            &["read_file".to_string()],
        );
        let text = render(&views);
        assert!(text.starts_with("## 预设（工具相关规则）"), "{text}");
        assert!(text.contains("tool-usage（v3）"), "{text}");
        assert!(text.contains("先读再改"), "{text}");
        assert!(text.contains("先读再改\n"), "正文要单独占一行：{text}");
        // 空表给空串：调用方据此决定"要不要往 system 后面接一段"。
        assert_eq!(render(&[]), "");
    }

    /// 摘要必须对内容敏感，且大小写无关的比较由 `state` 负责。
    #[test]
    fn hash_changes_with_content() {
        assert_eq!(hash("a"), hash("a"));
        assert_ne!(hash("a"), hash("b"));
        assert_eq!(hash("a").len(), 64);
    }

    /// 四种状态的界面文案必须两两不同（写成同一个词，用户分不出该做什么）。
    #[test]
    fn every_state_has_its_own_label() {
        let labels: Vec<&str> = [
            PresetState::Follow,
            PresetState::LocalEdited,
            PresetState::UpstreamNewer,
            PresetState::Conflict,
        ]
        .iter()
        .map(|state| state.label())
        .collect();
        let unique: std::collections::BTreeSet<&str> = labels.iter().copied().collect();
        assert_eq!(unique.len(), 4, "{labels:?}");
        assert!(!PresetState::Follow.needs_attention());
        assert!(labels.iter().all(|label| !label.is_empty()));
    }
}
