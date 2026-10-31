package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源更新（D201 第三片）。
 *
 * 这一层要钉住的是一件很贵的事：**它只动"没人认领"的字段**。
 * 猜错一个模型 → 把别人的窗口写到它身上；覆盖一格用户填的 → 他下次只会发现"我明明改过"。
 * 所以每条用例都在问："为什么允许你动这一格？"
 */
class ResourceUpdateTest {

    private fun snapshot(
        contextWindow: Int? = null,
        maxOutput: Int? = null,
        tools: Boolean? = null,
        structured: Boolean? = null,
        reasoning: Boolean? = null,
        efforts: List<String> = emptyList(),
        images: Boolean? = null,
        inputPrice: Double? = null,
        outputPrice: Double? = null,
    ) = SnapshotEntry(
        id = "vendor/model-a",
        family = "vendor/model-a",
        contextWindow = contextWindow,
        maxOutput = maxOutput,
        tools = tools,
        structuredOutput = structured,
        reasoning = reasoning,
        efforts = efforts,
        images = images,
        inputPerMillion = inputPrice,
        outputPerMillion = outputPrice,
    )

    private fun index(vararg entries: SnapshotEntry) = SnapshotIndex("2026-10-20T00:00:00Z", entries.toList())

    private fun model(
        id: String = "vendor/model-a",
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        tools: Boolean? = null,
        efforts: List<String> = emptyList(),
        inputModalities: List<String> = emptyList(),
        inputPrice: Double? = null,
        currency: String = "",
    ) = ProviderModel(
        id = id,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        tools = tools,
        efforts = efforts,
        inputModalities = inputModalities,
        inputPrice = inputPrice,
        currency = currency,
    )

    // ---- 匹配 -------------------------------------------------------------

    @Test
    fun `归一化剥掉分组前缀-分隔符统一`() {
        assertEquals("claude-3-7-sonnet", ModelSnapshot.normalize("[分组 A] Claude 3.7_Sonnet"))
        assertEquals("deepseek-v4-1-flash", ModelSnapshot.normalize("DeepSeek/V4.1:Flash"))
    }

    @Test
    fun `匹配先认 id 与别名-再按家族收尾`() {
        val exact = SnapshotEntry(
            id = "deepseek-v4-1-flash",
            family = "deepseek-v4-1-flash",
            contextWindow = 128000,
            maxOutput = null,
            tools = null,
            structuredOutput = null,
            reasoning = null,
            efforts = emptyList(),
            images = null,
            inputPerMillion = null,
            outputPerMillion = null,
        )
        val idx = index(exact)
        assertEquals(128000, idx.find("deepseek-v4-1-flash")?.contextWindow)
        // 大小写 / 分隔符不同也算同一条（归一化说的是同一件事）。
        assertEquals(128000, idx.find("DeepSeek V4.1 Flash")?.contextWindow)
        // 带版本尾巴：切出来按家族查。
        assertEquals(128000, idx.find("deepseek-v4-1-flash-20260101")?.contextWindow)
        assertNull(idx.find("deepseek-v4-1-pro"))
    }

    @Test
    fun `家族重名时不猜-宁可说快照里没有`() {
        val a = snapshot(contextWindow = 1000).copy(id = "claude-3-7-sonnet", family = "claude")
        val b = snapshot(contextWindow = 2000).copy(id = "claude-3-5-sonnet", family = "claude")
        val idx = index(a, b)
        // 精确命中照旧可以（id 就是它自己）。
        assertEquals(1000, idx.find("claude-3-7-sonnet")?.contextWindow)
        // 只切出家族 `claude` 时，两条都说得通 → 返回 null。
        assertNull(idx.find("claude-3-7-sonnet-20250219"))
    }

    // ---- 计划 -------------------------------------------------------------

    @Test
    fun `只写没被自定义过的字段-改过的跳过并说出原因`() {
        val entry = snapshot(contextWindow = 200000, maxOutput = 8192)
        val idx = index(entry)
        val before = model(contextWindow = 128000, maxTokens = 4096)
        val sources = mapOf(ModelField.CONTEXT_WINDOW to FieldSource.Custom)

        val plan = ResourceUpdate.plan(listOf(before), mapOf(before.id to sources), idx).single()

        // 窗口是用户填的 → 跳过；最大输出没人认领 → 更新。
        assertEquals(setOf(ModelField.MAX_TOKENS), plan.write)
        assertEquals(1, plan.changes.size)
        assertEquals(ModelField.MAX_TOKENS, plan.changes.single().key)
        assertEquals(ModelField.CONTEXT_WINDOW, plan.skipped.single().key)
    }

    @Test
    fun `值已经一样就不算更新`() {
        val idx = index(snapshot(contextWindow = 128000, tools = true))
        val before = model(contextWindow = 128000, tools = true)
        val plan = ResourceUpdate.plan(listOf(before), emptyMap(), idx).single()
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.write.isEmpty())
    }

    @Test
    fun `快照里没有的模型什么都不动`() {
        val idx = index(snapshot(contextWindow = 128000))
        val plan = ResourceUpdate.plan(listOf(model(id = "vendor/other")), emptyMap(), idx).single()
        assertNull(plan.entry)
        assertTrue(plan.write.isEmpty())
    }

    @Test
    fun `价格为美元时补上货币-否则界面会凭空多出单位`() {
        val idx = index(snapshot(inputPrice = 0.27, outputPrice = 1.1))
        val before = model()
        val plan = ResourceUpdate.plan(listOf(before), emptyMap(), idx).single()

        assertTrue(ModelField.INPUT_PRICE in plan.write)
        assertTrue(ModelField.CURRENCY in plan.write)
        assertEquals("USD", plan.changes.first { it.key == ModelField.CURRENCY }.to)
    }

    @Test
    fun `模态只从图片位推-别的模态快照没有就不碰`() {
        val idx = index(snapshot(images = true))
        val plan = ResourceUpdate.plan(listOf(model()), emptyMap(), idx).single()
        val applied = ResourceUpdate.apply(listOf(model()), listOf(plan))

        assertEquals(listOf("image"), applied.models.single().inputModalities)
        assertEquals(setOf(ModelField.INPUT_MODALITIES), plan.write)
    }

    // ---- 应用 -------------------------------------------------------------

    @Test
    fun `应用只写计划内的字段-并交出写进去的值`() {
        val idx = index(snapshot(contextWindow = 200000, maxOutput = 8192, tools = true))
        val before = model(contextWindow = 64000, maxTokens = 4096, tools = null)
        val sources = mapOf(ModelField.CONTEXT_WINDOW to FieldSource.Custom)
        val plans = ResourceUpdate.plan(listOf(before), mapOf(before.id to sources), idx)

        val applied = ResourceUpdate.apply(listOf(before), plans)
        val after = applied.models.single()

        assertEquals(64000, after.contextWindow)     // 用户填的：纹丝不动
        assertEquals(8192, after.maxTokens)
        assertEquals(true, after.tools)
        // 「推断」只认更新器写的那几格。
        assertEquals(
            setOf(ModelField.MAX_TOKENS, ModelField.TOOLS),
            applied.inferred[before.id]?.keys,
        )
    }

    @Test
    fun `更新之后用户又手改了同一格-那一格归他`() {
        val merged = mapOf(
            "vendor/model-a" to mapOf(ModelField.MAX_TOKENS to FieldSource.Default),
        )
        val edited = model(maxTokens = 1234)

        val marked = ModelField.markInferred(
            merged,
            listOf(edited),
            // 更新器写进去的是 8192，用户后来改成了 1234 —— 值对不上，不认这条账。
            mapOf("vendor/model-a" to mapOf(ModelField.MAX_TOKENS to "8192")),
        )

        assertEquals(FieldSource.Default, marked["vendor/model-a"]!![ModelField.MAX_TOKENS])
    }

    @Test
    fun `更新器写的值还在原位时记成推断`() {
        val merged = mapOf(
            "vendor/model-a" to mapOf(ModelField.MAX_TOKENS to FieldSource.Default),
        )
        val marked = ModelField.markInferred(
            merged,
            listOf(model(maxTokens = 8192)),
            mapOf("vendor/model-a" to mapOf(ModelField.MAX_TOKENS to "8192")),
        )
        assertEquals(FieldSource.Inferred, marked["vendor/model-a"]!![ModelField.MAX_TOKENS])
    }

    @Test
    fun `价格与档位的显示写法不该制造假更新`() {
        // 0.0 与 "0" 是同一个数；档位顺序不同也是同一组。
        val idx = index(snapshot(inputPrice = 0.0, efforts = listOf("high", "low")))
        val before = model(
            inputPrice = 0.0,
            currency = "USD",
            efforts = listOf("low", "high"),
        )
        val plan = ResourceUpdate.plan(listOf(before), emptyMap(), idx).single()
        assertTrue(plan.changes.isEmpty())
    }
}