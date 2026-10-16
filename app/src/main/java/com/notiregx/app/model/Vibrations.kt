package com.notiregx.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

enum class VibrationSource {
    /** Estimated: a notification arrived whose settings say it vibrates. */
    NOTIFICATION,

    /** Read from Android's own vibration history; includes vibrations with no notification. */
    SYSTEM,
}

data class VibrationEvent(
    val time: Long,
    val packageName: String,
    val appName: String,
    val source: VibrationSource,
    /** Notification title for estimates; the system's stated reason for logged vibrations. */
    val detail: String,
    val usage: String? = null,
    val durationMs: Long? = null,
) {
    val key: String get() = "$source|$packageName|$time"
}

/**
 * One line of the vibration timeline: a system-logged vibration paired with the notification
 * that explains it (if any), or a notification estimate the system log didn't confirm.
 */
data class VibrationRow(val event: VibrationEvent, val notification: VibrationEvent?) {
    /** A vibration Android logged with no notification behind it. */
    val isGhost: Boolean get() = event.source == VibrationSource.SYSTEM && notification == null
}

class VibrationLog(initial: List<VibrationEvent> = emptyList()) {
    private val _events = MutableStateFlow(initial.sortedByDescending { it.time })
    val events: StateFlow<List<VibrationEvent>> = _events.asStateFlow()

    fun add(new: List<VibrationEvent>) {
        if (new.isEmpty()) return
        _events.update { current ->
            val known = current.mapTo(HashSet()) { it.key }
            val fresh = new.filter { it.key !in known }.distinctBy { it.key }
            if (fresh.isEmpty()) current else (fresh + current).sortedByDescending { it.time }.take(LIMIT)
        }
    }

    fun clear() = _events.update { emptyList() }

    companion object {
        const val LIMIT = 2000

        /** How far apart a logged vibration and a notification can be and still belong together. */
        const val MATCH_WINDOW_MS = 3_000L

        fun timeline(events: List<VibrationEvent>): List<VibrationRow> {
            val estimates = events.filter { it.source == VibrationSource.NOTIFICATION }
            val used = HashSet<VibrationEvent>()
            val rows = events.filter { it.source == VibrationSource.SYSTEM }.map { logged ->
                val match = estimates
                    .filter { it !in used && it.packageName == logged.packageName && abs(it.time - logged.time) <= MATCH_WINDOW_MS }
                    .minByOrNull { abs(it.time - logged.time) }
                match?.let(used::add)
                VibrationRow(logged, match)
            }
            return (rows + estimates.filter { it !in used }.map { VibrationRow(it, null) })
                .sortedByDescending { it.event.time }
        }
    }
}
