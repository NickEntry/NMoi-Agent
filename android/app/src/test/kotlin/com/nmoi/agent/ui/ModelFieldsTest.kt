package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型字段与来源标记（D201 数据层）。
 *
 * 这一层要钉住的只有一条：**用户看到的徽标，说的是不是真话**。
 * 所以每条用例都在问同一个问题——"这个值是谁说的，凭什么这么记"。
 *
 * 用纯逻辑测：不碰 `JSONObject`（那是 android 的类，JVM 单测里是桩），
 * 序列化那一半靠"读写共用 `ModelField` 常量"来防错，靠 CI 编过一遍。
 */
class ModelFieldsTest {

    private fun model(
        id: String = "vendor/model-a",
        name: String = "",
        kind: String = ModelKinds.CHAT,
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        inputModalities: List<String> = emptyList(),
        tools: Boolean? = null,
        inputPrice: Double? = null,
        currency: String = "",
        efforts: List<String> = emptyList(),
        headers: List<HeaderRow> = emptyList(),
        bodyOverride: String = "",
    ) = ProviderModel(
        id = id,
        name = name,
        kind = kind,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        temperature = temperature,
        inputModalities = inputModalities,
        tools = tools,
        inputPrice = inputPrice,
        currency = currency,
        efforts = efforts,
        headers = headers,
        bodyOverride = bodyOverride,
    )

    private fun entry(vararg models: ProviderModel, sources: Map<String, Map<String, FieldSource>> = emptyMap()) =
        ProviderEntry(id = "p1", models = models.toList(), fieldSources = sources)

    private fun sourcesOf(model: ProviderModel, previous: ProviderEntry?) =
        ModelField.mergeSources(previous, listOf(model))[model.id].orEmpty()

    @Test
    fun `老数据没有来源记录时一切按默认`() {
        val old = entry(model(contextWindow = 128000))
        val same = model(contextWindow = 128000)

        val got = sourcesOf(same, old)

        // 24 个字段一个都不许漏——漏一个，界面那一格就没有徽标的依据。
        assertEquals(ModelField.ALL.size, got.size)
        assertTrue(got.values.all { it == FieldSource.Default })
    }

    @Test
    fun `改过的字段记自定义-没改的仍是默认`() {
        val old = entry(model(contextWindow = 128000, maxTokens = 8192))
        val changed = model(contextWindow = 200000, maxTokens = 8192)

        val got = sourcesOf(changed, old)

        assertEquals(FieldSource.Custom, got[ModelField.CONTEXT_WINDOW])
        assertEquals(FieldSource.Default, got[ModelField.MAX_TOKENS])
    }

    @Test
    fun `数据源来的推断在用户没动它的时候要留住`() {
        val old = entry(
            model(contextWindow = 128000),
            sources = mapOf("vendor/model-a" to mapOf(ModelField.CONTEXT_WINDOW to FieldSource.Inferred)),
        )

        val got = sourcesOf(model(contextWindow = 128000), old)

        // "值没变就沿用上一份"——否则每次保存都会把"这是数据源说的"洗成"程序默认"，
        // 用户就永远看不出这个数有没有出处。
        assertEquals(FieldSource.Inferred, got[ModelField.CONTEXT_WINDOW])
    }

    @Test
    fun `改回上一个值仍然算自定义-回到推断只有资源更新页能做到`() {
        val old = entry(
            model(contextWindow = 128000),
            sources = mapOf("vendor/model-a" to mapOf(ModelField.CONTEXT_WINDOW to FieldSource.Inferred)),
        )
        // 先把窗口改成 200000（这一次保存记 Custom），再改回 128000。
        val edited = model(contextWindow = 200000)
        val firstPass = ModelField.mergeSources(old, listOf(edited))
        assertEquals(FieldSource.Custom, firstPass["vendor/model-a"]!![ModelField.CONTEXT_WINDOW])

        val back = model(contextWindow = 128000)
        val secondPass = ModelField.mergeSources(
            old.copy(models = listOf(edited), fieldSources = firstPass),
            listOf(back),
        )

        // 比的是"上一份存在库里的是什么"，不是"快照给的原始值"（后者没落盘）。
        // 所以反复改来改去仍然是用户说的——除非资源更新页明确写回"出自哪份快照"。
        assertEquals(FieldSource.Custom, secondPass["vendor/model-a"]!![ModelField.CONTEXT_WINDOW])
    }

    @Test
    fun `新加的模型填了值就算自定义-空的算默认`() {
        val got = sourcesOf(
            model(name = "我的模型", contextWindow = 32000, currency = ""),
            previous = null,
        )

        assertEquals(FieldSource.Custom, got[ModelField.NAME])
        assertEquals(FieldSource.Custom, got[ModelField.CONTEXT_WINDOW])
        assertEquals(FieldSource.Custom, got[ModelField.ID])
        assertEquals(FieldSource.Default, got[ModelField.CURRENCY])
        assertEquals(FieldSource.Default, got[ModelField.TOOLS])
    }

    @Test
    fun `三态的不知道与知道是假必须能分开`() {
        val unknown = entry(model(tools = null))
        val knownFalse = entry(model(tools = false))

        // `null` → `false` 是一次真实的改动，必须记成自定义；
        // 把两者混成一个的话，"没数据"会被说成"用户关掉了"。
        val got = sourcesOf(model(tools = false), unknown)
        assertEquals(FieldSource.Custom, got[ModelField.TOOLS])

        val kept = sourcesOf(model(tools = false), knownFalse)
        assertEquals(FieldSource.Default, kept[ModelField.TOOLS])
    }

    @Test
    fun `header 顺序变了不算改动-值变了才算`() {
        val old = entry(
            model(
                headers = listOf(HeaderRow("X-A", "1"), HeaderRow("X-B", "2")),
            ),
        )

        val reordered = model(
            headers = listOf(HeaderRow("X-B", "2"), HeaderRow("X-A", "1")),
        )
        assertEquals(FieldSource.Default, sourcesOf(reordered, old)[ModelField.HEADERS])

        val changed = model(
            headers = listOf(HeaderRow("X-A", "1"), HeaderRow("X-B", "3")),
        )
        assertEquals(FieldSource.Custom, sourcesOf(changed, old)[ModelField.HEADERS])
    }

    @Test
    fun `body 只多打了首尾空白不算改动-改中间算`() {
        val old = entry(model(bodyOverride = """{"a":1}"""))
        val padded = model(bodyOverride = """  {"a":1}
""")
        assertEquals(FieldSource.Default, sourcesOf(padded, old)[ModelField.BODY])

        // 中间改一个字（或缩进）就是改内容：不先解析 JSON 就没法说"结构一样"。
        val respaced = model(bodyOverride = """{"a": 1}""")
        assertEquals(FieldSource.Custom, sourcesOf(respaced, old)[ModelField.BODY])
    }

    @Test
    fun `来源按当前模型表重建-删掉的模型不留下孤行`() {
        val old = entry(
            model(id = "a"),
            model(id = "b"),
            sources = mapOf(
                "a" to mapOf(ModelField.ID to FieldSource.Custom),
                "gone" to mapOf(ModelField.ID to FieldSource.Custom),
            ),
        )

        val got = ModelField.mergeSources(old, listOf(model(id = "a")))

        // `gone` 已经被删了：留着它的来源行就是"存了但界面上指不出来"。
        assertEquals(setOf("a"), got.keys)
    }

    @Test
    fun `模态词表把大小写和未知值收干净`() {
        val got = Modalities.normalize(
            listOf("TEXT", "image", "png", "text", " video "),
            Modalities.INPUT,
        )
        // 大小写归一、丢不认识的（`png` 不是模态，是格式）、去重，顺序按输入。
        assertEquals(listOf("text", "image", "video"), got)
        // 输出侧没有 pdf：生成不了的东西不该出现在勾选框里。
        assertTrue(Modalities.OUTPUT.none { it.wire == "pdf" })
    }

    @Test
    fun `推理档位按高低排好并去掉重复`() {
        assertEquals(
            listOf("low", "medium", "high"),
            Efforts.normalize(listOf("high", "low", "high", "medium")),
        )
        assertEquals("极高", Efforts.label("xhigh"))
        assertEquals("未知档位不动它", "weird", Efforts.label("weird"))
    }

    @Test
    fun `认不出的来源当默认`() {
        assertEquals(FieldSource.Default, FieldSource.of(null))
        assertEquals(FieldSource.Default, FieldSource.of(""))
        assertEquals(FieldSource.Default, FieldSource.of("guess"))
        assertEquals(FieldSource.Inferred, FieldSource.of(" inferred "))
    }

    @Test
    fun `推断的来源也走同一个比较-值变了照样记自定义`() {
        val old = entry(
            model(contextWindow = 128000),
            sources = mapOf("vendor/model-a" to mapOf(ModelField.CONTEXT_WINDOW to FieldSource.Inferred)),
        )

        val got = sourcesOf(model(contextWindow = 64000), old)

        // "数据源说的"不享有豁免权：用户改了就归用户。
        assertEquals(FieldSource.Custom, got[ModelField.CONTEXT_WINDOW])
    }

    @Test
    fun `一行副标题只用填了的数`() {
        assertNull(model().contextWindow)
        assertEquals("用默认参数", model().summary)
        assertEquals("窗口 128000 · 温度 0.7", model(contextWindow = 128000, temperature = 0.7).summary)
    }
}
