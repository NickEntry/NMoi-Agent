//! 视觉覆盖（P3.3 / D62 / D63）。
//!
//! 用户 2026-10-21 的原话：**"OCR 文字识别我们这边叫视觉覆盖，启用后聊天中的模型将不使用
//! 自己的图片、视频等模态，相应信息将会发给设置好的默认视觉模型。"**
//!
//! 所以这一层的产出不是"一次额外的模型调用"，而是一份**决定**：
//! 哪些附件要交给视觉模型、按哪个提示词、分批几次、主模型该被告知什么。
//! 真正的网络调用与内容块留给 P3.4（多模态）；这里只管形状，因为形状错了后面全错。
//!
//! # 一条硬规矩：开着覆盖时，主模型一个媒体块都不收
//!
//! 如果开着覆盖、请求里还留着图，那就成了"同时问两个模型"：主模型照样自己看，
//! 视觉模型的描述只是一段额外文字。用户没法判断哪套在起作用，也解释不了
//! "为什么它说的是图里的 A，而你给我的描述里是 B"（D62）。
//!
//! 所以 [`VisionPlan::stays_with_main`] 在开着覆盖时**必须为空**——
//! 它非空就是实现漏了，测试里钉着这一条。
//!
//! # 提示词为什么要两套
//!
//! 图像是一次快照，丢的细节还能"再看一眼"补；视频丢了时间轴，"什么时候发生了什么"
//! 就永远补不回来。拿图像的模板去描述视频，出来的是六张互不相干的图（D63）。

use serde::{Deserialize, Serialize};

/// 图像默认提示词：用户 2026-10-21 提供的高精度复刻版，**逐字收**。
///
/// 放在 `data/vision.image.md` 而不是写成 Rust 字面量：那份提示词里有大量引号与反引号，
/// 塞进字符串字面量等于找罪受；而且它是给人改的，改 `md` 比改代码现实。
pub const DEFAULT_IMAGE_PROMPT: &str = include_str!("../../../data/vision.image.md");

/// 视频默认提示词：另写的，核心是时间轴而不是画面细节。
pub const DEFAULT_VIDEO_PROMPT: &str = include_str!("../../../data/vision.video.md");

/// 一次视觉覆盖要处理的媒体种类。
///
/// 只有图像和视频：音频交给主模型（如果它支持）或者由 ASR 转文字（P5.6），
/// 那是另一条路，不套"视觉"这个名字。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaKind {
    Image,
    Video,
}

impl MediaKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MediaKind::Image => "image",
            MediaKind::Video => "video",
        }
    }
}

/// 一个待处理的附件（元信息；字节在附件库里，按 `reference` 取）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaItem {
    /// 内容寻址 id（和归档同一套思路：同内容不重复存）。
    pub reference: String,
    /// 给人看的名字（文件名或"图片 1"），只用于提示词里指代。
    pub name: String,
}

/// 一份媒体 + 它的种类。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaAttachment {
    pub kind: MediaKind,
    pub item: MediaItem,
}

/// 两套默认提示词。用户可以整份替换，也可以只改一份。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VisionPrompts {
    pub image: String,
    pub video: String,
}

impl Default for VisionPrompts {
    fn default() -> Self {
        Self {
            image: DEFAULT_IMAGE_PROMPT.to_string(),
            video: DEFAULT_VIDEO_PROMPT.to_string(),
        }
    }
}

impl VisionPrompts {
    pub fn for_kind(&self, kind: MediaKind) -> &str {
        match kind {
            MediaKind::Image => &self.image,
            MediaKind::Video => &self.video,
        }
    }
}

/// 视觉覆盖的设置。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VisionOverride {
    pub enabled: bool,
    /// 视觉模型（provider/model 的组合由调用方拼；这里是给人看的标签 + 分发用）。
    pub model: Option<String>,
    pub prompts: VisionPrompts,
    /// 一次请求里最多塞几个媒体。图片可以成批（同一提示词一次看完上下文更准）；
    /// 视频不受这个限制影响——每个视频单独一条，见 D63。
    pub max_items_per_call: usize,
}

impl Default for VisionOverride {
    fn default() -> Self {
        Self {
            enabled: false,
            model: None,
            prompts: VisionPrompts::default(),
            max_items_per_call: 4,
        }
    }
}

/// 一批要发给视觉模型的活。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VisionTask {
    pub kind: MediaKind,
    pub items: Vec<MediaItem>,
    /// 该模态的提示词，**原样**带上；调用方不要在这里再加"请分析以下内容"之类的前缀。
    pub prompt: String,
}

impl VisionTask {
    /// 实际发给视觉模型的文本：提示词 + 本次素材清单。
    ///
    /// 素材清单必须在那里：一批四张图的时候，模型得知道"第几张是哪个文件"，
    /// 否则描述回来的东西对不上附件。
    pub fn request_text(&self) -> String {
        let mut text = self.prompt.trim_end().to_string();
        text.push_str("\n\n---\n本次素材（共 ");
        text.push_str(&self.items.len().to_string());
        text.push_str(" 个，按顺序对应）：\n");
        for (index, item) in self.items.iter().enumerate() {
            text.push_str(&format!(
                "{}. {}\n",
                index + 1,
                if item.name.trim().is_empty() {
                    item.reference.as_str()
                } else {
                    item.name.as_str()
                }
            ));
        }
        text
    }
}

/// 一次覆盖的决定。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct VisionPlan {
    /// 要发给视觉模型的批次。
    pub tasks: Vec<VisionTask>,
    /// 留在主模型请求里的媒体。**开着覆盖时必须为空**（D62）。
    pub stays_with_main: Vec<String>,
    /// 给主模型的一句话交代：让它知道"下面那段描述是从附件来的"，而不是用户打的字。
    pub main_note: Option<String>,
    /// 覆盖开着却没法干活时，这里说明为什么（比如没选视觉模型）。
    /// 非空时调用方**不要**把这批附件当成功处理——悄悄少一张图是最坏的结果。
    pub blocked_reason: Option<String>,
}

impl VisionPlan {
    pub fn media_count(&self) -> usize {
        self.tasks.iter().map(|task| task.items.len()).sum()
    }
}

impl VisionOverride {
    /// 给一批附件做决定。
    pub fn plan(&self, media: &[MediaAttachment]) -> VisionPlan {
        if !self.enabled {
            return VisionPlan {
                tasks: Vec::new(),
                stays_with_main: media
                    .iter()
                    .map(|attachment| attachment.item.reference.clone())
                    .collect(),
                main_note: None,
                blocked_reason: None,
            };
        }

        // 开着覆盖却没模型：不干活，也**不把媒体兜给主模型**——那正好违反 D62。
        if self.model.as_deref().unwrap_or_default().trim().is_empty() {
            return VisionPlan {
                blocked_reason: Some(
                    "视觉覆盖已开启，但没有选择视觉模型：这批附件不会发给聊天模型，请先选一个视觉模型"
                        .to_string(),
                ),
                ..VisionPlan::default()
            };
        }

        if media.is_empty() {
            return VisionPlan::default();
        }

        let mut tasks = Vec::new();
        let mut images = Vec::new();
        for attachment in media {
            match attachment.kind {
                MediaKind::Image => images.push(attachment.item.clone()),
                // 视频一个一条：时间轴信息不能和别的东西混在一起（D63）。
                MediaKind::Video => tasks.push(VisionTask {
                    kind: MediaKind::Video,
                    items: vec![attachment.item.clone()],
                    prompt: self.prompts.video.clone(),
                }),
            }
        }

        let batch = self.max_items_per_call.max(1);
        for chunk in images.chunks(batch) {
            tasks.push(VisionTask {
                kind: MediaKind::Image,
                items: chunk.to_vec(),
                prompt: self.prompts.image.clone(),
            });
        }

        let count = media.len();
        let task_count = tasks.len();
        VisionPlan {
            tasks,
            stays_with_main: Vec::new(),
            main_note: Some(format!(
                "本轮有 {count} 个附件，已交给视觉模型转成文字描述（共 {task_count} 次调用），\
                 描述在这条消息之后。附件本身没有发给聊天模型。"
            )),
            blocked_reason: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(name: &str) -> MediaAttachment {
        MediaAttachment {
            kind: MediaKind::Image,
            item: MediaItem {
                reference: format!("ref-{name}"),
                name: name.to_string(),
            },
        }
    }

    fn video(name: &str) -> MediaAttachment {
        MediaAttachment {
            kind: MediaKind::Video,
            item: MediaItem {
                reference: format!("ref-{name}"),
                name: name.to_string(),
            },
        }
    }

    fn enabled() -> VisionOverride {
        VisionOverride {
            enabled: true,
            model: Some("vision-model".to_string()),
            ..VisionOverride::default()
        }
    }

    #[test]
    fn default_prompts_are_the_two_the_user_asked_for() {
        let prompts = VisionPrompts::default();
        assert!(prompts.image.contains("Image Analysis Assistant"));
        assert!(prompts.video.contains("时间轴"));
        assert_ne!(
            prompts.image, prompts.video,
            "图像与视频必须各一份，不是同一份改个标题（D63）"
        );
    }

    /// 关掉覆盖：媒体留在主模型那边，什么都不做。这是唯一允许 `stays_with_main` 非空的情况。
    #[test]
    fn disabled_override_leaves_media_to_the_main_model() {
        let plan = VisionOverride::default().plan(&[image("a.png"), video("b.mp4")]);
        assert!(plan.tasks.is_empty());
        assert_eq!(plan.stays_with_main, vec!["ref-a.png", "ref-b.mp4"]);
        assert!(plan.main_note.is_none());
        assert!(plan.blocked_reason.is_none());
    }

    /// 开着覆盖：主模型一个媒体块都不收（D62）。
    #[test]
    fn enabled_override_takes_everything_away_from_the_main_model() {
        let plan = enabled().plan(&[image("a.png"), video("b.mp4")]);
        assert!(plan.stays_with_main.is_empty(), "开着覆盖还留媒体就是漏了");
        assert_eq!(plan.media_count(), 2);
        assert_eq!(plan.tasks.len(), 2, "一张图一条 + 一个视频一条");
        let note = plan.main_note.unwrap();
        assert!(note.contains("2 个附件"), "{note}");
    }

    /// 开着覆盖但没选模型：不干活，**也不把媒体兜给主模型**。
    #[test]
    fn enabled_without_a_model_blocks_instead_of_falling_back() {
        let plan = VisionOverride {
            enabled: true,
            model: None,
            ..VisionOverride::default()
        }
        .plan(&[image("a.png")]);
        assert!(plan.tasks.is_empty());
        assert!(
            plan.stays_with_main.is_empty(),
            "没模型也不能偷偷交给主模型看"
        );
        let reason = plan.blocked_reason.unwrap();
        assert!(reason.contains("视觉模型"), "{reason}");
    }

    #[test]
    fn images_are_batched_by_the_configured_size() {
        let override_ = VisionOverride {
            max_items_per_call: 2,
            ..enabled()
        };
        let plan = override_.plan(&[image("1"), image("2"), image("3")]);
        assert_eq!(plan.tasks.len(), 2, "三张图、每批两张");
        assert_eq!(plan.tasks[0].items.len(), 2);
        assert_eq!(plan.tasks[1].items.len(), 1);
        assert!(plan.tasks.iter().all(|task| task.kind == MediaKind::Image));
    }

    /// 视频永远一个一条，而且拿的是视频提示词——不和图片混批。
    #[test]
    fn every_video_is_its_own_task_with_the_video_prompt() {
        let plan = VisionOverride {
            max_items_per_call: 8,
            ..enabled()
        }
        .plan(&[video("1.mp4"), video("2.mp4"), image("3.png")]);
        assert_eq!(plan.tasks.len(), 3);
        let videos: Vec<&VisionTask> = plan
            .tasks
            .iter()
            .filter(|task| task.kind == MediaKind::Video)
            .collect();
        assert_eq!(videos.len(), 2);
        for task in videos {
            assert_eq!(task.items.len(), 1);
            assert!(task.prompt.contains("时间轴"), "视频要用视频那份提示词");
        }
        let image_task = plan
            .tasks
            .iter()
            .find(|task| task.kind == MediaKind::Image)
            .expect("图片那一条");
        assert!(image_task.prompt.contains("Image Analysis Assistant"));
    }

    /// 一批多张的时候，模型必须知道"第几张是哪个文件"，否则描述回来对不上附件。
    #[test]
    fn request_text_lists_the_items_in_order() {
        let plan = enabled().plan(&[image("一.png"), image("二.png")]);
        let text = plan.tasks[0].request_text();
        assert!(text.contains("共 2 个"), "{text}");
        assert!(text.contains("1. 一.png"), "{text}");
        assert!(text.contains("2. 二.png"), "{text}");
        assert!(
            text.starts_with("Image Analysis Assistant") || text.contains('#'),
            "提示词原样在最前面，不要加前缀：{text}"
        );
    }

    #[test]
    fn nothing_to_do_is_not_an_error() {
        let plan = enabled().plan(&[]);
        assert!(plan.tasks.is_empty());
        assert!(plan.main_note.is_none());
        assert!(plan.blocked_reason.is_none());
    }
}
