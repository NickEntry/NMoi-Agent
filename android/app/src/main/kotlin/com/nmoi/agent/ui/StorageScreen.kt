package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储空间（D426）：一页看见"这台设备的空间都被谁占了"。
 *
 * 用户 2026-11-25 给的那批截图里，这一页是十项账本 + 一段占比条。这里不照抄的几处：
 *
 * - 图例只列前五项，其余并进「其他」——十项图例在手机上一屏放不下，第二行会被切；
 * - 「可清理」那个数字**能点**，点它直接进清理动作；放在那儿不能点等于装饰；
 * - 每一行右边是大小 + 箭头，不给"清理"小按钮：一屏十个按钮会把这一页变成控制板，
 *   而它其实是"看一眼，再决定进哪一项"。
 *
 * 数据在 IO 线程现算，算完 `PageReady`——不报的话切页会先看到一屏 0 再跳。
 */
@Composable
internal fun StorageScreen(
    onBack: () -> Unit,
    onOpen: (StorageKind) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scan by remember { mutableStateOf<StorageRepository.Scan?>(null) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { StorageRepository.scan(context) }
        }
        result.fold(
            onSuccess = { scan = it },
            onFailure = {
                notice = "量不出来：${it.message}"
                scan = StorageRepository.Scan(emptyList(), emptyList())
            },
        )
        loading = false
    }

    LaunchedEffect(Unit) {
        reload()
        PageReady.notifyReadyAfterFrame()
    }

    val current = scan
    val buckets = current?.buckets.orEmpty()
    val total = current?.total ?: 0L
    val legend = StorageUsage.legend(buckets)
    val reclaimable = StorageUsage.reclaimable(buckets)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "存储空间",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Text(
                    text = if (loading) "统计中…" else "刷新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !loading) {
                            scope.launch { reload() }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ---- 总占用卡 ----
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "已用空间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (loading && current == null) "—" else formatBytes(total),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        UsageBar(segments = StorageUsage.segments(buckets, total), total = total)
                        Spacer(Modifier.height(10.dp))
                        Legend(
                            rows = legend,
                            total = total,
                            onOpen = onOpen,
                        )
                        if (reclaimable > 0) {
                            Spacer(Modifier.height(10.dp))
                            // 能点：直接进「缓存」那一页（可清理的两项里缓存通常最大）。
                            // 不写成"清理缓存与日志"——那是两个动作合成一句，点下去删什么说不清。
                            Text(
                                text = "可清理 ${formatBytes(reclaimable)} ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onOpen(StorageKind.Cache) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }

                // ---- 十项账本 ----
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Column {
                        buckets.forEachIndexed { index, bucket ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 52.dp, end = 16.dp)
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                )
                            }
                            BucketRow(bucket = bucket, onClick = { onOpen(bucket.kind) })
                        }
                    }
                }

                // 跳过的那些要说出来：不跟符号链接是对的，但"看见了却没算"得让人知道。
                val skipped = current?.skipped.orEmpty()
                if (skipped.isNotEmpty()) {
                    Text(
                        text = "跳过了 ${skipped.size} 个链接或特殊文件（不重复计入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 一段占比条。零字节的项不画（一段 0 宽的颜色块看着像缝隙）。 */
@Composable
private fun UsageBar(segments: List<UsageBucket>, total: Long) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(empty),
    ) {
        if (total <= 0L) {
            // 一点东西都没有时整条留灰：画一段假的颜色会让人以为有东西。
            Spacer(Modifier.fillMaxWidth().fillMaxHeight())
            return@Row
        }
        for (segment in segments) {
            val share = segment.share(total)
            // 极小的一段至少给 2dp：否则"有 300 KB 缓存"这件事在条子上完全看不见。
            Box(
                modifier = Modifier
                    .weight(share.coerceAtLeast(0.0001f))
                    .fillMaxHeight()
                    .background(Color(segment.kind.argb)),
            )
        }
    }
}

/** 图例：每行一项，点它进对应的子页。 */
@Composable
private fun Legend(
    rows: List<UsageBucket>,
    total: Long,
    onOpen: (StorageKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row.kind) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(row.kind.argb)),
                )
                Text(
                    text = row.kind.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(row.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = " ${(row.share(total) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

/** 账本里的一行：图标 + 名字 + 副标题，右边大小与箭头。 */
@Composable
private fun BucketRow(bucket: UsageBucket, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = storageIcon(bucket.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = bucket.kind.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = when {
                    bucket.bytes == 0L -> "空"
                    bucket.files == 0 -> formatBytes(bucket.bytes)
                    else -> "${bucket.files} 个文件 · ${formatBytes(bucket.bytes)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatBytes(bucket.bytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = ActionIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 6.dp).size(16.dp),
        )
    }
}

/** 每一项给一颗图标。语义牵强的宁可给同一个，不要给错的。 */
private fun storageIcon(kind: StorageKind) = when (kind) {
    StorageKind.Images -> ActionIcons.Image
    StorageKind.Files -> ActionIcons.Paperclip
    StorageKind.Chat -> ActionIcons.Database
    StorageKind.Cache -> ActionIcons.RotateCcw
    StorageKind.Logs -> ActionIcons.FileCode
    StorageKind.Other -> ActionIcons.Folder
    StorageKind.Workspace -> ActionIcons.Folder
    StorageKind.Envir -> ActionIcons.Cpu
    StorageKind.Skills -> ActionIcons.Wrench
    StorageKind.SessionFiles -> ActionIcons.Folder
}
