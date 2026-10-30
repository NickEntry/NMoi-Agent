package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 面板里的一行：一个 (供应商, 模型) 组合。
 *
 * `key` 是这一行在所有地方的身份证（当前选中、收藏、LazyColumn 的 key 都用它）。
 * **不拆开用**——模型 id 本身就可能带 `/`（`alibaba/qwen-max`），拆了就对不上。
 */
internal data class ModelChoice(val provider: ProviderEntry, val model: ProviderModel) {
    val key: String get() = ModelFavorites.key(provider.id, model.id)

    /** 搜索命中：模型名、模型 id、供应商名，任一含关键字即可（忽略大小写）。 */
    fun matches(query: String): Boolean {
        val q = query.lowercase()
        return model.displayName.lowercase().contains(q) ||
            model.id.lowercase().contains(q) ||
            provider.name.lowercase().contains(q) ||
            provider.id.lowercase().contains(q)
    }
}

/**
 * 模型选择面板（0.15x）。
 *
 * 形状：**搜索框 → 按供应商分组 → 每行（名称 / id / 窗口徽标 / 心形）→ 底部供应商 chips**。
 * 参考过别的客户端那张图，但只借了结构：行高 52、圆角 20、配色用我们自己的
 * （“不要一比一复刻，用我们的行高/圆角/配色”）。
 *
 * 三件事是这面板存在的理由，缺一件它就不比原来那个平铺列表强：
 *
 * 1. **能搜**——模型 id 是那种"少一个字符就 404"的东西，列表一长，翻比想更费劲；
 * 2. **按供应商分组**——同一个模型名在不同端点下价钱不一样，混成一个列表等于看不出用的是谁的；
 * 3. **收藏**——常用的一两个置顶，不必每次往下翻（收藏存库里，见 [ModelFavorites]）。
 *
 * 收藏与"当前"都由调用方传进来的 [choices] 决定：面板自己不读供应商配置，
 * 两处各读一次就会出现"面板里有、页面上没有"这种对不上的情况。
 */
@Composable
internal fun ModelPickerSheet(
    databasePath: String,
    title: String,
    choices: List<ModelChoice>,
    /** 当前用的那个（[ModelChoice.key]）；没有就 `null`。 */
    selected: String?,
    /** 一条模型都没有时说的话：点了给原因，不给一个空面板。 */
    emptyHint: String,
    onPick: (ModelChoice) -> Unit,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    // 打开时读一遍收藏，顺手清掉指向**已经不存在的模型**的键（删过供应商 / 删过模型）。
    // 只在有模型可对照时才清：读失败给的空列表不能当成"全都不存在"。
    LaunchedEffect(choices) {
        if (choices.isEmpty()) return@LaunchedEffect
        favorites = withContext(Dispatchers.IO) {
            ModelFavorites.sync(databasePath, choices.map { it.key }.toSet())
        }
    }

    // 点了立刻改本地那一份（心形要跟手），落库失败再换回来并说一句。
    val toggleFavorite: (ModelChoice) -> Unit = { choice ->
        val on = choice.key !in favorites
        favorites = if (on) favorites + choice.key else favorites - choice.key
        if (favorites.isEmpty()) onlyFavorites = false
        scope.launch {
            ModelFavorites.set(databasePath, choice.key, on).onFailure { error ->
                favorites = if (on) favorites - choice.key else favorites + choice.key
                onNotice("收藏没写成：${error.message}")
            }
        }
    }

    val needle = query.trim()
    val groups = remember(choices, needle, providerFilter, onlyFavorites, favorites) {
        val filtered = choices.filter { choice ->
            val visible = when {
                onlyFavorites -> choice.key in favorites
                providerFilter != null -> choice.provider.id == providerFilter
                else -> true
            }
            visible && (needle.isEmpty() || choice.matches(needle))
        }
        filtered
            .groupBy { it.provider.id }
            .map { (_, rows) ->
                // 组内收藏在前；`sortedBy` 是稳定排序，收藏之间仍是模型表里的顺序。
                rows.first().provider to rows.sortedBy { it.key !in favorites }
            }
    }
    val providers = remember(choices) { choices.map { it.provider }.distinctBy { it.id } }
    // 列表最多占半屏：矮屏（横屏）上整张面板要能连同搜索框与 chips 一起待在下半屏里，
    // 否则标题会被顶出屏幕，而那一行正是"这是什么面板"的答案。
    val listCap = (LocalConfiguration.current.screenHeightDp / 2).dp

    SheetFrame(title = title, onDismiss = onDismiss) {
        SearchRow(value = query, onChange = { query = it })

        if (groups.isEmpty()) {
            Text(
                text = if (choices.isEmpty()) emptyHint else "没有匹配「$needle」的模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listCap),
            ) {
                groups.forEach { (provider, rows) ->
                    item(key = "group:${provider.id}") {
                        GroupHeader(provider = provider, count = rows.size)
                    }
                    items(rows, key = { it.key }) { choice ->
                        ModelRow(
                            choice = choice,
                            current = choice.key == selected,
                            favorite = choice.key in favorites,
                            onPick = {
                                onPick(choice)
                                onDismiss()
                            },
                            onFavorite = { toggleFavorite(choice) },
                        )
                    }
                }
            }
        }

        // 列表与 chips 之间那道线：列表是会滚的，不划线时最后一行看着像 chips 的标题。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                label = "全部",
                selected = providerFilter == null && !onlyFavorites,
                onClick = {
                    providerFilter = null
                    onlyFavorites = false
                },
            )
            // 一个收藏都没有时不给这一格：点进去只会看到"没有匹配"，那是个死胡同。
            if (favorites.isNotEmpty()) {
                FilterChip(
                    label = "收藏 ${favorites.size}",
                    selected = onlyFavorites,
                    onClick = {
                        onlyFavorites = true
                        providerFilter = null
                    },
                )
            }
            providers.forEach { provider ->
                FilterChip(
                    label = provider.name.ifBlank { provider.id },
                    selected = providerFilter == provider.id,
                    onClick = {
                        providerFilter = provider.id
                        onlyFavorites = false
                    },
                )
            }
        }
    }
}

/** 搜索框：和抽屉里那颗胶囊同形（999 圆角、surfaceContainerHigh），但**真的能输入**。 */
@Composable
private fun SearchRow(value: String, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ActionIcons.SearchInput,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isEmpty()) {
                    Text(
                        text = "搜索模型或供应商",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
            if (value.isNotEmpty()) {
                // 清空：热区 36、视觉 16。框里这一颗不按 48dp 走——48 会把胶囊撑成一个方块。
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ActionIcons.Close,
                        contentDescription = "清空搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 组标题：`供应商名 · 兼容 · 3`。第二段是 wire 形态——模型一样、端点不同，价钱就不一样。 */
@Composable
private fun GroupHeader(provider: ProviderEntry, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = provider.name.ifBlank { provider.id },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = " · ${ProviderKinds.label(provider.kind)} · $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

/**
 * 一行模型。
 *
 * 右边三样按"信息密度从高到低"排：**当前**（勾，主色）→ 窗口徽标 → 心形。
 * 勾在最前是因为它是"这一行发出去的是什么"的答案；心形在最后，因为它是唯一的可点区域，
 * 放在拇指容易落到的边上（行本身也是可点的，两者靠 36dp 的独立热区分开）。
 */
@Composable
private fun ModelRow(
    choice: ModelChoice,
    current: Boolean,
    favorite: Boolean,
    onPick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onPick)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = choice.model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 名字和 id 不一样时才补一行 id：模型 id 是唯一能拿去对端点日志的东西，
            // 但它只该在"名字不是 id"的时候出现，否则同一串字写两遍。
            if (choice.model.name.isNotBlank() && choice.model.name != choice.model.id) {
                Text(
                    text = choice.model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (current) {
            Icon(
                imageVector = ActionIcons.Check,
                contentDescription = "当前在用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp),
            )
        }
        val window = choice.model.contextWindow
        if (window != null) {
            Spacer(Modifier.width(8.dp))
            Badge(text = formatWindow(window))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (favorite) ActionIcons.HeartFilled else ActionIcons.Heart,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 小徽标（窗口大小那种）。和 `TabChip` 的未选中态同色，不额外造一层灰。 */
@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 底部那颗筛选 chip：选中用 `secondaryContainer`（与工具卡 stdout/stderr 页签同一套）。 */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 窗口徽标上的数字：`128k` / `1M` / 原值。
 *
 * 用整数算，不走 `String.format`——那个按系统区域设置选小数点，某些区域会给出逗号，
 * 而这一格只有 40dp 宽。
 */
private fun formatWindow(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val tenths = (tokens + 50_000) / 100_000
        if (tenths % 10 == 0) "${tenths / 10}M" else "${tenths / 10}.${tenths % 10}M"
    }

    tokens >= 1_000 -> "${(tokens + 500) / 1_000}k"

    else -> "$tokens"
}