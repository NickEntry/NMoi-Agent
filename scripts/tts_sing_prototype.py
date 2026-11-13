#!/usr/bin/env python3
"""唱歌渲染原型（0.17b 的实验，不是产品代码）。

思路：**不要求 TTS 自己会唱**。任何一个能念字的 TTS 都能拿来唱：
每个音符单独念一个音节 → 估它的基频 → WSOLA 拉长到目标时值 → 重采样到目标音高 → 按谱拼接。
云端 TTS（edge / OpenAI / Gemini / MiniMax…）和本地 TTS 走的是同一条流水线。

用法：
  python3 sing.py out.wav
  python3 sing.py out.wav --check      # 只打印每个音符实测音高与偏差
"""

import argparse
import asyncio
import math
import os
import subprocess
import sys
import wave

import numpy as np

SR = 24000
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")
VOICE = "zh-CN-XiaoxiaoNeural"

# 小星星：音高 + 时值（拍）。1 = C4
C4, D4, E4, F4, G4, A4 = 0, 2, 4, 5, 7, 9


def midi(note: int, octave: int = 0) -> int:
    return 60 + note + 12 * octave


MELODY = [
    (midi(C4), 1.0, "一"),
    (midi(D4) - 2, 1.0, "闪"),  # C4
    (midi(G4) - 5, 1.0, "一"),  # G4? 见下方修正
]
MELODY = [
    (60, 1.0, "一"),
    (60, 1.0, "闪"),
    (67, 1.0, "一"),
    (67, 1.0, "闪"),
    (69, 1.0, "亮"),
    (69, 1.0, "晶"),
    (67, 2.0, "晶"),
    (65, 1.0, "满"),
    (65, 1.0, "天"),
    (64, 1.0, "都"),
    (64, 1.0, "是"),
    (62, 1.0, "小"),
    (62, 1.0, "星"),
    (60, 2.0, "星"),
]

BEAT_MS = 420


def hz(note: int) -> float:
    return 440.0 * 2 ** ((note - 69) / 12)


async def tts(text: str, path: str) -> None:
    import edge_tts

    communicate = edge_tts.Communicate(text, VOICE)
    await communicate.save(path)


def decode(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True,
        capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def write_wav(path: str, data: np.ndarray) -> None:
    peak = float(np.max(np.abs(data))) or 1.0
    out = np.clip(data / peak * 0.92, -1.0, 1.0)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((out * 32767).astype(np.int16).tobytes())


def trim(x: np.ndarray, floor_db: float = -34.0) -> np.ndarray:
    win = int(SR * 0.02)
    if len(x) < win:
        return x
    frames = len(x) // win
    energy = np.array([np.sqrt(np.mean(x[i * win:(i + 1) * win] ** 2)) for i in range(frames)])
    peak = energy.max() or 1e-9
    keep = np.where(energy > peak * 10 ** (floor_db / 20))[0]
    if len(keep) == 0:
        return x
    start = max(0, keep[0] * win - win)
    end = min(len(x), (keep[-1] + 2) * win)
    return x[start:end]


def f0_of(x: np.ndarray, lo: float = 70.0, hi: float = 500.0) -> float:
    """自相关估基频（取中间那段最响的 60 ms）。"""
    if len(x) < int(SR * 0.05):
        return 0.0
    win = int(SR * 0.06)
    center = len(x) // 2
    seg = x[max(0, center - win // 2): center + win // 2]
    if len(seg) < win:
        seg = x[:win]
    seg = seg - seg.mean()
    corr = np.correlate(seg, seg, mode="full")[len(seg) - 1:]
    lo_lag = int(SR / hi)
    hi_lag = min(len(corr) - 1, int(SR / lo))
    if hi_lag <= lo_lag:
        return 0.0
    band = corr[lo_lag:hi_lag]
    idx = int(np.argmax(band)) + lo_lag
    if idx <= 0 or idx >= len(corr) - 1:
        return SR / max(1, idx)
    a, b, c = corr[idx - 1], corr[idx], corr[idx + 1]
    denom = a - 2 * b + c
    shift = 0.5 * (a - c) / denom if denom != 0 else 0.0
    return SR / (idx + shift)


def wsola(x: np.ndarray, rate: float) -> np.ndarray:
    """时间伸缩，保持音高。rate > 1 = 变长。"""
    if rate <= 0:
        return x
    frame = int(SR * 0.05)
    hop = frame // 4
    ana = max(1, int(round(hop / rate)))
    tol = int(SR * 0.010)
    win = np.hanning(frame)
    out = np.zeros(int(len(x) * rate) + 2 * frame)
    wsum = np.zeros_like(out)
    pos = 0
    k = 0
    prev_tail = None
    while pos + frame <= len(x):
        best = pos
        if prev_tail is not None:
            score = -1e18
            for cand in range(max(0, pos - tol), min(len(x) - frame, pos + tol) + 1):
                seg = x[cand:cand + hop]
                value = float(np.dot(seg, prev_tail))
                if value > score:
                    score = value
                    best = cand
        seg = x[best:best + frame] * win
        out[k * hop:k * hop + frame] += seg
        wsum[k * hop:k * hop + frame] += win
        prev_tail = x[best + hop:best + hop * 2]
        pos += ana
        k += 1
    mask = wsum > 1e-6
    out[mask] /= wsum[mask]
    return out[:int(len(x) * rate)]


def resample(x: np.ndarray, factor: float) -> np.ndarray:
    if abs(factor - 1.0) < 1e-4:
        return x
    n = max(1, int(len(x) / factor))
    src = np.linspace(0, len(x) - 1, n)
    return np.interp(src, np.arange(len(x)), x)


def vibrato(x: np.ndarray, rate_hz: float, depth_semitone: float) -> np.ndarray:
    """长音加一点颤音：用时间弯曲实现，音高按 5.5 Hz 上下摆动。"""
    n = len(x)
    t = np.arange(n) / SR
    warp = t + (depth_semitone / 12.0) * 0.5 / (math.pi * rate_hz) * np.sin(2 * math.pi * rate_hz * t)
    mapped = np.interp(warp, t, x)
    return mapped


def render() -> np.ndarray:
    os.makedirs(CACHE, exist_ok=True)
    total_ms = sum(dur for _, dur, _ in MELODY) * BEAT_MS + 400
    track = np.zeros(int(SR * total_ms / 1000))
    at = 0
    for note, dur, lyric in MELODY:
        safe = "".join(ch if ch.isalnum() else "_" for ch in lyric)
        raw = os.path.join(CACHE, f"{VOICE}-{safe}.mp3")
        if not os.path.exists(raw):
            asyncio.run(tts(lyric, raw))
        src = trim(decode(raw))
        f_src = f0_of(src)
        f_dst = hz(note)
        d0 = len(src) / SR
        d_target = dur * BEAT_MS / 1000.0
        if f_src <= 0:
            raise SystemExit(f"估不出「{lyric}」的基频")
        ratio = f_dst / f_src
        stretch = d_target * ratio / d0
        stretched = wsola(src, stretch)
        shifted = resample(stretched, ratio)
        if len(shifted) > int(SR * d_target):
            shifted = shifted[:int(SR * d_target)]
        if dur >= 1.5 and len(shifted) > SR // 4:
            shifted = vibrato(shifted, 5.5, 0.18)
        edge = min(int(SR * 0.006), len(shifted) // 2)
        if edge > 0:
            shifted[:edge] *= np.linspace(0, 1, edge)
            shifted[-edge:] *= np.linspace(1, 0, edge)
        start = int(SR * at / 1000)
        end = min(len(track), start + len(shifted))
        track[start:end] += shifted[:end - start]
        at += dur * BEAT_MS
    fade = int(SR * 0.02)
    track[:fade] *= np.linspace(0, 1, fade)
    track[-fade:] *= np.linspace(1, 0, fade)
    return track


def cents(a: float, b: float) -> float:
    if a <= 0 or b <= 0:
        return 999.0
    return 1200 * math.log2(a / b)


def check(path: str) -> int:
    data = decode(path)
    at = 0
    bad = 0
    for note, dur, lyric in MELODY:
        start = int(SR * at / 1000)
        end = int(SR * (at + dur * BEAT_MS) / 1000)
        seg = data[start:min(end, len(data))]
        measured = f0_of(seg) if len(seg) > SR // 20 else 0.0
        delta = cents(measured, hz(note))
        flag = "ok " if abs(delta) < 60 else "OFF"
        if flag == "OFF":
            bad += 1
        print(f"{flag} {lyric} 目标 {hz(note):7.1f} Hz  实测 {measured:7.1f} Hz  偏差 {delta:+7.1f} 音分")
        at += dur * BEAT_MS
    return bad


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("out", nargs="?", default="sing.wav")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        sys.exit(0 if check(args.out) == 0 else 1)
    audio = render()
    write_wav(args.out, audio)
    print(f"写入 {args.out}：{len(audio) / SR:.2f} 秒")
