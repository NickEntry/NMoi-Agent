package com.nmoi.agent.ui

/**
 * 单框标记编辑器的文档模型与解析（0.15g / D179）。
 *
 * 一条消息在库里是几块：正文（`messages.content`）、思考（`messages.reasoning`）、
 * 每条工具调用的参数（`tool_calls[N].arguments`）与它的结果（另一条消息的 `content`）。
 * 0.14x 把它们拆成几张卡；这一版放进**一个文本框**，靠标记分块：
 *
 * ```text
 * [□{<nmoi-edit v="1">
 * <nmoi:output>
 * 正文
 * </nmoi:output>
 * <nmoi:thinking>
 * 思考
 * </nmoi:thinking>
 * <nmoi:tool part="p3">
 * <nmoi:name>read_file</nmoi:name>
 * <nmoi:input>{"path":"a"}</nmoi:input>
 * <nmoi:stdout>
 * 结果原文
 * </nmoi:stdout>
 * </nmoi:tool>
 * </nmoi-edit>}□]
 * ```
 *
 * 三层防护，别删任何一层：
 *
 * 1. **组合符号 + `nmoi` 前缀**——`[□{` / `}□]` / `<nmoi:` 这三样普通正文里撞不上；
 * 2. **块内容不做转义**，靠"结束标签必须是单独一行"来切；所以 `serialize` 在内容里
 *    发现这些串时**拒绝生成**，而不是生成一份自己都解析不回来的文档；
 * 3. **解析严格**：未知标签 / 未闭合 / 重复块 / 块外文字 / 工具序号跳号，一律
 *    带**行号**报错，整页不写库（"报错就整页不存"是 D179 定的）。
 *
 * 这个文件不 import 任何 Android / Compose 类：单测要能在本机用 kotlinc + junit 跑
 * （`android/app/src/test`），而本机没有 AGP。
 */
data class EditToolBlock(
    /** 第几个工具调用（0 起）。只给旧文档（v1）用；新文档按 [part] 定位。 */
    val index: Int,
    /**
     * 这一块的 part id（`p3`）。删掉一个调用不会让别的块错位（D257）。
     *
     * `null` = 这份文档是 v1（按序号定位），或者是从老草稿里读出来的。
     */
    val part: String? = null,
    val name: String,
    val input: String,
    /** 结果原文。库里只有一份（`role=tool` 那条消息的 content），先放在 stdout 这一栏。 */
    val stdout: String?,
    val stderr: String?,
) {
    /** 界面上给人看的结果：两栏都有时按 stdout、stderr 的顺序接起来。 */
    fun result(): String = listOfNotNull(stdout, stderr)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

data class EditDocument(
    val output: String,
    val thinking: String?,
    val tools: List<EditToolBlock>,
)

/**
 * 这一轮里的一条消息：`messageId` 是保存时定位用的，`doc` 是它那几块。
 *
 * 为什么编辑要横跨几条消息（2026-11-26）：折行（D258）之后**一轮回答是几条消息**，
 * 而屏幕上看到的是连成一片的一个整体——工具卡、思考、正文跨着好几条。
 * 用户长按那段正文点「编辑整条消息」时，红框里那一整片都该能改；
 * 只改一条的话，另外几条的正文和工具参数在编辑器里根本不存在。
 */
data class EditRound(
    val messageId: String,
    val doc: EditDocument,
)

/**
 * 一整轮：`rounds` 按屏幕上从上到下的顺序。
 *
 * 段数**不能变**：每一段对应库里的一条消息，凭空多一段或少一段，保存时就不知道该写哪一条。
 * 与工具块"不能增删"是同一条道理，所以校验也放在解析之后、落库之前。
 */
data class EditTurn(val rounds: List<EditRound>)

/**
 * 解析 / 序列化的失败。**带行号**（1 起；序列化时的冲突给 0）。
 *
 * 不带行号的话，用户看到的是"格式不对"——一份几十行的文档，他得自己找是哪一行。
 */
class EditDocException(
    val line: Int,
    val reason: String,
) : Exception(if (line > 0) "第 $line 行：$reason" else reason)

object EditDocSyntax {
    /** v1：按序号定位（`index="0"`）。旧草稿还是这个形状，解析要认。 */
    const val OPEN_V1 = """[□{<nmoi-edit v="1">"""
    /** v2：按 part id 定位（`part="p3"`）。删一个调用不再让后面的块错位。 */
    const val OPEN = """[□{<nmoi-edit v="2">"""
    const val CLOSE = """</nmoi-edit>}□]"""

    /**
     * v3：**一轮几条消息**（2026-11-26）。每一段外面再套一层
     * `<nmoi:message id="…">`，里面还是 v2 那套块。
     *
     * 为什么加版本而不是让 v2 兼容两种：读的人得先知道"这里面是一条还是几条"。
     * 混在一个头里，一份两段的文档在旧版眼里就是"重复的正文块"——报错报得莫名其妙。
     * 现在 v2 文档照旧只读作一条（草稿里存的就是它），v3 才是多段。
     */
    const val OPEN_V3 = """[□{<nmoi-edit v="3">"""

    /** 开头那一行；三个版本都认。 */
    val OPEN_ANY = Regex("""^\[□\{<nmoi-edit v="([123])">$""")

    const val MESSAGE_OPEN_PREFIX = """<nmoi:message id=""""
    const val MESSAGE_CLOSE = "</nmoi:message>"

    /** `<nmoi:message id="会话:序号">`。 */
    private val MESSAGE_OPEN = Regex("""^<nmoi:message id="([^"]+)">$""")

    fun messageOpen(id: String): String = """<nmoi:message id="$id">"""

    /** 这一行是不是消息段开头；是就返回它的 id。 */
    fun messageTag(line: String): String? = MESSAGE_OPEN.find(line.trim())?.groupValues?.get(1)

    const val OUTPUT_OPEN = "<nmoi:output>"
    const val OUTPUT_CLOSE = "</nmoi:output>"
    const val THINKING_OPEN = "<nmoi:thinking>"
    const val THINKING_CLOSE = "</nmoi:thinking>"
    const val NAME_OPEN = "<nmoi:name>"
    const val NAME_CLOSE = "</nmoi:name>"
    const val INPUT_OPEN = "<nmoi:input>"
    const val INPUT_CLOSE = "</nmoi:input>"
    const val STDOUT_OPEN = "<nmoi:stdout>"
    const val STDOUT_CLOSE = "</nmoi:stdout>"
    const val STDERR_OPEN = "<nmoi:stderr>"
    const val STDERR_CLOSE = "</nmoi:stderr>"

    const val TOOL_CLOSE = "</nmoi:tool>"

    /** 内容里出现这些串就没法无歧义地切块：序列化拒绝生成，解析会按第一个结束标签切。 */
    val RESERVED = listOf("</nmoi:", "<nmoi:", OPEN, OPEN_V1, CLOSE)

    /** `<nmoi:tool part="p3">`（v2）或 `<nmoi:tool index="3">`（v1）。 */
    private val TOOL_OPEN = Regex("""^<nmoi:tool (?:part="([^"]+)"|index="(\d+)")>$""")

    /** 工具块里的四个子块，顺序即报错时提示的顺序。 */
    val SUBS = listOf(
        Triple(NAME_OPEN, NAME_CLOSE, "name"),
        Triple(INPUT_OPEN, INPUT_CLOSE, "input"),
        Triple(STDOUT_OPEN, STDOUT_CLOSE, "stdout"),
        Triple(STDERR_OPEN, STDERR_CLOSE, "stderr"),
    )

    fun toolOpen(part: String?, index: Int): String = if (part != null) {
        """<nmoi:tool part="$part">"""
    } else {
        """<nmoi:tool index="$index">"""
    }

    /** 这一行是不是工具块开头；是就返回 (part, index)，不是返回 null。 */
    fun toolTag(line: String): Pair<String?, Int?>? {
        val hit = TOOL_OPEN.find(line.trim()) ?: return null
        val part = hit.groupValues[1].ifEmpty { null }
        val index = hit.groupValues[2].ifEmpty { null }?.toIntOrNull()
        return part to index
    }
}

/**
 * 文档 → 文本框内容。
 *
 * 块内容原样写进去（不 trim：正文尾随的空行是用户写的）。内容里带上语法标记时抛
 * [EditDocException]——生成一份解析不回来的文档，比直接说"这一块里有 `</nmoi:`" 糟得多。
 */
fun serializeEditDocument(document: EditDocument): String {
    val out = StringBuilder()
    // 有 part id 就写 v2；一份文档里不许混着两种定位方式（读的人会以为它们是两根轴）。
    val byPart = document.tools.all { it.part != null }
    val byIndex = document.tools.all { it.part == null }
    if (!byPart && !byIndex) {
        throw EditDocException(0, "工具块要按 part id 定位就全都给 part，要么全都按序号")
    }
    out.append(if (byPart) EditDocSyntax.OPEN else EditDocSyntax.OPEN_V1).append('\n')
    writeBlocks(out, document, byPart)
    out.append(EditDocSyntax.CLOSE)
    return out.toString()
}

/**
 * 一整轮 → 文本框内容（v3）。
 *
 * 每一段外面套 `<nmoi:message id="…">`：保存时按 id 一条条写回去，**不靠顺序猜**。
 * 顺序仍然是屏幕上的顺序，读的人先看到哪一段就是屏幕上哪一段。
 */
fun serializeEditTurn(turn: EditTurn): String {
    if (turn.rounds.isEmpty()) {
        throw EditDocException(0, "这一轮里一条消息都没有")
    }
    val out = StringBuilder()
    out.append(EditDocSyntax.OPEN_V3).append('\n')
    turn.rounds.forEach { round ->
        if (round.messageId.isBlank()) {
            throw EditDocException(0, "有一段没有消息 id：保存时不知道该写回哪一条")
        }
        val byPart = round.doc.tools.all { it.part != null }
        val byIndex = round.doc.tools.all { it.part == null }
        if (!byPart && !byIndex) {
            throw EditDocException(0, "工具块要按 part id 定位就全都给 part，要么全都按序号")
        }
        out.append(EditDocSyntax.messageOpen(round.messageId)).append('\n')
        writeBlocks(out, round.doc, byPart)
        out.append(EditDocSyntax.MESSAGE_CLOSE).append('\n')
    }
    out.append(EditDocSyntax.CLOSE)
    return out.toString()
}

/** 一条消息的块写进 [out]。调用方负责头尾（老形状是整份文档，v3 是每一段）。 */
private fun writeBlocks(out: StringBuilder, document: EditDocument, byPart: Boolean) {
    fun put(open: String, close: String, text: String) {
        out.append(open).append('\n')
        out.append(text)
        out.append('\n')
        out.append(close).append('\n')
    }

    checkReserved("正文", document.output)
    put(EditDocSyntax.OUTPUT_OPEN, EditDocSyntax.OUTPUT_CLOSE, document.output)

    document.thinking?.let { thinking ->
        checkReserved("思考", thinking)
        // 空思考块也要写出来：写出来才看得见"删掉这一块"这个动作（清空并保存 = 删）。
        put(EditDocSyntax.THINKING_OPEN, EditDocSyntax.THINKING_CLOSE, thinking)
    }

    document.tools.forEachIndexed { position, tool ->
        if (!byPart && tool.index != position) {
            throw EditDocException(0, "工具块序号要从 0 连续排：第 ${position + 1} 个是 #${tool.index}")
        }
        out.append(EditDocSyntax.toolOpen(tool.part, tool.index)).append('\n')
        checkReserved("工具名 #${tool.index}", tool.name)
        put(EditDocSyntax.NAME_OPEN, EditDocSyntax.NAME_CLOSE, tool.name)
        checkReserved("工具参数 #${tool.index}", tool.input)
        put(EditDocSyntax.INPUT_OPEN, EditDocSyntax.INPUT_CLOSE, tool.input)
        tool.stdout?.let { text ->
            checkReserved("工具结果 #${tool.index}", text)
            put(EditDocSyntax.STDOUT_OPEN, EditDocSyntax.STDOUT_CLOSE, text)
        }
        tool.stderr?.let { text ->
            checkReserved("工具错误 #${tool.index}", text)
            put(EditDocSyntax.STDERR_OPEN, EditDocSyntax.STDERR_CLOSE, text)
        }
        out.append(EditDocSyntax.TOOL_CLOSE).append('\n')
    }
}

/**
 * 文本框内容 → 文档。失败抛 [EditDocException]，行号指到"用户该看哪一行"。
 *
 * 行号是**文本里的行**（1 起），不是块内偏移：用户看到的是整段文本。
 */
fun parseEditDocument(text: String): EditDocument {
    val turn = parseEditTurn(text)
    if (turn.rounds.size != 1) {
        throw EditDocException(
            1,
            "这份文档里有 ${turn.rounds.size} 段消息：那是「编辑整条消息」的形状，" +
                "要按一条消息读就别用它",
        )
    }
    return turn.rounds.first().doc
}

/**
 * 文本框内容 → **一整轮**（可能几条消息）。失败抛 [EditDocException]。
 *
 * 三种形状都认：
 * - v1 / v2（没有 `<nmoi:message>` 段）= 一条消息，`messageId` 为空串（调用方补上目标 id）；
 * - v3 = 若干段，每段一个 `<nmoi:message id="…">`。
 *
 * 段数、段 id 都在这里定：保存时要按 id 一条条写回去，而"少一段"或"多一段"
 * 在库里就是另一条消息被改/没改——那是最难查的一类。
 */
fun parseEditTurn(text: String): EditTurn {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")

    // 1) 首尾。空文档给一句人话，不报"第 1 行标签不对"。
    if (text.isBlank()) {
        throw EditDocException(0, "文档是空的：至少要有一段正文")
    }
    var first = 0
    while (first < lines.size && lines[first].isBlank()) first++
    val header = if (first < lines.size) EditDocSyntax.OPEN_ANY.find(lines[first].trim()) else null
    if (header == null) {
        throw EditDocException(
            first + 1,
            "开头必须是 ${EditDocSyntax.OPEN}（少了它就没法确定这是哪种文档）",
        )
    }
    val version = header.groupValues[1].toInt()
    var last = lines.size - 1
    while (last > first && lines[last].isBlank()) last--
    if (lines[last].trim() != EditDocSyntax.CLOSE) {
        throw EditDocException(
            last + 1,
            "结尾必须是 ${EditDocSyntax.CLOSE}（没闭合就不写库）",
        )
    }

    if (version < 3) {
        // 老形状：整份文档就是一条消息的几块。按序号定位（v1）或按 part（v2）。
        return EditTurn(
            listOf(
                EditRound(
                    messageId = "",
                    doc = parseBlocks(lines, first + 1, last, version),
                ),
            ),
        )
    }

    // v3：逐段。段外不许有别的字——那多半是手抖删掉了 `</nmoi:message>`。
    val rounds = mutableListOf<EditRound>()
    var cursor = first + 1
    while (cursor < last) {
        val raw = lines[cursor]
        val line = cursor + 1
        if (raw.isBlank()) {
            cursor++
            continue
        }
        val id = EditDocSyntax.messageTag(raw)
            ?: throw EditDocException(
                line,
                "v3 文档里每一段都要以 ${EditDocSyntax.messageOpen("会话:序号")} 开头（这一段不是）",
            )
        if (rounds.any { it.messageId == id }) {
            throw EditDocException(line, "同一条消息 $id 出现了两段：一段只能改一次")
        }
        val end = indexOf(lines, cursor + 1, last, EditDocSyntax.MESSAGE_CLOSE, line)
        rounds += EditRound(
            messageId = id,
            doc = parseBlocks(lines, cursor + 1, end, version),
        )
        cursor = end + 1
    }
    if (rounds.isEmpty()) {
        throw EditDocException(first + 1, "这一份里一段消息都没有：至少要有一段")
    }
    return EditTurn(rounds)
}

/** 一段（或整份老文档）里的块。`from` / `end` 是行下标，半开区间。 */
private fun parseBlocks(
    lines: List<String>,
    from: Int,
    end: Int,
    version: Int,
): EditDocument {
    var output: String? = null
    var thinking: String? = null
    var thinkingSeen = false
    val tools = mutableListOf<EditToolBlock>()

    var cursor = from
    while (cursor < end) {
        val raw = lines[cursor]
        val line = cursor + 1
        if (raw.isBlank()) {
            cursor++
            continue
        }
        val tag = raw.trim()
        // 单行形：`<nmoi:output>正文</nmoi:output>`。手写短块时很常见（工具名、一行正文），
        // 要求"结束标签必须单独一行"会把这种写法直接判死，那不是严格，是不好用。
        val onOneLineOutput =
            oneLineBlock(tag, EditDocSyntax.OUTPUT_OPEN, EditDocSyntax.OUTPUT_CLOSE)
        val onOneLineThinking =
            oneLineBlock(tag, EditDocSyntax.THINKING_OPEN, EditDocSyntax.THINKING_CLOSE)
        when {
            onOneLineOutput != null -> {
                if (output != null) {
                    throw EditDocException(line, "正文块只能有一块（这是第二块）")
                }
                output = onOneLineOutput
                cursor++
            }
            onOneLineThinking != null -> {
                if (thinkingSeen) {
                    throw EditDocException(line, "思考块只能有一块（这是第二块）")
                }
                thinkingSeen = true
                thinking = onOneLineThinking
                cursor++
            }
            tag == EditDocSyntax.OUTPUT_OPEN -> {
                if (output != null) {
                    throw EditDocException(line, "正文块只能有一块（这是第二块）")
                }
                val close = indexOf(lines, cursor + 1, end, EditDocSyntax.OUTPUT_CLOSE, line)
                output = joinLines(lines, cursor + 1, close)
                cursor = close + 1
            }
            tag == EditDocSyntax.THINKING_OPEN -> {
                if (thinkingSeen) {
                    throw EditDocException(line, "思考块只能有一块（这是第二块）")
                }
                thinkingSeen = true
                val close = indexOf(lines, cursor + 1, end, EditDocSyntax.THINKING_CLOSE, line)
                thinking = joinLines(lines, cursor + 1, close)
                cursor = close + 1
            }
            EditDocSyntax.toolTag(tag) != null -> {
                val (part, rawIndex) = EditDocSyntax.toolTag(tag)!!
                val index = rawIndex ?: tools.size
                if (version == 1) {
                    if (index != tools.size) {
                        throw EditDocException(
                            line,
                            "工具块序号要从 0 连续排：这一块是 #$index，按顺序这里应该是 #${tools.size}",
                        )
                    }
                } else if (part == null) {
                    throw EditDocException(
                        line,
                        "v2 / v3 文档里的工具块要写 part=\"p3\" 这种 id（按序号定位会删一个就全错位）",
                    )
                }
                val close = indexOf(lines, cursor + 1, end, EditDocSyntax.TOOL_CLOSE, line)
                tools += parseToolBlock(lines, cursor + 1, close, index, part, line)
                cursor = close + 1
            }
            else -> {
                throw EditDocException(
                    line,
                    "这一行不是能认的块开头（只认 ${EditDocSyntax.OUTPUT_OPEN} / " +
                        "${EditDocSyntax.THINKING_OPEN} / <nmoi:tool part=\"pN\">）",
                )
            }
        }
    }

    val body = output ?: throw EditDocException(from, "没有正文块：至少要有一段正文")
    // 纯工具那一轮本来就没有正文（屏幕上只有工具卡）。那种段允许正文为空——
    // 否则用户删掉占位行会看到"删掉正文等于把这条消息变成空壳"，而它根本没有正文。
    // 有正文的那种仍然不许清空：删了它就是一条空壳（core 的 `CannotDeleteOutput` 也拒）。
    if (body.isBlank() && tools.isEmpty()) {
        throw EditDocException(from, "正文是空的：删掉正文等于把这条消息变成空壳")
    }
    return EditDocument(
        output = body,
        thinking = if (thinkingSeen) thinking.orEmpty() else null,
        tools = tools,
    )
}

private fun parseToolBlock(
    lines: List<String>,
    from: Int,
    end: Int,
    index: Int,
    part: String?,
    openLine: Int,
): EditToolBlock {
    var name: String? = null
    var input: String? = null
    var stdout: String? = null
    var stderr: String? = null

    fun assign(slot: String, text: String, line: Int) {
        when (slot) {
            "name" -> {
                if (name != null) throw EditDocException(line, "工具名只能有一份")
                name = text
            }
            "input" -> {
                if (input != null) throw EditDocException(line, "工具参数只能有一份")
                input = text
            }
            "stdout" -> {
                if (stdout != null) throw EditDocException(line, "stdout 只能有一份")
                stdout = text
            }
            "stderr" -> {
                if (stderr != null) throw EditDocException(line, "stderr 只能有一份")
                stderr = text
            }
        }
    }

    var cursor = from
    while (cursor < end) {
        val raw = lines[cursor]
        val line = cursor + 1
        if (raw.isBlank()) {
            cursor++
            continue
        }
        val tag = raw.trim()

        // 单行子块：`<nmoi:name>read_file</nmoi:name>`。手写时几乎都会写成这样。
        val single = EditDocSyntax.SUBS.firstNotNullOfOrNull { (open, close, slot) ->
            oneLineBlock(tag, open, close)?.let { Triple(slot, it, line) }
        }
        if (single != null) {
            assign(single.first, single.second, line)
            cursor++
            continue
        }

        val multi = EditDocSyntax.SUBS.firstOrNull { (open, _, _) -> tag == open }
            ?: throw EditDocException(
                line,
                "工具块里不认这一行（只认 name / input / stdout / stderr 四个子块）：$tag",
            )
        val closeAt = indexOf(lines, cursor + 1, end, multi.second, line)
        assign(multi.third, joinLines(lines, cursor + 1, closeAt), line)
        cursor = closeAt + 1
    }

    val resolvedName = name
        ?: throw EditDocException(openLine, "工具块 #$index 没有名字（${EditDocSyntax.NAME_OPEN}）")
    if (resolvedName.isBlank()) {
        throw EditDocException(openLine, "工具块 #$index 的名字是空的")
    }
    val resolvedInput = input
        ?: throw EditDocException(openLine, "工具块 #$index 没有参数（${EditDocSyntax.INPUT_OPEN}）")
    return EditToolBlock(index, part, resolvedName, resolvedInput, stdout, stderr)
}

/** 单行形 `<open>内容<close>`；不是这个形状就返回 null（交给多行分支）。 */
private fun oneLineBlock(line: String, open: String, close: String): String? {
    if (!line.startsWith(open) || !line.endsWith(close)) return null
    if (line.length < open.length + close.length) return null
    return line.substring(open.length, line.length - close.length)
}

/** 从 [from] 起找第一个恰好等于 [closer] 的行；找不到就报**开头那一行**的行号。 */
private fun indexOf(
    lines: List<String>,
    from: Int,
    end: Int,
    closer: String,
    openLine: Int,
): Int {
    for (at in from until end) {
        if (lines[at].trim() == closer) return at
    }
    throw EditDocException(openLine, "这一块没闭合：缺少单独一行的 $closer")
}

private fun joinLines(lines: List<String>, from: Int, to: Int): String {
    if (to <= from) return ""
    return lines.subList(from, to).joinToString("\n")
}

private fun checkReserved(what: String, text: String) {
    val hit = EditDocSyntax.RESERVED.firstOrNull { text.contains(it) }
    if (hit != null) {
        throw EditDocException(
            0,
            "$what 里有 `$hit`，它会跟分块标记撞；先改写这一处再保存",
        )
    }
}
