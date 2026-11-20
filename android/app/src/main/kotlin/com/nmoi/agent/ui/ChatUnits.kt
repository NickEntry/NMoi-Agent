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
    /**
     * 图片附件（D208）：**只有元数据**（id / mime / 大小），字节按需再取。
     * 一次列 80 条消息时把几十 MB base64 拖过来，滚动会直接卡死。
     */
    val images: List<ChatImage> = emptyList(),
    /** 文件附件（D337）：芯片上写名字与大小，内容按需由模型读。 */
    val files: List<ChatFile> = emptyList(),
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

/**
 * 一次工具调用（D158 / D258）：参数与结果在**同一块**里。
 *
 * 以前结果在另一条 `role=tool` 的消息上，按 `call_id` 配对；折行之后结果就是
 * 这一块的 `output`，界面上那一行也只读这一份——不再有两处各拿一份的情况。
 */
internal data class ToolCallArg(
    val callId: String,
    val part: String,
    val name: String,
    val arguments: String,
    val output: String,
    val status: String,
)

/**
 * 读一个字符串字段。
 *
 * **JSON 的 null 会被 `optString` 变成字面量 `"null"`**（Android 的 org.json 走
 * `JSON.toString(NULL)`，结果就是这四个字母）。界面上真的会把它显示出来——
 * 思考块里那个 `null` 就是这么来的，不是模型说的。所以字符串字段一律走这里。
 */
internal fun JSONObject.text(key: String, fallback: String = ""): String {
    if (isNull(key)) return fallback
    val raw = optString(key, fallback)
    return if (raw == "null") fallback else raw
}

private fun parseImages(array: JSONArray?): List<ChatImage> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.text("id")
            if (id.isEmpty()) continue
            add(
                ChatImage(
                    id = id,
                    mime = row.text("mime", "image/jpeg"),
                    size = row.optLong("size", 0L),
                ),
            )
        }
    }
}

private fun parseFiles(array: JSONArray?): List<ChatFile> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.text("id")
            if (id.isEmpty()) continue
            add(
                ChatFile(
                    id = id,
                    name = row.text("name").ifBlank { "文件" },
                    mime = row.text("mime"),
                    size = row.optLong("size", 0L),
                ),
            )
        }
    }
}

private fun parseToolArgs(array: JSONArray?): List<ToolCallArg> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            add(
                ToolCallArg(
                    callId = row.text("call_id"),
                    part = row.text("part"),
                    name = row.text("name"),
                    arguments = row.text("arguments"),
                    output = row.text("output"),
                    status = row.text("status", "ok"),
                ),
            )
        }
    }
}

/** 一张附在消息上的图片（D208）：元数据随消息来，字节用 `id` 单独取。 */
internal data class ChatImage(
    val id: String,
    val mime: String,
    val size: Long,
)

/** 一份附在消息上的文件（D337）：只给名字 / 类型 / 大小，内容在会话 `chat/` 里。 */
internal data class ChatFile(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
)

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
    /**
     * 这一条在压缩线上（D324）：仍然在库里、仍然能编辑，只是**不进请求**了。
     *
     * 界面必须标出来——"已经压走的东西还在列表里"如果不说，用户会以为模型还看得见它。
     */
    val compacted: Boolean = false,
) {
    val isToolPair: Boolean get() = kind == "tool_pair"
}

internal data class ChatPage(
    val sessionId: String,
    val units: List<ChatUnit>,
    /** 库里的总数：`units.size < total` 时就还有更早的没读（首屏只取最后 N 个）。 */
    val total: Int,
    val truncated: Boolean,
    /** 线上那段的摘要正文（D324）。没压过、或取不回来时是 null——不假装还在。 */
    val summary: String? = null,
    /** 生效的那条线的 id。删它 = 还原（原文一条都没删）。 */
    val dividerId: String? = null,
    /** 线上有几个单元：界面写"已移走 N 条"直接用它，不自己数。 */
    val covered: Int = 0,
    /** 摘要本身占多少 token：它每轮都发，算占用率时必须加进去（否则压完还显示满格）。 */
    val summaryTokens: Int = 0,
    /** 往上还有更早的没读（D355）。分页游标在 [nextBefore]。 */
    val hasMore: Boolean = false,
    /** 下一次往上的游标：上一页最老那个单元的首条 `seq`。`null` = 到头了。 */
    val nextBefore: Long? = null,
) {
    val isEmpty: Boolean get() = units.isEmpty()

    /** 压过没有：判据只有"有没有生效的线"，不看 `covered` 之类的派生值。 */
    val compacted: Boolean get() = dividerId != null
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
                    compacted = unit.optBoolean("compacted", false),
                ),
            )
        }
    }
    val divider = envelope.optJSONObject("divider")
    return ChatPage(
        sessionId = envelope.text("session"),
        units = units,
        total = envelope.optInt("units_total", units.size),
        truncated = envelope.optBoolean("truncated", false),
        // `has("summary")` 才算问过：原生那边给 null 是"取不到"，不是"空摘要"。
        summary = if (envelope.has("summary") && !envelope.isNull("summary")) {
            envelope.optString("summary", "")
        } else {
            null
        },
        dividerId = divider?.text("id"),
        covered = divider?.optInt("covered", 0) ?: 0,
        summaryTokens = divider?.optInt("summary_tokens", 0) ?: 0,
        // `has_more` 是库算的；老接口没有这个键时退回 `truncated`（"上面还有"）。
        hasMore = if (envelope.has("has_more")) {
            envelope.optBoolean("has_more", false)
        } else {
            envelope.optBoolean("truncated", false)
        },
        nextBefore = if (envelope.has("next_before") && !envelope.isNull("next_before")) {
            envelope.optLong("next_before", 0L).takeIf { it > 0L }
        } else {
            null
        },
    )
}

/** 这个单元的第一条 `seq`（分页排序、找空档都用它）。 */
internal fun firstSeqOf(unit: ChatUnit): Long = unit.messages.firstOrNull()?.seq ?: 0L

/**
 * 把"往上翻出来的老一段"和"最新那一页"接起来（D355）。
 *
 * 同 id 的单元以**新的那一份**为准：编辑过的那条内容在后来的那一份里，
 * 拿旧的会把用户刚改的字又变回去。顺序按首条 `seq` 升序——两段各自都是升序，
 * 但接缝处不一定谁先谁后（中间来过新消息的话，最新一页可能整体后移了）。
 *
 * **这里不负责填空档**：中间真漏了一段（最新的那一页整体挪走了几条），
 * 由调用方看 [gapBefore] 再补一次，补不上就留着顶部那行提示——宁可说"还有没读的"，
 * 也不要把两段拼在一起假装连续。
 */
internal fun mergeUnits(older: List<ChatUnit>, newest: List<ChatUnit>): List<ChatUnit> {
    if (older.isEmpty()) return newest
    val byId = LinkedHashMap<String, ChatUnit>(older.size + newest.size)
    for (unit in older) byId[unit.id] = unit
    for (unit in newest) byId[unit.id] = unit
    return byId.values.sortedBy(::firstSeqOf)
}

/**
 * 接缝处有没有空档（D355）：`older` 的末尾与 `newest` 的开头之间缺了 seq。
 *
 * 什么时候会有：一轮新消息落库后，最新那一页整体往后挪了几条，原来在窗口头部的
 * 那几条被挤出去了——它们既不在新的一页里，也没进 `older`。
 * 判据是**连续性**，不是"两边数量对不对"：数量对上也可能中间断了。
 *
 * 真断了就补一页（`before = newest 第一条的 seq`），补不上就留顶部那行提示。
 * 绝不允许两段拼在一起假装连续——那等于把库里的消息藏起来（铁律②）。
 */
internal fun hasGap(older: List<ChatUnit>, newest: List<ChatUnit>): Boolean {
    if (older.isEmpty() || newest.isEmpty()) return false
    val olderLast = older.maxOf(::firstSeqOf)
    val newestFirst = firstSeqOf(newest.first())
    return newestFirst > olderLast + 1
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
                    versionCount = message.optInt("version_count", 0),
                    versionActive = message.optInt("version_active", 0),
                    versionView = message.text("version_view", "raw"),
                    versionEdited = message.optBoolean("version_edited", false),
                    reasoning = message.text("reasoning").ifBlank { null },
                    toolArgs = parseToolArgs(message.optJSONArray("tool_call_args")),
                    images = parseImages(message.optJSONArray("images")),
                    files = parseFiles(message.optJSONArray("files")),
                ),
            )
        }
    }
}

/**
 * 把**最新那一页**接进窗口（D356）。
 *
 * 与 [mergeUnits] 的区别：这一份是"库里此刻的样子"，所以同一段范围内的单元
 * 以它为准（删掉的那条不能在窗口里留着），范围之外（更早的）保持不动。
 */
internal fun mergeTail(window: List<ChatUnit>, newest: List<ChatUnit>): List<ChatUnit> {
    if (newest.isEmpty()) return window
    val cut = firstSeqOf(newest.first())
    return window.filter { firstSeqOf(it) < cut } + newest
}

/**
 * 只留最后 `cap` 个单元（D356）：800 MB 的会话不可能整条进内存。
 *
 * `keepIndex` 是"正在看的那一行所在的单元下标"——从前往后丢的时候不许越过它，
 * 否则屏幕上正在看的内容会自己消失。丢不动就这一轮先不丢，下次再丢。
 */
internal fun trimFrontKeeping(window: List<ChatUnit>, cap: Int, keepIndex: Int): List<ChatUnit> {
    if (window.size <= cap) return window
    val want = window.size - cap
    val allowed = keepIndex.coerceAtLeast(0)
    val drop = minOf(want, allowed)
    if (drop <= 0) return window
    return window.drop(drop)
}
