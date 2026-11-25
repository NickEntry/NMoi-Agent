package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 App 私有目录里有什么读出来（D426）。
 *
 * 只读**自己家**：`filesDir` 与 `cacheDir`，走 `File.listFiles()` 递归。不需要越界检查——
 * 这两个目录是 App 私有的，越界的意思在这里不存在；而工作区那套 `nmoi_workspace_*`
 * 是给"模型与用户一起改文件"用的，拿来量磁盘占用是另一件事（它还会把忽略清单算进去）。
 *
 * 两件事必须做对：
 *
 * 1. **符号链接不跟**：`filesDir` 里可能有指向 `nativeLibraryDir` 的链接（`envir/lib/` 那两个），
 *    跟过去会把 APK 里的 13 MB 算成沙箱环境。判据是 `canonicalPath` 去重。
 * 2. **每一项都要能对上账**：`StorageUsage.kindOf` 认不出的归「其他」，不丢。
 */
internal object StorageRepository {

    /** 一次扫描的结果。`entries` 里每一条都带"归哪一项"。 */
    data class Scan(
        val entries: List<UsageEntry>,
        /** 扫的时候跳过的路径（符号链接、读不了的目录）。界面上要能说出来。 */
        val skipped: List<String>,
    ) {
        val buckets: List<UsageBucket> get() = StorageUsage.buckets(entries)
        val total: Long get() = StorageUsage.total(entries)
    }

    /**
     * 扫一遍。**不报进度**：App 私有目录正常几千个文件，一秒钟内扫完；
     * 真有上万个（工作区里放着 node_modules）时界面上那个转圈也只转一两秒。
     */
    suspend fun scan(context: Context): Scan = withContext(Dispatchers.IO) {
        val roots = listOf(
            // `filesDir` 的路径前缀是空的：相对路径就相对它。
            context.filesDir to "",
            // `cacheDir` 的每一条都加 `cache/` 前缀，否则它会被认成「其他」。
            context.cacheDir to "cache/",
        )
        val entries = mutableListOf<UsageEntry>()
        val skipped = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for ((root, prefix) in roots) {
            if (!root.isDirectory) continue
            walk(root, prefix, entries, skipped, seen)
        }
        Scan(entries = entries, skipped = skipped)
    }

    private fun walk(
        dir: File,
        prefix: String,
        out: MutableList<UsageEntry>,
        skipped: MutableList<String>,
        seen: MutableSet<String>,
    ) {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        // 环与重复都挡在这里：`envir/lib/` 里那两个链接指回 nativeLibraryDir，
        // 跟过去会把 APK 的内容算进沙箱环境。
        if (!seen.add(key)) {
            skipped += "$prefix（已经走过，或是链接）"
            return
        }
        for (file in dir.listFiles().orEmpty()) {
            val relative = if (prefix.isEmpty()) file.name else "$prefix${file.name}"
            when {
                file.isDirectory -> walk(file, "$relative/", out, skipped, seen)
                file.isFile -> {
                    val bytes = runCatching { file.length() }.getOrDefault(0L)
                    out += UsageEntry(
                        kind = StorageUsage.kindOf(relative),
                        relative = relative,
                        bytes = bytes,
                    )
                }
                // 符号链接 / 设备节点：不算，但要说出来。
                else -> skipped += relative
            }
        }
    }

    /**
     * 某一项底下的文件清单（子页用）。返回的是**绝对路径 + 相对路径 + 大小 + 时间**，
     * 因为子页要能点开、要能删、要能排序。
     */
    data class Item(
        val relative: String,
        val file: File,
        val bytes: Long,
        val modifiedMs: Long,
        /** 目录行：子页里点进去是它底下那一层。 */
        val isDir: Boolean,
    )

    /**
     * 列某一项的文件。
     *
     * `directories = true` 的那几项（工作区 / 沙箱 / 技能）只列**第一层**：
     * 一屏摊平整棵树没有意义，用户要的是"进哪个目录"。
     */
    suspend fun items(context: Context, kind: StorageKind): List<Item> = withContext(Dispatchers.IO) {
        val scan = scan(context)
        val matched = scan.entries.filter { it.kind == kind }
        matched.mapNotNull { entry ->
            val file = absolute(context, entry.relative) ?: return@mapNotNull null
            Item(
                relative = entry.relative,
                file = file,
                bytes = entry.bytes,
                modifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
                isDir = false,
            )
        }.sortedByDescending { it.bytes }
    }

    /** 相对路径 → 绝对文件。`cache/` 前缀回到 `cacheDir`，其余回 `filesDir`。 */
    fun absolute(context: Context, relative: String): File? {
        val clean = relative.trimStart('/')
        if (clean.isEmpty()) return null
        if (clean.startsWith("cache/")) {
            return File(context.cacheDir, clean.removePrefix("cache/"))
        }
        return File(context.filesDir, clean)
    }

    /**
     * 删掉一组文件（多选删除）。**返回真的删掉了几个**。
     *
     * 不删目录：目录里的文件由调用方逐个传进来。这样"删了一半"是可数的，
     * 而不是一句"删掉了 3 个目录"然后盘上还留着空壳。
     */
    suspend fun delete(context: Context, relatives: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                var removed = 0
                for (relative in relatives) {
                    val file = absolute(context, relative) ?: continue
                    // 库文件不在这里拦：`kindOf` 已经把 `nmoi.db` 归到「聊天记录」，
                    // 而那一项在界面上不给删除按钮。这里再加一道会掩盖界面上的错。
                    if (file.isFile && file.delete()) removed += 1
                }
                removed
            }
        }
}
