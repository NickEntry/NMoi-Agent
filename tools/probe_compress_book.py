#!/usr/bin/env python3
"""验压缩册那十二条提示词（D433）。

为什么单独验：压缩的判据不是"摘要读着顺"，是三条硬约束——
1. **必须档不能丢**：下一步 / 卡点 / 约束 / 硬事实；
2. **地址与端口两半一起抄**（261126 实测的缺口：模型会把绑定地址省掉）；
3. **分两档**：必须档之外才补次重要的，不是一张平铺清单。

只打本机 litellm 的 `custom:test1/…`，不经过 App。

用法：
    python3 tools/probe_compress_book.py          # 全部
    python3 tools/probe_compress_book.py 2        # 只跑第 2 个
"""

import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8790/v1/chat/completions"
MODEL = "custom:test1/deepseek-ai/DeepSeek-V4.1-Flash-1017"

# 压缩册十二条，原样从 json 里读出来（改 json 这个脚本自动跟着变）。
BOOK = "/workspace/自我认知V5-261126/压缩-261126.json"

# 一段"像真会话"的历史：有下一步、有卡点、有地址端口、有次重要的理由。
HISTORY = """[1] user: 帮我把 NMoi 的备份接口接上，跑一遍确认能用
[2] assistant → 工具 System-Shell({"command":"ss -ltnp | grep 8790"})
[3] tool: LISTEN 0 128 127.0.0.1:8790 users:(("python",pid=4182))
[4] assistant: 8790 在听，但只绑了 127.0.0.1。接下来改 crates/storage/src/backup.rs 的 STEP_PAGES。
[5] user: 改成 8192 试试
[6] assistant → 工具 edit_file({"path":"crates/storage/src/backup.rs"})
[7] tool: 改好了，STEP_PAGES = 8192
[8] assistant: 试跑了一次，246 MB 的库从 14.6 秒降到 2.3 秒。还差一件事：没验 integrity_check 那一步在新步长下是否还过。
[9] user: 先别管那个，把 /workspace/NMoi-Agent-debug.apk 的指纹核一遍
[10] assistant: 核了，19,812,845 字节，sha256 b2f531d9a458c696…，四样齐全。下一步要把它装到设备上验一次。
"""

# 必须档：这些丢了就是没记住。
MUST = [
    "8192",
    "crates/storage/src/backup.rs",
    "127.0.0.1",
    "8790",
    "/workspace/NMoi-Agent-debug.apk",
]
# 必须档里"下一步"这一条：可以用不同说法，但要指向装到设备上。
NEXT_STEP_HINTS = ["装到设备", "安装到设备", "装一次", "上设备", "真机"]

PROMPT_TAIL = """

材料在上面。按这一册的判据写摘要，写完给一句回执（留了什么、丢了什么）。"""


def load_book():
    with open(BOOK, encoding="utf-8") as handle:
        entries = json.load(handle)["data"]["entries"]
    entries.sort(key=lambda item: item["priority"])
    return "\n\n".join(entry["content"] for entry in entries)


def call(system, user, max_tokens=3000):
    body = json.dumps(
        {
            "model": MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.2,
            "max_tokens": max_tokens,
        }
    ).encode()
    request = urllib.request.Request(
        BASE,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        payload = json.load(response)
    choice = payload["choices"][0]
    text = choice["message"].get("content") or ""
    # 思考型模型把 max_tokens 一起吃掉了：`finish_reason=length` 时正文可能只剩十几字。
    # 那不是"没记住"，是预算不够——说出来，别把它算成提示词的问题。
    if choice.get("finish_reason") == "length" and len(text) < 200:
        raise RuntimeError(
            f"被截断了（finish_reason=length，正文只剩 {len(text)} 字）："
            f"这是 max_tokens 不够，不是提示词的问题"
        )
    return text


def check(summary):
    problems = []
    missing = [fact for fact in MUST if fact not in summary]
    if missing:
        problems.append(f"必须档丢了：{missing}")
    if not any(hint in summary for hint in NEXT_STEP_HINTS):
        problems.append(f"没写下一步（要找 {'/'.join(NEXT_STEP_HINTS)} 之一）")
    # 地址与端口必须成对出现，不能只剩端口。
    if "8790" in summary and "127.0.0.1" not in summary:
        problems.append("只剩端口、丢了绑定地址")
    # 长度**不是判据**。用户要的是"先保马上要用的、有余量才补次重要的"，
    # 没说要压到多短；而材料很密时摘要接近材料长度是正常的。
    # 这里只留一条 sanity 上界：超过两倍就是没压，把经过也抄进去了。
    if len(summary) > len(HISTORY) * 2:
        problems.append(f"没压下去（超过两倍）：{len(summary)} > {len(HISTORY)}×2")
    return problems


def scene_1():
    """正常压缩：必须档一条不丢，地址与端口成对，写了下一步。"""
    summary = call(load_book(), HISTORY + PROMPT_TAIL)
    problems = check(summary)
    print(f"  材料 {len(HISTORY)} 字 → 摘要 {len(summary)} 字")
    print("  摘要：", summary.replace("\n", " ")[:400])
    assert not problems, "；".join(problems)


def scene_2():
    """同一份材料跑第二次：判据得稳（不是碰巧这一次写对了）。"""
    summary = call(load_book(), HISTORY + PROMPT_TAIL)
    problems = check(summary)
    print("  第二次：", summary.replace("\n", " ")[:300])
    assert not problems, "；".join(problems)


def scene_3():
    """一段几乎没东西可压的历史：不该硬编，也不该丢硬事实。"""
    short = """[1] user: 这个项目的测试命令是什么
[2] assistant: cargo test -q -p nmoi-core --lib
[3] user: 记住了，谢谢
"""
    summary = call(load_book(), short + PROMPT_TAIL)
    print("  短材料 → ", summary.replace("\n", " ")[:240])
    assert "cargo test" in summary, f"唯一的硬事实丢了：{summary}"
    assert len(summary) < 800, f"短材料被写长了：{len(summary)} 字"


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
