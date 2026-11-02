package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * 容器里的代理接线（D226 / 0.15ar-e）。
 *
 * 四件事，顺序固定：
 *
 * 1. 把 `libnmoi_netshim.so` 从 `nativeLibraryDir` 复制进 rootfs 的 `/usr/local/lib/`；
 * 2. 写 `/etc/ld.so.preload`（**只在 .so 真的存在时写**，写之前备份原文件）；
 * 3. 起本地 SOCKS5 端点，把 `host/port/user/pass` 写进 `/etc/nmoi-net.conf`；
 * 4. 关代理时只删配置、停端点，**不删垫片**——垫片常驻，"开"才能对已经在跑的会话即时生效。
 *
 * 垫片写坏 = 容器里所有动态程序起不来。所以这里每一步都先检查再动手，
 * 失败就把原因交回去，不装作成功。
 */
internal object ProxyContainer {
    private const val SHIM_NAME = "libnmoi_netshim.so"
    private const val GUEST_LIB = "usr/local/lib/nmoi-netshim.so"
    private const val PRELOAD = "etc/ld.so.preload"
    private const val CONF = "etc/nmoi-net.conf"
    private const val PREFS = "nmoi_proxy"
    private const val KEY_USER = "container_user"
    private const val KEY_PASS = "container_pass"
    private const val KEY_CONF = "container_conf"

    /** 按当前代理开关同步一次。返回一句给人看的结果。 */
    suspend fun sync(context: Context): Result<String> = runCatching {
        val installed = EnvirRepository.installed(context)
            ?: return@runCatching "隔离环境没装：容器内代理用不上"
        val rootfs = File(installed.rootfs)
        val config = ProxyRepository.load(context)

        if (!config.enabled) {
            NmoiFfi.proxyServerStop()
            File(rootfs, CONF).delete()
            return@runCatching "代理关着：容器内直连（垫片仍在，下次打开即时生效）"
        }

        installShim(context, rootfs).getOrThrow()

        val started = NmoiFfi.proxyServerStart(
            org.json.JSONObject().put("user", credentials(context, KEY_USER))
                .put("pass", credentials(context, KEY_PASS)),
        )
        val confText = started.fold(
            onSuccess = { envelope ->
                envelope.optString("config", "").also {
                    prefs(context).edit().putString(KEY_CONF, it).apply()
                }
            },
            onFailure = { error ->
                // 已经在跑：用上次写下的那一份（端口和凭证都没变）。
                if (error.message?.contains("已经起着") == true) {
                    prefs(context).getString(KEY_CONF, "").orEmpty()
                } else {
                    throw error
                }
            },
        )
        if (confText.isBlank()) {
            return@runCatching "端点起来了，但没有可写的配置：先点一次「保存并应用」"
        }
        File(rootfs, CONF).writeText(confText)
        "容器内代理已接通：${config.summary()}"
    }

    /** 删垫片与 preload。给"救命"用：容器起不来时先点这个。 */
    suspend fun removeShim(context: Context): Result<String> = runCatching {
        val installed = EnvirRepository.installed(context)
            ?: return@runCatching "隔离环境没装"
        val rootfs = File(installed.rootfs)
        File(rootfs, PRELOAD).delete()
        File(rootfs, GUEST_LIB).delete()
        NmoiFfi.proxyServerStop()
        File(rootfs, CONF).delete()
        "垫片已移除：容器里的程序恢复直连"
    }

    private fun installShim(context: Context, rootfs: File): Result<Unit> = runCatching {
        val source = File(context.applicationInfo.nativeLibraryDir, SHIM_NAME)
        if (!source.exists() || source.length() == 0L) {
            error("APK 里没有 $SHIM_NAME（构建没带垫片），容器内代理用不了")
        }
        val target = File(rootfs, GUEST_LIB)
        target.parentFile?.mkdirs()
        if (!target.exists() || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        // 只在复制成功之后写 preload：先写后复制，中间失败就是容器全挂。
        val preload = File(rootfs, PRELOAD)
        val line = "/$GUEST_LIB"
        val current = if (preload.exists()) preload.readText() else ""
        if (!current.lineSequence().any { it.trim() == line }) {
            if (current.isNotBlank()) {
                // 别人（或上一个版本）写的 preload 先留一份，别直接覆盖掉。
                File(rootfs, "$PRELOAD.nmoi-bak").writeText(current)
            }
            preload.writeText(current.trimEnd() + (if (current.isBlank()) "" else "\n") + line + "\n")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 随机凭证：loopback 同设备别的 App 也连得到，不能空着。 */
    private fun credentials(context: Context, key: String): String {
        val existing = prefs(context).getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val text = random.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(key, text).apply()
        return text
    }
}
