package com.nmoi.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
 * 资源更新页（D201 第三片；多选删除是 2026-10-31 真机反馈加的）。
 *
 * 这一页只回答三个问题，顺序也是这个顺序：
 *
 * 1. **哪些模型在快照里对得上**（对不上的明说；对上了但属于"猜的"要用户点一下）；
 * 2. **哪些字段会变**（`旧 → 新`，一行一个）；
 * 3. **哪些字段本可以变但没动**（用户自己填过 → 跳过，并写出原因）。
 *
 * 右上角那个"方框 + 对勾"是**多选**：勾几个模型直接删掉（删的是这份供应商配置里的行，
 * 落库仍然由「保存」/ 关闭这一页那一下完成）。
 *
 * 它是一层整页浮层，和模型编辑页同级、同样不落库：改动先回到供应商页的表单里。
 */
@Composable
internal fun ModelResourceScreen(
    models: List<ProviderModel>,
    sources: Map<String, Map<String, FieldSource>>,
    onClose: () -> Unit,
    /** 应用：改完的模型表 + "哪些字段是快照写的"（用来落 `推断` 来源）。 */
    onApply: (List<ProviderModel>, Map<String, Map<String, String>>) -> Unit,
    /** 多选删掉的模型 id（父页面把它从表单里去掉；落库由那一下「保存」完成）。 */
    onDelete: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableStateOf<SnapshotIndex?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(setOf<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 1.2 MB 的 JSON：解析一次几百毫秒，必须在 IO 上，而且只解析一次。
        val loaded = withContext(Dispatchers.IO) { ModelSnapshot.load(context) }
        index = loaded
        failure = if (loaded == null) "这一版没带上模型快照（assets 里没有 ${ModelSnapshot.ASSET}）" else null
        loading = false
    }

    BackHandler(enabled = true) {
        if (selecting) {
            selecting = false
            selected = emptySet()
        } else {
            onClose()
        }
    }

    val plans = remember(index, models, sources, accepted) {
        index?.let { ResourceUpdate.plan(models, sources, it, accepted) }.orEmpty()
    }
    val changeCount = plans.sumOf { it.write.size }
    val guessed = plans.count { it.awaitingAccept }
    val skipCount = plans.sumOf { it.skipped.size }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = {
                    if (selecting) {
                        selecting = false
                        selected = emptySet()
                    } else {
                        onClose()
                    }
                })
                Text(
                    text = if (selecting) "已选 ${selected.size} 项" else "资源更新",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (selecting) {
                    TextAction(
                        label = "删除",
                        danger = true,
                        onClick = {
                            if (selected.isEmpty()) {
                                notice = "先勾几个模型"
                            } else {
                                onDelete(selected)
                                notice = "从这份配置里删掉了 ${selected.size} 个模型（还要保存）"
                                selecting = false
                                selected = emptySet()
                            }
                        },
                    )
                    IconActionButton(
                        image = ActionIcons.Close,
                        description = "退出多选",
                        onClick = {
                            selecting = false
                            selected = emptySet()
                        },
                    )
                } else {
                    // 方框 + 对勾 = 多选（用户 2026-10-31 口述的形状）。
                    IconActionButton(
                        image = ActionIcons.CheckSquare,
                        description = "多选模型（可以批量删除）",
                        onClick = { selecting = true },
                    )
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Note(
                        title = "数据来自随包的快照",
                        body = buildString {
                            append("生成时间 ")
                            append(index?.fetchedAt?.takeIf { it.isNotBlank() } ?: "（没写）")
                            append("。只填**你没自己填过**的字段：")
                            append("窗口 / 最大输出 / 工具 / 结构化输出 / 推理 / 支持的级别 / 输入模态（只有图片一项）/ 输入价 / 输出价。")
                        },
                    )
                }

                when {
                    loading -> item { Note("正在读快照…", null) }
                    failure != null -> item { Note("读不到快照", failure) }
                    models.isEmpty() -> item {
                        Note("这个供应商还没有模型", "先回上一页加一个模型，再来这里对齐。")
                    }

                    else -> Unit
                }

                val line = notice
                if (line != null) {
                    item {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }

                // **懒加载**：一个供应商几十个模型时，每张卡里都是七八行文字，
                // 一次性铺开在真机上会明显掉帧（用户 2026-11-01 提的滚动性能）。
                items(plans, key = { it.modelId }) { plan ->
                    ModelPlanCard(
                        plan = plan,
                        selecting = selecting,
                        selected = plan.modelId in selected,
                        onToggleSelect = {
                            selected = if (plan.modelId in selected) {
                                selected - plan.modelId
                            } else {
                                selected + plan.modelId
                            }
                        },
                        onAccept = { accepted = accepted + plan.modelId },
                    )
                }

                if (plans.isNotEmpty() && !selecting) {
                    item {
                    Text(
                        text = buildString {
                            if (changeCount == 0) {
                                append("没有要自动写入的字段——库里这份已经和快照一致")
                            } else {
                                append("会写 $changeCount 处")
                            }
                            if (skipCount > 0) append("；跳过 $skipCount 处（你填过）")
                            if (guessed > 0) append("；另有 $guessed 个模型是猜出来的，要你点「用这条」才写")
                            append("。")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (!selecting) {
                WideButton(
                    label = when {
                        loading -> "读快照…"
                        changeCount == 0 -> "没有可更新的字段"
                        else -> "更新 ${plans.count { it.hasWork }} 个模型 · $changeCount 处"
                    },
                    enabled = !loading && changeCount > 0,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    onClick = {
                        val applied = ResourceUpdate.apply(models, plans)
                        onApply(applied.models, applied.inferred)
                    },
                )
            }
        }
    }
}

/** 一个模型一张卡：多选框（多选时）+ 名字 + 匹配方式 + `旧 → 新` 的逐行清单。 */
@Composable
private fun ModelPlanCard(
    plan: ModelPlan,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onAccept: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .then(
                if (selecting) {
                    Modifier.clickable(onClick = onToggleSelect)
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    SelectBox(selected)
                    Spacer(Modifier.size(10.dp))
                }
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
                val match = plan.match
                Text(
                    text = when {
                        match == null -> "快照里没有"
                        plan.awaitingAccept -> "猜的"
                        plan.write.isNotEmpty() -> "${plan.write.size} 处"
                        else -> "已是最新"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        match == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        plan.awaitingAccept -> MaterialTheme.colorScheme.tertiary
                        plan.write.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (plan.match == null) {
                Text(
                    text = "快照里没有这个模型的条目，什么都不动。名字差一个字符就对不上——" +
                        "宁可空着，也不拿一个最像的来顶。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            val match = plan.match
            if (match != null && match.how != MatchHow.Exact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${match.how.label}：${match.entry.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (plan.awaitingAccept) {
                        DialogButton(label = "用这条", primary = true, onClick = onAccept)
                    }
                }
            }

            plan.changes.forEach { change ->
                ChangeRow(change, muted = false, guessed = plan.awaitingAccept)
            }
            plan.skipped.forEach { change ->
                ChangeRow(change, muted = true, guessed = false)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChangeRow(change: FieldChange, muted: Boolean, guessed: Boolean) {
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
                color = when {
                    muted -> MaterialTheme.colorScheme.onSurfaceVariant
                    guessed -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
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
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 页面里的一段说明（不是提示条，不带图标）。 */
@Composable
private fun Note(title: String, body: String?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
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