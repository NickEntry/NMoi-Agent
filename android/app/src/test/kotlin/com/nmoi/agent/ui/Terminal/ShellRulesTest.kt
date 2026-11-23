package com.nmoi.agent.ui.Terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端的命令判定与历史（D395）。
 *
 * 判错的表现是"按了没反应"（全屏程序卡住）或"莫名其妙被拦"。
 * 前者在真机上最难查，所以两个方向都要试。
 */
class ShellRulesTest {

    @Test
    fun `全屏程序被拦下`() {
        for (command in listOf("vim", "vim a.txt", "top", "htop -d 1", "less README.md", "tmux new")) {
            assertNotNull(command, ShellRules.blockedReason(command))
        }
        // 拦下来那一句要说清替代办法，不能只说"不支持"。
        val reason = ShellRules.blockedReason("vim a.txt")!!
        assertTrue(reason, reason.contains("cat"))
    }

    @Test
    fun `普通命令放行`() {
        for (command in listOf("ls", "ls -la", "cat a.txt", "make", "npm install", "python x.py", "git status")) {
            assertNull(command, ShellRules.blockedReason(command))
        }
    }

    @Test
    fun `裸的解释器会进交互模式，带脚本就放行`() {
        assertNotNull(ShellRules.blockedReason("python"))
        assertNotNull(ShellRules.blockedReason("python3"))
        assertNotNull(ShellRules.blockedReason("node"))
        assertNotNull(ShellRules.blockedReason("sh"))
        // 带脚本 / 带 -c 是正常执行。
        assertNull(ShellRules.blockedReason("python x.py"))
        assertNull(ShellRules.blockedReason("python3 -c 'print(1)'"))
        assertNull(ShellRules.blockedReason("node build.js"))
    }

    @Test
    fun `第一个词才算，字符串里的不算`() {
        assertNull(ShellRules.blockedReason("echo vim"))
        assertNull(ShellRules.blockedReason("grep vim a.txt"))
        assertNotNull(ShellRules.blockedReason("vim"))
    }

    @Test
    fun `路径前缀与包装词都能认出来`() {
        assertNotNull(ShellRules.blockedReason("/usr/bin/vim"))
        assertNotNull(ShellRules.blockedReason("env vim"))
        assertNotNull(ShellRules.blockedReason("command top"))
        assertNull(ShellRules.blockedReason("env FOO=1 ls"))
    }

    @Test
    fun `环境变量赋值前缀跳过`() {
        assertNull(ShellRules.blockedReason("FOO=1 ls"))
        assertNotNull(ShellRules.blockedReason("FOO=1 vim"))
    }

    @Test
    fun `切词认引号`() {
        assertEquals(listOf("bash", "my script.sh"), ShellRules.split("bash \"my script.sh\""))
        assertEquals(listOf("echo", "a b"), ShellRules.split("echo 'a b'"))
        assertEquals(listOf("a", "b"), ShellRules.split("  a   b  "))
        assertEquals(emptyList<String>(), ShellRules.split(""))
        // 转义的空格不切。
        assertEquals(listOf("echo", "a b"), ShellRules.split("echo a\\ b"))
    }

    @Test
    fun `引号里的分号不切词`() {
        assertEquals(listOf("echo", "a;b"), ShellRules.split("echo \"a;b\""))
    }

    @Test
    fun `历史不记连续重复`() {
        var history = emptyList<String>()
        history = ShellRules.pushHistory(history, "ls")
        history = ShellRules.pushHistory(history, "ls")
        assertEquals(listOf("ls"), history)
        history = ShellRules.pushHistory(history, "pwd")
        history = ShellRules.pushHistory(history, "ls")
        assertEquals(listOf("ls", "pwd", "ls"), history)
    }

    @Test
    fun `历史忽略空命令与首尾空白`() {
        var history = ShellRules.pushHistory(emptyList(), "   ")
        assertTrue(history.isEmpty())
        history = ShellRules.pushHistory(emptyList(), "  ls  ")
        assertEquals(listOf("ls"), history)
    }

    @Test
    fun `历史封顶`() {
        var history = emptyList<String>()
        for (index in 1..(ShellRules.HISTORY_MAX + 10)) {
            history = ShellRules.pushHistory(history, "cmd$index")
        }
        assertEquals(ShellRules.HISTORY_MAX, history.size)
        assertEquals("cmd11", history.first())
        assertEquals("cmd${ShellRules.HISTORY_MAX + 10}", history.last())
    }

    @Test
    fun `上下键在历史里走`() {
        val history = listOf("a", "b", "c")
        // 还没进历史（index = size）：按上到最新一条。
        assertEquals(2 to "c", ShellRules.stepHistory(history, 3, up = true))
        assertEquals(1 to "b", ShellRules.stepHistory(history, 2, up = true))
        assertEquals(0 to "a", ShellRules.stepHistory(history, 1, up = true))
        // 到头了不动。
        assertEquals(0 to "a", ShellRules.stepHistory(history, 0, up = true))
        // 往下走到头回到空。
        assertEquals(3 to "", ShellRules.stepHistory(history, 2, up = false))
        assertEquals(1 to "b", ShellRules.stepHistory(history, 0, up = false))
    }

    @Test
    fun `空历史上下键不动`() {
        assertEquals(0 to "", ShellRules.stepHistory(emptyList(), 0, up = true))
        assertEquals(0 to "", ShellRules.stepHistory(emptyList(), 0, up = false))
    }

    @Test
    fun `结果摘要说清退出码与耗时`() {
        val ok = ShellResult("ls", 0, "a\n", "", false, false, 202)
        assertEquals("exit 0 · 202ms", ok.summary())
        val killed = ShellResult("x", null, "", "", false, false, 5)
        assertTrue(killed.summary().contains("信号"))
        val timedOut = ShellResult("x", null, "", "", false, true, 600_000)
        assertTrue(timedOut.summary().startsWith("超时"))
        val cut = ShellResult("x", 0, "", "", true, false, 1)
        assertTrue(cut.summary().contains("截断"))
    }

    @Test
    fun `结果为空也要有摘要`() {
        // 跑成功了但什么都没说，也必须有那一行——否则用户以为命令没执行。
        val quiet = ShellResult("true", 0, "", "", false, false, 3)
        assertTrue(quiet.isEmpty)
        assertEquals("exit 0 · 3ms", quiet.summary())
    }
}
