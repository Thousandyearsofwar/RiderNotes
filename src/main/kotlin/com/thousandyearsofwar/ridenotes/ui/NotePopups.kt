package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.thousandyearsofwar.ridenotes.model.Anchor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * 笔记编辑框。
 *
 * 只读浮层已经去掉了：看笔记靠编辑器里的内联摘要，编辑靠这个"盖在摘要块上"的输入框，
 * 管理（列表 / 跳转 / 删除）靠 Notes 工具窗。
 */
object NotePopups {

    private var active: JBPopup? = null

    fun showEditor(
        project: Project,
        editor: Editor,
        file: VirtualFile,
        anchor: Anchor,
        initial: String,
        onSave: (String) -> Unit,
        onCancel: () -> Unit = {},
        at: RelativePoint? = null,
        size: Dimension? = null,
        backgroundColor: Color? = null
    ) {
        val inPlace = size != null
        val area = JBTextArea(initial, if (inPlace) 3 else 6, 44)
        area.lineWrap = true
        area.wrapStyleWord = true
        if (backgroundColor != null) {
            area.background = backgroundColor
            area.isOpaque = true
        }

        val panel = JPanel(BorderLayout(0, if (inPlace) 0 else 6))
        panel.border = if (inPlace) JBUI.Borders.empty(2) else JBUI.Borders.empty(12)
        if (backgroundColor != null) {
            panel.background = backgroundColor
            panel.isOpaque = true
        }
        val scroll = JBScrollPane(area)
        if (backgroundColor != null) {
            scroll.background = backgroundColor
            scroll.viewport.background = backgroundColor
            scroll.isOpaque = true
            scroll.border = JBUI.Borders.empty()
        }
        panel.add(scroll, BorderLayout.CENTER)

        if (!inPlace) {
            panel.add(
                JBLabel("<html><small>Ctrl+Enter 保存　Esc 取消</small></html>"),
                BorderLayout.SOUTH
            )
        }
        if (size != null) panel.preferredSize = size

        var committed = false
        val builder = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, area)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelKeyEnabled(true)
            .setCancelOnClickOutside(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelCallback {
                if (!committed) runCatching { onCancel() }
                true
            }
        val popup = builder.createPopup()

        area.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "ridenotes-save"
        )
        area.actionMap.put("ridenotes-save", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                committed = true
                runCatching { onSave(area.text) }
                popup.cancel()
            }
        })

        active?.cancel()
        active = popup
        val point = at ?: JBPopupFactory.getInstance().guessBestPopupLocation(editor)
        popup.show(point)
        area.requestFocusInWindow()
    }
}
