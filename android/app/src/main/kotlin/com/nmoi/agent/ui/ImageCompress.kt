package com.nmoi.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 发图前的压缩（用户 2026-11-04 定）。
 *
 * 为什么在**选图那一刻**压，而不是发送时：这张图最终要落库、要落盘、还要 base64 编进请求，
 * 三处都按同一份字节算。压得越早，后面每一步都省；压得晚，库里已经躺着原图了。
 *
 * 规矩两条：
 * 1. **等比**——只缩不裁。裁掉的是用户没同意丢的东西。
 * 2. **只缩不放**——原图比上限小就原样发，不要为了"凑满"把 800 像素的图放大到 2048。
 *
 * 格式：`image/png` 出 PNG（可能有透明，转 JPEG 会变黑底），其余出 JPEG 并按质量压。
 * 质量参数只对 JPEG 有意义——这一点写在设置页上，不假装对 PNG 也生效。
 */
internal data class ImageLimits(
    val enabled: Boolean,
    val longEdge: Int,
    val shortEdge: Int,
    val quality: Int,
) {
    companion object {
        fun of(context: Context) = ImageLimits(
            enabled = AppSettings.imageCompress(context),
            longEdge = AppSettings.imageLongEdge(context),
            shortEdge = AppSettings.imageShortEdge(context),
            quality = AppSettings.imageQuality(context),
        )
    }
}

internal object ImageCompress {

    /** 一次压缩的结果：字节、mime、以及"有没有真的动过"（界面要能说出来）。 */
    data class Result(val bytes: ByteArray, val mime: String, val resized: Boolean, val reencoded: Boolean)

    fun apply(raw: ByteArray, mime: String, limits: ImageLimits): Result {
        if (!limits.enabled) return Result(raw, mime, resized = false, reencoded = false)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return Result(raw, mime, resized = false, reencoded = false)

        val (targetW, targetH) = targetSize(width, height, limits)
        val resized = targetW != width || targetH != height
        val png = mime.equals("image/png", ignoreCase = true)
        // 不用缩也不压：原样发。多编一次只会掉质量。
        if (!resized && (png || limits.quality >= 100)) {
            return Result(raw, mime, resized = false, reencoded = false)
        }

        val decoded = decode(raw, targetW, targetH) ?: return Result(raw, mime, false, false)
        val scaled = if (decoded.width != targetW || decoded.height != targetH) {
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        scaled.compress(format, if (png) 100 else limits.quality, out)
        scaled.recycle()
        val bytes = out.toByteArray()
        // 压完反而更大（小图重编码常见）：那就用原来那份，别做负优化。
        return if (bytes.size >= raw.size && !resized) {
            Result(raw, mime, resized = false, reencoded = false)
        } else {
            Result(bytes, if (png) "image/png" else "image/jpeg", resized, reencoded = true)
        }
    }

    /** 等比缩到"长边 ≤ longEdge 且短边 ≤ shortEdge"的框里；只缩不放。 */
    fun targetSize(width: Int, height: Int, limits: ImageLimits): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        val scale = minOf(
            1f,
            limits.longEdge.toFloat() / longSide,
            limits.shortEdge.toFloat() / shortSide,
        )
        if (scale >= 1f) return width to height
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /**
     * 解码到"刚好够用"的尺寸：先按 2 的幂做 `inSampleSize`（省内存），
     * 再交给 `createScaledBitmap` 精确缩——一步到位会在 8 MB 的图上直接 OOM。
     */
    private fun decode(raw: ByteArray, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        var sample = 1
        while (options.outWidth / (sample * 2) >= targetW && options.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            raw,
            0,
            raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}
