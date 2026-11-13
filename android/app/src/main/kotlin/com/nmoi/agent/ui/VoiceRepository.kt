package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 语音服务条目（0.17b）：TTS / ASR 两条列表，各自单选。
 *
 * 为什么不按"提供方"写死代码：同一个基址上不同模型的能力差很远（`tts-1` 不收
 * `instructions`，`gpt-4o-mini-tts` 收）。所以条目里带一份**能力表**，
 * 工具声明（`Speak` 的参数）按它裁——不支持的那一格根本不出现。
 *
 * 密钥进 `Vault`（`vault/provider-voice-<id>.bin`），prefs 里只留 `key_id`。
 */
internal object VoiceRepository {
    private const val PREFS = "nmoi.local"
    private const val PREFIX = "voice.entry."
    private const val KEY_ENABLED = "voice.enabled"

    enum class Direction(val wire: String, val label: String) {
        Tts("tts", "文字转语音"),
        Asr("asr", "语音识别"),
    }

    /** 一家提供方的内置默认值。`voices` 是已知清单，**不是白名单**——界面上照样能手填。 */
    data class Preset(
        val id: String,
        val label: String,
        val baseUrl: String,
        val model: String,
        val voices: List<String>,
        val languages: List<String>,
        val caps: List<String>,
        val note: String = "",
    )

    data class Entry(
        val id: String,
        val direction: Direction,
        val provider: String,
        val name: String,
        val baseUrl: String,
        val model: String,
        val voice: String,
        val language: String,
        val hasKey: Boolean,
    )

    val presets: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "tts-1",
            listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "verse"),
            listOf("auto", "en", "zh", "ja", "ko", "de", "fr", "es"),
            listOf("speed", "format", "instructions")),
        Preset("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash-preview-tts",
            listOf("Kore", "Puck", "Charon", "Fenrir", "Leda", "Orus", "Zephyr", "Aoede"),
            listOf("auto", "en", "zh", "ja", "ko", "de", "fr", "es", "pt", "it"),
            listOf("style", "instruct", "multi_speaker"), "音色与语种以官方文档为准，未逐条实测"),
        Preset("minimax", "MiniMax", "https://api.minimax.chat/v1", "speech-02-hd",
            emptyList(),
            listOf("auto", "zh", "en", "ja", "ko"),
            listOf("speed", "pitch", "volume", "emotion"), "音色 ID 请按账号后台填"),
        Preset("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-tts",
            listOf("Cherry", "Serena", "Ethan", "Chelsie"),
            listOf("auto", "zh", "en"),
            listOf("speed", "style", "instruct")),
        Preset("xai", "xAI", "https://api.x.ai/v1", "grok-voice",
            listOf("eve"),
            listOf("auto", "en"),
            listOf("speed"), "音色清单未核实，按后台填"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "playai-tts",
            listOf("Arista-PlayAI", "Atlas-PlayAI"),
            listOf("auto", "en"),
            listOf("speed")),
        Preset("system", "系统 TTS", "", "", listOf("跟随系统"), listOf("auto"), listOf("speed", "pitch")),
        Preset("local", "本地模型", "", "sherpa-onnx", emptyList(), listOf("auto", "zh", "en"), listOf("speed", "pitch")),
        Preset("custom", "自定义", "", "", emptyList(), listOf("auto"), emptyList()),
    )

    fun preset(id: String): Preset = presets.firstOrNull { it.id == id } ?: presets.last()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(context: Context, direction: Direction): List<Entry> {
        val raw = prefs(context).getString("$PREFIX${direction.wire}", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                add(
                    Entry(
                        id = id,
                        direction = direction,
                        provider = row.optString("provider").ifBlank { "custom" },
                        name = row.optString("name").ifBlank { "未命名" },
                        baseUrl = row.optString("base_url"),
                        model = row.optString("model"),
                        voice = row.optString("voice"),
                        language = row.optString("language").ifBlank { "auto" },
                        hasKey = Vault.has(context, keyId(id)),
                    ),
                )
            }
        }
    }

    fun save(context: Context, entry: Entry, key: String?) {
        val existing = entries(context, entry.direction)
        val rows = JSONArray()
        var replaced = false
        for (item in existing) {
            val row = toJson(if (item.id == entry.id) entry else item)
            if (item.id == entry.id) replaced = true
            rows.put(row)
        }
        if (!replaced) rows.put(toJson(entry))
        prefs(context).edit().putString("$PREFIX${entry.direction.wire}", rows.toString()).apply()
        if (!key.isNullOrBlank()) {
            Vault.save(context, keyId(entry.id), key).onFailure {
                NLog.w("Voice", "存密钥失败：${it.message}")
            }
        }
        if (selected(context, entry.direction).isBlank()) select(context, entry.direction, entry.id)
    }

    fun remove(context: Context, entry: Entry) {
        val rows = JSONArray()
        for (item in entries(context, entry.direction)) {
            if (item.id == entry.id) continue
            rows.put(toJson(item))
        }
        prefs(context).edit().putString("$PREFIX${entry.direction.wire}", rows.toString()).apply()
        runCatching { java.io.File(context.filesDir, "vault/provider-${keyId(entry.id)}.bin").delete() }
        if (selected(context, entry.direction) == entry.id) select(context, entry.direction, "")
    }

    fun selected(context: Context, direction: Direction): String =
        prefs(context).getString("voice.${direction.wire}.selected", "").orEmpty()

    fun select(context: Context, direction: Direction, id: String) {
        prefs(context).edit().putString("voice.${direction.wire}.selected", id).apply()
    }

    /** 当前那一条（没有就是 null）。工具与 `Voice-Caps` 都读它。 */
    fun active(context: Context, direction: Direction): Entry? {
        val id = selected(context, direction)
        return entries(context, direction).firstOrNull { it.id == id }
    }

    fun newId(direction: Direction): String =
        "v${direction.wire}${System.currentTimeMillis().toString(36)}"

    private fun keyId(id: String) = "voice-$id"

    private fun toJson(entry: Entry) = JSONObject()
        .put("id", entry.id)
        .put("provider", entry.provider)
        .put("name", entry.name)
        .put("base_url", entry.baseUrl)
        .put("model", entry.model)
        .put("voice", entry.voice)
        .put("language", entry.language)
}
