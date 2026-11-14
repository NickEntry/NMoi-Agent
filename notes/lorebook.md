# 世界书：完整落地 + kelivo / RikkaHub 导入（设计稿，未动工）

> 用户 2026-11-02：「完整世界书以及 kelivo、rikkahub 格式兼容导入也要尽快实现，
> 这对于作为 AGENT 使用很重要」。

## 1. 现在到哪一步了（读代码）

| 层 | 状态 |
|---|---|
| `core::lorebook` | **有**：kelivo 格式的解析、激活判定（常驻 / 关键词 / 正则 / 大小写 / 扫描深度）、`plan()` 按位置排注入、`summarize()` 给"为什么没命中"、`keyword_index()` |
| 存储 | **没有**：没有任何表存世界书 |
| FFI | **没有**：`crates/ffi/src` 里搜不到 lorebook |
| 界面 | **没有**：设置里只有一行灰字「世界书（待接）」 |
| 请求组装 | **没有**：`plan()` 从没被 `run_session_turn` 调过 |

所以"完整世界书"不是补一个导入按钮，是**把已经写好的那层接到库里、接到请求上、接到界面上**。

## 2. 两种格式差在哪

| 字段 | kelivo | RikkaHub |
|---|---|---|
| `position` | `AFTER_SYSTEM_PROMPT`（大写蛇形） | `after_system_prompt`（小写） |
| `role` | `ASSISTANT` / `USER` / `SYSTEM` | `assistant` / `user` / `system` |
| `priority` | 0、1 | 0、10001（数值大，语义同"越小越前"） |
| 其余 | `id/name/enabled/content/injectDepth/keywords/useRegex/caseSensitive/scanDepth/constantActive` | 同名同义 |

差别只有**大小写**和 priority 的量级。所以读取层做成**大小写不敏感**，两边都能吃；
写出去统一用 kelivo 的大写形式（这样导回 kelivo 不会变样）。

## 3. 读取层：宽容但不猜

```rust
// core::lorebook 里给 Position / Role 各写一个自定义反序列化：
// 先按原样试，失败再转大写试一次；两次都不行就报错，不默认成某个位置。
```

规则：

1. `position` / `role` 大小写不敏感；**认不出就报错并点名是哪个值**，不静默落到
   `AFTER_SYSTEM_PROMPT`——落错位置比导入失败更难查。
2. `keywords` 允许 `null` / 缺省 → 空数组；`injectDepth` / `scanDepth` 缺省 → 4；
   `priority` 缺省 → 0；`enabled` 缺省 → true（跟两家一致）。
3. `useRegex=true` 但关键词不是合法正则：**导入时逐条校验**，把不合法的那几条列出来，
   让用户选"跳过这几条"或"取消导入"。不在运行时才炸。
4. 不认识的字段**保留原样**（存在 `raw` 里），导出时原样写回。理由：两家以后加字段，
   我们不该在导入导出之间把它们吃掉。

## 4. 存储

一张表就够：**整本书存一份 JSON**，不拆条目表。

```sql
CREATE TABLE lorebooks (
  id         TEXT PRIMARY KEY,      -- 沿用文件里的 id；没有就生成
  name       TEXT NOT NULL,
  enabled    INTEGER NOT NULL DEFAULT 1,
  json       TEXT NOT NULL,         -- kelivo 格式的完整文档（含 data.entries）
  origin     TEXT NOT NULL DEFAULT '', -- 'kelivo' / 'rikkahub' / 'manual'
  updated_at INTEGER NOT NULL
);
```

为什么整本存：导入导出要**逐字节可回**，拆成条目表再拼回去，字段顺序和缺省值都会变；
`plan()` / `summarize()` 本来就要整本在内存里。条目编辑走"读出来 → 改一条 → 整本写回"，
一本书几十条，代价可以忽略。

绑定：助手加一列 `lorebooks TEXT NOT NULL DEFAULT '[]'`（id 数组）。
一个会话用哪个助手的书，就按助手走；会话级覆盖以后再说（别一上来就三处能配）。

## 5. 请求组装

`run_session_turn` 在装历史之前做三步：

1. 读这个助手的书（现读库，不用界面快照）；
2. `lorebook::plan(book, history)` → 按 `position.order()` 排好的注入；
3. 按位置插进请求：`BEFORE_SYSTEM_PROMPT` / `AFTER_SYSTEM_PROMPT` / `TOP_OF_CHAT` /
   `AT_DEPTH` / `BOTTOM_OF_CHAT`。

两条硬约束：

- **注入是新增消息，不改写已有消息**（`core` 的既有约定）：缓存前缀不破。
- **算进 `reserve_fixed`**（D144 已经留了这个口子）：世界书不随压缩变小，
  不先扣掉就会"压完还占 70%"。

## 6. 界面

设置 → 模型与提示词 → **世界书**：

- 列表：名称、条目数、启用开关、来源徽标（kelivo / rikkahub / 手写）。
- 顶部两个动作：**导入**（SAF 选 `.json`）、新建。
- 导入预览（**先看再落库**）：
  「这本书有 12 条：9 条常驻、3 条关键词；2 条正则不合法（第 3、7 条）」
  → 「跳过那 2 条并导入」/「取消」。不合法的那两条要能看到原文片段。
- 编辑页：条目列表（拖不动，按 priority 排），点进条目：
  名称 / 内容 / 位置 / 角色 / 注入深度 / 关键词 / 正则 / 大小写 / 扫描深度 / 常驻。
- 底栏一个动作：**导出**（kelivo 格式）。

聊天页的「上下文」面板里加一块**这一轮命中了什么**：
`lorebook::summarize()` 直接给「命中 3 条（关键词：退款 / 状态机）/ 未命中 9 条」，
点开看每条为什么没中（关键词没出现 / 扫描深度不够 / 被停用）。

## 7. 边界

| 情形 | 处理 |
|---|---|
| 导入的文件不是世界书（`type != "lorebook"`） | 明确说"这不是世界书文件"，不按 JSON 硬解 |
| 文件 2 MB 以上 | 拒绝并说大小；世界书不该有这么大 |
| 同名书已存在 | 问「覆盖 / 另存一本」，不静默覆盖 |
| 条目内容为空 | 允许（有的书用空条目占位），但列表里标「空条目」 |
| 正则编译失败 | 导入时拦（见 §3.3），运行时再遇到就跳过并在面板里说 |
| 书被删但助手还指着 | 助手那一列按 id 过滤，指着不存在的就当他没配 |

## 8. 分批

1. **0.15ap-a**：读取层大小写不敏感 + 逐条正则校验（core，带单测；两家样例文件当 fixture）。
2. **0.15ap-b**：`lorebooks` 表 + FFI 五条（list/get/put/delete/import）+ 助手绑定列。
3. **0.15ap-c**：界面（列表 / 导入预览 / 条目编辑 / 导出）。
4. **0.15ap-d**：接进 `run_session_turn` + 上下文面板的命中说明。

## 9. 验收

1. 导入 `世界书示例-kelivo.json`：5 条全进，位置/角色/关键词都对。
2. 导入 `世界书示例-rikkahub.json`：同样 5 条，**不因为小写 position 失败**。
3. 导出再导入：条目数、启用状态、位置、关键词一字不差。
4. 一条 `useRegex=true` 且正则有问题的书：导入预览点名那一条，跳过之后其余照常。
5. 会话里说"关键词1"：对应条目进上下文（`summarize` 里能看到命中原因）；
   不说就只在常驻条目里。
6. 上下文面板：命中/未命中条数与实际请求一致。

## 9. 按 ST（SillyTavern World Info）的语义做（2026-11-07 定，计划稿）

用户 2026-11-07：「按照 ST lorebook 的支持去做就行」。这一节是**增量**：§1–§8 已经落地的
（读取层 / `plan` / 注入）不动，这里补 ST 有、我们还没有的那部分，以及"多册"。

### 9.1 数据模型：多册

```json
{"books":[{"id":"b1","name":"主 AGENT","note":"槽位 200 起","enabled":true,
           "entries":[ … ]}]}
```

- 老的 `files/lorebook.json`（单册）读进来当**一册**，不丢数据。
- 册级 `enabled=false` = 整册不参与（列表上挂「已停用」）。
- 注入 = 合并**所有启用册**里启用的条目，再走同一个 `plan`。

### 9.2 条目字段：ST 有什么，我们怎么办

| ST 字段 | 现在的我们 | 计划 |
|---|---|---|
| `key[]` / `keysecondary[]` / `selective` / `selectiveLogic` | 只有 `keywords` | **补**：主键命中后按 `selectiveLogic`（AND_ANY / AND_ALL / NOT_ANY / NOT_ALL）判次键 |
| `constant` | 有 `constantActive`（常驻） | 保持 |
| `caseSensitive` / `matchWholeWords` | 有大小写；**没有整词** | 补 `matchWholeWords`（按词边界匹配） |
| `scanDepth` | 有 | 保持（ST 的 `scanDepth` 是"扫最近 N 条消息"） |
| `useRegex` | 有 | 保持 |
| `sticky` / `cooldown` / `delay` | **没有** | **补**（见 9.3） |
| `order`（ST 里越小越前） | 有 `priority` | 语义相同，导入时直接映射 |
| `position` 0–4 + `depth` | 有 5 个位置 + `injectDepth` | 映射表见 9.4 |
| `role` | 有 | 保持 |
| `probability` / `useProbability` / `group` / `groupWeight` | 没有 | **不做**（要随机与分组加权，先记在"以后"） |
| `excludeRecursion` / `preventRecursion` / `delayUntilRecursion` | 没有 | **不做**（递归触发是另一层，先把非递归做对） |
| `triggers`（按生成类型触发） | 没有 | **不做**（我们没有"生成类型"这个概念） |

### 9.3 时序三条：要状态，`plan` 不再纯

- `sticky`：触发后**保持** N 条消息（重复命中不延长）。
- `cooldown`：触发后（含粘滞结束）N 条消息内不再触发。
- `delay`：对话至少有 N 条消息才允许触发（按单条消息数，不是轮数）。

实现：`plan(book, history, state)`，`state` 是**会话级**的"每条上次在哪条消息触发的"。
来源两处：本进程里刚算过的（内存表）+ 库里存一份（`settings` 的 `lorebook_state`，
跟着会话走，重启还能接上）。判据是"同一份历史两次调用给同一结果"，所以状态必须显式传。

### 9.4 位置映射

| ST `position` | 我们的 `Position` |
|---|---|
| 0（before char defs） | `BeforeSystemPrompt` |
| 1（after char defs） | `AfterSystemPrompt` |
| 2（before AN） | `TopOfChat` |
| 3（after AN） | `TopOfChat` |
| 4（at depth） | `AtDepth`（用 `depth`） |

### 9.5 分批（每批都能单独验）

1. **多册 + 页面**：存储改 `books[]`；页面按 Kelivo 的形状（册行六样：名称/副标题/已停用/启用数/四个动作；
   展开是条目行：拖动柄 + 标题 + 位置徽标 + 常驻 + 开关；条目编辑与新建册都用底部面板）。
   这一批**不改匹配行为**，只把"看得见、能开关"做出来。
2. **匹配补全**：`keysecondary` + `selectiveLogic` + `matchWholeWords`。
3. **时序**：`sticky` / `cooldown` / `delay` + 状态表。
4. **ST 格式导入**：`entries` 是对象（键是 uid），字段名 camelCase，和 kelivo 的数组形状不同；
   导入时按 9.2 的表映射，认不出的字段**忽略但计数**（在导入结果里写"跳过了 N 个不支持的字段"）。

判据（每批）：core 单测 + 一条真机项；第 1 批的真机项是"两册、一册停用、条目开关各一次"。

## 10. 内置提示词册 + 导入覆盖（2026-11-07 夜定）

### 10.1 内置册

- 一册 id 固定 `builtin`，名字「内置提示词」，**不可删除**（整册没有删除动作）。
- 条目来源：`assets/presets/<id>/preset.json` 的 entries（现在 3 包 6 条：tool-usage / shell-safety /
  edit-message）。用户改过的那一份在 `settings(scope="preset")`，与原文按四态合并——
  **不复制一份数据**，这一册是那份词条的视图。
- **不可减项**：内置条目没有删除按钮、也没有停用开关，列表上挂「内置」徽标。
  要改内容去「预设」页（一个动作一个入口），改完这一册跟着变。
- **能增项**：手动加的条目存在 `files/lorebook.json` 的 `builtin` 册里（`origin: "manual"`），
  可删、可停用，走世界书的注入路径——和内置条目不是同一条路，界面上用徽标分开。
- **恢复默认**：只重置内置条目（清掉 `settings(scope="preset")` 里那些覆盖），
  手动加的条目不动——那是用户自己写的，不该被「恢复默认」吃掉。

### 10.2 导入：按 id 覆盖

- **同 id 才算同一册**，名字相同不算。同 id → 覆盖，覆盖前**清空原有条目**（不做合并），
  免得留下"删了一条又冒出来"的半覆盖状态。
- 内置册是唯一例外：导入一份 id=`builtin` 的文件时**按 id 补缺**——
  文件里没有的内置条目保留，文件里有的覆盖正文，手动条目原样留着。
- 其余册：覆盖 = 整册替换。

### 10.3 导入导出都走文件

- 导入：SAF `GetContent("application/json")` → 预览（几册、几常驻、几关键词、和现有哪一册同 id）→ 落库。
- 导出：SAF `CreateDocument("application/json")`。单册导出用 kelivo 的单册形状
  `{"version":1,"type":"lorebook","data":{…}}`，整库导出用 `{"version":2,"type":"lorebooks","books":[…]}`。

### 10.4 分批

1. 多册 + 整页（**已落**，D282）。
2. 内置册（不可删 / 可增 / 恢复默认）+ 导入按 id 覆盖与补缺。
3. 匹配补全、时序、ST 导入（§9.5 第 2–4 批，顺序不变）。

## 11. 导入 / 导出（2026-11-07 定，计划稿）

**存储格式 = ST lorebook**（多册，§9.1）。导入导出支持 **ST / kelivo / RikkaHub** 三种。

### 11.1 导入

- 可以**多选文件**。每个文件独立走一遍：识别格式 → 解析 → 需要就转换 → 以 ST 形状存进 `books[]`。
- 识别靠形状，不靠扩展名：`data.entries` 是**数组** = kelivo / RikkaHub（差别只在大小写，§2）；
  `entries` 是**对象**（键是 uid）= ST。
- 单个文件失败**不影响其他文件**：结果逐条报（成功 N 册 / 失败 M 个，各自点名原因）。
- 认不出的字段**忽略但计数**（"跳过了 3 个不支持的字段"），不静默。

### 11.2 导出

- 导出前弹**格式选项**：ST / kelivo / RikkaHub。
- **多选导出**：先在一个**临时目录**里把每册转成目标格式，再打包成 **7z**（压缩等级用标准档）。
- 单册导出：直接给一个文件，不打包。

### 11.3 密码（导出可选）

- 导出时可以设密码；**设了密码就同时加密文件名**（7z 的 header encryption）。
- 导入时先检测是否需要密码：需要就弹输入；**输错不导入**，并删掉"这一次导入复制进应用数据目录的副本"
  （源文件不动）。
- 压缩等级一律**标准**，不为省体积换档。

### 11.4 残留清理（硬要求）

- 临时目录用完就删（成功、失败、取消三条路都要删）。
- 导入时若把文件复制进了应用数据目录，失败/取消要删那份副本。
- 判据：跑完一轮导入导出，`cacheDir` 与 `filesDir` 下不该多出任何 `*.7z` / `*.tmp` / `*.json.part`。


## 12. 用户手册

这一节的内容是给用户看的，正文在 `docs/worldbook.md`（**以那边为准**）：
条目各字段怎么填、`tool_call_*` 怎么写、一个能抄的骨架。这里不复制，只指向。
