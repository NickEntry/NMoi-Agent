package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一条语音服务的编辑面（0.17b）。
 *
 * 音色那一格就是用户要的控件：右边 `ˇ` 点开变 `^`，下面是清单能点，**同时也能直接打字**。
 * 清单是快捷方式，不是白名单——账号后台多出来的音色，不该等到我们发版才能用。
 */
@Composable
internal fun VoiceEntrySheet(
    direction: VoiceRepository.Direction,
    initial: VoiceRepository.Entry?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(initial?.provider ?: "openai") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: VoiceRepository.preset("openai").baseUrl) }
    var model by remember { mutableStateOf(initial?.model ?: VoiceRepository.preset("openai").model) }
    var voice by remember { mutableStateOf(initial?.voice ?: "") }
    var language by remember { mutableStateOf(initial?.language ?: "auto") }
    var key by remember { mutableStateOf("") }
    var voiceOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val preset = VoiceRepository.preset(provider)

    SheetFrame(title = if (initial == null) "新增${direction.label}" else "编辑${direction.label}", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = "服务提供方",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chunk in VoiceRepository.presets.chunked(4)) {
                    Column {
                        for (item in chunk) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (provider == item.id) FontWeight.Medium else FontWeight.Normal,
                                color = if (provider == item.id) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .clickable {
                                        provider = item.id
                                        if (name.isBlank()) name = item.label
                                        baseUrl = item.baseUrl
                                        model = item.model
                                        if (voice.isBlank()) voice = item.voices.firstOrNull().orEmpty()
                                        voiceOpen = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API 基址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (initial?.hasKey == true) "API Key（已存，留空不改）" else "API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = { Text("音色 ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (voiceOpen) " ^" else " ˇ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = preset.voices.isNotEmpty()) { voiceOpen = !voiceOpen }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
            if (voiceOpen && preset.voices.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    for (item in preset.voices) {
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    voice = item
                                    voiceOpen = false
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("语言（auto / zh / ja / en）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )

            if (preset.note.isNotBlank()) {
                Text(
                    text = preset.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (preset.caps.isNotEmpty()) {
                Text(
                    text = "这一条支持：${preset.caps.joinToString(" / ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            error?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ChipButton("取消", primary = false) { onDismiss() }
                Spacer(Modifier.size(8.dp))
                ChipButton("保存", primary = true) {
                    when {
                        name.isBlank() -> error = "名称不能空"
                        baseUrl.isBlank() && provider !in setOf("system", "local") -> error = "基址不能空"
                        else -> {
                            val entry = VoiceRepository.Entry(
                                id = initial?.id ?: VoiceRepository.newId(direction),
                                direction = direction,
                                provider = provider,
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                voice = voice.trim(),
                                language = language.trim().ifBlank { "auto" },
                                hasKey = initial?.hasKey == true || key.isNotBlank(),
                            )
                            VoiceRepository.save(context, entry, key.trim().ifBlank { null })
                            onSaved()
                        }
                    }
                }
            }
        }
    }
}
