package com.nmoi.agent.ui.Files

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.BackButton
import com.nmoi.agent.ui.ConfirmDialog
import com.nmoi.agent.ui.NLog
import kotlinx.coroutines.launch
import java.io.File

/** 工作区独立页的两个页签。 */
private enum class WorkspaceTab { Files, Tools }

/**
 * 工作区独立页（D392）：**文件 / 工具** 两个页签。
 *
 * - **文件**：与对话内那个「工作区」页签同一套组件（`FileBrowser`），只是没有外层三页签。
 * - **工具**：这个工作区的对话能用哪些工具，一行一个开关，改完即时保存。
 *
 * 工具开关落 `settings(workspace, <id>)` 的一份文档（D86 的形状），字段 `tools: {off: [...]}`。
 * **与助手/会话那两级取交集**：任何一级关掉就不声明。它是"再收一道"，不是"再放一道"。
 */
@Composable
internal fun WorkspaceFilesScreen(
    root: File,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(WorkspaceTab.Files) }
    var order by remember { mutableStateOf(FileOrder()) }
    // 显示隐藏文件：只存本机、全局共用、不进备份（用户 2026-11-22 明确）。
    var showHidden by remember { mutableStateOf(FilePrefs.showHidden(context)) }
    var sortSheet by remember { mutableStateOf(false) }
    var createSheet by remember { mutableStateOf(false) }
    var createDir by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf<NameTarget?>(null) }
    var actionsFor by remember { mutableStateOf<FileRow?>(null) }
    var confirmDelete by remember { mutableStateOf<FileRow?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<FileRow?>(null) }

    // 预览页压在页面栈上：这里用一层局部状态，返回一次就回到列表。
    val open = preview
    if (open != null) {
        FilePreviewScreen(root = root, row = open, onBack = { preview = null })
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = root.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TabStrip(
                left = "文件",
                right = "工具",
                rightSelected = tab == WorkspaceTab.Tools,
                onPick = { tab = if (it) WorkspaceTab.Tools else WorkspaceTab.Files },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val note = notice
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when (tab) {
                WorkspaceTab.Files -> FileBrowser(
                    root = root,
                    order = order,
                    showHidden = showHidden,
                    reloadKey = reloadKey,
                    onOpenOrder = { sortSheet = true },
                    onToggleHidden = {
                        showHidden = !showHidden
                        FilePrefs.setShowHidden(context, showHidden)
                    },
                    onCreate = { dir ->
                        createDir = dir
                        createSheet = true
                    },
                    onActions = { actionsFor = it },
                    onOpen = { preview = it },
                )

                WorkspaceTab.Tools -> WorkspaceToolsTab(
                    workspaceId = title,
                    notice = { notice = it },
                )
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

    if (createSheet) {
        CreateSheet(
            onPick = { isDir -> naming = NameTarget(dir = createDir, isDir = isDir) },
            onDismiss = { createSheet = false },
        )
    }

    val target = naming
    if (target != null) {
        NameInputSheet(
            title = if (target.isDir) "新建文件夹" else "新建文件",
            placeholder = if (target.isDir) "例如：素材" else "例如：notes.md",
            initial = "",
            onCancel = { naming = null },
            onSubmit = { name ->
                naming = null
                val path = childPath(target.dir, name)
                scope.launch {
                    val result = if (target.isDir) {
                        FileRepository.makeDir(root, path)
                    } else {
                        FileRepository.write(root, path, "")
                    }
                    result.fold(
                        onSuccess = {
                            notice = if (target.isDir) "已新建文件夹「$name」" else "已新建文件「$name」"
                            reloadKey += 1
                        },
                        onFailure = { notice = "没建成：${FileRepository.describe(it)}" },
                    )
                }
            },
        )
    }

    val sheet = actionsFor
    if (sheet != null) {
        FileActionsSheet(
            row = sheet,
            onDismiss = { actionsFor = null },
            onRename = {
                actionsFor = null
                naming = NameTarget(dir = sheet.path.substringBeforeLast('/', ""), isDir = sheet.isDir, renaming = sheet)
            },
            onCopyPath = {
                actionsFor = null
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("路径", sheet.path))
                notice = "已复制路径：${sheet.path}"
            },
            onDelete = {
                actionsFor = null
                confirmDelete = sheet
            },
        )
    }

    // 改名走另一张卡：它的初值是原名。
    val renaming = naming
    if (renaming?.renaming != null) {
        val row = renaming.renaming
        NameInputSheet(
            title = "改名",
            placeholder = row.name,
            initial = row.name,
            onCancel = { naming = null },
            onSubmit = { name ->
                naming = null
                val to = childPath(renaming.dir, name)
                scope.launch {
                    FileRepository.rename(root, row.path, to).fold(
                        onSuccess = {
                            notice = "已改名为「$name」"
                            reloadKey += 1
                        },
                        onFailure = { notice = "没改成：${FileRepository.describe(it)}" },
                    )
                }
            },
        )
    }

    val doomed = confirmDelete
    if (doomed != null) {
        ConfirmDialog(
            title = "删除「${doomed.name}」？",
            detail = deleteWarning(doomed),
            confirmLabel = "删除",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    FileRepository.delete(root, doomed.path).fold(
                        onSuccess = { removed ->
                            // 删完要说剩多少：只说"已删除"，用户不知道有没有删干净。
                            notice = "已删除「${doomed.name}」（$removed 项）"
                            reloadKey += 1
                        },
                        onFailure = { notice = "没删成：${FileRepository.describe(it)}" },
                    )
                }
            },
        )
    }
}

/** 一次新建 / 改名的目标。`renaming` 非空时是改名。 */
private data class NameTarget(
    val dir: String,
    val isDir: Boolean,
    val renaming: FileRow? = null,
)

/**
 * 工作区页的「工具」页签（D392）。
 *
 * 一行一个开关，改完即时保存。**与助手/会话那两级取交集**：任何一级关掉就不声明。
 * 这一页只做"再收一道"，不做"再放一道"——放开的权力在上面那两级，不在这里。
 *
 * 第一版只摆出「这个工作区能关掉哪几样」，落库那一步（`settings(workspace,<id>)`）
 * 要等 policy 那一层把工作区这一级读进去——**没接的东西不摆假开关**，
 * 所以这一屏现在写清"还没接"并且不给可拨的控件。
 */
@Composable
private fun WorkspaceToolsTab(workspaceId: String, notice: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "这个工作区能用哪些工具",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "这一级是**再收一道**：与助手、会话那两级取交集，任何一级关掉就不声明。" +
                        "现在还差 policy 读工作区这一级（下一批），所以这里先不摆开关——" +
                        "摆了也拨不动的那种，比没有更糟。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { notice("逐项开关要等 policy 那一层（下一批）；现在去 设置 → 工具与工作区 改全局那几颗") },
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全局那几颗开关",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "设置 → 工具与工作区 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "工作区：$workspaceId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 两格页签条（文件 / 工具）。与预览页那一套同一个形状，只是标签不同。 */
@Composable
private fun TabStrip(
    left: String,
    right: String,
    rightSelected: Boolean,
    onPick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            for ((label, isRight) in listOf(left to false, right to true)) {
                val active = isRight == rightSelected
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.weight(1f).clickable { onPick(isRight) },
                ) {
                    Text(
                        text = label,
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

/**
 * 「这台设备上怎么摆」的偏好（D392）：显示隐藏文件、排序方式。
 *
 * **只存本机、全局共用、不进备份**（用户 2026-11-22 明确）。理由写在
 * `notes/design/workspace-and-terminal.md` §4：它们是"这台设备上怎么摆"，
 * 跟着库搬到另一台设备没有意义。
 */
internal object FilePrefs {
    private const val PREFS = "nmoi.local"
    private const val KEY_SHOW_HIDDEN = "files.show_hidden"
    private const val KEY_SORT = "files.sort"
    private const val KEY_DESC = "files.sort_desc"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun showHidden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_HIDDEN, false)

    fun setShowHidden(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        NLog.i("Files", "显示隐藏文件：${if (value) "开" else "关"}")
    }

    fun order(context: Context): FileOrder {
        val stored = prefs(context).getString(KEY_SORT, null)
        val sort = FileSort.entries.firstOrNull { it.name == stored } ?: FileSort.Name
        return FileOrder(sort, prefs(context).getBoolean(KEY_DESC, false))
    }

    fun setOrder(context: Context, order: FileOrder) {
        prefs(context)
            .edit()
            .putString(KEY_SORT, order.sort.name)
            .putBoolean(KEY_DESC, order.desc)
            .apply()
    }
}
