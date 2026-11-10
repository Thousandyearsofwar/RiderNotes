package com.thousandyearsofwar.ridenotes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.thousandyearsofwar.ridenotes.editor.AnchorRenderService
import com.thousandyearsofwar.ridenotes.actions.registerUndo
import com.thousandyearsofwar.ridenotes.editor.NotePalette
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.AnchorStore
import com.thousandyearsofwar.ridenotes.model.GroupNode
import com.thousandyearsofwar.ridenotes.model.Placement
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

sealed class Row(val depth: Int) {
    abstract val id: String
}

class GroupRow(val group: GroupNode, depth: Int) : Row(depth) {
    override val id: String get() = group.id
}

class NoteRow(
    val relativePath: String,
    val anchor: Anchor,
    depth: Int,
    /** 刷新时算好的"原文已变更"标记，渲染器只读这个字段，不做任何 I/O。 */
    val changed: Boolean = false
) : Row(depth) {
    override val id: String get() = anchor.id
}

/** 锚点下的一条笔记（子节点）。 */
class NoteItemRow(
    val relativePath: String,
    val anchor: Anchor,
    val note: com.thousandyearsofwar.ridenotes.model.Note,
    depth: Int
) : Row(depth) {
    override val id: String get() = note.id
}

/** "无法定位"分组的表头。 */
class StaleHeaderRow(depth: Int) : Row(depth) {
    override val id: String get() = "stale_header"
}

/** 定位失败的锚点。reason：内容已变 / 无法定位。 */
class StaleRow(
    val relativePath: String,
    val anchor: Anchor,
    depth: Int,
    val reason: String
) : Row(depth) {
    override val id: String get() = "stale_" + anchor.id
}

private const val COL_VISIBLE = 0
private const val COL_LOCK = 1
private const val COL_COMPACT = 2
private const val COL_NAME = 3

private class DotIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 12
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillRoundRect(x + 1, y + 1, 10, 10, 3, 3)
            // 描边：浅色主题下亮色块与背景对比度低，需要一圈深色轮廓
            g2.color = color.darker()
            g2.drawRoundRect(x + 1, y + 1, 10, 10, 3, 3)
        } finally {
            g2.dispose()
        }
    }
}

/** 状态开关图标：kind 0=显示 1=锁定 2=简洁。 */
private class StatusIcon(private val kind: Int, private val on: Boolean) : Icon {
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (on) com.intellij.ui.JBColor.foreground() else Color(0x88, 0x88, 0x88, 130)
            when (kind) {
                0 -> if (on) g2.fillOval(x + 2, y + 4, 10, 7) else g2.drawOval(x + 2, y + 4, 10, 7)
                1 -> {
                    if (on) g2.fillRect(x + 3, y + 7, 8, 6) else g2.drawRect(x + 3, y + 7, 8, 6)
                    g2.drawArc(x + 4, y + 2, 6, 8, 0, 180)
                }
                else -> {
                    if (on) {
                        g2.fillRect(x + 2, y + 4, 10, 2)
                        g2.fillRect(x + 2, y + 9, 7, 2)
                    } else {
                        g2.drawRect(x + 2, y + 4, 10, 2)
                        g2.drawRect(x + 2, y + 9, 7, 2)
                    }
                }
            }
        } finally {
            g2.dispose()
        }
    }
}

/** 带落点指示线的表格。dropMode: 0=插到前面 1=放进组里 2=插到后面 */
private class NotesTable(model: javax.swing.table.TableModel) : JTable(model) {
    var dropRow: Int = -1
    var dropMode: Int = 0

    /** 拖拽中：屏蔽 JTable 自带的"拖动扩选"行为（否则经过的行都会被选中）。 */
    var dragging: Boolean = false

    /** 多选状态下按住其中一行准备拖动：不要立刻把选区塌缩成一行。 */
    var suppressCollapse: Boolean = false

    override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
        if (dragging) return
        if (suppressCollapse && !toggle && !extend && isRowSelected(rowIndex)) return
        // 多选状态下点"显示/锁定/简洁"开关列：不要让表格把选区塌缩成单行
        if (!toggle && !extend && selectedRowCount > 1 &&
            columnIndex in intArrayOf(COL_VISIBLE, COL_LOCK, COL_COMPACT) && isRowSelected(rowIndex)
        ) {
            return
        }
        super.changeSelection(rowIndex, columnIndex, toggle, extend)
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        if (dropRow < 0) return
        val rect = getCellRect(dropRow, 0, true)
        val g2 = g.create() as Graphics2D
        try {
            if (dropMode == 1) {
                g2.color = Color(0x3D, 0x7E, 0xC7, 60)
                g2.fillRect(0, rect.y + 1, width, rect.height - 2)
                g2.color = Color(0x3D, 0x7E, 0xC7)
                g2.drawRect(0, rect.y + 1, width - 1, rect.height - 3)
            } else {
                g2.color = Color(0x3D, 0x7E, 0xC7)
                val y = if (dropMode == 2) rect.y + rect.height - 2 else rect.y
                g2.fillRect(0, y, width, 2)
            }
        } finally {
            g2.dispose()
        }
    }

    fun clearDrop() {
        if (dropRow != -1) {
            dropRow = -1
            repaint()
        }
    }
}

private class NotesTableModel : AbstractTableModel() {

    val rows = mutableListOf<Row>()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 4
    override fun getColumnName(column: Int): String = when (column) {
        COL_VISIBLE -> "显示"
        COL_LOCK -> "锁定"
        COL_COMPACT -> "简洁"
        COL_NAME -> "名称"
        else -> ""
    }

    override fun isCellEditable(row: Int, col: Int): Boolean = false

    override fun getValueAt(row: Int, col: Int): Any = rows[row]

    fun replace(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }
}

class NotesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = NoteAnchorService.get(project)
    private val model = NotesTableModel()
    private val table = NotesTable(model)
    private val collapsed = mutableSetOf<String>()

    private val filterField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.filter.placeholder")
    }
    private val tagFilterButton = javax.swing.JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.tagFilter"))
    private val filterTags = mutableSetOf<String>()
    private val expandedNotes = mutableSetOf<String>()
    private var uiStateLoaded = false

    private var pressPoint: java.awt.Point? = null
    private var pressRow: Int = -1
    private var lastPoint: java.awt.Point? = null

    private var watchedDocument: com.intellij.openapi.editor.Document? = null
    private var documentWatcher: com.intellij.openapi.editor.event.DocumentListener? = null

    /** 每次 refresh 重建一次：anchorId -> (相对路径, 锚点)。避免每行都去重读全部分片。 */
    private var anchorIndex: Map<String, Pair<String, com.thousandyearsofwar.ridenotes.model.Anchor>> = emptyMap()

    /** 数据是否变脏（服务层有写入）；只有变脏才重读分片，纯过滤不读盘。 */
    private var dataDirty = true

    /** 当前编辑器的文档一变，就重算"无法定位"分组（删代码本身不会触发数据变更事件）。 */
    private fun watchCurrentDocument() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        if (document === watchedDocument) return
        watchedDocument?.let { old -> documentWatcher?.let { old.removeDocumentListener(it) } }
        val watcher = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                ApplicationManager.getApplication().invokeLater { refresh() }
            }
        }
        document.addDocumentListener(watcher, project)
        watchedDocument = document
        documentWatcher = watcher
    }

    init {
        // 编辑器 gutter 图标点击 → 列表跳转
        runCatching {
            project.messageBus.connect(project).subscribe(
                AnchorActivationTopic.TOPIC,
                AnchorActivationTopic.Listener { anchorId ->
                    javax.swing.SwingUtilities.invokeLater { revealAnchor(anchorId) }
                }
            )
        }

        border = JBUI.Borders.empty(4)
        // 浮动工具窗在 Windows 11 下带半透明（Mica），编辑器里的笔记会"透"上来。
        // 面板自身刷成不透明，避免看起来像笔记穿过了面板。
        isOpaque = true
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        table.rowHeight = 32
        table.setShowGrid(false)
        table.isOpaque = true
        table.background = com.intellij.util.ui.UIUtil.getPanelBackground()
        table.intercellSpacing = Dimension(0, 0)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.tableHeader.reorderingAllowed = false
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        for (col in intArrayOf(COL_VISIBLE, COL_LOCK, COL_COMPACT)) {
            val column: TableColumn = table.columnModel.getColumn(col)
            column.maxWidth = 42
            column.minWidth = 36
            column.preferredWidth = 38
            column.cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
                    row: Int, column: Int
                ): Component {
                    val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                    label.text = ""
                    label.horizontalAlignment = javax.swing.SwingConstants.CENTER
                    val on = model.rows.getOrNull(row)?.let { stateOf(it, column) } ?: false
                    label.icon = StatusIcon(column, on)
                    return label
                }
            }
        }

        table.columnModel.getColumn(COL_NAME).apply {
            preferredWidth = 420
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
                    row: Int, column: Int
                ): Component {
                    val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                    val item = model.rows.getOrNull(row)
                    label.border = JBUI.Borders.emptyLeft(4 + (item?.depth ?: 0) * 16)
                    when (item) {
                        is GroupRow -> {
                            val arrow = if (hasChildren(item.group.id)) {
                                if (item.group.id in collapsed) "\u25B8 " else "\u25BE "
                            } else "  "
                            label.icon = AllIcons.Nodes.Folder
                            val defaultMark = if (item.group.id == service.defaultGroupId()) " <font color=\u0027#8a8a8a\u0027>默认</font>" else ""
                                label.text = "<html><b>${escape(arrow + item.group.title.ifBlank { "未命名分组" })}</b>$defaultMark" +
                                flagsOf(item.group.visible, item.group.locked, item.group.compact) +
                                tagChipsOf(item.group.tags) + "</html>"
                        }

                        is NoteRow -> {
                            label.icon = DotIcon(NotePalette.accent(service.effectiveColor(item.anchor)))
                            val anchorNotes = service.resolvedNotes(item.anchor)
                            val arrow = if (anchorNotes.size > 1) {
                                if (item.anchor.id in expandedNotes) "\u25BE " else "\u25B8 "
                            } else "  "
                            val previewRaw = anchorNotes.firstOrNull()?.body?.replace("\n", " ")?.trim().orEmpty()
                            val tagsPlain = anchorNotes.flatMap { it.tags }.distinct()
                                .joinToString(" ") { "#$it" }
                            val where = "${shortPath(item.relativePath)}:${item.anchor.startLine}"
                            val wherePlain = "\u2014 $where"
                            val fm = label.getFontMetrics(label.font)
                            // 预留：标题 + 标签 + "— 文件:行" + 警告 + 安全余量，
                            // 否则预览会把来源挤掉、或者紧贴边界导致 HTML 折到第二行
                            val used = fm.stringWidth(arrow + item.anchor.displayTitle) +
                                fm.stringWidth(tagsPlain) +
                                fm.stringWidth(wherePlain) +
                                (if (item.changed) 60 else 0) +
                                90
                            val preview = fitText(
                                previewRaw, fm,
                                (rowTextWidth(table, item.depth) - used).coerceAtLeast(0)
                            )
                            val warn = if (item.changed) {
                                " <span style='color:#d05a5a'>\u26A0已变更</span>"
                            } else ""
                            // 标签放在预览前面：预览一长，行尾的标签就会被挤掉看不见
                            label.text = "<html><b>${escape(arrow + (if (item.anchor.mirrorOf != null) "⧉ " else "") + service.resolvedTitle(item.anchor).ifBlank { item.anchor.displayTitle })}</b>" +
                                flagsOf(
                                    service.effectiveVisible(item.anchor),
                                    service.effectiveLocked(item.anchor),
                                    service.effectiveCompact(item.anchor)
                                ) +
                                tagChipsOf(anchorNotes.flatMap { it.tags }) +
                                " <span style='color:gray'>${escape(preview)}</span>" +
                                " <span style='color:#7a7a7a'>\u2014 ${escape(where)}</span>" +
                                warn + "</html>"
                        }

                        is NoteItemRow -> {
                            label.icon = null
                            val raw = item.note.body.replace("\n", " ").trim()
                            val when0 = item.note.createdAt.take(16).replace('T', ' ')
                            val author = item.note.author.trim()
                            val prefix = if (author.isEmpty()) "$when0  " else "$when0 · $author  "
                            val fm = label.getFontMetrics(label.font)
                            val avail = (rowTextWidth(table, item.depth) - fm.stringWidth(prefix) - 18)
                                .coerceAtLeast(0)
                            val body = fitText(raw, fm, avail)
                            label.text = "<html><span style='color:gray'>${escape(prefix.trimEnd())}</span>  " +
                                "${escape(body)}</html>"
                        }

                        is StaleHeaderRow -> {
                            label.icon = null
                            label.text = "<html><b style='color:#d05a5a'>\u26A0 无法定位（原代码已变）</b></html>"
                        }

                        is StaleRow -> {
                            label.icon = DotIcon(java.awt.Color(0xD0, 0x5A, 0x5A))
                            val where = "${shortPath(item.relativePath)}:${item.anchor.startLine}"
                            label.text = "<html><b style='color:#d05a5a'>${escape(item.anchor.displayTitle)}</b>" +
                                " <span style='color:gray'>\u2014 ${escape(where)}</span>" +
                                " <span style='color:#d05a5a'>[${item.reason}]</span></html>"
                        }

                        else -> label.text = ""
                    }
                    return label
                }
            }
        }

        val tableMouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastPoint = e.point
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    val row = table.rowAtPoint(e.point)
                    // 右键点在已选中的行上时保留整个选区，只有点到选区外才重新选中
                    if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                    showContextMenu(e)
                    return
                }
                pressPoint = e.point
                pressRow = table.rowAtPoint(e.point)
                val pressedColumn = table.columnAtPoint(e.point)
                val onToggleColumn = pressedColumn in intArrayOf(COL_VISIBLE, COL_LOCK, COL_COMPACT)
                table.suppressCollapse = !e.isControlDown && !e.isShiftDown && !onToggleColumn &&
                    table.selectedRowCount > 1 && pressRow >= 0 && table.isRowSelected(pressRow)
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = pressPoint ?: return
                if (pressRow < 0) return
                if (start.distance(e.point) < 5) return
                // 一旦开始拖动，禁止表格自己扩选；否则经过的每一行都会被"激活"
                table.dragging = true
                val row = table.rowAtPoint(e.point)
                if (row < 0) {
                    table.clearDrop()
                    return
                }
                val rect = table.getCellRect(row, 0, true)
                val ratio = if (rect.height <= 0) 0.5 else (e.y - rect.y).toDouble() / rect.height
                val target = model.rows.getOrNull(row)
                val mode = if (target is GroupRow) {
                    when {
                        ratio < 0.10 -> 0
                        ratio > 0.90 -> 2
                        else -> 1
                    }
                } else {
                    if (ratio > 0.5) 2 else 0
                }
                if (row != table.dropRow || mode != table.dropMode) {
                    table.dropRow = row
                    table.dropMode = mode
                    table.repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val start = pressPoint
                pressPoint = null
                val sourceRow = pressRow
                pressRow = -1
                val wasDragging = table.dragging
                table.dragging = false
                val moved = start != null && start.distance(e.point) >= 5
                val collapse = table.suppressCollapse
                table.suppressCollapse = false
                if (!moved && collapse && sourceRow >= 0 && table.rowAtPoint(e.point) >= 0) {
                    // 只有"普通点击多选中的某行"才塌缩；Ctrl/Shift 加选、开关列点击都保留选区
                    table.setRowSelectionInterval(sourceRow, sourceRow)
                }
                if (start == null || sourceRow < 0 || table.dropRow < 0) {
                    table.clearDrop()
                    if (wasDragging) refresh()   // 恢复被拖动扩选打乱的高亮
                    return
                }
                dropRow(sourceRow, e)
                table.clearDrop()
            }

            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                val item = model.rows.getOrNull(row) ?: return
                when (col) {
                    COL_VISIBLE -> toggleVisible(item)
                    COL_LOCK -> toggleLock(item)
                    COL_COMPACT -> toggleCompact(item)
                    COL_NAME -> {
                        val arrowX = 4 + item.depth * 16 + 20
                        val onArrow = e.x in arrowX..(arrowX + 14)
                        when (item) {
                            is GroupRow -> {
                                if (item.group.id in collapsed || hasChildren(item.group.id)) {
                                    if (onArrow || e.clickCount >= 2) {
                                        toggleCollapse(item.group.id)
                                    }
                                }
                            }

                            is NoteRow -> {
                                val resolvedCount = service.resolvedNotes(item.anchor).size
                                if (onArrow && resolvedCount > 1) {
                                    toggleNotes(item.anchor.id)
                                } else if (e.clickCount >= 2) {
                                    activate(item)
                                    if (resolvedCount > 1) toggleNotes(item.anchor.id)
                                }
                            }

                            is NoteItemRow -> if (e.clickCount >= 2) editNote(item)
                            is StaleRow, is StaleHeaderRow -> Unit
                        }
                    }
                }
            }
        }
        table.addMouseListener(tableMouse)
        table.addMouseMotionListener(tableMouse)
        installUndoKeys()

        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "ridenotes-rename")
        table.actionMap.put("ridenotes-rename", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = renameSelected()
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "ridenotes-delete")
        table.actionMap.put("ridenotes-delete", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = deleteSelected()
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.newGroup")).apply { addActionListener { newGroup() } })
        toolbar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.rename")).apply { addActionListener { renameSelected() } })
        toolbar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.delete")).apply { addActionListener { deleteSelected() } })
        toolbar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.tagLibrary")).apply { addActionListener { TagLibraryDialog.show(project); refresh() } })
        toolbar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.settings")).apply {
            addActionListener {
                if (NotesSettingsDialog.show(project)) AnchorRenderService.get(project).refreshAll()
            }
        })

        // 低频操作收进「更多」，避免工具栏一排七八个按钮
        val moreButton = JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.more") + " \u25BE")
        moreButton.addActionListener {
            val menu = javax.swing.JPopupMenu()
            menu.add(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.refresh")).addActionListener { refresh() }
            menu.add(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.cleanup")).addActionListener { pruneOrphanPlacements() }
            menu.addSeparator()
            menu.addSeparator()
            menu.add("移除所选标题").addActionListener { removeTitles() }
            menu.add("从 IDE 书签导入…").addActionListener { importBookmarks() }
            menu.add("恢复为 IDE 书签…").addActionListener { restoreBookmarks() }
            menu.add("导出 Markdown 到剪贴板").addActionListener { exportMarkdown() }
            menu.add("导出 PR 评论到剪贴板").addActionListener { exportPullRequest() }
            menu.show(moreButton, 0, moreButton.height)
        }
        toolbar.add(moreButton)

        filterField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleRefresh()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleRefresh()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleRefresh()
        })
        tagFilterButton.addActionListener { showTagFilterMenu() }

        val filterRow = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(filterField, BorderLayout.CENTER)
            add(tagFilterButton, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(filterRow, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        table.tableHeader = null
        val scrollPane = JBScrollPane(table)
        scrollPane.setColumnHeaderView(null)
        scrollPane.isOpaque = true
        scrollPane.viewport.isOpaque = true
        scrollPane.viewport.background = com.intellij.util.ui.UIUtil.getPanelBackground()
        add(scrollPane, BorderLayout.CENTER)

        service.addChangeListener {
            dataDirty = true
            scheduleRefresh()
        }
        refresh()
    }

    // ---------------------------------------------------------------- 数据

    fun refresh() {
        watchCurrentDocument()
        if (!uiStateLoaded) {
            val stored = service.index()
            collapsed.addAll(stored.collapsedGroups)
            expandedNotes.addAll(stored.expandedAnchors)
            uiStateLoaded = true
        }
        val selectedId = model.rows.getOrNull(table.selectedRow)?.id
        val index = service.index()

        // 只有数据真的变了才重读磁盘；单纯改过滤条件复用缓存
        if (dataDirty) {
            anchorIndex = AnchorStore.listAll(project)
                .flatMap { shard -> shard.anchors.map { anchor -> anchor.id to (shard.file to anchor) } }
                .toMap()
            dataDirty = false
        }

        // 行号锚定：所有笔记都留在自己的分组里，只在行上标 ⚠
        // ⚠ 在刷新时一次性算好（读锁内），渲染器只读字段，避免 paint 期间做 I/O
        val changedById = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction<Map<String, Boolean>> {
                val result = HashMap<String, Boolean>(anchorIndex.size)
                anchorIndex.values.forEach { (path, anchor) ->
                    val file = fileOf(path)
                    val document = file?.let {
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)
                    }
                    result[anchor.id] = when {
                        file == null -> true
                        document == null -> false
                        else -> com.thousandyearsofwar.ridenotes.model.Anchoring.textChanged(document, anchor)
                    }
                }
                result
            }

        // 有筛选时忽略折叠状态，自动展开命中的分组
        val filtering = isFiltering()

        // schema 2：归属存在锚点自身，按 groupId 分组后与分组树合并
        fun anchorsOf(groupId: String?): List<Pair<String, Anchor>> =
            anchorIndex.values.filter { it.second.groupId == groupId }.sortedWith(
                compareBy({ it.second.orderValue }, { it.second.startLine })
            )

        fun build(groupId: String?, depth: Int): MutableList<Row> {
            val out = mutableListOf<Row>()

            // 组和锚点共用 orderValue，可以任意穿插（schema 3）
            val children = mutableListOf<Triple<Double, Int, Any>>()
            index.groups.filter { it.parentId == groupId }
                .forEach { children.add(Triple(it.orderValue, 0, it)) }
            anchorsOf(groupId).forEach { pair -> children.add(Triple(pair.second.orderValue, 1, pair)) }
            children.sortWith(compareBy({ it.first }, { it.second }))

            children.forEach { child ->
                if (child.second == 0) {
                    val group = child.third as GroupNode
                    val nested = build(group.id, depth + 1)
                    if (nested.isNotEmpty() || !filtering) {
                        out.add(GroupRow(group, depth))
                        if (filtering || group.id !in collapsed) out.addAll(nested)
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val (path, anchor) = child.third as Pair<String, Anchor>
                    if (!matchesFilter(anchor, path)) return@forEach
                    out.add(NoteRow(path, anchor, depth, changedById[anchor.id] ?: false))
                    if (anchor.notes.size > 1 && (filtering || anchor.id in expandedNotes)) {
                        anchor.notes.forEach { note -> out.add(NoteItemRow(path, anchor, note, depth + 1)) }
                    }
                }
            }
            return out
        }

        // 重建前记住整个选区（多选也要保留），重建后按行 id 逐个恢复
        val previousSelection = table.selectedRows.toList()
            .mapNotNull { model.rows.getOrNull(it)?.id }
            .ifEmpty { listOfNotNull(selectedId) }

        val rows = build(null, 0)
        model.replace(rows)

        if (previousSelection.isNotEmpty()) {
            val targets = previousSelection.mapNotNull { id ->
                rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            }
            if (targets.isNotEmpty()) {
                table.selectionModel.clearSelection()
                targets.forEach { table.addRowSelectionInterval(it, it) }
                table.selectionModel.anchorSelectionIndex = targets.first()
                table.selectionModel.leadSelectionIndex = targets.last()
            }
        }
    }

    /** 行号锚定后不再需要逐条判定"过期"，只在行上标 ⚠（见 textChanged）。 */

    /** 按像素宽度截断（二分，放不下就末尾加 …）。 */
    private fun fitText(text: String, fm: java.awt.FontMetrics, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (fm.stringWidth(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fm.stringWidth(text.substring(0, mid) + "\u2026") <= maxWidth) low = mid else high = mid - 1
        }
        return text.substring(0, low) + "\u2026"
    }

    /** 当前行可用于文本的像素宽度。 */
    private fun rowTextWidth(table: JTable, depth: Int): Int {
        val columnWidth = runCatching { table.columnModel.getColumn(COL_NAME).width }.getOrDefault(0)
        if (columnWidth <= 0) return 0
        return (columnWidth - 4 - depth * 16 - 12).coerceAtLeast(0)
    }

    /** 手动清理：删掉索引里指向已消失锚点的归属记录（会确认，不自动执行）。 */
    private fun pruneOrphanPlacements() {
        val count = runCatching { service.countOrphanPlacements() }.getOrElse {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("count orphans failed: ${it.message}")
            return
        }
        if (count <= 0) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "没有无效归属，无需清理")
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "发现 $count 条无效归属（对应的锚点已不存在）。\n\n" +
                "清理后这些条目会被删除，无法恢复。\n" +
                "如果你在别的分支上还有这些笔记，建议先切回去确认。\n\n确定清理吗？",
            "清理无效归属",
            "清理",
            "取消",
            null
        )
        if (answer != Messages.YES) return

        val removed = runCatching { service.pruneOrphanPlacements() }.getOrElse {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("prune failed: ${it.message}")
            0
        }
        com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "已清理 $removed 条无效归属")
        refresh()
    }

    /** 把 IDE 的 F11 书签批量转换成锚点。 */
    private fun importBookmarks() {
        val choice = Messages.showYesNoCancelDialog(
            project,
            "把当前项目里的 IDE 书签（gutter 上的 F11 书签）转换成 Sidecar Notes 锚点。\n\n" +
                "· 每个书签会变成一条锚点，标题取该行代码内容\n" +
                "· 转换后可以选择是否删除原来的书签\n\n" +
                "要同时删除原书签吗？",
            "从 IDE 书签导入",
            "导入并删除原书签",
            "仅导入",
            "取消",
            null
        )
        if (choice == Messages.CANCEL) return
        val deleteAfter = choice == Messages.YES

        val result = runCatching { BookmarkImporter.import(project, deleteAfter) }.getOrElse {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("bookmark import failed: ${it.message}")
            return
        }
        val suffix = if (deleteAfter) "，并删除原书签" else ""
        if (result.created == 0) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, "没有可导入的书签（跳过 ${result.skipped} 个）"
            )
        } else {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, "已导入 ${result.created} 个书签$suffix（跳过 ${result.skipped} 个）"
            )
        }
        AnchorRenderService.get(project).refreshAll()
        refresh()
    }

    /** 从锚点重建 IDE 书签（撤销“导入并删除”）。 */
    private fun restoreBookmarks() {
        val imported = runCatching {
            service.anchorsSnapshot().count { it.createdBy == "bookmark-import" }
        }.getOrDefault(0)
        if (imported == 0) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "没有从书签导入的锚点可恢复")
            return
        }
        val choice = Messages.showYesNoDialog(
            project,
            "将把 $imported 条从书签导入的锚点重新写成 IDE 书签。\n\n" +
                "· 位置按锚点当前所在行恢复\n" +
                "· 描述写回书签\n" +
                "· 分组名沿用锚点所在分组\n\n" +
                "继续吗？",
            "恢复为 IDE 书签",
            "恢复",
            "取消",
            null
        )
        if (choice != Messages.YES) return

        val result = runCatching { BookmarkImporter.restore(project) }.getOrElse {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("bookmark restore failed: ${it.message}")
            return
        }
        com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
            project, "已恢复 ${result.created} 个书签（跳过 ${result.skipped} 个）"
        )
    }

    /** 导出到剪贴板：Markdown 报告 / PR 评论格式。 */
    private fun exportMarkdown() {
        val text = runCatching { com.thousandyearsofwar.ridenotes.api.NotesExporter.markdown(project) }
            .getOrElse {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("export failed: ${it.message}")
                return
            }
        copyToClipboard(text, "已导出 Markdown（${text.length} 字符）到剪贴板")
    }

    private fun exportPullRequest() {
        val text = runCatching { com.thousandyearsofwar.ridenotes.api.NotesExporter.pullRequest(project) }
            .getOrElse {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("export failed: ${it.message}")
                return
            }
        copyToClipboard(text, "已导出 PR 评论格式（${text.length} 字符）到剪贴板")
    }

    private fun copyToClipboard(text: String, message: String) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(java.awt.datatransfer.StringSelection(text))
        com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, message)
    }

    private fun isFiltering(): Boolean = filterField.text.isNotBlank() || filterTags.isNotEmpty()

    /** 过滤：文本匹配标题 / 正文 / 文件路径 / 标签；标签过滤要求命中任一选中标签。 */
    private fun matchesFilter(anchor: Anchor, path: String): Boolean {
        val text = filterField.text.trim()
        if (text.isNotEmpty()) {
            val haystack = buildString {
                append(anchor.title).append(' ').append(path)
                anchor.notes.forEach { note ->
                    append(' ').append(note.body).append(' ').append(note.tags.joinToString(" "))
                }
            }
            if (!haystack.contains(text, ignoreCase = true)) return false
        }
        if (filterTags.isNotEmpty()) {
            val tags = anchor.notes.flatMap { it.tags }.toSet()
            if (tags.intersect(filterTags).isEmpty()) return false
        }
        return true
    }

    private fun updateTagFilterLabel() {
        tagFilterButton.text = if (filterTags.isEmpty()) "标签筛选" else "标签筛选 (${filterTags.size})"
    }

    private fun showTagFilterMenu() {
        val menu = javax.swing.JPopupMenu()
        service.tags().sortedBy { it.name }.forEach { tag ->
            val item = javax.swing.JCheckBoxMenuItem("#${tag.name}", tag.name in filterTags)
            item.addActionListener {
                if (!filterTags.remove(tag.name)) filterTags.add(tag.name)
                updateTagFilterLabel()
                scheduleRefresh()
            }
            menu.add(item)
        }
        if (menu.componentCount == 0) {
            menu.add(javax.swing.JMenuItem("（标签库为空）").apply { isEnabled = false })
        }
        menu.addSeparator()
        menu.add("清除标签筛选").addActionListener {
            filterTags.clear()
            updateTagFilterLabel()
            scheduleRefresh()
        }
        menu.show(tagFilterButton, 0, tagFilterButton.height)
    }

    private var refreshQueued = false

    /** 合并同一轮操作里的多次变更通知，避免一次保存刷新好几遍面板。 */
    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ApplicationManager.getApplication().invokeLater {
            refreshQueued = false
            refresh()
        }
    }

    private fun hasChildren(groupId: String): Boolean {
        val index = service.index()
        return index.groups.any { it.parentId == groupId } ||
            anchorIndex.values.any { it.second.groupId == groupId }
    }

    private fun toggleCollapse(groupId: String) {
        if (!collapsed.remove(groupId)) collapsed.add(groupId)
        persistUiState()
        refresh()
    }

    private fun toggleNotes(anchorId: String) {
        if (!expandedNotes.remove(anchorId)) expandedNotes.add(anchorId)
        persistUiState()
        refresh()
    }

    /** 折叠/展开是 UI 状态，跟索引一起持久化。 */
    private fun persistUiState() {
        val index = service.index()
        index.collapsedGroups = collapsed.toMutableList()
        index.expandedAnchors = expandedNotes.toMutableList()
        service.saveIndex()
    }

    private fun addNoteDialog(row: NoteRow) {
        if (row.anchor.mirrorOf != null) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "这是克隆锚点，请到源锚点追加笔记")
            return
        }
        if (service.effectiveLocked(row.anchor)) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "该笔记已锁定，先解锁再追加")
            return
        }
        val text = Messages.showMultilineInputDialog(project, "笔记内容", "追加笔记", "", null, null) ?: return
        if (text.isBlank()) return
        val file = fileOf(row.relativePath) ?: return
        undoable(file, "追加笔记") { service.addNote(file, row.anchor.id, text) }
        expandedNotes.add(row.anchor.id)
        AnchorRenderService.get(project).refresh(file)
        refresh()
    }

    /** 从标签库里选（不再手写）：按分组列出，勾选即切换。 */
    private fun chooseTag(applied: Set<String>, onPick: (String) -> Unit) {
        val menu = javax.swing.JPopupMenu()
        val byGroup = service.tags().groupBy { it.groupId }

        fun fill(target: javax.swing.JMenu, tags: List<com.thousandyearsofwar.ridenotes.model.TagDef>) {
            tags.sortedBy { it.name }.forEach { tag ->
                val item = javax.swing.JCheckBoxMenuItem("#${tag.name}", tag.name in applied)
                item.addActionListener { onPick(tag.name) }
                target.add(item)
            }
        }

        val ungrouped = byGroup[null].orEmpty()
        if (ungrouped.isNotEmpty()) {
            val sub = javax.swing.JMenu("未分组")
            fill(sub, ungrouped)
            menu.add(sub)
        }
        service.tagGroups().forEach { group ->
            val tags = byGroup[group.id].orEmpty()
            if (tags.isNotEmpty()) {
                val sub = javax.swing.JMenu(group.name)
                fill(sub, tags)
                menu.add(sub)
            }
        }
        if (menu.componentCount == 0) {
            menu.add(javax.swing.JMenuItem("（标签库为空）").apply { isEnabled = false })
        }
        menu.addSeparator()
        menu.add("新建标签…").addActionListener {
            val name = Messages.showInputDialog(project, "标签名（不用打 #）", "新建标签", null, "", null) ?: return@addActionListener
            service.addTag(name)?.let { onPick(it.name) }
        }

        val point = lastPoint ?: java.awt.Point(20, 20)
        menu.show(table, point.x, point.y)
    }

    /** 多选打标签：分组加/删组标签，笔记行作用于其全部笔记，单条笔记行作用于该条。 */
    private fun attachTagToSelection() {
        val groups = selectedGroupRows()
        val noteRows = selectedNoteRows()
        val noteItems = selectedNoteItems()
        if (groups.isEmpty() && noteRows.isEmpty() && noteItems.isEmpty()) return

        val applied = mutableSetOf<String>()
        groups.forEach { applied.addAll(it.group.tags) }
        noteItems.forEach { applied.addAll(it.note.tags) }
        noteRows.forEach { row -> row.anchor.notes.firstOrNull()?.let { applied.addAll(it.tags) } }

        chooseTag(applied) { name ->
            applyToSelection("修改标签") {
                groups.forEach { group ->
                    if (group.group.tags.contains(name)) service.removeTagFromGroup(group.group.id, name)
                    else service.addTagToGroup(group.group.id, name)
                }
                noteRows.forEach { row ->
                    val file = fileOf(row.relativePath) ?: return@forEach
                    row.anchor.notes.forEach { note ->
                        if (note.tags.contains(name)) service.removeTagFromNote(file, row.anchor.id, note.id, name)
                        else service.addTagToNote(file, row.anchor.id, note.id, name)
                    }
                }
                noteItems.forEach { item ->
                    val file = fileOf(item.relativePath) ?: return@forEach
                    if (item.note.tags.contains(name)) service.removeTagFromNote(file, item.anchor.id, item.note.id, name)
                    else service.addTagToNote(file, item.anchor.id, item.note.id, name)
                }
            }
        }
    }

    private fun attachTagToGroup(row: GroupRow) {
        chooseTag(row.group.tags.toSet()) { name ->
            undoable(firstFileInGroup(row.group.id), "修改标签") {
                if (row.group.tags.contains(name)) service.removeTagFromGroup(row.group.id, name)
                else service.addTagToGroup(row.group.id, name)
            }
            refresh()
        }
    }

    private fun attachTagToNote(row: NoteItemRow) {
        chooseTag(row.note.tags.toSet()) { name ->
            val file = fileOf(row.relativePath) ?: return@chooseTag
            undoable(file, "修改标签") {
                if (row.note.tags.contains(name)) service.removeTagFromNote(file, row.anchor.id, row.note.id, name)
                else service.addTagToNote(file, row.anchor.id, row.note.id, name)
            }
            AnchorRenderService.get(project).refresh(file)
            refresh()
        }
    }

    private fun attachTagToAnchor(row: NoteRow) {
        val note = row.anchor.notes.firstOrNull() ?: return
        chooseTag(note.tags.toSet()) { name ->
            val file = fileOf(row.relativePath) ?: return@chooseTag
            undoable(file, "修改标签") {
                if (note.tags.contains(name)) service.removeTagFromNote(file, row.anchor.id, note.id, name)
                else service.addTagToNote(file, row.anchor.id, note.id, name)
            }
            AnchorRenderService.get(project).refresh(file)
            refresh()
        }
    }

    private fun editNote(row: NoteItemRow) {
        if (service.effectiveLocked(row.anchor)) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "该笔记已锁定，先解锁再编辑")
            return
        }
        val text = Messages.showMultilineInputDialog(project, "笔记内容", "编辑笔记", row.note.body, null, null) ?: return
        val file = fileOf(row.relativePath) ?: return
        undoable(file, "编辑笔记") { service.updateNote(file, row.anchor.id, row.note.id, text) }
        AnchorRenderService.get(project).refresh(file)
        refresh()
    }

    private fun deleteNote(row: NoteItemRow) {
        if (service.effectiveLocked(row.anchor)) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "该笔记已锁定，先解锁再删除")
            return
        }
        val file = fileOf(row.relativePath) ?: return
        undoable(file, "删除笔记") { service.deleteNote(file, row.anchor.id, row.note.id) }
        AnchorRenderService.get(project).refresh(file)
        refresh()
    }

    private fun locate(anchorId: String): Pair<String, Anchor>? = anchorIndex[anchorId]

    private fun stateOf(row: Row, column: Int): Boolean = when (row) {
        is GroupRow -> when (column) {
            COL_VISIBLE -> row.group.visible
            COL_LOCK -> row.group.locked
            COL_COMPACT -> row.group.compact
            else -> false
        }

        is NoteRow -> when (column) {
            COL_VISIBLE -> service.effectiveVisible(row.anchor)
            COL_LOCK -> service.effectiveLocked(row.anchor)
            COL_COMPACT -> service.effectiveCompact(row.anchor)
            else -> false
        }

        else -> false
    }

    private fun flagsOf(visible: Boolean, locked: Boolean, compact: Boolean): String = buildString {
        if (!visible) append(" <span style='color:gray'>[隐藏]</span>")
        if (locked) append(" <span style='color:#c08040'>[锁定]</span>")
        if (compact) append(" <span style='color:gray'>[简洁]</span>")
    }


    private fun shortPath(path: String): String {
        val parts = path.split('/')
        return if (parts.size <= 2) path else parts.takeLast(2).joinToString("/")
    }

    /** 锚点下所有笔记的标签，去重后按标签库颜色渲染。 */
    private fun tagChipsOf(names: List<String>): String {
        if (names.isEmpty()) return ""
        val defs = service.tags().associateBy { it.name }
        val chips = names.distinct().joinToString(" ") { name ->
            val color = defs[name]?.let { NotePalette.accent(it.color) }
            val hex = color?.let { String.format("#%02x%02x%02x", it.red, it.green, it.blue) } ?: "#7a7a7a"
            "<span style='color:$hex'>#$name</span>"
        }
        return " $chips"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------------------------------------------------------------- 拖动

    private fun dropRow(sourceRow: Int, e: MouseEvent) {
        val target = model.rows.getOrNull(table.rowAtPoint(e.point)) ?: return
        val mode = table.dropMode

        // 多选时整批拖动；单选时只动按下的那一行
        val dragged = if (table.isRowSelected(sourceRow) && table.selectedRowCount > 1) {
            table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }
        } else {
            listOfNotNull(model.rows.getOrNull(sourceRow))
        }.filter { it is GroupRow || it is NoteRow }
        if (dragged.isEmpty()) return

        val sourceGroupIds = dragged.filterIsInstance<GroupRow>().map { it.group.id }.toSet()
        val sourceAnchorIds = dragged.filterIsInstance<NoteRow>().map { it.anchor.id }.toSet()

        // 目标父级下的混排列表（不含被拖动的项）
        fun siblings(parentId: String?): List<Pair<Double, Any>> {
            val list = mutableListOf<Pair<Double, Any>>()
            service.index().groups
                .filter { it.parentId == parentId && it.id !in sourceGroupIds }
                .forEach { list.add(it.orderValue to it) }
            anchorIndex.values
                .filter { it.second.groupId == parentId && it.second.id !in sourceAnchorIds }
                .forEach { list.add(it.second.orderValue to it.second) }
            return list.sortedBy { it.first }
        }

        // 在第 pos 位插入 n 项时可用的顺序键（夹在相邻两项之间均分）
        fun batchKeys(values: List<Double>, pos: Int, n: Int): List<Double> {
            val before = if (pos > 0) values[pos - 1] else null
            val after = if (pos < values.size) values[pos] else null
            return when {
                before == null && after == null -> (1..n).map { it.toDouble() }
                before == null -> (1..n).map { after!! - (n - it + 1) }
                after == null -> (0 until n).map { before + it + 1.0 }
                else -> (1..n).map { before + (after - before) * it / (n + 1) }
            }
        }

        fun apply(parentId: String?, keys: List<Double>) {
            dragged.forEachIndexed { index, row ->
                val key = keys.getOrElse(index) { keys.lastOrNull() ?: 1.0 }
                when (row) {
                    is GroupRow -> if (!isGroupInside(row.group.id, parentId)) {
                        service.moveGroupTo(row.group.id, parentId, key)
                    }

                    is NoteRow -> service.moveAnchorTo(row.anchor.id, parentId, key)
                    else -> Unit
                }
            }
        }

        when (target) {
            is NoteItemRow, is StaleRow, is StaleHeaderRow -> return

            is GroupRow -> {
                if (mode == 1) {
                    // 拖进分组：整批放到该组子项末尾
                    val list = siblings(target.group.id)
                    val base = list.lastOrNull()?.first ?: 0.0
                    apply(target.group.id, dragged.indices.map { base + it + 1.0 })
                } else {
                    val parentId = target.group.parentId
                    val list = siblings(parentId)
                    val pos = list.indexOfFirst { it.second === target.group }
                    val at = if (pos < 0) list.size else pos + if (mode == 2) 1 else 0
                    apply(parentId, batchKeys(list.map { it.first }, at, dragged.size))
                }
            }

            is NoteRow -> {
                val parentId = target.anchor.groupId
                val list = siblings(parentId)
                val pos = list.indexOfFirst { it.second === target.anchor }
                val at = if (pos < 0) list.size else pos + if (mode == 2) 1 else 0
                apply(parentId, batchKeys(list.map { it.first }, at, dragged.size))
            }
        }

        AnchorRenderService.get(project).refreshAll()
        refresh()
    }

    /** parentId 是否在 groupId 的子树里（把组拖进自己子树会成环）。 */
    private fun isGroupInside(groupId: String, parentId: String?): Boolean {
        var cursor = parentId?.let { service.index().group(it) }
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (cursor.id == groupId) return true
            cursor = cursor.parentId?.let { service.index().group(it) }
        }
        return false
    }
    // ---------------------------------------------------------------- 动作

    private fun selectedRow(): Row? = model.rows.getOrNull(table.selectedRow)

    // ---------------------------------------------------------------- 多选

    private fun selectionRows(): List<Row> = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }

    private fun selectedNoteRows(): List<NoteRow> = selectionRows().filterIsInstance<NoteRow>()

    private fun selectedGroupRows(): List<GroupRow> = selectionRows().filterIsInstance<GroupRow>()

    private fun selectedNoteItems(): List<NoteItemRow> = selectionRows().filterIsInstance<NoteItemRow>()

    /** 把操作套到整个选区上，并注册跨文件的撤销点。 */
    private fun applyToSelection(name: String, action: () -> Unit) {
        val files = selectionRows().mapNotNull { fileOfRow(it) }.distinct()
        if (files.size <= 1) undoable(files.firstOrNull(), name, action) else undoableFiles(files, name, action)
        AnchorRenderService.get(project).refreshAll()
        refresh()
    }

    private fun toggleVisible(row: Row) = toggleVisibleForSelection()
    private fun toggleLock(row: Row) = toggleLockForSelection()
    private fun toggleCompact(row: Row) = toggleCompactForSelection()

    /** 全选区内状态一致时反向切换：有任意一条可见就全部隐藏，否则全部显示。 */
    private fun toggleVisibleForSelection() {
        val rows = selectionRows().filter { it is GroupRow || it is NoteRow }
        if (rows.isEmpty()) return
        val turnOff = rows.all {
            when (it) {
                is GroupRow -> it.group.visible
                is NoteRow -> service.effectiveVisible(it.anchor)
                else -> true
            }
        }
        val groups = selectedGroupRows().map { it.group.id }
        val anchors = selectedNoteRows().map { it.relativePath to it.anchor.id }
        applyToSelection("切换显示") {
            service.updateGroups(groups) { it.visible = !turnOff }
            service.updateAnchors(anchors) { it.visible = !turnOff }
        }
    }

    private fun toggleLockForSelection() {
        val rows = selectionRows().filter { it is GroupRow || it is NoteRow }
        if (rows.isEmpty()) return
        val turnOn = rows.any {
            when (it) {
                is GroupRow -> !it.group.locked
                is NoteRow -> !service.effectiveLocked(it.anchor)
                else -> false
            }
        }
        val groups = selectedGroupRows().map { it.group.id }
        val anchors = selectedNoteRows().map { it.relativePath to it.anchor.id }
        applyToSelection("切换锁定") {
            service.updateGroups(groups) { it.locked = turnOn }
            service.updateAnchors(anchors) { it.locked = turnOn }
        }
    }

    private fun toggleCompactForSelection() {
        val rows = selectionRows().filter { it is GroupRow || it is NoteRow }
        if (rows.isEmpty()) return
        val turnOn = rows.any {
            when (it) {
                is GroupRow -> !it.group.compact
                is NoteRow -> !service.effectiveCompact(it.anchor)
                else -> false
            }
        }
        val groups = selectedGroupRows().map { it.group.id }
        val anchors = selectedNoteRows().map { it.relativePath to it.anchor.id }
        applyToSelection("切换简洁") {
            service.updateGroups(groups) { it.compact = turnOn }
            service.updateAnchors(anchors) { it.compact = turnOn }
        }
    }

    private fun applyColor(row: Row, color: Int) = applyColorToSelection(color)

    private fun applyColorToSelection(color: Int) {
        val groups = selectedGroupRows()
        val anchors = selectedNoteRows()
        if (groups.isEmpty() && anchors.isEmpty()) return
        val locked = groups.any { it.group.locked } || anchors.any { service.effectiveLocked(it.anchor) }
        if (locked) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "选区内有已锁定的项，先解锁再改颜色")
            return
        }
        applyToSelection("修改颜色") {
            service.updateGroups(groups.map { it.group.id }) { it.color = color }
            service.updateAnchors(anchors.map { it.relativePath to it.anchor.id }) { it.colorOverride = color }
        }
    }

    private fun resetColorToGroup() {
        val anchors = selectedNoteRows()
        if (anchors.isEmpty()) return
        applyToSelection("跟随分组颜色") {
            service.updateAnchors(anchors.map { it.relativePath to it.anchor.id }) { it.colorOverride = null }
        }
    }


    /** 面板里也能 Ctrl+Z：显式把 editor 传给 UndoManager，不依赖焦点。 */
    private fun installUndoKeys() {
        fun bind(stroke: KeyStroke, name: String, block: () -> Unit) {
            table.inputMap.put(stroke, name)
            table.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = block()
            })
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK), "ridenotes-undo") {
            performUndo(redo = false)
        }
        bind(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_Z,
                java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK
            ),
            "ridenotes-redo"
        ) { performUndo(redo = true) }
    }

    private var lastUndoFile: com.intellij.openapi.vfs.VirtualFile? = null

    /** 找撤销目标：优先当前选中的编辑器；面板聚焦时它常为 null，退回最近改动的文件。 */
    private fun undoTargetEditor(): com.intellij.openapi.fileEditor.FileEditor? {
        val manager = FileEditorManager.getInstance(project)
        manager.selectedEditor?.let { return it }
        val file = lastUndoFile ?: return null
        return manager.getAllEditors(file)
            .firstOrNull { it is com.intellij.openapi.fileEditor.TextEditor }
    }

    private fun performUndo(redo: Boolean) {
        val manager = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        val fileEditor = undoTargetEditor()

        val available = if (redo) {
            manager.isRedoAvailable(fileEditor) || manager.isRedoAvailable(null)
        } else {
            manager.isUndoAvailable(fileEditor) || manager.isUndoAvailable(null)
        }
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "[RN] undo target=${fileEditor?.file?.name ?: "null"} " +
                "avail(local)=${if (redo) manager.isRedoAvailable(fileEditor) else manager.isUndoAvailable(fileEditor)} " +
                "avail(global)=${if (redo) manager.isRedoAvailable(null) else manager.isUndoAvailable(null)}"
        )
        if (!available) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, if (redo) "没有可重做的操作" else "没有可撤销的操作"
            )
            return
        }

        if (redo) {
            if (manager.isRedoAvailable(fileEditor)) manager.redo(fileEditor) else manager.redo(null)
        } else {
            if (manager.isUndoAvailable(fileEditor)) manager.undo(fileEditor) else manager.undo(null)
        }
        refresh()
    }

    private fun newGroup() {
        val parent = when (val row = selectedRow()) {
            is GroupRow -> row.group.id
            is NoteRow -> row.anchor.groupId
            else -> null
        }
        val title = Messages.showInputDialog(project, "分组名称", "新建分组", null, "新分组", null) ?: return
        service.createGroup(parent, title.ifBlank { "新分组" })
        refresh()
    }

    private fun renameSelected() {
        when (val row = selectedRow()) {
            is GroupRow -> {
                val title = Messages.showInputDialog(project, "分组标题", "重命名", null, row.group.title, null) ?: return
                undoable(fileOfRow(row), "重命名分组") { service.renameGroup(row.group.id, title) }
            }

            is NoteRow -> {
                val title = Messages.showInputDialog(project, "笔记标题", "重命名", null, row.anchor.title, null) ?: return
                undoable(fileOf(row.relativePath), "重命名笔记") {
                    service.updateAnchorTitle(row.relativePath, row.anchor.id, title)
                }
                fileOf(row.relativePath)?.let { AnchorRenderService.get(project).refresh(it) }
            }

            else -> return
        }
        refresh()
    }

    /** 手动触发：按原文搜索一次，找到候选后先问再改。 */
    private fun tryRelocateAt(relativePath: String, anchor: Anchor) {
        val file = fileOf(relativePath) ?: return
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return
        val found = com.thousandyearsofwar.ridenotes.model.Anchoring.searchRelocate(document, anchor)
        if (found == null) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, "没找到相似代码，请用『重新绑定到当前选区』"
            )
            return
        }
        val line = document.getLineNumber(found.startOffset) + 1
        val answer = Messages.showYesNoDialog(
            project,
            "在第 $line 行找到匹配内容，把这条笔记移过去？",
            "尝试重新定位",
            "移动过去",
            "取消",
            null
        )
        if (answer == Messages.YES) {
            service.rebindAnchor(relativePath, anchor.id, document, found)
            AnchorRenderService.get(project).invalidate(file, anchor.id)
            refresh()
        }
    }

    private fun rebindAt(relativePath: String, anchor: Anchor) {
        val targetFile = fileOf(relativePath) ?: return
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor
        val editorFile = editor?.document?.let {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
        }

        if (editor == null || editorFile != targetFile) {
            manager.openFile(targetFile, true)
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, "已打开目标文件：请选中要绑定的代码，再执行一次『重新绑定』"
            )
            return
        }

        val selection = editor.selectionModel
        if (!selection.hasSelection()) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                project, "先在编辑器里选中要绑定的代码，再执行『重新绑定』"
            )
            return
        }

        val range = com.intellij.openapi.util.TextRange(selection.selectionStart, selection.selectionEnd)
        if (service.rebindAnchor(relativePath, anchor.id, editor.document, range)) {
            AnchorRenderService.get(project).invalidate(targetFile, anchor.id)
            refresh()
            val line = editor.document.getLineNumber(range.startOffset) + 1
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "已重新绑定到第 $line 行")
        }
    }

    private fun deleteStale(row: StaleRow) {
        val file = fileOf(row.relativePath) ?: return
        service.removeAnchor(file, row.anchor.id)
        refresh()
    }

    /**
     * 把一次写操作包成可撤销：动作前后各拍一次快照（分片 + 项目索引），
     * undo/redo 时整体还原。file 为 null（找不到归属文件）时退化为不可撤销。
     */
    private fun undoable(file: com.intellij.openapi.vfs.VirtualFile?, name: String, action: () -> Unit) {
        if (file == null) {
            action()
            return
        }
        val relativePath = service.relativePath(file)
        if (relativePath == null) {
            action()
            return
        }
        val beforeShard = service.snapshotShard(relativePath)
        val beforeIndex = service.snapshotIndex()
        action()
        lastUndoFile = file
        val afterShard = service.snapshotShard(relativePath)
        val afterIndex = service.snapshotIndex()
        registerUndo(
            project, file, name,
            undo = {
                service.restore(relativePath, beforeShard, beforeIndex)
                AnchorRenderService.get(project).refreshAll()
                refresh()
            },
            redo = {
                service.restore(relativePath, afterShard, afterIndex)
                AnchorRenderService.get(project).refreshAll()
                refresh()
            }
        )
    }

    /** 某个节点背后可以用作 undo 挂载点的文件。 */
    private fun fileOfRow(row: Row?): com.intellij.openapi.vfs.VirtualFile? = when (row) {
        is NoteRow -> fileOf(row.relativePath)
        is NoteItemRow -> fileOf(row.relativePath)
        is StaleRow -> fileOf(row.relativePath)
        is GroupRow -> firstFileInGroup(row.group.id)
        else -> null
    }

    private fun firstFileInGroup(groupId: String): com.intellij.openapi.vfs.VirtualFile? {
        val index = service.index()
        fun walk(id: String, depth: Int): String? {
            if (depth > 32) return null
            anchorIndex.values.firstOrNull { it.second.groupId == id }?.let { return it.first }
            index.groups.filter { it.parentId == id }.forEach { child -> walk(child.id, depth + 1)?.let { return it } }
            return null
        }
        return walk(groupId, 0)?.let { fileOf(it) }
    }

    /** 多文件撤销：对每个分片各存一份快照，撤销时逐个恢复。 */
    private fun undoableFiles(
        files: Collection<com.intellij.openapi.vfs.VirtualFile>,
        name: String,
        action: () -> Unit
    ) {
        val paths = files.mapNotNull { service.relativePath(it) }.distinct()
        if (paths.isEmpty()) {
            action()
            return
        }
        val beforeShards = paths.associateWith { service.snapshotShard(it) }
        val beforeIndex = service.snapshotIndex()
        action()
        val afterShards = paths.associateWith { service.snapshotShard(it) }
        val afterIndex = service.snapshotIndex()

        registerUndo(
            project, files.first(), name,
            undo = {
                beforeShards.forEach { (path, shard) -> service.restore(path, shard, beforeIndex) }
                AnchorRenderService.get(project).refreshAll()
                refresh()
            },
            redo = {
                afterShards.forEach { (path, shard) -> service.restore(path, shard, afterIndex) }
                AnchorRenderService.get(project).refreshAll()
                refresh()
            }
        )
    }

    /** 多选后批量移除标题。 */
    private fun removeTitles() {
        val rows = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }
        val targets = rows.filterIsInstance<NoteRow>().map { it.relativePath to it.anchor.id }
        if (targets.isEmpty()) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "请先选中要移除标题的笔记")
            return
        }
        val files = targets.map { it.first }.distinct().mapNotNull { fileOf(it) }
        undoableFiles(files, "移除标题") {
            service.clearAnchorTitles(targets)
        }
        AnchorRenderService.get(project).refreshAll()
        refresh()
    }

    /** gutter 图标被点击：展开所在分组、滚动并选中该锚点，必要时把工具窗提到前台。 */
    private fun revealAnchor(anchorId: String) {
        val entry = anchorIndex[anchorId] ?: return

        // 展开祖先分组
        var changed = false
        var cursor = entry.second.groupId?.let { service.index().group(it) }
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (collapsed.remove(cursor.id)) changed = true
            cursor = cursor.parentId?.let { service.index().group(it) }
        }
        if (changed) refresh()

        var index = model.rows.indexOfFirst { it.id == anchorId }
        if (index < 0 && isFiltering()) {
            filterField.text = ""
            filterTags.clear()
            refresh()
            index = model.rows.indexOfFirst { it.id == anchorId }
        }
        if (index < 0) return

        table.setRowSelectionInterval(index, index)
        table.scrollRectToVisible(table.getCellRect(index, 0, true))
        runCatching {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Sidecar Notes")?.activate(null, true)
        }
    }

    /**
     * 把选中的锚点克隆到编辑器当前选区：位置是新的，内容跟随源锚点。
     */
    /** 右键锚点 → 在同一个分组里追加一个克隆节点（不新增代码区间，也不渲染）。 */
    private fun createCloneNode(row: NoteRow) {
        val clone = service.createCloneNode(row.anchor.id)
        if (clone == null) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "创建克隆节点失败")
            return
        }
        dataDirty = true
        refresh()
        com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "已创建克隆节点，内容跟随源锚点")
    }
    /** 把选中的锚点移进指定分组（null = 根级）。 */
    private fun moveSelectionToGroup(groupId: String?) {
        val targets = selectedNoteRows()
        if (targets.isEmpty()) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "请先选中要移动的笔记")
            return
        }
        val pairs = targets.map { it.relativePath to it.anchor.id }
        val files = targets.mapNotNull { fileOf(it.relativePath) }.distinct()
        undoableFiles(files, "移动到分组") { service.moveAnchorsToGroup(pairs, groupId) }
        AnchorRenderService.get(project).refreshAll()
        refresh()
    }

    /** 新建一个分组，并把当前选中的锚点放进去。 */
    private fun groupSelectionIntoNewGroup() {
        val targets = selectedNoteRows()
        if (targets.isEmpty()) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "请先选中要分组的笔记")
            return
        }
        val parent = selectedGroupRows().firstOrNull()?.group?.id
        val title = Messages.showInputDialog(project, "分组名称", "新建分组并放入", null, "新分组", null) ?: return
        val group = service.createGroup(parent, title.ifBlank { "新分组" })
        moveSelectionToGroup(group.id)
    }

    /** 右键菜单里动态生成"移动到分组"子菜单（按层级缩进）。 */
    private fun buildMoveToGroupMenu(): javax.swing.JMenu {
        val moveMenu = javax.swing.JMenu("移动到分组")
        val root = javax.swing.JMenuItem("根级")
        root.addActionListener { moveSelectionToGroup(null) }
        moveMenu.add(root)
        moveMenu.addSeparator()

        fun walk(parentId: String?, depth: Int) {
            service.index().groups.filter { it.parentId == parentId }
                .sortedBy { it.orderValue }
                .forEach { group ->
                    val item = javax.swing.JMenuItem(
                        "   ".repeat(depth) + group.title.ifBlank { "未命名分组" }
                    )
                    item.addActionListener { moveSelectionToGroup(group.id) }
                    moveMenu.add(item)
                    if (depth < 8) walk(group.id, depth + 1)
                }
        }
        walk(null, 0)
        return moveMenu
    }

    private fun deleteSelected() {
        val rows = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }
        if (rows.isEmpty()) return

        val anchorsByFile = linkedMapOf<com.intellij.openapi.vfs.VirtualFile, MutableList<String>>()
        val notesByAnchor = linkedMapOf<Pair<com.intellij.openapi.vfs.VirtualFile, String>, MutableList<String>>()
        val groups = mutableListOf<String>()
        var skippedLocked = 0

        rows.forEach { row ->
            when (row) {
                is GroupRow -> groups.add(row.group.id)

                is NoteRow -> {
                    if (service.effectiveLocked(row.anchor)) {
                        skippedLocked++
                    } else {
                        fileOf(row.relativePath)?.let { file ->
                            anchorsByFile.getOrPut(file) { mutableListOf() }.add(row.anchor.id)
                        }
                    }
                }

                is NoteItemRow -> {
                    if (service.effectiveLocked(row.anchor)) {
                        skippedLocked++
                    } else {
                        fileOf(row.relativePath)?.let { file ->
                            notesByAnchor.getOrPut(file to row.anchor.id) { mutableListOf() }.add(row.note.id)
                        }
                    }
                }

                is StaleRow -> fileOf(row.relativePath)?.let { file ->
                    anchorsByFile.getOrPut(file) { mutableListOf() }.add(row.anchor.id)
                }

                is StaleHeaderRow -> Unit
            }
        }

        // 整批包成一个撤销步、每个文件只写一次盘、只重绘一次
        val undoFile = anchorsByFile.keys.firstOrNull()
            ?: notesByAnchor.keys.firstOrNull()?.first
            ?: groups.firstOrNull()?.let { firstFileInGroup(it) }

        undoable(undoFile, "删除") {
            groups.forEach { service.deleteGroup(it) }
            anchorsByFile.forEach { (file, ids) -> service.removeAnchors(file, ids) }
            notesByAnchor.forEach { (key, ids) -> service.deleteNotes(key.first, key.second, ids) }
        }

        (anchorsByFile.keys + notesByAnchor.keys.map { it.first }).distinct()
            .forEach { AnchorRenderService.get(project).refresh(it) }

        if (skippedLocked > 0) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "有 $skippedLocked 条已锁定，未删除")
        }
        refresh()
    }

    private fun activate(row: Row) {
        val note = row as? NoteRow ?: return
        val file = fileOf(note.relativePath) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
        ApplicationManager.getApplication().invokeLater {
            val offset = note.anchor.startOffset.coerceIn(0, file.length.toInt().coerceAtLeast(0))
            OpenFileDescriptor(project, file, offset).navigate(true)
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = selectedRow() ?: return
        val menu = javax.swing.JPopupMenu()

        if (row is NoteItemRow && table.selectedRowCount == 1) {
            menu.add("编辑笔记").addActionListener { editNote(row) }
            menu.add("添加标签…").addActionListener { attachTagToSelection() }
            menu.add("删除该笔记").addActionListener { deleteNote(row) }
            menu.show(table, e.x, e.y)
            return
        }

        if (table.selectedRowCount == 1 && row is StaleHeaderRow) return

        if (row is StaleRow && table.selectedRowCount == 1) {
            menu.add("尝试重新定位（搜索相似代码）").addActionListener { tryRelocateAt(row.relativePath, row.anchor) }
            menu.add("重新绑定到当前选区").addActionListener { rebindAt(row.relativePath, row.anchor) }
            menu.add("删除该锚点").addActionListener { deleteStale(row) }
            menu.show(table, e.x, e.y)
            return
        }

        fun check(label: String, on: Boolean, action: () -> Unit) {
            val item = javax.swing.JCheckBoxMenuItem(label, on)
            item.addActionListener { action() }
            menu.add(item)
        }

        val rows = selectionRows()
        val multi = rows.size > 1

        check("显示", rows.all { stateOf(it, COL_VISIBLE) }) { toggleVisibleForSelection() }
        check("锁定", rows.all { stateOf(it, COL_LOCK) }) { toggleLockForSelection() }
        check("简洁", rows.all { stateOf(it, COL_COMPACT) }) { toggleCompactForSelection() }

        if (rows.any { it is NoteRow }) {
            menu.addSeparator()
            menu.add(if (multi) "移除所选标题" else "移除标题").addActionListener { removeTitles() }
        }

        val colorMenu = javax.swing.JMenu("颜色")
        if (selectedNoteRows().any { it.anchor.colorOverride != null }) {
            val follow = javax.swing.JMenuItem("跟随分组颜色")
            follow.addActionListener { resetColorToGroup() }
            colorMenu.add(follow)
            colorMenu.addSeparator()
        }
        for (i in 0..7) {
            val item = javax.swing.JMenuItem("颜色 ${i + 1}", DotIcon(NotePalette.accent(i)))
            item.addActionListener { applyColorToSelection(i) }
            colorMenu.add(item)
        }
        menu.add(colorMenu)

        if (rows.any { it is GroupRow || it is NoteRow }) {
            menu.add("添加标签…").addActionListener { attachTagToSelection() }
        }

        if (multi) {
            if (rows.any { it is NoteRow }) {
                menu.addSeparator()
                menu.add(buildMoveToGroupMenu())
                menu.add("新建分组并放入所选…").addActionListener { groupSelectionIntoNewGroup() }
            }
            menu.addSeparator()
            menu.add("删除所选（${rows.size} 项）").addActionListener { deleteSelected() }
            menu.add("新建分组").addActionListener { newGroup() }
            menu.show(table, e.x, e.y)
            return
        }

        if (row is GroupRow) {
            if (service.defaultGroupId() == row.group.id) {
                menu.add("取消默认分组").addActionListener {
                    service.setDefaultGroup(null)
                    refresh()
                }
            } else {
                menu.add("设为默认分组").addActionListener {
                    service.setDefaultGroup(row.group.id)
                    refresh()
                }
            }
        }
        if (row is GroupRow && hasChildren(row.group.id)) {
            check("折叠", row.group.id in collapsed) { toggleCollapse(row.group.id) }
        }
        if (row is NoteRow) {
            menu.addSeparator()
            if (row.changed) {
                menu.add("尝试重新定位（搜索相似代码）").addActionListener { tryRelocateAt(row.relativePath, row.anchor) }
                menu.add("重新绑定到当前选区").addActionListener { rebindAt(row.relativePath, row.anchor) }
            }
            menu.add("追加笔记").addActionListener { addNoteDialog(row) }
            menu.add("创建克隆节点").addActionListener { createCloneNode(row) }
            menu.add(buildMoveToGroupMenu())
            menu.add("添加标签…").addActionListener { attachTagToAnchor(row) }
            if (service.resolvedNotes(row.anchor).size > 1) {                val label = if (row.anchor.id in expandedNotes) "收起笔记" else "展开笔记"
                menu.add(label).addActionListener { toggleNotes(row.anchor.id) }
            }
        }
        menu.addSeparator()
        menu.add("重命名").addActionListener { renameSelected() }
        menu.add("删除").addActionListener { deleteSelected() }
        menu.addSeparator()
        menu.add("新建分组").addActionListener { newGroup() }
        menu.show(table, e.x, e.y)
    }

    private fun fileOf(relativePath: String): com.intellij.openapi.vfs.VirtualFile? {
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$base/$relativePath")
    }
}
