package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内置命令规则（D329）：十组、79 条，**只读**。
 *
 * 这一页的职责只剩"看得见"：拒绝项（`rm -rf /` 那几条）逐条列在这里，闸不能隐形。
 * 问不问、加不加自己那条，在 设置 → 安全 里改——一个开关只有一个入口。
 */
@Composable
internal fun ShellRulesScreen(onBack: () -> Unit) {
    var catalog by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { NmoiFfi.shellRules().getOrNull() }
        PageReady.notifyReadyAfterFrame()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "内置规则",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                val data = catalog
                SectionLabel(
                    if (data == null) {
                        "规则"
                    } else {
                        "规则 · ${data.optInt("rule_count", 0)} 条"
                    },
                )
                GroupCard {
                    Text(
                        text = "这一页只管「问不问」，不决定「能不能跑」。清单永远不完备：" +
                            "脚本里的 rm、PowerShell 别名、容器里另一套命令名都躲得过去——" +
                            "它是减少打扰的折衷，不是安全保证。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }

                if (data == null) {
                    SectionLabel(" ")
                    // 不写"读规则表…"（D358）：占位就只是占位，别让它像一条内容。
                    GroupCard {
                        Box(modifier = Modifier.fillMaxWidth().height(44.dp))
                    }
                    return@Column
                }

                val groups = data.optJSONArray("groups") ?: return@Column
                for (index in 0 until groups.length()) {
                    val group = groups.optJSONObject(index) ?: continue
                    val id = group.text("id")
                    if (id.isEmpty()) continue
                    val name = group.text("name").ifBlank { id }
                    val rules = group.optJSONArray("rules")
                    val count = rules?.length() ?: 0

                    SectionLabel("$name · $count 条")
                    GroupCard {
                        if (rules != null) {
                            for (ruleIndex in 0 until rules.length()) {
                                val rule = rules.optJSONObject(ruleIndex) ?: continue
                                val label = rule.text("label")
                                val deny = rule.optBoolean("deny", false)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (deny) {
                                        Text(
                                            text = "总是拦",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}
