package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 日志设置页（索引页右上角齿轮）。**开关与上限都在这里，按通道分开**。
 *
 * 为什么不在索引页上直接摆开关：索引页一行的空间里要塞"名字 + 说明 + 大小 + 状态"，
 * 再摆一个 Switch 就得靠猜哪个可点；而且滑动时很容易误触到开关。
 * 分开之后：索引页 = 只读一眼现状，这一页 = 只改设置，子页 = 只做动作。
 */
@Composable
internal fun LogSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 本地快照：SharedPreferences 的改动不会触发重组，这几行得自己维护。
    var enabledMap by remember {
        mutableStateOf(LogKind.entries.associateWith { NLog.isEnabled(it) })
    }
    var limitMap by remember {
        mutableStateOf(LogKind.entries.associateWith { NLog.limitBytes(it) })
    }
    var message by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "日志设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    text = "开关关掉之后那一类连 logcat 也不打。上限改了立即生效：下一次写入就按新上限轮转，" +
                        "已经写下的内容不会因为改小上限被立刻删掉（要清就用子页的「清除日志」）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                LogKind.entries.forEach { kind ->
                    Text(
                        text = kind.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // D103 的三个例外之一：设置里的开关项标题必须是文字，不能用图标代替。
                            Text(
                                text = if (enabledMap[kind] == true) "写入这类日志" else "已关闭",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = kind.what,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabledMap[kind] == true,
                            onCheckedChange = { value ->
                                NLog.setEnabled(context, kind, value)
                                enabledMap = enabledMap + (kind to value)
                                message = "${kind.label}已${if (value) "打开" else "关闭"}"
                            },
                        )
                    }

                    Text(
                        text = "上限",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NLog.LIMIT_CHOICES_MB.forEach { megabytes ->
                            val bytes = megabytes * 1024L * 1024L
                            LimitChip(
                                label = "$megabytes MB",
                                selected = limitMap[kind] == bytes,
                                onClick = {
                                    NLog.setLimitBytes(context, kind, bytes)
                                    limitMap = limitMap + (kind to bytes)
                                    message = "${kind.label}上限改成 $megabytes MB"
                                },
                            )
                        }
                    }

                    if (kind.pendingProducer != null) {
                        Text(
                            text = "注意：这一类还没有产生者（${kind.pendingProducer}），开关开着也不会有内容。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                        )
                    }
                }

                val note = message
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
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
