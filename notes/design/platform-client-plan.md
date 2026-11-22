# 全平台 LLM 客户端 · 架构与实施计划

> 目标：Windows / Linux / Android 三平台客户端，数据互通，Rust 核心，各平台原生 UI
> 状态：规划稿 v1（2026-10-19）
> 定位：本文件是给下一个会话的**起点文档**，含决策、依据、未决项

---

## 0. 摘要

**核心判断**：跨平台 UI 框架在**文本输入**这一层必然失败，因为 IME 是平台深度耦合的协议，
而统一抽象会把它盖住。kelivo 是活证据：为了修一个输入框卡顿，最后 fork 了 10 个上游包、
重写了 Android 的 `BaseInputConnection`、提交了 +8535/-199 行代码。

**因此本方案**：

1. **Rust 核心**承担所有跨平台同构的逻辑（请求、流式解析、上下文管理、事件流、存储）
2. **各平台原生 UI** 承担渲染与输入，UI 是薄的（消费事件流）
3. **文本输入控件按平台选原生实现**，不用跨平台抽象（这是性能的关键决策点）
4. **SQLite 做本地存储**，启动时分层校验，损坏则阻塞并询问用户

---

## 1. 决策一览

| # | 决策 | 选择 | 依据 |
|---|---|---|---|
| D1 | 核心语言 | **Rust** | FFI 内存安全、cargo-ndk、UniFFI 有现成绑定生成 |
| D2 | UI 策略 | **各平台原生**（非跨平台框架） | 见 §2 |
| D3 | Windows UI | **WPF + AvalonEdit**（保守）/ **WinUI 3**（现代） | 见 §2.1 |
| D4 | Linux UI | **GTK4 + gtk4-rs**，文本用 `GtkSourceView 5` | 见 §2.2 |
| D5 | Android UI | **Compose 外壳 + 原生 `EditText` 输入岛** | 见 §2.3（决定性证据） |
| D6 | 存储 | **SQLite（Rust 侧）**，WAL 模式 | 见 §4 |
| D7 | 数据互通 | **本地优先 + 共享文件夹同步** | 见 §5 |
| D8 | FFI 策略 | Linux 免 FFI；Windows 用 `uniffi-bindgen-cs`；Android 用内置 Kotlin 绑定 | 见 §3.3 |
| D9 | 核心边界 | 无头引擎 + 事件流，不含 UI 状态 | 见 §3.2 |

---

## 2. 平台 UI 选型（性能优先）

### 2.1 Windows

| 候选 | 渲染 | 长文本编辑 | 判断 |
|---|---|---|---|
| **WPF + AvalonEdit** | DirectX 合成 | `Rope<char>` + UI 虚拟化，ILSpy/SharpDevelop 验证 | **保守推荐** |
| WinUI 3 (C#) | DirectX 12，合成器 | `RichEditBox`（RichEdit 内核，非为代码/长文档设计） | 现代但需权衡 |
| Electron / Tauri | WebView2 | 浏览器文本栈 | 排除（用户明确要原生） |

**WinUI 3 的现状**（2026-05，微软官方 GitHub discussion #11096）：

- File Explorer 启动路径上 **分配减少 41%、瞬时分配减少 63%、函数调用减少 45%、WinUI 代码耗时减少 25%**
- 但开发者社区反馈仍然「明显比 WPF 和 UWP 慢」，批评集中在 **WinRT 互操作层**
- 部分优化需要 opt-in，因为会破坏控件模板/动画属性的兼容性

**WPF + AvalonEdit 的优势**：`Rope<char>` 数据结构对大文档的插入/删除/随机访问都是 O(log n)；
`TextView` 只渲染可见行（UI 虚拟化）。已知缺陷：**超长单行 + 自动折行会严重掉帧**
（2MB 未缩进 JSON 渲染约 3 分钟），解法是 `TruncateLongLines`（超过 2000 字符的行用省略号渲染，
不影响全选复制）。

**建议**：第一版用 **WPF + AvalonEdit**。理由是成熟度与可预测性——WinUI 3 的文本栈还在演进，
而输入框是这个项目的高风险区（见 §2.3）。若后续要 Fluent 观感，再评估 WinUI 3。

### 2.2 Linux

**GTK4 + `gtk4-rs`**，文本控件 `GtkTextView`（或 `GtkSourceView 5`）。

- `GtkTextView` 是 **model/view** 架构：`GtkTextBuffer` 是模型，`GtkTextView` 是视图，**原生支持滚动**
- GTK4 的渲染路径为 `GtkTextView` 内容做了专门优化：**大部分内容可不切换 GL program、
  用 VBO 顶点 + `glDrawArrays()` 批量绘制**，这是平滑滚动的关键
- 文本以 **UTF-8** 存储，offset（字符）与 index（字节）分离——**多字节字符处理必须用 offset**
- Rust 绑定成熟（`gtk-rs`），且 Linux UI 直接是 Rust，**无需 FFI**

`GtkSourceView` 提供语法高亮，gedit 在用。若 GtkSourceView 5 在目标发行版不可用，退化为
`GtkTextView` + 自绘高亮。

### 2.3 Android —— 决定性证据

**这一节是本方案最重要的依据。**

Compose 的 `BasicTextField` **不使用原生文本控件**：它自己绘制文本，并且**自己重实现了 IME 协议**。
两个实现都把 IME 需要的「镜像」做错了：

| 实现 | 缺陷 |
|---|---|
| `BasicTextField(value, onValueChange)` | 每次按键要过一遍重组才到达 IME 读取的缓冲区，**镜像慢一帧** |
| `BasicTextField(state = TextFieldState)` | `getExtractedText` 只答一次，**从不调用 `updateExtractedText`**，镜像永不刷新 → **打字什么都不显示** |

**关键事实：在 Compose foundation 1.12（2026-08 稳定版）仍然如此，版本升级修不了。**

横屏时 IME 会启用全屏提取编辑，此时你正在输入的字段是**键盘侧的镜像**，靠
`getExtractedText`/`updateExtractedText` 同步。镜像错了，用户看到的就是退格字符残留、
密码框明文闪一下再变圆点。

**正确做法**（与 kelivo 的最终修法一致）：把真正的 `android.widget.EditText` 包在 Material3 的
装饰壳里：

```kotlin
AndroidView(factory = { EditText(it) }, ...)   // 用 OutlinedTextFieldDefaults.DecorationBox 包壳
```

`TextView`/`EditText` 实现了完整的 IME 契约，包括 extracted-text 更新与密码掩码，
所以镜像和字段本身都正常。副作用：Android 的焦点搜索不跨 Compose/View 边界，
表单里的 Next 键要显式串联。

**因此 Android 的 UI 策略是混合的**：

- **外壳用 Compose**（现代、声明式、开发快，覆盖 90% 界面）
- **文本输入用 `AndroidView` 包 `EditText`**（IME 关键路径走原生）

这与 kelivo 的 `MirrorConnection extends BaseInputConnection`（22.9KB，手写完整
`Editable` 镜像 + 版本号 + 稀疏有序事件 + CAS 式 compare-and-apply）形成对比：
**你不需要写这些，因为你用的是原生控件本身。**

### 2.4 kelivo 的代价（为什么值得换）

issue #186 的完整生命周期：

| 时间 | 事件 |
|---|---|
| 2025-12-18 | 开 issue：macOS 输入框卡顿 |
| 2026-01-22 | 用户定位根因：Flutter `TextField`/`RenderEditable` **不支持虚拟化**，粘贴 100K 字符时 `Element.inflateWidget` 调用 **20 万次以上**，`RenderParagraph.describeSemanticsConfiguration` 占 13.5% CPU |
| 2026-01-24 | PR #280 迁移到 `re_editor`，25 文件 +1933/-1215 |
| 2026-06-14 | **PR #280 关闭未合并**（搁置近 5 个月） |
| 2026-10-09 | v1.3.1 终于引入 `re_editor` |
| 2026-10-12 | #186 关闭 |
| 2026-10-14 | **#1238 新问题**：输入法全选/选择失效、删除偶发失效（用户直接写「可能和此前 #186 的修复有关」） |
| 2026-10-17/18 | 提交 `4c59b7a52`「preserve Android IME edits in long messages」，**+8535/-199，60+ 文件**，含手写 `BaseInputConnection` 镜像、`MethodChannel` 路由、fork `re_editor` |

**结论**：Flutter 的文本输入层是抽象泄漏。盖不住时只能掀开抽象、直接对接原生协议，
然后你就在维护两套东西（Flutter 侧渲染 + 原生侧输入）。kelivo 现在 `dependencies/`
下 fork 了 10 个包（re_editor、terminal_view、flutter_math_fork、mcp_client、downsize、
flutter_tts……），每一个都是「上游不改我自己改」。

**本方案避开了这个陷阱**：文本输入是平台专属能力，交给平台控件；Rust 核心只承担
真正同构的逻辑。

---

## 3. 架构

### 3.1 分层

```
┌─────────────────────────────────────────────────┐
│  Windows (WPF/C#)  │  Linux (GTK4/Rust)  │  Android (Compose+EditText/Kotlin)
│  UI 层：消费事件流，渲染，收集输入               │
└─────────┬──────────────┬───────────────┬────────┘
          │ FFI(CS)      │ 无 FFI(同语言) │ FFI(Kotlin)
┌─────────▼──────────────▼───────────────▼────────┐
│              Rust Core（无头引擎）                │
│  · 请求构造 / 流式解析 / provider 适配            │
│  · 上下文管理（四种模式，见设计文档 v1.1）        │
│  · 事件流（原子单元、消息 ID、逻辑时钟）          │
│  · 存储（SQLite）/ 归档层 / undo 快照            │
│  · 工具 trait 定义（实现放平台侧）               │
└─────────────────────────────────────────────────┘
```

### 3.2 核心边界（关键设计）

**core 必须是「无头引擎 + 事件流」，不含任何 UI 状态。**

理由：你的设计天然是 event-sourced 的（消息 ID、原子单元、归档层、undo 快照、fork 会话），
与 Rust 所有权模型契合。UI 只需订阅事件流并渲染。

```rust
// 概念接口（非最终签名）
pub trait Engine {
    fn send(&mut self, input: UserInput) -> StreamId;
    fn subscribe(&self) -> EventStream;        // UI 侧唯一入口
    fn snapshot(&self) -> SnapshotId;          // undo 用
    fn restore(&mut self, id: SnapshotId) -> Result<()>;
}

pub enum Event {
    TurnStarted { id: MsgId, at: LogicalClock },
    Delta { id: MsgId, text: String, reasoning: Option<String> },
    ToolCall { id: MsgId, call: ToolCall },
    TurnCompleted { id: MsgId, usage: Usage },
    ContextCompressed { from: SnapshotId, to: SnapshotId, archived: Vec<Ref> },
    Error { kind: ErrorKind, retryable: bool },
}
```

**硬约束**：

- **panic 不能跨 FFI**：所有导出函数包 `catch_unwind`，panic 转 `Result::Err`
- **工具执行放平台侧**：core 只定 trait（Android 的 SAF / scoped storage 权限模型与桌面不同）
- **ID 用全局唯一 + 逻辑时钟**：事后改代价极大
- **异步**：core 内部用 `tokio`，FFI 边界暴露 `async`（UniFFI 支持映射到 Kotlin `suspend` / C# `Task`）

### 3.3 FFI 策略

| 平台 | UI 语言 | 桥接方式 |
|---|---|---|
| **Linux** | Rust（gtk4-rs） | **无需 FFI**——core 作为 Rust crate 直接 `use` |
| **Android** | Kotlin | **UniFFI 内置 Kotlin 绑定**（一等支持，Mozilla 生产验证） |
| **Windows** | C# | **`uniffi-bindgen-cs`**（NordSecurity 第三方绑定） |

UniFFI 事实（2026-10 核实）：

- **一等支持：Kotlin、Swift、Python、Ruby**（Ruby 仅维持）
- **第三方：C#**（`NordSecurity/uniffi-bindgen-cs`）、Go、Dart、Java、Node、Haskell
- 用于 Firefox mobile/desktop：核心写一次 Rust，同时生成 Kotlin（Android）+ Swift（iOS）绑定
- 支持 async Rust 函数跨 FFI，映射到目标语言的 async/await
- **未到 1.0**，官方定位「生产可用，但进阶特性升级时可能破坏兼容」

**风险**：Windows 的 C# 绑定是第三方维护，成熟度低于 Kotlin。缓解：
若 `uniffi-bindgen-cs` 出问题，退路是**手写 C ABI**（core 暴露 `extern "C"` 函数 + `cdylib`），
C# 侧用 `P/Invoke`。这条退路永远可用，但要自己管内存生命周期。

---

## 4. 存储设计（SQLite）

### 4.1 为什么 SQLite

- 单文件、无服务、事务安全（ACID），崩溃恢复有保障
- Rust 侧 `rusqlite` / `sqlx` 成熟
- 三平台一致（Android 自带 SQLite，桌面可 bundle）
- **单写者模型**与「本地优先 + 共享文件夹同步」契合

### 4.2 模式

```sql
PRAGMA journal_mode = WAL;        -- 崩溃韧性 + 读写并发
PRAGMA synchronous = NORMAL;      -- WAL 下的安全/性能平衡点
PRAGMA foreign_keys = ON;         -- 显式开启（默认关闭）
```

**单写者纪律**：SQLite 设计为单写者。两个进程写同一文件**最终必然损坏**。
共享文件夹同步时必须用**文件锁 + 会话级独占**，或改用「每设备一份 + 事件合并」。

### 4.3 表结构（概念）

```sql
-- 会话
conversations(id TEXT PK, title TEXT, created_at INT, updated_at INT,
              schema_version INT, device_origin TEXT,
              model TEXT NULL)             -- 最近一次用的模型；NULL = 还没跑过（D101）

-- 消息（原子单元）
messages(id TEXT PK, conv_id TEXT FK, role TEXT, content TEXT,
         reasoning TEXT, created_at INT, logical_clock INT,
         unit_kind TEXT,              -- user_turn | assistant_turn | tool_call | tool_result
         archived_ref TEXT NULL)

-- 归档层（原文不删）
archive(ref TEXT PK, conv_id TEXT FK, content BLOB, tokens INT,
        created_at INT, note TEXT)

-- 事件日志（可选，用于审计与重放）
events(id INTEGER PK AUTOINCREMENT, conv_id TEXT, kind TEXT,
       payload BLOB, at INT)

-- 元数据
meta(key TEXT PK, value TEXT)       -- 含 schema_version、最后备份时间等
```

**不变量**（写入前校验，启动时验证）：

- 每个 `messages.conv_id` 必须存在对应 `conversations.id`
- `messages.id` 全局唯一
- `logical_clock` 在同一会话内单调
- `archived_ref` 若非空，必须能在 `archive` 中解析
- `tool_call` 与 `tool_result` 必须成对（原子单元完整性）

### 4.4 启动校验：三层安全设计

用户要求：「发现哪部分数据语法不正确可以先阻塞，询问用户是否把错误部分恢复默认」。
落成三层，**由快到慢、由粗到细**：

```
启动
 ├─ L0 文件层：文件存在？可打开？大小合理？WAL/shm 是否残留？
 │    └─ 失败 → 提示「存储不可用」，提供 重建 / 指定其他目录
 │
 ├─ L1 结构层：PRAGMA quick_check(1)   ← 100ms 预算（OpenAI codex 的做法）
 │    ├─ 通过 → 进入 L2
 │    └─ 失败 → PRAGMA integrity_check（全量）→ 判定损坏范围
 │         └─ 阻塞 → 展示损坏详情 → 提供三选项（见 §4.5）
 │
 ├─ L2 模式层：schema_version 是否匹配？迁移是否完整？
 │    └─ 不匹配 → 执行迁移；迁移失败 → 阻塞 + 提供回滚
 │
 └─ L3 语义层：逐记录校验不变量（§4.3）
      └─ 失败 → 隔离坏记录（不删）→ 阻塞 → 询问「是否将该部分恢复默认」
```

**L3 是用户特别要的那一层**。它不是数据库级损坏，而是**应用级语义损坏**，例如：

- 某条 `messages` 的 `content` 是非法 UTF-8 或 JSON 解析失败
- `tool_call` 没有配对的 `tool_result`（截断的原子单元）
- `archived_ref` 指向不存在的归档
- 时间戳超出合理范围（时钟回拨导致）

处理原则：

1. **隔离而非删除**：坏记录移入 `quarantine` 表或导出到 `*.quarantine.jsonl`
2. **阻塞而非静默修复**：不猜测用户意图，展示「哪些记录坏了、坏在哪、影响什么」
3. **恢复默认是显式选择**：用户确认后才替换，且**替换前先把原值写入隔离区**
4. **可回滚**：隔离区保留，提供「撤销上次恢复」

### 4.5 损坏处置选项（L1 失败时）

按优先级：

| 选项 | 操作 | 代价 |
|---|---|---|
| 1. 从备份恢复 | 用最近验证过的备份替换 | 丢失备份后的变更 |
| 2. 抢救（`.recover`） | `sqlite3 damaged.db ".recover" \| sqlite3 recovered.db`，**扫描原始页**，B-tree 损坏也能救 | 部分行丢失，需人工清理 |
| 3. 导出可读部分 | `.dump` → 只保留可解析的表 | 可能语义不一致 |
| 4. 重建为空库 | 保留损坏文件为证据 | 全丢（但原文归档可能仍在文件系统） |

**铁律**：

- **绝不自动删除**。损坏文件先改名归档（`app.db.corrupt-<timestamp>`）再操作
- **在副本上诊断**，不要直接动主文件
- **恢复后必须重新校验**：对新库跑 `integrity_check` **和** `foreign_key_check`
  （前者不检查外键！）
- **恢复出的库结构合法 ≠ 语义正确**，必须重跑 L3 语义校验

### 4.6 备份策略

- **自动备份**：每次成功启动后、每次 schema 迁移前
- **验证备份**：`integrity_check` 要在**备份上**跑，不只在活动库上。
  「没人验证的备份是希望，不是备份」
- **保留策略**：最近 N 份 + 每周一份
- **同步目录不放备份**：备份与活动库分离，避免同步冲突

### 4.6.1 备份目标：本地目录 / WebDAV（用户 2026-11-22 提）

用户原话：「常见的云备份支持，比如 webdav 什么的」。

**目标抽象**（core 侧 trait，三个实现）：

```rust
pub trait BackupTarget {
    fn put(&self, path: &str, bytes: &[u8], if_match: Option<&str>) -> Result<(), BackupError>;
    fn get(&self, path: &str) -> Result<Vec<u8>, BackupError>;
    fn list(&self, dir: &str) -> Result<Vec<RemoteEntry>, BackupError>;
    fn mkcol(&self, dir: &str) -> Result<(), BackupError>;
    fn delete(&self, path: &str) -> Result<(), BackupError>;
    fn move_to(&self, from: &str, to: &str) -> Result<(), BackupError>;
}
```

实现：`LocalDir`（现在就有，SAF 目录）、`WebDav`（Basic + TLS，够 Nextcloud / 坚果云 / Alist 那一档）、
`S3`（**先不做**，v4 签名自己写要一大段，等真有人要）。

**传输层只改一处**：`transport.rs` 的 `Method` 现在只有 `Get` / `Post`，
加 `Put` / `Delete` / `Head` 与 `Method::Custom(&'static str)`（PROPFIND / MKCOL / MOVE 走它）。
**不要在 WebDAV 里另写一份 HTTP**——两份 HTTP 就是两份超时、两份 TLS、两份错误映射。

**原子性**（这一条是备份能不能用的分界）：

```text
PUT  <name>.part      →  HEAD 核对 Content-Length 与本地字节数
                      →  MOVE <name>.part → <name>（Overwrite: T）
```

不直接 PUT 正式名：传一半断网会在服务端留下一个「看着像备份」的坏文件，而恢复的时候没人知道它是坏的。
同理，`manifest.json` 里写 sha256 与字节数，恢复前算一遍再动库。

**恢复顺序**（与 §4.4 那三层对齐）：

```text
GET 到临时目录 → 校验 sha256 / 字节数 → schema_version 高于本地？拒绝并提示升级
              → 备份当前库（改名，不删）→ 替换 → 重跑 L1 quick_check + L3 语义校验
              → 任一失败：把当前库换回来，报哪一步失败
```

**不做增量**（第一版）：整库 + 附件 tar.gz 通常几十 MB，一次传完比维护增量索引便宜；
真上 1 GB 再说。**不做端到端加密**（那是 §5.3，另算）。

**凭据不进库**：WebDAV 口令进 `Vault`（已有），备份 manifest 里只写「用了哪个 target 的 id」，
不写口令、不写完整 URL（URL 里可能带 token）。

**定时**：只在充电 + 有网时跑，失败退避 15 分钟起。Android 侧 `WorkManager`；
**不碰前台服务**（那是 0.15aj 那条的地盘，另外等真机）。

### 4.7 版本兼容

- 每次写入 `schema_version`
- 迁移是**单向**的（向前），回滚靠备份
- 迁移失败 → 阻塞，不静默降级
- 跨平台打开时若版本高于本地代码支持 → **拒绝打开**并提示升级客户端
  （避免旧客户端写坏新结构）

---

## 5. 数据互通

### 5.1 三种方案

| 方案 | 机制 | 优点 | 缺点 |
|---|---|---|---|
| **A 共享文件夹** | 同步目录（Syncthing / iCloud / OneDrive / 局域网） | 本地优先、无服务依赖、实现简单 | 冲突需自己解决；SQLite 文件级同步有风险 |
| B 自建服务 | 中央 API + 客户端同步 | 冲突解决集中 | 要运维、要账号、离线体验差 |
| C P2P | 设备直连 | 无中心 | 实现复杂，移动端穿透难 |

### 5.2 推荐：A（本地优先 + 共享文件夹），但**不直接同步 SQLite 文件**

**直接同步 `.db` 文件是危险的**：SQLite 的单写者模型 + WAL 的 `-wal`/`-shm` 附属文件，
在文件级同步下会产生撕裂状态。

**正确做法：同步「变更日志」，本地各自物化**。

```
设备 A:  SQLite(本地物化) ← 重放 ←  changes/device-A/0001.jsonl
设备 B:  SQLite(本地物化) ← 重放 ←  changes/device-A/0001.jsonl
                                      changes/device-B/0001.jsonl
```

- 每台设备写自己的追加式变更日志（JSONL，append-only）
- 同步层只搬这些日志文件（天然追加，冲突少）
- 每台设备启动/定时重放全部日志，物化到本地 SQLite
- 日志用 **全局唯一 ID + 逻辑时钟**（你的设计已有），冲突解决用确定性规则

**冲突解决**：

- 消息是 append-only 的（对话不会原地改），冲突面很小
- 真正的冲突点是：会话标题、消息编辑、删除操作
- 规则：**逻辑时钟大的胜出；时钟相同则按 device_id 字典序**（确定性，可复现）

### 5.3 加密（可选，后续）

共享文件夹意味着数据离开设备。若同步到云：

- 方案：**端到端加密**，密钥由用户口令派生（Argon2id），日志文件加密后再落地
- 不放服务端，客户端自己加解密
- 第一版可以先只支持局域网/自建 Syncthing（信任网络）

### 5.4 远程会话模式（2026-11-22 用户提，**与同步是两件事**）

用户原话：「进入一个远程连接模式，通过外部调用接口进行连接，比如手机上的 NMoi 连接我们未来的其他平台的
NMoi 客户端，从而做到这边只传数据到那边，具体的运行在那边，这边加载的也是那边的数据」。

**先分清两件事**，混起来就会做出一个两份数据互相顶掉的东西：

| | 同步（§5.2） | 远程（本节） |
|---|---|---|
| 库在哪 | 每台设备各一份 | **只有一份，在远端** |
| 冲突 | 要变更日志 + 确定性合并 | 不存在（本地没有副本） |
| 断网 | 照常用，回头合 | 那几行变灰 |
| 工作量 | 数月（日志、重放、冲突、三端物化） | 数周 |

**判断：先做远程，不做同步。** 用户真正要的是「在手机上接着用桌面那份数据」，远程就满足了；
而同步要的冲突解决在「只有一份库」的前提下根本不存在。等远程做完，同步的需求会被削掉一大半。

**本地存什么**：只存「我知道哪些远端」——`remotes.json`：`{id, name, base_url, token, last_ok_at}`。
**不存内容**：正文、图片、附件一个字都不落本地（落了就有两份，而两份迟早不一样）。
会话列表 = 本地会话 ∪ 各远端会话，每行带来源徽标；远端 id 只在显示层加前缀，不写库。

**接口切在哪一层**：`NmoiFfi.kt` 是唯一的 Kotlin 门面（556 行 / 94 个符号），切在**它下面**：

```kotlin
internal interface Link { suspend fun call(symbol: String, args: JSONObject): Result<JSONObject> }
internal object LocalLink : Link                       // 现有 JNI 实现
internal class RemoteLink(base: String, token: String) : Link   // POST /rpc/<symbol>
```

- 94 个方法体从直接调 JNI 改成 `link(session).call("nmoi_xxx_json", args)`。机械改动，但量大，
  分两批：先 storage 那一组（约 40 个），再 turn / workspace（约 30 个）。
- **句柄翻译**：本地 handle 是 u64，远端有自己的一套。`handles.rs` 加 `Entry::Remote(RemoteHandle)`，
  进远端会话时本地分配新 id 并记住 `local → remote`；关的时候先在远端关、再删本地格。
- **流式不用改**：界面本来就是轮询 `turn_busy`（`LiveStream` 那套「见过 `running:true` 才算终态」的规矩），
  远程只是把这次轮询从 JNI 换成 HTTP。**这是这次重构能小下来的唯一原因**——如果当初选了 SSE 推，
  现在就得在两边各实现一遍断线重连。
- **附件与文件**：`putAttachment` 要传字节，走 base64 不划算（一张图几 MB）。
  另开 `POST /files?session=&name=`（`Content-Length` + 原始字节，回 `{id}`）与 `GET /files/{id}`（流式回）。
  下载的 `Content-Length` 必须对，否则界面拿不到进度、也不知道收完没有。
- **本地不能跑轮**：远程会话的 `TurnRunner` 直接调远端 `turn_run`；本地那份 `GeneratingTracker`
  **从远端 busy 派生**，不自己 mark。两边各有一份「在跑」就会出现「第二个入口一点就吃 busy」
  （这个毛病文档里已经有过一次）。
- **断线**：远端不可达 → 那一行变灰 + 一句「远端离线」，不删、不缓存正文、不假装还能发。

**重构量（诚实估）**：新增 `api_server.rs`（约 500 行 + 测试）、`remote.rs`（Link 客户端 + 句柄映射，
约 400 行）、FFI 3 条 + JNI 3 条、Kotlin `Link` / `RemoteLink`（约 200 行）、`remotes.json`（约 100 行）；
改动 `NmoiFfi.kt` 94 处、`handles.rs` 一种 Entry、`PageCache` 的键加来源、抽屉加来源徽标与「添加远程」、
`SendQueue` 认来源。

**不做成一次**：先做 §5.5（本机能验到底），再做远程的**只读一半**（浏览远端会话），
最后才做「在远端跑轮」。只读一半就能验完句柄映射、分页、附件下载三样；跑轮那一步再验流式与审批。

### 5.5 外部调用接口（默认关，开在 `127.0.0.1:7989`）

用户原话：「做一个外部调用接口……这个是可开关的，默认关闭，打开的话会在 localhost 的某个端口启动服务，
默认就 7989」。

**位置**：`crates/core/src/api_server.rs`，形状照 `proxy_server.rs`（std `TcpListener` + 线程 + 停止标志）；
FFI 三条（`start` / `stop` / `status`）+ JNI 三条。

**不加依赖**：手写 HTTP/1.1 最小子集——只要 `Content-Length` 与 `Connection: close`，
不做 chunked、不做 keep-alive、不做 100-continue。项目里手写 SOCKS5 已经开过这个头，
zip 那条禁令也是同一个意思：能自己写清楚就别引依赖。

**绑定与认证**：

- 默认只绑 `127.0.0.1`。跨设备（远程模式）必须**显式**改绑 `0.0.0.0`，并且这时界面要红字说明
  「同一网络里谁都能连」。
- `Authorization: Bearer <token>`，开启时随机生成 32 hex，存 prefs（设备本地），界面上能看、能换。
  **loopback 也要 token**：同设备别的 App 连得到 loopback，`proxy_server` 的注释已经踩过这一条。
- 端口被占 → 报 `port_in_use` 并给「换一个端口」的入口，**不静默 +1**（静默换端口 = 外部工具连不上，
  而且没人知道为什么）。

**两层，别混**：

| 层 | 路径 | 给谁 | 稳定性 |
|---|---|---|---|
| 控制面 | `/v1/*` | 人和别的 AGENT / 工具 | 语义化，改要通知 |
| 数据面 | `/rpc/<symbol>` | 另一台 NMoi | 跟着 FFI 走，每周都在变 |

控制面（第一版就这些，够一个外部 agent 干完活）：

```text
GET  /v1/health                          {version, schema, busy:[session…]}
GET  /v1/conversations?limit=&cursor=
GET  /v1/conversations/{id}/messages?before=&limit=
POST /v1/conversations/{id}/messages     {text, images?, files?} → 202 {turn_id}
GET  /v1/conversations/{id}/turn         {running, text_tail, text_len, round, calls:[…]}
POST /v1/conversations/{id}/cancel
GET  /v1/conversations/{id}/changes?turn= 文件变更（见 §5.6）
POST /v1/conversations/{id}/approve      {call_id, decision}
```

`/turn` 就是 `turn_busy` 那一份，语义已经定型（`LiveStream` 的规矩都是按它写的），
**不要为外部接口另造一份形状**——两份形状迟早对不上。

数据面：`POST /rpc/<symbol>`，body = 该符号的参数 JSON，回包 = 该符号原来的 JSON。
`GET /rpc/capabilities` 回 `{abi: 1, symbols: [...]}`，**版本对不上就拒绝连接**，不给半份数据。

为什么分两层：控制面是「别人写好的工具」依赖的，改一次要通知所有人；数据面跟着 FFI 走。
混成一层，等于让 FFI 的每次改动都变成一次外部破坏性变更。

### 5.6 文件变更跟踪（用户 2026-11-22 提）

用户原话：「调用子 AGENT 的时候应该选择性的屏蔽一些内容，就是这个调用输出的内容一般来说应该是
『哪次调用子 AGENT 修改了哪些文件』什么的，记得做好文件跟踪，而且平常主 AGENT 执行一些命令
还是什么记得在助手消息的结尾加上文件变动」。参考图：变更芯片按文件名排、最多三排、
点开是文件详情（图标 + 名字 + 大小 · 时间 + 路径 + 打开方式 / 分享 / 导出）。

**单一出处**：`crates/core/src/changes.rs`：

```rust
pub struct FileChange { pub path: String, pub kind: ChangeKind, pub added: i64, pub removed: i64 }
pub enum ChangeKind { Created, Modified, Deleted }
```

`TurnReport.changes: Vec<FileChange>` 带出，Kotlin **不自己数**（两个地方各数一次，迟早对不上，
这条已经吃过一次亏）。

**四条规矩**：

1. **按路径合并**：同一轮里同一文件改三次 = 一条，`added` / `removed` 累加。用 `BTreeMap`，
   排序天然是路径 A–Z（用户要的顺序），不必另写比较器。
2. **只统计工具自己的写操作**：`write_file` / `edit_file` / `delete`。**shell 不算**——
   从命令行猜「它改了哪些文件」是猜，猜错比不列更糟。要统计 shell 的副作用得接 `strace` 一类，
   不在这一版。
3. **只存结束**（铁律③）：轮开始清空，轮结束写 `messages.changes_json`（schema v21，与 `preview` 同类）。
   进行中的变更不进库。
4. **上限与截断**：core 侧上限 200 条，超了 `truncated: true`，界面如实写「仅列出部分文件」；
   界面最多 3 排（用户原话），第 4 排起收进「+N」。

**子 AGENT 的回包契约**（0.14v 那条一直没实现，这里定死）：

```json
{ "conversation_id": "…", "rounds": 3, "tokens": 8123, "truncated": false,
  "reply": "改了 3 个文件：a.rs(+12 −4)、b.rs(新建)、c.rs(删)",
  "changes": [{"path": "a.rs", "kind": "modified", "added": 12, "removed": 4}] }
```

- **默认只回摘要 + 变更清单**，不回子会话全文。全文在子会话里，主 AGENT 要就自己去取
  （`read_file`，或以后加 `sub_agent_transcript`）。
- 理由：子 AGENT 的输出会进主上下文，全文会把主上下文吃掉、也会把「我在干什么」冲掉；
  而「改了哪些文件」是**可核对的**（铁律①：能在界面上指出来）。

---

## 6. 实施计划

### 阶段 0：地基（1–2 周）

- [ ] Rust workspace 骨架：`core/`、`ffi/`、`storage/`
- [ ] 数据模型定义（消息、原子单元、逻辑时钟）
- [ ] SQLite schema + 迁移框架 + **L0/L1/L2 校验**
- [ ] 事件流接口定型
- [ ] 单元测试：校验逻辑、冲突解决、迁移

### 阶段 1：Linux 端打通（2–3 周）

选 Linux 先做的理由：**UI 是 Rust，无 FFI**，开发循环最快；长线 agent 任务主场是桌面。

- [ ] `gtk4-rs` 外壳：会话列表 + 消息列表（虚拟化）
- [ ] `GtkTextView`/`GtkSourceView` 输入区
- [ ] 接 core 事件流，流式渲染
- [ ] 存储接 SQLite + 启动校验
- [ ] **性能基线测量**（关键）：10 万行消息列表滚动帧率、长文本输入延迟

### 阶段 2：FFI 与 Android（3–4 周）

- [ ] UniFFI 接口定义（`.udl` 或 proc-macro）
- [ ] `cargo-ndk` 构建 Android 库
- [ ] Kotlin 绑定接入
- [ ] Compose 外壳 + **`AndroidView` 包 `EditText` 输入岛**（§2.3）
- [ ] 焦点链显式串联（Compose/View 边界）
- [ ] Android 存储：`app-private` 目录 + SAF 导出

### 阶段 3：Windows（3–4 周）

- [ ] `uniffi-bindgen-cs` 生成 C# 绑定
- [ ] WPF 外壳 + AvalonEdit 输入区
- [ ] 处理 `TruncateLongLines`（超长单行）
- [ ] MSIX 打包

### 阶段 4：互通与安全（2–3 周）

- [ ] 变更日志格式 + 重放器
- [ ] 共享文件夹同步 + 文件锁
- [ ] L3 语义校验 + 隔离区 + 恢复默认流程
- [ ] 备份与恢复 UI

### 阶段 5：上下文管理接入（持续）

按设计文档 v1.1 的优先级：

1. 归档层 + 回灌 + 指针格式（**注意 §2.3.0 任务锚点是强制项**）
2. 写入时刻路由规则
3. 溢出安全阀
4. 模式 3（打标 + epoch）
5. 模式 4（灰烬，最后做）

---

## 7. 风险登记

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | `uniffi-bindgen-cs` 不成熟 | Windows 端桥接不稳 | 退路：手写 C ABI + P/Invoke |
| R2 | AvalonEdit 超长单行掉帧 | 用户粘贴大段代码时卡 | `TruncateLongLines`；限制单行渲染长度 |
| R3 | WinUI 3 文本栈演进 | 若选 WinUI，输入框可能踩坑 | 第一版选 WPF，避开 |
| R4 | 三套 UI 维护成本 | 工作量 3 倍 | UI 做薄（只消费事件流）；共享 UI 逻辑用 core 输出的事件描述 |
| R5 | SQLite 文件级同步撕裂 | 数据损坏 | 不同步 `.db`，只同步变更日志（§5.2） |
| R6 | 迁移失败 | 用户数据不可用 | 迁移前自动备份 + 阻塞不静默降级 |
| R7 | 三平台时钟不一致 | 逻辑时钟错乱 | 用逻辑时钟（Lamport）而非墙上时钟排序 |
| R8 | Android 存储权限模型差异 | 工具执行失败 | 工具实现放平台侧，core 只定 trait |
| R9 | 缓存键跨设备不共享 | 首轮全冷 | 无法避免；可用稳定前缀提高命中 |

---

## 8. 未决项（需要你拍板）

| # | 问题 | 选项 | 影响 |
|---|---|---|---|
| Q1 | Windows UI 用 WPF 还是 WinUI 3 | WPF（稳）/ WinUI 3（现代） | 输入框风险 vs 观感 |
| Q2 | 同步方案 A/B/C | 共享文件夹 / 自建服务 / P2P | 运维成本 vs 体验 |
| Q3 | 是否端到端加密（第一版） | 是 / 否（先信任网络） | 复杂度 |
| Q4 | 变更日志粒度 | 每条消息 / 每轮 / 每会话 | 同步流量 vs 冲突面 |
| Q5 | 归档原文存哪 | SQLite BLOB / 文件系统 | 库体积 vs 检索速度 |
| Q6 | 第一阶段先做 Linux 还是先做 Android | Linux（无 FFI，快）/ Android（移动优先） | 见 §6 阶段 1 理由 |
| Q7 | 同步 vs 远程先做哪个 | 远程（一份库在远端）/ 同步（变更日志） | 见 §5.4：远程能把「在手机上接着用桌面那份」直接满足，同步要的冲突面在远程里不存在 |
| Q8 | 外部接口绑 `127.0.0.1` 还是 `0.0.0.0` | 默认 loopback，跨设备要显式打开 | 打开那一下要红字说「同网段谁都能连」 |
| Q9 | 备份目标第一版做几个 | 本地目录 + WebDAV；S3 不做 | 见 §4.6.1：S3 签名自己写一大段，等真有人要 |
| Q10 | 文件变更要不要统计 shell 的副作用 | 不统计（只认工具自己的写操作） | 从命令行猜是猜；猜错比不列更糟，见 §5.6 |

---

## 9. 给下一个会话的交接要点

**必须带上的文件**：

1. `platform-client-plan.md`（本文件）
2. `context-modes-design-v1.1.md`（上下文管理设计，含实验结论）
3. `context-experiments-report.md`（实验原始数据与判分器教训）

**核心背景（一句话）**：

> 跨平台 UI 框架在文本输入层必然失败（IME 是平台协议）；kelivo 为修一个输入框卡顿
> 付出 +8535/-199、60+ 文件的代价。本方案用 Rust 无头核心 + 各平台原生 UI 避开这个陷阱。

**下一步动作**：确认 §8 的 Q1–Q6，然后从阶段 0（地基）开始。

---

## 附录：证据来源

| 结论 | 来源 |
|---|---|
| Compose `BasicTextField` 重实现 IME 协议且做错镜像（1.12 仍如此） | GitHub PR coat/rommdroid#4 |
| Compose `BasicTextField` 曾修 extracted-text 更新 bug | Android Compose Foundation 发布说明 |
| kelivo #186 完整生命周期 | `gh api repos/Chevey339/kelivo/issues/186` + comments + timeline |
| kelivo 最终修法（原生 IME + fork re_editor） | commit `4c59b7a52`（+8535/-199，60+ 文件） |
| WinUI 3 性能改进（41%/63%/45%/25%） | microsoft/microsoft-ui-xaml discussion #11096（2026-05） |
| WinUI 3 仍被指比 WPF 慢 | DevClass / Techzine 报道（2026-05） |
| AvalonEdit `Rope<char>` + UI 虚拟化 | AvalonEdit DeepWiki 核心架构 |
| AvalonEdit 超长单行问题 | issue #11 + `TruncateLongLines` 实践 |
| GtkTextView VBO 批量绘制 | GNOME blog（chergert，2023-02） |
| GtkTextView model/view + UTF-8 offset | GTK4 官方文档 |
| UniFFI 语言支持矩阵 | mozilla/uniffi-rs README + user guide |
| SQLite 损坏检测与恢复 | PRAGMA integrity_check / quick_check / .recover 官方文档 |
| 启动 quick_check(1) + 100ms 预算 | openai/codex PR #49701 |
| SQLite 单写者纪律 | SQLite 官方 + 多篇实践总结 |
