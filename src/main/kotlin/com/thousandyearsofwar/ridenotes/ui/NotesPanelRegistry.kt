package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.project.Project
import java.util.WeakHashMap

/**
 * 面板注册表：动作（AnAction）需要拿到当前项目的面板实例去执行面板内操作。
 */
object NotesPanelRegistry {

    private val panels = WeakHashMap<Project, NotesPanel>()

    fun register(project: Project, panel: NotesPanel) {
        panels[project] = panel
    }

    fun unregister(project: Project) {
        panels.remove(project)
    }

    fun get(project: Project): NotesPanel? = panels[project]
}
