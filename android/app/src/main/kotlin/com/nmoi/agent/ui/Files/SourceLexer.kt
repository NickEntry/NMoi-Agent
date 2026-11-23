package com.nmoi.agent.ui.Files

/**
 * 源码页的**行级最小着色**（D392）。
 *
 * 只认三样：注释、字符串、数字。不引依赖、不写 parser——一个覆盖多语言的着色器
 * 是另一件事（要引依赖，而项目对依赖的态度见 D10 与 zip 禁令）。
 * 认不出语言就**不上色**，只给等宽：宁可少上，不要乱上。
 *
 * 单遍左到右扫描，所以三条规矩天然成立：
 * 1. 字符串里的 `//` 不是注释（先吃字符串）；
 * 2. 注释里的引号不开字符串（遇到注释符就吃掉整行剩余）；
 * 3. 转义引号（`\"`）不结束字符串。
 *
 * 这是纯函数，本机 JUnit 能钉住——着色错起来不会报错，只会"看着怪"，最需要单测。
 */
internal enum class SourceToken { Comment, String, Number }

/** 一段着色。`end` 是**开区间**，与 Kotlin 的 `substring` 一致。 */
internal data class SourceSpan(val start: Int, val end: Int, val token: SourceToken)

internal object SourceLexer {

    /** 注释起始符。多语言里前两个字符的语义一致，所以只按"哪种语言"选一个标记。 */
    private val LINE_COMMENT = mapOf(
        "rust" to "//",
        "kotlin" to "//",
        "js" to "//",
        "python" to "#",
        "sh" to "#",
        "sql" to "--",
        "lua" to "--",
    )

    /**
     * 这个文件用哪种语言着色。认不出给 `null`（= 不上色）。
     *
     * 只覆盖五种：其余语言的注释符与字符串规则各有各的例外，猜错了会画出
     * "整行都是注释"那种更糟的图。
     */
    fun languageOf(path: String): String? = when (FileKinds.extensionOf(FileKinds.baseName(path))) {
        "rs" -> "rust"
        "kt", "kts", "java", "gradle" -> "kotlin"
        "py" -> "python"
        "js", "jsx", "ts", "tsx", "json", "jsonl" -> "js"
        "sh", "bash", "zsh", "fish", "conf", "env", "properties", "toml", "yaml", "yml" -> "sh"
        "sql" -> "sql"
        "lua" -> "lua"
        else -> null
    }

    /**
     * 一行里的着色段。**按位置升序、互不重叠**，调用方直接按段拼 `AnnotatedString`。
     *
     * `language` 为 `null` 时只认字符串与数字，不认注释：不知道注释符是什么的时候，
     * 猜一个 `#` 会把 Python 的 `#` 认对，却把 C 的 `#include` 整行染成注释。
     */
    fun spans(line: String, language: String?): List<SourceSpan> {
        if (line.isEmpty()) return emptyList()
        val comment = language?.let { LINE_COMMENT[it] }
        val out = ArrayList<SourceSpan>()
        var index = 0
        while (index < line.length) {
            val char = line[index]

            // 注释：吃掉整行剩余，后面不再看。
            if (comment != null && line.startsWith(comment, index)) {
                out += SourceSpan(index, line.length, SourceToken.Comment)
                break
            }

            // 字符串：找到同一行里配对的引号；找不到就吃到行尾（多行字符串的第一行）。
            if (char == '"' || char == '\'') {
                val end = stringEnd(line, index)
                out += SourceSpan(index, end, SourceToken.String)
                index = end
                continue
            }

            if (char.isDigit()) {
                var end = index + 1
                while (end < line.length && (line[end].isDigit() || line[end] == '_' || line[end] == '.')) {
                    end += 1
                }
                // `a1` 这种标识符里的数字不上色：前一个字符是字母或下划线就当它是一部分。
                val previous = if (index == 0) ' ' else line[index - 1]
                if (!previous.isLetterOrDigit() && previous != '_') {
                    out += SourceSpan(index, end, SourceToken.Number)
                }
                index = end
                continue
            }

            index += 1
        }
        return out
    }

    /** 从 `start`（引号本身）开始，返回字符串结束后的位置（开区间）。 */
    private fun stringEnd(line: String, start: Int): Int {
        val quote = line[start]
        var index = start + 1
        while (index < line.length) {
            val char = line[index]
            if (char == '\\' && index + 1 < line.length) {
                index += 2
                continue
            }
            if (char == quote) return index + 1
            index += 1
        }
        return line.length
    }

    /** 行号那一栏的宽度（字符数）。`9` → 1，`10` → 2，`1000` → 4。 */
    fun numberWidth(lineCount: Int): Int = lineCount.coerceAtLeast(1).toString().length

    /**
     * 这一页的第一行行号（1-based）。分页读时 `start_line` 是 1-based，
     * 页内的行号要从它接着数，不能每页都从 1 开始——那会让"第几行"这句话失去意义。
     */
    fun firstLineNumber(startLine: Int): Int = startLine.coerceAtLeast(1)
}
