package com.nmoi.agent.ui.Terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端按键的控制字节（D397）。
 *
 * 这一层错起来**不报错**：按了键，程序里多出一个字符、或者什么都没发生。
 * 所以每条判据两个方向都试。
 */
class TerminalKeysTest {

    private val ctrl = setOf(TerminalKey.Ctrl)
    private val alt = setOf(TerminalKey.Alt)
    private val ctrlAltShift = setOf(TerminalKey.Ctrl, TerminalKey.Alt, TerminalKey.Shift)

    @Test
    fun `默认两排照图`() {
        assertEquals(2, TerminalKeys.DEFAULT_ROWS.size)
        assertEquals(
            listOf("ESC", "TAB", "CTRL", "ALT", "—", "↑", "↵"),
            TerminalKeys.DEFAULT_ROWS[0].map { it.label },
        )
        assertEquals(
            listOf("INS", "END", "SHIFT", ":", "←", "↓", "→"),
            TerminalKeys.DEFAULT_ROWS[1].map { it.label },
        )
        assertEquals(7, TerminalKeys.DEFAULT_COLUMNS)
    }

    @Test
    fun `Ctrl 加字母折成控制字符`() {
        assertArrayEquals(byteArrayOf(0x01), TerminalKeys.bytesForLetter('a', ctrl))
        assertArrayEquals(byteArrayOf(0x03), TerminalKeys.bytesForLetter('c', ctrl))
        assertArrayEquals(byteArrayOf(0x1A), TerminalKeys.bytesForLetter('z', ctrl))
        // 大写折到同一个：Ctrl+Shift+A 与 Ctrl+A 在终端里是同一个 0x01。
        assertArrayEquals(byteArrayOf(0x01), TerminalKeys.bytesForLetter('A', ctrl))
    }

    @Test
    fun `Alt 加字母前缀一个 ESC`() {
        assertArrayEquals(byteArrayOf(0x1B, 'a'.code.toByte()), TerminalKeys.bytesForLetter('a', alt))
        // Ctrl+Alt+a：先 ESC 再 0x01。
        assertArrayEquals(
            byteArrayOf(0x1B, 0x01),
            TerminalKeys.bytesForLetter('a', setOf(TerminalKey.Ctrl, TerminalKey.Alt)),
        )
    }

    @Test
    fun `只有 Shift 时按原样发，保留大小写`() {
        assertArrayEquals(
            byteArrayOf('A'.code.toByte()),
            TerminalKeys.bytesForLetter('A', setOf(TerminalKey.Shift)),
        )
        assertArrayEquals(
            byteArrayOf('a'.code.toByte()),
            TerminalKeys.bytesForLetter('a', setOf(TerminalKey.Shift)),
        )
    }

    @Test
    fun `Ctrl Alt Shift 三个一起也认`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x01), TerminalKeys.bytesForLetter('a', ctrlAltShift))
    }

    @Test
    fun `不是单个字母就什么都不发`() {
        for (text in listOf("", "ab", "1", "中", "!", " ", "\u0000")) {
            assertFalse(text, TerminalKeys.isSingleLetter(text))
        }
        // 数字与中文：`bytesForLetter` 也拒绝（调用方据此取消选中）。
        assertNull(TerminalKeys.bytesForLetter('1', ctrl))
        assertNull(TerminalKeys.bytesForLetter('中', ctrl))
    }

    @Test
    fun `单个字母才认`() {
        assertTrue(TerminalKeys.isSingleLetter("a"))
        assertTrue(TerminalKeys.isSingleLetter("Z"))
    }

    @Test
    fun `不带修饰时普通键发它自己的字节`() {
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeys.bytesFor( TerminalKey.Esc, emptySet()))
        assertArrayEquals(byteArrayOf(0x09), TerminalKeys.bytesFor(TerminalKey.Tab, emptySet()))
        assertArrayEquals(byteArrayOf(0x0D), TerminalKeys.bytesFor(TerminalKey.Enter, emptySet()))
        assertArrayEquals("\u001b[A".toByteArray(), TerminalKeys.bytesFor(TerminalKey.Up, emptySet()))
        assertArrayEquals("\u001b[2~".toByteArray(), TerminalKeys.bytesFor(TerminalKey.Ins, emptySet()))
        assertArrayEquals(byteArrayOf(':'.code.toByte()), TerminalKeys.bytesFor(TerminalKey.Colon, emptySet()))
    }

    @Test
    fun `带修饰的方向键用 CSI 修饰参数`() {
        // xterm：1 + Shift(1) + Alt(2) + Ctrl(4)
        assertArrayEquals("\u001b[1;5A".toByteArray(), TerminalKeys.bytesFor(TerminalKey.Up, ctrl))
        // Alt 单独是 1+2=3（不是 2）：`1` 是基数，Shift 加 1、Alt 加 2、Ctrl 加 4。
        assertArrayEquals("\u001b[1;3B".toByteArray(), TerminalKeys.bytesFor(TerminalKey.Down, alt))
        assertArrayEquals(
            "\u001b[1;8D".toByteArray(),
            TerminalKeys.bytesFor(TerminalKey.Left, ctrlAltShift),
        )
        assertArrayEquals("\u001b[2;5~".toByteArray(), TerminalKeys.bytesFor(TerminalKey.Ins, ctrl))
    }

    @Test
    fun `修饰键与本地键单按没有字节`() {
        for (key in listOf(TerminalKey.Ctrl, TerminalKey.Alt, TerminalKey.Shift)) {
            assertTrue(key.label, key.isModifier)
            assertNull(key.label, TerminalKeys.bytesFor(key, emptySet()))
        }
        for (key in listOf(TerminalKey.Copy, TerminalKey.Paste)) {
            assertTrue(key.label, key.isLocal)
            assertNull(key.label, TerminalKeys.bytesFor(key, emptySet()))
        }
    }

    @Test
    fun `发完清空修饰选中态`() {
        assertTrue(TerminalKeys.afterSend(ctrlAltShift).isEmpty())
    }

    @Test
    fun `自定义布局去重并夹列数`() {
        val (rows, columns) = TerminalKeys.sanitize(
            listOf(
                listOf(TerminalKey.Esc, TerminalKey.Esc, TerminalKey.Tab),
                listOf(TerminalKey.Esc),
                emptyList(),
            ),
            columns = 3,
        )
        assertEquals(5, columns)
        // 第二行只剩一个重复的 Esc，被去掉了。
        assertEquals(1, rows.size)
        assertEquals(listOf(TerminalKey.Esc, TerminalKey.Tab), rows[0])

        val (_, wide) = TerminalKeys.sanitize(TerminalKeys.DEFAULT_ROWS, columns = 20)
        assertEquals(TerminalKeys.MAX_COLUMNS, wide)
    }

    @Test
    fun `空布局退回默认`() {
        val (rows, columns) = TerminalKeys.sanitize(emptyList(), columns = 7)
        assertEquals(TerminalKeys.DEFAULT_ROWS, rows)
        assertEquals(7, columns)
    }

    @Test
    fun `可选键集里没有重复`() {
        assertEquals(TerminalKeys.ALL.size, TerminalKeys.ALL.distinct().size)
        // 默认两排里每颗键都该在可选集里（否则配置页里调不回来）。
        for (key in TerminalKeys.DEFAULT_ROWS.flatten()) {
            assertTrue(key.label, key in TerminalKeys.ALL)
        }
    }
}
