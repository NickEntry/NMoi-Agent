#!/usr/bin/env python3
"""唱歌渲染 v2：用 TD-PSOLA 替换"重采样改音高"，保住共振峰。

v1（sing.py）的毛病：重采样改音高会连共振峰一起搬走，听起来像加了个鼻音塞子。
TD-PSOLA 是复制原始基音周期再按目标周期摆回去，音高变了、音色（共振峰）还在原处。

用法：
  python3 sing2.py --voice zh-CN-XiaoxiaoNeural --out /tmp/a.wav [--legato] [--vibrato]
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
    out = np.clip(data / peak * 0.92, -1.0, 1.0)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((out * 32767).astype(np.int16).tobytes())


def frames(x: np.ndarray, size: int, hop: int) -> np.ndarray:
    if len(x) < size:
        x = np.pad(x, (0, size - len(x)))
    count = 1 + (len(x) - size) // hop
    return np.lib.stride_tricks.as_strided(
        x, shape=(count, size), strides=(x.strides[0] * hop, x.strides[0])
    ).copy()


def f0_track(x: np.ndarray, lo: float = 70.0, hi: float = 550.0) -> np.ndarray:
    size, hop = int(SR * 0.03), int(SR * 0.01)
    lo_lag, hi_lag = int(SR / hi), int(SR / lo)
    out = np.zeros(len(frames(x, size, hop)))
    for index, frame in enumerate(frames(x, size, hop)):
        frame = frame - frame.mean()
        energy = float(np.sqrt(np.mean(frame ** 2)))
        if energy < 1e-3:
            continue
        corr = np.correlate(frame, frame, mode="full")[size - 1:]
        band = corr[lo_lag:hi_lag]
        if len(band) == 0:
            continue
        lag = int(np.argmax(band)) + lo_lag
        if corr[0] > 0 and corr[lag] / corr[0] > 0.35:
            out[index] = SR / lag
    return out


def voiced_start(x: np.ndarray, f0s: np.ndarray) -> int:
    hop = int(SR * 0.01)
    found = [i for i, value in enumerate(f0s) if value > 0]
    if not found:
        return 0
    return max(0, found[0] * hop - hop)


def psola(x: np.ndarray, src_f0: float, dst_f0: float, length: int, vibrato: float) -> np.ndarray:
    """TD-PSOLA：按目标周期摆放原始基音周期窗口，共振峰留在原地。"""
    if src_f0 <= 0 or dst_f0 <= 0:
        return np.zeros(length)
    p_src = int(round(SR / src_f0))
    p_dst = SR / dst_f0
    if p_src < 8:
        return np.zeros(length)

    window = 2 * p_src
    out = np.zeros(length + 2 * window)
    weight = np.zeros_like(out)
    taper = np.hanning(window)

    # 输入基音标记：对齐到最强的峰，之后均匀铺开
    lo = max(0, len(x) // 2 - window)
    hi = min(len(x), len(x) // 2 + window)
    anchor = lo + int(np.argmax(np.abs(x[lo:hi]))) if hi > lo else 0
    marks = []
    position = anchor
    while position - window >= 0:
        position -= p_src

    # 贴到附近最强的峰上，并且**从贴过的位置继续往后走**。
    # 不这么做的话，均匀铺开的标记会慢慢跑偏出声门脉冲，复制出来的窗口互相抵消
    # （听感是"半个音高"或者沙哑），三声这种音高会变的字尤其明显。
    def snap(at: int) -> int:
        lo = max(0, at - p_src // 4)
        hi = min(len(x), at + p_src // 4 + 1)
        if hi <= lo:
            return at
        return lo + int(np.argmax(np.abs(x[lo:hi])))

    # 网格按 p_src 往前走，标记只在网格附近 ±p_src/4 内贴峰：
    # 让标记跟着脉冲走（否则复制窗口互相抵消），但不许网格自己被带跑
    # （从贴过的位置继续走会漏掉周期，听感是音低了一截）。
    while position < len(x):
        hit = snap(position)
        if hit - window // 2 >= 0 and hit + window // 2 <= len(x):
            marks.append(hit)
        position += p_src
    if not marks:
        return np.zeros(length)

    t = 0.0
    index = 0
    while t < length:
        period = p_dst
        if vibrato > 0:
            period = p_dst * (1.0 + vibrato * math.sin(2 * math.pi * 5.5 * t / SR))
        source = marks[min(len(marks) - 1, max(0, index))]
        seg = x[max(0, source - window // 2):source + window // 2]
        if len(seg) < window:
            seg = np.pad(seg, (0, window - len(seg)))
        start = int(t)
        out[start:start + window] += seg * taper
        weight[start:start + window] += taper
        t += period
        index += 1

    mask = weight > 1e-6
    out[mask] /= weight[mask]
    return out[:length]


def wsola(x: np.ndarray, rate: float) -> np.ndarray:
    """时间伸缩，音高不变（audio-tempo 那条工具用它）。"""
    if rate <= 0:
        return x
    frame = int(SR * 0.05)
    hop = frame // 4
    ana = max(1, int(round(hop / rate)))
    tol = int(SR * 0.010)
    win = np.hanning(frame)
    out = np.zeros(int(len(x) * rate) + 2 * frame)
    wsum = np.zeros_like(out)
    pos, k, prev = 0, 0, None
    while pos + frame <= len(x):
        best = pos
        if prev is not None:
            score = -1e18
            for cand in range(max(0, pos - tol), min(len(x) - frame, pos + tol) + 1):
                value = float(np.dot(x[cand:cand + hop], prev))
                if value > score:
                    score, best = value, cand
        seg = x[best:best + frame] * win
        out[k * hop:k * hop + frame] += seg
        wsum[k * hop:k * hop + frame] += win
        prev = x[best + hop:best + hop * 2]
        pos += ana
        k += 1
    mask = wsum > 1e-6
    out[mask] /= wsum[mask]
    return out[:int(len(x) * rate)]


def pitch_shift(x: np.ndarray, semitones: float) -> np.ndarray:
    """整段变调不变速（audio-pitch）。逐段估基频，按局部周期做 PSOLA。"""
    if abs(semitones) < 1e-3:
        return x
    ratio = 2 ** (semitones / 12.0)
    frame = int(SR * 0.04)
    out = np.zeros(len(x) + 4 * frame)
    weight = np.zeros_like(out)
    pos_in = 0.0
    pos_out = 0.0
    while pos_in + frame < len(x):
        center = int(pos_in) + frame // 2
        seg = x[max(0, center - frame // 2):center + frame // 2]
        f0 = float(np.median([v for v in f0_track(seg) if v > 0])) if len(seg) else 0.0
        period = SR / f0 if f0 > 0 else float(frame // 2)
        period = min(max(period, SR / 500), SR / 80)
        span = int(period)
        window = np.hanning(max(8, 2 * span))
        start_in = max(0, center - span)
        piece = x[start_in:start_in + len(window)]
        if len(piece) < len(window):
            piece = np.pad(piece, (0, len(window) - len(piece)))
        at = int(pos_out - span)
        lo = max(0, at)
        if lo + len(window) > len(out):
            break
        out[lo:lo + len(window)] += piece * window
        weight[lo:lo + len(window)] += window
        pos_in += period
        pos_out += period / ratio
    mask = weight > 1e-6
    out[mask] /= weight[mask]
    end = int(pos_out) + frame
    return out[:min(len(out), max(end, frame))]


def resample(x: np.ndarray, factor: float) -> np.ndarray:
    if abs(factor - 1.0) < 1e-4:
        return x
    n = max(1, int(len(x) / factor))
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x)


def shift_pitch(x: np.ndarray, semitones: float) -> np.ndarray:
    """变调不变速：先按时长拉伸，再重采样回原长。音高准，共振峰跟着走（2 个半音内可接受）。"""
    if abs(semitones) < 1e-3:
        return x
    ratio = 2 ** (semitones / 12.0)
    return resample(wsola(x, ratio), ratio)


def vibrato_curve(note_seconds: float, depth: float) -> float:
    return depth


def render(voice: str, legato: bool, vib: float, consonant_keep: bool = True) -> np.ndarray:
    os.makedirs(CACHE, exist_ok=True)
    total_ms = int(sum(dur for _, dur, _ in MELODY) * BEAT_MS + 600)
    track = np.zeros(int(SR * total_ms / 1000))
    at = 0.0
    for note, dur, lyric in MELODY:
        safe = "".join(ch if ch.isalnum() else "_" for ch in lyric)
        raw = os.path.join(CACHE, f"{voice}-{safe}.mp3")
        if not os.path.exists(raw):
            asyncio.run(tts(lyric, raw, voice))
        src = decode(raw)
        f0s = f0_track(src)
        voiced = np.median([v for v in f0s if v > 0]) if np.any(f0s > 0) else 200.0
        onset = voiced_start(src, f0s)
        d_target = dur * BEAT_MS / 1000.0
        target_len = int(SR * d_target)

        head = src[:onset] if consonant_keep else np.zeros(0)
        body = src[onset:] if onset < len(src) else src
        body_len = max(1, target_len - len(head))
        sung = psola(body, float(voiced), hz(note), body_len, vib)
        if len(head) and len(sung):
            join = min(len(head), len(sung))
            sung[:join] += head[:join] * np.linspace(1, 0, join)
        note_audio = sung if len(head) == 0 else sung

        start = int(SR * at / 1000)
        end = min(len(track), start + len(note_audio))
        if legato:
            fade = int(SR * 0.03)
            if fade and end - start > fade:
                note_audio = note_audio.copy()
                note_audio[:fade] *= np.linspace(0, 1, fade)
                note_audio[-fade:] *= np.linspace(1, 0, fade)
        track[start:end] += note_audio[:end - start]
        at += dur * BEAT_MS - (60 if legato else 0)

    return track


def check(path: str) -> int:
    data = decode(path)
    at, bad = 0.0, 0
    for note, dur, lyric in MELODY:
        start = int(SR * at / 1000)
        end = int(SR * (at + dur * BEAT_MS) / 1000)
        seg = data[start:min(end, len(data))]
        if len(seg) > SR // 12:
            middle = len(seg) // 4
            seg = seg[middle:len(seg) - middle]
        if len(seg) < SR // 25:
            print(f"?? {lyric} 太短")
            at += dur * BEAT_MS
            continue
        f0s = f0_track(seg)
        voiced = [v for v in f0s if v > 0]
        measured = float(np.median(voiced)) if voiced else 0.0
        delta = 1200 * math.log2(measured / hz(note)) if measured > 0 else 999.0
        flag = "ok " if abs(delta) < 60 else "OFF"
        if flag == "OFF":
            bad += 1
        print(f"{flag} {lyric} 目标 {hz(note):7.1f} 实测 {measured:7.1f} 偏差 {delta:+7.1f} 音分")
        at += dur * BEAT_MS
    return bad


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--voice", default="zh-CN-XiaoxiaoNeural")
    parser.add_argument("--out", default="/tmp/sing2.wav")
    parser.add_argument("--legato", action="store_true")
    parser.add_argument("--vibrato", type=float, default=0.0)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    if args.check:
        sys.exit(0 if check(args.out) == 0 else 1)

    audio = render(args.voice, args.legato, args.vibrato)
    fade = int(SR * 0.02)
    audio[:fade] *= np.linspace(0, 1, fade)
    audio[-fade:] *= np.linspace(1, 0, fade)
    write_wav(args.out, audio)
    print(f"{args.out}: {len(audio) / SR:.2f}s")
