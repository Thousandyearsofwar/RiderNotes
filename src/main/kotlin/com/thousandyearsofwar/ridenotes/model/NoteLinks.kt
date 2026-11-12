package com.thousandyearsofwar.ridenotes.model

/**
 * 笔记正文里的锚点链接：`[显示文字](anchor:a_1234abcd)`
 *
 * 只解析"行内链接"，不做完整 Markdown。位置范围是**在传入字符串里的字符下标**，
 * 便于渲染时按段着色、点击时做命中测试。
 */
object NoteLinks {

    data class Link(val start: Int, val end: Int, val text: String, val targetId: String)

    private val PATTERN = Regex("\\[([^\\]\\n]{1,80})]\\(anchor:([A-Za-z0-9_\\-]{3,64})\\)")

    fun parse(text: String): List<Link> =
        PATTERN.findAll(text).map { match ->
            Link(
                start = match.range.first,
                end = match.range.last + 1,
                text = match.groupValues[1],
                targetId = match.groupValues[2]
            )
        }.toList()

    /** 生成一个链接片段，供"插入锚点链接"使用。 */
    fun format(displayText: String, anchorId: String): String {
        val clean = displayText.replace("[", "(").replace("]", ")").take(80)
        return "[$clean](anchor:$anchorId)"
    }
}
