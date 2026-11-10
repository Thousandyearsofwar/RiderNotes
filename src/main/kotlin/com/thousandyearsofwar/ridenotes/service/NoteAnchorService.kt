package com.thousandyearsofwar.ridenotes.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.AnchorStore
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.model.Note

/**
 * 锚点的内存视图 + 持久化。
 *
 * 所有改动都走这里：改完立刻落盘（单文件分片很小），然后通知渲染层刷新。
 */
@Service(Service.Level.PROJECT)
class NoteAnchorService(private val project: Project) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, MutableList<Anchor>>()

    /** 本次会话写过盘的分片：后台预取时不能覆盖它们。 */
    private val writtenPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 后台预取完成后把解析结果塞进缓存（在 EDT 上调用）。
     * 已经写过的分片跳过，避免用旧内容覆盖新数据。
     */
    fun seedCache(shards: List<com.thousandyearsofwar.ridenotes.model.AnchorFile>) {
        var seeded = 0
        shards.forEach { shard ->
            val path = shard.file ?: return@forEach
            if (path in writtenPaths) return@forEach
            val list = shard.anchors.sortedBy { it.startOffset }.toMutableList()
            if (cache.putIfAbsent(path, list) == null) seeded++
        }
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("prefetched $seeded shards")
    }
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addChangeListener(listener: (relativePath: String) -> Unit) {
        listeners.add(listener)
    }

    fun relativePath(file: VirtualFile): String? = AnchorStore.relativePath(project, file.path)

    fun anchorsFor(file: VirtualFile): List<Anchor> {
        val relativePath = relativePath(file) ?: return emptyList()
        return anchorsFor(relativePath)
    }

    fun anchorsFor(relativePath: String): List<Anchor> = mutableAnchors(relativePath)

    private fun mutableAnchors(relativePath: String): MutableList<Anchor> =
        cache.getOrPut(relativePath) { load(relativePath) }

    private fun load(relativePath: String): MutableList<Anchor> {
        val stored = AnchorStore.load(project, relativePath)?.anchors ?: mutableListOf()
        return stored.sortedBy { it.startOffset }.toMutableList()
    }

    /** 默认分组：新建锚点自动落进去；分组被删后自动失效。 */
    fun defaultGroupId(): String? {
        val index = index()
        val id = index.defaultGroupId ?: return null
        return id.takeIf { index.group(it) != null }
    }

    fun setDefaultGroup(id: String?) {
        index().defaultGroupId = id
        saveIndex()
    }

    fun addAnchor(file: VirtualFile, anchor: Anchor) {
        val relativePath = relativePath(file) ?: return
        val list = mutableAnchors(relativePath)
        anchor.color = nextColor(list)
        if (anchor.groupId == null) anchor.groupId = defaultGroupId()
        list.add(anchor)
        list.sortBy { it.startOffset }
        persist(relativePath)
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "add anchor id=${anchor.id} file=$relativePath lines=${anchor.startLine}-${anchor.endLine} color=${anchor.color}"
        )
    }

    /** 批量添加（导入/压测用）：只分配一次颜色、只写一次盘。 */
    fun addAnchors(file: VirtualFile, anchors: List<com.thousandyearsofwar.ridenotes.model.Anchor>) {
        val relativePath = relativePath(file) ?: return
        val list = mutableAnchors(relativePath)
        val fallbackGroup = defaultGroupId()
        anchors.forEach { anchor ->
            anchor.color = nextColor(list)
            if (anchor.groupId == null) anchor.groupId = fallbackGroup
            list.add(anchor)
        }
        list.sortBy { it.startOffset }
        persist(relativePath)
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("add ${anchors.size} anchors to $relativePath")
    }

    /** 批量删除锚点：只写一次盘，同时清掉索引里的残留归属。 */
    fun removeAnchors(file: VirtualFile, ids: Collection<String>): Int {
        val relativePath = relativePath(file) ?: return 0
        val list = mutableAnchors(relativePath)
        val before = list.size
        list.removeIf { it.id in ids }
        val removed = before - list.size
        if (removed > 0) {
            dropPlacements(ids)
            persist(relativePath)
        }
        return removed
    }

    /** 删锚点后清理索引里的 placement，否则 _project.json 会无限膨胀。 */
    private fun dropPlacements(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val index = index()
        val before = index.placements.size
        index.placements.removeIf { it.anchorId in ids }
        index.expandedAnchors.removeAll(ids.toSet())
        if (index.placements.size != before) saveIndex()
    }

    /** 统计指向已不存在锚点的 placement（只读，不动数据）。 */
    fun countOrphanPlacements(): Int {
        val known = AnchorStore.listAll(project)
            .flatMap { shard -> shard.anchors.map { it.id } }
            .toSet()
        return index().placements.count { it.anchorId !in known }
    }

    /**
     * 一次性清理：去掉所有指向已不存在锚点的 placement。
     * 老版本删除锚点时没清理，`_project.json` 可能已经几 MB，启动解析很慢。
     */
    fun pruneOrphanPlacements(): Int {
        val known = AnchorStore.listAll(project)
            .flatMap { shard -> shard.anchors.map { it.id } }
            .toSet()
        val index = index()
        val before = index.placements.size
        index.placements.removeIf { it.anchorId !in known }
        index.expandedAnchors.removeIf { it !in known }
        index.collapsedGroups.removeIf { groupId -> index.groups.none { it.id == groupId } }
        val removed = before - index.placements.size
        if (removed > 0) {
            saveIndex()
            com.thousandyearsofwar.ridenotes.util.NoteLog.info("pruned $removed orphan placements")
        }
        return removed
    }

    /** 批量删除某锚点下的笔记：只写一次盘。 */
    fun deleteNotes(file: VirtualFile, anchorId: String, noteIds: Collection<String>): Int {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return 0
        val before = anchor.notes.size
        anchor.notes.removeIf { it.id in noteIds }
        val removed = before - anchor.notes.size
        if (removed > 0) persistAndNotify(file)
        return removed
    }

    fun removeAnchor(file: VirtualFile, anchorId: String): Anchor? {
        val relativePath = relativePath(file) ?: return null
        val list = mutableAnchors(relativePath)
        val removed = list.firstOrNull { it.id == anchorId } ?: return null
        list.remove(removed)
        dropPlacements(listOf(anchorId))
        persist(relativePath)
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("remove anchor id=$anchorId file=$relativePath")
        return removed
    }

    /** 修改某条笔记的正文和/或标签（null 表示不改）。 */
    fun updateNoteContent(file: VirtualFile, anchorId: String, noteId: String, body: String?, tags: List<String>?) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        val note = anchor.notes.firstOrNull { it.id == noteId } ?: return
        if (body != null) note.body = body.trim()
        if (tags != null) {
            note.tags = tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        }
        note.updatedAt = java.time.Instant.now().toString()
        persistAndNotify(file)
    }

    fun addNote(file: VirtualFile, anchorId: String, body: String): Note? =
        addNote(file, anchorId, body, resolveAuthor(project))

    fun addNote(file: VirtualFile, anchorId: String, body: String, author: String): Note? {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return null
        val note = Note(body = body.trim(), author = author)
        anchor.notes.add(0, note)
        anchor.updatedAt = java.time.Instant.now().toString()
        persistAndNotify(file)
        return note
    }

    fun deleteNote(file: VirtualFile, anchorId: String, noteId: String) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        anchor.notes.removeIf { it.id == noteId }
        anchor.updatedAt = java.time.Instant.now().toString()
        persistAndNotify(file)
    }

    fun updateNote(file: VirtualFile, anchorId: String, noteId: String, body: String) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        val note = anchor.notes.firstOrNull { it.id == noteId } ?: return
        note.body = body.trim()
        note.updatedAt = java.time.Instant.now().toString()
        anchor.updatedAt = note.updatedAt
        persistAndNotify(file)
    }

    /** 文档里改过以后，把锚点的偏移同步回内存（下次落盘时写出去）。 */
    fun syncOffsets(file: VirtualFile, anchorId: String, start: Int, end: Int, startLine: Int, endLine: Int) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        if (anchor.startOffset == start && anchor.endOffset == end) return
        anchor.startOffset = start
        anchor.endOffset = end
        anchor.startLine = startLine
        anchor.endLine = endLine
    }

    fun persist(file: VirtualFile) {
        val relativePath = relativePath(file) ?: return
        persist(relativePath)
    }

    /** 丢掉内存缓存，重新从磁盘读。 */
    fun reload(relativePath: String) {
        cache.remove(relativePath)
        anchorsFor(relativePath)
        notifyChanged(relativePath)
    }

    private fun persistAndNotify(file: VirtualFile) {
        val relativePath = relativePath(file) ?: return
        persist(relativePath)
        notifyChanged(relativePath)
    }

    // ------------------------------------------------------------------
    // 批量模式：批内只标脏，结束时每个分片只写一次
    // ------------------------------------------------------------------

    private var bulkDepth = 0
    private val dirtyShards = linkedSetOf<String>()
    private var indexDirty = false

    /** 在一个批次里执行多次写操作：分片与索引都只在最后各写一次。 */
    fun <T> inBulk(block: () -> T): T {
        bulkDepth++
        try {
            return block()
        } finally {
            bulkDepth--
            if (bulkDepth == 0) flushBulk()
        }
    }

    private fun flushBulk() {
        val shards = dirtyShards.toList()
        dirtyShards.clear()
        shards.forEach { path ->
            AnchorStore.save(project, path, anchorsFor(path))
            recordChange("shard", path)
            notifyChanged(path)
        }
        if (indexDirty) {
            indexDirty = false
            AnchorStore.saveIndex(project, index())
            recordChange("index", null)
            notifyChanged("")
        }
    }

    /** 在内存里按 id 找锚点（批量模式下磁盘可能还没落盘）。 */
    fun findAnchor(anchorId: String): Pair<String, com.thousandyearsofwar.ridenotes.model.Anchor>? {
        cache.forEach { (path, list) ->
            list.firstOrNull { it.id == anchorId }?.let { return path to it }
        }
        AnchorStore.listAll(project).forEach { shard ->
            val anchor = shard.anchors.firstOrNull { it.id == anchorId } ?: return@forEach
            cache.putIfAbsent(shard.file, shard.anchors.sortedBy { it.startOffset }.toMutableList())
            return shard.file to anchor
        }
        return null
    }

    private fun persist(relativePath: String) {
        writtenPaths.add(relativePath)
        if (bulkDepth > 0) {
            dirtyShards.add(relativePath)
            return
        }
        AnchorStore.save(project, relativePath, anchorsFor(relativePath))
        recordChange("shard", relativePath)
        notifyChanged(relativePath)
    }

    // ------------------------------------------------------------------
    // 变更序列（供 /changes 增量比对）
    // ------------------------------------------------------------------

    private val changeSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val changeLog = mutableListOf<Triple<Long, String, String?>>()   // seq, kind, file
    private val changeLock = Any()

    private fun recordChange(kind: String, file: String?) {
        val seq = changeSeq.incrementAndGet()
        synchronized(changeLock) {
            changeLog.add(Triple(seq, kind, file))
            if (changeLog.size > 2000) changeLog.removeAt(0)
        }
    }

    /**
     * 增量比对：返回 since 之后发生的变化。
     *
     * - `reset = true` 表示 since 太旧（超出缓冲）或为 0，调用方应全量重拉
     * - `files` 是需要重新拉取的源文件（`GET /anchors?file=`）
     * - `indexChanged` 表示分组/标签/归属变了（需要重拉 `/groups` 与 `/tags`）
     */
    fun changesSince(since: Long): Map<String, Any?> {
        val current = changeSeq.get()
        if (since <= 0) {
            return mapOf("ok" to true, "seq" to current, "reset" to true, "files" to emptyList<String>(), "indexChanged" to true, "changes" to emptyList<Any>())
        }
        val snapshot: List<Triple<Long, String, String?>>
        synchronized(changeLock) { snapshot = changeLog.toList() }
        val oldest = snapshot.firstOrNull()?.first ?: (current + 1)
        if (since < oldest - 1) {
            return mapOf("ok" to true, "seq" to current, "reset" to true, "files" to emptyList<String>(), "indexChanged" to true, "changes" to emptyList<Any>())
        }
        val relevant = snapshot.filter { it.first > since }
        val files = relevant.filter { it.second == "shard" }.mapNotNull { it.third }.distinct()
        val indexChanged = relevant.any { it.second == "index" }
        return mapOf(
            "ok" to true,
            "seq" to current,
            "reset" to false,
            "files" to files,
            "indexChanged" to indexChanged,
            "changes" to relevant.map { mapOf("seq" to it.first, "kind" to it.second, "file" to it.third) }
        )
    }

    /** 给外部批量操作使用：只刷新一次、不重复触发。 */
    fun touch(relativePath: String) {
        recordChange("shard", relativePath)
    }

    private fun notifyChanged(relativePath: String) {
        listeners.forEach { runCatching { it(relativePath) } }
    }

    private fun nextColor(anchors: List<Anchor>): Int {
        val used = anchors.map { it.color }.toSet()
        return (0..7).firstOrNull { it !in used } ?: (anchors.size % 8)
    }

    // ------------------------------------------------------------------
    // 分组树（项目索引）
    // ------------------------------------------------------------------

    private var cachedIndex: com.thousandyearsofwar.ridenotes.model.ProjectIndex? = null

    private var migrationDone = false

    fun index(): com.thousandyearsofwar.ridenotes.model.ProjectIndex {
        val loaded = cachedIndex
            ?: (AnchorStore.loadIndex(project) ?: com.thousandyearsofwar.ridenotes.model.ProjectIndex())
                .also { cachedIndex = it }
        if (!migrationDone) {
            migrationDone = true
            runCatching { migratePlacements(loaded) }
                .onFailure { com.thousandyearsofwar.ridenotes.util.NoteLog.warn("migration failed: ${it.message}") }
            runCatching { migrateOrder(loaded) }
                .onFailure { com.thousandyearsofwar.ridenotes.util.NoteLog.warn("order migration failed: ${it.message}") }
        }
        return loaded
    }

    /**
     * schema 2 → 3：组和锚点改用同一个顺序键 `orderValue`，从此可以任意穿插。
     *
     * 现有数据里组用 `order`（0,1,2…）、锚点用 1,2,3…，直接把锚点整体 +1000，
     * 保证迁移后"组在前、锚点在后"的观感不变，之后拖拽再按中点插入。
     */
    private fun migrateOrder(index: com.thousandyearsofwar.ridenotes.model.ProjectIndex) {
        if (index.schema >= 3) return
        index.groups.forEach { it.orderValue = it.order.toDouble() }

        AnchorStore.listAll(project).forEach { shard ->
            var dirty = false
            shard.anchors.forEach { anchor ->
                anchor.orderValue += 1000.0
                dirty = true
            }
            if (dirty) {
                val list = shard.anchors.sortedBy { it.startOffset }.toMutableList()
                cache[shard.file] = list
                AnchorStore.save(project, shard.file, list)
            }
        }

        index.schema = 3
        saveIndex()
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("migrated order keys to schema 3")
    }

    /**
     * schema 1 → 2：把 `_project.json` 里的 placements 并入锚点自身。
     *
     * 之后"锚点归属"只有一处真相源，删锚点即删归属，不会再产生孤儿。
     * 幂等：已经是 schema 2 直接返回；placements 为空也直接升版。
     */
    private fun migratePlacements(index: com.thousandyearsofwar.ridenotes.model.ProjectIndex) {
        if (index.schema >= 2) return
        val placements = index.placements.associateBy { it.anchorId }

        if (placements.isEmpty()) {
            index.schema = 2
            saveIndex()
            return
        }

        var migratedAnchors = 0
        var migratedFiles = 0
        AnchorStore.listAll(project).forEach { shard ->
            var dirty = false
            shard.anchors.forEach { anchor ->
                val placement = placements[anchor.id] ?: return@forEach
                anchor.groupId = placement.groupId
                anchor.orderValue = placement.order.toDouble()
                anchor.visible = placement.visible
                anchor.locked = placement.locked
                anchor.compact = placement.compact
                anchor.colorOverride = placement.colorOverride
                dirty = true
                migratedAnchors++
            }
            if (dirty) {
                val list = shard.anchors.sortedBy { it.startOffset }.toMutableList()
                cache[shard.file] = list
                AnchorStore.save(project, shard.file, list)
                migratedFiles++
            }
        }

        index.placements.clear()
        index.schema = 2
        saveIndex()
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "migrated placements -> anchors: $migratedAnchors anchors in $migratedFiles files"
        )
    }

    fun saveIndex() {
        if (bulkDepth > 0) {
            indexDirty = true
            return
        }
        AnchorStore.saveIndex(project, index())
        recordChange("index", null)
        notifyChanged("")
    }

    fun createGroup(parentId: String?, title: String): com.thousandyearsofwar.ridenotes.model.GroupNode {
        val index = index()
        val siblings = index.childrenOf(parentId)
        val used = siblings.map { it.color }.toSet()
        val color = (0..7).firstOrNull { it !in used } ?: (siblings.size % 8)
        val group = com.thousandyearsofwar.ridenotes.model.GroupNode(
            title = title,
            parentId = parentId,
            order = index.nextOrder(parentId),
            color = color
        )
        index.groups.add(group)
        saveIndex()
        return group
    }

    fun renameGroup(id: String, title: String) {
        index().group(id)?.title = title
        saveIndex()
    }

    fun setGroupColor(id: String, color: Int) {
        index().group(id)?.color = color
        saveIndex()
    }

    fun setAnchorColor(relativePath: String, anchorId: String, color: Int) {
        mutableAnchors(relativePath).firstOrNull { it.id == anchorId }?.let {
            it.color = color
            persist(relativePath)
        }
    }

    fun setGroupVisible(id: String, visible: Boolean) {
        index().group(id)?.visible = visible
        saveIndex()
    }

    fun deleteGroup(id: String) {
        val index = index()
        val group = index.group(id) ?: return
        // 子组上移一层
        index.groups.filter { it.parentId == id }.forEach { it.parentId = group.parentId }
        index.groups.remove(group)
        // 默认分组被删：回退到它的父级，避免新锚点落进不存在的组
        if (index.defaultGroupId == id) index.defaultGroupId = group.parentId
        saveIndex()

        // 子锚点移到父级（归属现在存在锚点里，需要写回各自分片）
        val touched = mutableSetOf<String>()
        cache.forEach { (path, list) ->
            val moved = list.filter { it.groupId == id }
            if (moved.isNotEmpty()) {
                moved.forEach { it.groupId = group.parentId }
                touched.add(path)
            }
        }
        AnchorStore.listAll(project).forEach { shard ->
            if (cache.containsKey(shard.file)) return@forEach
            if (shard.anchors.none { it.groupId == id }) return@forEach
            shard.anchors.filter { it.groupId == id }.forEach { it.groupId = group.parentId }
            cache[shard.file] = shard.anchors.sortedBy { it.startOffset }.toMutableList()
            touched.add(shard.file)
        }
        touched.forEach { persist(it) }
    }

    /** 找到锚点并修改它的字段，然后写回它所在分片。 */
    private fun updateAnchor(anchorId: String, mutate: (Anchor) -> Unit): Boolean {
        val (path, anchor) = findAnchor(anchorId) ?: return false
        mutate(anchor)
        persist(path)
        return true
    }

    /** 全部分片里的锚点（缓存优先，未加载的从磁盘补）。 */
    private fun allAnchors(): List<Anchor> {        val result = mutableListOf<Anchor>()
        AnchorStore.listAll(project).forEach { shard ->
            val list = cache[shard.file]
                ?: shard.anchors.sortedBy { it.startOffset }.toMutableList().also { cache[shard.file] = it }
            result.addAll(list)
        }
        return result
    }

    fun setAnchorVisible(anchorId: String, visible: Boolean) {
        updateAnchor(anchorId) { it.visible = visible }
    }

    /** 供面板/接口做全库视图（归属已经存在锚点里）。 */
    fun anchorsSnapshot(): List<Anchor> = allAnchors()

    /** 锚点 + 它所在的分片路径，用于导出、恢复等需要文件路径的场景。 */
    fun anchorsWithFiles(): List<Pair<String, Anchor>> =
        AnchorStore.listAll(project).flatMap { shard ->
            val list = cache[shard.file]
                ?: shard.anchors.sortedBy { it.startOffset }.toMutableList().also { cache[shard.file] = it }
            list.map { shard.file to it }
        }

    /**
     * 在面板里给锚点创建一个**克隆节点**：只是列表里的别名，
     * 不占用新的代码区间，也不在编辑器里渲染任何内容。
     */
    fun createCloneNode(sourceId: String): Anchor? {
        val (path, found) = findAnchor(sourceId) ?: return null
        val source = mirrorSource(found) ?: found
        val basePath = project.basePath ?: return null
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(if (path.startsWith("/") || path.contains(":")) path else "$basePath/$path")
            ?: return null

        val clone = Anchor(
            startOffset = source.startOffset,
            endOffset = source.endOffset,
            startLine = source.startLine,
            endLine = source.endLine,
            anchorText = source.anchorText,
            anchorSha1 = source.anchorSha1,
            contextBefore = source.contextBefore,
            contextAfter = source.contextAfter
        )
        clone.mirrorOf = source.id
        clone.groupId = source.groupId
        // 紧跟在源锚点后面
        clone.orderValue = source.orderValue + 0.5
        clone.createdBy = resolveAuthor(project)
        addAnchor(file, clone)
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("create clone node ${clone.id} of ${source.id}")
        return clone
    }

    /** 批量把锚点移进同一个分组（多选"移动到分组"用），每个分片只写一次。 */
    fun moveAnchorsToGroup(items: List<Pair<String, String>>, groupId: String?): Int {
        if (items.isEmpty()) return 0
        var nextOrder = (allAnchors().filter { it.groupId == groupId }.maxOfOrNull { it.orderValue } ?: 0.0) + 1.0
        var moved = 0
        inBulk {
            items.groupBy({ it.first }, { it.second }).forEach { (path, ids) ->
                val list = mutableAnchors(path)
                var changed = false
                ids.forEach { id ->
                    val anchor = list.firstOrNull { it.id == id } ?: return@forEach
                    anchor.groupId = groupId
                    anchor.orderValue = nextOrder++
                    anchor.updatedAt = java.time.Instant.now().toString()
                    changed = true
                    moved++
                }
                if (changed) persist(path)
            }
        }
        return moved
    }

    /** 克隆锚点的源锚点；不是克隆、或源已被删除时返回 null。 */
    fun mirrorSource(anchor: Anchor): Anchor? =
        anchor.mirrorOf?.let { sourceId -> findAnchor(sourceId)?.second }

    /** 渲染/面板展示用的笔记：克隆锚点用源的笔记。 */
    fun resolvedNotes(anchor: Anchor): List<com.thousandyearsofwar.ridenotes.model.Note> =
        mirrorSource(anchor)?.notes ?: anchor.notes

    /**
     * 展示用标题：克隆锚点用**源锚点的显示名**（标题为空时用源的正文首行/行号），
     * 保证克隆行和源行看起来一致。
     */
    fun resolvedTitle(anchor: Anchor): String {
        val source = mirrorSource(anchor) ?: return anchor.title
        return source.title.ifBlank { source.displayTitle }
    }

    /**
     * 在指定区间创建一个**克隆锚点**：位置是自己的，笔记内容跟随源锚点。
     * 源锚点改了标题/笔记，克隆处渲染出来的内容随之变化。
     */
    fun createMirror(
        sourceId: String,
        file: VirtualFile,
        document: com.intellij.openapi.editor.Document,
        range: com.intellij.openapi.util.TextRange
    ): Anchor? {
        val source = findAnchor(sourceId)?.second ?: return null
        val mirror = com.thousandyearsofwar.ridenotes.model.Anchoring.createAnchor(document, range)
        mirror.mirrorOf = source.id
        mirror.groupId = source.groupId
        mirror.orderValue = source.orderValue
        mirror.createdBy = source.createdBy.ifBlank { currentAuthor() }
        addAnchor(file, mirror)
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "create mirror id=${mirror.id} of=${source.id} lines=${mirror.startLine}-${mirror.endLine}"
        )
        return mirror
    }

    /** 批量修改锚点：按分片归组，每片只写一次。返回处理条数。 */
    fun updateAnchors(items: List<Pair<String, String>>, mutate: (Anchor) -> Unit): Int {
        var count = 0
        inBulk {
            items.groupBy({ it.first }, { it.second }).forEach { (path, ids) ->
                val list = mutableAnchors(path)
                var changed = false
                ids.forEach { id ->
                    val anchor = list.firstOrNull { it.id == id } ?: return@forEach
                    mutate(anchor)
                    anchor.updatedAt = java.time.Instant.now().toString()
                    changed = true
                    count++
                }
                if (changed) persist(path)
            }
        }
        return count
    }

    /** 批量修改分组属性。返回处理条数。 */
    fun updateGroups(ids: Collection<String>, mutate: (com.thousandyearsofwar.ridenotes.model.GroupNode) -> Unit): Int {
        var count = 0
        ids.distinct().forEach { id ->
            index().group(id)?.let {
                mutate(it)
                count++
            }
        }
        if (count > 0) saveIndex()
        return count
    }

    /** 批量清除标题（多选操作）：按分片归组，每个分片只写一次。返回实际清除的条数。 */
    fun clearAnchorTitles(items: List<Pair<String, String>>): Int {
        var cleared = 0
        inBulk {
            items.groupBy({ it.first }, { it.second }).forEach { (path, ids) ->
                val list = mutableAnchors(path)
                var changed = false
                ids.forEach { id ->
                    val anchor = list.firstOrNull { it.id == id } ?: return@forEach
                    if (anchor.title.isNotEmpty()) {
                        anchor.title = ""
                        anchor.updatedAt = java.time.Instant.now().toString()
                        changed = true
                        cleared++
                    }
                }
                if (changed) persist(path)
            }
        }
        return cleared
    }

    fun updateAnchorTitle(relativePath: String, anchorId: String, title: String) {
        mutableAnchors(relativePath).firstOrNull { it.id == anchorId }?.let {
            it.title = title
            persist(relativePath)
        }
    }

    // ------------------------------------------------------------------
    // 混排顺序（schema 3）：组和锚点共用 orderValue，可以任意穿插
    // ------------------------------------------------------------------

    /**
     * 把分组放到 parentId 下、第 index 位（index 是在"组+锚点"混排列表里的位置）。
     */
    fun placeGroup(id: String, parentId: String?, index: Int) {
        val index0 = index()
        val group = index0.group(id) ?: return
        if (parentId == id || isAncestor(id, parentId)) return

        val keys = mixedKeys(parentId, excludeGroupId = id)
        val orderValue = orderAt(keys, index)
        group.parentId = parentId
        group.orderValue = orderValue
        group.order = index
        saveIndex()
    }

    /** 把锚点放到 groupId 下、混排列表里的第 index 位。 */
    fun placeAnchor(anchorId: String, groupId: String?, index: Int) {
        val (path, anchor) = findAnchor(anchorId) ?: return
        val keys = mixedKeys(groupId, excludeAnchorId = anchorId)
        val orderValue = orderAt(keys, index)
        anchor.groupId = groupId
        anchor.orderValue = orderValue
        persist(path)
    }

    /** 直接指定顺序键（拖拽到两行之间时用中点，不需要重排其他项）。 */
    fun moveGroupTo(id: String, parentId: String?, orderValue: Double) {
        val group = index().group(id) ?: return
        if (parentId == id || isAncestor(id, parentId)) return
        group.parentId = parentId
        group.orderValue = orderValue
        saveIndex()
    }

    fun moveAnchorTo(anchorId: String, groupId: String?, orderValue: Double) {
        updateAnchor(anchorId) {
            it.groupId = groupId
            it.orderValue = orderValue
        }
    }

    /** 某个父级下所有子项的顺序键（组 + 锚点），已排序。 */
    private fun mixedKeys(
        parentId: String?,
        excludeGroupId: String? = null,
        excludeAnchorId: String? = null
    ): List<Double> {
        val keys = mutableListOf<Double>()
        index().groups.filter { it.parentId == parentId && it.id != excludeGroupId }
            .forEach { keys.add(it.orderValue) }
        allAnchors().filter { it.groupId == parentId && it.id != excludeAnchorId }
            .forEach { keys.add(it.orderValue) }
        return keys.sorted()
    }

    /** 在已排序的键列表里插入到第 index 位时应该用的键值（取相邻中点）。 */
    private fun orderAt(sortedKeys: List<Double>, index: Int): Double {
        val pos = index.coerceIn(0, sortedKeys.size)
        return when {
            sortedKeys.isEmpty() -> 1.0
            pos == 0 -> sortedKeys.first() - 1.0
            pos >= sortedKeys.size -> sortedKeys.last() + 1.0
            else -> (sortedKeys[pos - 1] + sortedKeys[pos]) / 2.0
        }
    }

    /** id 是否是 candidate 的祖先（防止把分组拖进自己的子树）。 */
    private fun isAncestor(id: String, candidate: String?): Boolean {
        var cursor = candidate?.let { index().group(it) }
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (cursor.id == id) return true
            cursor = cursor.parentId?.let { index().group(it) }
        }
        return false
    }

    /** 编辑器里用的实际颜色：单条覆盖 > 组色 > 自身色。 */
    fun effectiveColor(anchor: Anchor): Int {
        val index = index()
        return anchor.colorOverride
            ?: index.effectiveColor(anchor.groupId)
            ?: anchor.color
    }

    fun setAnchorColorOverride(anchorId: String, color: Int?) {
        updateAnchor(anchorId) { it.colorOverride = color }
    }

    // ------------------------------------------------------------------
    // 标签库
    // ------------------------------------------------------------------

    fun tags(): List<com.thousandyearsofwar.ridenotes.model.TagDef> = index().tags

    fun tagGroups(): List<com.thousandyearsofwar.ridenotes.model.TagGroupNode> =
        index().tagGroups.sortedBy { it.order }

    fun addTagGroup(name: String): com.thousandyearsofwar.ridenotes.model.TagGroupNode? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val index = index()
        index.tagGroups.firstOrNull { it.name == clean }?.let { return it }
        val group = com.thousandyearsofwar.ridenotes.model.TagGroupNode(
            name = clean,
            order = (index.tagGroups.maxOfOrNull { it.order } ?: -1) + 1
        )
        index.tagGroups.add(group)
        saveIndex()
        return group
    }

    fun renameTagGroup(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        index().tagGroups.firstOrNull { it.id == id }?.name = clean
        saveIndex()
    }

    /** 删分组只删分类，标签本身保留（变成未分组）。 */
    fun removeTagGroup(id: String) {
        val index = index()
        index.tagGroups.removeIf { it.id == id }
        index.tags.filter { it.groupId == id }.forEach { it.groupId = null }
        saveIndex()
    }

    fun setTagGroup(tagId: String, groupId: String?) {
        index().tags.firstOrNull { it.id == tagId }?.groupId = groupId
        saveIndex()
    }

    /** 把分组移到 beforeId 之前（null = 末尾）。 */
    fun moveTagGroupBefore(id: String, beforeId: String?) {
        val index = index()
        val list = index.tagGroups.sortedBy { it.order }.toMutableList()
        val group = list.firstOrNull { it.id == id } ?: return
        list.remove(group)
        val pos = if (beforeId == null) list.size
        else list.indexOfFirst { it.id == beforeId }.let { if (it < 0) list.size else it }
        list.add(pos.coerceIn(0, list.size), group)
        list.forEachIndexed { i, g -> g.order = i }
        saveIndex()
    }

    fun addTag(name: String, groupId: String? = null): com.thousandyearsofwar.ridenotes.model.TagDef? {
        val clean = name.trim().removePrefix("#")
        if (clean.isEmpty()) return null
        val index = index()
        index.tags.firstOrNull { it.name == clean }?.let { return it }
        val used = index.tags.map { it.color }.toSet()
        val color = (0..7).firstOrNull { it !in used } ?: (index.tags.size % 8)
        val tag = com.thousandyearsofwar.ridenotes.model.TagDef(name = clean, color = color, groupId = groupId)
        index.tags.add(tag)
        saveIndex()
        return tag
    }

    fun removeTag(id: String) {
        val tag = index().tags.firstOrNull { it.id == id } ?: return
        index().tags.remove(tag)
        // 同时把这个名字从所有分组和笔记上摘掉，避免残留
        index().groups.forEach { group -> group.tags.remove(tag.name) }
        AnchorStore.listAll(project).forEach { file ->
            val list = mutableAnchors(file.file)
            var changed = false
            list.forEach { anchor ->
                anchor.notes.forEach { note ->
                    if (note.tags.contains(tag.name)) {
                        note.tags = note.tags - tag.name
                        changed = true
                    }
                }
            }
            if (changed) persist(file.file)
        }
        saveIndex()
    }

    fun renameTag(id: String, name: String) {
        val clean = name.trim().removePrefix("#")
        if (clean.isEmpty()) return
        index().tags.firstOrNull { it.id == id }?.name = clean
        saveIndex()
    }

    fun setTagColor(id: String, color: Int) {
        index().tags.firstOrNull { it.id == id }?.color = color
        saveIndex()
    }

    /** 给某条笔记打标签（结构化字段，不往正文里写）。 */
    fun addTagToNote(file: VirtualFile, anchorId: String, noteId: String, tagName: String) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        val note = anchor.notes.firstOrNull { it.id == noteId } ?: return
        val clean = tagName.trim().removePrefix("#")
        if (clean.isEmpty() || note.tags.contains(clean)) return
        note.tags = (note.tags + clean).distinct()
        note.updatedAt = java.time.Instant.now().toString()
        persistAndNotify(file)
    }

    /** 给分组打标签。 */
    fun addTagToGroup(groupId: String, tagName: String) {
        val clean = tagName.trim().removePrefix("#")
        if (clean.isEmpty()) return
        index().group(groupId)?.let { group ->
            if (!group.tags.contains(clean)) group.tags = (group.tags + clean).distinct().toMutableList()
        }
        saveIndex()
    }

    fun removeTagFromGroup(groupId: String, tagName: String) {
        index().group(groupId)?.tags?.remove(tagName)
        saveIndex()
    }

    fun removeTagFromNote(file: VirtualFile, anchorId: String, noteId: String, tagName: String) {
        val anchor = anchorsFor(file).firstOrNull { it.id == anchorId } ?: return
        val note = anchor.notes.firstOrNull { it.id == noteId } ?: return
        note.tags = note.tags - tagName
        persistAndNotify(file)
    }

    fun snapshotShard(relativePath: String): String? = AnchorStore.readRaw(project, relativePath)

    fun snapshotIndex(): String? = AnchorStore.readRawIndex(project)

    /** 库目录变了：丢掉内存缓存，下次读盘走新路径。 */
    fun reloadLibrary() {
        cache.clear()
        cachedIndex = null
        com.thousandyearsofwar.ridenotes.util.NoteLog.info("library reloaded")
    }

    fun restore(relativePath: String, shardJson: String?, indexJson: String?) {
        AnchorStore.writeRaw(project, relativePath, shardJson)
        AnchorStore.writeRawIndex(project, indexJson)
        cache.remove(relativePath)
        cachedIndex = null
        notifyChanged(relativePath)
    }

    /** 编辑器里用的实际可见性：自己的开关 + 所有祖先组的开关。 */
    fun effectiveVisible(anchor: Anchor): Boolean =
        anchor.visible && index().effectivelyVisible(anchor.groupId)

    /** 用编辑器里的新选区重新锚定（用于"过期笔记 → 重新绑定"）。 */
    fun rebindAnchor(
        relativePath: String,
        anchorId: String,
        document: com.intellij.openapi.editor.Document,
        range: com.intellij.openapi.util.TextRange
    ): Boolean {
        val anchor = mutableAnchors(relativePath).firstOrNull { it.id == anchorId } ?: return false
        val fresh = Anchoring.createAnchor(document, range)
        anchor.startOffset = fresh.startOffset
        anchor.endOffset = fresh.endOffset
        anchor.startLine = fresh.startLine
        anchor.endLine = fresh.endLine
        anchor.anchorText = fresh.anchorText
        anchor.anchorSha1 = fresh.anchorSha1
        anchor.contextBefore = fresh.contextBefore
        anchor.contextAfter = fresh.contextAfter
        anchor.updatedAt = java.time.Instant.now().toString()
        persist(relativePath)
        return true
    }

    /** 锁定：祖先组上锁或自己上锁。锁定的笔记在编辑器里不能编辑/删除。 */
    fun effectiveLocked(anchor: Anchor): Boolean =
        anchor.locked || index().effectivelyLocked(anchor.groupId)

    /** 简洁：编辑器里只显示标题，内容折叠。 */
    fun effectiveCompact(anchor: Anchor): Boolean =
        anchor.compact || index().effectivelyCompact(anchor.groupId)

    fun setAnchorLocked(anchorId: String, locked: Boolean) {
        updateAnchor(anchorId) { it.locked = locked }
    }

    fun setAnchorCompact(anchorId: String, compact: Boolean) {
        updateAnchor(anchorId) { it.compact = compact }
    }

    fun setGroupLocked(id: String, locked: Boolean) {
        index().group(id)?.locked = locked
        saveIndex()
    }

    fun setGroupCompact(id: String, compact: Boolean) {
        index().group(id)?.compact = compact
        saveIndex()
    }

    private fun parseTags(body: String): List<String> {
        val regex = Regex("#([\\p{L}\\p{N}_-]{1,24})")
        return regex.findAll(body).map { it.groupValues[1] }.distinct().toList()
    }

    companion object {
        fun currentAuthor(): String = System.getProperty("user.name") ?: "unknown"

        /** 按项目解析作者：优先 `git config user.name`，失败回落系统用户名。结果按项目缓存。 */
        private val authorCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun resolveAuthor(project: Project): String {
            val base = project.basePath ?: return currentAuthor()
            authorCache[base]?.let { return it }
            val fromGit = runCatching {
                val process = ProcessBuilder("git", "config", "user.name")
                    .directory(java.io.File(base))
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    ""
                } else {
                    process.inputStream.bufferedReader().readText().trim()
                }
            }.getOrDefault("")
            val resolved = fromGit.ifBlank { currentAuthor() }
            authorCache[base] = resolved
            return resolved
        }

        fun get(project: Project): NoteAnchorService = project.service()
    }
}
