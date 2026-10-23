#!/usr/bin/env python3
"""把 docs/ui/icons 下的 SVG 母版打成"原版图标包"：PNG × 三档密度 + manifest.json + NOTICE.txt。

为什么是 PNG 而不是运行时渲染 SVG：Android 没有系统级 SVG 渲染器，为十几张小图引一个
渲染库不划算，PNG 解码是系统自带的（D105）。

为什么单色图标烤成白色：SVG 里用的是 `currentColor`，栅格化时必须落成一个具体颜色。
烤成纯白剪影 + 保留 alpha，运行时用 tint 上色——这样一份文件同时满足深色/浅色主题，
不用为每个主题各存一套。彩色标原样烤，运行时**不许** tint。

用法：
    python3 scripts/build_icon_pack.py                    # 生成到 data/icon-packs/original
    python3 scripts/build_icon_pack.py --version 1.0.1
    SOURCE_DATE_EPOCH=1790000000 python3 scripts/build_icon_pack.py   # 可复现构建

依赖：rsvg-convert（Ubuntu: librsvg2-bin）
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICON_SRC = ROOT / "docs" / "ui" / "icons"

# 目录 → (kind, 三档边长)。动作图标小，品牌图标大；档位与 docs/ui/icon-packs.md §2 一致。
GROUPS = {
    "actions": ("action", (24, 48, 72)),
    "providers": ("provider", (40, 80, 120)),
    "models": ("model", (40, 80, 120)),
}

PACK_ID = "nmoi-original"
TINT_FILL = "#ffffff"

NOTICE_HEADER = """NMoi-Agent 图标包 — 第三方声明
================================

这个包里每一张 PNG 都是从 SVG 母版栅格化来的，母版来源与许可见下文。
本项目自身是 GPL-3.0-only（https://www.gnu.org/licenses/gpl-3.0.txt）。

1. 界面动作图标（actions/）
   来源：Lucide（lucide-static@1.61.0），ISC 许可；
         其中 11 个图形源自 Feather，MIT 许可（Copyright (c) 2013-present Cole Bemis）。
   我们的改动：描边宽度 2 → 1.75，删除 class 属性；栅格化时把 currentColor 烤成白色。
   divider 图标为本项目自绘（GPL-3.0-only）。

2. 供应商与模型图标（providers/、models/）
   来源：Lobe Icons（@lobehub/icons-static-svg@1.97.0），MIT 许可，
         Copyright (c) 2023 LobeHub。
   我们的改动：width/height 由 1em 改为数值，删除内联 style；单色图标烤成白色。
   这些图形是各家的商标，此处仅用于标识当前使用的模型/厂商，未改色、未改形，
   不用于暗示授权或背书。

3. 许可全文
   ISC（含 Feather 的 MIT 段落）：LICENSES/ISC-lucide.txt
   MIT（Lobe Icons）：LICENSES/MIT-lobe-icons.txt
   随本项目分发时，这两个文件与本声明需一并提供。
"""


def load_text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def bake(svg_text: str, tint: bool) -> str:
    """把 currentColor 落成具体颜色。

    - 单色剪影（tint=True）：烤白，运行时用 tint 上色，一份文件通吃深浅主题。
    - 彩色标（tint=False）：不许 tint；里面若还夹着 `currentColor`（Lucide 的 Lobe 图标里会有），
      就烤成它自己的主色，烤成黑会在深色底上消失。
    """
    if tint:
        return svg_text.replace("currentColor", TINT_FILL)
    return svg_text.replace("currentColor", dominant_color(svg_text))


def is_monochrome(svg_text: str) -> bool:
    """整份图形一个显式颜色都没有，才算单色剪影——运行时该 tint。"""
    return not explicit_colors(svg_text)


def explicit_colors(svg_text: str) -> dict[str, int]:
    """统计显式颜色（排除 white，它不是"品牌色"）。"""
    counts: dict[str, int] = {}
    for raw in re.findall(r"#[0-9a-fA-F]{3,8}|fill=\"(?:black|white)\"", svg_text):
        value = raw.strip()
        if value.lower() in {"#fff", "#ffffff", 'fill="white"', 'fill="black"'}:
            continue
        counts[value] = counts.get(value, 0) + 1
    return counts


def dominant_color(svg_text: str) -> str:
    colors = explicit_colors(svg_text)
    if not colors:
        return "#333333"
    return max(colors.items(), key=lambda item: item[1])[0]


def render(svg_path: pathlib.Path, out_png: pathlib.Path, size: int, tint: bool) -> None:
    text = bake(load_text(svg_path), tint)
    tmp = out_png.with_suffix(".bake.svg")
    tmp.write_text(text, encoding="utf-8")
    try:
        subprocess.run(
            ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", str(out_png), str(tmp)],
            check=True,
            capture_output=True,
        )
    finally:
        tmp.unlink(missing_ok=True)


def png_size(path: pathlib.Path) -> tuple[int, int]:
    """读 IHDR，确认真的按预期边长出图（rsvg 有时会因为 viewBox 之类悄悄变尺寸）。"""
    data = path.read_bytes()[:24]
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: 不是 PNG（魔数不对）")
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=pathlib.Path, default=ROOT / "data" / "icon-packs" / "original")
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--now", type=int, default=None, help="created_at（毫秒）；默认取 SOURCE_DATE_EPOCH")
    args = parser.parse_args(argv)

    if shutil.which("rsvg-convert") is None:
        print("缺 rsvg-convert：apt install librsvg2-bin", file=sys.stderr)
        return 2

    out: pathlib.Path = args.out
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    created_at = args.now
    if created_at is None:
        epoch = os.environ.get("SOURCE_DATE_EPOCH")
        created_at = int(epoch) * 1000 if epoch else 0

    entries: list[dict] = []
    # 彩色标里有白色部件的：浅色主题下白会白掉，UI 必须垫一层 surface-variant 底
    needs_plate: list[str] = []
    # 每个 (kind, key) 只能有一条：同 key 两处来源会让"取哪张"取决于目录遍历顺序
    seen: set[tuple[str, str]] = set()
    total = 0

    for dirname, (kind, sizes) in GROUPS.items():
        source_dir = ICON_SRC / dirname
        svgs = sorted(source_dir.glob("*.svg"))
        if not svgs:
            print(f"{source_dir} 下没有 SVG", file=sys.stderr)
            return 1
        (out / dirname).mkdir()
        for svg in svgs:
            key = svg.stem
            if (kind, key) in seen:
                raise SystemExit(f"重复的 key：{kind}/{key}")
            seen.add((kind, key))

            text = load_text(svg)
            tint = is_monochrome(text) or kind == "action"
            base = f"{dirname}/{key}"
            for index, size in enumerate(sizes):
                suffix = "" if index == 0 else f"@{index + 1}x"
                target = out / f"{base}{suffix}.png"
                render(svg, target, size, tint)
                got = png_size(target)
                if got != (size, size):
                    raise SystemExit(f"{target}: 期望 {size}×{size}，实际 {got[0]}×{got[1]}")
                total += target.stat().st_size

            entry = {"kind": kind, "key": key, "file": base, "tint": "auto" if tint else "none"}
            # 含白色部件的彩色标：浅色主题下白会白掉。给它们一个显式标记，
            # UI 只对这几个垫底——不是所有品牌图标都套一层底，那圈底就是"多余的描边"。
            if not tint and re.search(r"#fff\b|#ffffff|fill=\"white\"", text, re.I):
                entry["backing"] = "surface"
                needs_plate.append(f"{dirname}/{key}")
            entries.append(entry)

    manifest = {
        "schema_version": 1,
        "pack_id": PACK_ID,
        "name": "原版图标包",
        "version": args.version,
        "created_at": created_at,
        "entries": entries,
        "defaults": {"rules": _default_rules(seen)},
    }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (out / "NOTICE.txt").write_text(NOTICE_HEADER, encoding="utf-8")

    pngs = list(out.rglob("*.png"))
    print(f"打包完成：{out}")
    print(f"  条目 {len(entries)} 个（action {sum(1 for e in entries if e['kind']=='action')}"
          f" / provider {sum(1 for e in entries if e['kind']=='provider')}"
          f" / model {sum(1 for e in entries if e['kind']=='model')}）")
    print(f"  PNG {len(pngs)} 张，共 {total / 1024:.0f} KB（另有 manifest.json / NOTICE.txt）")
    print(f"  version={args.version} created_at={created_at}")
    if needs_plate:
        print(f"  含白色部件（浅色主题需垫底色）：{', '.join(needs_plate)}")
    return 0


def _fnv1a(text: str) -> int:
    value = 0x811C9DC5
    for byte in text.encode("utf-8"):
        value ^= byte
        value = (value * 0x01000193) & 0xFFFFFFFF
    return value


def _rule(kind: str, field: str, pattern: str, key: str) -> dict:
    """预设规则的一条。id 由 (kind, pattern) 决定——重建两次必须得到同一个 id，
    否则 manifest 每次打包都不一样，diff 会变成噪音（而且 id 是排序的最后一级）。"""
    return {
        "id": f"preset-{kind}-{_fnv1a(f'{kind}|{pattern}'):08x}",
        "pattern": pattern,
        "mode": "regex",
        "field": field,
        "kind": kind,
        "key": key,
        "priority": 500,
        "source": "preset",
    }


def _default_rules(seen: set[tuple[str, str]]) -> list[dict]:
    """首次启动的规则种子（docs/ui/icon-packs.md §3）。

    全部是 `regex` + 优先级 500：谁更具体靠 `core::icons` 的有效字面量长度决胜，
    不靠堆优先级数字。用户加的规则默认也是 500，但来源排在预设前面，所以一加就生效。
    """
    model_rules = [
        (r"^(anthropic/)?claude", "claude"),
        (r"^(openai/)?gpt-|^o[134](?:-|$)", "openai"),
        (r"^(google/)?gemini", "gemini"),
        (r"^(google/)?gemma", "gemma"),
        (r"^deepseek|^deep-seek", "deepseek"),
        (r"^(alibaba/|dashscope/)?qwen|^qwq", "qwen"),
        (r"^(moonshot/)?kimi|^moonshot", "kimi"),
        (r"^(bytedance/)?doubao|^seed-", "doubao"),
        (r"^hunyuan|^(tencent/)", "hunyuan"),
        (r"^glm-4v|^glmv|^cogvlm", "glmv"),
        (r"^glm|^chatglm|^zhipu", "chatglm"),
        (r"^grok", "grok"),
        (r"^command", "commanda"),
        (r"^dbrx", "dbrx"),
        (r"^ling-|^(ant/)", "ling"),
        (r"^sensenova|^sense-", "sensenova"),
        (r"^reka", "reka"),
        (r"^mistral|^mixtral|^codestral", "mistral"),
        (r"^spark", "spark"),
        (r"^step-|^stepfun", "stepfun"),
        (r"^baichuan", "baichuan"),
        (r"^yi-|^01-ai", "yi"),
        (r"^nova-|^(amazon/)", "nova"),
    ]
    keys = {key for kind, key in seen if kind == "model"}
    rules = [_rule("model", "model", pattern, key) for pattern, key in model_rules if key in keys]

    # 供应商规则匹配"用户给这个供应商起的名字"（provider_name）。
    # kind 只有五个值（openai/gemini/anthropic/ollama/compat），host 是域名，
    # 预设靠名字关键词命中；想按域名钉，用户自己加一条 provider_host 规则。
    provider_rules = [
        (r"^openai", "openai"),
        (r"^anthropic", "anthropic"),
        (r"^gemini|^google", "google"),
        (r"^deepseek", "deepseek"),
        (r"^zhipu|^bigmodel|^智谱", "zhipu"),
        (r"^moonshot|^kimi", "moonshot"),
        (r"^dashscope|^alibaba|^qwen", "alibaba"),
        (r"^volc|^bytedance|^ark|^豆包", "bytedance"),
        (r"^baichuan", "baichuan"),
        (r"^minimax", "minimax"),
        (r"^hunyuan|^tencent", "tencent"),
        (r"^stepfun|^阶跃", "stepfun"),
        (r"^mistral", "mistral"),
        (r"^xai|^grok", "xai"),
        (r"^meta|^llama", "meta"),
        (r"^ollama", "ollama"),
        (r"^openrouter", "openrouter"),
        (r"^groq", "groq"),
        (r"^together", "together"),
        (r"^silicon|^硅基", "siliconcloud"),
        (r"^perplexity", "perplexity"),
        (r"^sambanova", "sambanova"),
        (r"^nvidia", "nvidia"),
        (r"^microsoft|^azure", "microsoft"),
        (r"^aws|^amazon|^bedrock", "aws"),
    ]
    provider_keys = {key for kind, key in seen if kind == "provider"}
    rules += [
        _rule("provider", "provider_name", pattern, key)
        for pattern, key in provider_rules
        if key in provider_keys
    ]
    return rules


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
