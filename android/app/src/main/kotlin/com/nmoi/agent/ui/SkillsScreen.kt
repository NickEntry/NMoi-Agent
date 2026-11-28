package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 技能（D444）。用户 2026-11-26：「顺便把技能这部分也按计划做好」。
 *
 * # 技能进上下文的**只有 name + description**
 *
 * 全文不进：模型知道有这么个技能，需要时自己去读 `SKILL.md`。这是 D80 定的约定，
 * 也是"技能"与"把整份文档塞进上下文"的区别——后者一轮就把窗口吃掉。
 *
 * # 关掉 ≠ 够不着
 *
 * 关掉只是**不告诉模型它存在**；文件照样能读（`Read` / 容器里的 `/skills`）。
 * 所以这里的开关不是"禁用"，是"要不要在这一轮提它"。
 *
 * # 与工具开关的区别
 *
 * 工具关掉 = 不声明（模型够不着）。技能关掉 = 不提（模型不知道，但够得着）。
 * 两件事，所以技能用的是「全部 / 点名」这一对，不是一串 `off`。
 */
@Composable
internal fun SkillsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<SkillRow>>(emptyList()) }
    var useAll by remember { mutableStateOf(ToolingRepository.skillsUseAll(context)) }
    var picked by remember { mutableStateOf(ToolingRepository.skillsPicked(context)) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            val root = File(context.filesDir, "skills")
            rows = withContext(Dispatchers.IO) {
                runCatching { readSkills(root) }
                    .onFailure { notice = "扫不出来：${it.message}" }
                    .getOrDefault(emptyList())
            }
            loading = false
            PageReady.notifyReadyAfterFrame()
        }
    }

    LaunchedEffect(Unit) { reload() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "技能",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when {
                            loading -> "扫描中…"
                            rows.isEmpty() -> "还没装技能"
                            useAll -> "${rows.size} 个技能 · 全部可用"
                            else -> "${rows.size} 个技能 · 选了 ${picked.count { name -> rows.any { it.name == name } }} 个"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---- 总开关 ----
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "使用全部技能",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "关掉之后只提下面勾中的那几个；文件照样能读，只是不主动提",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ChipButton(
                            label = if (useAll) "开" else "关",
                            primary = useAll,
                        ) {
                            useAll = !useAll
                            ToolingRepository.setSkillsUseAll(context, useAll)
                        }
                    }
                }

                if (rows.isEmpty()) {
                    Text(
                        text = "技能放在 `files/skills/<名字>/SKILL.md`。从存储页能看到这个目录，" +
                            "把技能包解压进去就会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                // ---- 逐个 ----
                for (row in rows) {
                    val on = useAll || row.name in picked
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // `useAll` 开着时逐个不可点：那会让"全部"与"点名"互相打架
                                // （勾了三个但全部开着，关掉全部之后到底带哪几个？）。
                                .clickable(enabled = !useAll) {
                                    picked = if (row.name in picked) {
                                        picked - row.name
                                    } else {
                                        picked + row.name
                                    }
                                    ToolingRepository.setSkillsPicked(context, picked)
                                }
                                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (on) ActionIcons.CheckSquare else ActionIcons.Square,
                                contentDescription = null,
                                tint = if (on) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = row.description.ifBlank { "（SKILL.md 里没有 description）" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            row.version?.let { version ->
                                Text(
                                    text = "v$version",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "进上下文的只有名字与一句描述，全文由模型自己按需读取。" +
                        "所以技能再多也不会把窗口吃掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 技能列表的一行。 */
internal data class SkillRow(
    val name: String,
    val description: String,
    val version: String?,
    val path: String,
)

/**
 * 扫技能目录。
 *
 * 走 native（`nmoi_skills_list_json`）而不是在 Kotlin 里解 front matter：
 * 解析规则（大小写不敏感的文件名、front matter 的兜底）只有一份，
 * 两处各写一遍迟早一边认得出、另一边认不出。
 */
private suspend fun readSkills(root: File): List<SkillRow> {
    if (!root.isDirectory) return emptyList()
    val envelope = NmoiFfi.skillsList(root.absolutePath).getOrThrow()
    val array: JSONArray = envelope.optJSONArray("skills") ?: JSONArray()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val name = row.optString("name")
            if (name.isBlank()) continue
            add(
                SkillRow(
                    name = name,
                    description = row.optString("description"),
                    version = row.optString("version").takeIf { it.isNotBlank() },
                    path = row.optString("path"),
                ),
            )
        }
    }
}
