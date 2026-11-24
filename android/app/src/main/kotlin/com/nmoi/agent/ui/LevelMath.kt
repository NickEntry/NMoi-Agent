package com.nmoi.agent.ui

/** 英文档位名的显示规则：≤5 个字母写全名，超过取缩写（`minimal→min`、`medium→mid`）。 */
internal fun shortLevelName(en: String): String = when {
    en.length <= 5 -> en
    en == "medium" -> "mid"
    else -> en.take(3)
}

/** 星点的颜色与层数（D402）：档位不同，颜色深浅与点数不同。 */
internal data class StarSpec(
    val rings: Int,
    val alpha: Float,
    val tint: Long,
    val outerTint: Long? = null,
    val outerFrom: Int = 0,
)

internal object StarSpecs {

    /** 思考图标里那 44dp 画布能铺几层：6 层 = 127 点，再多就糊成一片。 */
    const val ICON_RINGS = 6

    /** 标准档不画星：1.0× 不该有装饰。 */
    val NONE = StarSpec(rings = 0, alpha = 0f, tint = 0x00000000)

    fun of(tier: SpeedTier): StarSpec = when (tier) {
        SpeedTier.Standard -> NONE
        SpeedTier.Flex -> StarSpec(ICON_RINGS, 0.78f, tier.tint)
        SpeedTier.Priority -> StarSpec(ICON_RINGS, 0.82f, tier.tint)
        SpeedTier.Ultrafast -> StarSpec(
            rings = ICON_RINGS,
            alpha = 1f,
            tint = tier.tint,
            // 最外两层换红：6 层里 k>=5 就是第 5、6 层（30 + 36 = 66 点）。
            outerTint = tier.outerTint,
            outerFrom = ICON_RINGS - 1,
        )

        SpeedTier.Fast -> StarSpec(ICON_RINGS, 0.90f, tier.tint)
    }

    /**
     * 生成星点。**确定性**：同一份参数永远同一张图，重画不跳。
     *
     * 第 k 层 6k 个点，严格等角距、严格同一个半径；相邻两层错开半个角距
     * （不错开的话点在径向上会连成辐条）。`hole` 是中心挖掉的半径——仪表盘占的那块，
     * 落在里面的整层都跳过，星星不会从仪表盘里透出来。
     *
     * 返回 `(x, y)` 相对画布中心，单位是 dp。
     */
    fun points(spec: StarSpec, radius: Float, hole: Float): List<StarPoint> {
        if (spec.rings <= 0 || radius <= 0f) return emptyList()
        val out = ArrayList<StarPoint>()
        val step = radius / spec.rings
        if (hole <= 0f) out.add(StarPoint(0f, 0f, 0))
        for (k in 1..spec.rings) {
            val r = step * k
            if (r <= hole) continue
            val n = 6 * k
            val offset = if (k % 2 == 1) Math.PI / n else 0.0
            for (i in 0 until n) {
                val angle = (i.toDouble() / n) * 2 * Math.PI + offset
                out.add(
                    StarPoint(
                        x = (Math.cos(angle) * r).toFloat(),
                        y = (Math.sin(angle) * r).toFloat(),
                        ring = k,
                    ),
                )
            }
        }
        return out
    }
}

/** 一颗星：相对画布中心的偏移，加上它属于第几层（层号决定亮度与大小）。 */
internal data class StarPoint(val x: Float, val y: Float, val ring: Int)

/**
 * 一个思考档位在仪表盘上长什么样。
 *
 * `rings` = 画几段环（0 = 只有一圈细弧），`needleDeg` = 红针偏角，
 * `slash` = 关闭那道斜杠，`dots` = 弧外那圈红点（ultra）。
 */
internal data class GaugeSpec(
    val rings: Int,
    val needleDeg: Float,
    val slash: Boolean = false,
    val dots: Boolean = false,
)

internal object GaugeSpecs {

    /**
     * 这个形状的图形在 24 格坐标里占多高（`top` … `bottom`）。
     *
     * 三种形状不一样高：`off` 的弧从 6.6 起、`auto` 的细弧从 10 起、
     * 带环的从 `16 - 9`（外环半径 9）起。所以**不能用一个统一的上移量**——
     * 那样有的档居中、有的档偏一截。
     */
    fun extent(spec: GaugeSpec): Pair<Float, Float> {
        val bottom = 16f
        val top = when {
            spec.slash -> 6.6f
            spec.rings == 0 -> 10f
            else -> bottom - 9f
        }
        return top to bottom
    }

    /**
     * 这个形状该上移多少格，才能让它的视觉中线落在画布中线（12）上。
     *
     * 返回值可以是负的（那种形状本身偏上，要下移）。单测
     * `gauge_sits_on_the_canvas_center` 盯着每种形状落点。
     */
    fun liftUnits(spec: GaugeSpec): Float {
        val (top, bottom) = extent(spec)
        return (top + bottom) / 2f - 12f
    }

    /**
     * wire 值必须与 `crates/ffi/src/turn.rs` 的 `apply_thinking` 对表。
     *
     * 环数：`minimal` / `low` 一档，`medium` / `high` / `xhigh` 两档，`max` / `ultra` 三档。
     * 指针从左往右：越强的档越靠右。这是"数环 + 看针"两个线索同时可用——
     * 小尺寸时数环更快，看得清时针的角度更准。
     */
    fun of(wire: String): GaugeSpec = when (wire) {
        "off" -> GaugeSpec(rings = 0, needleDeg = 0f, slash = true)
        "auto" -> GaugeSpec(rings = 0, needleDeg = 0f)
        "minimal" -> GaugeSpec(rings = 1, needleDeg = -38f)
        "low" -> GaugeSpec(rings = 1, needleDeg = -20f)
        "medium" -> GaugeSpec(rings = 2, needleDeg = 0f)
        "high" -> GaugeSpec(rings = 2, needleDeg = 22f)
        "xhigh" -> GaugeSpec(rings = 2, needleDeg = 38f)
        "max" -> GaugeSpec(rings = 3, needleDeg = 52f)
        "ultra" -> GaugeSpec(rings = 3, needleDeg = 52f, dots = true)
        // 认不出的 wire 按"自动"画：画一个空仪表，不假装它是某一档。
        else -> GaugeSpec(rings = 0, needleDeg = 0f)
    }
}
