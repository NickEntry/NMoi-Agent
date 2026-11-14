//! `Speak`（0.17d）：把一句话变成音频文件。
//!
//! 只接 **OpenAI 形状**（`POST /audio/speech`）：`{model, input, voice, response_format, speed}`。
//! 别的形状（MiniMax `t2a_v2`、Gemini 的音频模态、系统 TTS）各有各的请求体，硬套会得到一个
//! 看不懂的 400 —— 所以它们在这里**明确回一句"还没接"**，并说清现在支持哪一种。
//!
//! 三条边界：
//!
//! - **只要 wav**：`trim` / `normalize` / `fade` 只认 16-bit PCM，mp3 进来只能原样落盘，
//!   那就成了"后处理看着有、其实没做"。所以请求里写死 `response_format: "wav"`。
//! - **密钥只往前走一步**：它由这一轮的语音配置带进来，只用来拼 `Authorization`，
//!   不进结果、不进日志、不进库。
//! - **产物落在会话自己的目录**（`sessions/<id>/audio/<hash>.wav`）：删会话一起删，
//!   界面上那一行说得清它在哪儿。

use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{json, Value};

use crate::audio::{self, Wav};
use crate::policy::Capability;
use crate::tools::{Lane, Tool, ToolContext, ToolOutcome};
use crate::transport::{HttpRequest, Transport};
use crate::voicecaps::VoiceSlot;

/// 一次请求最多几个字。OpenAI 的上限是 4096 个字符，取它。
const MAX_TEXT: usize = 4096;
/// `script` 最多几段：再多就不是"一句话"，该分段生成再拼。
const MAX_SEGMENTS: usize = 12;
/// 回包上限。4 分钟 44.1 kHz 单声道 16-bit 大约 21 MB。
const MAX_BYTES: usize = 24 * 1024 * 1024;
/// 单次请求的读超时：合成比对话慢，但慢过两分钟就是出问题了。
const READ_TIMEOUT: Duration = Duration::from_secs(120);
/// 峰值归一的目标。留一点余量，转成 16-bit 时不削顶。
const PEAK: f32 = 0.95;

/// 结果里的 `texts` 其实是字数——名字直白一点。
const TEXT_KEY: &str = "chars";

pub struct SpeakTool;

impl Tool for SpeakTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "Speak"
    }

    fn description(&self) -> &str {
        concat!(
            "把一段文字合成音频，落到这条会话的 audio 目录，返回路径、字节数与时长。\n",
            "两种写法（二选一）：`text` 一句话；`script` 分段（每段自己的语速与停顿，",
            "段间按 pause_ms 补静音）。\n",
            "参数按 Voice-Caps 返回的 supports 来：这一条不支持 speed 时别填 rate。\n",
            "当前只支持 OpenAI 形状的端点；别的提供方会明确回\"还没接\"。",
            "产物是 wav，写完会顺手做归一与淡入淡出。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "要说的一句话（与 script 二选一，最多 4096 字）"
                },
                "script": {
                    "type": "array",
                    "description": "分段脚本（与 text 二选一，最多 12 段）",
                    "items": {
                        "type": "object",
                        "properties": {
                            "text": { "type": "string", "description": "这一段说的话" },
                            "rate": {
                                "type": "number",
                                "description": "这一段的语速倍率（0.25–4），不填就用工具的 rate"
                            },
                            "pause_ms": {
                                "type": "integer",
                                "description": "这一段之后的停顿（毫秒，0–5000）"
                            }
                        },
                        "required": ["text"],
                        "additionalProperties": false
                    }
                },
                "voice": {
                    "type": "string",
                    "description": "音色（覆盖语音服务里那一条的设定）"
                },
                "rate": {
                    "type": "number",
                    "description": "语速倍率 0.25–4，默认 1"
                }
            },
            "additionalProperties": false
        })
    }

    fn capability(&self) -> Capability {
        Capability::Voice
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        // 参数先看，再看环境：参数写错了与"这一轮没有语音"是两种问题。
        let Some(request) = Request::parse(args) else {
            return ToolOutcome::failed(
                "参数要么给 text（一句话），要么给 script（分段），二选一、不能都给也不能都不给",
            );
        };
        let Some(slot) = ctx.voice.and_then(|caps| caps.tts.as_ref()) else {
            return ToolOutcome::failed(
                "还没选文字转语音：设置 → 工具与工作区 → 语音 → 语音服务里加一条并选中它",
            );
        };
        if let Err(message) = supported(slot) {
            return ToolOutcome::failed(message);
        }
        let Some(transport) = ctx.transport else {
            return ToolOutcome::failed("这个入口没有传输层：语音合成只能在对话里调");
        };
        let Some(dir) = ctx.session_audio else {
            return ToolOutcome::failed("这条会话没有音频目录：合成得在一条具体的会话里");
        };

        let Plan { segments, total } = match request.plan(ctx, MAX_TEXT) {
            Ok(plan) => plan,
            Err(message) => return ToolOutcome::failed(message),
        };
        let mut rendered: Vec<Wav> = Vec::with_capacity(segments.len() * 2);
        for (index, segment) in segments.iter().enumerate() {
            if ctx.call.map(|call| call.cancelled()).unwrap_or(false) {
                return ToolOutcome::failed("这一条被取消了");
            }
            let wav = match synthesize(transport, slot, segment) {
                Ok(wav) => wav,
                Err(message) => {
                    return ToolOutcome::failed(format!(
                        "第 {} 段（共 {} 段）没合成出来：{message}",
                        index + 1,
                        segments.len()
                    ))
                }
            };
            rendered.push(wav);
            if segment.pause_ms > 0 {
                rendered.push(silence(&rendered[0], segment.pause_ms));
            }
        }
        let mut joined = match audio::concat(&rendered, 0) {
            Ok(wav) => wav,
            Err(message) => return ToolOutcome::failed(format!("拼接失败：{message}")),
        };
        // 后处理：归一 + 两端 8 ms 淡入淡出。
        //
        // 允许抬升：合成音只有一条声源，底噪本来就低；不抬的话，一条小声的回复会一直小声。
        audio::normalize(&mut joined, PEAK, true);
        audio::fade(&mut joined, 8, 8);
        let bytes = audio::encode_wav(&joined);
        let hash = fingerprint(&segments, slot);
        let path = dir.join(format!("{hash}.wav"));
        if let Err(error) = write_atomic(&path, &bytes) {
            return ToolOutcome::failed(format!("写 {} 失败：{error}", path.display()));
        }
        ToolOutcome::ok(
            json!({
                "path": path.to_string_lossy(),
                "bytes": bytes.len(),
                "duration_ms": duration_ms(&joined),
                "segments": segments.len(),
                "voice": segments[0].voice.clone(),
                "model": slot.model,
                "provider": slot.provider,
                TEXT_KEY: total,
                "post": ["normalize", "fade"],
            })
            .to_string(),
        )
    }
}

/// 这一条能不能走这条链。不能的话把"现在支持哪一种"一起说出来。
fn supported(slot: &VoiceSlot) -> Result<(), String> {
    match slot.provider.as_str() {
        "openai" | "custom" => {}
        "system" => {
            return Err("系统 TTS 是手机自己的引擎，core 这条路到不了它（平台侧还没接）".into())
        }
        "local" => return Err("本地语音模型还没接运行时：装好模型也暂时发不出声".into()),
        other => {
            return Err(format!(
                "{other} 的语音形状还没接：当前支持 OpenAI 形状（provider=openai，\
                 或者把这一条按 custom 配一个兼容基址）"
            ))
        }
    }
    if slot.base_url.trim().is_empty() {
        return Err("这一条没有填 API 基址".into());
    }
    if slot.api_key.as_deref().unwrap_or("").trim().is_empty() {
        return Err("这一条还没存密钥：在语音服务那一条里填一次 API Key".into());
    }
    Ok(())
}

/// 一段要合成的东西。
#[derive(Debug, Clone, PartialEq)]
struct Segment {
    text: String,
    voice: String,
    rate: Option<f32>,
    pause_ms: u32,
}

/// 解析后的入参。两种形状在这里就分开，后面只认 `Segment`。
#[derive(Debug, Clone, PartialEq)]
struct Request {
    text: Option<String>,
    script: Vec<Value>,
    voice: Option<String>,
    rate: Option<f32>,
}

struct Plan {
    segments: Vec<Segment>,
    total: usize,
}

impl Request {
    fn parse(args: &Value) -> Option<Self> {
        let text = args
            .get("text")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|text| !text.is_empty())
            .map(str::to_string);
        let script = args
            .get("script")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let has_text = text.is_some();
        let has_script = !script.is_empty();
        if has_text == has_script {
            // 两个都有、或者两个都没有
            return None;
        }
        let voice = args
            .get("voice")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|voice| !voice.is_empty())
            .map(str::to_string);
        let rate = args.get("rate").and_then(Value::as_f64).map(|v| v as f32);
        Some(Self {
            text,
            script,
            voice,
            rate,
        })
    }

    /// 展开成一段一段要合成的东西，顺便把长度、段数、倍率都过一遍。
    fn plan(&self, ctx: &ToolContext<'_>, max_text: usize) -> Result<Plan, String> {
        let slot = ctx
            .voice
            .and_then(|caps| caps.tts.as_ref())
            .ok_or_else(|| "还没选文字转语音".to_string())?;
        let default_voice = self.voice.clone().unwrap_or_else(|| slot.voice.clone());
        if default_voice.trim().is_empty() {
            return Err("这一条没有音色：在语音服务里填一个，或者调用时给 voice".into());
        }
        let mut segments = Vec::new();
        if let Some(text) = &self.text {
            if text.chars().count() > max_text {
                return Err(format!(
                    "这一段 {} 个字，超过了 {max_text}：拆成 script 分几段说",
                    text.chars().count()
                ));
            }
            segments.push(Segment {
                text: text.clone(),
                voice: default_voice,
                rate: self.rate,
                pause_ms: 0,
            });
        } else {
            if self.script.len() > MAX_SEGMENTS {
                return Err(format!(
                    "script 有 {} 段，最多 {MAX_SEGMENTS} 段：再长就分段生成，别一次塞进来",
                    self.script.len()
                ));
            }
            for (index, item) in self.script.iter().enumerate() {
                let text = item
                    .get("text")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|text| !text.is_empty())
                    .ok_or_else(|| format!("第 {} 段缺 text", index + 1))?;
                if text.chars().count() > max_text {
                    return Err(format!(
                        "第 {} 段 {} 个字，超过了 {max_text}",
                        index + 1,
                        text.chars().count()
                    ));
                }
                let rate = item
                    .get("rate")
                    .and_then(Value::as_f64)
                    .map(|value| value as f32)
                    .or(self.rate);
                let pause_ms = item.get("pause_ms").and_then(Value::as_u64).unwrap_or(0) as u32;
                if pause_ms > 5_000 {
                    return Err(format!("第 {} 段的 pause_ms 超过 5000", index + 1));
                }
                segments.push(Segment {
                    text: text.to_string(),
                    voice: default_voice.clone(),
                    rate,
                    pause_ms,
                });
            }
            if let Some(last) = segments.last_mut() {
                // 最后一段之后的停顿只会让文件多一截静音，没有意义
                last.pause_ms = 0;
            }
        }

        for (index, segment) in segments.iter().enumerate() {
            if let Some(rate) = segment.rate {
                if !(0.25..=4.0).contains(&rate) {
                    return Err(format!("第 {} 段的 rate 是 {rate}，只认 0.25–4", index + 1));
                }
                if !slot.supports.iter().any(|cap| cap == "speed") {
                    return Err(
                        "这一条不支持 speed：把 rate 去掉（Voice-Caps 的 supports 里没有它）"
                            .into(),
                    );
                }
            }
        }
        let total = segments.iter().map(|s| s.text.chars().count()).sum();
        Ok(Plan { segments, total })
    }
}

/// 发一次请求，拿回一段 WAV。
fn synthesize(
    transport: &dyn Transport,
    slot: &VoiceSlot,
    segment: &Segment,
) -> Result<Wav, String> {
    let mut body = json!({
        "model": slot.model,
        "input": segment.text,
        "voice": segment.voice,
        "response_format": "wav",
    });
    if let Some(rate) = segment.rate {
        // `f32` 直接转 `f64` 会带出一串尾巴（0.9 变成 0.8999999761581421），
        // 端点不认这种数也只是"看着怪"，但日志里没法读，先抹平到三位。
        let speed = (f64::from(rate) * 1000.0).round() / 1000.0;
        body["speed"] = json!(speed);
    }
    let url = format!(
        "{}/audio/speech",
        slot.base_url.trim().trim_end_matches('/')
    );
    let mut request = HttpRequest::post_json(url, body.to_string()).with_read_timeout(READ_TIMEOUT);
    if let Some(key) = slot.api_key.as_deref() {
        request = request.header("authorization", format!("Bearer {key}"));
    }
    let mut response = transport
        .execute(request)
        .map_err(|error| format!("请求没发出去：{error}"))?;
    if !response.is_success() {
        let status = response.status;
        let text = response.read_to_string_limit(2048).unwrap_or_default();
        return Err(format!(
            "端点回了 {}：{}",
            status,
            text.trim().chars().take(300).collect::<String>()
        ));
    }
    let mut bytes = Vec::new();
    let mut reader = response.body.take(MAX_BYTES as u64 + 1);
    let read = reader
        .read_to_end(&mut bytes)
        .map_err(|error| format!("读回包失败：{error}"))?;
    if read > MAX_BYTES {
        return Err(format!(
            "回包超过 {} MB，拒绝继续读",
            MAX_BYTES / 1024 / 1024
        ));
    }
    audio::parse_wav(&bytes).map_err(|error| {
        format!(
            "回包不是 16-bit PCM WAV（{error}）。开场字节是 {}；这一条可能不认 \
             response_format=wav，换一条或者换成 OpenAI 兼容端点",
            hex_head(&bytes)
        )
    })
}

fn silence(reference: &Wav, ms: u32) -> Wav {
    let frames = (reference.sample_rate as u64 * ms as u64 / 1000) as usize;
    Wav {
        sample_rate: reference.sample_rate,
        channels: reference.channels,
        samples: vec![0.0; frames * reference.channels as usize],
    }
}

fn duration_ms(wav: &Wav) -> u64 {
    let frames = wav.samples.len() / wav.channels.max(1) as usize;
    (frames as u64 * 1000) / wav.sample_rate.max(1) as u64
}

/// 结果文件名的来源：同样的输入落同一个名字，第二遍不会在盘上堆一堆。
fn fingerprint(segments: &[Segment], slot: &VoiceSlot) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    let mut feed = |text: &str| {
        for byte in text.as_bytes() {
            hash ^= u64::from(*byte);
            hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
        }
    };
    feed(&slot.provider);
    feed(&slot.model);
    for segment in segments {
        feed(&segment.text);
        feed(&segment.voice);
        feed(&format!("{:?}|{};", segment.rate, segment.pause_ms));
    }
    format!("{hash:016x}")
}

/// 先写临时文件再改名：合成到一半被杀不会留半截音频。
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let temp: PathBuf = path.with_extension("part");
    std::fs::write(&temp, bytes).map_err(|error| error.to_string())?;
    std::fs::rename(&temp, path).map_err(|error| error.to_string())
}

fn hex_head(bytes: &[u8]) -> String {
    bytes
        .iter()
        .take(16)
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

/// 测试里要能伪造一条"选中的 TTS"，所以这里给一个构造入口（`api_key` 只在测试里设）。
pub fn test_slot(provider: &str, base_url: &str, api_key: &str) -> VoiceSlot {
    VoiceSlot {
        name: "测试".into(),
        provider: provider.into(),
        model: "tts-1".into(),
        voice: "alloy".into(),
        language: "auto".into(),
        supports: vec!["speed".into(), "format".into()],
        known_voices: vec!["alloy".into()],
        known_languages: vec!["auto".into()],
        has_key: !api_key.is_empty(),
        base_url: base_url.into(),
        api_key: (!api_key.is_empty()).then(|| api_key.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{AssistantPolicy, WorkspaceBinding};
    use crate::tools::ToolRegistry;
    use crate::transport::PlainHttpTransport;
    use crate::voicecaps::VoiceCaps;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;

    /// 一条假端点：收下请求，回一段固定的 WAV。返回（基址，收到的请求数通道）。
    fn fake_server(wav: Vec<u8>, replies: usize) -> (String, mpsc::Receiver<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("绑本机端口");
        let address = listener.local_addr().unwrap();
        let (sender, receiver) = mpsc::channel();
        std::thread::spawn(move || {
            for _ in 0..replies {
                let Ok((mut stream, _)) = listener.accept() else {
                    return;
                };
                let mut head = Vec::new();
                let mut byte = [0u8; 1];
                while !head.ends_with(b"\r\n\r\n") {
                    match stream.read(&mut byte) {
                        Ok(1) => head.push(byte[0]),
                        _ => break,
                    }
                }
                let text = String::from_utf8_lossy(&head).into_owned();
                let length = text
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().ok())?
                    })
                    .unwrap_or(0);
                let mut body = vec![0u8; length];
                if length > 0 {
                    let _ = stream.read_exact(&mut body);
                }
                let _ = sender.send(format!("{text}\n{}", String::from_utf8_lossy(&body)));
                let head = format!(
                    "HTTP/1.1 200 OK\r\ncontent-type: audio/wav\r\ncontent-length: {}\r\n\r\n",
                    wav.len()
                );
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(&wav);
                let _ = stream.flush();
            }
        });
        (format!("http://{address}/v1"), receiver)
    }

    fn tone(ms: u32) -> Wav {
        let rate = 24_000u32;
        let frames = rate * ms / 1000;
        let samples = (0..frames)
            .map(|index| {
                let t = index as f32 / rate as f32;
                (2.0 * std::f32::consts::PI * 220.0 * t).sin() * 0.3
            })
            .collect();
        Wav {
            sample_rate: rate,
            channels: 1,
            samples,
        }
    }

    fn context<'a>(
        policy: &'a AssistantPolicy,
        voice: &'a VoiceCaps,
        audio: &'a Path,
        transport: &'a dyn Transport,
    ) -> ToolContext<'a> {
        ToolContext::new(policy, None)
            .with_voice(Some(voice))
            .with_audio(Some(audio))
            .with_transport(Some(transport))
    }

    fn policy() -> AssistantPolicy {
        AssistantPolicy::locked_down()
            .with_binding(WorkspaceBinding::read_only("ws"))
            .enable_voice(true)
    }

    #[test]
    fn one_sentence_lands_as_a_playable_wav() {
        let wav = audio::encode_wav(&tone(600));
        let (base, received) = fake_server(wav, 1);
        let dir = std::env::temp_dir().join(format!("nmoi-speak-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let policy = policy();
        let voice = VoiceCaps {
            tts: Some(test_slot("openai", &base, "sk-test")),
            ..VoiceCaps::default()
        };
        let transport = PlainHttpTransport::new();
        let outcome = SpeakTool.invoke(
            &json!({"text": "今天有点累"}),
            &context(&policy, &voice, &dir, &transport),
        );
        let text = outcome.text();
        assert!(outcome.is_ok(), "{text}");
        let request = received
            .recv_timeout(Duration::from_secs(5))
            .expect("端点收到过请求");
        assert!(request.starts_with("POST /v1/audio/speech"), "{request}");
        assert!(
            request
                .to_lowercase()
                .contains("authorization: bearer sk-test"),
            "密钥要进 Authorization：{request}"
        );
        assert!(request.contains("\"input\":\"今天有点累\""), "{request}");
        assert!(request.contains("\"response_format\":\"wav\""), "{request}");
        assert!(request.contains("\"voice\":\"alloy\""), "{request}");
        let result: Value = serde_json::from_str(&text).expect("结果是 JSON");
        let path = PathBuf::from(result["path"].as_str().unwrap());
        assert_eq!(path.extension().and_then(|ext| ext.to_str()), Some("wav"));
        assert!(path.is_file(), "文件要真的写下来：{}", path.display());
        assert!(result["duration_ms"].as_u64().unwrap() > 300, "{result}");
        let parsed = audio::parse_wav(&std::fs::read(&path).unwrap()).expect("产物还是 wav");
        assert_eq!(parsed.channels, 1);
        // 归一之后峰值接近 0.95：证明后处理真的跑了
        let peak = parsed
            .samples
            .iter()
            .fold(0.0f32, |acc, s| acc.max(s.abs()));
        assert!(peak > 0.9 && peak <= 1.0, "峰值 {peak}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_script_form_posts_once_per_segment_and_keeps_the_pauses() {
        let wav = audio::encode_wav(&tone(400));
        let (base, received) = fake_server(wav, 2);
        let dir = std::env::temp_dir().join(format!("nmoi-speak2-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let policy = policy();
        let voice = VoiceCaps {
            tts: Some(test_slot("openai", &base, "sk-test")),
            ..VoiceCaps::default()
        };
        let transport = PlainHttpTransport::new();
        let outcome = SpeakTool.invoke(
            &json!({"script": [
                {"text": "第一句", "rate": 0.9, "pause_ms": 200},
                {"text": "第二句", "rate": 1.4}
            ]}),
            &context(&policy, &voice, &dir, &transport),
        );
        let text = outcome.text();
        assert!(outcome.is_ok(), "{text}");
        let first = received
            .recv_timeout(Duration::from_secs(5))
            .expect("第一段");
        let second = received
            .recv_timeout(Duration::from_secs(5))
            .expect("第二段");
        assert!(first.contains("\"speed\":0.9"), "{first}");
        assert!(second.contains("\"speed\":1.4"), "{second}");
        let result: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(result["segments"], json!(2));
        // 400 + 200 + 400 = 1000 ms 左右（最后一段之后的停顿不算）
        let duration = result["duration_ms"].as_u64().unwrap();
        assert!((900..=1200).contains(&duration), "时长 {duration}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_unsupported_provider_says_what_is_supported_instead() {
        for (provider, needle) in [
            ("minimax", "OpenAI 形状"),
            ("system", "手机自己的引擎"),
            ("local", "运行时"),
        ] {
            let policy = policy();
            let voice = VoiceCaps {
                tts: Some(test_slot(provider, "http://127.0.0.1:1/v1", "k")),
                ..VoiceCaps::default()
            };
            let transport = PlainHttpTransport::new();
            let dir = std::env::temp_dir();
            let outcome = SpeakTool.invoke(
                &json!({"text": "x"}),
                &context(&policy, &voice, &dir, &transport),
            );
            let text = outcome.text();
            assert!(!outcome.is_ok(), "{provider} 不该成功：{text}");
            assert!(text.contains(needle), "{provider}: {text}");
        }
    }

    #[test]
    fn a_missing_key_and_a_missing_base_url_are_named() {
        let policy = policy();
        let transport = PlainHttpTransport::new();
        let dir = std::env::temp_dir();
        let no_key = VoiceCaps {
            tts: Some(test_slot("openai", "http://127.0.0.1:1/v1", "")),
            ..VoiceCaps::default()
        };
        let text = SpeakTool
            .invoke(
                &json!({"text": "x"}),
                &context(&policy, &no_key, &dir, &transport),
            )
            .text();
        assert!(text.contains("密钥"), "{text}");

        let no_base = VoiceCaps {
            tts: Some(test_slot("openai", "  ", "k")),
            ..VoiceCaps::default()
        };
        let text = SpeakTool
            .invoke(
                &json!({"text": "x"}),
                &context(&policy, &no_base, &dir, &transport),
            )
            .text();
        assert!(text.contains("基址"), "{text}");
    }

    #[test]
    fn both_forms_at_once_is_rejected_with_the_rule() {
        let policy = policy();
        let voice = VoiceCaps {
            tts: Some(test_slot("openai", "http://127.0.0.1:1/v1", "k")),
            ..VoiceCaps::default()
        };
        let transport = PlainHttpTransport::new();
        let dir = std::env::temp_dir();
        let text = SpeakTool
            .invoke(
                &json!({"text": "x", "script": [{"text": "y"}]}),
                &context(&policy, &voice, &dir, &transport),
            )
            .text();
        assert!(text.contains("二选一"), "{text}");
    }

    #[test]
    fn rate_without_support_is_rejected_by_name() {
        let policy = policy();
        let mut slot = test_slot("openai", "http://127.0.0.1:1/v1", "k");
        slot.supports = vec!["format".into()];
        let voice = VoiceCaps {
            tts: Some(slot),
            ..VoiceCaps::default()
        };
        let transport = PlainHttpTransport::new();
        let dir = std::env::temp_dir();
        let text = SpeakTool
            .invoke(
                &json!({"text": "x", "rate": 1.2}),
                &context(&policy, &voice, &dir, &transport),
            )
            .text();
        assert!(text.contains("不支持 speed"), "{text}");
    }

    #[test]
    fn a_non_wav_reply_says_what_came_back() {
        let (base, _received) = fake_server(b"{\"error\":\"nope\"}".to_vec(), 1);
        let policy = policy();
        let voice = VoiceCaps {
            tts: Some(test_slot("openai", &base, "k")),
            ..VoiceCaps::default()
        };
        let transport = PlainHttpTransport::new();
        let dir = std::env::temp_dir().join(format!("nmoi-speak3-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let text = SpeakTool
            .invoke(
                &json!({"text": "x"}),
                &context(&policy, &voice, &dir, &transport),
            )
            .text();
        assert!(text.contains("WAV"), "{text}");
        assert!(text.contains("7b 22 65 72"), "要带开场字节：{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_tool_is_not_declared_while_voice_is_off() {
        let registry = ToolRegistry::workspace_defaults();
        assert!(registry.names().contains(&"Speak"));
        let off = AssistantPolicy::locked_down();
        assert!(registry
            .specs_for(&off)
            .iter()
            .all(|spec| spec.name != "Speak"));
        let on = AssistantPolicy::locked_down().enable_voice(true);
        assert!(registry
            .specs_for(&on)
            .iter()
            .any(|spec| spec.name == "Speak"));
    }

    #[test]
    fn the_key_never_shows_up_in_voice_caps() {
        let slot = test_slot("openai", "http://127.0.0.1:1/v1", "sk-secret");
        let caps = VoiceCaps {
            tts: Some(slot),
            ..VoiceCaps::default()
        };
        let text = caps.to_json().to_string();
        assert!(!text.contains("sk-secret"), "{text}");
        assert!(text.contains("\"has_key\":true"), "{text}");
    }
}
