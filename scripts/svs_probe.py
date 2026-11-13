#!/usr/bin/env python3
"""探一个 DiffSinger ONNX 声库：输入输出、词表上限、能不能出 mel。

用法：python3 scripts/svs_probe.py <acoustic.onnx> [vocoder.onnx]

2026-11-13 对着 `LogiAI10/diffsinger-mobile-onnx`（HF，172 MB，MIT）跑过一次：
  txt_tokens int64[1,N] / pitch_midi int64[1,N] / midi_dur float32[1,N] / is_slur int64[1,N]
  → decoder_inp [1,T,256] + mel_out [1,T,80]；词表上限 62（63 个 token）；
  hifigan_vocoder.onnx：mel[1,T,80] + f0[1,T] → wav[1,S]
**没有词表**（没有 dsconfig / phonemes.txt），token id 到音素的对应关系未知——
这是接歌唱歌引擎那一格真正的拦路虎，不是模型跑不起来。
"""

import sys

import numpy as np
import onnxruntime as ort


def inputs(path):
    options = ort.SessionOptions()
    options.log_severity_level = 3
    session = ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])
    print([(i.name, i.shape, i.type) for i in session.get_inputs()])
    print([(o.name, o.shape, o.type) for o in session.get_outputs()])
    return session


def vocab_ceiling(session, high: int = 512) -> int:
    def ok(index: int) -> bool:
        try:
            feed = {
                "txt_tokens": np.array([[index]], dtype=np.int64),
                "pitch_midi": np.array([[60]], dtype=np.int64),
                "midi_dur": np.array([[20.0]], dtype=np.float32),
                "is_slur": np.array([[0]], dtype=np.int64),
            }
            session.run(None, feed)
            return True
        except Exception:
            return False

    low = 0
    while low < high:
        mid = (low + high + 1) // 2
        if ok(mid):
            low = mid
        else:
            high = mid - 1
    return low


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    session = inputs(sys.argv[1])
    print("可用最大 token id:", vocab_ceiling(session))
    if len(sys.argv) > 2:
        inputs(sys.argv[2])
