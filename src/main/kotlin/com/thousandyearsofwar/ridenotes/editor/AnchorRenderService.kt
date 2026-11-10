package com.thousandyearsofwar.ridenotes.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.ui.NotePopups
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon

/** 可视区上下各留多少行做块渲染，超出部分只保留色带和 gutter 图标。 */
private const val MARGIN = 200

/**
 * 编辑器视口尺寸。
 *
 * - contentComponent.width/height 是**内容**尺寸（1 万行时高度达 22 万像素）
 * - scrollingModel.visibleArea 在布局期常为 0
 * 所以优先用外层 editor.component 的 visibleRect。
 */
private fun editorViewportSize(editor: com.intellij.openapi.editor.Editor): java.awt.Dimension {
    val visibleArea = runCatching { editor.scrollingModel.visibleArea }.getOrNull()
    val content = runCatching { editor.contentComponent }.getOrNull()
    val outer = runCatching { editor.component?.visibleRect }.getOrNull()

    // 取所有候选里的**最小**宽度：
    // - visibleArea：未被浮动面板遮挡的视口宽
    // - contentComponent.width：内容宽
    // - outer.visibleRect.width：整个编辑器宽度（可能包含被面板盖住的部分，最大）
    // 取最小可以保证块不会画到面板下面去。
    val widths = listOfNotNull(
        visibleArea?.width?.takeIf { it > 0 },
        content?.width?.takeIf { it > 0 },
        outer?.width?.takeIf { it > 0 }
    )
    val heights = listOfNotNull(
        visibleArea?.height?.takeIf { it > 0 },
        outer?.height?.takeIf { it > 0 }
    )
    return java.awt.Dimension(widths.minOrNull() ?: 800, heights.minOrNull() ?: 600)
}

/** 笔记段首的时间戳格式。 */
private val NOTE_STAMP_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

/** 8 色调色板。 */
object NotePalette {
    private val RGB = intArrayOf(
        0x4FC3F7, 0xFFF176, 0xAED581, 0xFFB74D,
        0xBA68C8, 0x4DB6AC, 0xFF8A80, 0x90A4AE
    )

    /** 标记用的饱和色：gutter 图标、面板色块、颜色菜单、摘要块左侧竖条。 */
    fun accent(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    /** 内容底用的低饱和色：代码底色带、摘要块填充。用透明度压住，保证文字可读。 */
    private const val FILL_ALPHA = 46

    fun fill(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, FILL_ALPHA)
    }

    fun background(colorIndex: Int): Color = fill(colorIndex)

    fun blockBackground(colorIndex: Int): Color = fill(colorIndex)

    /** 把半透明填充色压到编辑器背景上，得到不透明色（用于盖住摘要块的编辑框）。 */
    fun opaque(colorIndex: Int, base: Color): Color {
        val tint = fill(colorIndex)
        val alpha = tint.alpha / 255.0
        return Color(
            (tint.red * alpha + base.red * (1 - alpha)).toInt().coerceIn(0, 255),
            (tint.green * alpha + base.green * (1 - alpha)).toInt().coerceIn(0, 255),
            (tint.blue * alpha + base.blue * (1 - alpha)).toInt().coerceIn(0, 255)
        )
    }

    fun solid(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}

/** gutter 上的小方块。 */
class NoteGutterIcon(private val colorIndex: Int) : Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 14

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = NotePalette.accent(colorIndex)
            g2.fillRoundRect(x + 1, y + 1, 10, 12, 3, 3)
            g2.color = Color(0x33, 0x33, 0x33, 160)
            g2.drawRoundRect(x + 1, y + 1, 10, 12, 3, 3)
        } finally {
            g2.dispose()
        }
    }
}

/** 行尾的小胶囊。 */
private class LineEndRenderer(private val colorIndex: Int) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 26

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = NotePalette.accent(colorIndex)
            g2.fillRoundRect(targetRegion.x, targetRegion.y + 2, 22, targetRegion.height - 4, 8, 8)
            g2.color = Color(0x33, 0x33, 0x33, 200)
            g2.font = g2.font.deriveFont(Font.BOLD, 11f)
            g2.drawString("N", targetRegion.x + 7, targetRegion.y + targetRegion.height - 5)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 锚点下方的内联摘要（只读，纯绘制）。
 *
 * 标题和该锚点的所有笔记在**同一个块**里；笔记首行右侧右对齐显示时间。
 */
private class SummaryBlockRenderer(
    private val anchor: Anchor,
    private val text: String,
    private val indentPx: Int,
    private val compact: Boolean,
    private val color: Int,
    private val stamps: Map<Int, String>
) : EditorCustomElementRenderer {

    private val settings = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get()
    private val font: Font = settings.summaryFont()

    private var cacheKey = ""
    private var cachedLines: List<String> = emptyList()

    /**
     * 宽度：默认跟随窗口（贴着右边缘）；设置里填了 >0 才作为上限。
     * 必须用 scrollingModel.visibleArea（视口），contentComponent.width 是内容宽度，横向滚动时会更大。
     */
    /**
     * 视口尺寸。
     *
     * contentComponent 在各种时机下 visibleRect 高度都是 0，
     * 这里统一改为外层 editor.component（真正被 splitter 布局的那个组件）。
     */
    private fun viewportSize(inlay: Inlay<*>): java.awt.Dimension = editorViewportSize(inlay.editor)

    /**
     * 分配宽度：**永远不小于当前视口宽度**。
     *
     * 真正的宽度在 paint 里按设置/视口收紧。这样即使平台在设置变更后没有及时重新分配，
     * 也不会出现"块被 targetRegion 卡窄"的问题（之前就是被卡在旧宽度上）。
     */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val indent = indent(inlay)
        val viewport = editorViewportSize(inlay.editor).width
        val desired = settings.maxWidth.takeIf { it > 0 } ?: viewport
        val allocated = maxOf(desired, viewport).coerceAtLeast(160)
        return indent + allocated + 8
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay)
        return (renderLines(metrics).size * metrics.height + 6).coerceAtLeast(16)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.clip = targetRegion
            g2.font = font

            val metrics = g2.fontMetrics
            val lines = renderLines(metrics)
            val blockHeight = lines.size * metrics.height + 4

            // 关键：把绘制裁到编辑器的**可见矩形**。
            // 块 inlay 的 targetRegion 可能超出视口（滚出屏幕的块、宽度超出），
            // 而编辑器组件不在 JViewport 里裁剪，超出的部分会直接画到标签栏、工具窗口上——
            // 这就是"笔记透到面板上"。
            val outerVisible = runCatching { inlay.editor.component?.visibleRect }.getOrNull()
            val clip = if (outerVisible != null && outerVisible.width > 0 && outerVisible.height > 0) {
                targetRegion.intersection(outerVisible)
            } else {
                targetRegion
            }
            g2.clip = if (clip.isEmpty) targetRegion else clip

            val left = targetRegion.x + indent(inlay)

            // 真正的宽度在这里决定：设置 >0 用它，0 则跟随视口；永远留 2px 不压住滚动条
            val viewport = viewportSize(inlay).width
            val width = if (viewport > 0) {
                settings.summaryWidth(viewport, indent(inlay))
            } else {
                settings.maxWidth.takeIf { it > 0 } ?: 880
            }
            if (width < 40) return

            g2.color = NotePalette.blockBackground(color)
            g2.fillRoundRect(left, targetRegion.y, width, blockHeight, 6, 6)
            g2.color = NotePalette.accent(color)
            g2.fillRoundRect(left, targetRegion.y, 3, blockHeight, 2, 2)

            var baseline = targetRegion.y + metrics.ascent + 2
            lines.forEachIndexed { index, line ->
                val stamp = stamps[index].orEmpty()
                val textLeft = left + 8
                if (stamp.isEmpty()) {
                    g2.color = JBColor.foreground()
                    g2.drawString(ellipsize(metrics, line, (width - 16).coerceAtLeast(20)), textLeft, baseline)
                } else {
                    val stampWidth = metrics.stringWidth(stamp)
                    val textWidth = (width - 16 - stampWidth - 12).coerceAtLeast(20)
                    g2.color = JBColor.foreground()
                    g2.drawString(ellipsize(metrics, line, textWidth), textLeft, baseline)
                    g2.color = Color(0x99, 0x99, 0x99)
                    g2.drawString(stamp, left + width - 8 - stampWidth, baseline)
                }
                baseline += metrics.height
            }

            // 笔记之间画一条细分隔线（第一条之前不画）
            val noteStarts = stamps.keys.sorted()
            if (noteStarts.size > 1) {
                val divider = Color(0x88, 0x88, 0x88, 120)
                g2.color = divider
                noteStarts.drop(1).forEach { index ->
                    val y = targetRegion.y + 2 + index * metrics.height - 2
                    g2.drawLine(left + 8, y, left + width - 8, y)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun metrics(inlay: Inlay<*>): java.awt.FontMetrics =
        inlay.editor.contentComponent.getFontMetrics(font)

    private fun indent(inlay: Inlay<*>): Int = indentPx.coerceAtLeast(0)

    /** 行数不设上限：简洁模式已经在外层把每条压成一行了。 */
    private fun renderLines(metrics: java.awt.FontMetrics): List<String> {
        val key = "all|$text"
        if (key == cacheKey && cachedLines.isNotEmpty()) return cachedLines

        val out = text.split('\n').map { it.trimEnd() }
        cacheKey = key
        cachedLines = out.ifEmpty { listOf("") }
        return cachedLines
    }

    /** 二分截断，别用逐字删（长文本会 O(n²) 卡绘制）。 */
    private fun ellipsize(metrics: java.awt.FontMetrics, text: String, maxWidth: Int): String {
        if (text.isEmpty() || metrics.stringWidth(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (metrics.stringWidth(text.substring(0, mid) + "…") <= maxWidth) low = mid else high = mid - 1
        }
        return text.substring(0, low) + "…"
    }

    private companion object {
        const val MAX_LINES = 3
        const val MAX_WIDTH = 880
    }
}

/**
 * 把锚点画到编辑器上：底色带 + gutter 图标 + 行尾按钮 + 行下摘要。
 */
@Service(Service.Level.PROJECT)
class AnchorRenderService(private val project: Project) {

    private val states = mutableMapOf<Editor, EditorState>()

    companion object {
        val ANCHOR_ID: Key<String> = Key.create("ridenotes.anchorId")
        val NOTE_ID: Key<String> = Key.create("ridenotes.noteId")

        fun get(project: Project): AnchorRenderService = project.service()
    }

    fun attach(editor: Editor, file: VirtualFile) {
        if (states.containsKey(editor)) return
        val state = EditorState(editor, file)
        states[editor] = state
        editor.document.addDocumentListener(state.documentListener, project)
        editor.addEditorMouseListener(state.mouseListener)
        // 延后一帧再画：项目打开瞬间不要同步去建几千个 highlighter
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) render(state)
        }
    }

    fun detach(editor: Editor) {
        val state = states.remove(editor) ?: return
        editor.document.removeDocumentListener(state.documentListener)
        editor.removeEditorMouseListener(state.mouseListener)
        state.dispose()
        state.clear()
    }

    fun refresh(file: VirtualFile) {
        states.values.filter { it.file == file }.forEach { render(it) }
    }

    /** 丢掉某条锚点的存活位置（重绑后调用），下一次渲染按新行号重算。 */
    fun invalidate(file: VirtualFile, anchorId: String) {
        states.values.filter { it.file == file }.forEach { it.dropAnchor(anchorId) }
        refresh(file)
    }

    fun refreshAll() {
        states.values.forEach { render(it) }
    }

    private fun render(state: EditorState) {
        val startedAt = System.nanoTime()
        val service = NoteAnchorService.get(project)
        val anchors = service.anchorsFor(state.file)
        val document = state.editor.document

        // 记录现有 highlighter 的位置（它们本身就是 RangeMarker，会跟着编辑走）
        val live = state.painted.associate { it.anchor.id to (it.highlighter.startOffset to it.highlighter.endOffset) }
        val desired = LinkedHashMap<String, Pair<Anchor, com.intellij.openapi.util.TextRange>>()

        anchors.forEach { anchor ->
            // 克隆节点只是面板里的别名，不在代码区渲染任何东西
            if (anchor.mirrorOf != null) return@forEach
            if (!service.effectiveVisible(anchor)) return@forEach

            val liveRange = live[anchor.id]
            val range = if (liveRange != null &&
                liveRange.first in 0..document.textLength &&
                liveRange.second in 0..document.textLength
            ) {
                // 会话内以 marker 的实际位置为准，避免"用户改了这段代码就判过期"
                com.intellij.openapi.util.TextRange(liveRange.first, liveRange.second)
            } else {
                Anchoring.locate(document, anchor)
            }

            if (range == null) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("stale anchor ${anchor.id} in ${state.file.name}")
                return@forEach
            }

            // 会话内改过内容 → 用新文本刷新锚定身份；跨会话只认行号
            if (liveRange != null) {
                val text = document.getText(range)
                val normalized = Anchoring.normalize(text)
                if (normalized != anchor.anchorText) {
                    anchor.anchorText = normalized
                    anchor.anchorSha1 = Anchoring.sha1(text)
                    anchor.contextBefore = Anchoring.contextBefore(document, document.getLineNumber(range.startOffset))
                    anchor.contextAfter = Anchoring.contextAfter(
                        document, document.getLineNumber(range.endOffset.coerceAtMost(document.textLength))
                    )
                }
                anchor.startOffset = range.startOffset
                anchor.endOffset = range.endOffset
            }

            anchor.startLine = document.getLineNumber(range.startOffset) + 1
            anchor.endLine = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength)) + 1
            desired[anchor.id] = anchor to range
        }

        // 1) 删掉已经不该存在的
        state.painted.map { it.anchor.id }.filter { it !in desired }.forEach { state.dropAnchor(it) }

        // 2) 增量更新：位置/颜色没变的保留 highlighter，只按内容签名决定是否重建块
        val window = state.windowOf()
        val existingById = state.painted.associateBy { it.anchor.id }

        desired.forEach { (id, pair) ->
            val (anchor, range) = pair
            val color = service.effectiveColor(anchor)
            val signature = contentSignature(anchor, color)
            val withBlock = anchor.endLine >= window.first && anchor.startLine <= window.last

            val existing = existingById[id]
            val sameRange = existing != null &&
                existing.highlighter.isValid &&
                existing.highlighter.startOffset == range.startOffset &&
                existing.highlighter.endOffset == range.endOffset

            if (existing != null && sameRange && existing.color == color) {
                // 关键：把 Painted 里的锚点引用换成最新的。
                // 否则重建块时会用旧的 Anchor 实例（比如重新加载过库之后），
                // 表现就是"笔记只显示一行/旧内容"，切换一下显示开关才恢复。
                val anchorReplaced = existing.anchor !== anchor
                existing.anchor = anchor
                if (anchorReplaced) {
                    existing.highlighter.gutterIconRenderer =
                        gutterFor(project, anchor, color) { announceActivation(anchor) }
                }

                if (existing.contentSignature != signature || anchorReplaced) {
                    state.disposeInlays(existing)
                    if (withBlock) state.addInlays(existing, signature)
                    existing.contentSignature = signature
                } else if (withBlock && existing.blocks.isEmpty()) {
                    state.addInlays(existing, signature)
                } else if (!withBlock && existing.blocks.isNotEmpty()) {
                    state.disposeInlays(existing)
                }
            } else {
                existing?.let { state.dropAnchor(id) }
                state.add(anchor, range.startOffset, range.endOffset, withBlock, signature)
            }
        }

        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "render file=${state.file.name} anchors=${anchors.size} painted=${state.painted.size} " +
                "blocks=${state.painted.sumOf { it.blocks.size }} " +
                "in ${(System.nanoTime() - startedAt) / 1_000_000}ms"
        )
    }

    /** gutter 图标被点击：通知面板把列表滚动并选中到该锚点。 */
    private fun announceActivation(anchor: Anchor) {
        runCatching {
            project.messageBus
                .syncPublisher(com.thousandyearsofwar.ridenotes.ui.AnchorActivationTopic.TOPIC)
                .anchorActivated(anchor.id)
        }
    }

    /** 标题/笔记内容/外观设置/简洁开关变了才需要重建摘要块。 */
    private fun contentSignature(anchor: Anchor, color: Int): String {
        val settings = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get()
        val service = NoteAnchorService.get(project)
        val compact = service.effectiveCompact(anchor)
        return buildString {
            append(settings.maxWidth).append(':').append(settings.fontSize).append(':').append(color)
            append(':').append(if (compact) 1 else 0)
            append('|').append(service.resolvedTitle(anchor))
            service.resolvedNotes(anchor).forEach { note ->
                append('|').append(note.id).append(':').append(note.updatedAt).append(':').append(note.body.length)
            }
        }
    }

    private inner class EditorState(val editor: Editor, val file: VirtualFile) {

        var lastWindowStart: Int = Int.MIN_VALUE

        /** 滚动时只补/删可视区的块，不重建 highlighter（否则千级锚点会卡死 EDT）。 */
        private val scrollListener = object : com.intellij.openapi.editor.event.VisibleAreaListener {
            override fun visibleAreaChanged(e: com.intellij.openapi.editor.event.VisibleAreaEvent) {
                // 滚动后强制重绘一次：块滚出视口（尤其顶部）时会留下半截像素。
                // repaint() 由 Swing 合并，不涉及任何 inlay/模型操作。
                if (!editor.isDisposed) editor.contentComponent.repaint()

                val window = windowOf()
                if (lastWindowStart != Int.MIN_VALUE && Math.abs(window.first - lastWindowStart) < MARGIN / 2) return
                lastWindowStart = window.first
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.isDisposed) updateBlockVisibility()
                }
            }
        }

        init {
            runCatching { editor.scrollingModel.addVisibleAreaListener(scrollListener) }
            runCatching { editor.contentComponent.addComponentListener(repaintListener) }
            // 外层组件也会被 splitter 改尺寸，两个都挂上
            runCatching { editor.component?.addComponentListener(repaintListener) }
        }

        fun dispose() {
            runCatching { editor.scrollingModel.removeVisibleAreaListener(scrollListener) }
            runCatching { editor.contentComponent.removeComponentListener(repaintListener) }
            runCatching { editor.component?.removeComponentListener(repaintListener) }
        }

        /**
         * 任何布局变化（拖任意面板/分隔条、改窗口大小）之后：
         *   1. 等布局落定（invokeLater）
         *   2. 把**当前可见的块**全部销毁重建 —— 位置变了，旧 inlay 的像素会残留
         *   3. 强制重画编辑器
         *
         * 只处理可见的几十个块，不遍历全部锚点；用 pending 标记把连续事件合并成一次。
         * 之前把 render()（1000+ 锚点）塞进 resize 回调，才会把 CPU 打满。
         */
        private var layoutPending = false

        private val repaintListener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (layoutPending || editor.isDisposed) return
                layoutPending = true
                ApplicationManager.getApplication().invokeLater {
                    layoutPending = false
                    if (editor.isDisposed) return@invokeLater
                    // 只处理有块的项；painted 里可能有几千个锚点，全遍历会把 EDT 拖死
                    painted.forEach { if (it.blocks.isNotEmpty()) disposeInlays(it) }
                    updateBlockVisibility()
                    runCatching { editor.contentComponent.repaint() }
                    runCatching { editor.component?.repaint() }
                }
            }
        }

        val painted = mutableListOf<Painted>()
        var lastRenderedWidth: Int = -1

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // inlay 不随文档移动，重挂；highlighter 本身是 marker，自己会跟
                ApplicationManager.getApplication().invokeLater { reposition() }
            }
        }

        val mouseListener = object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                val inlay = event.inlay ?: return
                val anchorId = inlay.getUserData(ANCHOR_ID) ?: return
                val anchor = NoteAnchorService.get(project).anchorsFor(file).firstOrNull { it.id == anchorId } ?: return

                // 右键：不拦截菜单，但把光标挪到这条锚点的起点，
                // 让右键菜单里的删除动作指向你点的这一条
                if (javax.swing.SwingUtilities.isRightMouseButton(event.mouseEvent)) {
                    moveCaretTo(anchor)
                    return
                }

                event.consume()
                if (event.mouseEvent.clickCount >= 2) {
                    beginEdit(anchor, inlay, inlay.getUserData(NOTE_ID))
                } else {
                    moveCaretTo(anchor)
                }
            }
        }

        private fun moveCaretTo(anchor: Anchor) {
            runCatching {
                editor.caretModel.moveToOffset(anchor.startOffset.coerceIn(0, editor.document.textLength))
            }
        }

        /** 就地编辑：编辑框正好盖在摘要块上。 */
        fun beginEdit(anchor: Anchor, inlay: Inlay<*>?, noteId: String? = null) {
            val service = NoteAnchorService.get(project)
            if (anchor.mirrorOf != null) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                    project, "这是克隆锚点，请到源锚点编辑"
                )
                return
            }
            if (service.effectiveLocked(anchor)) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "该笔记已锁定，请在 Notes 面板中编辑")
                return
            }
            val note = service.resolvedNotes(anchor).firstOrNull { it.id == noteId } ?: service.resolvedNotes(anchor).firstOrNull()
            val bounds = inlay?.let { runCatching { it.bounds }.getOrNull() }

            // 编辑时先把这一锚点的所有摘要块收起来，避免两层颜色叠加
            painted.firstOrNull { it.anchor.id == anchor.id }?.let { disposeInlays(it) }

            val at = bounds?.let {
                com.intellij.ui.awt.RelativePoint(editor.contentComponent, java.awt.Point(it.x, it.y))
            }
            val size = bounds?.let {
                java.awt.Dimension(
                    (it.width - 8).coerceAtLeast(280),
                    it.height.coerceAtLeast(editor.lineHeight * 4)
                )
            } ?: java.awt.Dimension(420, editor.lineHeight * 5)

            NotePopups.showEditor(
                project = project,
                editor = editor,
                file = file,
                anchor = anchor,
                initial = note?.body ?: "",
                onSave = { body ->
                    if (note != null) {
                        service.updateNote(file, anchor.id, note.id, body)
                    } else if (body.isNotBlank()) {
                        service.addNote(file, anchor.id, body)
                    }
                    this@AnchorRenderService.refresh(file)
                },
                onCancel = { reposition() },
                at = at,
                size = size,
                backgroundColor = NotePalette.opaque(service.effectiveColor(anchor), editor.colorsScheme.defaultBackground)
            )
        }

        fun add(anchor: Anchor, start: Int, end: Int, withBlock: Boolean, signature: String = "") {
            val document = editor.document
            val color = NoteAnchorService.get(project).effectiveColor(anchor)
            val attributes = TextAttributes().apply {
                backgroundColor = NotePalette.background(color)
            }
            val highlighter = editor.markupModel.addRangeHighlighter(
                start, end,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.setGreedyToLeft(true)
            highlighter.setGreedyToRight(false)

            val item = Painted(anchor, highlighter, color)
            item.contentSignature = signature
            highlighter.gutterIconRenderer = gutterFor(project, anchor, color) { announceActivation(anchor) }
            painted.add(item)
            if (withBlock) addInlays(item, signature)
        }

        /** 可视区（上下各留 MARGIN 行）。 */
        fun windowOf(): IntRange {
            val size = editorViewportSize(editor)
            val height = size.height

            // xyToLogicalPosition 的坐标是**内容坐标**，可见区顶部对应 verticalScrollOffset
            val scrollY = runCatching { editor.scrollingModel.verticalScrollOffset }.getOrDefault(0)
            val top = runCatching { editor.xyToLogicalPosition(java.awt.Point(0, scrollY)).line }.getOrDefault(0)
            val bottom = runCatching { editor.xyToLogicalPosition(java.awt.Point(0, scrollY + height)).line }
                .getOrDefault(top + 30)
            val boundedBottom = if (bottom - top > 400) top + 120 else bottom
            return (top - MARGIN)..(boundedBottom + MARGIN)
        }

        /** 滚动后只增删块，不重建 highlighter。 */
        fun updateBlockVisibility() {
            val window = windowOf()
            painted.forEach { item ->
                val first = item.anchor.startLine
                val last = item.anchor.endLine
                val want = last >= window.first && first <= window.last
                if (want && item.blocks.isEmpty()) {
                    addInlays(item, item.contentSignature)
                } else if (!want && item.blocks.isNotEmpty()) {
                    disposeInlays(item)
                }
            }
            // 块增减后重画一次，清掉旧位置的像素
            if (!editor.isDisposed) editor.contentComponent.repaint()
        }

        fun addInlays(item: Painted, signature: String = item.contentSignature) {
            val document = editor.document
            val start = item.highlighter.startOffset.coerceIn(0, document.textLength)
            val end = item.highlighter.endOffset.coerceIn(0, document.textLength)

            val startLine = document.getLineNumber(start)

            // 摘要行要和代码缩进对齐：取锚定首行第一个非空白字符的像素位置
            val lineStart = document.getLineStartOffset(startLine)
            val lineEnd = document.getLineEndOffset(startLine)
            var firstNonWs = lineStart
            while (firstNonWs < lineEnd && document.charsSequence[firstNonWs].isWhitespace()) firstNonWs++
            val indentPx = runCatching {
                editor.visualPositionToXY(editor.offsetToVisualPosition(firstNonWs)).x
            }.getOrDefault(0).coerceAtLeast(0)

            val endLine = document.getLineNumber(end)
            val service = NoteAnchorService.get(project)
            val compact = service.effectiveCompact(item.anchor)
            val offset = document.getLineEndOffset(endLine)

            // 摘要总开关：关掉后只有色带 + gutter 图标
            if (!com.thousandyearsofwar.ridenotes.settings.NotesSettings.get().showSummary) return


            // 一条笔记一个块；标题只在面板/悬停里显示，不再占用代码区
            val blocks = mutableListOf<Pair<String?, List<String>>>()

            fun noteLines(note: com.thousandyearsofwar.ridenotes.model.Note): List<String> {
                val body = note.body.trim()
                if (body.isEmpty()) return emptyList()
                return body.lines()
            }

            service.resolvedNotes(item.anchor).forEach { note ->
                val lines = if (compact) note.body.trim().lines().take(1) else noteLines(note)
                if (lines.isEmpty() || lines.all { it.isBlank() }) return@forEach
                blocks.add(note.id to lines)
            }

            if (blocks.isEmpty()) {
                blocks.add(null to listOf("空笔记 · 双击编辑"))
            }

            blocks.forEachIndexed { index, (noteId, lines) ->
                val text = lines.joinToString("\n")
                val note = service.resolvedNotes(item.anchor).firstOrNull { it.id == noteId }
                val stamps = if (note == null) emptyMap() else mapOf(0 to stampOf(note))

                val inlay = editor.inlayModel.addBlockElement(
                    offset, true, false, 50,
                    SummaryBlockRenderer(item.anchor, text, indentPx, compact, item.color, stamps)
                )
                if (inlay != null) {
                    inlay.putUserData(ANCHOR_ID, item.anchor.id)
                    if (noteId != null) inlay.putUserData(NOTE_ID, noteId)
                    item.blocks.add(inlay)
                }
            }
        }

        private fun reposition() {
            if (editor.isDisposed) return
            painted.forEach { item ->
                if (!item.highlighter.isValid) return@forEach
                disposeInlays(item)
                addInlays(item)
            }
        }

        fun disposeInlays(item: Painted) {
            item.blocks.forEach { runCatching { it.dispose() } }
            item.blocks.clear()
        }

        fun clear() {
            painted.forEach { item ->
                disposeInlays(item)
                runCatching { editor.markupModel.removeHighlighter(item.highlighter) }
            }
            painted.clear()
        }

        /** 移除单个锚点的视觉（保留数据），让下次渲染重新定位。 */
        fun dropAnchor(anchorId: String) {
            val item = painted.firstOrNull { it.anchor.id == anchorId } ?: return
            painted.remove(item)
            disposeInlays(item)
            runCatching { editor.markupModel.removeHighlighter(item.highlighter) }
        }
    }

    /** 一条笔记的块文本：完整多行，日期时间挂在**首行末尾**。 */
    private fun noteBlockText(note: com.thousandyearsofwar.ridenotes.model.Note): String {
        val body = note.body.trim()
        if (body.isEmpty()) return ""
        val stamp = stampOf(note)
        if (stamp.isEmpty()) return body
        val lines = body.lines()
        return buildString {
            append(lines.firstOrNull().orEmpty())
            append("   ")
            append(stamp)
            lines.drop(1).forEach { append("\n").append(it) }
        }
    }

    private fun stampOf(note: com.thousandyearsofwar.ridenotes.model.Note): String = runCatching {
        NOTE_STAMP_FORMAT.format(java.time.Instant.parse(note.createdAt))
    }.getOrDefault("")

    private fun gutterFor(project: Project, anchor: Anchor, color: Int, onClick: () -> Unit): GutterIconRenderer = object : GutterIconRenderer() {
        override fun getIcon(): Icon = NoteGutterIcon(color)

        override fun getTooltipText(): String = buildString {
            val svc = com.thousandyearsofwar.ridenotes.service.NoteAnchorService.get(project)
            append(svc.resolvedTitle(anchor).ifBlank { anchor.displayTitle })
            val resolved = svc.resolvedNotes(anchor)
            if (resolved.isNotEmpty()) append("  ·  ${resolved.size} 条")
            resolved.firstOrNull()?.let { append("\n").append(it.body.take(80)) }
            if (anchor.mirrorOf != null) append("\n（克隆锚点，跟随源锚点）")
            append("\n双击就地编辑")
        }

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }

        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private class Painted(var anchor: Anchor, val highlighter: RangeHighlighter, val color: Int) {
        /** 标题/笔记内容签名，变了才重建块。 */
        var contentSignature: String = ""
        /** 每条笔记一个块（简洁模式只有一个）。 */
        val blocks = mutableListOf<Inlay<*>>()
    }
}
