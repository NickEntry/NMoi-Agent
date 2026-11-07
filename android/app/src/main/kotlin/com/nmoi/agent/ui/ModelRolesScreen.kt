package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认模型的六个角色（D289）。
 *
 * 每个角色是"一件事配一个模型"，不是"选一个模型"。三态：
 * `disabled`（未启用）/ `follow`（跟着当前对话模型）/ `provider|model`（指定）。
 *
 * 初始值分两类：标题总结 / 聊天建议 / 视觉覆盖 初始**未启用**；摘要 / 压缩 / 翻译 初始**跟随**
 * ——后三样永远有活干，没有"关掉"这一说。
 *
 * 卡片右侧第一个动作跟着状态变：
 * - 未启用 → 重置箭头（点了变成"使用当前对话模型"）
 * - 选了具体模型 → 禁用（点了回到这个角色的初始值）
 * - 跟随（且它本来就是跟随型）→ 不显示第一个动作：点了也没变化的东西不摆。
 */
internal enum class ModelRole(
    val key: String,
    val title: String,
    val followByDefault: Boolean,
    val icon: ImageVector,
) {
    Title("title", "标题总结", false, ActionIcons.Square),
    Suggest("suggest", "聊天建议", false, ActionIcons.SearchAssistant),
    Vision("vision", "视觉覆盖", false, ActionIcons.Image),
    Summary("summary", "摘要", true, ActionIcons.Database),
    Compress("compress", "压缩", true, ActionIcons.Gauge),
    Translate("translate", "翻译", true, ActionIcons.Languages),
}

/** `provider|model` 拆开；不是那个形状就返回 null。 */
private fun splitPinned(value: String): Pair<String, String>? {
    if (!value.contains('|')) return null
    val provider = value.substringBefore('|')
    val model = value.substringAfter('|')
    return if (provider.isBlank() || model.isBlank()) null else provider to model
}

@Composable
internal fun ModelRolesScreen(
    context: Context,
    databasePath: String,
    onOpenPrompts: () -> Unit,
    onOpenPresets: () -> Unit,
    onBack: () -> Unit,
) {
    var notice by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<ModelRole?>(null) }
    // 每次改了角色配置就 +1：值从 prefs 现读，不另存一份（免得两处对不上）。
    var revision by remember { mutableStateOf(0) }
    // 选模型那一页跟聊天页用的是同一份来源（供应商 → 模型），所以这里也读同一份。
    val choices by produceState(initialValue = emptyList<ModelChoice>(), databasePath) {
        value = withContext(Dispatchers.IO) {
            AssistantRepository.choices(databasePath).getOrDefault(emptyList())
                .map { ModelChoice(it.first, it.second) }
        }
    }
    val keys = remember(choices) { choices.map { it.key }.toSet() }

    // 选过但模型不在了：切回这个角色的初始值（用户 2026-11-08 定）。
    val values = remember(revision, keys) {
        ModelRole.entries.associateWith { role ->
            val raw = AppSettings.roleValue(context, role.key, role.followByDefault)
            val pinned = splitPinned(raw)
            if (pinned != null && "${pinned.first}|${pinned.second}" !in keys) {
                if (role.followByDefault) AppSettings.ROLE_FOLLOW else AppSettings.ROLE_DISABLED
            } else {
                raw
            }
        }
    }
    // 失效的那几个落盘改回默认：写盘放在 effect 里，不在组合里做。
    LaunchedEffect(values) {
        values.forEach { (role, value) ->
            if (AppSettings.roleValue(context, role.key, role.followByDefault) != value) {
                AppSettings.setRoleValue(context, role.key, value)
            }
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
                    text = "默认模型",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelRole.entries.forEach { role ->
                    RoleCard(
                        role = role,
                        value = values[role].orEmpty(),
                        label = roleLabel(role, values[role].orEmpty(), choices),
                        onPickModel = { picking = role },
                        onReset = {
                            val fallback = if (role.followByDefault) {
                                AppSettings.ROLE_FOLLOW
                            } else {
                                AppSettings.ROLE_DISABLED
                            }
                            AppSettings.setRoleValue(context, role.key, fallback)
                            notice = "${role.title}：${roleLabel(role, fallback, choices)}"
                            revision += 1
                        },
                        onDisable = {
                            val fallback = if (role.followByDefault) {
                                AppSettings.ROLE_FOLLOW
                            } else {
                                AppSettings.ROLE_DISABLED
                            }
                            AppSettings.setRoleValue(context, role.key, fallback)
                            notice = "${role.title}：${roleLabel(role, fallback, choices)}"
                            revision += 1
                        },
                    )
                }

                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    NavRow("提示词", onOpenPrompts)
                    RowDivider(leading = 16.dp)
                    NavRow("预设", onOpenPresets)
                }

                notice?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    picking?.let { role ->
        ModelPickerSheet(
            databasePath = databasePath,
            title = role.title,
            choices = choices,
            selected = splitPinned(values[role].orEmpty())?.let { "${it.first}|${it.second}" },
            emptyHint = "还没有供应商或模型：先去「供应商」加一个",
            onPick = { choice ->
                AppSettings.setRoleValue(context, role.key, "${choice.provider.id}|${choice.model.id}")
                notice = "${role.title}：${choice.model.name.ifBlank { choice.model.id }}"
                picking = null
                revision += 1
            },
            onNotice = { notice = it },
            onDismiss = { picking = null },
        )
    }
}

/** 值条上写什么：未启用 / 使用当前对话模型 / 模型名。 */
private fun roleLabel(role: ModelRole, value: String, choices: List<ModelChoice>): String {
    if (value == AppSettings.ROLE_DISABLED) return "未启用"
    if (value == AppSettings.ROLE_FOLLOW) return "使用当前对话模型"
    val pinned = splitPinned(value) ?: return "未启用"
    val choice = choices.firstOrNull { it.provider.id == pinned.first && it.model.id == pinned.second }
    return choice?.model?.name?.ifBlank { choice.model.id } ?: "未启用"
}

@Composable
private fun RoleCard(
    role: ModelRole,
    value: String,
    label: String,
    onPickModel: () -> Unit,
    onReset: () -> Unit,
    onDisable: () -> Unit,
) {
    val pinned = splitPinned(value) != null
    val disabled = value == AppSettings.ROLE_DISABLED
    SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = role.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = role.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when {
                disabled -> IconActionButton(ActionIcons.RotateCcw, "重置", onReset, compact = true)
                pinned -> IconActionButton(ActionIcons.Ban, "禁用", onDisable, compact = true)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .clickable(onClick = onPickModel),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    disabled -> "未"
                                    value == AppSettings.ROLE_FOLLOW -> "使"
                                    else -> label.take(1)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = ActionIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = ActionIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
