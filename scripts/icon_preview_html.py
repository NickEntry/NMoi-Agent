#!/usr/bin/env python3
"""把图标包渲染成**一个 HTML 文件**，离线可开、无外部依赖（PNG 全内联成 base64）。

为什么是单文件：手机上评审最省事的路径是"发一个文件 → 点开"。任何外部引用都会变成
"图裂了但我不知道为什么"。代价是文件大一点（当前约 1.2 MB），换来的是哪里都能开。

用法：
    python3 scripts/icon_preview_html.py                        # 读 data/icon-packs/original
    python3 scripts/icon_preview_html.py --pack <dir> --out /tmp/icons.html

能看什么：
  - 深色 / 浅色两套（右上角切换，纯 CSS，没有脚本依赖）；
  - 三档实际尺寸（24 / 22 / 18）—— 24 好看不代表 18 能认出来；
  - tint=auto 的图标在深色主题下由 CSS 上色，等价于运行时的 tint 行为；
  - 品牌图标垫了 surface-variant 底：彩色标里有白色部件的，不垫底在浅色主题下会白掉；
  - 末尾是首字母方块的退化演示（含 FNV-1a 取色）。
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import pathlib
import re
import sys

DEFAULT_PACK = pathlib.Path(__file__).resolve().parent.parent / "data" / "icon-packs" / "original"

MONOGRAM_PALETTE_HUE_STEP = 15
MONOGRAM_SAT = 0.30
MONOGRAM_LIGHT = 0.36

SAMPLE_NAMES = [
    "gpt-4o",
    "anthropic/claude-sonnet-4.5",
    "deepseek-chat",
    "[分组]Qwen3-Max",
    "gemini-2.5-pro",
    "moonshot/kimi-k2",
    "豆包-seed",
    "my-local-model",
]


def fnv1a(text: str) -> int:
    value = 0x811C9DC5
    for byte in text.encode("utf-8"):
        value ^= byte
        value = (value * 0x01000193) & 0xFFFFFFFF
    return value


def monogram(name: str) -> tuple[str, str]:
    """与 docs/ui/icons/README.md §4 的规则一致：去 [分组]、取最后一段、首个字母数字。"""
    tail = name.split("/")[-1]
    tail = re.sub(r"^\[[^\]]*\]", "", tail).strip()
    letter = next((ch for ch in tail if ch.isalnum()), "?")
    hue = (fnv1a(tail) % 24) * MONOGRAM_PALETTE_HUE_STEP
    return letter.upper(), f"hsl({hue} {MONOGRAM_SAT:.2f} {MONOGRAM_LIGHT:.2f})"


def b64(path: pathlib.Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


CSS = """
:root { color-scheme: dark; }
body { margin:0; font: 14px/1.5 "Noto Sans SC", system-ui, sans-serif; }
body[data-theme="dark"]  { --bg:#14161a; --fg:#e8e9ea; --dim:#8b9096; --line:#262a30;
                           --plate:#1e2229; --tint-filter:none; }
body[data-theme="light"] { --bg:#f7f7f5; --fg:#1b1d21; --dim:#6b7075; --line:#e2e2de;
                           --plate:#ecece8; --tint-filter:invert(1); }
img.tint { filter: var(--tint-filter); }
body { background:var(--bg); color:var(--fg); }
header { position:sticky; top:0; background:var(--bg); border-bottom:1px solid var(--line);
         padding:10px 16px; display:flex; gap:12px; align-items:center; flex-wrap:wrap; }
header h1 { font-size:15px; margin:0; font-weight:600; }
header .meta { color:var(--dim); font-size:12px; }
button { background:transparent; border:1px solid var(--line); color:var(--fg);
         border-radius:999px; padding:5px 12px; font-size:12px; cursor:pointer; }
main { padding:16px; }
section { margin-bottom:28px; }
h2 { font-size:13px; color:var(--dim); font-weight:500; margin:0 0 10px; letter-spacing:.04em; }
.grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(112px,1fr)); gap:10px; }
.cell { border:1px solid var(--line); border-radius:10px; padding:10px 6px 8px; text-align:center; }
.cell .row { display:flex; align-items:flex-end; justify-content:center; gap:9px; height:52px; }
.cell img { display:block; }
.cell .cap { margin-top:6px; font-size:10px; color:var(--dim); word-break:break-all; }
.plate { background:var(--plate); border-radius:50%; display:inline-flex; align-items:center;
         justify-content:center; width:44px; height:44px; }
.mono { display:grid; grid-template-columns:repeat(auto-fill,minmax(112px,1fr)); gap:10px; }
.tile { width:40px; height:40px; border-radius:12px; display:flex; align-items:center;
        justify-content:center; color:#fff; font-size:22px; font-weight:500; margin:0 auto; }
.note { color:var(--dim); font-size:12px; margin:0 0 12px; }
"""

SCRIPT = """
const body = document.body;
document.getElementById('theme').addEventListener('click', () => {
  body.dataset.theme = body.dataset.theme === 'dark' ? 'light' : 'dark';
});
"""


def cell(entry: dict, pack: pathlib.Path, css_h: int) -> str:
    path = pack / f"{entry['file']}@2x.png"
    if not path.exists():
        path = pack / f"{entry['file']}.png"
    data = b64(path)
    tint = entry.get("tint") == "auto"
    # tint=auto：PNG 是白色剪影，靠 CSS filter 上色（等价于运行时 tint）
    # tint=auto 的 PNG 是白色剪影：深色主题直接用，浅色主题靠 CSS 翻成黑色。
    # 这正是运行时"白色剪影 + tint 上色"的等价物，所以滤镜跟着主题走，不能写死。
    cls = ' class="tint"' if tint else ""
    img = f'<img src="data:image/png;base64,{data}" style="width:{css_h}px;height:{css_h}px"{cls} alt="">'
    # 只有 manifest 明确标了 backing 的才垫底（含白色部件的品牌标）；
    # 一律垫底会在每个图标外围画出一圈"多余的描边"，深浅主题下都难看。
    if entry.get("backing") == "surface":
        img = f'<span class="plate">{img}</span>'
    label = html.escape(entry["key"]) + (" ▫" if tint else "")
    return f'<div class="cell"><div class="row">{img}</div><div class="cap">{label}</div></div>'


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=pathlib.Path, default=DEFAULT_PACK)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("icon-preview.html"))
    args = parser.parse_args(argv)

    manifest_path = args.pack / "manifest.json"
    if not manifest_path.exists():
        print(f"{manifest_path} 不存在：先跑 scripts/build_icon_pack.py", file=sys.stderr)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    groups = {"action": [], "provider": [], "model": []}
    for entry in manifest["entries"]:
        groups.setdefault(entry["kind"], []).append(entry)

    blocks = []
    for kind, title in (
        ("action", "动作图标（▫ = 运行时 tint；三档尺寸 24 / 22 / 18）"),
        ("provider", "供应商图标（40dp，垫 surface-variant 底）"),
        ("model", "模型图标（40dp）"),
    ):
        items = groups.get(kind, [])
        if not items:
            continue
        cells = "".join(cell(entry, args.pack, 40 if kind != "action" else 24) for entry in items)
        blocks.append(f"<section><h2>{html.escape(title)}</h2><div class='grid'>{cells}</div></section>")

    # 动作图标再单独给一遍 24/22/18 三档，方便比"小尺寸还认不认得出"
    actions = groups.get("action", [])
    if actions:
        rows = []
        for entry in actions:
            imgs = "".join(cell(entry, args.pack, size) for size in (24, 22, 18))
            rows.append(f'<div class="cell" style="grid-column:span 1">{imgs}</div>')
        blocks.append(
            "<section><h2>小尺寸对比（同一图标的 24 / 22 / 18）</h2>"
            f"<div class='grid'>{''.join(rows)}</div></section>"
        )

    tiles = "".join(
        f'<div><div class="tile" style="background:{color}">{html.escape(letter)}</div>'
        f'<div class="cap" style="text-align:center;margin-top:6px;font-size:10px;color:var(--dim)">'
        f"{html.escape(name)}</div></div>"
        for name, (letter, color) in ((n, monogram(n)) for n in SAMPLE_NAMES)
    )
    blocks.append(
        "<section><h2>首字母退化（没有任何品牌图标命中时）</h2>"
        "<p class='note'>底色 = FNV-1a(归一化名) mod 24 × 15°，s 0.30 / l 0.36；CJK 原样，拉丁转大写。</p>"
        f"<div class='mono'>{tiles}</div></section>"
    )

    doc = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NMoi-Agent 图标包预览（{html.escape(manifest['pack_id'])} {html.escape(manifest['version'])}）</title>
<style>{CSS}</style></head>
<body data-theme="dark">
<header>
  <h1>图标包预览</h1>
  <span class="meta">{html.escape(manifest['pack_id'])} · {html.escape(manifest['version'])} ·
    {len(manifest['entries'])} 个条目 · 规则 {len(manifest.get('defaults', {}).get('rules', []))} 条</span>
  <button id="theme">切换深色 / 浅色</button>
</header>
<main>{''.join(blocks)}</main>
<script>{SCRIPT}</script>
</body></html>
"""

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(doc, encoding="utf-8")
    size = args.out.stat().st_size
    print(f"wrote {args.out}  ({size/1024:.0f} KB, 内联 {sum(len(v) for v in groups.values())} 个条目)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
