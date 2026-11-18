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
    /** 目录里这一组现在几条。旧文件里它也当过"删掉那一刻有几条"，只留给读旧文件用。 */
    val count: Int,
    /** 用户加进这一组的名字（小写、去重）。 */
    val items: List<String>,
    /**
     * 内置组：写下这一行时目录那一组的指纹（`groupPrint`）。
     * 空 = 没改过、或者旧文件里没记过——两种都按"已改"处理。
     */
    val base: String = "",
    /** 派生值，不落盘：这一组与目录的关系。 */
    val state: PresetState = PresetState.Follow,
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
 * 文件里没有这一行 = 用户没动过 = 整组跟着目录走（名字、条数、规则都是新的）。
 * 删掉的内置组记着"删的那一刻目录长什么样"：这一版目录变了，说明它已经不是用户当初删的那一组，
 * 就让它回来（`movedOn`）。旧文件只记了条数，退回按条数比。
 */
internal fun mergedGroups(builtin: List<RuleGroup>, local: List<RuleGroup>): List<RuleGroup> {
    val mine = local.associateBy { it.id }
    val out = mutableListOf<RuleGroup>()
    for (base in builtin) {
        val override = mine[base.id]
        if (override == null) {
            out.add(base.copy(state = PresetState.Follow))
            continue
        }
        val moved = movedOn(override, base)
        val gone = override.hidden && !moved
        // 自动回来的组：用户当初那一手"删掉"已经作废，别再拿"过时"催他跟新版。
        val state = if (override.hidden && moved) {
            PresetState.Follow
        } else {
            presetStateOf(editedOf(override), override.base, base.base)
        }
        out.add(
            base.copy(
                name = override.name.ifBlank { base.name },
                asks = override.asks,
                hidden = gone,
                items = override.items,
                base = base.base,
                state = state,
            ),
        )
    }
    for (custom in local.filter { !it.builtin }) {
        out.add(custom.copy(hidden = false, count = custom.items.size, base = "", state = PresetState.Follow))
    }
    return out
}

/**
 * 用户动过这一组没有。**传的必须是用户那一份覆盖**：`name` 空 = 没改名
 * （"改回目录里那个名字"的那一版，`commit` 会先把它清成空再问这里）。
 */
internal fun editedOf(group: RuleGroup): Boolean =
    !group.builtin || group.name.isNotBlank() || !group.asks || group.hidden || group.items.isNotEmpty()

/** 这一次的改动是不是落在这个组上（改回原样不算）。 */
internal fun touched(before: RuleGroup, after: RuleGroup): Boolean =
    before.name != after.name ||
        before.asks != after.asks ||
        before.hidden != after.hidden ||
        before.items != after.items

/** 目录里这一组换过版本没有。旧文件没有指纹，退回条数。 */
internal fun movedOn(local: RuleGroup, catalog: RuleGroup): Boolean =
    if (local.base.isBlank()) local.count != catalog.count else local.base != catalog.base

/**
 * 一组规则内容的指纹（FNV-1a 64）。
 *
 * 只用来判"这一组跟写下那一行时还是不是同一组"——顺序与 `deny` 都进指纹，
 * 所以改了某一条的措辞、或者把一条升成拒绝档，都算新版。
 */
internal fun groupPrint(rows: List<Pair<String, Boolean>>): String {
    var hash = 0xcbf29ce484222325uL
    for ((label, deny) in rows) {
        for (byte in label.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        }
        hash = (hash xor if (deny) 1uL else 0uL) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
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