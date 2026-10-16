package com.notiregx.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.R
import com.notiregx.app.model.WatchdogAction
import com.notiregx.app.model.WatchdogPolicy
import com.notiregx.app.ui.MainActivity

/**
 * Keeps the notification filter running: if Android has disconnected the listener, it asks for a
 * reconnect, and if that doesn't help, it warns the user with a notification.
 */
class Watchdog(private val app: NotiRegXApp) {
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val startedAt = SystemClock.elapsedRealtime()
    private var rebindAt: Long? = null

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.watchdog_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = app.getString(R.string.watchdog_channel_description)
            }
        )
    }

    fun check() {
        val now = SystemClock.elapsedRealtime()
        val action = WatchdogPolicy.decide(
            hasAccess = app.settings.hasNotificationAccess(),
            connected = app.listenerConnected.value,
            sinceStartMs = now - startedAt,
            sinceRebindMs = rebindAt?.let { now - it },
        )
        when (action) {
            WatchdogAction.NOTHING -> Unit
            WatchdogAction.CLEAR -> {
                rebindAt = null
                manager.cancel(NOTIFICATION_ID)
            }
            WatchdogAction.REBIND -> rebind()
            WatchdogAction.WARN -> {
                rebind()
                warn()
            }
        }
    }

    /** Asks Android to reconnect the listener; safe to call any time. */
    fun rebind() {
        rebindAt = SystemClock.elapsedRealtime()
        try {
            NotificationListenerService.requestRebind(ComponentName(app, NotiListenerService::class.java))
        } catch (e: RuntimeException) {
            // Notification access was revoked in the meantime; the app shows that separately.
        }
    }

    private fun warn() {
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.watchdog_title))
            .setContentText(app.getString(R.string.watchdog_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.watchdog_text_long)))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Re-checks every ~15 minutes, even when the app isn't running. Re-arming is harmless. */
    fun schedule() {
        val pending = PendingIntent.getBroadcast(
            app, 1, Intent(app, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        app.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pending,
        )
    }

    private companion object {
        const val CHANNEL_ID = "problems"
        const val NOTIFICATION_ID = 4242
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as NotiRegXApp).watchdog.check()
    }
}
