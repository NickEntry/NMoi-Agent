package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 模型服务这一份配置（D156）。
 *
 * 分两处存，**故意的**：
 *
 * - `settings(provider, default)`：端点、模型名、供应商类型。跟着库走，导出/备份时可以带走；
 * - `vault/provider.bin`：API Key 密文。不跟着库走，删掉它等于吊销（[Vault]）。
 *
 * 这样"换一台机器恢复库"只会带回空密钥，不会把凭据一起搬过去。
 */
internal data class ProviderConfig(
    val baseUrl: String = "",
    val model: String = "",
    val kind: String = DEFAULT_KIND,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_KIND = "openai_compatible"

        /** 只认这几个——`nmoi_turn_run_json` 的 `provider.kind` 就这四种（D116 的词表）。 */
        val KINDS = listOf(
            Kind("openai_compatible", "兼容"),
            Kind("openai_responses", "Responses"),
            Kind("anthropic", "Anthropic"),
            Kind("gemini", "Gemini"),
        )

        val DEFAULT = ProviderConfig()
    }
}

internal data class Kind(val wire: String, val label: String)

internal object ProviderRepository {
    private const val KEY = "default"

    /** 设置页每次进来读一次；读不到就说原因，不拿默认值顶。 */
    suspend fun load(databasePath: String): Result<ProviderConfig> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsGet(handle, SettingsScope.Provider, KEY).map { envelope ->
                val doc = envelope.optJSONObject("doc")
                if (envelope.optString("state", "") != "ready" || doc == null) {
                    ProviderConfig.DEFAULT
                } else {
                    ProviderConfig(
                        baseUrl = doc.optString("base_url", ""),
                        model = doc.optString("model", ""),
                        kind = doc.optString("kind", ProviderConfig.DEFAULT_KIND),
                    )
                }
            }
        }

    suspend fun save(databasePath: String, config: ProviderConfig): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject()
                .put("base_url", config.baseUrl.trim())
                .put("model", config.model.trim())
                .put("kind", config.kind)
            NmoiFfi.settingsPut(handle, SettingsScope.Provider, KEY, doc)
                .onSuccess { NLog.i("Provider", "配置已保存：kind=${config.kind} 端点长度=${config.baseUrl.length}") }
                .onFailure { NLog.w("Provider", "配置保存失败：${it.message}") }
                .map { }
        }

    /** 已存的密钥打码（给界面显示用）。解不开返回 `null`，界面写"要重填"。 */
    fun keyHint(context: Context): String? = Vault.load(context)?.let(::maskSecret)

    fun hasKey(context: Context): Boolean = Vault.has(context)

    fun databasePath(context: Context): String =
        File(context.filesDir, "nmoi.db").absolutePath
}
