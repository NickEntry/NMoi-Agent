package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星点几何（D402）：**均匀，不随机**。
 *
 * 这些断言盯的是"画出来会不会像一圈圈线"——每层半径必须严格相同、
 * 每层角度必须严格等距。带一点随机就会变成噪点团，而肉眼分不清
 * "我写错了"和"本来就该这样"。
 */
class StarPointsTest {

    private fun spec(rings: Int = 6) = StarSpec(rings, 1f, 0xFFFFFFFF)

    @Test
    fun each_ring_has_six_k_points() {
        val dots = StarSpecs.points(spec(6), radius = 20f, hole = 0f)
        val byRing = dots.groupBy { it.ring }
        // 中心那一点：无洞时在，且只有它自己
        assertEquals(1, byRing.getValue(0).size)
        for (k in 1..6) {
            assertEquals("第 $k 层应为 ${6 * k} 点", 6 * k, byRing.getValue(k).size)
        }
    }

    @Test
    fun radius_is_identical_within_a_ring() {
        val dots = StarSpecs.points(spec(6), radius = 20f, hole = 0f)
        for ((k, ring) in dots.groupBy { it.ring }) {
            val radii = ring.map { kotlin.math.hypot(it.x, it.y) }.toSet()
            assertEquals("第 $k 层的半径必须只有一个值", 1, radii.size)
        }
    }

    @Test
    fun angle_gap_is_constant_within_a_ring() {
        val dots = StarSpecs.points(spec(6), radius = 20f, hole = 0f)
        for ((k, ring) in dots.filter { it.ring > 0 }.groupBy { it.ring }) {
            val angles = ring.map { kotlin.math.atan2(it.y, it.x) }.sorted()
            val gap = 2 * Math.PI / ring.size
            for (i in 1 until angles.size) {
                val delta = angles[i] - angles[i - 1]
                assertTrue(
                    "第 $k 层相邻角度差应为 $gap，实际 $delta",
                    kotlin.math.abs(delta - gap) < 1e-6,
                )
            }
        }
    }

    /** 挖洞：仪表盘占的那块里不能有星星。 */
    @Test
    fun hole_removes_the_inner_rings() {
        val hole = 12f
        val dots = StarSpecs.points(spec(6), radius = 20f, hole = hole)
        assertTrue("洞内不该有点", dots.all { kotlin.math.hypot(it.x, it.y) > hole })
        assertTrue("中心那一点也该被挖掉", dots.none { it.ring == 0 })
        // 挖洞之后点数必须变少
        val full = StarSpecs.points(spec(6), radius = 20f, hole = 0f)
        assertTrue(dots.size < full.size)
    }

    /** 半径 0 或层数 0：空表，不是一堆原点。 */
    @Test
    fun degenerate_inputs_give_no_points() {
        assertTrue(StarSpecs.points(spec(0), 20f, 0f).isEmpty())
        assertTrue(StarSpecs.points(spec(6), 0f, 0f).isEmpty())
    }

    /** 所有点都落在半径内：最外层的点不该被画到按钮外面。 */
    @Test
    fun points_stay_inside_the_radius() {
        val radius = 20f
        val dots = StarSpecs.points(spec(6), radius, 0f)
        for (dot in dots) {
            val dist = kotlin.math.hypot(dot.x, dot.y)
            assertTrue("点落在半径 $radius 外：$dist", dist <= radius + 1e-4f)
        }
    }

    /**
     * 仪表盘规格（D405）：环数随档位不减，指针角随档位递增。
     *
     * 这两条是"图标跟着档位变"的判据——之前图标是个固定矢量，
     * 调档位时只有底下那行文字在动。
     */
    @Test
    fun gauge_follows_the_level() {
        val order = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        var lastRings = -1
        var lastNeedle = -100f
        for (wire in order) {
            val spec = GaugeSpecs.of(wire)
            assertTrue("$wire 的环数不该比前一档少", spec.rings >= lastRings)
            // 指针角**不减**：max 与 ultra 同一角度（ultra 靠弧外那圈红点区分，
            // 不是把针再往右推——推出去就指到弧外了）。
            assertTrue("$wire 的指针角不该比前一档小", spec.needleDeg >= lastNeedle)
            lastRings = spec.rings
            lastNeedle = spec.needleDeg
        }
        // 关闭有斜杠、自动没有；ultra 多一圈红点
        assertTrue(GaugeSpecs.of("off").slash)
        assertTrue(!GaugeSpecs.of("auto").slash)
        assertEquals(0, GaugeSpecs.of("auto").rings)
        assertTrue(GaugeSpecs.of("ultra").dots)
        // 认不出的 wire 按自动画，不假装是某一档
        assertEquals(GaugeSpecs.of("auto"), GaugeSpecs.of("???"))

        // 每一档都得长得不一样：否则调了档位图标不动
        val shapes = order.map { GaugeSpecs.of(it) }
        assertEquals("七个档位应有七种形状", order.size, shapes.toSet().size)
    }

    /**
     * 仪表盘在画布里的视觉中线要落在 12（24 格坐标的中线）。
     *
     * 用户 2026-11-24 那张截图里，这颗图标比相邻几颗低一截——底座线在 y=16，
     * 图形占 6.6…16，重心偏下。`LIFT_UNITS` 就是修这件事的那个数。
     */
    @Test
    fun gauge_sits_on_the_canvas_center() {
        for (wire in listOf("auto", "off", "minimal", "medium", "high", "max", "ultra")) {
            val spec = GaugeSpecs.of(wire)
            val (top, bottom) = GaugeSpecs.extent(spec)
            val center = (top + bottom) / 2f - GaugeSpecs.liftUnits(spec)
            assertTrue(
                "$wire 的视觉中线应正好落在 12，实际 $center",
                kotlin.math.abs(center - 12f) < 1e-4f,
            )
        }
    }

    /** 缩写规则：≤5 个字母写全名，超过取缩写。 */
    @Test
    fun short_name_rule() {
        assertEquals("off", shortLevelName("off"))
        assertEquals("auto", shortLevelName("auto"))
        assertEquals("low", shortLevelName("low"))
        assertEquals("high", shortLevelName("high"))
        assertEquals("xhigh", shortLevelName("xhigh"))
        assertEquals("max", shortLevelName("max"))
        assertEquals("ultra", shortLevelName("ultra"))
        assertEquals("min", shortLevelName("minimal"))
        assertEquals("mid", shortLevelName("medium"))
    }

    /**
     * 返回栈深度（D407）：从聊天页进工作区那一页，**返回一次就该回聊天页**。
     *
     * 之前把"工具与工作区"和"工作区列表"也压进了栈，所以从聊天页点进去要按三次返回。
     */
    @Test
    fun workspace_page_returns_to_chat_in_one_step() {
        // 栈里只有根页面（聊天页）
        val afterChat = NavigationDepth.after(current = 1, entry = NavigationDepth.FROM_CHAT)
        assertEquals("从聊天页进只该压一层", 2, afterChat)
        // 返回一次 = 层数减一 = 回到 1（聊天页）
        assertEquals("返回一次该回到聊天页", 1, afterChat - 1)

        // 从设置走下去：路上已经有那两层，再压这一层就是 4
        val afterSettings = NavigationDepth.after(current = 3, entry = NavigationDepth.FROM_SETTINGS)
        assertEquals(5, afterSettings)
        assertTrue("从设置进不该只退一次就到聊天页", afterSettings - 1 > 1)
    }
}
