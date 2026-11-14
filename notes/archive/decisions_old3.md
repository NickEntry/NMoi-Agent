# 决策记录（归档 3：D147–D183）

> 从 `notes/decisions.md` 切出来的。**编号不变**，D 编号全仓库唯一，所以引用照样指得到。
> 切分规则见 `notes/archive/README.md`。

---

## D147 摘要要用**第一人称**写：它比"多留几条原话"更能让 AI 觉得那是自己的事

**背景**：用户 2026-10-26 要求验证"不留思考消息时，内容怎么做到高置信度"。用不输出思考的
测试模型（`reasoning_tokens = 0`，内部仍在思考）跑同一套状态槽探针，5 槽 × 2 采 × 6 条件。

**指标**：高置信 = 回复用了第一人称归属（我记得 / 我说过 / 是我 / 我叫）**且**没有文件口吻
（摘要 / 记录 / 根据 / 提到过 / 上下文）。纯文件口吻 = 只有后者、没有前者。

| 条件 | n | 高置信 | 纯文件口吻 |
|---|---|---|---|
| 完整历史 | 8 | **75%** | 0% |
| 只摘要（第三人称） | 9 | 22% | 22% |
| 摘要 + 3 条原话 | 10 | 30% | 20% |
| 摘要 + 状态段 | 10 | 30% | 0% |
| 摘要 + 状态段 + 原话 | 8 | 38% | 12% |
| **第一人称记忆 + 状态段 + 原话** | 8 | **62%** | **0%** |

**决定**

1. **摘要必须用第一人称写**：「我叫 Neko」「我说过我最讨厌香菜」「我前一天自己先去看过土」，
   不写「用户与助手互相认识过」「助手提到自己……」。
   实测这一项单独就把高置信从 22–38% 抬到 **62%**，并且把文件口吻打到 **0%**。
2. **摘录只解决语气，解决不了归属**：3 条原话把 22% 抬到 30%，收益远小于改人称。
   所以两者不是替代关系——原话保语气，人称保归属，状态段保事实在场。
3. **状态段把文件口吻从 20% 压到 0%**（C3），但它不产生"这是我的"效果（高置信仍 30%）。
4. 三者叠加（C5）仍低于完整历史的 75%，说明压缩条件下还有约 13 个百分点的缺口，
   下一层再看要不要用"角色自述块"补。

**要注意的两件事**

- 这个测试模型本身有质量问题：中文回复里会蹦出英文残词（`wholes` / `preventable` / `gnaws`），
  约 12% 的调用直接 HTTP 500。所以绝对数值只当方向看，不能当定论。
- 反向发现：**完整历史在对抗性提问下反而更容易松口**（"嗯，你说得对。Neko 只是我在这里的名字"），
  而第一人称摘要的回答是"名字是我自己认的，不是随便贴的标签……这些不是设定，是我自己的事"。
  也就是说"高置信"不是靠信息量堆出来的，是靠**视角**。

### D143 实现记录（2/2：摘录接进 commit，2026-10-26）

- `CompressionJob` 多存一份**窗口冻结快照** `below`（切点之下那段）。摘录只从这里取，
  所以二次压缩是替换而不是叠加——旧的摘录只活在摘要正文里，不会再抄一遍。
- `finish()` 里算 `injected = 摘要 + render(pick(below))`：
  **归档条目**、`summary_injection()`、trace 里的字数都用 `injected`。
  不然会出现"库里存的是草稿、上下文里是草稿+摘录"的分叉，重启前后看到的东西不一样。
- `CompressionOutcome` 拆成两个字段：`summary`（模型写的草稿，调试/审计用）与
  `injected`（真正进上下文的那份）。老测试里 `archive.fetch(summary_ref) == outcome.summary`
  这条断言跟着改成 `== outcome.injected`，并补了两条：注入份必须以草稿开头、必须含 `[原文节选`。
- `compress::runner::SCAFFOLD` 加第 5 条：通篇用第一人称写（D147 的依据写在提示词里，防止以后被删）。
- 测试：core 504、storage 28+5+17+9+13、ffi 24+1 全过。

### D144 实现记录（2026-10-26）

- `Budget` 加 `reserve_fixed` 字段 + `with_reserve_fixed(tokens)`；`usable()` 依次减 `reserve_output`
  与 `reserve_fixed`。`evaluate_trigger` 用的是 `budget.usable()`，所以两条线自动按"扣过固定成本的窗口"算，
  不用改判定表。
- 传进来的必须是**最坏情况的固定占用**（sys + 世界书常驻 + 命中上限 + 摘要 + 摘录），不是当前实际占用——
  拿实际值算，书一变窗口就变，判定会抖。
- 测试三条：`fixed_costs_shrink_usable`（128k/4k 场景 124,000 → 104,000）、
  `fixed_costs_make_threshold_fire_earlier`（同样 80k，扣 20k 固定成本后从 `None` 变 `Suggest`）、
  `fixed_costs_never_underflow`（固定成本超过窗口时 `usable=0`、`ratio=1.0`，不出 NaN）。
- **没做**：`reserve_fixed` 目前还没有人往里面填数——谁算世界书命中上限、谁调 `with_reserve_fixed`，
  要等请求构造那条路（`turn_run`）接上时一起做。

### D143 实现记录（3/3：摘要长度闸，2026-10-26）

`commit()` 现在**先查长度再查覆盖率**：

```text
draft_tokens > SUMMARY_MAX_TOKENS (2048)
  → rounds += 1；超 max_rounds 转 Forced + TooManyRounds
  → 否则 state = Rejected + CompressError::TooLong { tokens, limit }
```

runner 侧对 `TooLong` 的处理和 `Coverage` 一样：把"先删重复的、可推断的内容，只留事实/决定/未完成项"喂回去。
顺序是先查长度，因为反过来模型为补覆盖率只会写得更长。

测试两条：`commit_rejects_overlong_summary`（2200 字草稿 → `TooLong`，状态 `Rejected`，报的 token 真的超限）、
`overlong_summary_burns_rounds_then_forces`（连续超限把 `max_rounds` 烧完 → `TooManyRounds` + `Forced`）。

### D144 实现记录（补：兜底路径也受闸管）

`commit_fallback` 以前会把每条归档指针都列进摘要，历史一长兜底摘要本身就超预算——
等于"为了防止爆窗口而做的兜底，自己把窗口撑爆"。

现在：指针清单上限 `FALLBACK_LIST_TOKENS = 1024`（摘要上限的一半，另一半留给标题与尾巴）。
超了就只列前面一部分，尾巴写「其余 N 条未列出，原文都在归档里，可按 ref 回灌」，
统计行同时给总数与已列数——**不许假装都列了**。归档一条不少（`archive.put` 在截断判断之外）。

测试：`fallback_summary_respects_length_gate`（300 条短消息 → 兜底摘要 ≤ 2048 token，且含「未列出」「共」）。

### D137 实现记录（第四层：面板自己读版本实况，2026-10-26）

**用户报的两个现象**：① 看着 `2/2`，按下「编辑」却改到了 `1/2`；② 「复制为新版本」要点两次才生效。

**病根是同一件事：面板拿着的是"点开那一刻的对象"，动作也用它。**
`moreFor` 存的是 `ChatMessage` 副本，列表重读之后面板并不会更新；而「编辑」把 `message.versionActive`
（副本里的旧值，可能是 0 或 1）直接当版号写库。于是**显示用一份、动作用另一份**，
"看着 2/2 改到 1/2"就是这么来的。

**改法**：谁要动谁，谁自己去问库。

- `ChatRepository.versions(databasePath, messageId)`：读 `nmoi_storage_versions_json`，给
  `VersionInfo(count, active, edited, view)`。多了个 `VersionInfo` 与 `NmoiFfi.versions()` 包装。
- `ChatScreen` 加 `moreVersion`（`produceState`，key = `moreFor?.id` + `reloadKey`）：
  面板一打开就现读一次库；`sheetCount` / `sheetActive` 优先用它，读不到才退回列表那份。
- 「编辑」打开时把 `editingVersion = sheetActive` 记下来，保存时只用这个数；
  「删除此版本」同样用 `sheetActive`。**任何一处都不再从列表快照里取版号。**
- 加一行第一现场日志：`面板 id=… 列表=c/a 现读=c/a view=…`。
  两个数不一样，就说明滞后在列表那一层（`load_units` / 列表快照），而不是面板——
  这条日志正是以前一直缺的那个判据。

**没解决的部分（要真机看日志才能定）**：列表快照为什么会滞后。这行日志一出来，答案就二选一了。

### D137 实现记录（第五层：真机日志读出来的两件事，2026-10-26）

手里有真机日志（`nmoi-debug-20261026-1755`）之后，V8 剩下的那条终于能定性了。

**① "点两次复制才出 2/2" 不是滞后，是 count=1 按设计不显示。**

日志里两次 save 是干净的：`save ok version=1 count=1`（17:54:32）、`save ok version=2 count=2`（17:54:42）。
而界面有一条规矩：**`versionCount <= 1` 时不渲染 `n/n` 控件**（只有一版没什么可翻的）。
所以第一次点完，库里确实变成 1 版，屏幕上什么都不变——用户看到的就是"没反应"，再点一次才到 2 版。
换句话说：**这条是探针的语义没对齐，不是缓存没刷新。**

改法（只动临时探针，不动 storage）：`count == 0` 时一次点落**两条**——
先把当前显示的正文落成第 1 版，再把复制品落成第 2 版。一次点击就该看到 `2/2`。

**② 列表快照的版本字段恒为 0，而面板现读是对的——还没定位，但判据已经装好了。**

同一时刻的两份数据：`面板 … 列表=0/0 现读=2/2`。而 `load_units` 这条路在 Rust 单测里是对的：
临时探针（`crates/storage/tests/` 里那份，测完已删）走完整真机序列——
建会话 → append → save ×2 → **重新开一份 Storage** → `version_index` 查到键、`load_units` 的
`message.id` 也能取到 `brief=(2,2)`。所以库和 FFI 的序列化都没问题，差异在 Android 这一侧。

已加统计日志：`读到 N/N 个单元（带版本 M 条）`。M=0 就说明 `load_units` 的 JSON 没把版本字段带到 Kotlin，
下一份日志就能收口。

**③ 记一条隐患（给 `turn_run`，现在不改语义）**

`save_generated_version(id, text)` 在 `version_count == 0` 时把**入参**当第 1 版，并覆盖 `messages.content`。
如果将来 `turn_run` 传的是"重新生成出来的正文"，那**原文就没了**（没留成 v1）。
现在采用的做法是**调用方先 seed**（探针就是这么做的）；等接 `turn_run` 时要么沿用这个约定，
要么把它改成"v1 = 原 content、v2 = 入参"。这一条写在这里，免得接的时候想不起来。

### D146 真机结果（2026-10-26）

**V7 通过**（用户真机逐层验过）：浮层 → 选择模式 → 页面栈 → 抽屉 → 退出，每次只退一层，根页面按返回才退应用。
V8 的第 1、3 条也过（编辑落在正确的版号、面板与现读一致）；第 2 条见 D137 第五层。

## D148 颜色只管"被 AI 改过"：用户编辑走独立一层（schema v6）

> **2026-10-26 补记**：**存储形态作废**——`user_text` / `user_edit_of` 这一层一版只存得下一份，
> 改过一组再改另一组会互相顶掉，v7 已删、改成就地改正文（D152/D154）。
> 本条**结论仍有效**：颜色只看 `edited_by_tool`，用户改字不改颜色。

**用户 2026-10-26 指出的语义错误**：现在点「编辑」会让那一版变红/蓝。**不对。**
红/蓝的含义是"这一版被工具（AI）改过"；用户自己改字不该改变这个状态，
否则"我改了个错别字"和"模型重写过"看起来一模一样，三色就失去意义了。

**要能分别改的三种内容**：① 没被 AI 改过的原文 ② 被 AI 改过的原文 ③ AI 改后的那一份。
用户对哪一份不满意都能改，改了都不影响颜色。

**做法（schema v6）**

| 列 | 谁写 | 作用 |
|---|---|---|
| `raw_text` | 生成时 | 原文 |
| `edited_text` + `edited_by_tool` | **工具/AI** | 改后的正文；`edited_by_tool` 决定蓝/红 |
| `user_text` + `user_edit_of` | **用户** | 用户改的那一份，以及"改的是 raw 还是 edited" |

- 显示规则 `shown_text`：**用户改过且改的是这一组** → 用 `user_text`；否则按视图取
  `edited_text` / `raw_text`。所以"改了原文"不会串到"AI 改后"那一组上去。
- `messages.content` 仍然是"当前显示的那一份"，用户改到正在显示的那一版时同步更新。
- 用户改过另给一个标记 `user_edited`（`load_units` 的 `version_user_edited`），**不参与三色**。
- FFI：`edit_version` 的语义改成**用户编辑**（只写用户层）；新增 `tool_edit_version` 给工具用。

**临时探针**：`nmoi_storage_tool_edit_version_json` + 界面「更多 → 用工具改这一版（临时探针）」，
用来在真机上把红/蓝造出来。真正的工具编辑要等 `core::edit::apply_edit` 桥出 FFI，**接上就删**。

**测试**：`user_edit_does_not_mark_tool_edited`（改内容不变色、留用户标记）、
`user_edit_and_tool_edit_live_in_different_layers`（改 raw 不影响 edited，反之亦然）、
迁移测试补 v6 的两列默认为 NULL。

## D149 压缩后的窗口：摘要不进 `messages` 表，由窗口显式带进请求

**问题**（接 `turn_run` 前必须补的一段）：分界线（D12）早就把"线上不进上下文"写进了表结构，
但**读历史那条路没看它**——`load_units` 一律整条会话全给。于是"压过了"这件事在请求里没有效果：
压掉的原文照样发出去，摘要反而没人用。`summary_injection()` 到现在只有 core 的内部测试在调。

**窗口的形状**

- 窗口 = **最新一条分界线以下**的单元（按 `seq > divider.after_seq` 取，顺序不变）。
- 摘要只对 `kind = compression` 那条线有意义：正文从线的 `note` 首段拿 `ArchiveRef`
  （D143 定的格式就是 `"{summary_ref} | 压缩 N 单元 …"`），再去 `archive` 表读内容。
  `kind = user` 的手动线一样被尊重——线下才是窗口，只是没有摘要可注入。
- 没有线 = 整条历史都是窗口，行为与改动前一致（默认实现走这条，现有测试不受影响）。

**摘要为什么不落 `messages` 表**

它是"给模型看的那一份"，不是用户写过的一句话。落成消息会连带三件事：
① 混进消息区（用户没说过这句，却能在界面上看见一条）；② 混进版本与三色语义（D135/D148 没有它的位置）；
③ 得给它一个 `seq`——那个号被窗口以下的所有消息占着，塞进去要么撞号，要么把所有下游消息重编号，
重编号会连带碰到 `message_versions` 与归档里的 `origin_message`。

代价是：每个读窗口的人都要**显式**接上摘要，漏一个就少发一段。所以只留一种组合方式
（`ContextWindow::summary_message()`），不给第二个入口。

**二次压缩必须带上一次的摘要**

压缩的输入用 `ContextWindow::units_with_summary()`：把上一次的摘要装成第一段原文一起压。
不这样的话，第二次压缩看到的"线上"只有那些还在窗口里的单元，
上一次压出来的摘要就**整段丢掉**——那正是"内容看起来丢了"的来源。

**计量口径**

摘要按 `SourceTag::InstructionInjection` 走（`budget::breakdown` 把它归到"系统常驻"那一段）。
它是每轮都要发出去的固定成本，不是历史；触发线（D144）把它算进 `used` 里，不然会低估。

**这次没做的**：`Budget::reserve_fixed` 仍然没人填数。它要的是"世界书命中上限"，
而世界书注入（P5）还没进请求构造那条路——不是不知道填什么，是那条路还不存在。等它接上再填。

## D150 "现在显示的那一组"是唯一真相：预填、落点、删除都问它一次

**用户 2026-10-26 真机报三条**（原话在上）：

1. 只有一版、被工具改过时，`1/1` 不出现；
2. 屏幕上显示的是 AI 改后那份时，编辑框预填的是**原文**，存完正文却换成了他打的那份；
3. 「删除所有版本」没能正常删掉。

**① 门槛错了，不是数据错了。** `VersionControl` 用 `versionCount <= 1` 一刀切，于是
"一版但被 AI 改过"这种**有东西可切**的状态被藏掉了（蓝/红两态点击能切原文↔改后）。
改成 `count > 1 || 这一版被 AI 改过`。顺带兜住列表字段滞后的老问题：
`total = max(versionCount, active)`，否则会显示成 `1/0`。

**② 预填和落点不同源。** 落点由 storage 按 `active_view` 推断（对），预填却用列表快照的
`content`（滞后/过期就不对）。两处一旦错开，看到的就是"编辑框里是原文、保存却落到改后那一组"。
修法：`list_message_versions` 多返回一个 **`shown`**——当前版、当前视图该显示的正文，
用与 `switch_message_version` **同一个函数**（`shown_text`）算。FFI 的 `versions` 信封带出去，
界面预填只用它；"内容有没有变"也比它。Kotlin 侧**不再自己按 raw/edited 拼**（那是第二套显示规则）。

**③ 删除把用户改的那份丢了。** `delete_message_version` 自己拼了一遍"显示哪一份"：
只看 `edited_text`，`user_text` 整个不在场。于是删完版本，正文跳回 AI 那一份。
修法：同样走 `shown_text`。测试里第一次就红了（期望他打的那份，实际 `第二版的改后`）。

**测试**：`crates/storage/tests/version_display.rs` 四条——单版带 AI 标记的概要、
切到改后时 `shown` 是改后、显示改后时编辑落到改后（原文不被污染）、删所有版本后正文不变。

## D151 版本化是隐式的；「删除所有版本」是**清空**，不是"留一版"

> **2026-10-26 补记**：前半（没有版本行时先落第 1 版）仍有效；
> 后半的"清空版本、正文保留"已被 D152/D153 取代——现在是**删掉这条消息**（正文一起没）。

**用户 2026-10-26 第二次真机报**（两张截图）：`工具编辑失败：storage: 数据不一致: 消息
01a17945-…-e2 没有第 1 版`；点「删除所有版本」得到
`只剩一版：删了就等于是删消息，而那组动词还没有（D130）`，而且"还是会保留当前版本，不对"。

**① 没有版本行是常态，不是异常。** 只有「复制为新版本（临时探针）」会调
`save_generated_version`，正常发出来的消息 `version_count = 0`。而「编辑」「工具编辑」对
**任何**消息都可点——于是点下去就是"没有第 1 版"。修法：写入路径自己补第 1 版
（`seed_first_version`，`raw_text` 取 `messages.content`，也就是**当前显示的那一份**），
`mark_version_edited` 与 `user_edit_version` 都先过它。调用方不用记得 seed，
原来那条"调用方先 seed"的约定（§6.5 的隐患）也就不需要了。

**② `delete_message_version(0)` 改成清空整条消息的版本记录。**
D137 原设计是"删其余、留当前显示版"，用户明确说不对：他要的是把"版本"这件事**撤销掉**，
不是把两版合成一版。现在：

| 动作 | 行为 |
|---|---|
| `version_no > 0` | 删这一版；只剩一版时拒绝（删到零版等于删消息，D130） |
| `version_no == 0` | **删掉这条消息的全部版本行**，`version_count = version_active = 0`，`content` 一个字节不动 |

清空之后 `n/n` 与三色一起消失；代价（明确接受）：**再也不能切回原文/AI 改后那份**，
AI 改过的痕迹只剩正文本身。用户要的就是这个。

**③ 界面：单版也给「删除所有版本」。** 文案在单版时写成「删除所有版本（正文保留）」——
`SheetItem.hint` 是行内灰字状态，**面板根本不画它**，把解释写在 hint 里等于没写。

**测试**：`version_display.rs` 新增 `editing_a_message_that_never_had_versions_seeds_the_first_one`
（工具编辑 + 用户编辑各走一次，断言版本数仍是 1、`shown` 跟着改）；
`versions.rs` 的 `delete_all_versions_keeps_the_displayed_one` 改名并改成清空语义。

## D152 用户编辑**就地改正文**；「删除所有版本」= 删掉这条消息（schema v7）

**用户 2026-10-26 第三次反馈**（两条，原话）：

> 我发现编辑红字消息会导致蓝字消息变成最初的版本，编辑蓝字消息会导致红字消息变成最初的版本。
> 我是想要可以直接强制修改它们存储的消息，而不是一个什么覆盖的（……）我想要的是那种直接修改它们数据的。
>
> 而且我要的是能彻底删掉所有版本消息且不保留该消息任何正文的啊。

**① 覆盖层没了。** v6 的 `user_text + user_edit_of` 一版只有**一份**：改过原文再改 AI 那份，
前一份就被顶掉——表现正是"编辑红字，蓝字回到最初的版本"。这不是显示问题，是那套模型只能存一份。

现在：**改哪一组就写哪一组**（`raw_text` / `edited_text`），两组各改各的列，互不覆盖。
`shown_text(raw, edited, view)` 只剩三个参数，覆盖层整个删掉。

- schema **v7**：先把 `user_edit_of` 指到的那一组正文回填进 `raw_text` / `edited_text`，
  再 `DROP COLUMN user_text` / `user_edit_of`（用户之前改过的内容一行都不丢）。
- **D148 的存储形态作废，结论保留**：颜色只看 `edited_by_tool`；用户改字不改颜色。
  `user_edited` 这个标记一并删了——"用户改过"和"原文本来就是用户写的"已经不可区分，留着只会误导。
- 老约定仍然成立：还没记过版本的消息，第一次编辑先 `seed_first_version`（D151）。

**② 删除版本与删除消息分开。** `delete_message_version(0)` 这个暗号取消（传 ≤0 直接报错，
错误里写明该走 `delete_message`）。新增 `Storage::delete_message`：一条事务里删
`message_versions` → `edits` → `messages`，**正文、版本、审计一起没**。

- 只允许删**单元里只有它一条**的消息。工具往返是 `assistant(tool_calls) + 全部 tool_result`
  的整体，删单边会造出 `unpaired_tool_call`，那种要整单元删，属于删除流程的另一档——明确拒绝。
- FFI：`nmoi_storage_delete_message_json`；JNI：`Native.deleteMessageJson`；
  Kotlin：`ChatRepository.deleteMessage`。
- 界面：面板项改成「**删除这条消息（含所有版本）**」，任何消息都给（不限版本数），
  点了先出确认框（正文/版本数/首 80 字预览 + 红色「删除」）。破坏性动作必须确认。

**测试**：`editing_one_group_never_resets_the_other`（先改原文再改 AI 版、再改回来，两轮都不能丢）、
`deleting_a_message_removes_its_text_and_versions`、`zero_version_number_is_refused_now_that_messages_can_be_deleted`、
迁移用例改成断言 `user_text` / `user_edit_of` 两列不存在。

## D153 「复制为新版本」取**现在显示的那一份**；删除按**单元**删

**用户 2026-10-26 第四次反馈**（原话）：

> 修改后复制为新版本不是基于当前状态的（当前切换在红就复制红，当前切换在蓝就复制蓝，
> 为什么它给我复制的是原来的蓝，我明明已经改了？）你明白什么叫所见即所得吗？

> 还有整单元删也补上吧。

**① 复制的源文本现读。** 原来 `saveVersionProbe` 用的是列表快照里的 `content`——快照滞后一拍，
于是就"我明明改了，它复制的还是旧的"。现在先 `versions()` 现读，用 `shown`
（= 屏幕上显示的那一组，含他自己改过的字）当源；没有版本时才退回消息正文。
同一条口径也用在编辑预填（D150）上：**看得见的那份 = 唯一的源**。

**② 删除按单元粒度。** `delete_message` 改成删**整单元**并返回删掉几条：
工具往返是 `assistant(tool_calls) + 全部 tool_result`，只删半边会留下
`unpaired_tool_call`（L3 校验会报、模型也读不懂）。用户点的是"这条消息"，
它和它的工具结果本来就是一件事。界面据 `removed` 说话："已删除这条消息（连同它的 2 条工具往返）"，
确认框也把"工具往返一起删"写出来。

代价（明确）：点工具卡片删除会把它的结果一并删掉——这是单元语义，不是误伤。

**测试**：`deleting_a_tool_message_takes_the_whole_unit`（2 条一起走；删完重开库过语义校验）。

## D154 一版只有两种正文：`origin_message` / `tooledit_message`（schema v8）

**用户 2026-10-26 第五次反馈**（原话）：

> 不要给我搞那种有不可见 raw 的情况，红蓝这种状态的情况下，蓝就是一个 origin_message，
> 红就是 tooledit_message，这样区分来就行了，只存这两种，有 tooledit 消息的时候就会显示红蓝状态，
> 只有 origin 的时候就是正常的不显示红蓝的。不然要是有那种看不见但是存了的消息数据，这不成屎山了？

**改名，不动数据**（v8）：`raw_text` → `origin_message`、`edited_text` → `tooledit_message`。
`RENAME COLUMN` 一个字节都不搬，`CHECK` 表达式里的引用由 SQLite 自己跟着改。

**规则钉死**：

| 库里有什么 | 界面 | 行为 |
|---|---|---|
| 只有 `origin_message` | **不显示红蓝** | 正常正文；点不到"切改后" |
| 有 `tooledit_message` | 显示 `n/n`，蓝=看原文 / 红=看改后 | 点数字在两组之间切 |

切到 `edited` 而这一版没有 `tooledit_message` 时**降级成原文**、不改视图状态（
`a_version_without_tooledit_has_no_red_blue_state` 盯着）。

**没有第三种正文**。v6 那套 `user_text` 覆盖层已经在 v7 删掉了；`edited_by_tool` 只剩一个作用——
它是"这一版有没有 `tooledit_message`"的快照标记（`CHECK` 保证两者不可能不一致），
`version_index` 一次 `LEFT JOIN` 就能取到，不用为每行多判一次 NULL。
如果哪天觉得连这个布尔都算冗余，去掉它必须**重建表**（`CHECK` 引用了它，`DROP COLUMN` 不让），
那时再单独一个版本做，不要顺手改。

**`messages.content` 是什么**：它是"**当前显示的那一份**"的镜像，读路径（`load_units`）和发送都读它，
不是第二份隐藏数据——每次切版本 / 编辑 / 删除版本都同步写它。想彻底去掉它要改读路径，
那是另一件事，先不动。

## D155 UI 状态里不放数据对象，只放 id

**上一次真机留下的那条诊断**（§6.1）：日志里 `面板 … 列表=0/0 现读=2/2`，
以及更早的"点两次才出 `2/2`""看着 2/2、编辑落到 1/2"。三件事根子在一处。

**根因**：`moreFor` 与 `editing` 存的是 `ChatMessage` **对象**——点开那一刻的副本。
面板的显示用现读的库，动作却用这个副本，于是两边的版本号/正文可以不同步；
日志里"列表"那个数也是副本的，所以它看起来像"列表恒为 0"，其实只是**那份副本旧了**。

**修法**：两个状态都改成只记 id（`moreForId` / `editingId`），渲染和动作时从
`allMessages`（当前快照）现取。找不到就 `LaunchedEffect` 里把浮层收掉——
否则按返回会先"退"一个看不见的层，用户以为返回没反应。
日志也改成**同一刻两个来源**的对照：`行内=c/a view=… | 现读=c/a view=…`。

**为什么这条比修 case 值钱**：UI 状态里放对象，等于给自己留了一份看不见的副本——
正好撞在 D152–D154 那条线上（不许有不可见的数据；动作的源必须是屏幕上此刻显示的那一份）。
以后凡是"点开某个东西"的状态，**存 id，用时现取**。

**验证**：Kotlin 侧没有本地编译（CI 的 android job 编），行为要真机看那行日志两边是否相等。

## D156 模型服务（设置 → 模型与提示词 → 模型服务）

增强编辑、`turn_run` 那条路都要"发一次请求"，而界面上一直没有填端点/密钥的地方（D59 把系统提示词留给调用方，
这一步也就一直空着）。这一条把它补上，形状按 `notes/secrets.md` 的口径分两处存：

| 存什么 | 在哪 | 跟着库走吗 |
|---|---|---|
| 端点 / 模型名 / 供应商类型 | `settings(provider, "default")` | 跟着（导出、备份带走） |
| API Key | `vault/provider.bin`（AES-GCM 密文，主密钥在 AndroidKeyStore） | **不跟**（删文件等于吊销） |

- Rust 侧只加了 `Scope::Provider`（通用 JSON 文档，字段由 Kotlin 定义）。
- 密钥**从不回显**：界面只显示 `sk-****3f9a` 这样的打码；输入框永远是"重填"，留空就是不换。
  没有"显示密钥"的眼睛按钮——`secrets.md` 的立场是凭据只进不出。
- 解不开（换机 / 清数据 / Keystore 被重置）时界面写"要重填一次"，不装作没配过。
- 没有「测试连接」按钮：真发请求那条路还没接到 UI 上，现在给一个按钮就是假按钮（D133 的规矩）。
- `PendingRow` 里「供应商与密钥」那行删掉——已经接了，别跟真入口并排摆着。

**这一条只到"能填、能存、能读"**。发请求、增强编辑、插入工具块/思考块都还没接。

## D157 供应商多套 + 模型表；`turn_run` 接到 UI

D156 只做了"一套端点 + 一把钥匙"。发请求那条路要用哪一套、用哪个模型，一直没地方答。
这一条把它补成能用的形状，并把 `nmoi_turn_run_json` 接进聊天页。

**数据**（`settings(provider, <id>)`，一个供应商一个 key）：

```json
{
  "name": "本机中转",
  "kind": "openai_compatible",
  "base_url": "https://example.com/v1",
  "models": [{ "id": "vendor/model-a", "max_tokens": 8192, "temperature": 0.7 }],
  "active_model": "vendor/model-a"
}
```

- 当前用哪一套：`settings(provider, "active")` = `{"id": "<id>"}`。
  **不设 `enabled` 字段**——`active` 是"用哪一套"的唯一答案，多一个开关就会有两个答案。
- 密钥按条目分文件：`vault/provider-<id>.bin`；`id = default` 沿用 D156 的
  `vault/provider.bin`，**升级上来不用重填密钥**。
- 老形状（只有 `base_url` / `model` / `kind`）在读取时**就地解释**成"模型表里只有一条"，
  不做一次性迁移：迁移要写库，写库就可能把没读懂的字段洗掉。
- 没有 `active` 标记时兜底：`default` → 只有一个供应商时就是它；**多套又没选过就报错**，
  不替用户挑（挑一个等于替他决定花谁的钱）。

**模型表只存真会被用到的三样**：名字、`max_tokens`、`temperature`。
上下文窗口 / 定价 / 输入输出模态那些看着更"完整"的字段这一版没有——
`crates/core` 里没有任何一处读它们，存下来就是"存了但不生效"，正是用户最反感的那种
（撞 D154 那条：不许有"存了但看不见/没用上"的东西）。

**界面**（`ui/ProviderScreens.kt`，形状参考别的客户端那套"列表 → 详情"）：列表页
（搜索框 + 首字圆标 + 名字 + 类型/模型数 + 「当前」标记）→ 编辑页（页签「配置 / 模型」，
配置 = 名称 / 类型 / Base URL / API Key（打码、显隐只在输入时生效）/ 保存 / 设为当前；
模型 = 一行一个，点行设为当前、铅笔改、＋添加）。
不做这些按钮：是否启用开关、多 Key、Response API 开关、账户余额、网络代理、
自定义 Header/Body、API 路径、上下文窗口/定价——**它们在本项目里一条代码都没有读**。

**发请求**（`ui/TurnRepository.kt` + `NmoiFfi.turnRun` + JNI `turnRunJson`）：
按顺序解析——现读"当前供应商"（不是界面快照）→ `Vault` 现解一次钥匙（解不开也照发，
本地端点常常不要钥匙，真 401 时错误里会带状态码）→ `turn_run`。
`run_session_turn` 第一步就把用户那句话落库，所以**失败时它可能已经在库里**：
失败后现读一次库（`wasUserTextSaved`）判断草稿要不要清，判不出来就保守当成没存。

**取消**：`turn_cancel` 接到同一个按钮（生成中 ↑ 变方块）。它只置标志，
真正的停点在回合边界（D58 那条代价，界面文案照实说"停在这一次请求回来之后"）。

**为什么把 `turn_run` 排在多套供应商之后**：发送要用"哪一套 + 哪个模型"这个答案；
先有多套再发，才不会出现"发了才发现改不了用哪套"。

## D158 块级编辑与一次性补全（基建；界面在下一层）

增强编辑要落到"改哪一块"上，而一套消息里的三样东西存在**不同的列**里：
`content`（正文）/ `reasoning`（思考）/ `tool_calls[].arguments`（工具参数）。
以前只有"整段正文再存一版"，块级编辑的接口在 `core::edit` 里躺着，没人调用。

- storage：`Storage::apply_block_edit(message, BlockSelector, content)`——
  查出这条消息所在的单元 → `core::edit::apply_edit` → `save_message_edit` 写回那一行
  并往 `edits` 审计表记一笔（同一个事务）。五种拒绝都带原因：单元不匹配、消息不在单元里、
  **被用户锁定**、没有第 N 个工具调用、**内容没变化**。
- **工具调用只允许改参数**：名字与 `call_id` 不在射程里。名字决定下一轮怎么解释这一块，
  `call_id` 决定它跟哪条结果配对；让编辑改这两样，等于把一条历史换成另一条。
- FFI：`nmoi_storage_apply_block_edit_json(handle, message, block, content)`，
  `block` 只认 `output` / `thinking` / `tool:<0–63>`，单块上限 200 KB；
  `nmoi_complete_json(config)` = **一次性补全**，不落库、不进历史（`ChatRequest::from_turns`
  + `without_stream`）。用 `turn_run` 做这件事会在历史里留下"用户让我改"和"模型回了新正文"
  两条假对话，下一次压缩会把它们当真。
- `load_units` 补两个字段：`reasoning`（以前只存不带出去，界面上永远看不见）与
  `tool_call_args`（名字 + 参数原文）。**存了但看不见**正是铁律 ② 要挡的。
- 测试：`crates/storage/tests/block_edit.rs` 5 条（思考不动正文、工具参数不动名字与 id、
  锁定拒绝且不记账、未知消息/越界索引报错、无变化拒绝）。
- 界面（Kotlin 的 EditRepository + 「更多 → 增强编辑」）**还没接**，这一条只到"底座能用"。

## D159 命令工具的命名、注入门槛与容器口径（已定形状，未动工）

**两把工具，名字分开**（用户 2026-10-27 定）：

| 名字 | 是什么 | 平台 |
|---|---|---|
| `System-Shell` | **平台原生**命令（App 自己的身份跑） | 三端都有 |
| `WorkSpace-EnvirShell` | **隔离环境**里的命令（proot 根文件系统） | Android / Linux |

- 连字符在四家的函数名规则里都合法（OpenAI `^[a-zA-Z0-9_-]+$`、Anthropic 同、Gemini 允许 `.-`），
  所以名字照用户给的写，不做 snake_case 改写——改写会让界面上和日志里的名字对不上。
- Windows **只有** `System-Shell`（容器那套不往 Windows 上搬）。它的描述必须先写
  "这是本机原生命令，谨慎操作"。
- 两把工具的描述都写一句：**没有用户明确允许时不要调用**。
- 结果形状（界面那两页 stdout/stderr 的输入）：
  `{"exit_code":0,"stdout":"…","stderr":"…","truncated":false,"duration_ms":123}`；
  stdout / stderr 各自封顶 64 KB，截断要标出来；超时杀**进程组**（`setsid` + `kill -PGID`），
  不是杀一个 pid——否则 `sleep 1000 &` 会漏。
- 开关按工具、按平台摆：Android/Linux 两个（原生、隔离环境），Windows 只摆原生。

**世界书预设：只有对应工具开着才注入**

- 每条预设带 `applies_to`（工具名 / 能力位）。注入在 `TurnPlan::freeze` 里解析，
  **按 `plan_tools` 算出来的声明表过滤**——工具没开，它那几条提示词一起消失。
  不在请求构造时临时判断：一轮跑到一半改预设就变声明，缓存全废、模型看到两套指令。
- 这也解释了为什么"冻结"和"注入"必须在一处：门槛的输入（声明表）本身就是冻结的产物。

**`base-version` 的口径**（用户 2026-10-27 提出，我加了五条）

- 官方原文放 `assets/presets/`，词条只记 `base_id` / `base_version`（整数）/ `base_hash`
  和用户改后的正文。版本号会说谎（改了没递增），哈希不会。
- 四种状态：跟随官方 / 已改写 / 官方有新版 / **两边都变（冲突）**。冲突给三个选择，
  **绝不自动覆盖**。
- `base_version` 在应用内**双层不可改**：编辑器置灰 + 保存路径忽略它；导入时**重算**
  `base_hash`，不一致标"base 被改写"。
- 基线条目不可删，只能禁用。
- 预设只准改**描述正文**：工具名与参数名是 wire 契约，预设里出现未知工具名当场报错。
- 注入了哪几条、版本多少，进**请求日志**（用户要的位置），不另做面板。

**Shizuku：只在本地，不进导出**

- `System-Shell` 有一个"经由 Shizuku 执行"的开关。打开时**必须真的拿到 Shizuku 授权**，
  否则开关打不开（给一句具体原因：没装 / 没授权 / 版本太老）。
- 这个选择**只存本地**（SharedPreferences），**不进库、不进导出/导入**。
  理由：授权是"这台设备上的这个安装"的状态；导入一个 `true` 只会得到
  "界面看着开着、实际跑不了"，比没有更糟。
- 启动时重新校验一次授权，被撤销就把开关打回关，并在开关那一行写明原因。

**工作区与容器**

- 工作区根：`/data/user/<uid>/com.nmoi.agent/files/workspace`（**不是** `/sdcard`），
  走 `nmoi_workspace_open_json` 绑定，零权限。
- 容器：proot + Ubuntu 最新发行 rootfs，**首次使用时下载**。下载源两项：
  「Ubuntu」（官方地址）与「自定义」（自定义时输入框才亮）。

## D160 工作区落地 + `System-Shell` + 工具结果卡（D159 的第一段）

**工作区**：`filesDir/workspace`（真实路径 `/data/user/<uid>/com.nmoi.agent/files/workspace`），
不进 `/sdcard`。Kotlin 先 `mkdirs()` 再 `nmoi_workspace_open_json` 绑；每轮开、轮末关
（`try/finally`）。绑不上不中断这一轮——文件工具与命令不可用，聊天照旧。
六件文件工具从这一版起在真机上**真的能跑了**（以前 FFI 那条路从来没传过 workspace 句柄）。

**`System-Shell`**（`crates/core/src/shell.rs`）：

- 只有 `Capability::NativeShell` 门控放行才可能被调到；没有工作区绑定连门都过不去
  （`check` 回 `no_workspace`）。
- 平台壳：Unix 是 `/bin/sh -c`（Android 上优先 `/system/bin/sh`），Windows 是
  `powershell -NoProfile -NonInteractive -Command`。
- 起进程 `process_group(0)`，超时 `kill(-pgid, SIGKILL)`（**不是**只杀子进程，
  否则 `sleep 1000 &` 会留在后台）；Windows 用 `taskkill /T /F`。
- stdout / stderr 各开一个读线程（单读一个会在管道写满时互锁），各封顶 64 KiB，
  超了继续读但丢弃并在结果里标 `truncated`。
- 结果 JSON：`command` / `exit_code`（被信号杀是 `null`）/ `stdout` / `stderr` /
  `truncated` / `timed_out` / `duration_ms`。
- 默认超时 30 s，上限 300 s；`cwd` 只能用工作区内的相对路径。
- 6 条测试：门控先于一切、没工作区拒绝、两个流分开、cwd 是工作区根、超时被杀（退出码 null）、cwd 越界被拒。

**开关**：`native_shell` 由 FFI 的 turn 配置带进来（默认 `false`），
Kotlin 侧存 SharedPreferences（`nmoi.local`），**不进库、不进导出**（见 D159 的理由）。
界面：设置 → 工具与工作区 → `System-Shell` 开关 + 一次确认卡；
`WorkSpace-EnvirShell` 这一版是**灰的说明行**（运行时没打包，拨了也不生效的开关比没有更糟）。

**结果卡**：工具往返展开后按 `call_id` 配对，一条调用 = 一块：
「命令」+ 等宽框 + 复制，「输出」+ `stdout`/`stderr` 页签 + 复制，
右上角写退出码 / 超时 / 截断。失败且 stderr 非空、stdout 为空时**默认停在 stderr**。
配不上结果的调用单独标「没有结果（这一批里它没跑完）」。

## D161 发请求这条路：HTTPS + INTERNET（真机第一轮日志的直接结论）

2026-10-27 真机第一次发请求，三连失败，根因只有两个：

1. `连接失败 127.0.0.1:<端口>: Operation not permitted (os error 1)` —— **清单里没有
   `android.permission.INTERNET`**。以前从没发生过网络请求，这条权限一直没加；
   Android 上缺它时 `connect()` 回的就是 EPERM，看起来像"被防火墙拦了"。
2. `传输层不支持: 明文传输只支持 http://，收到 https://` —— core 里只有
   `PlainHttpTransport`，**整个项目没有 TLS**。任何正规供应商都是 https，等于一条都发不出去。

**做法**：新增 `HttpsTransport`（`crates/core/src/transport.rs`，feature `tls`，默认开）。
`http://` 原样交给 `PlainHttpTransport`，`https://` 用 rustls + 内置根证书（webpki-roots）。
为此把 `read_response` / `read_line` / `read_status_line` / `read_headers` / `boxed_body`
从"只吃 `BufReader<TcpStream>`"泛化成 `Read + Send`——两条路共用同一份头部构造与响应解析，
不复制一份解析器（复制就会有两份行为，迟早分叉）。

D38 原本写"TLS 由平台侧传输实现"。那样要 Rust 往 JVM 回调（`AttachCurrentThread` + 逐块搬 body），
出错面比 rustls 大得多，而且 Linux/Windows 还要各写一份。改成"Rust 侧一份跨平台实现"，
三端行为一致——这是对 D38 的一次修订，理由记在这里。

**依赖与 MSRV**：rustls 0.23 会拖进 `zeroize 1.9`，那是 edition2024 的包，1.75 的 Cargo
连 manifest 都读不进来。所以 `zeroize` 钉在 `=1.8.1`。除 TLS 外还加了 `libc`（杀进程组用）。
两者都写进了 `THIRD-PARTY-NOTICES.md`（`webpki-roots` 是 MPL-2.0，与 GPL-3.0 兼容）。

**下一层（未动工，形状已定）**：供应商页只当**目录**——有什么供应商、有什么模型、参数怎么设；
"用哪一套"归**助手**（`settings(assistant, <id>)`：`provider_id` + `model_id` + `temperature` +
`max_tokens`），聊天页顶部显示当前助手的模型并可切换。`provider.active_model` 与
`settings(provider,"active")` 都要退场，`turn_run` 改成"从当前助手的配置取"。

### D161 后半段（已落地）：模型归属搬到助手

- `settings(assistant, "default")` = `{name, provider_id, model_id, temperature?, max_tokens?}`。
  这一版只有**一个**助手（多助手要先给"会话属于哪个助手"建模），但归属现在就定死：
  「用哪一套」在助手，不在供应商。
- 供应商页退成**目录**：不再有「当前 / 设为当前」，列表只剩名字 + 类型 + 模型数；
  模型表里的标记改叫**默认模型**（新建助手时拿它当初始值）。
  `settings(provider,"active")` 只剩两处用途：读老数据时兜底、`ProviderRepository::active` 的兼容路径。
- 聊天页顶部加一条**模型条**（`provider / model`），点开是自绘选择面板，
  选完直接写助手配置——下次发送就用它（`TurnRepository` 每次现读助手，不缓存快照）。
- 参数优先级：助手上的 `temperature` / `max_tokens` 覆盖模型上的；都没填就不传。
- 一条兜底：助手配置读不到（老库）时，从供应商目录推导一份**但不写库**——
  读不该有副作用；用户第一次点选择才落盘。

## D162 思考强度与思考块

- **思考强度**（`FFI turn.thinking` → `ThinkingSetting.effort`）：`auto`（**一个字段都不注**）/
  `off` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`。字段族交给
  `default_thinking_field(kind, flavor)`——不自己拼 JSON，拼错一个键模型就当没看见。
  存在**助手**上（`settings(assistant,"default").thinking`），聊天页那颗仪表盘图标进档位面板。
- **思考块显示**：`load_units` 从这一版起带 `reasoning`（D158 就带上了），
  聊天页在助手正文上方放一张折叠卡（默认收起，只显示字数）。
  以前 `reasoning` 只存不显示，是铁律 ② 直接踩线的一处——这一版补上。
- 模型不支持某一档时不静默丢弃：`TurnPlan` 那条路会记 `Dropped { reason }`；
  这条 FFI 路径没有冻结计划，所以只保证"发出去的是你要的那一档"，界面文案写"下一轮生效"。

## D163 增强编辑（界面接上块级编辑）

「更多 → 增强编辑」现在真的能用了：说一句"要改成什么样"，模型回一份带围栏的东西，
按围栏落到三处不同的地方。

```output      → 版本那条路（tooledit_message，红/蓝）—— 用户已经会用红蓝切，不另造一套
```thinking    → messages.reasoning（块级编辑）
```tool:N      → messages.tool_calls[N].arguments（**只准改参数**）
```

- **围栏外的字一律不落库**：模型爱加"好的，我改好了"，塞进正文就是脏数据。
- 一个围栏都没有 = 模型没按格式来 → **整段当正文**（总比什么都不改强）。
- 解析不出任何块 → 什么都不写，只把原因写在提示行（不留"改了一半"的消息）。
- 单块上限 200 KB，跟 FFI 那条对齐；`tool:N` 的 N 超范围或这一条没那么多工具 → 跳过并记一句。
- 正文走 `toolEditVersion`（会写 `edited_by_tool`，所以红蓝、`n/n` 都是现成的）；
  改的是**现读的 `shown`**，不是面板点开时的副本（铁律 ①）。
- 空思考块 = 不改（不是清空）：让"没提到"和"要清空"分开，避免模型一句漏写就把思考删了。

## D164 隔离环境（proot + Ubuntu）：版本、依赖、许可、安装口径

**rootfs**：Ubuntu Base **26.04.1 LTS (Resolute Raccoon)**，
`ubuntu-base-26.04.1-base-arm64.tar.gz`，**33 MB**（2026-08-26 构建）。
运行时下载，**不进 APK**。用户点「安装」才下，下载源两项：Ubuntu（上面这个地址）与自定义 URL。

**内核**：**没有**。proot 是 ptrace 实现的用户态 chroot，容器里 `uname -r` 看到的是
**手机的 Android 内核**。所以界面上必须提前写清：`systemd` / `docker` / `iptables` /
内核模块都用不了（要 PID 1、cgroups、netns），apt / 编译 / 脚本 / Python / Node 正常。
Android 10+ 还有 seccomp 过滤，这也是 termux 维护自己 fork 的原因。

**proot 二进制**（2026-10-27 实测）：`packages.termux.dev` 上的
`proot_5.1.107.101_aarch64.deb`（101 KB），解出来 256 KB，
`ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, interpreter /system/bin/linker64`。

- 依赖（`Depends:` 与 `readelf -d` 都确认了）：`libtalloc.so.2` + `libandroid-shmem.so`，
  另外 `RUNPATH=/data/data/com.termux/files/usr/lib`——**那个路径在我们的包里不存在**，
  所以运行时必须给 `LD_LIBRARY_PATH`（RUNPATH 的优先级低于它）。
- 即"只扔一个 proot 进去"跑不起来，至少三件东西要一起就位。

**许可**：proot 是 **GPL-2.0-or-later**（`COPYING` 是 v2 全文，源码头写
"either version 2 … or (at your option) any later version"）。GPL-3.0-only 的项目
可以按 v3 使用它——**兼容**。

**口径选择：运行时下载 `.deb`，不把二进制打进 APK。**

- 好处：我们不成为 proot 的再分发者，GPLv2 §3 的"提供对应源码"义务不落到本项目头上；
  同时 APK 不背三个 .so 的体积，也不用处理 RUNPATH。
- 代价：首次安装要联网（本来就要下 rootfs），并且要认 termux 的仓库可用性。
- 仍然要做的**署名**：应用内「开源许可」列 PRoot 5.1.107.101（来源 termux/proot，
  未修改，GPL-2.0-or-later），`THIRD-PARTY-NOTICES.md` 同一条。
  许可全文用仓库里已有的 `LICENSES/` 机制补一份 GPL-2.0。

**安装布局**（App 私有）：
`filesDir/envir/bin/{proot,libtalloc.so.2,libandroid-shmem.so}`、
`filesDir/envir/ubuntu/`（解包后的 rootfs）、`filesDir/envir/state.json`（版本、来源 URL、
安装时间、字节数——界面上要能指出来）。

**下一步**：安装页（源选择 / 进度 / 已装版本 / 删除）→ 下载 + 解包（`.tar.gz`，
`java.util.zip.GZIPInputStream` + 一个最小 tar 读取器，不引依赖）→
`WorkSpace-EnvirShell` 工具（`proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -c <cmd>`，
超时/输出上限跟 `System-Shell` 共用同一份实现）。

### D164 修正（实现时撞上的）：`.deb` 不能在运行时解

原计划"运行时下载 termux 的 `.deb` 再解开"。动手时撞到一件事：`.deb` 里是
`data.tar.xz`，**XZ 没有 JDK 自带实现**（`GZIPInputStream` 能解 gz，解不了 xz），
要用就得引 `org.tukaani:xz` 或者自己写 XZ 解码器——两条都不划算，而且这条路只是为了
拿三个文件。

**改成**：三件二进制（`proot`、`libtalloc.so.2`、`libandroid-shmem.so`）**打进 assets**，
安装时复制到 `filesDir/envir/bin/` 并 `chmod +x`；rootfs 仍然运行时下载（`tar.gz`，不需要 xz）。

代价与义务：我们从"取用者"变成"再分发者"，所以 GPLv2 §3 的义务要自己履行——
`LICENSES/GPL-2.0.txt` 进包、notice 里写明版本（proot 5.1.107.101）、上游仓库、
我们未修改，以及可获取源码的位置。这件事下一轮随二进制一起提交。

### D165 隔离环境的安装（运行时进 assets + rootfs 运行时下载）

D164 定了"三件二进制进 assets"，这一条定**装的时候具体做什么、坏了怎么收场、
界面上能看见什么**。代码在 `ui/EnvirRepository.kt`（数据）与 `ui/EnvirScreen.kt`（界面），
入口是「设置 → 工具与工作区 → 隔离环境」。

**布局**（全在 App 私有目录，proot 以本 App 的 uid 跑）：

```
filesDir/envir/bin/{proot, libtalloc.so.2, libandroid-shmem.so}   ← 从 assets 复制 + chmod +x
filesDir/envir/ubuntu/                                            ← 解包后的 rootfs
filesDir/envir/state.json                                         ← 版本 / 来源 / 校验和 / 解包计数
filesDir/envir/tmp/                                               ← PROOT_TMP_DIR
```

**流程**：复制运行时 → 下载到 `ubuntu-base.tar.gz.part`（边下边算 SHA-256）→ 校验 →
解包（`TarGz`，`allowAbsoluteLinks = true`）→ 写 `state.json` → 删掉归档。
**任何一步失败都把半成品删干净**：半截 rootfs 会被 `installed()` 判成"已装"，
然后每条命令都在一个缺文件的系统里跑，比"没装"难查十倍。

**`installed()` 同时看 state 和磁盘**：`state.json` 存在、`rootfs/bin/sh`（或 `usr/bin/env`）在、
`bin/proot` 在，三条都成立才算已装。少了任何一条就当没装——界面上的"已装"必须能跑。

**官方源钉死校验和**：`sha256 = 5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd`，
35,092,106 字节（2026-10-27 实测下载后算的）。自定义 URL **不校验**，界面上明说
"没做校验和核对"——解包器会拒越界链接，但没人保证那个 tar 里是什么。

**自检按钮**（这一版就给了，不等工具接通）：起一次 `proot --version`，再起一次
`proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -c 'head -2 /etc/os-release; uname -r'`，
把 stdout / stderr / 退出码 / 耗时原样摆在界面上。两个环境变量必须给：
`LD_LIBRARY_PATH=filesDir/envir/bin`（proot 的 RUNPATH 指向 termux 的路径，我们包里没有那个目录）、
`PROOT_TMP_DIR=filesDir/envir/tmp`。另外设 `PROOT_NO_SECCOMP=1`——SELinux 拦 ptrace 时的老办法，
设了不亏。

**删除 = 全清**（rootfs + 运行时 + state），红色实心确认，写清会没掉约 110 MB。
递归删除用 `Files.walkFileTree`（**不跟随符号链接**），不用 `File.deleteRecursively()`：
后者会跟着 `var/run -> /run` 这种绝对链接往外走。

**这一条没做的事**：`WorkSpace-EnvirShell` 工具还没接（§PLAN 0.14o），所以界面上仍然没有它的开关。
只有"装了什么"和"能不能跑"，没有"拨了不生效"的东西。

### D166 运行时改走 `jniLibs`：私有目录不许 exec（真机实测）

用户装完 D165 的安装页，装是装上了（`5407 文件 / 755 目录 / 链接 287+0`），自检报：

```
Cannot run program "/data/user/0/com.nmoi.agent/files/envir/bin/proot"
(in directory ".../files/workspace"): error=13, Permission denied
```

**原因不是权限位**：文件已 `chmod 700`（`setExecutable(true, true)` 返回 true），而是
**Android 10（targetSdk ≥ 29）的 W^X 政策**——App 私有目录对 App 自己可写，因此
在 SELinux 里被标成**不可执行**；`execve` 一律 EACCES。可写与可执行被系统强制互斥。

**唯一可执行的落点**是 APK 的 `lib/<abi>/`：安装器把它解到 `applicationInfo.nativeLibraryDir`，
那个目录只读、且 SELinux 允许 exec。安装器**只按 `.so` 后缀**挑文件、不检查内容，
所以三个二进制装成 `.so`：

| 上游 | 包内 | 运行时怎么被找到 |
|---|---|---|
| `proot` | `libnmoi_proot.so` | 按路径 exec（名字无所谓） |
| `libtalloc.so.2` | `libnmoi_talloc.so` | 它自带 SONAME `libtalloc.so.2` → `LD_PRELOAD=<绝对路径>` 先加载，`NEEDED` 凭 SONAME 命中 |
| `libandroid-shmem.so` | `libandroid-shmem.so`（原名） | **没有 SONAME**，只能按文件名找 → `LD_LIBRARY_PATH=nativeLibraryDir` |

落点：源码在 `android/app/src/main/jniLibs-prebuilt/arm64-v8a/`，由
`sourceSets.getByName("main").jniLibs.srcDir(...)` 挂进 jniLibs 源集——**不能放 `src/main/jniLibs/`**，
那个目录被 `.gitignore` 忽略（CI 的 `cargo ndk -o …/jniLibs` 往里写 `libnmoi_ffi.so`），
放进去等于提交一个空目录。

配套：`packaging { jniLibs { useLegacyPackaging = true } }`。**没有这一行，lib 不会被解到磁盘**，
`nativeLibraryDir` 里是空的，前面那套白搭。代价是那 13 MB 的 `libnmoi_ffi.so` 也会被解出来。

**同一轮撞到的第二个 Android 限制**：`Files.createLink` 在 app 私有目录也失败
（115/115 条硬链接全挂，界面上的 `链接 287+0` 就是这么来的）。所以 `TarGz` 的硬链接改成
**真硬链接 → 符号链接（相对路径）→ 记账跳过**，三级，**绝不复制内容**
（coreutils 那一百多条 10 MB applet，复制一份就是 1 GB）。state.json 里多记 `link_fallbacks`，
界面上写"115 条退化成符号链接，功能不受影响"——不写的话那个 `+0` 看起来像解坏了。

顺带：老版本留在 `filesDir/envir/bin/` 的那份运行时会被 `install()` 顺手删掉。

### D167 发送：先上屏、先清输入框，再谈请求

用户 2026-10-27 报："发送的时候是先把消息推到消息框啊，而不是在输入框进行"——
原来的实现是**等 `turn_run` 整轮返回**才把 `draft` 清空，所以那一两分钟里他打的字一直躺在输入框里，
看起来像没发出去。

**改成**：点发送那一刻就
1. 把这句话作为 `PendingSend(text, at)` 画进消息区（用户气泡，正常样式）；
2. `draft` 当场清空；
3. 消息区末尾挂一行"正在生成…"（呼吸小字，不落库）。

`PendingSend` **只活在这一轮的界面状态里**，不进库、不算"已存"。这一轮收尾后（成功或失败）
一律清掉它、从库里重读——**库里那份才是真相**。失败且 `wasUserTextSaved == false` 时，
把正文**放回输入框**并在提示里写明"已放回输入框"：宁可让他看到重复的一句，也不要静默丢字。

这条不违反铁律 ②：那条管的是"**库里**存了但界面上看不见"，这里是反过来——
**界面上先有、库里随后才有**，而且收尾后立刻以库里那份为准。

### D168 供应商：从端点读回模型清单

模型 ID 是"少一个字符 404、多一个后缀 400"的东西（`deepseek-flash` 这种），
让他手打就是让他反复猜。`GET {base_url}/models` 本来就不要钱，所以：

- **「测试连接」**（配置页，Base URL 下面）：只发一个 GET，回来写
  `通了：HTTP 200 · 12 个模型 · 380 ms`，失败写 `HTTP 401 …`。它同时验证地址、钥匙、网络三样。
- **「从端点拉取模型」**（模型页）：把清单并进模型表，**已有的一条都不动**
  （参数可能是手填的），只追加缺的；模型表为空时顺手把第一个设成默认模型。
- 密钥取**当前表单里的值**优先，没有再读磁盘上那把（刚填完还没保存的也能测）。
- 三家形状不同，都只有一层：OpenAI 系 `data[].id`、Anthropic `data[].id`（头用 `x-api-key`
  + `anthropic-version: 2023-06-01`）、Gemini `models[].name`（去掉 `models/` 前缀，钥匙走 `?key=`）。
- **日志只打 `host + path`**：Gemini 的钥匙在 query 里，一律抹掉（`ModelCatalog.safeUrl`）。
- 只读：拉取不改库，改配置仍然只由「保存」那一下完成。

### D169 proot 的 `loader` 是必需品（D164 的推断被真机推翻）

D164 当时判断"`libexec/proot/loader` 只有 `-q` 跑外来架构才用，不打包"，D166 照这个判断只带了
proot + 两个 .so。真机上 `proot --version` 通过（107 ms），但「进容器」报：

```
proot error: execve("/usr/bin/sh"): No such file or directory
proot info: possible causes:
  * the program is a script but its interpreter (eg. /bin/sh) was not found;
  * the program is an ELF but its interpreter (eg. ld-linux.so) was not found;
  ...
  * the loader was not found or doesn't work.
```

**原因**：容器里的 `/bin/sh` 是 ELF，`PT_INTERP` 写的是 `/lib/ld-linux-aarch64.so.1`——
**容器里的路径**。`execve` 之后内核按**宿主根**去找这个解释器，手机上当然没有，于是 ENOENT。
proot 的解法就是它自己那个**静态** loader（18,136 字节，无 `PT_INTERP`）：由它把「tracee 二进制 +
容器里的解释器」一起 mmap 起来再跳。少了这一步，任何动态链接的 guest 程序都起不来。

**改法**：`loader` 也进 `jniLibs-prebuilt/arm64-v8a/libnmoi_proot_loader.so`
（sha256 前 16 = `1da54355333cca3f`），运行时给 `PROOT_LOADER=<nativeLibraryDir>/libnmoi_proot_loader.so`。
proot 里编译死的默认路径是 `/data/data/com.termux/files/usr/libexec/proot/loader`（我们包里没有），
所以这个环境变量**必须给**，不是可选项。`loader32` 仍然不带：arm64 rootfs 里没有 32 位 ELF。

**教训**：`--version` 能跑只能证明"三件套能被链接器拉起来"，**证明不了容器能起进程**。
自检那两条命令要分开看：第一条过了、第二条没过，问题就在 guest 一侧（解释器/loader/rootfs 完整性）。

### D170 固定签名：一把钥匙，两边 secret，CI 注入

**为什么必须固定**：AGP 的 debug keystore 是**构建机自己生成**的，每个 CI runner 一把新钥匙。
于是两次构建的包签名不同，第二个包装不上去，只能卸载重装（会话库、设置全丢）。
用户 2026-10-27 提的正是这件事。

**做法**：
- 钥匙：RSA 4096，PKCS12，别名 `nmoi`，有效期到 2056-10-19；证书
  `SHA-256: 1A:29:73:…:0D:AA:A4`（公钥摘要，公开，写进仓库当闸）。
  文件在本机 `/workspace/keystore/`（仓库外、600），**主密钥要离线另存两份**。
- 注入：`build.gradle.kts` 读 `NMOI_KEYSTORE_PATH` / `NMOI_KEYSTORE_PASSWORD` /
  `NMOI_KEY_ALIAS` / `NMOI_KEY_PASSWORD`；**四样齐了才建 `signingConfigs.fixed`**，
  `debug` buildType 改用它；缺任何一样就退回默认 debug 签名（本机随手试用，不承诺可覆盖安装）。
- CI：`android` job 里 `解出签名材料`（secret → `$RUNNER_TEMP/nmoi-release.jks`，umask 077，
  不打印内容）→ 打包 → `验签`（`apksigner verify --print-certs`，指纹与仓库里那串比对，
  不一致直接红）。
- secret 名字四处一致（`notes/signing.md`）：`ANDROID_KEYSTORE_B64`、`ANDROID_KEYSTORE_PASSWORD`、
  `ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；GitHub 与 Codeberg 各一份。

**为什么签名跑在 debug 变体上**：现在唯一在发、也唯一被装的就是 `app-debug.apk`。
单独出 release 会得到两个包，而"装哪个"这件事每次都要重新解释一遍。等 1.0 再拆 release 渠道。

**代价（说清楚）**：换签名的第一个包**装不上去**，必须先卸载——库和设置全没。
所以这次升级是一次性的。之后每个包都能直接覆盖。

### D171 路径不拼、只读：`filesDir` 与 `nativeLibraryDir` 都从框架取

用户 2026-10-27 提醒："不能确定用户是 0 还是 999 还是别的，用 `/data/data` 替代 `/data/user/0` 更靠谱"。

**结论：两个都不写死，也不做"检测 + 回退"——因为我们根本不拼这个字符串。**
`context.filesDir` 本身就是"检测"的结果：单用户机上是 `/data/user/0/<pkg>/files`，
工作资料（user 10）上就是 `/data/user/10/<pkg>/files`，多用户/镜像机上是各自的值。
`/data/data/<pkg>` 是**指向 user 0 的兼容符号链接**，在非 0 用户下会指到错的地方，
所以它只适合写在文档/命令行示例里，不适合写进代码。

`nativeLibraryDir` 同理，而且它每次升级 APK 都会变（`/data/app/~~<hash>/<pkg>-<hash>/lib/arm64`），
凡是需要它的地方一律现取（`EnvirRepository.runtimeDir`），不缓存。

为了让"路径不对"这件事可查，`describePaths()` 把 `filesDir` / `nativeLibraryDir` / `envir`
三个真实值写进日志（安装一次、每次自检一次），出问题时不用猜这台机器上是什么。

### D172 `WorkSpace-EnvirShell` 接进工具面

容器能跑通（D171）之后的下一件：让模型在容器里执行命令。形状照 D159 定的来，一处没改。

- **core**：`crates/core/src/shell.rs` 里加 `EnvirSpec`（proot / loader / lib_dir / rootfs / tmp，
  五个**宿主机**绝对路径）与 `EnvirShellTool`。`System-Shell` 与它共用同一个 `execute()`——
  超时、杀进程组、双流并读、每流 64 KiB 封顶只有一份实现。两份实现迟早会在"容器里的超时"
  和"原生命令的超时"之间分叉，而那种分叉没人测得到。
- **命令行长这样**：`proot -0 -r <rootfs> -b /dev -b /proc -b /sys -b <工作区>:/root/workspace
  -w /root/workspace /bin/sh -c <命令>`。容器里看到的工作区固定是 `/root/workspace`，
  与宿主机 `filesDir/workspace` 是同一个目录。
- **环境变量**：`LD_LIBRARY_PATH`（库里那两条符号链接）、`PROOT_LOADER`、`PROOT_TMP_DIR`、
  `PROOT_NO_SECCOMP=1`、**`PATH`**（guest 那五条目录）、`HOME=/root`、`TERM`。
  `PATH` 是真机撞出来的：不给的话容器里的 `sh` 继承 Android 的 PATH，`head`/`uname`/`ls`
  全 "not found"、退出码 127。
- **门控**：`Capability::ContainerExec`，默认关。FFI 只在收到完整的 `envir` 五件套时
  `enable_container(true)`；开关关着或没装，core 连这个工具都不会声明（不是"声明了再报错"）。
- **`cwd`**：模型给的相对目录只做**词法**校验（不许绝对路径、不许 `..`）——它在容器里，
  宿主机上不存在，没法也不该 `canonicalize`。用法是往 `-w` 后面接。
- **界面**：设置 → 工具与工作区里 `WorkSpace-EnvirShell` 从"灰行"变成**真开关**（默认关，
  开之前一次确认）；环境没装时它仍是灰的（拨了不生效的开关比没有更糟）。
- **测试**：拿一个**假 proot**（把 `-c` 后面那段当真命令跑）验我们这一侧——命令拼得对、
  `PATH` 是 guest 的、超时会杀、没坐标会明说、门控挡得住。proot 本身是别人的活，真机验。

**没做的**：容器里的网络原本完全不通（`apt update` 需要 DNS 与 `/etc/resolv.conf`，
proot 默认不带）——这一条由 **D173** 补上；工作区挂载是只读写工作区，别的一概不绑。

### D173 容器允许联网（用户 2026-10-27 明确允许）

proot **不建 netns**：容器里的进程和宿主是同一套网络栈、同一个 uid 的权限。所以"能不能联网"
其实只剩一个问题——**DNS 有没有**。Ubuntu Base 里 `/etc/resolv.conf` 是空的/指向不存在的
systemd-resolved，于是 `apt update` 会卡在解析域名。

做法（0.14q，**已实现，待真机验证**）：把宿主当前的 DNS 列表写进 `filesDir/envir/resolv.conf`，
启动时 `-b <resolv>:/etc/resolv.conf`；`EnvirSpec` 加一个可选 `resolv`，没有就不绑——离线也照跑。
DNS 从 `ConnectivityManager.getLinkProperties().dnsServers` 取（不要 `getprop net.dns1`，那是老接口，
在双栈/Wi-Fi+蜂窝切换时常常是空的）。

实现时定下的几条：

- **每次起容器前重写**，不是安装时写一次。换 Wi-Fi、Wi-Fi↔蜂窝、开热点，DNS 都会变；
  写在安装那一刻等于让用户某天突然"容器里解析不了域名"，而界面上什么都不显示。
- 取 DNS 遍历 `allNetworks` 而不是只看 `activeNetwork`：active 那张可能正好没有 DNS
  （刚连上的 Wi-Fi、回环式 VPN），旁边那张有。`fe80::` 链路本地地址**丢掉**——
  resolv.conf 里写它要带 scope id，而容器里网卡名与宿主不同名，写了只会让每次查询先等一轮超时。
- **没有 DNS 就不建文件、也不绑**：绑一个空文件会把 rootfs 里那份（同样空的）盖掉，
  真出问题时少一条能看的线索。core 侧同理：`EnvirSpec::resolv` 是 `Option<PathBuf>`。
- `ACCESS_NETWORK_STATE` 是普通权限，不需要用户授权，但少了它 `ConnectivityManager`
  直接抛 `SecurityException`——那种失败会装成"DNS 是空的"。

**解包核过的事实**（2026-10-28，`tar -tzvf` 官方 tar.gz）：Ubuntu Base 26.04.1 里
`etc/resolv.conf` 是**一个 0 字节的普通文件**（不是指向 systemd-resolved 的符号链接），
`etc/hosts` 也是 0 字节；`usr/bin/getent`、`usr/bin/cat` 都在。所以一条 `-b` 就够，
不用连带绑 `/run/systemd/resolve/*`，也不用改 rootfs 里的东西。

**风险先记着**：Android 的 DNS 常走 netd 的私有通道，直接 UDP 53 有时被拦。所以验收判据
只写"容器里 `getent hosts archive.ubuntu.com` 出 IP"，`apt update` 通了才算真的通。

### D174 多助手：会话按助手归属，可移动

用户口径（2026-10-27）：**每个助手有自己的会话列表，会话可以移动到其他助手**。

- 存储：schema **v9**，`conversations` 加 `assistant_id TEXT NOT NULL DEFAULT 'default'`；
  迁移把现有会话全归 `default`（现在的唯一那份助手就是 `settings(assistant,"default")`）。
- 列表：抽屉里的会话按**当前助手**过滤；顶部显示当前助手并可直接切。
- 移动：会话「更多」里加「移动到其他助手」→ 选目标 → **改归属，不复制**（一句话一条记录，
  复制会立刻出现两份"同一个会话"，之后改哪份都说不清）。
- 删助手：**有会话就不许直接删**。要么先把 N 个会话移走，要么在这张卡上选"一起移到 default"。
  静默把会话挪走和静默删掉都属于"数据没了"。
- 助手本身：一页列表（现在是单个 `settings(assistant,"default")`），支持新建与"从现有复制配置"
  （复制的是模型/参数/思考档位，**不含会话**）。

**已实现的形状**（2026-10-28，schema **v9**）：

- `conversations.assistant_id TEXT NOT NULL DEFAULT 'default'` + 索引
  `conversations_by_assistant(assistant_id, updated_at DESC)`（列表那条 ORDER BY 与索引一致）。
  `DEFAULT` 不只是给迁移用：以后某条写入路径忘了给值，会话落进 `default`，
  而不是落进一个界面上哪儿都看不见的空归属（铁律 ②）。
- storage：`create_conversation(title, device, assistant)`、
  `list_conversations(assistant: Option<&str>, limit, offset)`（`None` = 不过滤，给导出/核对用）、
  `move_conversation(session, assistant)`、`conversation_assistant(session)`、
  `count_conversations_of(assistant)`；`ConversationSummary` 多带一个 `assistant_id`。
  **`move` 不动 `updated_at`**：挪个文件夹不该让它在列表里跳到最前，用户找的是那条会话。
- ffi：`create_conversation_json` 多一个**必填**的 `assistant`；
  `list_conversations_json` 多一个 `assistant`（**空指针 = 不过滤**，JNI 层不接这个口子）；
  新增 `move_conversation_json` / `count_conversations_of_json`。
- Kotlin："当前助手"存 `settings(assistant, "active")`（`{"id": …}`），与供应商
  `settings(provider, "active")` 同形——它是数据的一部分（决定新会话归谁），不是本机开关。
  列助手时跳过 `active` 这个 key（它是指针文档，不是助手）。`default` 不许改名、不许删。
- **删助手的闸**在 storage 这边只提供数（`count_conversations_of`），要不要"一起移到 default"
  由界面问用户——静默挪走和静默删掉都属于"数据没了"。
- **界面**（这一版）：抽屉底部那一行显示**当前助手**的名字与首字（不再是写死的「助手」）；
  点它弹出「切换助手」面板，列全部助手，当前那个标「（当前）」，右侧铅笔留着以后进助手页；
  面板末尾是「新建助手」——**复制当前助手那套配置**（模型/参数/思考档位），对话不复制，
  建完直接切过去并回一个空白会话。会话行**长按**弹出「把「标题」移到」，只列别的助手
  （只有一个助手时写"还没有别的助手"而不是给一个空面板）。
- **切助手之后必须回空白会话**：留在上一个助手的会话里，输入框里的下一句会发给
  "看着不像它"的那个。同理，被移走的那条会话如果正开着，也回空白会话。
- `ChatScreen` 多一个 `assistantKey`：切助手时如果本来就停在空白会话上，`sessionId` 没变，
  顶部模型条不会重读——那条会变成"显示旧助手、发给新助手"。
- **助手页**（`ui/AssistantScreen.kt`，入口：抽屉里那支铅笔 + 设置「模型与提示词 → 助手」）：
  列表（当前那个写「当前」、副标题写它用的端点/模型）、新建（复制当前配置）、**改名**（只改显示名，
  id 是会话的外键，永远不动）、**删除**。删除的三种形态都是**先算出来再给按钮**，不是点下去才报错：
  `default` 一行红字说不能删；有会话 → 主按钮变"删除…"再给「一起移到默认助手 / 取消」；
  没有会话 → 一次确认。**会话数读不出来（-1）时一律不许删**——数不清就不能说"没有会话"。
  删当前那个 → 当前先回 `default`，不然下一次建会话会拿到一个不存在的归属。
  批量搬会话前还要确认列表**来自原生层**：占位数据里的 id 是假的，照它搬等于把真会话留在原地就删助手。
- **搬会话与删助手的顺序不能反**：先移完再删，中间那一刻库里不能出现"指向不存在助手的会话"
  （那就是界面上哪儿都看不见的数据，铁律 ②）。
- **换模型不在这页**：主路径是聊天页顶部的模型条，改的正是"当前助手"那一份。
  两处各放一套选择器，迟早给出不一样的两个答案。

### D175 Shizuku：先做"检测 + 开关"，不做执行

用户口径（2026-10-27）：**授权肯定是他自己点，但我们要能验证是不是已授权**。

- 依赖：`dev.rikka.shizuku:api` + `:provider`（Apache-2.0，要进 notices；这是本项目
  **又一个外部依赖**，按规矩先记来源与版本）。
- 状态三态，界面必须分清（这三件事对用户来说是完全不同的下一步）：
  1. **没装**：`Shizuku.pingBinder()` 为 false 且 `moe.shizuku.privileged.api` 不在已装列表 →
     给"去装 Shizuku"的说明（不跳应用商店，本项目不分发它）；
  2. **装了没授权**：binder 在、`Shizuku.checkSelfPermission()` 不是 `GRANTED` → 给"去 Shizuku 里授权"；
  3. **已授权**：这时开关才**能拨**（默认仍是关）。
- 用 `Shizuku.addBinderReceivedListener` / `addBinderDeadListener` 跟着服务起落更新，不靠轮询。
- **执行（`Shizuku.newProcess`）不在这一版**：那条路要写 AIDL 包装 + 真机反复验，
  而"授权到了没有"这件事本身就得先在界面上说清楚。开关拨开之后先只记本机（不进库、不进导出）。

## 待定（还没定的事，不构成决定）

**一次多个工具调用，落成几个单元？**

- **代码现状**（`crates/core/src/session.rs`，2026-10-28 用
  `session::tests::three_tool_calls_in_one_message_yield_three_paired_results` 钉住的）：
  模型一次报三个调用 → 库里落**三个单元**，每个是 `assistant(tool_calls[1]) + tool_result`；
  三个调用 = 三个单元 + 一条最后的正文。
- **文档里的说法**：原子单元是 `assistant(tool_calls[n]) + 全部 tool_result[n]`
  （README / 术语表 / D159 那一套）。
- 两者在界面上不是同一件事：三个单元 = **三张工具卡**；一个合并单元 = **一张卡三个页签**。
  块级编辑的编号口径也跟着变（`tool:0..2` 是"这张卡里的第几个"还是"这一轮的第几个"）。
- **为什么现在不改**：它牵着 UI 卡片形状、块级编辑编号、版本单元边界三处，
  挑一边改都要连着验证；而现在的行为是自洽的（每单元闭合、按 id 配对、顺序不乱）。
  所以两边都保持原样，只在测试里写清"现状是三个单元"，等下一次动工具卡时一起定。

## 2026-10-28 补记：围栏解析的四个边界

`EditRepository.parseFences` 在真输出上跑出四个错（见 `EditFencesTest` 八条用例）：

1. **空块**（` ```output ` 后立刻闭合）与 **没闭合的围栏**：两者都会掉进"一个围栏都没有 →
   整段当正文"的兜底，于是三个反引号和模型的客套一起被写进消息里。修法不是删兜底，
   而是分两个标记：`sawFence`（有值得写回去的块）与 `sawAnyFence`（模型确实按格式回了）。
   兜底只看后者。
2. **超限块**：跳过是对的，但笔记被兜底路径吞掉，界面上就成了"模型没给出可用的改动"——
   用户没法知道是"太大"还是"模型没回"。
3. **每块末尾多一个换行**：补 `\n` 时判断的是"文件最后一行"而不是"围栏最后一行"。
   思考块写进 `messages.reasoning` 时带着它；正文因为有 `trimEnd()` 才没露出来。
4. 两个 `output` 块时**用后面那个**（模型常常先写一版再修正），但要在笔记里说出来。

这四条都不改协议，只是让"解析不出东西"变成"解析不出东西**并且说清为什么**"。

### D176 子 AGENT：助手兼职"可被调用"，靠占位符把名单喂给主 AGENT（计划，未实现）

用户口径（2026-10-28）：

> 子 AGENT 必须绑定助手才能被调用；助手里面弄一个"可作为子 AGENT 被调用"，
> 打开之后要填调用 ID 和描述，ID 与描述会作为工具信息在主 AGENT 每次请求之前替换到指定位置
> （像世界书那样，指定位置放一段特殊字符串，比如 `<var><sub_agent_info></var>`）。
> 另外调用子 AGENT **可以复用子 AGENT 的会话**，不是每次新建；主 AGENT 还能自定义子 AGENT 的
> 上下文历史，甚至只插几组消息、各自在什么位置——就像世界书的注入。

**为什么挂在助手上而不是新开一张表**：子 AGENT 需要的就是"一套端点 + 一个模型 + 参数 + 思考档位"，
那正好是助手（D161）已经在存的东西。多一张表等于把同一份配置抄两遍，之后必然漂移。

**助手文档加一块**（`settings(assistant, <id>)` 的 `doc.sub_agent`）：

```json
{ "enabled": true,
  "id": "test_agent",
  "description": "test",
  "session": "new" }
```

- `id`：`[a-z0-9_]{1,32}`，**跨助手唯一**（它是主 AGENT 手里唯一的钥匙，重名就等于调用随机一个）。
  保存时撞了要拒绝并说清撞了谁，不静默覆盖。
- `description`：给主 AGENT 看的一句话，写"什么时候该用它"，不是写实现。
- `session`：`new`（每次新会话）/ `reuse`（沿用这个助手最近一条会话）/ `conversation:<id>`（钉死一条）。
  默认 `new`——复用是省心，但"两次调用互相看见对方"这件事得用户主动要。

**名单怎么进主 AGENT 的上下文**：`<var><sub_agent_info></var>` 这个占位符可以出现在
**任何会被注入的文本**里（世界书条目、预设里的内部工具用法那段、系统提示词）。
每次组装请求前跑一遍替换，把 `enabled` 的子 AGENT 拼成：

```
{
---
id:test_agent
description:test
---
id:test_agent2
description:test
---
}
```

一条都没有时替换成 `（没有可调用的子助手）`——**不留一个空壳 `{ --- }`**：模型看到空壳会去猜格式。

**工具形状**（主 AGENT 看到的）：

```
sub_agent(id, instruction, session?, injections?, inherit?)
```

- `session.mode` / `session.conversation_id`：覆盖助手文档里的默认值（这次想复用就写 `reuse`）。
- `injections[]`：`{position, role, depth?, content}`，位置与角色**直接用世界书那套**
  （`BEFORE_SYSTEM_PROMPT` / `AFTER_SYSTEM_PROMPT` / `TOP_OF_CHAT` / `AT_DEPTH` / `BOTTOM_OF_CHAT`），
  这样"只插几组消息在指定位置"不需要第二套语汇。
- `inherit.rounds`：从子会话历史里带上最近 N 轮（默认 0 = 不带）。`include_tool_results` 默认 false——
  工具结果是给主 AGENT 看的中间产物，塞给子 AGENT 既费 token 又容易误导。
  与 `session: new` 同时用时，`inherit` 指的是"从主会话里截一段"（这就是"自定义子 AGENT 的上下文"）。

**回给主 AGENT 的是什么**：`{id, conversation_id, reply, rounds, tokens, truncated}`。
`reply` 封顶（与工具结果同一个上限），超了就 `truncated: true`；
**全文在子会话里**，界面上从工具卡点得进去（铁律 ②：库里那份必须能在界面上指出来）。

**三条硬约束**：

1. **深度只到 1**：子 AGENT 的工具面里不声明 `sub_agent`。套娃会同时烧钱和烧时间，
   而且"是谁在回答"这件事会立刻说不清。
2. **子 AGENT 的工具面收窄**：只读文件工具（`read_file` / `list_dir` / `glob` / `grep`），
   命令类（`System-Shell` / `WorkSpace-EnvirShell`）**不继承**主 AGENT 的开关。
   理由：主 AGENT 的开关是用户给"我看着它干活"的那一轮打的，不是给一个后台子进程打的。
3. **子会话归属它的助手**：`conversations.assistant_id = 子助手 id`（v9 已经有这张表），
   所以子会话天然出现在那个助手的列表里，不需要第二条"这是谁说的"字段。

**已实现的形状**（2026-10-28）：

- `crates/core/src/vars.rs`：`VarTable` / `SubAgentEntry` / `replace(text, &table)` / `is_valid_id` /
  `validate(&[(owner, entry)])`。渲染照用户给的形状抄；空表给「（没有可调用的子助手）」；
  认不出的占位符原样留着；`validate` 的每条问题都能点名（形状不对 / 和谁重名 / 描述是空的）。
- FFI：`turn_run` 认两个新字段——`system`（这一轮的系统提示词，**替换在这里做**，
  因为名单是"这一轮"的）与 `sub_agents: [{id, description}]`（形状不对或重名直接 `bad_argument`）。
  这一层没有 owner，所以只能点名 id；"和谁撞了"那种话留给保存时（那时才知道是哪个助手）。
- **界面**（助手页那张编辑卡里，2026-10-28）：一行开关「可作为子 AGENT 被调用」，
  打开之后才露出**调用 ID / 描述 / 会话策略（每次新建 · 复用最近）**。
  关着的时候那几个框根本不出现——免得用户以为它们是必填的。
- **查重走 core**：`nmoi_vars_validate_json([{owner,id,description}])` → `{ok, problems:[{kind,message}]}`。
  **先校验、再落库**：不合法的配置不进库。反过来（先存再查）会让库里躺着一条"主 AGENT 会叫错人"的
  配置，而用户以为没保存成功。校验时把**还没落库的那一份**按 id 替换进名单（`validateSubAgents(candidate)`），
  否则第一次填的时候它自己不在库里，等于没查。
- 另外 `system` 现在没有调用方——预设与世界书接线之后它才有内容，所以这一轮真机上看不到替换生效。

**没定的**：子 AGENT 的输出要不要也参与主轮的压缩统计（倾向前者计入、后者不计——它不在主会话的历史里）；
以及"同一轮里调用两次同一个子 AGENT"是否串行（倾向串行，省得两次都写同一条复用会话）。

### D177 增强编辑＝用户手动改块；模型改消息走工具（用户 2026-10-28 澄清，**没实现**）

用户原话：

> 增强编辑不是模型改这条，模型改消息是可以通过相应工具调用直接改，然后会在该次请求结束的时候生效，
> 明白吗？增强编辑还是用户手动改，但是修改会包括这一次消息的所有内容（思考块、工具块）
> 并且能编辑这种不同的块和创建这种块

所以 D163 里那条"让模型回一段围栏、客户端解析后落库"的路线**方向就错了**，拆成两件事：

| 谁在改 | 怎么改 | 什么时候生效 |
|---|---|---|
| **模型** | 工具调用（`edit_message`：块 = 正文 / 思考 / `tool:N`） | 该次请求结束时（与其它工具结果同一批落库） |
| **用户** | 「编辑」进一个**整页的块编辑器** | 点保存之后（走 `apply_block_edit` / 版本那条路） |

**手动编辑器的形状**（这一版要做的）：

- 整页，不是弹窗。顶部：返回 / 「编辑消息」/ 保存。
- 一条消息拆成**块列表**，每块一行：
  - `正文` —— Markdown 文本编辑（多行）；
  - `思考` —— 纯文本（多行，可空）；
  - `工具参数 #N` —— JSON 文本，保存时校验；非法 JSON 当场红字，不写库；
  - 每块右侧「删除」；底部「＋ 添加块」→ 选 正文 / 思考 / 工具参数。
- **能创建原本不存在的块**：一条消息原本没有思考块，用户可以加一个——这就是"创建这种块"。
  正文与思考各最多一份（消息只有一列正文、一列思考），工具参数可以有多份，编号按顺序重排。
- 保存后**立刻从库里重读**再画（铁律 ①）：不做"本地先改、界面再叠一层"。

**模型那条路**（下一步）：

- 工具名 `edit_message`，参数 `{message_id, blocks:[{kind:"output"|"thinking"|"tool", index?, content}]}`；
- 与其它工具一样在**这一轮结束时**统一落库（`apply_block_edit` 已经是这个底座）；
- 落库之后工具卡与消息卡都要重读——用户看到的是"模型改完了"，不是"下一轮才生效"。

**已实现的形状**（2026-10-28，`ui/MessageEditScreen.kt` + 路由 `MessageEdit(sessionId, messageId)`）：

- 块列表：正文（恒有）/ 思考（**可加可删**）/ 工具参数 #N（只改不增删）。
- 落库走 `apply_block_edit`：`output` / `thinking` / `tool:<index>` 一套词，
  与 `edits` 审计表同事务。**只写真的改过的块**——没改的块不产生审计记录。
- 工具参数保存前**全量校验 JSON**：一条不合法就整页不写（避免"改了三块、坏了一块"）。
- 存完 `onSaved` 让消息区重读（`externalReload`），**不在界面上叠一层"我刚改的"**（铁律 ①）。
- 消息「更多」里那条入口已改成「编辑消息（正文 / 思考 / 工具参数）」。
- **一处刻意的偏离**：工具块**不能凭空加、不能删**。理由：一条调用必须与一条结果按 `call_id`
  配对，孤儿调用会让下一轮请求直接 400；名字与 `call_id` 也不许改（D158）。
  界面把这条写在工具块旁边，而不是给一个点了会报错的按钮。要"连工具块也能加"，
  得先有"这一轮的调用与结果一起编辑"的模型，那是另一件事。

**还要下线的**：`EditRepository` 那套围栏解析（含 8 条单测）——入口已经不用它了，
等 `edit_message` 工具接上再一起退役；先留代码不留入口，免得有人还在用。
它是 D163 那条错路线的入口；围栏解析器（`EditRepository.parseFences` + 8 条单测）先留着，
等手动编辑器接好、入口删掉之后再一起退役——**先删入口再留代码**，免得有人还在用。

### D177 补记（2026-10-29）：模型那条路做完了（0.14y）

`edit_message` 已实现，形状与上面写的一致，另有四处**当时没写、做的时候才定下来**的：

1. **id 模型看不见**。消息 id（`<session>:<seq>`）不随请求发出去，只要求"必须是 id"
   等于让模型去猜一串它永远看不到的东西。所以 `message_id` 还认 `last_assistant` /
   `last_user` / `last`，在**这一轮的历史**里解析；具体 id 仍然照收。
2. **落库在收尾，不在工具里**。core 不依赖 storage（工具只认 `ToolContext`），
   所以工具只做"参数能不能站得住"的检查、回一句「已排队 N 处改动，这一轮结束时落库」；
   真写发生在 `run_session_turn` 第 3.5 步（工具结果落库之后、压缩判据之前）。
   `HistoryStore` 加了一个**默认拒绝**的 `apply_block_edit`——"悄悄什么都没写"比报错难查得多。
3. **回给模型的话必须写"排队"**。写"已修改"，模型就会替我们向用户宣布改好了，
   而用户此刻还什么都没看到。这条是文案，也是证据。
4. **门控沿用 `WorkspaceWrite`（与 `edit_file` 同档），但不要求真的有工作区**：
   改的是会话里的消息，不是磁盘。只读绑定下这个工具不会被声明——与 `edit_file` 同一条界。

**"改完了"必须看得见**（铁律②）：`turn_run` 的返回体多一个 `edits`（每块一条：
`target` / `block` / `ok` / `message`），界面把它数出来写进提示行——
「模型回了 N 字，改了 2 处消息内容」。没写进去的那几块单独说「N 处没写进去（原因在日志）」。

**块名只有一处解析**：`BlockSelector::parse`（core）——手动编辑的 FFI 入口与 `edit_message`
共用，以前 FFI 自己那份已删。`MAX_BLOCK_BYTES` 同理搬进 core。

**没做的**：`edits` 不进 UI 的其它地方（消息卡上不额外标"AI 改过"，红蓝那套照旧管这件事）。

**2026-10-29 补记（收尾两条）**

5. **入口那条断线**：`onEditMessage` 从 `MainActivity` 传进了 `ChatScreen`，但页面里没人用它——
   「更多 → 编辑消息（正文 / 思考 / 工具参数）」点下去走的是旧的「问模型改」弹窗
   （`EditRepository.enhance`）。也就是说 0.14x 那个整页编辑器**在真机上一个入口都没有**，
   而编译器不会对"没用的可选参数"说一句话（`kotlinc` 类型检查也过了）。
   现在那个菜单项直接 push `Route.MessageEdit(sid, messageId)`；回调没传时给一句
   「编辑页还没接上」，不是点了没反应。**判据还是 V20 第 1 步：点进去必须是整页。**
6. **`EditRepository` 正式退役**（D163 那条错路线的入口）：文件与 `EditFencesTest` 八条一起删，
   `build.gradle.kts` 的 junit 依赖留着（下一个纯逻辑件照样要用）。
   删的理由不是"没人调"，是"它代表的那条路线（模型回围栏、客户端解析）已经被 D177 取代"。

## D178 预设注入 + `base-version`（0.14t，2026-10-29 实现）

D159 定了"只有对应工具开着才注入"和"`base-version` 的口径"，这一版把它做成能用的东西。
三处当时没写、做的时候必须定的，先记在这儿。

**1. 落库存 `settings`，不动 schema。**

词条的用户改动放 `settings(scope="preset", key="<预设 id>/<词条 id>")`，值是
`{base_id, base_version, base_hash, content, disabled}`。理由：`(scope, key) → JSON` 这套
本来就是为"加一类设置"准备的（D86），而加列要一次**单向**迁移——为一个"用户可能一条都不改"
的功能动 schema 不划算。代价说清楚：这些文档不进列查询，导出/备份跟着 `settings` 表走，
真要拿来做批量统计得先解 JSON。

**2. 四态按"词条"算，列表页按"预设"取最要紧的那一条。**

一份预设 = 一个 `applies_to` + 若干词条。用户改的是**词条正文**（标题与工具名是 wire 契约，
不许改）。所以：`Follow < LocalEdited < UpstreamNewer < Conflict`，预设徽标取最要紧的那条
（`preset::preset_state`）——列表页只有一行位置放徽标，得有"这份现在要不要你管"的答案。

**3. 只有三件事由 core 算：四态、注入哪几条、注入那一段长什么样。**

界面**不自己推四态**（`nmoi_presets_json` 给什么显示什么），`turn_run` 与列表页共用
`preset::state / entry_view / select / render` 同一份。理由：显示与注入一旦各算一遍，
就会出现"列表说跟随、请求里注入的是旧正文"这种没人能复盘的错。

**其余形状**

- assets：`android/app/src/main/assets/presets/<id>/{manifest.json,preset.json}`。
  `manifest` 给元信息（`id`/`version`/`title`/`summary`/`applies_to`），`preset.json` 给词条。
  **目录名必须等于 `manifest.id`**（否则 `base_id` 会跟着错，四态静默算歪）；缺一个文件就跳过并记日志。
- 随包三份（我起草，用户可改）：`tool-usage`（v1，需要 read/write/edit_file）、
  `shell-safety`（v1，需要两把 shell）、`edit-message`（v1，需要 `edit_message`）。
- 注入位置：system 提示词**替换完占位符之后**接一段 `## 预设（工具相关规则）`。
  顺序固定（预设 id 升序 → 词条顺序），否则缓存前缀整段作废。
- 过滤：`declared = registry.specs_for(policy)`——与 `run_turn` 里"没冻结时"的那一条同源。
- 请求日志：`turn_run` 返回 `presets:{text,chars,injected:[{preset,entry,version}]}`，
  Kotlin 写一行 `NLog.i("Preset", …)`。**一条没注入也要说**（"没注入"和"注入了但没显示"是两件事）。
- 「更新 / 用上游」= 删文档（回到跟随）；「保留本地」= **只把 base 前进到上游那一版**，
  正文不动 → 状态从冲突变"已改"。**绝不自动覆盖**：冲突不点就不动。
- 空正文不许保存（要停用就点「停用」）；停用的词条不进请求，但还留在列表里（基线条目不可删，只能禁用）。
- `base_version` 在界面上**没有可以改它的控件**（D159 的双层不可改的第一层）。
  第二层在保存路径：视图把**用户上次对齐的那一版**（`entry.base`，没改过就是 `null`）带出来，
  界面保存正文时把它原样写回；只有视图里没有 base（第一次改）才用上游这一刻的版本。
  只有「保留本地」显式传上游那一版——那是它的定义。这条不做的话，一个"冲突"里改几个字保存
  就会变成"已改"，等于替用户把上游的新版悄悄盖掉了。

**没做的**：整份"一键更新"（会让某几条本地改动一起消失，属于"绝不自动覆盖"的反面）；
`applies_to` 认能力位（现在只认工具名，够用且好排查）；预设导出/导入。

## D179 单框标记编辑器：一套"不容易被误匹配"的块语法（2026-10-29 用户设计）

用户原话：编辑要「只有一个输入框，包括思维块工具块的」，用特殊字段表示它们，
并且**必须保证不会把正文里长得像标记的东西解析成块**。他给的例子是
`<start> … <tool_call> <input> <output> </tool_call> <end>`，
并要求我"弄得更复杂、更不容易被误匹配"。

**语法（v1）**

```
<nmoi-edit v="1">
<nmoi:output>
正文（Markdown 原文）
</nmoi:output>
<nmoi:thinking>
思考块正文（空 = 这条消息没有思考块）
</nmoi:thinking>
<nmoi:tool index="0">
<nmoi:name>read_file</nmoi:name>
<nmoi:input>
{"path":"a.txt"}
</nmoi:input>
<nmoi:stdout>
ok
</nmoi:stdout>
<nmoi:stderr>
</nmoi:stderr>
</nmoi:tool>
</nmoi-edit>
```

四条让它"不可能被误匹配"的规矩：

1. **前缀 `nmoi:`**（`nmoi-edit` 是根）。正文里写 `<output>`、`<tool_call>`、甚至别人的
   `<nmoi:output>`（缺 `</nmoi:output>`）都不会被当成块——解析器只认**配对出现**的那几个。
2. **根标签必须唯一**，首行之外出现 `<nmoi-edit` 一律报错（正文里提到它就得转义成 `&lt;`，
   界面上有一句提示）。
3. **严格失败**：未知标签、未闭合、嵌套错、同一块出现两次、`index` 重号或不连续 →
   **整页不写库**，并指出"第几行、哪个标签"。宁可让用户改一眼，也不要写进半截。
4. 标记里的内容**不做任何转义**（它就是正文），所以解析必须按"找结束标记"来做，
   不能按"行首像标签"来做：`</nmoi:output>` 出现在正文中间也会被当成结束——这是
   唯一一处"用户得知道"的边界，写在编辑页的提示里。

**与存储的关系**：这段文本是**一份视图**，不是新的一列。保存时按块拆分，
再走现有的落库路径（正文/思考走 `apply_block_edit`、工具参数与结果走 D182 的成对编辑）。
**不许"另存一份、显示时叠上去"**（铁律②）。

**为什么不用 JSON**：用户要的是"能直接读能直接改"的一段文本；JSON 里改一个引号就得整份祈祷。
也不用 YAML：缩进敏感，手机上编辑更糟。

## D180 会话管理：复制 / 置顶 / 多选（2026-10-29 用户提）

- **复制 = 新建一个会话 + 把消息整份拷过去**（新 id，`assistant_id` 跟随目标助手）。
  storage 加 `copy_conversation(from, title, device)`：一条事务里建会话、按 `seq` 顺序搬消息、
  **不搬**归档与分界线（那是压缩的历史，跟着原文走没有意义，反而会把"已压过"的状态带过去）。
- **置顶**：只影响列表顺序，不改 `updated_at`。存 `settings(scope="conversation_index",
  key="<assistant id>")` 的 `{pinned:[id…]}`——**不加列**，理由同 D178（迁移单向、代价高）。
- **多选**：进入后顶部「× · 已选 N 项 · 全选」，底部三键「置顶 / 移动 / 删除」。
  删除要给自绘确认（D152 那套），并写清"会没掉 N 条会话、M 条消息"。
- 长按一个**未选中**的行 → 进多选并选中它（与消息区的长按语言一致）；
  已经有多选时，长按换成"再选一条"。

## D181 续话与重新生成：能做，但边界要先说清（2026-10-29）

用户问"不修改当前会话消息、不加消息，直接把会话消息全部发过去请求，能不能做"。
**能做**，但有一处必须定死：回来的那条回复**要不要落库**。

- **要落库**（我的选择）。不落库的话，用户花了钱、等了几十秒，屏幕上什么都没有；
  而且它违反铁律②（库里存的东西要能在界面上指出来）的反面：**算出来的东西不给人看**。
  所以语义定为：**不新增用户消息、不改旧消息、把当前窗口原样再发一次，回来的助手消息照常落库**
  （它是一条独立助手消息，不挂在任何工具对上，结构合法）。
- `turn_run` 加 `mode`：`send`（默认，老行为）/ `continue`（不加用户消息）。
  `continue` 时用户那句话不是必填；`user_text` 给了也**不落库**。
- **重新生成**（动作行那个转圈键）= 删掉最后一条助手消息、再用 `continue` 发一次。
  与"续话"的区别只有一个：它把最后一轮重来。删除走现成的 `delete_message`（整单元删）。
- 两件事都要求"会话里至少有一条消息"，否则给一句实话（"这里还没有可以续的话"）。

## D182 AI 改消息要落"红"，成对删块（2026-10-29 用户实测后提）

用户原话：「我测了工具改消息可以，但是不能连此组对话的块一起改，我想要让它可以真正能改一切，
顺带一提现在 AI 改消息后它不会变成那种有红蓝的 1/1」。

两处都改：

1. **正文落到 `tooledit_message`**：模型改正文时，原文留在 `origin_message`（蓝），
   改后的写进 `tooledit_message`（红）——那条消息于是有 `< 1/1 >`，用户能在两份之间切。
   现在的 `apply_block_edit` 是"就地改原文"，用户手动改走它是对的（他改的就是他要的），
   模型改**不能**走它：模型改的内容不是"原文"，是"一个提议"。
   思考 / 工具参数没有两份正文，仍然就地改（它们不在版本那一套里）。
2. **成对删块**：允许把「工具调用 + 它那条结果」一起删（`unit` 级别，一个事务），
   也允许改结果正文（`tool_result` 那条消息的 `content`）。**不允许**只删一边：
   孤儿调用会让下一轮请求直接 400，而报错信息指向的是"消息结构"，不是"你刚才删了半块"。
3. 界面上：块编辑器与 `edit_message` 共用 `BlockSelector` 那套词，新增
   `result`（工具结果正文）与 `drop_tool(index)`（成对删除）。

## D183 别名与定位：F1–F5 是"形态档"，模式三/四才是"编辑权档"（2026-10-29 对齐设计稿）

搜关键词用：**F3 / F4 / 模式三 / 模式四 / 灰烬模式 / masking / 仅指针**。

`Context-Plans/context-modes-design-v1.1.md` 里有两套编号，之前在本项目里被混用了：

| 编号 | 是什么 | 在哪 | 能不能改内容 |
|---|---|---|---|
| **F1** | 全文 | §2.2 形态表 | — |
| **F2** | 截断 + 尾注（头 N + 尾 M + `[… 8.2k tok 已归档 ref#a3f9]`） | 同上 | 只截断，不改写 |
| **F3** | **masking 占位符**（`[output masked: 8.2k tok, ref#a3f9]`） | 同上 | 只换形态 |
| **F4** | **仅指针**（`[archived ref#a3f9 \| 8.2k tok \| tool:read_file \| src/x.ts:120-380 \| 一行描述]`） | 同上 | 只换形态 |
| **F5** | 索引摘要（一行描述，不承担内容职责） | 同上 | — |
| **模式三** | 模型**只打标**、策略层执行（`mark_context`） | §3 | **不能**（刻意限制） |
| **模式四** | 灰烬模式：模型有**完全编辑权**（`ctx_*`） | §4 | 能（compress/discard/reorder…） |

**用户口语里的"F3/F4 模式"指的是模式三/模式四**（"第三档/第四档"），因为"让模型用工具直接改上下文"
只在模式四存在；模式三连改都不许。以后写文档两个名字都带上，避免再混。
