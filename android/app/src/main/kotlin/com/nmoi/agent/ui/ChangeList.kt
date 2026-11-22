package com.nmoi.agent.ui

/**
 * 变更文件列表的**展示逻辑**（D381）。
 *
 * 抽出来的理由跟 `SwipeReveal` 一样：这里每一条都是"错了看不出来"的判断
 * （排哪几行、`+N` 是几、哪些标"无法预览"），而它们全是纯函数，
 * 能在本机 JUnit 里钉住，不必等真机。
 */

/** 一行变更。`path` 是给人看的完整路径（`/workspace/a/b.rs`）。 */
internal data class ChangeRow(
    val path: String,
    /** `true` = 这一轮新建的。 */
    val created: Boolean,
    /** 新增行数。`null` = 不算（二进制 / 太大）。 */
    val added: Int?,
    /** 删掉行数。 */
    val removed: Int?,
    /** 差异文本。`null` = 没有（二进制 / 太大）。 */
    val diff: String?,
    /** 文件现在还在不在盘上。点开之前要判——判完可能已经没了。 */
    val exists: Boolean = true,
)

internal object ChangeList {
    /**
     * 消息气泡下面**直接铺出来**几行。
     *
     * 用户 2026-11-22 定的是三排；一排放一个芯片，所以就是三条。
     * 这个数只影响气泡，不影响"变更的文件"页——那一页是全的。
     */
    const val INLINE_ROWS = 3

    /**
     * 按路径 A–Z 排。
     *
     * 用户明确要的顺序。**不按时间、不按大小**：找文件的时候人记的是名字。
     * 大小写不敏感：`README.md` 和 `a.rs` 混在一起时，按字节比会把所有大写排到前面。
     */
    fun sorted(rows: List<ChangeRow>): List<ChangeRow> =
        rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })

    /** 气泡里铺出来的那几条。 */
    fun inline(rows: List<ChangeRow>): List<ChangeRow> = sorted(rows).take(INLINE_ROWS)

    /**
     * `+N` 里的 N。<= 0 表示不显示那个芯片。
     *
     * 算的是"没铺出来的条数"，不是"总条数减三"——后者在只有两条时会给出负数。
     */
    fun overflow(rows: List<ChangeRow>): Int = (rows.size - INLINE_ROWS).coerceAtLeast(0)

    /**
     * 这个文件能不能预览。
     *
     * 判据是**有没有差异**，不是文件类型：二进制（图片 / 编译产物）没有差异文本，
     * 界面上标「无法预览」而不是点开一个空面板。
     */
    fun previewable(row: ChangeRow): Boolean = row.diff != null

    /**
     * 只显示文件名，还是带目录？
     *
     * 气泡里只给文件名（一行放不下完整路径，截断了反而更难看懂）；
     * 详情页给完整路径。
     */
    fun name(path: String): String = path.substringAfterLast('/').ifEmpty { path }

    /**
     * 路径中段省略：保留头尾。
     *
     * 用户要的是"哪一台的哪个目录"——掐掉末尾就只剩一串没用的前缀，
     * 所以省略号放中间。`max` 小于 12 时不省略（那种长度省略完什么都不剩）。
     */
    fun middleEllipsis(path: String, max: Int = 48): String {
        if (path.length <= max || max < 12) return path
        val keep = max - 1
        val head = keep * 2 / 3
        val tail = keep - head
        return path.take(head) + "…" + path.takeLast(tail)
    }

    /**
     * 点一个文件时该怎么办。
     *
     * **先判存在**（用户 2026-11-22 的要求）：那一轮跑完到用户点开之间，
     * 文件完全可能已经被删掉或改名。这时不打开空面板，只弹一句提示。
     */
    fun onTap(row: ChangeRow): TapAction =
        if (!row.exists) TapAction.Missing else TapAction.Open(row)

    sealed interface TapAction {
        /** 文件已经不在了：标题栏下方弹一句，向上划出消失。 */
        data object Missing : TapAction

        /** 打开详情（路径 / 差异 / 打开方式 / 分享 / 导出）。 */
        data class Open(val row: ChangeRow) : TapAction
    }

    /**
     * 从一份差异里数 `+` / `-`（`+++` / `---` / `@@` 不算）。
     *
     * 与 core 的 `changes::count_changes` 同一个判据。这里再有一份是为了
     * 让界面能在**只有差异文本**（没有 core 给的数字）时也能显示计数——
     * 但**能用 core 的就用 core 的**：两处各数一次迟早对不上。
     */
    fun count(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        for (line in diff.lineSequence()) {
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) continue
            when {
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return added to removed
    }
}
