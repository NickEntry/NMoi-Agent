# tokenizer 条目、token 显示与旧估算退役（设计稿，2026-11-26）

> 用户 2026-11-26 的原话：
> 「能不能不做成这种全局一个 json 加载，而是能新建项，然后配置每一项的名称、模型匹配关键词/正则、
>   然后导入 tokenizer.json」
> 「必须要有关键词/正则，且必须有 tokenizer.json，条目才能成功创建，然后可以编辑和删除条目，
>   有适配的 tokenizer.json 的就用，按照条目排序来决定匹配的优先级，最上方的条目最先匹配，
>   内置 tokenizer.json 是作为没有命中规则下的默认回退的 token 计算用的」
> 「这种计算要标注预估，然后每条消息结尾的 tokens，显示位置参考图片这个 641642 tokens，
>   只有正常请求的助手消息结束时会生成这个，这里写的是 provider 返回的上下文数据，
>   如果某些端点不支持所以没返回的话就填 unknown tokens」
> 「加号菜单里面的上下文管理里面的显示，如果是通过 tokenizer.json 算出来的就写(预估)，
>   如果助手消息生成结束后，能获取到上下文情况的话就可以写成(实际)」
> 「之前的旧的 token 估算方法可以总结成 md，归档到 /workspace/old_context.md」
> 「项目里面就不要再存这套旧的计算了？」

这一稿是**设计**，这一轮没有写进代码。旧的估算已经归档到 `/workspace/old_context.md`，
退役顺序在 §8（不能反过来）。

---

## 0. 一句话

**一个模型家族一套词表**：条目（profile）= 名字 + 匹配规则（关键词/正则）+ 一份
`tokenizer.json`。按列表顺序从上往下匹配，第一个命中的用它；一个都没命中，用**内置那份**
兜底。算出来的数一律标「预估」；消息跑完、端点回了 usage 的，标「实际」。

---

## 1. 许可：能内嵌，但只内嵌一份

`deepseek-ai/DeepSeek-V4.1-Flash-1017` 的 `LICENSE` 是 **MIT**
（`Copyright (c) 2023 DeepSeek`，2026-11-26 核对）。

- MIT 与 GPL-3.0 **兼容**：MIT 是最宽松的一档，可以被 GPL 项目直接包含；
  义务只有一条——**保留版权声明与许可全文**。所以"内嵌这份 tokenizer.json 会不会
  影响本项目本体的许可"——不会。
- 要做的事：`LICENSES/` 里加一份 `deepseek-tokenizer-MIT.txt`（原文照抄），
  并在 `notes/licenses.md`（或现有许可清单）里记一行"内置 tokenizer 词表来自
  DeepSeek-V4.1-Flash-1017，MIT"。
- **只内嵌一份**：其它模型（Qwen / Llama / 各家）的词表许可各不相同（有的带使用限制，
  有的要求同意条款），一份一份审下来不值当。用户自己导入的那几份，责任与许可是他的。

成本（实测，本机 aarch64 PRoot，Python `tokenizers` 0.23.3）：

```text
文件          6,367,257 字节（128k 词表 / 127,741 merges）
JSON 解析     425 ms（纯 json.load）
Tokenizer 就绪  903 ms
中文          288 k 字符/秒（9,680 字符 → 5,280 token，33.6 ms）
混排          416 k 字符/秒（15,200 字符 → 6,000 token，36.6 ms）
```

顺带印证了换词表的必要性：**同一段中文，o200k 数出 6,380 token，DeepSeek 自己的词表
数出 5,280**——高估 21%。在"窗口刚好差一点"的时候，这 21% 就是"该压没压"。

---

## 2. 条目（profile）的形状

```jsonc
{
  "id": "t1",                       // 生成，不可改
  "name": "Qwen2.5",                // 用户起的名字，用来认人
  "match": { "type": "keyword", "value": "qwen" },   // 或 {"type":"regex","value":"^qwen2\\.5"}
  "file": "tokenizers/t1.json",     // 落在 files/tokenizers/ 下，导入时复制进来
  "bytes": 7031645,
  "imported_at": 1764201600000,     // 界面上显示"加载于 2026-11-26 22:00"
  "source": "url:https://…"         // 或 "file:<原文件名>"，只为说清它从哪来
}
```

**创建的前置条件（两条都得满足，缺一条就不许建）**：

1. `match.value` 非空（关键词至少一个字符；正则必须能被编译）。
2. `tokenizer.json` 已经导入并且**解析通过**（见 §3 的三道校验）。

界面上就是"建不出来"——按钮灰着，并写清缺哪一条。**不做"先建空条目再补"**：
空条目会进列表参与匹配，而它什么都不匹配，等于在半路加了一个空档。

**排序即优先级**：列表从上到下，第一个命中就用它。改顺序的两个手段：

- 先给**上移/下移两个箭头**（确定性最好，也最好测）。
- 拖拽排序是后话：它要跟"横滑删除""长按多选"抢手势，值得单独一轮。

**匹配对象是哪个字符串**：模型 id（`trae-cn/DeepSeek-V4.1-Flash-1017` 这种，
带不带供应商前缀都试一次）。**不匹配显示名**——显示名用户可以随便改，
拿它做规则等于让"改个名"改变计费口径。

**关键词怎么比**：不区分大小写的**包含**（`qwen` 命中 `Qwen2.5-72B`）。
正则按原样编译、不区分大小写（`RegexOption.IGNORE_CASE`），
编译失败在创建时就拦住，不留到匹配时——那时候抛异常等于整页崩。

---

## 3. 导入：URL 与文件，两条路一道闸

```text
设置 → 上下文 → tokenizer 词表 → 新建条目
   名称       [ Qwen2.5                ]
   匹配       [关键词 ▾] [ qwen        ]
   词表       [ 从 URL 导入 ] [ 从文件导入 ]
              ✓ qwen2.5-tokenizer.json · 7.0 MB · 加载于 2026-11-26 22:41
```

- **从文件**：`GetContent("application/json")`，读进 `files/tokenizers/<id>.json`。
- **从 URL**：走应用已有的代理设置（`ProxyRepository`），下载到临时文件再校验、再落盘；
  显示进度与已下载字节，失败说清是哪一段（DNS / TLS / 404 / 不是 JSON）。
- **大小与时间**：卡片上写 `7.0 MB · 加载于 2026-11-26 22:41`。这两个数是用户唯一能
  判断"我导的是不是那一份"的依据，必须显示。

**三道校验**（顺序不能换）：

1. **大小**：上限 32 MB。超过就拒——词表是数据，32 MB 已经比所有已知词表大四倍。
2. **是不是 JSON 对象**：`JSONObject` 解析 + 必须有 `model` 与 `added_tokens`。
   只解析外层结构，不建整棵树（6 MB 全建一次要 400 ms，在 UI 线程上就是掉帧）。
3. **能不能真的切一次**：拿 `"hello 你好"` 切一遍，得得到非空 ids。
   这一道是**唯一能证明"这份文件真能用"**的检查；前两道只能证明它是 JSON。

失败一律**不落盘**：半份词表在盘上，下一轮会有人以为它已经导好了。

---

## 4. 匹配与回退（这一节要单测）

```text
match(modelId):
    for profile in 列表顺序:
        if 规则命中(profile, modelId): return profile
    return 内置
```

判据（纯函数，`TokenizerProfilesTest`）：

1. **顺序优先**：两条都能命中时，用上面那条（不是"更具体的那条"——具体与否是人的判断，
   顺序是人的决定）。
2. **不命中就回退内置**，不抛、不空。
3. **坏正则跳过**（导入时就该拦住；万一文件被手改，跳过而不是整页崩）。
4. **关键词不区分大小写**；正则也不区分。
5. **模型 id 带供应商前缀时两边都试**：`p1/qwen2.5` 与 `qwen2.5` 都要能命中 `qwen`。

---

## 5. 标注：（预估）/（实际）/ unknown

三种数，三种来源，**不许混着显示**：

| 标记 | 从哪来 | 什么时候有 |
|---|---|---|
| **（预估）** | 本地词表算的（命中条目 or 内置） | 任何时刻都有 |
| **（实际）** | 端点回的 `usage.prompt_tokens` | 只有"正常请求"的那一轮结束之后 |
| **unknown tokens** | 端点**没回** usage | 那一轮结束之后 |

规则：

- 消息尾部那一行（截图里 `641642 tokens` 那个位置）**只出现在正常请求结束的助手消息上**。
  工具往返里的中间消息、被压缩掉的、用户消息——都不显示。
- 端点没回 usage：写 `unknown tokens`，**不拿预估算出来的数顶上**。
  这里最容易犯的错是"没有实际值就退回预估值"——那会让用户以为端点回了。
- 上下文管理面板：有实际值就写（实际），没有写（预估）。**同一页里两种标记可以同时出现**，
  但要能一眼看出哪一行是哪个——因为"顺手滑到 90%"和"真的到了 90%"是两件事。

## 6. 数据从哪来（这一节要说清，不然做不出来）

现在 `turn_json` 的返回里**没有** usage（只有 `complete` 那条有）。要做三件事：

1. **core / ffi**：`turn_json` 的 report 加一段 `usage: {prompt, completion, cached}`，
   端点没回就是 `null`（区分"没回"与"回了 0"）。
2. **落库**：`messages` 加三列（`usage_prompt` / `usage_completion` / `usage_cached`），
   schema **v21 → v22**。这是内容，必须进库（铁律②：界面上要看得到的东西不能只活在内存）。
   只有那一轮的**最后一条助手消息**写这三个数；中间的工具往返不写。
3. **界面**：消息尾部那一行读这三列；null → `unknown tokens`。

迁移：`ALTER TABLE messages ADD COLUMN usage_prompt INTEGER;` 三条，
旧行是 NULL（= 那时候没有这个数据，不是 0）。

---

## 7. 计算层怎么做（Rust 侧）

> **2026-11-26 先卡住了（实测，见 §7.0）**：`tokenizers` 这个 crate 在 MSRV 1.75 上装不进来。
> 在解决之前，这一节是"计划"，不是"现状"。

### 7.0 实测：tokenizers 装不进来（2026-11-26）

在 `/tmp/toktest` 里单独建了一个 crate 试，**没有动仓库**：

| 试到哪一步 | 结果 |
|---|---|
| `tokenizers = "1.0.0-rc.5"` | 依赖树里有包要求 `edition2024`，Cargo 1.75 直接拒绝解析 |
| `tokenizers = "0.20.3"`（`fancy-regex`） | `rayon-core 1.13.0` 要求 rustc 1.80 |
| 手动钉 `rayon 1.10.0` + `rayon-core 1.12.1` | `unicode-segmentation 1.13.3` 要求 rustc 1.85 |
| 再钉 `unicode-segmentation 1.11.0` | `monostate 0.1.18` 要求 rustc 1.79 |
| 钉 `monostate 0.1.14` | 编到 tokenizers 本体，报 `use of undeclared crate or module onig`（`fancy-regex` 那条路在这个版本里不完整） |
| 换默认特性（走 onig） | `onig 6.5` 用了 `&raw const`（`unsafe` 的 raw-ref 语法，**rustc 1.82 起**）→ 15 个编译错误 |

也就是说：**不是"钉一个版本"能解决的**，卡点在两条线上——`onig` 要 1.82+，
`tokenizers` 的依赖树里还有几个包要 1.79–1.85。而本机只装了 1.75，`rustup` 也没有。

两条路，**都要人拍板**：

1. **抬 MSRV 到 1.82+**（`Cargo.toml` 的 `rust-version`，D10 定的是 1.75）。
   代价：本机这个沙箱仍然只有 1.75 → **本地一条也编不出来**，所有 Rust 侧的验证只能交给 CI。
   而且 onig 是 C 库，交叉编译到 `aarch64-linux-android` 还要 NDK 的 CC，
   CI 上大概率要再调一轮。
2. **自己写一个最小 BPE**（不加任何新依赖）：解析 tokenizer.json 的
   `model.vocab` + `model.merges` + `pre_tokenizer`（ByteLevel / Metaspace），
   按 HF 的规则切分并做合并。
   代价：这是**真的要在我们这边实现一遍分词**，正确性风险集中在"预切分正则"与
   "byte-level 映射表"两处。好处是**可以逐 token 对账**——`/tmp/ds.json` 与 Python
   `tokenizers` 就在手边，同一段文本输出 ids，我们那份必须**逐个相等**，
   差一个就算没做完。这条对账比"两个估算值差 3%"硬得多。

我倾向 2：它不牵动 MSRV、不牵动跨平台编译，而"对不对"有硬判据。
但它是几百行的实现，得单独一轮——**这一轮没有动手**。

### 7.1 其它（原计划）

`crates/core/src/tokens/` 现在是 tiktoken 那套。新的这一层要：

- **按条目加载**：`Tokenizer::from_file`（HF `tokenizers` crate）。懒加载 + 进程内缓存
  （按文件哈希，不按路径——用户换了文件但没改名时，路径相同内容不同）。
- **只在你真需要的时候加载**：903 ms 是一次性的，但也不能在启动时给所有条目各来一遍。
  第一次用到某个模型时才加载它那一份。
- **增量**：按消息缓存 token 数（键是内容哈希），只算新增与正在变的那条
  （实测：19 万 token 的上下文全量 1.2 s，只算最后 3 条 23 ms）。
- **节流**：全量重算（编辑早期消息 / 切版本 / 压缩之后）放 IO，且"正在算就记 dirty，
  算完补一次"；主线程不许出现 > 8 ms 的单点。

内置那份的加载时机：**第一次没有任何条目命中时**才加载。用户如果只用自己的条目，
那 6.37 MB 就永远不进内存。

---

## 8. 旧估算怎么退（顺序不能反）

完整归档在 `/workspace/old_context.md`（L0/L1/L2 的实测数字、代码落点、删除顺序）。

1. 新路径跑通并能对账（同一批文本，新旧两个数放一张表里，误差要能解释）。
2. 压缩判据切过去（`compress.rs` 的切点、`budget.rs` 的余量）。
3. 界面百分比切过去。
4. **最后**删 `tokens/bpe.rs`、`tokens/class.rs`、分级那套；留 `calibrate.rs`
   （用 usage 反推比例的校准，它是唯一的对账锚）与一个几行的字符增量探测器
   （流式用，不算估算）。

**为什么不能现在就删**：删了 `bpe` 之后 `compress.rs` 的切点判据没有来源，
压缩闸会**静默**失效——长会话在某一轮直接撞上游上限，而没有任何报错。

回答用户那句"项目里面就不要再存这套旧的计算了？"：**要退，但得按上面这个顺序退**。
现在删等于把压缩闸摘了。

---

## 9. 判据

1. 没有匹配规则、或没有可用词表的条目**建不出来**，且按钮上写清缺哪一条。
2. 两条都能命中时，用列表上面那条。
3. 一个都不命中 → 内置那份兜底，且**第一次用到时才加载**。
4. 导入的卡片上写着大小与加载时间。
5. 坏文件（不是 JSON / 没有 model / 切不出 ids）不落盘，并说清是哪一道没过。
6. 消息尾部只在"正常请求结束的助手消息"上出现一行，且要么是数字要么是 `unknown tokens`。
7. 上下文管理面板里，（预估）与（实际）能同时出现，且不混。
8. 编辑一条早期消息之后，只有它那一格重算（其余命中缓存）。

## 10. 顺序

1. **§2/§3/§4 条目 + 导入 + 匹配**（纯逻辑能单测的那一半先做，UI 跟着）。
2. **§7 计算层**：HF `tokenizers` 接进 core，先只给"上下文管理面板"用。
3. **§6 usage 落库**（schema v22）+ **§5 消息尾部那一行**。
4. **§8 退役**：压缩判据切到新路径，然后删旧的那三级。
5. 拖拽排序（可选的体验项，单独一轮）。

---

## 11. 各家词表从哪来（2026-11-26 查证）

用户点名了这一批：qwen / deepseek / gpt / claude / gemini / glm / kimi / doubao。
分类只有三种情况，处理方式各不相同。

### 11.1 有 HuggingFace `tokenizer.json` 的（BPE，我们的主路）

Qwen、DeepSeek、GLM、Kimi、Doubao 都在 HF 上直接发 `tokenizer.json`（BPE + ByteLevel，
6–7 MB，128k 上下词表）。**这就是 §7 要写的那条路**，`/tmp/ds.json` 已经验过结构：

```json
pre_tokenizer: Sequence[ Split(\p{N}{1,3}), Split([一-龥぀-ゟ゠-ヿ]+),
                        Split([!...][A-Za-z]+|...|\s+(?!\S)|\s+), ByteLevel(use_regex=false) ]
model.type: BPE, merges: ["Ġ t", "Ġ a", ...]   ← 空格分隔的字符串形式
```

两点要注意：**预切分是显式写在 JSON 里的 `Split` 正则**（不是 GPT-2 内置那一条），
所以我们的实现要**读 JSON 里的正则**而不是写死一份；`merges` 有「`"a b"` 字符串」与
「`["a","b"]` 数组」两种写法，都要收。

### 11.2 Claude：社区导出的 `tokenizer.json`（可用，但来源要标清）

`Xenova/claude-tokenizer`（HF）是 Claude 词表的 HF 兼容版，**约 1.7 MB / 65k 词表**，
README 写明"adapted from anthropics/anthropic-sdk-python"，`encode('hello world') == [9381, 2253]`。
形态就是普通 `tokenizer.json`，走我们同一条路即可。

风险要说清：**它是社区从官方 SDK 里导出的，不是 Anthropic 发布的一份词表**。
所以条目里的 `source` 字段要能区分「官方发布 / 社区导出 / 用户自备」——
三者的许可风险不一样，而用户导进来之后责任是他的。

### 11.3 Gemini：没有 tokenizer.json，但有一个确定的答案

查到的结论比"没有"更有用：

- Google 官方 Python SDK 里有 `google.genai.local_tokenizer.LocalTokenizer`（离线计数）。
- Rust 侧有 `gemini-tokenizer` crate，文档写明：**所有 Gemini 模型共用同一个分词器——
  Gemma 3 的 SentencePiece 词表，262,144 个 token**，它把那份词表嵌进 crate。
- 也就是说 Gemini 这一格不是"猜不到"，是"词表不在公开的 tokenizer.json 里，
  而在 Gemma 3 的 SentencePiece 模型里"。

**许可要单独看**：Gemma 词表受 Gemma Terms of Use 约束（不是 MIT），内嵌或再分发有额外条件。
所以它只能走"用户自己导入"，不能随包带。

### 11.4 GPT：格式不一样（这条是坑）

OpenAI 发的是 `.tiktoken`（BPE rank 表），**不是 `tokenizer.json`**。三种处理：

1. 复用现有 `tiktoken-rs`（已经编在包里，o200k / cl100k）——GPT 这一格**本来就有**，
   只是它不在"条目"这一层里。将来把它降级成一条"内置条目"就统一了。
2. 让用户导 HF 版（`Xenova/gpt-4o` 这类社区导出）。
3. 我们自己也读 `.tiktoken`——多一种格式，收益不大。

倾向 1：GPT 走内置的两份 tiktoken 词表，不占用户的条目列表。

### 11.5 对实现范围的影响（这一条改计划）

原来 §7 只写了 BPE。按这一批模型，**必须再加两种**：

| model.type | 谁 | 实现要点 |
|---|---|---|
| **BPE** | Qwen / DeepSeek / GLM / Kimi / Doubao / Claude / GPT(HF 版) | 按 rank 合并；预切分读 JSON 里的 `Split` 正则 |
| **Unigram** | Gemini（Gemma 3）、部分中文模型 | Viterbi 最大概率切分；262k 词表不能每步全表扫，要按首字符分桶 |
| **WordPiece** | BERT 系（备用） | 最长匹配 + `##` 续词 |

再加一个**格式判别**：`model.type` 不认识就拒绝导入并写清"只认 BPE / Unigram / WordPiece"，
不要让它在计数时静默出错。

### 11.6 于是 `source` 字段要分三档

```text
official   厂商自己发布的词表（DeepSeek / Qwen / GLM / Kimi / Doubao 的 HF 仓库）
community  社区从官方 SDK 里导出的（Claude 的 Xenova 版）
user       用户自己准备的（Gemini 的 Gemma 词表、其它）
```

界面上在卡片副标题里写一句来源，导入时把 URL / 文件名存进 `source`。
这一栏不参与匹配，只用来回答"这份东西的许可该找谁"。
