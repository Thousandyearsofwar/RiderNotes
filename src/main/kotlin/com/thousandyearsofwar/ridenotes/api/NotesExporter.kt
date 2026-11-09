package com.thousandyearsofwar.ridenotes.api

import com.intellij.openapi.project.Project
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.AnchorStore
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把笔记导出成 Markdown / PR 评论格式。
 *
 * 纯数据生成，不依赖编辑器，供 HTTP `/export` 和面板「导出」共用。
 */
object NotesExporter {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    fun markdown(project: Project, fileFilter: String? = null): String {
        val shards = AnchorStore.listAll(project)
            .filter { fileFilter == null || it.file == fileFilter }
            .filter { it.anchors.isNotEmpty() }

        val totalAnchors = shards.sumOf { it.anchors.size }
        val totalNotes = shards.sumOf { shard -> shard.anchors.sumOf { it.notes.size } }

        return buildString {
            appendLine("# Sidecar Notes — ${project.name}")
            appendLine()
            appendLine("_${totalAnchors} anchors · ${totalNotes} notes · generated ${STAMP.format(java.time.Instant.now())}_")
            appendLine()

            shards.sortedBy { it.file }.forEach { shard ->
                appendLine("## `${shard.file}`")
                appendLine()
                shard.anchors.sortedBy { it.startLine }.forEach { anchor ->
                    appendAnchor(this, project, shard.file, anchor)
                }
            }
        }
    }

    /** PR 评论格式：每条锚点一段，带代码摘录，适合直接贴进 review。 */
    fun pullRequest(project: Project, fileFilter: String? = null): String {
        val shards = AnchorStore.listAll(project)
            .filter { fileFilter == null || it.file == fileFilter }
            .filter { it.anchors.isNotEmpty() }

        return buildString {
            shards.sortedBy { it.file }.forEach { shard ->
                shard.anchors.sortedBy { it.startLine }.forEach { anchor ->
                    val range = if (anchor.startLine == anchor.endLine) "L${anchor.startLine}"
                    else "L${anchor.startLine}–${anchor.endLine}"
                    appendLine("### `${shard.file}` $range${titleSuffix(anchor)}")
                    appendLine()
                    codeExcerpt(project, shard.file, anchor)?.let { excerpt ->
                        appendLine("```${languageOf(shard.file)}")
                        appendLine(excerpt)
                        appendLine("```")
                        appendLine()
                    }
                    anchor.notes.forEach { note ->
                        appendLine("- **${STAMP.format(java.time.Instant.parse(note.createdAt))}** ${note.body.replace("\n", " ")}${tagSuffix(note.tags)}")
                    }
                    appendLine()
                }
            }
        }
    }

    private fun appendAnchor(sb: StringBuilder, project: Project, relativePath: String, anchor: Anchor) {
        val range = if (anchor.startLine == anchor.endLine) "L${anchor.startLine}"
        else "L${anchor.startLine}–${anchor.endLine}"
        sb.appendLine("### $range${titleSuffix(anchor)}")
        sb.appendLine()

        codeExcerpt(project, relativePath, anchor)?.let { excerpt ->
            sb.appendLine("```${languageOf(relativePath)}")
            sb.appendLine(excerpt)
            sb.appendLine("```")
            sb.appendLine()
        }

        if (anchor.notes.isEmpty()) {
            sb.appendLine("_（空锚点）_")
            sb.appendLine()
        } else {
            anchor.notes.forEach { note ->
                sb.appendLine(
                    "- **${STAMP.format(java.time.Instant.parse(note.createdAt))}** " +
                        note.body.replace("\n", "  \n  ") + tagSuffix(note.tags)
                )
            }
            sb.appendLine()
        }
    }

    private fun titleSuffix(anchor: Anchor): String {
        val title = anchor.title.trim()
        return if (title.isEmpty()) "" else " · $title"
    }

    private fun tagSuffix(tags: List<String>): String =
        if (tags.isEmpty()) "" else tags.joinToString(" ") { " `#$it`" }

    /** 取锚定行范围作为摘录（最多 12 行）。 */
    private fun codeExcerpt(project: Project, relativePath: String, anchor: Anchor): String? {
        val base = project.basePath ?: return null
        val file = File(base, relativePath)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.isEmpty()) return null
            val from = (anchor.startLine - 1).coerceIn(0, lines.size - 1)
            val to = (anchor.endLine - 1).coerceIn(from, lines.size - 1).coerceAtMost(from + 11)
            lines.subList(from, to + 1).joinToString("\n")
        }.getOrNull()
    }

    private fun languageOf(relativePath: String): String = when (relativePath.substringAfterLast('.', "").lowercase()) {
        "cs" -> "csharp"
        "cpp", "cc", "cxx", "h", "hpp" -> "cpp"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js" -> "javascript"
        "ts" -> "typescript"
        "json" -> "json"
        "md" -> "markdown"
        else -> ""
    }
}
