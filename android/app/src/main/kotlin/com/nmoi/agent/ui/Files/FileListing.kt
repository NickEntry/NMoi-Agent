package com.nmoi.agent.ui.Files

/** 工作区列表里的一行。`modifiedMs` 为 0 = 拿不到时间（旧接口 / 权限不够）。 */
internal data class FileRow(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val isSymlink: Boolean = false,
)

/** 排序键。用户 2026-11-22 要的三样：名称 / 修改时间 / 大小。 */
internal enum class FileSort { Name, Modified, Size }

/** 顶栏那一颗 `⇅` 的当前状态。`desc` 只是"再点一次"的结果，不是另一套排序。 */
internal data class FileOrder(val sort: FileSort = FileSort.Name, val desc: Boolean = false) {
    /** 再点一次：同一个键就翻转方向，换了键就回到升序。 */
    fun toggled(next: FileSort): FileOrder =
        if (next == sort) copy(desc = !desc) else FileOrder(next, desc = false)

    /** 按钮上那一行字：告诉用户现在是按什么排的。 */
    val label: String
        get() = when (sort) {
            FileSort.Name -> "名称"
            FileSort.Modified -> "修改时间"
            FileSort.Size -> "大小"
        } + if (desc) " ↓" else " ↑"
}

/**
 * 工作区列表的排序（D392）。
 *
 * 三条规矩，都是"错了没人会报 bug、只会觉得别扭"的那一类，所以抽成纯函数钉住：
 *
 * 1. **目录永远在文件前面**。按大小排的时候如果目录跟着大小走，它们会散在整张列表里，
 *    而用户找的是"进哪个文件夹"。
 * 2. **名字排序大小写不敏感**。`README.md` 与 `a.txt` 混排时按字节比会把所有大写排到前面。
 * 3. **时间/大小为 0 的排在最后**（升序时也一样）。0 不是"很小"，是"不知道"，
 *    把它排在最前面等于拿一个空值当最小值。
 */
internal object FileListing {

    fun sorted(rows: List<FileRow>, order: FileOrder): List<FileRow> {
        // 方向只作用在**排序键**上，不能对整个列表 `reversed()`：
        // 那样"不知道时间/大小"的那些会被翻到最前面，而 0 不是"很小"，是"不知道"。
        val key: Comparator<FileRow> = when (order.sort) {
            FileSort.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSort.Modified -> compareBy { it.modifiedMs }
            FileSort.Size -> compareBy { it.sizeBytes }
        }
        val directed = if (order.desc) key.reversed() else key
        return rows.sortedWith(
            compareBy<FileRow> { !it.isDir }
                .thenBy { unknown(order.sort, it) }
                .thenComparator { a, b -> directed.compare(a, b) }
                .thenBy { it.name.lowercase() },
        )
    }

    /** 这一行的排序键是不是"不知道"。是的话永远排在同组的最后。 */
    private fun unknown(sort: FileSort, row: FileRow): Boolean = when (sort) {
        FileSort.Name -> false
        FileSort.Modified -> row.modifiedMs <= 0
        FileSort.Size -> row.sizeBytes <= 0 && !row.isDir
    }

    /**
     * 显示隐藏文件的开关要不要让这一行出现。
     *
     * 判据只看名字开头那一个点：`.nmoi-workspace.json` 是元数据，`.git` 是版本库，
     * 两者对用户是同一类东西——"平时不想看，要看的时候一次全看"。
     */
    fun visible(row: FileRow, showHidden: Boolean): Boolean =
        showHidden || !row.name.startsWith(".")

    /** 顶行那句统计：`12 项 · 3 个文件夹`。空目录另说一句。 */
    fun summary(rows: List<FileRow>): String {
        if (rows.isEmpty()) return "空目录"
        val dirs = rows.count { it.isDir }
        val files = rows.size - dirs
        return when {
            dirs == 0 -> "$files 个文件"
            files == 0 -> "$dirs 个文件夹"
            else -> "${rows.size} 项 · $dirs 个文件夹"
        }
    }

    /**
     * 第二行那一句：目录给条目数与时间，文件给大小与时间。
     *
     * 目录的"大小"在大多数文件系统里是它自己那一条 inode 的字节数（4096 之类），
     * 对用户毫无意义——所以目录那一栏宁可空着，也不显示一个会让人误会的数字。
     */
    fun subtitle(row: FileRow): String = buildString {
        if (!row.isDir && row.sizeBytes > 0) {
            append(humanFileSize(row.sizeBytes))
        }
        if (row.modifiedMs > 0) {
            if (isNotEmpty()) append(" · ")
            append(humanFileTime(row.modifiedMs))
        }
        if (row.isSymlink) {
            if (isNotEmpty()) append(" · ")
            append("链接")
        }
        if (isEmpty()) append(if (row.isDir) "文件夹" else "空文件")
    }
}

/** 字节数 → 人话。工作区那一栏要的是"大概多大"，不是精确到个位。 */
internal fun humanFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 时间 → `11月16日 14:04`；跨年给 `2025年11月16日`。
 *
 * 不写"3 分钟前"：列表里的时间是拿来**对顺序**的，相对时间在滚动过程中会变，
 * 而"刚刚"和"1 分钟前"对排序毫无帮助。
 */
internal fun humanFileTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val now = java.time.ZonedDateTime.now(zone)
    return if (time.year == now.year) {
        "${time.monthValue}月${time.dayOfMonth}日 ${"%02d".format(time.hour)}:${"%02d".format(time.minute)}"
    } else {
        "${time.year}年${time.monthValue}月${time.dayOfMonth}日"
    }
}
