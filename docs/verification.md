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
