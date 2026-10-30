package com.nmoi.agent.ui

import android.content.Context

/**
 * 应用级设置（0.15m）：不是某个模型、也不是某个助手的开关都放这儿。
 *
 * 为什么用 SharedPreferences 而不是库里的 `settings` 表：这两项都是"装在这台手机上的
 * 偏好"，跟着库一起导出没有意义（换个设备，压缩窗口该按那台设备的模型重算）。
 */
internal object AppSettings {
    private const val FILE = "nmoi_app_settings"
    private const val KEY_ALLOW_NEW_VERSION = "allow_new_version"
    private const val KEY_DEFAULT_WINDOW = "default_context_window"

    /**
     * 模型没填上下文窗口时用的参考值。
     *
     * 128k 是现在主流聊天模型的常见窗口。用户要求"没填也要算"——所以它会在压缩判据里
     * 当分母用；界面上也会说明"这个数是按默认值算的"，免得用户以为模型报了 128k。
     */
    const val DEFAULT_WINDOW = 128_000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 重试时结果落成新版本（`true`，默认）还是覆盖当前版（`false`）——D185。 */
    fun allowNewVersion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_NEW_VERSION, true)

    fun setAllowNewVersion(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_NEW_VERSION, value).apply()
    }

    /** 压缩判据的默认窗口。**保证 > 0**：分母为 0 的判据比没有判据更糟。 */
    fun defaultContextWindow(context: Context): Int {
        val value = prefs(context).getInt(KEY_DEFAULT_WINDOW, DEFAULT_WINDOW)
        return if (value > 0) value else DEFAULT_WINDOW
    }

    fun setDefaultContextWindow(context: Context, value: Int) {
        if (value > 0) {
            prefs(context).edit().putInt(KEY_DEFAULT_WINDOW, value).apply()
        }
    }

    /** 一行摘要，给设置索引页那一行用。 */
    fun summary(context: Context): String =
        "默认窗口 ${defaultContextWindow(context)} · 新版本" +
            if (allowNewVersion(context)) "开" else "关"
}