package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 分组页（模型与提示词 / 会话与上下文 / 工具与工作区 / 关于 / 外观 / 调试）。
 *
 * **骨架照 D200 的样板**（`ui-controls-layout` 的 B. 分组列表 × M3 的皮）：
 * 一张连续的板 + 从文字起点缩进的行线 + 右侧一句话（当前值 / 待接阶段 / chevron），
 * 页边距 24，行高 ≥64。以前这里是"每行一段、没有板、右侧用 `›` 字符"，
 * 真机截图里跟设置索引页像两个应用（用户 2026-10-31 提的"有几个页面还没改"）。
 *
 * 能进的行与"还没接"的行**放在同一张板上**：它们本来就属于同一组事，
 * 分两块只会让用户以为灰行是另一类东西。区别只在右边那一格写什么。
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
    // 一页一行地拼：能进的先摆，没接的跟在后面（用户读的顺序就是"能做什么 → 还差什么"）。
    val entries = buildList {
        if (group == SettingsGroup.Models) {
            add(
                GroupEntry(
                    title = "助手",
                    subtitle = "每个助手一套模型配置、一份会话列表；可以改名、删除（有会话时先选去处）",
                    onClick = onOpenAssistants,
                ),
            )
            add(
                GroupEntry(
                    title = "供应商",
                    subtitle = "多套端点 / 密钥 / 模型；「当前」那一套用于发请求（密钥单独加密存，不进库）",
                    onClick = onOpenProvider,
                ),
            )
            add(
                GroupEntry(
                    title = "预设",
                    subtitle = "随包的几条工具规矩；只在对应工具真的声明出去的那一轮注入（四态：跟随 / 已改 / 有新 / 冲突）",
                    onClick = onOpenPresets,
                ),
            )
            add(
                GroupEntry(
                    title = "七条压缩提示词",
                    subtitle = "draft / fallback.strip / fallback.merge / single / segment / merge_segments / verify",
                    onClick = onOpenPrompts,
                ),
            )
        }
        if (group == SettingsGroup.About) {
            add(
                GroupEntry(
                    title = "开源许可",
                    subtitle = "GPL-3.0-only 全文 · 第三方组件声明 · LICENSES/ 下三份",
                    onClick = onOpenLicenses,
                ),
            )
        }
        pendingRows(group).forEach { row ->
            add(GroupEntry(title = row.title, subtitle = row.subtitle, pending = row.pending))
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
                Text(
                    text = group.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                if (entries.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) {
                        Column {
                            entries.forEachIndexed { index, entry ->
                                if (index > 0) RowDivider(leading = 16.dp)
                                GroupRow(entry)
                            }
                        }
                    }
                }

                Text(
                    text = "灰字是**没接**，不是坏了：每一项后面写着等哪个阶段。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}

/** 板上一行。`onClick == null` 就是"还没接"的那一种（右侧写阶段，不给箭头）。 */
private data class GroupEntry(
    val title: String,
    val subtitle: String,
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
            // 行高 ≥64：设置类页面一行里有两三行字，矮了就成了"一堆贴在一起的小字"。
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                // 灰一档：一眼能看出这一项还不能用（不是坏了，是没接）。
                color = if (clickable != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val tail = entry.pending
        if (tail != null) {
            // 右侧只写"等哪个阶段"：它回答的是"为什么点不了"，比一个灰箭头有用。
            Text(
                text = tail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            androidx.compose.material3.Icon(
                imageVector = ActionIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
            )
        }
    }
}