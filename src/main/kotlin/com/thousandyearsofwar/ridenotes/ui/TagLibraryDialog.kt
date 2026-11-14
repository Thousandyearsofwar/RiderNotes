package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.thousandyearsofwar.ridenotes.model.TagDef
import com.thousandyearsofwar.ridenotes.model.TagGroupNode
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** 带落点指示线的树（JTree 有 UI delegate，必须重写 paint 才画得出来）。 */
private class TagTree(model: javax.swing.tree.TreeModel) : JTree(model) {
    var dropRow: Int = -1
    var dropBelow: Boolean = false

    override fun paint(g: java.awt.Graphics) {
        super.paint(g)
        if (dropRow < 0) return
        val bounds = getRowBounds(dropRow) ?: return
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.color = java.awt.Color(0x3D, 0x7E, 0xC7)
            val y = if (dropBelow) bounds.y + bounds.height - 2 else bounds.y
            g2.fillRect(bounds.x, y, width - bounds.x - 4, 2)
        } finally {
            g2.dispose()
        }
    }

    fun clearDrop() {
        if (dropRow >= 0) {
            dropRow = -1
            repaint()
        }
    }
}

/**
 * 标签库：分组（TagGroupNode）→ 标签（TagDef）。数据存 `_project.json`。
 */
class TagLibraryDialog(private val project: Project) : DialogWrapper(project) {

    private val service = NoteAnchorService.get(project)
    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val tree = TagTree(treeModel)

        private object Ungrouped

    init {
        title = com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("tags.title")
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = 24
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): java.awt.Component {
                val label = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode ?: return label
                when (val payload = node.userObject) {
                    is TagGroupNode -> text = "<html><b>${payload.name}</b></html>"
                    is TagDef -> text = "<html>#${payload.name}</html>"
                    Ungrouped -> text = "<html><i>" +
                        com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("tags.defaultGroup") +
                        "</i></html>"
                    else -> text = ""
                }
                return label
            }
        }
        init()
        reload()

        // 拖拽：标签拖进分组 / 组之间排序
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                dragNode = tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                if (dragNode == null) return
                val row = tree.getRowForLocation(e.x, e.y)
                if (row < 0) {
                    tree.clearDrop()
                    return
                }
                val bounds = tree.getRowBounds(row) ?: return
                val below = e.y > bounds.y + bounds.height / 2
                if (row != tree.dropRow || below != tree.dropBelow) {
                    tree.dropRow = row
                    tree.dropBelow = below
                    tree.repaint()
                }
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                val source = dragNode
                dragNode = null
                val below = tree.dropBelow
                tree.clearDrop()
                if (source == null) return
                val targetPath = tree.getPathForLocation(e.x, e.y) ?: return
                val target = targetPath.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (source === target) return
                handleDrop(source.userObject, target.userObject, below)
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) renameSelected()
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)

        tree.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0),
            "ridenotes-tag-rename"
        )
        tree.actionMap.put("ridenotes-tag-rename", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = renameSelected()
        })
    }

    private var dragNode: DefaultMutableTreeNode? = null

    private fun handleDrop(source: Any?, target: Any?, below: Boolean) {
        when (source) {
            is TagDef -> {
                val groupId = when (target) {
                    is TagGroupNode -> target.id
                    Ungrouped -> null
                    is TagDef -> target.groupId
                    else -> return
                }
                service.setTagGroup(source.id, groupId)
                reload()
            }

            is TagGroupNode -> {
                if (target !is TagGroupNode) return
                val groups = service.tagGroups()
                val pos = groups.indexOfFirst { it.id == target.id }
                // 落在上半区 → 插到它前面；下半区 → 插到它后面
                val beforeId = if (below) groups.getOrNull(pos + 1)?.id else target.id
                service.moveTagGroupBefore(source.id, beforeId)
                reload()
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(360, 340)
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        // 新建标签放最前面（最常用）
        bar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("tags.newTag")).apply { addActionListener { newTag() } })
        bar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.newGroup")).apply { addActionListener { newGroup() } })
        bar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.rename")).apply { addActionListener { renameSelected() } })
        bar.add(JButton(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("panel.delete")).apply { addActionListener { deleteSelected() } })
        panel.add(bar, BorderLayout.SOUTH)
        return panel
    }

    private fun reload() {
        val expanded = mutableSetOf<String>()
        for (i in 0 until root.childCount) {
            val node = root.getChildAt(i) as DefaultMutableTreeNode
            if (tree.isExpanded(TreePath(node.path))) expanded.add(nodeKey(node))
        }

        root.removeAllChildren()
        val byGroup = service.tags().groupBy { it.groupId }

        byGroup[null]?.takeIf { it.isNotEmpty() }?.let { tags ->
            val node = DefaultMutableTreeNode(Ungrouped, true)
            tags.sortedBy { it.name }.forEach { node.add(DefaultMutableTreeNode(it, false)) }
            root.add(node)
        }

        service.tagGroups().forEach { group ->
            val tags = byGroup[group.id].orEmpty()
            val node = DefaultMutableTreeNode(group, true)
            tags.sortedBy { it.name }.forEach { node.add(DefaultMutableTreeNode(it, false)) }
            root.add(node)
        }

        treeModel.reload()
        for (i in 0 until root.childCount) {
            val node = root.getChildAt(i) as DefaultMutableTreeNode
            if (nodeKey(node) in expanded || root.childCount <= 3) tree.expandPath(TreePath(node.path))
        }
    }

    private fun nodeKey(node: DefaultMutableTreeNode): String = when (val p = node.userObject) {
        is TagGroupNode -> p.id
        is TagDef -> p.id
        Ungrouped -> "ungrouped"
        else -> ""
    }

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun selectedGroupId(): String? = when (val p = selectedNode()?.userObject) {
        is TagGroupNode -> p.id
        is TagDef -> p.groupId
        else -> null
    }

    private fun newGroup() {
        val name = Messages.showInputDialog(project, "分组名", "新建标签组", null, "", null) ?: return
        service.addTagGroup(name)
        reload()
    }

    private fun newTag() {
        val groupId = selectedGroupId()
        val name = Messages.showInputDialog(project, "标签名（不用打 #）", "新建标签", null, "", null) ?: return
        service.addTag(name, groupId)
        reload()
    }

    private fun renameSelected() {
        when (val p = selectedNode()?.userObject) {
            is TagGroupNode -> {
                val name = Messages.showInputDialog(project, "分组名", "重命名", null, p.name, null) ?: return
                service.renameTagGroup(p.id, name)
            }

            is TagDef -> {
                val name = Messages.showInputDialog(project, "标签名", "重命名", null, p.name, null) ?: return
                service.renameTag(p.id, name)
            }

            else -> return
        }
        reload()
    }

    private fun deleteSelected() {
        when (val p = selectedNode()?.userObject) {
            is TagGroupNode -> service.removeTagGroup(p.id)
            is TagDef -> service.removeTag(p.id)
            else -> return
        }
        reload()
    }

    companion object {
        fun show(project: Project) {
            TagLibraryDialog(project).show()
        }
    }
}
