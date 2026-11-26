#!/usr/bin/env python3
"""用 test1 端点验「压缩」那条提示词与判据（D432）。

压缩的核心判据不是"摘要读着顺"，而是三条硬事实：
1. **覆盖率**：原文里的硬事实（路径 / 命令 / 标识符 / 数字）在摘要里还能找到；
2. **不编**：摘要里不出现原文没有的路径/数字；
3. **短**：摘要明显短于原文（否则压了个寂寞）。

这个脚本只打 HTTP，不碰 App：本机 litellm 的 `custom:test1/…`。

用法：
    python3 tools/probe_compress.py            # 跑全部用例
    python3 tools/probe_compress.py 2          # 只跑第 2 个
退出码 0 = 全过。
"""

import json
import re
import sys
import urllib.request

BASE = "http://127.0.0.1:8790/v1/chat/completions"
MODEL = "custom:test1/deepseek-ai/DeepSeek-V4.1-Flash-1017"

# 一段"像真会话"的历史：带命令、路径、数字、结论。
HISTORY = [
    {"role": "user", "content": "把 /workspace/NMoi-Agent 里的 store.py 跑一下，看端口"},
    {
        "role": "assistant",
        "content": "先看一眼进程。",
        "tool_calls": [
            {
                "id": "c1",
                "type": "function",
                "function": {
                    "name": "System-Shell",
                    "arguments": json.dumps({"command": "ss -ltnp | grep 8790"}),
                },
            }
        ],
    },
    {"role": "tool", "tool_call_id": "c1", "content": "LISTEN 0 128 127.0.0.1:8790 users:((\"python\",pid=4182))"},
    {"role": "assistant", "content": "8790 上确实在听，pid 4182。接下来试一次真实请求。"},
    {
        "role": "assistant",
        "content": "试了 `/v1/chat/completions`，返回 200，用了 1.8 秒，模型是 custom:test1/deepseek-ai/DeepSeek-V4.1-Flash-1017。",
    },
    {"role": "user", "content": "那 /health 呢"},
    {"role": "assistant", "content": "/health 是 404。也就是说这个代理只实现了 chat completions。"},
]

# 原文里必须活下来的硬事实（路径 / 端口 / 编号 / 数字 / 关键词）。
MUST_KEEP = ["8790", "4182", "/v1/chat/completions", "404", "127.0.0.1"]
# 原文里没有的路径不能凭空出现。
MUST_NOT_APPEAR = ["/etc/passwd", "/var/log", "3000", "8080"]

PROMPT = """把下面这段会话压缩成一段摘要，供后续对话使用。

规则：
- 只保留"发生了什么、结论是什么、还差什么"，不要复述过程。
- 硬事实（路径、端口、编号、数字、命令）必须原样保留，一个都不能改。
- 不要编造原文没有的内容。
- 不超过 200 字。

会话：
"""


def call(system, user, max_tokens=600, temperature=0.2):
    body = json.dumps(
        {
            "model": MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
    ).encode()
    request = urllib.request.Request(
        BASE,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.load(response)
    return payload["choices"][0]["message"].get("content") or "", payload.get("usage") or {}


def transcript():
    lines = []
    for message in HISTORY:
        role = message["role"]
        if message.get("content"):
            lines.append(f"{role}: {message['content']}")
        for call_item in message.get("tool_calls") or []:
            function = call_item["function"]
            lines.append(f"{role} 调 {function['name']} {function['arguments']}")
    return "\n".join(lines)


def check_summary(summary, source):
    missing = [fact for fact in MUST_KEEP if fact not in summary]
    invented = [fact for fact in MUST_NOT_APPEAR if fact in summary]
    # 长度：摘要不该比原文长，也不该只剩一句话。
    src_chars = len(source)
    chars = len(summary)
    problems = []
    if missing:
        problems.append(f"硬事实丢了：{missing}")
    if invented:
        problems.append(f"凭空出现了原文没有的东西：{invented}")
    if chars > src_chars * 0.6:
        problems.append(f"没压下去：{chars} 字（原文 {src_chars} 字）")
    if chars < 40:
        problems.append(f"压得太狠，只剩 {chars} 字")
    return problems


def scene_1():
    """正常压缩：硬事实一个不丢，也不编。"""
    source = transcript()
    summary, usage = call(PROMPT, source)
    problems = check_summary(summary, source)
    print(f"  原文 {len(source)} 字 → 摘要 {len(summary)} 字")
    print("  摘要：", summary.replace("\n", " ")[:300])
    assert not problems, "；".join(problems)
    return usage


def scene_2():
    """压第二次（对摘要再压）：硬事实仍然要在。"""
    source = transcript()
    first, _ = call(PROMPT, source)
    second, usage = call(PROMPT, first)
    print(f"  第一次 {len(first)} 字 → 第二次 {len(second)} 字")
    print("  第二次：", second.replace("\n", " ")[:300])
    # 第二遍允许丢细节，但端口与路径这种"回头还要用"的不能丢。
    for fact in ["8790", "/v1/chat/completions"]:
        assert fact in second, f"二次压缩把 {fact} 丢了：{second}"
    return usage


def scene_3():
    """让它压一段**没有可压内容**的短会话：它应该原样/近乎原样，而不是硬编一段摘要。"""
    short = "user: 你好\nassistant: 你好，有什么要做的吗？"
    summary, usage = call(PROMPT, short)
    print("  短会话 → ", summary.replace("\n", " ")[:200])
    assert "你好" in summary or len(summary) <= len(short) + 20, (
        f"短会话被压成了别的东西：{summary}"
    )
    return usage


SCENES = {1: scene_1, 2: scene_2, 3: scene_3}


def main(argv):
    picks = [int(argv[1])] if len(argv) > 1 else sorted(SCENES)
    failed = 0
    for number in picks:
        print(f"== 场景 {number}: {SCENES[number].__doc__.strip().splitlines()[0]}")
        try:
            SCENES[number]()
            print("   ok")
        except Exception as error:  # noqa: BLE001
            failed += 1
            print(f"   FAILED: {error}")
    print(f"== {len(picks) - failed}/{len(picks)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
