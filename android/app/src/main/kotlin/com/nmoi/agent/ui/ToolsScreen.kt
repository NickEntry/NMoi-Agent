package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 工具与工作区（D159）。
 *
 * 这一页只放**真的会生效**的开关：
 *
 * - 工作区：`filesDir/workspace`。绑上之后六件文件工具（读/写/改/列/找/搜）才可用，
 *   `System-Shell` 也有起点。路径照用户要求落在 `/data/user/<uid>/…`，不走 `/sdcard`。
 * - `System-Shell`：默认**关**。打开要过一次确认——它跑在 App 自己的身份下，
 *   能碰的就是这个 App 能碰的文件。
 * - 隔离环境：**装在你自己的私有目录里**（proot + Ubuntu rootfs，D165）。这一行是入口，
 *   点进去装/看/删/自检；没装时它写清"未安装"和体积，不写成灰字糊过去。
 * - `WorkSpace-EnvirShell`（D172）：**真的开关**，默认关。环境没装时它是灰的——
 *   拨了也不会生效的那种开关比没有更糟；装了之后拨开要过一次确认（容器里的命令能改容器的一切，
 *   而工作区是共享的）。
 */
@Composable
internal fun ToolsScreen(onBack: () -> Unit, onOpenEnvir: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var nativeShell by remember { mutableStateOf(ToolingRepository.nativeShell(context)) }
    var confirmShell by remember { mutableStateOf(false) }
    val root = remember { ToolingRepository.workspaceRoot(context).absolutePath }
    // 从安装页回来时这个 composable 会重新组合，所以每次读到的都是当下的状态。
    val envir = remember { EnvirRepository.installed(context) }
    // 容器里的命令（D172）：开关是"用户允不允许"，能不能跑由 `envir` 说话。
    var envirShell by remember { mutableStateOf(ToolingRepository.envirShell(context)) }
    var confirmEnvirShell by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "工具与工作区",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionLabel("工作区")
                GroupCard {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "文件工具的根目录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = root,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "AI 只能在这个目录里读写文件；命令的起点也在这里。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SectionLabel("命令")
                GroupCard {
                    ToolSwitchRow(
                        title = "System-Shell",
                        subtitle = "平台原生命令。跑在这个 App 的身份下——它能碰的文件，命令都能碰。" +
                            "默认关；模型在没有你允许时也不该调用它。",
                        checked = nativeShell,
                        onChecked = { next ->
                            if (next) {
                                confirmShell = true
                            } else {
                                ToolingRepository.setNativeShell(context, false)
                                nativeShell = false
                            }
                        },
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 14.dp),
                    )
                    ToolSwitchRow(
                        title = "WorkSpace-EnvirShell",
                        subtitle = if (envir == null) {
                            "隔离环境里的命令。**先把下面那个环境装上**——没有 rootfs 就没有东西可跑，" +
                                "所以这一行现在是灰的。"
                        } else {
                            "在容器里的 Ubuntu 里跑命令，起点是工作区（容器里看到的是 /root/workspace）。" +
                                "容器里动什么都不影响这台手机；工作区是唯一共享的地方。默认关。"
                        },
                        checked = envir != null && envirShell,
                        enabled = envir != null,
                        onChecked = { next ->
                            if (next) {
                                confirmEnvirShell = true
                            } else {
                                ToolingRepository.setEnvirShell(context, false)
                                envirShell = false
                            }
                        },
                    )
                }

                SectionLabel("隔离环境")
                GroupCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenEnvir)
                            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "proot + Ubuntu rootfs",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (envir == null) {
                                    "未安装 · 33.4 MB 下载，装在 App 私有目录里"
                                } else {
                                    "${envir.label} · ${envir.files} 个文件 · 已装"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Text(
                    text = "这两个开关**只存在本机**（SharedPreferences），不进库、不进导出/导入：" +
                        "换台设备导入一个「开」，只会得到「看着开着、其实不能跑」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
                )
            }
        }
    }

    if (confirmEnvirShell) {
        ConfirmEnvirShellDialog(
            onCancel = { confirmEnvirShell = false },
            onConfirm = {
                confirmEnvirShell = false
                ToolingRepository.setEnvirShell(context, true)
                envirShell = true
            },
        )
    }

    if (confirmShell) {
        ConfirmShellDialog(
            onCancel = { confirmShell = false },
            onConfirm = {
                confirmShell = false
                ToolingRepository.setNativeShell(context, true)
                nativeShell = true
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun ToolSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
}

/** 打开原生命令前的确认。**红色实心**留给"收不回来的删除"，这里用主色就够。 */
@Composable
private fun ConfirmShellDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "允许模型跑原生命令？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "命令跑在这个 App 的身份下：工作区以外、只要 App 有权限的地方它也能碰。" +
                            "删目录、改系统设置这类事收不回来。你随时可以在这一页关掉。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogChip("取消", onClick = onCancel)
                        Spacer(Modifier.size(8.dp))
                        DialogChip("打开", primary = true, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

/** 打开容器命令前的确认。跟 `System-Shell` 一样的形状，但危险面小一档：
 *  容器里动什么都不影响手机，共享的只有工作区。 */
@Composable
private fun ConfirmEnvirShellDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "允许模型在容器里跑命令？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "命令跑在容器里的 Ubuntu 里：装包、改容器的 /、删容器里的东西都只影响那份 rootfs。" +
                            "**工作区是共享的**——它在容器里是 /root/workspace，在里面 rm 掉的东西，手机上也没了。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogChip("取消", onClick = onCancel)
                        Spacer(Modifier.size(8.dp))
                        DialogChip("打开", primary = true, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
