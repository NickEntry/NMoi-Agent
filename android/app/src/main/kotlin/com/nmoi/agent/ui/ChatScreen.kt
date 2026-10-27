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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
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
import org.json.JSONObject
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
    // 刚发出去的那句话：**先进消息区**（用户 2026-10-27："发送的时候先把消息推到消息框"），
    // 输入框当场清空。这一条不进库、也不算"已存"——库里有没有得等这一轮收尾后现读。
    var pending by remember { mutableStateOf<PendingSend?>(null) }
    // "这一轮正在等模型"——只给消息区那个"正在生成…"占位用（`busy` 还管别的事）。
    var waiting by remember { mutableStateOf(false) }
    // 正在跑的那一轮挂在哪个会话上——点"停止"要的就是这个 id。
    // 单独存一份而不是直接读 `sessionId`：跑的过程中用户可能切了会话。
    var runningSession by remember { mutableStateOf<String?>(null) }
    // 选择模式：长按任意一条进入，点别的消息加选，顶部条上复制 / 分享。
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // 分享底部两排：上排两个开关（思考调用 / 详细信息），下排两个动作（MD / 图片）。
    var shareOpen by remember { mutableStateOf(false) }
    // 「更多」是一个**自己画的底部面板**（不是 DropdownMenu）：要带图标、要盖住整屏。
    //
    // **只记 id，不记对象**（D155）：`ChatMessage` 是点开那一刻的副本，拿它当动作输入，
    // 中间刷新过的版本/正文就用不上——"我明明改了，它复制的还是旧的"就是这么来的。
    var moreForId by remember { mutableStateOf<String?>(null) }
    // 「编辑」：把某一版的正文改一遍（写 `edited_text` 并切到"编辑后"，D137）。
    // `editingVersion` 是**点开「编辑」那一刻面板确认过的版号**，不许回退到列表里的旧值。
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf(0L) }
    // 编辑框**预填的那一份正文**。必须是库里现读的 `shown`（屏幕上显示的就是它），
    // 不能用列表快照里的 content：两者一旦错开，就会出现"看着改后、编辑框里是原文"
    // 而保存又落到改后那一组上（2026-10-26 报）。
    var editingInitial by remember { mutableStateOf("") }
    // 待确认的"删除整条消息"（破坏性动作要确认，D152）。
    var confirmDelete by remember { mutableStateOf<ChatMessage?>(null) }
    // 「更多」面板自己读一次库：显示与动作都用这一份。
    // 为什么不信列表里的字段：列表是快照，面板是"点开那一刻"的副本，
    // 两者一旦错开就会出现"看着 2/2、编辑落到 1/2"（2026-10-26 报）。
    val moreVersion by produceState<ChatRepository.VersionInfo?>(
        initialValue = null,
        moreForId,
        reloadKey,
    ) {
        value = moreForId?.let { id ->
            withContext(Dispatchers.IO) {
                ChatRepository.versions(File(context.filesDir, "nmoi.db").absolutePath, id)
                    .getOrNull()
            }
        }
    }
    var includeThinkingTools by remember { mutableStateOf(false) }
    var includeDetails by remember { mutableStateOf(false) }
    val databasePath = remember { File(context.filesDir, "nmoi.db").absolutePath }
    // 用哪一套端点 / 哪个模型归**助手**（D161）。这一屏只负责显示和切换它。
    var assistant by remember { mutableStateOf(AssistantEntry()) }
    var choices by remember { mutableStateOf(listOf<Pair<ProviderEntry, ProviderModel>>()) }
    var modelSheet by remember { mutableStateOf(false) }
    var thinkingSheet by remember { mutableStateOf(false) }
    // 增强编辑：待改的那条 + 正在跑（跑的时候不让点第二次）
    var enhanceFor by remember { mutableStateOf<ChatMessage?>(null) }
    var enhanceBusy by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId, reloadKey) {
        withContext(Dispatchers.IO) {
            AssistantRepository.current(databasePath).onSuccess { assistant = it }
            AssistantRepository.choices(databasePath).onSuccess { choices = it }
        }
    }
    val thinkingLabel = remember(assistant.thinking) {
        THINKING_LEVELS.firstOrNull { it.first == assistant.thinking }?.second ?: assistant.thinking
    }
    val modelLabel = remember(assistant, choices) {
        choices.firstOrNull { it.first.id == assistant.providerId && it.second.id == assistant.modelId }
            ?.label()
            ?: if (assistant.isRunnable) {
                "${assistant.providerId} / ${assistant.modelId}"
            } else {
                "还没选模型"
            }
    }

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
            // **所见即所得**（D153）：复制的必须是屏幕上现在显示的那一份——
            // 切在红就复制改后的，切在蓝就复制原文，他改过的字也要在。
            // 所以源文本现读（`versions().shown`），不用列表快照。
            val existing = ChatRepository.versions(databasePath, message.id).getOrNull()
            val source = existing?.shown?.takeIf { it.isNotEmpty() } ?: message.content
            // 这条消息还没有任何版本时，一次「复制」要落**两条**：
            // 先把现在显示的正文落成第 1 版，再把复制品落成第 2 版。
            // 只落一条的话 count=1，而界面按设计只在 count>1 时才显示 `n/n`——
            // 于是"点一次没反应、点两次才出 2/2"（2026-10-26 报的正是这个）。
            val needSeed = (existing?.count ?: 0) <= 0
            if (needSeed) {
                ChatRepository.saveVersion(databasePath, message.id, source)
                    .onFailure { notice = "存版本失败：${it.message}" }
            }
            ChatRepository.saveVersion(databasePath, message.id, source).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = if (needSeed) "已复制为新版本（临时探针，两版）" else "已复制为新版本（临时探针）"
                },
                onFailure = { notice = "存版本失败：${it.message}" },
            )
        }
    }

    val saveEdit: (ChatMessage, Long, String, String) -> Unit = { message, versionNo, text, before ->
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> notice = "编辑后的内容不能为空"
            // 比的是**预填的那一份**，不是列表快照：快照滞后时会误判成"内容没变"。
            trimmed == before.trim() -> notice = "内容没变"
            else -> scope.launch {
                ChatRepository.editVersion(databasePath, message.id, versionNo, trimmed).fold(
                    onSuccess = {
                        reloadKey += 1
                        notice = "已保存（用户编辑：不改颜色）"
                    },
                    onFailure = { notice = "编辑失败：${it.message}" },
                )
            }
        }
    }

    // **临时探针**：用"工具编辑"这条路改一版，专门用来验红/蓝到底怎么来的（D148）。
    // 真正的工具编辑要等 `core::edit::apply_edit` 桥出 FFI，那之前先用它把颜色造出来。
    val toolEditProbe: (ChatMessage, Long) -> Unit = { message, versionNo ->
        val marked = message.content + "\n\n（这一版是工具改的）"
        scope.launch {
            ChatRepository.toolEditVersion(databasePath, message.id, versionNo, marked).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = "工具改完了（这一版应当变红/蓝）"
                },
                onFailure = { notice = "工具编辑失败：${it.message}" },
            )
        }
    }

    val deleteVersion: (String, Long) -> Unit = { id, versionNo ->
        // 先记下"界面想删哪一版"，再看仓储那边回的 count/active：两行对不上就能定位。
        NLog.i("Version", "点删除：id=$id 请求版号=$versionNo")
        scope.launch {
            ChatRepository.deleteVersion(databasePath, id, versionNo).fold(
                onSuccess = { left ->
                    reloadKey += 1
                    notice = "已删除这一版（剩 $left 版）"
                },
                onFailure = { notice = "删版本失败：${it.message}" },
            )
        }
    }

    // 删整条消息（D152）：正文连版本一起没。破坏性动作，先问一次。
    val deleteMessage: (ChatMessage) -> Unit = { message ->
        NLog.i("Version", "点删除整条消息：id=${message.id}")
        scope.launch {
            ChatRepository.deleteMessage(databasePath, message.id).fold(
                onSuccess = { removed ->
                    reloadKey += 1
                    notice = if (removed > 1) {
                        "已删除这条消息（连同它的 $removed 条工具往返）"
                    } else {
                        "已删除这条消息"
                    }
                },
                onFailure = { notice = "删除失败：${it.message}" },
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
    // 返回键要一层一层退（用户 2026-10-26 报：浮层弹出来后按返回直接退应用，不是关浮层）。
    // 优先级按叠放层次从高到低：编辑对话框 → 「更多」面板 → 分享面板 → 选择模式。
    // 抽屉与页面栈的返回在 MainActivity 里；ChatScreen 后注册，所以内层先轮到它。
    val overlayOpen = confirmDelete != null || editingId != null || moreForId != null || shareOpen || selecting
    BackHandler(enabled = overlayOpen) {
        when {
            confirmDelete != null -> confirmDelete = null
            editingId != null -> editingId = null
            moreForId != null -> moreForId = null
            shareOpen -> shareOpen = false
            else -> {
                selecting = false
                selected = emptySet()
            }
        }
    }

    // 只落库、不请求（长按 ↑ 的那两项，PLAN §3.12）：语义是"先记下来、晚点再发"。
    // 会话不存在就先建——建失败要说原因，不能静默丢掉用户刚打的字。
    val appendOnly: (String) -> Unit = { role ->
        val text = draft.trim()
        when {
            text.isEmpty() -> notice = "先写点什么"
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
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
                            notice = if (role == "user") {
                                "已存为用户消息（没发给模型）"
                            } else {
                                "已存为助手消息（没发给模型）"
                            }
                        },
                        onFailure = { notice = "存失败：${it.message}" },
                    )
                }
                busy = false
            }
        }
    }

    // 真的发一轮（D157）：用户那句话由 `run_session_turn` 在发之前就落库了，
    // 所以失败也要重读一次界面——[TurnRepository.wasUserTextSaved] 判它到底存没存。
    val sendTurn: () -> Unit = {
        val text = draft.trim()
        when {
            text.isEmpty() -> notice = "先写点什么"
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
            else -> scope.launch {
                busy = true
                notice = null
                // 先上屏、先清输入框，再谈请求：发送这件事对用户来说是**立刻发生**的，
                // 那一秒里输入框还留着字，看起来就像没发出去（2026-10-27 报的就是这个）。
                pending = PendingSend(text = text, at = System.currentTimeMillis())
                waiting = true
                draft = ""
                val sid = sessionId ?: onCreateSession()
                if (sid == null) {
                    // 连会话都建不出来：把字放回输入框，别吞掉用户刚打的。
                    pending = null
                    waiting = false
                    draft = text
                    notice = "建会话失败：原因在 设置 → 调试 → 日志"
                    busy = false
                } else {
                    runningSession = sid
                    val result = TurnRepository.send(context, databasePath, sid, text)
                    runningSession = null
                    pending = null
                    waiting = false
                    busy = false
                    result.fold(
                        onSuccess = { outcome ->
                            reloadKey += 1
                            notice = outcome.describe()
                        },
                        onFailure = { error ->
                            // 请求可能死在半路，但用户那句话**已经进库了**——这是
                            // `run_session_turn` 的第一步。所以先重读，再决定草稿留不留。
                            reloadKey += 1
                            val saved = withContext(Dispatchers.IO) {
                                TurnRepository.wasUserTextSaved(databasePath, sid, text)
                            }
                            // 没存进去就把字还回去：宁可让他看到重复的一句，也不要静默丢字。
                            if (!saved) draft = text
                            notice = buildString {
                                append("请求失败：")
                                append(error.message)
                                append(if (saved) "（你这句话已经存进库里了）" else "（这句话没存进去，已放回输入框）")
                            }
                        },
                    )
                }
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
                ModelBar(label = modelLabel, onClick = { modelSheet = true })
            }

            Box(modifier = Modifier.weight(1f)) {
                if (sessionId == null) {
                    BlankConversationHint()
                } else {
                    MessageArea(
                        snapshot = snapshot,
                        pending = pending,
                        pendingAssistant = waiting,
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
                        onMoreMessage = { id ->
                            // 只记 id（D155）：对象是这一拍的副本，写操作要用时现取。
                            // 这一行仍是排查版本的**第一现场**，但打的是"此刻行内认为"的值。
                            val target = allMessages.firstOrNull { it.id == id }
                            if (target != null) {
                                NLog.i(
                                    "Version",
                                    "打开更多：id=$id 行内=${target.versionCount}/${target.versionActive} " +
                                        "view=${target.versionView} edited=${target.versionEdited}",
                                )
                            }
                            moreForId = id
                        },
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
                    onThinking = { thinkingSheet = true },
                    thinkingLabel = thinkingLabel,
                    onEditTextReady = { editTextRef.value = it },
                    onSend = sendTurn,
                    onStop = {
                        val running = runningSession
                        if (running == null) {
                            notice = "这一轮已经收尾了"
                        } else {
                            scope.launch {
                                TurnRepository.cancel(running).fold(
                                    onSuccess = { cancelled ->
                                        notice = if (cancelled) {
                                            "已请求停止：当前这一次请求回来之后就不再往下走了"
                                        } else {
                                            "这一轮已经收尾了"
                                        }
                                    },
                                    onFailure = { notice = "取消失败：${it.message}" },
                                )
                            }
                        }
                    },
                    onSaveAssistant = { appendOnly("assistant") },
                    onSaveUser = { appendOnly("user") },
                )

            } else {
                // 选择模式下藏起输入区，但留 10dp 底边，免得最后一条消息贴住屏幕边缘。
                Spacer(Modifier.heightIn(min = 10.dp))
            }
        }

        if (modelSheet) {
            MenuSheet(
                title = "用哪个模型",
                items = choices.map { (provider, model) ->
                    SheetItem(
                        label = "${provider.name.ifBlank { provider.id }} / ${model.id}",
                        icon = ActionIcons.Cpu,
                        hint = null,
                        onClick = {
                            modelSheet = false
                            scope.launch {
                                val next = assistant.copy(
                                    providerId = provider.id,
                                    modelId = model.id,
                                )
                                AssistantRepository.save(databasePath, next).fold(
                                    onSuccess = {
                                        assistant = next
                                        notice = "这一轮起用 ${model.id}"
                                    },
                                    onFailure = { notice = "切换失败：${it.message}" },
                                )
                            }
                        },
                    )
                }.ifEmpty {
                    listOf(
                        SheetItem(
                            label = "还没有可用的模型",
                            hint = "先去 设置 → 模型与提示词 → 供应商 里填一套端点与模型",
                        ),
                    )
                },
                onHint = { notice = it },
                onDismiss = { modelSheet = false },
            )
        }

        val pendingEnhance = enhanceFor
        if (pendingEnhance != null) {
            EnhanceDialog(
                busy = enhanceBusy,
                onCancel = { if (!enhanceBusy) enhanceFor = null },
                onRun = { instruction ->
                    if (!enhanceBusy) {
                        enhanceBusy = true
                        scope.launch {
                            val result = EditRepository.enhance(
                                context = context,
                                databasePath = databasePath,
                                message = pendingEnhance,
                                instruction = instruction,
                            )
                            enhanceBusy = false
                            result.fold(
                                onSuccess = { report ->
                                    enhanceFor = null
                                    reloadKey += 1
                                    notice = report.describe()
                                },
                                onFailure = { notice = "增强编辑失败：${it.message}" },
                            )
                        }
                    }
                },
            )
        }

        if (thinkingSheet) {
            MenuSheet(
                title = "思考强度",
                items = THINKING_LEVELS.map { (wire, label) ->
                    SheetItem(
                        label = if (wire == assistant.thinking) "$label（当前）" else label,
                        icon = ActionIcons.Gauge,
                        hint = null,
                        onClick = {
                            thinkingSheet = false
                            scope.launch {
                                val next = assistant.copy(thinking = wire)
                                AssistantRepository.save(databasePath, next).fold(
                                    onSuccess = {
                                        assistant = next
                                        notice = "思考强度：$label（下一轮开始生效）"
                                    },
                                    onFailure = { notice = "保存失败：${it.message}" },
                                )
                            }
                        },
                    )
                },
                onHint = { notice = it },
                onDismiss = { thinkingSheet = false },
            )
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

        val pendingDelete = confirmDelete
        if (pendingDelete != null) {
            ConfirmDeleteDialog(
                preview = pendingDelete.content,
                versionCount = pendingDelete.versionCount,
                onCancel = { confirmDelete = null },
                onConfirm = {
                    confirmDelete = null
                    deleteMessage(pendingDelete)
                },
            )
        }

        // 现取（D155）：编辑的是"这一条现在的内容"，不是点开那一刻的副本。
        val editingMessage = editingId?.let { id -> allMessages.firstOrNull { it.id == id } }
        if (editingMessage != null) {
            EditVersionDialog(
                initial = editingInitial,
                onCancel = { editingId = null },
                onSave = { text ->
                    editingId = null
                    saveEdit(editingMessage, editingVersion.coerceAtLeast(1L), text, editingInitial)
                },
            )
        }

        // 现取（D155）：面板显示的、动作改的，都是"现在这一条"。
        val sheetMessage = moreForId?.let { id -> allMessages.firstOrNull { it.id == id } }
        // 那一条已经不在了（删掉 / 换了会话）：把面板收掉。
        // 不收的话浮层状态还挂着，按返回会先"退"这个看不见的层，用户以为返回没反应。
        LaunchedEffect(moreForId, allMessages) {
            if (moreForId != null && allMessages.isNotEmpty() &&
                allMessages.none { it.id == moreForId }
            ) {
                moreForId = null
            }
        }
        LaunchedEffect(editingId, allMessages) {
            if (editingId != null && allMessages.isNotEmpty() &&
                allMessages.none { it.id == editingId }
            ) {
                editingId = null
            }
        }
        if (sheetMessage != null) {
            // 面板显示/动作统一用 `moreVersion`（开面板时现读的库）；还没读到时先用行内那份顶一下。
            val sheetCount = moreVersion?.count ?: sheetMessage.versionCount
            val sheetActive = (moreVersion?.active ?: sheetMessage.versionActive).coerceAtLeast(1)
            // 第一现场：**同一刻**两个来源（行内快照 / 现读库）。不相等说明行内那条旧了。
            val nowCount = moreVersion?.count ?: -1
            val nowActive = moreVersion?.active ?: -1
            val nowView = moreVersion?.view ?: "none"
            LaunchedEffect(moreVersion, sheetMessage.versionCount) {
                NLog.i(
                    "Version",
                    "面板 id=${sheetMessage.id} 行内=${sheetMessage.versionCount}/${sheetMessage.versionActive} " +
                        "view=${sheetMessage.versionView} | 现读=$nowCount/$nowActive view=$nowView",
                )
            }
            val shown = sheetMessage.copy(
                versionCount = sheetCount,
                versionActive = sheetActive,
                versionView = moreVersion?.view ?: sheetMessage.versionView,
                versionEdited = moreVersion?.edited ?: sheetMessage.versionEdited,
            )
            MenuSheet(
                title = if (shown.isUser) "这条用户消息" else "这条助手消息",
                items = messageSheetItems(
                    message = shown,
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
                    onEdit = {
                        // 版号与预填都取**面板确认过的那个**，不是列表里的：
                        // 这就是"编辑 2/2 却改到 1/2"和"看着改后、编辑框里是原文"的病根。
                        editingVersion = sheetActive.toLong()
                        editingInitial = moreVersion?.shown?.takeIf { it.isNotEmpty() }
                            ?: sheetMessage.content
                        editingId = sheetMessage.id
                    },
                    onToolEdit = { toolEditProbe(sheetMessage, sheetActive.toLong()) },
                    onEnhance = {
                        moreForId = null
                        enhanceFor = sheetMessage
                    },
                    // `versionActive` 兜底成 1：万一它是 0（老库/字段没读到），
                    // 传 0 会被 storage 当成"删除所有版本"，那不是用户点"删除此版本"的意思。
                    onDeleteThis = {
                        deleteVersion(sheetMessage.id, sheetActive.toLong())
                    },
                    onDeleteAll = { confirmDelete = sheetMessage },
                ),
                onHint = { notice = it },
                onDismiss = { moreForId = null },
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
    /** 刚发出去、还在路上的那句话：**立刻显示在消息区里**，不要留在输入框里等。 */
    pending: PendingSend?,
    pendingAssistant: Boolean,
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
    if ((page == null || page.isEmpty) && pending == null) {
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

    val units = page?.units.orEmpty()
    val truncated = page?.truncated == true
    val listState = rememberLazyListState()
    val headerCount = if (truncated) 1 else 0
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    // 打开会话应当看到最新的一条，而不是最早的一条。
    // 发出去那一刻 `pending` 会插在末尾，所以它也要进 key——否则新气泡在屏幕外面。
    LaunchedEffect(units.size, truncated, pending, pendingAssistant) {
        val target = headerCount + units.size + if (pending != null) 1 else 0 +
            if (pendingAssistant) 1 else 0
        if (target - 1 >= 0) listState.scrollToItem(target - 1)
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
        if (truncated) {
            item {
                Text(
                    text = "上面还有更早的消息：共 ${page?.total} 个单元，这里只读了最后 ${units.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
        itemsIndexed(units) { index, unit ->
            val previous = units.getOrNull(index - 1)
            val gap = if (previous != null && previous.alignsRight() != unit.alignsRight()) 8.dp else 0.dp
            // 一轮助手的话可能被拆成好几个单元（工具往返一段、正文一段），抬头只画一次。
            val assistantSide = unit.isToolPair || unit.messages.none { it.isUser }
            val previousAssistantSide = previous != null &&
                (previous.isToolPair || previous.messages.none { it.isUser })
            UnitBlock(
                unit = unit,
                showHeader = assistantSide && !previousAssistantSide,
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

        val outbox = pending
        if (outbox != null) {
            item(key = "pending-user") {
                // 这一条还没从库里读回来（`run_session_turn` 是先落库再发请求，
                // 但读完要等这一轮收尾），所以先按用户消息的形状画出来。
                UserMessage(
                    message = ChatMessage(
                        id = "pending:user",
                        seq = Long.MAX_VALUE,
                        role = "user",
                        source = "history",
                        content = outbox.text,
                        tokens = 0,
                        createdAt = outbox.at,
                        incomplete = false,
                        locked = false,
                        toolCalls = 0,
                        toolCallId = null,
                    ),
                    maxBubble = maxBubble,
                    selecting = false,
                    isSelected = false,
                    onLongPress = {},
                    onTap = {},
                    onCopied = {},
                    onShare = {},
                    onMore = {},
                    onVersion = { _, _ -> },
                )
            }
        }
        if (pendingAssistant) {
            item(key = "pending-assistant") {
                PendingAssistantRow()
            }
        }
    }
}

/**
 * "模型还没回"的占位。**它不落库**，只是一个呼吸中的小字——
 * 用户要能看出"发出去了、在等"，而不是盯着输入框猜。
 */
@Composable
private fun PendingAssistantRow() {
    val transition = rememberInfiniteTransition(label = "pending")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "pending-alpha",
    )
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.width(42.dp))
        Text(
            text = "正在生成…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/**
 * 抬头该用哪个时间：优先这一段里**正文**那条的时间（用户看到的就是它），
 * 纯工具单元没有正文，退回单元自己的时间。思考块的时间更早，不拿它当抬头。
 */
private fun ChatUnit.headerAt(): Long =
    messages.firstOrNull { !it.isThinking && !it.isToolCall && !it.isToolResult }?.createdAt
        ?: messages.firstOrNull()?.createdAt
        ?: createdAt

/** 这个单元整体靠哪边——只用来算"换人"的间距，不参与别的判断。 */
private fun ChatUnit.alignsRight(): Boolean =
    !isToolPair && messages.isNotEmpty() && messages.all { it.isUser }

/** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 */
@Composable
private fun UnitBlock(
    unit: ChatUnit,
    /** 这一段是不是"这一轮助手的头一块"。连续的工具卡/正文只画一次抬头。 */
    showHeader: Boolean,
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
        // 抬头（头像 + 助手 + 时间）要在**思考块与工具卡的上方**（用户 2026-10-27 报：
        // 思考卡排在"助手"名字上面，看着像别人说的话）。所以这里统一先画抬头，
        // 再画这一段的卡和正文；助手连续几段只画第一个，不然一轮里会冒出三四行"助手"。
        val assistantSide = unit.isToolPair || unit.messages.none { it.isUser }
        if (assistantSide && showHeader) {
            AssistantHeader(createdAt = unit.headerAt())
        }
        if (broken != null) {
            BrokenCard(declaration = broken.declaration, reason = broken.reason)
        }
        if (unit.isToolPair) {
            ToolPairCard(unit)
        } else {
            unit.messages.forEach { message ->
                when {
                    // 空的思考消息没有内容可指，画一个"思考 · 0 字"的空壳只会让人以为坏了。
                    message.isThinking -> if (message.content.isNotBlank()) ThinkingBlock(message)
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

                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 思考块（D162）：以前 `reasoning` 只存不显示，界面上永远看不见——
                        // 那是铁律 ②（不许有存了但看不见的东西）直接踩线的一处。
                        message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningCard(it) }
                        AssistantMessage(
                            message = message,
                            showHeader = false,
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
}

/**
 * 刚发出去、还没从库里读回来的那一句。
 *
 * 它**不是**一条"看不见的存了的数据"（铁律 ② 管的是库里）：这一条只活在内存里，
 * 屏幕上看得见，`turn_run` 收尾后就用库里那份替换掉它。
 */
private data class PendingSend(val text: String, val at: Long)

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

        MessageActionRow(
            mine = true,
            onCopy = {
                putClipboard(context, "消息", message.content)
                onCopied()
            },
            onShare = onShare,
            onSelect = onLongPress,
            onMore = onMore,
            versionControl = { VersionControl(message = message, onPick = onVersion) },
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
    /** 抬头由 [UnitBlock] 统一画（思考卡/工具卡要排在它下面），所以这里默认不画。 */
    showHeader: Boolean = false,
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
        if (showHeader) AssistantHeader(createdAt = message.createdAt)
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
        MessageActionRow(
            mine = false,
            onCopy = {
                putClipboard(context, "消息", message.content)
                onCopied()
            },
            onShare = onShare,
            onSelect = onLongPress,
            onMore = onMore,
            versionControl = { VersionControl(message = message, onPick = onVersion) },
        )
    }
}

/**
 * 助手抬头：头像 + 名字 + 时间。
 *
 * 单独拎出来是因为它要画在**思考块和工具卡的上方**（2026-10-27 报），
 * 而那些卡片不在 `AssistantMessage` 里，而是在同一个单元的更上面。
 */
@Composable
private fun AssistantHeader(createdAt: Long) {
    val context = LocalContext.current
    val stamp = stampOf(context, createdAt)
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
}

/**
 * 编辑某一版的正文。
 *
 * **改的是"现在显示的那一组"**（D148）：显示着原文就改原文，显示着 AI 改后那份就改它——
 * 两份各存各的，互不覆盖。改完还是显示他改的那份，**颜色不变**（颜色只表示"被 AI 改过"）。
 * 预填的正文由调用方给（库里现读的 `shown`），不是列表快照里的 `content`。
 *
 * 用自绘的居中卡片，不用系统 `AlertDialog`：这里要放进原生 `EditText`（中文输入法那套），
 * 而系统对话框的尺寸与输入法联动在深浅两套主题下都不好对齐。
 */
@Composable
private fun EditVersionDialog(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "编辑这一版",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "改的是现在显示的那一组（原文 / AI 改后各存一份，互不覆盖）；颜色不变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        NativeEditText(
                            value = text,
                            onValueChange = { text = it },
                            hint = "编辑后的正文",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogAction(label = "取消", primary = false, onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        DialogAction(label = "保存", primary = true, onClick = { onSave(text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = when {
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val ink = when {
        danger -> MaterialTheme.colorScheme.onError
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = fill,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 删除整条消息前的确认（D152）。
 *
 * 这条动作把**正文、全部版本、编辑记录**一起删掉，收不回来——所以必须问一次，
 * 而且要把"会删掉什么"写出来（用户 2026-10-26 明确要的是"不保留任何正文"，
 * 那就更得先说清楚，别让他点完才发现）。
 *
 * 形制跟编辑框同一套：自绘居中卡片，不用系统 `AlertDialog`（那套在这两套主题下都不好对齐）。
 */
@Composable
private fun ConfirmDeleteDialog(
    preview: String,
    versionCount: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "删除这条消息？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (versionCount > 1) {
                            "正文、全部 $versionCount 个版本，以及它的工具往返一起删掉，收不回来。"
                        } else {
                            "正文、版本记录，以及它的工具往返一起删掉，收不回来。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview.trim().take(80).let {
                                if (preview.trim().length > 80) "$it…" else it
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogAction(label = "取消", primary = false, onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        DialogAction(label = "删除", primary = false, danger = true, onClick = onConfirm)
                    }
                }
            }
        }
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
    // 什么时候不画：**只有一版、而且这一版没被 AI 改过**。
    // 只管条数会漏掉"一版被工具改过"——那时候数字有颜色、点它能切原文/改后，
    // 藏起来等于把这个能力藏了（2026-10-26 报：改完只有一条消息，应该出现 1/1）。
    if (message.versionCount <= 1 && !message.versionEdited) return
    val active = message.versionActive.coerceAtLeast(1)
    // 列表里 `version_count` 可能是 0（那条字段滞后的老问题），但只要有 AI 标记就至少是 1 版；
    // 不兜这一下会显示成 `1/0`。
    val total = message.versionCount.coerceAtLeast(active)
    val numberColor = when {
        !message.versionEdited -> MaterialTheme.colorScheme.onSurface
        message.versionView == "edited" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionArrow(
            description = "上一版",
            rotate = 90f,
            enabled = active > 1,
            onClick = { onPick((active - 1).toLong(), "keep") },
        )
        Text(
            text = "$active/$total",
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
            enabled = active < total,
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
    versionControl: @Composable () -> Unit = {},
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
        // 版本控件跟在这一排里、⋯ 的右边，中间空一个图标的宽度（用户 2026-10-24 定的位置）。
        Spacer(Modifier.width(36.dp))
        versionControl()
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
                    // 配对：`call_id` 是唯一能对上"这次调用"和"那条结果"的东西。
                    // 配不上（断块、被取消的批）就照老样子单独列出来，别假装配上了。
                    val results = unit.messages
                        .filter { it.isToolResult }
                        .associateBy { it.toolCallId.orEmpty() }
                    val pairs = unit.messages.flatMap { message ->
                        message.toolArgs.map { arg -> arg to results[arg.callId] }
                    }
                    if (pairs.isNotEmpty()) {
                        pairs.forEach { (arg, result) ->
                            ToolCallBlock(command = arg, result = result)
                        }
                    } else {
                        unit.messages.forEach { message ->
                            Text(
                                text = buildString {
                                    append(if (message.isToolResult) "← 结果" else "→ 调用")
                                    message.toolCallId?.let { append(" [$it]") }
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
    onThinking: () -> Unit,
    thinkingLabel: String,
    onEditTextReady: (EditText) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
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
                        description = "思考强度：$thinkingLabel",
                        onClick = onThinking,
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
                        onStop = onStop,
                        onSaveAssistant = onSaveAssistant,
                        onSaveUser = onSaveUser,
                    )
                }
            }
        }

        Text(
            text = if (unsaved) {
                "这是还没落库的新会话：第一条消息发出去时才建会话。"
            } else {
                "点 ↑ 发请求；生成中再点一下是停止（停在这一次请求回来之后）。" +
                    "长按 ↑ 可以只保存、不发送。"
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
 * - 生成中：方块，点它 = 取消（`turn_cancel`，在下一个回合边界断开，D58 那条代价）；
 * - **长按出菜单**（用户 2026-10-21 定的）：仅保存为助手消息 / 仅保存为用户消息。
 *   这两项走 `append_text`，语义是"先记下来、晚点再发"，**不发请求**。
 */
@Composable
private fun SendStopButton(
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = enabled && !busy
    val background = if (active || busy) {
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
                        onTap = {
                            when {
                                busy -> onStop()
                                active -> onSend()
                            }
                        },
                        onLongPress = { if (!busy) menuOpen = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = background, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (busy) ActionIcons.Square else ActionIcons.ArrowUp,
                        contentDescription = if (busy) "停止" else "发送",
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
    onEdit: () -> Unit,
    onToolEdit: () -> Unit,
    onEnhance: () -> Unit,
    onDeleteThis: () -> Unit,
    onDeleteAll: () -> Unit,
): List<SheetItem> {
    val items = mutableListOf(
        SheetItem(
            label = "编辑",
            icon = ActionIcons.Pencil,
            onClick = onEdit,
        ),
        SheetItem(
            label = "增强编辑（让模型改这一条）",
            icon = ActionIcons.Wrench,
            onClick = onEnhance,
        ),
        // 临时探针：走"工具编辑"那条路，把 `edited_by_tool` 置上，用来验红/蓝（D148）。
        SheetItem(
            label = "用工具改这一版（临时探针）",
            icon = ActionIcons.Pencil,
            onClick = onToolEdit,
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
    // 多版：两个都给。只有一版：只给"删除所有版本"——它是**清空版本记录**
    // （连这一版一起删、正文不动，D151），所以单版也能用；"删除此版本"删到零版等于删消息，
    // 那组动词还没做（D130），于是只在多版时出现。
    if (message.versionCount > 1) {
        items += SheetItem(
            label = "删除此版本",
            icon = ActionIcons.Trash2,
            onClick = onDeleteThis,
        )
    }
    // 这一项删的是**整条消息**（正文一起没，D152）：所以只要有消息就给，不限版本数，
    // 而且文案把"含所有版本"写出来——`hint` 面板不画，写在里面等于没写。
    items += SheetItem(
        label = "删除这条消息（含所有版本）",
        icon = ActionIcons.Trash2,
        onClick = onDeleteAll,
    )
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
/**
 * 一次工具调用 + 它的结果（D159）。
 *
 * 形状照用户给的那张图：**命令**一块、**输出**一块（stdout / stderr 两个页签），
 * 各带复制。命令是模型传来的 `arguments.command`，输出是结果里那份结构化 JSON。
 *
 * 为什么不再"把参数和结果原样铺出来"：一条 `ls -la` 的输出几十行，
 * 原样铺开之后人根本看不出哪段是命令、哪段是 stdout、哪段是报错。
 */
@Composable
private fun ToolCallBlock(command: ToolCallArg, result: ChatMessage?) {
    val context = LocalContext.current
    val payload = result?.content?.let { text ->
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("stdout") || it.has("stderr") }
    }
    val shown = if (payload != null) {
        payload.optString("command", "")
    } else {
        runCatching { JSONObject(command.arguments).optString("command", "") }.getOrNull().orEmpty()
    }
    val label = if (command.name == "System-Shell") "运行命令" else command.name
    // 用「选过没有」+ 默认页签，而不是在组合过程里改状态：组合里赋值会多走一轮重组。
    var picked by remember(result?.id) { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (result == null) {
                Text(
                    text = "没有结果（这一批里它没跑完）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = "命令",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        MonoBox(text = shown.ifEmpty { command.arguments })
        CopyHint(text = shown.ifEmpty { command.arguments }, onCopied = { putClipboard(context, "命令", it) })

        if (payload == null) {
            if (result != null) {
                Text(
                    text = "输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                MonoBox(text = result.content.ifEmpty { "（空）" })
            }
            return@Column
        }

        val stdout = payload.optString("stdout", "")
        val stderr = payload.optString("stderr", "")
        // 失败时默认停在 stderr：让人第一眼看到的就是报错，而不是一片空白。
        val failed = (payload.opt("exit_code") as? Number)?.toInt()?.let { it != 0 } ?: true
        val defaultTab = if (failed && stderr.isNotBlank() && stdout.isBlank()) 1 else 0
        val tab = picked ?: defaultTab

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "输出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val exit = payload.opt("exit_code")
            Text(
                text = buildString {
                    append(if (exit == null || exit == JSONObject.NULL) "被信号终止" else "退出码 $exit")
                    if (payload.optBoolean("timed_out", false)) append(" · 超时")
                    if (payload.optBoolean("truncated", false)) append(" · 已截断")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TabChip("stdout", selected = tab == 0) { picked = 0 }
            Spacer(Modifier.width(6.dp))
            TabChip("stderr", selected = tab == 1) { picked = 1 }
        }

        val body = if (tab == 0) stdout else stderr
        MonoBox(text = body.ifEmpty { "（无输出）" })
        CopyHint(text = body, onCopied = { putClipboard(context, if (tab == 0) "stdout" else "stderr", it) })
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 等宽的一块内容。太长就截断显示——**复制复制的是全文**，不是这一屏。 */
@Composable
private fun MonoBox(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            text = if (text.length > 4000) {
                text.take(4000) + "\n…（还有 ${text.length - 4000} 字，复制能拿到全文）"
            } else {
                text
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CopyHint(text: String, onCopied: (String) -> Unit) {
    if (text.isEmpty()) return
    Text(
        text = "复制",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { onCopied(text) }
            .padding(top = 4.dp, bottom = 2.dp),
    )
}

/** 聊天页顶部那条"现在用哪个模型"。点开是选择面板（D161）。 */
@Composable
private fun ModelBar(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.Cpu,
            contentDescription = "当前模型",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false),
        )
        Icon(
            imageVector = ActionIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp).padding(start = 2.dp),
        )
    }
}

/** 思考强度档位：wire 值必须跟 `crates/ffi/src/turn.rs` 的 `apply_thinking` 对表。 */
private val THINKING_LEVELS = listOf(
    "auto" to "自动（不注字段）",
    "off" to "关闭",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最大",
)

/**
 * 一条消息的思考块（D162）。折叠起来只占一行，点开看全文。
 *
 * 为什么不做成"气泡旁边的小字"：思考经常几百字，铺开之后正文就被挤到屏外，
 * 而那才是他要读的东西。
 */
@Composable
private fun ReasoningCard(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ActionIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = if (expanded) "思考（点一下收起）" else "思考 · ${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 增强编辑的输入框（D163）：只问"要改成什么样"。
 *
 * 为什么不做成"把正文摆出来让你改"：那是「编辑」。这一个的输入是**要求**，
 * 输出是模型改写后的结果，落在同一版的红字那一份上。
 */
@Composable
private fun EnhanceDialog(busy: Boolean, onCancel: () -> Unit, onRun: (String) -> Unit) {
    var instruction by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "增强编辑",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "说说要改成什么样。模型会改正文；如果这条消息有思考块或工具参数，也可以一起改" +
                            "（改后的正文放进「改后」那一份，原文不动）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = instruction,
                            onValueChange = { instruction = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogChip(label = "取消", onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        DialogChip(
                            label = if (busy) "改写中…" else "开始改写",
                            primary = true,
                            onClick = { onRun(instruction) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
