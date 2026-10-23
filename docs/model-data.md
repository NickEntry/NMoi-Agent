# 模型元数据：来源、许可、刷新流程

> 目的：新增模型时**自动填对参数**（上下文长度、输出上限、模态、能力开关、价格），
> 而不是让用户手抄。正则是兜底，快照是主数据。
> 关联：D19（匹配链）、D34（本文件对应的决策）。

---

## 1. 数据源

| 源 | 许可 | 取什么字段 | 为什么用它 |
|---|---|---|---|
| [models.dev](https://models.dev/api.json)（`anomalyco/models.dev`） | **MIT** | `family`、`limit.context/output`、`modalities`、`reasoning`、`tool_call`、`cost`、`release_date` | 主结构。231 家 provider，字段最全，社区 TOML + schema 校验 |
| [OpenRouter](https://openrouter.ai/api/v1/models) | 公开 API | `supported_parameters`、`architecture.input_modalities`、`top_provider.max_completion_tokens` | 能力细化：能从参数表里直接看出 tools / structured_outputs / reasoning |
| [aihubmix](https://aihubmix.com/api/v1/models) | 公开 API | `context_length`、`max_output`、`reasoning`、`tool_call`、输入输出模态 | 补充覆盖（854 条），补齐前两个源没有的条目 |
| wavespeed | 需要 key（实测 401） | — | 不采用 |

**只取事实字段**：长度、模态、能力布尔、价格数字。**不复制**任何描述文本、基准分数、链接——那些是有表达性的内容，
而这里需要的只是事实。快照是我们自己 schema 的派生数据，不是上游文件的搬运。

## 2. 快照

- 位置：`data/models.snapshot.json`（当前 1.2 MB，1729 条"家族-版本"条目，其中 1244 条带价格）
- 生成：`python3 scripts/fetch_models.py`
- 结构：`{ provenance, entries: [CatalogEntry] }`，与 `core::model::CatalogEntry` 一一对应
  （`id` / `family` / `version` / `aliases` / `profile`）
- `provenance` 里记了 `fetched_at`、每个源的 URL、许可与"用来取什么字段"

**归一化规则必须两边一致**：脚本里的 `normalize` / `split_trailing_version` 与
`core::model` 里的是同一套规则（剥 `[分组]` 前缀 → 小写 → 统一分隔符 → 切尾部版本）。
Rust 侧有一条测试直接读这份快照并解析几个知名模型，规则漂移会立刻被发现。

## 3. 刷新流程

```bash
# 联网抓（离线环境用 --offline 三个本地文件代替）
python3 scripts/fetch_models.py

# 提交前自查
git diff --stat data/models.snapshot.json
```

- 快照里**不存**抓取时间以外的动态字段，所以 diff 基本只反映真实变化。
- 想固定时间戳便于复现：`--fetched-at 2026-10-20T00:00:00Z`。
- 上游挂了怎么办：脚本会直接抛错，快照保持旧版本，不影响已有功能——
  运行时不联网取元数据，**没有这份文件也能跑**（退化为正则 + 默认参数）。

## 4. 运行时的优先级

```text
用户手动覆盖  >  快照条目  >  正则家族匹配  >  保守默认
```

`MatchSource` 会把每一步说清楚：`Alias` / `FamilyVersion` / `FamilyLatest` / `FamilyFuzzy` / `Unknown`。
其中后两者 `needs_review()` 为真，UI 必须显示「参数是按名字猜的，可手动覆盖」。

## 5. 与"家族"的关系

快照里很多条目是**伞形条目**（`id == family`，`version` 为 `null`）——上游对
`deepseek-v4.1-flash` 这种"版本在中间"的名字本来就没有版本段，我们照实记录，
匹配时用 `family_entry()` 优先取伞形条目，其次取该家族最大版本。
这样 `DeepSeek-V4.1-Flash`、`deepseek-v4-1-flash`、`deepseek/v4.1-flash` 都能落到同一条。

## 5.5 思考档位（supported_efforts）

元数据的 `reasoning_options` 直接告诉我们"这个模型支持哪些强度档位"。实测分布：

| 形态 | 数量 | 说明 |
|---|---|---|
| `[{"type":"toggle"}]` | 760 | 只能开/关，没有档位 |
| `effort: low/medium/high` | 743 | 标准三档 |
| `effort: low/medium/high/xhigh/max` | 450 | 带超高档 |
| `effort: none/low/medium/high/xhigh/max` | 281 | 能显式关 |
| `effort: minimal/low/medium/high` | 238 | OpenAI 系 |
| `effort: low/high/max` | 236 | DeepSeek 系（**没有 medium**） |

**厂商自身数据优先**：同一条数据在聚合站上可能被改写（DeepSeek 在某个聚合站被写成
`none…max`）。脚本加了 native 判定——provider 是模型厂商本身时，档位"先到先锁"，
其余来源只在空位时补，避免求并集把档位撑成"全都有"。

快照里 `supported_efforts` 为空的意思是**没有数据**，不是"不支持"：
UI 这时只给"关闭 / 自动"两个位置。

## 6. 待办

- [ ] 价格用于 UI 成本估算（现在只存了数字，还没接）
- [ ] `reasoning_options`（models.dev 提供的强度档位）细化到 `ThinkingSetting.effort` 的可选值
- [ ] 定期刷新：跟版本走 + 设置页放个"刷新模型元数据"按钮（带失败提示，不阻塞）

## 模态探针（`scripts/modality_probe.sh`，P3.4）

本机没有多模态测试模型，所以这一节**只记"打了一次、看到了什么"，不记"支持/不支持"的结论**。

2026-10-21 实测（`http://127.0.0.1:8790/v1`，`relay-b/DeepSeek-V4.1-Flash`）：

| 模态 | HTTP | 探针结论 | 说明 |
|---|---|---|---|
| text | 200 | ok | 正常回正文 |
| image | 200 | ok | 端点收下 `image_url`，模型在**思考**里描述"一张纯色图片"，但那张图是 1×1 的测试图 |
| pdf / audio / video | - | untested | 本机没有可用素材，也没给 `--pdf/--audio/--video` |

**那一行 image 不能读成"这个模型支持看图"**：1×1 的图只证明字段没被拒；模型给出的
"浅粉色/肉色"更像是在补一个合理答案。要下结论，得拿一张真图重跑，并且看它能不能说出
图里的具体文字——这一点写在这里，免得以后有人把 200 当成能力证明。

结论沉淀方式：谁有可用的多模态模型，就跑同一份脚本，把输出表贴进 issue 或本文件，
标明日期、端点与模型名。**没有日期和模型的结论文档等于传闻。**
