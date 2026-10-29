package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 预设列表（0.14t / D178）。
 *
 * 一行一份预设，右侧是 core 算出来的四态徽标（跟随 / 已改 / 有新 / 冲突）。
 * **界面不自己推四态**：`nmoi_presets_json` 给什么就显示什么——
 * 否则"要不要更新"这件事会有两个答案，而用户的动作只会落在一个上。
 */
@Composable
internal fun PresetScreen(
    context: Context,
    databasePath: String,
    reloadKey: Int,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var list by remember { mutableStateOf<PresetList?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        PresetRepository.list(context, databasePath).fold(
            onSuccess = {
                list = it
                failure = null
            },
            onFailure = {
                list = null
                failure = it.message ?: "读不出预设"
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "预设",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            val current = list
            val reason = failure
            Text(
                // 这一句来自 core 的 `Summary::describe`：写的数与下面每一行的徽标同源。
                text = when {
                    reason != null -> "读不出预设：$reason（原因在 设置 → 调试 → 日志）"
                    current == null -> "读预设…"
                    else -> current.summaryLine
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                current?.items?.forEach { preset ->
                    PresetRow(preset = preset, onClick = { onOpen(preset.id) })
                }
                if (current != null && current.items.isEmpty()) {
                    Text(
                        text = "包里没有预设：`assets/presets/` 下缺文件（这不是坏事，只是没内容可注入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Text(
                    text = "只在这些工具真的声明出去的那一轮才会注入：工具关掉，它那几条规矩一起消失。" +
                        "原文随包走；你改过的那一份存在库里，永不自动覆盖。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetRow(preset: PresetView, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append("v${preset.version} · ${preset.entries.size} 条词条")
                    if (preset.appliesTo.isNotEmpty()) {
                        append(" · 需要 ${preset.appliesTo.joinToString(" / ")}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        StateBadge(state = preset.state, label = preset.label)
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** 四态徽标。四种状态必须长得不一样——写成同一个灰底加同一个字就分不出该做什么。 */
@Composable
private fun StateBadge(state: String, label: String) {
    val colors = MaterialTheme.colorScheme
    val (text, background) = when (state) {
        "conflict" -> colors.error to colors.error.copy(alpha = 0.12f)
        "upstream_newer" -> colors.primary to colors.primary.copy(alpha = 0.12f)
        "local_edited" -> colors.onSurface to colors.onSurface.copy(alpha = 0.08f)
        else -> colors.onSurfaceVariant to colors.onSurfaceVariant.copy(alpha = 0.08f)
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * 一份预设的整页编辑（0.14t）。
 *
 * 一条词条一张卡：正文可改、可禁用；`base_version` 只显示**不可改**（D159 的双层不可改，
 * 这里就是第一层：界面上根本没有可以改它的控件）。
 *
 * 冲突时每条词条各给两个动作（「用上游」/「留本地」）——这就是"逐条选"：
 * 不做一个"整份一键"的按钮，因为它会把某几条的本地改动一起抹掉。
 */
@Composable
internal fun PresetEditScreen(
    context: Context,
    databasePath: String,
    presetId: String,
    reloadKey: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var preset by remember { mutableStateOf<PresetView?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 每条词条的草稿（正文 / 是否禁用）。**只放 id 对应的值，不放对象**（D155）。
    val drafts = remember { mutableStateMapOf<String, String>() }
    val disabled = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        PresetRepository.list(context, databasePath).fold(
            onSuccess = { list ->
                val found = list.items.firstOrNull { it.id == presetId }
                preset = found
                failure = if (found == null) "包里没有预设 $presetId" else null
                drafts.clear()
                disabled.clear()
                found?.entries?.forEach {
                    drafts[it.id] = it.content
                    disabled[it.id] = it.disabled
                }
            },
            onFailure = {
                preset = null
                failure = it.message ?: "读不出预设"
            },
        )
    }

    LaunchedEffect(reloadKey, presetId) { refresh() }

    val current = preset

    fun save() {
        val target = current ?: return
        if (busy) return
        busy = true
        scope.launch {
            val changed = target.entries.filter { entry ->
                (drafts[entry.id] ?: entry.content) != entry.content ||
                    (disabled[entry.id] ?: entry.disabled) != entry.disabled
            }
            if (changed.isEmpty()) {
                busy = false
                notice = "什么都没改"
                return@launch
            }
            var written = 0
            var lastError: String? = null
            for (entry in changed) {
                val content = drafts[entry.id] ?: entry.content
                val off = disabled[entry.id] ?: entry.disabled
                if (content.isBlank() && !off) {
                    lastError = "「${entry.title}」的正文是空的：要停用就点「停用」，别留个空串"
                    break
                }
                PresetRepository.saveEntry(
                    databasePath = databasePath,
                    presetId = target.id,
                    entry = entry,
                    content = content,
                    disabled = off,
                ).fold(
                    onSuccess = { written += 1 },
                    onFailure = {
                        lastError = "「${entry.title}」保存失败：${it.message}"
                    },
                )
                if (lastError != null) break
            }
            busy = false
            if (lastError != null) {
                notice = lastError
            } else {
                notice = "已保存 $written 条词条"
                refresh()
                onSaved()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = current?.title ?: "预设",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Text(
                    text = if (busy) "保存中…" else "保存",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (busy) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clickable(enabled = !busy && current != null) { save() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            val shown = current
            val reason = failure
            val line = notice
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (shown == null) {
                Spacer(Modifier.heightIn(min = 8.dp))
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        text = shown.summary.ifBlank { "（没有说明）" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    shown.entries.forEach { entry ->
                        EntryCard(
                            entry = entry,
                            value = drafts[entry.id] ?: entry.content,
                            disabled = disabled[entry.id] ?: entry.disabled,
                            onValueChange = {
                                drafts[entry.id] = it
                                notice = null
                            },
                            onToggleDisabled = {
                                disabled[entry.id] = !(disabled[entry.id] ?: entry.disabled)
                                notice = null
                            },
                            onKeepLocal = {
                                busy = true
                                scope.launch {
                                    PresetRepository.keepLocal(databasePath, shown.id, entry)
                                        .fold(
                                            onSuccess = {
                                                busy = false
                                                notice = "「${entry.title}」保留了本地那份，基础版本已跟进上游"
                                                refresh()
                                                onSaved()
                                            },
                                            onFailure = {
                                                busy = false
                                                notice = "保留本地失败：${it.message}"
                                            },
                                        )
                                }
                            },
                            onTakeUpstream = {
                                busy = true
                                scope.launch {
                                    PresetRepository.takeUpstream(databasePath, shown.id, entry.id)
                                        .fold(
                                            onSuccess = {
                                                busy = false
                                                notice = "「${entry.title}」已换成上游版本"
                                                refresh()
                                                onSaved()
                                            },
                                            onFailure = {
                                                busy = false
                                                notice = "换上游失败：${it.message}"
                                            },
                                        )
                                }
                            },
                        )
                    }
                    Text(
                        text = "基础版本 v${shown.version}：它记着'你上次同步时看到的是哪一版'，" +
                            "只能靠同步往前推，界面上改不了（改了会让四态从此算不准）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: PresetEntryView,
    value: String,
    disabled: Boolean,
    onValueChange: (String) -> Unit,
    onToggleDisabled: () -> Unit,
    onKeepLocal: () -> Unit,
    onTakeUpstream: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            StateBadge(state = entry.state, label = entry.label)
        }

        // 冲突时把上游那份摆出来：不给对照，"用上游"就是闭着眼点的。
        if (entry.state == "conflict") {
            Text(
                text = "上游 v${entry.version}：" + entry.upstream.take(160),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(label = "用上游", primary = false, onClick = onTakeUpstream)
                PresetChip(label = "留本地", primary = true, onClick = onKeepLocal)
            }
        } else if (entry.state == "upstream_newer") {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(label = "更新到 v${entry.version}", primary = true, onClick = onTakeUpstream)
            }
        } else if (entry.state == "local_edited") {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(label = "恢复跟随上游", primary = false, onClick = onTakeUpstream)
            }
        }

        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = if (disabled) colors.onSurfaceVariant else colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetChip(
                label = if (disabled) "已停用 · 点击启用" else "停用这一条",
                primary = false,
                onClick = onToggleDisabled,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (disabled) "停用后不进请求，但还留在这里" else "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetChip(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = if (primary) colors.primary else colors.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 只有在别处需要"读一次再算"时才用它：整页自己会在 [LaunchedEffect] 里读。 */
internal suspend fun loadPresets(context: Context, databasePath: String): PresetList? =
    withContext(Dispatchers.IO) {
        PresetRepository.list(context, databasePath).getOrNull()
    }
