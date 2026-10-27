package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 隔离环境（D164 / D165）：proot 运行时 + Ubuntu rootfs。
 *
 * 两半的东西，来源完全不同，所以分两步：
 *
 * 1. **运行时**（`proot` + `libtalloc.so.2` + `libandroid-shmem.so`）**打在 APK 的 assets 里**，
 *    安装时复制到 `filesDir/envir/bin/` 并 `chmod +x`。做成资源而不是运行时下载，是因为
 *    `.deb` 里是 `data.tar.xz`，JVM 没有自带 XZ 解码器（D164 修正那条）。
 * 2. **rootfs** 运行时下载（`tar.gz`，`GZIPInputStream` 就能解），解包落到 `filesDir/envir/ubuntu/`。
 *
 * 为什么全在 App 私有目录：proot 以**本 App 的 uid** 跑，能碰的就是这个 App 能碰的文件；
 * 换到 `/sdcard` 既拿不到执行位，也会被别的 App 看到。工作区（`filesDir/workspace`）
 * 之后由 `WorkSpace-EnvirShell` 绑进容器，那是下一步。
 *
 * 为什么根目录叫 `envir` 而不是 `env`：`Environment` 在 Java 里是个太容易撞的类名，
 * 目录名跟着同一个词根，看日志时不用猜是哪一个。
 */
internal object EnvirRepository {
    /** 官方源：Ubuntu Base 26.04.1 LTS（arm64），35,092,106 字节。 */
    val UbuntuBase = Source(
        label = "Ubuntu Base 26.04.1 LTS",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/" +
            "ubuntu-base-26.04.1-base-arm64.tar.gz",
        // 校验和是**这一份**镜像的（2026-10-27 实测下载后算的）。官方源对不上就是被换了，
        // 别"大概能用"——解包一条绝对链接就能把别人的文件写进我们的私有目录。
        sha256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
        bytes = 35_092_106L,
    )

    /**
     * 运行时三件。**它们不是 assets，是 `jniLibs`**（D166）。
     *
     * 原因只有一条：Android 10（targetSdk 29+）起，App 私有目录里的文件**不许 exec**——
     * 真机上那句 `Cannot run program "…/files/envir/bin/proot": error=13, Permission denied`
     * 就是这个。只有 APK 的 `lib/<abi>/` 会被装到可执行的 `nativeLibraryDir`，
     * 而安装器只按 `.so` 后缀挑文件、不看内容，所以：
     *
     * - `proot` → `libnmoi_proot.so`（我们按路径 exec 它，叫什么无所谓）；
     * - `libtalloc.so.2` → `libnmoi_talloc.so`（它自带 SONAME `libtalloc.so.2`，
     *   用 `LD_PRELOAD` 按路径加载之后，proot 的 `NEEDED` 能凭 SONAME 认出来）；
     * - `libandroid-shmem.so` → **原名不动**（它没有 SONAME，只能靠文件名找，
     *   所以 `LD_LIBRARY_PATH` 指到同一个目录，让它按名字被找到）。
     *
     * 三个文件的字节内容**一个都没改**（sha256 与上游一致），改的只是磁盘上的名字。
     */
    val Runtime = listOf(
        RuntimeFile(packaged = "libnmoi_proot.so", label = "proot", version = "5.1.107.101"),
        RuntimeFile(packaged = "libnmoi_talloc.so", label = "libtalloc.so.2", version = "2.5.0"),
        RuntimeFile(packaged = "libandroid-shmem.so", label = "libandroid-shmem.so", version = "0.7"),
    )

    data class Source(val label: String, val url: String, val sha256: String?, val bytes: Long)

    data class RuntimeFile(val packaged: String, val label: String, val version: String)

    /** 装好之后写在 `state.json` 里的东西：**界面上要能一条条指出来**，所以字段就是给界面用的。 */
    data class Installed(
        val label: String,
        val url: String,
        val sha256: String?,
        val archiveBytes: Long,
        val installedAt: Long,
        val rootfs: String,
        val files: Int,
        val dirs: Int,
        val symlinks: Int,
        val hardLinks: Int,
        val linkFallbacks: Int,
        val skipped: Int,
    ) {
        val skippedNote: String
            get() = if (skipped == 0) "没有跳过任何条目" else "跳过 $skipped 条（解包报告的清单在日志里）"

        /** 硬链接退化这件事要说出来：不然 `链接 287+0` 看起来像解坏了。 */
        val linkNote: String
            get() = if (linkFallbacks == 0) {
                "硬链接都建成了真链接"
            } else {
                "$linkFallbacks 条硬链接退化成符号链接（这个文件系统不许建硬链接，功能不受影响）"
            }
    }

    // ------------------------------------------------------------------ 路径

    fun envirDir(context: Context): File = File(context.filesDir, "envir")

    /**
     * 运行时所在目录 = APK 的 native lib 目录（安装器解出来的那一份，可执行）。
     * 每次升级 APK 路径都会变，所以**不能缓存**，只能现取。
     */
    fun runtimeDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun rootfsDir(context: Context): File = File(envirDir(context), "ubuntu")

    fun archiveFile(context: Context): File = File(envirDir(context), "ubuntu-base.tar.gz")

    fun stateFile(context: Context): File = File(envirDir(context), "state.json")

    fun prootPath(context: Context): File = File(runtimeDir(context), "libnmoi_proot.so")

    /** 运行时齐不齐。缺哪件就把名字说出来——"跑不起来"这种话没法排查。 */
    fun missingRuntime(context: Context): List<String> =
        Runtime.filterNot { File(runtimeDir(context), it.packaged).isFile }.map { it.label }

    // ------------------------------------------------------------------ 读状态

    /**
     * 装没装：**同时看 `state.json` 和磁盘**。少一样都算没装——
     * 只有 state 而没有 rootfs（用户清了应用缓存）时，界面要是还说"已装"，
     * 后面每一条命令都会以一个看不懂的错收场。
     */
    fun installed(context: Context): Installed? {
        val state = stateFile(context)
        if (!state.isFile) return null
        val parsed = runCatching { JSONObject(state.readText()) }.getOrNull() ?: return null
        val rootfs = File(parsed.optString("rootfs", rootfsDir(context).absolutePath))
        if (!File(rootfs, "usr/bin/env").exists() && !File(rootfs, "bin/sh").exists()) return null
        if (missingRuntime(context).isNotEmpty()) return null
        return Installed(
            label = parsed.optString("label", "未知来源"),
            url = parsed.optString("url", ""),
            sha256 = parsed.optString("sha256", "").ifEmpty { null },
            archiveBytes = parsed.optLong("archive_bytes", 0L),
            installedAt = parsed.optLong("installed_at", 0L),
            rootfs = rootfs.absolutePath,
            files = parsed.optInt("files", 0),
            dirs = parsed.optInt("dirs", 0),
            symlinks = parsed.optInt("symlinks", 0),
            hardLinks = parsed.optInt("hard_links", 0),
            linkFallbacks = parsed.optInt("link_fallbacks", 0),
            skipped = parsed.optInt("skipped", 0),
        )
    }

    /** 运行时三件的实际大小（界面用；缺文件时是 -1）。 */
    fun runtimeBytes(context: Context): List<Pair<RuntimeFile, Long>> = Runtime.map { file ->
        val onDisk = File(runtimeDir(context), file.packaged)
        file to if (onDisk.isFile) onDisk.length() else -1L
    }

    // ------------------------------------------------------------------ 进度

    sealed interface Phase {
        /** 复制 assets 里的运行时。 */
        data object Runtime : Phase

        /** 下载 rootfs。`total <= 0` = 对方没给 Content-Length，只能报已下载量。 */
        data class Download(val bytes: Long, val total: Long) : Phase

        /** 解包。`entries` 是已处理的 tar 条目数（总量要读到结尾才知道，不假装知道）。 */
        data class Extract(val entries: Int) : Phase

        data object Cleanup : Phase
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 装一次。**先下再解**，中途失败就把半成品删干净——半截 rootfs 比没有更糟：
     * 它会让 `installed()` 判成"已装"，然后每条命令都在一个缺文件的系统里跑。
     */
    suspend fun install(
        context: Context,
        source: Source,
        onPhase: (Phase) -> Unit,
    ): Result<Installed> = withContext(Dispatchers.IO) { installBlocking(context, source, onPhase) }

    /**
     * 真正干活的那一半：**只在 IO 上跑**。包装成上面那个 `withContext`，是因为
     * 这里一次要写 110 MB、解 6564 个条目、下 33 MB——从主线程调一次就是一次 ANR，
     * 而调用方（界面）永远会忘记切线程，所以干脆不让它有这个机会。
     */
    private suspend fun installBlocking(
        context: Context,
        source: Source,
        onPhase: (Phase) -> Unit,
    ): Result<Installed> {
        onPhase(Phase.Runtime)
        // 运行时是随 APK 装好的（jniLibs → nativeLibraryDir），这里只核对它在不在。
        val missing = missingRuntime(context)
        if (missing.isNotEmpty()) {
            return Result.failure(
                IOException("这个 APK 里没有 arm64 的运行时（缺 ${missing.joinToString("、")}）"),
            )
        }
        // 老版本（≤ D165）把运行时复制到 files/envir/bin 并 chmod，D166 起不再用；
        // 留着它只会让人以为"proot 在那儿"，顺手清掉。
        runCatching { deleteTree(File(envirDir(context), "bin")) }

        val archive = archiveFile(context)
        val part = File(archive.parentFile, archive.name + ".part")
        onPhase(Phase.Download(0L, source.bytes))
        download(source, part) { done, total -> onPhase(Phase.Download(done, total)) }
            .onFailure {
                part.delete()
                return Result.failure(it)
            }

        val rootfs = rootfsDir(context)
        onPhase(Phase.Extract(0))
        val report = runCatching {
            deleteTree(rootfs)
            TarGz.extract(
                archive = part,
                dest = rootfs,
                options = TarGz.Options(allowAbsoluteLinks = true),
                onEntries = { seen -> onPhase(Phase.Extract(seen)) },
            )
        }.getOrElse { error ->
            runCatching { deleteTree(rootfs) }
            part.delete()
            NLog.w("Envir", "解包失败：${error.message}")
            return Result.failure(IOException("解包失败：${error.message}", error))
        }

        onPhase(Phase.Cleanup)
        val downloaded = part.length()
        runCatching { part.delete() }

        val installed = Installed(
            label = source.label,
            url = source.url,
            sha256 = source.sha256,
            archiveBytes = if (downloaded > 0) downloaded else source.bytes,
            installedAt = System.currentTimeMillis(),
            rootfs = rootfs.absolutePath,
            files = report.files,
            dirs = report.dirs,
            symlinks = report.symlinks,
            hardLinks = report.hardLinks,
            linkFallbacks = report.linkFallbacks,
            skipped = report.skipped.size,
        )
        writeState(context, installed, report.skipped)
        // 归档本身不留：解完了就没用，留着白占 35 MB。
        runCatching { archive.delete() }
        NLog.i(
            "Envir",
            "隔离环境已装：${installed.label}，文件 ${report.files} / 目录 ${report.dirs} / " +
                "链接 ${report.symlinks}+${report.hardLinks}，跳过 ${report.skipped.size}",
        )
        return Result.success(installed)
    }

    private fun download(
        source: Source,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = runCatching {
        target.parentFile?.mkdirs()
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NMoi/0.0.1 (isolated environment installer)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        done += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            val expected = source.sha256
            if (!expected.isNullOrEmpty()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw IOException("校验不符：期望 ${expected.take(16)}…，实际 ${actual.take(16)}…")
                }
            } else {
                NLog.w("Envir", "自定义来源没有校验和，跳过 SHA-256 核对：${source.url}")
            }
            if (total > 0 && done != total) throw IOException("下载不完整：$done / $total 字节")
        } finally {
            connection.disconnect()
        }
    }

    private fun writeState(context: Context, installed: Installed, skipped: List<String>) {
        val json = JSONObject().apply {
            put("label", installed.label)
            put("url", installed.url)
            put("sha256", installed.sha256.orEmpty())
            put("archive_bytes", installed.archiveBytes)
            put("installed_at", installed.installedAt)
            put("rootfs", installed.rootfs)
            put("files", installed.files)
            put("dirs", installed.dirs)
            put("symlinks", installed.symlinks)
            put("hard_links", installed.hardLinks)
            put("link_fallbacks", installed.linkFallbacks)
            put("skipped", installed.skipped)
            put("runtime", JSONObject().apply {
                for (file in Runtime) put(file.label, file.version)
            })
            put("state_version", 1)
        }
        stateFile(context).writeText(json.toString(2))
        if (skipped.isNotEmpty()) {
            // 跳过的清单**只在日志里**：它可能有几十行，放在界面上没人看得完。
            NLog.w("Envir", "解包跳过 ${skipped.size} 条：${skipped.take(20).joinToString("；")}")
        }
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 真机自检：直接起一次 `proot`，把 stdout / stderr / 退出码原样带回来。
     *
     * 为什么要有这个按钮：装完"文件都在"和"真能跑"是两件事，而**只有真机能回答第二件**。
     * 有了它，用户不用 adb、不用等下一版，装完当场就能看到
     * 「proot 5.1.107.101」和容器里的 `os-release`。
     *
     * 环境变量两件都不能少：`LD_LIBRARY_PATH` 指到 `bin/`（proot 的 RUNPATH 写的是 termux 的路径，
     * 那个路径在我们包里不存在）；`PROOT_TMP_DIR` 指到我们自己的可写目录（默认往 `/tmp` 走，
     * 在 Android 上不是可写的地方）。
     */
    data class Probe(
        val command: String,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
    )

    suspend fun probe(
        context: Context,
        args: List<String>,
        timeoutSeconds: Long = 40,
    ): Result<Probe> = withContext(Dispatchers.IO) {
        runCatching {
            val libs = runtimeDir(context)
            val tmp = File(envirDir(context), "tmp").apply { mkdirs() }
            val builder = ProcessBuilder(args)
            builder.directory(File(context.filesDir, "workspace").apply { mkdirs() })
            builder.redirectErrorStream(false)
            // 两道都得给（见 Runtime 那段注释）：
            //   LD_LIBRARY_PATH → 让没有 SONAME 的 libandroid-shmem.so 按**文件名**被找到；
            //   LD_PRELOAD      → 让带 SONAME 的 libtalloc.so.2 先被加载，proot 的 NEEDED 凭 SONAME 认它。
            builder.environment()["LD_LIBRARY_PATH"] = libs.absolutePath
            builder.environment()["LD_PRELOAD"] = File(libs, "libnmoi_talloc.so").absolutePath
            builder.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
            builder.environment()["PROOT_NO_SECCOMP"] = "1"
            val started = System.currentTimeMillis()
            val process = builder.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().use { it.forEachLine { l -> stdout.appendLine(l) } } }
            val errThread = Thread { process.errorStream.bufferedReader().use { it.forEachLine { l -> stderr.appendLine(l) } } }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            outThread.join(2_000)
            errThread.join(2_000)
            Probe(
                command = args.joinToString(" "),
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.toString().take(8_000),
                stderr = stderr.toString().take(8_000),
                durationMs = System.currentTimeMillis() - started,
                timedOut = !finished,
            )
        }
    }

    /** `proot --version`：只证明"三件套能被链接器加载起来"。 */
    fun versionCommand(context: Context): List<String> = listOf(prootPath(context).absolutePath, "--version")

    /** 进容器跑一句话：`os-release` 头两行 + 内核版本（**会是手机的内核**，这正是要让人看见的）。 */
    fun rootfsCommand(context: Context, rootfs: String): List<String> {
        val libs = runtimeDir(context)
        return listOf(
            prootPath(context).absolutePath,
            "-0",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/sh",
            "-c",
            "head -2 /etc/os-release; echo ---; uname -r; echo ---; ls /usr/bin | wc -l",
        ).also { NLog.i("Envir", "自检命令：${it.joinToString(" ")}（LD_LIBRARY_PATH=${libs.absolutePath}）") }
    }

    // ------------------------------------------------------------------ 删除

    /** 连运行时一起删。留一半的"已装"最坑人，所以这里就是**全清**。 */
    suspend fun delete(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            deleteTree(envirDir(context))
            NLog.i("Envir", "隔离环境已删除（含运行时）")
        }.onFailure { NLog.w("Envir", "删除隔离环境失败：${it.message}") }
    }

    /**
     * 递归删除，**不跟随符号链接**。
     *
     * `File.deleteRecursively()` 会跟随：rootfs 里有 `var/run -> /run`、`etc/alternatives/awk -> /usr/bin/mawk`
     * 这种绝对链接，跟随一下就是在删容器外面的东西（真机上 `/run` 有没有东西不好说，
     * 但"删 rootfs 顺手删了别处"这种事一次都不该发生）。`walkFileTree` 默认不跟随。
     */
    private fun deleteTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // 删不掉的（权限、正被占用）记一笔但继续：留一个半截目录，
                    // 下一次 install 会先清空再解，不需要这里死磕。
                    NLog.w("Envir", "删除失败：$file（${exc.message}）")
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** 给界面用：解包器是同一份，但"能不能真的跑起来"只有真机能回答——这句话要写在界面上。 */
    const val NO_KERNEL_NOTE =
        "这里没有内核：容器里 `uname -r` 显示的是手机的内核，`systemd` / `docker` / `iptables` / " +
            "内核模块都用不了；`proot` 是 ptrace 模拟，比原生慢，少数 syscall 不支持。"
}
