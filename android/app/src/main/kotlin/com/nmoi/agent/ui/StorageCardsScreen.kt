package com.nmoi.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储子页的**卡片**那一种（D434）：缓存 / 日志 / 其他 / 聊天记录。
 *
 * 判据只有一条：**这一页里一行文件都没有**。有文件行就说明它该是树形或平铺，
 * 写成卡片只是把文件清单又包了一层壳。
 *
 * 为什么不给文件列表：缓存里那些名字是哈希，日志列表在这里等于把"查看"重做一遍，
 * 而聊天记录里那三个文件（`nmoi.db` / `-wal` / `-shm`）用户既删不得也认不出。
 * 列出来只会让人不敢动。
 */
@Composable
internal fun StorageCardsScreen(
    kind: StorageKind,
    databasePath: String,
    onBack: () -> Unit,
    onOpenLog: (LogKind) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notice by remember(kind) { mutableStateOf<String?>(null) }
    var busy by remember(kind) { mutableStateOf<String?>(null) }
    var reload by remember(kind) { mutableStateOf(0) }
    /** 三项尺寸：缓存是 1 个，日志是 3 个，聊天记录是 3 个。按行号对上。 */
    var sizes by remember(kind) { mutableStateOf(List(3) { 0L }) }

    LaunchedEffect(kind, reload) {
        sizes = withContext(Dispatchers.IO) {
            when (kind) {
                StorageKind.Cache -> listOf(StorageRepository.cacheBytes(context), 0L, 0L)
                StorageKind.Logs -> LogKind.entries.map { NLog.fileSize(context, it) }
                StorageKind.Chat -> listOf("", "-wal", "-shm").map { suffix ->
                    runCatching { File(databasePath + suffix).length() }.getOrDefault(0L)
                }
                else -> listOf(0L, 0L, 0L)
            }
        }
        PageReady.notifyReadyAfterFrame()
    }

    // 备份：聊天记录那一页唯一的动作（D424 提速之后才放上来）。
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            busy = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = "正在备份…"
            val result = withContext(Dispatchers.IO) { StorageExport.backupTo(context, uri) }
            busy = null
            notice = result.fold(
                onSuccess = { it },
                onFailure = { "备份失败：${it.message}" },
            )
            reload += 1
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = kind.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        text = when (kind) {
                            StorageKind.Cache -> "清一下就能腾出来的空间"
                            StorageKind.Logs -> "三种通道，各自看、各自清"
                            StorageKind.Chat -> "只给看与备份，不给清"
                            else -> "App 自己的零件"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (kind) {
                    StorageKind.Cache -> CacheCards(
                        bytes = sizes[0],
                        busy = busy != null,
                        onClear = {
                            scope.launch {
                                busy = "正在清理…"
                                val result = withContext(Dispatchers.IO) {
                                    StorageRepository.clearCache(context)
                                }
                                busy = null
                                notice = result.fold(
                                    onSuccess = { "已清 ${formatBytes(it)}" },
                                    onFailure = { "清理失败：${it.message}" },
                                )
                                reload += 1
                            }
                        },
                    )

                    StorageKind.Logs -> LogKind.entries.forEachIndexed { index, logKind ->
                        val (line, hint) = logCopy(logKind)
                        StorageCard(
                            title = line,
                            detail = hint,
                            value = formatBytes(sizes.getOrElse(index) { 0L }),
                            actions = listOf(
                                CardAction(label = "查看", onClick = { onOpenLog(logKind) }),
                                CardAction(
                                    label = "清理",
                                    danger = true,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                NLog.clear(context, logKind)
                                            }
                                            notice = "已清「$line」"
                                            reload += 1
                                        }
                                    },
                                ),
                            ),
                        )
                    }

                    StorageKind.Other -> OtherCards()

                    StorageKind.Chat -> ChatCards(
                        bytes = sizes,
                        busy = busy != null,
                        onBackup = {
                            busy = "准备备份…"
                            backupLauncher.launch(StorageExport.suggestedBackupName())
                        },
                    )

                    else -> Unit
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 缓存那一页：**只有一张卡**。不给逐文件列表——名字是哈希，列出来只会让人不敢清。 */
@Composable
private fun CacheCards(bytes: Long, busy: Boolean, onClear: () -> Unit) {
    StorageCard(
        title = "系统缓存",
        detail = "缩略图与临时文件。清理不会影响使用：它们会按需重建。",
        value = formatBytes(bytes),
        actions = listOf(
            CardAction(
                label = "清理",
                enabled = !busy && bytes > 0,
                onClick = onClear,
            ),
        ),
    )
    Text(
        text = if (bytes == 0L) "现在是空的。" else "可清理 ${formatBytes(bytes)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * 其他那一页：**只有一张卡**「本地模型」，灰着。
 *
 * 灰的判据是"现在有没有东西可跑"，不是"功能写没写"——这一版还没有本地推理那一层，
 * 所以摆一个点了没反应的开关比不摆更坏。`files/vault`、`files/voice` 那些也归「其他」，
 * 但它们是 App 自己的零件，不进卡片，只计进大小（所以这一页的数字会比卡上多）。
 */
@Composable
private fun OtherCards() {
    StorageCard(
        title = "本地模型",
        detail = "还没接 · 等本地推理那一层",
        value = null,
        actions = listOf(CardAction(label = "选择模型", enabled = false, onClick = {})),
    )
    Text(
        text = "导入的字体、语音运行时也计进「其他」，它们是 App 自己的零件，不在这里列。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * 聊天记录那一页：三张卡 + **一颗备份按钮**，没有删除。
 *
 * 用户原话是「能在侧边栏长按清理会话，就不适合在存储管理这里清」——
 * 所以这里连"清理"两个字都不出现，换成一句"要清会话请去侧边栏"。
 */
@Composable
private fun ChatCards(
    bytes: List<Long>,
    busy: Boolean,
    onBackup: () -> Unit,
) {
    val rows = listOf(
        Triple("聊天数据库", "nmoi.db", bytes.getOrElse(0) { 0L }),
        Triple("写入日志", "nmoi.db-wal", bytes.getOrElse(1) { 0L }),
        Triple("共享内存索引", "nmoi.db-shm", bytes.getOrElse(2) { 0L }),
    )
    for ((name, file, size) in rows) {
        StorageCard(
            title = name,
            detail = file,
            value = formatBytes(size),
            actions = emptyList(),
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "备份",
                style = MaterialTheme.typography.labelLarge,
                color = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clickable(enabled = !busy) { onBackup() }
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "删了就没有聊天记录了；要清会话请去侧边栏长按。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一张卡：标题 + 一句说明 + 右边的数字 + 一行动作。**没有文件行**。 */
@Composable
private fun StorageCard(
    title: String,
    detail: String,
    value: String?,
    actions: List<CardAction>,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (action in actions) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                !action.enabled ->
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                action.danger -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier
                                .clickable(enabled = action.enabled) { action.onClick() }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 卡片上的一颗动作。**不可用时灰着**——灰着也要说明为什么（说明写在卡片的下方一行）。 */
private class CardAction(
    val label: String,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 日志三张卡的标题与说明。标签直接来自 [LogKind]，不在这里另写一份文案。 */
private fun logCopy(kind: LogKind): Pair<String, String> = kind.label to when (kind) {
    LogKind.Context -> "组装过程：注入了哪些块、各来源 token、压缩轮次与分界线位置"
    LogKind.Request -> "HTTP：URL、状态码、耗时、重试；header 脱敏"
    LogKind.Debug -> "应用事件：启动、开库、图标切换、原生层、崩溃前状态"
}
