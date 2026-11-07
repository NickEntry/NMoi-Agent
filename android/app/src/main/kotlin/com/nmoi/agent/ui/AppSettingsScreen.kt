package com.nmoi.agent.ui

import android.content.Context
import kotlin.math.roundToInt
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
    var imageLimitText by remember { mutableStateOf(AppSettings.imageLimit(context).toString()) }
    // 图片模式（D270）：直发 / 路径信息。
    var imageMode by remember { mutableStateOf(AppSettings.imageMode(context)) }
    // 临时多留几张（D272）。
    var imageTempText by remember { mutableStateOf(AppSettings.imageTempLimit(context).toString()) }
    // 压缩的两条线与输出预留（D274）：界面上是百分数，存的是 0–1。
    var suggestText by remember { mutableStateOf(percentText(AppSettings.suggestLine(context))) }
    var absoluteText by remember { mutableStateOf(percentText(AppSettings.absoluteLine(context))) }
    var reserveText by remember { mutableStateOf(AppSettings.reserveOutput(context).toString()) }
    // 上下文档位与内容形态阈值（D275）。
    var formLevel by remember { mutableStateOf(AppSettings.formLevel(context)) }
    var smallText by remember { mutableStateOf(routerText(context, "context.router.small", AppSettings.DEFAULT_ROUTER_SMALL)) }
    var largeText by remember { mutableStateOf(routerText(context, "context.router.large", AppSettings.DEFAULT_ROUTER_LARGE)) }
    var hugeText by remember { mutableStateOf(routerText(context, "context.router.huge", AppSettings.DEFAULT_ROUTER_HUGE)) }
    var headText by remember { mutableStateOf(routerText(context, "context.router.head", AppSettings.DEFAULT_ROUTER_HEAD)) }
    var tailText by remember { mutableStateOf(routerText(context, "context.router.tail", AppSettings.DEFAULT_ROUTER_TAIL)) }

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

                // —— 上下文图片上限（D248）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "上下文里最多带几张图",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "「查看图像」把图拉进上下文时按这个数封顶：新的在前，满了先丢最旧的。" +
                                "默认 ${AppSettings.DEFAULT_IMAGE_LIMIT}；上限 ${AppSettings.MAX_IMAGE_LIMIT}——" +
                                "三家端点里最弱的那家（Anthropic）就是 100 张/请求，填得更大换一家就会 400。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        SettingField(
                            value = imageLimitText,
                            onChange = { imageLimitText = it },
                            placeholder = AppSettings.DEFAULT_IMAGE_LIMIT.toString(),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这个值", selected = false) {
                                val parsed = imageLimitText.trim().toIntOrNull()
                                if (parsed == null ||
                                    parsed < 1 ||
                                    parsed > AppSettings.MAX_IMAGE_LIMIT
                                ) {
                                    notice = "图片上限要填 1–${AppSettings.MAX_IMAGE_LIMIT} 张"
                                } else {
                                    AppSettings.setImageLimit(context, parsed)
                                    imageLimitText = AppSettings.imageLimit(context).toString()
                                    notice = "上下文图片上限改成 ${AppSettings.imageLimit(context)} 张"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setImageLimit(context, AppSettings.DEFAULT_IMAGE_LIMIT)
                                imageLimitText = AppSettings.DEFAULT_IMAGE_LIMIT.toString()
                                notice = "上下文图片上限恢复成 ${AppSettings.DEFAULT_IMAGE_LIMIT} 张"
                            }
                        }

                        // —— 临时多留几张（D272）——
                        // 和上面那个数是同一件事的两半，所以放在同一张卡里：
                        // 一个说"正常带几张"，一个说"满了还能多留几张"。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "满了还能临时多留几张",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "0 = 关（满了直接丢最旧的）。临时那几张活到**下一次思考结束**：\n" +
                                        "模型还能看到它们一次，然后就收掉——这样它连看几张时，" +
                                        "前面那几张不会在它还没说完结论的时候被挤走。\n" +
                                        "两个数相加不能超过 ${AppSettings.MAX_IMAGE_LIMIT}（端点硬顶）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        SettingField(
                            value = imageTempText,
                            onChange = { imageTempText = it },
                            placeholder = AppSettings.DEFAULT_IMAGE_TEMP.toString(),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这个值", selected = false) {
                                val parsed = imageTempText.trim().toIntOrNull()
                                if (parsed == null || parsed < 0 || parsed > AppSettings.MAX_IMAGE_LIMIT) {
                                    notice = "临时额度要填 0–${AppSettings.MAX_IMAGE_LIMIT} 张"
                                } else {
                                    AppSettings.setImageTempLimit(context, parsed)
                                    imageTempText = AppSettings.imageTempLimit(context).toString()
                                    notice = if (parsed == 0) {
                                        "临时额度关掉了：满了直接丢最旧的"
                                    } else {
                                        "临时额度改成 ${AppSettings.imageTempLimit(context)} 张"
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setImageTempLimit(context, AppSettings.DEFAULT_IMAGE_TEMP)
                                imageTempText = AppSettings.DEFAULT_IMAGE_TEMP.toString()
                                notice = "临时额度恢复成 ${AppSettings.DEFAULT_IMAGE_TEMP} 张"
                            }
                        }
                    }
                }

                // —— 压缩（D274）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "压缩的两条线",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "建议线：到了就提示该压了；绝对线：到了就**必须先压再发**（压不动就顶回来）。" +
                                "两个都是占**可用预算**的百分比——可用 = 窗口 − 给输出留的那部分。" +
                                "绝对线不许低于建议线，低了我按建议线算。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            SettingField(
                                value = suggestText,
                                onChange = { suggestText = it },
                                placeholder = percentText(AppSettings.DEFAULT_SUGGEST),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SettingField(
                                value = absoluteText,
                                onChange = { absoluteText = it },
                                placeholder = percentText(AppSettings.DEFAULT_ABSOLUTE),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "左：建议线（默认 ${percentText(AppSettings.DEFAULT_SUGGEST)}）　" +
                                "右：绝对线（默认 ${percentText(AppSettings.DEFAULT_ABSOLUTE)}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这两个值", selected = false) {
                                val suggest = parsePercent(suggestText)
                                val absolute = parsePercent(absoluteText)
                                if (suggest == null || absolute == null) {
                                    notice = "两条线都要填 1–99 之间的百分数，例如 75"
                                } else if (suggest < AppSettings.MIN_SUGGEST * 100 ||
                                    suggest > AppSettings.MAX_SUGGEST * 100
                                ) {
                                    notice = "建议线要填 ${(AppSettings.MIN_SUGGEST * 100).toInt()}–" +
                                        "${(AppSettings.MAX_SUGGEST * 100).toInt()} 之间"
                                } else if (absolute < AppSettings.MIN_ABSOLUTE * 100 ||
                                    absolute > AppSettings.MAX_ABSOLUTE * 100
                                ) {
                                    notice = "绝对线要填 ${(AppSettings.MIN_ABSOLUTE * 100).toInt()}–" +
                                        "${(AppSettings.MAX_ABSOLUTE * 100).toInt()} 之间"
                                } else if (absolute <= suggest) {
                                    notice = "绝对线要高于建议线（现在 $suggest ≥ $absolute）"
                                } else {
                                    AppSettings.setSuggestLine(context, suggest / 100.0)
                                    AppSettings.setAbsoluteLine(context, absolute / 100.0)
                                    suggestText = percentText(AppSettings.suggestLine(context))
                                    absoluteText = percentText(AppSettings.absoluteLine(context))
                                    notice = "压缩两条线改成 $suggest / $absolute（下一轮生效）"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setSuggestLine(context, AppSettings.DEFAULT_SUGGEST)
                                AppSettings.setAbsoluteLine(context, AppSettings.DEFAULT_ABSOLUTE)
                                suggestText = percentText(AppSettings.suggestLine(context))
                                absoluteText = percentText(AppSettings.absoluteLine(context))
                                notice = "两条线恢复成默认"
                            }
                        }

                        // —— 给输出留（D274）——
                        Text(
                            text = "给输出留多少 token",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = "0 = 按模型算（模型自己填的 max_tokens，夹 1024–8192）。" +
                                "填了就按这个数从窗口里扣掉——扣多少，留给上下文的就少多少。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingField(
                            value = reserveText,
                            onChange = { reserveText = it },
                            placeholder = AppSettings.DEFAULT_RESERVE.toString(),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这个值", selected = false) {
                                val parsed = reserveText.trim().toIntOrNull()
                                if (parsed == null || parsed < 0 ||
                                    (parsed in 1 until AppSettings.MIN_RESERVE) ||
                                    parsed > AppSettings.MAX_RESERVE
                                ) {
                                    notice = "要么填 0（按模型算），要么填 " +
                                        "${AppSettings.MIN_RESERVE}–${AppSettings.MAX_RESERVE}"
                                } else {
                                    AppSettings.setReserveOutput(context, parsed)
                                    reserveText = AppSettings.reserveOutput(context).toString()
                                    notice = if (parsed == 0) {
                                        "输出预留恢复成按模型算"
                                    } else {
                                        "输出预留改成 $parsed token"
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setReserveOutput(context, AppSettings.DEFAULT_RESERVE)
                                reserveText = AppSettings.DEFAULT_RESERVE.toString()
                                notice = "输出预留恢复成按模型算"
                            }
                        }
                    }
                }

                // —— 上下文档位（D275）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "上下文档位",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "一条内容**最多**留到什么程度：F1 全文 / F2 截断（头尾各留一段）/ " +
                                "F3 占位 / F4 只留一行指针。\n" +
                                "它是上限：工具结果照常按大小与类型逐条判，结果不许超过档位。" +
                                "**只作用于工具结果**——你说的话、系统提示、工具定义、报错永远全文。\n" +
                                "切档位不改写历史：之后新进来的才按新档位，已经换过形态的保持原样（原文在归档里）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            listOf(
                                "F1" to "全文",
                                "F2" to "截断",
                                "F3" to "占位",
                                "F4" to "指针",
                            ).forEachIndexed { index, (level, label) ->
                                if (index > 0) Spacer(Modifier.width(8.dp))
                                SettingChip(label = "$level $label", selected = formLevel == level) {
                                    AppSettings.setFormLevel(context, level)
                                    formLevel = AppSettings.formLevel(context)
                                    notice = when (level) {
                                        "F1" -> "档位 F1：工具结果一律全文"
                                        "F2" -> "档位 F2：大的工具结果只留头尾"
                                        "F3" -> "档位 F3：大的工具结果换成占位符"
                                        else -> "档位 F4：大的工具结果只留一行指针"
                                    }
                                }
                            }
                        }

                        // 内容形态阈值：F1 时用不上，但值留着（切回 F2 还是上次那份）。
                        Text(
                            text = "内容形态阈值（token）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = "小 / 大 / 超大：超过哪一档就按哪一档处理（必须递增，不递增我按递增夹平）。" +
                                "头 / 尾：F2 截断时各留多少。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingField(
                                value = smallText,
                                onChange = { smallText = it },
                                placeholder = AppSettings.DEFAULT_ROUTER_SMALL.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SettingField(
                                value = largeText,
                                onChange = { largeText = it },
                                placeholder = AppSettings.DEFAULT_ROUTER_LARGE.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SettingField(
                                value = hugeText,
                                onChange = { hugeText = it },
                                placeholder = AppSettings.DEFAULT_ROUTER_HUGE.toString(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingField(
                                value = headText,
                                onChange = { headText = it },
                                placeholder = AppSettings.DEFAULT_ROUTER_HEAD.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SettingField(
                                value = tailText,
                                onChange = { tailText = it },
                                placeholder = AppSettings.DEFAULT_ROUTER_TAIL.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            text = "左：小 / 大 / 超大　右：头 / 尾（默认 " +
                                "${AppSettings.DEFAULT_ROUTER_SMALL} / ${AppSettings.DEFAULT_ROUTER_LARGE} / " +
                                "${AppSettings.DEFAULT_ROUTER_HUGE}，头尾 ${AppSettings.DEFAULT_ROUTER_HEAD}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            SettingChip("用这些值", selected = false) {
                                val small = smallText.trim().toIntOrNull()
                                val large = largeText.trim().toIntOrNull()
                                val huge = hugeText.trim().toIntOrNull()
                                val head = headText.trim().toIntOrNull()
                                val tail = tailText.trim().toIntOrNull()
                                when {
                                    listOf(small, large, huge, head, tail).any { it == null || it < 0 } ->
                                        notice = "五个数都要填非负整数"
                                    small!! > large!! || large > huge!! ->
                                        notice = "阈值要递增：小 ≤ 大 ≤ 超大（现在 $small / $large / $huge）"
                                    else -> {
                                        AppSettings.setRouterValue(context, "context.router.small", small)
                                        AppSettings.setRouterValue(context, "context.router.large", large)
                                        AppSettings.setRouterValue(context, "context.router.huge", huge)
                                        AppSettings.setRouterValue(context, "context.router.head", head!!)
                                        AppSettings.setRouterValue(context, "context.router.tail", tail!!)
                                        smallText = routerText(context, "context.router.small", AppSettings.DEFAULT_ROUTER_SMALL)
                                        largeText = routerText(context, "context.router.large", AppSettings.DEFAULT_ROUTER_LARGE)
                                        hugeText = routerText(context, "context.router.huge", AppSettings.DEFAULT_ROUTER_HUGE)
                                        headText = routerText(context, "context.router.head", AppSettings.DEFAULT_ROUTER_HEAD)
                                        tailText = routerText(context, "context.router.tail", AppSettings.DEFAULT_ROUTER_TAIL)
                                        notice = "内容形态阈值改成 $small / $large / $huge，头尾 $head / $tail"
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip("恢复默认", selected = false) {
                                AppSettings.setRouterValue(context, "context.router.small", AppSettings.DEFAULT_ROUTER_SMALL)
                                AppSettings.setRouterValue(context, "context.router.large", AppSettings.DEFAULT_ROUTER_LARGE)
                                AppSettings.setRouterValue(context, "context.router.huge", AppSettings.DEFAULT_ROUTER_HUGE)
                                AppSettings.setRouterValue(context, "context.router.head", AppSettings.DEFAULT_ROUTER_HEAD)
                                AppSettings.setRouterValue(context, "context.router.tail", AppSettings.DEFAULT_ROUTER_TAIL)
                                smallText = AppSettings.DEFAULT_ROUTER_SMALL.toString()
                                largeText = AppSettings.DEFAULT_ROUTER_LARGE.toString()
                                hugeText = AppSettings.DEFAULT_ROUTER_HUGE.toString()
                                headText = AppSettings.DEFAULT_ROUTER_HEAD.toString()
                                tailText = AppSettings.DEFAULT_ROUTER_TAIL.toString()
                                notice = "内容形态阈值恢复默认"
                            }
                        }
                    }
                }

                // —— 图片模式（D270）——
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "用户发的图怎么进请求",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "直发：图直接发给模型，省一次工具往返，但每张都占上下文。" +
                                "路径信息：只把文件名告诉它，要看哪张由它自己调「查看图像」挑。" +
                                "路径信息只在会话绑了工作区时生效——没绑的话它调不动「查看图像」，" +
                                "图就白给了，那种情况会自动退回直发。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(modifier = Modifier.padding(top = 10.dp)) {
                            SettingChip(
                                label = "直发",
                                selected = imageMode == AppSettings.IMAGE_MODE_INLINE,
                            ) {
                                AppSettings.setImageMode(context, AppSettings.IMAGE_MODE_INLINE)
                                imageMode = AppSettings.imageMode(context)
                                notice = "图片改成直发：图直接发给模型"
                            }
                            Spacer(Modifier.width(8.dp))
                            SettingChip(
                                label = "路径信息",
                                selected = imageMode == AppSettings.IMAGE_MODE_PATH,
                            ) {
                                AppSettings.setImageMode(context, AppSettings.IMAGE_MODE_PATH)
                                imageMode = AppSettings.imageMode(context)
                                notice = "图片改成路径信息：只给文件名，让它自己挑"
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

/** 0.75 → "75"：界面上是百分数，存的是 0–1。 */
internal fun percentText(value: Double): String = (value * 100).roundToInt().toString()

/** "75" → 75.0；带小数也认（"72.5"）。填不成数就返回 null，由调用方说清哪里不对。 */
internal fun parsePercent(raw: String): Double? =
    raw.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

/** 阈值那一格读出来（没存过就用默认值）。 */
private fun routerText(context: Context, key: String, fallback: Int): String =
    AppSettings.routerValue(context, key, fallback).toString()
