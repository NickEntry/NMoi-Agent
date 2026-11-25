package com.nmoi.agent.ui.Files

/**
 * 一个会话的「对话文件」里某一项属于哪一边（D393）。
 *
 * 用户 2026-11-22 的原话是三个页签：附件 / 输出 / 工作区。
 * 前两个**是同一个目录的两种归属**——附件是用户发来的（库里有 `attachments` 记录），
 * 输出是 AI 写的（盘上有、库里没有）。分开的判据只有一条：**库里有没有那条记录**。
 *
 * 为什么这条要单独抽出来钉住：分错了的表现是"同一份文件在两个页签里各出现一次"
 * （重叠），或者"盘上有一个文件但两个页签都看不到"（漏）。后者正是铁律②
 * ——不得有"存了但看不见"的东西——被破坏的样子，而它在界面上完全不像错误。
 */
internal enum class DialogFileSide {
    /** 用户发来的：库里有记录。 */
    Attachment,

    /** AI 写的：盘上有、库里没有。 */
    Output,
}

/**
 * 会话 `chat/` 目录里的一个文件（盘上那一份）。
 *
 * `path` 是**相对 `chat/` 的整段路径**（D423）：用户发来的在 `attachments/` 那一层，
 * AI 写的在根上或它自己建的子目录里。`name` 只是文件名，显示用。
 */
internal data class ChatFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/**
 * 库里的一条附件记录（只取判归属要用的字段）。
 *
 * `path` 是盘上那份的**相对 `chat/` 路径**（D423），由绝对路径与 chat 根算出来；
 * 老行（BLOB 那批、或还没落盘的）是 `null`。判归属时它是第一条判据——
 * 比"名字像不像"硬得多。
 */
internal data class AttachmentRef(
    val id: String,
    val name: String,
    val kind: String,
    val path: String? = null,
)

internal object DialogFiles {

    /**
     * 落盘名：`<附件 id 的后 8 位>-<原名>`。
     *
     * 这个形状是 `put_file_attachment` 定的（`format!("{short}-{name}")`，
     * `short = &id[id.len()-8..]`）。界面按它反推，比"名字对得上就算"准得多——
     * 后者会把 AI 写的 `xxa.txt` 认成用户发来的 `a.txt`。
     */
    fun diskName(id: String, name: String): String {
        val short = id.takeLast(8)
        return "$short-${sanitizeName(name)}"
    }

    /**
     * 与 `crates/storage/src/repo.rs` 的 `sanitize_file_name` 同一套规则：
     * 取最后一段、控制字符与 `/\:*?"<>|` 换成 `_`、最多 120 字符、去掉首尾的点、
     * 空名给 `file`。
     *
     * 这里再写一份是因为界面要拿它反推落盘名。**两处必须一致**——
     * 改 Rust 那一份的时候要一起改，否则"用户发来的文件"会被判成 AI 写的。
     */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val bad = "/\\:*?\"<>|"
        val out = StringBuilder()
        for (char in base) {
            if (out.length >= 120) break
            out.append(if (char.isISOControl() || char in bad) '_' else char)
        }
        val trimmed = out.toString().trim('.', ' ').trim()
        return trimmed.ifEmpty { "file" }
    }

    /**
     * 把盘上的文件分成两堆。
     *
     * 判据按硬度排：
     * 1. **路径**（D423）：库里 `attachments.path` 与盘上那一份的相对路径逐字相等。这是
     *    `put_file_attachment` 自己写下去的，不靠任何反推，所以它排第一。
     * 2. **落盘名**：`<附件 id 后 8 位>-<原名>`。给没有 `path` 的老行兜底
     *    （BLOB 那批、或写库与落盘之间断过的行）。
     * 3. **形状**：盘上名是 `<8 位十六进制>-<库里某个原名>`，也算附件——兜的是
     *    "落盘时原名被 `sanitize_file_name` 改过"那一类。
     *
     * 都对不上的一律归输出。**宁可把一份用户文件误判成输出，也不要把一份 AI 写的文件
     * 藏进附件里**——前者只是分类不准（两个页签在同一屏上，用户还看得到它），
     * 后者是"库里没有、界面上也不给"，那才是铁律②要挡的。
     */
    fun split(
        onDisk: List<ChatFile>,
        attachments: List<AttachmentRef>,
    ): Map<DialogFileSide, List<ChatFile>> {
        // 只有 `kind = file` 的才落到 `chat/`；图片在 `images/`，不该拿来对名字。
        val files = attachments.filter { it.kind == "file" }
        val byPath = files.mapNotNull { it.path?.takeIf(String::isNotBlank) }.toSet()
        val exact = files
            .filter { it.name.isNotBlank() }
            .map { diskName(it.id, it.name) }
            .toSet()
        // 库里的原名与"按落盘规则洗过一遍"的那一份都收进来：落盘时原名会被
        // `sanitize_file_name` 改一遍（非法字符换 `_`），两边对不上时退到这一份。
        val names = files
            .filter { it.name.isNotBlank() }
            .flatMap { listOf(it.name, sanitizeName(it.name)) }
            .toSet()

        fun isAttachment(file: ChatFile): Boolean {
            if (file.path in byPath) return true
            if (file.name in exact) return true
            // `<8 位十六进制>-<原名>`：前缀那 8 位就是 id 的后 8 位，全是小写十六进制。
            val dash = file.name.indexOf('-')
            if (dash != 8) return false
            val prefix = file.name.substring(0, dash)
            if (!prefix.all { it in '0'..'9' || it in 'a'..'f' }) return false
            return file.name.substring(dash + 1) in names
        }

        val (attach, output) = onDisk.partition(::isAttachment)
        return mapOf(
            DialogFileSide.Attachment to attach.sortedBy { it.name.lowercase() },
            DialogFileSide.Output to output.sortedBy { it.name.lowercase() },
        )
    }

    /**
     * 三个页签各自的空态那一句。**说下一步做什么**，不写"暂无数据"。
     *
     * 附件与输出都是"这个会话里还没有"；工作区是另一回事（它跟这个会话的文件无关，
     * 空着只是那个目录还空）。
     */
    fun emptyHint(side: DialogFileSide): String = when (side) {
        DialogFileSide.Attachment -> "还没有发过文件。输入区的 ＋ 里可以选文件。"
        DialogFileSide.Output -> "AI 还没写过文件。让它写一个，就会出现在这里。"
    }

    /** 页签标题上那个数。0 时不显示数字（"附件 0"看着像出错）。 */
    fun badge(count: Int): String? = if (count <= 0) null else count.toString()
}
