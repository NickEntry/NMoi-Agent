@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.Files.FileRepository
import com.nmoi.agent.ui.Files.humanFileTime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储子页的**树形**那一种（D434）：沙箱环境 / 技能 / 工作区文件 / 会话文件。
 *
 * 四个根，进去之后都是正常目录结构——用户原话是「点进去看应该是 tree 那样的」。
 *
 * # 为什么不再用平铺列表
 *
 * 平铺能回答"这一项一共多大"，回答不了"里面是什么、能不能删"。而这两件事在
 * `rootfs` 与技能上恰好是**最不能猜**的：前者删一个文件容器就起不来，后者少一个
 * `SKILL.md` 整个技能就废了。所以这两层不给删，判据在 [StorageTree.canDelete]。
 *
 * # 每一层现读盘
 *
 * 删完文件返回上一层，数字要当场变。打开时抓一份快照再显示，用户看到的是"删了还在"
 * （`reload` 变了就重读，不缓存大小）。
 *
 * # 会话文件里**不出现** `images/` 与 `archive/`
 *
 * 前者归「图片」页，后者是这条会话的上下文原文（`/archive/<hex>.txt`），不属于
 * "用户会去翻的文件"。这一条写在界面上（进去之后有一行说明），否则下一轮会有人
 * 顺手把它补全——补全之后「图片」页与这里会出现同一份文件的两个入口。
 */
@Composable
internal fun StorageTreeScreen(
    kind: StorageKind,
    databasePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 从根到当前这一层的路径。空 = 根。 */
    var trail by remember(kind) { mutableStateOf<List<TreeRow>>(emptyList()) }
    var rows by remember(kind) { mutableStateOf<List<TreeRow>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var notice by remember(kind) { mutableStateOf<String?>(null) }
    var busy by remember(kind) { mutableStateOf<String?>(null) }
    var menuFor by remember(kind) { mutableStateOf<TreeRow?>(null) }
    var confirmDelete by remember(kind) { mutableStateOf<TreeRow?>(null) }
    var reload by remember(kind) { mutableStateOf(0) }

    LaunchedEffect(kind, trail, reload) {
        loading = true
        val depth = trail.size + 1
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    trail.isEmpty() -> roots(context, kind, databasePath)
                    // 会话里面只有两块：`chat/` 与 `tmp/`。再往下才是真目录。
                    kind == StorageKind.SessionFiles && trail.size == 1 ->
                        StorageTree.children(trail[0].file, kind, depth)
                            .filter { it.name == "chat" || it.name == "tmp" }
                    else -> StorageTree.children(trail.last().file, kind, depth)
                }
            }
        }
        loaded.fold(
            onSuccess = { rows = it },
            onFailure = {
                notice = "读不出来：${it.message}"
                rows = emptyList()
            },
        )
        loading = false
        PageReady.notifyReadyAfterFrame()
    }

    val total = rows.sumOf { it.bytes }
    val title = trail.lastOrNull()?.name ?: kind.title

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(
                    onBack = {
                        if (trail.isEmpty()) onBack() else trail = trail.dropLast(1)
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            loading -> "统计中…"
                            rows.isEmpty() -> "空"
                            else -> "${rows.size} 项 · ${formatBytes(total)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 面包屑只在进去之后出现：根那一层没有"上一层"可指。
                if (trail.isNotEmpty()) {
                    Text(
                        text = trail.joinToString(" › ") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            busy?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            // 会话里少列了哪两样，要说出来——"少了东西却不说"比多列两行更坏。
            if (kind == StorageKind.SessionFiles && trail.size == 1) {
                Text(
                    text = "这里只列 /chat 与 /tmp。图片在「图片」页；归档原文是这条会话的上下文，" +
                        "不在这里（它照样计进「会话文件」的总大小）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            if (!loading && rows.isEmpty()) {
                EmptyKind(kind)
            } else {
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
                    items(rows, key = { it.file.absolutePath }) { row ->
                        TreeRowView(
                            row = row,
                            onClick = {
                                if (row.isDir) {
                                    trail = trail + row
                                } else {
                                    notice = "「${row.name}」· ${formatBytes(row.bytes)} · " +
                                        humanFileTime(row.modifiedMs)
                                }
                            },
                            onLongClick = { menuFor = row },
                        )
                    }
                    item(key = "tail") { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

    // ---- 长按菜单：只做「删除」这一件事 ----
    menuFor?.let { target ->
        SheetFrame(title = target.name, onDismiss = { menuFor = null }) {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                Text(
                    text = buildString {
                        append(if (target.isDir) "${target.files} 个文件 · " else "")
                        append(formatBytes(target.bytes))
                        if (target.modifiedMs > 0) {
                            append(" · ")
                            append(humanFileTime(target.modifiedMs))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                TreeSheetAction(
                    label = "删除",
                    danger = true,
                    enabled = target.deletable,
                ) {
                    menuFor = null
                    confirmDelete = target
                }
                if (!target.deletable) {
                    Text(
                        text = whyNotDeletable(kind, target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    val doomed = confirmDelete
    if (doomed != null) {
        ConfirmDialog(
            title = "删掉「${doomed.name}」？",
            detail = buildString {
                if (doomed.isDir) append("整个目录（${doomed.files} 个文件）会从盘上删掉，收不回来。\n")
                else append("${formatBytes(doomed.bytes)} 会从盘上删掉，收不回来。\n")
                append("这一页不做导出：要打包留档请先复制出去。")
            },
            confirmLabel = "删除",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    busy = "正在删除…"
                    val result = withContext(Dispatchers.IO) { delete(kind, trail, doomed) }
                    busy = null
                    notice = result.fold(
                        onSuccess = { "已删掉「${doomed.name}」" },
                        onFailure = { "删除失败：${it.message}" },
                    )
                    reload += 1
                }
            },
        )
    }
}

/**
 * 这一行为什么不给删。**写清去哪删**，不写"不允许"。
 *
 * 一句"这一项不能删"会让用户以为功能坏了；写清"删整个技能请在这一层删"，
 * 他就知道下一步该点哪儿。
 */
private fun whyNotDeletable(kind: StorageKind, row: TreeRow): String = when (kind) {
    StorageKind.Envir ->
        "沙箱环境只读：rootfs 里删掉一个文件，容器就起不来了。要重置请去「工具与工作区 → 环境」。"
    StorageKind.Skills ->
        "技能里面不给删单个文件：少一个 SKILL.md 整个技能就废了，而这种坏法在界面上看不出来。" +
            "要删就返回上一层删整个技能。"
    StorageKind.Workspace ->
        "整个工作区不给在这里删：那是「工具与工作区 → 工作区」的事。"
    StorageKind.SessionFiles ->
        "会话本身不给在这里删：去侧边栏长按会话。"
    else -> "这一项不给删除。"
}

/** 删一行。工作区走工作区那套越界检查，其余直接删盘。返回删掉的条目数。 */
private suspend fun delete(
    kind: StorageKind,
    trail: List<TreeRow>,
    row: TreeRow,
): Result<Int> = runCatching {
    when (kind) {
        StorageKind.Workspace -> {
            val root = trail.firstOrNull()?.file ?: error("没有工作区根目录")
            val path = row.file.absolutePath
                .removePrefix(root.absolutePath)
                .trimStart('/')
            FileRepository.delete(root, path).getOrThrow()
        }
        StorageKind.Skills -> {
            deleteTreeNoFollow(row.file)
            1
        }
        else -> if (row.file.delete()) 1 else error("删不掉：${row.file.name}")
    }
}

/** 根那一层：四种页面各自的根不一样，形状一样。 */
private suspend fun roots(
    context: android.content.Context,
    kind: StorageKind,
    databasePath: String,
): List<TreeRow> = when (kind) {
    StorageKind.SessionFiles -> sessionRoots(context, databasePath)
    StorageKind.Skills -> StorageTree.children(File(context.filesDir, "skills"), kind, 1)
    StorageKind.Workspace -> workspaceRoots(context)
    StorageKind.Envir -> envirRoots(context)
    else -> emptyList()
}

/**
 * 会话一级：`标题 · 短id`，**新的在前**（与侧边栏一致）。
 *
 * 大小只算 `chat/` 与 `tmp/`——也就是这一页点进去真能看见的那些。把 `images/` 与
 * `archive/` 也算进来，就会出现"这一行 300 MB、点进去只有 2 MB"，而那正是判据 1 要挡的。
 */
private suspend fun sessionRoots(
    context: android.content.Context,
    databasePath: String,
): List<TreeRow> {
    val titles = StorageRepository.sessionTitles(databasePath)
    val root = File(context.filesDir, "sessions")
    return root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .map { session ->
            val chat = StorageTree.measure(File(session, "chat"))
            val tmp = StorageTree.measure(File(session, "tmp"))
            TreeRow(
                name = StorageTree.sessionLabel(titles[session.name], session.name),
                file = session,
                isDir = true,
                bytes = chat.bytes + tmp.bytes,
                files = chat.files + tmp.files,
                modifiedMs = runCatching { session.lastModified() }.getOrDefault(0L),
                deletable = false,
            )
        }
        .sortedByDescending { it.modifiedMs }
}

/** 工作区一级：名字取自 `.nmoi-workspace.json`，默认那个标「默认」。 */
private fun workspaceRoots(context: android.content.Context): List<TreeRow> =
    WorkspaceRepository.list(context).map { workspace ->
        TreeRow(
            name = workspace.name,
            file = workspace.dir,
            isDir = true,
            bytes = workspace.bytes,
            files = workspace.files,
            modifiedMs = runCatching { workspace.dir.lastModified() }.getOrDefault(0L),
            badge = if (workspace.isDefault) "默认" else null,
            deletable = false,
        )
    }

/**
 * 沙箱环境一级：只有 `rootfs/` 与 `tmp/` 两行。
 *
 * 不是把 `files/envir/` 摊平——那里面还有 `state.json`、`resolv.conf`、`bin/`、`lib/`，
 * 那些是"运行时的零件"，不是用户要看的。没装的时候两行都不在，走空态。
 */
private fun envirRoots(context: android.content.Context): List<TreeRow> {
    val rootfs = EnvirRepository.rootfsDir(context)
    val tmp = File(EnvirRepository.envirDir(context), "tmp")
    return buildList {
        if (rootfs.isDirectory) {
            add(
                StorageTree.row(rootfs, StorageKind.Envir, 1).copy(
                    name = "rootfs",
                    badge = "容器的根目录",
                ),
            )
        }
        if (tmp.isDirectory) add(StorageTree.row(tmp, StorageKind.Envir, 1).copy(name = "tmp"))
    }
}

/** 树里的一行：目录带箭头（点进去），文件带大小与时间。 */
@Composable
private fun TreeRowView(
    row: TreeRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (row.isDir) ActionIcons.Folder else ActionIcons.FileCode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    row.badge?.let { badge ->
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = buildString {
                        if (row.isDir) {
                            if (row.files == 0) append("空目录") else append("${row.files} 个文件")
                            append(" · ")
                            append(formatBytes(row.bytes))
                        } else {
                            append(formatBytes(row.bytes))
                            if (row.modifiedMs > 0) {
                                append(" · ")
                                append(humanFileTime(row.modifiedMs))
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.isDir) {
                Icon(
                    imageVector = ActionIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 底部浮层里的一行动作。不可用时灰着（说明由调用方写在下面一行）。 */
@Composable
private fun TreeSheetAction(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (enabled) onClick() },
                onLongClick = {},
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
