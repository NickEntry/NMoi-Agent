package com.nmoi.agent.ui

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从端点**读回模型清单**（`GET {base_url}/models`）。
 *
 * 为什么要有这一步：模型 ID 是那种"少一个字符就 404、多一个后缀就 400"的东西
 * （`deepseek-flash` 这种），让人拿手打就是让他反复猜。
 * 清单本身是端点免费给的，读回来给他挑，比"填错了自己发现"便宜得多。
 *
 * 三条界限：
 *
 * - **只读**：只发 GET，不改任何东西。写配置仍然只由「保存」那一下完成。
 * - **不回显密钥**：日志里只有 `host + path`（Gemini 的 key 在 query 里，一律抹掉）。
 * - **不替用户挑**：拉回来只是往列表里加，默认模型还是他自己点的那一个。
 *
 * `base_url` 的约定跟 Rust 侧一致（`crates/core/src/provider.rs`）：**自带版本段**
 * （`https://api.openai.com/v1`、`https://api.anthropic.com/v1`），这里只补 `/models`。
 */
internal object ModelCatalog {
    /** 一次拉取的结果。`status = 0` 表示没连上（DNS/TLS/超时），看 [error]。 */
    data class Fetched(
        val ids: List<String>,
        val status: Int,
        val durationMs: Long,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && status in 200..299
    }

    /**
     * @param key 密钥。为空就照发——本地端点常常不要钥匙，真被 401 打回来时
     *   错误里会带着状态码，那时候才知道是钥匙的问题（跟 `TurnRepository` 一个口径）。
     */
    fun fetch(baseUrl: String, kind: String, key: String?): Fetched {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            return Fetched(emptyList(), 0, 0, "先填 API Base URL")
        }
        val started = System.currentTimeMillis()
        val gemini = kind == ProviderKinds.WIRE_GEMINI
        val url = buildString {
            append(base)
            append("/models")
            // Gemini 的钥匙走 query，其他家走头。都只在这一个请求里用。
            if (gemini && !key.isNullOrEmpty()) {
                append("?key=").append(key)
            }
        }
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrElse {
                return Fetched(emptyList(), 0, System.currentTimeMillis() - started, "地址不对：${it.message}")
            }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "NMoi/0.0.1")
            when {
                key.isNullOrEmpty() -> Unit
                gemini -> Unit // 已经在 query 里了
                kind == ProviderKinds.WIRE_ANTHROPIC -> {
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
                else -> connection.setRequestProperty("Authorization", "Bearer $key")
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val duration = System.currentTimeMillis() - started
            NLog.i("Provider", "拉模型：${safeUrl(base)}/models → HTTP $status，${duration}ms")
            if (status !in 200..299) {
                Fetched(emptyList(), status, duration, "HTTP $status ${snippet(body)}")
            } else {
                Fetched(parse(body, kind), status, duration, null)
            }
        } catch (error: IOException) {
            // `status = 0` 的那一类失败必须翻成人话：Android 给的原始 message
            // 要么是英文异常名（`Cleartext HTTP traffic … not permitted`），
            // 要么干脆是 null（用户看到的就是"拉模型失败（HTTP 0）"，等于没说）。
            val reason = explain(error)
            NLog.w("Provider", "拉模型失败：${safeUrl(base)}：${error.javaClass.simpleName} ${error.message}")
            Fetched(emptyList(), 0, System.currentTimeMillis() - started, reason)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 连不上时的几种原因，各给一句话。
     *
     * **别把异常名当文案**：`Cleartext HTTP traffic … not permitted` 这种话，
     * 除了我没人看得懂；而 `status=0` 又丢掉了全部信息（用户只会看到"HTTP 0"）。
     */
    private fun explain(error: IOException): String {
        val message = error.message.orEmpty()
        // 明文被系统拦下（0.15a 之后正常配置不会走到这儿，留着兜底：老安装包/系统策略）
        if (message.contains("Cleartext", ignoreCase = true)) {
            return "系统拦了明文 HTTP：这个地址是 http://，换成 https:// 或者用本机/内网允许明文的端点"
        }
        if (error is java.net.UnknownHostException) {
            return "域名解析不了：检查地址拼写（或者这台设备现在没网）"
        }
        if (error is java.net.SocketTimeoutException) {
            return "超时：15 秒没连上、或者 20 秒没回数据（端点卡住，或者地址不通）"
        }
        if (error is javax.net.ssl.SSLException) {
            return "TLS 握手失败：证书不对、或者这个端口不是 https"
        }
        if (error is java.net.ConnectException) {
            return "连不上：端口没开、或者服务没起（地址里的 port 对不对？）"
        }
        return message.ifBlank { "连接失败（没有更具体的原因）" }
    }

    /** 三家（OpenAI 系 / Anthropic / Gemini）的清单形状不同，但都只有一层。 */
    private fun parse(body: String, kind: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val ids = buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val raw = if (kind == ProviderKinds.WIRE_GEMINI) {
                    // Gemini 给的是 `models/gemini-2.5-pro`，我们只存后面那段。
                    row.optString("name", "").removePrefix("models/")
                } else {
                    row.optString("id", "")
                }.trim()
                if (raw.isNotEmpty()) add(raw)
            }
        }
        return ids.distinct().sorted()
    }

    /** 日志里的地址：**去掉 query**（Gemini 的 key 就在那儿）。 */
    private fun safeUrl(base: String): String {
        val noQuery = base.substringBefore('?')
        return runCatching {
            val url = URL(noQuery)
            "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}${url.path}"
        }.getOrDefault(noQuery)
    }

    private fun snippet(body: String): String =
        body.replace(Regex("\\s+"), " ").trim().take(160)

    /**
     * 把拉回来的 id 并进现有模型表：**已有的一条都不动**（用户可能手动填过参数），
     * 只追加缺的。返回 (新表, 新增条数)。
     */
    fun merge(existing: List<ProviderModel>, incoming: List<String>): Pair<List<ProviderModel>, Int> {
        val known = existing.map { it.id }.toSet()
        val added = incoming
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in known }
            .distinct()
            .map { ProviderModel(it) }
        return (existing + added) to added.size
    }
}
