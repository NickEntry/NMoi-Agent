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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置索引页：**只摆分组入口和"现在是什么"**，不摆任何开关。
 *
 * 这样做的理由写在 `docs/ui/structure.md` §2：一页堆四类东西，用户就得在滚动里找，
 * 找不到时分不清"我没滚到"还是"根本没有"。索引页每行右侧写当前值——
 * 不该为了知道"现在图标是哪一个"而点进去。
 *
 * ## 骨架（0.16：按 ui-controls-layout 的 **B. 分组列表** 重排，混 M3 的皮）
 *
 * - **一张连续的板**，不拆成七张卡：七行各套一个圆角卡就是那个"卡片汤"反模式，
 *   而且七张卡之间的空隙会让"这里还有几项"看不出来。
 * - 行高 ≥ 64、页边距 16、面板圆角 16、行线 1px 且**从文字起点**缩进（不从图标起点）。
 * - 右侧只有三样：当前值（可省略，最长 140dp 后省略号）、chevron（**只在真能进下一层时**出现）。
 * - 一屏一个 filled 主按钮——这一页没有主按钮，它是导航页（设置页带"保存"是反模式）。
 * - 图标位留空：七行的标题已经够扫读，加七个凑数的图标只会变成装饰（"图标是扫读锚点，不是装饰"）。
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
                Spacer(Modifier.height(8.dp))
                // 一整块面板：七行在里面靠行线分隔，行线从文字起点缩进 16。
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column {
                        SettingsGroup.entries.forEachIndexed { index, group ->
                            if (index > 0) {
                                RowLine(leading = 16.dp)
                            }
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
                Text(
                    text = "灰底分组里还有没接的功能，点进去会写清差什么（不给假开关、不给假表单）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
                )
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
): String =
    when (group) {
        SettingsGroup.Appearance -> iconSummary
        SettingsGroup.App -> appSummary
        SettingsGroup.Context -> "75% / 92% · 未接"
        SettingsGroup.Models -> "供应商 · 助手 · 七条提示词"
        SettingsGroup.Tools -> toolsSummary
        SettingsGroup.Debug -> logSummary
        SettingsGroup.About -> "GPL-3.0-only · 0.0.1-dev"
    }

/**
 * 一行入口：标题 + 一句说明，右侧是"现在是什么"，最后是 chevron。
 *
 * 行高下限 64（两行文字 + 12 上下内距），点整行；chevron 只在能进下一层时出现，
 * 这一页每一行都能进，所以都有。
 */
@Composable
private fun GroupRow(group: SettingsGroup, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = group.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 当前值：先被压缩的那一项。标题永远比"现在是什么"重要。
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

/** 行线：1px，从文字起点缩进。和面板圆角同色系里最浅的一档。 */
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
