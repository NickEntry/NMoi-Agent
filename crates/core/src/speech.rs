//! 语音输入（ASR）与朗读（TTS）的**预留扩展位**。
//!
//! 现在只定形状，不实现。目的是让 UI（消息下的"朗读"按钮、输入框的语音按钮）和
//! 模型配置层（五类模型角色）从一开始就有位置可挂，而不是等实现时再改接口。
//!
//! 两条约束与五类模型角色一致：
//!
//! 1. 走同一套 provider / model 配置（含思考开关等无关项要能禁掉）；
//! 2. **单独计量**，失败静默降级，绝不影响主对话。

use serde::{Deserialize, Serialize};

use crate::provider::ProviderKind;

/// 指向某个 provider 上的某个模型。与五类模型角色共用形状。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelRef {
    pub provider_id: String,
    pub model_id: String,
    pub kind: ProviderKind,
}

/// 一个可插拔的语音能力配置。
///
/// 默认全关（`#[derive(Default)]` 就是我们要的语义）：与"可选增强默认关"（D31）一致。
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct SpeechConfig {
    /// 朗读（TTS）。`None` = 未配置，UI 上按钮置灰。
    pub tts: Option<ModelRef>,
    /// 语音识别（ASR）。`None` = 未配置，长按语音按钮不出现。
    pub asr: Option<ModelRef>,
    /// 音色 / 发音人（各家叫法不同，原样透传）。
    pub voice: Option<String>,
    /// 语速倍率，1.0 为原速。
    pub speed: Option<f32>,
    /// 是否把识别结果自动发送（false = 先填进输入框让用户确认）。
    pub auto_send_after_asr: bool,
}

impl SpeechConfig {
    pub fn tts_available(&self) -> bool {
        self.tts.is_some()
    }

    pub fn asr_available(&self) -> bool {
        self.asr.is_some()
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TtsRequest {
    pub text: String,
    pub model: ModelRef,
    pub voice: Option<String>,
    pub speed: Option<f32>,
}

/// 合成结果。音频字节由调用方负责落地（写临时文件/直接播放）。
#[derive(Debug, Clone, PartialEq)]
pub struct TtsAudio {
    pub bytes: Vec<u8>,
    /// MIME，例如 `audio/mpeg`、`audio/wav`。
    pub mime: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AsrRequest {
    pub audio: Vec<u8>,
    pub mime: String,
    pub model: ModelRef,
    /// 语言提示（BCP-47），`None` 交给模型判断。
    pub language: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AsrResult {
    pub text: String,
    /// 置信度（0.0–1.0），不是所有端都提供。
    pub confidence: Option<f32>,
    pub language: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum SpeechError {
    #[error("未配置该语音能力")]
    NotConfigured,
    #[error("provider 拒绝: {0}")]
    Provider(String),
    #[error("音频格式不支持: {0}")]
    UnsupportedFormat(String),
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
}

/// 朗读引擎。实现放平台侧（系统 TTS 或某个 provider）。
pub trait TtsEngine {
    fn name(&self) -> &str;
    fn synthesize(&self, request: TtsRequest) -> Result<TtsAudio, SpeechError>;
}

/// 语音识别引擎。实现放平台侧（系统 ASR 或某个 provider）。
pub trait AsrEngine {
    fn name(&self) -> &str;
    fn transcribe(&self, request: AsrRequest) -> Result<AsrResult, SpeechError>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_all_off() {
        let config = SpeechConfig::default();
        assert!(!config.tts_available());
        assert!(!config.asr_available());
        assert!(!config.auto_send_after_asr);
    }

    #[test]
    fn configured_capabilities_report_available() {
        let r#ref = ModelRef {
            provider_id: "local".into(),
            model_id: "tts-1".into(),
            kind: ProviderKind::OpenAiCompatible,
        };
        let config = SpeechConfig {
            tts: Some(r#ref.clone()),
            ..SpeechConfig::default()
        };
        assert!(config.tts_available());
        assert!(!config.asr_available());
    }

    struct Echo;
    impl TtsEngine for Echo {
        fn name(&self) -> &str {
            "echo"
        }
        fn synthesize(&self, request: TtsRequest) -> Result<TtsAudio, SpeechError> {
            Ok(TtsAudio {
                bytes: request.text.into_bytes(),
                mime: "audio/plain".into(),
            })
        }
    }

    #[test]
    fn trait_shape_is_usable() {
        let engine = Echo;
        let audio = engine
            .synthesize(TtsRequest {
                text: "你好".into(),
                model: ModelRef {
                    provider_id: "p".into(),
                    model_id: "m".into(),
                    kind: ProviderKind::OpenAiCompatible,
                },
                voice: None,
                speed: None,
            })
            .unwrap();
        assert_eq!(audio.mime, "audio/plain");
        assert_eq!(engine.name(), "echo");
    }
}
