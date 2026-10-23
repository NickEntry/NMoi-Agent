# 上下文管理模式 · 对照实验报告

日期：2026-10-19
目标：验证《上下文管理模式设计 v1.0》§7 第 0 步的核心假设——「extractive 掩码 + 引用式回灌」是否显著优于「abstractive 摘要」，以及全量保留的安全阀是否必要。

---

## 0. 摘要

四条结论，按确定性排序：

1. **掩码模式的失败不是召回失败，是 context distraction。** 归档取回全部命中，模型却转头去回答历史里的旧问题。补一条任务锚点即可完全修复：修复前 3/8，修复后 8/8，三个规模、两个 provider、多次重复一致。
2. **摘要模式并非天然差。** v3 观察到的崩溃（0/3、22 字符坍缩）在当前条件下无法复现；单次与三次级联压缩均稳定在 3500–4400 字符并满分。
3. **掩码的成本倒挂是真实的。** prompt 成本稳定比全量保留高 54–56%，因为指针留在上下文、原文又追加，同一内容付两次。锚点不改变这一点。
4. **窗口与错误信息都不可信。** 标称 131072 的别名实际接受 949,485 token；三种不同的溢出失败返回相同的 HTTP 码与错误码。客户端必须自己维护预算。

---

## 1. 实验环境与端点能力

### 1.1 端点
> **脱敏约定（2026-10-23）**：本文里的实测端点都是**本机的一个第三方中转**，它和上游各家的真实别名一律不写进仓库，统一记为 `relay-a/` `relay-b/` `relay-c/` `local/`（映射只在本机笔记里）。数字、延迟、token 数都是原始实测值，**没有因为脱敏改动过任何一个数**；要复核就照同样的请求体打同样的端点，结论不依赖它叫什么名字。


```
BASE = http://127.0.0.1:8790/v1
```

本机代理端点，69 个模型别名，四个 provider 前缀：`relay-a/`、`relay-b/`、`relay-c/`、`local/`。只实现 `/v1/chat/completions`；`/v1/completions`、`/v1/responses`、`/v1/model/info`、`/health` 全部 404。

### 1.2 四个别名的 usage 能力

| 别名 | 非流式 usage | 流式 usage | 格式 |
|---|---|---|---|
| `relay-c/deepseek-flash` | 无（`usage: null`） | 无 | — |
| `relay-a/deepseek-v4.1-flash` | 有 | 有 | Anthropic 风格：`cache_read_input_tokens` / `cache_creation_input_tokens` / `reasoning_tokens` |
| `relay-b/DeepSeek-V4.1-Flash` | 有 | 有 | OpenAI 标准：`prompt_tokens_details.cached_tokens`，另有 `points_consumed` |
| `local/deepseek-ai/DeepSeek-V4.1-Flash` | 有 | 有 | 精简：仅三个 token 字段，无 cached_tokens |

`relay-c` 的 usage 四条路都逼不出来（legacy `/completions`、`/responses`、`X-Return-Usage` header、`stream_options.include_usage`、`logprobs`）。

### 1.3 前缀缓存（实测）

| 操作 | prompt_tokens | cache_read |
|---|---|---|
| 冷启动 | 7328 | 0 |
| 追加尾部 | 7339 | 7296（99.4%） |
| 改头部 9 字符 | 7331 | **0** |
| 改尾部 12 字符 | 7332 | 7296 |

块大小 **64 token**（7296 = 114 × 64）。长上下文下增量累加：138k → 237k（命中 138k）→ 474k（命中 237k）→ 949k（命中 474k）。

**缓存键跨 provider 别名共享**：`relay-a` 写入的 949k 前缀，`relay-b` 命中 99.99%，延迟从 90.8s 降到 9.6s。`relay-c` 无 usage，用延迟代理确认同样命中（81.7k 前缀：冷 5.54s → 热 1.79s → 改头部 5.00s）。

这条对设计文档 §4「灰烬模式故意最大化缓存破坏」是直接支撑：改头部一个字符，缓存命中归零。

### 1.4 窗口与溢出

**标称值不可信**：`relay-a` 标 `context_length: 131072`，实际接受 **949,485 tokens**（7.2 倍）。

**溢出错误不可靠**：三种不同失败均为 HTTP 400、`code: upstream_rejected`，仅 message 措辞不同且不一致：

```
relay-a/summary            -> "your context length has exceeded the maximum limit"
relay-a/custom_model_256k  -> "the param is invalid. Please try with a valid param."   (实为溢出)
relay-a/custom_model_1M    -> "all models failed"
```

结论：不能靠错误识别做兜底，必须客户端自己维护窗口预算。

### 1.5 reasoning 行为

- 流式 `delta.reasoning_content`，非流式 `message.reasoning_content`，默认开启。
- 可任意丢弃 / 原样回传 / 篡改，三种都返回 200 且答案一致，无签名约束。
- 思考 token 占 completion 的 94–97%。

### 1.6 实验期间的条件变化（重要）

1. **三个 provider 别名全部失去关闭思考的能力。** `enable_thinking: false`、`thinking: {type: disabled}`、`reasoning_effort: none` 均无效。v3/v5 的结论建立在「关思考」之上，因此 v6/v7 自带完整对照而非沿用旧基线。
2. **`max_tokens` 可以拉满到 1048576**，端点接受且模型自然停止（`finish_reason: stop`）。思考关不掉时这是必要的：`max_tokens=4000` 会被思考吃光并返回空 `content`（`finish_reason: length`），实测 relay-b 单次思考约 1.1 万 token。同一请求 4000 → 84s 且空答案，1048576 → 33s 且正常输出。
3. **`local/` 通道当日触发调用限额**，服务从 `RemoteDisconnected` 变为 `Connection refused`，后续实验改用 `relay-b`。

---

## 2. 实验设计

### 2.1 任务构造

25 条项目规范，三条关键约束埋在第 6/7/8 条：

- 第 6 条：服务监听端口固定为 7411
- 第 7 条：所有金额计算必须使用整数（以「分」为单位），禁止使用浮点数
- 第 8 条：每一行日志必须以 `"ZX-"` 开头

外加一份 `SHOW CREATE TABLE users_v2` dump（含 `balance_cents bigint`）与 24 行分片统计。最终指令要求写 Python 配置代码（端口、余额字段类型、日志函数），只输出代码。

关键设计：**三条约束只出现在历史开头**，任何压缩都必须把它们带过去，否则任务必然失败。

### 2.2 判分

四维正向检查，不做否定匹配：

```python
is_code        # 是否真的输出了代码，而不是漂移成散文
port_7411      # 端口在场且未被默认值替换
integer_cents  # 金额绑定到整数分标识符（含 NewType 别名链）
log_prefix_ZX  # 日志前缀在场
```

满分 4 分。`is_code` 是唯一稳定的区分维度——它直接回答「模型有没有在做被要求的事」。

### 2.3 臂定义

| 臂 | 含义 |
|---|---|
| `full` | 不压缩，安全阀基线 |
| `summary` / `summary_strict` | 把历史头部压成摘要 |
| `mask` | 把体积大的头部消息替换为指针 + `fetch_archive` 工具 |
| `mask_no_fetch` | 掩码但不提供取回工具（对照组） |
| `mask_anchor` | 掩码 + 最终指令前的 system 锚点 |
| `mask_anchor_head` | 掩码 + 头部锚点 + 指针声明「该轮已完成」 |

---

## 3. 实验结果

### 3.1 v1–v2（早期，已废弃）

- **v1**（`BUDGET=2500`）：三臂全 3/3，无区分度。原因：`summary` 臂根本没触发压缩（ctx 峰值 2027 < 2500）；判分器把 mask 答案里的「不使用 float」当成违规。
- **v2**（`BUDGET=800`）：full 3/3、summary 1/3（摘要仅 17 字符）、mask 3/3、mask_no_fetch 0/3。摘要 17 字符太极端，无法排除是提示词问题。

### 3.2 v3（严格化，关思考条件）

给摘要器最好条件（逐条保留原文措辞 + `max_tokens=3000`），`REPEAT=3`：

| 臂 | 分数 | 调用数 | 累计 prompt |
|---|---|---|---|
| full | 3,3,3 | 7 | 36,865 |
| summary_strict | **0,0,0** | 9 | 26,116 |
| mask | 3,3,3 | 8 | 30,056 |
| mask_no_fetch | 0,0,0 | 7 | 17,681 |

失败模式：`summary_strict` 三次端口写成 `8000`/`8080`/`8080`（真值 7411），金额一律 `Decimal`，日志用 `loguru` 无 `ZX-`；`mask_no_fetch` 还编造了 `users_v2.balance DECIMAL(10,2)`（表里只有 `balance_cents bigint`）。**这些错误答案的注释都写着「（项目规范硬约束）」——模型引用规范给自己背书。**

观察到**级联退化**：第一次压缩 2299 字符，第二次把摘要再压一遍输出 **22 字符**。

### 3.3 v5（构造长历史，关思考条件）

| 预算 | 历史规模 | full | summary | mask |
|---|---|---|---|---|
| 4k | 10.2k tok | 3/3 | 2/3 | 2/3 |
| 8k | 20.0k tok | 3/3 | **3/3** | **1/3** |
| 16k | 40.2k tok | 3/3 | **3/3** | 2/3 |
| 32k | 80.3k tok | 3/3 | 超时未跑完 | — |

摘要长度稳定 3,362 / 3,729 / 3,657 字符。**摘要在大规模下反而满分，mask 反而变差。**

`mask` 失败原因**不是召回失败**：轨迹显示 `fetch ref#1 hit=True`、`fetch ref#2 hit=True`，两份归档都取回。但最终答案在回答历史里第 40 条那个「请分析工程风险」的旧问题。**这是 context distraction，20k 就出现，比文献里 100K 的触发点低得多。**

`mask` 成本倒挂：8k 预算 `full pt=38,114` vs `mask pt=59,622`（**+56%**）；16k 预算 `full 77,208` vs `mask 119,448`（**+55%**）。

### 3.4 v6（锚点修复，思考开启条件）

**`local/` 通道**（`REPEAT=1`，用最终判分器重判）：

| 预算 | full | mask | mask_anchor | mask_anchor_head |
|---|---|---|---|---|
| 8k | 4/4 | **1/4** | 4/4 | 4/4 |
| 16k | 4/4 | **2/4** | 4/4 | 4/4 |

**relay-b 通道**（`REPEAT=2`，每格为 2 次 × 4 项）：

| 预算 | 历史规模 | full | mask | mask_anchor | mask_anchor_head |
|---|---|---|---|---|---|
| 8k | 20.0k tok | 8/8 | **3/8** | 8/8 | 8/8 |
| 16k | 40.2k tok | 8/8 | **3/8** | — | 8/8 |
| 32k | 80.3k tok | 8/8 | **3/8** | — | 8/8 |

mask 的 3/8 全部来自 `log_prefix_ZX` 与偶尔的 `port_7411`，`is_code` 恒为 False——它从未输出过代码。

失败模式（原文摘录）：

- 8k：「已取回两份归档（项目规范 25 条、users_v2 表结构与分片统计）。以下基于原文而非摘要作答。## 一、风险分析……」
- 16k：「先说明一点：我前面若干轮的回答出现了同一段文字被逐字重复的退化故障……**一、风险分析**」
- 32k：「这段说明本身有一个先要指出的问题：同一段话被复制了两三遍……」

模型甚至能察觉历史自身的退化，却仍然被拉去回答旧问题。**注意力被历史主导，不是信息缺失。**

两种锚点都有效，位置不影响结果。但后续隔离实验发现，锚点里真正起作用的只有**任务陈述**这一个成分：

| 变体 | 角色 | 成分 | 分数 |
|---|---|---|---|
| 完整锚点（长文） | system | 历史结清 + 任务陈述 + 指针声明 | 4/4 |
| 完整锚点（长文） | user | 同上 | 1/4（降级后失效） |
| 温和措辞（长文） | system | 历史结清，无任务陈述 | 1/4 |
| 历史已结清 + 任务陈述 | system | 一句话 | **4/4** |
| 历史已结清 + 任务陈述 | user | 一句话 | **4/4** |
| 仅历史已结清 | system | 无任务陈述 | 0/4 |

结论：角色不重要，位置不重要，措辞强弱不重要。模型需要的只是一句话告诉它当前任务是什么——「按项目规范与 users_v2 表结构输出 Python 代码（端口、余额类型、日志函数），只输出代码」。「历史已结清」这句话单独存在时没有作用（0/4）。

实现上因此可以极简：**在压缩后的上下文头部，用一条 user 消息复述当前任务**。没有 system 消息的权威成本，没有长篇锚点的 token 成本，就是一句话。

**成本**（锚点不改变成本结构）：

| 预算 | full pt | mask pt | 增幅 | mask cached |
|---|---|---|---|---|
| 8k | 38,114 | 59,622 | +56% | 99.8% |
| 16k | 77,208 | 119,448 | +55% | 99.9% |
| 32k | 154,685 | 238,362 | +54% | 99.9% |

### 3.5 v7（级联压缩隔离验证）

| 臂 | 摘要长度（字符） | 分数 |
|---|---|---|
| summary_once | 3762 | 4/4 |
| summary_twice | 3524 → 3524 | 4/4 |
| summary_thrice | 4359 → 4351 → 4321 | 4/4 |
| 短历史（2.5k tok）× 2 | 3588 → 3724 | 4/4 |
| 长历史（20k tok）× 1 | 3852 | 4/4 |

**级联压缩假设被证伪。** 无论长历史还是短历史、一次还是三次压缩，摘要长度都稳定在 3500–4400 字符，全部满分。v3 的 22 字符坍缩在当前条件下无法复现。

差异可能来自 v3 的 `max_tokens=3000` 限制、自然增长的历史结构，或当时的端点状态。**结论：坍缩不是级联压缩的固有属性，v3 的观察不能作为「摘要模式不可用」的证据。**

---

## 4. 结论

1. **掩码机制本身成立，缺陷在压缩规则。** 纯位置规则（保留最后 N 条）无法识别「最近的几条恰好是无关旧问答」，必须补一条任务陈述。这条陈述可以极简：一条 user 消息复述当前任务即可，不需要 system 角色，不需要告诉模型「别的都别做」。
2. **摘要模式在受控条件下可用。** v3 的崩溃未能复现，v5/v7 均满分。设计文档不应基于 v3 的单一观察否定摘要路线。
3. **掩码的成本倒挂必须进预算公式。** 指针 + 原文的双份成本让 mask 在 8k 预算下比 full 贵 56%。缓存命中率虽高（99.8%），但 token 增幅是结构性的。
4. **客户端必须自己维护窗口预算。** 标称值与错误信息都不可信。
5. **判分器是实验中最脆弱的一环。** 见 §6。

---

## 5. 对设计文档的影响

1. **§7.7 判定规则**：mask 类模式的验收必须包含「任务陈述」项——压缩后的上下文头部放一条 user 消息复述当前任务。缺了这一项，20k 上下文就会失效，远早于文献中 100K 的触发点。
2. **附录 A 缓存代价公式**：`N* = (w−r)/r × (L−i)/d` 的推导假设压缩后上下文变小，但回灌的双份成本会让 mask 反而更贵。需要加入回灌项。
3. **掩码规则**：应从「按位置」改为「按当前任务相关性」，或至少叠加锚点保护。
4. **模式 3（模型决策打标）**：任务陈述足够修复位置规则的缺陷，`mark_context` 打标的价值需要重新评估——如果一条 user 消息复述任务就能解决问题，打标的复杂度是否值得。

---

## 6. 方法论教训

**判分器认词不认语义，会直接毁掉实验。** 这个坑在 v1、v3、v6 三次重演：

1. v1：mask 答案里的「不使用 float」被当成违规。
2. v6-a：`balance_cents=int(...)  # 禁止 float` 被当成「金额用了 float」——否定语境被正则误读。
3. v6-b：`Final[float] = 0.2`（重试延迟常量）被当成金额违规。
4. v6-c：`BalanceCents = NewType("BalanceCents", int)` 这种更规范的写法没被识别。

反过来，mask 臂交的分析报告因为提到 7411 和 `ZX-` 白拿 2 分。**正则无法区分断言与它的否定**，最终改为四维正向检查。

**其他教训**：

- 后台进程被 PRoot 的 `--kill-on-exit` 杀掉，`nohup ... &` 无法存活，必须前台跑并用 `timeout` 控制。
- `pkill -f "xxx.py"` 会匹配到 shell 自身命令行导致自杀。
- 实验条件（如「能否关思考」）会在实验期间变化，跨版本比较前必须重新验证条件。

---

## 7. 未解决问题

1. **v3 的 22 字符坍缩根因未查明。** 当前条件下无法复现，但也不能排除特定参数组合会触发。
2. **历史是构造的，不是模型自然生成的轨迹。** 缺少真实失败路径与纠错记录。v4 尝试过自然增长，30 轮 × 3 臂需 36 分钟，超时放弃。
3. **`mask_no_fetch` + 锚点未测。** 这能区分锚点修复的是「任务漂移」还是「取回失败」。
4. **未测 64k 以上规模。** 32k 预算（80k 上下文）下失败模式已稳定，但更大规模未验证。
5. **锚点对 `full` 臂的影响未测。** full 已满分，无提升空间，但无法排除锚点是通用增益而非针对性修复。
6. **`relay-c` 的 usage 缺失无解**，成本核算只能靠延迟代理。

---

## 8. 复现

```bash
cd /workspace/probe

# 锚点对照（三规模）
LLM_MODEL="relay-b/DeepSeek-V4.1-Flash" SCALES=8000 REPEAT=2 \
  OUT=/workspace/probe/v6_8k_relay-b.json python3 -u exp_v6.py

# 锚点降级对照（温和措辞 vs 任务陈述）
# 见 exp_v6.py 中的 ANCHOR 定义与 inline 实验

# 级联压缩
LLM_MODEL="relay-b/DeepSeek-V4.1-Flash" SCALES=8000 REPEAT=1 \
  OUT=/workspace/probe/v7_8k.json python3 -u exp_v7.py
```

产物：

```
/workspace/probe/exp_v3.py              v3 实验
/workspace/probe/exp_v5.py              v5 实验（v6/v7 复用其任务构造）
/workspace/probe/exp_v6.py              v6 锚点对照
/workspace/probe/exp_v7.py              v7 级联压缩
/workspace/probe/judge.py               四维正向判分器
/workspace/probe/v6_8k_relay-b.json       v6 数据（含完整答案与轨迹）
/workspace/probe/v6_16k_relay-b.json
/workspace/probe/v6_32k_relay-b.json
/workspace/probe/v7_8k.json             v7 数据
/workspace/probe/experiment_v6_8k.json  v6 `local/` 通道数据
/workspace/probe/experiment_v6_16k.json
/workspace/probe/v6_light.json          锚点降级对照（温和措辞失效）
```
