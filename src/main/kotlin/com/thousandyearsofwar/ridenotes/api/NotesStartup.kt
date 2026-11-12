package com.thousandyearsofwar.ridenotes.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 项目打开后启动本地自动化接口。端口固定 [NotesHttpServer.PORT]。
 */
class NotesStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        localizeActions()

        runCatching { NotesHttpServer.getInstance(project).start() }
            .onFailure { com.thousandyearsofwar.ridenotes.util.NoteLog.warn("http api startup failed: ${it.message}") }

        // 后台预取分片：JSON 解析放到池线程，解析结果回到 EDT 塞进缓存。
        // 这样打开大文件时不会在 EDT 上一次性卡一次解析。
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        app.executeOnPooledThread {
            runCatching {
                Thread.sleep(2000)
                val shards = com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project)
                if (shards.isNotEmpty()) {
                    app.invokeLater {
                        runCatching {
                            com.thousandyearsofwar.ridenotes.service.NoteAnchorService.get(project).seedCache(shards)
                        }
                    }
                }
            }.onFailure {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("prefetch failed: ${it.message}")
            }
        }
    }

    /**
     * 描述符里的 `%action.*.text` 在某些安装/加载路径下解析不到（右键菜单里会显示成
     * `%action.create.text` 这种原文），这里在启动时用我们自己的 bundle 把动作文本重设一遍。
     */
    private fun localizeActions() {
        val bundle = com.thousandyearsofwar.ridenotes.RiderNotesBundle
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val keys = listOf(
            "RiderNotes.EditorMenu" to ("action.group.text" to null),
            "RiderNotes.CreateAnchor" to ("action.create.text" to "action.create.description"),
            "RiderNotes.DeleteAnchor" to ("action.delete.text" to "action.delete.description"),
            "RiderNotes.Refresh" to ("action.refresh.text" to "action.refresh.description"),
            "RiderNotes.ToggleCompact" to ("action.compact.text" to null),
            "RiderNotes.ToggleLock" to ("action.lock.text" to null),
            "RiderNotes.Rename" to ("action.rename.text" to "action.rename.description")
        )
        keys.forEach { (id, textAndDescription) ->
            runCatching {
                val action = manager.getAction(id) ?: return@runCatching
                val presentation = action.templatePresentation
                presentation.text = bundle.message(textAndDescription.first)
                textAndDescription.second?.let { presentation.description = bundle.message(it) }
            }.onFailure {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("localize $id failed: ${it.message}")
            }
        }
    }
}
