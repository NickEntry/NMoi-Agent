package com.nmoi.agent.ui

/**
 * 抽屉多选（0.15d / D207）的纯逻辑。
 *
 * 为什么单独拎出来：这一层错起来不显眼——置顶顺序错一位，用户下次打开只看到
 * "怎么跑到第二去了"；全选的判据写错，点两次会有一次没反应。放成纯函数才测得动。
 *
 * 所有"选中的行"都**按列表里看到的顺序**返回（`rowsInOrder`），不用 `Set` 的迭代顺序：
 * 置顶与删除都要求"我勾的顺序就是我看到的顺序"。
 */
internal object ConversationSelection {

    fun toggle(ids: Set<String>, id: String): Set<String> =
        if (id in ids) ids - id else ids + id

    /** 勾上的行，按列表（含置顶排序）里的先后。 */
    fun rowsInOrder(rows: List<ConversationRow>, ids: Set<String>): List<ConversationRow> =
        rows.filter { it.id in ids }

    /**
     * 全选 / 取消全选。
     *
     * 判据是"**当前列表里每一条**都被勾上"：用 `rows.size == ids.size` 会被
     * 已删除却还留在选择集里的 id 骗过去（那时点全选像没反应）。
     */
    fun toggleAll(rows: List<ConversationRow>, ids: Set<String>): Set<String> =
        if (rows.isNotEmpty() && rows.all { it.id in ids }) {
            emptySet()
        } else {
            rows.map { it.id }.toSet()
        }

    /** 选中的是不是**全都**已经置顶——决定底部那颗按钮写「置顶」还是「取消置顶」。 */
    fun allPinned(pinned: List<String>, chosen: List<ConversationRow>): Boolean =
        chosen.isNotEmpty() && chosen.all { it.id in pinned }

    /**
     * 多选置顶的结果。
     *
     * - 选中的全都已置顶 → 把它们从置顶里去掉，其余顺序不动；
     * - 否则 → 选中的（按列表顺序）排到最前，剩下已置顶的接在后面；
     *   不置顶的会话本来就不在这个数组里，顺序由 `updated_at` 决定。
     *
     * 不做"部分取消"：同一颗按钮既置顶又取消的话，用户勾三条（两条已置顶、一条没有）
     * 时没法从文案上看出会发生什么。所以这一颗只按"全都已置顶"这一个条件分叉。
     */
    fun pinAfterToggle(pinned: List<String>, chosen: List<ConversationRow>): List<String> =
        if (allPinned(pinned, chosen)) {
            pinned.filterNot { id -> chosen.any { it.id == id } }
        } else {
            chosen.map { it.id } + pinned.filterNot { id -> chosen.any { it.id == id } }
        }

    /** 删掉之后把选择集里已经不存在的 id 清掉：留着它，全选的判据会一直歪。 */
    fun prune(ids: Set<String>, rows: List<ConversationRow>): Set<String> {
        val alive = rows.map { it.id }.toSet()
        return ids.filter { it in alive }.toSet()
    }
}
