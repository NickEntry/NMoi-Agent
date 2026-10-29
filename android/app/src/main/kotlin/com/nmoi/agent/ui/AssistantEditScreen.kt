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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个助手的**编辑页**（D174 / D176）。列表页点铅笔推到这里，是一整页而不是弹窗。
 *
 * 为什么不是弹窗：这个助手能改的东西有四块（名字 / 模型说明 / 子 AGENT / 删除），
 * 而且子 AGENT 打开之后还要填三项。塞进一张浮在列表上的小卡片，屏幕一大半是空的，
 * 手指还得去够；更糟的是**弹窗那种东西天生像"临时操作"**，而这里改的是长期配置。
 *
 * 页面里只有一处不归这里管：**换模型**。主路径是聊天页顶部那个模型条——
 * 改的正是"当前助手"那一份。两个地方各放一套模型选择器，迟早给出不一样的答案。
 */
@Composable
internal fun AssistantEditScreen(
    databasePath: String,
    assistantId: String,
    onBack: () -> Unit,
    /** 存好了 / 删掉了：外层要重读抽屉与列表。 */
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entry by remember(assistantId) { mutableStateOf<AssistantEntry?>(null) }
    var isCurrent by remember(assistantId) { mutableStateOf(false) }
    var conversations by remember(assistantId) { mutableStateOf<Long?>(null) }

    var name by remember(assistantId) { mutableStateOf("") }
    var subEnabled by remember(assistantId) { mutableStateOf(false) }
    var subId by remember(assistantId) { mutableStateOf("") }
    var subDescription by remember(assistantId) { mutableStateOf("") }
    var subSession by remember(assistantId) { mutableStateOf("new") }

    var notice by remember(assistantId) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(assistantId) { mutableStateOf(false) }
    var busy by remember(assistantId) { mutableStateOf(false) }
    var confirmDelete by remember(assistantId) { mutableStateOf(false) }

    LaunchedEffect(assistantId) {
        val (loaded, page, count) = withContext(Dispatchers.IO) {
            Triple(
                AssistantRepository.load(databasePath, assistantId).getOrNull(),
                AssistantRepository.list(databasePath).getOrNull(),
                ConversationRepository.countOf(databasePath, assistantId),
            )
        }
        if (loaded == null) {
            notice = "读不到这个助手（${assistantId}）"
            noticeIsError = true
            return@LaunchedEffect
        }
        entry = loaded
        isCurrent = page?.activeId == assistantId
        conversations = count
        // 首次进来才把表单灌上：之后用户改到一半，重进不该把他的话冲掉。
        name = loaded.name
        subEnabled = loaded.subAgent.enabled
        subId = loaded.subAgent.callId
        subDescription = loaded.subAgent.description
        subSession = loaded.subAgent.session
    }

    fun save(then: (AssistantEntry) -> Unit) {
        val base = entry ?: return
        val next = base.copy(
            name = name.trim().ifEmpty { base.name },
            subAgent = SubAgentSpec(
                enabled = subEnabled,
                callId = subId.trim(),
                description = subDescription.trim(),
                session = subSession,
            ),
        )
        busy = true
        notice = null
        scope.launch {
            // **先校验、再落库**：不合法的配置不进库。
            val problems = withContext(Dispatchers.IO) {
                AssistantRepository.validateSubAgents(databasePath, next)
            }.getOrElse { error ->
                busy = false
                notice = "校验失败：${error.message}"
                noticeIsError = true
                return@launch
            }
            if (problems.isNotEmpty()) {
                busy = false
                notice = problems.joinToString("\n")
                noticeIsError = true
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                AssistantRepository.save(databasePath, next)
            }
            busy = false
            saved.fold(
                onSuccess = {
                    entry = next
                    onChanged()
                    then(next)
                },
                onFailure = {
                    notice = "保存失败：${it.message}"
                    noticeIsError = true
                },
            )
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
                    text = "助手",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Chip(label = "保存", primary = true, enabled = !busy && entry != null) {
                    save { onBack() }
                }
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = name.take(1).ifBlank { "助" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                text = if (isCurrent) "当前助手" else "不是当前助手",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = entry?.let {
                                    if (it.isRunnable) {
                                        "${it.providerId} / ${it.modelId}"
                                    } else {
                                        "还没选模型（在聊天页顶部换）"
                                    }
                                } ?: "正在读…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    Label("名字")
                    Input(
                        value = name,
                        hint = "显示名，随时可改",
                        onChange = { name = it.take(24) },
                    )
                    Text(
                        text = "名字只是显示用的；库里所有会话认的是 id（${assistantId}），改名字不会碰到它们。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // —— 子 AGENT（D176）——
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "可作为子 AGENT 被调用",
                                style = MaterialTheme.typography.bodyLarge,
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
                        Chip(
                            label = if (subEnabled) "开" else "关",
                            primary = subEnabled,
                            enabled = !busy,
                        ) { subEnabled = !subEnabled }
                    }

                    if (subEnabled) {
                        Label("调用 ID")
                        Input(
                            value = subId,
                            hint = "小写字母 / 数字 / 下划线，例如 test_agent",
                            onChange = { subId = it.take(32) },
                        )
                        Label("描述")
                        Input(
                            value = subDescription,
                            hint = "写什么时候该用它，例如：改文案时交给它",
                            onChange = { subDescription = it.take(200) },
                        )
                        Label("会话")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Chip(
                                label = "每次新建",
                                primary = subSession == "new",
                                enabled = !busy,
                            ) { subSession = "new" }
                            Spacer(Modifier.width(8.dp))
                            Chip(
                                label = "复用最近",
                                primary = subSession == "reuse",
                                enabled = !busy,
                            ) { subSession = "reuse" }
                        }
                        Text(
                            text = "复用 = 两次调用互相看得见；怀疑串味就选「每次新建」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                // —— 删除 ——
                Card {
                    val count = conversations
                    val countable = count != null && count >= 0
                    if (assistantId == AssistantRepository.DEFAULT_ID) {
                        Text(
                            text = "默认助手不能删：库里那些第一次启动时就归在它下面的会话，删了就没有家。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!confirmDelete) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "删除这个助手",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(enabled = !busy) {
                                    when {
                                        !countable -> {
                                            notice = "数不清这个助手有多少会话，先别删"
                                            noticeIsError = true
                                        }
                                        count > 0 -> confirmDelete = true
                                        else -> {
                                            busy = true
                                            scope.launch {
                                                val cleared = withContext(Dispatchers.IO) {
                                                    if (isCurrent) {
                                                        AssistantRepository.setActive(
                                                            databasePath,
                                                            AssistantRepository.DEFAULT_ID,
                                                        )
                                                    } else {
                                                        Result.success(Unit)
                                                    }
                                                }
                                                if (cleared.isFailure) {
                                                    busy = false
                                                    notice = "切回默认助手失败：${cleared.exceptionOrNull()?.message}"
                                                    noticeIsError = true
                                                    return@launch
                                                }
                                                withContext(Dispatchers.IO) {
                                                    AssistantRepository.delete(databasePath, assistantId)
                                                }.fold(
                                                    onSuccess = {
                                                        busy = false
                                                        onChanged()
                                                        onBack()
                                                    },
                                                    onFailure = {
                                                        busy = false
                                                        notice = "删除失败：${it.message}"
                                                        noticeIsError = true
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = when {
                                    count == null -> "正在数会话…"
                                    !countable -> "数不清，暂时不能删"
                                    count == 0L -> "没有会话，可以直接删"
                                    else -> "$count 个会话，删之前要选怎么办"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isCurrent) {
                            Text(
                                text = "这是当前助手：删掉之后当前会回到「默认助手」。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "「${name}」底下还有 $count 个会话。删掉助手之后它们去哪？",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "**不会**连会话一起删：要么先挪走，要么在这里选「一起移到默认助手」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            Chip(label = "一起移到默认助手", primary = true, enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val moved = withContext(Dispatchers.IO) {
                                        moveAllThenDelete(databasePath, assistantId)
                                    }
                                    busy = false
                                    moved.fold(
                                        onSuccess = {
                                            onChanged()
                                            onBack()
                                        },
                                        onFailure = {
                                            notice = "删除失败：${it.message}"
                                            noticeIsError = true
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Chip(label = "取消", enabled = !busy) { confirmDelete = false }
                        }
                    }
                }

                notice?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (noticeIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
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
        // 占位数据里的会话 id 是假的：照它去移动，等于把真会话留在原地就删助手。
        return@withContext Result.failure(
            NmoiFfiException("storage_unavailable", "读不到库（${page.note ?: "未知原因"}），先别删"),
        )
    }
    if (page.rows.size >= 200) {
        // 一页装不下就先不删：留下助手比留下"看不见的会话"安全。
        return@withContext Result.failure(
            NmoiFfiException("too_many", "这个助手有 200 条以上会话，先手动移走一部分再删"),
        )
    }
    for (row in page.rows) {
        ConversationRepository.move(databasePath, row.id, AssistantRepository.DEFAULT_ID)
            .onFailure { return@withContext Result.failure(it) }
    }
    AssistantRepository.setActive(databasePath, AssistantRepository.DEFAULT_ID)
    AssistantRepository.delete(databasePath, assistantId)
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
    }
}

/** 卡片里的小节标签：比正文小一档、用次要色，视觉上是一句"下面是这一类"。 */
@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 一行输入框。空值时把提示写在框里（当占位符用），有值就不占地方。 */
@Composable
private fun Input(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
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

@Composable
private fun Chip(
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
