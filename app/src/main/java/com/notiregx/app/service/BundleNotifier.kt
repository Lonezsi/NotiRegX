package com.notiregx.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.notiregx.app.R
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType
import com.notiregx.app.ui.MainActivity

/** Shows one notification per BUNDLE rule that has unseen notifications. */
class BundleNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** What we last posted per rule id, so unchanged bundles aren't re-posted. */
    private val posted = HashMap<String, List<Long>>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bundles", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One notification per bundle rule, with its unseen count"
            }
        )
    }

    fun sync(rules: List<Rule>) {
        val live = rules.filter { it.type == RuleType.BUNDLE && it.bundle.count > 0 }.associateBy { it.id }
        (posted.keys - live.keys).forEach { manager.cancel(it, NOTIFICATION_ID) }
        posted.keys.retainAll(live.keys)
        for (rule in live.values) {
            if (posted[rule.id] == rule.bundle.unseen) continue
            post(rule)
            posted[rule.id] = rule.bundle.unseen
        }
    }

    private fun post(rule: Rule) {
        val unseen = rule.unseenNotifications()
        val count = rule.bundle.count
        val style = NotificationCompat.InboxStyle()
        unseen.take(PREVIEW_LINES).forEach { style.addLine("${it.appName}: ${it.title.ifEmpty { it.text }}") }
        if (count > PREVIEW_LINES) style.setSummaryText("+${count - PREVIEW_LINES} more")

        val uri = Uri.parse("notiregx://bundle/${rule.id}")
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setData(uri)
                .putExtra(MainActivity.EXTRA_RULE_ID, rule.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, BundleDismissReceiver::class.java).setData(uri)
                .putExtra(MainActivity.EXTRA_RULE_ID, rule.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(rule.name)
            .setContentText(if (count == 1) "1 unseen notification" else "$count unseen notifications")
            .setNumber(count)
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .setAutoCancel(true)
            .build()
        manager.notify(rule.id, NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "bundles"
        const val NOTIFICATION_ID = 1
        const val PREVIEW_LINES = 6
    }
}
