package com.thousandyearsofwar.ridenotes.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** 统一日志入口，前缀 [RN]，方便从 idea.log 里 grep。 */
object NoteLog {
    private val LOG: Logger = Logger.getInstance("RiderNotes")

    fun info(message: String) = LOG.info("[RN] $message")

    fun warn(message: String, t: Throwable? = null) {
        if (t != null) LOG.warn("[RN] $message", t) else LOG.warn("[RN] $message")
    }

    fun notify(project: Project, message: String) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("RiderNotes")
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        } catch (t: Throwable) {
            warn("notify failed: $message", t)
        }
    }
}
