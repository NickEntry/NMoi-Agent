package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 老会话从"共享工作区"迁到"各自的 /chat"（D232）。
 *
 * 为什么要有这一步：D231 让新会话各有一个 `sessions/<id>/chat`，老会话升级上来留在
 * `files/workspace`（不然文件会凭空消失）。但**留着不算迁完**——用户下次打开一条老会话，
 * 它看到的还是那个谁都能看见的目录。所以启动时问一次。
 *
 * 迁移的语义只有一种，而且要在界面上说清：
 * **每条老会话得到一份现在的共享目录副本**，之后各写各的。共享目录本身不动
 * （还有别的会话在用它，删了就是丢东西）。
 *
 * 为什么不"把共享目录搬给最近的那一条"：其余会话打开时会发现文件全没了——
 * 那不是迁移，那是丢东西。
 */
internal object WorkspaceMigration {
    private const val PREFS = "nmoi.local"

    /** 问过一次（迁了或选了"以后再说"）就不再问。 */
    private const val KEY_DONE = "workspace.migrated.v12"

    /** 一次迁移要做的事：迁哪些会话、共享目录里现在有多少东西。 */
    data class Plan(
        val sessionIds: List<String>,
        val files: Int,
        val bytes: Long,
    ) {
        /** 给界面看的大小，保留一位小数。 */
        val humanSize: String
            get() = when {
                bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun decided(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

    fun markDecided(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 共享目录里有多少文件、多少字节。目录不存在 = 0。 */
    fun sharedStats(context: Context): Pair<Int, Long> {
        val root = ToolingRepository.sharedRoot(context)
        var files = 0
        var bytes = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                files += 1
                bytes += file.length()
            }
        }
        return files to bytes
    }

    /**
     * 现在该不该问。
     *
     * 三个条件都成立才弹：**共享目录里真的有东西**（空目录迁不迁没区别，问了只是打扰）、
     * 还有 `shared` 的会话、并且没问过。少一个都不弹。
     */
    fun plan(context: Context, sharedSessionIds: List<String>): Plan? {
        if (decided(context)) return null
        if (sharedSessionIds.isEmpty()) return null
        val (files, bytes) = sharedStats(context)
        if (files == 0) return null
        return Plan(sharedSessionIds, files, bytes)
    }

    /**
     * 真的迁：每条会话复制一份共享目录，成功之后才把它切成 `chat`。
     *
     * 顺序是**先复制、再改模式**：反过来的话复制失败会留下一条"以为迁了、其实是空的"会话。
     * 已经存在的同名文件**不覆盖**（复制过来的是快照，用户后来在新目录里改过的那份更值钱）。
     */
    suspend fun apply(
        context: Context,
        databasePath: String,
        plan: Plan,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val source = ToolingRepository.sharedRoot(context)
        var migrated = 0
        var failed = 0
        for (id in plan.sessionIds) {
            val target = ToolingRepository.chatRoot(context, id)
            val copied = runCatching { copyTree(source, target) }
            if (copied.isFailure) {
                failed += 1
                NLog.w("Tools", "迁移复制失败：$id —— ${copied.exceptionOrNull()?.message}")
                continue
            }
            val mode = ChatRepository.setWorkspaceMode(databasePath, id, "chat")
            if (mode.isFailure) {
                failed += 1
                NLog.w("Tools", "迁移改模式失败：$id —— ${mode.exceptionOrNull()?.message}")
                continue
            }
            migrated += 1
        }
        // 问过一次就不再问：迁了一半也算问过（剩下的可以在会话菜单里单独切）。
        markDecided(context)
        if (failed == 0) {
            Result.success(migrated)
        } else {
            Result.failure(
                NmoiFfiException("migrate", "迁了 $migrated 条，$failed 条没成（原因在日志）"),
            )
        }
    }

    /** 逐文件复制，**不覆盖已存在的**。目录结构跟着建。 */
    private fun copyTree(source: File, target: File) {
        if (!source.isDirectory) return
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
            if (relative.isEmpty()) return@forEach
            val destination = File(target, relative)
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                if (!destination.exists()) {
                    file.copyTo(destination, overwrite = false)
                }
            }
        }
    }
}

/**
 * 启动时那一次询问（D232）。
 *
 * 三条规矩：**必须先处理**（它要改库里的模式）、**说清会复制多少东西**（"迁一下"听起来
 * 很轻，实际可能是几十 MB × 会话数）、**默认不迁**（默认动作是"以后再说"——
 * 迁移不是修 bug，用户没点头就不动他的文件）。
 */
@Composable
internal fun WorkspaceMigrationSheet(
    plan: WorkspaceMigration.Plan,
    busy: Boolean,
    onLater: () -> Unit,
    onMigrate: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩不吃点击：这一层没有"点外面关掉"。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f)),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "把老会话迁到独立工作区？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "还有 ${plan.sessionIds.size} 条会话在用共享工作区" +
                            "（共享目录里 ${plan.files} 个文件 · ${plan.humanSize}）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = "迁过去之后，每条会话得到一份现在的文件副本，之后各写各的；" +
                            "共享目录保持不动。已经存在的同名文件不会被覆盖。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MigrationAction(label = "以后再说", primary = false, onClick = onLater)
                        Spacer(Modifier.width(8.dp))
                        MigrationAction(
                            label = if (busy) "正在迁移…" else "迁移",
                            primary = true,
                            onClick = { if (!busy) onMigrate() },
                        )
                    }
                }
            }
        }
    }
}

/** 这颗按钮只在这一层用：形状跟别处的对话框动作一致（胶囊、主按钮填色）。 */
@Composable
private fun MigrationAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
