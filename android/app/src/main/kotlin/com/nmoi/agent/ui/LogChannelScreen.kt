package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 查看窗口取多少字节：够看一次启动，又不至于让 Compose 排版一屏大文本卡住。 */
private const val VIEW_BYTES = 64 * 1024

/** 复制取多少：剪贴板塞 8 MB 会把输入法和其他应用一起拖慢，尾部这些已经能定位问题。 */
private const val COPY_BYTES = 256 * 1024

/**
 * 单个通道的子页：**四个动作，只作用在这一种日志上**。
 *
 * - **查看**：页内读尾部，不跳出应用（跳出就没有"边看边改"的手感）；
 * - **复制**：进剪贴板，能直接贴出来，不用 adb、不用数据线；
 * - **导出**：SAF 写进用户选的位置，整份，适合留档；
 * - **清除**：真删文件，所以有二次确认。
 *
 * 开关与上限**不在这页**：它们在齿轮页（日志设置）里。一页一件事。
 */
@Composable
internal fun LogChannelScreen(kind: LogKind, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sizeBytes by remember { mutableStateOf(0L) }
    var limitBytes by remember { mutableStateOf(NLog.limitBytes(kind)) }
    var preview by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    suspend fun refreshSize() {
        sizeBytes = withContext(Dispatchers.IO) { NLog.fileSize(context, kind) }
        limitBytes = NLog.limitBytes(kind)
    }

    fun reloadPreview() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { NLog.readTail(context, kind, VIEW_BYTES) }
            preview = text.ifEmpty {
                "（这一类还没有内容：${kind.pendingProducer ?: "先做点会产生日志的操作"}）"
            }
            refreshSize()
        }
    }

    LaunchedEffect(kind) { refreshSize() }

    // 导出走 SAF：Android 10 之后自己拼外部存储路径会被拒。
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            message = "已取消导出"
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = NLog.readAll(context, kind)
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("打不开目标文件")
                        text.length
                    }
                }
                message = result.fold(
                    onSuccess = { "已导出 $it 个字符" },
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
                    text = kind.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Text(
                text = "${formatBytes(sizeBytes)} / ${formatBytes(limitBytes)}" +
                    "（超了自动丢最旧的，保留最新一半）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(
                text = "文件：files/logs/${kind.fileName}" +
                    if (NLog.isEnabled(kind)) "" else " · 这一通道现在是关的，不会再写入",
                style = MaterialTheme.typography.labelSmall,
                color = if (NLog.isEnabled(kind)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (kind.pendingProducer != null) {
                Text(
                    text = "还没有产生者：${kind.pendingProducer}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
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
                            val text = withContext(Dispatchers.IO) { NLog.readTail(context, kind, COPY_BYTES) }
                            if (text.isEmpty()) {
                                message = "这一类还没有日志可复制"
                            } else {
                                val clipboard = context
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NMoi ${kind.label}", text))
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
                        exportLauncher.launch("nmoi-${kind.id}-$stamp.log")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出日志") }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                ) { Text("清除日志") }
            }

            val note = message
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // 预览区自己滚：外层不能也包 verticalScroll，两个无限高会打架。
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
            title = { Text("清除${kind.label}？") },
            text = { Text("${formatBytes(sizeBytes)} 会被删掉，收不回来。需要留档就先用「导出日志」。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { NLog.clear(context, kind) }
                        preview = null
                        refreshSize()
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
