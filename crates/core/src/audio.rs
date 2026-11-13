//! 音频小工具（0.17b）：给 TTS 产物做后处理，也能被模型直接调。
//!
//! 只认 16-bit PCM 的 WAV——TTS 那边我们自己也写这个格式，够用；
//! 遇到别的容器就报"先转成 wav"，不猜。
//!
//! 这些操作是**纯函数**（`Vec<f32>` 进、`Vec<f32>` 出），单独能测；
//! 文件层面的读写放在 `audio_tool` 那一层，路径解析走工作区，跟别的工具一致。

use std::f32::consts::PI;

#[derive(Clone, Debug)]
pub struct Wav {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples: Vec<f32>,
}

pub fn parse_wav(bytes: &[u8]) -> Result<Wav, String> {
    if bytes.len() < 44 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("不是 WAV（要 RIFF/WAVE 头）".into());
    }
    let mut at = 12usize;
    let mut channels = 1u16;
    let mut sample_rate = 24_000u32;
    let mut bits = 16u16;
    let mut data: Option<&[u8]> = None;
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = u32::from_le_bytes([bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]])
            as usize;
        let body = at + 8;
        let end = body.saturating_add(size).min(bytes.len());
        match id {
            b"fmt " => {
                if size < 16 {
                    return Err("fmt 段太短".into());
                }
                let format = u16::from_le_bytes([bytes[body], bytes[body + 1]]);
                if format != 1 {
                    return Err(format!("只认 PCM（format=1），这个文件是 format={format}"));
                }
                channels = u16::from_le_bytes([bytes[body + 2], bytes[body + 3]]).max(1);
                sample_rate = u32::from_le_bytes([
                    bytes[body + 4],
                    bytes[body + 5],
                    bytes[body + 6],
                    bytes[body + 7],
                ]);
                bits = u16::from_le_bytes([bytes[body + 14], bytes[body + 15]]);
            }
            b"data" => data = Some(&bytes[body..end]),
            _ => {}
        }
        at = body + size + (size & 1);
    }
    if bits != 16 {
        return Err(format!("只认 16-bit PCM，这个文件是 {bits}-bit"));
    }
    let Some(raw) = data else {
        return Err("没有 data 段".into());
    };
    let samples: Vec<f32> = raw
        .chunks_exact(2)
        .map(|pair| i16::from_le_bytes([pair[0], pair[1]]) as f32 / 32768.0)
        .collect();
    Ok(Wav {
        sample_rate,
        channels,
        samples,
    })
}

pub fn encode_wav(wav: &Wav) -> Vec<u8> {
    let bytes_per_sample = 2usize;
    let data_len = wav.samples.len() * bytes_per_sample;
    let mut out = Vec::with_capacity(44 + data_len);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&((36 + data_len) as u32).to_le_bytes());
    out.extend_from_slice(b"WAVE");
    out.extend_from_slice(b"fmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&wav.channels.to_le_bytes());
    out.extend_from_slice(&wav.sample_rate.to_le_bytes());
    let byte_rate = wav.sample_rate * wav.channels as u32 * bytes_per_sample as u32;
    out.extend_from_slice(&byte_rate.to_le_bytes());
    out.extend_from_slice(&(wav.channels * bytes_per_sample as u16).to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(data_len as u32).to_le_bytes());
    for sample in &wav.samples {
        let value = (sample.clamp(-1.0, 1.0) * 32767.0).round() as i16;
        out.extend_from_slice(&value.to_le_bytes());
    }
    out
}

fn frame_energy(samples: &[f32], start: usize, len: usize) -> f32 {
    let end = (start + len).min(samples.len());
    if end <= start {
        return 0.0;
    }
    let slice = &samples[start..end];
    (slice.iter().map(|s| s * s).sum::<f32>() / slice.len() as f32).sqrt()
}

/// 去掉首尾静音。`threshold_db` 相对峰值；`pad_ms` 留一点余量，免得切掉字头。
pub fn trim(wav: &mut Wav, threshold_db: f32, pad_ms: u32) {
    let peak = wav.samples.iter().fold(0.0f32, |acc, s| acc.max(s.abs()));
    if peak <= f32::EPSILON {
        return;
    }
    let floor = peak * 10f32.powf(threshold_db / 20.0);
    let window = (wav.sample_rate as usize / 50).max(1);
    let frames = wav.samples.len() / window;
    if frames == 0 {
        return;
    }
    let mut first = None;
    let mut last = None;
    for index in 0..frames {
        if frame_energy(&wav.samples, index * window, window) > floor {
            if first.is_none() {
                first = Some(index);
            }
            last = Some(index);
        }
    }
    let (Some(first), Some(last)) = (first, last) else {
        return;
    };
    let pad = (wav.sample_rate as u64 * pad_ms as u64 / 1000) as usize;
    let start = (first * window).saturating_sub(pad);
    let end = ((last + 1) * window + pad).min(wav.samples.len());
    wav.samples = wav.samples[start..end].to_vec();
}

/// 峰值归一：把最大振幅压到 `peak`（0–1），**只降不升**由 `allow_boost` 决定。
pub fn normalize(wav: &mut Wav, peak: f32, allow_boost: bool) -> f32 {
    let current = wav.samples.iter().fold(0.0f32, |acc, s| acc.max(s.abs()));
    if current <= f32::EPSILON {
        return 1.0;
    }
    let target = peak.clamp(0.01, 0.999);
    let gain = if allow_boost || target < current {
        target / current
    } else {
        1.0
    };
    if (gain - 1.0).abs() > f32::EPSILON {
        for sample in &mut wav.samples {
            *sample *= gain;
        }
    }
    gain
}

/// 淡入淡出（毫秒）。两端都按余弦走，听感比线性自然。
pub fn fade(wav: &mut Wav, in_ms: u32, out_ms: u32) {
    let rate = wav.sample_rate as usize;
    let fade_in = (rate * in_ms as usize / 1000).min(wav.samples.len());
    for index in 0..fade_in {
        let gain = 0.5 - 0.5 * (PI * index as f32 / fade_in.max(1) as f32).cos();
        wav.samples[index] *= gain;
    }
    let fade_out = (rate * out_ms as usize / 1000).min(wav.samples.len());
    let start = wav.samples.len() - fade_out;
    for offset in 0..fade_out {
        let gain = 0.5 + 0.5 * (PI * offset as f32 / fade_out.max(1) as f32).cos();
        wav.samples[start + offset] *= gain;
    }
}

/// 重采样（线性插值）。`factor > 1` = 变成更高的采样率（同时更短、音更高）。
pub fn resample(wav: &Wav, factor: f32) -> Wav {
    if (factor - 1.0).abs() < 1e-4 || wav.samples.is_empty() {
        return Wav {
            sample_rate: wav.sample_rate,
            channels: wav.channels,
            samples: wav.samples.clone(),
        };
    }
    let out_len = ((wav.samples.len() as f32) / factor).round().max(1.0) as usize;
    let mut samples = Vec::with_capacity(out_len);
    for index in 0..out_len {
        let pos = index as f32 * factor;
        let base = pos.floor() as usize;
        let frac = pos - base as f32;
        let a = wav.samples.get(base).copied().unwrap_or(0.0);
        let b = wav.samples.get(base + 1).copied().unwrap_or(a);
        samples.push(a + (b - a) * frac);
    }
    Wav {
        sample_rate: (wav.sample_rate as f32 * factor).round() as u32,
        channels: wav.channels,
        samples,
    }
}

/// WSOLA：时长伸缩、音高不变。`rate > 1` = 变长。
pub fn stretch(wav: &Wav, rate: f32) -> Wav {
    if rate <= 0.0 || (rate - 1.0).abs() < 1e-3 || wav.samples.is_empty() {
        return Wav {
            sample_rate: wav.sample_rate,
            channels: wav.channels,
            samples: wav.samples.clone(),
        };
    }
    let rate_hz = wav.sample_rate as usize;
    let frame = (rate_hz / 20).max(64);
    let hop = frame / 4;
    let analysis = ((hop as f32) / rate).round().max(1.0) as usize;
    let tolerance = rate_hz / 100;
    let window: Vec<f32> = (0..frame)
        .map(|index| 0.5 - 0.5 * (2.0 * PI * index as f32 / frame as f32).cos())
        .collect();

    let out_len = (wav.samples.len() as f32 * rate).round() as usize + frame;
    let mut out = vec![0.0f32; out_len];
    let mut weight = vec![0.0f32; out_len];
    let mut position = 0usize;
    let mut write_at = 0usize;
    let mut previous: Option<Vec<f32>> = None;

    while position + frame <= wav.samples.len() {
        let mut best = position;
        if let Some(tail) = &previous {
            let mut score = f32::MIN;
            let low = position.saturating_sub(tolerance);
            let high = (position + tolerance).min(wav.samples.len().saturating_sub(frame));
            for candidate in low..=high {
                let dot: f32 = wav.samples[candidate..candidate + hop]
                    .iter()
                    .zip(tail.iter())
                    .map(|(a, b)| a * b)
                    .sum();
                if dot > score {
                    score = dot;
                    best = candidate;
                }
            }
        }
        for index in 0..frame {
            if write_at + index < out_len {
                out[write_at + index] += wav.samples[best + index] * window[index];
                weight[write_at + index] += window[index];
            }
        }
        previous = Some(wav.samples[best + hop..best + hop * 2].to_vec());
        position += analysis;
        write_at += hop;
    }
    for index in 0..out_len {
        if weight[index] > 1e-6 {
            out[index] /= weight[index];
        }
    }
    out.truncate((wav.samples.len() as f32 * rate).round() as usize);
    Wav {
        sample_rate: wav.sample_rate,
        channels: wav.channels,
        samples: out,
    }
}

/// 变调不变速：先按时长拉伸，再重采样回原长。
pub fn pitch(wav: &Wav, semitones: f32) -> Wav {
    if semitones.abs() < 1e-3 {
        return Wav {
            sample_rate: wav.sample_rate,
            channels: wav.channels,
            samples: wav.samples.clone(),
        };
    }
    let ratio = 2f32.powf(semitones / 12.0);
    let stretched = stretch(wav, ratio);
    resample(&stretched, ratio)
}

/// 按顺序拼接，中间可以插静音。
pub fn concat(parts: &[Wav], gap_ms: u32) -> Result<Wav, String> {
    let Some(first) = parts.first() else {
        return Err("没有可拼接的音频".into());
    };
    let rate = first.sample_rate;
    let channels = first.channels;
    if parts
        .iter()
        .any(|p| p.sample_rate != rate || p.channels != channels)
    {
        return Err("拼接要求采样率与声道数一致（先各自转成同一个格式）".into());
    }
    let gap = vec![0.0f32; (rate as u64 * gap_ms as u64 / 1000) as usize * channels as usize];
    let mut samples = Vec::new();
    for (index, part) in parts.iter().enumerate() {
        if index > 0 {
            samples.extend_from_slice(&gap);
        }
        samples.extend_from_slice(&part.samples);
    }
    Ok(Wav {
        sample_rate: rate,
        channels,
        samples,
    })
}

/// 两条叠在一起（配乐 + 人声）。长度取长的，短的那条按 `gain` 补在后面。
pub fn mix(base: &Wav, overlay: &Wav, gain: f32) -> Result<Wav, String> {
    if base.sample_rate != overlay.sample_rate || base.channels != overlay.channels {
        return Err("混音要求采样率与声道数一致".into());
    }
    let len = base.samples.len().max(overlay.samples.len());
    let mut samples = vec![0.0f32; len];
    for (index, value) in base.samples.iter().enumerate() {
        samples[index] += value;
    }
    for (index, value) in overlay.samples.iter().enumerate() {
        samples[index] += value * gain;
    }
    for sample in &mut samples {
        *sample = sample.clamp(-1.0, 1.0);
    }
    Ok(Wav {
        sample_rate: base.sample_rate,
        channels: base.channels,
        samples,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(rate: u32, seconds: f32, hz: f32) -> Wav {
        let count = (rate as f32 * seconds) as usize;
        let samples = (0..count)
            .map(|index| (2.0 * PI * hz * index as f32 / rate as f32).sin() * 0.3)
            .collect();
        Wav {
            sample_rate: rate,
            channels: 1,
            samples,
        }
    }

    #[test]
    fn wav_round_trips() {
        let wav = tone(24_000, 0.5, 220.0);
        let bytes = encode_wav(&wav);
        let back = parse_wav(&bytes).unwrap();
        assert_eq!(back.sample_rate, 24_000);
        assert_eq!(back.samples.len(), wav.samples.len());
        let diff: f32 = back
            .samples
            .iter()
            .zip(&wav.samples)
            .map(|(a, b)| (a - b).abs())
            .fold(0.0, f32::max);
        assert!(diff < 0.001, "往返差 {diff}");
    }

    #[test]
    fn trim_drops_silence_and_keeps_the_tone() {
        let mut wav = tone(24_000, 0.2, 300.0);
        let mut padded = vec![0.0f32; 4_800];
        padded.extend_from_slice(&wav.samples);
        padded.extend(std::iter::repeat(0.0f32).take(4_800));
        wav.samples = padded;
        let before = wav.samples.len();
        trim(&mut wav, -40.0, 20);
        assert!(wav.samples.len() < before, "没切掉静音");
        assert!(
            wav.samples.len() > 4_000,
            "把音本身也切了：{}",
            wav.samples.len()
        );
    }

    #[test]
    fn normalize_sets_peak() {
        let mut wav = tone(24_000, 0.1, 200.0);
        normalize(&mut wav, 0.9, true);
        let peak = wav.samples.iter().fold(0.0f32, |acc, s| acc.max(s.abs()));
        assert!((peak - 0.9).abs() < 0.01, "峰值 {peak}");
    }

    #[test]
    fn fade_starts_and_ends_quiet() {
        let mut wav = tone(24_000, 0.5, 200.0);
        normalize(&mut wav, 0.8, true);
        fade(&mut wav, 50, 50);
        assert!(wav.samples[0].abs() < 0.01);
        assert!(wav.samples[wav.samples.len() - 1].abs() < 0.01);
        // 单个采样点可能正好落在过零处，取中间一段的 RMS 才说明"没被淡掉"。
        let middle = wav.samples.len() / 2;
        let slice = &wav.samples[middle - 1_200..middle + 1_200];
        let rms = (slice.iter().map(|s| s * s).sum::<f32>() / slice.len() as f32).sqrt();
        assert!(rms > 0.3, "中间被淡没了：rms={rms}");
    }

    #[test]
    fn tempo_changes_duration_not_pitch() {
        let wav = tone(24_000, 1.0, 200.0);
        let slow = stretch(&wav, 1.5);
        assert!(slow.samples.len() > wav.samples.len() * 3 / 2 - 200);
        // 过零率与原来差不多 = 音高没被搬走
        let rate_of = |data: &[f32]| {
            let crossings = data
                .windows(2)
                .filter(|w| (w[0] >= 0.0) != (w[1] >= 0.0))
                .count();
            crossings as f32 / data.len() as f32
        };
        let a = rate_of(&wav.samples);
        let b = rate_of(&slow.samples);
        assert!((a - b).abs() / a < 0.15, "过零率 {a} → {b}");
    }

    #[test]
    fn pitch_moves_the_tone() {
        let wav = tone(24_000, 0.6, 200.0);
        let up = pitch(&wav, 12.0);
        let rate_of = |data: &[f32]| {
            let crossings = data
                .windows(2)
                .filter(|w| (w[0] >= 0.0) != (w[1] >= 0.0))
                .count();
            crossings as f32 / (data.len() as f32 / 24_000.0) / 2.0
        };
        let before = rate_of(&wav.samples);
        let after = rate_of(&up.samples);
        assert!(
            (after / before - 2.0).abs() < 0.25,
            "频率 {before} → {after}"
        );
    }

    #[test]
    fn concat_and_mix_keep_the_longest() {
        let a = tone(24_000, 0.3, 200.0);
        let b = tone(24_000, 0.2, 300.0);
        let joined = concat(&[a.clone(), b.clone()], 100).unwrap();
        assert_eq!(
            joined.samples.len(),
            a.samples.len() + b.samples.len() + 2_400
        );
        let mixed = mix(&a, &b, 0.5).unwrap();
        assert_eq!(mixed.samples.len(), a.samples.len());
    }

    #[test]
    fn other_containers_are_refused() {
        let error = parse_wav(b"not a wav at all").unwrap_err();
        assert!(error.contains("WAV"), "{error}");
    }
}
