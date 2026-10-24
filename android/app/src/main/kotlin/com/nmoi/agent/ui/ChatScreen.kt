package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天页——**这一页就是主页**（D125）。
 *
 * 消息区的形状照 D129（2026-10-24 用户按截图重新定的）：
 *
 * - **用户**：右侧。上面一行是「名字 + 时间 + 头像」，下面是气泡（`primaryContainer`、圆角 16），
 *   气泡下面一排动作（复制 / 重新生成 / 编辑 / 更多）。
 * - **助手**：左侧。**没有气泡**——头像 + 名字 + 时间，正文直接铺在背景上，
 *   走 [MarkdownBody] 渲染。思考块与工具卡在这里仍然是卡片（它们是"过程"，不是"回答"）。
 * - **长按 = 进入选择模式**（不是复制）：整行泛出选中底色，顶部换成选择条，
 *   可以逐条点选，然后复制。删除还灰着（`delete` 的 FFI 还没有）。
 *
 * 名字与头像现在都是占位：库里还没有"助手"这个实体（助手设置页未接），
 * 所以写死「我」/「助手」+ 首字母圆标。等助手设置接上，从 D122 的助手文档里取名字与头像。
 */
@Composable
internal fun ChatScreen(
    title: String,
    sessionId: String?,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onCreateSession: suspend () -> String?,
    onNewChat: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 存完一条要重读：`produceState` 只有 key 变了才会重跑，所以用它当"刷新一下"。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 选择模式：长按任意一条进入，点别的消息加选，顶部条上复制 / 分享。
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // 分享底部两排：上排两个开关（思考调用 / 详细信息），下排两个动作（MD / 图片）。
    var shareOpen by remember { mutableStateOf(false) }
    // 「更多」是一个**自己画的底部面板**（不是 DropdownMenu）：要带图标、要盖住整屏。
    var moreFor by remember { mutableStateOf<ChatMessage?>(null) }
    var includeThinkingTools by remember { mutableStateOf(false) }
    var includeDetails by remember { mutableStateOf(false) }
    val databasePath = remember { File(context.filesDir, "nmoi.db").absolutePath }
    // 原生 EditText 的引用：抽屉打开时要主动清焦点 + 收输入法。
    // Compose 的 clearFocus 对 AndroidView 里的原生控件不一定生效，所以这里拿真控件。
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            editTextRef.value?.let { field ->
                field.clearFocus()
                val imm = context.getSystemService(
                    android.view.inputmethod.InputMethodManager::class.java,
                )
                imm?.hideSoftInputFromWindow(field.windowToken, 0)
            }
        }
    }

    // 换会话就把选择清掉：跨会话的选中集合没有任何意义，还容易误复制。
    LaunchedEffect(sessionId) {
        selecting = false
        selected = emptySet()
    }

    // 空白会话（还没落库）不发读请求：没有 id 可读，界面直接给"还没有消息"的静态提示。
    val snapshot by produceState<ChatSnapshot?>(initialValue = null, sessionId, reloadKey) {
        value = if (sessionId == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ChatRepository.load(File(context.filesDir, "nmoi.db").absolutePath, sessionId)
            }
        }
    }

    val allMessages = snapshot?.page?.units?.flatMap { it.messages }.orEmpty()

    val selectedMessages = allMessages.filter { it.id in selected }.sortedBy { it.seq }

    val copySelected: () -> Unit = {
        if (selectedMessages.isEmpty()) {
            notice = "先选一条消息"
        } else {
            putClipboard(context, "选中的消息", selectedMessages.joinToString("\n\n") { it.content })
            notice = "已复制 ${selectedMessages.size} 条消息"
            selecting = false
            selected = emptySet()
        }
    }

    // 切版本 / 切原文-编辑（D137）：成功后重读，界面只看库里的状态，不做本地猜测。
    val switchVersion: (String, Long, String) -> Unit = { id, versionNo, view ->
        scope.launch {
            ChatRepository.switchVersion(databasePath, id, versionNo, view).fold(
                onSuccess = { reloadKey += 1 },
                onFailure = { notice = "切版本失败：${it.message}" },
            )
        }
    }

    // 「更多 → 复制为新版本」：**临时探针**。
    // `turn_run` 还没接，重新生成产不出版本，而版本 UI 没有数据就没法验。
    // 这条把当前正文再存一版，专门用来验 D137 那一套；接上 turn_run 就删掉它。
    val saveVersionProbe: (ChatMessage) -> Unit = { message ->
        scope.launch {
            ChatRepository.saveVersion(databasePath, message.id, message.content).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = "已复制为新版本（临时探针）"
                },
                onFailure = { notice = "存版本失败：${it.message}" },
            )
        }
    }

    val deleteVersion: (String, Long) -> Unit = { id, versionNo ->
        scope.launch {
            ChatRepository.deleteVersion(databasePath, id, versionNo).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = if (versionNo == 0L) "已删除其余版本" else "已删除这一版"
                },
                onFailure = { notice = "删版本失败：${it.message}" },
            )
        }
    }

    // 分享的正文在这里成形：Markdown 而不是纯文本——分享出去多半是要粘进文档或笔记。
    val shareAsMarkdown: () -> Unit = {
        if (selectedMessages.isEmpty()) {
            notice = "先选一条消息"
        } else {
            val body = buildShareMarkdown(
                picked = selectedMessages,
                units = snapshot?.page?.units.orEmpty(),
                includeThinkingTools = includeThinkingTools,
                includeDetails = includeDetails,
                stampOf = { millis -> stampOf(context, millis) ?: "—" },
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(Intent.createChooser(send, "分享对话"))
            shareOpen = false
            selecting = false
            selected = emptySet()
            notice = "已打开分享（Markdown，含 ${selectedMessages.size} 条消息）"
        }
    }

    // 选择模式也要吃返回键：先退选择，再谈退出页面（和抽屉同一套"后注册者优先"）。
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }

    // 存一条：`role` 是 core 的词（`user` / `assistant`）。
    // 会话不存在就先建——建失败要说原因，不能静默丢掉用户刚打的字。
    val submit: (String) -> Unit = { role ->
        val text = draft.trim()
        when {
            text.isEmpty() -> notice = "先写点什么"
            busy -> notice = "上一条还在存"
            else -> scope.launch {
                busy = true
                val sid = sessionId ?: onCreateSession()
                if (sid == null) {
                    notice = "建会话失败：原因在 设置 → 调试 → 日志"
                } else {
                    ChatRepository.append(databasePath, sid, role, text).fold(
                        onSuccess = {
                            draft = ""
                            reloadKey += 1
                            notice = if (role == "user") "已存为用户消息" else "已存为助手消息"
                        },
                        onFailure = { notice = "存失败：${it.message}" },
                    )
                }
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionTopBar(
                    count = selected.size,
                    onCancel = {
                        selecting = false
                        selected = emptySet()
                    },
                    onCopy = copySelected,
                    onShare = { shareOpen = true },
                )
                // 选择模式下输入区是藏起来的，所以"已复制 N 条"这类提示要放在这儿。
                // 注意不能写 `if (notice != null) Text(notice)`：notice 是委托属性，
                // Kotlin 不给它做智能转换（编译期就会报 Smart cast is impossible）。
                val selectingNotice = notice
                if (selectingNotice != null) {
                    Text(
                        text = selectingNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    )
                }
            } else {
                ChatTopBar(
                    title = title,
                    onOpenDrawer = onOpenDrawer,
                    onNewChat = onNewChat,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (sessionId == null) {
                    BlankConversationHint()
                } else {
                    MessageArea(
                        snapshot = snapshot,
                        selecting = selecting,
                        selected = selected,
                        onLongPressMessage = { id ->
                            selecting = true
                            selected = setOf(id)
                        },
                        onToggleMessage = { id ->
                            val next = if (id in selected) selected - id else selected + id
                            selected = next
                            if (next.isEmpty()) selecting = false
                        },
                        onCopied = { notice = "已复制这条消息" },
                        onMoreMessage = { id -> moreFor = allMessages.firstOrNull { it.id == id } },
                        onVersionMessage = switchVersion,
                        // 「更多 → 分享」= 选中这一条并直接打开分享面板：
                        // 分享本来就是"选几条再导"，让它从一条开始，少两步。
                        onShareMessage = { id ->
                            selecting = true
                            selected = setOf(id)
                            shareOpen = true
                        },
                    )
                }
            }

            if (!selecting) {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    notice = notice,
                    canSend = !busy,
                    busy = busy,
                    unsaved = sessionId == null,
                    onHint = { notice = it },
                    onEditTextReady = { editTextRef.value = it },
                    onSend = { submit("user") },
                    onSaveAssistant = { submit("assistant") },
                    onSaveUser = { submit("user") },
                )
            } else {
                // 选择模式下藏起输入区，但留 10dp 底边，免得最后一条消息贴住屏幕边缘。
                Spacer(Modifier.heightIn(min = 10.dp))
            }
        }

        if (shareOpen) {
            ShareOptionsSheet(
                count = selectedMessages.size,
                includeThinkingTools = includeThinkingTools,
                includeDetails = includeDetails,
                onToggleThinkingTools = { includeThinkingTools = !includeThinkingTools },
                onToggleDetails = { includeDetails = !includeDetails },
                onMarkdown = shareAsMarkdown,
                onImage = {},
                onDismiss = { shareOpen = false },
            )
        }

        val sheetMessage = moreFor
        if (sheetMessage != null) {
            MenuSheet(
                title = if (sheetMessage.isUser) "这条用户消息" else "这条助手消息",
                items = messageSheetItems(
                    message = sheetMessage,
                    onShare = {
                        selecting = true
                        selected = setOf(sheetMessage.id)
                        shareOpen = true
                    },
                    onSelectOnly = {
                        selecting = true
                        selected = setOf(sheetMessage.id)
                    },
                    onSaveVersion = { saveVersionProbe(sheetMessage) },
                    onDeleteThis = { deleteVersion(sheetMessage.id, sheetMessage.versionActive.toLong()) },
                    onDeleteAll = { deleteVersion(sheetMessage.id, 0L) },
                ),
                onHint = { notice = it },
                onDismiss = { moreFor = null },
            )
        }
    }
}

/**
 * 分享选项：**上排两个开关、下排两个动作**（用户 2026-10-24 定的形状）。
 *
 * - 上排：思考调用（把这条消息所属单元里的思考 / 工具往返一并带上）、详细信息（带上 id / seq / token 这类元数据）。
 * - 下排：MD（真做，走系统分享）、图片（**灰着**：把消息渲染成图片那一层还没做，
 *   现在给"能按但没反应"的按钮就是骗人）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareOptionsSheet(
    count: Int,
    includeThinkingTools: Boolean,
    includeDetails: Boolean,
    onToggleThinkingTools: () -> Unit,
    onToggleDetails: () -> Unit,
    onMarkdown: () -> Unit,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                text = if (count == 0) "分享" else "分享 $count 条消息",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "先选要带上的内容，再决定导成什么。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 上排：两个开关。选中的按钮用 tonal 底色 + 勾，别只靠颜色差别。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetToggle(
                    label = "思考调用",
                    checked = includeThinkingTools,
                    onClick = onToggleThinkingTools,
                    modifier = Modifier.weight(1f),
                )
                SheetToggle(
                    label = "详细信息",
                    checked = includeDetails,
                    onClick = onToggleDetails,
                    modifier = Modifier.weight(1f),
                )
            }

            // 下排：两个动作。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onMarkdown,
                    enabled = count > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("MD") }
                FilledTonalButton(
                    onClick = onImage,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) { Text("图片（待接）") }
            }
        }
    }
}

/** 分享面板里的开关：勾选态用图标 + 底色两个信号，色盲也分得出来。 */
@Composable
private fun SheetToggle(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (checked) ActionIcons.Check else ActionIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 分享正文：Markdown。
 *
 * `includeThinkingTools` 时，把**同一条消息所属单元**里的思考与工具往返也带上——
 * 用户勾这个开关的意思是"我要连过程一起分享"，不是"只分享我选的那几条正文"。
 * `includeDetails` 时每条后面附一行元数据（id / seq / source / tokens / 是否未完成）。
 */
private fun buildShareMarkdown(
    picked: List<ChatMessage>,
    units: List<ChatUnit>,
    includeThinkingTools: Boolean,
    includeDetails: Boolean,
    stampOf: (Long) -> String,
): String {
    val byId = picked.associateBy { it.id }
    val unitOf = mutableMapOf<String, ChatUnit>()
    units.forEach { unit -> unit.messages.forEach { if (it.id in byId) unitOf[it.id] = unit } }

    return buildString {
        picked.forEach { message ->
            append("### ")
            append(if (message.isUser) USER_NAME else ASSISTANT_NAME)
            append(" · ")
            append(stampOf(message.createdAt))
            append("\n\n")
            append(message.content.ifBlank { "（空消息）" })
            append("\n")

            if (includeThinkingTools) {
                val unit = unitOf[message.id]
                val extra = unit?.messages.orEmpty().filter {
                    it.id != message.id && (it.isThinking || it.isToolCall || it.isToolResult)
                }
                if (extra.isNotEmpty()) {
                    append("\n<details><summary>思考与工具调用（")
                    append(extra.size)
                    append(" 段）</summary>\n\n")
                    extra.forEach { part ->
                        append("- **")
                        append(
                            when {
                                part.isThinking -> "思考"
                                part.isToolCall -> "工具调用"
                                else -> "工具结果"
                            },
                        )
                        append("**：")
                        append(part.content.replace("\n", "\n  "))
                        append("\n")
                    }
                    append("\n</details>\n")
                }
            }

            if (includeDetails) {
                append("\n> id=")
                append(message.id)
                append(" · seq=")
                append(message.seq)
                append(" · source=")
                append(message.source)
                append(" · tokens=")
                append(message.tokens)
                if (message.incomplete) append(" · 未完成")
                append("\n")
            }
            append("\n---\n\n")
        }
    }
}

/** 选择模式的顶栏：取消 + 已选条数 + 复制。删除灰着（`delete` 的 FFI 还没有）。 */
@Composable
private fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconActionButton(
            image = ActionIcons.Close,
            description = "取消选择",
            onClick = onCancel,
        )
        Text(
            text = if (count == 0) "选择消息" else "已选 $count 条",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
        )
        IconActionButton(
            image = ActionIcons.Copy,
            description = "复制选中的消息",
            onClick = onCopy,
            enabled = count > 0,
        )
        IconActionButton(
            image = ActionIcons.Share2,
            description = "分享选中的消息",
            onClick = onShare,
            enabled = count > 0,
        )
    }
}

/**
 * 顶栏：抽屉 + 标题 + 新建。
 *
 * 标题只给一行、可省略：会话名长了之后换行会把顶栏撑高，滚动时看着像页面在跳。
 * 右上角是"新建"而不是"更多"——这是每次都要用的动作，藏进菜单等于每次多点一层。
 */
@Composable
private fun ChatTopBar(title: String, onOpenDrawer: () -> Unit, onNewChat: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(onClick = onOpenDrawer)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconActionButton(
            image = ActionIcons.Plus,
            description = "新建会话",
            onClick = onNewChat,
        )
    }
}

/** 还没落库的空会话：说明"接下来会发生什么"，不摆一个假的欢迎卡。 */
@Composable
private fun BlankConversationHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有消息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "写第一句存下去，会话就在那一刻建立。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

/**
 * 消息区。
 *
 * 状态必须说清是哪一种，不能都是"一片空"：
 * ① 还没读出来（`snapshot == null` 且已落库）；② 读不出来（`page == null`，附原因）；
 * ③ 读到了但一条没有；④ 有内容。
 */
@Composable
private fun MessageArea(
    snapshot: ChatSnapshot?,
    selecting: Boolean,
    selected: Set<String>,
    onLongPressMessage: (String) -> Unit,
    onToggleMessage: (String) -> Unit,
    onCopied: () -> Unit,
    onShareMessage: (String) -> Unit,
    onMoreMessage: (String) -> Unit,
    onVersionMessage: (String, Long, String) -> Unit,
) {
    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "正在读…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val page = snapshot.page
    if (page == null || page.isEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (page == null) "这一段历史还没被读出来" else "这个会话还空着",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val note = snapshot.note
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
                    )
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val headerCount = if (page.truncated) 1 else 0
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    // 打开会话应当看到最新的一条，而不是最早的一条。
    LaunchedEffect(page.units.size, page.truncated) {
        val target = headerCount + page.units.lastIndex
        if (target >= 0) listState.scrollToItem(target)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 左右边距一致 18dp（2026-10-24 第二轮：8dp 太贴边，按 2.2 倍放大）。
        // 助手正文**整行铺开**、左边缘和头像左边缘同一条线（D142）。
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        // 同角色 4dp、异角色 12dp（D126）：4 是基准间距，换人时再加 8。
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (page.truncated) {
            item {
                Text(
                    text = "上面还有更早的消息：共 ${page.total} 个单元，这里只读了最后 ${page.units.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
        itemsIndexed(page.units) { index, unit ->
            val previous = page.units.getOrNull(index - 1)
            val gap = if (previous != null && previous.alignsRight() != unit.alignsRight()) 8.dp else 0.dp
            UnitBlock(
                unit = unit,
                maxBubble = maxBubble,
                gapAbove = gap,
                selecting = selecting,
                selected = selected,
                onLongPressMessage = onLongPressMessage,
                onToggleMessage = onToggleMessage,
                onCopied = onCopied,
                onShareMessage = onShareMessage,
                onMoreMessage = onMoreMessage,
                onVersionMessage = onVersionMessage,
            )
        }
    }
}

/** 这个单元整体靠哪边——只用来算"换人"的间距，不参与别的判断。 */
private fun ChatUnit.alignsRight(): Boolean =
    !isToolPair && messages.isNotEmpty() && messages.all { it.isUser }

/** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 */
@Composable
private fun UnitBlock(
    unit: ChatUnit,
    maxBubble: Dp,
    gapAbove: Dp,
    selecting: Boolean,
    selected: Set<String>,
    onLongPressMessage: (String) -> Unit,
    onToggleMessage: (String) -> Unit,
    onCopied: () -> Unit,
    onShareMessage: (String) -> Unit,
    onMoreMessage: (String) -> Unit,
    onVersionMessage: (String, Long, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = gapAbove),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val broken = unit.broken
        if (broken != null) {
            BrokenCard(declaration = broken.declaration, reason = broken.reason)
        }
        if (unit.isToolPair) {
            ToolPairCard(unit)
        } else {
            unit.messages.forEach { message ->
                when {
                    message.isThinking -> ThinkingBlock(message)
                    message.isUser -> UserMessage(
                        message = message,
                        maxBubble = maxBubble,
                        selecting = selecting,
                        isSelected = message.id in selected,
                        onLongPress = { onLongPressMessage(message.id) },
                        onTap = { if (selecting) onToggleMessage(message.id) },
                        onCopied = onCopied,
                        onShare = { onShareMessage(message.id) },
                        onMore = { onMoreMessage(message.id) },
                        onVersion = { no, view -> onVersionMessage(message.id, no, view) },
                    )

                    else -> AssistantMessage(
                        message = message,
                        selecting = selecting,
                        isSelected = message.id in selected,
                        onLongPress = { onLongPressMessage(message.id) },
                        onTap = { if (selecting) onToggleMessage(message.id) },
                        onCopied = onCopied,
                        onShare = { onShareMessage(message.id) },
                        onMore = { onMoreMessage(message.id) },
                        onVersion = { no, view -> onVersionMessage(message.id, no, view) },
                    )
                }
            }
        }
    }
}

private const val USER_NAME = "我"
private const val ASSISTANT_NAME = "助手"

/**
 * 时间戳。
 *
 * 两个坑都在这一行里：
 * - **库里存的是毫秒**（`SystemTime::as_millis`，见 `crates/storage`）。上一版按秒解，
 *   界面上出现 `+58782-03-11`——年份 5 万多，是"毫秒当秒用"的典型症状。
 * - **12/24 小时制要跟系统走**：用户在系统设置里选的，不该被我们写死成 24 小时。
 */
private var cachedStampPattern: String? = null
private var cachedStampFormat: DateTimeFormatter? = null

private fun stampFormatFor(context: Context): DateTimeFormatter {
    val pattern = if (DateFormat.is24HourFormat(context)) {
        "yyyy-MM-dd HH:mm:ss"
    } else {
        "yyyy-MM-dd hh:mm:ss a"
    }
    cachedStampFormat?.let { if (cachedStampPattern == pattern) return it }
    // 缓存一份：每条消息都新建 Formatter 在长会话里是纯浪费。
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .also {
            cachedStampPattern = pattern
            cachedStampFormat = it
        }
}

/** 0 表示库里没给，宁可不显示也不写一个假时间。 */
private fun stampOf(context: Context, epochMillis: Long): String? =
    if (epochMillis <= 0L) null else stampFormatFor(context).format(Instant.ofEpochMilli(epochMillis))

private fun putClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 首字母圆标。
 *
 * 先不引图片：`Assistant` 还没有实体（助手设置页未接），拿不到名字与头像字节。
 * 用首字母圆标是"诚实的占位"——它明确地不是一张脸，也不会假装自己是谁。
 */
@Composable
private fun InitialAvatar(letter: String, size: Dp, mine: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (mine) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelLarge,
                color = if (mine) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** 用户那一条：右对齐的头像/名字/时间 + 气泡 + 动作行。 */
@Composable
private fun UserMessage(
    message: ChatMessage,
    maxBubble: Dp,
    selecting: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onCopied: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onVersion: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val stamp = stampOf(context, message.createdAt)
    val tint = if (selecting && isSelected) {
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(4.dp)
    } else {
        Modifier
    }

    Column(modifier = Modifier.fillMaxWidth().then(tint)) {
        // 用户：**名字在上排、时间在下排**，两行都在头像左侧（D139）。
        // 文字块右对齐、头像贴右边，跟助手那边成镜像。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = USER_NAME,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (stamp != null) {
                    Text(
                        text = stamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            InitialAvatar(letter = USER_NAME, size = 32.dp, mine = true)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .pointerInput(selecting, message.id) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() },
                        )
                    },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        text = message.content.ifEmpty { "（空消息）" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (message.incomplete) {
                        Text(
                            text = "未完成 · 这一轮被中断",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        VersionControl(message = message, onPick = onVersion)
        MessageActionRow(
            mine = true,
            onCopy = {
                putClipboard(context, "消息", message.content)
                onCopied()
            },
            onShare = onShare,
            onSelect = onLongPress,
            onMore = onMore,
        )
    }
}

/**
 * 助手那一条：**没有气泡**。
 *
 * 头像在左上，名字、时间各自一行，正文用 [MarkdownBody] 铺开——气泡会把长回答框成一个
 * "大卡片"，读起来像在念一段引文；真实聊天客户端里助手本来也是贴着背景说话的。
 */
@Composable
private fun AssistantMessage(
    message: ChatMessage,
    selecting: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onCopied: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onVersion: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val stamp = stampOf(context, message.createdAt)
    val tint = if (selecting && isSelected) {
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(4.dp)
    } else {
        Modifier
    }

    Column(modifier = Modifier.fillMaxWidth().then(tint)) {
        // 头像 + 名字/时间占一行；**正文在它们下面整行铺开**，
        // 左边缘和头像左边缘同一条线（用户 2026-10-24："消息要在头像下方，这样美观"）。
        Row(verticalAlignment = Alignment.Top) {
            InitialAvatar(letter = "助", size = 32.dp, mine = false)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = ASSISTANT_NAME,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (stamp != null) {
                    Text(
                        text = stamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        MarkdownBody(
            text = message.content.ifEmpty { "（空消息）" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .pointerInput(selecting, message.id) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                },
        )
        if (message.incomplete) {
            Text(
                text = "未完成 · 这一轮被中断",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        VersionControl(message = message, onPick = onVersion)
        MessageActionRow(
            mine = false,
            onCopy = {
                putClipboard(context, "消息", message.content)
                onCopied()
            },
            onShare = onShare,
            onSelect = onLongPress,
            onMore = onMore,
        )
    }
}

/**
 * 版本切换（D135 / D137）：`<  n/n  >`。
 *
 * 那个数字的颜色就是三态语义：
 * - 白（`onSurface`）：这一版**没被工具编辑过**，点它没反应；
 * - 蓝（`primary`）：被编辑过，现在显示**原文**，点它切到编辑后；
 * - 红（`error`）：被编辑过，现在显示**编辑后**，点它切回原文。
 *
 * 左右箭头翻版本，传 `keep`：**沿用那一版自己记住的视图**。用当前这一版的视图去套邻居，
 * 会出现"翻过去突然变成编辑态"的怪事。
 *
 * `versionCount <= 1` 时整个控件不渲染——只有一个版本的消息不该看到翻页按钮。
 */
@Composable
private fun VersionControl(message: ChatMessage, onPick: (Long, String) -> Unit) {
    if (message.versionCount <= 1) return
    val active = message.versionActive.coerceAtLeast(1)
    val numberColor = when {
        !message.versionEdited -> MaterialTheme.colorScheme.onSurface
        message.versionView == "edited" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionArrow(
            description = "上一版",
            rotate = 90f,
            enabled = active > 1,
            onClick = { onPick((active - 1).toLong(), "keep") },
        )
        Text(
            text = "$active/${message.versionCount}",
            style = MaterialTheme.typography.labelMedium,
            color = numberColor,
            modifier = Modifier
                .pointerInput(message.id, message.versionView, message.versionEdited) {
                    detectTapGestures(
                        onTap = {
                            if (message.versionEdited) {
                                onPick(
                                    active.toLong(),
                                    if (message.versionView == "edited") "raw" else "edited",
                                )
                            }
                        },
                    )
                }
                .padding(horizontal = 8.dp),
        )
        VersionArrow(
            description = "下一版",
            rotate = -90f,
            enabled = active < message.versionCount,
            onClick = { onPick((active + 1).toLong(), "keep") },
        )
    }
}

@Composable
private fun VersionArrow(description: String, rotate: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .pointerInput(enabled) {
                detectTapGestures { if (enabled) onClick() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ActionIcons.ChevronDown,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.3f,
            ),
            modifier = Modifier.size(16.dp).rotate(rotate),
        )
    }
}

/**
 * 消息下面那排动作（照用户给的截图）：复制 / 重新生成 / 编辑 / 更多。
 *
 * **重新生成与编辑现在是灰的**：`turn_run` 没接（没有可重发的一轮），`edit` 没桥出 FFI。
 * 灰着而不是藏起来，是因为这两个动作的位置会一直在；藏起来用户会以为这里没有编辑。
 * 长按灰按钮会浮一句"待接什么"，不让人猜。
 */
@Composable
private fun MessageActionRow(
    mine: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一排四个动作（用户 2026-10-24 定的最终形状）：复制 / 刷新 / 文字转语音 / 翻译。
        // 后三个现在是灰的：turn_run、TTS、翻译都还没接。灰着而不是藏起来——
        // 位置就是最终位置，接上之后只改 enabled，不改布局。
        IconActionButton(
            image = ActionIcons.Copy,
            description = "复制",
            onClick = onCopy,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.RotateCcw,
            description = "刷新（重新生成，待接 turn_run）",
            onClick = {},
            compact = true,
            enabled = false,
        )
        IconActionButton(
            image = ActionIcons.Volume2,
            description = "文字转语音（待接 TTS）",
            onClick = {},
            compact = true,
            enabled = false,
        )
        IconActionButton(
            image = ActionIcons.Languages,
            description = "翻译（待接翻译）",
            onClick = {},
            compact = true,
            enabled = false,
        )
        IconActionButton(
            image = ActionIcons.Ellipsis,
            description = "更多",
            onClick = onMore,
            compact = true,
        )
    }
}

/**
 * 思考块：**默认收起**，只显示"思考 · N 字"。展开看全文。
 *
 * 收起是刻意的：思考通常很长，默认铺开会把真正的回答挤到屏幕外。
 * 左边留出头像那一段缩进，让它看起来属于助手那一条，而不是一条独立卡片。
 */
@Composable
private fun ThinkingBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ActionIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (expanded) {
                            "思考（点一下收起）"
                        } else {
                            "思考 · ${message.content.length} 字（点一下展开）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 工具调用单元：一张折叠卡。标题给"调用 N 次 / 结果 M 条"，
 * 展开后按消息列出（调用参数与结果原文，等宽字体）。
 *
 * 为什么不逐条做成气泡：工具往返是**一条逻辑**——把参数和结果拆到两条气泡里，
 * 读的人要在两屏之间来回对 id。
 */
@Composable
private fun ToolPairCard(unit: ChatUnit) {
    var expanded by remember(unit.id) { mutableStateOf(false) }
    val calls = unit.messages.count { it.isToolCall || it.toolCalls > 0 }
    val results = unit.messages.count { it.isToolResult }

    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ActionIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = buildString {
                            append("工具调用")
                            if (calls > 0) append(" × $calls")
                            if (results > 0) append(" · 结果 $results 条")
                            if (!unit.closed) append(" · 未收全")
                            append(if (expanded) "（点一下收起）" else "（点一下展开）")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (expanded) {
                    unit.messages.forEach { message ->
                        Text(
                            text = buildString {
                                append(if (message.isToolResult) "← 结果" else "→ 调用")
                                val id = message.toolCallId
                                if (id != null) append(" [$id]")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = message.content.ifEmpty { "（空）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 断块（P4.1 / D67）：声明行由 core 给，UI 不自己拼。 */
@Composable
private fun BrokenCard(declaration: String, reason: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = declaration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 输入岛。
 *
 * 形状（圆角 24 的整条输入区、圆形的发送键）是聊天软件的通行做法：
 * 手指落在屏幕底部中间，不看也能点准。左边**没有**假附件按钮——附件管线没接，
 * 摆一个点了没反应的图标比不摆更糟。
 *
 * 长按发送键 = 存为助手消息：这条留着是因为还没有模型可发，
 * 只有"能往库里写两侧"才验得了气泡形状。等 `nmoi_turn_run_json` 接上，这个手势就删掉。
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    notice: String?,
    canSend: Boolean,
    busy: Boolean,
    unsaved: Boolean,
    onHint: (String) -> Unit,
    onEditTextReady: (EditText) -> Unit,
    onSend: () -> Unit,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
) {
    Column {
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
        }

        // 形状照 PLAN §3.12（也是 kelivo 那一套）：**输入框 + 底部一行图标按钮**。
        // 左：模型 / 搜索 / 思考 / 工具；右：＋菜单 / 语音 / 发送（生成中变停止，同一按钮换形态）。
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                NativeEditText(
                    value = draft,
                    onValueChange = onDraftChange,
                    hint = "说点什么…",
                    modifier = Modifier.fillMaxWidth(),
                    onReady = onEditTextReady,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 六个都还没接：点一下把"差什么、等哪个阶段"写进上面那行提示。
                    // 不给假开关的意思是"点了要有回应"，不是"不许摆"。
                    IconActionButton(
                        image = ActionIcons.Cpu,
                        description = "模型",
                        onClick = { onHint("模型选择待接：要先有供应商与密钥页（P3）") },
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.SearchInput,
                        description = "网络搜索",
                        onClick = { onHint("网络搜索待接：工具层（P7）") },
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Gauge,
                        description = "思考强度",
                        onClick = { onHint("思考强度待接：滑块 + 模型能力位（P3.4，D37）") },
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Wrench,
                        description = "工具开关",
                        onClick = { onHint("工具开关待接：工具层（P7，两级门）") },
                        compact = true,
                    )
                    Spacer(Modifier.weight(1f))
                    IconActionButton(
                        image = ActionIcons.Plus,
                        description = "＋ 菜单",
                        onClick = { onHint("「＋」面板待接：附件管线（P7.7）+ 技能 / 世界书 / 上下文管理") },
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Mic,
                        description = "语音识别",
                        onClick = { onHint("语音识别待接：ASR（未排期）") },
                        compact = true,
                    )
                    SendStopButton(
                        enabled = canSend && draft.isNotBlank(),
                        busy = busy,
                        onSend = onSend,
                        onSaveAssistant = onSaveAssistant,
                        onSaveUser = onSaveUser,
                    )
                }
            }
        }

        Text(
            text = if (unsaved) {
                "这是还没落库的新会话：第一条消息存下去时才建会话。"
            } else {
                "发送还没接：点 ↑ 只是把话存进库里，不请求模型；长按 ↑ 可以选「仅保存为助手/用户消息」。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
        )
    }
}

/**
 * 发送 / 停止：**同一个按钮换形态**（PLAN §3.12）。
 *
 * - 空闲：↑，草稿非空才亮；
 * - 生成中：方块。现在没有可生成的东西，`busy` 只覆盖"正在写库"那一瞬间，
 *   所以停止态是**灰的**——`turn_run` 接上之后它才真有东西可停。
 * - **长按出菜单**（用户 2026-10-21 定的）：仅保存为助手消息 / 仅保存为用户消息。
 *   这两项走 `append_text`，现在就能用；语义是"先记下来、晚点再发"，不是发给模型。
 */
@Composable
private fun SendStopButton(
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = enabled && !busy
    val background = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    }

    Box {
        Box(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(active, busy) {
                    detectTapGestures(
                        onTap = { if (active) onSend() },
                        onLongPress = { if (!busy) menuOpen = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = background, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (busy) ActionIcons.Square else ActionIcons.ArrowUp,
                        contentDescription = if (busy) "停止（待接 turn_run）" else "发送",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("仅保存为助手消息") },
                onClick = {
                    menuOpen = false
                    onSaveAssistant()
                },
            )
            DropdownMenuItem(
                text = { Text("仅保存为用户消息") },
                onClick = {
                    menuOpen = false
                    onSaveUser()
                },
            )
        }
    }
}

/**
 * 「更多」面板的内容。
 *
 * 顺序照用户 2026-10-24 给的：编辑 / 增强编辑 / 分享 / 选择消息 / 创建分支 / 删除此版本 /
 * 删除所有版本（**只有真的还有其他版本时才出现**）。没接的项不隐藏、也不做成一按就死的行，
 * 而是点了给一句"待接什么"——这是这个项目里"不给假开关"的一贯做法。
 */
private fun messageSheetItems(
    message: ChatMessage,
    onShare: () -> Unit,
    onSelectOnly: () -> Unit,
    onSaveVersion: () -> Unit,
    onDeleteThis: () -> Unit,
    onDeleteAll: () -> Unit,
): List<SheetItem> {
    val items = mutableListOf(
        SheetItem(
            label = "编辑",
            icon = ActionIcons.Pencil,
            hint = "编辑待接：core::edit::apply_edit 还没桥出 FFI（与 D135 那一层一起做）",
        ),
        SheetItem(
            label = "增强编辑",
            icon = ActionIcons.Pencil,
            hint = "增强编辑待接：同编辑，另外要先定「用模型改写这一段」的提示词与预算",
        ),
        SheetItem(
            label = "分享",
            icon = ActionIcons.Share2,
            onClick = onShare,
        ),
        SheetItem(
            label = "选择消息",
            icon = ActionIcons.Check,
            onClick = onSelectOnly,
        ),
        // 临时探针：`turn_run` 未接，重新生成产不出版本；
        // 没有它，版本 UI 在真机上永远没有数据可看。接上 turn_run 就删掉这一项。
        SheetItem(
            label = "复制为新版本（临时探针）",
            icon = ActionIcons.Copy,
            onClick = onSaveVersion,
        ),
        SheetItem(
            label = "创建分支",
            icon = ActionIcons.GitBranch,
            hint = "创建分支待接：要先把「从某条消息开始的新会话」建模",
        ),
    )
    if (message.versionCount > 1) {
        items += SheetItem(
            label = "删除此版本",
            icon = ActionIcons.Trash2,
            onClick = onDeleteThis,
        )
        items += SheetItem(
            label = "删除所有版本",
            icon = ActionIcons.Trash2,
            onClick = onDeleteAll,
        )
    } else {
        items += SheetItem(
            label = "删除此版本",
            icon = ActionIcons.Trash2,
            hint = "只剩一版：删了就等于删消息，而那组动词还没有（D130）",
        )
    }
    return items
}

/**
 * 原生 `EditText` 塞进 Compose。三处要留神：
 * - **别在 `update` 里无脑 `setText`**：中文输入法还在组字时改内容会把候选打断。
 *   只在外部值真的变了、且与控件当前文本不同才写。
 * - 背景置空、padding 交给外层 Surface，否则会看到两层圆角。
 * - `maxLines` 给 6、`heightIn` 给下限：一行时不能塌成一条缝。
 */
@Composable
private fun NativeEditText(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onReady: (EditText) -> Unit = {},
) {
    // 文字色跟着主题走：写死深色主题的两支颜色，换到浅色就是"浅灰字压白底"，读不出来。
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier.heightIn(min = 44.dp),
        factory = { context ->
            EditText(context).apply {
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                this.hint = hint
                textSize = 16f
                maxLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
                setPadding(16, 22, 12, 18)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 光标跟着主题走；不设的话某些 ROM 上是纯黑，深色底看不见。
                    textCursorDrawable = null
                }
                onReady(this)
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: android.text.Editable?) {
                            onValueChange(s?.toString().orEmpty())
                        }
                    },
                )
            }
        },
        update = { editText ->
            // factory 只跑一次：主题在运行中切换时，颜色要在这里跟上来，
            // 否则从浅色切回深色，输入框还是白底黑字的旧色。
            editText.setTextColor(textColor)
            editText.setHintTextColor(hintColor)
            if (editText.text?.toString() != value) {
                editText.setText(value)
                editText.setSelection(value.length)
            }
        },
    )
}