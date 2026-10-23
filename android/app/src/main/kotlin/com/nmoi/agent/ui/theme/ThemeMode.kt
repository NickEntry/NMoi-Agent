package com.nmoi.agent.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 主题模式（D120）：**三态**，不是两态。
 *
 * 只给"深 / 浅"的话，手机日落自动切深浅的人每天都得来手动点一次；
 * 所以默认是**跟随系统**，另外两态是覆盖。
 */
internal enum class ThemeMode(val id: String, val label: String) {
    System("system", "跟随系统"),
    Dark("dark", "深色"),
    Light("light", "浅色"),
}

/**
 * 主题模式的存储。
 *
 * 用 SharedPreferences 而不是 settings 表（D86）：主题要在**第一帧之前**就确定，
 * 而 settings 表要开库、要线程——等它读出来，第一帧已经用错颜色画完了。
 */
internal object ThemeStore {
    private const val PREFS = "app_theme"
    private const val KEY_MODE = "mode"

    fun load(context: Context): ThemeMode {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return ThemeMode.entries.firstOrNull { it.id == id } ?: ThemeMode.System
    }

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.id).apply()
    }

    /**
     * 真正决定用哪套色。
     *
     * **跟随系统**必须走 `isSystemInDarkTheme()`——它会跟着系统配置变化重组；
     * 在启动时读一次 `uiMode` 存起来的话，用户切了系统主题得杀进程才生效。
     */
    /**
     * 非 Composable 版本：给 `onCreate` 里设窗口底色用（那时候还没有组合）。
     * 两处判断必须同一套规矩，否则会出现"第一帧深、界面浅"的闪。
     */
    fun isDarkNow(context: Context): Boolean = when (load(context)) {
        ThemeMode.System -> {
            val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mask == Configuration.UI_MODE_NIGHT_YES
        }
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    @Composable
    fun isDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
}
