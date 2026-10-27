# 第三方组件声明

本项目自身是 **GPL-3.0-only**（全文见 `LICENSE`）。这份文件管的是**别人写的东西**：
用了什么、什么许可、全文在哪、我们改过什么、在哪个产物里能看到它。

**这份文件要随每一份分发物走**。MIT / ISC 的原文要求是"声明必须出现在**每一份副本**里"：

- MIT：`The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.`
- ISC：`provided that the above copyright notice and this permission notice appear in all copies.`
- Apache-2.0（§4(a)）：`You must give any other recipients of the Work or Derivative Works a copy of this License.`

所以源码仓库、APK、桌面安装目录、便携包、导出/导入的图标包，**每一处都要有**
`THIRD-PARTY-NOTICES.md` 与本文件提到的 `LICENSES/`。具体放哪见 `docs/packaging.md`。

## 1. 界面图标（`docs/ui/icons/actions/`，34 个）

| | |
|---|---|
| 来源 | [Lucide](https://lucide.dev)，包 `lucide-static@1.61.0`，取样 2026-10-22 |
| 许可 | **ISC**；其中 11 个图形源自 [Feather](https://feathericons.com)（**MIT**） |
| 全文 | `LICENSES/ISC-lucide.txt`（该文件本身就是上游 LICENSE 的逐字副本，**同时包含** Feather 派生清单与 MIT 全文，不必再拆） |
| 我们的改动 | `stroke-width` 由 `2` 改为 `1.75`、删除 `class="lucide …"`；图形路径未动 |
| 例外 | `divider.svg` 是**本项目自绘**（GPL-3.0-only），不来自外部 |

源自 Feather 的 11 个（MIT，Copyright (c) 2013-present Cole Bemis）：
`arrow-right` `arrow-up` `check` `chevron-down` `lock` `minimize-2` `plus` `search` `square` `trash-2` `x`

其余 22 个是 Lucide 自有图形（ISC）；加上 `divider` 共 34 个。

2026-10-24 追加三个（同一包 `lucide-static@1.61.0`）：`volume-2`（文字转语音）、
`languages`（翻译）、`git-branch`（创建分支）。改动同前（`stroke-width` 2 → 1.75、删 `class`）。
`volume-2` 在 Feather 里也有同形图标（MIT），而 `LICENSES/ISC-lucide.txt` 本身就同时带着
Feather 派生清单与 MIT 全文，所以这次追加**不新增许可文件**。

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

## 3. Android / Kotlin 运行期依赖（`android/app/build.gradle.kts`）

**全是 Apache-2.0**，一份全文够用：`LICENSES/Apache-2.0.txt`
（逐字副本，176 行 / 9723 字节，取自 crates.io 上通行的标准文本）。

| 组件 | 版本 | 许可 | 进 APK 吗 |
|---|---|---|---|
| `androidx.compose.material3:material3`（Material 3 的 Compose 实现，D126 用的就是它） | 由 BOM `2024.09.00` 定版 | Apache-2.0 | 进 |
| `androidx.compose.ui:ui`、`androidx.compose.foundation:foundation` | 同上 BOM | Apache-2.0 | 进 |
| `androidx.activity:activity-compose` | `1.9.2` | Apache-2.0 | 进 |
| `androidx.core:core-ktx` | `1.13.1` | Apache-2.0 | 进 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.8.1` | Apache-2.0 | 进 |
| Kotlin 标准库（随 KGP 进包） | `2.0.21` | Apache-2.0 | 进 |
| `androidx.compose.ui:ui-tooling` | 同上 BOM | Apache-2.0 | 只进 **debug** 包（`debugImplementation`） |

**不在包里的**：AGP `8.5.2`、Gradle `8.7`、Kotlin 编译器与 Compose 编译插件 —— 只在构建期跑，
不分发给用户，所以没有"随副本走"的义务；列在这里是为了"到底用了什么"可查。

**我们的改动**：一行代码都没改，直接用 AAR / JAR 里的类。

**M3 是"库"还是"规范"，分清楚**：这里声明的是 `androidx.compose.material3` 这个**代码库**。
Google 的 Material Design 3 **规范文档**不是随包分发的软件，不在这份声明里；
规范里提到的 Roboto 字体本项目**没内置**（Android 侧只用系统默认字体，见 `docs/fonts.md`），
所以没有额外的字体许可要挂。

**`META-INF/AL2.0` 是刻意剔掉的**：`packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }`
把各 jar 自带的许可副本从 APK 里去掉（不剔会在资源合并阶段撞重名）。
代价是"上游自带的那份"不进包，**由我们自己随包顶一份** —— 就是 `LICENSES/Apache-2.0.txt`，
同一段文本，经 `syncNotices` 进 `assets/notices/LICENSES/`。免得以后有人把它当成"漏了"。

**未核对项**：AAR 里若带 `META-INF/NOTICE` 文本，Apache-2.0 §4(b) 要求原样保留。
本机没有 Android SDK，打不开 AAR 看内容 → **未核对**。补法：CI 里加一条读 AAR 的检查，
结论回填到这一段（现在这一行就是"还没验"的标记）。

## 4. Rust 依赖

**尚未生成**。当前 `Cargo.toml` 里几十个 crate 各有其许可（多为 MIT / Apache-2.0，
与 GPL-3.0-only 兼容）。1.0 之前必须把它们也列进这份文件——用 `cargo metadata` 导出
名称/版本/许可，或 `cargo-about` 生成独立文件，挂进打包流程。
**没做就是没做**：现在这份文件覆盖图标（§1/§2）与 Android / Kotlin 运行期依赖（§3），
**不覆盖 Rust crate** —— `Cargo.lock` 里那几十个还没有清单。

## 5. 每个产物里放哪里

| 产物 | 放法 |
|---|---|
| 源码仓库 | 根目录本文件 + `LICENSES/` |
| APK | `assets/notices/` 下原样带一份，应用内「设置 → 关于 → 开源许可」直接渲染 |
| Windows / Linux / macOS 安装包 | 装进安装目录 `licenses/`，**装完就存在，不依赖联网** |
| 便携包（zip / tar.gz） | **压缩包根目录**内，解压即见 |
| 图标包（导出 / 导入的 zip） | 包内根目录 `NOTICE.txt`（图标是再分发，声明必须跟着走） |

细则、路径与"安装后能解压覆盖到安装目录"这条约束见 `docs/packaging.md`。


### 3.x TLS（D161，进包）

发请求要 HTTPS，Rust 侧这一层由以下几个 crate 提供。它们**编译进 `libnmoi_ffi.so`**，
所以随每个产物一起分发。

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| `rustls` | 0.23.x | Apache-2.0 OR MIT OR ISC（三选一，本项目按 Apache-2.0 使用） | TLS 实现（纯 Rust 协议栈） |
| `ring` | 0.17.x | Apache-2.0 AND ISC（含 ISC 风格的第三方代码） | 密码学原语 |
| `webpki-roots` | 0.26.x | **MPL-2.0** | Mozilla 根证书集合（数据）。MPL-2.0 与 GPL-3.0 兼容；该 crate 的源码在 crates.io 与上游仓库公开可取 |
| `rustls-pki-types` / `zeroize` / `subtle` / `untrusted` / `log` / `once_cell` | 见 `Cargo.lock` | Apache-2.0 OR MIT（`untrusted` 为 ISC） | 传递依赖，全文引用 Apache-2.0 / MIT |

`zeroize` 在 `Cargo.toml` 里被钉在 `=1.8.1`：1.9 起用了 **edition2024**，
而工作区 MSRV 是 1.75（D10），老 Cargo 连它的 manifest 都解析不了。

## 6. 更新这份文件

新增任何外部图形/字体/代码时，同一次提交里改这一处：加一行来源与许可、把全文放进
`LICENSES/`、写清改了什么。**换版本要连版本号和取样日期一起改**——过期的许可证信息
比没有更糟，因为它看起来像核对过。

同一个许可已经有全文的，不要再放第二份：引一个新的 **Apache-2.0** 依赖时，
只在 §3 表里加一行（组件 / 版本 / 是否进包），全文仍是 `LICENSES/Apache-2.0.txt`。
**换成 MIT / BSD / OFL 这类才需要新开一份全文**，并在 §1/§2 那种表里写清来源版本。
