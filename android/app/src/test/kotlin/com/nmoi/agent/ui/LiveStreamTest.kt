package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式拼接（0.15ah）。
 *
 * 这几条钉的都是**真机上看着像 bug 的样子**：回答开头凭空消失、同一段出现两遍、
 * 表情符号每隔几个字吞一个。它们都不报错，只能靠对着屏幕猜。
 */
class LiveStreamTest {

    @Test
    fun `新长出来的那一段接在已有正文后面`() {
        // turn_busy 给的是尾部（这里假设只有 2 个字）和总量 4。
        assertEquals("你好世界", growText("你好", 4, "世界"))
        // 尾部里还带着手里的旧字：只取末尾那截新的，别把"好"再写一遍。
        assertEquals("你好世界", growText("你好", 4, "好世界"))
    }

    @Test
    fun `总量没变就不重复追加`() {
        assertEquals("你好", growText("你好", 2, "你好"))
        // 尾部比手里还短，但总量没涨：一个字都不该加。
        assertEquals("你好世界", growText("你好世界", 4, "世界"))
    }

    @Test
    fun `界面卡了一下只拿到尾部时-不假装完整`() {
        // 手里 2 个字，总量 10，尾部只有 3 个字的窗口：中间那 5 个字确实没拿到。
        // 这时候给尾部（而不是把尾部硬接在后面），缺的那截看得见。
        assertEquals("八九十", growText("一二", 10, "八九十"))
    }

    @Test
    fun `表情符号按码点数-不会吞字`() {
        // "😀" 在 Kotlin 里 length=2，在 Rust 的 chars().count() 里是 1。
        val emoji = "😀"
        assertEquals(2, emoji.length)
        assertEquals(1, emoji.codePointCount(0, emoji.length))
        // 手里有一个表情（1 个码点），总量 2，尾部是"表情 + 好"：要接上"好"。
        assertEquals("${emoji}好", growText(emoji, 2, "${emoji}好"))
    }

    @Test
    fun `还没收到任何增量时保持原样`() {
        assertEquals("", growText("", 0, ""))
        assertEquals("已有", growText("已有", 0, "已有"))
    }
}

/**
 * 轮询的状态机（D240）。
 *
 * 这一组钉的是**时序**，不是拼接：`Started` 早于 native 登记，那一小段里
 * `turn_busy` 说"没在跑"。以前把它当终态，于是轮询当场退出、正文一个字都不长；
 * 更糟的是那份快照里还带着上一轮的残字（D236），屏幕上就成了"新消息里写着上一条回答"。
 */
class LivePollTest {

    private fun snapshot(
        running: Boolean,
        round: Int = 1,
        text: String = "",
        reasoning: String = "",
    ) = BusySnapshot(
        running = running,
        round = round,
        textLen = text.codePointCount(0, text.length),
        reasoningLen = reasoning.codePointCount(0, reasoning.length),
        text = text,
        reasoning = reasoning,
    )

    private fun updated(step: LiveStep): LiveStep.Update {
        assertTrue("这一步不该停：$step", step is LiveStep.Update)
        return step as LiveStep.Update
    }

    @Test
    fun `还没登记的空快照-不结束也不动正文`() {
        val reply = LiveReply(round = 0, running = true)
        val step = updated(advanceLive(reply, LivePoll(), snapshot(running = false, round = 0)))
        assertEquals("", step.reply.text)
        assertTrue("这一小段里必须还是 running，否则 effect 会把自己重启掉", step.reply.running)
        assertFalse("没见过 running:true，就不算开始", step.poll.started)
    }

    @Test
    fun `登记之后按增量长`() {
        var reply = LiveReply(round = 0, running = true)
        var poll = LivePoll()
        updated(advanceLive(reply, poll, snapshot(running = true, round = 1, text = "第一段"))).let {
            reply = it.reply
            poll = it.poll
        }
        assertTrue(poll.started)
        assertEquals("第一段", reply.text)
        updated(advanceLive(reply, poll, snapshot(running = true, round = 1, text = "第一段第二段"))).let {
            reply = it.reply
        }
        assertEquals("第一段第二段", reply.text)
    }

    @Test
    fun `见过 running 之后变成 false-才是结束`() {
        val started = LivePoll(started = true)
        val reply = LiveReply(text = "写完了", round = 1, running = true)
        val step = advanceLive(reply, started, snapshot(running = false, round = 1, text = ""))
        assertTrue(step is LiveStep.Stop)
        assertEquals("写完了", (step as LiveStep.Stop).reply.text)
        assertFalse(step.reply.running)
    }

    @Test
    fun `换轮-上一轮收进 finished-正文回到 prefix`() {
        val reply = LiveReply(text = "原正文续了一半", round = 1, running = true, prefix = "原正文")
        val step = updated(
            advanceLive(reply, LivePoll(started = true), snapshot(running = true, round = 2, text = "原正文新一段")),
        )
        assertEquals(1, step.reply.finished.size)
        assertEquals("原正文续了一半", step.reply.finished.first().text)
        assertEquals("原正文新一段", step.reply.text)
    }

    @Test
    fun `缓冲砍头之后-从尾部重来而不是卡住`() {
        val reply = LiveReply(text = "很长很长的一段正文", round = 1, running = true)
        // 总量比手里还少（native 砍了头），但确实有内容：从尾部重来，不假装完整。
        val step = updated(
            advanceLive(reply, LivePoll(started = true), snapshot(running = true, round = 1, text = "尾部")),
        )
        assertEquals("尾部", step.reply.text)
    }

    @Test
    fun `收尾那一瞬的空快照-不清空屏幕上的正文`() {
        val reply = LiveReply(text = "已经写好的", round = 1, running = true)
        val step = updated(advanceLive(reply, LivePoll(started = true), snapshot(running = true, round = 1, text = "")))
        assertEquals("已经写好的", step.reply.text)
    }
}
