//! `Voice-Caps`（0.17c）：这一轮选中的 TTS / ASR 各自支持什么。
//!
//! 为什么它排在合成之前：`Speak` 的参数该有哪几格，取决于选中那一条的能力表。
//! 先让模型能问"你现在这条支持什么"，schema 才有依据；反过来先定 schema，
//! 就是拿一个猜出来的并集去顶所有家——每条都收到自己不认的字段。
//!
//! 边界：
//!
//! - 只读、不联网、不碰密钥。`has_key` 只说"有没有"，值不进这一层。
//! - 没配任何一条时**不声明**（`Capability::Voice` 关着），模型看不到它。
//! - `known_*` 是快捷清单，不是白名单：账号后台多出来的音色照样能用。

use serde_json::{json, Map, Value};

use crate::policy::Capability;
use crate::tools::{Lane, Tool, ToolContext, ToolOutcome};

/// 一条语音服务在**这一轮**的样子，字段与界面上那一行一一对应。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct VoiceSlot {
    pub name: String,
    pub provider: String,
    pub model: String,
    pub voice: String,
    pub language: String,
    /// 这一条支持哪些参数（`speed` / `pitch` / `format` / `instructions` …）。
    pub supports: Vec<String>,
    /// 已知音色。**不是白名单**。
    pub known_voices: Vec<String>,
    pub known_languages: Vec<String>,
    pub has_key: bool,
}

impl VoiceSlot {
    /// `key` 是这段 JSON 在整份配置里的位置（`voice.tts`），报错时原样带上。
    pub fn from_json(value: &Value, key: &str) -> Result<Self, String> {
        let object = value
            .as_object()
            .ok_or_else(|| format!("{key} 必须是对象"))?;
        Ok(Self {
            name: text(object, key, "name", true)?,
            provider: text(object, key, "provider", true)?,
            model: text(object, key, "model", false)?,
            voice: text(object, key, "voice", false)?,
            language: text(object, key, "language", false)?,
            supports: list(object, key, "supports")?,
            known_voices: list(object, key, "known_voices")?,
            known_languages: list(object, key, "known_languages")?,
            has_key: flag(object, key, "has_key")?,
        })
    }

    fn to_json(&self, direction: &str) -> Value {
        let mut out = Map::new();
        out.insert("direction".into(), json!(direction));
        out.insert("name".into(), json!(self.name));
        out.insert("provider".into(), json!(self.provider));
        if !self.model.is_empty() {
            out.insert("model".into(), json!(self.model));
        }
        if !self.voice.is_empty() {
            out.insert("voice".into(), json!(self.voice));
        }
        if !self.language.is_empty() {
            out.insert("language".into(), json!(self.language));
        }
        out.insert("supports".into(), json!(self.supports));
        if !self.known_voices.is_empty() {
            out.insert("known_voices".into(), json!(self.known_voices));
        }
        if !self.known_languages.is_empty() {
            out.insert("known_languages".into(), json!(self.known_languages));
        }
        out.insert("has_key".into(), json!(self.has_key));
        Value::Object(out)
    }
}

/// 唱歌那一半的现状：引擎与声库都要在，缺一样就是"唱不了"。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SingStatus {
    pub ready: bool,
    pub engine: String,
    pub bank: String,
    /// 不可用时的一句话原因。`ready` 时为空。
    pub reason: String,
}

impl SingStatus {
    pub fn from_json(value: &Value, key: &str) -> Result<Self, String> {
        let object = value
            .as_object()
            .ok_or_else(|| format!("{key} 必须是对象"))?;
        Ok(Self {
            ready: flag(object, key, "ready")?,
            engine: text(object, key, "engine", false)?,
            bank: text(object, key, "bank", false)?,
            reason: text(object, key, "reason", false)?,
        })
    }

    fn to_json(&self) -> Value {
        let mut out = Map::new();
        out.insert("ready".into(), json!(self.ready));
        if !self.engine.is_empty() {
            out.insert("engine".into(), json!(self.engine));
        }
        if !self.bank.is_empty() {
            out.insert("bank".into(), json!(self.bank));
        }
        if !self.reason.is_empty() {
            out.insert("reason".into(), json!(self.reason));
        }
        Value::Object(out)
    }
}

/// 这一轮的语音现状。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct VoiceCaps {
    pub tts: Option<VoiceSlot>,
    pub asr: Option<VoiceSlot>,
    pub sing: Option<SingStatus>,
}

impl VoiceCaps {
    /// 有没有可问的东西。`false` = 这一轮不该声明 `Voice-Caps`
    /// （只装了声库但没引擎不算——那时候它只会回一句"唱不了"）。
    pub fn any(&self) -> bool {
        self.tts.is_some()
            || self.asr.is_some()
            || self.sing.as_ref().map(|sing| sing.ready).unwrap_or(false)
    }

    pub fn from_json(value: &Value) -> Result<Self, String> {
        let object = value
            .as_object()
            .ok_or_else(|| "voice 必须是对象（tts / asr / sing）".to_string())?;
        Ok(Self {
            tts: slot_of(object, "tts")?,
            asr: slot_of(object, "asr")?,
            sing: match object.get("sing") {
                None | Some(Value::Null) => None,
                Some(sing) => Some(SingStatus::from_json(sing, "voice.sing")?),
            },
        })
    }

    pub fn to_json(&self) -> Value {
        json!({
            "tts": self.tts.as_ref().map(|slot| slot.to_json("tts")),
            "asr": self.asr.as_ref().map(|slot| slot.to_json("asr")),
            "sing": self.sing.as_ref().map(SingStatus::to_json),
            "note": "known_* 是快捷清单，不是白名单：音色可以直接写清单以外的值。",
        })
    }
}

fn slot_of(object: &Map<String, Value>, key: &str) -> Result<Option<VoiceSlot>, String> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => Ok(Some(VoiceSlot::from_json(value, &format!("voice.{key}"))?)),
    }
}

fn text(
    object: &Map<String, Value>,
    key: &str,
    name: &str,
    required: bool,
) -> Result<String, String> {
    match object.get(name) {
        None | Some(Value::Null) => {
            if required {
                Err(format!("缺 {key}.{name}"))
            } else {
                Ok(String::new())
            }
        }
        Some(Value::String(value)) => Ok(value.clone()),
        Some(_) => Err(format!("{key}.{name} 必须是字符串")),
    }
}

fn list(object: &Map<String, Value>, key: &str, name: &str) -> Result<Vec<String>, String> {
    match object.get(name) {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::Array(items)) => {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                match item.as_str() {
                    Some(text) => out.push(text.to_string()),
                    None => return Err(format!("{key}.{name} 里有一项不是字符串")),
                }
            }
            Ok(out)
        }
        Some(_) => Err(format!("{key}.{name} 必须是字符串数组")),
    }
}

fn flag(object: &Map<String, Value>, key: &str, name: &str) -> Result<bool, String> {
    match object.get(name) {
        None | Some(Value::Null) => Ok(false),
        Some(Value::Bool(value)) => Ok(*value),
        Some(_) => Err(format!("{key}.{name} 必须是 true/false")),
    }
}

/// `Voice-Caps`：回答"选中的那一条支持什么"，不合成、不识别。
pub struct VoiceCapsTool;

impl Tool for VoiceCapsTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "Voice-Caps"
    }

    fn description(&self) -> &str {
        concat!(
            "读，不联网：当前选中的文字转语音与语音识别各支持什么。\n",
            "返回每一条的提供方 / 模型 / 音色 / 语种 / 支持的参数（speed、pitch、format 之类），",
            "以及唱歌能不能用（声库与引擎都在才行）。\n",
            "参数用哪几格以这里返回的 supports 为准：不同提供方差得很远，别按上一家猜。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {},
            "additionalProperties": false
        })
    }

    fn capability(&self) -> Capability {
        Capability::Voice
    }

    fn invoke(&self, _args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let Some(caps) = ctx.voice else {
            return ToolOutcome::failed(
                "这一轮没有语音配置：设置 → 工具与工作区 → 语音 → 语音服务里先选一条",
            );
        };
        if !caps.any() {
            return ToolOutcome::failed("选中的语音服务都没有：TTS 与 ASR 两条列表都是空的");
        }
        ToolOutcome::ok(caps.to_json().to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{AssistantPolicy, DenyReason};
    use crate::tools::ToolRegistry;

    fn slot(name: &str) -> VoiceSlot {
        VoiceSlot {
            name: name.into(),
            provider: "openai".into(),
            model: "tts-1".into(),
            voice: "alloy".into(),
            language: "auto".into(),
            supports: vec!["speed".into(), "format".into()],
            known_voices: vec!["alloy".into(), "nova".into()],
            known_languages: vec!["auto".into(), "zh".into()],
            has_key: true,
        }
    }

    #[test]
    fn a_selected_tts_reports_its_supported_parameters() {
        let caps = VoiceCaps {
            tts: Some(slot("OpenAI 默认")),
            ..VoiceCaps::default()
        };
        assert!(caps.any());
        let json = caps.to_json();
        assert_eq!(json["tts"]["direction"], json!("tts"));
        assert_eq!(json["tts"]["voice"], json!("alloy"));
        assert_eq!(json["tts"]["supports"][0], json!("speed"));
        assert_eq!(json["tts"]["known_voices"][1], json!("nova"));
        assert_eq!(json["tts"]["has_key"], json!(true));
        // 没配的那一条也要显式给 null：缺字段与"没有"是两件事。
        assert_eq!(json["asr"], Value::Null);
    }

    #[test]
    fn a_configured_slot_alone_is_enough_to_declare() {
        assert!(VoiceCaps {
            asr: Some(slot("Whisper")),
            ..VoiceCaps::default()
        }
        .any());
    }

    #[test]
    fn a_bank_without_an_engine_does_not_count() {
        let caps = VoiceCaps {
            sing: Some(SingStatus {
                ready: false,
                engine: String::new(),
                bank: "EcklDaTiger".into(),
                reason: "引擎运行时还没接".into(),
            }),
            ..VoiceCaps::default()
        };
        assert!(!caps.any(), "唱不了的唱歌不该让工具出现在声明里");
        assert_eq!(caps.to_json()["sing"]["ready"], json!(false));
        assert_eq!(caps.to_json()["sing"]["reason"], json!("引擎运行时还没接"));
    }

    #[test]
    fn parsing_names_the_exact_field() {
        let bad = json!({ "tts": { "name": "x", "provider": "openai", "model": 5 } });
        let error = VoiceCaps::from_json(&bad).expect_err("model 不是字符串要报错");
        assert!(error.contains("voice.tts.model"), "{error}");

        let missing = json!({ "asr": { "provider": "openai" } });
        let error = VoiceCaps::from_json(&missing).expect_err("缺 name 要报错");
        assert!(error.contains("voice.asr.name"), "{error}");

        let not_object = json!({ "tts": "openai" });
        let error = VoiceCaps::from_json(&not_object).expect_err("不是对象要报错");
        assert!(error.contains("voice.tts"), "{error}");
    }

    #[test]
    fn parsing_accepts_a_slot_without_the_optional_lists() {
        let value = json!({
            "tts": { "name": "本机", "provider": "system" },
            "sing": { "ready": true, "engine": "warble", "bank": "EcklDaTiger" }
        });
        let caps = VoiceCaps::from_json(&value).expect("可选字段缺了也算合法");
        let slot = caps.tts.as_ref().expect("tts 在");
        assert!(slot.supports.is_empty());
        assert!(!slot.has_key);
        assert!(caps.any());
        assert_eq!(caps.sing.as_ref().unwrap().engine, "warble");
    }

    fn context<'a>(policy: &'a AssistantPolicy, voice: Option<&'a VoiceCaps>) -> ToolContext<'a> {
        ToolContext::new(policy, None).with_voice(voice)
    }

    #[test]
    fn the_tool_reports_what_the_selected_entry_supports() {
        let policy = AssistantPolicy::locked_down().enable_voice(true);
        let caps = VoiceCaps {
            tts: Some(slot("OpenAI 默认")),
            ..VoiceCaps::default()
        };
        let outcome = VoiceCapsTool.invoke(&json!({}), &context(&policy, Some(&caps)));
        let text = outcome.text();
        assert!(outcome.is_ok(), "{text}");
        assert!(text.contains("\"tts\""), "{text}");
        assert!(text.contains("speed"), "{text}");
    }

    #[test]
    fn the_tool_is_denied_while_the_capability_is_off() {
        let policy = AssistantPolicy::locked_down();
        let caps = VoiceCaps {
            tts: Some(slot("OpenAI 默认")),
            ..VoiceCaps::default()
        };
        let outcome = VoiceCapsTool.invoke(&json!({}), &context(&policy, Some(&caps)));
        match outcome {
            ToolOutcome::Denied { capability, reason } => {
                assert_eq!(capability, Capability::Voice);
                assert_eq!(reason, DenyReason::FeatureDisabled);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn the_tool_says_which_setting_is_missing() {
        let policy = AssistantPolicy::locked_down().enable_voice(true);
        let outcome = VoiceCapsTool.invoke(&json!({}), &context(&policy, None));
        assert!(outcome.text().contains("语音服务"), "{}", outcome.text());
    }

    #[test]
    fn the_tool_is_not_declared_without_a_voice_capability() {
        let registry = ToolRegistry::workspace_defaults();
        assert!(registry.names().contains(&"Voice-Caps"));
        let off = AssistantPolicy::locked_down();
        assert!(registry
            .specs_for(&off)
            .iter()
            .all(|spec| spec.name != "Voice-Caps"));
        let on = AssistantPolicy::locked_down().enable_voice(true);
        assert!(registry
            .specs_for(&on)
            .iter()
            .any(|spec| spec.name == "Voice-Caps"));
    }
}
