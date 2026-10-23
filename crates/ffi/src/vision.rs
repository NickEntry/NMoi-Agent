//! 视觉覆盖的设置面（P3.3 / D62 / D63）。
//!
//! 现在只给"默认提示词"——设置页要能把它们显示出来、让用户改，改完再存回配置
//! （持久化在 P3.2）。这里不替 UI 决定文案，也不做模板变量替换：提示词原样进出。

use std::ffi::c_char;

use nmoi_core::vision::{VisionPrompts, DEFAULT_IMAGE_PROMPT, DEFAULT_VIDEO_PROMPT};
use serde_json::json;

use crate::json::run_json;

/// 两套默认提示词：图像一份、视频一份（D63）。**两份是不同的东西**，不是同一份改个标题。
///
/// 还多说一句"它们分别是给什么模态用的"，免得 UI 把视频那份接到图片上。
#[no_mangle]
pub extern "C" fn nmoi_vision_defaults_json() -> *mut c_char {
    run_json(|| {
        let prompts = VisionPrompts::default();
        Ok(json!({
            "image": {
                "prompt": prompts.image,
                "chars": prompts.image.chars().count(),
                "media": "image",
                "note": "高精度复刻版：要求可被图像生成模型按文字复现。默认输出语言中文，专名保留原文。",
            },
            "video": {
                "prompt": prompts.video,
                "chars": prompts.video.chars().count(),
                "media": "video",
                "note": "时间轴版：分镜、镜头运动、场景切换、字幕与音轨、关键时刻。",
            },
            "identical": prompts.image == prompts.video,
            "builtin_image_chars": DEFAULT_IMAGE_PROMPT.chars().count(),
            "builtin_video_chars": DEFAULT_VIDEO_PROMPT.chars().count(),
        }))
    })
}
