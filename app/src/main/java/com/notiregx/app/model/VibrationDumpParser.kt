package com.notiregx.app.model

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A vibration as Android's `dumpsys vibrator_manager` reports it. */
data class LoggedVibration(
    val time: Long,
    /** The app responsible: the notification's app for notification vibrations, else the caller. */
    val packageName: String?,
    val uid: Int?,
    val usage: String?,
    val durationMs: Long?,
    val reason: String?,
    val viaNotification: Boolean,
)

/**
 * Parses the vibration history out of `dumpsys vibrator_manager`. The format differs between
 * Android versions and manufacturers, so this is deliberately lenient: every line (plus its
 * more-indented continuation lines) with a timestamp is a record, and known fields are picked
 * out of it wherever they appear.
 */
object VibrationDumpParser {
    private val TIMESTAMP = Regex("""(?:(\d{4})-)?(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")
    private val PKG = Regex("""\b(?:opPkg|pkg|packageName)\s*[=:]\s*([A-Za-z][\w.]*)""")
    private val UID = Regex("""\buid\s*[=:]\s*(\d+)""")
    private val USAGE = Regex("""\busage\s*[=:]\s*(?:USAGE_)?([A-Za-z_]+)""")
    private val DURATION = Regex("""\bdurationMs\s*[=:]\s*(\d+)""")
    private val REASON = Regex("""\breason\s*[=:]\s*(.*?)(?:,\s*\w+\s*[=:]|[}\]]|$)""")
    private val STATUS = Regex("""\bstatus\s*[=:]\s*(\w+)""")
    private val NOTIFICATION = Regex("""Notification \(([A-Za-z][\w.]*)""")

    /** Haptic feedback (keyboard taps, gestures) isn't what anyone means by a vibration. */
    private val IGNORED_USAGES = setOf("TOUCH", "HARDWARE_FEEDBACK", "PHYSICAL_EMULATION")

    fun parse(dump: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): List<LoggedVibration> =
        records(dump).mapNotNull { parseRecord(it, now, zone) }.distinctBy { it.time to it.packageName }

    private fun records(dump: String): List<String> {
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var indent = 0
        for (line in dump.lines()) {
            val lineIndent = line.length - line.trimStart().length
            if (TIMESTAMP.containsMatchIn(line) && (current == null || lineIndent <= indent)) {
                current?.let { out += it.toString() }
                current = StringBuilder(line.trim())
                indent = lineIndent
            } else if (current != null && line.isNotBlank() && lineIndent > indent) {
                current.append(' ').append(line.trim())
            } else {
                current?.let { out += it.toString() }
                current = null
            }
        }
        current?.let { out += it.toString() }
        return out
    }

    private fun parseRecord(record: String, now: Long, zone: TimeZone): LoggedVibration? {
        val status = STATUS.find(record)?.groupValues?.get(1)
        if (status != null && status.startsWith("ignored", ignoreCase = true)) return null
        val usage = USAGE.find(record)?.groupValues?.get(1)?.uppercase()
        if (usage != null && usage in IGNORED_USAGES) return null

        val viaNotification = NOTIFICATION.find(record)
        val pkg = viaNotification?.groupValues?.get(1) ?: PKG.find(record)?.groupValues?.get(1)
        val uid = UID.find(record)?.groupValues?.get(1)?.toIntOrNull()
        if (pkg == null && uid == null) return null

        val time = TIMESTAMP.find(record)?.let { parseTime(it, now, zone) } ?: return null
        return LoggedVibration(
            time = time,
            packageName = pkg,
            uid = uid,
            usage = usage,
            durationMs = DURATION.find(record)?.groupValues?.get(1)?.toLongOrNull(),
            reason = REASON.find(record)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            viaNotification = viaNotification != null,
        )
    }

    private fun parseTime(match: MatchResult, now: Long, zone: TimeZone): Long? {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
        val year = match.groupValues[1].ifEmpty { null }
        val thisYear = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.YEAR)
        return try {
            val time = format.parse("${year ?: thisYear}-${match.groupValues[2]}")?.time ?: return null
            // A year-less timestamp "in the future" is from late last year.
            if (year == null && time > now + 60_000) format.parse("${thisYear - 1}-${match.groupValues[2]}")?.time else time
        } catch (e: ParseException) {
            null
        }
    }
}
