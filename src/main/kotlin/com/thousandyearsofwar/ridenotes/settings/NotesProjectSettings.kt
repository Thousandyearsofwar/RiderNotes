package com.thousandyearsofwar.ridenotes.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 项目级设置：笔记库目录。存 `.idea/ridenotes.xml`，可随项目提交。
 */
@Service(Service.Level.PROJECT)
@State(name = "RiderNotesProject", storages = [Storage("ridenotes.xml")])
class NotesProjectSettings : PersistentStateComponent<NotesProjectSettings.State> {

    class State(var libraryPath: String = DEFAULT)

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var libraryPath: String
        get() = state.libraryPath.takeIf { it.isNotBlank() } ?: DEFAULT
        set(value) {
            state.libraryPath = value.takeIf { it.isNotBlank() } ?: DEFAULT
        }

    companion object {
        const val DEFAULT = ".ridenotes"

        fun getInstance(project: Project): NotesProjectSettings = project.service()
    }
}
