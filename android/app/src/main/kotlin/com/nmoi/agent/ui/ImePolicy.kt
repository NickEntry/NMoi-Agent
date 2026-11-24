package com.nmoi.agent.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 离开应用（切后台 / 手势退到桌面 / 被别的界面盖住）时把输入焦点放掉。
 *
 * 不这么做的话，回来那一刻系统会按"上一帧还有焦点"把输入法重新弹起来——
 * 用户什么都没点，键盘先自己冒出来，盖住半个屏。焦点是应用自己的状态，
 * 系统只负责照它恢复。
 */
@Composable
internal fun BlurOnLeaveApp(blur: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) blur()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * 输入法从"开着"变成"关着"时回调一次。
 *
 * 键盘被用户按返回键收起来之后，输入框**还留着焦点**：下一次随便点哪儿、
 * 或者切回来，它会立刻又弹一次。收键盘的意图是"我现在不想打字"，焦点该跟着一起掉。
 *
 * 只在"之前确实看见过键盘"时才回调：首次组合时 insets 是 0，
 * 那时候当成"刚关上"会白白抢一次焦点。
 */
@Composable
internal fun OnImeHidden(onHidden: () -> Unit) {
    val density = LocalDensity.current
    val visible = WindowInsets.ime.getBottom(density) > 0
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            seen = true
        } else if (seen) {
            seen = false
            onHidden()
        }
    }
}
