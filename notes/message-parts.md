# 消息 = parts（设计稿，2026-11-04）

> 状态：**已拍板，第一批已落地**（2026-11-04）。
> 拍板结果：**全做**（v15 + core + UI）、版本**全存**、单框编辑器标记格式**升 v2**。
> 第一批（内存模型）已进主干：`Message` 的正文 / 思考 / 工具调用改成 parts 的唯一投影；
> 库还是老列，v15 在第二批。进度见 §12。
> 起因：用户 2026-11-04 问「这个改动会不会影响编辑与上下文工具的设计」。
> 结论先说：**编辑能完整实现**（代价是红蓝粒度降到 part）；**上下文工具除压缩切点外都能实现**；
> 只有一样必须拒绝照抄——Rikkahub 的 `Tool.output` 嵌套 parts（撞铁律②）。

## 1. 现在是什么形状

库里「一轮」= 1 条 assistant 消息（`content` + `reasoning` + `tool_calls[]`）+ N 条 `role=tool` 的独立消息，
它们共用 `unit_id`，`unit_kind = tool_pair`，全部 result 到齐才算闭合。

同一个结构现在有**三份表示**：

| 表示 | 在哪 | 形状 |
|---|---|---|
| 库 | `messages` 的列 | content / reasoning / tool_calls / tool_call_id |
| 编辑器 | `MessageEditDoc` 的标记串 | `<nmoi:output>` / `<nmoi:thinking>` / `<nmoi:tool index>` |
| 界面 | `ChatMessage` + 过程块 | 正文一段、思考一块、工具若干行 |

三份各自演进就会对不上。D188 那次「改第 0 个工具的结果，落到调用消息的正文上」就是编辑器与库的
序号轴错位——`tool:<i>` 与 `tool_result:<i>` 指的不是同一条消息。

### 装不下的四件事

1. **正文只能出现在调用之前。** `agent.rs` 把这一轮已收到的正文写进 `assistant.content`，
   工具之后再说话就是**下一轮**、下一条消息。用户 D248 给的结构是「块 → 正文 → 块 → 正文」，
   库里表达不出「同一条消息里正文夹在两个工具批之间」。
2. **一次调用与它的结果分属两条消息。** 编辑要跨两条写，版本只跟着调用那条走，**结果那条没有版本**。
3. **块选择器是序号轴**（`tool:<index>` / `tool_result:<index>`）。删掉一个调用，后面的序号全错位，
   而序号是编辑与审计唯一的定位手段。
4. **压缩切点只能落在原子单元边界**（`divider::validate_position` 的 `SplitsUnit`）。
   单元边界是「这一轮所有 result 都到齐」，它不是消息边界，切点画在哪儿、界面上怎么指，都得先解释一遍。

## 2. 目标形状

**一条消息 = 一串有序的 part。** 库里存的就是界面上画的那几块。

| kind | 能出现在 | 可编辑字段 | 界面 |
|---|---|---|---|
| `text` | 任何 role | text | 气泡正文；有 `tooledit` 时红蓝 |
| `reasoning` | assistant | text | 折叠块「深度思考 · N 字」 |
| `tool` | assistant | arguments / output | 过程块里一行；点开半屏弹窗 |
| `image` | user | 无（只能移出） | 气泡缩略图 |
| `notice` | 任何 role | 无 | 断块 / 声明行 |

### 约束（写进代码，不是口头的）

1. `image` 只在 `role=user`；`tool` / `reasoning` 只在 `role=assistant`。构造时校验，读库时也校验。
2. **`tool` 的 output 是字符串，不是 parts 数组。** 这是唯一一处明确拒绝照抄 Rikkahub 的地方：
   嵌套 parts 意味着库里有一层结构在界面上没有对应位置——撞铁律②。
3. 一条消息里 `text` 可以有多个（被工具批隔开），但**相邻 text 必须合并**；空 text 不许存在。
4. `tool.status` ∈ `ok` / `error` / `killed`。`pending` 只在内存里（D252：只存结束），落库时必须是这三者之一。
5. **part id 稳定、不复用**：`p1`、`p2`…，消息内唯一，删掉就留空号，不重排。
   跨消息引用写成 `msg#p3`。序号（`ord`）只决定顺序，**不当身份用**——这是第 3 条病根的正面修法。
6. 一条消息至少一个 part；`text` part 的 text 不许全空白（替代现在 `Message::validate` 的 `EmptyMessage`）。

### 一轮的边界

**一条 assistant 消息 = 一轮。** `round_end` 就是这条消息的最后一个 part 之后。
多轮 = 多条 assistant 消息，和现在一样，只是每轮内部不再拆行。

## 3. 库里的形状（schema v15）

```sql
CREATE TABLE message_parts (
    message_id  TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    part_id     TEXT NOT NULL,
    ord         INTEGER NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('text','reasoning','tool','image','notice')),
    origin      TEXT NOT NULL,
    tooledit    TEXT,
    call_id     TEXT,
    archived_ref TEXT,
    PRIMARY KEY (message_id, part_id),
    UNIQUE (message_id, ord),
    CHECK (tooledit IS NULL OR tooledit <> origin)
);
CREATE INDEX message_parts_by_call ON message_parts(call_id);
```

`origin` / `tooledit` 是该 part **可编辑字段的 JSON**，按 kind 定形状：

```json
text      {"text": "……"}
reasoning {"text": "……"}
tool      {"name": "read_file", "arguments": "{…}", "output": "……", "status": "ok",
           "started_at": 1730000000000, "ended_at": 1730000000121}
image     {"attachment": "att_…", "mime": "image/jpeg", "size": 184320}
notice    {"code": "broken_block", "text": "……"}
```

为什么 `origin` / `tooledit` 是 JSON 而不是十二个可空列：一个 tool part 有两样可编辑的东西
（arguments 与 output），拆成列就要 `origin_arguments` / `tooledit_arguments` / `origin_output` /
`tooledit_output` 四列，再加 image 的三列——一张表大半是空列，读的人还得先猜哪种 kind 用哪几列。
JSON 把「这种 part 有哪些字段」收在一处，界面上这几样**全部可见**，不违反铁律②。

为什么单独建表而不是 `messages.parts` 一列 JSON：`call_id` 要能查（「这个会话里哪些调用失败了」）、
`ord` 要能排序、`archived_ref` 要能对上归档。整块塞一列，这三样都得靠代码扫全表。

### `messages` 上退场的列

| 列 | 去处 |
|---|---|
| `content` | 第一个 `text` part |
| `reasoning` | `reasoning` part |
| `tool_calls` | `tool` part 的 name / arguments |
| `tool_call_id` | `tool` part 的 `call_id`（role=tool 那条行整体并进 part） |
| `unit_id` / `unit_kind` / `unit_closed` | 一条消息就是一个单元，三个列没有第二个答案 |

**迁移后删列，不留着**（v13 已经证明 `DROP COLUMN` 在这个库上安全：无索引、无视图、无生成列依赖）。
留着的理由只有「回滚方便」，但留着就是两份答案——那正是铁律②要挡的。旧版应用打开新库会被
`OpenError::SchemaTooNew` 挡住，不会读到半份数据。

`messages.source` **保留**：它回答「这条消息哪来的」（system / history / 注入），
但**渲染与路由改看 part 种类**；`model_thinking` / `tool_call` / `tool_result` 三个值迁移后不再产生。
顺带一个好处：上下文管理（0.15aq 的 F1–F4）的判据从「这条消息是谁说的」降到「这个 part 是什么」——
同一轮里工具输出可以被压，正文不受影响，现在做不到。

## 4. 版本与红蓝

一版 = 这条消息**全部 parts 的快照**。

```sql
ALTER TABLE message_versions ADD COLUMN parts_json TEXT;
```

- `parts_json` = `[{part_id, ord, kind, origin, tooledit}, …]`，切版本时整份写回 `message_parts`（同一事务）。
- 红蓝仍按**整条消息**切（`active_view`）：没有 `tooledit` 的 part 在两种视图下都显示 `origin`。
  **不做按 part 各自切**——一条消息里出现「这段蓝、那段红」，用户没法一眼看出自己改的是哪一版。
- 版本控件仍在消息上（`<n/n>`），切一次全换：正文、思考、工具参数、工具输出。
  这是 D250 那条结论的自然延伸（思考跟着版本走），现在把工具结果也带上。
- **2026-11-06 修正（D263）：一版 = 一整轮。** 折行之后一轮回答是 N 条消息，
  而重试换掉的是整轮——只快照最后一条的话，切回旧版会得到「旧正文 + 新的工具」。
  所以 `parts_json` 现在存 `{"turn":[{role,source,created_at,incomplete,parts}]}`：
  切版本 = 删掉这一轮的全部行、按快照写回来，版本行跟着搬到新的落点上。
  老库里那种 `[{id,kind,body}]`（只管一条消息）仍然能读，靠有没有 `turn` 键分开。

**代价**：一版的体积从「一段正文」变成「这条消息全部 parts」。工具输出大的消息（几十 KB 的 stdout）
乘 50 版，一条消息就能到几 MB。两条缓解，拍板时选一条：

1. 版本只快照**可编辑的 part**（text / reasoning / tool），image / notice 不重复存（它们不参与编辑）。
2. `MAX_VERSIONS` 从 50 降到 20（退回 D252 之前的数），并在版本控件长按提示里写清上限。

我倾向 1 + 保持 50：体积问题主要在 image，而 image 本来就不该进版本。

## 5. 编辑

`BlockSelector` 从四个改成 `Part { part_id }`，旧四个保留做兼容映射（老 FFI 调用方还在用）：

| 旧选择器 | 映射到 |
|---|---|
| `output` | 第一个 `text` part |
| `thinking` | 第一个 `reasoning` part |
| `tool:<i>` | 第 i 个 `tool` part 的 arguments |
| `tool_result:<i>` | 第 i 个 `tool` part 的 output |

`Part` 选择器直接点名 part id，**删掉一个调用不会让别的块错位**——这是现在这套序号轴的根本毛病。

单框编辑器（`MessageEditDoc`）的标记串继续存在，但它从「给人看的序列化」变成「parts 的序列化」：
`<nmoi:tool index="0">` 换成 `<nmoi:tool part="p3">`。**格式要升到 v2**，旧文档仍能解析（按 index 落回映射表）。
不升格式也能做，但那等于把序号轴又抄了一遍。

判据：改第 1 个工具的结果，只有那个 part 的 `output` 变；切版本时正文、思考、参数、结果一起换。

## 6. 上下文（wire）展开

库里一条消息带 parts，发请求时要展开成上游要的形状。**展开规则只有一处**（`wire.rs` 一个函数），
Kotlin 不重写。

走一遍 parts，累积 text：

1. 遇到一组连续的 `tool` part → 发一条 `assistant(content = 累积的 text, tool_calls = 这组)`，
   紧接 N 条 `tool` 消息；清空累积。
2. 末尾还有 text → 再发一条 `assistant(content = 它)`。
3. `reasoning` 按端口决定带不带（OpenAI 兼容端口现在就不带），不参与 content 拼接。
4. `image` 只在 user 消息上，拼成多模态 content（现在的做法不变）。
5. `notice` 展开成一行文本，`source` 跟着原值走。

两条必须钉死的事实：

- **一条库消息 → N 条 wire 消息**（N = 工具批数 + 1）。所以「库里的消息数」与「发出去的消息数」不再相等，
  上下文预算、`used` 校准、请求日志都要按 wire 数算，不能按库消息数算。
- **`tool` 消息必须紧跟在带 `tool_calls` 的 assistant 之后**。文本在工具之后 → 只能另起一条 assistant。
  部分上游对「连续两条 assistant」有意见；缓解是展开时把**相邻且中间没有工具批**的 assistant 文本合并，
  其余情况原样发，并在请求日志里标出「这一轮被拆成 N 条」。

## 7. 压缩切点

**切点只允许落在消息末尾（`round_end`）。** 不做 mid-message 切点。

理由：切在 parts 中间会得到「有发起没结果」或「有结果没发起」，上游直接 400；
而库消息边界**天然就是 wire 安全切点**——一条消息展开出来的 wire 序列一定是闭合的。
这条不变量是 parts 带来的最大好处：切点在界面上画在两条消息之间，不用再解释「原子单元」。

落地：

- `dividers.after_seq` 保留（seq 仍是消息的行号），但校验从 `SplitsUnit` 变成「这个 seq 必须是一条消息的 seq」
  （一条消息只有一个 seq，天然满足）。错误码保留名字，语义注释改成「切开一条消息」。
- 界面上切点画在消息之间；落在消息内部的位置**根本不提供**，不做一个点了会报错的位置。

## 8. 迁移（v15）

迁移前先 `Storage::backup` 一份（用户要求：动 schema 之前先备份）。

1. 建 `message_parts`；`message_versions` 加 `parts_json`。
2. 逐条回填：
   - `role=user`：`content` → `text` part；附件按 `created_at` 排成 `image` part。
   - `role=assistant` 无 `tool_calls`：`reasoning`（若有）→ `reasoning` part，`content`（若非空）→ `text` part。
   - `role=assistant` 有 `tool_calls`：`reasoning` → `reasoning` part，`content`（若非空）→ `text` part，
     每个 call → `tool` part，`output` 从同一 unit 里 `tool_call_id` 匹配的那条 tool 消息取。
   - `role=tool`：**不单独成消息**，内容并进对应 `tool` part 的 `output`；`archived_ref` 一起搬过去。
   - `role=system`：`content` → `text` part。
3. **删掉被并入的 `role=tool` 行之前**，先断言没有 `attachments.message_id` 指向它们
   （图片只挂在 user 消息上，应该有 0 行；不为 0 就中止迁移，不猜）。
4. **`dividers.after_seq` 要重映射**：指向被并入行的，改成它所属那条 assistant 消息的 seq。
   不重映射的后果是「分界线指向一个不存在的 seq」→ `OutOfRange` → 窗口静默退回整条历史，
   用户看到的是「我明明压过，怎么又全了」。
5. `edits.message_id` / `archive.origin_message` 里指向被并入行的值**原样保留**（它们是审计，
   指向一条已经并进 part 的消息是可解释的）；`archive` 读出来落到 part 上时按 `archived_ref` 找，
   找不到就按原 message_id 找——两处都要试，只试一处就会报「摘要悬空」。
6. 版本表回填：用该版的 `origin_message` + `reasoning` 拼最小 parts（一个 text + 一个 reasoning）。
   **工具 part 不回溯进旧版本**——旧版本本来就没存过工具块，回溯等于编造。
7. 删列（见 §3）。
8. `SCHEMA_VERSION = 15`。

判据：迁移后 `load_units` 出来的消息数变少（tool 行并进消息），但屏幕上看到的东西**一样多**——
每条工具结果都还能在对应的 tool part 里找到原文，字数不变；老会话的分界线还在，窗口没变宽。

## 9. 代价

1. **这是大改。** core 消息层、编辑、版本、压缩、路由、FFI、Kotlin 渲染全要动。估 3–4 批：
   v15 迁移 → core → UI → 真机清单。
2. **多了一层展开。** 展开写错的表现是「模型看不到某个工具结果」或「400 缺 tool 消息」，
   比现在难查。缓解：`wire.rs` 一个函数 + 一组把「parts → wire」钉死的单测（含交错序列）。
3. **版本体积变大**（§4，两条缓解选一条）。
4. **迁移不可逆**：删掉的列不会自己回来，回退只能靠备份。
5. **`load_units` 的读路径要一次批量取 parts**（`WHERE message_id IN (…)`），不能每条消息一次——
   那是 N+1，滚动会顿。这条和现在 `version_index` / `attachments_of` 的做法一致。

## 10. 不做什么

- 不做 `tool.output` 嵌套 parts（铁律②）。
- 不做 mid-message 压缩切点。
- 不做「按 part 各自切红蓝」。
- 不把 image 做成可编辑 part（图片只有「在 / 不在」）。
- 不在这一版做 part 级并行（0.15an 那条线另算；parts 只是让它将来有地方落）。
- 不顺手改 `source` 的取值集合（迁移后不再产生的三个值除外）。

## 11. 判据（做完怎么算对）

1. 一轮里「正文 → 工具 → 正文」三段，库里是**一条** assistant 消息、三个 part；
   界面上是过程块 + 正文 + 过程块 + 正文。
2. 改第 1 个工具的结果，只有那个 part 变红；切版本时正文、思考、参数、结果一起换。
3. 压缩切点只能落在消息末尾；界面上切点画在两条消息之间，不画在消息内部。
4. 迁移后老会话打开：每条工具结果都在，字数不变，分界线还在。
5. 展开的 wire 序列里，每条 `tool` 消息前面都紧跟着带对应 `call_id` 的 assistant。
6. `cargo test -p nmoi-core --lib` 全绿；`bash scripts/check_jni_symbols.sh --expected-only` 数目对得上
   （编辑仍走 `apply_block_edit`，只换选择器，预计仍是 50）。

## 12. 进度（2026-11-04）

| 批 | 内容 | 状态 |
|---|---|---|
| 1 | `Part` / `ToolPart` + `Message` 以 parts 为唯一来源（正文 / 思考 / 工具调用都是投影）+ 全仓调用点迁移 | **已落地**（D255），库仍是老列，行为不变 |
| 2 | schema **v15**：`message_parts` 建表 + 回填 + 删三列（**已落地**，D256） | 已完成 |
| 3b | 编辑按 part id（`BlockSelector::Part`）+ part id 不再随重建换号 + FFI 给 `parts` 清单 | **已落地**（D257） |
| 3 | 折行（`role=tool` 的行并进调用消息的 tool 块）+ wire 展开 + schema v16 | **已落地**（D258），2026-11-05 |
| 3a | `message_versions.parts_json`（版本快照带工具块） | **已落地**（D259，schema v17） |
| 3c | 单框编辑器标记 v2（`tool index` → `part id`） | **已落地**（D259） |
| 4 | UI 从 parts 渲染（过程块与正文来自同一条消息） | 工具那一半**已落地**（D258：一行读 `output` / `status`）；正文与过程块的分段渲染未动工 |

第一批落地时定下的两条顺序规则（写进代码，不是口头）：

- **思考 → 正文 → 工具**：`set_reasoning` 插到最前，`set_text` 插在第一个工具块之前。
  模型是"先想、再说、再调工具"，反了的话界面上读起来像两次回答。
- **`ToolPart.part` 与 `ToolPart.call_id` 是两样东西**：前者是"这条消息里的第几块"，
  后者是模型给的调用 id（结果按它配对）。混成一个字段，改一次调用 id 会把 part 的身份也改掉。

## 12.1 为什么"版本带工具块"不能先于折行（2026-11-04，试过）

想先做 `parts_json`（一版 = 整份块的快照），测试立刻撞上：

1. 一条消息 + 它的工具结果**是两条记录**；
2. 切回旧版时，快照把**调用那条**的块换回去了（`c9` → `c1`），
   可**结果那条**还指着 `c9`；
3. 于是单元校验当场报 `UnknownToolCall { call: "c1" }`——库里出现了一次"结果指向不存在的调用"。

要修就得让版本快照覆盖**整个单元**（调用 + 全部结果），而那就是"折行"要做的事：
结果本来该是调用块的一个 `output` 字段，而不是另一条消息。
所以顺序是 **先折行、再让版本快照整份块**，反过来做要写两遍、还要维护一个临时的跨消息还原。

## 13. 拍板记录（2026-11-04）

1. **动不动**：全做。
2. **版本快照**：全存（含 image / notice）。体积先量再决定要不要降 `MAX_VERSIONS`。
3. **标记格式**：升 v2（`tool index` → `part id`）。

## 14. 原来的三个问题（留着看当时怎么想的）

1. **动不动？** 选项：
   - A. 全做（v15 + core + UI），parts 是 1.0 之前最后一块地基。
   - B. 只做「tool 结果并进调用消息」这一小步（v15-lite：不加 parts 表，只把 tool 行并进 unit 的版本与编辑），
     parts 留到 1.0 之后。**代价**：交错正文还是表达不出来，序号轴还是错位。
   - C. 先不做，把 0.15an（批次并行）与 0.15aq（上下文档位）做完再说。
2. **版本快照**：只存可编辑的 part（我的倾向），还是全存 + 把 `MAX_VERSIONS` 降回 20。
3. **单框编辑器的标记格式**：升到 v2（`tool index` → `part id`），还是保持 v1 靠映射表。
   我倾向升——不升等于把序号轴又抄了一遍。
