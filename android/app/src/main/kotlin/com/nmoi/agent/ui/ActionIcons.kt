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
 *
 * 一个坑（2026-10-24 踩过）：往这个文件追加图标时，别用"在最后一个 `}` 前插入"的写法——
 * 文件末尾的 `}` 是 `stroke()` 辅助函数的，插进去的 val 会变成函数里的局部变量，
 * 编译在调用点报 `Unresolved reference`。要么手工加到 object 里，要么整份重写。
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

    /** Lucide `plus.svg`：新建会话。 */
    val Plus: ImageVector = buildVector("plus") {
        stroke("M5 12h14")
        stroke("M12 5v14")
    }

    /** Lucide `arrow-up.svg`：输入岛的发送键（现在做的是"存进库里"，见 ChatScreen 的说明）。 */
    val ArrowUp: ImageVector = buildVector("arrow-up") {
        stroke("m5 12 7-7 7 7")
        stroke("M12 19V5")
    }

    /**
     * Lucide `copy.svg`：复制。
     *
     * 那个 `<rect x=8 y=8 width=14 height=14 rx=2>` 直接用路径写（`h`/`a`/`v`），
     * 不必为圆角矩形引入额外的图形 API。
     */
    val Copy: ImageVector = buildVector("copy") {
        stroke("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2Z")
        stroke("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    }

    /** Lucide `panel-left.svg`：抽屉入口（和其余动作图标同一套描边，不用 Material 的实心三横）。 */
    val PanelLeft: ImageVector = buildVector("panel-left") {
        stroke("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z")
        stroke("M9 3v18")
    }

    /** Lucide `rotate-ccw.svg`：重新生成（现在灰着：`turn_run` 还没接）。 */
    val RotateCcw: ImageVector = buildVector("rotate-ccw") {
        stroke("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
        stroke("M3 3v5h5")
    }

    /** Lucide `pencil.svg`：编辑这条（现在灰着：`edit` 还没桥出 FFI）。 */
    val Pencil: ImageVector = buildVector("pencil") {
        stroke(
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83" +
                "l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        )
        stroke("m15 5 4 4")
    }

    /** Lucide `ellipsis.svg`：更多。三个 `circle r=1` 写成三段小圆弧。 */
    val Ellipsis: ImageVector = buildVector("ellipsis") {
        stroke("M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0")
        stroke("M20 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0")
        stroke("M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0")
    }

    /** Lucide `trash-2.svg`：删除（现在灰着：`delete` 的 FFI 还没有）。 */
    val Trash2: ImageVector = buildVector("trash-2") {
        stroke("M10 11v6")
        stroke("M14 11v6")
        stroke("M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6")
        stroke("M3 6h18")
        stroke("M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }

    /** Lucide `check.svg`：选中态 / 复制成功。 */
    val Check: ImageVector = buildVector("check") {
        stroke("M20 6 9 17l-5-5")
    }

    /** Lucide `x.svg`：取消选择。 */
    val Close: ImageVector = buildVector("x") {
        stroke("M18 6 6 18")
        stroke("m6 6 12 12")
    }

    /** Lucide `chevron-down.svg`：折叠块的开合指示。 */
    val ChevronDown: ImageVector = buildVector("chevron-down") {
        stroke("m6 9 6 6 6-6")
    }

    /** Lucide `share-2.svg`：分享。三个 `circle r=3` + 两条连线。 */
    val Share2: ImageVector = buildVector("share-2") {
        stroke("M21 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
        stroke("M9 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
        stroke("M21 19a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
        stroke("M8.59 13.51l6.83 3.98")
        stroke("M15.41 6.51l-6.82 3.98")
    }

    /** Lucide `volume-2.svg`：文字转语音（现在灰着：TTS 还没接）。 */
    val Volume2: ImageVector = buildVector("volume-2") {
        stroke(
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6" +
                "a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        )
        stroke("M16 9a5 5 0 0 1 0 6")
        stroke("M19.364 18.364a9 9 0 0 0 0-12.728")
    }

    /** Lucide `languages.svg`：翻译（现在灰着：翻译还没接）。 */
    val Languages: ImageVector = buildVector("languages") {
        stroke("m5 8 6 6")
        stroke("m4 14 6-6 2-3")
        stroke("M2 5h12")
        stroke("M7 2h1")
        stroke("m22 22-5-10-5 10")
        stroke("M14 18h6")
    }

    /** Lucide `git-branch.svg`：创建分支（现在灰着：分支还没有数据模型）。 */
    val GitBranch: ImageVector = buildVector("git-branch") {
        stroke("M15 6a9 9 0 0 0-9 9V3")
        stroke("M21 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
        stroke("M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
    }
    /** Lucide `cpu.svg`：模型选择。 */
    val Cpu: ImageVector = buildVector("cpu") {
        stroke("M12 20v2")
        stroke("M12 2v2")
        stroke("M17 20v2")
        stroke("M17 2v2")
        stroke("M2 12h2")
        stroke("M2 17h2")
        stroke("M2 7h2")
        stroke("M20 12h2")
        stroke("M20 17h2")
        stroke("M20 7h2")
        stroke("M7 20v2")
        stroke("M7 2v2")
        stroke("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z")
        stroke("M9 8h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z")
    }

    /** Lucide `search.svg`：网络搜索。 */
    val Search: ImageVector = buildVector("search") {
        stroke("m21 21-4.34-4.34")
        stroke("M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0")
    }

    /** Lucide `gauge.svg`：思考强度（D37 的仪表盘语义）。 */
    val Gauge: ImageVector = buildVector("gauge") {
        stroke("m12 14 4-4")
        stroke("M3.34 19a10 10 0 1 1 17.32 0")
    }

    /** Lucide `wrench.svg`：工具开关。 */
    val Wrench: ImageVector = buildVector("wrench") {
        stroke(
            "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218" +
                "a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259" +
                "c.438.12.54.662.219.984z",
        )
    }

    /** Lucide `mic.svg`：语音识别。 */
    val Mic: ImageVector = buildVector("mic") {
        stroke("M12 19v3")
        stroke("M19 10v2a7 7 0 0 1-14 0v-2")
        stroke("M12 2a3 3 0 0 1 3 3v7a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3Z")
    }

    /** Lucide `square.svg`：生成中显示"停止"（同一按钮换形态，PLAN §3.12）。 */
    val Square: ImageVector = buildVector("square") {
        stroke("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z")
    }

    /**
     * 自绘：设置齿轮（8 齿 + 中心孔）。
     *
     * 为什么不直接用 Lucide 的 `settings`：那个是 6 段圆弧拼出来的"圆润花齿"，图上参考的也是这种，
     * 用户明确要求"设置图标自己画一版"。这个是按齿形算出来的（外半径 10 / 根半径 7.4 / 8 齿），
     * 保留同一套描边（1.75、圆角连接），和其余图标放在一起不会显突兀。
     */
    val GearSelf: ImageVector = buildVector("gear-self") {
        stroke(
            "M19.28 10.65 L21.88 10.44 L21.88 13.56 L19.28 13.35 L18.1 16.19 L20.09 17.88 " +
                "L17.88 20.09 L16.19 18.1 L13.35 19.28 L13.56 21.88 L10.44 21.88 L10.65 19.28 " +
                "L7.81 18.1 L6.12 20.09 L3.91 17.88 L5.9 16.19 L4.72 13.35 L2.12 13.56 " +
                "L2.12 10.44 L4.72 10.65 L5.9 7.81 L3.91 6.12 L6.12 3.91 L7.81 5.9 " +
                "L10.65 4.72 L10.44 2.12 L13.56 2.12 L13.35 4.72 L16.19 5.9 L17.88 3.91 " +
                "L20.09 6.12 L18.1 7.81 Z",
        )
        stroke("M15.1 12 a3.1 3.1 0 1 1 -6.2 0 a3.1 3.1 0 1 1 6.2 0")
    }

    /**
     * 自绘：细长尾巴的搜索图标（输入区用）。
     *
     * 用户嫌 Lucide `search` 的尾巴太短（原版约 6.1 单位），要求"聊天框这个放大到三倍"。
     * 24 的画布放不下严格的三倍（18 会顶出边界），所以把圆缩小到 r=4.2、尾巴拉到 11.5，
     * 视觉上是原来那个的两倍多——再长就该跑出画布了。
     * 尾巴方向：**从圆往左偏下 60°**。
     */
    val SearchInput: ImageVector = buildVector("search-input") {
        stroke("M19 8 a4.2 4.2 0 1 1 -8.4 0 a4.2 4.2 0 1 1 8.4 0")
        stroke("M12.7 11.64 L6.95 21.6")
    }

    /**
     * 自绘：更细长尾巴的搜索图标（侧栏"搜索当前助手"用）。
     *
     * 同一个圆长比 `SearchInput` 再长一截（尾巴 15、圆 r=3.3）：用户说它应当是输入区那个的四倍。
     * 同样受画布限制，实际是"能画多长画多长"，圆心右上移，尾巴压到右下角不出界。
     */
    val SearchAssistant: ImageVector = buildVector("search-assistant") {
        stroke("M19.3 6.4 a3.3 3.3 0 1 1 -6.6 0 a3.3 3.3 0 1 1 6.6 0")
        stroke("M14.35 9.26 L6.85 22.25")
    }

    /** 自绘：数据库（切到"搜索全部会话"时的图标）。圆柱 = 顶椭圆 + 两侧边 + 底弧 + 一道中缝。 */
    val Database: ImageVector = buildVector("database") {
        stroke("M20 6 a8 3 0 1 1 -16 0 a8 3 0 1 1 16 0")
        stroke("M4 6 V18")
        stroke("M20 6 V18")
        stroke("M4 12 a8 3 0 0 0 16 0")
        stroke("M4 18 a8 3 0 0 0 16 0")
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