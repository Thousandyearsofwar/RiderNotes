package com.thousandyearsofwar.ridenotes.ui

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.FileBookmark
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.thousandyearsofwar.ridenotes.editor.AnchorRenderService
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.model.Note
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.util.NoteLog

/**
 * 把 IDE 自带的 F11 书签转换为 Sidecar Notes 的锚点。
 *
 * 关键点：
 * - 书签描述不在书签对象上，而在 `BookmarkGroup.getDescription(bookmark)` 里
 * - Rider 的「书签列表」映射成本工具的**同名分组**
 * - 同一列表内某文件书签 ≥ 2 时，再建一个以文件命名的子分组
 */
object BookmarkImporter {

    data class Result(val created: Int, val skipped: Int)

    private data class Entry(val file: VirtualFile, val anchor: Anchor, val list: String?)

    fun import(project: Project, deleteAfter: Boolean): Result {
        val manager = BookmarksManager.getInstance(project) ?: return Result(0, 0)
        val service = NoteAnchorService.get(project)

        val entries = mutableListOf<Entry>()
        val used = mutableListOf<com.intellij.ide.bookmark.Bookmark>()
        var skipped = 0

        manager.bookmarks.forEach { bookmark ->
            val file = (bookmark as? FileBookmark)?.file
            val lineBookmark = bookmark as? LineBookmark
            if (file == null || lineBookmark == null || !file.isValid || file.isDirectory) {
                skipped++
                return@forEach
            }
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null || document.lineCount <= 0) {
                skipped++
                return@forEach
            }

            val line = lineBookmark.line.coerceIn(0, document.lineCount - 1)
            val range = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
            val anchor = Anchoring.createAnchor(document, range)

            val lineText = document.getText(range).trim().take(60)
            val typeName = runCatching { manager.getType(bookmark)?.name }.getOrNull()
                ?.removePrefix("DIGIT_")?.removePrefix("LETTER_")

            // 描述存在分组里：BookmarkGroup.getDescription(bookmark)，正是书签树显示的那段文字
            val groups = runCatching { manager.getGroups(bookmark) }.getOrDefault(emptyList())
            val attributes = runCatching { bookmark.attributes }.getOrDefault(emptyMap())
            val description = runCatching {
                groups.firstNotNullOfOrNull { group -> group.getDescription(bookmark)?.takeIf { it.isNotBlank() } }
            }.getOrNull()
                ?: attributes["description"]
                ?: bookmark.prepareDefaultDescription()?.takeIf { it.isNotBlank() && it != lineText }
            val cleanDescription = description?.trim().orEmpty()

            anchor.title = when {
                cleanDescription.isNotEmpty() -> cleanDescription.lineSequence().first().take(60)
                !typeName.isNullOrBlank() && lineText.isNotEmpty() -> "$typeName · $lineText"
                !typeName.isNullOrBlank() -> "书签 $typeName"
                lineText.isNotEmpty() -> lineText
                else -> "书签"
            }
            anchor.createdBy = "bookmark-import"
            if (cleanDescription.isNotEmpty()) {
                anchor.notes.add(Note(body = cleanDescription, author = "bookmark-import"))
            }

            val listName = groups.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            entries.add(Entry(file, anchor, listName))
            used.add(bookmark)
        }

        // 分组：书签列表 → 同名分组；列表内单文件书签 ≥ 2 时再加一层文件分组
        val fallbackTitle = "书签导入 " + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())

        fun listKey(entry: Entry): String = entry.list ?: fallbackTitle

        val listGroups = linkedMapOf<String, String>()
        val fileGroups = mutableMapOf<Pair<String, VirtualFile>, String>()
        val counts = entries.groupingBy { listKey(it) to it.file }.eachCount()

        entries.forEach { entry ->
            val key = listKey(entry)
            val listGroupId = listGroups.getOrPut(key) { service.createGroup(null, key).id }
            val count = counts.getValue(key to entry.file)
            entry.anchor.groupId = if (count >= 2) {
                fileGroups.getOrPut(key to entry.file) {
                    service.createGroup(listGroupId, displayName(entry.file)).id
                }
            } else {
                listGroupId
            }
        }

        // 批量写：每个文件只写一次盘、只重绘一次
        service.inBulk {
            entries.groupBy { it.file }.forEach { (file, list) ->
                service.addAnchors(file, list.map { it.anchor })
            }
        }
        entries.map { it.file }.distinct().forEach { AnchorRenderService.get(project).refresh(it) }

        if (deleteAfter) {
            used.forEach { bookmark -> runCatching { manager.remove(bookmark) } }
        }

        NoteLog.info(
            "bookmark import: created=${entries.size} skipped=$skipped " +
                "lists=${listGroups.keys} deleteAfter=$deleteAfter"
        )
        return Result(entries.size, skipped)
    }

    /** 分组标题：相对路径最后两段，避免同名文件混淆。 */
    private fun displayName(file: VirtualFile): String {
        val segments = file.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) segments.joinToString("/") else segments.takeLast(2).joinToString("/")
    }

    /**
     * 反向操作：把锚点恢复成 IDE 书签。
     *
     * 用于「导入并删除原书签」之后反悔，或把本工具的笔记同步回 IDE 书签。
     * 只处理 `createdBy = bookmark-import` 的锚点（即从书签导入的那些）。
     */
    fun restore(project: Project): Result {
        val manager = BookmarksManager.getInstance(project) ?: return Result(0, 0)
        val service = NoteAnchorService.get(project)
        var created = 0
        var skipped = 0

        service.anchorsWithFiles().forEach { (relativePath, anchor) ->
            if (anchor.createdBy != "bookmark-import") return@forEach

            // 锚点可能指向项目外的文件（如引擎源码），相对路径里会带 ../../，必须按绝对路径解析
            val basePath = project.basePath
            val absolute = basePath?.let { runCatching { java.io.File(it, relativePath).canonicalPath }.getOrNull() }
            val file = absolute?.let {
                runCatching {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(it)
                }.getOrNull()
            }
            if (file == null || !file.isValid || file.isDirectory) {
                if (skipped < 3) NoteLog.warn("restore skip: no file for $relativePath (abs=$absolute)")
                skipped++
                return@forEach
            }
            val document = runCatching { FileDocumentManager.getInstance().getDocument(file) }.getOrNull()
            if (document == null || document.lineCount <= 0) {
                if (skipped < 3) NoteLog.warn("restore skip: no document for ${file.path}")
                skipped++
                return@forEach
            }

            val line = (anchor.startLine - 1).coerceIn(0, document.lineCount - 1)
            val description = anchor.notes.firstOrNull()?.body?.takeIf { it.isNotBlank() } ?: anchor.title

            val bookmarkResult = runCatching { createLineBookmark(project, manager, file, line, description) }
            val bookmark = bookmarkResult.getOrNull()
            if (bookmark == null) {
                if (skipped < 3) {
                    val cause = bookmarkResult.exceptionOrNull()
                    NoteLog.warn(
                        "restore skip: createBookmark null for ${file.name}:$line " +
                            "err=${cause?.javaClass?.name}: ${cause?.message}"
                    )
                }
                skipped++
                return@forEach
            }

            val groupName = anchor.groupId?.let { id -> service.index().group(id)?.title }?.takeIf { it.isNotBlank() }
                ?: "书签恢复"
            val groupResult = runCatching {
                manager.getGroup(groupName) ?: manager.addGroup(groupName, false)
            }
            val group = groupResult.getOrNull()
            val addResult = runCatching {
                group?.add(bookmark, com.intellij.ide.bookmark.BookmarkType.DEFAULT, description) ?: false
            }
            val added = addResult.getOrDefault(false)
            if (added) {
                created++
            } else {
                if (skipped < 3) {
                    NoteLog.warn(
                        "restore skip: add failed group=$groupName " +
                            "groupErr=${groupResult.exceptionOrNull()?.message} addErr=${addResult.exceptionOrNull()?.message}"
                    )
                }
                skipped++
            }
        }

        NoteLog.info("bookmark restore: created=$created skipped=$skipped")
        return Result(created, skipped)
    }

    /** 行书签的 provider 全限定名（BookmarkState.provider 需要它）。 */
    private const val LINE_PROVIDER = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"

    /**
     * `BookmarkState` 只存在于 IDE 内置插件里、不在编译期 classpath，所以用反射构造，
     * 再交给 `manager.createBookmark(state)` 反序列化成书签对象。
     */
    private fun createLineBookmark(
        project: Project,
        manager: BookmarksManager,
        file: VirtualFile,
        line: Int,
        description: String
    ): com.intellij.ide.bookmark.Bookmark? {
        // 策略 A：反射构造 BookmarkState（不同版本 setter 可能不同，setter → 字段 依次尝试）
        runCatching {
            val stateClass = Class.forName("com.intellij.ide.bookmark.BookmarkState")
            val state = stateClass.getDeclaredConstructor().newInstance()
            setProperty(stateClass, state, "provider", "setProvider", String::class.java, LINE_PROVIDER)
            setProperty(
                stateClass, state, "type", "setType",
                com.intellij.ide.bookmark.BookmarkType::class.java, com.intellij.ide.bookmark.BookmarkType.DEFAULT
            )
            setProperty(stateClass, state, "description", "setDescription", String::class.java, description)
            setProperty(
                stateClass, state, "attributes", "setAttributes",
                java.util.Map::class.java, mapOf("url" to file.url, "line" to line.toString())
            )
            manager.createBookmark(state)
        }.getOrNull()?.let { return it }

        // 策略 B：把 OpenFileDescriptor（Navigatable）交给 manager，由 provider 自行匹配
        val offset = runCatching {
            val document = FileDocumentManager.getInstance().getDocument(file)
            document?.getLineStartOffset(line.coerceIn(0, document.lineCount - 1))
        }.getOrNull()
        if (offset != null) {
            runCatching {
                manager.createBookmark(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, offset))
            }.getOrNull()?.let { return it }
        }

        return null
    }

    /** 优先调用 setter；没有该 setter 就写同名字段（Kotlin `var` 的私有字段）。 */
    private fun setProperty(
        owner: Class<*>,
        target: Any,
        fieldName: String,
        setterName: String,
        setterType: Class<*>,
        value: Any?
    ) {
        val setter = runCatching { owner.getMethod(setterName, setterType) }.getOrNull()
        if (setter != null && runCatching { setter.invoke(target, value) }.isSuccess) return

        val field = runCatching {
            generateSequence(owner as Class<*>?) { it.superclass }
                .mapNotNull { runCatching { it.getDeclaredField(fieldName) }.getOrNull() }
                .firstOrNull()
        }.getOrNull() ?: throw NoSuchMethodException("$fieldName on ${owner.name}")
        field.isAccessible = true
        field.set(target, value)
    }
}
