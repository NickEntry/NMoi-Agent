package com.nmoi.agent.ui

import android.content.Context
import java.io.File

/**
 * 世界书的落盘（D280）：一个文件，不进库。
 *
 * 为什么不进库：它是**用户导入的一份配置**（kelivo / RikkaHub 导出的 JSON），
 * 不是这个库里的数据——换设备时用户会重新导一份，而不是指望备份带着它。
 * 文件在 `files/lorebook.json`，大小几十 KB 到几百 KB，SharedPreferences 放不下。
 */
internal object LorebookStore {
    private const val FILE = "lorebook.json"

    fun file(context: Context): File = File(context.filesDir, FILE)

    /** 读出来；没有或读不出来就是空串（界面按"还没导入"处理）。 */
    fun text(context: Context): String =
        runCatching { file(context).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")

    fun save(context: Context, text: String): Result<Unit> = runCatching {
        val target = file(context)
        if (text.isBlank()) {
            target.delete()
        } else {
            target.writeText(text)
        }
    }

    fun clear(context: Context): Result<Unit> = runCatching { file(context).delete() }
}
