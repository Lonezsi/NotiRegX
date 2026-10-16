package com.notiregx.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.notiregx.app.R
import com.notiregx.app.model.LoggedVibration
import com.notiregx.app.model.VibrationDumpParser
import com.notiregx.app.model.VibrationEvent
import com.notiregx.app.model.VibrationSource
import java.util.concurrent.TimeUnit

/**
 * Reads Android's own vibration history via `dumpsys vibrator_manager`. That needs the DUMP
 * permission, which only adb can grant:
 * `adb shell pm grant com.notiregx.app android.permission.DUMP`.
 */
class SystemVibrationReader(private val context: Context, private val reader: NotificationReader) {
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** The raw dump, kept for the "copy system log" debugging button. Null if unavailable. */
    fun dump(): String? {
        if (!hasPermission()) return null
        for (service in listOf("vibrator_manager", "vibrator")) {
            val out = run("dumpsys", service) ?: continue
            if (out.isNotBlank() && !out.contains("Permission Denial") && !out.startsWith("Can't find service")) return out
        }
        return null
    }

    fun parse(dump: String): List<VibrationEvent> =
        VibrationDumpParser.parse(dump).map { toEvent(it) }

    private fun toEvent(v: LoggedVibration): VibrationEvent {
        val pkg = v.packageName
            ?: v.uid?.let(::packageForUid)
            ?: "uid ${v.uid}"
        val detail = when {
            // Stored marker, not display text: Storage.kt and VibrationDetailSheet compare against it. Keep it English.
            v.viaNotification -> "Notification"
            v.reason != null -> v.reason
            else -> v.usage?.lowercase()?.replace('_', ' ') ?: ""
        }
        return VibrationEvent(
            time = v.time,
            packageName = pkg,
            appName = if (pkg == "android") context.getString(R.string.vibrations_android_system) else reader.appName(pkg),
            source = VibrationSource.SYSTEM,
            detail = detail,
            usage = v.usage,
            durationMs = v.durationMs,
            viaNotification = v.viaNotification,
        )
    }

    private fun packageForUid(uid: Int): String? =
        if (uid == Process.SYSTEM_UID) "android" else context.packageManager.getPackagesForUid(uid)?.firstOrNull()

    private fun run(vararg command: String): String? =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)
            text
        } catch (e: Exception) {
            null
        }
}
