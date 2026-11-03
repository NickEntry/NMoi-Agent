package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区（D234）：列 / 新建 / 改名 / 删除。
 *
 * 这一页只做四件事，因为工作区本身只有四个字段：名字、目录、里面有多少东西、现在绑给谁。
 * **绑定不在这里改**——绑定是助手的一个属性，去助手页改，否则同一件事有两个入口。
 *
 * 三条规矩：
 * 1. 默认工作区（`files/workspace`）不能改名、不能删：老会话还指着它，删了就是"文件全没了"。
 * 2. 删除要确认，并**点名**哪几个助手还绑着它——那些助手会退回默认工作区。
 * 3. 换工作区不搬文件：这句话写在页面上，不然用户会以为切一下文件就跟着走了。
 */
@Composable
internal fun WorkspaceScreen(
    databasePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var assistants by remember { mutableStateOf<List<AssistantEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 输入卡：新建与改名共用一张。`editing` 非空时是改名。
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<Workspace?>(null) }
    var confirmDelete by remember { mutableStateOf<Workspace?>(null) }

    fun reload() {
        scope.launch {
            val (list, all) = withContext(Dispatchers.IO) {
                WorkspaceRepository.list(context) to
                    AssistantRepository.list(databasePath).getOrNull()?.rows.orEmpty()
            }
            rows = list
            assistants = all
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun submit() {
        val name = draft.trim()
        if (name.isEmpty()) {
            notice = "名字不能为空"
            return
        }
        busy = true
        scope.launch {
            val target = editing
            val result = withContext(Dispatchers.IO) {
                if (target == null) {
                    WorkspaceRepository.create(context, name)
                } else {
                    WorkspaceRepository.rename(context, target, name)
                }
            }
            busy = false
            result.fold(
                onSuccess = {
                    creating = false
                    editing = null
                    draft = ""
                    notice = null
                    reload()
                },
                onFailure = { notice = "没成：${it.message}" },
            )
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
                    text = "工作区",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionLabel("全部（${rows.size}）")
                GroupCard {
                    rows.forEachIndexed { index, workspace ->
                        if (index > 0) RowDivider(14.dp)
                        WorkspaceRow(workspace = workspace, onClick = { sheetFor = workspace })
                    }
                }

                Hint(
                    "每个助手指向一个工作区（在助手页里改）。换工作区只影响以后，**文件不搬**。\n" +
                        "容器里它是 `/workspace`；这个会话自己的东西（附件、工具产物）在 `/chat`。",
                )

                if (creating || editing != null) {
                    SectionLabel(if (creating) "新建工作区" else "改名")
                    GroupCard {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                ) {
                                    if (draft.isEmpty()) {
                                        Text(
                                            text = "例如：论文、爬虫、家里那台",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    BasicTextField(
                                        value = draft,
                                        onValueChange = { draft = it.take(40) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Row(modifier = Modifier.padding(top = 12.dp)) {
                                ChipButton(
                                    label = "取消",
                                    primary = false,
                                    enabled = !busy,
                                ) {
                                    creating = false
                                    editing = null
                                    draft = ""
                                    notice = null
                                }
                                Spacer(Modifier.width(8.dp))
                                ChipButton(
                                    label = if (busy) "存着…" else "确定",
                                    primary = true,
                                    enabled = !busy,
                                ) { submit() }
                            }
                        }
                    }
                } else {
                    WideButton(
                        label = "新建工作区",
                        enabled = !busy,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        notice = null
                        draft = ""
                        creating = true
                    }
                }

                val line = notice
                if (line != null) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    // 一行一个动作入口：改名 / 删除。默认那个两项都灰着并写清为什么。
    val sheet = sheetFor
    if (sheet != null) {
        MenuSheet(
            title = sheet.name,
            items = listOf(
                SheetItem(
                    label = "改名",
                    icon = ActionIcons.Pencil,
                    hint = if (sheet.isDefault) "默认工作区不能改名（老会话还指着它）" else null,
                    onClick = if (sheet.isDefault) {
                        null
                    } else {
                        {
                            editing = sheet.id
                            creating = false
                            draft = sheet.name
                            sheetFor = null
                        }
                    },
                ),
                SheetItem(
                    label = "删除",
                    icon = ActionIcons.Close,
                    hint = if (sheet.isDefault) "默认工作区不能删（老会话还指着它）" else null,
                    onClick = if (sheet.isDefault) {
                        null
                    } else {
                        {
                            confirmDelete = sheet
                            sheetFor = null
                        }
                    },
                ),
            ),
            onHint = { text -> notice = text },
            onDismiss = { sheetFor = null },
        )
    }

    val doomed = confirmDelete
    if (doomed != null) {
        val bound = WorkspaceRepository.boundAssistants(assistants, doomed.id)
        ConfirmDialog(
            title = "删除“${doomed.name}”？",
            detail = buildString {
                append("会删掉 ${doomed.dir.absolutePath} 里的全部文件（${doomed.summary}），删了就没了。")
                if (bound.isNotEmpty()) {
                    append("\n\n还绑着它的助手：${bound.joinToString("、")}——删完它们会退回默认工作区。")
                }
            },
            confirmLabel = "删除",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        WorkspaceRepository.delete(context, doomed.id)
                    }
                    busy = false
                    result.fold(
                        onSuccess = { notice = "已删除「${doomed.name}」"; reload() },
                        onFailure = { notice = "没删成：${it.message}" },
                    )
                }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(workspace: Workspace, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (workspace.isDefault) {
                    Text(
                        text = "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                text = workspace.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = workspace.dir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}
