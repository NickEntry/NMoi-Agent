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
