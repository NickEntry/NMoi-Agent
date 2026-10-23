#!/usr/bin/env python3
"""把 docs/ui/icons 下的一组 SVG 排成一张预览图（需要 rsvg-convert）。

为什么要有这个：图标是 24px 画布上的东西，光看 XML 判断不出"22px 还认不认得出"。
改完图标跑一遍，看 **小尺寸那张**——24px 好看不代表 18px 能读。

用法：
    python3 scripts/icon_preview.py docs/ui/icons/actions /tmp/actions.png
    python3 scripts/icon_preview.py docs/ui/icons/actions /tmp/small.png --icon 22 --cols 10

依赖：rsvg-convert（PRoot 里是手装的；Ubuntu: librsvg2-bin）。

图标来源（重取时用这些 pin 住的版本，别用 latest）：
    Lucide       lucide-static@1.61.0                 ISC（部分图标源自 Feather，MIT）
    Lobe Icons   @lobehub/icons-static-svg@1.97.0     MIT
"""

from __future__ import annotations

import argparse
import html
import pathlib
import re
import shutil
import subprocess
import sys

THEMES = {
    # 彩色品牌图标是按浅色底设计的，深色底上必须跟着换一遍看，否则"能看清"是假的
    "dark": {"bg": "#14161a", "fg": "#e8e9ea", "label": "#8b9096"},
    "light": {"bg": "#f7f7f5", "fg": "#1b1d21", "label": "#6b7075"},
}
BG = THEMES["dark"]["bg"]
FG = THEMES["dark"]["fg"]
LABEL = THEMES["dark"]["label"]
STROKE = (
    'fill="none" stroke="{fg}" stroke-width="1.75" '
    'stroke-linecap="round" stroke-linejoin="round"'
)
FILLED = 'fill="{fg}"'


def inner_markup(path: pathlib.Path) -> str:
    """取出 <svg> 里的内容，去掉注释与 xml 声明。"""
    text = path.read_text(encoding="utf-8")
    match = re.search(r"<svg[^>]*>(.*)</svg>", text, re.S)
    if match is None:
        raise SystemExit(f"{path}: 找不到 <svg>…</svg>")
    body = re.sub(r"<!--.*?-->", "", match.group(1), flags=re.S)
    return body.strip()


def is_filled(path: pathlib.Path) -> bool:
    """品牌图标是纯填充的，动作图标是纯描边的——按根元素判断，不靠目录名。"""
    head = re.search(r"<svg[^>]*>", path.read_text(encoding="utf-8"), re.S)
    return head is not None and 'fill="currentColor"' in head.group(0)


def build(paths: list[pathlib.Path], out: pathlib.Path, cols: int, icon: int, label: str) -> None:
    label_size = max(8, int(icon / 2))
    # 格子要装得下最长的文件名，否则 22px 那档标签会互相压上
    widest = max((len(p.stem) for p in paths), default=4) * label_size * 0.58
    cell_w = int(max(icon * 2.1, widest + 10))
    cell_h = int(icon * 1.9) + 18
    rows = (len(paths) + cols - 1) // cols
    width, height = cols * cell_w, rows * cell_h + 8

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="{BG}"/>',
    ]
    for index, path in enumerate(paths):
        row, col = divmod(index, cols)
        cx = col * cell_w + cell_w / 2
        cy = row * cell_h + cell_h / 2 - 8
        body = inner_markup(path).replace("currentColor", FG)
        attr = FILLED.format(fg=FG) if is_filled(path) else STROKE.format(fg=FG)
        scale = icon / 24
        parts.append(
            f'<g transform="translate({cx - icon / 2:.1f},{cy - icon / 2:.1f}) '
            f'scale({scale:.4f})" {attr}>{body}</g>'
        )
        if label:
            parts.append(
                f'<text x="{cx:.1f}" y="{row * cell_h + cell_h - 4}" font-family="Noto Sans SC" '
                f'font-size="{label_size}" fill="{LABEL}" text-anchor="middle">'
                f"{html.escape(path.stem)}</text>"
            )
    parts.append("</svg>")

    svg_path = out.with_suffix(".svg")
    svg_path.write_text("\n".join(parts), encoding="utf-8")
    subprocess.run(
        ["rsvg-convert", "-z", "2", "-o", str(out), str(svg_path)],
        check=True,
    )
    print(f"wrote {out}  ({len(paths)} 个图标, {icon}px)")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path, help="放 SVG 的目录")
    parser.add_argument("output", type=pathlib.Path, help="输出 PNG 路径")
    parser.add_argument("--cols", type=int, default=8)
    parser.add_argument("--icon", type=int, default=44, help="预览尺寸（px）；看可读性用 22/18")
    parser.add_argument("--no-label", action="store_true", help="不画文件名")
    parser.add_argument("--theme", choices=sorted(THEMES), default="dark")
    args = parser.parse_args(argv)

    if shutil.which("rsvg-convert") is None:
        print("缺 rsvg-convert：apt install librsvg2-bin", file=sys.stderr)
        return 2

    paths = sorted(args.directory.glob("*.svg"))
    if not paths:
        print(f"{args.directory} 下没有 SVG", file=sys.stderr)
        return 1

    global BG, FG, LABEL
    theme = THEMES[args.theme]
    BG, FG, LABEL = theme["bg"], theme["fg"], theme["label"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    build(paths, args.output, args.cols, args.icon, not args.no_label)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
