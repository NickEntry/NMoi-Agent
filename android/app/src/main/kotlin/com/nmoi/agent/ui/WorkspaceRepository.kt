package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 工作区（D234）：**用户建的目录，可复用、天生共享**。
 *
 * 与 `/chat`（`sessions/<id>/chat`）是两件事：
 * - 工作区是"项目"那一层。一个助手指向一个工作区，同一份文件会被好几个会话看见——
 *   这正是要的：换个会话接着干同一个活，文件还在。
 * - `/chat` 只属于这一条会话：附件、工具产物都落在那儿，模型要看就自己用命令读。
 *
 * 一个工作区 = `files/workspaces/<id>/` 一个目录，名字写在它自己的
 * `.nmoi-workspace.json` 里（不是另存一份索引）：目录在、名字就在，删目录不会留下一条
 * "看着有、点进去是空的"记录。**id 一旦生成就不再变**，改名只改那个文件——
 * 助手文档里存的是 id，所以改名不需要去改任何助手的配置。
 *
 * 默认工作区是老的 `files/workspace`（id 固定 `default`）：升级上来文件还在老地方，
 * 谁都不用搬。它不能改名、不能删——它是老会话最后的落脚点。
 */
internal data class Workspace(
    val id: String,
    val name: String,
    val dir: File,
    val files: Int,
    val bytes: Long,
) {
    val isDefault: Boolean get() = id == WorkspaceRepository.DEFAULT_ID

    /** 容器里看到的位置（D234：工作区挂在 `/workspace`）。 */
    val guestPath: String get() = "/workspace"

    /** 界面上那一行副标题：`3 个文件 · 12.4 KB`。 */
    val summary: String
        get() = if (files == 0) "空目录" else "$files 个文件 · ${humanBytes(bytes)}"
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

internal object WorkspaceRepository {
    const val DEFAULT_ID = "default"
    private const val META = ".nmoi-workspace.json"

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")

    fun defaultRoot(context: Context): File = File(context.filesDir, "workspace").apply { mkdirs() }

    fun workspacesRoot(context: Context): File =
        File(context.filesDir, "workspaces").apply { mkdirs() }

    fun dir(context: Context, id: String): File =
        if (id.isBlank() || id == DEFAULT_ID) defaultRoot(context)
        else File(workspacesRoot(context), id)

    /** 新 id：`w` + 36 进制时间戳，与助手的 `a…` 同形，一眼能看出是哪一类。 */
    fun newId(): String = "w" + System.currentTimeMillis().toString(36)

    /**
     * 一个 id 解析成工作区。**目录不在了就退回默认**，并记一行——
     * 让模型绑到一个空目录上，比让它看见默认工作区更糟（用户会以为文件全丢了）。
     */
    fun resolve(context: Context, id: String?): Workspace {
        val wanted = id?.trim().orEmpty()
        if (wanted.isEmpty() || wanted == DEFAULT_ID) return measure(context, DEFAULT_ID)
        val dir = dir(context, wanted)
        if (!dir.isDirectory) {
            NLog.w("Tools", "工作区 $wanted 不在了，退回默认工作区")
            return measure(context, DEFAULT_ID)
        }
        return measure(context, wanted)
    }

    /** 全部工作区：默认那个排最前，其余按名字。 */
    fun list(context: Context): List<Workspace> {
        val rows = mutableListOf(measure(context, DEFAULT_ID))
        val children = workspacesRoot(context).listFiles().orEmpty()
        for (child in children) {
            if (!child.isDirectory) continue
            val id = child.name
            if (!ID_PATTERN.matches(id)) {
                NLog.w("Tools", "跳过形状不对的工作区目录：$id")
                continue
            }
            rows += measure(context, id)
        }
        return rows.sortedWith(compareBy<Workspace> { !it.isDefault }.thenBy { it.name })
    }

    suspend fun create(context: Context, rawName: String): Result<Workspace> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = cleanName(rawName) ?: throw IOException("名字不能为空")
                val id = newId()
                val dir = dir(context, id)
                if (!dir.mkdirs()) throw IOException("建不出目录：${dir.absolutePath}")
                writeMeta(dir, name)
                NLog.i("Tools", "新建工作区 $id（$name）")
                measure(context, id)
            }
        }

    /**
     * 改名：只改目录里的那份元数据，**不动 id**。
     *
     * 不动 id 是有意的：助手文档里存的是 id，改名要是换了 id，所有绑过它的助手
     * 都会指向一个不存在的目录——那看起来就是"文件没了"。
     */
    suspend fun rename(context: Context, id: String, rawName: String): Result<Workspace> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (id == DEFAULT_ID) throw IOException("默认工作区不能改名")
                val dir = dir(context, id)
                if (!dir.isDirectory) throw IOException("这个工作区不在了")
                val name = cleanName(rawName) ?: throw IOException("名字不能为空")
                writeMeta(dir, name)
                NLog.i("Tools", "工作区 $id 改名为 $name")
                measure(context, id)
            }
        }

    /** 删一个工作区。**默认那个不删**：老会话还指着它。 */
    suspend fun delete(context: Context, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (id == DEFAULT_ID) throw IOException("默认工作区不能删")
            val dir = dir(context, id)
            if (!dir.isDirectory) throw IOException("这个工作区不在了")
            deleteTreeNoFollow(dir)
            NLog.i("Tools", "已删工作区 $id")
        }
    }

    /** 有几个文件夹着它（给删除确认卡用）：**说清会没掉什么**。 */
    fun boundAssistants(assistants: List<AssistantEntry>, id: String): List<String> =
        assistants.filter { it.workspaceId == id }.map { it.name.ifBlank { it.id } }

    /** 数一遍：**不跟随符号链接**（模型可能在工作区里建链接，跟一下就把别处也算进来了）。 */
    private fun measure(context: Context, id: String): Workspace {
        val dir = dir(context, id)
        var files = 0
        var bytes = 0L
        if (dir.isDirectory) {
            runCatching {
                Files.walkFileTree(
                    dir.toPath(),
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            val hidden = file.fileName.toString().startsWith(".")
                            if (attrs.isRegularFile && !hidden) {
                                files += 1
                                bytes += attrs.size()
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException) =
                            FileVisitResult.CONTINUE
                    },
                )
            }
        }
        return Workspace(
            id = id,
            name = if (id == DEFAULT_ID) "共享工作区" else readName(dir) ?: id,
            dir = dir,
            files = files,
            bytes = bytes,
        )
    }

    private fun readName(dir: File): String? = runCatching {
        val file = File(dir, META)
        if (!file.isFile) return@runCatching null
        val text = JSONObject(file.readText()).optString("name", "").trim()
        text.ifEmpty { null }
    }.getOrNull()

    private fun writeMeta(dir: File, name: String) {
        File(dir, META).writeText(JSONObject().put("name", name).toString(2))
    }

    /** 名字只挡掉"当目录名会出事"的东西；中文、空格、标点都留着。 */
    private fun cleanName(raw: String): String? {
        val name = raw.trim()
            .replace("/", "／")
            .replace("\u0000", "")
            .take(40)
            .trim()
        return name.ifEmpty { null }
    }
}
