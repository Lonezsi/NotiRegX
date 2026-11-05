package com.notiregx.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.notiregx.app.R
import com.notiregx.app.model.Notification
import com.notiregx.app.ui.MainActivity

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
                NotificationChannel(POPUP, context.getString(R.string.alert_channel_popup), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.alert_channel_popup_description)
                },
                NotificationChannel(SOUND, context.getString(R.string.alert_channel_sound), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.alert_channel_sound_description)
                },
                NotificationChannel(RESTORED, context.getString(R.string.alert_channel_restored), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.alert_channel_restored_description)
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

    /**
     * Alerts for an "Always alert" rule match that would otherwise stay quiet (Do Not Disturb, or a
     * silenced app). With Do Not Disturb access the channel bypasses DND; channels can't be changed
     * after creation, so that case gets its own channel id.
     */
    fun breakthrough(sbn: StatusBarNotification, n: Notification) {
        val canBypass = manager.isNotificationPolicyAccessGranted
        val id = if (canBypass) BREAKTHROUGH_DND else BREAKTHROUGH
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, context.getString(R.string.alert_channel_always), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.alert_channel_always_description)
                    enableVibration(true)
                    setBypassDnd(canBypass)
                }
            )
        }
        val builder = NotificationCompat.Builder(context, id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title.ifEmpty { n.appName })
            .setContentText(n.text)
            .setSubText(n.appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setCategory(sbn.notification.category ?: NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(sbn.notification.contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS * 3)
        appIcon(n.packageName)?.let(builder::setLargeIcon)
        manager.notify(TAG, nextId++, builder.build())
    }

    /** "Zénó unsent a message: …", with the message NotiRegX kept. */
    fun rescued(n: Notification, personName: String) {
        ensureChannel(RESCUED, R.string.rescue_channel, R.string.rescue_channel_description, NotificationManager.IMPORTANCE_DEFAULT)
        val builder = NotificationCompat.Builder(context, RESCUED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.rescue_title, personName))
            .setContentText(n.text)
            .setSubText(n.appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setAutoCancel(true)
        appIcon(n.packageName)?.let(builder::setLargeIcon)
        manager.notify(RESCUED, nextId++, builder.build())
    }

    /** "You haven't heard from Zénó in 14 days"; tapping opens their page. */
    fun reminder(personId: String, personName: String, days: Int) {
        ensureChannel(REMINDER, R.string.reminder_channel, R.string.reminder_channel_description, NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context, personId.hashCode(),
            Intent(context, MainActivity::class.java).setData(Uri.parse("notiregx://person/$personId"))
                .putExtra(MainActivity.EXTRA_PERSON_ID, personId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.reminder_title, days, personName, days))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(REMINDER, personId.hashCode(), notification)
    }

    /** A best friend has gone quiet compared to normal; tapping opens their page. */
    fun fading(personId: String, personName: String, recent: Int, usual: Int) {
        ensureChannel(REMINDER, R.string.reminder_channel, R.string.reminder_channel_description, NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context, personId.hashCode() + 1,
            Intent(context, MainActivity::class.java).setData(Uri.parse("notiregx://fading/$personId"))
                .putExtra(MainActivity.EXTRA_PERSON_ID, personId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.fading_title, personName))
            .setContentText(context.getString(R.string.fading_text, recent, usual))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.fading_text, recent, usual)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(REMINDER, personId.hashCode() + 1, notification)
    }

    /** The evening summary. */
    fun recap(lines: List<String>) {
        ensureChannel(RECAP, R.string.recap_channel, R.string.recap_channel_description, NotificationManager.IMPORTANCE_LOW)
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, RECAP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.recap_title))
            .setContentText(lines.firstOrNull())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(RECAP, 1, notification)
    }

    private fun ensureChannel(id: String, name: Int, description: Int, importance: Int) {
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, context.getString(name), importance).apply { this.description = context.getString(description) }
        )
    }

    /** Puts a filtered notification back in the shade, with its click action if it's still known. */
    fun restore(n: Notification, contentIntent: PendingIntent?) {
        val builder = NotificationCompat.Builder(context, RESTORED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title.ifEmpty { n.appName })
            .setContentText(n.text)
            .setSubText(context.getString(R.string.alert_restored_subtext, n.appName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setWhen(n.postedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
        val open = contentIntent ?: context.packageManager.getLaunchIntentForPackage(n.packageName)?.let {
            PendingIntent.getActivity(context, n.id.toInt(), it, PendingIntent.FLAG_IMMUTABLE)
        }
        open?.let(builder::setContentIntent)
        appIcon(n.packageName)?.let(builder::setLargeIcon)
        manager.notify(RESTORED_TAG, n.id.toInt(), builder.build())
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
        const val RESTORED = "restored"
        const val RESCUED = "rescued"
        const val REMINDER = "reminder"
        const val RECAP = "recap"
        const val BREAKTHROUGH = "breakthrough"
        const val BREAKTHROUGH_DND = "breakthrough_dnd"
        const val TAG = "alert"
        const val RESTORED_TAG = "restored"
        const val TIMEOUT_MS = 10_000L
        const val ICON_PX = 128
    }
}
