package com.thousandyearsofwar.ridenotes.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 项目打开后启动本地自动化接口。端口固定 [NotesHttpServer.PORT]。
 */
class NotesStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
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
}
