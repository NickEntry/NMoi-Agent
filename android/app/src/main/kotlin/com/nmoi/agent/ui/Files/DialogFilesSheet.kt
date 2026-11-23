package com.nmoi.agent.ui.Files

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.ActionIcons
import com.nmoi.agent.ui.SheetFrame
import java.io.File

/** 三个页签。 */
private enum class DialogTab { Attachments, Outputs, Workspace }

/**
 * 对话内的「对话文件」面板（D393）：**附件 / 输出 / 工作区** 三个页签。
 *
 * 前两个是同一个目录的两种归属（判据在 `DialogFiles.split`，单测钉住），
 * 第三个是这条会话绑的工作区根——它与前两个目录**无关**，所以放在最后。
 *
 * 为什么做成底部浮层而不是整页：它是"看一眼这个会话有哪些文件"，
 * 看完就回去接着聊。整页要占满屏，还得再按一次返回。
 */
@Composable
internal fun DialogFilesSheet(
    sessionId: String?,
    workspaceRoot: File,
    onDismiss: () -> Unit,
    onOpenFile: (root: File, row: FileRow) -> Unit,
) {
    val context = LocalContext.current
    // 默认停在「工作区」（用户 2026-11-24：点这个图标默认是工作区）。
    // **没开工作区时才落到附件**：那时工作区那一页只会是一句"这个工作区还是空的"，
    // 而用户点进来是想看这个会话的文件。
    var tab by remember(sessionId) {
        mutableStateOf(
            if (workspaceRoot.isDirectory) DialogTab.Workspace else DialogTab.Attachments,
        )
    }
    var snapshot by remember(sessionId) { mutableStateOf<DialogFilesSnapshot?>(null) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var order by remember { mutableStateOf(FileOrder()) }
    var showHidden by remember { mutableStateOf(FilePrefs.showHidden(context)) }
    var sortSheet by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(sessionId, reloadKey) {
        val sid = sessionId
        if (sid == null) {
            snapshot = DialogFilesSnapshot(emptyList(), emptyList(), null, "")
            loading = false
            return@LaunchedEffect
        }
        loading = true
        snapshot = DialogFilesRepository.load(context, sid)
        loading = false
    }

    // 浮层高度给半屏（用户 2026-11-18 定的「0.3 秒加载动画」那一套是给整页的，
    // 浮层按屏高的一半算，内容再多就自己滚）。
    val half = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    SheetFrame(title = "对话文件", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = half)) {
            DialogTabs(
                selected = tab,
                counts = mapOf(
                    DialogTab.Attachments to (snapshot?.attachments?.size ?: 0),
                    DialogTab.Outputs to (snapshot?.outputs?.size ?: 0),
                ),
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    tab == DialogTab.Workspace -> FileBrowser(
                        root = workspaceRoot,
                        order = order,
                        showHidden = showHidden,
                        reloadKey = reloadKey,
                        onOpenOrder = { sortSheet = true },
                        onToggleHidden = {
                            showHidden = !showHidden
                            FilePrefs.setShowHidden(context, showHidden)
                        },
                        // 工作区页签里不做新建：这一层是"看一眼"，改文件去工作区页。
                        onCreate = {},
                        onActions = {},
                        onOpen = { row -> onOpenFile(workspaceRoot, row) },
                    )

                    loading -> LoadingPage()

                    else -> {
                        val data = snapshot
                        val error = data?.attachmentsError
                        val rows = when (tab) {
                            DialogTab.Attachments -> data?.attachments.orEmpty()
                            else -> data?.outputs.orEmpty()
                        }
                        // 读不到库的时候「附件」页签要说清是读不到，而不是显示空——
                        // 空列表与"没读到"在界面上长得一样，那正是最难查的一种。
                        if (tab == DialogTab.Attachments && error != null) {
                            UnreadablePage(reason = "读不到附件清单：$error")
                        } else if (rows.isEmpty()) {
                            DialogEmpty(tab)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(rows, key = { it.path }) { file ->
                                    DialogFileRow(file = file, onClick = {
                                        onOpenFile(
                                            com.nmoi.agent.ui.ToolingRepository.chatRoot(
                                                context,
                                                sessionId.orEmpty(),
                                            ),
                                            FileRow(
                                                name = file.name,
                                                path = file.path,
                                                isDir = false,
                                                sizeBytes = file.sizeBytes,
                                                modifiedMs = file.modifiedMs,
                                            ),
                                        )
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sortSheet) {
        SortSheet(
            order = order,
            onPick = { order = order.toggled(it) },
            onDismiss = { sortSheet = false },
        )
    }
}

/** 三格页签条：等宽，带计数（0 时不显示数字）。 */
@Composable
private fun DialogTabs(
    selected: DialogTab,
    counts: Map<DialogTab, Int>,
    onSelect: (DialogTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            for ((tab, label) in listOf(
                DialogTab.Attachments to "附件",
                DialogTab.Outputs to "输出",
                DialogTab.Workspace to "工作区",
            )) {
                val active = tab == selected
                val count = counts[tab]
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.weight(1f).clickable { onSelect(tab) },
                ) {
                    Text(
                        text = if (count == null) {
                            label
                        } else {
                            DialogFiles.badge(count)?.let { "$label $it" } ?: label
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** 一行：图标 + 名字 + 大小 · 时间。整行可点开预览。 */
@Composable
private fun DialogFileRow(file: ChatFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ActionIcons.FileCode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(humanFileSize(file.sizeBytes))
                    if (file.modifiedMs > 0) {
                        append(" · ")
                        append(humanFileTime(file.modifiedMs))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空态：一句说明 + 一句下一步。**不写"暂无数据"**。 */
@Composable
private fun DialogEmpty(tab: DialogTab) {
    val hint = when (tab) {
        DialogTab.Attachments -> DialogFiles.emptyHint(DialogFileSide.Attachment)
        DialogTab.Outputs -> DialogFiles.emptyHint(DialogFileSide.Output)
        DialogTab.Workspace -> "这个工作区还是空的。"
    }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
