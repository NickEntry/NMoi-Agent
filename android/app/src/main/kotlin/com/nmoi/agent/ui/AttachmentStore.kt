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
/** 存储管理页的一行（D216）：带会话标题与盘上路径。 */
internal data class StoredAttachment(
    val id: String,
    val conversation: String,
    val title: String,
    val message: String,
    val mime: String,
    val size: Long,
    val path: String?,
)

internal object AttachmentStore {

    /** 全部附件（存储管理页）。 */
    suspend fun listAll(databasePath: String): Result<List<StoredAttachment>> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.listAllAttachments(handle).mapCatching { envelope ->
                val items = envelope.optJSONArray("items") ?: org.json.JSONArray()
                buildList {
                    for (index in 0 until items.length()) {
                        val row = items.optJSONObject(index) ?: continue
                        val id = row.optString("id", "")
                        if (id.isEmpty()) continue
                        add(
                            StoredAttachment(
                                id = id,
                                conversation = row.optString("conversation", ""),
                                title = row.optString("title", ""),
                                message = row.optString("message", ""),
                                mime = row.optString("mime", ""),
                                size = row.optLong("size", 0L),
                                // `optString` 对 JSONObject.NULL 会返回字符串 "null"——
                                // 真机上就是它让存储页写出 `sessions/null`（D217）。
                                path = row.optString("path", "")
                                    .takeUnless { it.isEmpty() || it == "null" },
                            ),
                        )
                    }
                }
            }
        }

    /** 删一张附件：行与文件一起删。 */
    suspend fun delete(databasePath: String, id: String): Result<Boolean> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.deleteAttachment(handle, id)
                .map { envelope -> envelope.optBoolean("deleted", false) }
        }

    /** 原图字节（导出用）。 */
    suspend fun bytes(databasePath: String, id: String): Result<ByteArray> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.attachment(handle, id).mapCatching { envelope ->
                val data = envelope.optString("data", "")
                if (data.isEmpty()) error("附件 $id 没有字节")
                Base64.decode(data, Base64.NO_WRAP)
            }
        }


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
