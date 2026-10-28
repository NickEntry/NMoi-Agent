package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手页（D174 / P3.6）：**列表 + 改名 + 删除**。
 *
 * 为什么"换模型"不在这页：模型选择的主路径是聊天页顶部那个模型条——用户在那儿一边看对话
 * 一边换，改的正是"当前助手"那一份配置。在这页再放一套模型选择器，等于把同一件事做两遍，
 * 两处迟早会给出不一样的答案。
 *
 * 这页负责的是**助手这个东西本身**：有几个、叫什么、当前是哪个、怎么删。
 * 三条规矩都写死在界面上，不靠用户记住：
 * - `default` 不许删（v9 迁移把老会话都归在它下面，删了那些会话就没有家）；
 * - 有会话的助手不许直接删：要么先移走，要么明确选"一起移到 default"；
 * - 删掉的正巧是当前那个 → 当前回到 `default`（不然下一次建会话会拿到一个不存在的归属）。
 */
@Composable
internal fun AssistantScreen(
    databasePath: String,
    onBack: () -> Unit,
    /** 助手有变动（切了 / 新建 / 改名 / 删了）：外层要重读抽屉与聊天页。 */
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<AssistantList?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 提示行的颜色分开：真的出错才用 error 色，否则"名字已保存"看着像报警。
    var noticeIsError by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssistantEntry?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) { AssistantRepository.list(databasePath) }.fold(
            onSuccess = { list = it },
            onFailure = { notice = "读助手失败：${it.message}"; noticeIsError = true },
        )
    }

    fun refresh() {
        reload += 1
        onChanged()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "助手",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconActionButton(
                    image = ActionIcons.Plus,
                    description = "新建助手",
                    onClick = {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                // 新建 = 复制当前助手那套配置（模型 / 参数 / 思考档位），会话不复制。
                                val base = AssistantRepository.active(databasePath).getOrNull()
                                    ?: AssistantEntry()
                                val all = AssistantRepository.list(databasePath).getOrNull()
                                val entry = base.copy(
                                    id = AssistantRepository.newId(),
                                    name = "助手 ${(all?.rows?.size ?: 1) + 1}",
                                )
                                AssistantRepository.save(databasePath, entry).map { entry }
                            }
                            created.fold(
                                onSuccess = { entry ->
                                    withContext(Dispatchers.IO) {
                                        AssistantRepository.setActive(databasePath, entry.id)
                                    }
                                    refresh()
                                    editing = entry
                                },
                                onFailure = { notice = "新建助手失败：${it.message}"; noticeIsError = true },
                            )
                        }
                    },
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    text = "每个助手有自己的会话列表和模型配置。模型在聊天页顶部换，改的是当前助手那一份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                val page = list
                when {
                    page == null && notice == null -> Notice("正在读…")
                    else -> {
                        GroupCard {
                            page?.rows.orEmpty().forEachIndexed { index, entry ->
                                if (index > 0) RowDivider()
                                AssistantRow(
                                    entry = entry,
                                    current = entry.id == page?.activeId,
                                    onClick = {
                                        if (entry.id == page?.activeId) {
                                            notice = "已经是当前助手"; noticeIsError = false
                                        } else {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    AssistantRepository.setActive(databasePath, entry.id)
                                                }.onFailure { notice = "切助手失败：${it.message}"; noticeIsError = true }
                                                refresh()
                                            }
                                        }
                                    },
                                    onEdit = { editing = entry },
                                )
                            }
                        }

                        Text(
                            text = "会话在抽屉里长按可以移动到别的助手；移动是改归属，不复制。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                notice?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (noticeIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    // 改名 / 删除：一张卡里两件事，删除那半边写着"为什么现在不能删"。
    editing?.let { entry ->
        AssistantEditSheet(
            entry = entry,
            databasePath = databasePath,
            isCurrent = entry.id == list?.activeId,
            onDismiss = { editing = null },
            onRenamed = {
                editing = null
                notice = "名字已保存"
                noticeIsError = false
                refresh()
            },
            onDeleted = {
                editing = null
                notice = "已删除「${entry.name}」"
                noticeIsError = false
                refresh()
            },
            onError = { editing = null; notice = it; noticeIsError = true },
        )
    }
}

@Composable
private fun AssistantRow(
    entry: AssistantEntry,
    current: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.name.take(1).ifBlank { "助" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (current) append("当前 · ")
                    append(
                        if (entry.isRunnable) {
                            "${entry.providerId} / ${entry.modelId}"
                        } else {
                            "还没选模型（在聊天页顶部换）"
                        },
                    )
                    if (entry.thinking != "auto") append(" · 思考 ${entry.thinking}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconActionButton(
            image = ActionIcons.Pencil,
            description = "编辑助手",
            onClick = onEdit,
            compact = true,
        )
    }
}

/**
 * 改名 + 删除。删除这一半的三种形态都是**先算出来再显示**，不是点下去才报错：
 * - `default`：一行红字说"不能删"，不给按钮；
 * - 有会话：主按钮变成"删除…"，点了要给"还有 N 个会话"的选择；
 * - 没会话：主按钮就是"删除"，一次确认。
 */
@Composable
private fun AssistantEditSheet(
    entry: AssistantEntry,
    databasePath: String,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
    onDeleted: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(entry.id) { mutableStateOf(entry.name) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<Long?>(null) }
    // 子 AGENT（D176）：开关打开之后才露出后面三项。
    var subEnabled by remember(entry.id) { mutableStateOf(entry.subAgent.enabled) }
    var subId by remember(entry.id) { mutableStateOf(entry.subAgent.callId) }
    var subDescription by remember(entry.id) { mutableStateOf(entry.subAgent.description) }
    var subSession by remember(entry.id) { mutableStateOf(entry.subAgent.session) }
    var subProblem by remember(entry.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.id) {
        conversations = withContext(Dispatchers.IO) {
            ConversationRepository.countOf(databasePath, entry.id)
        }
    }

    val isDefault = entry.id == AssistantRepository.DEFAULT_ID
    val known = conversations
    // `countOf` 读不出来时给 -1。**数不清就不能说"没有会话"**，所以那种情况一律按"有"处理：
    // 让它走三选一那条路，而不是点一下就删掉一批看不见指向谁的会话。
    val count = (known ?: 0L).coerceAtLeast(0L)
    val countable = known != null && known >= 0L

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "编辑「${entry.name}」",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 名字：只改显示名。id 是库里所有会话的外键，永远不动。
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetChip(label = "保存", primary = true, enabled = !busy) {
                    busy = true
                    subProblem = null
                    scope.launch {
                        // 先把这份配置写进"候选"，再让 core 查一遍重名：
                        // 不查就存，故障会推迟到"主 AGENT 有时叫错人"那一刻才出现。
                        val next = entry.copy(
                            name = name.trim(),
                            subAgent = SubAgentSpec(
                                enabled = subEnabled,
                                callId = subId.trim(),
                                description = subDescription.trim(),
                                session = subSession,
                            ),
                        )
                        // **先校验，再落库**：不合法的配置不进库。
                        // 反过来（先存再查）会让库里躺着一条"主 AGENT 会叫错人"的配置，
                        // 而用户以为没保存成功。
                        val problems = withContext(Dispatchers.IO) {
                            AssistantRepository.validateSubAgents(databasePath, next)
                        }.getOrElse { error ->
                            busy = false
                            onError("校验失败：${error.message}")
                            return@launch
                        }
                        if (problems.isNotEmpty()) {
                            // 名字改回去了吗？没有——留着让人看见自己填了什么，
                            // 只是把问题写在下面，改完还能再按保存。
                            busy = false
                            subProblem = problems.joinToString("\n")
                            return@launch
                        }
                        val saved = withContext(Dispatchers.IO) {
                            AssistantRepository.save(databasePath, next)
                        }
                        saved.fold(
                            onSuccess = { onRenamed() },
                            onFailure = {
                                busy = false
                                onError("保存失败：${it.message}")
                            },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                SheetChip(label = "取消", enabled = !busy) { onDismiss() }
            }

            // —— 子 AGENT（D176）：开关 → 调用 ID → 描述 → 会话策略 ——
            // 顺序是"先决定要不要，再填细节"：关着的时候后面三项根本不出现，
            // 免得用户以为那几个框是必填的。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "可作为子 AGENT 被调用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (subEnabled) {
                            "主 AGENT 会在它的提示词里看到这个 ID 与描述"
                        } else {
                            "关着：主 AGENT 的名单里没有它，也调不到"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SheetChip(
                    label = if (subEnabled) "开" else "关",
                    primary = subEnabled,
                    enabled = !busy,
                ) { subEnabled = !subEnabled }
            }

            if (subEnabled) {
                Field(
                    label = "调用 ID",
                    value = subId,
                    hint = "小写字母 / 数字 / 下划线，例如 test_agent",
                    onChange = { subId = it.take(32) },
                )
                Field(
                    label = "描述",
                    value = subDescription,
                    hint = "写什么时候该用它，例如：改文案时交给它",
                    onChange = { subDescription = it.take(200) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                    )
                    SheetChip(
                        label = "每次新建",
                        primary = subSession == "new",
                        enabled = !busy,
                    ) { subSession = "new" }
                    Spacer(Modifier.width(6.dp))
                    SheetChip(
                        label = "复用最近",
                        primary = subSession == "reuse",
                        enabled = !busy,
                    ) { subSession = "reuse" }
                }
                Text(
                    text = "复用 = 两次调用互相看得见；怀疑串味就选「每次新建」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            subProblem?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (isDefault) {
                    Text(
                        text = "默认助手不能删：v9 迁移把老会话都归在它下面，删了那些会话就没有家。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!confirmDelete) {
                    Text(
                        text = "删除助手",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable {
                            if (!countable) {
                                onError("数不清这个助手有多少会话，先别删")
                            } else if (count > 0) {
                                confirmDelete = true
                            } else {
                                busy = true
                                scope.launch {
                                    deleteAssistant(entry, databasePath, isCurrent, onDeleted, onError)
                                }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            known == null -> "正在数会话…"
                            !countable -> "数不清会话数，暂时不能删"
                            count == 0L -> "没有会话，可以直接删"
                            else -> "$count 个会话，删之前要选怎么办"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 有会话时的三选一：移走（一起移到 default）或取消。**不给"静默删掉"这个选项**。
            if (confirmDelete) {
                Text(
                    text = "「${entry.name}」底下还有 $count 个会话。删掉助手之后它们去哪？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    SheetChip(label = "一起移到默认助手", primary = true, enabled = !busy) {
                        busy = true
                        scope.launch {
                            val moved = withContext(Dispatchers.IO) {
                                moveAllThenDelete(databasePath, entry.id)
                            }
                            moved.fold(
                                onSuccess = { onDeleted() },
                                onFailure = { busy = false; onError("删除失败：${it.message}") },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    SheetChip(label = "取消", enabled = !busy) { confirmDelete = false }
                }
            }

            if (isCurrent) {
                Text(
                    text = "这是当前助手：删掉之后当前会回到「默认助手」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** 删一个没有会话的助手。当前那个被删 → 当前回到 default（不然下一次建会话拿到空归属）。 */
private suspend fun deleteAssistant(
    entry: AssistantEntry,
    databasePath: String,
    isCurrent: Boolean,
    onDeleted: () -> Unit,
    onError: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    val cleared = if (isCurrent) {
        AssistantRepository.setActive(databasePath, AssistantRepository.DEFAULT_ID)
    } else {
        Result.success(Unit)
    }
    if (cleared.isFailure) {
        onError("切回默认助手失败：${cleared.exceptionOrNull()?.message}")
        return@withContext
    }
    AssistantRepository.delete(databasePath, entry.id).fold(
        onSuccess = { onDeleted() },
        onFailure = { onError("删除失败：${it.message}") },
    )
}

/**
 * 把这个助手底下的会话**全部移到 default**，再删助手。
 *
 * 顺序不能反：先删助手再移会话，中间那一刻库里有一批指向不存在助手的会话——
 * 那正是"界面上哪儿都看不见的数据"（铁律 ②）。所以先移、确认移完了，才删。
 */
private suspend fun moveAllThenDelete(
    databasePath: String,
    assistantId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
    val page = ConversationRepository.load(databasePath, assistantId, limit = 200)
    if (page.source != RowSource.Native) {
        // 占位数据里的会话 id 是假的：照它去移动，等于把助手底下的真会话留在原地就删助手。
        return@withContext Result.failure(
            NmoiFfiException("storage_unavailable", "读不到库（${page.note ?: "未知原因"}），先别删"),
        )
    }
    for (row in page.rows) {
        ConversationRepository.move(databasePath, row.id, AssistantRepository.DEFAULT_ID)
            .onFailure { return@withContext Result.failure(it) }
    }
    if (page.rows.size >= 200) {
        // 一页装不下就先不删：留下助手比留下"看不见的会话"安全。
        return@withContext Result.failure(
            NmoiFfiException("too_many", "这个助手有 200 条以上会话，先手动移走一部分再删"),
        )
    }
    // 会话都移完了，才轮到删助手；删之前把当前指回 default。
    AssistantRepository.setActive(databasePath, AssistantRepository.DEFAULT_ID)
    AssistantRepository.delete(databasePath, assistantId)
}

/** 一行"标签 + 输入框"。标签固定 56dp，三行对齐才不会各说各话。 */
@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 与 `EnvirScreen` 那个 chip 同形：小圆角、按下去才有反应。 */
@Composable
private fun SheetChip(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainer
            primary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                primary -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
