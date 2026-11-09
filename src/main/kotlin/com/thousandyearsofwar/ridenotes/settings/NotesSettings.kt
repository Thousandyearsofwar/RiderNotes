package com.thousandyearsofwar.ridenotes.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.ui.UIUtil
import java.awt.Font

/**
 * 全局外观设置。放应用级（跟人走，不跟项目走）。
 */
@Service(Service.Level.APP)
@State(name = "RiderNotesSettings", storages = [Storage("riderNotes.xml")])
class NotesSettings : PersistentStateComponent<NotesSettings.State> {

    class State(
        var fontFamily: String = "",
        var fontSize: Int = 11,
        var maxWidth: Int = 0,
        var showSummary: Boolean = true,
        // 本地 HTTP 接口（默认关闭，避免普通用户暴露端口）
        var apiEnabled: Boolean = false,
        var apiPort: Int = 47800,
        var apiToken: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        // 兼容旧版本：老默认值 880 视为"跟随窗口"
        if (state.maxWidth == 880) state.maxWidth = 0
        // 首次运行生成访问令牌
        if (state.apiToken.isBlank()) state.apiToken = regenerateApiToken()
        cachedFont = null
        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "settings loaded: font='${state.fontFamily}' size=${state.fontSize} width=${state.maxWidth}"
        )
    }

    var fontFamily: String
        get() = state.fontFamily
        set(value) {
            state.fontFamily = value
            cachedFont = null
        }

    var fontSize: Int
        get() = state.fontSize
        set(value) {
            state.fontSize = value
            cachedFont = null
        }

    /** 0 = 跟随窗口宽度；>0 才是上限。 */
    var maxWidth: Int
        get() = state.maxWidth
        set(value) {
            state.maxWidth = value.coerceIn(0, 4000)
        }

    /** 给定视口宽度，返回摘要块应该用的宽度。 */
    fun summaryWidth(viewportWidth: Int, indent: Int): Int {
        val available = (viewportWidth - indent - 1).coerceAtLeast(80)
        val cap = state.maxWidth
        return if (cap <= 0) available else cap.coerceAtMost(available)
    }

    /** 关掉后只保留色带 + gutter 图标，不再绘制摘要块。 */
    var showSummary: Boolean
        get() = state.showSummary
        set(value) {
            state.showSummary = value
        }

    var apiEnabled: Boolean
        get() = state.apiEnabled
        set(value) {
            state.apiEnabled = value
        }

    var apiPort: Int
        get() = state.apiPort
        set(value) {
            state.apiPort = value.coerceIn(1024, 65535)
        }

    /** 本地接口的访问令牌；为空表示不校验（仅回环地址）。 */
    var apiToken: String
        get() = state.apiToken
        set(value) {
            state.apiToken = value.trim()
        }

    fun regenerateApiToken(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        state.apiToken = token
        return token
    }

    fun summaryFont(): Font {
        cachedFont?.let { return it }
        val family = state.fontFamily.ifBlank { UIUtil.getLabelFont().family }
        val font = Font(family, Font.PLAIN, state.fontSize.coerceIn(8, 24))
        cachedFont = font
        return font
    }

    companion object {
        private var cachedFont: Font? = null

        fun get(): NotesSettings = ApplicationManager.getApplication().service()

        fun summaryFont(): Font = get().summaryFont()
    }
}
