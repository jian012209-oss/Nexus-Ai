package com.jarvis.ai

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Reads notifications ONLY after you grant Notification access,
 * and only when you ask ("read my notifications") or turn on announcements.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        var instance: NotifListener? = null
            private set

        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        JarvisCore.init(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun describe(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val app = appLabel(sbn.packageName)
        return listOf(app, title, text).filter { it.isNotEmpty() }.distinct().joinToString(": ").take(160)
    }

    private fun relevant(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_SYSTEM) return false
        val text = (n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() + " " +
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).lowercase()
        if (text.contains("chat heads") || text.contains("is running") || text.contains("running in the background")) return false
        return true
    }

    fun summaries(): List<String> = try {
        activeNotifications.filter { relevant(it) }.sortedByDescending { it.postTime }
            .mapNotNull { describe(it) }.take(5)
    } catch (_: Exception) { emptyList() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.announceNotifications || !relevant(sbn)) return
        val d = describe(sbn) ?: return
        JarvisCore.announce("New notification. $d")
    }
}
