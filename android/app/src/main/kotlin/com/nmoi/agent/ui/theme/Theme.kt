package com.nmoi.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// 暂时只给深色一套：浅色主题要跟图标包的 backing: surface 规则一起调（D105），
// 现在给一半会得到"某些图标该垫底却没垫"的样子，不如先不给。
private val DarkColors = darkColorScheme()

@Composable
fun NMoiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
