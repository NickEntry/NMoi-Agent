# 第三方组件声明

本项目自身是 **GPL-3.0-only**（全文见 `LICENSE`）。这份文件管的是**别人写的东西**：
用了什么、什么许可、全文在哪、我们改过什么、在哪个产物里能看到它。

**这份文件要随每一份分发物走**。MIT / ISC 的原文要求是"声明必须出现在**每一份副本**里"：

- MIT：`The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.`
- ISC：`provided that the above copyright notice and this permission notice appear in all copies.`

所以源码仓库、APK、桌面安装目录、便携包、导出/导入的图标包，**每一处都要有**
`THIRD-PARTY-NOTICES.md` 与本文件提到的 `LICENSES/`。具体放哪见 `docs/packaging.md`。

## 1. 界面图标（`docs/ui/icons/actions/`，31 个）

| | |
|---|---|
| 来源 | [Lucide](https://lucide.dev)，包 `lucide-static@1.61.0`，取样 2026-10-22 |
| 许可 | **ISC**；其中 11 个图形源自 [Feather](https://feathericons.com)（**MIT**） |
| 全文 | `LICENSES/ISC-lucide.txt`（该文件本身就是上游 LICENSE 的逐字副本，**同时包含** Feather 派生清单与 MIT 全文，不必再拆） |
| 我们的改动 | `stroke-width` 由 `2` 改为 `1.75`、删除 `class="lucide …"`；图形路径未动 |
| 例外 | `divider.svg` 是**本项目自绘**（GPL-3.0-only），不来自外部 |

源自 Feather 的 11 个（MIT，Copyright (c) 2013-present Cole Bemis）：
`arrow-right` `arrow-up` `check` `chevron-down` `lock` `minimize-2` `plus` `search` `square` `trash-2` `x`

其余 19 个是 Lucide 自有图形（ISC）；加上 `divider` 共 31 个。

## 2. 供应商与模型图标（`docs/ui/icons/providers/` 21 个、`docs/ui/icons/models/` 16 个）

| | |
|---|---|
| 来源 | [Lobe Icons](https://github.com/lobehub/lobe-icons)，包 `@lobehub/icons-static-svg@1.97.0`，取样 2026-10-22 |
| 许可 | **MIT**，Copyright (c) 2023 LobeHub |
| 全文 | `LICENSES/MIT-lobe-icons.txt` |
| 我们的改动 | `width`/`height` 由 `1em` 改为 `24`；删除内联 `style="flex:none;line-height:1"`；路径、`fill-rule`、`<title>` 未动 |

六家没有彩色版（官方本身就是单色标，不是我们漏取）：
`openai` `anthropic` `moonshot` `xai` `ollama` `groq`

**商标**：这些图形是各家的商标，MIT 不授权商标。本项目只在"标识当前使用哪个模型/厂商"
这一指明性场景下使用，不改色、不改形、不用作品牌标志、不暗示授权或背书。
应用内提供"无品牌图标"模式（只显示首字母方块），不使用任何商标图形；
图标包可整体切换，任一图标都可被移除而不影响功能（退化为首字母方块）。
细则见 `docs/ui/icons/NOTICE.md`。

## 3. Rust 依赖

**尚未生成**。当前 `Cargo.toml` 里几十个 crate 各有其许可（多为 MIT / Apache-2.0，
与 GPL-3.0-only 兼容）。1.0 之前必须把它们也列进这份文件——用 `cargo metadata` 导出
名称/版本/许可，或 `cargo-about` 生成独立文件，挂进打包流程。
**没做就是没做**：现在这份文件只覆盖图标，不覆盖依赖。

## 4. 每个产物里放哪里

| 产物 | 放法 |
|---|---|
| 源码仓库 | 根目录本文件 + `LICENSES/` |
| APK | `assets/notices/` 下原样带一份，应用内「设置 → 关于 → 开源许可」直接渲染 |
| Windows / Linux / macOS 安装包 | 装进安装目录 `licenses/`，**装完就存在，不依赖联网** |
| 便携包（zip / tar.gz） | **压缩包根目录**内，解压即见 |
| 图标包（导出 / 导入的 zip） | 包内根目录 `NOTICE.txt`（图标是再分发，声明必须跟着走） |

细则、路径与"安装后能解压覆盖到安装目录"这条约束见 `docs/packaging.md`。

## 5. 更新这份文件

新增任何外部图形/字体/代码时，同一次提交里改这一处：加一行来源与许可、把全文放进
`LICENSES/`、写清改了什么。**换版本要连版本号和取样日期一起改**——过期的许可证信息
比没有更糟，因为它看起来像核对过。
