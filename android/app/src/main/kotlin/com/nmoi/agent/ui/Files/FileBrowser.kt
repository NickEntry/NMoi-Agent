package com.nmoi.agent.ui.Files

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.ActionIcons
import com.nmoi.agent.ui.IconActionButton
import java.io.File

/**
 * 一套文件列表（D392）：顶栏三颗按钮 + 行列表 + 进目录。
 *
 * 三处出现（工作区独立页、对话内「对话文件」的工作区页签、以后的终端文件面板），
 * 所以抽成一个 composable：零件同一套，形状由调用方给的外框决定。
 *
 * # 目录栈在组件里
 *
 * 进一个文件夹、按返回退一层——这一层如果由调用方管，两个入口各写一遍就会
 * 出现"从这里进去退不出来"。返回键也在这里注册（栈深 > 1 才接管），
 * 与全应用那条返回链一致：只在这一层消费，不在 Activity 上注册。
 *
 * # 重读由调用方驱动
 *
 * `reloadKey` 变了就重读一次。删掉一个文件之后要重读的是**当前这一层**，
 * 而当前层只有组件自己知道，所以调用方只能递一个计数进来。
 */
@Composable
internal fun FileBrowser(
    root: File,
    order: FileOrder,
    showHidden: Boolean,
    reloadKey: Int,
    onOpenOrder: () -> Unit,
    onToggleHidden: () -> Unit,
    onCreate: (dir: String) -> Unit,
    onActions: (FileRow) -> Unit,
    onOpen: (FileRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var stack by remember(root.absolutePath) { mutableStateOf(listOf<String>()) }
    var rows by remember(root.absolutePath) { mutableStateOf<List<FileRow>>(emptyList()) }
    var loading by remember(root.absolutePath) { mutableStateOf(true) }
    var reason by remember(root.absolutePath) { mutableStateOf<String?>(null) }

    val current = stack.lastOrNull().orEmpty()
    LaunchedEffect(root.absolutePath, current, reloadKey) {
        loading = true
        FileRepository.list(root, current, showHidden = true).fold(
            onSuccess = {
                rows = it
                reason = null
            },
            onFailure = {
                rows = emptyList()
                reason = FileRepository.describe(it)
                FileRepository.warn("列目录失败 $current", it)
            },
        )
        loading = false
    }

    // 返回键退一层目录。只在栈深 > 1 时接管——在根目录按返回应当退出这一页，
    // 而不是"什么都不发生"。
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) {
        stack = stack.dropLast(1)
    }

    val shown = remember(rows, order, showHidden) {
        FileListing.sorted(rows.filter { FileListing.visible(it, showHidden) }, order)
    }

    // 页边距加在这一层：两个入口（工具菜单 / 设置）用的是同一个 `FileBrowser`，
    // 加一次两边都对。行本身不再各自缩进——那样会缩两遍。
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        FileToolbar(
            title = if (current.isEmpty()) "工作区" else current,
            summary = FileListing.summary(shown),
            canGoUp = stack.isNotEmpty(),
            order = order,
            showHidden = showHidden,
            onUp = { stack = stack.dropLast(1) },
            onOpenOrder = onOpenOrder,
            onToggleHidden = onToggleHidden,
            onCreate = { onCreate(current) },
        )

        when {
            loading && rows.isEmpty() -> LoadingPage()
            reason != null -> UnreadablePage(reason = reason ?: "读不出来")
            shown.isEmpty() -> EmptyFiles()
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { row ->
                    FileRowItem(
                        row = row,
                        onClick = {
                            if (row.isDir) {
                                stack = stack + row.path
                            } else {
                                onOpen(row)
                            }
                        },
                        onActions = { onActions(row) },
                    )
                }
            }
        }
    }
}

/** 顶栏：路径 + 统计 + 三颗按钮（排序 / 隐藏 / 新建）。返回上级在左边。 */
@Composable
private fun FileToolbar(
    title: String,
    summary: String,
    canGoUp: Boolean,
    order: FileOrder,
    showHidden: Boolean,
    onUp: () -> Unit,
    onOpenOrder: () -> Unit,
    onToggleHidden: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoUp) {
            IconActionButton(
                image = ActionIcons.ChevronRight,
                description = "上一级",
                onClick = onUp,
                compact = true,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (canGoUp) 2.dp else 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$summary · 按${order.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconActionButton(
            image = ActionIcons.ArrowUp,
            description = "排序：${order.label}",
            onClick = onOpenOrder,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.Image,
            description = if (showHidden) "不显示隐藏文件" else "显示隐藏文件",
            onClick = onToggleHidden,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.Plus,
            description = "新建文件 / 文件夹",
            onClick = onCreate,
            compact = true,
        )
    }
}

/** 一行：图标 + 名字 + 副行 + 行尾 `⋯`。整行可点（目录进、文件开预览）。 */
@Composable
private fun FileRowItem(row: FileRow, onClick: () -> Unit, onActions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 2.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (row.isDir) ActionIcons.Folder else ActionIcons.FileCode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = FileListing.subtitle(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconActionButton(
            image = ActionIcons.Ellipsis,
            description = "更多",
            onClick = onActions,
            compact = true,
        )
    }
}

/** 空目录那一屏：一句说明 + 一个动作。**不写"暂无数据"**。 */
@Composable
private fun EmptyFiles() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "这个目录是空的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "用右上角那颗 ＋ 新建",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 排序选择卡（`⇅` 点开）。
 *
 * 三项全摆出来：排序键只有三个，收进下拉等于让用户多点一次才看得见选项。
 * 点当前那一项 = 翻转方向（与 `FileOrder.toggled` 同一个判据）。
 */
@Composable
internal fun SortSheet(
    order: FileOrder,
    onPick: (FileSort) -> Unit,
    onDismiss: () -> Unit,
) {
    com.nmoi.agent.ui.SheetFrame(title = "排序", onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            for ((sort, label) in listOf(
                FileSort.Name to "名称",
                FileSort.Modified to "修改时间",
                FileSort.Size to "大小",
            )) {
                val active = sort == order.sort
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onPick(sort)
                        }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        Text(
                            text = if (order.desc) "↓" else "↑",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 新建时先选类型：文件 / 文件夹。两件事，所以两步，不合成一张表单。 */
@Composable
internal fun CreateSheet(onPick: (Boolean) -> Unit, onDismiss: () -> Unit) {
    com.nmoi.agent.ui.MenuSheet(
        title = "新建",
        items = listOf(
            com.nmoi.agent.ui.SheetItem(
                label = "文件夹",
                icon = ActionIcons.Folder,
                onClick = {
                    onDismiss()
                    onPick(true)
                },
            ),
            com.nmoi.agent.ui.SheetItem(
                label = "文件",
                icon = ActionIcons.FileCode,
                onClick = {
                    onDismiss()
                    onPick(false)
                },
            ),
        ),
        onHint = {},
        onDismiss = onDismiss,
    )
}

/** 拼一个子路径：父目录为空时就是名字本身。 */
internal fun childPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** 一句话说清"删掉会没掉什么"（确认卡正文用）。 */
internal fun deleteWarning(row: FileRow): String = if (row.isDir) {
    "会删掉「${row.name}」这个文件夹和它里面的全部东西，删了就没了。"
} else {
    "会删掉「${row.name}」（${humanFileSize(row.sizeBytes)}），删了就没了。"
}
