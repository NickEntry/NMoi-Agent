package com.nmoi.agent.ui

/**
 * 安全页的数据形状（0.17g）。
 *
 * 这一层不碰 Android、不碰 JSON：合并规则、算"哪些组不问"、算"哪些名字要额外问"，
 * 三件都只是列表运算，能拿 JVM 单测钉住。真正的读写与迁移在 `SecurityRepository`。
 */
internal data class RuleGroup(
    val id: String,
    /** 显示名。内置组留空 = 用目录里那个名字。 */
    val name: String,
    val builtin: Boolean,
    val asks: Boolean,
    /** 只对内置组有意义：用户把它从列表里删掉过。自建组是真删，不留这个标记。 */
    val hidden: Boolean,
    /** 删掉那一刻这个内置组有几条规则。升级后条数变了 → 让它回来。 */
    val count: Int,
    /** 用户加进这一组的名字（小写、去重）。 */
    val items: List<String>,
)

internal data class SecurityState(
    val ask: Boolean = true,
    val blacklist: Boolean = true,
    val groups: List<RuleGroup> = emptyList(),
)

/** 一轮请求真正要下发的四个值。 */
internal data class GuardSettings(
    val ask: Boolean,
    val blacklist: Boolean,
    val offGroups: List<String>,
    val extra: List<String>,
)

/**
 * 目录（内置十组）+ 本地那份覆盖，合成界面上要摆的清单。
 *
 * 顺序照目录：内置十组在前、自建组按创建顺序在后。
 * 内置组被删过、但这一版里它的条数变了，就让它回来——用户删的是"当时那一组"，
 * 不是"以后这一组变成什么样子都不许再出现"。
 */
internal fun mergedGroups(builtin: List<RuleGroup>, local: List<RuleGroup>): List<RuleGroup> {
    val mine = local.associateBy { it.id }
    val out = mutableListOf<RuleGroup>()
    for (base in builtin) {
        val override = mine[base.id]
        if (override == null) {
            out.add(base)
            continue
        }
        out.add(
            base.copy(
                name = override.name.ifBlank { base.name },
                asks = override.asks,
                hidden = override.hidden && override.count == base.count,
                items = override.items,
            ),
        )
    }
    local.filter { !it.builtin }.forEach { out.add(it.copy(hidden = false, count = it.items.size)) }
    return out
}

/** 自己加的那些名字，只从"还留着、还问着"的组里出。 */
internal fun extraNames(groups: List<RuleGroup>): List<String> =
    groups.filter { !it.hidden && it.asks }
        .flatMap { it.items }
        .distinct()
        .sorted()

/** 不问的组：显式关掉的那些，加上被删掉的那些（删掉 = 别再拿这一组烦我）。 */
internal fun offGroupIds(groups: List<RuleGroup>): List<String> =
    groups.filter { it.hidden || !it.asks }.map { it.id }.sorted()

internal fun guardOf(state: SecurityState): GuardSettings = GuardSettings(
    ask = state.ask,
    blacklist = state.blacklist,
    offGroups = offGroupIds(state.groups),
    extra = extraNames(state.groups),
)