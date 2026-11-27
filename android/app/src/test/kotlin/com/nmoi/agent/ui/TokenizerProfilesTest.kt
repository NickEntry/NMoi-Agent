package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tokenizer 条目（D437）。
 *
 * 这一层错起来**不报错**：匹配到错的那一条，所有 token 数悄悄偏一截，
 * 而界面上那个数看起来照常。所以优先级、回退、坏条目这几条钉在这里。
 */
class TokenizerProfilesTest {

    private fun profile(
        id: String,
        name: String,
        type: TokenizerMatch,
        value: String,
        bytes: Long = 1024,
    ) = TokenizerProfile(
        id = id,
        name = name,
        matchType = type,
        matchValue = value,
        bytes = bytes,
        importedAt = 1_764_000_000_000,
    )

    @Test
    fun `列表顺序就是优先级：上面那条先命中`() {
        val qwen25 = profile("t1", "Qwen2.5", TokenizerMatch.Keyword, "qwen2.5")
        val qwen = profile("t2", "Qwen", TokenizerMatch.Keyword, "qwen")
        val pick = TokenizerProfiles.pick("qwen2.5-72b", listOf(qwen25, qwen))
        assertEquals(TokenizerPick.Profile(qwen25), pick)

        // 顺序换过来就是另一条——顺序是人的决定，不是"更具体的优先"。
        val flipped = TokenizerProfiles.pick("qwen2.5-72b", listOf(qwen, qwen25))
        assertEquals(TokenizerPick.Profile(qwen), flipped)
    }

    @Test
    fun `一个都不命中就回退内置`() {
        val pick = TokenizerProfiles.pick(
            "deepseek-v4.1-flash",
            listOf(profile("t1", "Qwen", TokenizerMatch.Keyword, "qwen")),
        )
        assertEquals(TokenizerPick.Builtin, pick)
    }

    @Test
    fun `空模型名也回退内置，不抛`() {
        assertEquals(TokenizerPick.Builtin, TokenizerProfiles.pick("", emptyList()))
        assertEquals(TokenizerPick.Builtin, TokenizerProfiles.pick("   ", emptyList()))
    }

    @Test
    fun `关键词不区分大小写`() {
        val row = profile("t1", "Qwen", TokenizerMatch.Keyword, "QWEN")
        assertEquals(TokenizerPick.Profile(row), TokenizerProfiles.pick("qwen2.5", listOf(row)))
    }

    @Test
    fun `模型 id 带供应商前缀时，规则两边都试`() {
        val row = profile("t1", "Qwen", TokenizerMatch.Keyword, "qwen")
        assertEquals(TokenizerPick.Profile(row), TokenizerProfiles.pick("p1/qwen2.5", listOf(row)))
    }

    @Test
    fun `正则命中，且不区分大小写`() {
        val row = profile("t1", "Qwen2.5 家族", TokenizerMatch.Regex, "^qwen2\\.5")
        assertEquals(TokenizerPick.Profile(row), TokenizerProfiles.pick("QWEN2.5-72B", listOf(row)))
    }

    @Test
    fun `坏条目跳过，不整页崩`() {
        val noRule = profile("t1", "空的", TokenizerMatch.Keyword, " ")
        val noFile = profile("t2", "没词表", TokenizerMatch.Keyword, "qwen", bytes = 0)
        val badRegex = profile("t3", "坏正则", TokenizerMatch.Regex, "qwen(")
        val good = profile("t4", "好的", TokenizerMatch.Keyword, "qwen")

        val pick = TokenizerProfiles.pick("qwen2.5", listOf(noRule, noFile, badRegex, good))
        assertEquals(TokenizerPick.Profile(good), pick)
    }

    @Test
    fun `只有坏条目时回退内置，而不是抛`() {
        val bad = profile("t1", "坏正则", TokenizerMatch.Regex, "[")
        assertEquals(TokenizerPick.Builtin, TokenizerProfiles.pick("qwen2.5", listOf(bad)))
    }

    @Test
    fun `建条目之前：名字与规则缺一不可`() {
        assertNotNull(TokenizerProfiles.missingRule("", "qwen", TokenizerMatch.Keyword))
        assertNotNull(TokenizerProfiles.missingRule("Qwen", "  ", TokenizerMatch.Keyword))
        assertNotNull(TokenizerProfiles.missingRule("Qwen", "qwen(", TokenizerMatch.Regex))
        assertNull(TokenizerProfiles.missingRule("Qwen", "qwen", TokenizerMatch.Keyword))
        assertNull(TokenizerProfiles.missingRule("Qwen", "^qwen2\\.5$", TokenizerMatch.Regex))
    }

    @Test
    fun `建条目之前：词表必须有，且不能超上限`() {
        assertNotNull(TokenizerProfiles.missingVocabulary(bytes = 0, importedAt = 0))
        assertNotNull(
            TokenizerProfiles.missingVocabulary(
                bytes = TokenizerProfiles.MAX_BYTES + 1,
                importedAt = 1,
            ),
        )
        assertNull(TokenizerProfiles.missingVocabulary(bytes = 6_367_257, importedAt = 1))
    }

    @Test
    fun `名字太长要拦住：卡片一行写不下`() {
        val long = "名".repeat(TokenizerProfiles.MAX_NAME + 1)
        assertNotNull(TokenizerProfiles.missingRule(long, "qwen", TokenizerMatch.Keyword))
    }

    @Test
    fun `词表没导进来的条目不能算可用`() {
        assertFalse(profile("t1", "Qwen", TokenizerMatch.Keyword, "qwen", bytes = 0).usable)
        assertTrue(profile("t1", "Qwen", TokenizerMatch.Keyword, "qwen").usable)
    }

    @Test
    fun `上移下移改的是顺序，也就是优先级`() {
        val a = profile("t1", "A", TokenizerMatch.Keyword, "a")
        val b = profile("t2", "B", TokenizerMatch.Keyword, "b")
        val c = profile("t3", "C", TokenizerMatch.Keyword, "c")

        val moved = TokenizerProfileStore.move(listOf(a, b, c), "t3", up = true)
        assertEquals(listOf("t1", "t3", "t2"), moved.map { it.id })

        val back = TokenizerProfileStore.move(moved, "t3", up = false)
        assertEquals(listOf("t1", "t2", "t3"), back.map { it.id })
    }

    @Test
    fun `移到头顶或队尾就停住，不绕回去`() {
        val a = profile("t1", "A", TokenizerMatch.Keyword, "a")
        val b = profile("t2", "B", TokenizerMatch.Keyword, "b")
        assertEquals(listOf("t1", "t2"), TokenizerProfileStore.move(listOf(a, b), "t1", up = true).map { it.id })
        assertEquals(listOf("t1", "t2"), TokenizerProfileStore.move(listOf(a, b), "t2", up = false).map { it.id })
        // 不存在的 id：原样返回，不抛。
        assertEquals(listOf("t1", "t2"), TokenizerProfileStore.move(listOf(a, b), "t9", up = true).map { it.id })
    }

    @Test
    fun `顺序真的会改变匹配结果`() {
        val wide = profile("t1", "Qwen", TokenizerMatch.Keyword, "qwen")
        val narrow = profile("t2", "Qwen2.5", TokenizerMatch.Keyword, "qwen2.5")
        // 宽规则在上 → 它先吃掉。
        assertEquals(
            TokenizerPick.Profile(wide),
            TokenizerProfiles.pick("qwen2.5", listOf(wide, narrow)),
        )
        // 把窄的提上来 → 命中窄的。这就是那两个箭头的意义。
        assertEquals(
            TokenizerPick.Profile(narrow),
            TokenizerProfiles.pick("qwen2.5", TokenizerProfileStore.move(listOf(wide, narrow), "t2", up = true)),
        )
    }
}
