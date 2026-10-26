package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型服务（D156）：能不能发请求，就看这一页填没填。
 *
 * 三件事分开存，界面也要分开说：
 *
 * - 端点 / 模型 / 类型 进 `settings(provider)`，跟着库走；
 * - API Key 进 `vault/provider.bin`（Keystore 加密），**不跟着库走**；
 * - 密钥**从不回显**：只显示打码的尾巴，输入框永远是"重填"，留空就是不换。
 *
 * 没有「测试连接」按钮：真发请求那条路（`turn_run`）还没接到 UI 上，
 * 现在给一个按钮就是假按钮。等接上了再加，位置留在这儿。
 */
@Composable
internal fun ProviderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val databasePath = remember { ProviderRepository.databasePath(context) }

    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProviderConfig.DEFAULT_KIND) }
    var keyDraft by remember { mutableStateOf("") }
    var keyHint by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        keyHint = ProviderRepository.keyHint(context)
        ProviderRepository.load(databasePath).fold(
            onSuccess = { config ->
                baseUrl = config.baseUrl
                model = config.model
                kind = config.kind
            },
            onFailure = { notice = "读配置失败：${it.message}" },
        )
    }

    fun save() {
        val config = ProviderConfig(baseUrl = baseUrl, model = model, kind = kind)
        if (!config.isConfigured) {
            notice = "端点和模型都要填"
            return
        }
        saving = true
        scope.launch {
            val stored = ProviderRepository.save(databasePath, config)
            // Keystore 运算 + 写文件：别放在主线程上，设置页卡一帧是看得出来的。
            val keyed = if (keyDraft.isBlank()) {
                Result.success(Unit)
            } else {
                withContext(Dispatchers.IO) { Vault.save(context, keyDraft.trim()) }
            }
            saving = false
            stored.fold(
                onSuccess = {
                    keyed.fold(
                        onSuccess = {
                            if (keyDraft.isNotBlank()) {
                                keyHint = maskSecret(keyDraft)
                                keyDraft = ""
                            }
                            notice = "已保存"
                        },
                        onFailure = { notice = "密钥没存进去：${it.message}" },
                    )
                },
                onFailure = { notice = "保存失败：${it.message}" },
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "模型服务",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionLabel("端点")
                Hint("完整的 base URL。是否带 /v1 看供应商怎么说，别自己猜。")
                Field(
                    value = baseUrl,
                    onChange = { baseUrl = it },
                    placeholder = "https://example.com/v1",
                )

                SectionLabel("模型")
                Hint("这一轮用哪个模型名。切模型等于换一套 token 口径（校准按模型分开记）。")
                Field(
                    value = model,
                    onChange = { model = it },
                    placeholder = "例如 gpt-4.1-mini",
                )

                SectionLabel("供应商类型")
                Hint("决定请求体长什么样。选错了不是报错，是回一段看不懂的话。")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderConfig.KINDS.forEach { option ->
                        Chip(
                            label = option.label,
                            selected = kind == option.wire,
                            onClick = { kind = option.wire },
                        )
                    }
                }

                SectionLabel("API Key")
                Hint(
                    when {
                        keyHint != null -> "已保存：$keyHint。要换就在下面重填，留空就是不换。"
                        ProviderRepository.hasKey(context) -> "存是存过，但解不开（换机或清了数据），要重填一次。"
                        else -> "还没存过。存在 vault/ 下，用 Keystore 加密，不进库、不进备份。"
                    },
                )
                Field(
                    value = keyDraft,
                    onChange = { keyDraft = it },
                    placeholder = "填了才会存；从不回显",
                    secret = true,
                )

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (saving) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clickable(enabled = !saving) { save() },
                ) {
                    Text(
                        text = if (saving) "保存中…" else "保存",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (saving) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                val line = notice
                Text(
                    text = line ?: "这一页只决定「用什么端点、什么模型、带什么钥匙」。发请求那条路还没接到界面上。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (line == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 一行输入。自绘：系统 `TextField` 在这两套主题下的内边距与圆角与页面其它控件对不上。 */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary,
            ),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            decorationBox = { inner ->
                // placeholder 与输入框**同一个 Box**：直接并排写会叠在一起（decorationBox 没有默认容器）。
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
