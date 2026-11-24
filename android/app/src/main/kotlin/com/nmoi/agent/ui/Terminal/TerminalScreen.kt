package com.nmoi.agent.ui.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmoi.agent.ui.ActionIcons
import com.nmoi.agent.ui.BackButton
import com.nmoi.agent.ui.BlurOnLeaveApp
import com.nmoi.agent.ui.OnImeHidden
import com.nmoi.agent.ui.IconActionButton
import com.nmoi.agent.ui.MenuSheet
import com.nmoi.agent.ui.SheetItem
import kotlinx.coroutines.launch

/**
 * 终端（D395 / D397 / D399）：**命令回显式**，不是真 PTY。
 *
 * 一行一条命令、输出追加到滚动区、提示符自己画。能跑脚本与构建，
 * 跑不了 `vim` / `top` 这类全屏程序——那一类在按下之前就拦下来并说清替代办法。
 *
 * # 会话（D399）
 *
 * 返回**不销毁**：会话活在 `TerminalSessions`（进程级），再点进来打开已有的那个。
 * 顶部一排胶囊是会话列表，长按给「重命名 / 关闭」。
 *
 * # 底栏（D397）
 *
 * 两排可配键，默认照 Termux 那张图。**修饰键按下变选中态**，再按一个字母就组合发送
 * （`Ctrl+C` = 0x03）。输入的不是单个字母就取消选中、什么都不发——猜一个反而更糟。
 */
@Composable
internal fun TerminalScreen(
    workspaceId: String,
    workspacePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 进页面：有会话就复用，没有才建（用户 2026-11-24 明确要求）。
    val initial = remember(workspaceId) {
        val shell = ShellSessions.default()
        TerminalSessions.openOrCreate(
            workspaceId = workspaceId,
            workspacePath = workspacePath,
            label = shell.label,
            shellLabel = shell.label,
        )
    }
    var activeId by remember { mutableStateOf(initial.id) }
    val session = TerminalSessions.sessions.firstOrNull { it.id == activeId } ?: initial

    var draft by remember(activeId) { mutableStateOf("") }
    var historyIndex by remember(activeId) { mutableStateOf(-1) }
    // 键盘只在"点过终端"之后出现（D417）：跟 Termux 一样，点一下正文就呼出，
    // 敲的字直接长在提示符后面，没有另起一行的输入框。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // 焦点落到那一行之后才叫键盘：`requestFocus` 是异步生效的，
    // 同一帧里叫 `show()` 有一半机型不认（点了没反应）。
    LaunchedEffect(focused) {
        if (focused) {
            keyboard?.show()
            // 键盘一弹起来正文区会变矮，输入行可能被挤出屏幕：滚到底再敲。
            listState.animateScrollToItem(session.lines.size)
        }
    }
    // 切后台 / 手势退到桌面时放掉焦点：回来那一刻系统不会再自己把键盘弹起来。
    BlurOnLeaveApp {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    // 用户自己把键盘按回去了：焦点跟着掉，否则下次点任意地方它又冒出来。
    OnImeHidden { focusManager.clearFocus() }
    var notice by remember(activeId) { mutableStateOf<String?>(null) }
    // 修饰键选中态（D397）：按下变选中，再按一个字母组合发送。
    var modifiers by remember(activeId) { mutableStateOf<Set<TerminalKey>>(emptySet()) }
    var sessionSheet by remember { mutableStateOf(false) }
    var renameSheet by remember { mutableStateOf<TerminalSession?>(null) }
    var closeAsk by remember { mutableStateOf<TerminalSession?>(null) }
    var keysSheet by remember { mutableStateOf(false) }

    val shell = remember(session.shellLabel) {
        ShellSessions.all.firstOrNull { it.label == session.shellLabel } ?: ShellSessions.default()
    }

    // 首屏给一行提示符。
    LaunchedEffect(activeId) {
        if (session.lines.isEmpty()) {
            session.append(TerminalLine.Prompt(shell.label, workspacePath))
        }
    }

    /** 发一段字节（控制字符 / 组合键）。 */
    fun send(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        if (!session.busy) {
            // 没有命令在跑：字节没人收。写清楚，别让人以为按了没反应。
            notice = "现在没有命令在跑，按键没有接收方"
            return
        }
        scope.launch {
            val written = shell.write(session.id, bytes)
            if (written == 0) notice = "这条命令已经不再读了（可能刚结束）"
        }
    }

    fun submit() {
        val command = draft.trim()
        if (command.isEmpty() || session.busy) return
        val blocked = ShellRules.blockedReason(command)
        if (blocked != null) {
            session.append(TerminalLine.Command(command))
            session.append(TerminalLine.Notice(blocked))
            draft = ""
            return
        }
        session.append(TerminalLine.Command(command))
        draft = ""
        historyIndex = -1
        val next = ShellRules.pushHistory(session.history, command)
        session.history = next
        TerminalPrefs.setHistory(context, next)
        session.busy = true
        scope.launch {
            shell.run(session.id, command, workspacePath).fold(
                onSuccess = { result ->
                    if (result.stdout.isNotEmpty()) {
                        session.append(TerminalLine.Output(result.stdout.trimEnd('\n')))
                    }
                    if (result.stderr.isNotEmpty()) {
                        session.append(TerminalLine.Error(result.stderr.trimEnd('\n')))
                    }
                    if (result.isEmpty) session.append(TerminalLine.Notice("（没有输出）"))
                    session.append(TerminalLine.Notice(result.summary()))
                    if (result.changes.isNotEmpty()) {
                        session.append(
                            TerminalLine.Notice(
                                "改了 ${result.changes.size} 个文件：" + result.changes.joinToString("、"),
                            ),
                        )
                    }
                },
                onFailure = { session.append(TerminalLine.Error(it.message ?: "跑不起来")) },
            )
            session.busy = false
            TerminalNotifications.sync(context)
        }
    }

    /** 上下键翻历史。返回 null = 已经到头，草稿不动。 */
    fun historyStep(delta: Int): String? {
        val list = session.history
        if (list.isEmpty()) return null
        val from = if (historyIndex < 0) {
            if (delta < 0) list.size else return null
        } else {
            historyIndex + delta
        }
        if (from < 0 || from >= list.size) return null
        historyIndex = from
        return list[from]
    }

    /** 按一颗键（D397 / D417）。 */
    fun press(key: TerminalKey) {
        if (key.isModifier) {
            modifiers = if (modifiers.contains(key)) modifiers - key else modifiers + key
            return
        }
        if (key.isLocal) {
            when (key) {
                TerminalKey.Copy -> {
                    val text = session.lines.joinToString("\n") { it.plain() }
                    val clipboard = context
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("终端", text))
                    notice = "已复制 ${text.length} 个字符"
                }

                TerminalKey.Paste -> {
                    val clipboard = context
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val text = clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        .orEmpty()
                    if (text.isEmpty()) {
                        notice = "剪贴板是空的"
                    } else {
                        draft += text.replace("\n", " ")
                        notice = "已粘贴 ${text.length} 个字符"
                    }
                }

                else -> Unit
            }
            return
        }
        // 没有命令在跑的时候，回车与上下键是**这一层**的动作，不是往管道里写字节
        // （D417）：这个终端是一行一条命令，没有 PTY 收这些字节，发过去只会得到
        // "没有接收方"。回车 = 跑这一条，上下 = 翻历史。
        if (!session.busy && modifiers.isEmpty()) {
            when (key) {
                TerminalKey.Enter -> {
                    submit()
                    return
                }

                TerminalKey.Up -> {
                    val next = historyStep(-1)
                    if (next != null) draft = next
                    return
                }

                TerminalKey.Down -> {
                    val next = historyStep(1)
                    if (next != null) draft = next
                    return
                }

                else -> Unit
            }
        }
        send(TerminalKeys.bytesFor(key, modifiers))
        modifiers = TerminalKeys.afterSend(modifiers)
    }

    // 滚到**输入行**（D417）：列表最后一项是输入行，所以目标下标是 `lines.size`，
    // 不是 `lines.lastIndex`——差一就停在最后一行输出上，敲字的地方还在屏幕外。
    LaunchedEffect(session.lines.size) {
        if (session.lines.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(session.lines.size)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconActionButton(
                    image = ActionIcons.Ellipsis,
                    description = "更多",
                    onClick = { sessionSheet = true },
                    compact = true,
                )
            }

            // 会话胶囊（D399）：一排，点切换，长按给重命名 / 关闭。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(TerminalSessions.sessions.size) { index ->
                        val item = TerminalSessions.sessions[index]
                        SessionChip(
                            session = item,
                            active = item.id == activeId,
                            onClick = { activeId = item.id },
                            onLongPress = { renameSheet = item },
                        )
                    }
                }
                IconActionButton(
                    image = ActionIcons.Plus,
                    description = "新建会话",
                    onClick = {
                        val fresh = TerminalSessions.create(
                            workspaceId = workspaceId,
                            workspacePath = workspacePath,
                            label = shell.label,
                            shellLabel = shell.label,
                        )
                        activeId = fresh.id
                    },
                    compact = true,
                )
            }

            // 来源胶囊：一行，点开切档（容器 / 系统 / 宿主）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { sessionSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (shell.available()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    RoundedCornerShape(999.dp),
                                ),
                        )
                        Text(
                            text = shell.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (session.busy) "跑着…" else "一行一条命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 修饰键待用提示（D397）：选中了 Ctrl 但还没按字母时，要说清"接下来敲一个字母"。
            if (modifiers.isNotEmpty()) {
                Text(
                    text = modifiers.joinToString("+") { it.label } + "+ ？  ·  敲一个字母发送，其他键取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            val note = notice
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // 终端正文（D417）：**输入就长在正文最后一行**，没有另外一条输入框。
            //
            // 手势照 Termux：点正文任意位置就呼出键盘，敲的字直接接在提示符后面；
            // 回车 = 跑这一条。原来那一条独立的输入行（下面还挂一颗 ↑ 按钮）已经去掉——
            // 它把"在终端里打字"变成了"往一个表单里填字"，手感是两回事。
            val mono = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                        )
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    items(session.lines.size) { index ->
                        TerminalRow(session.lines[index])
                    }
                    // 最后一行就是输入行：提示符 + 草稿 + 光标。
                    item(key = "input") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "$ ", style = mono, color = MaterialTheme.colorScheme.primary)
                            BasicTextField(
                                value = draft,
                                onValueChange = { input ->
                                    // 修饰键选中时敲进来的第一个字符**当组合键发出去**，
                                    // 不进这一行（D397）。不是单个字母就取消选中、什么都不发。
                                    if (modifiers.isNotEmpty()) {
                                        if (TerminalKeys.isSingleLetter(input)) {
                                            send(TerminalKeys.bytesForLetter(input[0], modifiers))
                                            modifiers = TerminalKeys.afterSend(modifiers)
                                        } else {
                                            modifiers = emptySet()
                                            notice = "组合键只接单个字母，已取消"
                                        }
                                        return@BasicTextField
                                    }
                                    draft = input
                                    historyIndex = -1
                                },
                                singleLine = true,
                                textStyle = mono.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        submit()
                                        keyboard?.show()
                                    },
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focused = it.isFocused },
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty() && !focused) {
                                            Text(
                                                text = if (session.busy) {
                                                    "等这一条跑完…"
                                                } else {
                                                    "点一下这里敲命令"
                                                },
                                                style = mono,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 底栏两排键（D397）：照 Termux 那张图，列数与按键可配。
            val (rows, columns) = TerminalPrefs.layout(context)
            KeyPad(
                rows = rows,
                columns = columns,
                modifiers = modifiers,
                onPress = ::press,
                onLongPressEmpty = { keysSheet = true },
            )
        }
    }

    if (sessionSheet) {
        MenuSheet(
            title = "终端",
            items = buildList {
                add(
                    SheetItem(
                        label = "按键布局",
                        icon = ActionIcons.Wrench,
                        onClick = {
                            sessionSheet = false
                            keysSheet = true
                        },
                    ),
                )
                add(
                    SheetItem(
                        label = "清屏",
                        icon = ActionIcons.RotateCcw,
                        onClick = {
                            sessionSheet = false
                            session.replaceLines(listOf(TerminalLine.Prompt(shell.label, workspacePath)))
                        },
                    ),
                )
                add(
                    SheetItem(
                        label = "关闭全部会话",
                        icon = ActionIcons.Trash2,
                        onClick = {
                            sessionSheet = false
                            TerminalSessions.closeAll()
                            TerminalNotifications.sync(context)
                            onBack()
                        },
                    ),
                )
                for (candidate in ShellSessions.all) {
                    add(
                        SheetItem(
                            label = candidate.label,
                            value = if (candidate == shell) "当前" else null,
                            hint = if (!candidate.available()) candidate.unavailableReason() else null,
                            onClick = if (!candidate.available()) {
                                null
                            } else {
                                {
                                    sessionSheet = false
                                    session.shellLabel = candidate.label
                                    session.append(TerminalLine.Notice("已切到「${candidate.label}」"))
                                }
                            },
                        ),
                    )
                }
            },
            onHint = { notice = it },
            onDismiss = { sessionSheet = false },
        )
    }

    renameSheet?.let { target ->
        com.nmoi.agent.ui.Files.NameInputSheet(
            title = "重命名会话",
            placeholder = target.title,
            initial = target.title,
            onCancel = { renameSheet = null },
            onSubmit = { name ->
                TerminalSessions.rename(target.id, name)
                renameSheet = null
            },
        )
    }

    if (keysSheet) {
        TerminalKeysSheet(
            onDismiss = { keysSheet = false },
            onSaved = { notice = "按键布局已保存" },
        )
    }
}

/** 一个会话胶囊：绿点 + 名字。长按给重命名（关闭在长按菜单里）。 */
@Composable
private fun SessionChip(
    session: TerminalSession,
    active: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.pointerInput(session.id, active) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        if (session.busy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        RoundedCornerShape(999.dp),
                    ),
            )
            Text(
                text = session.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 底栏键区（D397）：两排（或更多）等宽键，列数可配。
 *
 * 选中态用 `secondaryContainer` 底 + 主色字：修饰键选中要一眼看得出来，
 * 否则用户不知道"现在按的字母会带上 Ctrl"。
 */
@Composable
private fun KeyPad(
    rows: List<List<TerminalKey>>,
    columns: Int,
    modifiers: Set<TerminalKey>,
    onPress: (TerminalKey) -> Unit,
    onLongPressEmpty: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // 最下面一排键往上抬一点（D417）：全面屏四角是圆角，贴着底边会被切掉一角。
            .padding(bottom = 6.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressEmpty() }) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (index in 0 until columns) {
                    val key = row.getOrNull(index)
                    Box(modifier = Modifier.weight(1f)) {
                        if (key != null) {
                            KeyCell(
                                key = key,
                                selected = modifiers.contains(key),
                                onClick = { onPress(key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(key: TerminalKey, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TerminalRow(line: TerminalLine) {
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    when (line) {
        is TerminalLine.Prompt -> Text(
            text = "── ${line.label} · ${line.cwd}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is TerminalLine.Command -> Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(text = "$ ", style = mono, color = MaterialTheme.colorScheme.primary)
            Text(text = line.text, style = mono, color = MaterialTheme.colorScheme.onSurface)
        }

        is TerminalLine.Output -> Text(
            text = line.text,
            style = mono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 1.dp),
        )

        is TerminalLine.Error -> Text(
            text = line.text,
            style = mono,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 1.dp),
        )

        is TerminalLine.Notice -> Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/** 终端里的一行。四种：提示符 / 命令回显 / 输出 / 错误或说明。 */
internal sealed interface TerminalLine {
    data class Prompt(val label: String, val cwd: String) : TerminalLine
    data class Command(val text: String) : TerminalLine
    data class Output(val text: String) : TerminalLine
    data class Error(val text: String) : TerminalLine
    data class Notice(val text: String) : TerminalLine

    fun plain(): String = when (this) {
        is Prompt -> "── $label · $cwd"
        is Command -> "$ $text"
        is Output -> text
        is Error -> text
        is Notice -> text
    }
}
