package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 第一屏：会话列表。形状照 docs/PLAN.md §3.11 的列表行来。
 *
 * `snapshot == null` 表示还在读库；读完了不管是原生数据还是占位数据，
 * 页面底部都会写清楚**这一屏的数字是从哪来的**——骨架阶段最怕的不是没数据，
 * 是"看起来有数据，其实全是写死的"。
 */
@Composable
internal fun ConversationListScreen(
    snapshot: ConversationSnapshot?,
    nativeStatus: NativeStatus,
    onOpenDrawer: () -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onCreateConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(nativeStatus, onOpenDrawer)

            if (snapshot == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在读库…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (snapshot.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // D103 的三个例外之一：空状态要说明，不给图标按钮。
                    Text(
                        text = "还没有会话。下一步才做输入岛。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(snapshot.rows) { row ->
                        ConversationCard(row, onClick = { onOpenConversation(row) })
                    }
                }
            }

            SourceNotice(snapshot)
        }
    }
}

@Composable
private fun Header(nativeStatus: NativeStatus, onOpenDrawer: () -> Unit) {
    // start 只留 4dp：菜单按钮自己带 48dp 热区，再多加内边距会把图标推到看着不对的位置。
    Column(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 侧边栏入口**在左上角**（用户 2026-10-23 第二次提到）。
            // 之前它在标题右边：`weight(1f)` 把标题撑满，按钮被推到了最右。
            MenuButton(onClick = onOpenDrawer)
            Text(
                text = "NMoi",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            // 新建会话：全新安装的库里一条都没有，没有这个入口就进不去聊天页。
            IconActionButton(
                image = Icons.Filled.Add,
                description = "新建会话",
                onClick = onCreateConversation,
            )
        }
        Text(
            text = "本地优先 · 骨架版 0.0.1-dev",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        // 实机验证先看这一行（docs/verification.md 的 V1）：
        //   `NMoi-Agent v… · abi 1`            → 通了
        //   `原生层未加载：…`                    → .so 没进包 / 架构不对
        //   `已加载，但 JNI 符号对不上`           → 名字对不上（这次真机上遇到的就是这个）
        Text(
            text = nativeStatus.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (nativeStatus.ok) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ConversationCard(row: ConversationRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.incomplete) {
                    Text(
                        text = "断流",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Text(
                text = row.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                text = row.updatedAt + "  ·  " + (row.model ?: "未跑过"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SourceNotice(snapshot: ConversationSnapshot?) {
    val text = when {
        snapshot == null -> "数据源：还在读"
        snapshot.source == RowSource.Native && snapshot.note == null -> "数据源：原生层（nmoi_ffi → storage）"
        snapshot.source == RowSource.Native -> "数据源：原生层 · ${snapshot.note}"
        else -> "数据源：占位数据 · ${snapshot.note ?: "未说明"}"
    }
    val color = when {
        snapshot?.source == RowSource.Native -> MaterialTheme.colorScheme.onSurfaceVariant
        snapshot == null -> MaterialTheme.colorScheme.onSurfaceVariant
        // 占位数据一律用醒目色：这不是"少了一点"，是"没接上"。
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    )
}
