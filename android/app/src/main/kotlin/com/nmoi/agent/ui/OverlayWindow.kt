package com.nmoi.agent.ui

import android.graphics.Color
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.nmoi.agent.ui.theme.dimBackground
import com.nmoi.agent.ui.theme.isLight

/**
 * 贴底浮层再往上抬的一点（D422）。
 *
 * 全面屏的四个角是圆角，紧贴屏幕底边画的那一行字会被圆角切掉一角。
 * 光靠 `navigationBars` 不够：手势导航下那一段只有二十来 dp，
 * 而圆角半径本身就有二三十 dp；三段键盘导航的机器上又反过来偏大。
 * 所以**在 insets 之外再加一个固定值**，两头都不赌。
 */
internal val BOTTOM_CORNER_LIFT = 12.dp

/**
 * 浮层的遮罩浓度（D420）。底部面板、居中确认卡、抽屉里那几张卡共用这一个数。
 */
internal const val OVERLAY_SCRIM = 0.32f

/**
 * 一个以**窗口**为基准的浮层（D413 / D420）。
 *
 * 为什么用 `Dialog` 而不是就地铺一层 `Box`：就地铺的话宽度跟着父布局走，
 * 侧边栏里长出来的菜单会被卡在侧边栏那么宽（D413 那张截图只有半屏）。
 *
 * # 遮罩只能有**一层**
 *
 * 真机上量过：底部面板打开时，状态栏那一条是 `#63`、页面是 `#43`。两个值都能对上账——
 *
 * - 状态栏：窗口背景 `#F7F7FA` 被**系统那层 dim（0.6）**压一次 → `#63`；
 * - 页面：同一份底色先被 dim 压、再被面板里自画的那层 0.32 压 → `#43`。
 *
 * 也就是说：**系统 dim 盖到了状态栏，自画的那层没盖到**（对话框窗口的坐标从状态栏
 * 下面才开始）。两层叠着，就必然"对不上"。
 *
 * 所以这里不再自己画：遮罩交给窗口的 dim，它本来就是整屏的，状态栏与页面同色。
 * 自己画的那层只留一个透明的点击区，负责"点外面关掉"。
 *
 * [onDismiss] 为 `null` 表示这一层**必须处理才能继续**：点外面不关，返回键也不关
 * （调用方自己决定给不给出口）。
 */
@Composable
internal fun OverlayWindow(
    onDismiss: (() -> Unit)?,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()
    val bottomAligned = contentAlignment == Alignment.BottomCenter
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = onDismiss != null,
        ),
    ) {
        // 窗口参数只能拿到真窗口再改：`Dialog` 的 composition 里 LocalView 就是
        // `DialogLayout`，它自己就是 `DialogWindowProvider`。
        LaunchedEffect(view, background) {
            val provider = (view as? DialogWindowProvider)
                ?: (view.parent as? DialogWindowProvider)
            val window = provider?.window ?: return@LaunchedEffect
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 浓度与抽屉那层分开：面板是"临时的一层"，比抽屉淡。
            window.setDimAmount(OVERLAY_SCRIM)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            // 状态栏图标深浅要在这里再设一次：浮层是**另一个窗口**，
            // 系统按最上面那个窗口的请求画图标。不设就沿用对话框主题的默认值——
            // 浅色主题下那一条会变成白图标压在浅灰上。
            val dimmed = dimBackground(background, OVERLAY_SCRIM)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = isLight(dimmed)
            controller.isAppearanceLightNavigationBars = isLight(dimmed)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            // 只负责"点外面关掉"：底色是窗口的 dim，这里必须透明。
            if (onDismiss != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 让位分两种（D422）：
                    //
                    // - **贴底**：导航栏与键盘取并集（不是相加），再加一个固定值
                    //   [BOTTOM_CORNER_LIFT]——圆角会把贴着底边的字切掉一角，
                    //   而手势导航那一段只有二十来 dp，不够。
                    // - **居中**：只让键盘。居中的卡片本来就不挨着底边。
                    //
                    // 加在这一层而不是各个面板里：面板有二十几个，漏一个就是一处被切。
                    .then(
                        if (bottomAligned) {
                            Modifier
                                .windowInsetsPadding(
                                    WindowInsets.navigationBars.union(WindowInsets.ime),
                                )
                                .padding(bottom = BOTTOM_CORNER_LIFT)
                        } else {
                            Modifier.imePadding()
                        },
                    ),
                contentAlignment = contentAlignment,
            ) {
                content()
            }
        }
    }
}
