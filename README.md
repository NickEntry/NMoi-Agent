# NMoi-Agent

一个本地优先的 LLM 客户端。Rust 无头核心 + 各平台原生 UI；数据留在自己设备上。

> 状态：**stage 0（地基）代码完成，测试待 CI 执行。**
> 进度写在 [`docs/PLAN.md`](docs/PLAN.md)，每一项做到哪一步都在表里。
> 决策与理由写在 [`docs/decisions.md`](docs/decisions.md)。

---

## 这是什么

- **核心**（Rust，`crates/core`）：请求构造、流式事件、上下文管理四模式、归档层、逻辑时钟、工作区文件工具。
- **存储**（`crates/storage`）：SQLite，WAL，L0–L3 启动校验，隔离区与"恢复默认"，可验证备份。
- **边界**（`crates/ffi`）：C ABI，panic 绝不跨边界。
- **诊断入口**（`crates/cli`）：`NMoi-Agent doctor / init / backup`。

UI 层的原则（来自 `docs/design/platform-client-plan.md`）：
**文本输入是平台专属能力，交给平台控件**。
Compose 的 `BasicTextField` 自己重实现 IME 协议且镜像出错（横屏全屏提取编辑时表现为退格残留、密码框明文闪一下），
所以 Android 用 Compose 外壳 + `AndroidView` 包原生 `EditText`；Linux 用 GTK4；Windows 用 WPF + AvalonEdit。

## 关键设计（已实现的部分）

| 能力 | 说明 |
|---|---|
| 原子单元 | `assistant(tool_calls) + 全部 result` 才算闭合；未闭合禁止编辑。所有编辑接口只接受 `unit_id` |
| 内容形态 F1–F5 | 全文 / 截断+尾注 / 掩码 / 指针 / 索引；除全文外都必须带归档 ref |
| 写入时刻路由 | 9 条确定性规则，命中即停；`protected` 与用户输入永不路由 |
| 归档与回灌 | 内容寻址 `ref`，取回是**确定性**的（不存在就报错，不做模糊匹配） |
| 写入时刻脱敏 | `ghp_` / `sk-` / `AKIA` / 私钥块 / URL 凭据 / JWT 六类，导出时二次扫描 |
| 三种保护标记 | `protected`（禁压缩）/ `pinned`（禁重排）/ `locked`（用户锁：禁编辑、**可压缩**） |
| 上下文分界线 | 一条线把窗口切成两段；压缩工具画线，用户也能手动插线 / 长按删线 |
| 块级编辑 | 正文、思考块、工具调用块可分别编辑；每次编辑留前后哈希与 revision |
| 工作区工具 | 读 / 写 / 编辑 / 看目录 / 查找 / 搜索；三级截断，`force_full` 优先级最高 |
| 容器（可选） | 只在用户显式开通后提供 `bash`；选型与许可影响见 `docs/sandbox.md` |
| 退化检测 | n-gram 连续重复（流式 + 整段）、多样性、跨轮相似度代理 |
| 预算与触发器 | 缓存代价 `(L−i)(w−r)`、回本周期、回灌双份成本、安全阀升级（软→强制→截断） |

## 构建与检查

```bash
cargo check --workspace --all-targets     # 本地必做（语法/类型）
cargo test -p nmoi-core --lib -- <模块>::  # 本地可跑：短测试，单次 ≤ 60s（D16.1）
cargo test --workspace                     # 全量留给 CI
bash scripts/target_usage.sh               # 看 target 多大；--clean 是整目录清（见 docs/build.md）
```

CLI：

```bash
cargo run -p nmoi-cli -- version
cargo run -p nmoi-cli -- doctor ./nmoi.db     # 输出 JSON 体检报告；退出码 0/2/3
cargo run -p nmoi-cli -- backup ./nmoi.db ./backup/nmoi.db
```

## 目录

```
crates/core/     无头核心（模型 / 路由 / 归档 / 预算 / 事件流 / 引擎 / 工作区 / 编辑 / 分界线 / 传输 / 流式解析 / provider 运行时）
crates/storage/  SQLite schema、迁移、L0–L3 校验、隔离区与恢复、备份
crates/ffi/      C ABI（panic 不跨边界）
crates/cli/      NMoi-Agent 诊断入口
data/            模型元数据快照（由 scripts/fetch_models.py 生成）
scripts/         抓取 / 生成脚本
docs/            PLAN.md（进度）、decisions.md（决策）、provider-runtime.md（协议字段来源与实测）、design/（上游设计文档）
```

## 真机冒烟（需要可达的模型端点）

CI 上没有可达的 LLM 端点，所以流式那一路靠一个手动跑的例子验证：

```bash
NMOI_SMOKE_MODEL="<模型名>" cargo run -p nmoi-core --example provider_smoke
```

它会把一次真实请求走完：请求构造 → 明文 HTTP → SSE 解析 → 归一事件 → 引擎落库，
并把 `usage` 与首字延迟打出来。环境变量表在同名文件的顶部注释里，
实测记录在 `docs/provider-runtime.md`。

压缩那一路有单独的例子（脚手架 → 多步草稿 → 覆盖率校验 → 归档 + 画线）：

```bash
NMOI_SMOKE_MODEL="<模型名>" cargo run -p nmoi-core --example compress_smoke
```

它走的是**非流式**（`complete()`）——实测本机代理在流式模式下根本不下发 `tool_calls`，
见 `docs/decisions.md` D46。想抓我们实际发出去的请求体，设 `NMOI_SMOKE_DUMP=/tmp/req.json`。

工具循环有第三个例子，让模型真的读文件、改文件、再读回来确证：

```bash
NMOI_SMOKE_MODEL="<模型名>" cargo run -p nmoi-core --example tool_smoke
```

它在临时目录里建一个工作区，跑完直接读磁盘核对——模型说改好了不算，文件变了才算。

## 致谢

- **kelivo**（Chevey339，AGPL-3.0）：参考了它的**部分 UI 设计和部分交互设计**。
  只参考设计与行为，未复制任何代码（AGPL 与 GPL 不兼容，见 `docs/decisions.md` D15）。
- kelivo 自身标注的设计来源 **RikkaHub**，以及它提到的 **Minis**、**PRoot / Termux**（沙盒基础）。
- 模型元数据来自 [models.dev](https://models.dev)（MIT）、OpenRouter 与 aihubmix 的公开 API，
  只取事实字段；来源与刷新方式见 `docs/model-data.md`。

## 许可

**GPL-3.0-only**（完整文本见 `LICENSE`）。本项目不计划商用。

用的第三方东西**不并进** `LICENSE`，而是单独记在
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)（聚合说明）与 `LICENSES/`（各许可全文）。
这份声明随每个分发物走——APK、安装目录、便携包、导出的图标包，都带一份，
细则见 [`docs/packaging.md`](docs/packaging.md)。

切到 GPL 的原因写在 `docs/decisions.md` D15：容器可选项要分发 PRoot（GPL-2.0-or-later），
整体取 GPL-3.0 最干净。工作区文件工具本身不依赖容器，见 `docs/sandbox.md`。
