package com.nmoi.agent.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储管理（D216）：**图片附件现在在盘上**，这一页把它们列出来，能看、能导出、能删。
 *
 * 三条口径：
 * - **按会话分组**：附件的归属是会话，用户找图时先想起的是"哪个会话里的那张"，不是 id。
 * - **路径要写出来**：`sessions/<会话>/images/<id>.jpg` 是工作区文件工具能直接读的地方，
 *   用户得知道它到底在哪。
 * - **删除是收不回来的**：走确认；删完立刻从列表里消失，不做"看着还在"的假删除。
 */
@Composable
internal fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val databasePath = remember(context) { ProviderRepository.databasePath(context) }

    var rows by remember { mutableStateOf<List<StoredAttachment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<StoredAttachment?>(null) }
    var exporting by remember { mutableStateOf<StoredAttachment?>(null) }

    LaunchedEffect(reload) {
        loading = true
        rows = withContext(Dispatchers.IO) {
            AttachmentStore.listAll(databasePath).getOrElse { failure ->
                notice = "读不到附件：${failure.message}"
                emptyList()
            }
        }
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = exporting
        exporting = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                AttachmentStore.bytes(databasePath, target.id)
            }
            bytes.fold(
                onSuccess = { data ->
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(data)
                            } ?: error("打不开目标文件")
                        }
                    }
                    notice = if (ok.isSuccess) "已导出 ${data.size / 1024} KB" else "导出失败：${ok.exceptionOrNull()?.message}"
                },
                onFailure = { notice = "导出失败：${it.message}" },
            )
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
                    text = "存储",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Text(
                    text = if (loading) "读取中…" else "${rows.size} 张 · ${sizeText(rows.sumOf { it.size })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val line = notice
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }

            if (!loading && rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "还没有图片附件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "在聊天页「＋ → 图片」发一张，它就会出现在这里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                // 按会话分组（D217）：用户找图时先想起的是"哪个会话里的那张"。
                // 每组一行小标题（标题 · N 张 · 大小），下面才是条目。
                val grouped = rows.groupBy { it.conversation }
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    grouped.forEach { (_, itemsInGroup) ->
                        item(key = "group:${itemsInGroup.first().conversation}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = itemsInGroup.first().title.ifBlank { "（没有标题的会话）" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${itemsInGroup.size} 张 · ${sizeText(itemsInGroup.sumOf { it.size })}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                )
                            }
                        }
                        items(itemsInGroup, key = { it.id }) { row ->
                            StorageRow(
                                row = row,
                                databasePath = databasePath,
                                onExport = {
                                    exporting = row
                                    exportLauncher.launch("nmoi-${row.id.take(8)}.${extensionOf(row.mime)}")
                                },
                                onDelete = { confirmDelete = row },
                            )
                        }
                    }
                }
            }
        }
    }

    val target = confirmDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删掉这张图？") },
            text = {
                Text(
                    "文件会从盘上删掉，收不回来。这条消息本身还在，只是不再有这张图。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            AttachmentStore.delete(databasePath, target.id)
                        }
                        notice = deleted.fold(
                            onSuccess = { ok -> if (ok) "已删除" else "这张图已经不在了" },
                            onFailure = { "删除失败：${it.message}" },
                        )
                        reload += 1
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StorageRow(
    row: StoredAttachment,
    databasePath: String,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(row.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(row.id) {
        bitmap = AttachmentStore.bitmap(databasePath, row.id)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "附件缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("读不出", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title.ifBlank { "（没有标题的会话）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.mime} · ${sizeText(row.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // 路径写出来：工作区文件工具读的就是这个文件。
                Text(
                    text = row.path?.substringAfterLast("/sessions/")?.let { "sessions/$it" }
                        ?: "还在库里（第一次查看时会搬到盘上）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextAction(label = "导出", onClick = onExport)
            TextAction(label = "删除", danger = true, onClick = onDelete)
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun extensionOf(mime: String): String = when (mime) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/bmp" -> "bmp"
    else -> "bin"
}
