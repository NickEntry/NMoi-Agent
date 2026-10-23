# Provider 运行时（P2）：字段来源与实测记录

这一页是 `crates/core/src/{transport,sse,stream,runtime}.rs` 的**证据附页**：
每个字段名、每条判断，要么指向官方文档，要么指向本机实测。

抓取/实测日期：**2026-10-20**。这类字段名会变，隔一段时间要重验。

---

## 0. 一层一层是什么

```
ChatRequest ──build_body──► Value ──PlainHttpTransport──► 字节
                                                     │
             StreamEvent ◄──StreamParser◄──SseReader◄─┘
                  ▲
            UI / 引擎（push_delta / push_reasoning / finish_turn）
```

| 模块 | 职责 | 不知道什么 |
|---|---|---|
| `transport` | 把字节发出去、按帧读回来 | 不知道 body 里是什么 |
| `sse` | 字节 → SSE 帧 | 不知道 JSON 里是什么 |
| `stream` | 帧 → 归一事件 | 不知道连接怎么来的 |
| `runtime` | 请求构造、错误映射、重试、驱动 | 不知道 UI 长什么样 |

---

## 1. 真机实测（本机代理 `http://127.0.0.1:8790/v1`）

> **脱敏约定（2026-10-23）**：本文里的实测端点都是**本机的一个第三方中转**，它和上游各家的真实别名一律不写进仓库，统一记为 `relay-a/` `relay-b/` `relay-c/` `local/`（映射只在本机笔记里）。数字、延迟、token 数都是原始实测值，**没有因为脱敏改动过任何一个数**；要复核就照同样的请求体打同样的端点，结论不依赖它叫什么名字。


```bash
NMOI_SMOKE_MODEL="relay-b/DeepSeek-V4.1-Flash" \
NMOI_SMOKE_PROMPT="只回两个字：喵喵。不要解释。" \
  cargo run -p nmoi-core --example provider_smoke
```

2026-10-20 的结果：

```
→ http://127.0.0.1:8790/v1 relay-b/DeepSeek-V4.1-Flash (kind=openai_compatible)
[meta] id=Some("02179250611903731c26a7cf9ea611aa30972127acb8017c9592e") model=Some("DeepSeek-V4.1-Flash")
[usage] prompt=40 completion=60 cached=0 reasoning=57
喵喵
[finish] stop
[done]
--- 1.46s 首字 Some(1.453321666s) 正文 1 段 思考 18 段
已落库: assistant 消息 01a159b0-c079-72b5-ad73-8e9530f3500a:2，历史 2 个原子单元，用量 74
```

**非流式**（`NMOI_SMOKE_MODE=complete`，同一个端点，2026-10-20）：

```
--- 2.68s 正文 2 字 思考 441 字
[text] 喵喵
[usage] prompt=40 completion=115 cached=0 reasoning=112
[finish] stop
[model] Some("relay-b/DeepSeek-V4.1-Flash") / id Some("0217925172837...")
已落库: assistant 消息 01a15a5b-...:2，历史 2 个原子单元
```

注意 `model` 这一栏：**非流式回的是别名 `relay-b/DeepSeek-V4.1-Flash`，流式回的是上游真名
`DeepSeek-V4.1-Flash`**。同一个端点、同一次请求，两种模式的 `model` 不一样——
要按真名归因就用流式的 `Meta`。

三个值得记住的点：

- **`Meta.model` 和请求里的名字不一样**（请求 `relay-b/DeepSeek-V4.1-Flash`，上游回 `DeepSeek-V4.1-Flash`）。
  计费按谁算、日志里该记谁，只有拿到这个字段才知道。
- **思考段数远多于正文段数**（18 : 1），`reasoning_tokens=57 / completion=60`。
  和实验报告 §1.5「思考占 completion 的 94–97%」一致。
- **`cached=0`**：第一次请求，前缀缓存还没命中。

### 1.1 `usage` 缺失是真的会发生

| 别名 | 非流式 usage | 流式 usage | 缓存字段命名 |
|---|---|---|---|
| `relay-b/DeepSeek-V4.1-Flash` | 有 | 有 | `prompt_tokens_details`（实测是**空对象**，没有 `cached_tokens`） |
| `relay-c/deepseek-flash` | **无** | **无** | — |
| `relay-a/deepseek-v4.1-flash` | 有 | 有 | Anthropic 风格：`cache_read_input_tokens` |

对流式用法的影响：**不能假设最终会有 `Usage` 事件**。没有就用本地估算兜底
（`core::tokens::estimate_tokens`）——这也是 `budget` 本来就要求客户端自己维护预算的原因。

实测的 `relay-b` 非流式 usage 原文（含一个非标准字段）：

```json
{"prompt_tokens":31,"completion_tokens":8,"total_tokens":39,
 "prompt_tokens_details":{},
 "completion_tokens_details":{"reasoning_tokens":8},
 "points_consumed":1}
```

要点：`prompt_tokens_details` 存在但为空 → 解析必须按"字段可能缺席"写，
不能因为对象在就假定里面有 `cached_tokens`。

### 1.2 流式 delta 的实测形状

```json
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":1792504437,
       "model":"deepseek-flash",
       "choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

data: {"choices":[{"index":0,"delta":{"reasoning_content":"We","content":""}}]}

data: {"choices":[{"index":0,"delta":{"reasoning_content":" need answer in","content":""}}]}
```

- 第一帧只有 `role`、`content` 是空串 → **空串不是"内容"，别产出空 Text 事件**。
- 思考与正文**同时出现在一个 delta 里**，`reasoning_content` 与 `content:""` 并存。
- 字段名在各网关间不统一，实测与文档见过三种：`reasoning_content`（DeepSeek 系）、
  `reasoning`、`thinking`。三者都收，取第一个非空的。

### 1.2.1 流式下 `tool_calls` 会整段消失（实测，会致命）

同一份请求体（同一 model、同一 tools、同一 messages），只改 `stream`：

| 模式 | `tool_calls` | `finish_reason` |
|---|---|---|
| `stream: false` | **2 条**（`compress_draft_append` ×2，各自带完整 arguments） | `tool_calls` |
| `stream: true` | **0 条**（整条流里没有任何一帧带 `tool_calls`） | 空 |

复现方式（本机端点，`relay-b/DeepSeek-V4.1-Flash`）：

```bash
# 非流式：能拿到 2 条调用
curl -s $BASE/chat/completions -H 'content-type: application/json' \
  -d '{"model":"relay-b/DeepSeek-V4.1-Flash","stream":false,...}' | jq '.choices[0].message.tool_calls | length'
# → 2
# 流式：同样请求，把 stream 改 true，grep 整条流
# → 0 帧带 tool_calls
```

**后果**：压缩的工具循环曾经一直拿不到调用，模型每轮只吐文字，循环空转到上限。
看起来像"模型不会用工具"，其实是上游在流式模式下把 `tool_calls` 吞了。

**结论**（D46）：工具循环一律走非流式 `complete()`。压缩是批量任务，没有首字延迟可言，
换来的是一条不依赖上游流式实现的路。等哪天真要流式工具调用，必须先在目标端点上验
这一条——不能靠"协议里有"就假设它会发。

---

### 1.3 角色请求（P3）在同一个端点上的实测

标题总结走的是非流式那条路（`complete()`），实测输出与用量：

```
[role] 标题总结 ok（12 字）
[raw]   "SSE 流式解析空行规则"
[usage] prompt=106 completion=131 reasoning=121
```

`reasoning=121 / completion=131`——我们发了 `reasoning_effort: "none"`，它照样思考。
**"关闭思考"在这个端点上不生效**（实验报告 §1.6 的同一现象）。结论：
角色请求的 `max_tokens` 不能按"只要几个字"去设，得留够思考的量（见 D42 的默认值表）。

## 2. 字段来源（逐端口）

### OpenAI 兼容 `POST {base}/chat/completions`

字段名来源：[DeepSeek/OpenAI 兼容的公开文档](https://developers.openai.com/api/docs/guides/streaming-responses)
（`delta` 而非 `message`、`finish_reason`），逐字面由 §1.2 的实测帧确认。

| 我们读的 | 位置 | 说明 |
|---|---|---|
| 正文 | `choices[].delta.content` | 字符串；也有网关给 parts 数组，两种都收 |
| 思考 | `delta.reasoning_content` / `reasoning` / `thinking` | 按序取第一个非空 |
| 工具 | `delta.tool_calls[].{index,id,function.name,function.arguments}` | 参数是**片段** |
| 结束 | `choices[].finish_reason` | `stop`/`length`/`tool_calls`/`content_filter` |
| 用量 | 顶层 `usage` | 需要 `stream_options.include_usage`，且**有的别名根本不给** |
| 终止 | `data: [DONE]` | 见到就收工，不等 socket 关闭 |

### Anthropic `POST {base}/messages`

来源：[官方 streaming 文档](https://platform.claude.com/docs/en/build-with-claude/streaming)（事件序列与示例逐字对照）。

| 事件 | 我们读的 |
|---|---|
| `message_start` | `message.{id,model}`、`message.usage.input_tokens` |
| `content_block_start` | `content_block.type == tool_use` 时取 `{id,name}`，`index` 是块序号 |
| `content_block_delta` | `text_delta.text` / `thinking_delta.thinking` / `input_json_delta.partial_json` |
| `message_delta` | `delta.stop_reason`、`usage.output_tokens`（**累计值**） |
| `message_stop` | 终止 |
| `ping` | 忽略 |
| `error` | `error.{type,message}` |
| — | `signature_delta` / `citations_delta`：**目前丢弃**（见 §4） |

两个坑：

- `message_start` 里的缓存计数是**占位值**，要等 `message_delta` 才准（多个代理商的文档都这么说）。
  用 max 合并正好能吸收这种情况。
- `input_tokens` **不含**缓存命中部分，所以归一成全量输入时要加 `cache_read_input_tokens`
  （见 §3）。

### OpenAI Responses `POST {base}/responses`

来源：[官方流式事件列表](https://developers.openai.com/api/reference/resources/responses/streaming-events)
与社区整理的事件顺序（`response.created` → `output_item.added` → `output_text.delta` → `completed`）。

| 事件 | 我们读的 |
|---|---|
| `response.created` / `in_progress` | `response.{id,model}` |
| `response.output_text.delta` | `delta` |
| `response.reasoning_summary_text.delta` / `response.reasoning_text.delta` | `delta` |
| `response.function_call_arguments.delta` | `delta`、`output_index`、`item_id` |
| `response.output_item.added`（`item.type == function_call`） | `call_id`、`name` |
| `response.completed` / `response.incomplete` | `response.usage`、`incomplete_details.reason` |
| `response.failed` / `error` | `error.{code,message}` |

`response.completed` 与 `response.incomplete` 都当终止：前者 `stop`，后者 `length`
（`incomplete_details.reason == "max_output_tokens"`）——**截断不能算说完**。
有的网关会再补一个 `data: [DONE]`，也认。

### Gemini `POST {base}/models/{model}:streamGenerateContent?alt=sse`

来源：[GenerateContentResponse 的字段表](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/v1/GenerateContentResponse)
（`usageMetadata` 全字段）与多份代理文档的示例（`parts[].thought`、`finishReason` 取值）。

| 我们读的 | 说明 |
|---|---|
| `candidates[].content.parts[].text` | `thought: true` → 思考，否则正文 |
| `candidates[].content.parts[].functionCall` | `{name,args,id}`，**参数一次给全**，不是片段 |
| `candidates[].finishReason` | `STOP`/`MAX_TOKENS`/`SAFETY`/`PROHIBITED_CONTENT`/`RECITATION`/`CONTINUATION` |
| `usageMetadata` | `promptTokenCount`/`candidatesTokenCount`/`thoughtsTokenCount`/`cachedContentTokenCount` |
| `promptFeedback.blockReason` | 请求被拦时的原因（此时没有 candidates） |
| `modelVersion` / `responseId` | Meta |

三个实测/文档里看到的偏门情况，都处理了：

1. **`alt=sse` 不是可选项**：不带它回来的是一串 JSON **数组**。解析器两种都收，
   但请求路径统一带 `alt=sse`。
2. **`usageMetadata` 是累计的**，每一段都带当前总量 → 取大值合并才对。
3. **网关可能用 HTTP 200 回错误**（`{"code":200,"msg":"..."}`，没有 candidates）。
   只看状态码会当成"成功但空回复"，所以专门判了这种形状。
4. 字段名有两套写法：官方是 `thoughtsTokenCount`，有的网关写 `thinkingTokenCount`，都收。

---

### 2.5 工具消息的端口映射（写这一节是因为四家四处不一样）

| | 助手发起的调用 | 工具结果 | 关键差异 |
|---|---|---|---|
| OpenAI 兼容 | `message.tool_calls[].function.arguments` | `role: "tool"` + `tool_call_id` | `arguments` 是**字符串** |
| Responses | 顶层 `{"type":"function_call","call_id"}` item | 顶层 `{"type":"function_call_output","call_id"}` item | 调用不是消息的字段，是并列的 item |
| Anthropic | `content[]` 的 `tool_use` 块 | **user 轮**里的 `tool_result` 块（`tool_use_id`） | `input` 是**对象**，且结果占的是 user 角色 |
| Gemini | `parts[].functionCall` | `parts[].functionResponse` | **只认函数名**，不认 id；`response` 必须是对象 |

Gemini 那一条最坑：结果消息里只有 `tool_call_id`，没有函数名，所以 `build_body`
会先扫一遍历史的 `tool_calls` 建 id→name 表；查不到就报错（不硬编名字）。

## 3. usage 口径归一（不做这一步，预算会算错）

| 端口 | 上游给的 | 归一后 `prompt_tokens` | `cached_tokens` 取自 |
|---|---|---|---|
| OpenAI 兼容 | `prompt_tokens`（含缓存） | 原样 | `prompt_tokens_details.cached_tokens`，兼容 Anthropic 命名的 `cache_read_input_tokens` |
| Anthropic | `input_tokens`（**不含**缓存） | `input + cache_read + cache_creation` | `cache_read_input_tokens` |
| Responses | `input_tokens`（含缓存） | 原样 | `input_tokens_details.cached_tokens` |
| Gemini | `promptTokenCount`（含缓存） | 原样 | `cachedContentTokenCount` |

归一之后 `prompt_tokens` 一律表示"这次请求实际吃掉的输入量"，
可以和本地估算直接比、也能直接和窗口预算比。
缓存命中率用 `Usage::cache_hit_ratio()`（= `cached / prompt`），**不能拿未归一的值算**。

思考 token：

| 端口 | 来源 | 备注 |
|---|---|---|
| OpenAI 兼容 | `completion_tokens_details.reasoning_tokens` | 也见过顶层 `reasoning_tokens` |
| Anthropic | **没有单独字段** | 思考算在 `output_tokens` 里，所以归一为 0 |
| Responses | `output_tokens_details.reasoning_tokens` | |
| Gemini | `thoughtsTokenCount` | 计入输出计费 |

---

## 4. 这一轮**没做**的（别当成做完了）

| 项 | 现状 | 影响 |
|---|---|---|
| 非流式回复 | **已做**（`complete()`），真机实测见 §1 | — |
| 工具调用 / 工具结果的消息 | **已做**四端口映射（D41），有单测 | 工具**执行**（P7）还没接 |
| 工具声明（`tools`） | OpenAI / Responses / Anthropic 已做；**Gemini 未做**（要 OpenAPI 子集转换，报 `Unsupported`） | Gemini 上不能调工具 |
| Gemini `functionResponse` 的 role | 用 `user`，**未真机验证**（文档里 `user`/`function` 都有） | 若 400 先查这里 |
| Responses 的 `stop` | 该端口没有这个参数，给了发不出去 | 停止词在 Responses 上无效 |
| 思考回放 | OpenAI 兼容可回放；Anthropic / Responses / Gemini 报错 | Anthropic 需要保存 `signature_delta`，消息结构还没这些字段（D20 只落了策略） |
| 归档消息（F2–F5 形态） | 报错 | 要先经过归档取回 |
| TLS | `https://` → `Unsupported` | 云端端点暂时用不了，见 D38 |
| Anthropic `cache_control` 断点 | 没写 | 显式缓存命中率优化没做（隐式缓存不受影响） |
| 代理 / 系统证书库 | 没做 | 需要走公司代理的环境连不上 |
| `stop` 参数在 Responses 下的字段名 | 按通用名 `stop` 发 | 未实测，可能该是别的名字 |

---

## 5. 怎么重验

```bash
# 1) 离线：CI 上跑的就是这些（本机 socket + 预置响应，不需要外网）
cargo test -p nmoi-core transport:: sse:: stream:: runtime::   # 按名字过滤，CI 上是全量

# 2) 真机：换一个别名就换了一个上游，值得多试几个
NMOI_SMOKE_MODEL="relay-c/deepseek-flash" \
  cargo run -p nmoi-core --example provider_smoke     # 这个别名没有 usage，正好验兜底

# 3) 只想看我们发了什么：本地起个回显服务器
#    见 crates/core/examples/provider_smoke.rs 顶部注释的环境变量表
```

> 2026-10-20 的踩坑：冒烟第一次跑挂了 HTTP 400 `upstream_rejected`，报错里没有任何线索。
> 用本地回显服务器抓到真正的原因是**请求体里 `messages` 是空数组**（example 先 `submit`
> 后建请求，忘了把历史装进去）。修完之后 `build_body` 加了空 messages 的前置拒绝：
> 上游对这个错误的表述完全不指向原因，不该让它有机会发出去。
