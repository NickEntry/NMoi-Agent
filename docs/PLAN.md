# NMoi-Agent 实施计划（含进度）

> 本文件是主计划。**每完成一项就更新那一行的「进度」列**，不删历史、不空转。
> 进度写法：`未开始` → `设计已定` → `已实现 core` → `已实现 storage` → `已接 UI` → `已实测`。
> 每次开工先读本表，收工前把变动写回去。

- 上游设计：`docs/design/context-modes-design-v1.1.md`、`docs/design/platform-client-plan.md`、`docs/design/context-experiments-report.md`
- 决策记录：`docs/decisions.md`（D1–D9 来自计划文档，D10 起是本仓库新增）
- 最近更新：2026-10-27（**一步到位那一轮**：0.14j 供应商多套 + 模型表（D157）、
  0.14k `turn_run` 接进聊天页 —— `↑` 真的发请求、生成中同一按钮变停止。两件都是
  **已实现、待真机验证**（Kotlin 只能在 CI 里编）；同轮：文档同步 `docs/decisions.md` D157、
  `docs/ui/structure.md` 供应商三页、`docs/verification.md` V9）
- 上一轮：2026-10-24（0.14g **已实现、待真机验证**：助手去气泡 + 头像/名字/时间 + 动作行、长按选择消息、自研 Markdown 渲染，D129–D131；同轮 0.14e/0.14f：聊天页升主页 D125、气泡照 M3 D126；
  同轮：M3 的许可义务（`LICENSES/Apache-2.0.txt` + 声明 §3 + CI 闸，D127）、
  应用内「关于 → 开源许可」页 + 自己的 GPL 全文进包 + 闸改精确比对（D128））
- 同轮（2026-10-26）：**压缩后窗口的最小形状** —— 人格/文风连续性怎么保（D143、§3.4.2）；结构 + 两模型行为都已实测（口癖率 / 思考要点命中 / 思考膨胀倍数）；另量了四本真实世界书的占用预算（D144）；侧面验证方法定在“文件口吻率”（D145）；**core 已落地**：`choose_cut_for_window_shape` + `compress::excerpt`，504 项测试；**目标改写**：让 AI 觉得此前对话是它自己说的（归属感，不是措辞相似）；返回键分层（D146）
- 上一轮：2026-10-22（0.15：压缩失败阶梯 + 提示词全部可自定义；**设计已定未实现**，见 docs/compression.md、D115）
- 同轮：0.14（应用图标 4 款 × 黑白底、设置里可切、默认 4 号黑底；D114）
- 同轮：0.13（JNI 桥接上原生层，列表读真库；D112；真机清单 docs/verification.md）
- 上一轮：2026-10-22（0.12：Android 骨架 + CI 出 debug APK；D110 / D111）
- 上一轮：2026-10-21（0.8：自动重试 —— `retry` 模块 + `complete_retrying`，D99）
- 同轮：2026-10-21（0.7：双线压缩接线 —— `Threshold` 双单位、`decide` 动作表、造请求前拒绝、生成中截断，D97）
- 同轮：0.6 token 估算 L1＝tiktoken-rs 双词表 + L2 校准，D94–D96；§3.22 换成实测数字
- 上一轮：2026-10-21（D93 世界书格式定为酒馆格式、导入兼容 kelivo / RikkaHub；只记录未动工）
- 上一轮：2026-10-21（断块修复 —— 断在半路的工具调用/思考块在**请求构造时**翻译成文字块，库里原样保留；UI 形状按截图冻结，加号面板与上下文窗口面板逐项对上已有能力）
- 上一轮更新：2026-10-21（P4 压缩 / P10 数据可靠性 / P7 工具层 / P6 编排层；D44–D52）

---

## 0. 总览

| # | 阶段 | 状态 | 进度 | 证据 / 备注 |
|---|---|---|---|---|
| S0 | 地基：核心模型 + 存储 + 事件流 | 进行中 | core/storage/ffi/cli 四个 crate 可 `cargo check --all-targets` 通过；存储往返、分界线、编辑审计、备份校验的测试已写好，**尚未在 CI 跑过** | `crates/*`；`cargo check` 本地绿 |
| S0.5 | 工作区文件工具（不依赖容器） | 进行中 | `core::workspace` 已实现读/写/编辑/看目录/查找/搜索 + 分级截断 + `force_full`；测试已写，未跑 | `crates/core/src/workspace.rs` |
| P1 | 锁定 / 分界线 / 块级编辑（模型层） | 进行中 | core + storage 已落地（`locked`、`dividers` 表、`edits` 表）；UI 未做 | `core/src/edit.rs`、`core/src/divider.rs` |
| P2 | Provider / 模型配置层 | 已实现 core | `core::provider` + `core::model`（归一名、家族兜底、来源可解释）；**模型快照已接入**（1729 条家族-版本，1.2MB） | `crates/core/src/provider.rs`、`model.rs`、`data/models.snapshot.json` |
| P2.1 | 传输层（明文 HTTP/1.1 + 分块/定长/关闭定界） | 已实现 core | `core::transport`：`Transport` trait + `PlainHttpTransport`（std 手写）。`https` 明确报 `Unsupported`（TLS 见 D38 的待定项）；有本机 socket 单测（CI 可跑，不需要外网） | `crates/core/src/transport.rs`、D38 |
| P2.2 | SSE 帧解析 | 已实现 core | `core::sse`：空行断帧 / 多行 data 拼接 / 注释行 / EOF 前无空行不吞帧 / 单行上限 1 MiB；有"逐字节切碎"的测试 | `crates/core/src/sse.rs` |
| P2.3 | 四端口流式适配 + usage 归一 | 已实现 core | `core::stream`：`StreamEvent` 统一形状；OpenAI 兼容 / Responses / Anthropic / Gemini；工具参数分片、`thought:true`、`[DONE]`、流内 error、无终止信号=Interrupted；usage 口径归一（D39） | `crates/core/src/stream.rs`、`docs/provider-runtime.md` |
| P2.4 | 请求构造 / 错误映射 / 重试 | 已实现 core | `core::runtime`：四端口请求体、屏蔽顺序沿用 D33、`max_tokens` 字段按端口分、鉴权头按端口分、`OverflowHint` 两档线索、429/5xx 重试（只在零字节阶段） | `crates/core/src/runtime.rs`、D40 |
| P2.4a | 真机冒烟 | 已实测 | `cargo run -p nmoi-core --example provider_smoke`（2026-10-20，`relay-b/DeepSeek-V4.1-Flash`）：正文/思考/usage/finish/done 全通，引擎落库 2 个原子单元 | `docs/provider-runtime.md` §1 |
| P2.6 | 非流式回复 + 工具消息接线 | 已实现 core + 已实测 | `ChatClient::complete()` + `stream::parse_complete`（四端口解析）；`Completion { text, reasoning, tool_calls, finish, usage }`。工具消息四端口映射（D41）；工具声明三端口已做、Gemini 报 `Unsupported`。真机非流式实测通过 | `core::runtime`、`core::stream`、D41 |
| P2.5 | 模型元数据抓取流水线 | 已跑通 | `scripts/fetch_models.py`（三源合并、只存事实字段、--offline 可复现）；来源与许可见 `docs/model-data.md` | `scripts/fetch_models.py` |
| P2.7 | 逐参数覆盖（默认/已修改 + 恢复） | 已实现 core | `core::config`：`OverrideState`、`ModelOverrides`、跟随规则、`ThinkingChoice`；UI 未接（P6） | `crates/core/src/config.rs` |
| P2.9 | 思考滑块语义（关闭显式 / 自动不发 / 滑块最少两格） | 已实现 core | `ThinkingChoice::slider`；关闭对 Anthropic 预算式走 `type: disabled`，其余走显式 `none` | `crates/core/src/config.rs` |
| P2.8 | 思考档位数据（每模型支持哪些强度） | 已跑通 | 脚本从 `reasoning_options` 提取，**厂商数据优先**（DeepSeek 实测 low/high/max） | `data/models.snapshot.json` |
| P3 | 五类模型角色（标题/压缩/翻译/视觉/建议） | 已实现 core | `core::roles`：角色配置与默认值、内置提示词、标题清洗、失败静默降级、单独计量账本（不进会话预算）；视觉角色按"未显式指定则置灰"实现门控 | `crates/core/src/roles.rs`、D42 |
| P3.1 | 视觉图片通道（图片 → 文字描述） | 未开始 | 需要：`SourceTag::VisionBridge`（**已加**）、四种端口的图片内容块、工作区的二进制读、尺寸/格式限制。当前视觉角色只有门控，发不出图 | D22、D42 |
| P3.3 | **视觉覆盖**（开着就由视觉模型看，主模型只收文字） | 契约已定 + 决策函数已实现 + FFI 可读默认提示词 | `core::vision`：`VisionOverride` / `VisionPlan` / `Task::request_text`；两套默认提示词（`data/vision.image.md` 逐字收用户给的 11888 字节、`data/vision.video.md` 另写），`nmoi_vision_defaults_json` 读给 UI。缺：附件库、真把媒体发出去（等 P3.4 的内容块） | §3.14、D62、D63 |
| P3.4 | 多模态内容块 + Gemini 全模态 | 设计已定 + 探针可用 | 四端口能力矩阵见 §3.15；`scripts/modality_probe.sh` 已写并在本机跑通 text/image 两行（结果与"为什么不能读成支持"记在 `docs/model-data.md`）。Gemini Files API **未实测** | §3.15、D64 |
| P6.3 | 聊天页 / 侧栏布局（形状冻结） | 设计已定 | §3.12：三层消息页 + 抽屉，每一块标出数据来自哪个调用。UI 本体等平台工具链 | §3.12 |
| P4.1 | **断块修复**（不完整调用/思考 → 文字块） | 已实现 core | `core::repair`：`diagnose` / `BrokenBlock::declaration` / `normalize`；声明行 = ID+块类+原因、ID 稳定、信息保留。接在 `run_session_turn` 汇总历史那一步，**不动库** | §3.17、D66、D67 |
| P6.4 | 上下文窗口面板（分段用量 + 三个操作） | 已实现 core + FFI | `budget::Segment`（八行，顺序固定、含 0 行）/ `breakdown_of_units` / `nmoi_context_breakdown_json`（库里算历史与工具，`extra` 由调用方给系统提示词/技能/世界书/工作区/草稿；未知字段报错，不静默双算）；core 7 项测试 + C 侧 19 项检查。面板 UI 本体仍等平台工具链 | §3.12、`crates/core/src/budget.rs`、`crates/ffi/src/storage.rs` |
| P6.5 | 字体与图标（内置 OFL / 导入变单槽 / 系统字体） | 设计已定 + 图标已画 | `docs/fonts.md`：内置 Noto Sans SC + Inter + JetBrains Mono + 得意黑（全部 OFL-1.1），导入字体自动改名为 `Imported Font` 且换走即删；Android 只给系统默认，Windows DirectWrite + 搜索，Linux fontconfig。图标：`docs/ui/icons/thinking-*.svg` 七个（自绘，D69）+ `actions/` 31 个（Lucide ISC）+ `brands/` 14 个（Lobe Icons MIT）+ 规范 `docs/ui/icons/README.md`（D103/D104）；预览工具 `scripts/icon_preview.py`（支持 dark/light 两档）；运行时用 PNG 图标包（原版不可删、可导入导出、正则规则可自定义，D105），供应商 `providers/` 与模型 `models/` 两套、彩色标；第三方声明随每个产物走（D106，`THIRD-PARTY-NOTICES.md` + `LICENSES/`）；规则匹配与排序已在 `core::icons` 落地（20 项测试，D107）：优先级 0–1000、有效字面量长度、全序落 `id`、供应商按 kind→host→name 三字段查 | `docs/fonts.md`、`docs/ui/icons/README.md`、`docs/ui/icon-packs.md`、`docs/packaging.md`、§3.18、D68、D69、D103–D106 |
| P6.7 | Android 骨架 + CI 出 debug APK | 骨架已实现（未接 JNI） | `android/`：Gradle 8.7 / AGP 8.5.2 / Kotlin 2.0.21（D110），`applicationId = com.nmoi.agent`（D102）；第一屏是会话列表的**形状**，数据写死在 `ui/PlaceholderData.kt`；CI 有 `android` job 出 debug APK（D111，release 无签名材料、不冒充）。**缺**：`crates/jni` + `cargo-ndk`、图标包进 assets、instrumentation 测试，全部未在真机跑过 | `android/`、`docs/android.md`、`.github/workflows/ci.yml` |
| P6.9 | UI 骨架：会话 / 聊天 / 设置三页 + 日志门面 | 已实现（未真机验证） | `sealed interface Route` 三页切换 + `BackHandler` 只在非根注册；聊天页 = 顶栏 + 消息区空态 + 输入岛（`AndroidView` 包原生 `EditText`，D5）；**发送按钮禁用**（turn 面未接，不假装）；设置分「外观 / 调试 / 关于」（现已改成分组索引，见 P6.9b）；`NLog` 日志门面默认开、`app_log/enabled` 可关（D117）。缺：消息区数据（`load_units`）、图标按钮（等图标包进 assets） | `android/.../ui/{ChatScreen,SettingsGroup,SettingsIndexScreen,NLog}.kt`、`MainActivity.kt` |
| P6.9d | 新建会话 + 存入（真机自验的写入口） | 已实现（待真机验证） | `createConversationJson` / `appendTextJson` 两个 JNI 符号；列表页左上「＋」新建并进入；输入岛「存入」= 存为用户消息、长按 = 存为助手消息；存完重读（`reloadKey`）。**动机**：没有写入口时，消息区只能靠桌面 CLI 造库才验得了 | `crates/ffi/src/jni_bridge.rs`、`ui/{ConversationRepository,ChatRepository,ChatScreen,ConversationListScreen}.kt` |
| P6.9c | 消息区接 `load_units` | 已实现（待真机验证） | `loadUnitsJson` 走 JNI → `ChatRepository`（自己开库/关库、异常不冒到 UI）→ `ChatUnits` 一对一映射 core 的词（`turn` / `tool_pair` / `model_thinking` / `tool_call` / `tool_result` / `broken_block`）；渲染：用户右气泡、助手左气泡、思考块默认收起、工具单元折叠卡、断块显示 core 给的声明行、`incomplete` 显式标注；打开会话自动滚到最后一条。**缺**：分界线（FFI 还没提供）、往上翻页（只取最后 80 个单元）、Markdown 渲染 | `android/.../ui/{ChatUnits,ChatRepository,ChatScreen}.kt`、`crates/ffi/src/jni_bridge.rs` |
| P6.9b | 应用内日志页（真机证据不再依赖 logcat） | 已实现：**三种通道**（上下文 / 请求 / 调试）；索引页 + 齿轮页 + 通道子页（待真机验证） | `NLog` 每条记录双写：logcat + `files/logs/nmoi.log`；上限默认 **8 MB**（2/4/8/16/32 可选，存 SharedPreferences `app_log/limit_bytes`），超限保留最新一半（`.tmp` → rename，D48）；通道子页 `LogChannelScreen`：查看（尾 64 KB）/ 复制（尾 256 KB）/ 导出（SAF 整份）/ 清除（二次确认）。起因：2026-10-23 真机冷启动后 `NMoi/` 前缀日志整段消失（`I` 级被 ROM 吞）。**已拆**：三种通道（上下文 / 请求 / 调试）各自文件与上限 + 索引页 + 齿轮设置页 + 通道子页（`LogKind` / `LogListScreen` / `LogChannelScreen` / `LogSettingsScreen`） | `android/.../ui/{LogKind,LogFile,NLog,LogListScreen,LogChannelScreen,LogSettingsScreen}.kt`、`{SettingsGroup,SettingsIndexScreen,AppearanceScreen,DebugScreen,GroupScreen}.kt`、`MainActivity.kt` |
| P6.11 | 返回键分层：浮层 → 选择模式 → 页面栈 → 抽屉 → 退出 | 已实现 Android（待真机验证） | 用户 2026-10-26 报：自绘浮层弹出来后按返回直接退应用。`ChatScreen` 一个按优先级分发的 `BackHandler`（编辑对话框 → 更多面板 → 分享面板 → 选择模式），`ConversationDrawer` 里助手面板各自一个；抽屉与页面栈仍在 `MainActivity`，内层后注册所以先轮到 | D146 |
| P6.10 | 聊天页抽屉 + 面板契约第一批 | 已实现（未真机验证） | 会话列表收进左侧抽屉（宽 300dp、遮罩 alpha 0.60、点遮罩关闭、只位移不缩放、返回键在面板层先关抽屉）；落地与未落地清单见 D119。**缺**：拖动关闭/35% 阈值/8dp 阻尼/动画中不可点/令牌时长 | `android/.../ui/ConversationDrawer.kt`、`ChatScreen.kt`、D119 |
| P6.8 | JNI 桥（Android ↔ Rust） | 已实现（未真机验证） | `crates/ffi/src/jni_bridge.rs`：4 个符号（版本 / 开库 / 关库 / 列会话），每个都转手调 C ABI（D112）；`jni` feature 默认关（MSRV 1.75 vs jni-sys 1.76+）；Kotlin 侧 `ui/NativeBridge.kt` + `ui/ConversationRepository.kt`，加载失败退回占位数据并在界面上直说；CI 用 cargo-ndk 编 `arm64-v8a/libnmoi_ffi.so`。**缺**：真机验证（V0–V5 在 `docs/verification.md`）、其它 ABI | `crates/ffi/src/jni_bridge.rs`、`android/.../ui/NativeBridge.kt`、`docs/verification.md` |
| P6.6 | 平台自适应排版（竖屏 / 横屏 / 桌面） | 设计已定 + 草稿图 | §3.12 下半：Android 竖屏保留底部弹层；横屏与桌面把高频项提成输入岛按钮行、左栏常驻、右栏上下文窗口。草稿：`docs/ui/draft-composer.svg`、`docs/ui/draft-desktop.svg` | §3.12 |
| P7.7 | 加号菜单与附件管线 | 设计已定 | §3.13：图片/拍照/文件/音频/粘贴/语音输入/视觉覆盖开关 + 内容寻址的附件库。缺：一个"点了图片就少一张图"的反面案例要靠置灰来防 | §3.13 |
| P7.8 | 图片管线：上传压缩 + `view_image` + 历史摘图 | 设计已定（D72 / D73 / D74） | 原图不动、只生成发送副本（长边/总像素/字节三上限，Lanczos3，不超不重编码）；`view_image` 返回值是文字、分析在隔离子上下文里、思考落库；历史图片在下一次请求构造时换成占位引用（视觉覆盖开着才摘） | §3.20、D72–D74 |
| P5.7 | token 估算（L0 / L1 / L2 校准） | L0 **已实测**，L1/L2 设计已定 | L0 对账结果（`relay-a/DeepSeek-V4-Flash`，1000/10000 字符各四类）：ASCII 0.62、中文 0.63、**混排 1.32（低估）**、代码 1.01。结论：不能乘全局系数，L2 按 (模型, 会话内容) 校准；未校准时绝对线判定乘 1.3。缺 L1 与 timing | `docs/token-estimation.md`、`scripts/token_check.py`、D91 |
| P4.2 | 双线压缩（建议线 / 绝对线，单位可切） | 设计已定（D90） | 内部统一存 token；判定只在"造请求前"与"生成结束"；绝对线截断标 `incomplete`；压缩不可重入 | §3.22、D90 |
| P4.4 | 压缩后窗口的最小形状（人格 / 文风连续性） | **已实现 core**（切点形状 + 摘录选择器 + 摘录进 commit + 第一人称脚手架 + 摘要长度闸 + 预算扣固定成本）；未接 FFI / 请求构造 | 切点按"轮"定义：钉轮边界、A 槽 = 整轮助手发言组、结尾不强钉；摘录 **3 条**（饱和点）、挑干活原话、代码原样搬；**摘要用第一人称**（D147，比摘录更管用）；验收看"要点命中 ≥0.9 + 思考长度膨胀 ≤1.5× + 文件口吻 ≤5%"；触发线先扣固定成本（D144） | §3.4.2、D143、D144 |
| P9.1 | 签名密钥与 CI 注入 | 设计已定（`docs/signing.md`） | environment secret + tag-only + 即用即焚；主密钥离线保存，CI 只是副本。**没有任何签名材料，未实测** | §3.16 |
| P3.2 | 设置持久化（模型覆盖 / 助手 / 会话 / 角色） | 已实现 storage + FFI + Kotlin 绑定 | `settings(scope, doc_key, doc_version, payload, updated_at)` + `settings_repair`；`put/get/load_setting/list/delete` + 三态 `Loaded::{Missing,Ready,Reset}`。版本过新与解析失败都记一条修复（摘要只存哈希前 16 位），**不硬读、不静默**。FFI 面 `nmoi_settings_{put,get,list,delete,repairs,trim_repairs}_json`（C 侧 242 项检查）。Android 侧：`jni_bridge.rs` 加四个符号 + `NmoiFfi.kt`（IO 线程、`Result`、`SettingsScope` 枚举），CI 对账 8 个符号。**缺**：`SettingsRepository` 与逐项界面接线（layer 2/3） | `crates/storage/src/settings.rs`、`crates/ffi/src/settings.rs`、`crates/ffi/src/jni_bridge.rs`、`android/.../ui/NmoiFfi.kt`、D86 |
| P2.10 | 断流标记落库（`incomplete`） | 已实现 core + storage | `Message.incomplete`（`#[serde(default)]`，旧数据能读）；`MemoryEngine::finish_turn_incomplete`；`TurnCompleted` 事件带标记；schema v2 = `ALTER TABLE messages ADD COLUMN incomplete`；往返与迁移各有测试 | D21、D43 |
| P2.11 | 自动重试（四张可编辑列表 + 范围时间点） | 设计已定（D70 / D71） | 判定顺序：停止项 → 可重试状态码 → 可重试关键词 → 放弃；只在零字节阶段重试（D40）；等待可中断、总时长上限 180s。时间点写成范围（两位小数），进项时 roll 一次、等完即丢，随机源可注入。缺 core 实现 | §3.19、D70、D71 |
| P4 | 上下文压缩落地（草稿式 + 分界线） | **已完成 core，真机跑通** | `core::compress`：四件草稿工具（append/edit/read/done）+ 状态机 + 冻结快照 + 覆盖率校验（**宽松版**）+ 归档 + 分界线 + trace + 确定性兜底；覆盖率两级校验（D49） | `crates/core/src/compress.rs`、D44、本文件 §3.4 |
| P5 | 世界书 / 提示词体系 | 已实现 core（格式要改） | 现有 `core::lorebook` 是 kelivo 格式解析/导出 + 确定性激活（常驻/关键词/正则/扫描深度）+ 按 5 个位置排序注入。**要改成以内核格式为准（D93）** | `crates/core/src/lorebook.rs`、D93 |
| P5.8 | 世界书格式：内部存酒馆格式，导入兼容 kelivo / RikkaHub | 设计已定（D93） | 入口三种形状（酒馆 entries 对象 / 酒馆 character_book 数组 / kelivo-RikkaHub 外壳），出口只有酒馆一种。字段映射表在 D93，**`disable` 与 `enabled` 语义相反**是最大的坑。导入要出"丢了什么"的报告，不静默丢字段 | D93；样例在 `/workspace/世界书示例-*.json`（不进仓库） |
| P5.5 | 技能系统（SKILL.md 自动发现） | 已实现 core | 发现深度 2、front matter + 标题兜底、开关只做提示、上下文只插 name+description、读取复用工作区规则、容器映射 `/skills` 只读 | `crates/core/src/skills.rs` |
| P5.6 | TTS / ASR 预留扩展位 | 已实现 core（接口） | 只定形状：`TtsEngine` / `AsrEngine` trait + `SpeechConfig`（默认全关）；实现留平台侧 | `crates/core/src/speech.rs` |
| P6 | UI（Android / Linux / Windows） | 编排层已实现 core，UI 未开始 | `core::session::run_session_turn`：用户消息先落库 → 工具循环 → 工具对闭合落库 → 正文落库 → 压缩判据（不落库）。UI 只剩画和接事件 | `crates/core/src/session.rs`、D52 |
| P6.1 | FFI 接口面（手写 C ABI） | stage F1 + F2 已实现，真 C 程序验收 | 契约见 §3.11：JSON 信封 + 整数句柄 + 字符串所有权 + panic 不外传。F1：元信息/自检、storage（打开/体检/落单元/读单元/备份/收口）、workspace（读/写/改/列/stat）、工具声明表与直接调用、脱敏、token 估算。F2：`nmoi_turn_run_json`（整轮：发一句话 → 工具循环 → 落库 → 压缩判据）+ `nmoi_turn_cancel_json`（协作式取消）+ turn 期间 storage 句柄 `busy` 而不卡锁。缺：事件回调（逐字流式） | `crates/ffi/src/`、`crates/ffi/include/nmoi.h`、`crates/ffi/tests/c/abi_smoke.c`、`scripts/ffi_smoke.sh`、D53–D60；C 侧 178 项检查本机全过（含真端点整轮），顺带修了备份 `<dest>.tmp-wal` 残留（D48 补记） |
| P6.2 | 会话列表（UI 第一屏的数据） | 已实现 storage + FFI | `Storage::list_conversations` → `nmoi_storage_list_conversations_json`：标题/时间/条数/用量/incomplete/最后一条预览，`updated_at DESC, id DESC`，`has_more` 而不是总数（D61） | `crates/storage/src/repo.rs`、本文件 §3.11；storage 往返测试 + C 侧 190 项检查（含真端点整轮） |
| P7 | 工具生态（工作区 + 沙盒 + MCP） | 工具层 + 工具循环已实现 core，真机跑通 | `core::tools`：`Tool` / `ToolRegistry` / `ToolContext`；六个内置工作区工具 + 能力门控 + 按策略过滤声明表。缺：MCP、沙盒执行、UI 接线 | `crates/core/src/tools.rs`、D50 |
| P7.5 | 密钥隔离 + 凭据代理（容器里跑 git / gh） | 设计已定 | Android 定透明代理（helper 兜底）；Linux/Windows 用系统原语不自研；实现未开始 | `docs/secrets.md`、D28–D30 |
| P7.6 | 能力门控（默认关 + 工作区绑定） | 已实现 core | `core::policy`：7 项能力、两层门、拒绝原因带人话；有单测；UI 未接 | `crates/core/src/policy.rs` |
| P8 | 云同步（变更日志，非文件同步） | 未开始 | 设计已在计划文档 §5 | — |
| P11 | README 双语 + 名字由来 | 待做（不急） | `README.md` 保持中文为默认；新增英文版；名字的梗只在中文版写。见 §3.10 | 本文件 §3.10 |
| P9 | CI / 发布 | 已跑通 | GitHub Actions 全绿（ubuntu + windows + MSRV 1.75 + rustfmt 阻塞 + clippy 建议性）；Codeberg 侧工作流已就位，等 runner | `.github/workflows/ci.yml`、`.woodpecker.yml` |
| P10 | 数据可靠性（进程被杀 / 断电） | 前两批已实现 | WAL + busy_timeout + 单元/编辑事务（原有）；本轮补上：**启动收口**（`storage::recover`）、**压缩提交跨表事务**（`commit_compression`）、**`durable` 分级**（`OpenOptions`）。本轮再补**备份的原子发布**。缺：真 SIGKILL 测试、长任务后台 | 本文件 §3.8、D45、`crates/storage/src/recover.rs` |

---

## 1. S0 已完成的具体项（可验收）

| 项 | 状态 | 位置 | 验收方式 |
|---|---|---|---|
| 消息 / 原子单元 / 形态 F1–F5 | 已实现 | `core::message` | 单元闭合、tool 配对不变量有单测 |
| ID 命名空间（message_id / unit_id / ref） | 已实现 | `core::ids` | 类型级隔离 + 单测 |
| 逻辑时钟（Lamport 简版）与单调性检查 | 已实现 | `core::clock` | 单测 |
| 来源标签（12 类，模式 2/3 的地基） | 已实现 | `core::source` | 单测 |
| 路由规则 R1–R9（含 protected、重复读取、超长行） | 已实现 | `core::router` | 9 条规则各有测试，确定性有测试 |
| 归档层 + 内容寻址 ref + 描述符 | 已实现 | `core::archive` | 幂等 / 确定性取回 / 描述符长度 |
| 写入时刻脱敏（6 类凭据） | 已实现 | `core::redact` | 6 类各有测试 + 幂等 |
| 任务锚点（§2.3.0 强制项） | 已实现 | `core::anchor` | 保真、不概括、按字符截断 |
| 预算 / 缓存代价 / 触发器 / 回灌成本 | 已实现 | `core::budget` | 附录 A 数值对照（11.5 / 24 轮） |
| 退化检测（n-gram + 流式 + 相似度代理） | 已实现 | `core::degenerate` | 单测；语义卡死需上层信号 |
| 事件流（EventBus + seq 顺序） | 已实现 | `core::events` | 多订阅者顺序、掉线剔除 |
| 无头引擎（submit/delta/finish/snapshot/restore） | 已实现 | `core::engine` | 事件顺序、时钟单调、回滚 |
| SQLite schema v2 + 迁移框架 | 已实现 | `storage::schema`/`migrate` | v1→v2 只加列（`messages.incomplete`）；迁移幂等、拒绝更高版本、升版不丢行（有测试） |
| L0–L3 启动校验 | 已实现 | `storage`（`open`/`validate`） | 分层报告 + 阻塞返回 `Blocked` |
| 隔离区 + 恢复默认 + 撤销 | 已实现 | `storage::heal` | 原值先入隔离区；`undo_last` |
| 备份 + 在副本上验证 | 已实现 | `storage::backup` | `integrity_check` + `foreign_key_check` |
| FFI：panic 不跨边界 | 已实现 | `crates/ffi` | `catch_unwind`，panic 探针返回 -3 |
| CLI：doctor / init / backup | 已实现 | `crates/cli` | JSON 输出 + 退出码约定 |
| 上下文压缩：草稿 + 确定性执行器 | 已实现 | `core::compress` | 四件草稿工具、覆盖率宽松版（漏项退回）、归档原文、画压缩线、确定性兜底；19 项单测 |

**测试状态**：已实测。本地（2026-10-21，本机 PRoot / cargo 1.75）core **445 项**、
storage 单测 **27 项** + 集成 **29 项**（calibrate 5 / roundtrip 15 / settings 9）、ffi **19 项**、
C 侧 ABI **242 项**全部通过；`cargo fmt --all -- --check` 干净；
`cargo clippy --workspace --all-targets` 无告警；`cargo check -p nmoi-core --no-default-features` 无告警
（关掉 `bpe` 的那条路也得编得过，否则它会悄悄烂掉）。

**本地跑测试的口径（2026-10-20 修订，见 `docs/decisions.md` D16.1）**：
短测试本地跑（本次改动那个 crate 的 `--lib` 过滤测试，单次 ≤ 60s），全量留 CI。
实测：core 单模块 2–3s，整个 `-p nmoi-core --lib`（238 项）3s，改一个文件后的增量重建约 21s——
贵的是编译不是运行。新写的测试**推之前先在本机跑一次**（这条是被一轮 CI 换 20 秒能发现的两个 bug 换来的）。

首轮 CI 暴露过 5 个测试失败 + 1 个真 bug（glob 的 `**` 结尾匹配不上），修法与原因记在
提交 `0646ba1`、`c8cf832`、`7949d29` 里。

---

## 2. 新需求并入（本轮新增，全部来自 2026-11-07 的需求）

### 2.1 消息锁定（已实现模型层）

| 项 | 状态 | 说明 |
|---|---|---|
| `locked` 字段 + 持久化 | 已实现 | `messages.locked`，`storage.set_message_locked` |
| 锁定拦截内容编辑 | 已实现 | `core::edit::apply_edit` 返回 `EditError::Locked` |
| 锁定**不**拦截压缩 | 已实现 | 压缩走形态变化与分界线，不经过 `apply_edit` |
| 与 `protected` / `pinned` 的正交关系 | 已实现 | 见 `docs/decisions.md` D11 |
| UI：长按菜单里的锁定开关 | 未开始 | 等 P6 |

### 2.2 上下文分界线（已实现模型层）

| 项 | 状态 | 说明 |
|---|---|---|
| `dividers` 表 + `Divider` 模型 | 已实现 | `after_seq` 定位，`kind = user \| compression` |
| 位置合法性（必须落在原子单元边界） | 已实现 | `validate_position` / `DividerError::SplitsUnit` |
| 窗口计算（最新合法线之下才是当前窗口） | 已实现 | `active_window` / `window_start` |
| 压缩工具自动画线 | 已实现（算法） | `choose_cut_for_budget` 保留最近预算 |
| 用户手动插线 | 已实现（数据层） | `insert_divider`；UI 长按两条消息之间 |
| 长按删除 + 确认弹窗 | 未开始 | `delete_divider` 已就绪，等 UI 的确认对话框 |
| 删线后恢复完整窗口 | 已实现 | 有测试 |
| L3 校验线的三查（越界 / 切开单元 / 下面为空） | 已实现 | `validate::check_dividers` |

### 2.3 块级编辑（已实现模型层）

| 项 | 状态 | 说明 |
|---|---|---|
| 编辑输出块 / 思考块 / 工具调用块 | 已实现 | `BlockSelector::{Output, Thinking, ToolCall{index}}` |
| 编辑后重算 token + 递增 revision | 已实现 | `message_tokens`、`revision` |
| 编辑审计（前后哈希、区块名、token 变化） | 已实现 | `edits` 表 + `save_message_edit` |
| 消息下按钮：复制 / 重试 / 朗读 / 翻译 / 更多 | 未开始 | UI，见 §3.6 |
| 「增强编辑」= 连思考块和工具块一起编辑 | 已实现（接口） | 同一 `EditRequest`，UI 决定入口 |
| 分享：截图 / 导出 MD（可含思考与工具摘要） | 未开始 | 需要"工具摘要"渲染器（见 §3.6） |
| 「工具/调用详细」开关联动灰化 | 未开始 | UI 状态，无后端依赖 |

### 2.4 工作区文件工具（已实现，细节见 §3.5）

| 项 | 状态 | 说明 |
|---|---|---|
| read_file：行数 / 单行字节 / 总字节三级截断 | 已实现 | 默认 2000 行、单行 4 KiB、总 256 KiB |
| 超长单行策略 | 已实现 | 保留头部 + 标注原始字节数 + 记入 `long_lines` |
| `force_full` 最高优先级 | 已实现 | 忽略行数/字节/单行/二进制/大小上限 |
| 路径越界防护 | 已实现 | 绝对路径、`..`、指向外部的软链一律拒绝 |
| write_file / edit_file（唯一匹配要求） | 已实现 | 模糊匹配 > 1 处时报错要求 `replace_all` |
| list_dir / find（glob）/ search（文本） | 已实现 | 自带 `*` `?` `**` 匹配器，跳过 `.git`/`node_modules` 等 |
| 二进制检测与拒绝 | 已实现 | 非 `force_full` 下拒绝，避免把二进制塞进上下文 |

---

### 2.5 两条铁律：所见即所得 + 不得有"不可见消息"（已落地，D152–D154）

用户 2026-10-26 定的两条，**比具体功能更硬**：任何设计先过这两条，过不了就重做。

1. **所见即所得**：凡是"针对某条消息"的动作（复制版本、编辑、切换、删除），
   源必须是**屏幕上此刻显示的那一份**——现读库（`versions().shown`），不用"点开之前抓的快照"。
   踩过的两个反例：复制的源取列表快照 → "我明明改了，它复制的还是旧的"；
   编辑预填取快照、落点却按库里的视图 → "看着改后、编辑框里是原文"。
2. **不得有"不可见消息"**：库里存的每一样东西都要能在界面上指出来。
   - 一版只有两种正文：`origin_message`（蓝）/ `tooledit_message`（红）；
     **没有 `tooledit_message` 就完全不显示红蓝状态**（D154）。
   - 不许"另存一份、显示时再叠上去"的覆盖层——v6 的 `user_text` 就是这么来的，v7 已经删掉。
   - 冗余的布尔只允许当"存在性快照"（`edited_by_tool` ⟺ `tooledit_message IS NOT NULL`，
     由 `CHECK` 锁死），不许当第二份状态。

自检一句话：**这个字段、这份数据，用户能在界面上看到它在哪儿吗？** 看不到就别存。

## 3. 待做设计（本轮需求展开）

### 3.1 依赖与许可

| 项 | 状态 | 结论 |
|---|---|---|
| 本项目许可 | 已定 | MIT（`LICENSE`） |
| 运行时依赖许可 | 已核对 | rusqlite/regex/serde/uuid/sha2/thiserror/tempfile 全部 MIT 或 Apache-2.0 |
| 参考 kelivo | 已核对 | kelivo 为 **AGPL-3.0-only**：**只参考 UI/交互设计，不复制代码**；若将来要抄代码，整个项目必须转 AGPL |
| 模型元数据来源 | 待办 | 计划用 models.dev + LiteLLM 的 `model_prices_and_context_window.json`（均宽松许可）做**离线快照**，附来源与抓取日期 |

### 3.2 Provider / 模型配置层

| 项 | 状态 | 设计要点 |
|---|---|---|
| Provider 类型 | 设计已定 | `openai_compatible`、`openai_responses`、`anthropic`、`gemini`，留扩展位 |
| 能力位（capability flags） | 设计已定 | 流式 usage、图片、工具、结构化输出、**assistant prefill（强制续接依赖）**、思考回传要求 |
| 思考字段类型（ThinkingField） | 设计已定 | `openai_effort` / `responses_reasoning` / `anthropic_thinking` / `gemini_thinking_config` / `none` / `custom`；新增模型时按 provider 类型给默认 |
| 自定义 body 与 body 屏蔽 | 设计已定 | 合并顺序：软件默认 → provider 自定义 → model 自定义 → body 屏蔽（屏蔽可删任意 key/value） |
| 模型名正则表 | 设计已定 | 归一化后匹配（`.`/`-` 等价、剥离 `[分组]` 前缀）；命中家族但无版本 → 取该家族最新；UI 必须显示"值来自规则 X，可手动覆盖" |
| 多模态 | 设计已定 | 按 openai / gemini / anthropic 三类端口的图片编码与尺寸限制分别适配 |
| 结构化输出 | 设计已定 | 优先 provider 原生（json_schema / response_format），否则工具约束 + 本地 schema 校验 + 有限重试 |
| 错误与 usage 韧性 | 已实现 core | 沿用实验结论：usage 可能缺失、错误码不可靠 → **客户端自维护预算**；`max_tokens` 给足。`OverflowHint` 分 Likely/Unreliable 两档，只当线索 | `core::runtime`、D40 |
| 传输 | 已实现 core（明文） | `Transport` trait + `PlainHttpTransport`；分块/定长/关闭定界；`https` 报 `Unsupported` | `core::transport`、D38 |
| 流式解析 | 已实现 core | SSE 帧 + 四端口适配，统一成 `StreamEvent`；usage 口径归一 | `core::sse`、`core::stream`、D39 |
| 非流式 / 工具消息 | 已实现 core | `complete()` + `parse_complete`；工具消息四端口映射；工具声明三端口（Gemini 未做） | D41 |
| 五类模型角色 | 已实现 core | `core::roles`：配置 + 提示词 + 门控 + 降级 + 单独计量；图片通道未做（P3.1） | D42 |
| 实现 | 已实现 core | 落 `core::provider` + `core::model` + `core::runtime`（真机冒烟见 `docs/provider-runtime.md`） | `crates/core/src/` |

### 3.3 五类模型角色

统一抽象：`ModelRole { TitleSummary, Compression, Translation, Vision, ChatSuggestion }`。
每个角色可配：provider+model、思考开关、思考强度、自定义提示词 / 世界书引用、是否禁用、是否「使用当前聊天模型」。

| 角色 | 触发时机 | 默认建议 | 备注 |
|---|---|---|---|
| 标题总结 | 首次生成结束且会话未命名 | 便宜小模型；无则用当前模型 | 输出写回会话标题，可撤销（保留原值） |
| 压缩模型 | 自动触发或手动压缩 | **默认当前聊天模型** | 理解上下文需要同等能力；可换 |
| 翻译模型 | 消息下「翻译」按钮 | 便宜模型 | 结果只进 UI，不进上下文（可选写入） |
| 视觉模型 | 输入框「+ → 视觉替换」开启时 | 必须显式指定，未指定则功能置灰 | 图片不发给聊天模型，改为文字描述（新 `SourceTag::VisionBridge`） |
| 聊天建议 | 每轮回答结束 | 便宜小模型 | 可关闭；不阻塞主流程 |

共同要求：这些请求**单独计费与计量**，不并入会话上下文预算；失败静默降级，不影响主对话。

### 3.4 上下文压缩落地（草稿式 + 分界线）

用户提出的流程，逐条落成机制：

```text
触发（自动 safety valve / 手动）
  → 冻结快照，注入压缩脚手架
  → 模型可多次调用草稿工具：compress_draft_append / compress_draft_edit / compress_draft_read
      · 草稿只存在内存/scratch，不进上下文、不进归档、零缓存代价
      · 工具返回：已写入字节数、当前草稿长度
  → 模型调用 compress_draft_done（"压缩草稿已完成"）
  → 模型本轮输出自然结束
  → 执行器（确定性代码，不是模型）：
      1. 覆盖率校验（§3.6）：protected / keep 项是否都能回答
      2. 不合格 → 拒绝提交，保留草稿，把缺失项回给模型继续补
      3. 合格 → 线上内容写归档（原文不删）→ 插入 compression 分界线 → 把草稿作为一条新消息放到线下
      4. 记录 trace：操作序列 / 草稿长度 / 分界线位置 / 覆盖率结果
```

| 项 | 状态 | 说明 |
|---|---|---|
| 草稿工具接口 | 已实现 core | `core::compress`：`Draft` + `parse_command` + 四件工具声明；`edit` 只接受唯一匹配 |
| 上限与兜底 | 已实现 core | 草稿 64 KiB / 单次 8 KiB / 64 次操作；超轮次转 `Forced`；`commit_fallback` 用归档指针拼确定性摘要 |
| 覆盖率校验 | 已实现（§3.6） | `core::facts`：路径 / 命令 / 错误码 = 硬项；标识符与数字按"谁说的"分级；未完成标记 = 软项。三道防过抽的闸（每条 3 条 / 总 48+64 / 包含去重）。缺硬项拒提交，缺软项只提示 |
| 分界线插入 | 已实现 | `choose_cut_for_budget` + `Divider::compression`；摘要挂在 `Divider.note`（D44） |
| 归档线上内容 | 已实现 | 每条线上消息 `capture` → `put`，原文不删；摘要也进归档 |
| trace | 已实现 | 操作序列 / 草稿长度 / 切点 / 覆盖率 / 退回次数 / 是否兜底 |
| 与模式 2 掩码并存 | 设计已定 | 掩码是"写入时刻的形态"，分界线是"窗口切分"；两者叠加不冲突 |
| 接模型跑通 | **已实测** | `compress::runner`：脚手架 + 工具循环 + 失败当工具结果喂回 + 退回后继续补；真机 2 次往返、2 次调用、覆盖率 3/3。**非流式**（D46）|

### 3.4.2 压缩后窗口的最小形状（人格 / 文风连续性，D143）

**目标（用户 2026-10-26 定，比"风格延续"更准）**：让 AI 觉得**此前的对话是它自己说的**，从而保持相对的思维可信度（相对于某个模型而言）。不追求文本一致，追求**归属感**——历史在它眼里是"我的过去"，不是"一份别人给的摘要"。

这个目标决定了验收要看什么：不是措辞相似度（实测连不压缩重跑都只有 7–21%），而是**它在用什么口吻讲那些事**（D145 的文件口吻率）。

压缩把原文换成摘要之后，模型**自己最近说过的话一条原文都不剩**：`compress.draft` 只要求
"数字、路径、命令、版本号逐字保留"，没有任何一条要求保住语气、自称、句法。于是同一个会话里
前后文风会漂 —— 这是"压缩后不像同一个人"的结构性原因，不是模型坏了。

用户 2026-10-24 提的方案：压缩结果最多带 **3 条助手 + 2 条用户**，顺序强制
`A1 → U1 → A2 → U2 → A3`。

**已测**（本机 `cargo test -p nmoi-core`，7 种历史形状 × 5 档预算，全部走真实的
`divider::choose_cut_for_budget` 与真实的 `AtomicUnit`；探针文件测完已删）：

| 历史形状 | 角色序列 | 消息层能选中 `A U A U A` |
|---|---|---|
| 纯聊天 ×5 轮 | `UAUAUAUAUA` | 5/5 |
| 单工具轮 | `UATA` | 1/5 |
| 双工具轮 | `UATATA` | 1/5 |
| 收尾是用户（还没回） | `UAUAU` | 4/5 |
| 短历史 | `UAU` | 2/5 |
| 连续助手（重试 / 续接） | `AAA` | 1/5 |
| 混合三轮（含工具） | `UATAUAUATTA` | 5/5 |

现有切点的实测分布：**9 次切点里 7 次让窗口以助手消息开头**（其中 **2 次是悬空的工具对**——
`assistant(tool_calls)` 留在窗内、它的 `tool_result` 在线上，于是窗口里出现"有发起没结果"），
**1 次窗口以用户消息结尾**。

**结论：可行，但不能按"消息"定义，要按"轮"定义；落地形态是「窗口形状约束」，不是"摘要里再抄 5 条"。**

1. **槽位是轮，不是消息。** 一个工具轮里有 1 条用户 + n 条助手（`assistant(tool_calls)` × n + 收尾文本），
   消息层的裸交替在单工具轮里**只有 1/5 可满足**（助手连排，中间没有用户）。
   所以 A 槽 = "一整轮的助手发言组"，按原子单元不变量整组搬（`assistant(tool_calls[n]) + 全部 tool_result[n]`
   不许切开）。可行前提是最近**至少 3 个完整轮**，不足就按阶梯降级。
2. **窗口起点钉在轮边界（用户消息之前）。** 这一条是本次实测暴露的真问题：7/9 的切点让窗口以助手发言开头，
   模型读到的是"自己回答了一个看不见的问题"。做法：在 `choose_cut_for_budget` 之上加
   `choose_cut_for_window_shape` —— 先按预算取最靠后的位置，再往前挪到最近的轮边界；挪不动就退一档预算。
3. **结尾不强钉成助手。** 最后一条是用户消息（刚发、还没回）时必须保留它，否则等于把用户刚说的话压掉。
   所以 `A3` 结尾只对**摘录块**成立，对**活窗口**不成立。

**降级阶梯（预算不够时）**：`A1 U1 A2 U2 A3` → `A2 U2 A3` → `A3` → 不留。
丢最老的一对、保尾部：越近的声音越影响下一句。

**摘录块的适用场景只有一个**：活窗口里助手发言不足 2 轮时（多次压缩之后窗口被压得很短），
从更早的历史里挑原话补进去，当跨压缩周期的人格锚。窗口够长时**不要再抄**——
重复内容既费 token，又把注意力从"这一轮到底要做什么"上挪开。

**两条硬判据（实现时必须验）**：

- **摘录必须由代码从库里原样搬**，不能写进提示词让模型"输出最多 3 条助手消息"。
  改写过就可能编出不存在的对话（见下方实测：改写版把"你叫我笨蛋也行"改成了"你可叫我笨蛋"，语义反了）。
  注意：**口癖这一项改写版没有变差**（3.04 vs 2.83），所以这条的理由是"防捏造"，不是"防丢语气"。
- **二次压缩要替换、不要叠加**：旧摘录已在摘要正文里，新的压缩必须把它从正文摘掉再挂新的，
  否则每压一次就多一份重复的声音。

#### 实测（2026-10-26，两个上游各 10 样本 / 条件）

人格只在历史里立起来（system 只有一句"你是 Neko。"），三段闲聊 + 三个探针问题。

**口癖率（"喵"/百字，越高越像）**

| 条件 | relay-b | relay-c |
|---|---|---|
| 完整历史（上限参照） | 3.84 | 4.57 |
| 只摘要（0 条原话） | **0.00** | **0.14** |
| 摘要 + 1 条原话 | 1.56 | 1.39 |
| 摘要 + 3 条原话 | 2.30 | 2.11 |
| 摘要 + 5 条原话（A U A U A） | 2.83 | 1.97 |
| 摘要 + 5 条（改写版） | 3.04 | 2.61 |

- **只摘要 = 语气归零**，而且回复长度是完整历史的 **2.5 倍**（69–75 字 vs 27 字）——像在写说明书。
- **1 条就回一半，3 条接近满值，3→5 没有额外收益**（两个上游一致）。所以"3 助手 + 2 用户"这个数量正好落在饱和点，
  但实际给 **3 条** 就够，5 条是白花 token。
- relay-b 追加对照：**只摘要 + 一行风格系统提示 = 4.50–4.95**（比 5 条原话的 3.11 更好，且只多 34 token）；
  **风格提示 + 5 条原话 = 5.84**（叠起来最好）。也就是说摘录不是保风格的唯一手段，风格能写进提示词时性价比更高。
- 挑哪 5 条不重要：**"最近 5 条" 3.25 vs "口癖最强 5 条" 2.92**，差异在噪声内。

**做事时的思考一致性（工作场景，同一道调试题）**

| 指标 | 结果 |
|---|---|
| 同一条件重跑两次的思考相似度（噪声底） | **0.071 – 0.212** |
| 各压缩条件对"完整历史"的思考相似度 | 0.079 – 0.121 |
| 要点命中（闭包/旧值、快照、读时机） | 完整历史 / 只摘要 / +5 条 都 **1.00** |
| 思考长度（relay-b） | 完整 1229 字 → 只摘要 **3400** 字 → +5 条 **4433** 字 |

- **"思考文本 90% 相似"这个判据不成立**——不压缩、同一个提示重跑两次，相似度也只有 7–21%。
  措辞每次都在变，跟压缩无关。要验收只能看**要点/结论命中率**，或者看**思考长度的膨胀倍数**。
- 压缩**没有**丢掉诊断路径（三个条件下要点都命中），但**思考膨胀约 3 倍**：只给摘要时信息不足，
  模型自己补推——这才是"压缩后想得不一样"的体感来源。
- **反面代价**：加了那行风格提示之后，思考直接塌到 **162 字**（C10/C11），要点命中掉到 0.33–0.67。
  人格化指令会挤掉推理长度。**做事场景和闲聊场景的最佳配置不是同一个**。

#### 侧面验证"历史置信度"的三个办法，只有一个能用

要验的不是"风格像不像"，而是**AI 还把不把这段历史当成自己的过去**——会话里养出来的状态（自称、约定、禁忌、
自己做过的事）能不能继续被它当作事实用。三条路子都试了：

**① 文件口吻率（能用）**：回复里出现"摘要/记录/根据/提到过"这类词，说明它在**复述一份材料**，
而不是在讲自己的事。两模型合并，每格 30 采（5 槽 × 3 采 × 2 上游）：

| 条件 | 文件口吻 | 第一人称 | 4 槽答对 |
|---|---|---|---|
| 完整历史 | **0%** | 33% | 96% |
| 只摘要 | **33%** | 50% | 92% |
| +3 条原话 | 17% | 57% | 88% |
| +状态段 | 10% | 40% | 88% |
| +状态段+原话 | **3%** | 60% | 96% |

→ 单调下降，**原话 + 状态段能把 33% 压回 3%**（不压缩是 0%）。这是三条路子里唯一拉得开差距的。

**② 状态槽命中（不能用）**：5 个"只能靠历史知道"的槽（称呼、禁忌、旧事、怕的东西、约定），关键词打分。
结果 67% / 60% / 80% / 67%——**连"完整历史"都只有 67%**。口径太粗：它测的是"回复里有没有提到那个词"，
不是"记不记得"。要修得改成强制二选一提问，否则测的是措辞习惯。

**③ 第三方模型盲评（不能用）**：让另一个上游当裁判，同一槽的两段回复选"哪段更像自己记得"。
`C1 vs C4` → 后者只赢 3/13；`C2 vs C3` → 后者 3/15，方向与①相反。翻原文才发现两版**内容几乎一样**，
裁判判的是格式和措辞噪声。**盲评只在两版差别明显时有效；差别小的时候它会给出一个稳定但无意义的结论。**

**这一轮定下的方法原则**：

- 优先用**能数出来的机制量**（原话在不在窗口、状态槽在不在摘要、注入字节是否一致）——确定性，不依赖裁判；
- 行为侧只认**拉得开差距**的指标，这次只有"文件口吻率"合格，因为它直接对应"当自己的事还是当材料"；
- 用盲评前必须做**差值校准**：先拿两段完全相同的文本当对照，裁判若答不出"一样"，它的结论就不要用。

#### 第一人称摘要：比"多留几条原话"更管用的一招（D147）

用**不输出思考**的测试模型（`reasoning_tokens = 0`，内部仍在思考）跑同一套状态槽探针，
5 槽 × 2 采 × 6 条件。指标：**高置信** = 回复用第一人称归属（我记得 / 我说过 / 是我）**且**没有
文件口吻（摘要 / 记录 / 根据 / 上下文）。

| 条件 | n | 高置信 | 纯文件口吻 |
|---|---|---|---|
| 完整历史 | 8 | **75%** | 0% |
| 只摘要（第三人称） | 9 | 22% | 22% |
| 摘要 + 3 条原话 | 10 | 30% | 20% |
| 摘要 + 状态段 | 10 | 30% | 0% |
| 摘要 + 状态段 + 原话 | 8 | 38% | 12% |
| **第一人称记忆 + 状态段 + 原话** | 8 | **62%** | **0%** |

三条读数：

1. **改人称最划算**：同一份内容，把摘要从"用户与助手…"改成"我叫 Neko / 我说过 / 我做过"，
   高置信 22% → 62%，文件口吻 22% → 0%。只改提示词，不花 token。
2. **摘录只解决语气**：3 条原话把 22% 抬到 30%，跟改人称不是一个量级。两者不是替代关系——
   原话保语气，人称保归属，状态段保事实在场（它单独就能把文件口吻压到 0%）。
3. **完整历史不是上限**：对抗性提问下它反而更容易松口（"嗯，你说得对。Neko 只是我在这里的名字"），
   而第一人称摘要的回答是"名字是我自己认的，不是随便贴的标签……这些不是设定，是我自己的事"。
   高置信靠**视角**，不是靠信息量。

**已落进提示词**：`docs/compression.md` §2.0 把"通篇用第一人称"写成所有摘要类提示词的第一条硬要求，
`compress.draft` / `compress.single` 的正文里也各加了一条。

**数据可信度**：这个测试模型有质量缺陷（中文里蹦英文残词 `wholes` / `preventable` / `gnaws`），
约 12% 调用直接 HTTP 500，每格只有 8–10 个样本。方向可信，绝对值别当真。

#### 按实测改的三条

1. **摘录条数定为 3**（3 助手 + 2 用户是上限形态，实际给 `A? U? A3` 三条；不值得上 5 条，3 条已到饱和点）。
2. **摘录挑"干活的原话"**：代码、报错、结论句优先于闲聊句子。理由是做事场景里真正变的是思考膨胀
   （1229 → 4433 字），不是语气；喂回关键细节能直接砍掉重新推演。
3. **风格提示与摘录按场景二选一**：闲聊 / 角色场景用"一行风格提示"（更省更准，4.50 vs 3.11，只多 34 token）；
   做事场景**不要**加风格提示（思考塌到 162 字、要点命中掉一半），走"摘录 + 中性摘要"。

**验收判据（定死，不再改口）**：不看思考文本相似度，看两条——

- ① **要点命中率 ≥ 0.9**：同一道题，压缩前后命中同一批关键判断；
- ② **思考长度膨胀 ≤ 1.5 倍**：以"完整历史"为基线（当前只摘要 = 2.8 倍，不合格）；
- ③ **文件口吻率 ≤ 5%**：回复里不出现"摘要 / 记录 / 根据 / 提到过"这类复述口吻（只摘要现在是 33%，D145）。

#### 上下文占用预算：天花板在固定成本，不在压缩（D144）

用四本真实世界书（用户提供，**不进仓库**）量规模，token 按仓库 L0 口径 **0.63 tok/字** 折算：

| 书 | 条 | 正文字数 | ≈token | 常驻字数 | 单条最长（≈tok） | 均条（≈tok） |
|---|---|---|---|---|---|---|
| A | 32 | 178,061 | 112,178 | 14,027 | 26,775（16,868） | 3,506 |
| B | 103 | 67,983 | 42,829 | 0 | 5,164（3,253） | 416 |
| C | 81 | 13,922 | 8,771 | 1,501 | 1,501（946） | 108 |
| D | 5 | 1,152 | 726 | 328 | 434（273） | 145 |

压缩后的占用 = `R`（保留近期）+ 摘要 + 摘录 + sys + **世界书命中条目**：

| W | R=30% | 常量 | 书命中 5 条 | 合计 | 占比 | 到 65% 还剩 | ≈轮数（150 tok/轮） |
|---|---|---|---|---|---|---|---|
| 128k | 38,400 | 3,500 | 17,528 | 59,428 | 46.4% | 23,772 | 158 |
| 200k | 60,000 | 3,500 | 17,528 | 81,028 | 40.5% | 48,972 | 326 |
| 256k | 76,800 | 3,500 | 17,528 | 97,828 | 38.2% | 68,572 | 457 |
| 1M | 300,000 | 3,500 | 17,528 | 321,028 | **32.1%** | 328,972 | **2,193** |

最坏情形（命中 10 条大条目 + `R=40%`）：

| W | 合计 | 占比 | 到 65% |
|---|---|---|---|
| 128k | 89,756 | **70.1%** | **−6,556（压缩完仍在线上）** |
| 200k | 118,556 | 59.3% | +11,444 |
| 256k | 140,956 | 55.1% | +25,444 |
| 1M | 438,556 | 43.9% | +211,444 |

四条结论：

1. **1M 不会撑爆**：最坏 43.9%，离触发线还有 21 万 token（≈1,400 轮）。窗口越大越安全——
   书的大头是固定成本，占 1M 的 3.5%，占 128k 的 27%，占比随窗口线性下降。
2. **会炸的是小窗口**：128k + 命中 10 条大条目 = 70.1%，**压缩后立刻又在触发线以上**，退化成压缩风暴。
3. 所以触发线**不能按 W 算**，要按 `W − sys − 世界书常驻 − 命中上限` 算。否则书一大，历史没剩多少，
   压了等于没压，还把语气全洗掉。
4. **单条条目要设上限**：建议 `≤ W×3%`（128k → 3.8k，1M → 30k）。最长那条 **16,868 tok**，
   一条就吃掉 128k 窗口的 13%。

压缩率随历史长度上升（合成文本，重复度高，**压缩率被高估**，只看趋势）：

| 原文 | 输入 tok | 摘要 tok | 压缩比 |
|---|---|---|---|
| 30 轮 | 1,441 | 962 | 1.5 : 1 |
| 120 轮 | 5,438 | 892 | 6 : 1 |
| 300 轮 | 13,287 | 545 | 24 : 1 |

方向与"越长越省"一致，所以大窗口的策略可以更宽松（摘录留长、摘要留细），小窗口才需要抠。

#### 已落地（2026-10-26，core 部分）

- `crates/core/src/divider.rs`：新增 `choose_cut_for_window_shape(units, keep_recent_tokens, min_turns)`。
  预算切点之上再挪一次——先丢窗口开头的残轮，轮数不够再往前扩到那个残缺轮的用户消息，两条路都保证
  落在单元边界上。`CompressionJob::start` 已改用这个函数（`MIN_HIDDEN_TURNS = 2`）。
- `crates/core/src/compress/excerpt.rs`（新）：`EXCERPT_MAX_MESSAGES = 3`、`EXCERPT_MAX_TOKENS = 512`、
  `SUMMARY_MAX_TOKENS = 2048`；`pick(units)` 从**窗口**里挑最近三条原话（形态尽量 `assistant → user → assistant`，
  跳过工具消息与 `tool_calls`，超预算整条放弃而不是截断），`render()` 出一个带标题的注入块。
  只从窗口取 → 二次压缩天然是替换，不会把上一次的摘录再抄一遍（有测试盯着）。
- 测试：`cargo test -q -p nmoi-core --lib` **504 项全过**（原 492 + 摘录 7 + 窗口形状 5）。
- **`commit` 已接**（同一轮补）：`CompressionJob` 冻结窗口快照 `below`，`finish` 时算摘要 + 摘录，
  产出**注入份** `injected = 摘要 + [原文节选]`；归档条目、`summary_injection()` 用的都是这一份，
  重启前后看到的内容一致。`CompressionOutcome` 因此分成两个字段：`summary`（草稿）与 `injected`（进上下文的）。
- **脚手架提示词**（`compress::runner::SCAFFOLD`）加了第 5 条：**通篇用第一人称写**（D147）。
- **硬上限变成了闸**：`commit()` 先查长度（`SUMMARY_MAX_TOKENS`）再查覆盖率，超长返回
  `CompressError::TooLong`，runner 把"先压短再提交"喂回去；退回次数（`max_rounds`）用尽转 `Forced`。
  顺序是故意的：先查覆盖率的话，模型为补覆盖率只会写得更长。
- **兜底路径也受闸管**：`commit_fallback` 的指针清单上限 `FALLBACK_LIST_TOKENS = 1024`，
  超了只列前面一部分并写"其余 N 条未列出，原文都在归档里"——归档一条不少，摘要不许爆。
- **预算先扣固定成本**：`Budget::reserve_fixed` + `with_reserve_fixed()`，`usable()` 里减掉它，
  `evaluate_trigger` 因此自动按"扣过固定成本的窗口"算百分比。文档里那句"实测 128k 最坏 70.1% / 1M 43.9%"
  就是这条的量化依据。
- **窗口读回（D149，2026-10-26 补）**：`divider::ContextWindow` + `Storage::load_window()`
  ——最新一条分界线以下的单元 + 那条线 `note` 首段的 `ArchiveRef` 指向的摘要正文。
  `run_session_turn` 读的是**窗口**，摘要当作请求的第一条（`summary_message()`），
  并且算进 `used` 与校准的 `request_pieces`。手动线同样生效（线下是窗口，只是没有摘要）。
  二次压缩用 `units_with_summary()` 把上一次的摘要当第一段原文压，不然会整段丢。
  摘要**不落 `messages` 表**（理由见 D149）。
- **仍未接**：FFI / UI 还没让用户看见压缩——`nmoi_turn_run_json` 里没有"先压再发"那一步
  （`SessionError::CompactionRequired` 现在只是把错误给调用方），Android 侧也还没调 `turn_run`。

**结构事实（决定"人格设定能不能维持"）**：世界书条目注入在 **system 侧**，不参与历史压缩，
压缩前后注入的字节完全相同。压缩只影响"历史里那种语气接不接得上"，不影响条目本身在不在场。
所以这一项的风险不在压缩会不会削弱它，而在**预算够不够让它一直在场**。

**两条硬上限（跟着 D143 一起落地）**：摘要 `≤ 2k tok`、摘录 `≤ 0.5k tok`（3 条）。
这两条定了之后，"压缩后占用 = 常量 + R" 才是可预测的，不用指望模型自律。



### 3.5 工作区与工具（Android 后续要跑容器，但工作区独立）

| 工具 | 状态 | 备注 |
|---|---|---|
| read / write / edit / list / find / search | 已实现 + **已接工具层** | `core::tools` 六个 `Tool` 实现，先过能力门；`specs_for(policy)` 按策略裁剪声明 |
| bash（可选，仅沙盒内） | 未开始 | 无沙盒时工具置灰，不假装能跑 |
| MCP 接入 | 未开始 | 参考 kelivo 的能力面，独立实现 |
| 容器（可选）：PRoot + Alpine rootfs | 设计已定 | 见 `docs/sandbox.md`：GPL-2.0-or-later / GPL-3.0-only 决定了整体许可切 GPL-3.0；PRoot 不能嵌套，开发沙箱里测不了 |
| 工具调用循环 | 已实现 core | `core::agent::run_turn`：非流式、工具失败即结果、轮数上限、`finished` 诚实回报；真机读→改→读确证通过 |
| 工具执行放平台侧 | 部分实现 | core 已有 `Tool` trait + 工作区实现；容器/原生命令仍只留能力位，执行在平台侧 |
| 能力门控（默认关 + 绑定工作区） | 已实现 core | `core::policy`；所有工具入口先问一次 `allows()` |

### 3.5.1 密钥与凭据代理（P7.5）

| 项 | 状态 | 说明 |
|---|---|---|
| `core::secrets`（`SecretHandle` + Vault trait + 内存实现） | 未开始 | 打印任何句柄都不出明文 |
| Android Keystore 后端 / 桌面 keyring | 未开始 | StrongBox 优先；落盘只有密文 |
| `core::broker`（UDS + 白名单 + 审计 + 吊销） | 未开始 | 未授权 host 一律拒绝 |
| rootfs shim + `/etc/gitconfig`（A 方案） | 未开始 | 容器内 `git clone/push` 可用，且搜不到 `.git-credentials` |
| 透明 TLS 代理（B 方案，**Android 默认**） | 未开始 | 容器里的 `GH_TOKEN` 是假值也能用；CA 只装进 rootfs |
| 证书信任尾处置（环境变量 + 失败提示） | 未开始 | Python/Node 能通；rustls 系失败时提示走 helper |
| SSH agent 转发（C 方案，可选） | 未开始 | 私钥文件不出现在容器里 |
| 蜜罐诱饵 | 未开始 | 被使用即告警 |
| Linux：systemd-creds + 不改自研代理 | 设计已定 | 密钥目录不进容器；启动器控制挂载列表 |
| Windows：DPAPI + 原生命令默认关 | 设计已定 | 不碰容器 |
| storage 的 L3 新检查 | 未开始 | 库里出现疑似明文凭据即报警并隔离 |

### 3.6 UI 要点（P6 之前先冻结形状）

| 项 | 状态 | 说明 |
|---|---|---|
| 输入区左侧：模型选择 / 网络搜索 / 思考强度 / 工具开关 | 未开始 | 长按弹小菜单，含「强制续接」 |
| 输入区右侧：发送/停止 / 语音输入 / ＋菜单（含视觉替换） | 未开始 | ＋菜单承载视觉替换开关 |
| 消息下按钮：复制 / 重试 / 朗读 / 翻译 / 更多 | 未开始 | 「更多」含复制、编辑、增强编辑、分享、工具详细 |
| 分界线长按删除 + 确认/取消 | 未开始 | 与 `delete_divider` 对接 |
| 锁定开关（长按菜单 / 消息详情） | 未开始 | 与 `set_locked` 对接 |
| 分享导出（截图 / MD，可含思考与工具摘要） | 未开始 | 需"工具摘要渲染"：工具名 + 耗时 + 关键动作（如写入了哪些文件），**不含完整内部消息**；完整内容走灰化的「工具/调用详细」 |
| 参考对象 | 已确认 | UI 与部分交互设计参考 kelivo（AGPL-3.0）与它提到的 RikkaHub；**只在设计与交互层面参考，不复制代码**，README 致谢 |
| 页面归属（谁指向它 / 怎么回去 / 子页怎么办） | 已冻结 | `docs/ui/structure.md`：设置改成**分组索引 + 子页**（索引页不摆开关，行右侧写当前值）；**日志拆三种通道**（上下文 / 请求 / 调试），索引页三行 + 右上角齿轮（三种开关与上限），每个通道子页四个动作（查看 / 复制 / 导出 / 清除）。规矩：一页一件事、不给假开关、没有生产者就明写 |

### 3.7 强制续接（可做，需分情况）

| 项 | 状态 | 结论 |
|---|---|---|
| 能力位 `supports_assistant_prefill` | 设计已定 | 支持则以 assistant 消息结尾续写（真正的 prefill） |
| 降级路径 | 设计已定 | 不支持则追加一条最简续接指令（"继续，不要重复已输出内容"） |
| 缓存友好 | 设计已定 | 两种都是**追加**，不破坏前缀 |
| 未完成标记 | 设计已定 | 被中断的 assistant 消息标 `incomplete`，UI 显示「继续」，与「重试」区分开 |
| 落地 | 未开始 | 依赖 P2 的能力位 |

### 3.8 数据可靠性（用户非正常关闭应用）

**前提**：安卓上划掉卡片就是清进程，任何时刻都可能被系统杀掉；桌面端也会被强杀、断电、OOM。
所以要求不是"尽量少丢"，而是**只要界面显示过的东西，就一定能读回来**。

先把两种事故分开，它们的对策不一样：

| 事故 | 会丢什么 | 对策 |
|---|---|---|
| 进程被杀（划卡片、OOM、`kill -9`） | 只丢**还在本进程缓冲区**里的数据；已 `write()` 到内核的都在 | 不许有"已经告诉用户成功、但还在内存里"的数据 |
| 断电 / 内核崩溃 | 内核页缓存里没落盘的数据全丢 | `fsync`（SQLite 的 `synchronous=FULL`） |

WAL + `synchronous=NORMAL` 已经覆盖第一类；第二类要显式开 FULL，代价是每个事务多一次 fsync
（手机上一次几毫秒，写入频率低，可接受）。

| 项 | 状态 | 说明 |
|---|---|---|
| WAL + `busy_timeout` 5s | 已实现 | `storage::open` 里设；WAL 是连接级行为，不进 DDL |
| 单元写入 / 编辑在事务里 | 已实现 | `append_unit`、`apply_edit` 都是 begin→写→commit，不留半个单元 |
| `synchronous` 分级 | 已实现 | `OpenOptions { durable }` → `open_with`；默认 `NORMAL`（抗杀进程），`durable: true` 切 `FULL`（抗断电）。`synchronous_level()` 可查 |
| 启动收口 | 已实现 | `Storage::reconcile()` 只读扫一遍：会话以 user/tool 结尾（没等到回复）、未闭合工具单元（带没等到结果的调用名）、已有的 `incomplete` 计数。修由显式动作 `abandon_open_unit()` 完成——不伪造 tool_result |
| 压缩提交的跨表事务 | 已实现 | `Storage::commit_compression()`：归档条目 + 摘要 + 分界线一个事务，重放幂等（`INSERT OR IGNORE`）；有回滚测试（分界线外键失败 → 归档一并回滚） |
| 草稿可丢（有意如此） | 已定 | 草稿只在内存，杀掉就没了；兜底是 `commit_fallback`（确定性指针摘要）。**不允许**为了保草稿把原文留在半压缩状态 |
| 导出 / 备份 / 分享 | 已实现（备份） | `storage::atomic`：`.tmp` → fsync → rename → fsync 目录；备份改成"写 tmp → 在 tmp 上验证 → 过了才发布"，验证不过不发布、旧备份不动。导出（Markdown / 分享）走同一个 `write_bytes`，UI 未接 |
| 长任务不依赖后台 | 未开始 | 压缩、索引这类多轮任务走前台服务；被杀就停在最后一个已提交事务上，重启后重新开始 |
| 启动校验 | 已实现 | L0–L3 + 隔离区 + 撤销 +（本轮）启动收口 |
| 测试方法 | 部分 | 已有"未提交事务在连接丢弃后消失"的测试（SIGKILL 在 SQLite 眼里是同一件事）+ 压缩提交的回滚测试。**真 SIGKILL（fork 子进程中途杀）未做**，留给桌面 CI |

**两条硬规矩**（比上面任何一条都重要）：

1. **ack 时机 = 事务提交之后**。UI 上的"已发送""已保存"必须在仓库层返回 `Ok` 之后才点亮。
2. **不做异步批量写**。手机上 SQLite 单条插入是微秒级，为省这点时间引入写队列，换来的是
   "杀进程丢最后 N 条"，不值。

### 3.9 推理回放（需按 provider 配，不能全局开关）

| 项 | 状态 | 结论 |
|---|---|---|
| 语义 | 已明确 | 把历史轮次的思考内容原样回传，还是不回传 |
| 三种模式 | 设计已定 | `auto`（按 provider 能力与要求）/ `always` / `never` |
| 本项目端点的实测 | 已验证（上游实验） | 该端点思考内容可丢弃 / 原样回传 / 篡改，三种都 200 且答案一致，**无签名约束** → 是客户端策略，不是协议要求 |
| 与退化传染的关系 | 设计已定 | 回传会强化"Hmm 循环"（§3.4.1.1），默认对本地这个端点取 `never`（省 token + 切断传染） |
| 与压缩的关系 | 设计已定 | 若 provider 要求回传，则删除思考内容不合法 → 压缩只能归档，不能丢思考 |

---

### 3.10 README 双语与名字由来

用户 2026-10-21 提出（原话记在这里，免得转述走形）：

> NMoi 这个词取自 Omni 打乱顺序，NM 就是中文「你妈」的简写，常用于骂人的前缀。
> 所以这个客户端名称的详细意思是，你妈全能 Agent 😋

| 项 | 状态 | 说明 |
|---|---|---|
| `README.md` 保持中文 | 已如此 | 默认入口不变，中文优先 |
| 英文版 | 待做 | 建议文件名 `README.en.md`（GitHub 语言栏两个都会认）；开头互链一份 |
| 名字的梗写在哪 | 待做 | **只在中文版**写，英文版不解释、也不暗示 |
| 中文版写法 | 待做 | 放在「名字」小节，别放开头第一段：第一段要能让陌生人在十秒内知道这是干什么的；梗放后面，好笑且不挡路 |
| 英文版写法 | 待做 | 一句 `The name is a pun that does not survive translation.` 封顶，或者干脆不提 |

**一个约束**：README 里不写任何**只有本机才有**的东西（本地端点地址、局域网路径、
临时目录），双语版都按"别人克隆下来就能跑"来写。

### 3.11 FFI 契约（P6.1，UI 与核心之间的那条线）

UI 三种平台都要跨语言：Android 走 JNI/JNA、Linux 走 GTK4-rs（可选直连）、Windows 走 C# P/Invoke。
桥梁是 `crates/ffi` 的手写 C ABI（D24：UniFFI 是备选，不是前提）。
这一层只做一件事：**把 core / storage 的形状翻成能跨语言传的形状**，不藏业务逻辑。

#### 调用形状（每个函数只用其中一种）

| 形状 | 签名 | 失败怎么报 |
|---|---|---|
| 数据 / 句柄 | `fn(...) -> *mut c_char` | 永远返回堆上的 JSON，**永不 null**；失败是 `{"ok":false,"error":{...}}` |
| 标量 | `fn(...) -> i32` / `u32` | 负数 = `FfiStatus`（只用于 ABI 级问题：空指针、缓冲区太小、边界内 panic） |
| 输入串 | `*const c_char`（NUL 结尾 UTF-8） | 只读，不接管所有权；调用期间必须有效 |

**为什么不给"句柄指针 + last_error"那套**：那会让绑定层有两条错误路径，而且 use-after-free 是 UB。
句柄装进 JSON 之后，两种失败走同一条路，绑定层只解析一次。

#### JSON 信封

```json
{"ok": true,  "handle": 7, "...": "..."}
{"ok": false, "error": {"kind": "invalid_handle", "message": "句柄 7 不存在或已释放"}}
```

`kind` 是**稳定词表**（绑定层可以 switch）：`null_argument` / `invalid_utf8` / `invalid_handle` /
`bad_argument` / `id` / `storage` / `workspace` / `panic`。`message` 是给人看的，允许改。
所有字段名 `snake_case`；`ok` 必在第一位（解析器可以先只看它）。

#### 句柄是整数 id，不是指针

理由不是洁癖：UI 是多线程的，"后台任务拿着旧句柄回来"这件事必然发生。
裸指针在那一步就是崩进程；整数 id 只返回 `invalid_handle`，UI 弹一句"这个会话已经关了"就够了。
id **单调递增、永不复用**——复用会把"旧 id 指向新对象"变成静默错误，比崩更难查。
表是全局 `Mutex<BTreeMap<u64, Entry>>`，`Entry` 持有 `Storage` / `Workspace` 的 `Box`。

**句柄不跨线程共享语义**：表锁只保证表不撕裂，不保证业务顺序。同一 id 的调用由调用方串行化。

#### 字符串所有权

- Rust 分配的（所有 JSON 返回值）：**只能**用 `nmoi_string_free` 归还。不归还是泄漏，不会崩。
- 调用方分配的（`nmoi_product_name` / `nmoi_version` 的 `buf,len` 那条路）：Rust 只写，不持有。
  缓冲区不足时返回**所需长度（含 NUL）**，调用方按这个数重试——不需要"先问长度再分配"的两次握手。
- 不往调用方缓冲区里塞变长 JSON：长度不可预知，猜错就是截断。

#### panic 与版本

- 每个导出函数都进 `guarded`（`catch_unwind`）：JSON 函数 panic → `{"ok":false,"error":{"kind":"panic"}}`，
  标量函数 → `FfiStatus::Panic`。`extern "C"` 里让 panic 穿过去是 UB，这条不是防御性编程。
- `nmoi_abi_version()`：**加函数不递增，改参数含义 / 返回语义必须递增**。绑定层加载后先比对，
  不匹配就别用——比在运行时收到看不懂的 JSON 便宜得多。

#### 线程与阻塞

所有 `*_json` 都是**同步阻塞**调用：storage 会锁库、workspace 会读盘、F2 的 turn 会发网络。
FFI 自己**不建线程、不回调**（F2 的事件回调也只在调用方线程上同步回调，回调里再调 FFI 只允许读句柄）。

#### 函数清单（stage F1）

| 分组 | 函数 | 说明 |
|---|---|---|
| 元信息 | `nmoi_abi_version` / `nmoi_product_name` / `nmoi_version` / `nmoi_selftest` / `nmoi_panic_probe` / `nmoi_string_free` | 前五个是 stage 0 就有的，`string_free` 是 JSON 面的配套 |
| 能力面 | `nmoi_capabilities_json` | 绑定层 init 时打一行日志：版本、是否有 https、工具名、schema 版本 |
| 存储 | `nmoi_storage_open_json` / `close` / `health` / `reconcile` / `create_conversation` / `append_text` / `append_unit` / `load_units` / `backup` | `open` 的返回带 `state`：`ready` / `blocked`（阻塞不是错误，D47——UI 要弹恢复选项） |
| 工作区 | `nmoi_workspace_open_json` / `close` / `read` / `write` / `edit` / `list` / `stat` | 截断信息原样摊平（`truncated` / `remaining_lines` / `long_lines`），不替 UI 判断 |
| 工具 | `nmoi_tool_specs_json` / `nmoi_tool_names_json` / `nmoi_tool_invoke_json` | 声明表按策略过滤；直接调用要 workspace 句柄 + 策略，失败也是结果（D50） |
| 文本 | `nmoi_redact_json` / `nmoi_estimate_tokens_json` | 脱敏命中只报类型与长度，**不回显原文** |

#### F2：turn 面（`nmoi_turn_run_json` / `nmoi_turn_cancel_json`）

F1 只给"存/读/工具"这些立即返回的调用；真正让 UI 有东西可画的是**跑一轮**。
它的形状和 F1 不同，所以单写一节。

**入参是一个 JSON 对象**（不是十个标量参数）：

```json
{
  "storage": 1,                    // F1 的 storage 句柄，必填
  "session": "018f…",              // 必填
  "workspace": 2,                  // 可选；缺省/0 = 没绑工作区
  "read_only_workspace": false,
  "provider": {
    "id": "local",
    "kind": "openai_compatible",   // 或 openai_responses / anthropic / gemini
    "base_url": "http://127.0.0.1:<端口>/v1",
    "api_key": null                // 只在这一层出现，不回显、不进日志
  },
  "model": "…",
  "user_text": "…",                // 必填，空串会被拒
  "now_ms": 1700000000000,
  "max_rounds": 12,                // 可选
  "max_tokens": 16384,             // 可选
  "temperature": null,             // 可选
  "budget": {"window": 128000, "reserve_output": 8192},  // 可选；给了才算压缩判据
  "avg_per_turn": null
}
```

**出参**是这一轮的完整交代：落库的单元 id、用户消息、助手正文（可能没有）、
每次工具调用（名字/参数/成功与否/返回文字）、轮数、`finished`、`cancelled`、压缩判据。

三条边界，都是故意的：

| 决定 | 理由 |
|---|---|
| **不注入系统提示词** | 系统提示是会话的一部分，要能看见、能改、能被压缩。所以由调用方先 `nmoi_storage_append_text_json(role="system")` 落库。FFI 里偷偷加一条，UI 就永远看不到它 |
| **不猜思考档位** | D37 要求"关闭必须显式"。这一层不知道模型支持哪一档，猜一个等于替用户做决定。F2 不发思考字段，等 P3.2 角色配置持久化把档位接上 |
| **`api_key` 只进不出** | 配置里有，返回体与错误信息里没有 |

**取消是协作式的，只在轮次边界生效。** `nmoi_turn_cancel_json(session)` 置一个标志，
`run_turn` 在**每次模型调用之前**和**每个工具调用之前**看它一眼。
所以：正在飞的那次往返不会被打断（最坏等一次往返，受 `DEFAULT_READ_TIMEOUT` 约束）；
已经执行完的工具调用是**真发生过的事**，照常落库；这一轮标 `finished=false`，
库里那条助手消息带 `incomplete`（D21/D43）。这和"掐断流"是同一套语义，不另起一套。

取消**只对正在跑的轮次生效**：没在跑就是空操作（`{"cancelled":false,"running":false}`）。
不做"预存一个取消请求等着下一个轮次"——那会让一次误触静默吃掉后面某一轮。

**turn 期间 storage 句柄被"取出"**：`run_turn` 要 `&mut Storage`，整轮可能跑几十秒。
如果一直攥着那把全局锁，UI 读消息就会卡住（不是报错，是**静默卡死**，最坏的那种）。
所以 turn 开跑时把 `Storage` 从表里取出来、原位置留占位，期间对该句柄的其他调用
立刻得到 `{"kind":"busy"}`；跑完（**包括失败和 panic**）再放回去。
工作区不用取出——`Workspace` 可以 clone，拿一个副本进去就够。

#### F2.1 会话列表（P6.2）

F1/F2 之后 UI 还差第一屏：**去哪儿选会话**。这一项单列，因为它有自己的一套契约。

```
nmoi_storage_list_conversations_json(handle, limit, offset)
→ {"items":[{ "id","title","created_at","updated_at","messages","tokens",
               "incomplete","last_seq","last_role","last_text","last_text_truncated" }],
   "returned": n, "has_more": false}
```

三条约定：

| 约定 | 理由 |
|---|---|
| 排序 `updated_at DESC, id DESC` | 第二键不是为了好看：同一毫秒建的两个会话，只按时间排会让顺序随机跳，而屏幕上"会话乱跳"是最容易被当成数据损坏的现象 |
| 给 `has_more`，**不给总数** | UI 要的是"还能不能往下翻"。为这个跑一次全表 `COUNT(*)`，在手机上就是白花几十毫秒 |
| 预览截断到 200 字符 + `last_text_truncated` | 最后一条可能是工具结果（几十 KB 的目录列表）。不截断就是让"打开会话列表"变成拉一个文件回来；截了不说，UI 就会把半句话当全文显示 |

`limit` 缺省 50，硬上限 200（UI 传 1e9 不该把手机拖死）。
`last_*` 三件指**最后一条消息**（不分角色）——列表上显示"最后说了什么"，比"最后助手说了什么"更接近人的记忆。

#### 本层明确不做

不解析模型输出、不管压缩策略、不碰 UI 布局、不做"自动重试"。
每个函数都把 core 的返回结构**摊平成字段**，而不是重新组织——重新组织就是在 FFI 里写第二套业务逻辑。

---

### 3.12 聊天页、侧栏与两个面板（P6.3，形状按截图冻结）

UI 本体要等有平台工具链的机器，但**形状可以现在就冻结**：每一块都写出"数据从哪来"，
将来画的人不用猜，也不会在 UI 层重造一套已经有的语义。
2026-10-21 按用户给的 kelivo 截图对齐（结构照抄，像素不照抄）。

**聊天页三层：**

| 层 | 内容 | 数据来源 |
|---|---|---|
| 顶栏 | 会话标题（可改）· 当前模型（点开换模型）· 右：上下文窗口 / 搜索 / 更多 | `list_conversations` 的 `title`；模型来自设置 |
| 消息区 | 日期分隔 · 用户右 / 助手左（可切全宽）· **工具调用折叠卡** · **思考折叠块** · 压缩分界线 · `incomplete` 标记 · **断块卡**（§3.17） | `load_units` 按原子单元渲染；分界线来自 `dividers`；断块来自 `repair::diagnose` |
| 输入岛 | 多行输入 + 左「＋」+ 右「发送/停止」（同一按钮换形态）+ 上方一行：token 估算 / 模型 / 视觉覆盖是否生效 | `estimate_tokens`；`report.cancelled` 决定按钮回弹 |

**发送/停止按钮的长按菜单**（用户 2026-10-21 加）：

| 项 | 行为 | 用到的调用 |
|---|---|---|
| 仅保存为助手消息 | 把输入框里的内容存成**最新一条助手消息**，不发给模型 | `nmoi_storage_append_text_json(role="assistant")` |
| 仅保存为用户消息 | 同上，角色换成 user | 同上，`role="user"` |

这两项的好处在"先记下来、晚点再发"：想法来的时候模型可能正忙、或者你不想花钱。
注意它会把会话的历史改写成"以助手结尾"，下一次发消息时模型看到的是"我上次说过这句"
——这正是用户要的语义，不要"顺手修正"成 user。

**「＋」面板（照截图）：**

| 位置 | 项 | 对应我们的东西 |
|---|---|---|
| 顶部三个圆形 | 拍照 / 照片 / 上传文件 | §3.13 附件管线（相机 Android/iOS 原生，桌面不做） |
| 列表 | 技能 `23/23` | `core::skills` 发现结果 + 对话级开关（`跟随助手` 总开关在下一屏） |
| 列表 | 指令注入 `>` | 注入的 md 集合（按分组） |
| 列表 | 世界书 `1/11` | `core::lorebook`：启用条数 / 总条数 |
| 列表 | **视觉覆盖** `✓` | §3.14 的视觉覆盖（用户原话：不要叫「OCR 文字识别」）；右侧勾表示当前生效 |
| 列表 | 上下文管理 `>` | 打开下面的「上下文窗口」面板 |

**「上下文窗口」面板（照截图，从顶栏进）：**

标题 + 估算总量（`445.9k`）→ 一条分段进度条 → 图例（每行：色块 + 名称 + 数值）→ 三个操作。

图例那八行正好是 `SourceTag` 的归类，**不用另造一套分类**：

| 截图里的行 | 我们按什么算 |
|---|---|
| 历史消息 | `History` |
| 内置工具 | `ToolDefinition` + `ToolCall` + `ToolResult` |
| MCP 工具 | 同上，来源标成 MCP 的子集（P7 接入后才有数） |
| 技能 | `Skills` |
| 系统提示词 | `System` + `InstructionInjection` |
| 世界书 | `WorldBook` |
| 工作区 | `Workspace` |
| 草稿 | 压缩草稿（`compress` 那一份，没提交就不进历史） |

三个操作对应我们已经有的能力：

- **设置上下文窗口** → `Budget` 的三段（声明窗口 / 有效窗口 / 预留输出），P2.7 的逐参数覆盖。
- **压缩上下文**（截图副标题"总结对话并开始新聊天"）→ `core::compress` 全链已经在（P4），
  差别只在入口：这里是"用户主动按"。
- **清空上下文**（截图副标题"标记上下文分界点"，右侧"10 条消息"）→ `dividers` 表 + `DividerKind`，
  按 Q8 允许线以上回灌；右侧那个数字就是"线以下还有多少条"。

#### 平台自适应（P6.6）

同一套功能，按平台给不同的排布。**Android 竖屏照截图走，横屏与桌面把东西放出来**：

| 区域 | Android 竖屏 | 横屏 / 平板 / 桌面 |
|---|---|---|
| 会话列表 | 抽屉（从左滑） | **左栏常驻 260–320**（桌面不该用抽屉） |
| 附件入口 | 「＋」→ 底部弹层（拍照/照片/上传文件） | 输入岛左侧按钮行：图片 / 文件 / 技能 / 世界书 / ⋯ |
| 上下文面板 | 从「＋」进二级页 | **右栏常驻 280–320**，可折叠 |
| 发送按钮 | 右下圆形 | 胶囊「发送 / 停止」，长按出"仅保存为…" |
| 思考强度 | chip 在输入岛上方一行 | chip 在输入岛右侧，发送键左边 |

"放出来"的判据只有一条：**宽度够不够放得不挤**。默认是**全部放出来**，一行排不下时才把末尾的收进 ⋯（顺序不变）；Android 窄屏（＜720）自然落到"抽屉 + 弹层"那一档。

#### 思考强度的图标与标签（P6.5）

六档 + 关闭，共七个图标（`docs/ui/icons/`）。语法是"仪表盘"：

| 档位 | 环 | 指针 | 标签 |
|---|---|---|---|
| min / low / mid | 1 环（深蓝） | 红针从左往右 | `min` `low` `mid` |
| high / xhigh | 2 环（外深蓝 + 内淡蓝） | 同上 | `high` `xhigh` |
| max | 3 环（紫 / 深蓝 / 淡蓝）+ **环外一圈红色颗粒** | 同上 | `max` |
| off | 同上一版：环 + 中心点 + 斜杠 | 无针 | `off` |

底部用一条**淡蓝水平线**把仪表封口（不是开口的弧）。环数=档位、颜色=强度，
这样"数环"和"看针"两个线索同时可用——小尺寸时数环更快。

标签规则（用户定的）：**名字 ≥ 6 个字母就缩**：`minimal → min`、`medium → mid`；
`low/high/xhigh/max` 原样。缩到 3–5 个字母是刻意的：chip 要窄，且英文名只是给"这几档叫什么"
一个锚点，真正的信息在针和环上。

排版（实测过 3× 对比）：图标 18×18、文字 12px/500、间距 6px、chip 高 30/圆角 15、
**文字基线 = chip 中心 + 0.28em（≈3.4px）**。这个偏移不是玄学：等宽包围盒对齐会让文字看着偏高，
要按"x 高度 + 下伸部"的视觉中线对。`+4.3px`（0.36em）试过，明显偏上。

**一条硬规矩**：UI 不解释 JSON 里的业务含义，只渲染。比如"这个会话上次没说完"是
`incomplete > 0` 这个事实，不是 UI 去比对最后一条是不是 user——那种判断放错层，两个平台就会各错一次。

### 3.13 加号菜单与附件管线（P7.7）

「＋」里按 kelivo 的能力面对齐（**我能在公开 README 里核到的**：图片、PDF 与 Word 文档、
文本与代码文件、模型支持时的音频、可指定视觉模型做 OCR、图片压缩质量可调）：

| 菜单项 | 说明 | 备注 |
|---|---|---|
| 相册图片 | 多选；上传前压缩（质量可调，默认 0.85，长边 2048） | 原图保留本地，请求里用压缩版 |
| 拍照 | 直接进相机 | Android/iOS 原生；桌面不做 |
| 文件 | PDF / Word / 纯文本 / 代码 | 按扩展名分流，文本类直接读，PDF/Word 走抽取 |
| 音频 | 录音 + 音频文件 | 只有声明了 audio 输入的模型才发得出去（D40 显式报错） |
| 粘贴 | 从剪贴板取图或长文本 | 长文本走工作区/归档，不进消息体 |
| 语音输入 | ASR（`AsrEngine` 预留位，P5.6） | 与"音频附件"是两件事：这个只转文字 |
| **视觉覆盖** | 不是附件，是模式开关 | 见下 |

> 菜单项的**图标与顺序**还没对齐：我需要 kelivo 那张截图，按图摆，不凭印象摆。

**附件管线**（选完文件到发出去之间）：

1. 拿字节（手机走系统文件选择器，桌面走文件对话框）；
2. **算内容哈希**，同内容不重复存；
3. 存进本地附件库（内容寻址，跟归档同一套 ref 思路）；
4. 按模态生成内容块（见 §3.14），或交给视觉覆盖转成文字（见下）；
5. 请求里**只带需要的那个版本**（压缩图 / 描述文字），原图和全文留在本地。

**没做完之前不许假装做完**：管线没通的时候，UI 上"图片"必须置灰并说明原因，
而不是让用户点了之后消息发出去少一张图。

### 3.14 视觉覆盖（Vision Override，P3.3）

用户原话（2026-10-21）：

> OCR 文字识别我们这边叫视觉覆盖，启用后聊天中的模型将不使用自己的图片、视频等模态，
> 相应信息将会发给设置好的默认视觉模型，视觉模型的默认提示词应该存两套，一份图像，一份视频。

所以它不是"OCR 功能"，是**一条通路**：

```
附件（图片/视频） ──[视觉模型 + 该模态的提示词]──> 文字描述 ──> 主对话（SourceTag::VisionBridge）
                                                        └─> 参与预算与压缩，和普通消息一样
```

**四条语义**（写进 D62）：

1. **主模型一个媒体块都不收**。开着覆盖时，请求体里不许出现 `image_url` / `inline_data` 之类——
   否则"覆盖"就只是"多问一次"，主模型照样按自己的方式瞎猜。
2. **描述是普通历史**：进消息表、有 `SourceTag::VisionBridge`、参与 token 预算、能被压缩。
   媒体本身不进上下文，但**留在本地库里**（可按 ref 重放、可换提示词重看）。
3. **失败要说出来，不许静默丢附件**：视觉模型挂了，这条消息里要写"这张图没看成（原因）"，
   UI 显示成失败态。悄悄少一张图，是最坏的一种"成功"。
4. **两套默认提示词**，一份图像一份视频；用户可以全局改、也可以按会话覆盖。
   图像默认 = 用户给的高精度复刻提示词（`data/vision.image.md`，逐字收）；视频默认见 `data/vision.video.md`。

**为什么视频不能复用图像的提示词**：图像是一次性快照，视频是"时间轴上的东西"——
要分镜、镜头运动、场景切换、字幕、音轨、关键帧时刻。用一张图的模板去描述视频，
出来的东西会丢掉"什么时候发生了什么"，而那是视频唯一不可替代的信息。

### 3.15 多模态内容块与 Gemini 全模态（P3.4）

**内容块**（请求里能出现的东西，与 `SourceTag` 正交）：

| 块 | OpenAI chat | Responses | Anthropic | Gemini |
|---|---|---|---|---|
| 文本 | ✅ | ✅ | ✅ | ✅ |
| 图片（URL/base64） | ✅ `image_url` | ✅ `input_image` | ✅ `image` | ✅ `inline_data` |
| PDF/文档 | ❌（上游要自己抽文本） | ⚠️ `input_file`（待认证） | ✅ `document` | ✅ `inline_data` |
| 音频 | ⚠️ 部分兼容端点 | ⚠️ | ❌ | ✅ |
| 视频 | ❌ | ❌ | ❌ | ✅（inline 有大小上限，大文件走 Files API） |

**Gemini 走全模态**：小文件 `inline_data`（base64，一次性），大文件走 Files API
（先 `POST /upload`、再引用 URI），本地测试没有 key 跑不了，**标注"待外部认证"**。
其他端点尽量做，但同样受"没有多模态测试模型"限制。

**能力门在模型档案**：每个 provider/模型声明能收哪些模态（快照里有就取，没有就按家族兜底 + 手动覆盖）。
发之前先问能力矩阵；缺能力就**显式报错**（D40），不静默降级成"看不见"。
真做了静默降级，用户会发现"为什么它说没看到图"而永远查不出原因。

**怎么让"找不到模型测"这件事不变成糊弄**：写一个探针脚本 `scripts/modality_probe.sh`，
对着一个端点和模型逐模态打一遍（图 / PDF / 音频 / 视频），输出支持矩阵。
本机只能跑文本和图片（端点没有别的），其余列在报告里写明"未测"——**别人有 key 就能跑同一份脚本**。

### 3.16 签名密钥与 CI 注入（P9.1）

要签的东西：Android keystore（`.jks`）、Windows 代码签名证书（`.pfx`）、Linux 包签名（GPG）。

**存哪儿，写在 `docs/signing.md`**，这里只说结论：

- **CI secret 只是副本，主密钥离线保存**。keystore 丢了 = 已发出去的应用从此更新不了
  （Android 的包名绑定签名），这不是"重新生成一个"能解决的。所以：离线备份两份，放两处。
- **GitHub**：用 **environment secret**（`release` 环境，需要人工批准），不给仓库级 secret。
  这样即使某个 workflow 被改坏，也只有 release 那一步拿得到。
- **Codeberg / Woodpecker**：repo secret + `event: tag` 过滤。Woodpecker 的 secret
  默认不注入 fork 的 PR，这一点和 GitHub 一致。
- **只在 tag 构建里注入**，普通 push / PR 一律拿不到 —— fork 的 PR 能跑 workflow，
  那就绝不能让它有机会看到签名材料。
- **注入即用即焚**：解到 `$RUNNER_TEMP`（不进仓库、不进 artifact）、`umask 077`、
  结尾 `if: always()` 删掉、脚本里禁 `set -x`、不 `echo`、不 `env`。
  两个平台都自动 mask secret 值，但 mask 只挡日志，挡不住你把它写进文件。

**两处都存 = 两份泄露面。** 建议：主密钥只存 Codeberg（主仓在那儿），GitHub 只在确实要发
GitHub Release 时临时注入；如果哪天不在 GitHub 发版，那条路直接不配。

（这一段的具体命令与可粘贴的 workflow 在 `docs/signing.md`；workflow 文件没有直接放进
`.github/workflows/`——现在没有任何签名材料，放进去只会让 CI 天天红，红久了就没人看了。）

### 3.17 断块修复：把不完整的调用/思考块"翻译成文字块"再发出去（P4.1）

用户 2026-10-21 提的问题很具体：**流断在半路，库里留下一个不完整的工具调用单元，
下一次把这个历史发给模型时，上游直接 400**（`assistant.tool_calls` 有 id、
却没有对应的 tool 消息），于是这个会话再也续不下去。

**不删、不改历史，改的是"发出去的那一份"。** 库里原样保留（那是事实），
构造请求时把这类单元**替换成一段文字块**：

```
1545374566161错误工具调用块原因:没等到工具结果
read_file({"path":"a.txt"})
```

三条约束，每条都有理由：

1. **声明行 = ID + 块类 + 原因，中间不隔符号**（用户明确要求，D66）。
   理由不是排版：以符号开头的重复行更容易把模型带进复读/退化；
   纯数字 ID + 中文块名的开头每一块都不一样，很难形成"同一 token 反复出现"的模式。
2. **ID 稳定，不是每次随机**。同一块每次请求都得是同一个 ID——
   否则每次请求的 prompt 都变，断点缓存全废，而且模型看到的是"无限多个新块"。
   由单元 id 做一次 FNV-1a 再取 13 位十进制，版本升级也不变。
   （用户说"随机生成"；这里取"看起来随机、实际确定"，性质上更安全。）
3. **信息要留下**。声明下面接原始内容（参数、思考尾巴），截断到上限。
   只写一句"这里坏了一块"，模型就不知道刚才想干什么，等于让人重新猜——
   而这一段是它自己上一次的动作。

**块类型**：`错误思考块`（思考被截断）、`错误工具调用块`（调用没等到结果 / 参数不是合法 JSON）。
**原因**：`调用中途断开` / `格式错误` / `没等到工具结果`。
够了：再细分对模型没有帮助，对排查有帮助的在库里（`reconcile` 报告 + 消息本身）。

**UI 侧**（照 kelivo 的折叠样式，D67）：断块默认折叠，标题就是那行声明，
展开看到原始内容。这样"出过错"这件事在会话里看得见、点得开，而不是被悄悄抹平。

**这条路的边界**：修复只发生在**请求构造**时（`session` 汇总历史那一步），
不动库、不动用户可见的历史文本；已经闭合的工具对一律不碰。
指回原文的超链接也没有——这是"翻译成另一种表示"，不是"变成另一条消息"。

## 3.19 自动重试（P2.11 / D70、D71）

**判据按这个顺序，第一条命中就停：**

1. HTTP 状态码在"不可重试"表 → 不重试；
2. 错误文本命中"停止关键词" → 不重试；
3. HTTP 状态码在"可重试"表 → 重试；
4. 错误文本命中"重试关键词" → 重试；
5. 其余 → 不重试。

**为什么状态码排在关键词前面**：关键词是给人写的判断（"限流"两个字可能出现在任何地方），
状态码是协议给的判断。协议能判的时候，不要让文本去猜。停止项永远排在可重试项前面——
`429` 的响应体里经常同时有 `quota`，顺序反了就会在余额已空的卡上重试到天亮。

**状态机**

```text
idle ──请求──▶ 判定 ──不重试──▶ 结束（把错误交给 UI）
                 │
                 └可重试─▶ 取第 n 项（范围 roll 一次）─▶ 等 d 秒 ─成功─▶ 继续会话（停表、丢弃本次 roll）
                                                    │          └失败─▶ 第 n+1 项还有吗？
                                                    │                    ├有  → 回到"取第 n+1 项"
                                                    └用户取消─▶ 结束（不算失败）
                                                                         └没有 → 结束
```

**数据结构**（新增 `core::retry`）

| 类型 | 形状 | 说明 |
|---|---|---|
| `RetryList<T>` | `{ items: Vec<T>, default: Vec<T> }` | 四张表共用；`restore()` 用 default 的副本覆盖 items |
| `CodeList` | `RetryList<u16>` | 状态码 |
| `KeywordList` | `RetryList<String>` | 关键词；匹配前统一小写（中文不受影响） |
| `WaitBand` | `{ min_s: f64, max_s: f64 }` | 时间点是范围，两位小数 |
| `RetryPolicy` | `{ enabled, retry_codes, stop_codes, retry_keywords, stop_keywords, bands, total_budget_s }` | — |

**判定只有一个出口**：`classify(&self, status: Option<u16>, error_text: &str) -> Decision`，
`Decision = Retry { band_index, wait_s } | GiveUp { reason }`。这个函数不打日志、不 sleep、不碰网络，
所以能穷举测试——重试逻辑最怕的就是"只有真出故障时才知道它对不对"。

**范围与 roll**：`bands` 按 `min_s` 升序（相等按 `max_s`）；进第 n 项时
`d = min + (max - min) × rng()`，保留两位小数（四舍五入到 0.01，且夹在 `[min, max]` 内）。
`rng` 注入：生产给系统熵，测试给固定序列（D71）。

**等待**：50ms 分片，每片看 `Cancellation`；`total_budget_s` 默认 180。

**与 D40 的关系**：D40 的 429/5xx 重试是**底层立刻重连**（只在零字节阶段）；
这张表是**上层按时间点再来一轮**。两层不叠次数：底层一次尝试失败后才进上层的计时，
否则会出现"重试的重试"，实际间隔和用户填的完全对不上。

## 3.20 图片管线（P7.8 / D72–D74）

三件事发生在三个不同位置，不要混：

| 事 | 什么时候 | 在哪 | 产物 |
|---|---|---|---|
| 上传压缩 | 用户选完图、还没发 | 平台侧（解码/缩放） | 附件库里的一份"发送副本" |
| `view_image` | 模型要求看图 | 工具层 + 隔离子调用 | 一条文字描述（含思考段）进工具结果 |
| 历史摘图 | 下一次请求构造 | `session` 拼历史那一步 | 占位引用替换掉媒体块 |

**上传压缩参数**

| 项 | 默认 | 说明 |
|---|---|---|
| 开关 | 关 | 关着就原样发；大图被端点拒，那就拒 |
| 长边上限 | 1568 | Anthropic 的建议值，OpenAI 兼容端也在同一量级 |
| 总像素上限 | 1.15 M | ≈ 1568×736；只限长边挡不住 1600×1600 |
| 字节上限 | 5 MiB | 先降质量，再缩边 |
| 滤波 | Lanczos3 | 可选 CatmullRom / Nearest |
| JPEG 质量 | 85 | PNG 带 alpha 时不转 JPEG |
| 结果提示 | 输入框上方一行 | 「已处理好 1024×768」/「本来就在范围内」 |

状态 `pending → processing → ready / failed(reason)`，**只有 ready 能发**。

**`view_image` 契约**

```json
入参 {"path":"docs/img/a.png","prompt":"这张图里的表格有几列？"}
出参 {"ok":true,"description":"…","reasoning":"…","cached":false,
      "image":{"format":"png","width":1024,"height":768,"bytes":183442,"att":"att_7f3a…"}}
```

- 只收**工作区相对路径**；`scheme://` 一律拒（远程要显式开开关）。
- 单次最多 4 张，单张 8 MiB；超限**报错**，不偷偷缩。
- 缓存键 `(att_hash, prompt_hash, vision_model)`；`cached:true` 时不再调模型。
- 隔离子上下文 = 图片 + 任务锚点 + 最近 4 条文字，**不带工具、不带写权限**。
- 格式按**魔数**判断，不看扩展名。

**历史摘图**

占位形如 `[image:att_7f3a2c1b 1024×768 image/png 视觉覆盖已转写]`。
三个条件同时满足才换：视觉覆盖开着、这张图已经有描述、它不是当前这一轮的。

## 3.21 设置的三个轴与两级门（D79–D85）

**一张表说清所有开关**。任何一层的"关"只影响它下面那层，永不改写别的层：

| 层 | 例子 | 关掉的后果 | 能改写别的层吗 |
|---|---|---|---|
| 模型能力（事实） | `reasoning: false`、`supported_efforts` | 这一份请求不发该字段 | 不能 |
| 用户设置（持久） | 每模型的思考强度、每助手的工具开关 | 影响下一次构造请求 | **不能被任何切换改写** |
| 会话临时 | 聊天窗口里的世界书勾选、视觉覆盖开关 | 只影响这个会话 | 不能 |
| 这一份请求（现算） | 交集结果 | 发出去就没了 | — |

**发不出去 = 这次不发，不是清零**。`core::config` 里已经落地：`ModelOverrides::emission()`
返回 `Setting` 或 `Dropped { reason, stored }`，`stored` 原样保留用户选的那一档。
`effective()` 走同一个出口（两条路各算各的迟早分叉）。

**四类东西的语义各不同，别混**：

| 东西 | 启用意味着 | 禁用之后 |
|---|---|---|
| 技能 | name + description 进上下文 | 模型不知道它存在；**文件照样能读**（有环境走 `/skills` 映射，没环境走工作区工具） |
| 指令注入 | 真的注入 md 内容 | 一个字都不进上下文（命令类开关，关了就是关了） |
| 世界书 | 设置级加载 + 会话级生效（两级门） | 设置级关：不加载、不生效；会话级没勾：加载但不注入 |
| 工具 / 工作区 | 声明进 `tools` 字段 | 声明不进；`policy` 门控是更上面一层，默认关 |

**"工作区"不是特殊物种**：它就是一组工具，UI 上归到"工具"页，开关走同一套三轴
（`policy` × 助手允许 × 会话临时）。kelivo 那个"MCP 与本地工具被关、工作区没被关"
就是因为它给工作区单开了一套判断。

**唯一的自动回退**：值坏了（解析失败 / 类型不符 / 越界到没法用）→ 回默认 + 记
`settings_repair`，UI 上明说被重置了。静默回退不行——用户会以为是自己改的。

## 3.22 token 估算与两条压缩线（D90 / D91）

**估算三级**（细节与理由见 D91、D94–D96）：

| 级别 | 手段 | 成本（实测，release / PRoot aarch64） | 用在哪儿 |
|---|---|---|---|
| L0 | `estimate_tokens`（ASCII 4 字符≈1、CJK 1 字符≈1） | 零 | 流式增量、每帧判绝对线、BPE 不可用时兜底 |
| L1 | 本地 BPE：`tiktoken-rs 0.5.9`，`o200k_base` 兜底、`cl100k_base` 给 gpt-4/gpt-3.5/claude 家族 | 首次初始化 691ms（o200k）/ 296ms（cl100k），之后 16k 汉字 26ms、10k 字符 ~14ms | 造请求前、压缩判据 |
| L2 | 端点回的 `usage.prompt_tokens` | 零 | 每轮结束**反推比例因子**，按 (模型, 级别, 内容类) 存滑动平均；`samples ≥ 3` 才算校准过 |

L2 是重点：一次真实值校准后面 N 次估算。在线 tokenizer 服务**默认不用**——
国内没有对等端点、跨境可达性差，且高频调用等于滥用。

**L1 的实测误差**（同一批文本，真值取自 `relay-a/DeepSeek-V4-Flash` 的 `usage.prompt_tokens`，
模型×词表见 `docs/token-estimation.md`）：ASCII 1.025、中文 0.804、混排 1.061、代码 1.050
（数 = 真实/估算，>1 是低估）。同一个 L0 在这些文本上是 0.62 / 0.63 / **1.32** / 1.01。
结论：**L1 把"随内容种类摆动 2 倍"压到"±6%"**，剩余偏差交给 L2。

**L1 的二进制代价**（strip 后实测）：空程序 395KB；只链 cl100k 3.67MB；只链 o200k 5.64MB；
两个都链 **7.28MB**。所以留一个 cargo feature `bpe`（默认开）：关掉之后 `tokens` 退成纯 L0+L2，
装在容量敏感的分发里时由打包方决定。

**调用面**：`tokens::Estimator`（`estimate` / `budgeted` / `observe_usage`）是唯一入口，
L0 的 `estimate_tokens` 保留原样给流式与兜底。**接线已完成**（D98）：`breakdown_of_units` 认估算器、
会话的"用了多少"走 breakdown、轮末 `observe_usage` → `SessionTurn::calibration` → FFI 落库。
`nmoi_context_breakdown_json` 那条也在 **D101**（schema v4 的 `conversations.model`）之后接上了：
会话记住最近用的模型，面板按它取校准、选词表；出参多 `model` 与 `level`（`l1`/`l0`），
`level` 是这一批数字的口径来源——没有模型名、或二进制没编 `bpe` 时它就是 `l0`，面板该据此说"粗估"。

**两条线**（`CompressionTrigger` 的 `force` / `emergency` 就是它们的旧名字，D97 已改名）：

| | 建议线（默认 0.75） | 绝对线（默认 0.92） |
|---|---|---|
| 轮开始前已过 | 照常发，本轮结束后压 → `ProceedThenCompact` | **先压再发** → `CompactThenProceed`（`SessionError::CompactionRequired`） |
| 生成中已过 | 不动，等生成结束 → `Proceed` | **立刻截断** → `TruncateThenCompact`（`TurnReport.truncated`） |
| 生成结束已过 | 落库后压 → `CompactAfterPersist` | 落库后压（已经截断过） |

三条硬约束（D97）：单位可切百分比 / token，**判定一律比较 token**；只在"造请求前"与
"生成结束"处判定，生成中只按字符增量累加；压缩**不可重入**（`SessionConfig::suppress_compression`
把造请求前的闸和轮末信号一起关掉）。

`Threshold` 双写 `percent` / `tokens`，由 `mode` 决定谁是权威——只存 token 的话，
在 200k 模型上设的 150k 换到 32k 模型会让第一次请求就触发压缩。

## 4. 需要你拍板的问题

| # | 问题 | 我的倾向 |
|---|---|---|
| Q7 | ~~锁定是否也锁**位置**（禁止重排）~~ **已定（2026-10-21）：锁位置** | 用户手动锁 = "这段别动"，连位置一起锁；`pinned` 留给模型/策略，见 D11 修订 |
| Q8 | 分界线是否也**阻止回灌**（线以上的 ref 还能不能 fetch） | 倾向允许：回灌是"追加到尾部"，不破坏窗口语义 |
| Q9 | 压缩时线上内容是"全部归档"还是"超阈值才归档" | 倾向全部归档（存储不花钱，回灌可控） |
| Q10 | `force_full` 是否设一个绝对上限（比如 512 KiB）防止把自己炸掉 | 倾向不设上限，但在 UI 上二次确认并显示字节数 |
| Q11 | 五类模型角色的默认是否都允许"禁用" | 标题/建议/翻译可禁用；压缩可禁用（退回确定性截断）；视觉未配置即置灰 |
| Q12 | 模型名正则表的快照多久更新一次 | 倾向：跟版本走 + 手动刷新按钮；快照带来源与日期 |
| Q13 | **压缩角色 vs 轮次冻结**（D76） | D42 说压缩是独立角色，D76 说这一轮冻结哪个模型就全程用它。倾向：显式配了压缩角色就用它，没配用冻结的会话模型（用户的原话是"这一轮归谁管就归谁管"，但配好压缩模型的人不该被无视）。**等你拍板** |
| Q14 | 助手 / 工具页的清单要不要先去读 kelivo 源码 | 倾向要读（AGPL，只读设计不抄代码），否则"工具项参考 kelivo 做"就成了猜。这一步需要网络，放在 schema v3 之后、UI 之前。**等你确认要不要现在读** |

---

## 5. 下一步（按依赖顺序）

0. ~~**`budget::breakdown` + FFI**~~（`3cab893`）：八行分段用量 + `nmoi_context_breakdown_json`。
   core 353 项、C 侧 204 项全过。

**这一期的顺序**（`3cab893` 之后）。排序依据只有一条：**谁决定了别人的形状，谁先做**。

| # | 事 | 为什么排在这里 |
|---|---|---|
| 0.1 | ~~设置三轴：`ThinkingEmission` / `TrimReason`~~ **已完成 core** | 决定后面所有持久化的形状；也是用户点名的"切过去切回来丢设置"。改晚了要迁移数据 |
| 0.2 | ~~**schema v3** + 设置文档读写 + `nmoi_settings_*`~~ **已完成** | 0.1 只是内存里的规矩，不落库等于每次重开就回到默认。UI 接线等平台工具链 |
| 0.3 | ~~**技能 / 指令注入 / 世界书 / 工具开关的语义落地**~~ **已完成 core**（`core::gate`，10 项测试） | 四个纯函数各管一种语义；工具按 `Capability` 分三轴 + MCP 两级门。缺：接进 `run_session_turn` 与 FFI |
| 0.4 | ~~**轮次模型冻结**（D76）~~ **已完成 core**：`turn::TurnPlan::freeze` 冻 model + 生效 profile + `ThinkingEmission` + `ToolPlan`；`SessionConfig.plan` 接线，工具声明按冻结名单（`ToolContext.declared`） | 压缩与工具循环读同一份值。Q13 已定（压缩角色优先，没配用冻结模型），压缩侧接线还没做 |
| 0.5 | ~~**并发粒度降到会话**（D75）~~ **已完成**：`Storage::attach` 开第二条连接，句柄不再被借走；同会话第二轮仍由 `Registration` 挡 busy | 结构问题；改完 A/B/C 三个会话才能真同时跑 |
| 0.6 | ~~**token 估算 L1 + L2 校准**~~ **已完成**（§3.22、D91、D94–D96）：`tokens::{bpe,class,calibrate,Estimator}` + `settings` 表新 scope `calibration`；L1 实测误差 1.025 / 0.804 / 1.061 / 1.050（ASCII / 中文 / 混排 / 代码，真实÷估算）；二进制 +7.28MB，可用 `--no-default-features` 关掉 | 压缩两条线要先知道"现在多少 token"，它是 0.7 的前置。**剩下的是接线**（`budget::breakdown` 换 Estimator、轮末 `observe_usage`），留给 0.7 一起做，一次改一处 |
| 0.7 | ~~**双线压缩**（D90）~~ **已完成**（D97）① `Threshold`：单位可切百分比 / token，判定一律折成 token；两条线改名 `suggest`（0.75）/ `absolute`（0.92）② `decide(trigger, point)` 三处判定的动作表 ③ 造请求前过绝对线 → `SessionError::CompactionRequired`（core 不替调用方压，也不假装发出去）④ 生成中过绝对线 → 工具循环立刻停、`TurnReport.truncated`，落库仍走 `mark_incomplete` ⑤ 压缩不可重入：`SessionConfig.suppress_compression`｜依赖 0.6 的估算。压缩本身要 provider + archive，只有调用方拿得全，所以 core 的职责是"算准 + 拒绝 + 交代"，不是自己压 |
| 0.8 | ~~**自动重试 core**~~ **已完成**（D70/D71/D99）：`retry` 模块（判据顺序 / 2 KiB 关键词扫描 / `{min_s,max_s}` roll + 注入随机源 / 50ms 可中断等待 / 四张可恢复的表）+ `ChatClient::complete_retrying`；**已接进 `run_turn`**（每次工具往返各算各的，`SessionConfig::retry`，FFI 暂传 `None` 等设置面）；底层 `RetryPolicy` 改名 `ReconnectPolicy`，两层不叠次数 | 独立模块，可穷举测试；与上面几项无耦合 |
| 0.8b | ~~**schema v4：`conversations.model`**~~ **已完成**（D101）：会话记住最近用的模型；`nmoi_context_breakdown_json` 出参多 `model` / `level`，`Estimator::level()` 报告实际走的那一级；列表页每行带 `model`；新增 `nmoi_storage_set_conversation_model_json`；`nmoi_turn_run_json` 造请求前写一次 | 补掉"面板只能按 L0 给数"那个洞。它是 UI 会话头的直接数据源，且不依赖任何外部条件——放在动 Android 之前 |
| 0.9 | **图片管线**（D72–D74，§3.20）：先是判定与契约，真发图等 P3.4 内容块 | 依赖附件库；`view_image` 的业务部分要等视觉子调用 |
| 0.10 | **F3 事件回调** | 先拿 curl 验端点支持不支持 `tools` + `stream`，验不过就明说 |
| 0.11 | **UI 外壳**（P6） | 需要平台工具链；在那之前只维护契约与草稿 |
| 0.12 | **Android 骨架**（P6.7）**已完成，（未接 JNI）**：`android/` + CI 出 debug APK | 先要有一条"改完能出包"的链路，否则后面每加一个界面都在赌；只写 Gradle/Compose 结构不依赖 secret，出包也只要 debug |
| 0.13 | ~~**JNI 桥 + `cargo-ndk`**~~ **已完成，未真机验证**（D112）：4 个符号转手 C ABI，CI 编 `arm64-v8a/libnmoi_ffi.so`，列表读真库 | 骨架有了才接核心，否则又是一个没人调的接口 |
| 0.14 | **真机验证一次**（`docs/verification.md` V0→V5） | 在这之前"能跑"不能用；装包 / `.so` 加载 / 建库 / 列表这四段没有任何证据 |
| 0.14e | **聊天页升为主页**（D125） | **已实现（待真机验证）**：启动 `backStack = [Chat(新会话, null)]`，`sessionId == null` 就是没落库的空白会话；首次存消息走 `onCreateSession`，父层把这条路由**原地换成带 id 的同一条**（栈深不变，不闪不跳）；左上角改成抽屉、右上角「＋新建会话」；**列表页 `ConversationListScreen.kt` 整个删除**，换会话与新会话都在抽屉里（抽屉顶部新增「＋ 新会话」，当前项按 **id** 高亮而非标题） | `MainActivity.kt`、`ui/ChatScreen.kt`、`ui/ConversationDrawer.kt`；D125 |
| 0.14f | **气泡照 Material 3**（D126） | **已实现（待真机验证）**：用户 `primaryContainer` 右、圆角 16、无尾巴；`widthIn(max = 屏宽 × 0.78)` 按内容收放；同角色 4dp / 异角色 12dp；时间 `labelSmall`；`createdAt == 0` 时不显示 | `ui/ChatScreen.kt`；D126 |
| 0.14h | **消息动作与分享**（D132 / D133 / D134） | **已实现（待真机验证）**：时间改成毫秒 + 跟系统 12/24 小时制（真机上出现过 `+58782-03-11`）；助手侧缩到 26+8、思考/工具卡放开到整行；动作行固定四图标（复制真做，刷新 / TTS / 翻译灰）；「更多」按序：编辑 / 增强编辑（待接）/ 用工具改这一版（临时探针）/ 分享 / 选择消息 / 复制为新版本（临时探针）/ 创建分支（待接）/ 删除此版本（多版才出现）/ 删除这条消息（含所有版本）（**总在**，D152/D153）；分享底部两排（思考调用 / 详细信息 + MD / 图片（灰）），MD 走 `ACTION_SEND` | `ui/ChatScreen.kt`、`ui/ConversationRepository.kt`；D132–D134、D153 |
| 0.14i | **消息版本**（D135 / D137，**存储形态已由 D152–D154 取代**） | **已实现（待真机验证）**：schema v8——一版只有两份正文 `origin_message` / `tooledit_message`，`active_view` 记上次看哪一组；v6 的 `user_text` 覆盖层在 v7 已删。storage：save_generated_version / mark_version_edited / user_edit_version（就地改当前那份）/ list_message_versions（多返回 `shown`）/ switch_message_version / delete_message_version / delete_message（整单元）；测试 19+4 条。FFI：versions / switch_version / delete_version / delete_message / save_version / edit_version。界面：`< n/n >` 只在有 tooledit 或多版时出现，蓝=看原文、红=看改后；编辑预填与复制都取现读的 `shown`；删除先确认、按单元删。`turn_run` 接上后删掉两个临时探针 | D135、D137、D150–D154 |
| 0.14j | **供应商多套 + 模型表**（D157） | **已实现（待真机验证）**：`settings(provider, <id>)` 一份一个供应商（`name` / `kind` / `base_url` / `models[]` / `active_model`），当前那套存 `settings(provider,"active")`；密钥分文件 `vault/provider-<id>.bin`（`id=default` 沿用 `provider.bin`，老配置不用重填）。老形状读取时就地解释，不做迁移。界面：列表（搜索 + 首字圆标 + 「当前」标记）→ 编辑（页签 配置/模型；配置 = 名称/类型/Base URL/密钥打码 + 显隐/保存/设为当前；模型 = 点行设为当前、铅笔改、＋添加，只存 `max_tokens` 与 `temperature`）。没有"是否启用"开关——`active` 是唯一答案 | `ui/{ProviderScreens,ProviderRepository,Vault}.kt`、`MainActivity.kt`；D157 |
| 0.14k | **`turn_run` 接进聊天页**（D157） | **已实现（待真机验证）**：`↑` 真的发请求（`JNI turnRunJson` ← `NmoiFfi.turnRun` ← `TurnRepository.send`），按"现读当前供应商 + 现解钥匙"组请求；成功后落库刷新，提示行写字数/轮数/工具次数；**失败也重读**（用户那句话在发之前就落库了），并现读库判断草稿清不清；生成中同一按钮变方块 = `turn_cancel`（停在回合边界）；长按 ↑ 仍是"只保存不发送"（走 `append_text`）。临时探针「复制为新版本」「用工具改这一版」保留，等增强编辑接上再删 | `ui/{ChatScreen,TurnRepository,NmoiFfi,NativeBridge}.kt`、`crates/ffi/src/jni_bridge.rs`；D157 |
| 0.14m | **模型归属搬到助手**（D161 后半） | **已实现（待真机验证）**：`settings(assistant,"default")` 存 `provider_id`/`model_id`/`temperature`/`max_tokens`；供应商页退成目录（去掉「当前/设为当前」，模型表只留"默认模型"标记）；聊天页顶部模型条 + 自绘选择面板，选完写助手；`turn_run` 现读助手取配置，参数按"助手 > 模型"覆盖 | `ui/{AssistantRepository,ChatScreen,ProviderScreens,TurnRepository}.kt`；D161 |
| 0.14l | **工作区 + `System-Shell` + 结果卡**（D159 / D160） | **已实现（待真机验证）**：App 私有工作区 `filesDir/workspace` 每轮绑/解绑，六件文件工具首次在真机可用；`run_shell` 落地为 `System-Shell`（进程组超时、双流并行读、各 64 KiB 封顶、结果 JSON）；设置 → 工具与工作区 的真开关（本地存，确认卡）；工具卡按 `call_id` 配对，命令 + stdout/stderr 页签 + 复制；`WorkSpace-EnvirShell` 灰行未就绪 | `crates/core/src/shell.rs`、`ui/{ToolsScreen,ToolingRepository,ChatScreen}.kt`；D159/D160 |
| 0.14g | **消息区按截图重定**（D129 / D130 / D131） | **已实现（待真机验证）**：① 助手消息**去气泡**——头像 28dp + 名字 + 时间 + Markdown 正文，思考/工具/断块仍是卡片（左缩进 38dp）；② 用户消息：名字+时间+头像一行、气泡、下面一排动作（复制真做；重新生成/编辑灰着等 `turn_run` / `edit`）；③ **长按 = 选择模式**：选择条（取消 / 已选 N / 复制 / 删除灰）、点选加减、复制按 `seq` 拼进剪贴板、选择时藏输入区、返回键先退选择、换会话清零；④ `ui/Markdown.kt` 自研最小渲染器（标题/围栏代码/列表/引用/分隔线/段落 + 行内粗斜体删除线行内代码链接上色），不引库；表格/嵌套/图片原样显示 | `ui/{ChatScreen,Markdown,ActionIcons,Controls}.kt`；D129–D131 |
| 0.14c | ~~**主题三态 + 外观子页接线**（D120）~~ **已完成**：`NMoiTheme(dark)` 两套 token（深/浅各自的背景、卡片、正文色，正文不用纯白）、`ThemeMode` + `ThemeStore`、跟随系统走 `isSystemInDarkTheme()`；冷启动窗口底色按主题选（`values-night` + `onCreate` 里改 window 背景），避免闪一帧；外观子页加「主题 / 聊天页背景 / 自定义主题」三节（后两节灰行 + 三色预览位）；输入框文字色改成跟主题取；**系统栏图标跟着主题反色**（`WindowCompat`，浅色下图标变深，真机上踩过白图标白底）；显式声明 `androidx.core:core-ktx` | 深色只有一套的时候，"黑白可切"是个空话；而自定义主题要等新 scope，先留位不挡路 |
| 0.14d | **聊天页背景**（D122–D124，`docs/ui/appearance.md`）：纯色 / 图片 / 视频，**按助手归属**；`MediaCodec`+`MediaMuxer` 入库转码（≤720p / 30fps / 2Mbps / I 帧 1s / 无音轨）；不可见即停；换种类与启动时回收旧文件 | 视频这条要先把"库存在哪、谁拥有、怎么清"定死，再谈渲染；顺序反了会留下没人清的文件 |
| 0.14b | **日志拆三种通道 + 设置子页化**（`docs/ui/structure.md`）：~~`NLog` 分 `context` / `request` / `debug` 三路~~ **已完成**；~~设置改成「分组索引 → 子页」~~ **已完成**（索引页六行 + 外观/调试两个真子页 + 其余四组灰行清单）、各自文件与上限；日志索引页三行 + 齿轮（三种开关与上限）；通道子页四动作；设置从六节平铺改成「分组索引 → 子页」，索引行右侧写当前值 | 现在一页把"开关 + 上限 + 四种动作 + 预览"挤在一起，手机上就是滚动里找东西。**先在界面上把入口/返回定下来，后面每接一个功能只是往对应子页里填**；顺序排在设置接线之前，因为接线的落点就是这些子页 |
| 0.14n | **隔离环境安装**（D164 / D165） | **已实现（待真机验证）**：三件二进制进 `assets/envir/`（proot 5.1.107.101 / libtalloc 2.5.0 / libandroid-shmem 0.7，共 307,808 字节，GPL-2.0-or-later + LGPL-3.0 + BSD-3-Clause，`LICENSES/` 各一份全文）；`ui/EnvirRepository.kt` 复制 assets → `filesDir/envir/bin`、下载 rootfs（官方源钉 sha256 `5a1906794ced63a7…`，35,092,106 字节）、`TarGz` 解包到 `filesDir/envir/ubuntu`、写 `state.json`、失败清半成品；`ui/EnvirScreen.kt` 装/看/删/自检（`proot --version` 与进容器读 `os-release`）；「工具与工作区」新增入口，索引页那一行写"环境装没装"。**顺带修掉 `TarGz` 三个会在真包上炸的毛病**（补白没跳、一律拒 `..`/绝对链接、硬链接复制内容） | 没有它，`WorkSpace-EnvirShell` 无米下锅；装和跑是两件事，所以自检按钮这一版就给了 |
| 0.14o | **`WorkSpace-EnvirShell` 接进工具面**（D172，**已实现，待真机验证**） | 环境装好之后的下一件：起 `proot -0 -r <rootfs> -b /dev -b /proc -b /sys -b <workspace> -w /root /bin/sh -c <cmd>`，给 `LD_LIBRARY_PATH=filesDir/envir/bin` 与 `PROOT_TMP_DIR=filesDir/envir/tmp`，超时/输出上限复用 `crates/core/src/shell.rs` 那一份；界面沿用同一个结果卡（命令 + stdout/stderr）；设置页那个灰行换成真开关。**前提**：D165 的安装页先在真机上装通（自检那两条命令能过） | 没有安装页时，"接工具"等于接一个永远报"环境缺失"的东西；顺序反了就是白写 | **落地的形状**：`EnvirSpec`（proot/loader/lib_dir/rootfs/tmp 五个宿主机路径）+ `EnvirShellTool`，与 `System-Shell` 共用 `execute()`（超时/杀组/双流/64 KiB 封顶一份）；容器里工作区固定绑在 `/root/workspace`；`PATH` 必须显式给（真机上不给就是 head/uname/ls 全 not found、退出码 127）；门控 `ContainerExec` 默认关，FFI 收到完整 五件套才开；设置页那一行从灰行变成真开关（环境没装仍灰）。测试用一个假 proot 验我们这一侧。
| 0.14p | **聊天页四处返工 + 供应商拉模型 + 运行时改 jniLibs**（D166–D168） | **已实现（待真机验证）**：① 发送即上屏——`PendingSend` 画进消息区、输入框当场清空、末尾"正在生成…"，收尾后以库里那份为准，失败未存则放回输入框；② `JSONObject.optString("reasoning")` 在 JSON null 时返回字面量 `"null"`（思考块里那四个字母就是这么来的）→ `ChatUnits` 加 `text()` 统一挡掉；③ 助手抬头（头像/名字/时间）移到**思考卡与工具卡上方**，连续几段只画一次（`UnitBlock.showHeader` + `AssistantHeader`）；④ 供应商页加「测试连接」与「从端点拉取模型」（`ModelCatalog`，三家形状各一，日志抹掉 query 里的钥匙）；⑤ 运行时从 assets 改走 `jniLibs`（私有目录不许 exec 是 Android 10 的 W^X，真机 error=13），`LD_PRELOAD` + `LD_LIBRARY_PATH` 解决两个 `NEEDED`；硬链接退化成符号链接 | 前四条是他这轮点名的；第五条是他装完撞上的那个错误，不修整个隔离环境就是"装好了跑不了" |
| 0.14q | **容器联网**（D173，**已实现，待真机验证**） | proot 与宿主共享网络栈，所以"能不能联网"其实只是"DNS 有没有"：把宿主的 DNS 列表写进 `filesDir/envir/resolv.conf`，用 `-b <resolv>:/etc/resolv.conf` 绑进容器。`EnvirSpec` 加一个可选 `resolv`，缺了就不绑（离线也能跑）。**用户 2026-10-27 已明确允许容器联网**。判据：容器里 `getent hosts archive.ubuntu.com` 能出 IP；`apt update` 能走完（真机验）。**落地的形状**：DNS 从 `ConnectivityManager.getLinkProperties().dnsServers` 取（遍历 `allNetworks`、丢掉 `fe80:` 链路本地），**每次起容器前重写**（不是安装时写一次）；`EnvirScreen` 加第三个自检「容器联网」（先打容器里那份 `resolv.conf` 的头 5 行，再 `getent hosts archive.ubuntu.com` 并回显 `getent=$?`）；manifest 加 `ACCESS_NETWORK_STATE`（普通权限，不需要用户点） |
| 0.14r | **多助手**（D174，**已实现，待真机验证**） | schema v9：`conversations.assistant_id TEXT NOT NULL DEFAULT 'default'`，迁移把现有会话全部归到 `default`。会话列表按当前助手过滤；抽屉顶部切助手；会话「更多」里加「移动到其他助手」（**改归属，不复制**）。删助手时若有会话，不许直接删——先让它选"一起移到 default"或取消。**用户口径**：每个助手有自己的会话列表，会话可以移动过去 |
| 0.14s | **Shizuku：检测 + 开关**（D175，**未实现，先不做执行**） | 引入 `dev.rikka.shizuku:api` / `:provider`（Apache-2.0，进 notices），manifest 加 `moe.shizuku.privileged.api.permission.READ` 之类的查询项。**三态**：未安装（`Shizuku.pingBinder()` false 且包不在）/ 已装未授权（`checkSelfPermission != GRANTED`）/ 已授权。开关只在"已授权"时能拨；状态卡写清缺哪一步，并给"去授权"的跳转。**执行（`newProcess`）不在这一版**——先让用户能看见"授权到了没有" |
| 0.14t | **预设注入 + `base-version`**（D159 形状，**未实现**） | `assets/presets/<id>/{manifest.json,preset.json}`（官方原文，`version` 整数 + `sha256`）；词条记 `base_id` / `base_version` / `base_hash`；`applies_to` 在 `TurnPlan::freeze` 里按声明的工具过滤；四态（跟随 / 已改 / 有新 / 冲突）由"本地是否改过"与"上游 version 是否更大"两个判据算出来，UI 只显示 core 给的态，不自己推。**这一条用户点过名"很在意"，但它最大**：先做 core（纯函数 + 测试），再做界面（列表 + 四态 + 冲突处理） |
| 0.15 | **压缩失败阶梯**（D115）**设计已定，未实现**：L0 多步 → L1 备用多步（剔思考块压事实层 → 合并再压）→ L2 单步 → L3 分段（约 16 段；重叠窗口只当上下文；低置信丢弃用硬事实命中 + 锚点覆盖算，不信模型自评）→ L4 兜底；提示词全部可自定义 | 现在只有 L0，失败就只剩截断；"压不干净"比"没压"更伤，所以降级必须分档、每档判据写清 |
| 0.16 | **窗口读回（D149）** **已完成 core**：`divider::ContextWindow`、`divider::effective_divider`、`Divider::summary_ref`、`Storage::load_window`（含摘要校验：引用悬空直接报错，不当成"没压过"）；`HistoryStore::load_window` 默认实现 = 整条历史（未压过的会话行为不变）；`run_session_turn` 按窗口组装请求、摘要排在最前、算进 `used` 与校准 | 没有这一步，压缩就算跑了也不影响请求——线上原文照样整段发出去 | D149 |

1. ~~stage 0 代码 / CI 跑通~~（已绿：ubuntu + windows + MSRV 1.75 + rustfmt 阻塞 + clippy）
2. ~~`core::provider` + `model_registry`（P2）~~ / ~~压缩草稿工具链（P4）~~ / ~~工具层与工具循环（P7）~~ 均已实现 core 且真机跑通
3. ~~`core::session` 编排（P6 地基）~~（D52）
4. ~~P6.1 FFI stage F1：契约 + 句柄 + JSON 信封 + storage/workspace/工具面~~（§3.11，真 C 程序验收）
5. ~~P6.1 FFI stage F2：`nmoi_turn_run` + 取消~~（已完成：真机端到端跑完"发一句话 → 工具调用 → 落库"，
   见 §3.11 的 F2 小节）。
   **剩下的 F3：事件回调**（`nmoi_set_event_callback`，同步回调、回调里只能读句柄、逐字吐出）。
   拦路的事实先说清：工具循环**必须**非流式（D46，上游流式吞 `tool_calls`），
   所以"逐字"要等端点支持流式工具调用，或者走"先流式出一段纯文本、工具轮次仍走非流式"的混合。
   这一条要做之前先拿 curl 在目标端点验一遍。
6. UI 外壳（P6）：Android 先做会话列表 + 输入岛（`AndroidView` 包 `EditText`），Linux 用 GTK4。
   侧栏/底部窗口的遮罩、边缘进出、返回与拖动关闭、动画中不可点，契约在 `docs/ui/overlays.md`（D109）；图标与规则见 `docs/ui/icon-packs.md`、`docs/ui/icons/README.md`。
   **需要一台有平台工具链的机器**（GTK4 / WPF / Android SDK 本机都没有），在那之前只管把 FFI 面钉死。
7. 容器可选增强（P7 内部）：PRoot 能力探测 → rootfs 管理 → 只挂载用户选定的工作区目录。
   **本机 PRoot 不能嵌套，这一项只能写不能验**，别假装验过。

**0.14r 落地进度（2026-10-28）**：schema **v9** 已上（`conversations.assistant_id` + 索引
`conversations_by_assistant`）；storage 五条（create / list 过滤 / move / 归属查询 / 计数）；
FFI 四条（`create_conversation_json` 多一个必填 `assistant`、`list_conversations_json` 多一个
可选 `assistant`、新增 `move_conversation_json` / `count_conversations_of_json`）；
Kotlin 数据层（`AssistantRepository.list/active/setActive/load/delete`、
`ConversationRepository.load(databasePath, assistantId)` / `create(…, assistantId)` / `move` / `countOf`）
已接上，**当前助手 = `settings(assistant,"active")`，默认 `default`**，所以这一版行为与上一版一致。
界面这一版已接上：抽屉底部显示当前助手 → 点开「切换助手」面板（列表 + 新建，复制当前配置）；
会话行**长按** → 「把这条移到」；切助手/被移走的会话会回到空白会话，顶部模型条跟着换。
助手页（`ui/AssistantScreen.kt`，入口＝抽屉铅笔 + 设置「模型与提示词 → 助手」）：
列表 / 新建（复制配置）/ 改名 / 删除（`default` 不可删；有会话时「一起移到默认助手 / 取消」；
会话数读不出来就不许删）。**没做**：头像（现在取名字首字）、助手的提示词与技能绑定。

**补证据的进度（2026-10-28）**：工具批次那条缺口已补
（`crates/core/src/session.rs` 的 `three_tool_calls_in_one_message_yield_three_paired_results`：
三个调用 → 三个闭合单元、顺序与 id 一一对应，顺带把"一次调用一个单元"这个**与文档不一致**的现状钉住了，
差异记在 `docs/decisions.md` 的「待定」一节）。
**`parseFences` 那条已补**（2026-10-28，走的是"加脚手架"那条路而不是搬进 core）：
`android/app/src/test/kotlin/com/nmoi/agent/ui/EditFencesTest.kt` 八条边界，
`build.gradle.kts` 加 `testImplementation("junit:junit:4.13.2")`，CI 的 android job 加
`gradle -p android :app:testDebugUnitTest`。

补这条时它当场抓出四个真错（都是"错起来不显眼"的那种）：
① 空的 ` ```output ` → 整段（连三个反引号）被当成正文写回；② 围栏没闭合 → 同样把半截围栏当正文；
③ 超 200 KB 的块被跳过，但**笔记也跟着丢了**，界面上只说"模型没给出可用的改动"；
④ 每块末尾多一个换行（思考块写进 `messages.reasoning` 时带着），根因是补 `\n` 时判断的是"文件最后一行"
而不是"围栏最后一行"。四条都改了，判据在测试里。

如果以后要把解析搬进 core（协议只该有一份实现），这道测试正好当回归基线。

| 0.14u | **子 AGENT：声明与占位符替换**（D176，**已实现，待真机验证**） | `settings(assistant,<id>).sub_agent`{enabled,id,description,session}；`id` 跨助手唯一（保存时查重、撞了点名）；核心一条纯函数 `vars::replace(text, &VarTable)`，把 `<var><sub_agent_info></var>` 换成名单（格式见 D176，空表换成一句人话）；名单来源＝`enabled` 的助手文档。**判据**：两条子 AGENT → 一次替换出两段；没有 → 一句"没有可调用的子助手"；未知占位符原样留着不动 |
| 0.14v | **子 AGENT：工具与会话**（D176，**未实现**） | `sub_agent(id, instruction, session?, injections?, inherit?)`；`session` 三态 new/reuse/conversation:<id>；`injections` 复用 `lorebook::Position` 与深度；`inherit.rounds` 默认 0、`include_tool_results` 默认 false；回主 AGENT 的只有 `{reply, conversation_id, rounds, tokens, truncated}`，全文在子会话里；**深度只到 1**、工具面只给只读文件工具 |
| 0.14w | **子 AGENT：界面**（D176，**已实现，待真机验证**） | 助手页那块卡片：「可作为子 AGENT 被调用」开关 + 调用 ID + 描述 + 会话策略（新建/复用/钉死一条）；打开开关才露出后面三项；ID 撞了当场红字说撞了谁；工具卡上能点进子会话（铁律 ②） |

| 0.14x | **手动块编辑器**（D177，**未实现**） | 消息「编辑」进整页：块列表（正文 / 思考 / 工具参数 #N），每块可删、可改，底部「＋ 添加块」能建出原本不存在的块（正文与思考各最多一份，工具参数多份按顺序重排）。工具参数的 JSON 保存时校验，非法当场红字、不写库。保存走 `apply_block_edit` / `toolEditVersion`，存完**现读库重画**（铁律 ①）。**判据**：改思考、给没有思考块的消息加一个、改工具参数、删一个块，四种都能在消息卡上立刻看见 |
| 0.14y | **模型改消息的工具 `edit_message`**（D177，**未实现**） | 参数 `{message_id, blocks:[{kind:"output"\|"thinking"\|"tool", index?, content}]}`；与其它工具一样**这一轮结束时**统一落库（`apply_block_edit` 是现成底座）；门控与 `edit` 同档。落了之后消息卡与工具卡一起重读。**判据**：模型调一次 → 这一轮收尾时那条消息真的变了，不需要下一轮 |
