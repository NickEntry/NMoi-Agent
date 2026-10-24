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
    // M3 的容器色阶（D126 用到的就是这几档）：助手气泡 `surfaceContainerLow`、
    // 思考块与工具卡 `surfaceContainer`、输入区 `surfaceContainerHigh`。
    // 不写死颜色，模板里就只剩"抬一档"这个说法；而 `darkColorScheme` 的默认值是从
    // 基线紫算出来的，和我们的灰紫背景不在一套里——所以必须在这里给值。
    surfaceContainerLowest = Color(0xFF0C0C10),
    surfaceContainerLow = Color(0xFF17171D),
    surfaceContainer = Color(0xFF1C1C23),
    surfaceContainerHigh = Color(0xFF22222A),
    surfaceContainerHighest = Color(0xFF282831),
    outlineVariant = Color(0xFF33333D),
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
    // 浅色这一侧同理：气泡比背景深半档，输入区再深一点；不是"把深色反过来"。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F1F6),
    surfaceContainer = Color(0xFFEAEAF0),
    surfaceContainerHigh = Color(0xFFE4E4EB),
    surfaceContainerHighest = Color(0xFFDDDDE5),
    outlineVariant = Color(0xFFDCDCE3),
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
