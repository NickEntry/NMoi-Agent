package com.nmoi.agent.ui

/**
 * 工具面板的分组与排序（0.17g）。
 *
 * 顺序照人的习惯：读 → 写 → 语音 → 命令。清单本身仍从 native 注册表来；
 * 这里只决定**摆在哪儿**，不决定有没有——注册表里多一个名字而这里没列，
 * 它会掉进最后的「其他」，不会消失。
 */
internal data class ToolGroup(val title: String, val names: List<String>)

private val GROUPS: List<Pair<String, List<String>>> = listOf(
    "读" to listOf("read_file", "list_dir", "find_files", "search", "View-Image"),
    "写" to listOf("write_file", "edit_file", "edit_unit"),
    "语音" to listOf("Voice-Caps", "Speak"),
    "命令" to listOf("System-Shell", "WorkSpace-EnvirShell", "Session-Files"),
)

internal fun toolGroups(names: List<String>): List<ToolGroup> {
    val present = names.filter { it.isNotBlank() }.distinct().toSet()
    val out = mutableListOf<ToolGroup>()
    val listed = mutableSetOf<String>()
    for ((title, members) in GROUPS) {
        val hit = members.filter { present.contains(it) }
        if (hit.isEmpty()) continue
        out.add(ToolGroup(title, hit))
        listed.addAll(hit)
    }
    val rest = names.filter { it.isNotBlank() && !listed.contains(it) }.distinct()
    if (rest.isNotEmpty()) out.add(ToolGroup("其他", rest))
    return out
}

/**
 * 工具名的中文显示名。
 *
 * 放在这里而不是 ChatScreen：工具面板与工作区「工具」页签都要用它，
 * 两处各写一份就会有两个说法（用户看到的是"运行命令"和"System-Shell"）。
 * 认不出的名字原样返回——注册表里多一个工具时，界面上至少还认得出它叫什么。
 */
internal fun toolDisplayName(name: String): String = when (name) {
    "System-Shell" -> "运行命令"
    "WorkSpace-EnvirShell" -> "运行命令（容器）"
    "View-Image" -> "查看图像"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "edit_file" -> "修改文件"
    "list_dir" -> "列出目录"
    "find_files" -> "查找文件"
    "search" -> "搜索内容"
    "edit_unit" -> "修改消息"
    "Session-Files" -> "读别的会话"
    "Voice-Caps" -> "语音能力"
    "Speak" -> "文字转语音"
    else -> name
}

/**
 * 工具清单 + 每个工具要不要工作区（D419）。
 *
 * `workspace` 那一列由**原生注册表**算（`Capability::needs_workspace`），这里只负责解析。
 * 以前 Kotlin 自己抄了一份 when 表，抄漏了三个（`View-Image` / `WorkSpace-EnvirShell` /
 * `edit_unit`）——表现是"在工作区里关掉了一个跟工作区无关的工具"，用户以为生效了，
 * 其实它压根不在那一批里。
 */
internal data class ToolCatalog(
    val names: List<String>,
    /** 需要工作区的那一批（来自 core 的能力判定，不是这里猜的）。 */
    val workspace: Set<String>,
) {
    /** 不依赖工作区的那一批（「本地工具」菜单里）。 */
    val local: List<String> get() = names.filter { it !in workspace }

    /** 依赖工作区的那一批（工作区那一行的 ⚙ 里）。 */
    val workspaceOnly: List<String> get() = names.filter { it in workspace }
}

/** 纯逻辑那一半：两份清单进来，去空去重再分。单测打这里。 */
internal fun toolCatalog(names: List<String>, workspace: List<String>): ToolCatalog =
    ToolCatalog(
        names = names.filter { it.isNotBlank() }.distinct(),
        workspace = workspace.filter { it.isNotBlank() }.toSet(),
    )

/**
 * 从 `toolNamesJson` 的返回里解析。
 *
 * 缺 `workspace` 那一列时按"全部本地"退：老版本 `.so` 配新界面时，开关至少还在原处，
 * 不会整块消失。
 */
internal fun parseToolCatalog(json: org.json.JSONObject?): ToolCatalog {
    fun read(key: String): List<String> {
        val array = json?.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
    return toolCatalog(names = read("names"), workspace = read("workspace"))
}
