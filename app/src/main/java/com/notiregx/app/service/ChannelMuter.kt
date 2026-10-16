package com.notiregx.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.service.notification.NotificationListenerService

/**
 * Silences other apps' notification channels so filtered notifications never buzz or pop up,
 * remembering each channel's original importance so it can be restored.
 *
 * Android only lets a notification listener change other apps' channels when the app has a
 * companion device association; without one every call here fails with a SecurityException,
 * reported back as an error message.
 */
class ChannelMuter(private val listener: NotificationListenerService, context: Context) {
    private val prefs = context.getSharedPreferences("muted_channels", Context.MODE_PRIVATE)
    private val user = Process.myUserHandle()

    private data class Entry(val pkg: String, val channelId: String, val original: Int, val learned: Boolean)

    val mutedCount: Int get() = prefs.all.size

    /** Importance the channel had before we silenced it, or null if we didn't silence it. */
    fun originalImportance(pkg: String, channelId: String): Int? =
        prefs.getString(key(pkg, channelId), null)?.let { parse(key(pkg, channelId), it)?.original }

    /**
     * Silences every alerting channel of [packages], keeps channels learned from any-app rules
     * while [keepLearned], and restores everything else. Returns an error message, or null.
     */
    fun sync(packages: Set<String>, keepLearned: Boolean): String? = guard {
        for (e in entries()) {
            if (e.pkg !in packages && !(e.learned && keepLearned)) restore(e)
        }
        packages.forEach(::mutePackage)
    }

    /** Silences one channel that an any-app rule caught something from. */
    fun learn(pkg: String, channelId: String): String? = guard {
        if (originalImportance(pkg, channelId) == null) {
            channels(pkg).firstOrNull { it.id == channelId }?.let { mute(pkg, it, learned = true) }
        }
    }

    /** Silences any alerting channels [pkg] added since the last sync. */
    fun mutePackage(pkg: String): String? = guard {
        for (channel in channels(pkg)) {
            if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
                mute(pkg, channel, learned = originalEntry(pkg, channel.id)?.learned ?: false)
            }
        }
    }

    fun restoreAll(): String? = guard { entries().forEach(::restore) }

    private fun mute(pkg: String, channel: NotificationChannel, learned: Boolean) {
        // Keep the first recorded importance if the user turned the channel back up since.
        val original = originalEntry(pkg, channel.id)?.original ?: channel.importance
        prefs.edit().putString(key(pkg, channel.id), "$original,$learned").commit()
        channel.importance = NotificationManager.IMPORTANCE_LOW
        listener.updateNotificationChannel(pkg, user, channel)
    }

    private fun restore(e: Entry) {
        try {
            val channel = channels(e.pkg).firstOrNull { it.id == e.channelId }
            // Leave it alone if the user has changed it themselves since.
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_LOW) {
                channel.importance = e.original
                listener.updateNotificationChannel(e.pkg, user, channel)
            }
        } catch (ex: IllegalArgumentException) {
            // App uninstalled; nothing to restore.
        }
        prefs.edit().remove(key(e.pkg, e.channelId)).commit()
    }

    private fun channels(pkg: String): List<NotificationChannel> =
        try {
            listener.getNotificationChannels(pkg, user)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }

    private fun originalEntry(pkg: String, channelId: String) =
        prefs.getString(key(pkg, channelId), null)?.let { parse(key(pkg, channelId), it) }

    private fun entries(): List<Entry> = prefs.all.mapNotNull { (k, v) -> parse(k, v as? String ?: return@mapNotNull null) }

    private fun parse(key: String, value: String): Entry? {
        val pkg = key.substringBefore(SEPARATOR)
        val channelId = key.substringAfter(SEPARATOR)
        val (original, learned) = value.split(",").takeIf { it.size == 2 } ?: return null
        return Entry(pkg, channelId, original.toIntOrNull() ?: return null, learned.toBoolean())
    }

    private fun key(pkg: String, channelId: String) = pkg + SEPARATOR + channelId

    private inline fun guard(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SecurityException) {
            "Android refused to change channels. Link a companion device again."
        } catch (e: RuntimeException) {
            e.message ?: e.javaClass.simpleName
        }

    private companion object {
        const val SEPARATOR = "\u0000"
    }
}
