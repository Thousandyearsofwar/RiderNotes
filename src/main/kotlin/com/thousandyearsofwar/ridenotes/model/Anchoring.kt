package com.thousandyearsofwar.ridenotes.model

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import java.security.MessageDigest

/**
 * 锚定的核心：把"一段代码"变成一个可以在文档变动后重新找回的东西。
 *
 * 身份 = 规范化原文（sha1）+ 前后各 3 行上下文。
 * 偏移量只当作"上一次在哪"的提示，不作为身份。
 */
object Anchoring {

    private const val CONTEXT_LINES = 3

    fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(normalize(text).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 记录上下文行，用于"同一段文本出现多次"时的打分。 */
    fun contextBefore(document: Document, startLine: Int): List<String> {
        val from = (startLine - CONTEXT_LINES).coerceAtLeast(0)
        return (from until startLine).map { document.getText(lineRange(document, it)).trim() }
    }

    fun contextAfter(document: Document, endLine: Int): List<String> {
        val to = (endLine + CONTEXT_LINES).coerceAtMost(document.lineCount - 1)
        return ((endLine + 1)..to).map { document.getText(lineRange(document, it)).trim() }
    }

    fun lineRange(document: Document, line: Int): TextRange =
        TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))

    /**
     * 自动定位：**按行号**（书签语义）。
     *
     * 会话内由 RangeMarker 跟随编辑；跨会话就认存储的行号，夹到文档范围内。
     * 不做文本搜索——搜索会把笔记挪到别处，成本远高于收益。
     */
    fun locate(document: Document, anchor: Anchor): TextRange? {
        if (document.lineCount <= 0) return null
        val last = document.lineCount - 1
        val startLine = (anchor.startLine - 1).coerceIn(0, last)
        val endLine = (anchor.endLine - 1).coerceIn(startLine, last)
        return TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine))
    }

    /** 记录时的原文是否还和当前位置对得上（仅用于显示 ⚠，不拦截渲染）。 */
    fun textChanged(document: Document, anchor: Anchor): Boolean {
        val needle = normalize(anchor.anchorText)
        if (needle.isEmpty()) return false
        if (anchor.startOffset !in 0..document.textLength ||
            anchor.endOffset !in 0..document.textLength ||
            anchor.endOffset < anchor.startOffset
        ) {
            return true
        }
        val current = normalize(document.getText(TextRange(anchor.startOffset, anchor.endOffset)))
        return current != needle
    }

    /**
     * 手动重定位：按原文搜索 + 上下文打分。只在用户点"尝试重新定位"时调用。
     */
    fun searchRelocate(document: Document, anchor: Anchor): TextRange? {
        val needle = normalize(anchor.anchorText)
        if (needle.isEmpty()) return null

        val haystack = document.text
        val hits = mutableListOf<TextRange>()
        var index = haystack.indexOf(needle)
        while (index >= 0 && hits.size < 200) {
            hits.add(TextRange(index, index + needle.length))
            index = haystack.indexOf(needle, index + 1)
        }
        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits.first()

        val scored = hits.maxByOrNull { score(document, it, anchor) } ?: return null
        return if (score(document, scored, anchor) > 0) scored else null
    }

    /** 上下文行匹配度：命中一行 +1。 */
    private fun score(document: Document, range: TextRange, anchor: Anchor): Int {
        val startLine = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
        val endLine = document.getLineNumber(range.endOffset.coerceIn(0, document.textLength))
        var score = 0
        val before = contextBefore(document, startLine)
        val after = contextAfter(document, endLine)
        score += overlap(before, anchor.contextBefore)
        score += overlap(after, anchor.contextAfter)
        return score
    }

    private fun overlap(actual: List<String>, stored: List<String>): Int =
        actual.zip(stored).count { (a, b) -> a.isNotEmpty() && a == b }

    /** 从当前文档创建一个新锚点。 */
    fun createAnchor(document: Document, range: TextRange): Anchor {
        val start = range.startOffset.coerceIn(0, document.textLength)
        val end = range.endOffset.coerceIn(start, document.textLength)
        val startLine = document.getLineNumber(start)
        val endLine = document.getLineNumber(end.coerceAtMost(document.textLength))
        val text = document.getText(TextRange(start, end))
        return Anchor(
            startOffset = start,
            endOffset = end,
            startLine = startLine + 1,
            endLine = endLine + 1,
            anchorText = normalize(text),
            anchorSha1 = sha1(text),
            contextBefore = contextBefore(document, startLine),
            contextAfter = contextAfter(document, endLine)
        )
    }
}
