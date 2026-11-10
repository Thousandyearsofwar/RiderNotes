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
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.util.NoteLog

/**
 * 把 IDE 自带的 F11 书签转换为 Sidecar Notes 的锚点。
 *
 * 直接走 `BookmarksManager` API，不依赖 workspace.xml 的落盘格式，
 * 所以 Rider 还开着、书签尚未写盘时也能导入。
 */
object BookmarkImporter {

    /** 只读探针：打印前几个书签的 attributes，用来确认描述字段的键名。 */
    private val DEBUG_ATTRS = java.util.concurrent.atomic.AtomicInteger(0)

    data class Result(val created: Int, val skipped: Int)

    /** 分组标题：相对项目根的最后两段路径，避免同名文件混淆。 */
    private fun displayName(file: VirtualFile): String {
        val segments = file.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) segments.joinToString("/") else segments.takeLast(2).joinToString("/")
    }

    fun import(project: Project, deleteAfter: Boolean): Result {
        val manager = BookmarksManager.getInstance(project) ?: return Result(0, 0)
        val service = NoteAnchorService.get(project)

        val byFile = linkedMapOf<VirtualFile, MutableList<Anchor>>()
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

            // 书签描述：不同实现放在 attributes 里，键名不完全统一，按优先级兜底
            val attributes = runCatching { bookmark.attributes }.getOrDefault(emptyMap())
            val description = runCatching {
                attributes["description"]
                    ?: attributes["DESCRIPTION"]
                    ?: attributes["comment"]
                    ?: bookmark.prepareDefaultDescription()?.takeIf { it.isNotBlank() && it != lineText }
            }.getOrNull()?.trim().orEmpty()

            if (DEBUG_ATTRS.getAndIncrement() < 3) {
                NoteLog.info(
                    "bookmark attrs: file=${file.name} line=${line + 1} type=$typeName " +
                        "attrs=${attributes.keys} desc='${description.take(120)}'"
                )
            }

            anchor.title = when {
                description.isNotEmpty() -> description.lineSequence().first().take(60)
                !typeName.isNullOrBlank() && lineText.isNotEmpty() -> "$typeName · $lineText"
                !typeName.isNullOrBlank() -> "书签 $typeName"
                lineText.isNotEmpty() -> lineText
                else -> "书签"
            }
            anchor.createdBy = "bookmark-import"
            if (description.isNotEmpty()) {
                anchor.notes.add(
                    com.thousandyearsofwar.ridenotes.model.Note(body = description, author = "bookmark-import")
                )
            }

            byFile.getOrPut(file) { mutableListOf() }.add(anchor)
            used.add(bookmark)
        }

        // 分组：顶层「书签导入 <时间>」；每个源文件一个子组（单条书签直接挂顶层）
        val topTitle = "书签导入 " + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())
        val top = service.createGroup(null, topTitle)
        byFile.forEach { (file, anchors) ->
            if (anchors.size >= 2) {
                val group = service.createGroup(top.id, displayName(file))
                anchors.forEach { it.groupId = group.id }
            } else {
                anchors.forEach { it.groupId = top.id }
            }
        }

        // 批量写：每个文件只写一次盘、只重绘一次
        service.inBulk {
            byFile.forEach { (file, anchors) -> service.addAnchors(file, anchors) }
        }
        byFile.keys.forEach { AnchorRenderService.get(project).refresh(it) }

        if (deleteAfter) {
            used.forEach { bookmark -> runCatching { manager.remove(bookmark) } }
        }

        val created = byFile.values.sumOf { it.size }
        NoteLog.info("bookmark import: created=$created skipped=$skipped deleteAfter=$deleteAfter")
        return Result(created, skipped)
    }
}
