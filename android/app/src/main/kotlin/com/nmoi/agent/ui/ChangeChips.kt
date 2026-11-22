package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 变更卡片（D381 / D383）：助手消息**结尾**那一组文件芯片。
 *
 * 形状照用户 2026-11-22 给的两张参考图：
 *
 * - 气泡里最多 [ChangeList.INLINE_ROWS] 行，一行一个芯片（图标 + 文件名 + `+N −M`）；
 * - 第 4 条起收进 `+N`，点开是「变更的文件」整页；
 * - 点一个芯片开底部面板（路径 / 差异 / 打开方式 / 分享 / 导出）；
 * - **文件不在了只弹一句**，不打开空面板。
 *
 * 为什么芯片里只放文件名、不放路径：一行放不下完整路径，截断了反而更难认。
 * 完整路径在详情页，那里用中段省略（保留头尾）。
 */
@Composable
internal fun ChangeChips(
    changes: List<ChangeRow>,
    onOpenAll: () -> Unit,
    onOpenFile: (ChangeRow) -> Unit,
) {
    if (changes.isEmpty()) return
    val inline = ChangeList.inline(changes)
    val overflow = ChangeList.overflow(changes)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        inline.forEach { row ->
            ChangeChip(row = row, onClick = { onOpenFile(row) })
        }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onOpenAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 一个文件芯片。
 *
 * 视觉：`surfaceContainerHigh` 底、`RoundedCornerShape(10)`、左侧一个文档图标。
 * 右侧的 `+N −M` 用两种颜色分开——**不共用一个加号**（参考图里 `+47` 混着
 * "新增 47 个"和"改了 47 行"两种意思，那是它的毛病，不学）。
 */
@Composable
private fun ChangeChip(row: ChangeRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.FileCode,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = ChangeList.name(row.path),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!ChangeList.previewable(row)) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "无法预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        ChangeCounts(row)
    }
}

/** `+12 −4`。两边都是 0 时什么也不画（纯改名之类）。 */
@Composable
private fun ChangeCounts(row: ChangeRow) {
    val added = row.added ?: 0
    val removed = row.removed ?: 0
    if (added == 0 && removed == 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (added > 0) {
            Text(
                text = "+$added",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (removed > 0) {
            Text(
                text = "−$removed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 「变更的文件」整页（点 `+N` 进来的）。
 *
 * 全量、按路径 A–Z（`ChangeList.sorted` 已经排好，这里不再排）。
 * 每一行整行可点，右侧的 `+N −M` 与气泡里一致。
 */
@Composable
internal fun ChangeListSheet(
    changes: List<ChangeRow>,
    onDismiss: () -> Unit,
    onOpenFile: (ChangeRow) -> Unit,
) {
    val rows = ChangeList.sorted(changes)
    SheetFrame(title = "变更的文件（${rows.size}）", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.forEach { row ->
                ChangeListRow(row = row, onClick = { onOpenFile(row) })
            }
        }
    }
}

/** 整页里的一行：完整路径（中段省略）+ 计数。 */
@Composable
private fun ChangeListRow(row: ChangeRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.FileCode,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = ChangeList.middleEllipsis(row.path, max = 56),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!ChangeList.previewable(row)) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "无法预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        ChangeCounts(row)
    }
}

/**
 * 一个变更文件的详情（点芯片进来的）。
 *
 * 参考图的形状：文件名（大一号）→ `197.8 KB · 11月16日 周一 上午 2:04` →
 * 两行等宽路径 → 三个按钮（打开方式 / 分享 / 导出）。
 *
 * 这里先给**能做的**：路径（可长按复制）、差异、大小与时间。
 * 打开方式 / 分享 / 导出要接 `Intent`，下一批做——**不摆点了没反应的按钮**。
 */
@Composable
internal fun ChangeFileSheet(
    row: ChangeRow,
    onDismiss: () -> Unit,
) {
    val file = File(row.path)
    val size = remember(file) { runCatching { file.length() }.getOrDefault(0L) }
    val modified = remember(file) {
        runCatching { file.lastModified() }.getOrDefault(0L)
    }
    SheetFrame(title = ChangeList.name(row.path), onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 路径：中段省略，长按复制全文。路径的意义在"哪一台的哪个目录"，
            // 掐掉末尾就没用了。
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = ChangeList.middleEllipsis(row.path, max = 96),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = buildString {
                    append(humanSize(size))
                    if (modified > 0) {
                        append(" · ")
                        append(humanTime(modified))
                    }
                    row.added?.let { if (it > 0) append(" · +$it 行") }
                    row.removed?.let { if (it > 0) append(" · −$it 行") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val diff = row.diff
            if (diff == null) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "这个文件没有可看的差异（二进制，或者太大没算）。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.size(12.dp))
                DiffBlock(diff)
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * 差异块：按行上色。
 *
 * `-` 用 `errorContainer`、`+` 用 `primaryContainer`、其余用普通底色。
 * 行号不画：统一差异里 `@@ -a,b +c,d @@` 已经给了位置，再画一列行号要重算，
 * 而这一段的主要用途是"看它改了什么"，不是"跳到第几行"。
 */
@Composable
private fun DiffBlock(diff: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            diff.lineSequence().forEach { line ->
                val (fill, ink) = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        MaterialTheme.colorScheme.surfaceContainerHigh to
                            MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("@@") ->
                        MaterialTheme.colorScheme.surfaceContainerHigh to
                            MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("+") ->
                        MaterialTheme.colorScheme.primaryContainer to
                            MaterialTheme.colorScheme.onPrimaryContainer
                    line.startsWith("-") ->
                        MaterialTheme.colorScheme.errorContainer to
                            MaterialTheme.colorScheme.onErrorContainer
                    else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = if (line.isEmpty()) " " else line,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = ink,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(fill)
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 字节数 → 人话。与存储页同一套口径。 */
internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 时间戳 → `11月16日 周一 上午 2:04`（参考图的写法）。 */
internal fun humanTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val week = when (time.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    val hour = time.hour
    val half = if (hour < 12) "上午" else "下午"
    val shown = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "${time.monthValue}月${time.dayOfMonth}日 $week $half $shown:${"%02d".format(time.minute)}"
}

/**
 * 标题栏下方那条「文件已不存在」，**向上划出消失**（D383）。
 *
 * 为什么不是底部 toast：用户点的是列表里的一行，眼睛在那儿；提示出现在底部
 * 他看不见。为什么向上划走：向下划像"又一个面板要收"，向上划才像"一句话飘走了"。
 */
@Composable
internal fun MissingFileHint(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(1800)
        onDone()
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
