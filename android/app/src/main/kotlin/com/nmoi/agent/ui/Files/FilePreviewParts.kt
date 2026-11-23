package com.nmoi.agent.ui.Files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmoi.agent.ui.MarkdownBody

/**
 * 源码页（D392）：**行号一列、文本一列**，两栏分开。
 *
 * 为什么不做成"把行号拼进字符串"：用户复制代码时不想要行号，而拼进去之后
 * 复制出来就带着——`SelectionContainer` 选中的是屏幕上那一份文本，它不认
 * "这半截是行号"。
 *
 * 折行默认关：开着折行时一个源行会占好几个渲染行，行号那一列要么错位、
 * 要么得按"一个源行占几个渲染行"补空行号。前者比没有行号更糟，
 * 后者要多算一遍排版——第一版先关掉，把选择权留给顶栏那颗开关。
 */
@Composable
internal fun SourceView(
    text: FileText,
    language: String?,
    wrap: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = remember(text.content) { text.content.split('\n') }
    val width = remember(text) { SourceLexer.numberWidth(text.endLine) }
    // 三个记号的颜色在这里取一次：`tokenColor` 是 @Composable，不能在 `remember` 的
    // lambda 里调（那里不是组合上下文）。
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val stringColor = MaterialTheme.colorScheme.primary
    val numberColor = MaterialTheme.colorScheme.tertiary
    val gutter = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val body = MaterialTheme.colorScheme.onSurface

    // 每一行的着色段算一次就够：滚动时每进一屏都重算，长文件会拖住列表。
    val styled = remember(lines, language, width, commentColor, stringColor, numberColor) {
        lines.mapIndexed { index, line ->
            val number = (text.startLine + index).toString().padStart(width)
            number to colored(line, language, commentColor, stringColor, numberColor)
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(styled.size) { index ->
            val (number, content) = styled[index]
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                    ),
                    color = gutter,
                    modifier = Modifier.padding(start = 12.dp, end = 10.dp),
                )
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                        color = body,
                        softWrap = wrap,
                        modifier = if (wrap) {
                            Modifier.fillMaxWidth().padding(end = 12.dp)
                        } else {
                            Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp)
                        },
                    )
                }
            }
        }
        val more = text.moreLabel()
        if (more != null) {
            item {
                Text(
                    text = more,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 一行文本按着色段拼出来。`language` 为 `null` 时不上色，直接给原文。 */
private fun colored(
    line: String,
    language: String?,
    comment: Color,
    string: Color,
    number: Color,
): AnnotatedString {
    if (language == null || line.isEmpty()) return AnnotatedString(line)
    val spans = SourceLexer.spans(line, language)
    if (spans.isEmpty()) return AnnotatedString(line)
    return buildAnnotatedString {
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) append(line.substring(cursor, span.start))
            val color = when (span.token) {
                SourceToken.Comment -> comment
                SourceToken.String -> string
                SourceToken.Number -> number
            }
            withStyle(SpanStyle(color = color)) {
                append(line.substring(span.start, span.end))
            }
            cursor = span.end
        }
        if (cursor < line.length) append(line.substring(cursor))
    }
}

/** Markdown 渲染页。复用正文那一套（`MarkdownBody`），不另写一份解析。 */
@Composable
internal fun MarkdownPage(text: FileText, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        MarkdownBody(text = text.content)
        val more = text.moreLabel()
        if (more != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "$more（这一页只到第 ${text.endLine} 行）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 详情页（D392）：APK / EXE / 压缩包 / 二进制走这里。
 *
 * 三颗按钮（打开方式 / 分享 / 导出）**这一批只摆位置不接**——按项目的规矩
 * 「空位可以，空响应不行」：点了要在下面那行写清"差什么、等哪一层"，
 * 而不是给一颗点了什么都不发生的按钮。
 */
@Composable
internal fun BinaryPage(
    row: FileRow,
    stat: FileStat?,
    onHint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(humanFileSize(stat?.sizeBytes ?: row.sizeBytes))
                        val modified = stat?.modifiedMs ?: row.modifiedMs
                        if (modified > 0) {
                            append(" · ")
                            append(humanFileTime(modified))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = row.path,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "这种文件没有可以看的内容，只能拿出去用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (label in listOf("打开方式", "分享", "导出")) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .androidxClickable { onHint("「$label」还没接：要等 Android 的 Intent 那一层（下一批）") }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** `Modifier.clickable` 的短名（这个文件里要用三次，写全名太长）。 */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))

/**
 * 读不出文本时的那一屏（D392）。
 *
 * **三种原因各说各的话**：太大、不是文本、读失败。合成一句"读取失败"，
 * 排查方向直接跑偏——用户会去查权限，而真正的原因是 NUL 字节。
 */
@Composable
internal fun UnreadablePage(reason: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一屏占位：还在读。用一句灰字，不用转圈——转圈会让整页跟着跳一下。 */
@Composable
internal fun LoadingPage(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "正在读…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 宽度占位（行尾与屏幕边之间留一点）。 */
internal val EDGE = 12.dp

/** 副标题与标题之间那 2dp。 */
internal val TITLE_GAP = 2.dp

/** 行与行之间不留白（分隔线自己管）。 */
internal val ROW_GAP = Arrangement.spacedBy(0.dp)

/** 一个 `Spacer` 的短写法。 */
internal fun Modifier.gap(dp: androidx.compose.ui.unit.Dp = 8.dp): Modifier =
    this.then(Modifier.width(dp))
