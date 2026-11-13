# 语音服务（0.17b 设计稿，未动工）

用户 2026-11-13 提，附了一张别家客户端的「语音服务」页：两段列表，一段「文字转语音」、
一段「语音识别」，每段右上角一个「+」，条目右侧是对号——**对号 = 当前用的那一个**，
"AI 调用相关工具的时候就会调用哪个"。同日追加两条要求：

- 「具体要看各家 API 的支持情况，都做好兼容」；
- 「调用工具的时候 AI 要能判断当前选择的 API 的支持情况」；
- 「最好支持精细的调校参数，比如那种全是自定义语速音调的脚本，然后可以模拟唱歌那种」。

这一份是计划。**没有实现**，别当已落地看。

---

## 1. 形状：两条列表，各自单选

- 两段互相独立：TTS 选中的那个只影响 `Speak`，ASR 选中的只影响 `Transcribe`。
- 同一段里**只能有一个对号**。不是"默认项"，是唯一项——两个都能用的时候，
  模型没法知道该用哪个，用户也没法预期。
- 删掉当前选中的那一个 → 回落到 `system`（TTS）或 `off`（ASR），界面把回落写出来。
  静默换引擎比没有引擎更坏：用户以为还是他挑的那个声音。
- 「+」新建条目；条目右侧两个入口：参数（齿轮）/ 试听（喇叭）/ 删除。

## 2. 条目 = 连接 + 模型 + 能力

一条记录至少这几格：

```
id, kind, label, base_url, key_id, model, voice, caps, extras
```

- `kind`：`system` | `openai` | `gemini` | `grok` | `xai` | `aihubmix` | `local` | `custom`
- `key_id` 指向 Vault 里的密钥，**settings 里不存明文**（跟 provider 一条规矩）。
- `caps` 是这一条的**能力表**（见 §3），由 kind + model 推出一个默认值，用户可以改。
  为什么不按 kind 算死：同一个 `base_url` 上，`tts-1` 和 `gpt-4o-mini-tts` 的能力就不一样，
  按 kind 算会把"这个模型没有的参数"声明出去。
- `extras` 是供应商私有参数的透传袋，键必须在该条目的 caps 白名单里。

## 3. 能力矩阵：工具 schema 由它生成

**这一条是整套设计的承重点**：`Speak` / `Transcribe` 的 schema **按当前选中的条目现生成**
（D50 那三层里最里面那层）。不支持 `pitch` 的引擎，工具声明里就没有 `pitch`——
模型看得到"没有这一格"，比"有这一格、传了被静默丢掉"好一百倍。

TTS 的 caps：

| 键 | 含义 |
|---|---|
| `voices[]` | 可选声音清单（空 = 自由字符串） |
| `rate` / `pitch` / `volume` | 粗档，是否支持、范围各是多少 |
| `style` / `emotion` / `instruct` | 自然语言或枚举的风格控制 |
| `ssml` | 收不收 SSML（`<break>` `<prosody>`） |
| `script` | 收不收逐段脚本（§4） |
| `pitch_curve` | 收不收音高曲线（§5，唱歌那条） |
| `format[]` / `sample_rate[]` | 输出容器与采样率 |
| `stream` | 能不能边生成边播 |
| `language[]` | 语言清单 |

ASR 的 caps：`stream`、`timestamps`、`diarization`、`language_hint`、`hotwords`、
`format[]`、`max_bytes`、`max_seconds`。

### 3.1 各家（按 2026-11-13 的公开文档抄，**未逐家实测**）

| 条目 | 形状 | 已知差异 | 状态 |
|---|---|---|---|
| `system` | Android `TextToSpeech` | 离线可用；`setSpeechRate` / `setPitch` 两档；声音随系统；没有 SSML | 已核对 API |
| `openai` | `POST /v1/audio/speech` | 声音是固定清单；`speed` 0.25–4；`response_format` 多档；`gpt-4o-mini-tts` 另收自然语言 `instructions`；**没有 SSML** | 文档核实 |
| `gemini` | `generateContent` + 音频模态 | 多说话人；风格走自然语言；输出 PCM，要自己封容器 | 文档核实（细节未实测） |
| `grok` / `xai` | 各家自己的语音端点 | 声音与参数集都不一样，必须按条目配 caps，不能套 OpenAI 那套 | **未核实** |
| `aihubmix` | 聚合 | 能力由**模型名**决定，不由条目决定；caps 按模型名匹配 | 未核实 |
| `local` (TTS) | sherpa-onnx / piper | 离线；音高曲线看模型（VITS 系列没有） | 未核实 |
| `local` (ASR) | sherpa-onnx zipformer | 中英 mobile 已下载；`timestamps` 有，`diarization` 没有 | 模型已在盘上 |

这张表**必须带日期**：语音这一块各家改得快，一条 2025 年的说法放在这里就是弱证据。
新增条目时先在设置页点「探测能力」跑一次，把结果写进 caps，别靠猜。

## 4. 精细参数

- 通用：`rate` / `pitch` / `volume` / `format` / `sample_rate`。
- **逐段脚本**（用户说的"全是自定义语速音调的脚本"）：

```json
{"voice": "nova", "script": [
  {"text": "这一句", "rate": 0.9, "pitch": 1.05, "pause_ms": 180},
  {"text": "快一点", "rate": 1.4},
  {"text": "最后拖长", "rate": 0.7, "pause_ms": 600}
]}
```

  引擎不支持逐段控制时，core **自己切段多次请求再拼**（caps.script = `segmented`），
  拼的时候按 `pause_ms` 插静音；caps.script = `none` 的引擎直接拒绝，点名说清。

- `extras`：只放 caps 白名单里的键；白名单外的键**报错**，附上"这条支持哪些"。

## 5. 唱歌

唱歌不是"pitch 调高一点"，是**逐音符的音高曲线**。所以单开一个工具：

```json
{"voice": "local-vits", "notes": [
  {"midi": 60, "start_ms": 0,    "dur_ms": 500, "lyric": "一"},
  {"midi": 62, "start_ms": 500,  "dur_ms": 500, "lyric": "闪"},
  {"midi": 64, "start_ms": 1000, "dur_ms": 500, "lyric": "一", "phoneme": "i1"}
]}
```

- 只有 `caps.pitch_curve` 的引擎接（本地 DiffSinger / OpenUtau 这一类；云端极少）。
- 不接的引擎：**明确拒绝**，并说清"当前 TTS 只有 rate/pitch 两个粗档，做不了逐音符"，
  再给一条能走的路（先分段念，或者换成本地引擎）。让它跑出一段走调的音频是最坏的结果。
- 判据：给 8 个音符的《小星星》，把输出反解成音高序列，跟谱子逐个对上（本地引擎）；
  对不上就是没做对，不是"听着差不多"。

## 6. 存哪

- 条目与选中的那一个：settings 文档 `voice`（D86 的四类 scope 那套），
  键 `voice.tts.<id>` / `voice.asr.<id>` / `voice.tts.selected` / `voice.asr.selected`。
- 密钥：Vault，settings 里只留 `key_id`。
- 音频产物：`sessions/<id>/audio/<hash>.<fmt>`，与 archive 同一条路子——
  **能在界面上指出来**（铁律②），不能只是"存在某个地方"。
- 试听：本地播一次，不落库、不进上下文。

## 7. 工具

| 工具 | 入参 | 出参 |
|---|---|---|
| `Speak` | `text` 或 `script`，加 §4 的那些参数 | `audio_path`、`duration_ms`、`provider`、`used`（实际生效的参数） |
| `Transcribe` | `path` 或 `audio`，加语言提示 | `text`、`segments[]`、`language` |
| `Voice-Caps` | 无（只读） | 当前选中的 TTS / ASR 及其 caps |

- **`Voice-Caps` 就是"AI 判断当前 API 支持什么"的落点**：模型先读一次，再决定怎么调；
  不必让它去猜、也不必让它从错误里学。
- `Speak` 失败时结果里**附一份 caps**（跟 `Voice-Caps` 同一份），模型下一轮就能改对。
- `used` 那格是给用户看的：调了 `pitch` 但引擎其实忽略了，界面要能看出来。

## 8. 判据（做的时候照着测）

1. 单选唯一：两条同时被打勾这件事在数据层就不可能（只存一个 `selected`）。
2. 删掉选中的 → 回落，并且界面写清落到哪。
3. 换 provider → `Voice-Caps` 与 `Speak` 的 schema 同一拍变，不用重开应用。
4. 传了不支持的参数 → 报错点名（"当前 TTS 不支持 pitch；支持 rate / style"），不静默丢。
5. 本地 TTS / 本地 ASR 断网可用。
6. 唱歌脚本打到不支持音高曲线的引擎 → 明确拒绝并给替代，不产生音频。
7. 失败分三种写法：网络断 / 401 密钥 / 声音不存在（点名哪一个声音）。
8. 生成的音频在界面上指得出来（路径 + 时长 + 用的哪一条）。

## 9. 不做

- 实时双工语音对话（另一条线，等这一套稳了再谈）。
- 声音克隆（要样本、要授权，先不碰）。
- 模型内建的"语音回复"（那是 provider 自己的事，不走这条）。
- 把 TTS 塞进聊天流自动念（先手动点；自动念是习惯问题，不是能力问题）。
