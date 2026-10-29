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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手列表页（D174 / D176）。**只管"有哪几个、当前是哪个"**，改东西去 [AssistantEditScreen]。
 *
 * 为什么拆成两页：一个助手能改四块（名字 / 子 AGENT / 删除 / 看它用哪个模型），
 * 塞进列表行的弹窗里既挤又像"临时操作"。列表页只做一件事：点一下就切过去。
 */
@Composable
internal fun AssistantScreen(
    databasePath: String,
    onBack: () -> Unit,
    /** 进某个助手的编辑页。新建之后也直接推进去——刚建的东西应当当场能改。 */
    onEdit: (String) -> Unit,
    /** 助手有变动（切了 / 新建 / 删了）：外层要重读抽屉与聊天页。 */
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<AssistantList?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        // 每次进这一页先保证默认助手在库里：它是"第一次启动就自带的那一个"，
        // 少了它，迁移到 default 的老会话在界面上就没有家（铁律 ②）。
        withContext(Dispatchers.IO) { AssistantRepository.ensureDefault(databasePath) }
        withContext(Dispatchers.IO) { AssistantRepository.list(databasePath) }.fold(
            onSuccess = { list = it },
            onFailure = {
                notice = "读助手失败：${it.message}"
                noticeIsError = true
            },
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
                                    subAgent = SubAgentSpec(),
                                )
                                AssistantRepository.save(databasePath, entry).map { entry }
                            }
                            created.fold(
                                onSuccess = { entry ->
                                    withContext(Dispatchers.IO) {
                                        AssistantRepository.setActive(databasePath, entry.id)
                                    }
                                    refresh()
                                    onEdit(entry.id)
                                },
                                onFailure = {
                                    notice = "新建助手失败：${it.message}"
                                    noticeIsError = true
                                },
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
                    page == null && notice == null -> Text(
                        text = "正在读…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    else -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column {
                                page?.rows.orEmpty().forEachIndexed { index, entry ->
                                    if (index > 0) Divider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (entry.id == page?.activeId) {
                                                    notice = "已经是当前助手"
                                                    noticeIsError = false
                                                } else {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            AssistantRepository.setActive(databasePath, entry.id)
                                                        }.onFailure {
                                                            notice = "切助手失败：${it.message}"
                                                            noticeIsError = true
                                                        }
                                                        refresh()
                                                    }
                                                }
                                            }
                                            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    CircleShape,
                                                ),
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
                                                    if (entry.id == page?.activeId) append("当前 · ")
                                                    append(
                                                        if (entry.isRunnable) {
                                                            "${entry.providerId} / ${entry.modelId}"
                                                        } else {
                                                            "还没选模型"
                                                        },
                                                    )
                                                    if (entry.subAgent.enabled) {
                                                        append(" · 子 ${entry.subAgent.callId}")
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (entry.id == page?.activeId) {
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
                                            onClick = { onEdit(entry.id) },
                                            compact = true,
                                        )
                                    }
                                }
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
