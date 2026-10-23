# 图标来源与商标约定

三个目录、三种来源。许可汇总在根目录 [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md)，
全文在 `LICENSES/`；这份文件只管"每个图形从哪来、我们改了什么、什么不许做"。

## 1. `actions/`（31 个）

- 29 个来自 **Lucide**（`lucide-static@1.61.0`，ISC），其中 11 个的图形源自 **Feather**（MIT）。
  上游自己的 LICENSE 文件把这两段写在一起，我们逐字保留在 `LICENSES/ISC-lucide.txt`。
- 改动只有两处，都是机械的：`stroke-width: 2 → 1.75`、删掉 `class="lucide lucide-xxx"`。
  文件头那句 `<!-- @license lucide-static v1.61.0 - ISC -->` 保留着。
- `divider.svg` **本项目自绘**（GPL-3.0-only）：Lucide 里没有能读成"分界线"又不跟折叠箭头撞的形状。

## 2. `providers/`（21 个）

| slug | 有彩色版 | 备注 |
|---|---|---|
| `google` `deepseek` `zhipu` `alibaba` `bytedance` `baichuan` `minimax` `tencent` `stepfun` `mistral` `meta` `openrouter` `together` `siliconcloud` `perplexity` | ✅ | 取的是 `-color` 变体 |
| `openai` `anthropic` `moonshot` `xai` `ollama` `groq` | ❌ | 上游没有 `-color`，官方标本身是单色；取单色版 |

- 改动：`width`/`height` 由 `1em` 改为 `24`、删内联 `style`。图形未动。
- **不自行上色**。单色的就是单色的，不拿品牌色去染——染了反而更像"自制标"。

## 3. `models/`（16 个）

`claude` `gemini` `gemma` `deepseek` `qwen` `kimi` `doubao` `hunyuan` `yi` `chatglm`
`glmv` `mistral` `spark` `stepfun` `baichuan` `nova` —— 全部有彩色版。

**智谱相关的三个 slug 是分开的**，别混用：

| slug | 指的是 | 用在哪 |
|---|---|---|
| `zhipu` | **智谱 AI（公司）** | `providers/`——这是供应商图标 |
| `glmv` | GLM-V | `models/`——视觉模型 |
| `chatglm` | ChatGLM 早期命名 | `models/`——只在老模型名（`chatglm-*`）命中时用 |

供应商图标一律用 `zhipu`：之前 `chatglm` 顶在供应商位上是错的，已改。

## 4. 商标：能做什么、不能做什么

**能**：在客户端里标识"当前这个会话用的是哪家的模型"。这在商标法上叫指明性使用。

**不能**：

- 改色、改形、加装饰、把多个合成一个新标；
- 用在本项目自己的 logo、图标、宣传图里，也不放进 README 头图；
- 写"官方客户端""战略合作"这类暗示授权或背书的措辞；
- 把品牌图标当成"应用图标"打包分发的封面。

**随时可撤**：某个品牌方要求移除时，删掉那个 PNG 即可——会退化成首字母方块，
不需要改代码。设置里还有一档"无品牌图标"，等于一键停用全部商标图形。

## 5. 加一个新图标

1. 先按 slug 查上游有没有（`providers/` 用公司名，`models/` 用模型家族名）。
2. 有 `-color` 就取彩色；没有就用单色，**不要自己调色**。
3. 跑 `python3 scripts/icon_preview.py docs/ui/icons/providers /tmp/p.png --theme light`
   看一眼浅色底——彩色标多半是按浅色底设计的，深色底好看不代表浅色底能看。
4. 改动本文件的两张表 + 根 `THIRD-PARTY-NOTICES.md` 的数量与快照版本。
