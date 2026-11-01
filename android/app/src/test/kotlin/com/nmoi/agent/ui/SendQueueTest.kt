package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送队列（用户 2026-10-31 定的语义）。
 *
 * 这一层要钉住的是**顺序与边界**：队列里的顺序就是真实发送顺序，
 * 取错一条就是把 A 的话发到了 B 的上下文里。
 */
class SendQueueTest {

    private val session = "s-test"
    private val other = "s-other"

    @Test
    fun `先进先出-取出来就从队列里消失`() {
        SendQueue.forget(session)
        assertEquals(1, SendQueue.enqueue(session, "第一句"))
        assertEquals(2, SendQueue.enqueue(session, "第二句"))
        assertEquals(2, SendQueue.count(session))

        assertEquals("第一句", SendQueue.takeNext(session)?.text)
        assertEquals(1, SendQueue.count(session))
        assertEquals("第二句", SendQueue.takeNext(session)?.text)
        // 空了要返回 null（"自动继续"的终止条件），而不是一个空壳。
        assertNull(SendQueue.takeNext(session))
        SendQueue.forget(session)
    }

    @Test
    fun `两个会话的队列互不影响`() {
        SendQueue.forget(session)
        SendQueue.forget(other)
        SendQueue.enqueue(session, "A")
        SendQueue.enqueue(other, "B")

        assertEquals(listOf("A"), SendQueue.items(session).map { it.text })
        assertEquals(listOf("B"), SendQueue.items(other).map { it.text })
        SendQueue.forget(session)
        SendQueue.forget(other)
    }

    @Test
    fun `上移下移到头不再动`() {
        SendQueue.forget(session)
        SendQueue.enqueue(session, "一")
        SendQueue.enqueue(session, "二")
        SendQueue.enqueue(session, "三")
        val items = SendQueue.items(session)
        val first = items[0]
        val last = items[2]

        // 第一条往上、最后一条往下：到头了，返回 false（界面据此把按钮灰掉）。
        assertFalse(SendQueue.move(session, first.id, -1))
        assertFalse(SendQueue.move(session, last.id, 1))

        // 第三条往上挪两格 → 到队首。
        assertTrue(SendQueue.move(session, last.id, -1))
        assertTrue(SendQueue.move(session, last.id, -1))
        assertEquals(listOf("三", "一", "二"), SendQueue.items(session).map { it.text })
        SendQueue.forget(session)
    }

    @Test
    fun `删掉一条不影响别的`() {
        SendQueue.forget(session)
        SendQueue.enqueue(session, "一")
        SendQueue.enqueue(session, "二")
        val middle = SendQueue.items(session)[0]

        assertTrue(SendQueue.remove(session, middle.id))
        assertEquals(listOf("二"), SendQueue.items(session).map { it.text })
        // 删第二次：已经没了，返回 false（不是异常）。
        assertFalse(SendQueue.remove(session, middle.id))
        SendQueue.forget(session)
    }

    @Test
    fun `阻塞是按会话记的-解除之后才继续`() {
        SendQueue.forget(session)
        SendQueue.forget(other)
        SendQueue.enqueue(session, "A")

        SendQueue.block(session, true)
        assertTrue(SendQueue.isBlocked(session))
        // 阻塞不影响"取"，只影响"要不要自动取"——判断在 TurnRunner 那边。
        assertEquals("A", SendQueue.peek(session)?.text)

        SendQueue.block(session, false)
        assertFalse(SendQueue.isBlocked(session))
        assertFalse(SendQueue.isBlocked(other))
        SendQueue.forget(session)
    }

    @Test
    fun `清空与会话被删时不留孤行`() {
        SendQueue.forget(session)
        SendQueue.enqueue(session, "一")
        SendQueue.clear(session)
        assertTrue(SendQueue.items(session).isEmpty())

        SendQueue.enqueue(session, "二")
        SendQueue.block(session, true)
        SendQueue.forget(session)
        assertTrue(SendQueue.items(session).isEmpty())
        assertFalse(SendQueue.isBlocked(session))
    }

    @Test
    fun `每次变更都会推一次版本号`() {
        SendQueue.forget(session)
        val before = SendQueue.version.value
        SendQueue.enqueue(session, "一")
        assertTrue(SendQueue.version.value > before)
        SendQueue.clear(session)
    }
}