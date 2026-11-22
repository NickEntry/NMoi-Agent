#!/usr/bin/env python3
"""SwipeDeleteRow 的 Modifier 顺序守卫。

为什么要有这一条：`offset` 写在 `background` 后面时，位移只作用在行里的内容上，
底色留在原处——滑开之后红条被一整块不透明底色盖住，真机上是「能点，但是空白」。
这个错在代码里看不出来（两行都合法），只有真机上才看得见，所以用一条断言钉住。

用法：python3 scripts/check_swipe_row.py
退出码 0 = 顺序对；1 = 顺序反了或找不到那段。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "android/app/src/main/kotlin/com/nmoi/agent/ui/Controls.kt"

SOURCE = TARGET.read_text(encoding="utf-8")

# SwipeDeleteRow 函数体
start = SOURCE.find("internal fun SwipeDeleteRow(")
if start < 0:
    print("FAIL 找不到 SwipeDeleteRow")
    sys.exit(1)
body = SOURCE[start:]

# 内容行那一串 Modifier：从 .fillMaxWidth() 起，到 .pointerInput( 之前
anchor = body.find("Modifier\n                // **`offset` 必须在")
if anchor < 0:
    print("FAIL 找不到内容行的 Modifier 起点（注释被改过？）")
    sys.exit(1)
chain_end = body.find(".pointerInput(", anchor)
if chain_end < 0:
    print("FAIL 找不到内容行的 .pointerInput(")
    sys.exit(1)
chain = body[anchor:chain_end]

pos_offset = chain.find(".offset {")
pos_background = chain.find(".background(")

ok = True
if pos_offset < 0:
    print("FAIL 内容行没有 .offset {")
    ok = False
if pos_background < 0:
    print("FAIL 内容行没有 .background(")
    ok = False
if pos_offset >= 0 and pos_background >= 0 and pos_offset > pos_background:
    print("FAIL .background 在 .offset 前面 —— 滑开之后红条会被底色盖住（D376）")
    ok = False

# 顺带钉住：删除区不能再用 matchParentSize（D215 之后修过一次）
if "matchParentSize" in body:
    print("FAIL SwipeDeleteRow 里又出现了 matchParentSize")
    ok = False

# 删除区两样东西不能靠 graphicsLayer 里的 lambda 算 alpha（D376）。
# 只看代码行：注释里提到 `graphicsLayer{}` 是在讲为什么不用它。
code_lines = [
    line for line in body.splitlines() if not line.lstrip().startswith(("//", "*", "/*"))
]
code = "\n".join(code_lines)
if re.search(r"graphicsLayer\s*\{", code):
    print("FAIL SwipeDeleteRow 里又出现了 graphicsLayer{}")
    ok = False

if ok:
    print("OK offset 在 background 前面，没有 matchParentSize，没有 graphicsLayer")
sys.exit(0 if ok else 1)
