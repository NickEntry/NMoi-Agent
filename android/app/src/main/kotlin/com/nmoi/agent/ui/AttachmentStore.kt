package com.nmoi.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一张待发的图片（D208）：界面里先拿在手上，发送时随这一轮交出去。
 *
 * `data` 是 base64（`Base64.NO_WRAP`）：FFI 那条路只认字符串，
 * 而图片最多 8 MB，编一次比来回切 JNI 数组便宜。
 */
internal data class PendingImage(
    val mime: String,
    val data: String,
    val bytes: Int,
) {
    /** 预览用的小图。解码失败给 `null`——界面画一个占位，而不是崩掉。 */
    fun bitmap(): Bitmap? = runCatching {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }.getOrNull()
}

/**
 * 读库里那张图片（D208）。
 *
 * 为什么按需取而不是跟着消息列表一起下来：80 条消息乘几 MB base64，
 * 首屏会被拖死；而屏幕上真正要画的往往只有一两条。
 * 结果留在内存里（最多 24 张），滚回去不用再走一次 JNI。
 */
internal object AttachmentStore {

    private const val MAX_CACHED = 24
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_CACHED
    }

    /** 读一张图。`null` = 读不到（附件被删、base64 坏了、原生层没加载）。 */
    suspend fun bitmap(databasePath: String, id: String): Bitmap? {
        synchronized(cache) { cache[id]?.let { return it } }
        val loaded = withContext(Dispatchers.IO) {
            withStorageHandle(databasePath) { handle ->
                NmoiFfi.attachment(handle, id).mapCatching { envelope ->
                    val data = envelope.optString("data", "")
                    if (data.isEmpty()) error("附件 $id 没有字节")
                    val raw = Base64.decode(data, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?: error("附件 $id 解不成图片")
                }
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[id] = loaded }
        return loaded
    }
}
