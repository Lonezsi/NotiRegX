package com.notiregx.app.service

import android.app.NotificationChannel
import android.content.Context
import android.os.Process
import android.service.notification.NotificationListenerService

/**
 * Turns vibration off for an app's notification categories (sound and pop-ups stay), remembering
 * each category's original vibration so it can be restored. Like [ChannelMuter], this needs a
 * companion device association; without one, calls fail and an error message is returned.
 */
class VibrationMuter(private val listener: NotificationListenerService, context: Context) {
    /** "pkg\u0000channelId" -> original vibration pattern ("" for the default pattern). */
    private val prefs = context.getSharedPreferences("vibration_muted", Context.MODE_PRIVATE)

    /** Apps the user asked to mute, so categories they add later get muted too. */
    private val apps = context.getSharedPreferences("vibration_muted_apps", Context.MODE_PRIVATE)
    private val user = Process.myUserHandle()

    val mutedPackages: Set<String> get() = apps.all.keys

    fun isMuted(pkg: String) = apps.contains(pkg)

    fun mute(pkg: String): String? = guard {
        apps.edit().putBoolean(pkg, true).commit()
        muteNewChannels(pkg)
    }

    /** Mutes vibrating categories of a muted app that aren't muted yet (e.g. ones it added since). */
    fun muteNewChannels(pkg: String): String? = guard {
        if (!isMuted(pkg)) return@guard
        for (channel in channels(pkg)) {
            if (!channel.shouldVibrate() || prefs.contains(key(pkg, channel.id))) continue
            prefs.edit().putString(key(pkg, channel.id), channel.vibrationPattern?.joinToString(",") ?: "").commit()
            channel.enableVibration(false)
            listener.updateNotificationChannel(pkg, user, channel)
        }
    }

    fun unmute(pkg: String): String? = guard {
        val byId = channels(pkg).associateBy { it.id }
        for ((k, pattern) in prefs.all) {
            if (k.substringBefore(SEPARATOR) != pkg) continue
            val channel = byId[k.substringAfter(SEPARATOR)]
            // Leave it alone if the user turned vibration back on themselves.
            if (channel != null && !channel.shouldVibrate()) {
                channel.enableVibration(true)
                (pattern as? String)?.takeIf { it.isNotEmpty() }?.split(",")?.mapNotNull { it.toLongOrNull() }
                    ?.let { channel.vibrationPattern = it.toLongArray() }
                listener.updateNotificationChannel(pkg, user, channel)
            }
            prefs.edit().remove(k).commit()
        }
        apps.edit().remove(pkg).commit()
    }

    private fun channels(pkg: String): List<NotificationChannel> =
        try {
            listener.getNotificationChannels(pkg, user)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }

    private fun key(pkg: String, channelId: String) = pkg + SEPARATOR + channelId

    private inline fun guard(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SecurityException) {
            "Android refused to change this app's notification settings. Link a companion device in Settings."
        } catch (e: RuntimeException) {
            e.message ?: e.javaClass.simpleName
        }

    private companion object {
        const val SEPARATOR = "\u0000"
    }
}
