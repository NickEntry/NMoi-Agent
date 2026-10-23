package com.nmoi.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 动作图标：直接抄 `docs/ui/icons/actions/` 下 SVG 里的 path 数据（Lucide 1.61.0，ISC），
 * 用 `PathParser` 解析成 `ImageVector`。
 *
 * 为什么不引 `material-icons-extended`：为了一个齿轮引一整个包，debug 包会大一大截，
 * 而这份 SVG 本来就在仓库里、许可也在 `LICENSES/` 里。也不手画：手画的齿轮跟
 * `docs/ui/icons/` 里那套描边宽度、圆角对不上，界面上会一眼看出是"另一个人画的"。
 *
 * 描边参数照 `docs/ui/icons/README.md` §1：画布 24×24、描边 1.75、round cap/join、不填色。
 * 颜色由 `Icon(tint = …)` 统一上色，这里固定写黑——**不要**把主题色写死在图标里。
 */
internal object ActionIcons {
    /** Lucide `settings.svg`（`docs/ui/icons/actions/settings.svg`）。 */
    val Settings: ImageVector = buildVector("settings") {
        stroke(
            // 齿轮外圈：一圈由 8 段等距圆弧拼出来的闭环。
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 " +
                "2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 " +
                "2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 " +
                "2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 " +
                "2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831 " +
                "2.34 2.34 0 0 1 2.33-4.033 2.34 2.34 0 0 0 3.319-1.915",
        )
        // SVG 里的 <circle cx="12" cy="12" r="3"> 写成两段半圆弧（PathParser 认 a 命令）。
        stroke("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
    }
}

private fun buildVector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private fun ImageVector.Builder.stroke(path: String) {
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}
