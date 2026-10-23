package com.nmoi.agent.ui

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 聊天页（PLAN §3.12）：顶栏 + 消息区 + 输入岛，会话列表收进左侧抽屉。
 *
 * 消息区现在**读真数据**了（`load_units` → `ChatRepository`）。渲染的规矩只有一条：
 * **按 core 给的词渲染**——`source` 是 `model_thinking` 就折成思考块，是 `tool_call` /
 * `tool_result` 就进工具卡，`broken` 有值就显示声明行。不在客户端重新判断"什么算思考"。
 *
 * 返回键的两层：抽屉开着时返回先关抽屉（本页注册得更晚，Compose 的 `BackHandler` 后注册者优先）。
 */
@Composable
internal fun ChatScreen(
    title: String,
    sessionId: String?,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 存完一条要重读：`produceState` 只有 key 变了才会重跑，所以用它当"刷新一下"。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val databasePath = remember { File(context.filesDir, "nmoi.db").absolutePath }

    // 读单元要开库、读盘，不许在主线程上做。
    val snapshot by produceState<ChatSnapshot?>(initialValue = null, sessionId, reloadKey) {
        value = if (sessionId == null) {
            ChatSnapshot(
                page = null,
                source = RowSource.Placeholder,
                note = "这一行是占位数据，没有会话 id 可读",
            )
        } else {
            withContext(Dispatchers.IO) {
                ChatRepository.load(File(context.filesDir, "nmoi.db").absolutePath, sessionId)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                )
                // 抽屉入口：图标按钮，48dp 热区 / 22dp 视觉（D103）。
                MenuButton(onClick = onOpenDrawer)
            }

            MessageArea(snapshot = snapshot, modifier = Modifier.weight(1f))
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                canSave = sessionId != null,
                onSaveUser = {
                    val text = draft.trim()
                    val sid = sessionId
                    when {
                        text.isEmpty() -> notice = "先写点什么"
                        sid == null -> notice = "这一行是占位会话，没有 id，存不进去"
                        else -> scope.launch {
                            val result = ChatRepository.append(databasePath, sid, "user", text)
                            result.fold(
                                onSuccess = {
                                    draft = ""
                                    reloadKey += 1
                                    notice = "已存为用户消息（真正发给模型等引擎接线）"
                                },
                                onFailure = { notice = "存失败：${it.message}" },
                            )
                        }
                    }
                },
                onSaveAssistant = {
                    val text = draft.trim()
                    val sid = sessionId
                    when {
                        text.isEmpty() -> notice = "先写点什么"
                        sid == null -> notice = "这一行是占位会话，没有 id，存不进去"
                        else -> scope.launch {
                            // 存成 assistant 会让历史变成"以助手结尾"——那是用户要的语义，
                            // 别在 UI 层"顺手修正"成 user（PLAN §3.12）。
                            val result = ChatRepository.append(databasePath, sid, "assistant", text)
                            result.fold(
                                onSuccess = {
                                    draft = ""
                                    reloadKey += 1
                                    notice = "已存为助手消息"
                                },
                                onFailure = { notice = "存失败：${it.message}" },
                            )
                        }
                    }
                },
                notice = notice,
            )
        }
    }
}

/**
 * 消息区。
 *
 * 三种状态必须说清是哪一种，不能都是"一片空"：
 * ① 读不出来（`page == null`）——附原因；② 读到了但一条没有——"这个会话还空着"；
 * ③ 有内容——按单元渲染，顶部说明"上面还有更早的"。
 */
@Composable
private fun MessageArea(snapshot: ChatSnapshot?, modifier: Modifier = Modifier) {
    val page = snapshot?.page
    if (page == null || page.isEmpty) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (page == null) "这一段历史还没被读出来" else "这个会话还空着",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val note = snapshot?.note
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

    // 打开会话应当看到最新的一条，而不是最早的一条。
    LaunchedEffect(page.units.size, page.truncated) {
        val target = headerCount + page.units.lastIndex
        if (target >= 0) listState.scrollToItem(target)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
        items(page.units) { unit -> UnitBlock(unit) }
    }
}

/** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 */
@Composable
private fun UnitBlock(unit: ChatUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val broken = unit.broken
        if (broken != null) {
            BrokenCard(declaration = broken.declaration, reason = broken.reason)
        }
        if (unit.isToolPair) {
            ToolPairCard(unit)
        } else {
            unit.messages.forEach { message -> MessageRow(message) }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when {
        message.isThinking -> ThinkingBlock(message)
        message.isUser -> Bubble(text = message.content, mine = true, incomplete = message.incomplete)
        else -> Bubble(text = message.content, mine = false, incomplete = message.incomplete)
    }
}

/**
 * 说话气泡。用户靠右、助手靠左——这是聊天界面最不需要解释的一条。
 *
 * 助手消息**不铺满整行**（`fillMaxWidth(0.98f)` 只是防止超长行贴边），
 * 长文本靠换行，不做"卡片里再套卡片"。
 */
@Composable
private fun Bubble(text: String, mine: Boolean, incomplete: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(if (mine) 16.dp else 12.dp),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(if (mine) 0.86f else 0.98f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    text = text.ifEmpty { "（空消息）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (incomplete) {
                    // 「未完成」不是错误：这一轮被中断了，界面要说清，别让它看着像正常回复。
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
}

/**
 * 思考块：**默认收起**，只显示"思考 · N 字"。展开看全文。
 *
 * 收起是刻意的：思考通常很长，默认铺开会把真正的回答挤到屏幕外。
 */
@Composable
private fun ThinkingBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = if (expanded) {
                    "思考（点一下收起）"
                } else {
                    "思考 · ${message.content.length} 字（点一下展开）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
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

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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
            )
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

/** 断块（P4.1 / D67）：声明行由 core 给，UI 不自己拼。 */
@Composable
private fun BrokenCard(declaration: String, reason: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(0.98f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
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
 * 输入岛。**现在能做的是"存入"，不是"发送"**：`nmoi_turn_run_json` 还没接，
 * 所以按钮上写"存入"，下面一行小字说清区别——写"发送"然后不发送才是骗人。
 *
 * 点 = 存为用户消息；长按 = 存为助手消息。这条长按菜单是 PLAN §3.12 的形状，
 * 好处是"想法先记下来、晚点再发"。
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSave: Boolean,
    onSaveUser: () -> Unit,
    onSaveAssistant: () -> Unit,
    notice: String?,
) {
    Column {
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
        }
        val shape = RoundedCornerShape(22.dp)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                NativeEditText(
                    value = draft,
                    onValueChange = onDraftChange,
                    hint = "说点什么…（点存入记下来，长按存为助手消息）",
                    modifier = Modifier.weight(1f),
                )
                // 加号：附件管线未接（P7.7），所以禁用而不是隐藏——
                // 位置先占住，用户看到的是"这里以后能加东西"，而不是"没有这个功能"。
                TextButton(onClick = {}, enabled = false) { Text("＋") }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${draft.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SaveButton(
                        enabled = canSave && draft.isNotBlank(),
                        onClick = onSaveUser,
                        onLongPress = onSaveAssistant,
                    )
                }
            }
        }
    }
}

/** 存入按钮：点 = 存为用户消息，长按 = 存为助手消息（手势不用 `combinedClickable`，那段还是 Experimental）。 */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = background,
        modifier = Modifier.pointerInput(enabled) {
            if (enabled) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
            }
        },
    ) {
        Text(
            text = "存入",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
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
) {
    // 文字色跟着主题走：写死深色主题的两支颜色，换到浅色就是"浅灰字压白底"，读不出来。
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier.heightIn(min = 48.dp),
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
                setPadding(20, 26, 8, 26)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 光标跟着主题走；不设的话某些 ROM 上是纯黑，深色底看不见。
                    textCursorDrawable = null
                }
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
