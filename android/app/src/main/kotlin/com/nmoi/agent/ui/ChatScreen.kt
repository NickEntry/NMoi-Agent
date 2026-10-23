package com.nmoi.agent.ui

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 聊天页（PLAN §3.12 第一版）：顶栏 + 消息区 + 输入岛，会话列表收进左侧抽屉。
 *
 * 顶栏顺序：返回箭头 → 标题 → 菜单（打开抽屉）。返回和抽屉是两件事：
 * 返回是"退出这一页回列表"，抽屉是"就地换一个会话"，所以两个都留着。
 *
 * 返回键的两层：这一页里抽屉开着时，返回先关抽屉（注册在本页，
 * 比 MainActivity 那个后注册，Compose 的 BackHandler 是后注册者优先）。
 */
@Composable
internal fun ChatScreen(
    title: String,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

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

                MessageArea(modifier = Modifier.weight(1f))
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { /* 未接线：nmoi_turn_run_json 还没接进 Kotlin 侧 */ },
                    sendEnabled = false,
                )
            }
    }
}

/**
 * 消息区。现在只有空状态——库里的消息单元还没接线（`load_units`）。
 *
 * 空状态必须说清"为什么是空的"：这里不是"没有对话"，是"读不出来"。
 * 一片黑和一句"还没有消息"是两种完全不同的信息。
 */
@Composable
private fun MessageArea(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "这一段历史还没被读出来",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "消息区要等 load_units 接线（PLAN 0.16）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
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
                hint = "说点什么…（发送要等引擎接上）",
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
                TextButton(onClick = onSend, enabled = sendEnabled) {
                    Text(if (sendEnabled) "发送" else "待接")
                }
            }
        }
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
