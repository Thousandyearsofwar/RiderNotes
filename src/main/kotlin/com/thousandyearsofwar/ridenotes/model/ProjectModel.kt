package com.thousandyearsofwar.ridenotes.model

/**
 * 分组节点。可以嵌套（parentId 指向父组，null 表示顶层）。
 */
/** 同一层级里组和笔记共用一个顺序空间。 */
interface OrderedNode {
    var order: Int
}

class GroupNode(
    var id: String = "g_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8),
    var title: String = "",
    var color: Int = 0,
    var visible: Boolean = true,
    var parentId: String? = null,
    override var order: Int = 0,
    /** 与锚点共用的混排顺序键（schema 3 起）：组和锚点可以任意穿插。 */
    var orderValue: Double = 0.0,
    var locked: Boolean = false,
    var compact: Boolean = false,
    var tags: MutableList<String> = mutableListOf()
) : OrderedNode

/**
 * 锚点在项目里的归属与顺序（不属于分片文件，属于项目索引）。
 */
class Placement(
    var anchorId: String = "",
    var groupId: String? = null,
    var visible: Boolean = true,
    override var order: Int = 0,
    var locked: Boolean = false,
    var compact: Boolean = false,
    /** 为 null 表示跟随分组颜色；有值则覆盖。 */
    var colorOverride: Int? = null
) : OrderedNode

/** 标签分组（标签库里的一级分类）。 */
class TagGroupNode(
    var id: String = "tg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8),
    var name: String = "",
    var order: Int = 0
)

/** 标签定义（标签库里的一个条目）。 */
class TagDef(
    var id: String = "t_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8),
    var name: String = "",
    var color: Int = 0,
    var groupId: String? = null
)

/**
 * 项目级索引：分组树 + 锚点归属 + 标签库。放在 `.ridenotes/_project.json`。
 */
class ProjectIndex(
    var schema: Int = 2,
    var groups: MutableList<GroupNode> = mutableListOf(),
    var placements: MutableList<Placement> = mutableListOf(),
    var tags: MutableList<TagDef> = mutableListOf(),
    var tagGroups: MutableList<TagGroupNode> = mutableListOf(),
    /** 面板折叠的分组（UI 状态，持久化以便重启保持）。 */
    var collapsedGroups: MutableList<String> = mutableListOf(),
    /** 面板里展开子笔记的锚点。 */
    var expandedAnchors: MutableList<String> = mutableListOf(),
    /** 新建锚点默认落入的分组；null 表示根级。 */
    var defaultGroupId: String? = null
) {
    fun group(id: String?): GroupNode? {
        if (id == null) return null
        var map = groupMap
        if (map == null || map.size != groups.size) {
            map = groups.associateBy { it.id }.toMutableMap()
            groupMap = map
        }
        return map[id]
    }

    /** id -> Placement 的缓存。没有缓存时是 O(n) 线性扫描，千级锚点会被拖到秒级。 */
    @kotlin.jvm.Transient
    private var placementMap: MutableMap<String, Placement>? = null

    @kotlin.jvm.Transient
    private var groupMap: MutableMap<String, GroupNode>? = null

    fun placement(anchorId: String): Placement {
        var map = placementMap
        if (map == null || map.size != placements.size) {
            map = placements.associateBy { it.anchorId }.toMutableMap()
            placementMap = map
        }
        map[anchorId]?.let { return it }
        val created = Placement(anchorId = anchorId, order = nextOrder(null))
        placements.add(created)
        map[anchorId] = created
        return created
    }

    fun childrenOf(parentId: String?): List<GroupNode> =
        groups.filter { it.parentId == parentId }.sortedBy { it.order }

    fun notesOf(groupId: String?): List<Placement> =
        placements.filter { it.groupId == groupId }.sortedBy { it.order }

    /** 组和笔记混排后的顺序（同一个 parent 下共用一个序号空间）。 */
    fun sortedChildren(parentId: String?): List<OrderedNode> =
        (groups.filter { it.parentId == parentId } + placements.filter { it.groupId == parentId })
            .sortedWith(compareBy({ it.order }, { if (it is GroupNode) 0 else 1 }))

    /** 重排后把序号压实成 0..n。 */
    fun renumber(parentId: String?, ordered: List<OrderedNode>) {
        ordered.forEachIndexed { index, node -> node.order = index }
    }

    /** 从该分组一路往上，只要有一层被隐藏就算隐藏。 */
    fun effectivelyVisible(groupId: String?): Boolean {
        var current = group(groupId) ?: return true
        var guard = 0
        while (guard++ < 32) {
            if (!current.visible) return false
            val parent = group(current.parentId) ?: return true
            current = parent
        }
        return true
    }

    /** 分组颜色：取最上层祖先的颜色，保证"同一组同色"。 */
    fun effectiveColor(groupId: String?): Int? {
        var current = group(groupId) ?: return null
        var guard = 0
        var last: GroupNode = current
        while (guard++ < 32) {
            last = current
            current = group(current.parentId) ?: return last.color
        }
        return last.color
    }

    /** 祖先里只要有一层上锁，就算锁定。 */
    fun effectivelyLocked(groupId: String?): Boolean = walkFlag(groupId) { it.locked }

    /** 祖先里只要有一层是简洁模式，就算简洁。 */
    fun effectivelyCompact(groupId: String?): Boolean = walkFlag(groupId) { it.compact }

    private fun walkFlag(groupId: String?, predicate: (GroupNode) -> Boolean): Boolean {
        var current = group(groupId) ?: return false
        var guard = 0
        while (guard++ < 32) {
            if (predicate(current)) return true
            current = group(current.parentId) ?: return false
        }
        return false
    }

    fun nextOrder(groupId: String?): Int =
        (childrenOf(groupId).map { it.order } + notesOf(groupId).map { it.order })
            .maxOrNull()?.plus(1) ?: 0
}
