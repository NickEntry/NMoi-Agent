package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 日志索引页（设置 → 调试 → 日志）。
 *
 * 一页只干一件事：**把三种通道摆出来，选一个进去**。开关、上限、四个动作全在各自的
 * 子页与齿轮页里——一页把"开关 + 上限 + 查看 / 复制 / 导出 / 清除 + 预览"挤在一起，
 * 在手机上就是滚动里找东西（用户 2026-10-23 的原话：这种设计不太符合人类操作习惯）。
 *
 * 右上角齿轮 → 日志设置页（三种开关与上限）。
 * 每行右侧写"大小 / 上限"和开/关状态：**用户不该为了知道现在是几兆而点进去**。
 */
@Composable
internal fun LogListScreen(
    onOpen: (LogKind) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 每次进入本页都会重新组合（路由是一层 `when` 分支），所以从子页返回时大小是新的。
    val sizes by produceState<Map<LogKind, Long>>(initialValue = emptyMap()) {
        value = withContext(Dispatchers.IO) {
            LogKind.entries.associateWith { kind -> NLog.fileSize(context, kind) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconActionButton(
                    image = ActionIcons.Settings,
                    description = "日志设置",
                    onClick = onOpenSettings,
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    text = "三种通道各自一个文件、各自的上限；缺省 8 MB，超了自动丢最旧的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                )

                LogKind.entries.forEach { kind ->
                    LogKindRow(
                        kind = kind,
                        sizeBytes = sizes[kind] ?: 0L,
                        limitBytes = NLog.limitBytes(kind),
                        enabled = NLog.isEnabled(kind),
                        onClick = { onOpen(kind) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogKindRow(
    kind: LogKind,
    sizeBytes: Long,
    limitBytes: Long,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = kind.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (enabled) "开" else "关",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = kind.what,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            // 没有产生者时明写，别让人对着空文件猜"是不是坏了"。
            if (kind.pendingProducer != null) {
                Text(
                    text = "还没有产生者：${kind.pendingProducer}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                text = "${formatBytes(sizeBytes)} / ${formatBytes(limitBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
