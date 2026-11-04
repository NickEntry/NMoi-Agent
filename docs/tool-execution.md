# 工具调用的并行与阻塞（设计稿；0.15an-a 已落地，见 D254）

> 用户 2026-11-02 问：模型一次要调好几个工具时，能不能并行？其中一个阻塞 0–30 秒，
> 会不会把别的也拖住？各家的格式支持吗？这一份先答"端点支持不支持"，再答"我们怎么排"。

## 1. 端点支持（2026-11 核对官方文档）

| 端口 | 一次返回多个调用 | 结果怎么回填 | 能不能关掉并行 |
|---|---|---|---|
| OpenAI（Chat Completions / Responses） | 支持。`tool_calls` 是数组，`output` 里可以有多个 `function_call` | 每个调用一条 `role: tool`（`tool_call_id`）或一条 `function_call_output`（`call_id`） | `parallel_tool_calls: false` 可强制零或一个 |
| Anthropic | 支持。一条 assistant 消息里可以有多个 `tool_use` 块 | 下一条 user 消息里放多个 `tool_result` 块，按 `tool_use_id` 配对 | 文档的 tool_choice 一节只列 auto/any/tool/none；`disable_parallel_tool_use` 这个开关**落地前用 curl 验一次** |
| Gemini（generateContent） | 支持，文档里专门有一节 "Parallel function calling" | `functionResponse` **只认函数名**，不认 call id（我们现在的 `runtime.rs` 就是这么映射的，见 1326 行） | 无 |
| Gemini（Interactions，新） | 支持 | `function_result` 带 `call_id`，能配对 | 无 |

两点必须说清：

1. **"支持并行"是模型那边的事，不是我们这边的事。** 三家都能让模型一次吐多个调用；
   真正卡住的是我们的循环——`agent::run_turn` 现在是 `for call in &outgoing`（326–350 行），
   一个跑完才跑下一个。三个各 10 秒的调用就是 30 秒。
2. **Gemini 的老接口没法配对同名调用**：`functionResponse` 只给 `name`，两个 `read_file`
   并行回来时，谁是谁分不清。所以 Gemini 这条路上「同名工具并行」要等
   Interactions 那套 `call_id`，或者干脆在 Gemini 上不并行同名工具。

## 2. 现在的形状

```
模型回 3 个调用
  for call in calls:            ← 串行
      invoke()                  ← 阻塞就卡在这里
      push(tool_result)
  下一轮请求（带 3 条结果）
```

`ToolContext` 是 `Copy` 的借用集合（policy / workspace / declared / envir / history），
`Tool: Send + Sync`，`invoke(&self, ...)` 不拿 `&mut`。**并发执行不需要改工具本身**，
只需要把那个 `for` 换成"派出去、收回来、按原顺序落"。

## 3. 设计：一个批次，四条车道

### 3.1 批次

一次 assistant 消息里的 N 个调用算一个**批次**。批次内：

- 结果按**调用顺序**回填（`Vec<Option<ToolRecord>>` 按 index 放），不是按完成顺序。
  wire 上顺序无所谓（都带 id），但历史里顺序乱了以后排查会疯。
- `seq` 在收齐之后按顺序分配——并发时抢 `seq` 会让消息 id 与顺序对不上。
- 整个批次共用一次 `spent` 累加（收齐后算），不在工作线程里改共享计数。

### 3.2 车道

不是所有工具都能一起跑。按"碰不碰共享状态"分四条：

| 车道 | 并发度 | 谁在上面 |
|---|---|---|
| `read` | 4 | `read_file` / `list_dir` / `find_files` / `search` / `stat` / `token_estimate` / `Session-Read` / `Session-List` |
| `workspace` | 1 | `write_file` / `edit_file` / `edit_unit` / `append_text` 这类改文件或改消息的 |
| `session:<id>` | 每会话 1 | `Session-Write`（同一个 REPL 两个写会串行） |
| `process` | 4 | `System-Shell` / `WorkSpace-EnvirShell` / `Session-Open`（各自独立进程） |

- 车道之间**互不等待**：`Session-Open` 阻塞 30 秒时，`read` 车道上的读照常跑完。
- 同一车道上排队，按调用顺序。
- 默认车道是 `workspace`（**保守**）：新工具不声明就串行，宁可慢一点也不要两个写撞上。

### 3.3 阻塞调用在批次里是什么样

`Session-Open(block_ms=30000)` + `read_file` + `list_dir` 同一个批次：

```
0ms      三条一起发出去
20ms     read_file 回来
35ms     list_dir 回来
30000ms  Session-Open 回来
30000ms  收齐 → 落库 → 下一轮请求
```

**这一轮仍然要等 30 秒**——因为下一轮请求必须带齐三条结果，缺一条上游会拒
（Anthropic 明确要求每个 `tool_use` 都有 `tool_result`；OpenAI 也会把缺的结果当成没执行）。
并行省下的是"三条相加"，不是"最长那条"。

所以工具描述里要写一句：**起服务用 `block_ms=0`**，别用 30 秒去等一个本来就常驻的东西。

### 3.4 取消

- 每个调用一个 `call_id → AtomicBool`（`crate::kills`），工作线程每 20 ms 看一眼。
- 用户点"停止"（整轮取消）：把**批次里所有**在跑的调用一起置上，各自杀进程组/提前返回。
- 用户点某条工具卡上的"关闭"：只置那一个。
- 结果里分开写：`killed_by_user: true` / `timed_out: true` / `cancelled: true`（整轮停）。

## 4. 会撞的地方（先写下来）

| 情形 | 处理 |
|---|---|
| 同一批次两个 `write_file` 写同一个文件 | `workspace` 车道只有 1 个工人，天然串行；后一个看到的是前一个写完的内容 |
| 同一批次一个 `write_file` + 一个 `read_file` 读同一个文件 | 并行，读到的可能是写前也可能是写后。**不保证**——这是并行读的代价，工具描述里不承诺 |
| 同一批次两个 `edit_unit` 改同一条消息 | `workspace` 车道串行；第二个会因为"内容没变/版本已变"被拒，照常回给模型 |
| 同一批次两个 `Session-Write` 写同一个会话 | `session:<id>` 车道串行 |
| 同一批次两个 `Session-Open` | `process` 车道并行，各自一个会话 |
| 工作线程 panic | 该调用变成 `失败：工具内部错误`，其余照常；批次不整体失败 |
| 整轮取消时还有调用在跑 | 全部置 kill，等它们收尾（最多 2 秒）再返回，避免"人已经走了进程还在" |

## 5. 界面

- 工具卡里每条调用**各自一行**进度：`运行中 · 3 秒` / `完成` / `被用户关闭`。
  现在是一张卡笼统写"工具调用 × 3"，并行之后必须能看出是哪一条还没回来。
- 结果按调用顺序排，不按完成顺序——否则用户看到的顺序每批都不一样。
- 批次里有一条在阻塞时，卡片上写「还有 1 条在跑」，别让整张卡看起来卡死。

## 6. 分批

1. **0.15an-a ✅**（D254）：`Tool::lane()` + `agent::execute_batch`
   （`read` 4 / `workspace` 1 / `process` 4，默认 `workspace`）。
   结果按调用顺序回填；单个工具 panic 只让那一条失败。
2. **0.15an-b ✅**（D246 那一套已经在）：`crate::kills` 按 `(session, call_id)` 登记，
   界面每条调用一行、弹窗底部「关闭」，`nmoi_tool_cancel_json` 只置那一条。
   并行之后不需要新增界面。
3. **0.15an-c 未做**：`session:<id>` 车道 + `Session-*` 的 `call_id` 配对
   （`Session-*` 工具本身还没做，见 0.15aj）。
4. **未做**：Gemini 的 `call_id`（等 Interactions 那套接上）。

## 6.1 落地时改掉的两处

- `Cancellation` 加了 `Send + Sync`：取消标志现在被工作线程读。测试里那几个 `Cell` 实现
  换成了原子类型。
- 批次里**没执行**的调用（整轮取消时排在后面的）不产生记录，也不进历史——
  与串行时代的行为一致：已经跑完的照常记，没跑的不编一条"没执行"出来。

## 7. 验收

1. 让模型一次调三个 `read_file`：三个都回来，顺序与调用顺序一致；总耗时接近最慢那条。
2. 一次调两个 `write_file` 写同一个文件：串行，第二个写的是第一个之后的内容。
3. 一次调 `Session-Open(block_ms=0)` + 两个 `read_file`：读的两个先回来（界面上能看到），
   `Session-Open` 立刻返回 `持久会话已运行：s_xxx`；整轮不受 30 秒影响。
4. `Session-Open(block_ms=30000)` + 两个读：整轮等 30 秒，但读的两条在 100 ms 内就显示完成。
5. 批次跑到一半点"停止"：在跑的那条写 `被用户关闭`，已完成的照常保留，这一轮标 `cancelled`。
6. 批次里一条工具 panic：其余照常，panic 那条写"工具内部错误"。
