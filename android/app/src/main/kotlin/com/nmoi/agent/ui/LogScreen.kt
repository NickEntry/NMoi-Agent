package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 查看窗口取多少字节：够看一次启动，又不至于让 Compose 排版一屏大文本卡住。 */
private const val VIEW_BYTES = 64 * 1024

/** 复制取多少：剪贴板塞 8 MB 会把输入法和其他应用一起拖慢，尾部这些已经能定位问题。 */
private const val COPY_BYTES = 256 * 1024

/**
 * 日志页（设置 → 调试 → 日志）。
 *
 * 四个动作对应四件不同的事，别混：
 * - **查看**：在页面里读尾部，不跳出应用（跳出就没有"边看边改"的手感了）；
 * - **复制**：进剪贴板，能直接贴给我——不用 adb、不用数据线；
 * - **导出**：写进用户自己选的位置（SAF），整份，适合留档或发邮件；
 * - **清除**：真的删文件，所以有二次确认。
 *
 * 上限那排档位是**写进 SharedPreferences 就生效**的：下一次 `NLog` 写入就会按新上限轮转，
 * 不需要重启应用，也不需要清理已经写下的内容。
 */
@Composable
internal fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sizeBytes by remember { mutableStateOf(0L) }
    var limitBytes by remember { mutableStateOf(NLog.limitBytes) }
    var preview by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    suspend fun refreshSize(): Long = withContext(Dispatchers.IO) { NLog.fileSize(context) }

    fun reloadPreview() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { NLog.readTail(context, VIEW_BYTES) }
            preview = text.ifEmpty { "（还没有日志：先把上面的开关打开，再回来）" }
            sizeBytes = refreshSize()
        }
    }

    LaunchedEffect(Unit) { sizeBytes = refreshSize() }

    // 导出：SAF 让用户自己挑位置。这里不自己拼路径——Android 10 之后乱写外部存储会被拒。
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            message = "已取消导出"
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = NLog.readAll(context)
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("打不开目标文件")
                        text.length
                    }
                }
                message = result.fold(
                    onSuccess = { "已导出 ${it} 个字符" },
                    onFailure = { "导出失败：${it.message ?: it.javaClass.simpleName}" },
                )
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Text(
                text = "当前 ${formatBytes(sizeBytes)} / 上限 ${formatBytes(limitBytes)}" +
                    "（超了自动丢最旧的，保留最新一半）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(
                text = "文件：files/logs/${LogFile.FILE_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Text(
                text = "上限",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NLog.LIMIT_CHOICES_MB.forEach { megabytes ->
                    LimitChip(
                        label = "$megabytes MB",
                        selected = limitBytes == megabytes * 1024L * 1024L,
                        onClick = {
                            val bytes = megabytes * 1024L * 1024L
                            NLog.setLimitBytes(context, bytes)
                            limitBytes = bytes
                            message = "上限改成 $megabytes MB，下一次写入就按它轮转"
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (preview == null) {
                            reloadPreview()
                        } else {
                            preview = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (preview == null) "查看日志" else "收起") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { NLog.readTail(context, COPY_BYTES) }
                            if (text.isEmpty()) {
                                message = "还没有日志可复制"
                            } else {
                                val clipboard = context
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NMoi 日志", text))
                                message = "已复制 ${text.length} 个字符（尾部）"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制日志") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val stamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                        exportLauncher.launch("nmoi-log-$stamp.log")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出日志") }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                ) { Text("清除日志") }
            }

            if (message != null) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // 预览区自己滚：外层不能也包 verticalScroll，两个无限高会打架（同设置页网格那条注释）。
            val previewText = preview
            if (previewText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除日志？") },
            text = { Text("${formatBytes(sizeBytes)} 会被删掉，收不回来。需要留档就先用「导出日志」。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { NLog.clear(context) }
                        preview = null
                        sizeBytes = refreshSize()
                        message = "已清除"
                    }
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LimitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 人看的体积。日志页上出现 `8380416 字节` 没人愿意换算。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
