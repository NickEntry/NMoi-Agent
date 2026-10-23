package com.nmoi.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 两套主题（D120）。**组件里不写死颜色**，一律取 `MaterialTheme.colorScheme.*`，
 * 否则切到另一套就会出现"背景变了、字还是那个色"。
 *
 * 深色不是把浅色调暗：背景取很暗的灰紫、卡片比背景抬一档、正文**不用纯白**——
 * OLED 上纯白正文会发光晕，夜里看着刺。
 *
 * 浅色也不是把深色反过来：正文用近黑而不是纯黑，背景留一点灰，避免白底刺眼与
 * 卡片/背景分不出来。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF101014),
    primaryContainer = Color(0xFF2A3A55),
    onPrimaryContainer = Color(0xFFD7E3FF),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF14141A),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF1E1E26),
    onSurfaceVariant = Color(0xFF9AA0A6),
    inverseSurface = Color(0xFFE6E6EA),
    inverseOnSurface = Color(0xFF1A1A20),
    error = Color(0xFFF2B8B5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BD0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001945),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF1A1A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1F),
    surfaceVariant = Color(0xFFEFEFF4),
    onSurfaceVariant = Color(0xFF5A5F66),
    inverseSurface = Color(0xFF2E2E33),
    inverseOnSurface = Color(0xFFF4F4F8),
    error = Color(0xFFB3261E),
)

/**
 * 主题入口。[dark] 由 `ThemeStore.resolve` 算出（跟随系统 / 强制深 / 强制浅）。
 *
 * 还没有 `dynamicColor`：动态取色（Material You）会让"自定义主题"那一行彻底没意义，
 * 等自定义主题真做了再谈要不要叠一层。
 */
@Composable
fun NMoiTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
