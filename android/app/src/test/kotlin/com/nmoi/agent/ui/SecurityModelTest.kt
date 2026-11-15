package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityModelTest {
    private fun builtin(id: String, name: String, count: Int) = RuleGroup(
        id = id,
        name = name,
        builtin = true,
        asks = true,
        hidden = false,
        count = count,
        items = emptyList(),
    )

    private val disk = builtin("disk", "磁盘操作", 10)
    private val delete = builtin("delete", "文件删除", 13)

    @Test
    fun 没有本地那份时就是目录本身() {
        val merged = mergedGroups(listOf(disk, delete), emptyList())
        assertEquals(listOf("磁盘操作", "文件删除"), merged.map { it.name })
        assertEquals(listOf(10, 13), merged.map { it.count })
    }

    @Test
    fun 停用一组只改那一组() {
        val local = listOf(delete.copy(asks = false))
        val merged = mergedGroups(listOf(disk, delete), local)
        assertTrue(merged[0].asks)
        assertFalse(merged[1].asks)
        assertEquals(listOf("delete"), offGroupIds(merged))
    }

    @Test
    fun 删掉的内置组不再出现也不再问() {
        val local = listOf(disk.copy(hidden = true, count = 10))
        val merged = mergedGroups(listOf(disk, delete), local)
        assertTrue(merged[0].hidden)
        assertEquals(listOf("disk"), offGroupIds(merged))
    }

    @Test
    fun 内置组这一版变了它自己会回来() {
        val local = listOf(disk.copy(hidden = true, count = 9))
        val merged = mergedGroups(listOf(disk.copy(count = 10)), local)
        assertFalse(merged[0].hidden)
        assertTrue(offGroupIds(merged).isEmpty())
    }

    @Test
    fun 自建组排在最后而且不会被隐藏() {
        val custom = RuleGroup(
            id = "custom:1",
            name = "我自己那几条",
            builtin = false,
            asks = true,
            hidden = true,
            count = 0,
            items = listOf("rm", "mytool"),
        )
        val merged = mergedGroups(listOf(disk), listOf(custom))
        assertEquals(listOf("磁盘操作", "我自己那几条"), merged.map { it.name })
        assertFalse(merged.last().hidden)
    }

    @Test
    fun 自己加的名字只从还问着的组里出并且去重排序() {
        val a = disk.copy(items = listOf("rm", "mytool"))
        val b = delete.copy(items = listOf("rm"), asks = false)
        assertEquals(listOf("mytool", "rm"), extraNames(listOf(a, b)))
    }

    @Test
    fun 被删掉的组里的名字不再下发() {
        val hidden = disk.copy(hidden = true, items = listOf("rm"))
        assertTrue(extraNames(listOf(hidden)).isEmpty())
    }

    @Test
    fun 一轮请求的四个值同源() {
        val state = SecurityState(
            ask = false,
            blacklist = false,
            // 名字只从**还问着**的组里出：关掉的那一组连它下面的名字一起静音。
            groups = listOf(
                disk.copy(items = listOf("mytool")),
                delete.copy(asks = false, items = listOf("rm")),
            ),
        )
        val guard = guardOf(state)
        assertFalse(guard.ask)
        assertFalse(guard.blacklist)
        assertEquals(listOf("delete"), guard.offGroups)
        assertEquals(listOf("mytool"), guard.extra)
    }
}