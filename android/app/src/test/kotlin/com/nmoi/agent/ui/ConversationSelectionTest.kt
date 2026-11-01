package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉多选（D207）。钉的是顺序与判据：
 * 置顶写错一位，用户下次打开只会看到"怎么排到第二去了"；全选判据歪了，点两次会有一次没反应。
 */
class ConversationSelectionTest {

    private fun row(id: String, pinnedTitle: String = id) = ConversationRow(
        id = id,
        title = pinnedTitle,
        preview = "",
        model = null,
        updatedAt = "—",
        incomplete = false,
    )

    private val rows = listOf(row("a"), row("b"), row("c"))

    @Test
    fun `勾上再点一次就取消`() {
        var ids = ConversationSelection.toggle(emptySet(), "a")
        assertEquals(setOf("a"), ids)
        ids = ConversationSelection.toggle(ids, "a")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `选中的行按列表顺序返回-不是Set的迭代顺序`() {
        val chosen = ConversationSelection.rowsInOrder(rows, setOf("c", "a"))
        assertEquals(listOf("a", "c"), chosen.map { it.id })
    }

    @Test
    fun `全选-全勾上之后再点一次是取消`() {
        val all = ConversationSelection.toggleAll(rows, emptySet())
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(ConversationSelection.toggleAll(rows, all).isEmpty())
    }

    @Test
    fun `全选的判据是每一条都在-不是数量相等`() {
        // 有一条已经被删了、却还挂在一个数量相同的选择集里：这时应当"补齐全选"而不是判成已全选。
        val stale = setOf("a", "b", "gone")
        assertEquals(setOf("a", "b", "c"), ConversationSelection.toggleAll(rows, stale))
    }

    @Test
    fun `部分已置顶时按置顶处理-选中的排到最前`() {
        val pinned = listOf("b")
        val chosen = ConversationSelection.rowsInOrder(rows, setOf("c", "a"))
        assertFalse(ConversationSelection.allPinned(pinned, chosen))
        assertEquals(listOf("a", "c", "b"), ConversationSelection.pinAfterToggle(pinned, chosen))
    }

    @Test
    fun `全都已置顶时同一颗按钮变成取消置顶`() {
        val pinned = listOf("a", "b", "c")
        val chosen = ConversationSelection.rowsInOrder(rows, setOf("a", "b"))
        assertTrue(ConversationSelection.allPinned(pinned, chosen))
        assertEquals(listOf("c"), ConversationSelection.pinAfterToggle(pinned, chosen))
    }

    @Test
    fun `删掉之后选择集里不存在的 id 要被清掉`() {
        val remaining = listOf(row("a"))
        assertEquals(setOf("a"), ConversationSelection.prune(setOf("a", "b"), remaining))
    }
}
