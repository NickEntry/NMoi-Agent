package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 分组页（默认模型&提示词 / 关于 这类"只有几个入口"的页）。
 *
 * 一张连续的板 + 从文字起点缩进的行线 + 右侧一个箭头；行高 52。
 * 页面上不再写说明文字：能点的一行就是一行，没接的那一行才带一句"差什么"。
 */
@Composable
internal fun GroupScreen(
    group: SettingsGroup,
    onOpenPrompts: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val entries = buildList {
        if (group == SettingsGroup.Defaults) {
            add(GroupEntry(title = "默认模型", onClick = onOpenAssistants))
            add(GroupEntry(title = "提示词", onClick = onOpenPrompts))
            add(GroupEntry(title = "预设", onClick = onOpenPresets))
        }
        if (group == SettingsGroup.About) {
            add(GroupEntry(title = "开源许可", onClick = onOpenLicenses))
        }
        pendingRows(group).forEach { row ->
            add(GroupEntry(title = row.title, pending = row.pending))
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
                    text = group.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) RowDivider(leading = 16.dp)
                            GroupRow(entry)
                        }
                    }
                }
            }
        }
    }
}

/** 板上一行。`onClick == null` 就是"还没接"的那一种（右侧写阶段，不给箭头）。 */
private data class GroupEntry(
    val title: String,
    val onClick: (() -> Unit)? = null,
    val pending: String? = null,
)

@Composable
private fun GroupRow(entry: GroupEntry) {
    val clickable = entry.onClick
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable != null) {
                    Modifier.clickable(onClick = clickable)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                // 灰一档：一眼能看出这一项还不能用（不是坏了，是没接）。
                color = if (clickable != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val tail = entry.pending
        if (tail != null) {
            Text(
                text = tail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            Icon(
                imageVector = ActionIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
            )
        }
    }
}
