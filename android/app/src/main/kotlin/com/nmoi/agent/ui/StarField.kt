package com.nmoi.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 思考图标底下的星图（D402）。
 *
 * 档位不同，星点不同：颜色深浅、点数、最外几层是否换色。**静态**——画一次就定住，
 * 没有动画（用户 2026-11-24 明确"不要闪烁，弄成固定的图标"）。
 *
 * # 为什么星点落在图标里而不是聊天页
 *
 * 它表达的是"这一轮跑多快"，是这颗按钮的属性。铺满整屏会让人以为那是背景装饰，
 * 而且聊天内容压在星星上更难读。
 *
 * # 中心挖洞
 *
 * 仪表盘本身占着中心那块，落在 `hole` 半径内的整层都跳过——星星被那颗圆挡住，
 * 不是从它里面透出来。
 */
@Composable
internal fun StarField(
    spec: StarSpec,
    modifier: Modifier = Modifier,
    /** 画布边长；星点的半径按它的一半算。 */
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    if (spec.rings <= 0) return
    val tint = Color(spec.tint)
    val outerTint = spec.outerTint?.let { Color(it) }
    val rings = spec.rings

    Canvas(modifier = modifier.size(size)) {
        val reach = max(4f, min(this.size.width, this.size.height) / 2f - 1.5f)
        // 仪表盘视觉 22dp，半圆半径约 11dp，加 1dp 余量。
        val hole = min(reach * 0.62f, (this.size.width * 22f / 44f) / 2f + 1f)
        val dots = StarSpecs.points(spec, reach, hole)
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f

        for (dot in dots) {
            // 越往外越暗：中心区域是仪表盘，外圈只负责铺满。
            val falloff = max(0.62f, 1f - 0.38f * (dot.ring.toFloat() / rings))
            val alpha = min(1f, spec.alpha * falloff)
            val color = if (outerTint != null && dot.ring >= spec.outerFrom) outerTint else tint
            // 点的大小按层微收：内层看得清，外层不糊成一片。
            val radius = if (dot.ring == 0) 2.1f else max(0.8f, 1.35f - 0.028f * dot.ring)
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(cx + dot.x, cy + dot.y),
                alpha = alpha,
            )
        }
    }
}
