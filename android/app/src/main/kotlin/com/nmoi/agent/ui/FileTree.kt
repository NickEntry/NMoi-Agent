package com.nmoi.agent.ui

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 递归删一棵树，**不跟随符号链接**。
 *
 * `File.deleteRecursively()` 会跟随：rootfs 里有 `var/run -> /run` 这种绝对链接，
 * 跟随一下就是在删容器外面的东西；工作区里同样可能是模型自己建的链接。
 * `walkFileTree` 默认不跟随，所以两处都用这一份。
 */
internal fun deleteTreeNoFollow(root: File) {
    if (!root.exists()) return
    Files.walkFileTree(
        root.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                NLog.w("FileTree", "删除失败：$file（${exc.message}）")
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
