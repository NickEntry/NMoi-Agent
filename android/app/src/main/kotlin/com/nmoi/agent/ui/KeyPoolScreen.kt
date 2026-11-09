package com.nmoi.agent.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个供应商的 key 池（D302）。
 *
 * 形状照用户给的那张参考图：上面四个数（总数 / 正常 / 错误 / 负载均衡策略），下面一行一把 key。
 * 状态是**用户自己点的**，不是从请求里猜出来的——猜错的代价是"明明能用的那把被跳过"，
 * 而用户看不到为什么。自动标错要等错误码从 core 里带出来（`verification.md` V112 里记着）。
 */
@Composable
internal fun KeyPoolScreen(
    context: Context,
    providerId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pool by remember { mutableStateOf(KeyPool.Pool()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<KeyPool.Key?>(null) }
    var strategyOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<KeyPool.Key?>(null) }

    LaunchedEffect(providerId) {
        pool = withContext(Dispatchers.IO) { KeyPool.load(context, providerId) }
    }

    fun write(next: KeyPool.Pool) {
        pool = next
        scope.launch {
            withContext(Dispatchers.IO) { KeyPool.save(context, providerId, next) }
                .onFailure { notice = "没存上：${it.message}" }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "多Key管理",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (pool.keys.isNotEmpty()) {
                    IconActionButton(
                        image = ActionIcons.Trash2,
                        description = "清空这个池",
                        onClick = { confirmClear = true },
                        compact = true,
                    )
                    val allFavorite = pool.keys.all { it.favorite }
                    IconActionButton(
                        image = if (allFavorite) ActionIcons.HeartFilled else ActionIcons.Heart,
                        description = if (allFavorite) "全部取消收藏" else "全部收藏",
                        onClick = {
                            write(pool.copy(keys = pool.keys.map { it.copy(favorite = !allFavorite) }))
                        },
                        compact = true,
                    )
                }
                IconActionButton(
                    image = ActionIcons.Plus,
                    description = "加一把 key",
                    onClick = { draft = KeyPool.Key(id = KeyPool.newId(), value = "") },
                    compact = true,
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
                    StatRow("总数", pool.keys.size.toString())
                    RowDivider(leading = 16.dp)
                    StatRow("正常", pool.okCount.toString())
                    RowDivider(leading = 16.dp)
                    StatRow("错误", pool.errorCount.toString())
                    RowDivider(leading = 16.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { strategyOpen = true }
                            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "负载均衡策略",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (pool.strategy == KeyPool.FIXED) "顺序" else "轮询",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }

                if (pool.keys.isEmpty()) {
                    Text(
                        text = "还没有 key。点右上角那颗 ＋ 放一把进来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                    )
                } else {
                    SettingsCard(modifier = Modifier.padding(top = 12.dp)) {
                        pool.keys.forEachIndexed { index, key ->
                            if (index > 0) RowDivider(leading = 16.dp)
                            KeyRow(
                                key = key,
                                onToggle = { on ->
                                    write(
                                        pool.copy(
                                            keys = pool.keys.map { if (it.id == key.id) it.copy(enabled = on) else it },
                                        ),
                                    )
                                },
                                onFavorite = {
                                    write(
                                        pool.copy(
                                            keys = pool.keys.map {
                                                if (it.id == key.id) it.copy(favorite = !it.favorite) else it
                                            },
                                        ),
                                    )
                                },
                                onState = {
                                    val next = if (key.state == KeyPool.OK) KeyPool.ERROR else KeyPool.OK
                                    write(
                                        pool.copy(
                                            keys = pool.keys.map { if (it.id == key.id) it.copy(state = next) else it },
                                        ),
                                    )
                                },
                                onEdit = { draft = key },
                                onDelete = { confirmDelete = key },
                            )
                        }
                    }
                    Text(
                        text = "收藏的优先用；轮询只在收藏的那几把里转。全部标成错误时，仍会挑一把启用的发出去。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }

                notice?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    if (strategyOpen) {
        MenuSheet(
            title = "负载均衡策略",
            items = listOf(
                SheetItem(
                    label = "轮询",
                    value = if (pool.strategy == KeyPool.ROUND_ROBIN) "当前" else null,
                    onClick = {
                        write(pool.copy(strategy = KeyPool.ROUND_ROBIN))
                        strategyOpen = false
                    },
                ),
                SheetItem(
                    label = "顺序（永远用第一把可用的）",
                    value = if (pool.strategy == KeyPool.FIXED) "当前" else null,
                    onClick = {
                        write(pool.copy(strategy = KeyPool.FIXED))
                        strategyOpen = false
                    },
                ),
            ),
            onHint = { notice = it },
            onDismiss = { strategyOpen = false },
        )
    }

    draft?.let { editing ->
        KeySheet(
            key = editing,
            onApply = { next ->
                val exists = pool.keys.any { it.id == next.id }
                write(
                    pool.copy(
                        keys = if (exists) {
                            pool.keys.map { if (it.id == next.id) next else it }
                        } else {
                            pool.keys + next
                        },
                    ),
                )
                draft = null
            },
            onDismiss = { draft = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空这 ${pool.keys.size} 把 key？",
            detail = "池里全部 key 会一起没掉（这个文件里只剩一个空池）。关掉多Key模式不影响那个单独的 API Key。",
            confirmLabel = "清空",
            onCancel = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                KeyPool.clear(context, providerId)
                pool = KeyPool.Pool()
            },
        )
    }

    confirmDelete?.let { target ->
        ConfirmDialog(
            title = "删掉这一把？",
            detail = "「${target.label.ifBlank { target.masked }}」会从池里没掉。",
            confirmLabel = "删除",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                write(pool.copy(keys = pool.keys.filterNot { it.id == target.id }))
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyRow(
    key: KeyPool.Key,
    onToggle: (Boolean) -> Unit,
    onFavorite: () -> Unit,
    onState: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = when {
                !key.enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
                key.state == KeyPool.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier.clickable { onState() },
        ) {
            Text(
                text = when {
                    !key.enabled -> "停用"
                    key.state == KeyPool.ERROR -> "错误"
                    else -> "正常"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !key.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    key.state == KeyPool.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key.label.ifBlank { key.masked },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (key.label.isNotBlank()) {
                Text(
                    text = key.masked,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = key.enabled, onCheckedChange = onToggle)
        IconActionButton(
            image = if (key.favorite) ActionIcons.HeartFilled else ActionIcons.Heart,
            description = if (key.favorite) "取消收藏" else "收藏这一把（优先用它）",
            onClick = onFavorite,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.Pencil,
            description = "改这一把",
            onClick = onEdit,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.Trash2,
            description = "删这一把",
            onClick = onDelete,
            compact = true,
        )
    }
}

/** 加 / 改一把 key：值 + 一句认得出来的标签。值不回显历史——改就是整条重填。 */
@Composable
private fun KeySheet(
    key: KeyPool.Key,
    onApply: (KeyPool.Key) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(key.value) }
    var label by remember { mutableStateOf(key.label) }

    SheetFrame(title = if (key.value.isBlank()) "加一把 key" else "改这一把", onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "key",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(value = value, onChange = { value = it }, placeholder = "sk-…")
            Spacer(Modifier.size(12.dp))
            Text(
                text = "标签（可选，写一句认得出它的）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(value = label, onChange = { label = it }, placeholder = "例如：主号 / 备用")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ChipButton("取消", primary = false, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                ChipButton(
                    label = "存下来",
                    primary = true,
                    enabled = value.trim().isNotEmpty(),
                    onClick = {
                        onApply(key.copy(value = value.trim(), label = label.trim()))
                    },
                )
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
        )
    }
}
