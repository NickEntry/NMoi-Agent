# 应用图标（启动器）

四个设计 × 黑/白两种底色 = **8 套**，在「设置 → 外观」里切换，默认 **4 号（许愿终端 · 星群）+ 黑底**。

## 母版是脚本，不是手抄的 XML

```bash
python3 scripts/gen_app_icons.py     # 重新生成 8 个前景矢量 + 8 个 adaptive icon + Kotlin 目录
./scripts/check_app_icon_aliases.sh  # 对账：manifest 的 alias 与生成结果一致、默认恰好开一个
```

为什么必须生成，不能手写两份：

- **VectorDrawable 不支持虚线**（没有 `stroke-dasharray`）。2 号的三层弧在 SVG 里是虚线圆，
  进矢量必须算成真正的圆弧路径（`A` 命令 + sweep），这件事不适合手算。
- **白底不是"换个背景色"**：amber 在白色上几乎没有对比，暗晕那层也要翻成白色。
  所以每个设计出两份前景，配色两套（见下表）。手抄一定会漂移。

## 两个色板

| 用途 | 黑底 | 白底 |
|---|---|---|
| 底色 | `#14121C`（纯色） | 渐变：`#FFFFFF` → `#FBFCFF` → `#F0F1F6` → `#E4E5EC`（中心纯白，外圈淡银） |
| 紫 | `#7C5CFF` | `#4B2FD6` |
| 青 | `#2EC5E0` | `#0E7F99` |
| 粉 | `#FF5FA2` | `#D62F72` |
| 琥珀 | `#FFC24B` | `#C97A00` |
| 星核渐变 | `#FFE3A3 → #FFC24B → #FF5FA2` | `#FFC46B → #FFA51F → #E2376F` |
| 暗晕（veil） | `#14121C` 由 98% 到 0 | `#FFFFFF` 由 98% 到 0 |

白底那套不是"调亮"，是**压深**：原色在白纸上一律偏浮，尤其琥珀和青色。

白底的**背景层**不是纯色，而是径向渐变（`res/drawable/bg_appicon_light.xml`）：
中心纯白、往外极淡地走到 `#E4E5EC`。渐变的银感只当材质用，强度刻意压住——
再重一点就会盖过前景图形，到时候 48px 上先看见的是"一块银灰"，不是那个图形。
`gradientRadius` 取 76：中心到圆角是 54√2 ≈ 76.4，取小了会在角上留一圈硬边。

因此白底的 `adaptive-icon` 背景指向 `@drawable/bg_appicon_light` 而不是 `@color/...`——
`<background>` 想放渐变，只能给 drawable。

## 尺寸与安全区

`adaptive-icon`：**108dp 画布，中间 72dp 参与裁切，66dp 安全区**。
图形按 `SCALE = 0.145` 缩进 108dp 画布（512 坐标 → 约 74dp），实际图形落在 49dp 左右，
任何厂商遮罩（圆/方/水滴/方形圆角）都不会切到内容。

没做 monochrome 层（Android 13 的主题图标）：要做得另出一份纯单色前景，
而这一套的价值就在"多色混合"，所以先不做；真要做时在脚本里加一层输出即可。

## 四套设计

| # | 名字 | 一句话 |
|---|---|---|
| 2 | 许愿星 · 环轨 | 中心一颗星，外面三层同心弧（100°/92°/84°）：星是你的愿望，环是它替你管的边界 |
| 4 | 许愿终端 · 星群 | `>` 提示符，右下落一颗大星，右上撒一串小星：说一句，它自己往下做（**默认**） |
| 5 | 无限环 | 无限环上落一颗星：上下文这件事不必想 |
| 6 | 代码星屑 | 上面几行彩色代码片段，右下角一颗许愿星 |

## 类名按 namespace，不是 applicationId

这是 2026-10-23 真机上崩过一次的地方，值得单独写。

manifest 里 alias 写成相对名 `.icon.WishingStarDark` 时，它对着**namespace**
（`com.nmoi.agent`）解析成 `com.nmoi.agent.icon.WishingStarDark`；
而 `aliasFor()` 当时照着 applicationId（`com.NMoi.agent`）拼，得到
`com.NMoi.agent.icon.WishingStarDark`。D102 里这两个名字**故意**大小写不同，
于是运行期：

```
java.lang.IllegalArgumentException: Component class com.NMoi.agent.icon.WishingStarDark does not exist in com.NMoi.agent
```

对策两层：

1. **manifest 与 Kotlin 都用全名**，同一个常量 `NAMESPACE` 生成，不依赖相对名怎么解析。
2. `AppIconSwitcher.apply` 在调用 `setComponentEnabledSetting` 前先
   `getActivityInfo` 确认组件存在；不存在就记一条 `NMoiIcon` 日志并**保持原状**。
   名字写错不该让应用死掉——它是在点击回调里被调用的。

`scripts/check_app_icon_aliases.sh` 会比对 manifest 前缀与 Kotlin 前缀：
故意把 Kotlin 侧改回 `com.NMoi.agent` 时，它当场报出两边前缀不同并退出非 0（实测过）。

> 上面这段保留了原来的大小写，因为那段错误信息只在这个组合下才会出现。
> 2026-10-23 之后 `applicationId` 也改成了全小写（D116），namespace 与 applicationId
> 已经一致，同一类失误在结构上不会再发生；但"先查组件存在"这道运行时护栏保留——
> 它防的不只是包名，还有改名、删 alias、渠道配置这些别的原因。

## 切换机制（D114）

Android 没有"运行期换图标"的 API，唯一干净的路子是**多个 `activity-alias`**：

- `MainActivity` 自己**不带** LAUNCHER（带了桌面上会出现两个图标），只做 `targetActivity`。
- 8 个 alias 各带自己的 `android:icon`，manifest 里默认只开 `TerminalStarsDark`。
- 切换走 `PackageManager.setComponentEnabledSetting`：**先开新的、再关掉其余全部**，
  一律 `DONT_KILL_APP`。顺序反了会有"图标凭空消失"的一瞬；只关"上一个"会留下两个图标
  （状态可能来自上次崩在中间）。
- 落盘用 SharedPreferences（键 `design` / `plate`）。设置面板接上 settings 表（D86）之后整体搬过去，键名不变。
- 预览用的是**进包的那层前景矢量**叠在底色上，不是另画一套缩略图——否则"预览好看、装上不是那个样"。
