# 图标包与图标规则（P6.5 / D105）

聊天界面里的图标**运行时用 PNG 渲染**，不用 SVG：Android 上没有现成的 SVG 渲染器，
为了几张小图引一个渲染库不划算，而 PNG 解码是系统自带的。PNG 打成一个"图标包"，
可切换、可导入、可导出；原版包随安装包分发，不可删除。

设计源（SVG）留在 `docs/ui/icons/`，PNG 由 `scripts/build_icon_pack.py` 生成，
两者是"母版与产物"的关系——**手改 PNG 没有意义，下次生成会覆盖**。

## 1. 目录

```
docs/ui/icons/{actions,providers,models}/*.svg    设计母版（入库）
<install>/icon-packs/original/                    原版包（PNG，随分发物走）
  manifest.json
  NOTICE.txt
  actions/*.png  actions/*@2x.png  actions/*@3x.png
  providers/…    models/…
<user-data>/icon-packs/imported/<pack-id>/        用户导入的包
<user-data>/icons/custom/<icon-id>.png            用户自建的单个图标
```

Android 的 `assets/` 只读：原版包在 `assets/icon-packs/original/`，导入包与自建图标
在应用私有目录。桌面端原版包**不藏起来**，用户可以按 `docs/packaging.md` §2 直接覆盖。

## 2. 包格式

`manifest.json`：

```json
{
  "schema_version": 1,
  "pack_id": "nmoi-original",
  "name": "原版图标包",
  "version": "1.0.0",
  "created_at": 1790000000000,
  "entries": [
    { "kind": "action",   "key": "search",   "file": "actions/search" },
    { "kind": "provider", "key": "openai",   "file": "providers/openai" },
    { "kind": "model",    "key": "claude",   "file": "models/claude" }
  ],
  "defaults": {
    "rules": [
      { "pattern": "^claude",  "kind": "model",    "key": "claude" },
      { "pattern": "^gpt-|^o[13]", "kind": "model", "key": "openai" },
      { "pattern": "^glm|^chatglm", "kind": "model", "key": "chatglm" }
    ]
  }
}
```

- `file` 是**不带扩展名、不带密度后缀**的基名；实际文件是 `<file>.png`、`<file>@2x.png`、`<file>@3x.png`。
- 密度档固定 1×/2×/3×：动作图标 24/48/72 px，品牌图标 40/80/120 px。
  缺 `@2x` 时按 1× 放大（宁可糊一点，也不要 404）。
- `key` 是稳定标识，**不跟着文件名变**：`key` 一旦发布就不许改，改了就换 `pack_id`。
- `defaults.rules` 可选，只是"首次启动时把规则种子写进设置"用；设置里改过之后以设置为准。
- `schema_version` 只增不减。不认识的版本 → 整包拒绝并提示升级客户端。

## 3. 取图顺序（五级，先命中先用）

给一个模型选图标时按顺序问：

1. **用户规则**（设置里自己加的，按列表顺序，可拖动排序）。
2. **包内预设规则**（`defaults.rules`，只在用户规则没有命中时用）。
3. **供应商直配**：provider 的 `kind`（`openai` / `anthropic` / `gemini` / `ollama` / `openai-compatible`…）
   → `kind == "openai-compatible"` 时看端点域名或用户填的名字，命中 `providers/<slug>`。
4. **模型名首段**：`deepseek-chat` → `deepseek`；`claude-3-5-sonnet` → `claude`。
   归一化规则：转小写、`.`/`_` 视同 `-`、去掉 `[分组]` 前缀、去掉 `/` 之前的部分。
5. **首字母方块**（monogram）。前三步全不中时用它，规则见 `docs/ui/icons/README.md` §4。

**规则对象**：

```json
{
  "id": "preset-model-31ac0a93",
  "pattern": "^(anthropic/)?claude",
  "mode": "regex",
  "field": "model",
  "kind": "model",
  "key": "claude",
  "priority": 500,
  "case_sensitive": false,
  "source": "preset"
}
```

| 字段 | 含义 |
|---|---|
| `id` | 稳定标识，**发布后不许复用**。排序的最后一极，保证全序 |
| `pattern` | 正则或关键词。≤ **200 字节** |
| `mode` | `regex` / `keyword` |
| `field` | `model` / `provider_kind` / `provider_host` / `provider_name` |
| `kind` | 这条规则产出哪类图标：`action` / `provider` / `model` |
| `key` | 目标图标 key（包内 entry，或用户自建图标 id）。只允许字母数字 `-` `_`，≤64 字符——它同时是文件名 |
| `priority` | **0–1000，数字越小越优先**，默认 500 |
| `case_sensitive` | 只对 `keyword` 有效；`regex` 下忽略 |
| `source` | `preset` / `user`。同优先级时**用户赢预设** |

### 3.1 排序键（全序，只在一处决定）

```
(priority 升序, source(user< preset), 锚定 true 优先, 有效字面量长度 降序, 通配符数 升序, pattern, id)
```

- **为什么不用字符串长度**：`(?i)`、`\.`、`(?<name>)`、`[Cc]` 都会虚增长度；
  而 `^(alibaba/|dashscope/)?qwen` 比 `^claude-opus` 长得多却远不具体。
- **为什么最后要落到 `id`**：加载顺序（文件遍历、导入包、UI 拖动）不稳定，
  拿它兜底会让同一份配置在两台设备上给出不同图标。全序之后任何顺序结果都一样。
- 实现见 `crates/core/src/icons.rs` 的 `rank_key`，20 项单测钉着这些行为。

### 3.2 "有效字面量长度"怎么算

一条规则算一个 `Specificity`：`{ anchored, literal, wildcards }`。

- 去掉 `(?i)` `(?is)` 这类内联标志（不是具体度）。
- 转义还原成一个字面量：`\.` 算 1；`\d` `\w` `\s` `\b` 算通配符。
- 元字符不算：`.` `*` `+` `?` `{` `}` `[` 计入通配符，`(` `)` `^` `$` 不计入。
- **可选组和"多选一"组里的字面量不算**：`(…)?`、`(…)*`、含顶层 `|` 的组，
  都是"可能不出现"，不能拿来证明具体。所以 `^(alibaba/|dashscope/)?qwen` 的具体度 = `qwen` = 4。
- 顶层 `|` 拆成多分支，取**最长的那一支**。
- `^` 开头算锚定；锚定优先于不锚定（`^claude` 比 `claude` 具体）。

这是**可解释的近似**，不是语义精确度。所以设置页要把算出来的分数显示出来——
用户看得见"为什么这条排前面"，才愿意用自动排序而不是拼命改优先级。

### 3.3 大小写

- `keyword`：给一个"区分大小写"开关。
- `regex`：**以写法为准**，该开关置灰。要忽略大小写就写 `(?i)`。
  同一条规则在两种模式下的行为因此一致、可解释。

### 3.4 优先级怎么给

数字区间在 UI 上显示名字，不让用户算：

| 区间 | 名字 | 用途 |
|---|---|---|
| 0–99 | 钉死 | 覆盖一切，包括更具体的规则 |
| 100–499 | 高 | 我希望这条先被看 |
| 500 | 默认 | 预设与新建都用它，靠具体度决胜 |
| 501–900 | 低 | 兜底用 |
| 901–1000 | 最后 | 只在没有任何其他命中时才考虑 |

**优先级数字本身要显示出来**（列表每项用小号字体写在旁边），拖动/输入都能改——
不然用户不知道自己在调什么。

### 3.5 上限与保存拦截

- 规则总数 ≤ **500** 条；单条 `pattern` ≤ **200 字节**；`key` ≤ 64 字符。
- 超限时**不许保存**：点保存直接拦下，弹出具体建议（`RuleError::suggestion`）：
  - 塞了分支 → "把 `A|B` 拆成两条规则，各自更短也更可控"
  - 塞了 `.*` → "`.*` 能删就删，用实际分隔符更准（`claude-opus-5` 比 `claude.*opus.*5` 好）"
  - 其它 → "只保留能区分的开头部分，例如 `^claude-opus`"
- 坏正则不阻塞整体：**逐条编译、逐条跳过**，那条标红并给出编译错误原文。

### 3.6 两个子页面 + 命中测试

设置 → 图标设两个子页：**供应商自定义图标** / **模型自定义图标**。分开是因为
匹配对象本来就是两样东西，混在一张表里，一条 `openai` 会同时吃掉"供应商叫 openai"
和"模型名含 openai"这两件事。

两个子页各带一个**测试框**：输入 `anthropic/claude-opus-5.5`，直接显示命中了哪条、
分数是多少、为什么排在前面。规则系统一旦调不出来就变黑盒，用户只能瞎试。
两条规则同优先级同具体度命中同一对象时标黄提示冲突。

供应商侧的查询顺序是固定的：`provider_kind` → `provider_host` → `provider_name`。
kind 是结构化的（只有五个值），比域名可靠；域名比用户随口起的名字可靠。

## 4. 用户自建图标

设置 → 图标 → 「添加图标」：

1. 选一张图（PNG/JPEG）。**解码与缩放交给平台**（Android `BitmapFactory`、桌面各平台自带），
   核心不解码，也不为此引 Rust 图像库——为一次缩放引几 MB 的依赖不划算。
   平台转成正方形 PNG（居中裁切）后交给核心校验，通过才落盘：
   `core::iconpack::validate_custom_icon`（是不是 PNG、是不是正方形、边长 48–512），
   存 `<user-data>/icons/custom/<id>.png`。
2. 起一个 `key`（默认用文件名清洗后的结果）。
3. 填匹配正则，**边填边预览**：下面实时列出"当前会命中这条规则的模型名"，一条都没命中就提示。
4. 自建图标优先于包内图标：同 `key` 时自建覆盖包内，删掉自建就回落到包内。

## 5. 图标包页（设置 → 图标）

| 行 | 内容 |
|---|---|
| 当前使用 | 下拉：原版 / 导入的包（按 `pack_id` 排） |
| 无品牌图标 | 开关。打开后跳过第 3、4 级里所有品牌图标，直接走首字母方块 |
| 原版 | 只有「导出」按钮，**没有删除**——删除接口对 `pack_id == "nmoi-original"` 直接返回错误 |
| 导入的包 | 「导出」「删除」；删除当前生效的包时自动切回原版 |
| 导入 | 选 zip → 校验 → 落到 `<user-data>/icon-packs/imported/<pack-id>/` |
| 导出 | 选「原版」或「当前包」→ 生成 `<pack_id>-<version>.zip` |

**导入校验**（任何一条不过就整包拒绝，不半信半疑地装）：

- `manifest.json` 能在根目录找到、`schema_version` 认得、`pack_id`/`version` 非空。
- **不许冒用 `nmoi-original`**：导入包里出现这个 id 就拒绝（那是原版的保留 id）。
- 每个 entry 至少 1× 的 PNG 在；PNG 魔数对（`89 50 4E 47`）。
- 单个文件 ≤ 512 KiB、包内文件总数 ≤ 512、解压后总大小 ≤ 8 MiB。
- 路径不许越界：条目名不得含 `..`、不得以 `/` 开头、不得是绝对路径。

**zip 自己写**（已实现，`core::zip` + `core::iconpack`，D108）：项目有一条禁令——不用 Rust
的 zip 系 crate（`NMoi-Notes/constraints.md`）。打包用 store（不压缩；PNG 已经压过），
解包只实现"读中央目录 + 逐条取数据"，CRC32 手写一张表（标准向量已对过）。
这条路能覆盖我们自己的包，**不要**声称能解任意 zip：zip64 / 加密 / deflate 一律报错。

拒绝清单（任一条不过就整包拒绝，23 项单测钉着）：schema 版本不认识、`pack_id` 空或冒用
`nmoi-original`、条目重复、key 不是安全文件名、文件不在对应 kind 的目录、缺 1× PNG、
PNG 魔数不对、路径含 `..` 或是绝对路径、重名、单文件 >512 KiB、文件数 >512、总量 >8 MiB、
CRC 不符。缺 `NOTICE.txt` 只 warning 不拒，但导出时一定补上。

## 6. 导出包里必须有什么

```
<pack_id>-<version>.zip
  manifest.json
  NOTICE.txt          ← 第三方声明，图标是再分发，必须跟着走（docs/packaging.md §1）
  actions/…
  providers/…
  models/…
```

`NOTICE.txt` 的内容 = 根目录 `THIRD-PARTY-NOTICES.md` 中与图标相关的两节 + 两段许可全文。

## 7. 缓存

- 解码按需，内存里最多 64 张（LRU），命中率高的就是那十几个。
- 同一 `(pack_id, key, density)` 只解码一次；包被切换或删除时整块失效。
- 品牌图标解码失败 → 当"没有"处理，走下一级；**不要**显示裂图占位。

## 8. 两个脚本

```
python3 scripts/build_icon_pack.py                  # SVG 母版 → data/icon-packs/original（PNG×3 档 + manifest + NOTICE.txt）
python3 scripts/icon_preview_html.py --out x.html   # 把包渲染成单文件 HTML（PNG 内联，离线可开，深/浅两主题）
```

**单色与彩色的分界线是"有没有显式颜色"，不是"根元素是不是 currentColor"**：
纯 `currentColor` 的烤成白色剪影，运行时 tint（一份文件通吃深浅主题）；
有显式颜色的原样烤、**运行时不许 tint**。彩色标里若还夹着 `currentColor`（Lobe 的图标里有），
烤成它自己的主色——烤成黑会在深色底上消失。

**品牌图标必须垫一层 `surface-variant` 底色**：`nova` 这类含白色部件的标，在浅色主题下
白色会白掉。打包脚本会点名这类图标，UI 侧统一垫底就不用逐个判断。

## 9. FFI 面（已开）

```
nmoi_icons_open_json(root)                     → {handle, root, packs}
nmoi_icons_close_json(handle)                  → {closed}
nmoi_icons_list_json(handle)                   → {packs, count}   原版恒在第一行
nmoi_icons_import_json(handle, zip_path)       → {pack_id, entries, warnings}
nmoi_icons_export_json(handle, pack_id, dest)  → {path, bytes, entries}
nmoi_icons_delete_json(handle, pack_id)        → {deleted} | error kind=refused（原版）
nmoi_icons_rules_json(handle, pack_id?)        → {pack_id, rules}  给设置打种子
nmoi_icons_resolve_json(handle, rules_json, subject) → {icon, via, matched_rule, monogram, problems}
```

**这一层不碰 settings 表**：当前用哪个包、用户规则是什么属于设置，存在 `settings` 里（D86），
由调用方读出来**当参数**传给 `resolve`。句柄表是一把全局锁，图标句柄里再嵌存储句柄会自己等自己
——宁可多一个参数，也不要一个会死锁的接口。"切包 / 存规则"因此不在这一层，是 UI 层的两个调用。

`via` 说明是哪一级命中的（`rule` / `provider_kind` / `model_prefix` / `monogram`），
界面拿它解释"为什么是这个图标"。没命中时 `icon` 为 null 但 `monogram` 永远有值。

## 10. 还没做的

- 自建图标的**落盘路径**还没定：核心只做 `validate_custom_icon` 校验，
  文件写在 `<user-data>/icons/custom/<id>.png`，但"id 怎么生成""重名怎么办"等 UI 层一起定。
