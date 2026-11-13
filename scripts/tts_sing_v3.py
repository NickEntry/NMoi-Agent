#!/usr/bin/env python3
"""唱歌渲染 v3：WORLD 声码器（pyworld）。

v2 的 PSOLA 有两个天花板：标记贴不准就抵消，贴准了共振峰也还是被窗口长度牵着走。
WORLD 把声音拆成三样——基频、谱包络（音色）、非周期成分——改基频和时长、
谱包络原样带过去，这才是"变调不变音色"的正经做法（BiSinger 那类工作也是这么做的）。

用法：
  python3 sing3.py --out /tmp/v3.wav [--glide] [--vibrato 0.25] [--voice ...]
"""

import argparse
import asyncio
import math
import os
import subprocess
import sys
import wave

import numpy as np
import pyworld as pw

SR = 24000
FRAME_MS = 5.0
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")
BEAT_MS = 420

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


def hz(note: int) -> float:
    return 440.0 * 2 ** ((note - 69) / 12)


def midi_of(freq: float) -> float:
    return 69 + 12 * math.log2(freq / 440.0) if freq > 0 else 0.0


async def tts(text: str, path: str, voice: str) -> None:
    import edge_tts

    await edge_tts.Communicate(text, voice).save(path)


def decode(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True,
        capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def write_wav(path: str, data: np.ndarray) -> None:
    peak = float(np.max(np.abs(data))) or 1.0
    clipped = np.clip(data / peak * 0.92, -1.0, 1.0)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((clipped * 32767).astype(np.int16).tobytes())


def analyse(x: np.ndarray):
    f0, sp, ap = pw.wav2world(x.astype(np.float64), SR, frame_period=FRAME_MS)
    return f0, sp, ap


def synth_syllable(
    src: np.ndarray,
    target_hz: float,
    target_len: int,
    glide_from: float,
    vibrato: float,
) -> np.ndarray:
    """一个音：辅音原速，元音拉长；基频换成目标音高（带滑音与颤音），谱包络不动。"""
    f0, sp, ap = analyse(src)
    voiced = f0 > 0
    if not np.any(voiced):
        return np.zeros(target_len)

    onset = int(np.argmax(voiced))
    frames = len(f0)
    head_frames = onset
    body_frames = frames - onset

    head_ms = head_frames * FRAME_MS
    body_ms = max(40.0, target_len / SR * 1000.0 - head_ms)
    out_frames = int(round((head_ms + body_ms) / FRAME_MS))
    body_out = max(1, out_frames - head_frames)

    # 时间映射：辅音 1:1，元音线性拉长
    out_t = np.arange(out_frames) * FRAME_MS
    src_t = np.empty(out_frames)
    for index, ms in enumerate(out_t):
        if ms <= head_ms:
            src_t[index] = ms
        else:
            ratio = (ms - head_ms) / (body_out * FRAME_MS)
            src_t[index] = head_ms + ratio * body_frames * FRAME_MS
    src_index = np.clip(src_t / FRAME_MS, 0, frames - 1)

    def take(matrix):
        lo = np.floor(src_index).astype(int)
        hi = np.minimum(lo + 1, frames - 1)
        frac = (src_index - lo)[:, None]
        return matrix[lo] * (1 - frac) + matrix[hi] * frac

    sp_new = take(sp)
    ap_new = take(ap)
    voiced_new = take(voiced.astype(np.float64)[:, None])[:, 0] > 0.5

    # 目标基频：滑音（从上一个音滑过来）+ 颤音（元音 150 ms 之后才起）
    f0_new = np.zeros(out_frames)
    body_t = np.maximum(0.0, out_t - head_ms)
    for index in range(out_frames):
        if not voiced_new[index]:
            continue
        value = target_hz
        if glide_from > 0 and body_t[index] < 90.0:
            weight = body_t[index] / 90.0
            value = math.exp(
                math.log(glide_from) * (1 - weight) + math.log(target_hz) * weight
            )
        if vibrato > 0 and body_t[index] > 150.0:
            ramp = min(1.0, (body_t[index] - 150.0) / 220.0)
            depth = vibrato * ramp
            value *= 2 ** (depth * math.sin(2 * math.pi * 5.5 * body_t[index] / 1000.0) / 12.0)
        f0_new[index] = value

    y = pw.synthesize(f0_new, sp_new, ap_new, SR, FRAME_MS)
    if len(y) > target_len:
        y = y[:target_len]
    elif len(y) < target_len:
        y = np.pad(y, (0, target_len - len(y)))
    return y


def render(voice: str, glide: bool, vibrato: float, overlap_ms: int = 0) -> np.ndarray:
    os.makedirs(CACHE, exist_ok=True)
    total = int(sum(dur for _, dur, _ in MELODY) * BEAT_MS + 700)
    track = np.zeros(int(SR * total / 1000))
    at = 0.0
    previous = 0.0
    for note, dur, lyric in MELODY:
        safe = "".join(ch if ch.isalnum() else "_" for ch in lyric)
        raw = os.path.join(CACHE, f"{voice}-{safe}.mp3")
        if not os.path.exists(raw):
            asyncio.run(tts(lyric, raw, voice))
        src = decode(raw)
        target_len = int(SR * dur * BEAT_MS / 1000)
        piece = synth_syllable(
            src,
            hz(note),
            target_len,
            previous if glide else 0.0,
            vibrato,
        )
        edge = int(SR * 0.012)
        if len(piece) > 2 * edge:
            piece = piece.copy()
            piece[:edge] *= np.linspace(0, 1, edge)
            piece[-edge:] *= np.linspace(1, 0, edge)
        start = int(SR * at / 1000)
        end = min(len(track), start + len(piece))
        track[start:end] += piece[: end - start]
        previous = hz(note)
        at += dur * BEAT_MS - (overlap_ms if overlap_ms else 0)

    fade = int(SR * 0.02)
    track[:fade] *= np.linspace(0, 1, fade)
    track[-fade:] *= np.linspace(1, 0, fade)
    return track


def check(path: str, overlap_ms: int = 25) -> int:
    data = decode(path)
    at, bad = 0.0, 0
    for note, dur, lyric in MELODY:
        start = int(SR * at / 1000)
        end = int(SR * (at + dur * BEAT_MS) / 1000)
        seg = data[start:min(end, len(data))]
        if len(seg) > SR // 10:
            mid = len(seg) // 3
            seg = seg[mid:len(seg) - mid]
        if len(seg) < SR // 40:
            print(f"?? {lyric} 太短")
            at += dur * BEAT_MS - overlap_ms
            continue
        f0, _, _ = analyse(seg)
        voiced = f0[f0 > 0]
        measured = float(np.median(voiced)) if len(voiced) else 0.0
        delta = 1200 * math.log2(measured / hz(note)) if measured > 0 else 999.0
        flag = "ok " if abs(delta) < 60 else "OFF"
        if flag == "OFF":
            bad += 1
        print(f"{flag} {lyric} 目标 {hz(note):7.1f} 实测 {measured:7.1f} 偏差 {delta:+7.1f}")
        at += dur * BEAT_MS - overlap_ms
    return bad


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--voice", default="zh-CN-XiaoxiaoNeural")
    parser.add_argument("--out", default="/tmp/v3.wav")
    parser.add_argument("--glide", action="store_true")
    parser.add_argument("--vibrato", type=float, default=0.0)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        sys.exit(0 if check(args.out) == 0 else 1)
    audio = render(args.voice, args.glide, args.vibrato)
    write_wav(args.out, audio)
    print(f"{args.out}: {len(audio) / SR:.2f}s")
