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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置索引页：**只摆分组入口和"现在是什么"**，不摆任何开关。
 *
 * 这样做的理由写在 `docs/ui/structure.md` §2：一页堆四类东西，用户就得在滚动里找，
 * 找不到时分不清"我没滚到"还是"根本没有"。索引页每行右侧写当前值——
 * 不该为了知道"现在图标是哪一个"而点进去。
 */
@Composable
internal fun SettingsIndexScreen(
    iconSummary: String,
    logSummary: String,
    onOpen: (SettingsGroup) -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsGroup.entries.forEach { group ->
                    GroupRow(
                        group = group,
                        summary = summaryFor(group, iconSummary, logSummary),
                        onClick = { onOpen(group) },
                    )
                }
                Text(
                    text = "灰底分组里还有没接的功能，点进去会写清差什么（不给假开关、不给假表单）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
                )
            }
        }
    }
}

private fun summaryFor(group: SettingsGroup, iconSummary: String, logSummary: String): String =
    when (group) {
        SettingsGroup.Appearance -> iconSummary
        SettingsGroup.Context -> "75% / 92% · 未接"
        SettingsGroup.Models -> "七条提示词 · 只读"
        SettingsGroup.Tools -> "未接"
        SettingsGroup.Debug -> logSummary
        SettingsGroup.About -> "GPL-3.0-only · 0.0.1-dev"
    }

@Composable
private fun GroupRow(group: SettingsGroup, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = group.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
