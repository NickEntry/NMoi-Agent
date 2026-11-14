# 语音服务与唱歌（0.17b 设计稿，未动工；0.17b0 原型已跑）

用户 2026-11-13 提，附三张图：语音服务页（两段列表 + 对号）、编辑语音服务页（服务提供方 chips +
名称 / API Key / API 基址 / 音色 ID / 语言）、聊天页「工具」面板（本地工具逐项开关 + MCP 段 +
工作区段）。要求原话：

- 「如果能调用 TTS 的时候给个可选的复杂编排的调用就好了，这样就可以写好合适的脚本然后调用，
  就能让 TTS 唱歌了，主要是想做这个」；
- 「甚至你可以内置一些简单的音频调整工具绑给 TTS 调用」；
- 「TTS 和 ASR 都有开关的，在聊天页面工具里面……其他工具也最好在这里做开关」；
- 「最好对现有的 Gemini, Minimax, Qwen, DeepSeek, MIMO, xai 的 TTS 还有本地 TTS 进行歌曲创作适配」；
- 「音色这些什么的你可以弄成有选项也可以自定义填写的，就是右边有一个 ˇ 点开变成 ^ 显示列表可以点，
  当然也可以自己输入填写」；
- 「把各种类型 API 的相关文档看一下，目前有哪些音色语种什么的支持就填上去」；
- 「找个 400M 内的小模型测一测……直到我觉得完全通过之前这个模型可以一直装着，后面通过后卸了」。

---

## 0. 原型已经跑出来的东西（2026-11-13，可听）

`scripts/tts_sing_prototype.py`：不要求 TTS 自己会唱。任何一个能念字的引擎都能拿来唱——

```
每个音符 → 单独念一个音节 → 自相关估基频 → WSOLA 拉长到目标时值 → 重采样到目标音高 → 按谱拼接
```

《小星星》14 个音，实测每个音的基频与谱面偏差：最大 42 音分，多数 < 5 音分
（`python3 sing.py out.wav --check` 逐条打印）。产物：`/chat/outputs/xiaoxingxing-sing.{wav,mp3}`。

这条流水线是 **provider 无关**的，所以"让 Gemini / MiniMax / Qwen / xAI / 本地都会唱歌"不靠各家
自己去支持音高曲线，靠这一层。原生支持音高曲线的引擎（DiffSinger 那一类）走另一条快路。

### 0.1 v2：换成 PSOLA（保共振峰），并跑出对比版本

v1 用"重采样改音高"，共振峰跟着搬，听感像鼻音塞子。v2 改成 **TD-PSOLA**：
复制原始基音周期、按目标周期摆回去，音高变了、音色留在原处（`scripts/tts_sing_v2.py`）。

踩到并修掉的两条：

1. **标记必须贴声门脉冲**，否则复制的窗口互相抵消（听感"半个音高"或沙哑）。
2. **但网格不能被贴过的位置带跑**——从贴过的点继续走会漏周期，音会低一截。
   做法：网格按源周期前进，标记只在网格 ±p_src/4 内贴峰。

音频工具的机制在这次也一并验了（`scripts/tts_variants.py`，产物 `/chat/outputs/sing/`）：

| 文件 | 演示什么 | 客观核验 |
|---|---|---|
| `a-resample` | v1：重采样改音高（对照） | 音高准、音色变薄 |
| `b-psola` | v2：PSOLA，不连奏 | 各音偏差 ≤ 42 音分 |
| `c-legato` | v2 + 30 ms 交叠 + 长音颤音 | 同上 |
| `d-space` | 加混响/提亮（一条 ffmpeg 链） | — |
| `e-male` | 换男声（同一套参数） | — |
| `f-mixed` | `audio-mix`：人声 + 极简伴奏 | 低频占比 3.6% → 15.3% |
| `g-pitch-up2` / `g2-pitch-down2` | `audio-pitch` 变调不变速 | 多点实测比率 1.08–1.17（目标 1.122） |
| `h-tempo80` | `audio-tempo` 变速不变调 | 时长 ×1.25 命中 |
| `i-raw` | **不做** trim/normalize 的样子 | 对照，说明那两个工具在修什么 |

结论（给实现用）：这套数学在 Rust 里照抄即可，不需要额外依赖——
WSOLA 与 PSOLA 都只是"取窗 + 加权叠加"，FFT 都不用。

### 0.2 换了个「耳朵」：用 ASR 反听来判，而不是靠猜

我听不到音频，所以搭了一条客观回路：把渲染结果喂回 Whisper（`faster-whisper` small，
`HF_ENDPOINT=https://hf-mirror.com`），看它能不能把歌词转回来。**转得回来 ≈ 辅音还在**。
厂商 TTS 念的原句（`i-raw`）是满分基线。

| 样本 | 转写 | 结论 |
|---|---|---|
| `i-raw`（原声，不处理） | 一闪一闪亮晶晶，满天都是小星星 | 基线，满分 |
| `a-resample` | 一闪一闪亮晶晶 蓝天都是小星星 | 10/11 字对，只剩「满→蓝」（共振峰被搬走） |
| `b-psola` / `c-legato` | 一盏一盏亮晶晶 漫天都只叫晶晶 | 错 4–6 字：PSOLA 的窗口在毁辅音 |
| `d-space`（混响） | 一炸炸一炸炸两斤斤… | 全毁：混响把辅音糊成一团 |
| `f-mixed`（伴奏） | 一盏一盏亮晶晶 那些都只叫晶晶 | 人声被伴奏压住 |
| `n1-split`（只拉伸元音） | 一身衣上样津津 哪天都是像星星 | 8/14，后半句反而对了 |
| `n2-world`（pyworld 重合成） | 一上一上要惊惊 漫天都失相行行 | 更差 |
| `n3-formant`（倒谱包络校正） | 一陪一陪二星星 那天都是他星星 | 更差 |

三条能直接用的结论：

1. **把 spoken 音节的元音拉 2–3 倍，辅音就被稀释掉了**。这是「念字重合成」这条路的天花板，
   不是参数没调好——`a` 之所以最清楚，是因为它在重采样时把时长一起压短了。
2. **混响 / 伴奏是在毁判据**：先别加，等音色定了再谈空间感。
3. 「像人声」靠 DSP 挪音高挪不到位，得换**真·歌唱模型**（SVS）：DiffSinger / NNSVS / BiSinger
   这一类的做法是把「音高曲线 + 音素 + 时长」直接喂给声学模型，不是把念白的波形拉长。
   这一档有 ONNX 版本可以塞进 400 MB，音素表能接 OpenUtau 那一套。

于是主线改成：**念白继续走 TTS（`Speak`），唱歌走一个中文 SVS 小模型（`sing`）**，
`n1` 那条重合成留作兜底（引擎不支持 SVS 时用，并且明说「只有念白质量」）。


未验证：本地引擎在这台机器上没跑起来——piper 的 aarch64 二进制从 GitHub release 拉 300 秒没拉完；
`pip install piper-tts` 在 aarch64 没有可用轮子（要现场编）。声音文件本身已经下下来了：
`zh_CN-huayan-medium.onnx` **61 MB**（hf-mirror，28 秒），说明"400M 以内"这条在体积上是成立的，
只是这台机器缺一个能加载它的运行时。

---

## 1. 聊天页「工具」面板：每个工具一个开关

照那张图：一个半屏面板，分三段。

| 段 | 内容 |
|---|---|
| 本地工具 | 逐项开关：时间信息 / 剪贴板 / **文字转语音** / **语音识别** / 询问用户 / 计算器 / 屏幕使用时间 / 查询日历 / 创建日程 / 当前位置 / 手机控制 / 浏览器控制 / 文件六件 / 两个 shell |
| MCP 服务器 | 每个服务器一行，右侧 `已连工具数/总数` + 开关（跟现在 MCP 那块对齐） |
| 工作区 | 默认工作区、工作目录、环境、权限（已有的那几行） |

**开关的语义是"声不声明"，不是"灰掉"**：关掉的工具不出现在这一轮的工具表里，
模型根本看不到它。这跟 `Session-Files` 的老规矩一致（D295：关着时**不声明**）。

**已落地（0.17e / D332）**：聊天页「工具」面板顶部是「本地工具 N/M」+ 每行一个开关，
清单从 native 的注册表读（`NativeBridge.toolNamesJson()`），不在 Kotlin 里另列一份。
关掉的名字随这一轮配置下发（`tools_off`），core 在 `specs_excluding` 里剔掉。
理由很硬：声明了再拒绝，模型会一直试，用户会一直看到"被拒绝"。

- 开关存本机（跟 `native_shell` 一样），不进库、不进导出。
- 一屏一个分组，行内左图标右开关，文案就是工具名——不加解释性小字。
- 关掉的那一行**不留"待接"字样**：灰 vs 关是两件事，这里是"用户关的"，不是"没做"。
- 面板顶部给一个总数（`本地工具 1/11`），跟图一致——一眼看出开了几个。

和 0.17a 的关系：命令询问管的是"跑之前问不问"，这里管的是"给不给模型看见"。
两层都要有：只有开关没有询问 = 一开就全放行；只有询问没有开关 = 每个用不上的工具都占着声明位。

---

## 2. 语音服务：两条列表，各自单选

- **TTS / ASR 两段互不影响**，各自只有一个对号。
- 删掉选中的 → 回落 `system`（TTS）/ `off`（ASR），界面写明回落。
- 条目字段：名称 / 服务提供方 / API Key（Vault，界面只显示圆点 + 眼睛）/ API 基址 / 音色 ID /
  语言 / 模型 / 能力表。
- **音色字段就是这个控件**：一行输入框 + 右侧 `ˇ`，点开变 `^`，下面是可点的清单
  （来自这一条的能力表）；同时**可以直接在输入框里打字**，清单只是快捷方式，不是白名单。
  语言同理（`auto` / `zh` / `en` / `ja` …）。

## 3. 服务提供方与能力（填表的方式）

三张来源，优先级从高到低：

1. **探测**：条目页有「探测能力」。能列音色的端点（`/v1/audio/voices` 一类）直接拉回来，
   写进这一条的能力表并存本地；拉不到就跳过，不报错。
2. **内置静态表**（随包发，`data/voice-providers.json`）：名称、默认基址、已知音色、已知语种、
   已知参数。**这张表是某一天的快照，必须带日期**——各家加音色的速度比我们发版快。
3. **用户手填**：能力表可编辑（收不收 `speed`、`ssml`、`pitch_curve`…）。

下面这些是**文档核实的部分**（2026-11-13）：

| 提供方 | 形状 | 音色 | 语种 | 特有参数 | 状态 |
|---|---|---|---|---|---|
| 系统 TTS | Android `TextToSpeech` | 随系统 | 随系统 | `rate` / `pitch` 两档 | API 已核对 |
| OpenAI | `POST /v1/audio/speech` | alloy, ash, ballad, coral, echo, fable, onyx, nova, sage, shimmer, verse | 跟随文本 | `speed` 0.25–4；`response_format` mp3/opus/aac/flac/wav/pcm；`gpt-4o-mini-tts` 收自然语言 `instructions`；**无 SSML** | 文档核实 |
| Gemini | `generateContent` 音频模态 | 30 个预置音色（Kore/Puck/Charon…） | 24 种语言 | 多说话人；风格走自然语言；输出裸 PCM，要自己封容器 | 文档核实（细节未实测） |
| MiniMax | `t2a_v2` | 系统音色 + 克隆音色 | 中/英/日/韩… | `emotion`、`speed`、`vol`、`pitch` | 待核实 |
| Qwen | `qwen-tts` / `qwen3-tts-flash` | Cherry / Serena / Ethan / Chelsie 等 | 中/英 + 多语 | 部分支持 `instruct`（自然语言风格） | 待核实 |
| Groq | PlayAI TTS | Arista / Atlas 等 | 英为主 | 走 OpenAI 兼容形状 | 待核实 |
| xAI | `/v1/audio/speech` | 图中填的是 `eve` | 待核实 | 走 OpenAI 兼容形状 | 待核实 |
| MiMo | 语音端点 | 待核实 | 待核实 | 待核实 | 待核实 |
| Fish Audio / StepFun / ElevenLabs / Azure | 各家自己的形状 | 各家自己的清单 | 各家 | 克隆、情绪、SSML 各不相同 | 待核实 |
| 本地 piper / sherpa-onnx | 本地进程 | 每个模型一套（如 `zh_CN-huayan-medium`，**61 MB**） | 模型决定 | 无 SSML，无音高曲线 | 模型已下，运行时未接 |

**DeepSeek 目前没有公开的 TTS/ASR 端点**（只有文本）。所以它不会出现在语音服务列表里——
摆一个填了也没用的条目就是假开关。ASR 那头同理：`system`（Android `SpeechRecognizer`）+
本地 `sherpa-onnx zipformer`（模型已在盘上）+ 各家（OpenAI `whisper-1`/`gpt-4o-transcribe`、
Gemini、Groq `whisper-large-v3` 等）。

---

## 3.5 真找了个 SVS 小模型来试（2026-11-13）

`LogiAI10/diffsinger-mobile-onnx`（HF，镜像可下）：`diffsinger_acoustic.onnx` **107 MB** +
`hifigan_vocoder.onnx` **59 MB**，MIT。两个加起来 166 MB，在 400 MB 预算里。

实测（`scripts/svs_probe.py`）：

```
acoustic  in : txt_tokens int64[1,N]  pitch_midi int64[1,N]  midi_dur f32[1,N]  is_slur int64[1,N]
          out: decoder_inp [1,T,256]  mel_out [1,T,80]
vocoder   in : mel_out [1,T,80]  f0 [1,T]      out: wav [1,S]
        词表上限：62（也就是 63 个 token）
```

形状是"每个音符一个 token + 该音符的帧数"，正是我们要的那种（不用自己做音素时长预测）。

**但它没带词典**：没有 `dsconfig.yaml`、没有 `phonemes.txt`，63 个 id 对应哪些音素不知道。
这不是"模型跑不起来"——它跑得起来，mel 出得来（`[1,1590,80]`）——而是**没有 token↔音素这张表，
就念不出字**。所以下一步不是调参，是拿到词表，三条路：

1. 找同一作者/同一转换工具的配置（HF 仓里没有，去作者的其他仓或 GitHub 上找）；
2. 换成 OpenUTAU 声库包（**那套格式一定带 dsconfig.yaml**），体积通常也在 200–300 MB；
3. 自己训（走不通：要数据、要算力）。

判据很硬：拿"啦啦啦"（`l a` 三个音）先试，ASR 能转回"啦啦啦"才算接对；
转不回来就说明 id 映射错了，改顺序重试。

与此同时，界面上那个「唱歌」开关已经摆上去了，但**现在是灰的**——
`svsEngine()` 找不到 acoustic + vocoder + phonemes.json 三样就不点亮。
按老规矩：灰的判据是"有没有东西可跑"，不是"功能写没写"。

## 3.6 真跑起来了一个 SVS（2026-11-14，英文声库）

工具链：`erz0r/warble`（MIT，DiffSinger + UTAU 两套引擎，CLI 全 JSON）。声库：
`geckl/cyber-singer` 仓库里带的 `EcklDaTiger`（英文，ARPAbet 音素表）。

在这台机器（aarch64 / PRoot）上实测：

```
warble sing -e "twin:C4:1 kle:C4:1 twin:G4:1 kle:G4:1 lit:A4:1 tle:A4:1 star:G4:2" \
  --bank EcklDaTiger --lang en -o t1.wav
→ 4.35 s / 44.1 kHz / 92% 有声帧 / 渲染 44 s（vocoder 21 s 占大头）
ASR 反听：「Twinkle, twinkle, let's last star!」——前两句全对，little 听岔了
```

体积（这个声库的真实账）：acoustic **327 MB** + linguistic 51 + dur 14 + pitch 60 +
variance 18 + vocoder 56 ≈ **526 MB**。

三条结论：

1. **400 MB 这条线对这类四段式声库不现实。** 最省的装法（acoustic + vocoder + dur +
   linguistic）也 448 MB。要么把预算调到 ~550 MB，要么换单说话人、无 pitch/variance 的三段式
   （这种一般 150–250 MB）。
2. **词表不是问题**——声库自带 `phonemes.txt` 与 `dsdict-*.yaml`，`warble probe` 能直接读出来。
   之前那个 172 MB 的 ONNX 之所以卡住，是因为它不是"声库包"，只是一对模型文件。
3. **中文还缺一个声库**。`dsdict-zh.yaml` 这一层（拼音→音素）是通用的，随便找得到；
   缺的是**用中文音素训过的 acoustic**。这类声库作者通常只在自己的发布页放 zip，不放 repo，
   所以下一轮要么按发布页找，要么由用户提供。

## 3.7 声库的装 / 选 / 删（已落地，2026-11-14）

用户定：体积放宽到 **0.7 GB**，中/日/英三种都要，声库像容器一样**可选下载**，还要能自己给地址装。

界面上：设置 → 工具与工作区 → 「语音」→ 语音服务。

- **装**：填名字 + zip 地址 +（可选）sha256 + 语言，跑一次下载 → 校验 → 解包。
  进度三段（下载字节数 / 解包条目数 / 校验），跟容器那一页同一套写法。
- **判据是包里的东西**：有 `dsconfig.yaml`（DiffSinger）或 `oto.ini`（UTAU）才算声库，
  否则整条回滚并说"这个 zip 里没有 dsconfig.yaml 也没有 oto.ini——不是声库"。
- **防穿越**：条目名里出现 `..` 或绝对路径**直接拒绝**，不是跳过继续；解包总量封 4 GB。
- **不覆盖**：同名目录已存在就报错，让人先删——静默覆盖会把一个能用的声库换成半个。
- **装完不自动信任**：先落 `.part`，校验过才改名；中途断掉不会留下"看着装好了"的目录。
- **选**：同一时间只有一个"当前用这一个"（`voice.svs.selected`），删掉它就把选中清空。
- **删**：只删这个声库的目录，已经渲染出来的音频不动。

为什么不内置声库：DiffSinger / UTAU 的声库授权普遍禁止再分发。所以内置的只能是**作者页面的链接**，
不能是文件本体。

**还没接的**：引擎运行时（Android 上的 onnxruntime + warble 那套音素/时长流程）。
所以「唱歌」那个开关现在还是灰的——装了声库也唱不了，界面在"状态"那段直说。
按老规矩，这个开关不提前点亮。

## 3.8 歌词 → 音素 → 音符（已落地，2026-11-14）

`crates/core/src/voice.rs`：这一步不依赖任何 ONNX 模型，所以先做它。

- **输入**：一段谱面文本（`bpm 96` / `lang zh|ja|en` / `一 C4 1` / `r 1`），音高认 `C4 F#3 Bb4` 或 midi 数字。
- **中文**：汉字查 `data/pinyin.json`（**6763 字**，GB2312 一级+二级，不带声调）
  → 拆声母/韵母：`亮` → `l` + `iang`，`闪` → `sh` + `an`，`儿` → `er`。
  也可以直接写拼音（`yi shan`），或者写 `[y i]` 这种显式音素。
- **日文**：假名表（平/片假名 100 项）→ 罗马字 → 辅音/元音：`さ` → `s a`，`きょう` → `k i y o u`，
  `っ` → `cl`，`ー` 延长前一个元音。
- **英文**：CMUdict（`data/cmudict.txt`，135k 词，去掉重音数字）→ `twinkle` → `t w ih ng k ah l`。
  查不到就报"CMUdict 里没有这个英文词"，不猜。
- **出口**：每个音符 `{lyric, midi, frames, phonemes}`——正是后面 DiffSinger 要的形状。
  帧数按时值算（`bpm 96` 一拍 = 625 ms = 125 帧 @5 ms）。

判据（`voice::tests`，8 条全绿）：中文/日文/英文各一条、未知汉字点名、未知英文词点名、
显式音素直通、谱面解析、缺 `lang` 报错、帧数随时值变。core **740**（+8）。

已知边界：

- 多音字按最常见的读法（`pypinyin` 的默认），要按词消歧得上下文，先不做；
- 日文汉字不转假名（那要另一本词典），现在要求写成假名或罗马字；
- 英文只认 CMUdict 里的词，生造词给显式音素；
- 时长现在是"平均分给音素"，真时长（`dsdur`）是运行时那一步的事。

## 3.9 TTS / ASR 两条列表（已落地，2026-11-14）

设置 → 工具与工作区 → 语音 → 语音服务，上面那两段：

- **各自单选**：同一段里只有一个 `✓`（`voice.tts.selected` / `voice.asr.selected`）。
- **条目**：名称、服务提供方（九家：OpenAI / Gemini / MiniMax / Qwen / xAI / Groq / 系统 TTS / 本地模型 / 自定义）、
  API 基址、模型、音色 ID、语言。密钥进 `Vault`（`vault/provider-voice-<id>.bin`），prefs 里只留 id。
- **音色那一格**：右边 `ˇ` 点开变 `^`，下面是这一家的已知音色，能点；**同时可以直接打字**。
  清单是快捷方式，不是白名单——账号后台多出来的音色不该等到我们发版才能用。
- **能力表**：每家带一份（`speed` / `pitch` / `style` / `instruct` / `ssml` / `format` …），
  编辑页底下直接写"这一条支持：…"。将来 `Speak` 的 schema 就按它裁。
- **不给假默认**：MiniMax / xAI 的音色清单没核实，就写"按后台填"，不编一串看着像真的 ID。

内置的默认值：

| 提供方 | 默认模型 | 已知音色 |
|---|---|---|
| OpenAI | `tts-1` | alloy / ash / ballad / coral / echo / fable / onyx / nova / sage / shimmer / verse |
| Gemini | `gemini-2.5-flash-preview-tts` | Kore / Puck / Charon / Fenrir / Leda / Orus / Zephyr / Aoede |
| MiniMax | `speech-02-hd` | 按后台填 |
| Qwen | `qwen-tts` | Cherry / Serena / Ethan / Chelsie |
| xAI | `grok-voice` | eve（未核实） |
| Groq | `playai-tts` | Arista-PlayAI / Atlas-PlayAI |

`Voice-Caps`（§3.11）与 `Speak`（§3.12）已落地。还没做的：`Transcribe`、以及 `Speak` 的
唱歌那一支（要 SVS 引擎）。
条目先能配、能选，是因为工具声明要按"选中的那一条"现生成——那一层依赖这个。

## 3.10 音频小工具（已落地，2026-11-14）

`crates/core/src/audio.rs`：给 TTS 产物做后处理的纯函数，不依赖任何库。

| 函数 | 做什么 | 判据 |
|---|---|---|
| `parse_wav` / `encode_wav` | 16-bit PCM WAV 读写 | 往返误差 < 0.001 |
| `trim` | 去首尾静音（阈值相对峰值，留余量） | 切掉静音、不切字头 |
| `normalize` | 峰值归一（可只降不升） | 峰值命中 ±0.01 |
| `fade` | 余弦淡入淡出 | 两端接近 0、中间 RMS 不变 |
| `stretch` | WSOLA 变速不变调 | 时长 ×rate、过零率变化 < 15% |
| `pitch` | 变调不变速（拉伸后重采样） | ±12 半音后频率翻倍（±25%） |
| `concat` / `mix` | 拼接（可插静音）/ 叠加 | 长度与采样率/声道一致性检查 |

只认 16-bit PCM：别的容器直接报"先转成 wav"，不猜。`concat` / `mix` 要求采样率与声道一致，
不一致就拒绝并说清楚——静默转换会把两条音轨错位。

core **748**（+8）。这些函数还没挂成工具：挂上去要等 `Speak` 那条链（HTTP）——
现在它们是"能测、能用、还没有入口"的一层，下一批跟 `Speak` 一起接。

## 3.11 `Voice-Caps`（已落地，2026-11-14）

只读、不联网、不碰密钥。模型要合成或识别之前先问它一次，参数用哪几格就以它的回答为准。

- **声明位**：`Capability::Voice`（默认关）。FFI 只在请求里带了 `voice` 时才开这一位，
  而 Kotlin 只在"语音总开关开着 + 至少有一条选中的（或唱歌真的能用）"时才带这一段。
  两头都满足才声明——配了东西却不让模型知道，等于没配。
- **形状**（`crates/core/src/voicecaps.rs`）：`{tts, asr, sing, note}`。`tts` / `asr` 是
  `{direction, name, provider, model, voice, language, supports, known_voices, known_languages, has_key}`，
  没配的那一条显式给 `null`——缺字段与"没有"是两件事。
- **`known_*` 是快捷清单，不是白名单**：账号后台多出来的音色照样能写进 `Speak`。
- **`has_key` 只说有没有**，密钥不出 `Vault`：core 收到的是布尔值。
- **`sing`**：`{ready, engine, bank, reason}`。判据与工具页那一行**同源**
  （`svsEngine()` + `VoiceBankRepository.installed`）——两处各写一套的话，
  界面能拨、模型却问不出，迟早对不上。只装了声库没有引擎时 `ready:false`，
  `reason` 直说"引擎运行时还没接"。
- **只装了声库不算"有东西可问"**：`ready:false` 时它不参与声明。一个只会回"唱不了"的
  工具占着声明位，模型会去试，用户会看到一串没结果的过程块。

判据：`voicecaps` 9 条（`to_json` 的形状 / 显式的 `null` / 解析点名到 `voice.tts.model` /
能力位关着时拒绝 / 没有配置时那句提示 / 注册表里不被声明），ffi 2 条（坏字段点名、
`voice` 缺席与 `null` 等价、能力位跟着条目走）。core **758**、ffi **69**。

## 3.12 `Speak`（已落地，2026-11-14）

一句话或一段脚本 → 一个 wav 文件。只接 **OpenAI 形状**（`POST /audio/speech`）：
`{model, input, voice, response_format, speed}`。别的形状明确回"还没接"，
并说清现在支持哪一种（`provider=openai`，或者把这一条按 `custom` 配一个兼容基址）。

- **只要 wav**：`response_format` 写死 `"wav"`。`trim` / `normalize` / `fade` 只认 16-bit PCM，
  mp3 进来只能原样落盘——那就成了"后处理看着有、其实没做"。
- **两种写法**：`text` 一句话；`script` 分段（每段自己的 `rate` 与 `pause_ms`）。
  分段是**每段一次请求**，然后按 `concat` 拼起来；最后一段之后的停顿不算（多一截静音没有意义）。
- **参数按能力表裁**：`rate` 只有在 `supports` 里有 `speed` 时才接受，否则点名报错。
  倍率 0.25–4，超出点名。
- **后处理**：归一（峰值 0.95，**允许抬升**——合成音只有一条声源，底噪本来就低）+ 两端 8 ms 淡入淡出。
- **落点**：`sessions/<id>/audio/<hash>.wav`。`hash` 由 provider / model / 文本 / 音色 / 倍率算，
  同样的输入落同一个名字。先写 `.part` 再改名，中途被杀不会留半截音频。删会话时整目录一起删。
- **密钥只往前走一步**：Kotlin 从 `Vault` 读出来、塞进这一轮的 `voice.tts.api_key`，
  core 只拿它拼 `Authorization`，不进结果、不进日志、不进库。`Voice-Caps` 的回包里没有它。
- **界面上能听**：工具详情里认回包的 `path`（`.wav` / `.mp3` 且文件在），就地给一个播放键；
  关掉弹窗就释放播放器——不然声音还在响，用户找不到是谁在放。

判据：`speak` 9 条（一句落地 + Authorization + 回包形状、分段两次请求与停顿时长、
三家不支持的 provider 各自点名、缺密钥 / 缺基址、两种写法同时给、`rate` 不被支持、
回包不是 WAV 时带开场字节、能力位关着不声明、密钥不出 `Voice-Caps`）。
core **767**、ffi **69**。真机 **V140**。

## 4. 复杂编排：一个工具，两种写法

用户要的"写好脚本再调用"，落成一个 `Speak` 的三选一入参：

```jsonc
// A. 一句话
{"text": "今天有点累", "rate": 0.95}

// B. 分段脚本（每段自己的语速/音调/停顿）
{"script": [
  {"text": "这一句", "rate": 0.9, "pitch": 1.05, "pause_ms": 180},
  {"text": "快一点", "rate": 1.4},
  {"text": "最后拖长", "rate": 0.7, "pause_ms": 600}
]}

// C. 唱歌（逐音符）
{"sing": {
  "tempo_bpm": 96,
  "notes": [
    {"midi": 60, "beats": 1, "lyric": "一"},
    {"midi": 60, "beats": 1, "lyric": "闪"},
    {"midi": 67, "beats": 2, "lyric": "亮", "vibrato": 0.2}
  ]
}}
```

- **C 那条是承重的那条**：`midi`（或 `hz`）+ `beats` + `lyric`，`lyric` 里一个汉字 = 一个音节。
  支持的引擎多了 `phoneme`（`i1`、`zh` 这种）与 `velocity`（力度）。
- 渲染交给 §0 那条流水线，**跟 provider 无关**；原生支持音高曲线的引擎直接走它的接口。
- 一次渲染写一个文件，`duration_ms` 与 `used`（实际生效的参数）一起回给模型。

## 5. 内置音频工具（绑给 TTS 用，也开放）

用户提的"简单音频调整工具"，做成一个小集合，既给流水线内部用，也能被工具直接调：

| 工具 | 做什么 | 判据 |
|---|---|---|
| `audio-trim` | 去首尾静音（阈值可给） | 输出比输入短、两端 RMS 低于阈值 |
| `audio-normalize` | 峰值/响度归一 | 峰值到 -1 dBFS ±0.5 |
| `audio-fade` | 淡入淡出 | 两端包络单调 |
| `audio-resample` | 重采样/改容器（wav/mp3/flac） | 时长一致（±1 frame） |
| `audio-pitch` | 变调不变速（同一套 WSOLA+重采样） | 基频比例 = 目标比例 ±10 音分 |
| `audio-tempo` | 变速不变调 | 时长比例命中，基频不变 ±10 音分 |
| `audio-mix` | 两条叠（配乐 + 人声） | 相加不削顶（自动 -3 dB 余量） |
| `audio-concat` | 按顺序拼，可插静音 | 总时长 = 各段之和 |

这些都落在 core 的纯函数上（跟 `shellguard` 一个路子），不在 Kotlin 里算——
算完的字节落 `sessions/<id>/audio/`，界面能指出来（铁律②）。

## 6. 本地模型：装、用、卸

- 形态：一个「本地语音」条目，模型从设置页装（进度、体积、校验和），装完在条目里选音色。
- 体积目标 **≤400 MB**（用户定的线）：piper 中文 `medium` 一档 61 MB，够用；
  真·唱歌模型（DiffSinger/OpenVINO 那一类）另算一档，**单独装、单独卸**。
- 只装一个：同时装两个模型没有意义（一次只用选中的那个），磁盘却是双份。
- 卸的时候：删模型目录 + 把条目回落 `system`，**不删已经渲染出来的音频**（那是用户的东西）。

## 7. 判据

1. 工具面板关掉的工具**不出现在这一轮的工具表里**（不是声明了再拒绝）。
2. TTS / ASR 各自只有一个对号；删掉选中的回落，界面写明。
3. 音色字段点 `ˇ` 出清单、点清单能填进去、也能手打一个不在清单里的值。
4. 换 provider → `Voice-Caps` 与 `Speak` 的 schema 同一拍变。
5. 传了不支持的参数 → 报错点名；成功时 `used` 里能看出哪些参数真的生效了。
6. `sing` 渲染出来的每个音，基频与谱面偏差 ≤ 50 音分（原型实测最大 42）。
7. 本地引擎断网可用；卸掉模型之后条目回落、下一次调用不报"文件不存在"。
8. 音频产物在界面上指得出来（路径 + 时长 + 用的哪一条）。

## 8. 分步

| 步 | 内容 | 依赖 |
|---|---|---|
| b0 | 渲染流水线原型（已做，`scripts/tts_sing_prototype.py`） | — |
| b1 | 工具面板开关（含 TTS/ASR 两项）+ core 的"按开关裁声明" | 无 |
| b2 | 语音服务两条列表 + 编辑页（音色 ˇ 控件）+ 单选落库 | b1 |
| b3 | `Speak`（A/B）+ 系统 TTS 与 OpenAI 形状两个适配器 | b2 |
| b4 | `sing`（C）+ 音频工具集 + 内置静态表 | b0/b3 |
| b5 | 本地模型安装/卸载（piper 61 MB 起步） | b3 |
| b6 | ASR：系统 + 本地 zipformer + whisper 形状 | b1 |

## 9. 不做

- 实时双工语音对话（另一条线）。
- 声音克隆（要样本、要授权）。
- 把别人的歌声当音色（那是版权问题，不是技术问题）。
- 模型内建的"语音回复"（provider 自己的事，不走这条）。
