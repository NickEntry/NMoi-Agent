package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 存储子页的入口（D434）：**十项是三种东西，不是一种**。
 *
 * 以前十项共用一套"平铺文件列表"，每一种都别扭：沙箱环境摊平之后用户看不见
 * "rootfs 里是什么"，聊天记录摊平之后那三个文件既删不得也认不出，图片又需要多选与导出。
 * 现在按形状分派：
 *
 * | 形状 | 哪几项 | 点进去是什么 |
 * |---|---|---|
 * | [StorageShape.Tree] | 沙箱环境 / 技能 / 工作区文件 / 会话文件 | 一级是"根"，之后是正常目录结构 |
 * | [StorageShape.Cards] | 缓存 / 日志 / 其他 / 聊天记录 | 几张卡，各有自己的动作，**没有文件行** |
 * | [StorageShape.Flat] | 图片 / 文件 | 同一批文件按类型聚一份，给快捷预览与删除 |
 *
 * 判据只有一条总的：**同一个文件在这三种页面里出现时，路径必须一致**——
 * 否则用户会以为盘上有两份。
 */
@Composable
internal fun StorageDetailScreen(
    kind: StorageKind,
    databasePath: String,
    onBack: () -> Unit,
    onOpenLog: (LogKind) -> Unit,
) {
    when (kind.shape) {
        StorageShape.Tree -> StorageTreeScreen(
            kind = kind,
            databasePath = databasePath,
            onBack = onBack,
        )

        StorageShape.Cards -> StorageCardsScreen(
            kind = kind,
            databasePath = databasePath,
            onBack = onBack,
            onOpenLog = onOpenLog,
        )

        StorageShape.Flat -> StorageFlatScreen(kind = kind, onBack = onBack)
    }
}

/** 空态：一句说明 + 一句下一步。不写"暂无数据"。三种形状共用这一份。 */
@Composable
internal fun EmptyKind(kind: StorageKind) {
    val (line, hint) = when (kind) {
        StorageKind.Images -> "还没有图片附件" to "在聊天页「＋ → 图片」发一张，它就会出现在这里。"
        StorageKind.Files -> "还没有发过文件" to "在聊天页「＋ → 文件」发一个。"
        StorageKind.Chat -> "还没有聊天记录" to "发一条消息就会建库。"
        StorageKind.Cache -> "缓存是空的" to "用一段时间之后这里会有缩略图与临时文件。"
        StorageKind.Logs -> "还没有日志" to "在「调试 → 日志」里打开一个通道就会开始写。"
        StorageKind.Other -> "没有其他文件" to "导入的字体、语音运行时会落在这里。"
        StorageKind.Workspace -> "工作区还是空的" to "在「工具与工作区 → 工作区」里建一个，或者让 AI 写个文件。"
        StorageKind.Envir -> "还没装隔离环境" to "在「工具与工作区 → 环境」里装一次。"
        StorageKind.Skills -> "还没装技能" to "技能包解开之后落在这里。"
        StorageKind.SessionFiles -> "会话目录里没有别的文件" to "AI 写进 /chat 的输出会出现在这里。"
    }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 导出的落点（D425）。
 *
 * 两件事：把选中的文件写成一份 7z；以及给这次导出起个名字。
 *
 * 走 `nmoi_sevenz_write_json`（C ABI），不自己压：压缩器要跟解压器对得上，
 * 而那一份是拿 `7z` 命令行逐字节验过的。
 */
internal object StorageExport {

    /** 备份的默认文件名：`nmoi-backup-20261125.db`。日期不带时分，重复备份会让系统自己加序号。 */
    fun suggestedBackupName(): String {
        val now = java.time.LocalDate.now()
        val text = "%04d%02d%02d".format(now.year, now.monthValue, now.dayOfMonth)
        return "nmoi-backup-$text.db"
    }

    /** 建议的文件名：`nmoi-图片-12项.7z`。带项数是因为用户常常连导几次。 */
    fun suggestedName(kind: StorageKind, count: Int): String = "nmoi-${kind.title}-${count}项.7z"

    /**
     * 备份库到 `uri`（D424 提速之后这条路才敢放在界面上）。
     *
     * 中间那份临时库落在 `cacheDir`：SQLite 的备份 API 要一个可读写的路径，
     * 而 SAF 给的是流。成功后整份拷进 `uri`，临时文件无论成败都删——
     * 留着会在缓存里堆出用户看不见的库副本（那里面全是聊天记录）。
     */
    suspend fun backupTo(
        context: android.content.Context,
        uri: android.net.Uri,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!NativeBridge.loaded) error("原生层没加载")
            val databasePath = ProviderRepository.databasePath(context)
            val temp = File(context.cacheDir, "nmoi-backup-${System.currentTimeMillis()}.db")
            try {
                val report = withStorageHandle(databasePath) { handle ->
                    NmoiFfi.backup(handle, temp.absolutePath)
                }.getOrElse { error("备份失败：${it.message}") }
                if (!report.optBoolean("verified", false)) {
                    val problems = report.optJSONArray("problems")
                    val first = problems?.optString(0).orEmpty()
                    error("副本没通过校验，没有发布（旧备份还在原处）${if (first.isEmpty()) "" else "：$first"}")
                }
                val bytes = temp.length()
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("打不开目标文件")
                out.use { stream -> temp.inputStream().use { it.copyTo(stream) } }
                "已备份 ${formatBytes(bytes)}（校验通过）"
            } finally {
                // 这份临时文件里是全部聊天记录，删迟一步就是一份明文副本留在缓存里。
                if (temp.exists()) temp.delete()
            }
        }
    }

    /**
     * 导出到 `uri`。
     *
     * 中间落一份临时包再拷进 SAF：`ContentResolver` 给的是流，而 7z 写入要 `Seek`
     * （签名头在最前面、要回头补）。所以先写到 `cacheDir` 里，再整份搬过去。
     * 失败时那份临时包要删掉——留着会在缓存里堆出用户看不见的垃圾。
     */
    suspend fun exportTo(
        context: android.content.Context,
        uri: android.net.Uri,
        kind: StorageKind,
        items: List<StorageRepository.Item>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (items.isEmpty()) error("没有选中任何文件")
            if (!NativeBridge.loaded) error("原生层没加载")
            val root = context.filesDir.absolutePath.trimEnd('/')
            val cacheRoot = context.cacheDir.absolutePath.trimEnd('/')
            val spec = org.json.JSONObject()
            val dest = File(context.cacheDir, "nmoi-export-${System.currentTimeMillis()}.7z")
            spec.put("dest", dest.absolutePath)
            val list = org.json.JSONArray()
            for (item in items) {
                // 包内名字用相对 App 私有目录的那一段：解出来就是 `sessions/...`、`workspace/...`，
                // 与它在盘上的位置对得上。`cache/` 前缀要换算成 cacheDir 底下的相对路径。
                val name = item.relative.trimStart('/')
                val absolute = if (name.startsWith("cache/")) {
                    File(cacheRoot, name.removePrefix("cache/")).absolutePath
                } else {
                    File(root, name).absolutePath
                }
                list.put(
                    org.json.JSONObject()
                        .put("name", name)
                        .put("path", absolute),
                )
            }
            spec.put("entries", list)

            try {
                val envelope = org.json.JSONObject(NativeBridge.sevenzWriteJson(spec.toString()))
                if (!envelope.ok()) error(envelope.errorText())
                val bytes = dest.length()
                // 拷进 SAF。`openOutputStream` 拿不到就是用户把目标位置取消了。
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("打不开目标文件")
                out.use { stream ->
                    dest.inputStream().use { input -> input.copyTo(stream) }
                }
                "已导出 ${items.size} 个文件 · ${formatBytes(bytes)}"
            } finally {
                // 成功与失败都要删：临时包留着没人认领。
                if (dest.exists()) dest.delete()
            }
        }
    }
}
