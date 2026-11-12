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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
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
 * - 跟随、而这个角色本来初始就不是跟随 → 禁用（点了回到未启用）
 * - 跟随、且它本来就是跟随型 → 不显示第一个动作：点了也没变化的东西不摆。
 */
internal enum class ModelRole(
    val key: String,
    val title: String,
    val followByDefault: Boolean,
    val icon: ImageVector,
    /**
     * 「未启用」时还摆不摆那颗重置箭头。
     *
     * 视觉覆盖不摆（用户 2026-11-09）：它的初始值就是未启用，摆一颗点了只会回到原处的箭头，
     * 等于告诉用户"这里能切"而其实切不动。要开就点值条选模型。
     */
    val resetWhenDisabled: Boolean = true,
) {
    Title("title", "标题总结", false, ActionIcons.Square),
    Suggest("suggest", "聊天建议", false, ActionIcons.SearchAssistant),
    Vision("vision", "视觉覆盖", false, ActionIcons.Image, resetWhenDisabled = false),
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
    // 齿轮（D298）：只对**已经有消费者**的角色出现——拨了不生效就是假开关。
    var promptFor by remember { mutableStateOf<ModelRole?>(null) }
    // 每次改了角色配置就 +1：值从 prefs 现读，不另存一份（免得两处对不上）。
    var revision by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    // 所有角色共用的那段系统提示词（D318）：值是现读的那一份，改完立刻反映到行尾。
    var globalSystem by remember { mutableStateOf(AppSettings.globalSystem(context)) }
    var editingGlobal by remember { mutableStateOf(false) }
    // 选模型那一页跟聊天页用的是同一份来源（供应商 → 模型），所以这里也读同一份。
    val choices by produceState(initialValue = emptyList<ModelChoice>(), databasePath) {
        val result = withContext(Dispatchers.IO) { AssistantRepository.choices(databasePath) }
        value = result.getOrDefault(emptyList()).map { ModelChoice(it.first, it.second) }
        loaded = result.isSuccess
    }
    val keys = remember(choices) { choices.map { it.key }.toSet() }
    // 角色那一层能勾的世界书（D313）：打开面板时现读一次，导入了新册不必重启。
    var books by remember { mutableStateOf(listOf<BookRow>()) }
    LaunchedEffect(promptFor) {
        if (promptFor == null) return@LaunchedEffect
        books = withContext(Dispatchers.IO) {
            LorebookStore.bookRows(LorebookStore.library(context))
        }
    }

    // 选过但模型不在了：切回这个角色的初始值（用户 2026-11-08 定）。
    // 读到库之前不动手：那时候 `keys` 是空的，"不在库里"和"还没读出来"看起来一样。
    val values = remember(revision, keys, loaded) {
        ModelRole.entries.associateWith { role ->
            val raw = AppSettings.roleValue(context, role.key, role.followByDefault)
            val pinned = splitPinned(raw)
            if (loaded && pinned != null && ModelFavorites.key(pinned.first, pinned.second) !in keys) {
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
                // 全局那段在最上面（D318）：它管的是**所有**角色，摆在第一个才说得通。
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    NavRow(
                        title = "系统提示词",
                        value = if (globalSystem.isBlank()) "未设置" else "${globalSystem.length} 字",
                        onClick = { editingGlobal = true },
                    )
                }
                ModelRole.entries.forEach { role ->
                    RoleCard(
                        role = role,
                        value = values[role].orEmpty(),
                        label = roleLabel(role, values[role].orEmpty(), choices),
                        onPickModel = { picking = role },
                        // 六张卡都有齿轮（D328）：摘要的消费者是索引摘要（D322），
                        // 它的那段提示词以前没有入口——面板里能配、程序不用，比没有这个框更坏。
                        onOpenPrompt = { promptFor = role },
                        // 卡片上那颗箭头自己算目标值：↺ → 跟随，🚫 → 初始值 / 未启用。
                        onSetValue = { next ->
                            AppSettings.setRoleValue(context, role.key, next)
                            notice = "${role.title}：${roleLabel(role, next, choices)}"
                            revision += 1
                        },
                    )
                }

                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    NavRow("提示词", onClick = onOpenPrompts)
                    RowDivider(leading = 16.dp)
                    NavRow("预设", onClick = onOpenPresets)
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

    if (editingGlobal) {
        GlobalSystemSheet(
            context = context,
            value = globalSystem,
            onChange = { globalSystem = it },
            onDismiss = { editingGlobal = false },
        )
    }

    promptFor?.let { role ->
        val pinned = splitPinned(values[role].orEmpty())
        val model = pinned?.let { target ->
            choices.firstOrNull {
                it.provider.id == target.first && it.model.id == target.second
            }?.model
        }
        RolePromptSheet(
            context = context,
            role = role,
            efforts = model?.efforts.orEmpty(),
            allowDisable = model?.allowDisable != false,
            books = books,
            onDismiss = { promptFor = null },
        )
    }

    picking?.let { role ->
        ModelPickerSheet(
            databasePath = databasePath,
            title = role.title,
            choices = choices,
            selected = splitPinned(values[role].orEmpty())
                ?.let { ModelFavorites.key(it.first, it.second) },
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
    onSetValue: (String) -> Unit,
    /** `null` = 这个角色还没有消费者，不摆齿轮（不给假开关）。 */
    onOpenPrompt: (() -> Unit)? = null,
) {
    val pinned = splitPinned(value) != null
    val disabled = value == AppSettings.ROLE_DISABLED
    val initial = if (role.followByDefault) AppSettings.ROLE_FOLLOW else AppSettings.ROLE_DISABLED
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
                pinned -> IconActionButton(
                    ActionIcons.Ban,
                    "禁用：回到这个角色的初始值",
                    { onSetValue(initial) },
                    compact = true,
                )
                disabled && role.resetWhenDisabled -> IconActionButton(
                    ActionIcons.RotateCcw,
                    "重置：改成使用当前对话模型",
                    { onSetValue(AppSettings.ROLE_FOLLOW) },
                    compact = true,
                )
                value == AppSettings.ROLE_FOLLOW && !role.followByDefault -> IconActionButton(
                    ActionIcons.Ban,
                    "禁用：回到未启用",
                    { onSetValue(AppSettings.ROLE_DISABLED) },
                    compact = true,
                )
            }
            if (onOpenPrompt != null) {
                IconActionButton(ActionIcons.Settings, "这个角色的提示词", onOpenPrompt, compact = true)
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
private fun NavRow(title: String, value: String? = null, onClick: () -> Unit) {
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
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = ActionIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 一个角色的三样东西（D298 / D313）：思考强度、世界书、系统提示词。
 *
 * 三样都走 `nmoi_complete_json`：`thinking` 与 `turn_run` 同一套档位，
 * `lorebook` 是**已按勾选筛过**的库，`system` 排在调用方给的那段之前。
 * 全都改了就存，所以没有「保存」也没有「完成」。
 *
 * 停手 100 ms 自动保存（用户 2026-11-07 定）：这类输入框不摆"保存"按钮。
 */
@Composable
private fun RolePromptSheet(
    context: Context,
    role: ModelRole,
    /** 这个角色指到的模型支持哪些档位（空 = 不知道，全给）。 */
    efforts: List<String>,
    allowDisable: Boolean,
    books: List<BookRow>,
    onDismiss: () -> Unit,
) {
    var text by remember(role) { mutableStateOf(AppSettings.roleSystem(context, role.key)) }
    // 停手 100 ms 落盘。`text` 变了就重来一次，所以连打不会一直写盘。
    LaunchedEffect(text) {
        delay(100)
        AppSettings.setRoleSystem(context, role.key, text)
    }
    var thinking by remember(role) { mutableStateOf(AppSettings.roleThinking(context, role.key)) }
    var ownBooks by remember(role) { mutableStateOf(AppSettings.roleLorebooks(context, role.key)) }
    val levels = THINKING_LEVELS.filter { (wire, _) ->
        when {
            wire == "auto" -> true
            wire == "off" -> allowDisable
            efforts.isEmpty() -> true
            else -> wire in efforts
        }
    }
    val unsupported = thinking.isNotEmpty() && levels.none { it.first == thinking }
    SheetFrame(title = "${role.title} · 提示词", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "思考强度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThinkingChips(levels = levels, selected = thinking) { wire ->
                thinking = wire
                AppSettings.setRoleThinking(context, role.key, wire)
            }
            if (unsupported) {
                Text(
                    text = "现在是「${thinkingShort(thinking)}」，这个模型不支持",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 摘要这条路不读世界书：`SummaryTarget` 里没有这个字段，摆一列勾选框
            // 就是"点了什么也不发生"（D328）。别的五个角色都有那条路，所以只对摘要收起来。
            if (role != ModelRole.Summary) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "世界书",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${ownBooks.count { id -> books.any { it.id == id } }}/${books.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LorebookBookList(
                    rows = books,
                    active = ownBooks.toSet(),
                    onToggle = { id, on ->
                        ownBooks = if (on) ownBooks + id else ownBooks - id
                        AppSettings.setRoleLorebooks(context, role.key, ownBooks)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                text = "系统提示词",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        if (role == ModelRole.Summary) {
                            "留空：只用内置的索引规则（段号、每段一行、48 字以内）"
                        } else {
                            "例如：只输出译文，不要解释"
                        },
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            // 站位字符串（D316）：框外一行，只列这个角色真的会被填的那些。
            val vars = PromptVars.names(role)
            if (vars.isNotEmpty()) {
                Text(
                    text = "变量：" + vars.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.isNotEmpty()) {
                    ChipButton("恢复默认", primary = false) {
                        text = ""
                        AppSettings.setRoleSystem(context, role.key, "")
                    }
                }
                Spacer(Modifier.weight(1f))
                ChipButton("收起", primary = true, onClick = onDismiss)
            }
        }
    }
}

/**
 * 所有角色共用那段系统提示词的编辑面板（D318）。
 *
 * 拼法只有一处（`RoleRunner.complete`）：内置那段 → 这一段 → 角色自己那段。
 * 它就是一张输入框，所以不摆「保存」——停手 100 ms 落盘，跟别的输入框同一个规矩。
 */
@Composable
private fun GlobalSystemSheet(
    context: Context,
    value: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(value) {
        delay(100)
        AppSettings.setGlobalSystem(context, value)
    }
    SheetFrame(title = "系统提示词 · 所有角色", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text("例如：只输出结果，不复述要求") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value.isNotEmpty()) {
                    ChipButton("清空", primary = false) {
                        onChange("")
                        AppSettings.setGlobalSystem(context, "")
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
