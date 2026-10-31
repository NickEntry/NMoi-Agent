package com.nmoi.agent.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息区的数据模型：**照 `nmoi_storage_load_units_json` 的形状一对一映射**，不加解释。
 *
 * 两条刻意的界限：
 * - `kind` / `source` 是 core 的稳定词（`turn`、`tool_pair`、`model_thinking`、
 *   `tool_call`、`tool_result`、`broken_block` …），Kotlin 侧只照词渲染，
 *   **不在这里再发明"什么算思考"**——两套判断迟早会分叉。
 * - 不做富文本解析：`content` 原样显示。等接上 Markdown 渲染再说，别现在猜格式。
 */
internal data class ChatMessage(
    val id: String,
    val seq: Long,
    val role: String,
    val source: String,
    val content: String,
    val tokens: Long,
    val createdAt: Long,
    val incomplete: Boolean,
    val locked: Boolean,
    val toolCalls: Int,
    val toolCallId: String?,
    /** 版本（D137）：总共几版。<= 1 时界面不显示切换控件。 */
    val versionCount: Int = 0,
    /** 当前第几版（1 起）。 */
    val versionActive: Int = 0,
    /** 当前这一版显示的是哪一组：`raw` / `edited`。 */
    val versionView: String = "raw",
    /** 这一版被工具/编辑改过没有——决定那个 `n/n` 的颜色（白 / 蓝 / 红）。 */
    val versionEdited: Boolean = false,
    /**
     * 思考块（D158）。**以前只存不显示**：`reasoning` 列一直在库里，
     * `load_units` 不带出来，界面上就永远看不见——"存了但看不见"正是他反感的那种。
     */
    val reasoning: String? = null,
    /** 这条消息声明的工具调用（名字 + 参数原文）。增强编辑要能在界面上指出改的是哪一块。 */
    val toolArgs: List<ToolCallArg> = emptyList(),
) {
    val isThinking: Boolean get() = source == "model_thinking"
    val isToolCall: Boolean get() = source == "tool_call"
    val isToolResult: Boolean get() = source == "tool_result"
    val isBrokenBlock: Boolean get() = source == "broken_block"
    val isUser: Boolean get() = role == "user"

    /**
     * 这一条能不能当「重试」的目标（0.15h）。
     *
     * 用户消息可以（重发这一句）；助手那边只有**正文**可以——
     * 思考块 / 工具调用 / 工具结果都是"一轮里的零件"，单独重试它们没有意义，
     * 而且会把新版本落到一条不该有版本的记录上。
     */
    val canRetry: Boolean
        get() = isUser || (!isThinking && !isToolCall && !isToolResult && !isBrokenBlock)
}

/** 一条工具调用（D158）：名字决定怎么解释它，`call_id` 决定它跟哪条结果配对。 */
internal data class ToolCallArg(
    val callId: String,
    val name: String,
    val arguments: String,
)

/**
 * 读一个字符串字段。
 *
 * **JSON 的 null 会被 `optString` 变成字面量 `"null"`**（Android 的 org.json 走
 * `JSON.toString(NULL)`，结果就是这四个字母）。界面上真的会把它显示出来——
 * 思考块里那个 `null` 就是这么来的，不是模型说的。所以字符串字段一律走这里。
 */
private fun JSONObject.text(key: String, fallback: String = ""): String {
    if (isNull(key)) return fallback
    val raw = optString(key, fallback)
    return if (raw == "null") fallback else raw
}

private fun parseToolArgs(array: JSONArray?): List<ToolCallArg> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            add(
                ToolCallArg(
                    callId = row.text("call_id"),
                    name = row.text("name"),
                    arguments = row.text("arguments"),
                ),
            )
        }
    }
}

/** 断块（P4.1 / D67）的声明行：由 core 给，不由 UI 拼 `ID+块类+原因`。 */internal data class BrokenBlock(
    val id: String,
    val kind: String,
    val reason: String,
    val declaration: String,
)

internal data class ChatUnit(
    val id: String,
    val kind: String,
    val closed: Boolean,
    val createdAt: Long,
    val messages: List<ChatMessage>,
    val broken: BrokenBlock?,
) {
    val isToolPair: Boolean get() = kind == "tool_pair"
}

internal data class ChatPage(
    val sessionId: String,
    val units: List<ChatUnit>,
    /** 库里的总数：`units.size < total` 时就还有更早的没读（首屏只取最后 N 个）。 */
    val total: Int,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = units.isEmpty()
}

internal fun parseUnits(envelope: JSONObject): ChatPage {
    val items = envelope.optJSONArray("items") ?: JSONArray()
    val units = buildList {
        for (index in 0 until items.length()) {
            val unit = items.optJSONObject(index) ?: continue
            add(
                ChatUnit(
                    id = unit.text("id"),
                    kind = unit.text("kind", "turn"),
                    closed = unit.optBoolean("closed", true),
                    createdAt = unit.optLong("created_at", 0L),
                    messages = parseMessages(unit.optJSONArray("messages")),
                    broken = unit.optJSONObject("broken")?.let { block ->
                        BrokenBlock(
                            id = block.text("id"),
                            kind = block.text("kind"),
                            reason = block.text("reason"),
                            declaration = block.text("declaration", "这一段当时断了"),
                        )
                    },
                ),
            )
        }
    }
    return ChatPage(
        sessionId = envelope.text("session"),
        units = units,
        total = envelope.optInt("units_total", units.size),
        truncated = envelope.optBoolean("truncated", false),
    )
}

private fun parseMessages(array: JSONArray?): List<ChatMessage> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val message = array.optJSONObject(index) ?: continue
            add(
                ChatMessage(
                    id = message.text("id"),
                    seq = message.optLong("seq", 0L),
                    role = message.text("role", "assistant"),
                    source = message.text("source", "history"),
                    content = message.text("content"),
                    tokens = message.optLong("tokens", 0L),
                    createdAt = message.optLong("created_at", 0L),
                    incomplete = message.optBoolean("incomplete", false),
                    locked = message.optBoolean("locked", false),
                    toolCalls = message.optInt("tool_calls", 0),
                    toolCallId = message.text("tool_call_id").ifEmpty { null },
                    versionCount = message.optInt("version_count", 0),
                    versionActive = message.optInt("version_active", 0),
                    versionView = message.text("version_view", "raw"),
                    versionEdited = message.optBoolean("version_edited", false),
                    reasoning = message.text("reasoning").ifBlank { null },
                    toolArgs = parseToolArgs(message.optJSONArray("tool_call_args")),
                ),
            )
        }
    }
}
