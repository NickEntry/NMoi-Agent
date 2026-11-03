package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
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
    var compress by remember { mutableStateOf(AppSettings.imageCompress(context)) }
    var longText by remember { mutableStateOf(AppSettings.imageLongEdge(context).toString()) }
    var shortText by remember { mutableStateOf(AppSettings.imageShortEdge(context).toString()) }
    var qualityText by remember { mutableStateOf(AppSettings.imageQuality(context).toString()) }
    var roundsText by remember { mutableStateOf(AppSettings.maxRounds(context).toString()) }

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
                SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
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

                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
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
                        SettingField(
                            value = windowText,
                            onChange = { windowText = it },
                            placeholder = "128000",
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

                // —— 工具轮数（D244）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "工具轮数上限（0 = 不限制）",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "留空或填 0 就是不限制：一句话里可以调任意多次工具，只要上下文还装得下。" +
                                "真正的边界是上下文绝对线、你自己点停止、以及上游自己的限制。" +
                                "填了正数就会在到顶后再给一轮（不带工具）让它收尾。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        SettingField(
                            value = roundsText,
                            onChange = { roundsText = it },
                            placeholder = AppSettings.DEFAULT_MAX_ROUNDS.toString(),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这个值", selected = false) {
                                val parsed = roundsText.trim().toIntOrNull()
                                if (parsed == null ||
                                    parsed < AppSettings.MIN_MAX_ROUNDS ||
                                    parsed > AppSettings.MAX_MAX_ROUNDS
                                ) {
                                    notice = "轮数要填 0–${AppSettings.MAX_MAX_ROUNDS}（0 = 不限制）"
                                } else {
                                    AppSettings.setMaxRounds(context, parsed)
                                    roundsText = AppSettings.maxRounds(context).toString()
                                    notice = "工具轮数上限改成 ${AppSettings.maxRoundsLabel(context)}"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复不限制", selected = false) {
                                AppSettings.setMaxRounds(context, AppSettings.DEFAULT_MAX_ROUNDS)
                                roundsText = AppSettings.DEFAULT_MAX_ROUNDS.toString()
                                notice = "工具轮数上限恢复成不限制"
                            }
                        }
                    }
                }

                // —— 图片压缩（D241）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "发图前压缩",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "选图那一刻就压：按长边/短边等比缩，再按质量重编码。" +
                                        if (compress) "" else "现在是关的：原图直发。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            SettingChip("开", selected = compress) {
                                compress = true
                                AppSettings.setImageCompress(context, true)
                                notice = "发图前会压缩"
                            }
                            Spacer(Modifier.width(6.dp))
                            SettingChip("关", selected = !compress) {
                                compress = false
                                AppSettings.setImageCompress(context, false)
                                notice = "原图直发（大图会很慢，也占地方）"
                            }
                        }
                        Text(
                            text = "只缩不放：比上限小的图原样发。PNG 走 PNG（可能有透明），" +
                                "其余走 JPEG——质量只对 JPEG 生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "长边像素",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingField(
                                    value = longText,
                                    onChange = { longText = it },
                                    placeholder = AppSettings.DEFAULT_IMAGE_LONG.toString(),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "短边像素",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingField(
                                    value = shortText,
                                    onChange = { shortText = it },
                                    placeholder = AppSettings.DEFAULT_IMAGE_SHORT.toString(),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "质量 %",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingField(
                                    value = qualityText,
                                    onChange = { qualityText = it },
                                    placeholder = AppSettings.DEFAULT_IMAGE_QUALITY.toString(),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            SettingChip("用这三个值", selected = false) {
                                val long = longText.trim().toIntOrNull()
                                val short = shortText.trim().toIntOrNull()
                                val quality = qualityText.trim().toIntOrNull()
                                if (long == null || long <= 0 || short == null || short <= 0) {
                                    notice = "长边/短边要填正整数"
                                } else if (quality == null || quality !in 1..100) {
                                    notice = "质量要填 1–100"
                                } else {
                                    AppSettings.setImageLongEdge(context, long)
                                    AppSettings.setImageShortEdge(context, short)
                                    AppSettings.setImageQuality(context, quality)
                                    longText = long.toString()
                                    shortText = short.toString()
                                    qualityText = quality.toString()
                                    notice = "以后发图按 长边 $long / 短边 $short / 质量 $quality%"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setImageLongEdge(context, AppSettings.DEFAULT_IMAGE_LONG)
                                AppSettings.setImageShortEdge(context, AppSettings.DEFAULT_IMAGE_SHORT)
                                AppSettings.setImageQuality(context, AppSettings.DEFAULT_IMAGE_QUALITY)
                                longText = AppSettings.DEFAULT_IMAGE_LONG.toString()
                                shortText = AppSettings.DEFAULT_IMAGE_SHORT.toString()
                                qualityText = AppSettings.DEFAULT_IMAGE_QUALITY.toString()
                                notice = "恢复成 2048 / 2048 / 90%"
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

/**
 * 这一页自己的输入框。`ProviderField` 是 ProviderScreens 的文件私有件，跨文件用不了。
 *
 * 与项目里其它输入框同形：填色小框、没有下划线、空值时显示占位。
 */
@Composable
internal fun SettingField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 密码这类：掩码显示。 */
    secret: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (secret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isBlank()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 这一页自己的分组卡。`GroupCard` 是 ProviderScreens 的文件私有件，跨文件用不了。 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** 行内小胶囊：这一页的两个动作都同形（ui-taste：控件用行内 chip，不用 Material Switch）。 */
@Composable
internal fun SettingChip(
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