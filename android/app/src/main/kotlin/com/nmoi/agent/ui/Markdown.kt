package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 最小 Markdown 渲染器。**自己写的，不引库**（理由在 D131）：
 * 一个 Compose 的 Markdown 库要跟着 BOM 走，而我们只用到会话里真实出现的那几样。
 *
 * 覆盖（够 Agent 回答用）：
 * - 块：`#` 标题（1–6 级）、围栏代码块 ``` ```、无序 / 有序列表、引用 `>`、分隔线 `---`、段落
 * - 行内：`**粗**`、`*斜*`/`_斜_`、`~~删除线~~`、`` `行内代码` ``、`[文字](链接)`（只上色，不跳）
 *
 * **不做**（写清楚，免得被当成"该有效果却没有"）：
 * - 表格、脚注、HTML、嵌套列表、引用里的引用、图片 —— 原样显示文字；
 * - 行内样式**不嵌套**（`**粗里有 *斜***` 只认外层）；
 * - 链接不跳转（还没有"链接该在应用内开还是丢给浏览器"的决定）。
 *
 * 数学公式也不在这里：`$…$` 现在按等宽原样显示，排版引擎是单独一层（D131）。
 */
internal enum class MdKind { Paragraph, Heading, Code, Bullet, Numbered, Quote, Rule }

internal data class MdBlock(
    val kind: MdKind,
    val text: String,
    /** 标题级别 1–6；有序列表的序号；其余为 0。 */
    val level: Int = 0,
    /** 围栏代码块的语言标记（可能为空）。 */
    val lang: String = "",
)

private val HeadingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val BulletRegex = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
private val NumberedRegex = Regex("^\\s{0,3}(\\d{1,3})[.)]\\s+(.*)$")
private val RuleRegex = Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$")

/**
 * 切块。按行扫，不递归——递归在"引用里有列表、列表里有代码块"时会失控，
 * 而那种嵌套在本项目的真实对话里很少见。
 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var index = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock(MdKind.Paragraph, paragraph.toString().trim())
            paragraph.setLength(0)
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()

        // 围栏代码块：连同语言标记一起收到块里，渲染时用等宽字体原样显示。
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val body = StringBuilder()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                body.append(lines[index]).append('\n')
                index += 1
            }
            // 跳过结尾那行 ```（没有结尾也能渲染：宁可显示完整内容，不要吞掉）
            if (index < lines.size) index += 1
            blocks += MdBlock(MdKind.Code, body.toString().trimEnd('\n'), lang = lang)
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            index += 1
            continue
        }

        val heading = HeadingRegex.find(line)
        if (heading != null) {
            flushParagraph()
            blocks += MdBlock(
                kind = MdKind.Heading,
                text = heading.groupValues[2].trim(),
                level = heading.groupValues[1].length,
            )
            index += 1
            continue
        }

        if (RuleRegex.matches(line)) {
            flushParagraph()
            blocks += MdBlock(MdKind.Rule, "")
            index += 1
            continue
        }

        val numbered = NumberedRegex.find(line)
        if (numbered != null) {
            flushParagraph()
            blocks += MdBlock(
                kind = MdKind.Numbered,
                text = numbered.groupValues[2].trim(),
                level = numbered.groupValues[1].toIntOrNull() ?: 1,
            )
            index += 1
            continue
        }

        val bullet = BulletRegex.find(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MdBlock(MdKind.Bullet, bullet.groupValues[1].trim())
            index += 1
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks += MdBlock(MdKind.Quote, trimmed.removePrefix(">").trim())
            index += 1
            continue
        }

        // 普通行：并进当前段落。保留换行（聊天里常常是"一句话一行"，合成一行反而难读）。
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line.trimEnd())
        index += 1
    }
    flushParagraph()
    return blocks
}

/**
 * 行内样式。扫描器写法：从左往右认标记，认到就找它的收尾，收尾找不到就当普通字符输出
 * （半截标记不该让整条消息变形）。
 */
internal fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                }

                text.startsWith("~~", i) && text.indexOf("~~", i + 2) > 0 -> {
                    val end = text.indexOf("~~", i + 2)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                }

                text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }

                // 公式：原样、等宽、带底。排版引擎还没接，不给它加"看起来渲染过"的样式（D131）。
                text[i] == '$' && text.indexOf('$', i + 1) > 0 -> {
                    val end = text.indexOf('$', i + 1)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                        ),
                    ) {
                        append(text.substring(i, end + 1))
                    }
                    i = end + 1
                }

                (text[i] == '*' || text[i] == '_') && text.indexOf(text[i], i + 1) > 0 -> {
                    val end = text.indexOf(text[i], i + 1)
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }

                text[i] == '[' && text.indexOf("](", i) > 0 -> {
                    val labelEnd = text.indexOf("](", i)
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd < 0) {
                        append(text[i])
                        i += 1
                    } else {
                        withStyle(SpanStyle(color = linkColor)) {
                            append(text.substring(i + 1, labelEnd))
                        }
                        i = urlEnd + 1
                    }
                }

                else -> {
                    append(text[i])
                    i += 1
                }
            }
        }
    }

/**
 * 渲染一段 Markdown。助手消息的正文、之后用户消息（如果也想渲染）都走这里。
 *
 * 每个块之间 6dp；代码块和引用有自己的容器，别让它们和正文黏在一起。
 */
@Composable
internal fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainer
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MdKind.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                MdKind.Code -> Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = codeBackground,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (block.lang.isNotEmpty()) {
                            Text(
                                text = block.lang,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                MdKind.Heading -> Text(
                    text = inlineMarkdown(block.text, codeBackground, linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        3 -> 16.sp
                        else -> 15.sp
                    },
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = inlineColor,
                    modifier = Modifier.padding(top = 2.dp),
                )

                MdKind.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        text = inlineMarkdown(block.text, codeBackground, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = inlineColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                MdKind.Numbered -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${block.level}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        text = inlineMarkdown(block.text, codeBackground, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = inlineColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                MdKind.Quote -> Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(2.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text(
                        text = inlineMarkdown(block.text, codeBackground, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                MdKind.Paragraph -> Text(
                    text = inlineMarkdown(block.text, codeBackground, linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = inlineColor,
                )
            }
        }
    }
}