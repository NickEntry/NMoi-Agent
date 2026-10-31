package com.nmoi.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资源更新页（D201 第三片）：把随包的模型快照对齐到这份供应商配置上。
 *
 * 这一页只回答三个问题，顺序也是这个顺序：
 *
 * 1. **哪些模型在快照里对得上**（对不上的明说，不猜）；
 * 2. **哪些字段会变**（`旧 → 新`，一行一个）；
 * 3. **哪些字段本可以变但没动**（用户自己填过 → 跳过，并写出原因）。
 *
 * 它是**一层整页浮层**，和模型编辑页同级、同样不落库：改动先回到供应商页的表单里，
 * 由那一个「保存」写库。理由和模型页一样——供应商草稿只能有一个写者。
 */
@Composable
internal fun ModelResourceScreen(
    models: List<ProviderModel>,
    sources: Map<String, Map<String, FieldSource>>,
    onClose: () -> Unit,
    /** 应用：改完的模型表 + "哪些字段是快照写的"（用来落 `推断` 来源）。 */
    onApply: (List<ProviderModel>, Map<String, Map<String, String>>) -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableStateOf<SnapshotIndex?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 1.2 MB 的 JSON：解析一次几百毫秒，必须在 IO 上，而且只解析一次。
        val loaded = withContext(Dispatchers.IO) { ModelSnapshot.load(context) }
        index = loaded
        failure = if (loaded == null) "这一版没带上模型快照（assets 里没有 ${ModelSnapshot.ASSET}）" else null
        loading = false
    }

    BackHandler(enabled = true) { onClose() }

    val plans = remember(index, models, sources) {
        index?.let { ResourceUpdate.plan(models, sources, it) }.orEmpty()
    }
    val changeCount = plans.sumOf { it.changes.size }
    val skipCount = plans.sumOf { it.skipped.size }
    val touched = plans.count { it.hasWork }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onClose)
                Text(
                    text = "资源更新",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Note(
                    title = "数据来自随包的快照",
                    body = buildString {
                        append("生成时间 ")
                        append(index?.fetchedAt?.takeIf { it.isNotBlank() } ?: "（没写）")
                        append("。只填**你没自己填过**的字段：")
                        append("窗口 / 最大输出 / 工具 / 结构化输出 / 推理 / 支持的级别 / 输入模态（只有图片一项）/ 输入价 / 输出价。")
                    },
                )

                when {
                    loading -> Note("正在读快照…", null)
                    failure != null -> Note("读不到快照", failure)
                    models.isEmpty() -> Note("这个供应商还没有模型", "先回上一页加一个模型，再来这里对齐。")
                    else -> Unit
                }

                plans.forEach { plan ->
                    ModelPlanCard(plan)
                }

                if (plans.isNotEmpty()) {
                    Text(
                        text = if (changeCount == 0) {
                            "没有要更新的字段——库里这份已经和快照一致（跳过 $skipCount 处你自己填的）。"
                        } else {
                            "会写 $changeCount 处" + if (skipCount > 0) "；跳过 $skipCount 处（你填过）" else "。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            WideButton(
                label = when {
                    loading -> "读快照…"
                    changeCount == 0 -> "没有可更新的字段"
                    else -> "更新 $touched 个模型 · $changeCount 处"
                },
                enabled = !loading && changeCount > 0,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                onClick = {
                    val applied = ResourceUpdate.apply(models, plans)
                    onApply(applied.models, applied.inferred)
                },
            )
        }
    }
}

/** 一个模型一张卡：名字 + 状态 + `旧 → 新` 的逐行清单。 */
@Composable
private fun ModelPlanCard(plan: ModelPlan) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = plan.modelId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = when {
                        plan.entry == null -> "快照里没有"
                        plan.changes.isNotEmpty() -> "${plan.changes.size} 处"
                        plan.skipped.isNotEmpty() -> "已是最新"
                        else -> "已是最新"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        plan.entry == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        plan.changes.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (plan.entry == null) {
                Text(
                    text = "快照里没有这个模型的条目，什么都不动。名字差一个字符就对不上——" +
                        "宁可空着，也不拿一个最像的来顶。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            plan.changes.forEach { change ->
                ChangeRow(change, muted = false)
            }
            plan.skipped.forEach { change ->
                ChangeRow(change, muted = true)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChangeRow(change: FieldChange, muted: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = change.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = change.from,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " → ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = change.to,
                style = MaterialTheme.typography.labelSmall,
                color = if (muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (muted) {
            Text(
                text = "这一格你自己填过，跳过",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 6.dp, start = 0.dp),
        )
    }
}

/** 页面里的一段说明（不是提示条，不带图标）。 */
@Composable
private fun Note(title: String, body: String?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
