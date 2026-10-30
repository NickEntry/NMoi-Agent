package com.nmoi.agent.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 单框编辑器用的输入区（0.15g）：`AndroidView` 包一层**原生 `EditText`**。
 *
 * 为什么不用 `BasicTextField`：设计稿 §2.3 那条（D179 也引了）——Compose 那套在长文本上
 * 的 IME 镜像要自己算，几千字的文档一滚就露馅（光标位置、选区、输入法弹窗位置各算一套）。
 * 原生 `EditText` 这块是用了十几年的，别重写。
 *
 * 三条容易写错的：
 *
 * 1. **`setText` 只在外部值真的不同时才调**。每次重组都 `setText` 会把光标顶到末尾，
 *    用户打的字会跑到别处去——这类"按钮没问题、打字乱跳"最难查。
 * 2. `afterTextChanged` 里把值抛出去，但**不在这里 setText**：那会自己触自己。
 * 3. 报错时要能**滚到出事那一行**：给行号，算偏移，`setSelection` 之后 EditText 自己会滚。
 */
@Composable
internal fun MessageEditField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 要跳过去的那一行（1 起）。改它会滚过去并把光标放在行首。 */
    revealLine: Int? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    // 记住这个 EditText 实例：滚行、取布局都要它（`AndroidView` 的 update 里拿不到工厂那个）。
    val holder = remember { arrayOfNulls<EditText>(1) }
    // 只跟 `revealLine` 变：把 `value` 也放进 key，等于每敲一个字就把光标拽回去一次。
    val latest = rememberUpdatedState(value)

    LaunchedEffect(revealLine) {
        val line = revealLine ?: return@LaunchedEffect
        val edit = holder[0] ?: return@LaunchedEffect
        val offset = offsetOfLine(latest.value, line)
        edit.requestFocus()
        edit.setSelection(offset.coerceIn(0, edit.text.length))
    }

    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            factory = { context ->
                EditText(context).apply {
                    holder[0] = this
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    gravity = Gravity.TOP or Gravity.START
                    isSingleLine = false
                    isVerticalScrollBarEnabled = true
                    // 长文本不要"全屏编辑"（提取模式）：那不是我们想给的东西，且会丢滚动位置。
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setTextColor(colors.onSurface.toArgb())
                    setHintTextColor(colors.onSurfaceVariant.toArgb())
                    hint = "这一条消息的全部块都在这里"
                    setPadding(28, 24, 28, 24)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            onValueChange(s?.toString().orEmpty())
                        }
                    })
                }
            },
            update = { edit ->
                // 只在真的不同才写回去（同一条的值相等时什么都不做，光标才不会跳）。
                if (edit.text.toString() != value) {
                    val start = edit.selectionStart.coerceAtLeast(0)
                    edit.setText(value)
                    edit.setSelection(start.coerceIn(0, edit.text.length))
                }
                edit.isEnabled = enabled
            },
        )
    }
}

/** 第 [line] 行（1 起）在文本里的字符偏移；越界就落到末尾。 */
private fun offsetOfLine(text: String, line: Int): Int {
    if (line <= 1) return 0
    var remaining = line - 1
    var index = 0
    while (index < text.length && remaining > 0) {
        if (text[index] == '\n') remaining--
        index++
    }
    return index.coerceAtMost(text.length)
}
