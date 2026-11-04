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
    private const val KEY_IMAGE_COMPRESS = "image.compress"
    private const val KEY_IMAGE_LONG = "image.long_edge"
    private const val KEY_IMAGE_SHORT = "image.short_edge"
    private const val KEY_IMAGE_QUALITY = "image.quality"
    private const val KEY_MAX_ROUNDS = "turn.max_rounds"
    private const val KEY_IMAGE_LIMIT = "context.image_limit"

    /**
     * 一轮里允许模型"调工具再开口"几次。**0 = 不限制**（D245，默认就是 0）。
     *
     * 一个 agent 该能在一句话里调任意多次工具，只要上下文还装得下——真正的边界是
     * 绝对线（上下文）、用户取消、上游自己的限制。硬性轮数是早期拍的安全阀，
     * 撞上会把话截断。想设闸的人可以显式填个数，到顶后还会再给一轮不带工具的收尾。
     */
    const val DEFAULT_MAX_ROUNDS = 0
    const val MIN_MAX_ROUNDS = 0
    const val MAX_MAX_ROUNDS = 10_000

    /** 图片压缩的出厂值（用户 2026-11-04 定）：开、长边 2048、短边 2048、质量 90%。 */
    const val DEFAULT_IMAGE_LONG = 2048
    const val DEFAULT_IMAGE_SHORT = 2048
    const val DEFAULT_IMAGE_QUALITY = 90

    /**
     * 上下文里最多带几张图（D248）。
     *
     * 12 是用户定的默认值。上限取 **100**：三家端点里最弱的 Anthropic 就是 100 张/请求
     * （OpenAI 500、Gemini 3 600）——填得比它大，换一家端点就会 400。
     */
    const val DEFAULT_IMAGE_LIMIT = 12
    const val MAX_IMAGE_LIMIT = 100

    /** 这一轮最多带几张图。**保证在 1..100 之间**：0 张等于把图全关了，超出最弱端点就 400。 */
    fun imageLimit(context: Context): Int {
        val value = prefs(context).getInt(KEY_IMAGE_LIMIT, DEFAULT_IMAGE_LIMIT)
        return value.coerceIn(1, MAX_IMAGE_LIMIT)
    }

    fun setImageLimit(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_IMAGE_LIMIT, value.coerceIn(1, MAX_IMAGE_LIMIT)).apply()
    }

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

    /**
     * 发图前压不压、压到多大。**默认开**：手机拍的图动辄 4–8 MB，
     * 原样发出去既慢又贵（按 token 算钱的模型尤其），而 2048 长边对聊天够用。
     */
    fun imageCompress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IMAGE_COMPRESS, true)

    fun setImageCompress(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IMAGE_COMPRESS, value).apply()
    }

    /** 长边上限（像素）。**保证 > 0**：0 会让缩放算出一个空图。 */
    fun imageLongEdge(context: Context): Int {
        val value = prefs(context).getInt(KEY_IMAGE_LONG, DEFAULT_IMAGE_LONG)
        return if (value > 0) value else DEFAULT_IMAGE_LONG
    }

    fun setImageLongEdge(context: Context, value: Int) {
        if (value > 0) prefs(context).edit().putInt(KEY_IMAGE_LONG, value).apply()
    }

    fun imageShortEdge(context: Context): Int {
        val value = prefs(context).getInt(KEY_IMAGE_SHORT, DEFAULT_IMAGE_SHORT)
        return if (value > 0) value else DEFAULT_IMAGE_SHORT
    }

    fun setImageShortEdge(context: Context, value: Int) {
        if (value > 0) prefs(context).edit().putInt(KEY_IMAGE_SHORT, value).apply()
    }

    /** 质量（1–100）。夹住而不是报错：滑到 0 只是"看不清"，不该拦住发送。 */
    fun imageQuality(context: Context): Int =
        prefs(context).getInt(KEY_IMAGE_QUALITY, DEFAULT_IMAGE_QUALITY).coerceIn(1, 100)

    fun setImageQuality(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_IMAGE_QUALITY, value.coerceIn(1, 100)).apply()
    }

    /**
     * 工具轮数上限（D244）。**这是安全阀，不是产品决定**——防的是模型无限调工具。
     * 撞上会 `finished=false`，界面显示"没收尾"。所以默认给 32，并且允许用户调。
     */
    fun maxRounds(context: Context): Int =
        prefs(context).getInt(KEY_MAX_ROUNDS, DEFAULT_MAX_ROUNDS)
            .coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS)

    /** 界面上那一行怎么念：0 读作"不限制"。 */
    fun maxRoundsLabel(context: Context): String =
        maxRounds(context).let { if (it <= 0) "不限制" else "$it 轮" }

    fun setMaxRounds(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_MAX_ROUNDS, value.coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS))
            .apply()
    }

    /** 一行摘要，给设置索引页那一行用。 */
    fun summary(context: Context): String =
        "默认窗口 ${defaultContextWindow(context)} · 新版本" +
            if (allowNewVersion(context)) "开" else "关"
}