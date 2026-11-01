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
        id: String = "vendor/model-a",
        family: String = id,
        version: List<Int> = emptyList(),
        aliases: List<String> = emptyList(),
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
        id = id,
        family = family,
        version = version,
        aliases = aliases,
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
        // 同一家公司的另一个型号（库里没有）：落到"厂商最新"那一档，并标成猜的。
        // 这是用户 2026-10-31 定的口径——没有对应数据就按公司最新的那条判断。
        assertEquals(MatchHow.VendorLatest, idx.match("deepseek-v4-1-pro")?.how)
        assertNull(idx.match("zzz-完全对不上的名字"))
    }

    @Test
    fun `同名家族取最新版本-并且明确说这是猜的`() {
        val older = snapshot(
            id = "claude-3-5-sonnet",
            family = "claude",
            version = listOf(3, 5),
            contextWindow = 2000,
        )
        val newer = snapshot(
            id = "claude-3-7-sonnet",
            family = "claude",
            version = listOf(3, 7),
            contextWindow = 1000,
        )
        val idx = index(older, newer)

        // 精确命中照旧（名字就是它自己）。
        assertEquals(MatchHow.Exact, idx.match("claude-3-7-sonnet")?.how)
        // 带日期尾巴、去掉日期就能对上已有 id → 「去掉装饰」那一档，不算猜。
        val stripped = idx.match("claude-3-7-sonnet-20250219")
        assertEquals("claude-3-7-sonnet", stripped?.entry?.id)
        assertEquals(MatchHow.Stripped, stripped?.how)

        // 换一个连去装饰都对不上的名字：先落到"同一家族的某个型号"这一档。
        val family = idx.match("claude-3-9-sonnet-20251010")
        assertEquals("claude-3-7-sonnet", family?.entry?.id)
        assertTrue(family!!.how.guessed)
    }

    @Test
    fun `家族前缀对得上但型号名对不上-按家族最新猜`() {
        val entry = snapshot(
            id = "deepseek-v4-1-flash",
            family = "deepseek-v4-1-flash",
            contextWindow = 1000000,
        )
        val idx = index(entry)

        val match = idx.match("deepseek-v4-1-flash-xyz")
        assertEquals(MatchHow.FamilyLatest, match?.how)
        assertEquals("deepseek-v4-1-flash", match?.entry?.id)
    }

    @Test
    fun `三种名字写法各走各的一档`() {
        val entry = snapshot(
            id = "deepseek-v4-1-flash-1017",
            family = "deepseek-v4-1-flash",
            version = listOf(1017),
            contextWindow = 1000000,
        )
        val plain = snapshot(id = "deepseek-v4-1-flash", family = "deepseek-v4-1-flash", contextWindow = 1000000)
        val idx = index(entry, plain)

        // 1) 原样
        assertEquals(MatchHow.Exact, idx.match("deepseek-v4-1-flash-1017")?.how)
        // 2) 中转前缀 + 日期尾巴（用户举的例子）
        val stripped = idx.match("raccoon/sn-deepseek-v4-1-flash-1017")
        assertEquals(MatchHow.Stripped, stripped?.how)
        assertEquals("deepseek-v4-1-flash-1017", stripped?.entry?.id)
        // 3) 版本号少了连字符
        assertEquals(MatchHow.DigitsJoined, idx.match("deepseek-v41-flash")?.how)
    }

    @Test
    fun `没有对应条目时按同一厂商最新的一条猜-并等用户点确认`() {
        val old = snapshot(id = "deepseek-v4-flash", version = listOf(4), contextWindow = 128000)
        val newest = snapshot(id = "deepseek-v4-1-flash", version = listOf(4, 1), contextWindow = 1000000)
        val idx = index(old, newest)

        val match = idx.match("deepseek-flash-xyz")
        assertEquals(MatchHow.VendorLatest, match?.how)
        assertEquals("deepseek-v4-1-flash", match?.entry?.id)

        // 猜出来的**不自动写**：没点「用这条」时 write 是空的。
        val model = model(id = "deepseek-flash-xyz", contextWindow = 32000)
        val plan = ResourceUpdate.plan(listOf(model), emptyMap(), idx).single()
        assertTrue(plan.awaitingAccept)
        assertTrue(plan.write.isEmpty())
        // 接受之前也要显示"会改成什么"（不然用户看不到自己错过了什么）。
        assertTrue(plan.changes.isNotEmpty())

        // 点了之后才写。
        val accepted = ResourceUpdate.plan(listOf(model), emptyMap(), idx, accepted = setOf(model.id)).single()
        assertEquals(setOf(ModelField.CONTEXT_WINDOW), accepted.write)
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
        val plan = ResourceUpdate.plan(listOf(model(id = "zzz-完全对不上的名字")), emptyMap(), idx).single()
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