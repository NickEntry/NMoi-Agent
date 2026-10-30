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
 * <nmoi:tool index="0">
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
    /** 第几个工具调用（0 起）。与 `tool_call_id` 的配对靠顺序，不靠名字。 */
    val index: Int,
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
 * 解析 / 序列化的失败。**带行号**（1 起；序列化时的冲突给 0）。
 *
 * 不带行号的话，用户看到的是"格式不对"——一份几十行的文档，他得自己找是哪一行。
 */
class EditDocException(
    val line: Int,
    val reason: String,
) : Exception(if (line > 0) "第 $line 行：$reason" else reason)

object EditDocSyntax {
    const val OPEN = """[□{<nmoi-edit v="1">"""
    const val CLOSE = """</nmoi-edit>}□]"""

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
    val RESERVED = listOf("</nmoi:", "<nmoi:", OPEN, CLOSE)

    /** `<nmoi:tool index="3">` */
    private val TOOL_OPEN = Regex("""^<nmoi:tool index="(\d+)">$""")

    /** 工具块里的四个子块，顺序即报错时提示的顺序。 */
    val SUBS = listOf(
        Triple(NAME_OPEN, NAME_CLOSE, "name"),
        Triple(INPUT_OPEN, INPUT_CLOSE, "input"),
        Triple(STDOUT_OPEN, STDOUT_CLOSE, "stdout"),
        Triple(STDERR_OPEN, STDERR_CLOSE, "stderr"),
    )

    fun toolOpen(index: Int): String = """<nmoi:tool index="$index">"""

    /** 这一行是不是工具块开头；是就返回序号，不是返回 null。 */
    fun toolIndex(line: String): Int? =
        TOOL_OPEN.find(line.trim())?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * 文档 → 文本框内容。
 *
 * 块内容原样写进去（不 trim：正文尾随的空行是用户写的）。内容里带上语法标记时抛
 * [EditDocException]——生成一份解析不回来的文档，比直接说"这一块里有 `</nmoi:`" 糟得多。
 */
fun serializeEditDocument(document: EditDocument): String {
    val out = StringBuilder()
    out.append(EditDocSyntax.OPEN).append('\n')

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
        if (tool.index != position) {
            throw EditDocException(0, "工具块序号要从 0 连续排：第 ${position + 1} 个是 #${tool.index}")
        }
        out.append(EditDocSyntax.toolOpen(tool.index)).append('\n')
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

    out.append(EditDocSyntax.CLOSE)
    return out.toString()
}

/**
 * 文本框内容 → 文档。失败抛 [EditDocException]，行号指到"用户该看哪一行"。
 *
 * 行号是**文本里的行**（1 起），不是块内偏移：用户看到的是整段文本。
 */
fun parseEditDocument(text: String): EditDocument {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")

    // 1) 首尾。空文档给一句人话，不报"第 1 行标签不对"。
    if (text.isBlank()) {
        throw EditDocException(0, "文档是空的：至少要有一段正文")
    }
    var first = 0
    while (first < lines.size && lines[first].isBlank()) first++
    if (first >= lines.size || lines[first].trim() != EditDocSyntax.OPEN) {
        throw EditDocException(
            first + 1,
            "开头必须是 ${EditDocSyntax.OPEN}（少了它就没法确定这是哪种文档）",
        )
    }
    var last = lines.size - 1
    while (last > first && lines[last].isBlank()) last--
    if (lines[last].trim() != EditDocSyntax.CLOSE) {
        throw EditDocException(
            last + 1,
            "结尾必须是 ${EditDocSyntax.CLOSE}（没闭合就不写库）",
        )
    }

    var output: String? = null
    var thinking: String? = null
    var thinkingSeen = false
    val tools = mutableListOf<EditToolBlock>()

    var cursor = first + 1
    while (cursor < last) {
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
                val end = indexOf(lines, cursor + 1, last, EditDocSyntax.OUTPUT_CLOSE, line)
                output = joinLines(lines, cursor + 1, end)
                cursor = end + 1
            }
            tag == EditDocSyntax.THINKING_OPEN -> {
                if (thinkingSeen) {
                    throw EditDocException(line, "思考块只能有一块（这是第二块）")
                }
                thinkingSeen = true
                val end = indexOf(lines, cursor + 1, last, EditDocSyntax.THINKING_CLOSE, line)
                thinking = joinLines(lines, cursor + 1, end)
                cursor = end + 1
            }
            EditDocSyntax.toolIndex(tag) != null -> {
                val index = EditDocSyntax.toolIndex(tag)!!
                if (index != tools.size) {
                    throw EditDocException(
                        line,
                        "工具块序号要从 0 连续排：这一块是 #$index，按顺序这里应该是 #${tools.size}",
                    )
                }
                val end = indexOf(lines, cursor + 1, last, EditDocSyntax.TOOL_CLOSE, line)
                tools += parseToolBlock(lines, cursor + 1, end, index, line)
                cursor = end + 1
            }
            else -> {
                throw EditDocException(
                    line,
                    "这一行不是能认的块开头（只认 ${EditDocSyntax.OUTPUT_OPEN} / " +
                        "${EditDocSyntax.THINKING_OPEN} / <nmoi:tool index=\"N\">）",
                )
            }
        }
    }

    val body = output ?: throw EditDocException(first + 1, "没有正文块：至少要有一段正文")
    if (body.isBlank()) {
        throw EditDocException(first + 1, "正文是空的：删掉正文等于把这条消息变成空壳")
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
    return EditToolBlock(index, resolvedName, resolvedInput, stdout, stderr)
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
