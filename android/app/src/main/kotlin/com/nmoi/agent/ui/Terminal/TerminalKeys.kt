package com.nmoi.agent.ui.Terminal

/**
 * 终端底栏的按键模型（D397）。
 *
 * 用户 2026-11-24 的要求：**按下变成选中状态，然后可以输入一个字母跟它一起发**，
 * 支持多选组合（`Ctrl+Alt+Shift+?`）；输入的不是单个字母就取消选中、什么都不做。
 *
 * 这一份只管"算出该发哪些字节"。真发字节由 `ShellSession.write` 走 FFI
 * （控制字节不是合法 UTF-8，只能按字节过桥）。
 *
 * # 为什么单字母才算
 *
 * `Ctrl+Shift+1` 在大多数终端里没有对应控制字符（`Ctrl+1` 也没有）。
 * 与其猜一个（比如发 `1`），不如**什么都不发**——猜错的表现是"按了键，程序里
 * 多出一个莫名其妙的字符"，而那种错用户根本不会报 bug，只会觉得这个终端有问题。
 */
internal enum class TerminalKey(val label: String) {
    Esc("ESC"),
    Tab("TAB"),
    Ctrl("CTRL"),
    Alt("ALT"),
    Shift("SHIFT"),
    Ins("INS"),
    End("END"),
    Minus("—"),
    Colon(":"),
    Up("↑"),
    Down("↓"),
    Left("←"),
    Right("→"),
    Enter("↵"),
    Slash("/"),
    Pipe("|"),
    Tilde("~"),

    /** 复制当前屏（由界面处理，不发字节）。 */
    Copy("复制"),

    /** 粘贴到输入行（由界面处理，不发字节）。 */
    Paste("粘贴"),
    ;

    /** 修饰键：按下只切选中态，不立即发送。 */
    val isModifier: Boolean
        get() = this == Ctrl || this == Alt || this == Shift

    /** 由界面自己处理、不走字节通道的键。 */
    val isLocal: Boolean
        get() = this == Copy || this == Paste
}

internal object TerminalKeys {

    /**
     * 用户 2026-11-24 定的默认两排（照 Termux 那张图）：
     *
     * ```
     * ESC  TAB  CTRL  ALT  —   ↑   ↵
     * INS  END  SHIFT  :   ←   ↓   →
     * ```
     *
     * 顺序照图，不重排：手上记住的是位置，重排一次就要重新记。
     */
    val DEFAULT_ROWS: List<List<TerminalKey>> = listOf(
        listOf(
            TerminalKey.Esc,
            TerminalKey.Tab,
            TerminalKey.Ctrl,
            TerminalKey.Alt,
            TerminalKey.Minus,
            TerminalKey.Up,
            TerminalKey.Enter,
        ),
        listOf(
            TerminalKey.Ins,
            TerminalKey.End,
            TerminalKey.Shift,
            TerminalKey.Colon,
            TerminalKey.Left,
            TerminalKey.Down,
            TerminalKey.Right,
        ),
    )

    /** 可配的列数范围（用户 2026-11-24：可以调 5~9 列）。 */
    const val MIN_COLUMNS = 5
    const val MAX_COLUMNS = 9
    const val DEFAULT_COLUMNS = 7

    /**
     * 可选按键全集（配置页里挑）。
     *
     * 只列"按下去有明确字节"的键，加上两个本地动作（复制 / 粘贴）。
     * 不列 `Ctrl+C` 这种组合——组合是"按修饰键再按字母"拼出来的，不是一颗键。
     */
    val ALL: List<TerminalKey> = listOf(
        TerminalKey.Esc,
        TerminalKey.Tab,
        TerminalKey.Ctrl,
        TerminalKey.Alt,
        TerminalKey.Shift,
        TerminalKey.Ins,
        TerminalKey.End,
        TerminalKey.Up,
        TerminalKey.Down,
        TerminalKey.Left,
        TerminalKey.Right,
        TerminalKey.Enter,
        TerminalKey.Minus,
        TerminalKey.Colon,
        TerminalKey.Slash,
        TerminalKey.Pipe,
        TerminalKey.Tilde,
        TerminalKey.Copy,
        TerminalKey.Paste,
    )

    /** 这些键自带字节，不参与修饰组合。 */
    private fun baseBytes(key: TerminalKey): ByteArray? = when (key) {
        TerminalKey.Esc -> byteArrayOf(0x1B)
        TerminalKey.Tab -> byteArrayOf(0x09)
        TerminalKey.Ins -> "\u001b[2~".toByteArray()
        TerminalKey.End -> "\u001b[F".toByteArray()
        TerminalKey.Up -> "\u001b[A".toByteArray()
        TerminalKey.Down -> "\u001b[B".toByteArray()
        TerminalKey.Right -> "\u001b[C".toByteArray()
        TerminalKey.Left -> "\u001b[D".toByteArray()
        TerminalKey.Enter -> byteArrayOf(0x0D)
        TerminalKey.Minus -> byteArrayOf('-'.code.toByte())
        TerminalKey.Colon -> byteArrayOf(':'.code.toByte())
        TerminalKey.Slash -> byteArrayOf('/'.code.toByte())
        TerminalKey.Pipe -> byteArrayOf('|'.code.toByte())
        TerminalKey.Tilde -> byteArrayOf('~'.code.toByte())
        // 修饰键、本地键：单独按下没有字节。
        else -> null
    }

    /**
     * 按下一颗**普通键**时该发什么。
     *
     * 有修饰键选中时，普通键要和它拼：`Ctrl` + `↑` = `\u001b[1;5A`。
     * 没选中修饰键就是它自己的字节。
     *
     * 返回 `null` = 这颗键单按没有字节（修饰键 / 本地键），调用方不该调这一条。
     */
    fun bytesFor(key: TerminalKey, modifiers: Set<TerminalKey>): ByteArray? {
        if (key.isModifier || key.isLocal) return null
        if (modifiers.isEmpty()) return baseBytes(key)
        // 带修饰键的方向键 / 功能键用 CSI 的修饰参数（xterm 那套）。
        val code = modifierCode(modifiers)
        val letter = when (key) {
            TerminalKey.Up -> "A"
            TerminalKey.Down -> "B"
            TerminalKey.Right -> "C"
            TerminalKey.Left -> "D"
            TerminalKey.End -> "F"
            TerminalKey.Ins -> return "\u001b[2;${code}~".toByteArray()
            TerminalKey.Esc -> return byteArrayOf(0x1B)
            TerminalKey.Tab -> return byteArrayOf(0x09)
            TerminalKey.Enter -> return byteArrayOf(0x0D)
            else -> return baseBytes(key)
        }
        return "\u001b[1;${code}${letter}".toByteArray()
    }

    /** xterm 的修饰参数：1 + Shift(1) + Alt(2) + Ctrl(4)。 */
    private fun modifierCode(modifiers: Set<TerminalKey>): Int {
        var code = 1
        if (TerminalKey.Shift in modifiers) code += 1
        if (TerminalKey.Alt in modifiers) code += 2
        if (TerminalKey.Ctrl in modifiers) code += 4
        return code
    }

    /**
     * 有修饰键选中时，输入的那**一个字母**该发什么。
     *
     * 三条判据，都在这一处：
     * 1. **必须是单个字母**（`a`–`z` / `A`–`Z`）。不是就返回 `null`，调用方取消选中、什么都不发。
     * 2. `Ctrl` 把字母折成控制字符（`Ctrl+A` = 0x01，`Ctrl+Z` = 0x1A）。
     * 3. `Alt` 在前面加一个 ESC（那是终端的既定做法，不是我们编的）。
     *
     * `Shift` 对**字母**没有额外字节：`Ctrl+Shift+A` 与 `Ctrl+A` 在终端里是同一个
     * 0x01（大小写由终端自己决定怎么显示）。这条要写在这里，否则以后会有人
     * 想给它拼一个 `CSI` 前缀——那对字母不成立。
     */
    fun bytesForLetter(letter: Char, modifiers: Set<TerminalKey>): ByteArray? {
        if (!letter.isLetter() || letter.code > 0x7F) return null
        val lower = letter.lowercaseChar()
        val out = ArrayList<Byte>()
        // Alt：前缀一个 ESC。
        if (TerminalKey.Alt in modifiers) out.add(0x1B)
        if (TerminalKey.Ctrl in modifiers) {
            // 字母折控制字符：`a`(0x61) → 0x01。大小写折到同一个（Ctrl+Shift+A = Ctrl+A）。
            val control = (lower.code - 'a'.code + 1).toByte()
            out.add(control)
        } else {
            // 没有 Ctrl 时按原样发（保留大小写——Shift 对字母的唯一作用就是这个）。
            for (byte in letter.toString().toByteArray()) out.add(byte)
        }
        return if (out.isEmpty()) null else out.toByteArray()
    }

    /**
     * 用户点了一颗普通键之后，修饰键选中态该怎么办。
     *
     * **发完就清空**：`Ctrl` 按着不放会让下一次敲普通字符也带上 Ctrl，
     * 而用户按 `Ctrl+C` 之后想的是"接下来正常打字"。
     */
    fun afterSend(modifiers: Set<TerminalKey>): Set<TerminalKey> = emptySet()

    /**
     * 这个字符算不算"输入了一个字母"。
     *
     * 单独抽出来是因为判据有两个方向：`'a'` 算、`'1'` 不算、`'中'` 不算、
     * 空串不算。判错了的表现是"按了修饰键，然后什么都没发生"——最难查。
     */
    fun isSingleLetter(text: String): Boolean =
        text.length == 1 && text[0].isLetter() && text[0].code <= 0x7F

    /** 校验一份自定义布局：去重、丢掉不认识的、列数夹到 5~9。 */
    fun sanitize(rows: List<List<TerminalKey>>, columns: Int): Pair<List<List<TerminalKey>>, Int> {
        val seen = mutableSetOf<TerminalKey>()
        val cleaned = rows.map { row ->
            row.filter { key -> seen.add(key) }
        }.filter { it.isNotEmpty() }
        val fixed = if (cleaned.isEmpty()) DEFAULT_ROWS else cleaned
        return fixed to columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }
}
