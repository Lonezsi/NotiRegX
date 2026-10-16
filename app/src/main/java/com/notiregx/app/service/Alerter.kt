package com.notiregx.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.notiregx.app.R
import com.notiregx.app.model.Notification

/**
 * Alerts on behalf of silenced channels for notifications the rules let through. The original
 * stays (silently) in the shade with its own actions; the alert removes itself shortly after.
 */
class Alerter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var nextId = 1

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(POPUP, "Alerts (pop up)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Let-through notifications from apps NotiRegX silenced that used to pop up"
                },
                NotificationChannel(SOUND, "Alerts (sound only)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Let-through notifications from apps NotiRegX silenced that used to make a sound"
                },
            )
        )
    }

    fun alert(sbn: StatusBarNotification, n: Notification, originalImportance: Int) {
        val channel = if (originalImportance >= NotificationManager.IMPORTANCE_HIGH) POPUP else SOUND
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title.ifEmpty { n.appName })
            .setContentText(n.text)
            .setSubText(n.appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setCategory(sbn.notification.category)
            .setContentIntent(sbn.notification.contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
        appIcon(n.packageName)?.let(builder::setLargeIcon)
        manager.notify(TAG, nextId++, builder.build())
    }

    private fun appIcon(pkg: String) =
        try {
            context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private companion object {
        const val POPUP = "alerts_popup"
        const val SOUND = "alerts_sound"
        const val TAG = "alert"
        const val TIMEOUT_MS = 10_000L
        const val ICON_PX = 128
    }
}
