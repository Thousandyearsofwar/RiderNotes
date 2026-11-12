package com.thousandyearsofwar.ridenotes.editor

import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * 字体回退：等宽字体（如 JetBrains Mono）不含中文，直接用会渲染成方块。
 *
 * 纯 AWT 的 `Font` 不会逐字回退，所以这里把文本按"能否被主字体显示"切成若干段，
 * 不能显示的字符交给一个带 CJK 字形的回退字体绘制。
 */
object FontFallback {

    /** 按优先级尝试的中文回退字体。 */
    private val CANDIDATES = listOf(
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "Noto Sans CJK SC",
        "Noto Sans SC",
        "Source Han Sans SC",
        "SimSun",
        "Dialog"
    )

    private val fallbackCache = java.util.concurrent.ConcurrentHashMap<String, Font>()
    private val availableFamilies: Set<String> by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
    }

    /** 取一个能显示中文的字体（同字号）。找不到就返回原字体。 */
    fun fallbackFor(primary: Font): Font {
        val key = "${primary.size}"
        fallbackCache[key]?.let { return it }
        val resolved = CANDIDATES.asSequence()
            .filter { availableFamilies.isEmpty() || it in availableFamilies || it == "Dialog" }
            .map { Font(it, primary.style, primary.size) }
            .firstOrNull { it.canDisplay('中') }
            ?: Font(Font.DIALOG, primary.style, primary.size)
        fallbackCache[key] = resolved
        return resolved
    }

    /** 文本是否全部能被该字体显示。 */
    fun canDisplayAll(font: Font, text: String): Boolean = text.none { !font.canDisplay(it) }

    /**
     * 把文本切成 (字体, 片段) 序列：主字体能显示的走主字体，其余走回退字体。
     * 相邻同字体的片段会合并，避免逐字绘制。
     */
    fun segments(primary: Font, text: String): List<Pair<Font, String>> {
        if (text.isEmpty()) return emptyList()
        if (canDisplayAll(primary, text)) return listOf(primary to text)

        val fallback = fallbackFor(primary)
        val out = mutableListOf<Pair<Font, String>>()
        val buffer = StringBuilder()
        var currentFont = if (primary.canDisplay(text[0])) primary else fallback

        text.forEach { ch ->
            val font = if (primary.canDisplay(ch)) primary else fallback
            if (font !== currentFont) {
                if (buffer.isNotEmpty()) {
                    out.add(currentFont to buffer.toString())
                    buffer.setLength(0)
                }
                currentFont = font
            }
            buffer.append(ch)
        }
        if (buffer.isNotEmpty()) out.add(currentFont to buffer.toString())
        return out
    }
}
