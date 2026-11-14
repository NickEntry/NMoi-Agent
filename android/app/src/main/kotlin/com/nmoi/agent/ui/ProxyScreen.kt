package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 网络代理（D226）。设置 → 应用设置 →「网络代理」。
 *
 * 三条口径，跟 `notes/proxy.md` 对齐：
 *
 * 1. **热生效**：保存后立刻 `proxyApply`，下一次请求就用新的；正在飞的那条不变。
 * 2. **密码进 Vault**：`prefs` 里只存非密字段，密码在 `vault/proxy.bin`。
 * 3. **没有"测试"按钮之前不摆它**：这一版先不做连通性探测（要另开一条 FFI），
 *    摆一个点了没反应的按钮比没有更糟。
 */
internal object ProxyRepository {
    private const val PREFS = "nmoi_proxy"
    private const val KEY_JSON = "config"
    private const val VAULT_ID = "proxy"

    internal data class Config(
        val enabled: Boolean = false,
        val kind: String = "socks5",
        val host: String = "127.0.0.1",
        val port: Int = 2080,
        val username: String = "",
        val bypass: String = "localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1",
        val trust: String = "bundle",
        val hasPassword: Boolean = false,
    ) {
        /** 交给 FFI 的那一份。密码由调用方现取，不放在这里。 */
        fun toJson(password: String?): JSONObject = JSONObject()
            .put("kind", kind)
            .put("host", host.trim())
            .put("port", port)
            .put("username", username.takeIf { it.isNotBlank() })
            .put("password", password?.takeIf { it.isNotBlank() })
            .put("bypass", bypass)
            .put("trust", trust)

        fun summary(): String = when {
            !enabled -> "关（直连）"
            else -> "$kind · ${host.trim()}:$port"
        }
    }

    fun load(context: Context): Config {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return Config()
        return runCatching {
            val json = JSONObject(raw)
            Config(
                enabled = json.optBoolean("enabled", false),
                kind = json.optString("kind", "socks5"),
                host = json.optString("host", "127.0.0.1"),
                port = json.optInt("port", 2080),
                username = json.optString("username", ""),
                bypass = json.optString("bypass", Config().bypass),
                trust = json.optString("trust", "bundle"),
                hasPassword = json.optBoolean("has_password", false),
            )
        }.getOrDefault(Config())
    }

    /** 保存并立刻应用。返回给界面一句话。 */
    suspend fun save(context: Context, config: Config, password: String?): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("enabled", config.enabled)
            .put("kind", config.kind)
            .put("host", config.host)
            .put("port", config.port)
            .put("username", config.username)
            .put("bypass", config.bypass)
            .put("trust", config.trust)
            .put("has_password", config.hasPassword || !password.isNullOrBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, payload.toString()).apply()

        if (!password.isNullOrBlank()) {
            Vault.save(context, VAULT_ID, password).getOrThrow()
        }
        apply(context).getOrThrow()
        // 容器那一侧跟着走：开 → 起端点 + 写配置；关 → 只删配置。失败只记日志，
        // 不让"容器没装"把"代理保存成功"这件事吞掉。
        ProxyContainer.sync(context).onFailure {
            NLog.w("Proxy", "容器接线没成功：${it.message}")
        }
    }

    /** 把当前配置推给原生层。启动时也要调一次，否则重启后请求是直连的。 */
    suspend fun apply(context: Context): Result<Unit> {
        val config = load(context)
        val body = JSONObject().put(
            "proxy",
            if (!config.enabled) JSONObject.NULL else config.toJson(password(context)),
        )
        return NmoiFfi.proxyApply(body).map { }
    }

    /** 测试按钮要用已存的密码（界面上那个框是空的）。 */
    fun passwordForTest(context: Context): String? = password(context)

    private fun password(context: Context): String? =
        if (!Vault.has(context, VAULT_ID)) null else Vault.load(context, VAULT_ID)
}

/** 设置 → 应用设置 → 网络代理。 */
@Composable
internal fun ProxyScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ProxyRepository.load(context)) }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var probeUrl by remember { mutableStateOf("https://www.google.com") }
    var probeResult by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "网络代理",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启动代理", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "关掉就是直连；改完立刻生效，下一次请求就用新的",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { on -> config = config.copy(enabled = on) },
                        )
                    }
                }

                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("代理类型", style = MaterialTheme.typography.bodyLarge)
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            listOf("socks5" to "SOCKS5", "http" to "HTTP", "https" to "HTTPS")
                                .forEach { (wire, label) ->
                                    SettingChip(label, selected = config.kind == wire) {
                                        config = config.copy(kind = wire)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                        }
                        if (config.kind == "https") {
                            Text(
                                "凭据来源",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                SettingChip("可信凭据源", selected = config.trust == "bundle") {
                                    config = config.copy(trust = "bundle")
                                }
                                Spacer(Modifier.width(8.dp))
                                SettingChip("本地凭据", selected = config.trust == "local") {
                                    config = config.copy(trust = "local")
                                }
                            }
                            Text(
                                "本地凭据这一档还没接（要走平台 TrustManager），现在选了会报不支持",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        ProxyField("服务器地址", config.host, Modifier.padding(top = 12.dp)) {
                            config = config.copy(host = it)
                        }
                        ProxyField("端口", config.port.toString(), Modifier.padding(top = 10.dp)) {
                            config = config.copy(port = it.trim().toIntOrNull() ?: config.port)
                        }
                        ProxyField("用户名", config.username, Modifier.padding(top = 10.dp)) {
                            config = config.copy(username = it)
                        }
                        ProxyField(
                            label = if (config.hasPassword) "密码（留空 = 不改）" else "密码",
                            value = password,
                            modifier = Modifier.padding(top = 10.dp),
                            secret = true,
                        ) { password = it }
                        ProxyField("代理绕过", config.bypass, Modifier.padding(top = 10.dp)) {
                            config = config.copy(bypass = it)
                        }
                        Text(
                            "当同时开启全局代理与供应商代理时，将优先使用供应商代理。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }

                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("连接测试", style = MaterialTheme.typography.bodyLarge)
                        ProxyField("测试地址", probeUrl, Modifier.padding(top = 8.dp)) {
                            probeUrl = it
                        }
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("测试", selected = false) {
                                scope.launch {
                                    val body = JSONObject()
                                        .put("url", probeUrl.trim())
                                        .put(
                                            "proxy",
                                            if (!config.enabled) JSONObject.NULL
                                            else config.toJson(password.ifBlank { ProxyRepository.passwordForTest(context) }),
                                        )
                                    probeResult = "测试中…"
                                    NmoiFfi.proxyProbe(body).fold(
                                        onSuccess = { result ->
                                            probeResult = if (result.optBoolean("ok", false)) {
                                                "成功 · ${result.optInt("status", 0)} · ${result.optLong("ms", 0)} ms"
                                            } else {
                                                "失败：${result.optString("error", "原因不明")}"
                                            }
                                        },
                                        onFailure = { probeResult = "失败：${it.message}" },
                                    )
                                }
                            }
                        }
                        probeResult?.let { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SettingChip("保存并应用", selected = true) {
                        scope.launch {
                            ProxyRepository.save(context, config, password).fold(
                                onSuccess = {
                                    config = ProxyRepository.load(context)
                                    password = ""
                                    notice = "已应用：${config.summary()}"
                                },
                                onFailure = { notice = "保存失败：${it.message}" },
                            )
                        }
                    }
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingChip("移除容器垫片", selected = false) {
                        scope.launch {
                            ProxyContainer.removeShim(context).fold(
                                onSuccess = { notice = it },
                                onFailure = { notice = "移除失败：${it.message}" },
                            )
                        }
                    }
                }

                notice?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProxyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SettingField(
            value = value,
            onChange = onChange,
            placeholder = "",
            secret = secret,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
