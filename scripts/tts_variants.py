#!/usr/bin/env python3
"""一次生成所有对比版本 + 音频工具的示例（给耳朵判）。

输出到 /tmp/variants/，再人工挑要发的拷到 /chat/outputs。
"""

import os
import subprocess
import sys

import numpy as np

import sing2

SR = 24000
OUT = "/tmp/variants"
os.makedirs(OUT, exist_ok=True)


def mp3(wav: str, name: str, filters: str = "") -> str:
    path = os.path.join(OUT, name + ".mp3")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", wav]
    if filters:
        cmd += ["-af", filters]
    cmd += ["-codec:a", "libmp3lame", "-b:a", "128k", path]
    subprocess.run(cmd, check=True)
    return path


def backing(total_len: int, beat_ms: int = 420) -> np.ndarray:
    """极简伴奏：每个音符一小段分解和弦（根音 + 五度 + 八度），带快起慢落包络。"""
    track = np.zeros(total_len)
    at = 0.0
    roots = [48, 48, 55, 55, 57, 57, 55, 53, 53, 52, 52, 50, 50, 48]
    for index, note in enumerate(sing2.MELODY):
        dur = note[1] * beat_ms / 1000.0
        root = roots[index]
        start = int(SR * at / 1000)
        span = int(SR * dur)
        t = np.arange(span) / SR
        tone = np.zeros(span)
        for step, offset in enumerate([0, 7, 12]):
            freq = sing2.hz(root + offset)
            env = np.exp(-3.2 * t) * (1 - np.exp(-60 * t))
            tone += 0.28 / (1 + step) * np.sin(2 * np.pi * freq * t) * env
        end = min(len(track), start + span)
        track[start:end] += tone[: end - start]
        at += dur
    return track


def normalise(x: np.ndarray, peak: float = 0.9) -> np.ndarray:
    top = float(np.max(np.abs(x))) or 1.0
    return x / top * peak


def main() -> None:
    # A：v1（重采样改音高）——上一轮给你的那版
    subprocess.run(
        ["python3", "sing.py", os.path.join(OUT, "a-resample.wav")],
        check=True,
        cwd=os.path.dirname(os.path.abspath(__file__)),
    )
    mp3(os.path.join(OUT, "a-resample.wav"), "a-resample")

    # B：PSOLA（保共振峰），不连奏、不颤音
    b = normalise(sing2.render("zh-CN-XiaoxiaoNeural", False, 0.0))
    sing2.write_wav(os.path.join(OUT, "b-psola.wav"), b)
    mp3(os.path.join(OUT, "b-psola.wav"), "b-psola")

    # C：B + 连奏（30ms 交叠）+ 长音颤音
    c = normalise(sing2.render("zh-CN-XiaoxiaoNeural", True, 0.25))
    sing2.write_wav(os.path.join(OUT, "c-legato.wav"), c)
    mp3(os.path.join(OUT, "c-legato.wav"), "c-legato")

    # D：C + 一点空间（混响 + 提亮 + 去闷）
    mp3(
        os.path.join(OUT, "c-legato.wav"),
        "d-space",
        "highpass=f=110,aecho=0.7:0.55:55|130:0.28|0.18,equalizer=f=3200:t=q:w=1.2:g=2.5,alimiter=limit=0.95",
    )

    # E：换个男声（连奏 + 颤音）
    e = normalise(sing2.render("zh-CN-YunxiNeural", True, 0.25))
    sing2.write_wav(os.path.join(OUT, "e-male.wav"), e)
    mp3(os.path.join(OUT, "e-male.wav"), "e-male")

    # F：C + 伴奏（演示 audio-mix 这条工具）
    pad = backing(len(c))
    mixed = normalise(c * 0.85 + pad * 0.55)
    sing2.write_wav(os.path.join(OUT, "f-mixed.wav"), mixed)
    mp3(os.path.join(OUT, "f-mixed.wav"), "f-mixed")

    # G：audio-pitch 工具 +2 半音（PSOLA，共振峰不动）
    up = sing2.psola(c, 262.0, 262.0 * 2 ** (2 / 12), len(c), 0.0)
    sing2.write_wav(os.path.join(OUT, "g-pitch-up2.wav"), normalise(up))
    mp3(os.path.join(OUT, "g-pitch-up2.wav"), "g-pitch-up2")

    # H：audio-tempo 工具 0.8 倍速（音高不动）
    slow = sing2.wsola(c, 1 / 0.8)
    sing2.write_wav(os.path.join(OUT, "h-tempo80.wav"), normalise(slow))
    mp3(os.path.join(OUT, "h-tempo80.wav"), "h-tempo80")

    for name in sorted(os.listdir(OUT)):
        size = os.path.getsize(os.path.join(OUT, name))
        print(f"{name:24s} {size/1024:7.1f} KB")


if __name__ == "__main__":
    main()
