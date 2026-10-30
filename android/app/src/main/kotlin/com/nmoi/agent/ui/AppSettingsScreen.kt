package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 「应用设置」页（0.15m）。
 *
 * 这里放的是**装在这台手机上**的偏好，不是某个模型、也不是某个助手的配置：
 * 那两类各有自己的页（模型编辑、助手编辑），混在一起用户会不知道"改了影响谁"。
 *
 * 两项：
 *
 * - **允许生成新版本**（D185）：重试时把结果记成一版新的；关着就覆盖当前这一版。
 * - **默认上下文窗口**：模型自己没填窗口时，压缩判据拿它当分母。
 *   这一项存在的前提是"没填也要算"——用户 2026-10-31 定的。
 */
@Composable
internal fun AppSettingsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    var allowNewVersion by remember { mutableStateOf(AppSettings.allowNewVersion(context)) }
    var windowText by remember {
        mutableStateOf(AppSettings.defaultContextWindow(context).toString())
    }
    var notice by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "应用设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GroupCard(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许生成新版本",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "重试时把结果记成一版新的；关着就覆盖当前这一版（D185）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        SettingChip("开", selected = allowNewVersion) {
                            allowNewVersion = true
                            AppSettings.setAllowNewVersion(context, true)
                            notice = "重试会留下新版本"
                        }
                        Spacer(Modifier.width(6.dp))
                        SettingChip("关", selected = !allowNewVersion) {
                            allowNewVersion = false
                            AppSettings.setAllowNewVersion(context, false)
                            notice = "重试会覆盖当前这一版"
                        }
                    }
                }

                GroupCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "默认上下文窗口",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "模型自己没填窗口时用这个数算压缩判据（D90）。" +
                                "填了窗口的模型仍然按它自己那份算。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        ProviderField(
                            value = windowText,
                            onChange = { windowText = it },
                            placeholder = "128000",
                            keyboard = KeyboardType.Number,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这个值", selected = false) {
                                val parsed = windowText.trim().toIntOrNull()
                                if (parsed == null || parsed <= 0) {
                                    notice = "窗口要填正整数"
                                } else {
                                    AppSettings.setDefaultContextWindow(context, parsed)
                                    windowText = parsed.toString()
                                    notice = "默认窗口改成 $parsed"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复 128000", selected = false) {
                                AppSettings.setDefaultContextWindow(context, AppSettings.DEFAULT_WINDOW)
                                windowText = AppSettings.DEFAULT_WINDOW.toString()
                                notice = "默认窗口恢复成 ${AppSettings.DEFAULT_WINDOW}"
                            }
                        }
                    }
                }

                notice?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 行内小胶囊：这一页的两个动作都同形（ui-taste：控件用行内 chip，不用 Material Switch）。 */
@Composable
private fun SettingChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}