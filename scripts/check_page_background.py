#!/usr/bin/env python3
"""每一页都必须自己铺底色（D408）。

为什么要有这条：页面根节点只写一个裸 `Column` 时，整页会透出底下那一层，
表现是"背景透明了"。这种错编译能过、不崩，只有真机看得见——VoiceScreen 就踩过。

判据：`internal fun XxxScreen(` 的函数体里要出现 `fillMaxSize()`，并且同时出现
`Surface(modifier = Modifier.fillMaxSize()` 或 `MaterialTheme.colorScheme.background`。
"""
import glob
import re
import sys

# 面板 / 子视图，不是整页：它们长在别人的页面里，底色由宿主给。
SKIP = {"FilePreviewScreen", "DialogFilesSheet", "TerminalKeysSheet"}


def bodies(src):
    """按行找 `internal fun XxxScreen(`，再按花括号配对取函数体。

    不用一个大正则：参数列表里会出现 `() -> Unit` 这种括号，`[^)]*` 匹配不了。
    """
    lines = src.split("\n")
    for idx, line in enumerate(lines):
        m = re.search(r"internal fun ([A-Za-z0-9_]+Screen)\(", line)
        if not m:
            continue
        name = m.group(1)
        # 从这一行开始，找第一个 '{' 之后再配对
        start = None
        depth = 0
        collected = []
        for k in range(idx, len(lines)):
            text = lines[k]
            for ch in text:
                if start is None:
                    if ch == "{":
                        start = True
                        depth = 1
                else:
                    if ch == "{":
                        depth += 1
                    elif ch == "}":
                        depth -= 1
                        if depth == 0:
                            collected.append(text)
                            yield name, idx + 1, "\n".join(collected)
                            start = "done"
                            break
            if start == "done":
                break
            if start:
                collected.append(text)


def main() -> int:
    bad = []
    for path in glob.glob("android/app/src/main/kotlin/com/nmoi/agent/ui/**/*.kt", recursive=True):
        src = open(path).read()
        for name, line, body in bodies(src):
            if name in SKIP:
                continue
            if "fillMaxSize()" not in body:
                continue
            has = (
                "Surface(modifier = Modifier.fillMaxSize()" in body
                or "MaterialTheme.colorScheme.background" in body
            )
            if not has:
                bad.append(f"{path}:{line} {name}")
    if bad:
        print("这些页面没有自己铺底色（整页会透出底下那一层）：")
        for row in bad:
            print("  " + row)
        return 1
    print("页面底色：全部都有")
    return 0


if __name__ == "__main__":
    sys.exit(main())
