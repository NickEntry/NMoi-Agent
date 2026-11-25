package com.nmoi.agent.ui

/**
 * 存储空间的分项账本（D426）。
 *
 * 用户 2026-11-25 给的那 12 张截图里，根页是一段占比条加十行明细。这里只做**算账**
 * 那一层：给一堆"哪个文件多大"，算出十项各自多少、各占几成、"其他"是多少。
 *
 * 为什么单独抽出来：这一层错起来完全不显眼。占比算错只是条子短一截，用户看不出；
 * 而"其他"算错会**把某一类文件藏起来**——那正是铁律②要挡的东西（存了但看不见）。
 * 抽成纯函数才能钉住"十项之和 + 其他 == 总和"这条不变量。
 */
internal enum class StorageKind(
    val title: String,
    /** 这一项点进去之后给不给删除。`nmoi.db` 不给：删了就没有聊天记录了。 */
    val deletable: Boolean,
    /** 占比条上的颜色（ARGB，界面那边转成 Compose 的 `Color`）。 */
    val argb: Long,
) {
    Images("图片", true, 0xFF4C8DFF),
    Files("文件", true, 0xFF20A0A0),
    Chat("聊天记录", false, 0xFFE07B39),
    Cache("缓存", true, 0xFF3AA6B9),
    Logs("日志", true, 0xFF8C8C8C),
    Other("其他", true, 0xFFB0B0B8),
    Workspace("工作区文件", true, 0xFF1F8A6D),
    Envir("沙箱环境", true, 0xFFD2691E),
    Skills("技能", true, 0xFF7C5CBF),
    SessionFiles("会话文件", true, 0xFFD6336C),
}

/** 一个文件在盘上的归属。`relative` 是相对 App 私有目录（`filesDir` / `cacheDir`）的路径。 */
internal data class UsageEntry(
    val kind: StorageKind,
    val relative: String,
    val bytes: Long,
)

/** 一项的账：多少字节、多少文件。 */
internal data class UsageBucket(
    val kind: StorageKind,
    val bytes: Long,
    val files: Int,
) {
    /** 占比（0..1）。总数为 0 时给 0——除以 0 得到 NaN 会让占比条画不出来。 */
    fun share(total: Long): Float =
        if (total <= 0L) 0f else (bytes.toDouble() / total.toDouble()).toFloat()
}

/**
 * 把"盘上的文件"分成十项。
 *
 * `relative` 是相对 App 私有目录的路径，形如 `sessions/<id>/images/x.jpg`、
 * `workspace/src/main.rs`、`logs/context.log`。判定顺序**有讲究**：
 * `sessions/<id>/chat/attachments/` 要先进「文件」，再轮到「会话文件」，
 * 否则用户发来的附件会被算成 AI 的输出。
 */
internal object StorageUsage {

    /** 一个文件归哪一项。认不出就是 `Other`——**不猜**，也不丢。 */
    fun kindOf(relative: String): StorageKind {
        val path = relative.trimStart('/')
        if (path.isEmpty()) return StorageKind.Other
        // 会话目录底下按子目录再分一次：images / chat/attachments / 其余。
        if (path.startsWith("sessions/")) {
            val rest = path.removePrefix("sessions/")
            val afterId = rest.substringAfter('/', "")
            return when {
                afterId.startsWith("images/") -> StorageKind.Images
                // 用户发来的原文件（D423 起落这一层）。
                afterId.startsWith("chat/attachments/") -> StorageKind.Files
                // 归档原文（`/archive/<hex>.txt`）与 AI 写的输出都算会话文件：
                // 它们都是"这一条会话自己的东西"，与工作区无关。
                afterId.startsWith("chat/") -> StorageKind.SessionFiles
                afterId.startsWith("archive/") -> StorageKind.SessionFiles
                afterId.startsWith("tmp/") -> StorageKind.SessionFiles
                afterId.startsWith("audio/") -> StorageKind.SessionFiles
                else -> StorageKind.SessionFiles
            }
        }
        return when {
            path.startsWith("workspace/") || path.startsWith("workspaces/") -> StorageKind.Workspace
            path.startsWith("envir/") -> StorageKind.Envir
            path.startsWith("skills/") -> StorageKind.Skills
            path.startsWith("logs/") -> StorageKind.Logs
            path.startsWith("cache/") -> StorageKind.Cache
            path == "nmoi.db" || path.startsWith("nmoi.db-") -> StorageKind.Chat
            else -> StorageKind.Other
        }
    }

    /**
     * 十项的账，按 [StorageKind] 的声明顺序返回（根页那一行的顺序就按它）。
     *
     * 每一项都在，哪怕它是 0——少一行会让用户以为那一类东西不存在。
     */
    fun buckets(entries: List<UsageEntry>): List<UsageBucket> {
        val bytes = LongArray(StorageKind.entries.size)
        val files = IntArray(StorageKind.entries.size)
        for (entry in entries) {
            val index = entry.kind.ordinal
            bytes[index] += entry.bytes
            files[index] += 1
        }
        return StorageKind.entries.mapIndexed { index, kind ->
            UsageBucket(kind = kind, bytes = bytes[index], files = files[index])
        }
    }

    /** 总和。 */
    fun total(entries: List<UsageEntry>): Long = entries.sumOf { it.bytes }

    /**
     * 占比条上要画的那几段：只给**非零**的项，按大小降序。
     *
     * 零字节的项不进条子：一段 0 宽的颜色块在视觉上等于"缝隙"，会让相邻两段看着不连续。
     */
    fun segments(buckets: List<UsageBucket>, total: Long): List<UsageBucket> =
        buckets.filter { it.bytes > 0 }.sortedByDescending { it.bytes }

    /** 图例最多列几项。剩下的并进"其他"，否则一屏放不下两行。 */
    const val LEGEND_ROWS = 5

    /**
     * 图例。前 [LEGEND_ROWS] 项单独列，其余合并成一行 `其他 N 项`。
     *
     * 合并之后点它跳到「其他」子页——不是"看不见"，是"要多点一次"。
     */
    fun legend(buckets: List<UsageBucket>): List<UsageBucket> {
        val nonZero = buckets.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
        if (nonZero.size <= LEGEND_ROWS) return nonZero
        val head = nonZero.take(LEGEND_ROWS - 1)
        val tail = nonZero.drop(LEGEND_ROWS - 1)
        return head + UsageBucket(
            kind = StorageKind.Other,
            bytes = tail.sumOf { it.bytes },
            files = tail.sumOf { it.files },
        )
    }

    /**
     * "可清理"是多少：缓存 + 日志 + 空目录。
     *
     * **不把图片与文件算进来**：那是用户的东西，删不删由他自己决定，
     * 界面上写一句"可清理 500 MB"然后一键删掉，是最坏的一种"帮你省事"。
     */
    fun reclaimable(buckets: List<UsageBucket>): Long =
        buckets.filter { it.kind == StorageKind.Cache || it.kind == StorageKind.Logs }
            .sumOf { it.bytes }
}
