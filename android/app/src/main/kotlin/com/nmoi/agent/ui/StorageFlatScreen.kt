@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nmoi.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.Files.humanFileTime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储子页的**平铺 + 快捷操作**那一种（D434）：图片 / 文件。
 *
 * 数据源与「会话文件」是同一批东西（`sessions/<id>/chat` 与 `tmp` 底下那些），
 * 只是按**类型**聚了一份。这一页存在的目的只有一个：**快捷预览与删除**
 * （用户原话：「可以用这两种分类页面快捷的预览和删除操作什么的」）。
 *
 * 所以这里保留平铺、保留多选、保留长按菜单——与另外两种形状不同，这一页要的就是
 * "一次能连着处理好几份"。
 *
 * # 长按菜单与多选（用户 2026-11-25 明确要的）
 *
 * 长按一行出底部浮层：「多选 / 删除 / 导出」。进多选后行首换勾选框、
 * 底部换动作条（删除 / 导出 / 退出），与抽屉那套一致。
 *
 * **删除走确认卡**，卡里写清会没掉多少；导出走 7z（标准压缩等级），
 * 单个文件也打成包——用户原话是"依旧是标准压缩等级 7z"，那就没有"单个文件直接拷"这一支。
 */
@Composable
internal fun StorageFlatScreen(
    kind: StorageKind,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember(kind) { mutableStateOf<List<StorageRepository.Item>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var notice by remember(kind) { mutableStateOf<String?>(null) }
    var selecting by remember(kind) { mutableStateOf(false) }
    var chosen by remember(kind) { mutableStateOf(emptySet<String>()) }
    /** 长按出来那一行的菜单。`null` = 没开。 */
    var menuFor by remember(kind) { mutableStateOf<StorageRepository.Item?>(null) }
    var confirmDelete by remember(kind) { mutableStateOf<List<StorageRepository.Item>>(emptyList()) }
    var busy by remember(kind) { mutableStateOf<String?>(null) }
    var reload by remember(kind) { mutableStateOf(0) }

    LaunchedEffect(kind, reload) {
        loading = true
        items = withContext(Dispatchers.IO) {
            runCatching { StorageRepository.items(context, kind) }
                .getOrElse {
                    notice = "读不出来：${it.message}"
                    emptyList()
                }
        }
        loading = false
        PageReady.notifyReadyAfterFrame()
    }

    // 导出：先让用户选目标位置，再交给 FFI 打 7z。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-7z-compressed"),
    ) { uri ->
        val pending = busy
        busy = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在打包…"
            val result = withContext(Dispatchers.IO) {
                StorageExport.exportTo(context, uri, kind, chosenItems(items, chosen))
            }
            busy = null
            result.fold(
                onSuccess = { line ->
                    notice = line
                    selecting = false
                    chosen = emptySet()
                },
                onFailure = { notice = "导出失败：${it.message}" },
            )
        }
    }

    val total = items.sumOf { it.bytes }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    TextButton(onClick = {
                        selecting = false
                        chosen = emptySet()
                    }) { Text("取消") }
                } else {
                    BackButton(onBack = onBack)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = kind.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            loading -> "统计中…"
                            selecting -> "已选 ${chosen.size} 项 · ${formatBytes(chosenItems(items, chosen).sumOf { it.bytes })}"
                            items.isEmpty() -> "空"
                            else -> "${items.size} 个文件 · ${formatBytes(total)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!selecting && items.isNotEmpty()) {
                    Text(
                        text = if (chosen.size == items.size) "取消全选" else "全选",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    selecting = true
                                    chosen = if (chosen.size == items.size) {
                                        emptySet()
                                    } else {
                                        items.map { it.relative }.toSet()
                                    }
                                },
                                onLongClick = {},
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            busy?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            if (!loading && items.isEmpty()) {
                EmptyKind(kind)
            } else {
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
                    items(items, key = { it.relative }) { item ->
                        DetailRow(
                            item = item,
                            selecting = selecting,
                            checked = item.relative in chosen,
                            onClick = {
                                if (selecting) {
                                    chosen = if (item.relative in chosen) {
                                        chosen - item.relative
                                    } else {
                                        chosen + item.relative
                                    }
                                } else {
                                    notice = "「${item.relative}」· ${formatBytes(item.bytes)} · ${humanFileTime(item.modifiedMs)}"
                                }
                            },
                            onLongClick = {
                                if (!selecting) menuFor = item
                            },
                        )
                    }
                    item(key = "tail") { Spacer(Modifier.height(80.dp)) }
                }
            }

            // 底部：多选时是动作条，否则什么都不摆（这一页的主操作在长按菜单里）。
            if (selecting) {
                BulkBar(count = chosen.size) {
                    BulkText(
                        label = "导出 7z",
                        onClick = {
                            if (chosen.isEmpty()) return@BulkText
                            busy = "准备打包…"
                            exportLauncher.launch(
                                StorageExport.suggestedName(kind, chosen.size),
                            )
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    BulkText(
                        label = "删除",
                        danger = true,
                        enabled = kind.deletable,
                        onClick = { confirmDelete = chosenItems(items, chosen) },
                    )
                }
            }
        }
    }

    // ---- 长按菜单 ----
    menuFor?.let { target ->
        SheetFrame(title = target.relative, onDismiss = { menuFor = null }) {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                SheetAction("多选") {
                    menuFor = null
                    selecting = true
                    chosen = setOf(target.relative)
                }
                SheetAction("导出 7z") {
                    menuFor = null
                    chosen = setOf(target.relative)
                    busy = "准备打包…"
                    exportLauncher.launch(StorageExport.suggestedName(kind, 1))
                }
                SheetAction("删除", danger = kind.deletable, enabled = kind.deletable) {
                    menuFor = null
                    confirmDelete = listOf(target)
                }
                if (!kind.deletable) {
                    Text(
                        text = "这一项不给删除：删了聊天记录就没了。要留档请用「备份」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (confirmDelete.isNotEmpty()) {
        val bytes = confirmDelete.sumOf { it.bytes }
        AlertDialog(
            onDismissRequest = { confirmDelete = emptyList() },
            title = { Text("删掉这 ${confirmDelete.size} 个文件？") },
            text = {
                Text(
                    "${formatBytes(bytes)} 会从盘上删掉，收不回来。\n" +
                        "需要留档就先「导出 7z」。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = confirmDelete.map { it.relative }
                    confirmDelete = emptyList()
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            StorageRepository.delete(context, kind, targets)
                        }
                        notice = removed.fold(
                            onSuccess = { count -> "已删掉 $count 个文件" },
                            onFailure = { "删除失败：${it.message}" },
                        )
                        selecting = false
                        chosen = emptySet()
                        reload += 1
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = emptyList() }) { Text("取消") }
            },
        )
    }
}
/** 按当前选择集取出条目，**按列表顺序**——导出包里条目的先后就是屏幕上看到的先后。 */
private fun chosenItems(
    items: List<StorageRepository.Item>,
    chosen: Set<String>,
): List<StorageRepository.Item> = items.filter { it.relative in chosen }

@Composable
private fun DetailRow(
    item: StorageRepository.Item,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                SelectBox(checked = checked)
                Spacer(Modifier.size(12.dp))
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.file.extension.take(3).uppercase().ifEmpty { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatBytes(item.bytes))
                        if (item.modifiedMs > 0) {
                            append(" · ")
                            append(humanFileTime(item.modifiedMs))
                        }
                        append(" · ")
                        append(item.relative)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
/** 底部浮层里的一行动作。删除用 error 色；不可用时灰着并说明为什么。 */
@Composable
private fun SheetAction(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (enabled) onClick() },
                onLongClick = {},
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
