package com.nmoi.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 思考强度的仪表盘（D405）：**环数表示档位，红针表示强度**。
 *
 * # 为什么不能沿用固定图标
 *
 * 之前那套只换了底下那行文字，图标本身一直是一个 `ActionIcons.Gauge`——
 * 用户调档位时看到的就是"什么都没变"。图标是扫读锚点，它必须跟着值走。
 *
 * # 画法
 *
 * 24×24 的画布，底座线在 y=16，圆心 `(12,16)`：
 *
 * - `off`：一段开口弧 + 一道斜杠（"关掉了"）。
 * - `auto`：一圈细弧 + 中心一点（"不指定"）。
 * - 其余：从外往里画 `rings` 段弧，越外越亮；再一根红针指到 `needleDeg`
 *   （0 = 正上方，负 = 偏左）。`ultra` 额外在弧外撒一圈红点。
 *
 * 颜色全部走主题角色（`onSurfaceVariant` / `primary` / `error`），不写死色值——
 * 深浅两套主题各要成立（D120）。
 */
@Composable
internal fun ThinkingGauge(
    wire: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val spec = remember(wire) { GaugeSpecs.of(wire) }
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    )
    val needle = MaterialTheme.colorScheme.error
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Canvas(modifier = modifier.size(size)) {
        // 画布按 24 格设计，实际尺寸等比缩。
        val unit = this.size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val cx = 12f * unit
        val cy = 16f * unit

        // 底座
        drawLine(
            color = dim,
            start = p(3f, 16f),
            end = p(21f, 16f),
            strokeWidth = 1.3f * unit,
            cap = StrokeCap.Round,
        )

        if (spec.slash) {
            // 关闭：开口弧 + 斜杠
            drawArc(
                color = base,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(3.6f, 6.6f),
                size = Size(16.8f * unit, 16.8f * unit),
                style = Stroke(width = 1.75f * unit, cap = StrokeCap.Round),
            )
            drawLine(
                color = base,
                start = p(7.5f, 8.2f),
                end = p(16.5f, 15.8f),
                strokeWidth = 1.75f * unit,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        if (spec.rings == 0) {
            // 自动：一圈细弧 + 中心点
            drawArc(
                color = base,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(6f, 10f),
                size = Size(12f * unit, 12f * unit),
                style = Stroke(width = 1.75f * unit, cap = StrokeCap.Round),
            )
            drawCircle(color = base, radius = 1.2f * unit, center = p(12f, 16f))
            return@Canvas
        }

        // 环：从外往里，第 0 个最亮
        val radii = listOf(9f, 6.3f, 4.2f)
        for (i in 0 until spec.rings.coerceAtMost(radii.size)) {
            val r = radii[i] * unit
            drawArc(
                color = ringColors.getOrElse(i) { ringColors.last() },
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(
                    width = (1.75f - i * 0.08f) * unit,
                    cap = StrokeCap.Round,
                ),
            )
        }

        // 红针：角度按"0 = 正上方，负 = 偏左"算
        val angle = Math.toRadians(spec.needleDeg.toDouble())
        val tipX = cx + (sin(angle) * 7.4f * unit).toFloat()
        val tipY = cy - (cos(angle) * 7.4f * unit).toFloat()
        drawLine(
            color = needle,
            start = Offset(cx, cy),
            end = Offset(tipX, tipY),
            strokeWidth = 2f * unit,
            cap = StrokeCap.Round,
        )
        drawCircle(color = needle, radius = 1.05f * unit, center = Offset(tipX, tipY))

        // ultra：弧外一圈红点（"顶档之上还有一层"）
        if (spec.dots) {
            for (i in 0 until 14) {
                val a = Math.toRadians(180.0 * (0.08 + 0.84 * i / 13))
                val dx = cx + (cos(a) * 10.6f * unit).toFloat()
                val dy = cy - (sin(a) * 10.6f * unit).toFloat()
                drawCircle(
                    color = needle.copy(alpha = 0.85f),
                    radius = 0.52f * unit,
                    center = Offset(dx, dy),
                )
            }
        }
    }
}
