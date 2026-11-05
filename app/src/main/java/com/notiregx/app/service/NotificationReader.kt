package com.notiregx.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import androidx.core.app.NotificationCompat
import com.notiregx.app.model.ChatMessage
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

    /**
     * The individual messages of a chat-style (MessagingStyle) notification, oldest first, or an
     * empty list for any other notification. Each message's title is its sender, so sender rules
     * and "Pick senders" work per message.
     */
    fun readMessages(sbn: StatusBarNotification): List<ChatMessage> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            ?: return emptyList()
        val fallbackTitle = style.conversationTitle?.toString()
            ?: sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val appName = appName(sbn.packageName)
        val group = style.conversationTitle?.toString()?.takeIf { style.isGroupConversation }
        // Some apps re-stamp every message when they re-post, so ids use sender, text and how many
        // times that same line came before, not the time.
        val occurrences = HashMap<String, Int>()
        return style.messages.map { m ->
            val sender = m.person?.name?.toString()
            val text = m.text?.toString().orEmpty()
            val time = if (m.timestamp > 0) m.timestamp else sbn.postTime
            val line = "$sender|${text.hashCode()}"
            val nth = occurrences.merge(line, 1, Int::plus)
            ChatMessage(
                id = "$line|$nth",
                notification = Notification(
                    id = 0,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = sender ?: fallbackTitle,
                    text = text,
                    postedAt = time,
                    url = URL.find(text)?.value,
                    group = group,
                ),
                // MessagingStyle marks the user's own messages with no sender.
                fromMe = m.person == null,
            )
        }
    }

    /**
     * The picture the chat app attached for [sender]: their avatar in the conversation, or for a
     * one-to-one chat the notification's large icon. Null if there's none.
     */
    fun senderAvatar(sbn: StatusBarNotification, sender: String): Bitmap? = try {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val icon = style?.messages?.lastOrNull { it.person?.name?.toString() == sender }?.person?.icon
        val drawable = icon?.loadDrawable(context)
            ?: sbn.notification.getLargeIcon()?.takeIf { style?.isGroupConversation != true }?.loadDrawable(context)
        drawable?.toBitmap(AVATAR_PX, AVATAR_PX)
    } catch (e: RuntimeException) {
        null
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
        private const val AVATAR_PX = 192
        private val URL = Regex("""https?://[^\s<>"]+""")

        fun isGroupSummary(sbn: StatusBarNotification) =
            sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0
    }
}
