#!/usr/bin/env python3
"""设置页各子页的页边距要与设置首页对齐（24dp，D412）。

为什么要有这条：页边距散成 12 / 16 / 24 三套时，翻页会看到内容左右跳动；
而且这种不齐**编译能过、不崩**，只有来回翻才看得出来。

判据（只查"页边距"形状，不碰卡片内部的行内边距）：
- `fillMaxWidth().padding(horizontal = 12.dp|16.dp)` —— 卡片本身的页边距
- `LazyColumn(... .padding(horizontal = 12.dp|16.dp))` —— 列表页边距

`padding(horizontal = 12.dp, vertical = N)` 这种是行内边距（卡片里的行、面板里的行），
不在这一条的范围内——它跟着卡片走，卡片缩进了它自然就缩进了。
"""
import glob
import re
import sys

BAD = re.compile(r"(fillMaxWidth\(\)\.padding\(horizontal = (12|16)\.dp\)"
                 r"|LazyColumn\([^)]*\.padding\(horizontal = (12|16)\.dp\))")

# 面板（底部浮层）里的那一层不是"页边距"：它长在浮层里，宽度本来就不是整屏。
# 判据：同一个函数体里出现过 `SheetFrame(`。
SHEET = re.compile(r"SheetFrame\(")


def main() -> int:
    bad = []
    for path in sorted(glob.glob("android/app/src/main/kotlin/com/nmoi/agent/ui/*.kt")):
        src = open(path).read()
        for m in BAD.finditer(src):
            line = src[: m.start()].count("\n") + 1
            # 往前找最近的函数声明，看它是不是一个浮层面板
            head = src[: m.start()]
            fn_at = max(head.rfind("\n@Composable"), head.rfind("\ninternal fun"), head.rfind("\nprivate fun"))
            fn_body = src[fn_at: m.start()] if fn_at >= 0 else ""
            if SHEET.search(fn_body):
                continue
            bad.append(f"{path}:{line} {m.group(0)}")
    if bad:
        print("这些页边距不是 24dp（与设置首页不齐）：")
        for row in bad:
            print("  " + row)
        return 1
    print("页边距：全部对齐 24dp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
