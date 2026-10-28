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
