# 实机验证：方法与顺序

> 这份是**给你（人）按着做**的清单，不是给我看的设计文档。
> CI 能证明"编得过、包出得来"，证明不了"装得上、跑得动、写下去的东西还对"。
> 下面按**失败越早越便宜**排序：先验包装对不对，再验符号对不对，再验库能不能建，
> 最后才验内容对不对。中间的某一步没过就停下，别往下跳。

## 0. 现在到底缺什么验证

| 已经验过的（CI / 本地） | 还没有任何证据的 |
|---|---|
| `cargo test --workspace`、`clippy`、`fmt`、MSRV 1.75 | APK 能不能装到手机上 |
| C 侧 ABI 277 项（`./scripts/ffi_smoke.sh`） | `.so` 会不会真的被加载（符号名、ABI、架构） |
| `:app:assembleDebug` 出包（9,462,044 字节） | 库能不能在 `/data/data/.../files/` 下建出来 |
| `cargo ndk` 交叉编译出 `arm64-v8a/libnmoi_ffi.so`（**CI 绿了才算**） | 列表里显示的是真数据还是占位数据 |

一句话：**从 APK 到"界面上的数字来自数据库"这一段，一次都没跑过。**

## 0.5 实测记录

| 日期 | 版本 | 通过项 | 证据 |
|---|---|---|---|
| 2026-10-23 | `5333173` | **V0.5**（点开不崩）、**V1**（标题下显示 `NMoi-Agent v0.1.0 · abi 1`）、**V5.5**（设置里点图标不再闪退） | `logcat` 无 `FATAL EXCEPTION`；`ActivityInfo{com.nmoi.agent.icon.TerminalStarsDark}` 说明入口是 alias；全程无 `NMoiIcon` 报错行 |

还没验的：V2/V3（库落盘与 schema）、V4（真数据）、V5（旋转与重开）、**桌面图标是否真的换了样子**
（这条只能眼睛看：logcat 说"切成功了"不等于桌面刷新了）。

## 1. 什么时候需要你动手

现在就需要。这几步我这边做不了——没有设备、也没有模拟器（本机 PRoot / aarch64，无 Android SDK）。

需要你准备：

- 一台 **arm64** 的 Android 手机（8.0 / API 26 以上；只编了 `arm64-v8a` 一个 ABI）
- 打开「开发者选项 → USB 调试」，或用无线调试
- 电脑上有 `adb`（跟手机同网段，或插线）
- 不需要 root；不需要 Play 商店

## 2. 前置命令

```bash
adb devices                      # 应看到设备且状态是 device（不是 unauthorized）
adb install -r -d nmoi-debug-apk/app-debug.apk
# 包名：com.nmoi.agent（全小写，D116 统一之后与 namespace 一致；2026-10-23 之前装过的
#        com.NMoi.agent 是另一个包，先 adb uninstall com.NMoi.agent 再装，否则会并存两份）
adb shell pm list packages | grep -i nmoi
```

从 GitHub Actions 的 run 页面下载工件 `nmoi-debug-apk`，解压即得 `app-debug.apk`。

当前这一版是 **20,767,078 字节**（debug，含未 strip 的 `arm64-v8a/libnmoi_ffi.so`，11,290,352 字节）。
体积对不上说明拿的不是同一版：早于提交 `ac0247a` 的包**没有 `.so`**，
装上去只会在 V1 看到「原生层未加载」。

## 3. 验证顺序

### V0 装包

```bash
adb install -r -d app-debug.apk
```

**通过判据**：输出 `Success`。

失败先看这几条：

| 报错 | 意思 |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名或包名跟已装的不一样 → `adb uninstall com.nmoi.agent` 再装（数据会丢，现在是骨架阶段无所谓） |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 手机的 ABI 不是 arm64-v8a（或 `.so` 没进包） |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足；APK 里带着 SQLite 与 Compose，debug 包 ~10MB |

### V0.5 点开应用不崩

装完先点开一次，停在主界面看两秒。

**通过判据**：能停在会话列表页（有没有数据都不算失败），不闪退回桌面。

这一步是补上的：之前清单从 V1 开始，默认"应用本来就能开"，结果 2026-10-23 在设置里
点图标那一格时崩了，而清单里没有任何一条能提前发现它。**应用能不能启动、界面能不能进，
永远该是第一条。**

崩了就先拿这一行，比什么都快：

```bash
adb logcat -d | grep -A30 "FATAL EXCEPTION"
```

### V1 冷启动 + 原生层连上没有

打开 App，看标题下面**第三行小字**。

**这一行有三种样子，必须分开看**（2026-10-22 第一次真机就撞在第二种上）：

| 显示 | 含义 | 下一步 |
|---|---|---|
| `NMoi-Agent v0.1.0 · abi 1` | 通了：`.so` 进包 + 加载成功 + 符号名对 + ABI 一致 | 去 V2 |
| `原生层未加载：…` | `.so` 没进包、架构不对、或被系统拒绝加载 | 见下面的 logcat 三条 |
| `原生层已加载，但 JNI 符号对不上：…` | **库加载成功**，但 JVM 找不到 `Java_<包>_<类>_<方法>` | 符号名错了，见下一段 |

第二种**没过**时：

```bash
adb logcat -d | grep -iE "UnsatisfiedLinkError|dlopen|nmoi_ffi|nmoi"
```

- `UnsatisfiedLinkError: dlopen failed: library "libnmoi_ffi.so" not found` → `.so` 没被打进 APK
- `UnsatisfiedLinkError: No implementation found for ... versionJson` → 符号名或参数形状对不上（`object NativeBridge` 的方法是**实例方法**，Rust 侧第二个参数得是 `JObject`）
- 其它 `dlopen` 失败 → 架构不对，或 `.so` 依赖了系统没有的库

第三种（符号对不上）时，logcat 会把**它找的确切名字**写出来，直接对：

```
No implementation found for java.lang.String com.nmoi.agent.ui.NativeBridge.openStorageJson(java.lang.String)
(tried Java_com_nmoi_agent_ui_NativeBridge_openStorageJson)
```

括号里 `tried ...` 之后那串就是要求的符号名——**Rust 侧必须一字不差**。
包名、类名、方法名任意一个改了，符号名跟着变。CI 里有一道
`scripts/check_jni_symbols.sh` 专门对账，所以这类错正常应该在出包前就被拦住；
如果漏到真机，说明有人绕过了 CI 或者 Kotlin 端新加了没对上 Rust 的方法。

> 第一次真机（2026-10-22）遇到的就是第三种：符号写成了 `Java_com_nmoi_agent_Native_*`，
> 少了 `ui_`、类名写成了 `Native`。`.so` 是在包里的、`loadLibrary` 也成功，
> 所以界面显示的是「已加载，但符号对不上」，而不是「未加载」。

### V2 库真的建出来了

```bash
adb shell run-as com.nmoi.agent ls -l files/
```

**通过判据**：有 `nmoi.db`，通常还有 `nmoi.db-wal` 和 `nmoi.db-shm`（WAL 开着，D6）。
只有 `nmoi.db` 没有 `-wal` 也算过（WAL 文件在 checkpoint 后可能不在）。

**没过**（目录空 / 报 Permission denied）：

- `files/` 空 → 打开库那一步就没走到，回 V1
- `run-as: package not debuggable` → 装的是 release 包；现在的流程只有 debug 包

### V3 库不是空壳

```bash
adb exec-out run-as com.nmoi.agent cat files/nmoi.db > /tmp/device.db
sqlite3 /tmp/device.db 'PRAGMA user_version; PRAGMA journal_mode;'
```

**通过判据**：`4` 和 `wal`。
`user_version = 4` 说明 schema v4 迁移（D101 的 `conversations.model`）在真机上跑过了；
这条比"文件存在"值钱得多——文件存在也可能是个空壳。

（本机没装 `sqlite3` 就在手机上跑：`adb shell run-as com.nmoi.agent sqlite3 files/nmoi.db 'PRAGMA user_version;'`，取决于系统镜像带不带 `sqlite3`。）

### V4 列表显示的是真数据

现在 UI 还**没有新建会话的入口**，所以得先从桌面造一条再推进去：

```bash
# 在仓库里，本机跑
cargo run -q -p nmoi-cli -- init /tmp/dev.db
adb push /tmp/dev.db /data/local/tmp/dev.db
adb shell run-as com.nmoi.agent cp /data/local/tmp/dev.db files/nmoi.db
adb shell am force-stop com.nmoi.agent
```

**通过判据**：底部那行写「数据源：原生层（nmoi_ffi → storage）」，而不是红字的占位数据。

**⚠ 这一步有个我还没验过的风险**：库里有 `device_id`（打开时记的），换成桌面造出来的库，
`device_id` 就跟这台手机对不上，可能被 L3 语义校验判成 `blocked`（不是错误，D57）。
真出现这种情况，界面会写「库没通过校验（state=blocked，issues=N）」，然后给占位数据。
**这就是一次有效发现**，把 `issues` 数和界面文字贴给我，我去看 `storage::validate` 的判据。
如果不想冒这个风险，就跳过 V4，等 P7 的输入岛做出来直接在建库上写。

### V4.5 设置面探针（layer 1，设置接线第一层）

**不再用 adb 取证据**：2026-10-23 真机上一次完整冷启动（`force-stop` → 新 pid → 点开），
`logcat -d` 里 `NMoi/*` 一条都没有——`app_log/enabled` 是 `true`、`App 启动入口` 是
`onCreate` 第一句，不依赖任何东西。同一台机器更早只出现过 `E NMoi/NMoiIcon`：
**`I` 级被 ROM 吞了。** 所以证据改成另一条腿 ：

```
设置 → 调试 → 日志 → 调试日志 → 查看日志
```

（日志页分三种通道：**上下文 / 请求 / 调试**。探针写的是「调试日志」那一路；
子页能「复制日志」，直接把内容贴给我，不用数据线。）

**通过判据**：预览里出现四行

```
HH:mm:ss.SSS I/Settings: settings_list 通了：model_overrides 返回 0 条（truncated=false）
HH:mm:ss.SSS I/Settings: settings_list 通了：assistant 返回 0 条（truncated=false）
HH:mm:ss.SSS I/Settings: settings_list 通了：conversation 返回 0 条（truncated=false）
HH:mm:ss.SSS I/Settings: settings_list 通了：roles 返回 0 条（truncated=false）
```

空库四个 0 就是通过；条数与库里的实际行数对得上更好。设置页里**什么都还点不动**，
这是对的——这一层只到"能调通"。

不通过时对着这一行看：

| 日志 | 意思 | 下一步 |
|---|---|---|
| `符号对不上` | `.so` 里没有 `Java_com_nmoi_agent_ui_NativeBridge_listSettingsJson` | 旧包，或编 `.so` 时漏了 `--features jni` |
| `返回错误 bad_argument：scope 只能是 …` | Kotlin 的 `SettingsScope.wire` 与 core 的 `Scope::parse` 对不上 | 改 `NmoiFfi.kt` 的 wire 值，别在 Rust 侧放宽 |
| `返回的不是 JSON` | JNI 转手层出了问题（不是数据库） | 把那行原文贴给我 |
| **一条都没有，且日志页也是空的** | 探针根本没跑到：`ConversationRepository` 在列会话之前就退回了占位数据 | 先按 V1 把原生层连上；同时把「关于」那行状态贴我 |

日志页本身也值得先确认一次：索引页三行的「大小 / 上限」分别是多少，做点操作（切一次图标）
再回来——调试那一行变大了就说明落盘这条路是通的。上下文 / 请求两行现在会明写
「还没有产生者」，**那是对的**（HTTP 层与压缩线还没接）。

### V4.6 消息区（`load_units`）+ 新建会话 + 存入

**这一条现在只用手机就能做完**——2026-10-23 用户指出：不能要求先跑桌面 CLI 造库，
否则消息区在真机上永远验不了。为此加了两个写入口：列表页左上「＋」新建会话、
输入岛「存入」（点 = 存为用户消息，长按 = 存为助手消息）。

步骤：
1. 打开应用 → 列表页左上「＋」→ 自动进入一个新会话（标题「新会话」）；
2. 输入一句话，点「存入」→ 消息应**立刻出现在右侧**，草稿清空，下面一行小字说"已存为用户消息"；
3. 再输入一句，**长按**「存入」→ 这条应出现在左侧（助手侧）——这是"先记下来、晚点再发"的语义，
   不是 bug；
4. 返回列表：新会话排在最上面，「预览」是最后那句话；
5. 再点进去：内容还在（说明真落库了，不是内存里的）。

**通过判据**：以上五步都对；思考块/工具卡/断块这几条只有在导入真实会话之后才有内容
（`nmoi-cli` 造库那条路仍在，但不是验这一条的前提）。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 点「＋」没反应，日志里有「新建会话失败」 | 库没开成 / 没过校验 | 设置 → 调试 → 日志 看那一行；走 V2 / V3 |
| 「这一段历史还没被读出来」 | 原生层没通（`.so` / 符号 / 句柄） | 走 V1 |
| 存入后消息没出现 | 写进去了但重读没发生，或渲染分支走错 | 看 `NMoi/Chat`、`NMoi/Storage` 那几行 |

**已知没做**：压缩分界线（FFI 还没给）、往上翻页、Markdown、真正的发送（`turn_run` 未接）。

### V4.7 开源许可页（D128）——顺手验了"声明进包没有"

也可以用手机一次做完，最快的一条。这一页读的是包里真实的 `assets/notices/`，
所以它既是"看声明"的地方，也是 D106 的自检入口。

步骤：
1. 设置 → 关于 → **开源许可**；
2. 列表里应当有五行：**本项目许可**、**第三方组件声明**、`Apache-2.0.txt`、`ISC-lucide.txt`、`MIT-lobe-icons.txt`；
3. 逐条点开，滑到底，再返回（每次返回只退一层，回到许可索引）；
4. 「本项目许可」点开，正文开头应当是 `GNU GENERAL PUBLIC LICENSE` / `Version 3, 29 June 2007`；
5. 随便点开一份，右上角**复制**图标 → 标题下面出现"已复制全文" → 粘到别处，长度与内容对得上。

**通过判据**：五份都能打开、都能滚到底、没有出现红字「包里没有这份文件」；复制能用。
出现红字就等于"构建时没同步进包"，先看 CI 的「声明进包对账」那一步，而不是怀疑这一页。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 红字「包里没有这份文件：assets/notices/LICENSE」 | `syncNotices` 没跑 / 文件没进 assets | 看 CI 那一步；本地没 gradle，只能靠 CI |
| `LICENSES/` 那一节是空的 | 目录整体没进包 | 同上 |
| 点开是空白但没红字 | 文件在、读出来是空 | 属实现 bug，记下条目名 |
| 复制没反应 | 剪贴板权限/服务取不到 | 记下机型与系统版本 |

### V4.8 聊天页就是主页（D125）+ 气泡照 M3（D126）

**这一条要按顺序做**，因为第 4 步验的正是"无感建会话"。

步骤：
1. 冷启动（force-stop 后点图标）：应当**直接进聊天页**——顶栏左边 `≡`、中间标题「新会话」、右边 `＋`；
   左上角**没有返回箭头**；
2. 空态显示「还没有消息」和一行小字；底部输入区是一条圆角，右侧是圆形 `↑`（灰色 = 现在点不了）；
3. 打一句话：`↑` 变亮。**这时先别点**，打开抽屉看一眼——里面**不应该**有这条会话（还没落库）；
4. 关抽屉，点 `↑`：这句话出现在右侧（名字 + 时间 + 头像一行、下面 `primaryContainer` 气泡、圆角 16），
   **页面没有跳、没有闪**；再开抽屉，这次里面应该多出一条「新会话」——那是刚才那一下建的；
5. 打第二句，**长按** `↑`：这条出现在左侧——**没有气泡**，头像 + 「助手」+ 时间 + 正文；
   底部小字这时应变成"发送还没接：点 ↑ 只是把话存进库里…"；
6. **长按用户那条** → 进选择模式（顶部换成「取消 / 已选 1 条 / 复制 / 删除（灰）」），
   点助手那条 → 变成"已选 2 条"，点它自己 → 退回 1 条；点复制 → 提示"已复制 2 条消息"，粘到别处按时间顺序、空行分隔；
   按系统返回 → 只退出选择模式，不退页面；
7. 右上 `＋` → 回到空白会话（页面上没有消息了，标题仍是「新会话」）；
8. 抽屉里点回第 4 步那条 → 两条消息都在（真落库了，不是内存）；
9. 根页面按**系统返回** → 应用退出（不是"什么都没发生"）；
   打开抽屉后按系统返回 → 只关抽屉，不退页面；
10. 旋转屏幕一次，再退回来：会话与消息还在，顶栏与输入区不错位。

**通过判据**：10 步都对。特别看两处——第 3 步抽屉里**没有**幽灵会话；第 4 步**不闪不跳**。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 启动进的还是列表页 | `backStack` 初值没改 / 装了旧包 | 看「关于」里的版本，重装 CI 产物 |
| 第 4 步页面闪一下或跳回顶部 | 建会话时重建了页面（路由被 push 而不是原地替换） | 看 `ensureSession` 那一行 |
| 点 `↑` 没反应，小字写"建会话失败" | 库没开成 / 没过校验 | 走 V2 / V3；设置 → 调试 → 日志 |
| 气泡每条都一样宽 | 又用回了 `fillMaxWidth` | 应是最多 78% 且按内容收放 |
| 长按气泡没提示 | 剪贴板服务取不到 | 记下机型 |

**已知没做**：真发送（`nmoi_turn_run_json` 未接，所以只有"存进库"）、压缩分界线、往上翻页、
LaTeX 公式排版（`$…$` 现在等宽原样显示）、动作行里的"重新生成 / 编辑 / 删除"（灰着，等 `turn_run` / `edit` / `delete` 的 FFI）。

### V4.9 Markdown 渲染（D131）

不用改代码，用输入岛自己写一段就能验（长按 `↑` 存为助手消息，才能在左边看到渲染效果）。

粘这一段进去：

````text
# 标题
**粗体**、*斜体*、~~删除线~~、`行内代码`

- 第一项
- 第二项

1. 有序一
2. 有序二

> 引用一行

```kotlin
val x = 1
```

---

公式：$a^2 + b^2 = c^2$
````

**通过判据**：标题比正文大、粗体真的粗、列表前面有圆点/序号、引用左侧有一条竖线、
代码块有底色且等宽、分隔线是一条细线；**最后那行公式应当等宽显示、没有被排版**——
这是"还没做"，不是坏了（LaTeX 是下一层，D131）。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 全文原样显示，什么都没渲染 | 走的是纯 Text 分支（用户消息就是纯文本，属正常） | 确认这条是**助手**消息 |
| 代码块把后面的内容都吞了 | 围栏没配对上 | 记下原文，看 `parseMarkdown` 的围栏分支 |
| `**粗体**` 显示成原文 | 行内扫描没命中 | 记下那一行原文 |

### V4.10 时间、动作行、分享（D132–D134）

1. 冷启动进聊天页，存两条消息（点 `↑` / 长按 `↑`）：
   **时间必须是当下**——`2026-10-24 14:05:31` 这种。若看到 `+58782-03-11`，说明毫秒又被当成秒了；
2. 系统设置里把时间改成 12 小时制，回到应用再看：时间应当带 `上午/下午`（或 AM/PM），**不是 24 小时**；
   验完记得改回 24 小时制；
3. 每条消息下面一排四个图标：复制亮着，刷新 / 喇叭 / 翻译是灰的（长按各能浮出一句"待接什么"）；
4. 点「⋯」：七项按序出现——编辑 / 增强编辑 / 分享 / 选择消息 / 创建分支 / 删除此版本（都灰）；
   **没有**「删除所有版本」（只有存在其他版本时才该出现）；
5. 点「⋯ → 分享」：直接进选择模式并弹出底部面板——上排 `思考调用` `详细信息`（能点、选中变色），
   下排 `MD`（亮）`图片（待接）`（灰）；
6. 点 `MD`：系统分享面板弹出，随便选一个应用（比如记事本），粘出来应当是 Markdown：
   `### 我 · 时间` + 正文 + `---`；
7. 勾上 `详细信息` 再来一次：每条后面多一行 `> id=… · seq=… · source=… · tokens=…`。

**通过判据**：第 1、2、5、6 步全对；第 4 步不多出「删除所有版本」。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 年份五万多 | 时间单位仍然是秒 | 看 `stampOf` 是不是 `ofEpochMilli` |
| 时间一直是 24 小时制 | 没读系统设置 | 看 `DateFormat.is24HourFormat` |
| 分享面板不弹 | `ModalBottomSheet` 没起来 | 看 logcat 有无 crash |
| 分享出来是纯文本 | `type` 或拼装分支走错 | 看 `buildShareMarkdown` |

### V4.11 抽屉横滑手势（D136）

1. 聊天页（或设置页）**从屏幕中间**向右横划：面板跟着手指出来，遮罩逐渐变深；松手过半 → 停在展开；
2. 快速一甩（很短的距离、很快的手速）→ 也应当展开，而且**面板是顺着甩的方向滑完的**
   （有惯性动画，不是啪一下归位）；松手后 100–250ms 内到位；
3. 同一位置**斜着 45° 划**：**不该**出抽屉，列表按竖直方向滚——这条是这次的关键判据；
4. 纯竖划：列表正常滚，抽屉不动；
5. 抽屉展开后向左横划：跟手收起；**轻轻向左一带就该关**（关的阈值是 160 dp/s / 位移 15%，
   比开更轻——用户原话"展开后往左滑，加速度应该大一些"）；
6. 抽屉展开时，在抽屉里竖划会话列表：列表滚，抽屉不关；
7. 拖动中途松手（不到 35%、速度也不够）→ 回弹到原来的状态，不留在半路。

**通过判据**：第 3、4 条（不抢竖向/斜向）、第 2 条（速度能开 + 有惯性）、
第 5 条（关闭比打开更灵敏）都要对。
阈值参考：开 350 dp/s / 25%，关 160 dp/s / 15%（2026-10-24 两轮真机反馈后定的，D136）。
**已知没做**：双指手势不处理。

### V4.12 输入区按钮行（D138）

1. 输入框是整条圆角，**框内底部**一行图标：左四个（模型 / 搜索 / 思考 / 工具），右三个（＋ / 语音 / 发送↑）；
2. 点左四个任意一个 → 上方的提示行写清"待接什么"（例如模型 → "要先有供应商与密钥页（P3）"）；
3. 点 ＋ 与 语音 → 同样是提示行给原因，不是没反应；
4. 打一句话：发送键从灰变亮 → **长按**它 → 弹出两项「仅保存为助手消息」「仅保存为用户消息」；
5. 选「仅保存为助手消息」→ 这条出现在左侧（助手）；选「仅保存为用户消息」→ 出现在右侧；
6. 发送键**点一下**仍然是"存为用户消息"（`turn_run` 还没接，界面上有一行小字说明）。

**通过判据**：第 1 条的**位置与形态**要对（图标按钮在框内底部，不是框外标签行）；第 4、5 条能出菜单且两条都能存。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 看到的是文字标签（`模型 · 未配置`）而不是图标 | 装到了旧包（D138 走偏的那一版） | 重新装当前工作区这份 |
| 长按发送没菜单 | `DropdownMenu` 没锚上 / 被手势吃掉 | 看 logcat |

### V4.13 消息头镜像与抽屉新结构（D139）

**消息头**
1. 存一条用户消息：**名字在上、时间在下**，两行都在头像左侧，头像贴右边缘；
2. 长按 `↑` 存一条助手消息：头像在左，名字/时间两行在右；
3. 左右两边距**目测一样**（都是 12dp），头像大小一致（24dp）——一边宽一边窄就是要报的问题。

**抽屉**
4. 输入框点开、键盘弹起，然后横滑出抽屉 → **键盘应当自动收起**；
5. 抽屉顶部：用户头像 + 名字（上排）+ 问候语（下排）。按系统时间对一下文案：
   `早上好🤡` / `中午好🤡` / `下午好🤡` / `晚上好🤡` / `夜猫子好😋`（改系统时间验最快，文案一字不差）；
6. 搜索框：左侧是"细长尾巴的搜索"图标、文字 `搜索当前助手`；**在框上向右滑** → 变成数据库图标 + `搜索全部会话`；
   向左滑回。点框本身会在下方提示行写"搜索还没接"；
7. 底部：助手头像（点一下 → 提示"助手设置待接"）、名字 + 箭头（点开 → 助手列表菜单）、右侧**齿轮 → 进设置页**（这条是真能用）。

**通过判据**：第 1、2、4、6、7 条；第 3 条要**两边对称**。

失败对照：

| 现象 | 意思 | 下一步 |
|---|---|---|
| 键盘不收 | 原生 EditText 没拿到真引用 | 看 `editTextRef` 那条链路 |
| 右滑不切换 | 手势被列表或 clickable 吃掉 | 看 `SearchPill` 的 pointerInput 顺序 |
| 齿轮长得像参考图那个 | 用的是 Lucide `settings`，应为自绘 `GearSelf` | 看 ActionIcons |

### V5.5 图标切换（设置 → 外观）

八格里逐个点一遍。

**通过判据**：桌面图标跟着变；杀进程重开，设置里高亮的那个与桌面上的图标是同一个。

- 部分厂商桌面不会立刻刷新，可能要重启桌面甚至重启手机，这是桌面自己的缓存。
- 点了没反应且界面出现一行红字「切换没生效」→ 看 `adb logcat -d | grep NMoiIcon`。
  最常见的原因是 alias 名与包里的类名对不上（`Component class ... does not exist`）。
- 桌面上的图标变成两个 → 有别的 alias 没被关掉；再进一次设置页会自动清干净
  （`apply` 会把其余全部关一遍）。

### V5 重开与旋转

- 旋转屏幕（横竖各一次）
- 从最近任务里划掉，重新打开

**通过判据**：列表还在、时间不变、没有闪退；`adb logcat -d | grep -i "panic"` 没有输出。
（旋转会重建 Activity——我们没声明 `configChanges`，这是有意的，见 D111。）

### V6 数据可靠性（暂时不做）

想验 `storage::recover`（启动收口）得在**写库的过程中**杀进程，而现在没有写入口。
等输入岛能发一句话了再回来补这一条，别现在硬造。

## 4. 证据怎么给我

按这个顺序贴，别写"都通过了"：

1. **每条的命令 + 原始输出**（文本，不要截图命令输出）
2. `adb logcat -d > logcat.txt`，先按关键词过滤再贴：
   ```bash
   grep -iE "nmoi|UnsatisfiedLink|SQLite|AndroidRuntime" logcat.txt | tail -80
   ```
3. **一张截图**：标题下方版本行 + 列表区 + 底部数据源那行，一张就够
4. 设备信息：`adb shell getprop ro.product.cpu.abi`、`adb shell getprop ro.build.version.sdk`

打码：序列号、用户名路径、任何 token。路径里的 `/data/user/0/com.nmoi.agent/...` 不用码。

## 5. 现象 → 先看哪里（省时间用）

| 现象 | 第一手线索 | 大概原因 |
|---|---|---|
| 装了打不开，直接闪退 | `adb logcat -d \| grep AndroidRuntime` | Kotlin 侧异常；`System.loadLibrary` 失败不该崩（已 try/catch），所以多半是别处 |
| 界面正常但没有版本行 | V1 的 logcat 三类 | `.so` 或符号名 |
| 版本行正常，列表是红字占位 | 底部那行括号里的原因 | 开库失败（路径/权限）、或 blocked |
| 库建出来了，列表是空的 | V3 的 `user_version` | 库真是空的（新建的库本来就没有会话） |
| 列表空白、无报错 | `adb logcat -d \| grep -iE "sqlite|nmoi"` | SQL 报错被兜成了空数组 |
| 设置里点图标就闪退 | `grep -A10 "FATAL EXCEPTION"`，看 `Component class … does not exist` | alias 类名对不上：类名按 **namespace**（`com.nmoi.agent`）解析，不是 applicationId（`com.nmoi.agent`） |

## 6. 不需要实机的验证（已经跑过，记录在这）

```bash
cargo test -q -p nmoi-core --lib --locked        # 492
cargo test -q -p nmoi-storage --locked           # 28 + 5/17/9
cargo test -q -p nmoi-ffi --lib --locked         # 24
./scripts/ffi_smoke.sh                           # C 侧 277
cargo fmt --all -- --check
cargo clippy -q --workspace --all-targets --locked
cargo check -q -p nmoi-core --no-default-features
```

Android 侧只有 CI 能跑（本机没有 SDK / NDK / JDK 之外的任何东西）：

```bash
gradle -p android :app:assembleDebug            # 需要 JDK 17 + Gradle 8.7
cargo ndk -t arm64-v8a -p 26 -o android/app/src/main/jniLibs \
  build --release -p nmoi-ffi --features jni    # 需要 NDK + rustup target
```

`--features jni` 是必须的：JNI 那层默认不编（`jni` / `jni-sys` 的 MSRV ≥ 1.76，
工作区 MSRV 是 1.75，见 D110/D112）。忘了这个参数会得到一个**没有 JNI 符号的 .so**，
装上去表现就是 V1 不过。
### V5.5 图标切换（设置 → 外观）

八格里逐个点一遍。

**通过判据**：桌面图标跟着变；杀进程重开，设置里高亮的那个与桌面上的图标是同一个。

- 部分厂商桌面不会立刻刷新，可能要重启桌面甚至重启手机，这是桌面自己的缓存。
- 点了没反应且界面出现一行红字「切换没生效」→ 看 `adb logcat -d | grep NMoiIcon`。
  最常见的原因是 alias 名与包里的类名对不上（`Component class ... does not exist`）。
- 桌面上的图标变成两个 → 有别的 alias 没被关掉；再进一次设置页会自动清干净
  （`apply` 会把其余全部关一遍）。

### V5 重开与旋转

- 旋转屏幕（横竖各一次）
- 从最近任务里划掉，重新打开

**通过判据**：列表还在、时间不变、没有闪退；`adb logcat -d | grep -i "panic"` 没有输出。
（旋转会重建 Activity——我们没声明 `configChanges`，这是有意的，见 D111。）

### V6 数据可靠性（暂时不做）

想验 `storage::recover`（启动收口）得在**写库的过程中**杀进程，而现在没有写入口。
等输入岛能发一句话了再回来补这一条，别现在硬造。

## 4. 证据怎么给我

按这个顺序贴，别写"都通过了"：

1. **每条的命令 + 原始输出**（文本，不要截图命令输出）
2. `adb logcat -d > logcat.txt`，先按关键词过滤再贴：
   ```bash
   grep -iE "nmoi|UnsatisfiedLink|SQLite|AndroidRuntime" logcat.txt | tail -80
   ```
3. **一张截图**：标题下方版本行 + 列表区 + 底部数据源那行，一张就够
4. 设备信息：`adb shell getprop ro.product.cpu.abi`、`adb shell getprop ro.build.version.sdk`

打码：序列号、用户名路径、任何 token。路径里的 `/data/user/0/com.nmoi.agent/...` 不用码。

## 5. 现象 → 先看哪里（省时间用）

| 现象 | 第一手线索 | 大概原因 |
|---|---|---|
| 装了打不开，直接闪退 | `adb logcat -d \| grep AndroidRuntime` | Kotlin 侧异常；`System.loadLibrary` 失败不该崩（已 try/catch），所以多半是别处 |
| 界面正常但没有版本行 | V1 的 logcat 三类 | `.so` 或符号名 |
| 版本行正常，列表是红字占位 | 底部那行括号里的原因 | 开库失败（路径/权限）、或 blocked |
| 库建出来了，列表是空的 | V3 的 `user_version` | 库真是空的（新建的库本来就没有会话） |
| 列表空白、无报错 | `adb logcat -d \| grep -iE "sqlite|nmoi"` | SQL 报错被兜成了空数组 |
| 设置里点图标就闪退 | `grep -A10 "FATAL EXCEPTION"`，看 `Component class … does not exist` | alias 类名对不上：类名按 **namespace**（`com.nmoi.agent`）解析，不是 applicationId（`com.nmoi.agent`） |

## 6. 不需要实机的验证（已经跑过，记录在这）

```bash
cargo test -q -p nmoi-core --lib --locked        # 492
cargo test -q -p nmoi-storage --locked           # 28 + 5/17/9
cargo test -q -p nmoi-ffi --lib --locked         # 24
./scripts/ffi_smoke.sh                           # C 侧 277
cargo fmt --all -- --check
cargo clippy -q --workspace --all-targets --locked
cargo check -q -p nmoi-core --no-default-features
```

Android 侧只有 CI 能跑（本机没有 SDK / NDK / JDK 之外的任何东西）：

```bash
gradle -p android :app:assembleDebug            # 需要 JDK 17 + Gradle 8.7
cargo ndk -t arm64-v8a -p 26 -o android/app/src/main/jniLibs \
  build --release -p nmoi-ffi --features jni    # 需要 NDK + rustup target
```

`--features jni` 是必须的：JNI 那层默认不编（`jni` / `jni-sys` 的 MSRV ≥ 1.76，
工作区 MSRV 是 1.75，见 D110/D112）。忘了这个参数会得到一个**没有 JNI 符号的 .so**，
装上去表现就是 V1 不过。
