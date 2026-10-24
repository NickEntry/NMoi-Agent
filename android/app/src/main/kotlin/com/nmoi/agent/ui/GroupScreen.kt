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
 * 还没接线的那几组（会话与上下文 / 模型与提示词 / 工具与工作区 / 关于）。
 *
 * 这一页**故意只有灰行**：每行写清"差什么、等哪个 P"。不给假开关、不给假表单——
 * 点了没反应的控件比没有这个控件更坏，用户会以为设置坏了或者自己点错了。
 *
 * 接好一项就从 [pendingRows] 里删掉一行，把它换成真控件；这一页自然会越来越短。
 */
@Composable
internal fun GroupScreen(
    group: SettingsGroup,
    onOpenPrompts: () -> Unit,
    onOpenLicenses: () -> Unit,
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )

                // 真正能进的入口：目前两处。接了新的就从 pendingRows 里删一行、在这里加一个 NavRow。
                if (group == SettingsGroup.Models) {
                    NavRow(
                        title = "七条压缩提示词",
                        subtitle = "draft / fallback.strip / fallback.merge / single / segment / merge_segments / verify",
                        onClick = onOpenPrompts,
                    )
                }

                // 关于 → 开源许可：页面读的是包里真实存在的 assets/notices/，
                // 所以它既是"看声明"的地方，也是"声明到底进包没有"的自检入口。
                if (group == SettingsGroup.About) {
                    NavRow(
                        title = "开源许可",
                        subtitle = "GPL-3.0-only 全文 · 第三方组件声明 · LICENSES/ 下三份",
                        onClick = onOpenLicenses,
                    )
                }

                pendingRows(group).forEach { row ->
                    PendingSettingRow(row = row)
                }

                Text(
                    text = "灰行是**没接**，不是坏了：每一项都写着等哪个阶段。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PendingSettingRow(row: PendingRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                // 灰掉：一眼能看出这一项还不能用。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.pending,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
