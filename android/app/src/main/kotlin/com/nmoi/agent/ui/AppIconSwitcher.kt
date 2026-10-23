package com.nmoi.agent.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** 用户选中的图标组合。就是设计 × 底色这两维，没有第三个自由度。 */
internal data class IconChoice(val design: IconDesign, val plate: IconPlate)

/**
 * 启动器图标切换。
 *
 * Android 换图标没有运行期 API 可走，唯一干净的路子是**多个 `activity-alias`**：
 * 每个组合一个 alias、各自带自己的 `android:icon`，靠
 * `PackageManager.setComponentEnabledSetting` 一次只开一个。
 *
 * 几条不能省的细节：
 * - **先开新的、再关旧的**。反过来的话，中间那一瞬间桌面上一个图标都没有，
 *   用户会以为应用被卸载了。
 * - **把所有的别的都关掉**，不是只关"上一个"。状态可能是从旧版本带来的
 *   （比如上次崩在中间），只关上一个会留下两个图标。
 * - 一律 `DONT_KILL_APP`，否则切个图标把当前会话杀掉，正在跑的轮次就没了。
 * - **落盘和 manifest 的默认值必须一致**：manifest 里默认开的是
 *   `TerminalStars + Black`（`DEFAULT_*`），这里读不到偏好时也返回它，
 *   两边不一致会出现"选中的高亮跟桌面上的图标不是一个"。
 *
 * 目前存在 SharedPreferences；设置面板接上 settings 表（D86）之后整体搬过去，
 * 那时键名保持不变即可。
 */
internal object AppIconSwitcher {
    private const val PREFS = "app_icon"
    private const val KEY_DESIGN = "design"
    private const val KEY_PLATE = "plate"

    val default = IconChoice(DEFAULT_DESIGN, DEFAULT_PLATE)

    fun load(context: Context): IconChoice {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val design = prefs.getString(KEY_DESIGN, null)
            ?.let { name -> IconDesign.entries.firstOrNull { it.name == name } }
            ?: default.design
        val plate = prefs.getString(KEY_PLATE, null)
            ?.let { name -> IconPlate.entries.firstOrNull { it.name == name } }
            ?: default.plate
        return IconChoice(design, plate)
    }

    /**
     * 切到 [choice]：开它、关掉其余、记住。
     *
     * 幂等——重复调用同一组不会产生副作用，所以可以在每次进设置页时都调一次，
     * 顺便修掉"上次崩在中间"留下的多个图标。
     */
    fun apply(context: Context, choice: IconChoice): Boolean {
        val packageManager = context.packageManager
        val wanted = component(context, choice.design.aliasFor(choice.plate))

        // 先确认这个 alias 真的在包里。名字算错（比如包名大小写、namespace/applicationId 混用）
        // 时 setComponentEnabledSetting 会抛 IllegalArgumentException，
        // 而它是在点击回调里被调用的——那就是一次闪退。真机上踩过，所以这里必须先查。
        if (!exists(packageManager, wanted)) {
            NLog.e(
                TAG,
                "图标 alias 查不到：${wanted.flattenToString()}（包 ${context.packageName}）。" +
                    "已带 MATCH_DISABLED_COMPONENTS，所以这是真的没有这个组件——" +
                    "类名要按 namespace 解析，不是 applicationId，见 docs/ui/icons/app/README.md",
            )
            return false
        }

        // 先开新的：反过来的话，中间那一瞬桌面上一个图标都没有。
        if (!runCatching {
                packageManager.setComponentEnabledSetting(
                    wanted,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.isSuccess
        ) {
            NLog.e(TAG, "启用 ${wanted.className} 失败，保持原状")
            return false
        }

        for (alias in ALL_ICON_ALIASES) {
            if (alias == wanted.className) continue
            val component = component(context, alias)
            // 关别人的时候更要小心：一个不存在的名字会让整个循环抛出去。
            if (!exists(packageManager, component)) continue
            runCatching {
                packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { NLog.w(TAG, "禁用 ${component.className} 失败：${it.message}") }
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DESIGN, choice.design.name)
            .putString(KEY_PLATE, choice.plate.name)
            .apply()

        // 成功也要留痕：以前只有失败才打日志，结果"点了没生效"和"生效了但桌面没刷新"
        // 在 logcat 里长得一模一样，只能靠眼睛分辨。
        NLog.i(TAG, "图标已切到 ${choice.design.name}/${choice.plate.name} → ${wanted.className}")
        return true
    }

    private fun component(context: Context, className: String) =
        ComponentName(context.packageName, className)

    private fun exists(packageManager: PackageManager, component: ComponentName): Boolean =
        runCatching {
            // 必须带 MATCH_DISABLED_COMPONENTS：8 个 alias 里永远只有 1 个是 enabled，
            // 不带这个 flag 时 getActivityInfo 对**被禁用**的组件抛 NameNotFoundException，
            // 于是"存在性检查"永远返回 false，切换一次都走不出去（2026-10-23 真机卡在这里）。
            packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.isSuccess

    private const val TAG = "NMoiIcon"
}
