package com.thousandyearsofwar.ridenotes.ui

import com.intellij.util.messages.Topic

/**
 * 编辑器 gutter 图标被点击时广播，面板据此把列表滚动并选中到对应锚点。
 */
object AnchorActivationTopic {

    val TOPIC: Topic<Listener> = Topic.create("SidecarNotes.anchorActivated", Listener::class.java)

    fun interface Listener {
        fun anchorActivated(anchorId: String)
    }
}
