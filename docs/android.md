# Android 骨架（P6.7）

这一层的定位：**证明工具链能出包**，不是证明功能。第一屏画的是写死的占位数据，
真数据要等 `crates/jni` + `cargo-ndk` 把 Rust 核心接进来。

## 1. 目录

```
android/
  settings.gradle.kts        仓库只有一个 Gradle 工程；依赖源只在这里声明
  build.gradle.kts           三个插件版本（唯一真源，模块里不写版本号）
  gradle.properties          jvmargs / AndroidX / nonTransitiveRClass
  app/
    build.gradle.kts         applicationId、SDK 级别、Compose、依赖
    src/main/AndroidManifest.xml
    src/main/res/values/     strings / colors / themes
    src/main/kotlin/com/nmoi/agent/
      MainActivity.kt
      ui/ConversationListScreen.kt
      ui/PlaceholderData.kt
      ui/theme/Theme.kt
```

`namespace = com.nmoi.agent`（Kotlin 包名），`applicationId = com.nmoi.agent`（D102）——
两者可以不同，而且**故意**不同：包名大小写敏感，装机的身份是大写那个。

## 2. 版本钉子（D110）

| 件 | 版本 | 约束来源 |
|---|---|---|
| Gradle | **8.7** | AGP 8.5.2 要求 ≥ 8.7；KGP 2.0.21 要求 ≤ 8.8。交集只剩 8.7 / 8.8 |
| AGP | **8.5.2** | 要求 JDK 17；配合 KGP 2.0.21 的上限（8.5）刚好压线 |
| Kotlin (KGP) | **2.0.21** | Kotlin 2.0 起 Compose 编译器是独立插件 |
| Compose 编译器插件 | **2.0.21** | **必须等于 Kotlin 版本**，不是 Compose 的版本 |
| Compose BOM | 2024.09.00 | 库版本全由它管 |
| JDK | 17 | AGP 8.x 硬要求，跟 `jvmTarget` 无关 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 | CI runner 上装着的 platform 决定 compileSdk |

三个数互相卡：动 Gradle 要先看 AGP 与 KGP 两条区间还有没有交集，动 Kotlin 要看 Compose
编译器跟着变、还要看 KGP 支持的 AGP 上限。**别只升一个。**

版本号**只在 `android/build.gradle.kts` 里出现一次**，模块里写死版本是"两台机器解析出两份依赖"的起点。

## 3. CI 怎么跑

`.github/workflows/ci.yml` 的 `android` job：

1. `setup-java` 钉 JDK 17（AGP 不要别的）
2. `gradle/actions/setup-gradle@v4` 装 Gradle 8.7 —— **仓库里没有 `gradle-wrapper.jar`**，
   这是有意的：那个 jar 是二进制，本地没有 JDK 就生成不了，硬塞一个来源不明的进仓库更糟。
   等哪台有 JDK 的机器跑一次 `gradle wrapper --gradle-version 8.7` 再补。
3. `dtolnay/rust-toolchain@stable` + `targets: aarch64-linux-android`
4. `cargo install cargo-ndk`，然后编原生层：
   `cargo ndk -t arm64-v8a -p 26 -o android/app/src/main/jniLibs build --release -p nmoi-ffi --features jni`
   —— **只编 arm64-v8a**，别的 ABI 等真机反馈再补；`--features jni` 忘了就等于编了个没有 JNI 符号的 `.so`。
4.5 `./scripts/check_jni_symbols.sh <编出来的 .so>`：把 Kotlin 的 `external fun` 与 `.so` 导出的
   `Java_*` 符号对账。**这道闸是真机上踩过一次才加的**（第一版符号名漏了 `ui_`、类名写成 `Native`）：
   编译能过、装包能过，只有点了按钮才报 `No implementation found`。
5. **环境自述**：打印 `ANDROID_HOME`、已装的 `platforms/` 与 `build-tools/`、NDK 路径，再 `gradle --version`。
   失败时不用猜是哪一件缺了。
6. `gradle -p android :app:assembleDebug`
7. 清点产物 + 上传 `nmoi-debug-apk` 工件（14 天）

**只有 debug**。debug 签名由构建机自己生成，不需要任何 secret；release 签名要 4 个
environment secret（`docs/signing.md`），现在一个都没有，所以不出 release，也不假装能出。

## 4. 现在能做什么

- 出一份能装的 debug APK（`applicationId = com.nmoi.agent`）
- 原生层**编得出来、也打得进包**：`libnmoi_ffi.so`（arm64-v8a，**未 strip**，11,290,352 字节）
  通过 JNI 转手调 C ABI（D112），八个符号：版本 / 开库 / 关库 / 列会话 + 设置面四个
  （`putSettingsJson` / `getSettingsJson` / `listSettingsJson` / `deleteSettingsJson`）。
  **包含设置面之后的新体积还没量过**：本机打不了 Android 包，等 CI 出包再填，别拿旧数字当准。
- 会话列表显示**真数据**（库空就显示空状态），退回去时会在界面上写清"这是占位数据 + 原因"
- **应用内日志**（设置 → 调试 → 日志）：**三种通道**——上下文 `context` / 请求 `request` /
  调试 `debug`，各自一个文件（`files/logs/*.log`）、各自的开关、各自的上限。
  `NLog.i/w/e` 进调试通道，另有 `NLog.context()` / `NLog.request()` 两个入口。
  上限默认 **8 MB**（可选 2 / 4 / 8 / 16 / 32），超了保留最新一半。
  索引页三行（名字 + 说明 + 大小/上限 + 开/关，右上角齿轮）；齿轮页管三种开关与上限；
  每个通道子页四个动作——查看（尾部 64 KB）/ 复制（尾部 256 KB）/ 导出（SAF 整份）/ 清除（二次确认）。
  **上下文与请求两路现在还没有产生者**，界面明写"还没有产生者：等 P4 / P7"，不拿空列表装正常。
  这一页是因为真机上 `I` 级 logcat 被 ROM 吞掉才做的：**证据不能只放在一根别人能关掉的线上**。

## 4.5 第三方声明怎么进包（D106）

`app/build.gradle.kts` 里有个 `syncNotices` 任务：把仓库根的 `THIRD-PARTY-NOTICES.md`
和 `LICENSES/` 同步到 `build/generated/notices`，再把那个目录挂成 `assets` 源，
`preBuild` 依赖它。于是**每一次 assemble 都会带**，不需要谁记得手动拷。

两条刻意的选择：

- **不提交副本**。提交进 `android/app/src/main/assets/` 的副本迟早和源头漂移，
  到时候"声明里写着"和"包里真正带的"是两份东西。
- **CI 有一道闸**：`unzip -l` 列出 APK，必须能看到
  `assets/notices/THIRD-PARTY-NOTICES.md`、`assets/notices/LICENSES/ISC-lucide.txt`、
  `assets/notices/LICENSES/MIT-lobe-icons.txt`，缺一个就失败。
  "签名之前"这件事在 Gradle 里天然成立（assets 在合并阶段进包，签名只是对最终 zip 盖章），
  真正会出事的是"忘了放"和"放了个旧的"——前者由 srcDir 保证，后者由不提交副本保证。

## 5. 现在不能做什么（写清楚，免得被当成已完成）

- **一次都没在真机上跑过**：装包、加载 `.so`、建库、列表，全是纸面。要跑的东西在
  `docs/verification.md`，按 V0→V5 走。
- **启动器图标**：4 设计 × 黑/白底共 8 套，设置里可切，默认 4 号黑底（D114、`docs/ui/icons/app/README.md`）。
  真机上**没验过**切换效果：manifest 的 alias 开关是标准做法，但各家桌面刷新快慢不一样。
- **没有输入岛**：发不出消息，所以库里永远没有会话；想验列表得先用桌面 CLI 造库推进去。
- **设置面只有绑定层，没有界面**：`NmoiFfi`（Kotlin）→ `NativeBridge` → `nmoi_settings_*` 四个动词
  已经通了（CI 里 `check_jni_symbols.sh` 对账 8 个符号），但设置页五节仍是灰行 ——
  界面接线是下一层（`SettingsRepository`）。真机上从「设置 → 调试 → 日志」看通没通（V4.5），不依赖 adb。
- **没有图标包**：`data/icon-packs/original/` 还没进 `assets/`。
- **没有签名**：没有 keystore 材料，release 出不来。
- **只有 arm64-v8a**：armv7 / x86_64 没编。
- **没有浅色主题**：正式主题要跟图标包的 `backing: surface` 规则一起调（D105）。
- **`.so` 没 strip**：AGP 报 `Unable to strip the following libraries … libnmoi_ffi.so`（它没找到能用的
  strip 工具，就原样打进去了）。11.3MB 里大半是符号；strip 之后应该在 3–4MB 量级。
  在意体积时再处理，先把功能跑通。
- 本机（PRoot / aarch64 / 无 Android SDK、无 rustup）**编不了 JNI 层**——`jni` 的 MSRV ≥ 1.76，
  而本机 cargo 是 1.75。这一层只有 CI 能验，代价是没有本地迭代。

## 6. 下一步

1. **真机验证一次**（`docs/verification.md` V0→V5）。在这之前，"能跑"这三个字不能用。
2. 输入岛（`AndroidView` 包原生 `EditText`）→ 然后才有 `nmoi_turn_run_json` 可接，
   库里也才会有真会话。
3. 签名：4 个 secret 到位后再开 release 那条路。
4. 图标包进 `assets/`（`assets/icon-packs/original/`），跟 `docs/packaging.md` 对齐。
5. 补别的 ABI（armv7 / x86_64）——等真机反馈需要再说，不提前编。
