#!/usr/bin/env python3
"""抓取模型元数据 → 生成 NMoi-Agent 的离线快照。

三个公开源，按"字段权威度"分工：

| 源 | 许可 | 取什么 |
|---|---|---|
| models.dev `api.json` | MIT | 主结构：family、limit(context/output)、modalities、reasoning、tool_call |
| OpenRouter `api/v1/models` | 公开 API（仅取事实字段） | 能力细化：supported_parameters、input_modalities、max_completion_tokens |
| aihubmix `api/v1/models` | 公开 API（仅取事实字段） | 补充覆盖：context_length、max_output、reasoning、tool_call |

**只保存事实字段**（长度、模态、能力开关、价格数字），不复制任何描述文本、基准、链接。
输出结构必须能被 `core::model::ModelSnapshot` 反序列化；`id` / `family` / `version`
的生成规则必须与 `core::model::normalize` / `split_trailing_version` 保持一致
（有 Rust 侧测试盯着这条）。

用法：

```bash
python3 scripts/fetch_models.py                     # 写 data/models.snapshot.json
python3 scripts/fetch_models.py --out /tmp/x.json   # 换个位置（本地试验用）
python3 scripts/fetch_models.py --offline a.json b.json c.json   # 用本地文件，不联网
```
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import urllib.request
from collections import defaultdict

MODELS_DEV = "https://models.dev/api.json"
OPENROUTER = "https://openrouter.ai/api/v1/models"
AIHUBMIX = "https://aihubmix.com/api/v1/models"

USER_AGENT = "NMoi-Agent-model-snapshot/0.1 (+https://codeberg.org/nickentry/NMoi-Agent)"

# 每个 entry 最多保留多少个别名，防止快照膨胀
MAX_ALIASES = 8


def fetch(url: str, timeout: int = 30) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


# ---------------------------------------------------------------- 归一化


def normalize(raw: str) -> tuple[str, str | None]:
    """与 core::model::normalize 保持一致：剥 [分组] 前缀 → 小写 → 统一分隔符。"""
    text = raw.strip()
    group = None
    while text.startswith("["):
        end = text.find("]")
        if end < 0:
            break
        if group is None:
            group = text[1:end].strip()
        text = text[end + 1 :].lstrip()

    normalized = re.sub(r"[._\s/:\t]+", "-", text.lower())
    normalized = "-".join(part for part in normalized.split("-") if part)
    return normalized, group


def split_trailing_version(normalized: str) -> tuple[str, list[int] | None]:
    """与 core::model::split_trailing_version 保持一致：切出尾部版本号。"""
    if not normalized or not normalized[-1].isdigit():
        return normalized, None
    index = len(normalized)
    while index > 0 and (normalized[index - 1].isdigit() or normalized[index - 1] in ".-_"):
        index -= 1
    version_text = normalized[index:]
    parts = [int(p) for p in re.split(r"[.\-_]+", version_text) if p.isdigit()]
    if not parts:
        return normalized, None
    family = normalized[:index].rstrip("-._")
    if not family:
        return normalized, None
    return family, parts


def as_int(value) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)) and value > 0:
        return int(value)
    return None


def modalities_of(entry: dict) -> set[str]:
    result: set[str] = set()
    # models.dev: modalities: {input: [...], output: [...]}
    modalities = entry.get("modalities")
    if isinstance(modalities, dict):
        for key in ("input", "output"):
            values = modalities.get(key)
            if isinstance(values, list):
                result.update(str(v).lower() for v in values)
    # openrouter: architecture.input_modalities / output_modalities
    architecture = entry.get("architecture")
    if isinstance(architecture, dict):
        for key in ("input_modalities", "output_modalities"):
            values = architecture.get(key)
            if isinstance(values, list):
                result.update(str(v).lower() for v in values)
    # aihubmix: input_modalities / output_modalities 直接在顶层
    for key in ("input_modalities", "output_modalities"):
        values = entry.get(key)
        if isinstance(values, list):
            result.update(str(v).lower() for v in values)
    return result


# provider id → 该厂商的模型家族前缀。用来判断"这条数据是不是厂商自己给的"：
# 同厂数据优先，避免多 provider 求并集把档位撑成"全都有"。
VENDOR_FAMILIES = {
    "anthropic": ["claude"],
    "openai": ["gpt", "o1", "o3", "o4", "chatgpt"],
    "google": ["gemini", "gemma", "palm"],
    "deepseek": ["deepseek"],
    "xai": ["grok"],
    "alibaba": ["qwen"],
    "qwen": ["qwen"],
    "moonshotai": ["kimi", "moonshot"],
    "moonshot": ["kimi", "moonshot"],
    "zai": ["glm"],
    "zhipuai": ["glm"],
    "minimax": ["minimax"],
    "mistral": ["mistral", "mixtral", "magistral"],
}

EFFORT_RANK = {
    "none": 0,
    "minimal": 1,
    "low": 2,
    "medium": 3,
    "high": 4,
    "xhigh": 5,
    "max": 6,
}

EFFORT_ALIASES = {
    "off": "none",
    "disabled": "none",
    "min": "minimal",
    "med": "medium",
    "extra_high": "xhigh",
    "maximum": "max",
}


def normalize_effort(value: str) -> str | None:
    key = value.strip().lower()
    key = EFFORT_ALIASES.get(key, key)
    return key if key in EFFORT_RANK else None


def is_native_source(provider_id: str, family: str) -> bool:
    """这条数据是不是"模型厂商自己"给的？（如 provider=某厂商 且 family=该厂商的模型族）"""
    provider = provider_id.strip().lower()
    family = family.lower()
    if family.startswith(provider):
        return True
    for prefix in VENDOR_FAMILIES.get(provider, ()):
        if family.startswith(prefix):
            return True
    return False


def efforts_of(entry: dict) -> tuple[list[str], bool]:
    """从 models.dev 的 `reasoning_options` 里取出这个模型支持哪些强度档位。

    形态实测（见 docs/model-data.md）：
      [{"type":"toggle"}]                        → 只能开关
      [{"type":"effort","values":["low","high","max"]}] → 有档位
    合并时取并集；档位按"从低到高"排好，UI 的滑块直接用这个顺序。
    """
    options = entry.get("reasoning_options")
    supported: set[str] = set()
    toggle = False
    if isinstance(options, list):
        for option in options:
            if not isinstance(option, dict):
                continue
            kind = option.get("type")
            if kind == "toggle":
                toggle = True
            elif kind == "effort":
                values = option.get("values")
                if isinstance(values, list):
                    for value in values:
                        effort = normalize_effort(str(value))
                        if effort:
                            supported.add(effort)
    return sorted(supported, key=lambda name: EFFORT_RANK[name]), toggle


def cost_of(entry: dict) -> dict | None:
    """统一成"每百万 token 美元"。不同源的字段名不一样，这里只取数字。"""
    cost = entry.get("cost")
    if isinstance(cost, dict):
        inp = cost.get("input")
        out = cost.get("output")
        if isinstance(inp, (int, float)) or isinstance(out, (int, float)):
            return {
                "input_per_million": round(float(inp or 0), 6),
                "output_per_million": round(float(out or 0), 6),
            }
    pricing = entry.get("pricing")
    if isinstance(pricing, dict):
        try:
            inp = float(pricing.get("prompt") or 0) * 1_000_000
            out = float(pricing.get("completion") or 0) * 1_000_000
        except (TypeError, ValueError):
            return None
        if inp or out:
            return {
                "input_per_million": round(inp, 6),
                "output_per_million": round(out, 6),
            }
    return None


# ---------------------------------------------------------------- 三源解析


def iter_models_dev(payload: dict):
    """{provider: {models: {id: {...}}}} → 逐条吐 (raw_id, entry)"""
    for provider_id, provider in payload.items():
        if not isinstance(provider, dict):
            continue
        models = provider.get("models")
        if not isinstance(models, dict):
            continue
        for model_id, model in models.items():
            if isinstance(model, dict):
                yield provider_id, model_id, model


def iter_list_payload(payload):
    data = payload.get("data")
    if not isinstance(data, list):
        return
    for entry in data:
        if isinstance(entry, dict):
            yield entry


def collect(models_dev: dict, openrouter: dict, aihubmix: dict) -> dict:
    """(family, version) → 合并后的 profile + 别名集合。

    key 用 tuple（list 不能当 dict 的键）。
    """
    merged: dict[tuple[str, tuple[int, ...] | None], dict] = {}
    aliases: dict[tuple[str, tuple[int, ...] | None], set[str]] = defaultdict(set)

    def bump(key, *, context=None, max_output=None, modalities=None,
             reasoning=False, tools=False, structured=False, cost=None, raw_id=None,
             efforts=None, toggle=False, native=False):
        slot = merged.setdefault(
            key,
            {
                "context_window": 0,
                "max_output": 0,
                "images": False,
                "audio_in": False,
                "video_in": False,
                "reasoning": False,
                "tools": False,
                "structured_output": False,
                "cost": None,
                "supported_efforts": [],
                "supports_toggle": False,
                "efforts_locked": False,
            },
        )
        if context:
            slot["context_window"] = max(slot["context_window"], context)
        if max_output:
            slot["max_output"] = max(slot["max_output"], max_output)
        for modality in modalities or ():
            if modality == "image" or modality == "images":
                slot["images"] = True
            elif modality == "audio":
                slot["audio_in"] = True
            elif modality == "video":
                slot["video_in"] = True
        slot["reasoning"] = slot["reasoning"] or bool(reasoning)
        slot["tools"] = slot["tools"] or bool(tools)
        slot["structured_output"] = slot["structured_output"] or bool(structured)
        if cost and not slot["cost"]:
            slot["cost"] = cost
        # 档位优先信厂商自己的数据：provider 是"转卖"的（聚合站）不覆盖已锁定的值，
        # 否则跨 provider 求并集会把 low/high/max 撑成 none…max，等于没有约束。
        if efforts:
            if native and not slot["efforts_locked"]:
                slot["supported_efforts"] = list(efforts)
                slot["efforts_locked"] = True
            elif not slot["efforts_locked"] and not slot["supported_efforts"]:
                slot["supported_efforts"] = list(efforts)
        if not slot["efforts_locked"]:
            slot["supported_efforts"].sort(key=lambda name: EFFORT_RANK.get(name, 99))
        slot["supports_toggle"] = slot["supports_toggle"] or bool(toggle)
        if raw_id:
            aliases[key].add(raw_id)

    # ---- models.dev（主结构）
    for _provider, model_id, model in iter_models_dev(models_dev):
        raw_id = str(model.get("id") or model_id)
        normalized, _ = normalize(raw_id)
        if not normalized:
            continue
        family_hint = model.get("family")
        family, version = split_trailing_version(normalized)
        if isinstance(family_hint, str) and family_hint.strip():
            hint_norm, _ = normalize(family_hint)
            # 只在提示确实像家族时采用（避免 "anthropic" 这种 provider 名盖掉真家族）
            if hint_norm and hint_norm != normalized and len(hint_norm) >= 3:
                family = hint_norm
        limit = model.get("limit") if isinstance(model.get("limit"), dict) else {}
        efforts, toggle = efforts_of(model)
        bump(
            (family, tuple(version) if version else None),
            context=as_int(limit.get("context")),
            max_output=as_int(limit.get("output")),
            modalities=modalities_of(model),
            reasoning=bool(model.get("reasoning")),
            tools=bool(model.get("tool_call")),
            structured=model.get("structured_output") is True,
            cost=cost_of(model),
            raw_id=raw_id,
            efforts=efforts,
            toggle=toggle,
            native=is_native_source(str(_provider), family),
        )

    # ---- OpenRouter（能力细化）
    for entry in iter_list_payload(openrouter):
        raw_id = str(entry.get("id") or "")
        normalized, _ = normalize(raw_id)
        if not normalized:
            continue
        family, version = split_trailing_version(normalized)
        params = entry.get("supported_parameters")
        params = {str(p) for p in params} if isinstance(params, list) else set()
        top_provider = entry.get("top_provider") if isinstance(entry.get("top_provider"), dict) else {}
        bump(
            (family, tuple(version) if version else None),
            context=as_int(entry.get("context_length")),
            max_output=as_int(top_provider.get("max_completion_tokens")),
            modalities=modalities_of(entry),
            reasoning=bool(entry.get("reasoning")) or "reasoning" in params,
            tools="tools" in params,
            structured={"response_format", "structured_outputs", "structured_output"} & params != set(),
            cost=cost_of(entry),
            raw_id=raw_id,
        )

    # ---- aihubmix（补充覆盖）
    for entry in iter_list_payload(aihubmix):
        raw_id = str(entry.get("model_id") or entry.get("id") or "")
        normalized, _ = normalize(raw_id)
        if not normalized:
            continue
        family, version = split_trailing_version(normalized)
        bump(
            (family, tuple(version) if version else None),
            context=as_int(entry.get("context_length")),
            max_output=as_int(entry.get("max_output")),
            modalities=modalities_of(entry),
            reasoning=bool(entry.get("reasoning")),
            tools=bool(entry.get("tool_call")),
            cost=cost_of(entry),
            raw_id=raw_id,
        )

    return merged, aliases


def has_any_fact(profile: dict) -> bool:
    """没有任何事实的条目（既没长度、又没能力、还没价格）不留：它们只会让快照变胖。"""
    return bool(
        profile["supported_efforts"]
        or profile["context_window"]
        or profile["max_output"]
        or profile["images"]
        or profile["tools"]
        or profile["structured_output"]
        or profile["reasoning"]
        or profile["cost"]
    )


def build_snapshot(merged: dict, aliases: dict, fetched_at: str) -> dict:
    entries = []
    for (family, version), profile in sorted(
        merged.items(), key=lambda item: (item[0][0], item[0][1] or ())
    ):
        version_list = list(version) if version else None
        if not has_any_fact(profile):
            continue
        entry_id = family if version_list is None else f"{family}-" + "-".join(str(p) for p in version_list)
        alias_list = sorted(name for name in aliases.get((family, version), set()) if name != entry_id)
        entries.append(
            {
                "id": entry_id,
                "family": family,
                "version": version_list,
                "aliases": alias_list[:MAX_ALIASES],
                "profile": {
                    "capabilities": {
                        "streaming": True,
                        # 下面三个是"端点属性"，由 provider 配置决定，快照不猜
                        "streaming_usage": False,
                        "assistant_prefill": False,
                        "reasoning_replay_required": False,
                        "images": profile["images"],
                        "tools": profile["tools"],
                        "structured_output": profile["structured_output"],
                    },
                    "thinking": {
                        "field": "none",
                        "effort": None,
                        "budget_tokens": None,
                        "custom": None,
                    },
                    "reasoning": profile["reasoning"],
                    "supported_efforts": profile["supported_efforts"],
                    "supports_toggle": profile["supports_toggle"],
                    "context_window": profile["context_window"],
                    "max_output": profile["max_output"],
                    "cost": profile["cost"],
                },
            }
        )

    return {
        "provenance": {
            "generated_by": "scripts/fetch_models.py",
            "fetched_at": fetched_at,
            "note": (
                "只保存事实字段（上下文长度 / 输出上限 / 模态 / 能力开关 / 价格数字），"
                "不复制任何描述文本、基准或链接。刷新请重跑脚本。"
            ),
            "sources": [
                {
                    "name": "models.dev",
                    "url": MODELS_DEV,
                    "license": "MIT",
                    "used_for": "family / context / max_output / modalities / reasoning / tool_call / cost",
                },
                {
                    "name": "OpenRouter",
                    "url": OPENROUTER,
                    "license": "公开 API，仅取事实字段",
                    "used_for": "supported_parameters / input_modalities / max_completion_tokens",
                },
                {
                    "name": "aihubmix",
                    "url": AIHUBMIX,
                    "license": "公开 API，仅取事实字段",
                    "used_for": "context_length / max_output / reasoning / tool_call（补充覆盖）",
                },
            ],
        },
        "entries": entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="生成模型元数据快照")
    parser.add_argument("--out", default="data/models.snapshot.json")
    parser.add_argument(
        "--offline",
        nargs=3,
        metavar=("MODELS_DEV", "OPENROUTER", "AIHUBMIX"),
        help="用本地 JSON 文件代替联网（三个都要给）",
    )
    parser.add_argument("--fetched-at", default=None, help="覆盖 fetched_at（便于可复现）")
    args = parser.parse_args()

    if args.offline:
        paths = args.offline
        models_dev = json.load(open(paths[0], encoding="utf-8"))
        openrouter = json.load(open(paths[1], encoding="utf-8"))
        aihubmix = json.load(open(paths[2], encoding="utf-8"))
    else:
        models_dev = fetch(MODELS_DEV)
        openrouter = fetch(OPENROUTER)
        aihubmix = fetch(AIHUBMIX)

    fetched_at = args.fetched_at or dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    merged, aliases = collect(models_dev, openrouter, aihubmix)
    snapshot = build_snapshot(merged, aliases, fetched_at)

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(snapshot, handle, ensure_ascii=False, indent=1, sort_keys=False)
        handle.write("\n")

    with_cost = sum(1 for entry in snapshot["entries"] if entry["profile"]["cost"])
    print(
        f"写入 {args.out}：{len(snapshot['entries'])} 条家族-版本，"
        f"{with_cost} 条带价格，fetched_at={fetched_at}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())