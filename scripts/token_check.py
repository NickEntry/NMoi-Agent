#!/usr/bin/env python3
"""token 估算对账（D91）：把同一段文本发给真端点，比对 usage.prompt_tokens。

用法：
    python3 scripts/token_check.py --base URL --model M [--sizes 1000,10000]

做三件小事：
1. 造三段固定文本（纯 ASCII / 纯中文 / 中英混排），长度按 --sizes 给；
2. 调本地端点，读 `usage.prompt_tokens`；
3. 用 `examples/token_estimate`（跟运行时同一份实现）算估算（有 `bpe` feature 时是 L1），打印误差。

为什么不用 Python 重写一遍估算：两份实现迟早分叉，那样对出来的误差是假的。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ASCII_WORDS = (
    "the quick brown fox jumps over a lazy dog while parsing structured output "
    "and counting tokens across a very long conversation about database indexes"
)
CJK = "我们要把上下文窗口切成几段，先估算每段占多少，再决定从哪里断开。"
MIXED = "第 {i} 段：estimate_tokens 把 ASCII 按 4 字符折 1 个 token，CJK 按 1 字符 1 个。"
CODE = '{"index": {i}, "role": "assistant", "content": "the quick brown fox", "n": 42},' 


def build_texts(size: int) -> dict[str, str]:
    ascii_text = ""
    while len(ascii_text) < size:
        ascii_text += ASCII_WORDS + " "
    cjk_text = ""
    while len(cjk_text) < size:
        cjk_text += CJK
    mixed = ""
    i = 0
    while len(mixed) < size:
        mixed += MIXED.format(i=i) + "\n"
        i += 1
    code = ""
    i = 0
    while len(code) < size:
        code += CODE.replace("{i}", str(i)) + "\n"
        i += 1
    return {
        "ascii": ascii_text[:size],
        "cjk": cjk_text[:size],
        "mixed": mixed[:size],
        "code": code[:size],
    }


def ask(base: str, model: str, text: str, timeout: float) -> dict:
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": text}],
            "max_tokens": 1,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=body,
        headers={"content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    usage = payload.get("usage") or {}
    return {
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "model_echo": payload.get("model"),
    }


def estimates(
    workdir: Path, texts: dict[str, str], model: str
) -> dict[str, tuple[int, int]]:
    """返回 name -> (字符数, 估算 token)，估算来自运行时的同一份实现。"""
    paths = []
    for name, text in texts.items():
        path = workdir / f"{name}.txt"
        path.write_text(text, encoding="utf-8")
        paths.append(str(path))

    out = subprocess.run(
        [
            "cargo",
            "run",
            "-q",
            "--offline",
            "-p",
            "nmoi-core",
            "--example",
            "token_estimate",
            "--",
            "--model",
            model,
            *paths,
        ],
        cwd=Path(__file__).resolve().parent.parent,
        capture_output=True,
        text=True,
        check=True,
    )
    result: dict[str, tuple[int, int]] = {}
    for line in out.stdout.strip().splitlines():
        parts = line.split("\t")
        # 现在是 5 列（路径 / 字符 / 估算 / 级别 / 内容类）；多出来的列是给人看的。
        if len(parts) < 3:
            continue
        name = Path(parts[0]).stem
        result[name] = (int(parts[1]), int(parts[2]))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    # 端点和模型都不给默认值：仓库里不留真实地址与标识，谁跑谁传。
    parser.add_argument("--base", required=True, help="带 /v1 这类版本段的端点地址")
    parser.add_argument("--model", required=True, help="要打的那个模型名")
    parser.add_argument("--sizes", default="1000,10000")
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args()

    sizes = [int(item) for item in args.sizes.split(",") if item.strip()]
    total_rows = []
    for size in sizes:
        with tempfile.TemporaryDirectory() as tmp:
            texts = build_texts(size)
            est = estimates(Path(tmp), texts, args.model)
            for name, text in texts.items():
                chars, estimate = est[name]
                try:
                    usage = ask(args.base, args.model, text, args.timeout)
                except Exception as error:  # noqa: BLE001 - 对账脚本，失败就记下来
                    print(f"{size}\t{name}\t请求失败\t{error}", file=sys.stderr)
                    continue
                real = usage["prompt_tokens"]
                ratio = (real / estimate) if (real and estimate) else 0.0
                total_rows.append((size, name, chars, estimate, real, ratio))

    print(f"model = {args.model}")
    print(f"{'size':>7} {'kind':<6} {'chars':>7} {'est':>7} {'real':>7} {'real/est':>9}")
    for size, name, chars, estimate, real, ratio in total_rows:
        print(f"{size:>7} {name:<6} {chars:>7} {estimate:>7} {real!s:>7} {ratio:>9.3f}")

    # 按语言给一个建议因子：真实/估算的几何均值（三种长度一起看）
    by_kind: dict[str, list[float]] = {}
    for _size, name, _chars, _estimate, _real, ratio in total_rows:
        if ratio:
            by_kind.setdefault(name, []).append(ratio)
    print()
    for name, ratios in by_kind.items():
        mean = sum(ratios) / len(ratios)
        print(f"建议因子 {name}: {mean:.3f}（{len(ratios)} 次）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
