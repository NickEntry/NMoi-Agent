package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 默认模型与场景提示词（只读）。
 *
 * **这里是只读的，不是编辑页**：编辑要写 settings 表（D86），还要做占位符校验与
 * "恢复默认"（D115）。在 settings 表接进 Kotlin 之前给一个能改、但改完不生效的界面，
 * 比不给更糟。所以现在只把"有哪些提示词、各自要哪些占位符"摊开，
 * 让后面接线时有明确的对账清单。
 */
@Composable
internal fun PromptsScreen(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "提示词",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Text(
                text = "只读。编辑与恢复默认要等 settings 表接上（D86 / D115）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Column(modifier = Modifier.fillMaxSize()) {
                PROMPTS.forEach { prompt ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = prompt.key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = prompt.role + " · 占位符 " + prompt.slots,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class PromptInfo(val key: String, val role: String, val slots: String)

/** 与 docs/compression.md §3 的那张表一一对应；改那边记得改这里。 */
private val PROMPTS = listOf(
    PromptInfo("compress.draft", "L0 多步压缩", "{{must_keep}} {{draft}} {{history}}"),
    PromptInfo("compress.fallback.strip", "L1 Pass A（剔思考块压事实层）", "{{must_keep}} {{history}}"),
    PromptInfo("compress.fallback.merge", "L1 Pass B（合并思考要点）", "{{draft_facts}} {{thought_points}}"),
    PromptInfo("compress.single", "L2 单步", "{{budget}} {{must_keep}} {{history}}"),
    PromptInfo("compress.segment", "L3 逐段", "{{index}} {{total}} {{context}} {{segment}}"),
    PromptInfo("compress.merge_segments", "L3 合并", "{{segments}} {{max_lines}}"),
    PromptInfo("compress.verify", "质检（默认关）", "{{history}} {{draft}}"),
)
