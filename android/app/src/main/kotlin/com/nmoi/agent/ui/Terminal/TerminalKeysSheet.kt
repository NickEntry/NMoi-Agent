package com.nmoi.agent.ui.Terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmoi.agent.ui.ChipButton
import com.nmoi.agent.ui.SheetFrame

/**
 * 按键布局配置（D397）。
 *
 * 用户 2026-11-24 的要求：**可以配置菜单项，可以调 5~9 列**，可选键是那一串常用的。
 *
 * 形状：上面两排是当前布局（点一颗就换成"待放"），下面一列是可选键（点一下放到第一个空位）。
 * 不做拖拽：拖拽在手机上要长按、要算落点，而"点一下放进去"两步就够，还不会拖错。
 */
@Composable
internal fun TerminalKeysSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { TerminalPrefs.layout(context) }
    var columns by remember { mutableStateOf(saved.second) }
    // 用可变列表的列表：编辑过程中允许空位。
    var rows by remember {
        mutableStateOf(saved.first.map { it.toMutableList() })
    }
    var picked by remember { mutableStateOf<TerminalKey?>(null) }

    fun setAt(row: Int, column: Int, key: TerminalKey?) {
        val line = rows.getOrNull(row) ?: return
        if (column >= line.size) {
            while (line.size < column) line.add(TerminalKey.Esc)
            line.add(key ?: return)
        } else {
            if (key == null) line.removeAt(column) else line[column] = key
        }
        rows = rows.map { it.toMutableList() }
    }

    SheetFrame(title = "按键布局", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "列数：$columns（${
                    TerminalKeys.MIN_COLUMNS}–${TerminalKeys.MAX_COLUMNS}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (count in TerminalKeys.MIN_COLUMNS..TerminalKeys.MAX_COLUMNS) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (count == columns) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.clickable { columns = count },
                    ) {
                        Box(
                            modifier = Modifier.size(width = 36.dp, height = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (count == columns) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "当前布局（点一颗换成待放的键；长按清空）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            rows.forEachIndexed { rowIndex, line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (column in 0 until columns) {
                        val key = line.getOrNull(column)
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (key != null) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 38.dp)
                                    .clickable {
                                        val pending = picked
                                        if (pending != null) {
                                            setAt(rowIndex, column, pending)
                                            picked = null
                                        } else {
                                            picked = key
                                        }
                                    },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key?.label ?: "+",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                        color = if (key != null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (picked != null) {
                Text(
                    text = "待放：${picked?.label} —— 点上面任意一格放进去",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = "可选按键",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            val used = rows.flatten().toSet()
            var line = mutableListOf<TerminalKey>()
            for (key in TerminalKeys.ALL) {
                line.add(key)
                if (line.size == columns) {
                    KeyChoiceRow(
                        keys = line,
                        used = used,
                        onPick = { picked = it },
                    )
                    line = mutableListOf()
                }
            }
            if (line.isNotEmpty()) {
                KeyChoiceRow(keys = line, used = used, onPick = { picked = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ChipButton(label = "恢复默认", primary = false) {
                    rows = TerminalKeys.DEFAULT_ROWS.map { it.toMutableList() }
                    columns = TerminalKeys.DEFAULT_COLUMNS
                    picked = null
                }
                Spacer(Modifier.size(8.dp))
                ChipButton(label = "保存", primary = true) {
                    TerminalPrefs.setLayout(context, rows, columns)
                    onSaved()
                    onDismiss()
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun KeyChoiceRow(
    keys: List<TerminalKey>,
    used: Set<TerminalKey>,
    onPick: (TerminalKey) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (key in keys) {
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (key in used) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clickable { onPick(key) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = if (key in used) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
