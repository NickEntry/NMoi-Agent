package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置根页（2026-11-07 重排）。
 *
 * 三条：**搜索在根页**（不在应用设置里）、**每行只有标题与当前值**（下面不再挂一句小字）、
 * **行高压到 52**——一行一个入口，密度高才扫得快。
 *
 * 助手 / 供应商 / 世界书 / 网络代理直接摆在根页：它们各自是一整件事，
 * 藏在中间层里用户得先猜在哪一组。
 */
@Composable
internal fun SettingsIndexScreen(
    iconSummary: String,
    logSummary: String,
    toolsSummary: String,
    appSummary: String,
    onOpen: (SettingsGroup) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val matched = SettingsGroup.entries.filter { group ->
        needle.isEmpty() ||
            group.title.contains(needle, ignoreCase = true) ||
            group.keywords.contains(needle, ignoreCase = true)
    }

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
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        SettingField(
                            value = query,
                            onChange = { query = it },
                            placeholder = "搜索设置项",
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (matched.isEmpty()) {
                    Text(
                        text = "没有匹配「$needle」的设置项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) {
                        Column {
                            matched.forEachIndexed { index, group ->
                                if (index > 0) RowLine(leading = 16.dp)
                                GroupRow(
                                    group = group,
                                    summary = summaryFor(
                                        group,
                                        iconSummary,
                                        logSummary,
                                        toolsSummary,
                                        appSummary,
                                    ),
                                    onClick = { onOpen(group) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun summaryFor(
    group: SettingsGroup,
    iconSummary: String,
    logSummary: String,
    toolsSummary: String,
    appSummary: String,
): String = when (group) {
    SettingsGroup.Appearance -> iconSummary
    SettingsGroup.App -> appSummary
    SettingsGroup.Defaults -> "每个助手一套"
    SettingsGroup.Providers -> "端点与密钥"
    SettingsGroup.Assistants -> "模型与子 AGENT"
    SettingsGroup.Lorebook -> "册与条目"
    SettingsGroup.Proxy -> "开关与端口"
    SettingsGroup.Tools -> toolsSummary
    SettingsGroup.Storage -> "附件"
    SettingsGroup.Debug -> logSummary
    SettingsGroup.About -> "GPL-3.0-only"
}

/** 一行入口：标题 + 当前值 + chevron。行高 52，不再挂第二行小字。 */
@Composable
private fun GroupRow(group: SettingsGroup, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 140.dp),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
}

/** 行线：1px，从文字起点缩进。 */
@Composable
private fun RowLine(leading: androidx.compose.ui.unit.Dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = leading)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}
