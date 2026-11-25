@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nmoi.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
 * 存储空间的一项（D426）。
 *
 * 一个页面、按 [StorageKind] 换数据源与可用动作，不给十项各写一屏——
 * 它们的差别只有"数据从哪来、能做什么"，那两样都是参数。
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
internal fun StorageDetailScreen(
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
                            StorageRepository.delete(context, targets)
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

/** 空态：一句说明 + 一句下一步。不写"暂无数据"。 */
@Composable
private fun EmptyKind(kind: StorageKind) {
    val (line, hint) = when (kind) {
        StorageKind.Images -> "还没有图片附件" to "在聊天页「＋ → 图片」发一张，它就会出现在这里。"
        StorageKind.Files -> "还没有发过文件" to "在聊天页「＋ → 文件」发一个。"
        StorageKind.Chat -> "还没有聊天记录" to "发一条消息就会建库。"
        StorageKind.Cache -> "缓存是空的" to "用一段时间之后这里会有缩略图与临时文件。"
        StorageKind.Logs -> "还没有日志" to "在「调试 → 日志」里打开一个通道就会开始写。"
        StorageKind.Other -> "没有其他文件" to "导入的字体、语音运行时会落在这里。"
        StorageKind.Workspace -> "工作区还是空的" to "在「工具与工作区 → 工作区」里建一个，或者让 AI 写个文件。"
        StorageKind.Envir -> "还没装隔离环境" to "在「工具与工作区 → 环境」里装一次。"
        StorageKind.Skills -> "还没装技能" to "技能包解开之后落在这里。"
        StorageKind.SessionFiles -> "会话目录里没有别的文件" to "AI 写进 /chat 的输出会出现在这里。"
    }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * 导出的落点（D425）。
 *
 * 两件事：把选中的文件写成一份 7z；以及给这次导出起个名字。
 *
 * 走 `nmoi_sevenz_write_json`（C ABI），不自己压：压缩器要跟解压器对得上，
 * 而那一份是拿 `7z` 命令行逐字节验过的。
 */
internal object StorageExport {

    /** 建议的文件名：`nmoi-图片-12项.7z`。带项数是因为用户常常连导几次。 */
    fun suggestedName(kind: StorageKind, count: Int): String = "nmoi-${kind.title}-${count}项.7z"

    /**
     * 导出到 `uri`。
     *
     * 中间落一份临时包再拷进 SAF：`ContentResolver` 给的是流，而 7z 写入要 `Seek`
     * （签名头在最前面、要回头补）。所以先写到 `cacheDir` 里，再整份搬过去。
     * 失败时那份临时包要删掉——留着会在缓存里堆出用户看不见的垃圾。
     */
    suspend fun exportTo(
        context: android.content.Context,
        uri: android.net.Uri,
        kind: StorageKind,
        items: List<StorageRepository.Item>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (items.isEmpty()) error("没有选中任何文件")
            if (!NativeBridge.loaded) error("原生层没加载")
            val root = context.filesDir.absolutePath.trimEnd('/')
            val cacheRoot = context.cacheDir.absolutePath.trimEnd('/')
            val spec = org.json.JSONObject()
            val dest = File(context.cacheDir, "nmoi-export-${System.currentTimeMillis()}.7z")
            spec.put("dest", dest.absolutePath)
            val list = org.json.JSONArray()
            for (item in items) {
                // 包内名字用相对 App 私有目录的那一段：解出来就是 `sessions/...`、`workspace/...`，
                // 与它在盘上的位置对得上。`cache/` 前缀要换算成 cacheDir 底下的相对路径。
                val name = item.relative.trimStart('/')
                val absolute = if (name.startsWith("cache/")) {
                    File(cacheRoot, name.removePrefix("cache/")).absolutePath
                } else {
                    File(root, name).absolutePath
                }
                list.put(
                    org.json.JSONObject()
                        .put("name", name)
                        .put("path", absolute),
                )
            }
            spec.put("entries", list)

            try {
                val envelope = org.json.JSONObject(NativeBridge.sevenzWriteJson(spec.toString()))
                if (!envelope.ok()) error(envelope.errorText())
                val bytes = dest.length()
                // 拷进 SAF。`openOutputStream` 拿不到就是用户把目标位置取消了。
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("打不开目标文件")
                out.use { stream ->
                    dest.inputStream().use { input -> input.copyTo(stream) }
                }
                "已导出 ${items.size} 个文件 · ${formatBytes(bytes)}"
            } finally {
                // 成功与失败都要删：临时包留着没人认领。
                if (dest.exists()) dest.delete()
            }
        }
    }
}
