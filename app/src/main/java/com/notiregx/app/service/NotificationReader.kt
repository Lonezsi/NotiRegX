package com.notiregx.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import com.notiregx.app.model.Notification
import java.util.concurrent.ConcurrentHashMap

/** Turns Android's notifications into [Notification]s. */
class NotificationReader(private val context: Context) {
    private val appNames = ConcurrentHashMap<String, String>()

    /** Ongoing and foreground-service notifications (music, navigation, downloads) are never filtered. */
    fun isFilterable(sbn: StatusBarNotification): Boolean =
        sbn.packageName != context.packageName &&
            sbn.notification.flags and (android.app.Notification.FLAG_ONGOING_EVENT or android.app.Notification.FLAG_FOREGROUND_SERVICE) == 0

    fun read(sbn: StatusBarNotification, id: Long): Notification? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
            ?: lines
            ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return Notification(
            id = id,
            packageName = sbn.packageName,
            appName = appName(sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            url = URL.find(text)?.value,
        )
    }

    fun appName(pkg: String): String = appNames.getOrPut(pkg) {
        val pm = context.packageManager
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        private val URL = Regex("""https?://[^\s<>"]+""")

        fun isGroupSummary(sbn: StatusBarNotification) =
            sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0
    }
}
