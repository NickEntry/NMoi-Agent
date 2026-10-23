package com.nmoi.agent.ui

// 这个文件在 ui 子包里，R 在 com.nmoi.agent：不写全就等于 Unresolved reference 'R'（CI 抓过）。
import com.nmoi.agent.R
import androidx.annotation.DrawableRes

// 由 scripts/gen_app_icons.py 生成，别手改：设计、资源名、alias 名是同一份来源。
// 加一个设计只需要改脚本里的 DESIGNS，然后重跑，资源与这份表一起出来。

internal enum class IconDesign(val label: String, val slug: String) {
    WishingStar("许愿星 · 环轨", "c2"),
    TerminalStars("许愿终端 · 星群", "c4"),
    InfinityRing("无限环", "c5"),
    CodeStardust("代码星屑", "c6"),
}

internal enum class IconPlate(val label: String, val slug: String) {
    Black("黑色", "dark"),
    White("白色", "light"),
}

/** 默认图标：4 号（许愿终端 · 星群）+ 黑底，用户没选过就是这个。 */
internal val DEFAULT_DESIGN = IconDesign.TerminalStars
internal val DEFAULT_PLATE = IconPlate.Black

/**
 * 前景层资源。Compose 里画预览也用它——预览不是另画一套，
 * 用的是进包的同两张图（前景叠在底色上），所见即所装。
 */
@DrawableRes
internal fun IconDesign.foregroundFor(plate: IconPlate): Int = when (this) {
    IconDesign.WishingStar -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c2_dark else R.drawable.fg_appicon_c2_light
    IconDesign.TerminalStars -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c4_dark else R.drawable.fg_appicon_c4_light
    IconDesign.InfinityRing -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c5_dark else R.drawable.fg_appicon_c5_light
    IconDesign.CodeStardust -> if (plate == IconPlate.Black) R.drawable.fg_appicon_c6_dark else R.drawable.fg_appicon_c6_light
}

/** activity-alias 的全名，manifest 里 android:name 必须跟它一字不差（有脚本对账）。 */
internal fun IconDesign.aliasFor(plate: IconPlate): String =
    "com.nmoi.agent.icon.${name}${if (plate == IconPlate.Black) "Dark" else "Light"}"

internal val ALL_ICON_ALIASES: List<String> =
    IconDesign.entries.flatMap { design -> IconPlate.entries.map { design.aliasFor(it) } }
