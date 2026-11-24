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
