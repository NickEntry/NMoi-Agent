package com.nmoi.agent.ui.Terminal

/**
 * 终端里那条命令"能不能跑"的判断（D395）。
 *
 * 第一版是**命令回显式**，没有 PTY。所以有些命令注定跑不了——而"跑不了"必须
 * **当场说清楚**，不能让用户看着一个卡住的屏。判据写在这里、单测钉住，
 * 因为它在真机上的表现是"按了没反应"，那是最难查的一种。
 */
internal object ShellRules {

    /**
     * 需要真 TTY 才能工作的全屏程序。跑它们的结果不是报错，是**卡住**——
     * 它们会等一个永远不来的输入，而界面还在等它的输出。
     */
    private val FULLSCREEN = setOf(
        "vim", "vi", "nvim", "nano", "emacs", "pico", "joe", "micro",
        "top", "htop", "atop", "btop", "iotop",
        "less", "more", "most", "man",
        "watch", "tmux", "screen", "byobu",
        "mc", "ranger", "nnn", "lazygit", "tig",
        "ncdu", "alsamixer", "nmtui", "dialog", "whiptail",
    )

    /**
     * 需要交互输入的程序。它们不卡在"等按键"上，而是等一行 stdin——
     * 而第一版把 stdin 接到 `/dev/null`，所以它们会立刻读到 EOF 并报错。
     * 报错比卡住好，但"明确说这个终端不支持"仍然更省事。
     */
    private val INTERACTIVE = setOf(
        "sudo", "su", "passwd", "ssh", "sftp", "telnet", "ftp",
        "python", "python3", "node", "irb", "psql", "mysql", "sqlite3",
        "read", "select", "bash", "sh", "zsh", "fish",
    )

    /**
     * 这条命令要不要拦下来。
     *
     * 判据是**第一个词**（去掉前面的环境变量赋值与 `sudo` 之类）。
     * 只认第一段是有意的：`echo vim` 里的 `vim` 只是一个字符串，
     * 拦下它会让用户莫名其妙。
     *
     * 返回 `null` = 能跑；非空 = 拦下来的原因（给用户看的那一句）。
     */
    fun blockedReason(command: String): String? {
        val words = split(command)
        // 跳过 `FOO=bar` 这类前缀赋值，以及 `command` / `env` 这类包装词。
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (word.contains('=') && !word.startsWith("-")) {
                index += 1
                continue
            }
            if (word == "command" || word == "env" || word == "nohup" || word == "time") {
                index += 1
                // `time -p` 这种带参数的包装词，参数也要跳。
                while (index < words.size && words[index].startsWith("-")) index += 1
                continue
            }
            break
        }
        val first = words.getOrNull(index)?.substringAfterLast('/') ?: return null

        if (first in FULLSCREEN) {
            return "这个终端不支持全屏程序（$first）：它要等按键，而这里一行一条命令。" +
                "要看文件用 cat，要看进程用 ps。"
        }
        if (first in INTERACTIVE) {
            // 裸的 `python` / `node` / `sh` 是 REPL，会卡在等输入；
            // 带参数时（`python x.py`）是正常执行，不拦。
            val hasArgument = words.drop(index + 1).any { !it.startsWith("-") }
            if (!hasArgument) {
                return "$first 不带参数会进交互模式，这个终端不支持。给一个脚本或 -c 再跑。"
            }
        }
        return null
    }

    /**
     * 切词：**引号感知**。`bash "my script.sh"` 是三个词，`"my script.sh"` 是一个。
     *
     * 与 `crates/core/src/attribution.rs` 的切词同一个判据（D381）：
     * 不认引号的话，`bash "my script.sh"` 会被切成 `bash` + `"my` + `script.sh"`，
     * 第一个词还是对的，但带空格的路径就会判错。
     */
    fun split(command: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (char in command) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true

                quote != null -> if (char == quote) quote = null else current.append(char)

                char == '"' || char == '\'' -> quote = char

                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                }

                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** 命令历史最多留多少条。环形，超了丢最旧的。 */
    const val HISTORY_MAX = 200

    /**
     * 加一条历史。**连续重复的不重复记**（与 shell 的 `ignoredups` 同一种体贴）：
     * 一条命令连跑五遍，历史里留五条只会把真正想找的那条挤走。
     *
     * `index == -1` = 不在历史里走（用户已经在编辑一条新命令）。
     */
    fun pushHistory(history: List<String>, command: String): List<String> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return history
        if (history.lastOrNull() == trimmed) return history
        val next = history + trimmed
        return if (next.size <= HISTORY_MAX) next else next.takeLast(HISTORY_MAX)
    }

    /**
     * 上下键在历史里走。`index` 是当前位置（`history.size` = 还没进历史）。
     *
     * 返回 `(新的 index, 该填进输入框的文本)`。到头了就不动——绕回最旧那条
     * 会让人以为自己按错了。
     */
    fun stepHistory(history: List<String>, index: Int, up: Boolean): Pair<Int, String> {
        if (history.isEmpty()) return index to ""
        val size = history.size
        val at = index.coerceIn(0, size)
        return if (up) {
            if (at <= 0) 0 to history[0] else (at - 1) to history[at - 1]
        } else {
            if (at >= size) size to "" else (at + 1) to if (at + 1 == size) "" else history[at + 1]
        }
    }
}
