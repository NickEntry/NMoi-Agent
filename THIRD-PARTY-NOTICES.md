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

## 3.y 隔离环境运行时（D164 / D165，进包）

三件 Android/arm64 二进制，源码位置 `android/app/src/main/jniLibs-prebuilt/arm64-v8a/`
（挂进 Gradle 的 `jniLibs` 源集，`useLegacyPackaging = true`），打进 **APK 的 `lib/arm64-v8a/`**。取用自 Termux 官方仓库（`packages.termux.dev`），
**字节内容未做任何修改**（未 patch、未 strip、未重编，sha256 与上游一致）。

为什么要"装成 `.so`"（D166）：Android 10（targetSdk 29+）起 App 私有目录里的文件**不许 exec**
（W^X，真机上是 `error=13, Permission denied`），只有 APK 的 `lib/<abi>/` 会被安装器解到
可执行的 `nativeLibraryDir`，而它**只按 `.so` 后缀**挑文件、不看内容。于是：

| 上游名字 | 包里的名字 | 版本 | 许可 | 来源 | 字节数 / sha256 前 16 |
|---|---|---|---|---|---|
| `proot` | `libnmoi_proot.so` | 5.1.107.101 | **GPL-2.0-or-later** | 包 `proot_5.1.107.101_aarch64.deb`；上游 <https://github.com/termux/proot>（tag `v5.1.107.101`） | 259,784 / `bc3e541674827637` |
| `libtalloc.so.2` | `libnmoi_talloc.so` | 2.5.0 | **LGPL-3.0** | 包 `libtalloc_2.5.0_aarch64.deb`；上游 <https://www.samba.org/ftp/talloc/> | 33,592 / `742b438c4d09e276` |
| `libandroid-shmem.so` | `libandroid-shmem.so`（**原名不动**） | 0.7 | **BSD-3-Clause** | 包 `libandroid-shmem_0.7_aarch64.deb`；上游 <https://github.com/termux/libandroid-shmem> | 14,432 / `84475798e07c8174` |

改名不是修改：`libtalloc.so.2` 改名之后**它自带的 SONAME 还是 `libtalloc.so.2`**，运行时用
`LD_PRELOAD` 按路径加载它，`proot` 的 `NEEDED` 凭 SONAME 就认得出；`libandroid-shmem.so`
**没有 SONAME**，只能靠文件名被找到，所以它保持原名，由 `LD_LIBRARY_PATH` 指到同一个目录。
`proot` 本身是按路径 exec 的（`libnmoi_proot.so`），叫什么无所谓。

全文：`LICENSES/GPL-2.0.txt`（proot 的 `COPYING`）、`LICENSES/LGPL-3.0.txt`（`talloc-2.5.0/LICENSE`）、
`LICENSES/BSD-3-Clause-libandroid-shmem.txt`（deb 里的 `copyright` 逐字副本）。

**为什么从"取用者"变成"再分发者"**：`.deb` 里的 `data.tar.xz` 需要 XZ 解码器，JVM 没有自带实现
（`.deb` 那条路就是这么断的，见 D164 修正）。把三个文件放进 assets，代价是 GPL 的再分发义务要自己履行：

- **源码可获取**：proot 的对应源码是上面那个 tag 的压缩包
  （`https://github.com/termux/proot/archive/v5.1.107.101.zip`，sha256
  `a66743f50c7b0bac1913b83bf55e139d679c80a0ca2808eec76432a65640017f`），构建脚本是
  `termux-packages/packages/proot/build.sh`（`PROOT_WITH_LIBANDROID_SHMEM=true`、
  `PROOT_UNBUNDLE_LOADER`、`-DARG_MAX=131072`）。libtalloc 与 libandroid-shmem 同理来自
  `termux-packages/packages/{libtalloc,libandroid-shmem}/build.sh`。
- **许可链**：proot 是 GPL-2.0-**or-later**（`COPYING` 是 v2 全文，源码头写 "either version 2 … or
  (at your option) any later version"），本项目按 **GPL-3.0** 使用它。它链接的 libtalloc 是 LGPL-3.0
  ——GPL-2.0-only 与 LGPL-3.0 不能组合，**GPL-3.0 可以**，所以"or later"这一条必须保留在声明里。
  （Termux 自己的 `TERMUX_PKG_LICENSE` 把 libtalloc 记成 `GPL-3.0`，而上游 2.5.0 的 `LICENSE`
  是 LGPL-3.0 全文；两种记法在本项目下都兼容，这里按上游那份记。）
- **只跑本机架构**：deb 里另有 `libexec/proot/loader` / `loader32`（QEMU 跑外来架构二进制时才用），
  **没有进包**——我们只跑 arm64 rootfs，不给跨架构执行留口子。**推断**：不装它不影响原生 arm64 运行，
  真机上如果报 `the loader was not found or doesn't work`，那是有人拿它跑了别的架构，不是装坏了。

**rootfs 不在这个包里**：Ubuntu Base 26.04.1 LTS arm64（33.4 MB）由用户在安装页触发下载，
许可随镜像里各个软件包自己的 `copyright` 走，装好之后在容器内 `/usr/share/doc/*/copyright` 可读。

## 6. 更新这份文件

新增任何外部图形/字体/代码时，同一次提交里改这一处：加一行来源与许可、把全文放进
`LICENSES/`、写清改了什么。**换版本要连版本号和取样日期一起改**——过期的许可证信息
比没有更糟，因为它看起来像核对过。

同一个许可已经有全文的，不要再放第二份：引一个新的 **Apache-2.0** 依赖时，
只在 §3 表里加一行（组件 / 版本 / 是否进包），全文仍是 `LICENSES/Apache-2.0.txt`。
**换成 MIT / BSD / OFL 这类才需要新开一份全文**，并在 §1/§2 那种表里写清来源版本。
