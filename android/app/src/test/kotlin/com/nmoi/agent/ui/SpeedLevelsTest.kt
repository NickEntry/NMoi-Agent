package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 速度档位（D402）：两家字段不同，档位数量也不同。
 *
 * 这些断言盯的是"选了会不会真的生效"——发错字段的表现是"选了没反应"，
 * 那种错用户不会报 bug，只会觉得这个功能是坏的。
 */
class SpeedLevelsTest {

    @Test
    fun gpt_lists_four_tiers() {
        val tiers = SpeedLevels.forModel(SpeedLevels.KIND_OPENAI, "gpt-5.6-sol")
        assertEquals(
            listOf(SpeedTier.Flex, SpeedTier.Standard, SpeedTier.Priority, SpeedTier.Ultrafast),
            tiers,
        )
        // Responses 端口同一套
        assertEquals(tiers, SpeedLevels.forModel(SpeedLevels.KIND_RESPONSES, "gpt-6-sol"))
    }

    @Test
    fun claude_lists_only_two_tiers() {
        val tiers = SpeedLevels.forModel(SpeedLevels.KIND_ANTHROPIC, "claude-opus-5-5")
        assertEquals(listOf(SpeedTier.Standard, SpeedTier.Fast), tiers)
    }

    /**
     * Claude 挂在 OpenAI 兼容中转后面时，端口说是兼容、模型名说是 Claude。
     * **模型名优先**：只看端口的话会发 `service_tier`，对方不认。
     */
    @Test
    fun claude_behind_a_relay_still_uses_speed() {
        val tiers = SpeedLevels.forModel(SpeedLevels.KIND_OPENAI, "claude-opus-5-5")
        assertEquals(listOf(SpeedTier.Standard, SpeedTier.Fast), tiers)
        assertEquals(
            SpeedField.AnthropicSpeed,
            SpeedLevels.fieldFor(SpeedLevels.KIND_OPENAI, "claude-opus-5-5"),
        )
    }

    @Test
    fun gemini_has_no_speed_axis() {
        assertTrue(SpeedLevels.forModel(SpeedLevels.KIND_GEMINI, "gemini-3-pro").isEmpty())
        assertEquals(
            SpeedField.None,
            SpeedLevels.fieldFor(SpeedLevels.KIND_GEMINI, "gemini-3-pro"),
        )
    }

    @Test
    fun field_matches_the_family() {
        assertEquals(
            SpeedField.ServiceTier,
            SpeedLevels.fieldFor(SpeedLevels.KIND_OPENAI, "gpt-5.6-sol"),
        )
        assertEquals(
            SpeedField.ServiceTier,
            SpeedLevels.fieldFor(SpeedLevels.KIND_RESPONSES, "gpt-5.6-sol"),
        )
        assertEquals(
            SpeedField.AnthropicSpeed,
            SpeedLevels.fieldFor(SpeedLevels.KIND_ANTHROPIC, "claude-opus-5-5"),
        )
    }

    @Test
    fun multipliers_are_known_where_they_exist() {
        assertEquals(0.5, SpeedTier.Flex.multiplier!!, 1e-9)
        assertEquals(1.0, SpeedTier.Standard.multiplier!!, 1e-9)
        assertEquals(2.0, SpeedTier.Priority.multiplier!!, 1e-9)
        assertEquals(2.0, SpeedTier.Fast.multiplier!!, 1e-9)
        // ultrafast 按端点计价：没有通用倍数，编一个数会误导人
        assertNull(SpeedTier.Ultrafast.multiplier)
        assertEquals("按端点", SpeedTier.Ultrafast.multiplierLabel())
    }

    @Test
    fun parse_accepts_wire_values_only() {
        assertEquals(SpeedTier.Flex, SpeedLevels.parse("flex"))
        assertEquals(SpeedTier.Fast, SpeedLevels.parse("FAST"))
        assertNull(SpeedLevels.parse("turbo"))
        assertNull(SpeedLevels.parse(""))
    }

    /** 星点：标准档不画，其余按档位分色；ultrafast 最外两层换红。 */
    @Test
    fun star_spec_follows_the_tier() {
        assertEquals(0, StarSpecs.of(SpeedTier.Standard).rings)
        assertTrue(StarSpecs.of(SpeedTier.Flex).rings > 0)
        assertTrue(StarSpecs.of(SpeedTier.Flex).tint != StarSpecs.of(SpeedTier.Priority).tint)

        val ultra = StarSpecs.of(SpeedTier.Ultrafast)
        assertNotNull(ultra.outerTint)
        // 6 层里 k>=5 就是最外两层
        assertEquals(StarSpecs.ICON_RINGS - 1, ultra.outerFrom)
    }
}
