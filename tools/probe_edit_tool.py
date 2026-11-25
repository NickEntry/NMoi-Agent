#!/usr/bin/env python3
"""对着本机 litellm 的 `custom:test1/…` 验「改消息」那套提示词（D430）。

为什么要这个脚本：那一层最需要真模型验的**不是代码，是它会不会照着提示词用**——
参数里会不会冒出 `part`/`block`，改完之后会不会凭记忆复述，会不会自己去读报告。
这些只有真端点答得出来。

用法：
    python3 tools/probe_edit_tool.py            # 跑全部场景
    python3 tools/probe_edit_tool.py 3          # 只跑第 3 个

判据写在每个场景的 `check` 里，**断言而不是眼看**：退出码 0 = 全过。
不打印 key（这个脚本里根本没有 key：本机代理自己带）。
"""

import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8790/v1/chat/completions"
MODEL = "custom:test1/deepseek-ai/DeepSeek-V4.1-Flash-1017"

# 这一份和随便哪条会话里的形状一样：一轮两条消息（工具那条 + 正文那条）。
HISTORY = [
    {"role": "user", "content": "跑一下，然后告诉我多少秒"},
    {
        "role": "assistant",
        "content": "",
        "tool_calls": [
            {
                "id": "call_1",
                "type": "function",
                "function": {
                    "name": "System-Shell",
                    "arguments": "{\"command\":\"sleep 10 && echo ser-1\"}",
                },
            }
        ],
    },
    {"role": "tool", "tool_call_id": "call_1", "content": "ser-1"},
    {"role": "assistant", "content": "这次改成一条等到完再发下一条，串行执行：ser-1 用了 10.1 秒。"},
]

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "find_messages",
            "description": "按关键词或正文开头找消息，返回消息 id 与开头几十字。"
                           "改消息之前先用它拿 id。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "关键词，或正文的开头几个字"},
                    "mode": {
                        "type": "string",
                        "enum": ["keyword", "body_prefix"],
                        "description": "keyword=全文包含；body_prefix=正文开头逐字匹配",
                    },
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_outputs",
            "description": "读 /chat/outputs/ 下的一份报告（改消息的前后对比都在那里）。"
                           "核对改过什么时用它，不要凭记忆复述。",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "报告路径"}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "edit_message",
            "description": "改一条已经存在的消息：**整条重写**，不是打补丁。"
                           "没给的块不动；工具清单是整份替换（可以增删改名）。"
                           "note 必填，一句话写清改了什么——它会留在上下文里，"
                           "详细的前后对比在 /chat/outputs/，需要核对时用 read_outputs 去读。",
            "parameters": {
                "type": "object",
                "properties": {
                    "message_id": {"type": "string", "description": "find_messages 给的 id"},
                    "text": {"type": "string", "description": "整段新正文（可省）"},
                    "thinking": {"type": "string", "description": "整段新思考（可省；空串=删）"},
                    "tools": {
                        "type": "array",
                        "description": "工具清单：**整份替换**，可以多一条少一条改名。"
                                       "不给 = 不动工具块。",
                        "items": {
                            "type": "object",
                            "properties": {
                                "name": {"type": "string"},
                                "arguments": {"type": "string", "description": "JSON 对象原文"},
                                "output": {"type": "string"},
                            },
                            "required": ["name", "arguments"],
                        },
                    },
                    "note": {
                        "type": "string",
                        "description": "一句话：我改了什么，不超过 60 字",
                    },
                },
                "required": ["message_id", "note"],
            },
        },
    },
]

SYSTEM = (
    "你是一个改消息的助手。要改哪一条，先 find_messages 拿 id，再 edit_message。"
    "edit_message 的参数里只写整条的新内容，不要写任何块标记、part、block 之类的东西。"
    "改完只说一句你改了什么，不要复述全文。"
)


def call(messages, tools=TOOLS):
    body = json.dumps(
        {
            "model": MODEL,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": 0.2,
            "max_tokens": 800,
        }
    ).encode()
    request = urllib.request.Request(
        BASE,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.load(response)
    message = payload["choices"][0]["message"]
    return message, payload.get("usage") or {}


def calls_of(message):
    return message.get("tool_calls") or []


def args_of(call):
    return json.loads(call["function"]["arguments"])


# ---------------------------------------------------------------------------
# 场景
# ---------------------------------------------------------------------------


def scene_1():
    """要它改上一条的正文：必须先找 id，参数里不许出现块标记。"""
    message, usage = call(
        [{"role": "system", "content": SYSTEM}]
        + HISTORY
        + [{"role": "user", "content": "把上一条最后那句话改一下措辞：把「这次改成」改成「这次改成了」，别的都不动"}]
    )
    first = calls_of(message)
    assert first, f"没调工具：{message}"
    assert first[0]["function"]["name"] == "find_messages", f"第一步该找消息：{first[0]}"
    query = args_of(first[0])["query"]
    assert query.strip(), "query 是空的"

    # 第二步：把我们包好的 id 给它，看它会不会照提示词写参数。
    found = [
        {
            "id": "01a1a42b-bf81-7709-9f40-8a37ea4ba121:97",
            "role": "assistant",
            "head": "这次改成一条等到完再发下一条，串行执行…",
        }
    ]
    message2, usage2 = call(
        [{"role": "system", "content": SYSTEM}]
        + HISTORY
        + [
            {"role": "user", "content": "把上一条最后那句话改一下措辞：把「这次改成」改成「这次改成了」，别的都不动"},
            {"role": "assistant", "content": "", "tool_calls": first},
            {
                "role": "tool",
                "tool_call_id": first[0]["id"],
                "content": json.dumps({"found": found}, ensure_ascii=False),
            },
        ]
    )
    second = calls_of(message2)
    assert second, f"拿到 id 之后没调 edit_message：{message2}"
    name = second[0]["function"]["name"]
    assert name == "edit_message", f"该改消息了，它调的是 {name}"
    raw = second[0]["function"]["arguments"]
    # 判据：参数里不许出现块标记。
    for banned in ("part", "block", "index", "<nmoi:"):
        assert banned not in raw, f"参数里出现了 {banned}：{raw}"
    args = args_of(second[0])
    assert args["message_id"] == found[0]["id"], f"message_id 不对：{args}"
    assert "这次改成了" in json.dumps(args, ensure_ascii=False), (
        f"没把新措辞写进去：{args}"
    )
    assert args.get("note"), "没写 note"
    assert len(args["note"]) <= 80, f"note 太长（{len(args['note'])} 字）：{args['note']}"
    print("  第一次调用：", json.dumps(first[0]["function"], ensure_ascii=False)[:160])
    print("  第二次调用：", json.dumps(second[0]["function"], ensure_ascii=False)[:220])
    print("  note：", args["note"])
    return usage, usage2


def scene_2():
    """改完之后问它改了什么：它该去读报告，不该凭记忆编。"""
    message, usage = call(
        [{"role": "system", "content": SYSTEM}]
        + [
            {
                "role": "user",
                "content": "把上一条最后那句话的措辞改一下：改成→改成了",
            },
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                        "id": "c1",
                        "type": "function",
                        "function": {
                            "name": "edit_message",
                            "arguments": json.dumps(
                                {
                                    "message_id": "01a1a42b:97",
                                    "text": "这次改成了一条等到完再发下一条，串行执行：ser-1 用了 10.1 秒。",
                                    "note": "改了一句措辞（改成→改成了）",
                                },
                                ensure_ascii=False,
                            ),
                        },
                    }
                ],
            },
            {
                "role": "tool",
                "tool_call_id": "c1",
                "content": "改了一句措辞（改成→改成了）\n详细结果：/chat/outputs/edit-97-1.md",
            },
            {"role": "user", "content": "你刚才到底改了哪几句？"},
        ]
    )
    names = [c["function"]["name"] for c in calls_of(message)]
    text = (message.get("content") or "").strip()
    # 两种都对：去读报告，或者明确说"报告里有、我没细看"。绝不能凭空复述细节。
    if names:
        assert names[0] == "read_outputs", f"该去读报告，它调的是 {names[0]}"
        print("  去读了报告：", names[0])
    else:
        banned = ("我把整段重写成了",)
        for phrase in banned:
            assert phrase not in text, f"它在凭记忆复述：{text}"
        print("  没有复述，回答：", text[:120])
    return usage, {}


def run_until_edit(messages, rounds=4):
    """一轮一轮喂回去，直到它调 `edit_message`（或轮数用完）。

    模型常常"找了再找"（第二次还给关键词），那不是错——它只是在确认。
    这里只关心最后那次改动长什么样。
    """
    seen = []
    for _ in range(rounds):
        message, usage = call(messages)
        calls = calls_of(message)
        if not calls:
            return None, message, seen, usage
        messages = messages + [{"role": "assistant", "content": "", "tool_calls": calls}]
        for call_item in calls:
            name = call_item["function"]["name"]
            seen.append((name, args_of(call_item)))
            if name == "edit_message":
                return call_item, message, seen, usage
            messages = messages + [
                {
                    "role": "tool",
                    "tool_call_id": call_item["id"],
                    "content": json.dumps(
                        {
                            "found": [
                                {"id": "s:2", "role": "assistant", "head": "（工具那条）"},
                                {"id": "s:3", "role": "assistant", "head": "（正文那条）"},
                            ]
                        },
                        ensure_ascii=False,
                    ),
                }
            ]
    return None, message, seen, {}


def scene_3():
    """让它给这一轮加一条工具调用：工具清单整份给出去，两条都在。"""
    messages = (
        [{"role": "system", "content": SYSTEM}]
        + HISTORY
        + [
            {
                "role": "user",
                "content": "这一轮再加一条工具调用：用 Read-File 读一下 /tmp/a.txt，"
                "结果写「内容 a」，原来那条别动",
            }
        ]
    )
    edit_call, message, seen, _usage = run_until_edit(messages)
    print("  走过的工具：", [name for name, _ in seen])
    assert edit_call, f"没走到 edit_message：{message}"
    args = args_of(edit_call)
    tools = args.get("tools")
    assert tools, f"没给 tools：{args}"
    assert len(tools) == 2, f"该是两条（原来那条 + 新加那条）：{tools}"
    names = [tool["name"] for tool in tools]
    assert "Read-File" in names, f"新那条不在：{names}"
    assert "System-Shell" in names, f"原来那条被丢了：{names}"
    for tool in tools:
        json.loads(tool["arguments"])
    print("  工具清单：", json.dumps(tools, ensure_ascii=False)[:220])
    return None


SCENES = {1: scene_1, 2: scene_2, 3: scene_3}


def main(argv):
    picks = [int(argv[1])] if len(argv) > 1 else sorted(SCENES)
    failed = 0
    for number in picks:
        print(f"== 场景 {number}: {SCENES[number].__doc__.strip().splitlines()[0]}")
        try:
            SCENES[number]()
            print("   ok")
        except Exception as error:  # noqa: BLE001 - 这里就是要看见真话
            failed += 1
            print(f"   FAILED: {error}")
    print(f"== {len(picks) - failed}/{len(picks)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
