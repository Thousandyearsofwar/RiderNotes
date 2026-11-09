package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.thousandyearsofwar.ridenotes.settings.NotesSettings
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 全局设置：摘要字体、行数上限、宽度上限。
 */
class NotesSettingsDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = NotesSettings.get()

    private val fonts: List<String> = (
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() +
            listOfNotNull(settings.fontFamily.takeIf { it.isNotBlank() })
        ).distinct().sorted()

    private val fontCombo = ComboBox(fonts.toTypedArray()).apply {
        selectedItem = settings.fontFamily.ifBlank { fonts.firstOrNull() ?: "Dialog" }
    }

    private val sizeSpinner = JSpinner(SpinnerNumberModel(settings.fontSize, 8, 24, 1))
    private val widthSpinner = JSpinner(SpinnerNumberModel(settings.maxWidth, 0, 4000, 40))
    private val summaryCheck = javax.swing.JCheckBox("在编辑器中显示摘要块", settings.showSummary)
    private val apiEnabledCheck = javax.swing.JCheckBox("启用本地 HTTP 接口（供脚本 / AI 使用）", settings.apiEnabled)
    private val apiPortSpinner = JSpinner(SpinnerNumberModel(settings.apiPort, 1024, 65535, 1))
    private val apiTokenField = com.intellij.ui.components.JBTextField(settings.apiToken, 24)
    private val libraryField = com.intellij.ui.components.JBTextField(
        com.thousandyearsofwar.ridenotes.settings.NotesProjectSettings.getInstance(project).libraryPath,
        24
    )

    init {
        title = com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val preview = JBLabel("示例：这里是一段笔记摘要文本 ABC 123").apply {
            font = Font(fontCombo.selectedItem as String, Font.PLAIN, sizeSpinner.value as Int)
            border = JBUI.Borders.empty(6, 0)
        }
        fontCombo.addActionListener { refreshPreview(preview) }
        sizeSpinner.addChangeListener { refreshPreview(preview) }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.font")), fontCombo)
            .addLabeledComponent(JBLabel(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.fontSize")), sizeSpinner)
            .addLabeledComponent(JBLabel(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.maxWidth")), widthSpinner)
            .addComponent(summaryCheck)
            .addComponent(preview)
            .addSeparator()
            .addLabeledComponent(JBLabel(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.library")), libraryField)
            .addComponentToRightColumn(JBLabel(com.thousandyearsofwar.ridenotes.RiderNotesBundle.message("settings.libraryHint")))
            .addSeparator()
            .addComponent(apiEnabledCheck)
            .addLabeledComponent(JBLabel("接口端口（本地回环）"), apiPortSpinner)
            .addLabeledComponent(JBLabel("访问令牌"), apiTokenField)
            .addComponentToRightColumn(
                javax.swing.JButton("重新生成令牌").apply {
                    addActionListener { apiTokenField.text = settings.regenerateApiToken() }
                }
            )
            .panel

        return JPanel(BorderLayout()).apply { add(form, BorderLayout.CENTER) }
    }

    private fun refreshPreview(label: JBLabel) {
        label.font = Font(fontCombo.selectedItem as String, Font.PLAIN, sizeSpinner.value as Int)
        label.repaint()
    }

    override fun doOKAction() {
        settings.fontFamily = fontCombo.selectedItem as? String ?: ""
        settings.fontSize = sizeSpinner.value as Int
        settings.maxWidth = widthSpinner.value as Int
        settings.showSummary = summaryCheck.isSelected

        val apiWasEnabled = settings.apiEnabled
        val apiOldPort = settings.apiPort
        settings.apiEnabled = apiEnabledCheck.isSelected
        settings.apiPort = apiPortSpinner.value as Int
        settings.apiToken = apiTokenField.text

        val projectSettings = com.thousandyearsofwar.ridenotes.settings.NotesProjectSettings.getInstance(project)
        val newLibraryPath = libraryField.text.trim().ifBlank { ".ridenotes" }
        val libraryChanged = newLibraryPath != projectSettings.libraryPath
        projectSettings.libraryPath = newLibraryPath

        super.doOKAction()

        if (libraryChanged) {
            // 换库：清缓存 + 重绘编辑器 + 刷新面板
            com.thousandyearsofwar.ridenotes.service.NoteAnchorService.get(project).reloadLibrary()
            refreshToolWindowPanel()
        }

        com.thousandyearsofwar.ridenotes.editor.AnchorRenderService.get(project).refreshAll()

        // 接口开关/端口变了就重启监听
        if (apiWasEnabled != settings.apiEnabled || apiOldPort != settings.apiPort) {
            val api = com.thousandyearsofwar.ridenotes.api.NotesHttpServer.getInstance(project)
            api.stop()
            api.start()
        }
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "settings saved (library='$newLibraryPath', changed=$libraryChanged), editors refreshed"
        )
    }

    private fun refreshToolWindowPanel() {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Sidecar Notes") ?: return
        toolWindow.contentManager.contents.forEach { content ->
            (content.component as? NotesPanel)?.refresh()
        }
    }

    companion object {
        fun show(project: Project): Boolean {
            val dialog = NotesSettingsDialog(project)
            return try {
                dialog.showAndGet()
            } catch (t: Throwable) {
                Messages.showErrorDialog(project, t.message ?: "打开设置失败", "Sidecar Notes")
                false
            }
        }
    }
}
