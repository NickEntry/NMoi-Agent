package com.nmoi.agent.ui

/**
 * 单框标记编辑器的文档模型与解析（0.15g / D179；2026-11-26 去掉包装）。
 *
 * **正文不包标签**：文本框里那些没有标记的字就是正文。其余三种块各有一对标签，
 * 一条消息里有几块就写几块，顺序就是你写的顺序。
 *
 * ```text
 * <nmoi:thinking>
 * 先跑一下看看
 * </nmoi:thinking>
 * 能，结果在这里：
 * <nmoi:tool name="System-Shell">
 * <nmoi:input>{"command":"ls"}</nmoi:input>
 * <nmoi:stdout>
 * a.txt
 * </nmoi:stdout>
 * </nmoi:tool>
 * ---
 * 第二条消息的正文
 * ```
 *
 * 四条规矩（都是为了少打字、少看无关的字）：
 *
 * 1. **没有文件头尾**。以前那份 `[□{<nmoi-edit v="3">` / `</nmoi-edit>}□]` 占了整整两行，
 *    除了证明"这是我们生成的"，什么事都不干——用户从来不会手打它们。
 * 2. **没有消息 id**。段与段之间就是一行 `---`，第几段写回第几条消息：段数对不上就整页不写
 *    （见 [MessageEditScreen]）。一串 uuid 摆在正文里，读的人只会问"这是啥"。
 * 3. **工具块不带 part**。它按**出现顺序**对应第 0、1、2 个调用——工具块不能增删，
 *    所以顺序就是身份。`part="p1"` 那 11 个字符一个信息都不多给。
 * 4. **没有 stderr 那一栏**。库里只有一份结果原文（D258），第二栏永远是空的。
 *
 * 三层防护，别删任何一层：
 *
 * 1. **`<nmoi:` 前缀 + 独占一行的结束标签**——普通正文里撞不上；
 * 2. **块内容不做转义**，靠"结束标签必须是单独一行"来切；所以 `serialize` 在内容里
 *    发现这些串时**拒绝生成**，而不是生成一份自己都解析不回来的文档；
 * 3. **解析严格**：未知标签 / 未闭合 / 工具块没有名字，一律带**行号**报错，整页不写库。
 *
 * 这个文件不 import 任何 Android / Compose 类：单测要能在本机用 kotlinc + junit 跑
 * （`android/app/src/test`），而本机没有 AGP。
 */
data class EditToolBlock(
    /** 这一条消息里第几个调用（0 起）。按出现顺序数的，不是写出来的。 */
    val index: Int,
    val name: String,
    val input: String,
    /** 结果原文。库里只有一份（D258），没有第二栏。 */
    val stdout: String?,
) {
    fun result(): String = stdout.orEmpty()
}

/**
 * 一条消息的几块。
 *
 * [thinkings] 是**一份列表**：思考块想写几个写几个（2026-11-26 用户提的）。
 * 库里 `reasoning` 是**一个字符串**，所以保存时按顺序接起来（空行分隔）——
 * 这是唯一不丢字的做法：多块存不进去，硬留第二个就是"存了但看不见"。
 * 因此存完再打开会看到一块（等于你写的那几块接在一起），内容一字不少。
 */
data class EditDocument(
    val output: String,
    val thinkings: List<String>,
    val tools: List<EditToolBlock>,
) {
    constructor(output: String, thinking: String?, tools: List<EditToolBlock>) :
        this(output, listOfNotNull(thinking), tools)

    /** 接起来的那一份：落库时写的就是它。全空等于"没有思考块"。 */
    val thinking: String?
        get() = thinkings.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null }
}

/**
 * 一整轮：`docs` 按屏幕上从上到下的顺序，下标就是第几条消息。
 *
 * 为什么要横跨几条（2026-11-26）：折行（D258）之后一轮回答是几条消息，
 * 而屏幕上看到的是连成一片的一个整体。用户长按那段正文点「编辑整条消息」时，
 * 红框里那一整片都该能改。
 *
 * 段数**不能变**：每一段对应库里的一条消息，少一段多一段就不知道该写回哪一条。
 */
data class EditTurn(val docs: List<EditDocument>)

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
    /** 两段消息之间的那一行。只有**独占一行**的它才算分隔，正文里出现不影响。 */
    const val SEPARATOR = "---"

    const val THINKING_OPEN = "<nmoi:thinking>"
    const val THINKING_CLOSE = "</nmoi:thinking>"
    const val INPUT_OPEN = "<nmoi:input>"
    const val INPUT_CLOSE = "</nmoi:input>"
    const val STDOUT_OPEN = "<nmoi:stdout>"
    const val STDOUT_CLOSE = "</nmoi:stdout>"
    const val TOOL_CLOSE = "</nmoi:tool>"

    /** `<nmoi:tool name="System-Shell">`。名字写在标签上，省掉一对 `<nmoi:name>` 行。 */
    private val TOOL_OPEN = Regex("""^<nmoi:tool name="([^"]*)">$""")

    fun toolOpen(name: String): String = """<nmoi:tool name="$name">"""

    /** 这一行是不是工具块开头；是就返回名字。 */
    fun toolTag(line: String): String? =
        TOOL_OPEN.find(line.trim())?.groupValues?.get(1)

    /** 内容里出现这些串就没法无歧义地切块：序列化拒绝生成，解析会按第一个结束标签切。 */
    val RESERVED = listOf(
        "</nmoi:",
        THINKING_OPEN,
        THINKING_CLOSE,
        INPUT_OPEN,
        INPUT_CLOSE,
        STDOUT_OPEN,
        STDOUT_CLOSE,
        TOOL_CLOSE,
        "<nmoi:tool",
    )
}

/**
 * 一整轮 → 文本框内容。
 *
 * 段与段之间一行 `---`；每段里按"思考 → 正文 → 工具"写，与库里的块顺序一致，
 * 也与屏幕上从上到下一致（思考卡在上、正文在下）。
 */
fun serializeEditTurn(turn: EditTurn): String {
    if (turn.docs.isEmpty()) {
        throw EditDocException(0, "这一轮里一条消息都没有")
    }
    val out = StringBuilder()
    turn.docs.forEachIndexed { position, doc ->
        if (position > 0) out.append(EditDocSyntax.SEPARATOR).append('\n')
        writeBlocks(out, doc)
    }
    return out.toString()
}

/** 一条消息的块写进 [out]。调用方负责段与段之间的 `---`。 */
private fun writeBlocks(out: StringBuilder, doc: EditDocument) {
    fun put(open: String, close: String, text: String) {
        out.append(open).append('\n')
        out.append(text)
        out.append('\n')
        out.append(close).append('\n')
    }

    doc.thinkings.forEachIndexed { position, thinking ->
        checkReserved("思考 #${position + 1}", thinking)
        // 空思考块也要写出来：写出来才看得见"删掉这一块"这个动作（清空并保存 = 删）。
        put(EditDocSyntax.THINKING_OPEN, EditDocSyntax.THINKING_CLOSE, thinking)
    }

    // 正文不包标签：它就是那几行字。空正文（纯工具那一轮）一行都不写。
    if (doc.output.isNotEmpty()) {
        checkReserved("正文", doc.output)
        out.append(doc.output).append('\n')
    }

    doc.tools.forEachIndexed { position, tool ->
        if (tool.index != position) {
            throw EditDocException(
                0,
                "工具块序号要从 0 连续排：第 ${position + 1} 个是 #${tool.index}",
            )
        }
        if (tool.name.isBlank()) {
            throw EditDocException(0, "第 ${position + 1} 个工具块没有名字")
        }
        checkReserved("工具名 #${tool.index}", tool.name)
        out.append(EditDocSyntax.toolOpen(tool.name)).append('\n')
        checkReserved("工具参数 #${tool.index}", tool.input)
        put(EditDocSyntax.INPUT_OPEN, EditDocSyntax.INPUT_CLOSE, tool.input)
        tool.stdout?.let { text ->
            checkReserved("工具结果 #${tool.index}", text)
            put(EditDocSyntax.STDOUT_OPEN, EditDocSyntax.STDOUT_CLOSE, text)
        }
        out.append(EditDocSyntax.TOOL_CLOSE).append('\n')
    }
}

/**
 * 文本框内容 → 一整轮。失败抛 [EditDocException]。
 *
 * **顶层没有标签的字就是正文**：它按出现顺序攒起来，一段里出现的所有散字拼成那一段的正文。
 * 段与段之间靠一行 `---` 切。
 */
fun parseEditTurn(text: String): EditTurn {
    if (text.isBlank()) {
        throw EditDocException(0, "文档是空的：至少要有一段正文")
    }
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val docs = mutableListOf<EditDocument>()

    // 当前这一段攒到的东西。
    var body = StringBuilder()
    val thinkings = mutableListOf<String>()
    val tools = mutableListOf<EditToolBlock>()
    // 这一段的起始行：报"这一段是空的"时指得到地方。
    var segmentStart = 1

    fun flush() {
        // 一段都没写（连续两个 `---`）：不安静吞掉，说清楚。
        if (body.isBlank() && thinkings.isEmpty() && tools.isEmpty()) {
            throw EditDocException(segmentStart, "这一段是空的：段不能空着")
        }
        docs += EditDocument(
            output = body.toString().trim('\n'),
            thinkings = thinkings.toList(),
            tools = tools.toList(),
        )
        body = StringBuilder()
        thinkings.clear()
        tools.clear()
    }

    var cursor = 0
    while (cursor < lines.size) {
        val raw = lines[cursor]
        val line = cursor + 1
        val tag = raw.trim()

        when {
            tag == EditDocSyntax.SEPARATOR -> {
                flush()
                segmentStart = line + 1
                cursor++
            }
            tag == EditDocSyntax.THINKING_OPEN -> {
                val close = indexOf(lines, cursor + 1, EditDocSyntax.THINKING_CLOSE, line)
                thinkings += joinLines(lines, cursor + 1, close)
                cursor = close + 1
            }
            EditDocSyntax.toolTag(tag) != null -> {
                val name = EditDocSyntax.toolTag(tag)!!
                val close = indexOf(lines, cursor + 1, EditDocSyntax.TOOL_CLOSE, line)
                tools += parseToolBlock(lines, cursor + 1, close, tools.size, name, line)
                cursor = close + 1
            }
            // 单行形：`<nmoi:thinking>一句想法</nmoi:thinking>`。手写短块时很常见。
            oneLineBlock(tag, EditDocSyntax.THINKING_OPEN, EditDocSyntax.THINKING_CLOSE) != null -> {
                thinkings += oneLineBlock(
                    tag,
                    EditDocSyntax.THINKING_OPEN,
                    EditDocSyntax.THINKING_CLOSE,
                )!!
                cursor++
            }
            tag.startsWith("<nmoi:") || tag.endsWith("</nmoi:thinking>") -> throw EditDocException(
                line,
                "这一行是标记但不认（只认 ${EditDocSyntax.THINKING_OPEN} / " +
                    """<nmoi:tool name="…"> / ${EditDocSyntax.SEPARATOR}）""",
            )
            else -> {
                body.append(raw).append('\n')
                cursor++
            }
        }
    }
    flush()
    return EditTurn(docs)
}

/**
 * 一段里的一个工具块。子块只有两个：`input` 与 `stdout`（名字在开头的标签上）。
 *
 * 两个都写在一行也行（`<nmoi:input>{}</nmoi:input>`），手写时几乎都会写成这样。
 */
private fun parseToolBlock(
    lines: List<String>,
    from: Int,
    end: Int,
    index: Int,
    name: String,
    openLine: Int,
): EditToolBlock {
    var input: String? = null
    var stdout: String? = null

    fun assign(slot: String, text: String, line: Int) {
        when (slot) {
            "input" -> {
                if (input != null) throw EditDocException(line, "工具参数只能有一份")
                input = text
            }
            "stdout" -> {
                if (stdout != null) throw EditDocException(line, "工具结果只能有一份")
                stdout = text
            }
        }
    }

    val subs = listOf(
        Triple(EditDocSyntax.INPUT_OPEN, EditDocSyntax.INPUT_CLOSE, "input"),
        Triple(EditDocSyntax.STDOUT_OPEN, EditDocSyntax.STDOUT_CLOSE, "stdout"),
    )

    var cursor = from
    while (cursor < end) {
        val raw = lines[cursor]
        val line = cursor + 1
        if (raw.isBlank()) {
            cursor++
            continue
        }
        val tag = raw.trim()

        val single = subs.firstNotNullOfOrNull { (open, close, slot) ->
            oneLineBlock(tag, open, close)?.let { Triple(slot, it, line) }
        }
        if (single != null) {
            assign(single.first, single.second, line)
            cursor++
            continue
        }

        val multi = subs.firstOrNull { (open, _, _) -> tag == open }
            ?: throw EditDocException(
                line,
                "工具块里不认这一行（只认 input / stdout 两个子块）：$tag",
            )
        val closeAt = indexOf(lines, cursor + 1, multi.second, line)
        assign(multi.third, joinLines(lines, cursor + 1, closeAt), line)
        cursor = closeAt + 1
    }

    if (name.isBlank()) {
        throw EditDocException(openLine, "工具块 #$index 没有名字（<nmoi:tool name=\"…\">）")
    }
    return EditToolBlock(
        index = index,
        name = name,
        input = input
            ?: throw EditDocException(openLine, "工具块 #$index 没有参数（<nmoi:input>）"),
        stdout = stdout,
    )
}

/** 单行形 `<open>内容<close>`；不是这个形状就返回 null（交给多行分支）。 */
private fun oneLineBlock(line: String, open: String, close: String): String? {
    if (!line.startsWith(open) || !line.endsWith(close)) return null
    if (line.length < open.length + close.length) return null
    return line.substring(open.length, line.length - close.length)
}

/** 从 [from] 起找第一个恰好等于 [closer] 的行；找不到就报**开头那一行**的行号。 */
private fun indexOf(lines: List<String>, from: Int, closer: String, openLine: Int): Int {
    for (at in from until lines.size) {
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
