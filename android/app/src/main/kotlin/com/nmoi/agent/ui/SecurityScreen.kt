package com.nmoi.agent.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设置 → 安全（0.17g）。
 *
 * 三件事：
 *
 * - 两颗总开关：**问不问**、**只问清单里的还是每条都问**。它们只管"要不要先问一句"，
 *   不决定"能不能跑"——能力开关还在 工具与工作区 那一页。
 * - 规则组：主项（组）能改名、停用、删除；子项（名字）能加、能左滑删。
 *   内置十组来自 core 的清单，自建组只存在本机 `security.json`。
 * - 拒绝档（`rm -rf /` 那几条）挂在内置组里，**不可删不可改名**，照旧逐条看得见。
 *
 * 「MCP 工具调用前询问」这一批没摆：MCP 客户端本身还没有，摆出来就是一个点了不生效的开关。
 */
@Composable
internal fun SecurityScreen(
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var local by remember { mutableStateOf(SecurityState()) }
    var confirmAskOff by remember { mutableStateOf(false) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<RuleGroup?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<RuleGroup?>(null) }
    var editingItem by remember { mutableStateOf<Pair<String, String>?>(null) }
    var itemText by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        local = SecurityRepository.state(context)
        catalog = withContext(Dispatchers.IO) { NmoiFfi.shellRules().getOrNull() }
    }

    val builtin = remember(catalog) { builtinGroups(catalog) }
    val merged = remember(builtin, local) { mergedGroups(builtin, local.groups) }
    val rules = remember(catalog) { builtinRules(catalog) }
    val prints = remember(builtin) { builtin.associate { it.id to it.base } }

    fun commit(next: List<RuleGroup>) {
        // 没动过的内置组不落盘：文件里没有那一行，就等于"整组跟着目录走"。
        // 动过的组记下**当时**目录的指纹，下一版目录换了才知道"你的改动基于旧版"。
        val catalogName = builtin.associate { it.id to it.name }
        val before = local.groups.associateBy { it.id }
        val kept = next.mapNotNull { group ->
            var out = group.copy(state = PresetState.Follow)
            if (out.builtin) {
                if (out.name == catalogName[out.id]) out = out.copy(name = "")
                val old = before[out.id]
                val mark = if (old == null || touched(old, out)) prints[out.id].orEmpty() else old.base
                out = out.copy(base = mark)
                if (!editedOf(out)) return@mapNotNull null
            }
            out
        }
        SecurityRepository.setGroups(context, kept)
        local = local.copy(groups = kept)
    }

    // 目录这一版变了、而这一组是被删掉的：当场把它放回来并落盘。
    // 只让界面知道没用——请求侧读的是这份文件，两处会给出两个答案。
    LaunchedEffect(builtin) {
        val rows = local.groups
        if (builtin.isEmpty() || rows.isEmpty()) return@LaunchedEffect
        var revived = false
        val next = rows.map { row ->
            val catalogRow = builtin.firstOrNull { it.id == row.id } ?: return@map row
            if (row.builtin && row.hidden && movedOn(row, catalogRow)) {
                revived = true
                row.copy(hidden = false)
            } else {
                row
            }
        }
        if (revived) commit(next)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "安全",
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
                SectionLabel("总开关")
                GroupCard {
                    SwitchRow(
                        title = "工具执行前询问",
                        subtitle = if (local.ask) {
                            "命中清单的调用先问一句。关掉就是「能力一开就全放行」。"
                        } else {
                            "已关：命中清单的也直接跑，收不回来。"
                        },
                        checked = local.ask,
                        onChecked = { next ->
                            if (next) {
                                SecurityRepository.setAsk(context, true)
                                local = local.copy(ask = true)
                            } else {
                                confirmAskOff = true
                            }
                        },
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 14.dp),
                    )
                    SwitchRow(
                        title = "黑名单模式",
                        subtitle = if (local.blacklist) {
                            "只问清单里的。清单永远不完备——它降低打扰，不是安全保证。"
                        } else {
                            "每条都问。"
                        },
                        checked = local.blacklist,
                        onChecked = { next ->
                            SecurityRepository.setBlacklist(context, next)
                            local = local.copy(blacklist = next)
                        },
                    )
                }

                SectionLabel(if (builtin.isEmpty()) "规则组" else "规则组 · ${merged.size} 组")
                if (builtin.isEmpty()) {
                    GroupCard {
                        Text(
                            text = "读规则表…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                    }
                } else {
                    merged.forEach { group ->
                        if (group.hidden) return@forEach
                        val rows = rules[group.id].orEmpty()
                        GroupBlock(
                            group = group,
                            builtinRows = rows,
                            adding = addingTo == group.id,
                            draft = draft,
                            onDraft = { draft = it },
                            onStartAdd = {
                                addingTo = group.id
                                draft = ""
                                notice = null
                            },
                            onCancelAdd = {
                                addingTo = null
                                draft = ""
                            },
                            onSubmitAdd = {
                                val name = draft.trim().lowercase()
                                when {
                                    name.isEmpty() -> notice = "写一个名字再添加"
                                    name.contains(' ') -> notice = "名字里不能有空格——它只匹配命令名"
                                    group.items.contains(name) -> notice = "「$name」已经在这一组里了"
                                    else -> {
                                        commit(
                                            merged.map {
                                                if (it.id == group.id) {
                                                    it.copy(items = it.items + name)
                                                } else {
                                                    it
                                                }
                                            },
                                        )
                                        addingTo = null
                                        draft = ""
                                        notice = null
                                    }
                                }
                            },
                            onToggleAsk = { asks ->
                                commit(merged.map { if (it.id == group.id) it.copy(asks = asks) else it })
                            },
                            onRename = {
                                renaming = group
                                renameText = group.name
                            },
                            onDelete = { deleting = group },
                            onFollowNew = {
                                commit(
                                    merged.map {
                                        if (it.id == group.id) it.copy(name = "", asks = true) else it
                                    },
                                )
                                notice = null
                            },
                            onEditItem = { name ->
                                editingItem = group.id to name
                                itemText = name
                                notice = null
                            },
                            onDeleteItem = { name ->
                                commit(
                                    merged.map {
                                        if (it.id == group.id) {
                                            it.copy(items = it.items - name)
                                        } else {
                                            it
                                        }
                                    },
                                )
                            },
                        )
                    }
                }

                val hiddenCount = merged.count { it.hidden }
                if (hiddenCount > 0) {
                    GroupCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picking = true }
                                .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "＋ 添加预设组",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "被删掉的内置组还有 $hiddenCount 个，逐个加回来",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SectionLabel("拒绝档")
                GroupCard {
                    Text(
                        text = "`rm -rf /`、`dd` 写块设备这些**总是拦**，挂在内置组里，不可删、不可改名。" +
                            "它们不走上面那些开关。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    RowLine()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenRules)
                            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "内置规则清单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                notice?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    )
                }
            }
        }
    }

    if (confirmAskOff) {
        ConfirmCard(
            title = "关掉之后不再问？",
            body = "模型接下来可以直接跑任何命令，包括删除和覆盖。清单里的那几条也不问了。" +
                "随时可以回来重新打开。",
            confirm = "关掉",
            onCancel = { confirmAskOff = false },
            onConfirm = {
                confirmAskOff = false
                SecurityRepository.setAsk(context, false)
                local = local.copy(ask = false)
            },
        )
    }

    editingItem?.let { target ->
        val owner = merged.firstOrNull { it.id == target.first }
        InputCard(
            title = "改这个名字",
            body = "只改名字，规则不动。改完还是只匹配命令名。",
            value = itemText,
            placeholder = "命令名",
            confirm = "保存",
            onValue = { itemText = it },
            onCancel = { editingItem = null },
            onConfirm = {
                val next = itemText.trim().lowercase()
                when {
                    next.isEmpty() -> notice = "名字不能空着"
                    next.contains(' ') -> notice = "名字里不能有空格——它只匹配命令名"
                    next == target.second -> editingItem = null
                    owner?.items?.contains(next) == true -> notice = "「$next」已经在这一组里了"
                    else -> {
                        commit(
                            merged.map { group ->
                                if (group.id == target.first) {
                                    group.copy(items = group.items.map { if (it == target.second) next else it })
                                } else {
                                    group
                                }
                            },
                        )
                        editingItem = null
                        notice = null
                    }
                }
            },
        )
    }

    if (picking) {
        val gone = merged.filter { it.hidden }
        AddPresetCard(
            groups = gone,
            onPick = { id ->
                commit(merged.map { if (it.id == id) it.copy(hidden = false) else it })
                notice = null
                if (gone.size <= 1) picking = false
            },
            onDismiss = { picking = false },
        )
    }

    renaming?.let { group ->
        InputCard(
            title = "改这一组的名字",
            body = if (group.builtin) "内置组改了名，清单还是原来那份。" else "",
            value = renameText,
            placeholder = "组名",
            confirm = "保存",
            onValue = { renameText = it },
            onCancel = { renaming = null },
            onConfirm = {
                val name = renameText.trim()
                if (name.isEmpty()) {
                    notice = "组名不能空着"
                } else {
                    commit(merged.map { if (it.id == group.id) it.copy(name = name) else it })
                    renaming = null
                }
            },
        )
    }

    deleting?.let { group ->
        ConfirmCard(
            title = if (group.builtin) "把「${group.name}」从列表里删掉？" else "删掉「${group.name}」？",
            body = if (group.builtin) {
                "这一组不再出现在列表里，也不再问你。它留在代码里；哪天这一组的规则变了，它会自己回来。"
            } else {
                "自建组是真删：这一组和它下面加的名字一起没了。"
            },
            confirm = "删除",
            onCancel = { deleting = null },
            onConfirm = {
                if (group.builtin) {
                    commit(
                        merged.map {
                            if (it.id == group.id) it.copy(hidden = true, count = group.count) else it
                        },
                    )
                } else {
                    commit(merged.filter { it.id != group.id })
                }
                deleting = null
            },
        )
    }
}

@Composable
private fun GroupBlock(
    group: RuleGroup,
    builtinRows: List<Pair<String, Boolean>>,
    adding: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onStartAdd: () -> Unit,
    onCancelAdd: () -> Unit,
    onSubmitAdd: () -> Unit,
    onToggleAsk: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onFollowNew: () -> Unit,
    onEditItem: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
) {
    var folded by remember(group.id) { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { folded = !folded }
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (folded) ActionIcons.ChevronRight else ActionIcons.ChevronDown,
                    contentDescription = if (folded) "展开「${group.name}」" else "收起「${group.name}」",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (group.builtin) {
                                "${group.count} 条 · 内置"
                            } else {
                                "${group.items.size} 条 · 自建"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (group.state != PresetState.Follow) {
                            Spacer(Modifier.size(6.dp))
                            StateBadge(
                                label = group.state.label,
                                tone = when (group.state) {
                                    PresetState.Stale -> BadgeTone.Warn
                                    PresetState.Modified -> BadgeTone.Edited
                                    PresetState.Follow -> BadgeTone.Quiet
                                },
                            )
                        }
                    }
                }
                Text(
                    text = if (group.asks) "问" else "不问",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (group.asks) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
                Switch(checked = group.asks, onCheckedChange = onToggleAsk)
            }

            if (!folded) {
                for ((label, deny) in builtinRows) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
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

                for (name in group.items) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TinyChip("改名", onClick = { onEditItem(name) })
                        Spacer(Modifier.size(6.dp))
                        TinyChip("删除", danger = true, onClick = { onDeleteItem(name) })
                    }
                }

                if (group.state == PresetState.Stale) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "内置清单已更新，你的改动还是旧那一版",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TinyChip("跟新版", onClick = onFollowNew)
                    }
                }

                RowLine(leading = 14.dp)
                if (adding) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingField(
                                value = draft,
                                onChange = onDraft,
                                placeholder = "命令名，如 rm",
                            )
                        }
                        TinyChip("取消", onClick = onCancelAdd)
                        Spacer(Modifier.size(6.dp))
                        TinyChip("加进去", primary = true, onClick = onSubmitAdd)
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onStartAdd)
                            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "＋ 添加一条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TinyChip("改名", onClick = onRename)
                        Spacer(Modifier.size(6.dp))
                        TinyChip("删除", danger = true, onClick = onDelete)
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

@Composable
private fun RowLine(leading: androidx.compose.ui.unit.Dp = 16.dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = leading)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun TinyChip(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        primary -> MaterialTheme.colorScheme.primary
        danger -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = when {
        primary -> MaterialTheme.colorScheme.onPrimary
        danger -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(999.dp), color = background, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 「添加预设组」：被删掉的内置组逐个加回来；加完那个就从单子里消失。 */
@Composable
private fun AddPresetCard(
    groups: List<RuleGroup>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "添加预设组",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "这些是内置组，之前从列表里删掉了。加回来之后照旧会问、也照旧能改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                    ) {
                        groups.forEach { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(group.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${group.count} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "＋",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TinyChip("关闭", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    title: String,
    body: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TinyChip("取消", onClick = onCancel)
                        Spacer(Modifier.size(8.dp))
                        TinyChip(confirm, danger = true, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

@Composable
private fun InputCard(
    title: String,
    body: String,
    value: String,
    placeholder: String,
    confirm: String,
    onValue: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (body.isNotEmpty()) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingField(value = value, onChange = onValue, placeholder = placeholder)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TinyChip("取消", onClick = onCancel)
                        Spacer(Modifier.size(8.dp))
                        TinyChip(confirm, primary = true, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

/**
 * 内置十组：id / 名字 / 条数 / **指纹**。
 *
 * `count` 是目录里现在几条；`base` 是这一版目录的指纹——用户改过一组时把它记下来，
 * 下一版目录换了，`presetStateOf` 才分得清"你的改动还是这一版"和"你改的是旧版"。
 */
private fun builtinGroups(catalog: JSONObject?): List<RuleGroup> {
    val array = catalog?.optJSONArray("groups") ?: return emptyList()
    val out = mutableListOf<RuleGroup>()
    for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val id = row.text("id")
        if (id.isEmpty()) continue
        val rules = rulesOf(row)
        out.add(
            RuleGroup(
                id = id,
                name = row.text("name").ifBlank { id },
                builtin = true,
                asks = true,
                hidden = false,
                count = rules.size,
                items = emptyList(),
                base = groupPrint(rules),
            ),
        )
    }
    return out
}

/** 一组里的条目：`(label, 是不是总是拦)`。指纹与展开后的清单读的是同一份。 */
private fun rulesOf(group: JSONObject): List<Pair<String, Boolean>> {
    val rules = group.optJSONArray("rules") ?: return emptyList()
    val out = mutableListOf<Pair<String, Boolean>>()
    for (at in 0 until rules.length()) {
        val rule = rules.optJSONObject(at) ?: continue
        val label = rule.text("label")
        if (label.isNotEmpty()) out.add(label to rule.optBoolean("deny", false))
    }
    return out
}

/** 内置组里那些条目。它们不可删、不可改名，只能整组不问。 */
private fun builtinRules(catalog: JSONObject?): Map<String, List<Pair<String, Boolean>>> {
    val array = catalog?.optJSONArray("groups") ?: return emptyMap()
    val out = mutableMapOf<String, List<Pair<String, Boolean>>>()
    for (index in 0 until array.length()) {
        val group = array.optJSONObject(index) ?: continue
        val id = group.text("id")
        if (id.isEmpty()) continue
        out[id] = rulesOf(group)
    }
    return out
}