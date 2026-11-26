package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跟着模型走的设置（D435）。
 *
 * 这一层错起来是"悄悄删掉用户在用的设置"，或者"换模型之后档位原样搬过去"——
 * 前者用户会以为设置没保存，后者会让他对着一个根本没生效的档位调半天。
 * 所以清理与解析这两条都抽成纯函数，钉在这里。
 */
class ModelPrefsTest {

    @Test
    fun `键是 provider 斜杠 model，认得出也认得出不是自己那类`() {
        assertEquals(
            "thinking.p1/m1",
            ModelPrefs.thinkingKey("p1", "m1"),
        )
        assertEquals("p1/m1", ModelPrefs.modelOf("thinking.p1/m1"))
        // 别人的键（主题、文件偏好）不该被当成模型设置。
        assertNull(ModelPrefs.modelOf("theme"))
        assertNull(ModelPrefs.modelOf("thinking."))
        assertNull(ModelPrefs.modelOf("thinking.p1"))
        assertNull(ModelPrefs.modelOf("thinking./m1"))
    }

    @Test
    fun `不在现存模型里的键要清掉，在的一个都不动`() {
        val keys = listOf(
            "thinking.p1/m1", // 还在
            "thinking.p1/m2", // 模型删了
            "thinking.p2/m1", // 供应商删了
            "theme", // 不是模型设置，不许碰
            "file_prefs.showHidden",
        )
        val stale = ModelPrefs.staleKeys(keys, alive = setOf("p1/m1"))
        assertEquals(listOf("thinking.p1/m2", "thinking.p2/m1"), stale)
    }

    @Test
    fun `一个模型都没有时全是待清——所以调用方必须先读到模型再清`() {
        val keys = listOf("thinking.p1/m1")
        // 空 alive 意味着"一个模型都没有"，此时按判据确实该清；
        // 这一条钉的是**危险方向**：读失败被当成空集，会把设置全清掉。
        assertEquals(listOf("thinking.p1/m1"), ModelPrefs.staleKeys(keys, alive = emptySet()))
        // 反过来：模型还在就一条都不清。
        assertTrue(ModelPrefs.staleKeys(keys, alive = setOf("p1/m1")).isEmpty())
    }

    @Test
    fun `清理不碰别人的键`() {
        val keys = listOf("theme", "file_prefs.sort", "thinking.p1/m1")
        val stale = ModelPrefs.staleKeys(keys, alive = emptySet())
        assertEquals(listOf("thinking.p1/m1"), stale)
    }

    @Test
    fun `助手指向的模型不在了才算失效`() {
        val assistants = listOf(
            "默认助手" to "p1/m1",
            "写代码的" to "p1/m9",
        )
        val stale = StartupPlan.stale(assistants, alive = setOf("p1/m1", "p2/m3"))
        assertEquals(listOf("写代码的" to "p1/m9"), stale)
    }

    @Test
    fun `模型全在时一个助手都不算失效`() {
        val assistants = listOf("默认助手" to "p1/m1", "写代码的" to "p2/m3")
        assertTrue(StartupPlan.stale(assistants, alive = setOf("p1/m1", "p2/m3")).isEmpty())
    }

    @Test
    fun `对账那一行的措辞：成功也要有一句`() {
        val clean = StartupPlan.Report(models = 3, prunedPrefs = 0, staleAssistants = emptyList())
        assertTrue(clean.summary().contains("3 个模型"))
        assertTrue(clean.summary().contains("对得上"))

        val dirty = StartupPlan.Report(
            models = 2,
            prunedPrefs = 4,
            staleAssistants = listOf("写代码的" to "p1/m9"),
        )
        assertTrue(dirty.summary().contains("清掉 4 条"))
        assertTrue(dirty.summary().contains("写代码的 → p1/m9"))

        // 读失败要明说"这一轮没做任何清理"——那句话是"我的设置没被动过"的证据。
        val failed = StartupPlan.Report(
            models = 0,
            prunedPrefs = 0,
            staleAssistants = emptyList(),
            failure = "读不到供应商：库没打开",
        )
        assertTrue(failed.summary().contains("没做任何清理"))
        assertTrue(!failed.ok)
    }

    @Test
    fun `老数据迁移：已经有那一格的绝不覆盖`() {
        val candidates = listOf(
            "p1/m1" to "xhigh", // 已经有那一格 → 不搬
            "p1/m2" to "high", // 没有 → 搬
        )
        val moves = StartupPlan.migrations(
            candidates = candidates,
            existing = setOf("p1/m1"),
            alive = setOf("p1/m1", "p1/m2"),
        )
        assertEquals(listOf(StartupPlan.Migration("p1/m2", "high")), moves)
    }

    @Test
    fun `老数据迁移：auto 不搬，模型已删的不搬`() {
        val moves = StartupPlan.migrations(
            candidates = listOf(
                "p1/m1" to "auto", // auto = 没设置过，搬过去只是写一个空值
                "p1/m2" to "", // 空也不搬
                "p1/m3" to "high", // 模型已删 → 搬了下一轮又要清
            ),
            existing = emptySet(),
            alive = setOf("p1/m1", "p1/m2"),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `老数据迁移：两个助手共用一个模型时先到先得`() {
        val moves = StartupPlan.migrations(
            candidates = listOf(
                "p1/m1" to "high",
                "p1/m1" to "low", // 同一个模型，第二个不该再写一次
            ),
            existing = emptySet(),
            alive = setOf("p1/m1"),
        )
        assertEquals(listOf(StartupPlan.Migration("p1/m1", "high")), moves)
    }
}
