package com.thousandyearsofwar.ridenotes.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.thousandyearsofwar.ridenotes.ui.NotesPanelRegistry

/**
 * 重命名当前选中的笔记/分组。
 *
 * 注册成动作而不是只挂 JTable 输入映射，这样：
 * - 会出现在 Settings → Keymap，用户可以自己改键
 * - 换键位方案（比如 VSCode）后仍然按动作系统解析，不会被编辑器动作抢走
 */
class RenameNoteAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NotesPanelRegistry.get(project)?.requestRename()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && NotesPanelRegistry.get(project) != null
    }
}
