# 持久会话（设计稿，未动工）

> 用户 2026-11-02 提的。这一份是**设计**，不是实现记录；落地时按这里的分批走，
> 改了什么再追加到 `notes/decisions.md` 的 D 编号里。
>
> 一句话：现在的两把 shell 是「起进程 → 等它死 → 杀进程组」，**调用结束会话就没了**。
> 持久会话要的是「进程活着、输出留着、下一轮还能接着说话」。

## 1. 两类会话

| | 临时会话（ephemeral） | 服务启动（service） |
|---|---|---|
| 从哪来 | 模型调 `Session-Open` 现开 | 用户在管理页配好，点开关启动 |
| 有没有启动配置 | 没有（命令就是这一次的参数） | 有（名称/命令/参数/工作目录/环境变量/工作区） |
| 活多久 | 应用这次运行期间；进程自己退出也算结束 | 同上，但**关掉开关就销毁** |
| 应用重启后 | 列表为空（没有配置可恢复） | 配置还在，**会话不会自动起来**（用户明确要求） |
| 谁能关 | 模型（`Session-Close`）或用户在管理页 | **只有用户**在管理页关；模型关会被拒绝 |

两类都出现在「设置 → 工具与工作区 → 持久会话」里，分两个子页。

**为什么服务不自动启动**：一条配好的 `python -m http.server` 或 `adb` 桥在开机时自己跑起来，
用户看不见它、也不知道它在吃电。开关就是「现在起」和「现在停」，不是「下次开机也起」。

## 2. 工具面

名字跟现有的 `System-Shell` 一致，用连字符（OpenAI / Anthropic / Gemini 的函数名规则都接受）。

### `Session-Open`

```json
{
  "name": "dev-server",
  "command": "python3",
  "args": ["-m", "http.server", "8000"],
  "cwd": "site",
  "env": {"PYTHONUNBUFFERED": "1"},
  "block_ms": 1500
}
```

- `command` 必填；`args` 是数组（**不拼字符串**，带空格的参数不会被拆开）。
- `cwd` 相对工作区根，词法校验同 `WorkSpace-EnvirShell`（不许绝对路径、不许 `..`）。
- `block_ms`：**这次调用愿意站着等多久**，0–30000，默认 **1000**。
  - 它**不是超时**：到点不会杀进程，也不算失败；进程照跑，只是这次调用先回去。
  - 规则是**等满这个窗口再回**（不是"见到第一个字就回"）：这段时间收到的输出一起给。
    唯一的提前返回是**进程自己退出**——它已经结束了，没必要再站。
  - `Session-Read` 的 `wait_ms` 是另一条规则：**一有新输出就回**，到点没新东西才空手回。
    两者不一样是故意的：`Open/Write` 问的是"我做完之后它有什么反应"（给一个稳定窗口），
    `Read` 问的是"有消息了吗"（有就立刻给）。
  - `0` = 不等，立刻把 `session_id` 交出去（适合"起个服务，我待会儿再来看"）。
    **这时候 `stdout` 可能一个字都没有，但结果里一定有一句 `summary`**
    （`持久会话已运行：s_7f3a1c02（python3 -m http.server 8000）`）——
    工具返回空字符串时模型会以为调用失败了，所以这一句不是装饰。
    代价是**快命令会抢跑**：`echo hi` 可能在返回之后才写完，这次拿到空的 `stdout`、
    `state: "running"`，下一次 `Session-Read` 才看到 `hi` 和 `exited`。这是竞态，不是 bug。
- 返回：

```json
{
  "session_id": "s_7f3a1c02",
  "summary": "持久会话已运行：s_7f3a1c02（python3 -m http.server 8000）",
  "kind": "ephemeral",
  "state": "running",
  "pid": 4812,
  "blocked_ms": 1500,
  "stdout": "Serving HTTP on 0.0.0.0 port 8000 ...\n",
  "stderr": "",
  "cursor": 46,
  "truncated": false,
  "dropped_bytes": 0
}
```

`cursor` 是**这条会话输出流的位置**，后面 `Session-Read` 从这里接着读。
**第一次阻塞期间的输出就在这次返回里**，不用再读一次。

### `Session-Write`

```json
{"session_id": "s_7f3a1c02", "data": "ls -la", "submit": true, "block_ms": 800}
```

`submit=true` 时补一个 `\n`（REPL 要的）；返回形状同 `Session-Open`（含新 `cursor`）。

### `Session-Read`

```json
{"session_id": "s_7f3a1c02", "cursor": 46, "wait_ms": 2000, "max_bytes": 65536}
```

- `wait_ms` 0–30000：这段时间里只要有新输出就立刻返回，没有就等到点。
- `cursor` 省略 = 从「上一次读到的位置」继续（会话自己记着）。
- 返回：`{stdout, stderr, cursor, state, exit_code, dropped_bytes}`。
- 输出按**到达顺序**合并，`out` / `err` 分开给；不做行交错美化（那会骗人）。

### `Session-List`

无参数。返回 `[{session_id, kind, name, command, state, pid, started_at, last_output_at, bytes}]`。
**这一条是给上下文被压缩之后用的**：模型忘了 id 时先列一遍，不要瞎猜。

### `Session-Close`

`{"session_id": "s_7f3a1c02"}`。杀整个进程组，从表里移除。

- 临时会话：允许。
- 服务会话：**拒绝**，返回 `{"refused": "service", "message": "这是用户启动的服务，去 设置 → 持久会话 里关"}`。
  理由就是用户那句「持久性不能在会话的调用块点取消」。

## 2.5 三个数字不是一回事

| 数字 | 管什么 | 到点了会怎样 | 谁有 |
|---|---|---|---|
| `timeout_ms` | 这个**进程**总共能活多久 | 杀掉整个进程组，`timed_out: true` | 只有非持久的 `System-Shell` / `WorkSpace-EnvirShell` |
| `block_ms` | 这次**工具调用**愿意等多久输出 | 不杀、不报错，带着已有输出和 `cursor` 返回；进程继续跑 | `Session-Open` / `Session-Write` |
| `wait_ms` | 这次**读**愿意等多久新输出 | 同上：返回 `{"stdout": "", "cursor": 原值}` | `Session-Read` |

一句话：`timeout_ms` 决定「它还能不能活」，`block_ms` 决定「这次调用站多久」。
持久会话**没有** `timeout_ms`——它的寿命由用户（管理页）或应用进程决定，不由时间决定。

## 3. 阻塞与游标

- 阻塞上限 30 秒是**故意的**：工具调用占着这一轮，超过半分钟用户会以为卡死。
  要更久的输出就分段读，或者干脆不阻塞。
- 一个批次里同时有几个调用时，它们**并行跑**（见 `notes/tool-execution.md`）：
  一条 `Session-Open` 阻塞 30 秒不会拖住别的调用，但**整轮仍要等它**——
  下一轮请求必须带齐所有结果。所以起服务请用 `block_ms=0`。
- 一个会话的输出进**环形缓冲**（每会话 256 KiB，`out`/`err` 各自算）。
  溢出丢最旧的，并把丢掉的字节数累加进 `dropped_bytes`——**不假装完整**。
- 游标是单调递增的字节偏移。环形缓冲丢头之后，比 `cursor` 更旧的请求返回
  `{"gap": true, "dropped_bytes": n}`，让模型知道中间缺了一段，而不是静默续上。
- 会话状态：`running` / `exited` / `killed` / `closed`。`exited` 之后还能读残留输出，
  但 `Session-Write` 返回 `{"state": "exited", "exit_code": n}`。

## 4. 非持久命令：超时与手动关闭

> **已实现**（2026-11-04，0.15ak / D246）。实现落在 `crates/core/src/kills.rs` +
> `shell::execute`；真机清单 V66。下面这一节是当时的约定，代码照它写。

### 4.1 超时

- `timeout_ms` 接受整数或字符串 `"inf"`。
- 整数范围 **1000–36 000 000**（1 秒 – 10 小时；用户说的 1–36000 秒）。
  **`0` 不是「不超时」**，是不合法的值——「不超时」只有一个写法：`"inf"`。
- 默认仍是 30 000。`"inf"` 时**不设闹钟**，但保留手动关闭（见下）。

### 4.2 手动关闭（只对非持久）

- 每个工具调用在 core 里登记一个 `call_id → AtomicBool`（`crate::kills`），
  进程等待循环每 20 ms 看一眼；置上了就 `kill(-pgid, SIGKILL)`，结果写：

```json
{
  "command": "sleep 600",
  "exit_code": null,
  "stdout": "",
  "stderr": "",
  "killed_by_user": true,
  "duration_ms": 4200,
  "timed_out": false
}
```

  文案（给模型看的那一行）：**「被用户关闭（不是命令自己退出）」**。
  `timed_out` 保持 false——「超时」和「用户关掉」是两件事，合成一个模型会误判。

- FFI 新入口：`nmoi_tool_cancel_json({"session": "...", "call_id": "..."})`
  → `{"cancelled": true, "found": true}`；没在跑就 `found: false`（不是错误）。
- UI：工具卡展开后，正在跑的那一条右侧一个「关闭」（文字按钮，不是图标）；
  点了之后卡片上写「已请求关闭」，结果回来换成「被用户关闭」。
- **持久会话的调用块上没有这个按钮**：那一条不是「正在跑的一次调用」，
  是一个活着的会话。卡片上写一句「这个会话在 设置 → 持久会话 里管理」。

## 5. 进程与生命周期

- 每个会话一个 `Command`，`stdin/stdout/stderr` 三根管道，`process_group(0)`，
  和现在 `execute()` 的做法一致（超时/杀组/封顶只有一份实现）。
- 原生（`System-Shell` 那套）和容器（`WorkSpace-EnvirShell` 那套）都支持：
  差别只在命令行怎么拼，`EnvirSpec::command()` 已经在了。
- 应用进程死掉时：我们这端的 stdin 写端随之关闭，`sh` 读到 EOF 自己退出。
  **这是唯一能跨进程生效的收尾**（Android 没有可靠的 onDestroy），文档里写清楚，
  不要假装「一定会被清理」。
- 上限：同时 8 个会话（临时 + 服务一起算）。第 9 个返回
  `{"error": "too_many_sessions", "limit": 8}`，并列出可以关掉哪几个。

## 6. 服务配置的存储

`SCHEMA_VERSION = 12`，新表：

```sql
CREATE TABLE shell_services (
  id          TEXT PRIMARY KEY,   -- 'svc_' + 8 hex
  name        TEXT NOT NULL,
  command     TEXT NOT NULL,
  args_json   TEXT NOT NULL DEFAULT '[]',
  cwd         TEXT NOT NULL DEFAULT '',
  env_json    TEXT NOT NULL DEFAULT '{}',
  workspace   INTEGER NOT NULL DEFAULT 0,  -- 0 = 不绑定
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
```

- **`enabled` 不入库**：开关是运行时状态。存了它，重启后开关是「开」而进程不在，
  界面上就有两个答案（铁律：同一件事只留一个控件、一个来源）。
- 删配置时如果会话在跑：先确认「会一起关掉」，再删。

## 7. 界面

### 7.1 持久会话管理（设置 → 工具与工作区 → 持久会话）

一页两个子页（分段控件，不是两个入口）：

**临时会话**

- 空态：「还没有临时会话 / 模型调 `Session-Open` 时会出现；应用退出就没了」。
- 每行：名称或命令（`python3 -m http.server 8000`）、状态点 + 一句话
  （`运行中 · 3 分 12 秒` / `已退出（0）`）、右侧「关闭」。
- 点行展开：最近 20 行输出（等宽、只读）、pid、开始时间。
- **没有「新建」按钮**：临时会话是模型开的东西，用户不该在这里手搓一条。

**服务启动**

- 空态：「还没有服务 / 配一条命令，点开关就起；应用重启不会自动起」。
- 每行：名称、命令一行（省略）、右侧开关（运行时的真开关）。
- 行尾 chevron 进编辑页。
- 开关打开 → 起进程；失败（命令不存在/工作区没绑）→ 开关弹回并写原因，
  **不要留在「开」的位置上骗人**。
- 删除走行内左滑 + 确认（和模型行一致）。

### 7.2 服务编辑页

照 MCP 的 STDIO 那一页的形状（用户给的截图），**不做渐变背景**：

```
名称          [__________]
命令          [__________]
参数          [__________]   （空格分隔，含空格的用引号；空参数写 ""）
工作目录      [__________]   （留空 = 工作区根）
环境变量      [+ 添加环境变量] [从环境导入]
绑定工作区    [Default            >]
──────────────
[ 保存 ]
```

- 参数在**存储上仍是数组**；输入框只是「按 shell 词法拆」的一层，拆不开就拦下并点名。
- 环境变量两列（键 / 值），值默认掩码（`••••`）+ 一只眼睛——它可能是 token。
- 没有「是否启用」这一格：开关在列表行上，一页只有一个地方能改它。

### 7.3 聊天里的工具卡

- 非持久调用（`System-Shell` / `WorkSpace-EnvirShell`）：展开后运行中有「关闭」。
- 持久会话调用（`Session-*`）：展开后显示 `session_id` 和状态，写一句
  「这个会话在 设置 → 持久会话 里管理」，**不给关闭按钮**。

## 8. 失败与边界（先想好，别等真机）

| 情形 | 表现 |
|---|---|
| 命令不存在 | 开关弹回；`Session-Open` 返回 `{"error": "spawn_failed", "message": "..."}` |
| 工作区没绑 | 和现在一样：`denied / no_workspace`，不假装能跑 |
| 进程自己退出 | 状态 `exited` + `exit_code`；输出还能读一次 |
| 输出爆炸（`yes`） | 环形缓冲丢头 + `dropped_bytes`；不 OOM |
| 两个会话同 id | 不可能：id 由服务端生成，不接客户端的 |
| 应用被杀 | 子进程读 stdin EOF 退出；**不保证**（文档写清） |
| 服务开关连点 | 第一次点就置忙；起来之前不接受第二次 |
| 会话在跑时改配置 | 保存只改配置，**不影响正在跑的进程**；页面写一句「下次启动生效」 |

## 9. 分批

1. **0.15ak**：`timeout_ms` 扩到 1–36 000 秒 + `"inf"`；`crate::kills` + `nmoi_tool_cancel_json`；
   工具卡上的「关闭」。**先做这一批**：它不依赖会话模型，且是持久会话的进程管理底座。
2. **0.15aj**：`crate::psession` 注册表 + 五个工具 + schema v12 + 管理页两子页 + 编辑页。
3. 之后：MCP 的 STDIO 复用同一套「命令/参数/工作目录/环境变量」表单与进程管理
   （用户说的「到时候 STDIO 也可以参考这边」）。

## 10. 验收（落地时抄进 `notes/verification.md`）

1. `Session-Open` 起 `python3 -m http.server`，`block_ms=1500`：第一次返回里就有
   `Serving HTTP on ...`，并给出 `session_id`。
1b. 同一条命令用 `block_ms=0`：调用立刻返回（几十毫秒内），`stdout` 可能是空的、
   `state: "running"`；紧接着 `Session-Read` 能读到 `Serving HTTP on ...`。
   **进程没有被杀**，`Session-List` 里还在。
2. 下一轮（新的一次 `turn_run`）用同一个 `session_id` 调 `Session-Read`：能读到新请求的日志。
3. `Session-Write` 给 `bc` 发 `2+2\n`：返回 `4`。
4. `Session-List` 在上下文被压缩之后仍能列出活着的会话。
5. 服务页配一条 `sleep 600`，开关打开 → 进程在；关掉 → 进程没了（`ps` 里查不到）。
6. 杀掉应用再打开：服务配置还在，开关是关的，列表里没有活会话。
7. 非持久 `sleep 600` + `timeout_ms: 3000`：3 秒后 `timed_out: true`。
8. 非持久 `sleep 600` + `timeout_ms: "inf"`，点工具卡上的「关闭」：
   `killed_by_user: true`、`timed_out: false`，进程组里没有残留。
