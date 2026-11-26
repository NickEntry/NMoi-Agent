package com.nmoi.agent.ui

import java.io.File

/**
 * 存储子页里「树」那一层要算的东西（D434）。
 *
 * 四个树形页（沙箱环境 / 技能 / 工作区文件 / 会话文件）点进去都是一层一层的目录，
 * 所以"这一层有哪些条目、每个多大、哪个能删"是同一套算法，只有**根在哪**和
 * **哪一层能给删**不同。抽成纯逻辑（只碰 `java.io.File`，不碰 `Context`）才能在本机单测里
 * 钉住三条容易错又看不出来的判据：
 *
 * 1. 上一层报的数 == 这一层条目之和。少算一处，用户就会看到"300 MB 的目录点进去只有 2 MB"，
 *    而他不会报 bug，只会以为东西丢了（铁律②的另一面）。
 * 2. `标题 · 短id` 里那个短 id 是**唯一**能把两条同名会话分开的东西。少了它，
 *    用户会以为其中一条被复制成了两条。
 * 3. 哪一层给不给删。`rootfs` 里删一个文件容器就起不来；技能里删一个 `SKILL.md`
 *    整个技能就废了——这两种坏法在界面上都看不出来。
 */
internal data class TreeMeasure(val bytes: Long, val files: Int)

/** 树里的一行。[file] 是它真实的落点，点进去时的 `root` 就是它。 */
internal data class TreeRow(
    val name: String,
    val file: File,
    val isDir: Boolean,
    val bytes: Long,
    val files: Int,
    val modifiedMs: Long,
    /** 副标题里的徽标：「默认」工作区之类。null = 不显示。 */
    val badge: String? = null,
    val deletable: Boolean = false,
)

internal object StorageTree {

    /**
     * 会话 id 的前 8 位。
     *
     * UUIDv7 的前 8 位带时间戳高位——同一毫秒建的两条会话会撞；所以它只用来**看**，
     * 不用来认（认人靠完整 id）。界面上那句「同一标题的两条会话要能分开」靠的是
     * "标题 + 短 id"这个组合，两条同标题的会话短 id 相同概率极低，而真撞上了
     * 两行仍然各自指向自己的目录（点击用的是 `File`，不是这个名字）。
     */
    fun shortSessionId(id: String): String = id.trim().take(8)

    /** 会话一级的名字：`标题 · 短id`。标题为空写「（没有标题）」——不替它编一个。 */
    fun sessionLabel(title: String?, id: String): String {
        val name = title?.trim().orEmpty().ifEmpty { "（没有标题）" }
        val short = shortSessionId(id)
        return if (short.isEmpty()) name else "$name · $short"
    }

    /**
     * 递归量一个目录：总字节 + 文件数。**不跟符号链接**。
     *
     * `rootfs/var/run` 这类绝对链接指回宿主，跟过去会把容器外面的东西算进来
     * （`StorageRepository.scan` 踩过同一个坑，判据一样：`canonicalPath` 去重）。
     */
    fun measure(root: File): TreeMeasure {
        if (!root.exists()) return TreeMeasure(0L, 0)
        if (!root.isDirectory) {
            return TreeMeasure(runCatching { root.length() }.getOrDefault(0L), 1)
        }
        var bytes = 0L
        var files = 0
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!seen.add(key)) continue
            for (child in dir.listFiles().orEmpty()) {
                when {
                    child.isDirectory -> pending.addLast(child)
                    child.isFile -> {
                        bytes += runCatching { child.length() }.getOrDefault(0L)
                        files += 1
                    }
                    // 符号链接 / 设备节点：不算，也不算错——它们本来就不是文件。
                }
            }
        }
        return TreeMeasure(bytes, files)
    }

    /**
     * 列一层。目录在前、名字大小写不敏感升序（与工作区那一套排序同一条规矩：
     * 用户在这一页找的是"进哪个文件夹"）。
     *
     * [depth] 是这一层条目在第几层（一级是 1），只为算 [TreeRow.deletable]。
     * 大小**现递归算**：删完文件返回上一层时数字要当场变，缓存了就会是旧的。
     */
    fun children(dir: File, kind: StorageKind, depth: Int): List<TreeRow> =
        dir.listFiles().orEmpty()
            .filter { it.isDirectory || it.isFile }
            .map { child -> row(child, kind, depth) }
            .sortedWith(compareBy<TreeRow> { !it.isDir }.thenBy { it.name.lowercase() })

    /** 一行（目录递归量大小，文件量自身）。 */
    fun row(file: File, kind: StorageKind, depth: Int): TreeRow {
        val isDir = file.isDirectory
        val size = if (isDir) measure(file) else TreeMeasure(
            runCatching { file.length() }.getOrDefault(0L),
            1,
        )
        return TreeRow(
            name = file.name,
            file = file,
            isDir = isDir,
            bytes = size.bytes,
            files = size.files,
            modifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
            deletable = canDelete(kind, depth, isDir),
        )
    }

    /**
     * 这一行给不给删。
     *
     * | 项 | 一级（根下面那一层） | 再往下 |
     * |---|---|---|
     * | 沙箱环境 | 不给（只有 `rootfs` / `tmp` 两行） | **不给**——删一个文件容器就起不来 |
     * | 技能 | 给（删整个技能，走确认卡） | **不给**——少一个 `SKILL.md` 整个技能就废了 |
     * | 工作区文件 | 不给（删工作区在「工作区」页） | 给（走工作区那套越界检查） |
     * | 会话文件 | 不给（删会话在侧边栏长按） | 只给删**文件**；`chat/` 整个删掉会让库里的附件行变成孤儿 |
     */
    fun canDelete(kind: StorageKind, depth: Int, isDir: Boolean): Boolean = when (kind) {
        StorageKind.Envir -> false
        StorageKind.Skills -> depth == 1 && isDir
        StorageKind.Workspace -> depth >= 2
        StorageKind.SessionFiles -> depth >= 2 && !isDir
        else -> false
    }
}

/** 三种页面形状。混在一个模子里的代价是每一种都别扭（见 `notes/design/storage-tree-plan.md`）。 */
internal enum class StorageShape { Tree, Cards, Flat }

internal val StorageKind.shape: StorageShape
    get() = when (this) {
        StorageKind.Envir, StorageKind.Skills, StorageKind.Workspace, StorageKind.SessionFiles ->
            StorageShape.Tree
        StorageKind.Cache, StorageKind.Logs, StorageKind.Other, StorageKind.Chat ->
            StorageShape.Cards
        StorageKind.Images, StorageKind.Files -> StorageShape.Flat
    }
