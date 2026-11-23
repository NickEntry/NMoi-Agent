package com.nmoi.agent.ui.Files

/**
 * 工具输出被降级归档之后，正文里那一行标记（D398）。
 *
 * core 侧（`crates/core/src/form.rs`）在把一段输出换成标记时，写的是这几种形状：
 *
 * ```text
 * [… 8.2k tok 已归档 ref#a3f9c1 → /archive/a3f9c1.txt | 失败那次的构建日志]
 * [output masked: 8.2k tok, ref#a3f9c1 → /archive/a3f9c1.txt | …]
 * [archived ref#a3f9c1 | 8200 tok | tool_result | … | /archive/a3f9c1.txt | …]
 * ```
 *
 * 界面上要做两件事：**认出它**（给一颗「摘要化」的标记）、**点得开**（跳到归档原文）。
 * 两件事都靠从这行里抠出 `/archive/xxx.txt` 这个路径。
 *
 * 为什么抠路径而不是抠 `ref#`：路径能直接交给那套文件预览页（`FilePreviewScreen`），
 * 而 `ref#` 还要再查一次库。一个东西能用一个标识指到就够了。
 */
internal data class ArchivedOutput(
    /** 归档文件的路径（`/archive/a3f9c1.txt`）。 */
    val path: String,
    /** 原来多少 token（标记里那个 `8.2k`）。抠不出来就是 0。 */
    val tokens: Int,
    /** 索引摘要（标记里 `|` 之后那一句）。没有就是空串。 */
    val summary: String,
) {
    /** 给用户看的那一行：`摘要化 · 8.2k tok`。 */
    val label: String
        get() = if (tokens > 0) "摘要化 · ${formatK(tokens)} tok" else "摘要化"
}

internal object ArchivedMarker {

    /** 归档路径的形状：`/archive/<hex>.txt`。 */
    private val PATH = Regex("""/archive/[A-Za-z0-9_-]+\.txt""")

    /** 标记里的 token 数：`8.2k` / `8200` / `1.2m`。 */
    private val TOKENS = Regex("""(\d+(?:\.\d+)?)\s*([km])?\s*tok""", RegexOption.IGNORE_CASE)

    /**
     * 从一段输出里认出标记。**认不出就是 `null`**，调用方据此不画那颗字。
     *
     * 判据是"整段里有一行以 `[` 开头、且含 `/archive/` 路径"。不要求行首严格匹配
     * `[archived`：三种形态的前缀不同，而它们的共同点只有那个路径。
     * 这一条比逐个匹配三种前缀稳——core 那边以后加第四种形态，这里不用跟着改。
     */
    fun parse(text: String): ArchivedOutput? {
        if (text.isEmpty()) return null
        val path = PATH.find(text)?.value ?: return null
        // 只在**含这个路径的那一行**里找 token 数与摘要：整段里可能还有别的数字
        // （`exit 0 · 202ms`），拿整段找会把 202 当成 token 数。
        val line = text.lineSequence().firstOrNull { it.contains(path) }.orEmpty()
        val tokens = TOKENS.find(line)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val scale = when (match.groupValues[2].lowercase()) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                else -> 1.0
            }
            (value * scale).toInt()
        } ?: 0
        // 摘要：最后一个 `|` 之后、去掉结尾的 `]`。
        val summary = line
            .substringAfterLast('|', "")
            .trim()
            .removeSuffix("]")
            .trim()
        return ArchivedOutput(path = path, tokens = tokens, summary = summary)
    }

    /**
     * 这段输出**整体**被换成了标记吗（而不是头尾各留一段、中间插一行）？
     *
     * 判据：去掉标记行之后几乎没有内容。这一条决定界面怎么画：
     * - 整体被换掉：stdout 那个页签里只剩一行标记，那就直接给「摘要化」提示，
     *   不必画一屏空行；
     * - 头尾留了内容：正文照画，标记行也在里面，右上一个「摘要化」让人点。
     */
    fun replacedWhole(text: String): Boolean {
        val parsed = parse(text) ?: return false
        val rest = text.lines()
            .filter { !it.contains(parsed.path) }
            .joinToString("")
            .trim()
        return rest.isEmpty()
    }

    /** 这一段里的归档路径（点开时用）。没有就是 `null`。 */
    fun pathOf(text: String): String? = parse(text)?.path
}

/** `8200` → `8.2k`。与 core 那边的 `format_k` 同一套口径。 */
internal fun formatK(tokens: Int): String = when {
    tokens < 1_000 -> "$tokens"
    tokens < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", tokens / 1_000.0)
    else -> String.format(java.util.Locale.US, "%.1fm", tokens / 1_000_000.0)
}
