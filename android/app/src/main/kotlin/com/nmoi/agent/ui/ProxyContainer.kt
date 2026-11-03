package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * 容器里的代理接线（D226 / 0.15ar-e）。**注入这条路已经停了**（D237）。
 *
 * 为什么停：`libnmoi_netshim.so` 是给 `aarch64-linux-android`（bionic）编的，
 * 而容器里是 Ubuntu 的 glibc。bionic 的 `.so` 需要 bionic 的 `libdl.so`，
 * 容器里只有 glibc 的 `libdl.so.2`——把它写进 `/etc/ld.so.preload`，
 * **容器里每一个动态程序都起不来**，报的就是
 * `/bin/sh: error while loading shared libraries: libdl.so: cannot open shared object file`。
 * 真机上撞到了，覆盖安装也修不了：`ld.so.preload` 不在 rootfs 包里，
 * 重解一遍它还在原处。
 *
 * 现在的行为：
 * - [sync] 只起本地端点（App 自己的请求走它），**不往容器里写任何东西**；
 * - [removeShim] 留着，专门清老版本留下的 preload 与垫片；
 * - [cleanStale] 装在每次安装/覆盖安装之后跑一遍，把老版本留下的那一行摘掉。
 */
internal object ProxyContainer {
    private const val GUEST_LIB = "usr/local/lib/nmoi-netshim.so"
    private const val PRELOAD = "etc/ld.so.preload"
    private const val CONF = "etc/nmoi-net.conf"
    private const val PREFS = "nmoi_proxy"
    private const val KEY_USER = "container_user"
    private const val KEY_PASS = "container_pass"

    /** 按当前代理开关同步一次。返回一句给人看的结果。 */
    suspend fun sync(context: Context): Result<String> = runCatching {
        val installed = EnvirRepository.installed(context)
            ?: return@runCatching "隔离环境没装：容器内代理用不上"
        val rootfs = File(installed.rootfs)
        val config = ProxyRepository.load(context)

        if (!config.enabled) {
            NmoiFfi.proxyServerStop()
            File(rootfs, CONF).delete()
            return@runCatching "代理关着：App 与容器都直连"
        }

        // 垫片不再注入（D237）：见文件头。容器那一侧就是直连，这里只把本地端点起起来。
        NmoiFfi.proxyServerStart(
            org.json.JSONObject().put("user", credentials(context, KEY_USER))
                .put("pass", credentials(context, KEY_PASS)),
        ).onFailure { error ->
            // 已经在跑：端口与凭证都没变，不用重启。
            if (error.message?.contains("已经起着") != true) throw error
        }
        // 配置文件只给垫片读；垫片不装了，就别留一个没人读的文件。
        File(rootfs, CONF).delete()
        "代理已生效（App 的请求走它）：${config.summary()}。容器里是直连——垫片那条路已经停了（D237）"
    }

    /** 删垫片与 preload。给"救命"用：容器起不来时先点这个。 */
    suspend fun removeShim(context: Context): Result<String> = runCatching {
        val installed = EnvirRepository.installed(context)
            ?: return@runCatching "隔离环境没装"
        val rootfs = File(installed.rootfs)
        val cleaned = cleanStale(rootfs)
        File(rootfs, CONF).delete()
        NmoiFfi.proxyServerStop()
        if (cleaned == 0) "没有垫片残留：容器本来就是直连" else "垫片已移除：容器里的程序恢复直连"
    }

    /**
     * 清老版本留下的东西：`/etc/ld.so.preload` 里我们那一行、以及垫片本身。
     *
     * 只动**我们写的**那一行（`/usr/local/lib/nmoi-netshim.so`）：别人写的 preload 原样留着。
     * 摘完文件空了就把文件删掉——空 preload 也是文件，留着只会让下一个人以为这里配过什么。
     *
     * 返回摘掉了几处（0 = 本来就没有）。
     */
    fun cleanStale(rootfs: File): Int {
        var cleaned = 0
        val preload = File(rootfs, PRELOAD)
        if (preload.isFile) {
            val line = "/$GUEST_LIB"
            val kept = preload.readText().lineSequence()
                .filter { it.isNotBlank() && it.trim() != line }
                .toList()
            if (kept.size != preload.readText().lineSequence().count { it.isNotBlank() }) {
                if (kept.isEmpty()) preload.delete() else preload.writeText(kept.joinToString("\n") + "\n")
                cleaned += 1
            }
        }
        val shim = File(rootfs, GUEST_LIB)
        if (shim.isFile) {
            shim.delete()
            cleaned += 1
        }
        if (cleaned > 0) NLog.i("Proxy", "清掉老版本留下的容器垫片：$cleaned 处")
        return cleaned
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
