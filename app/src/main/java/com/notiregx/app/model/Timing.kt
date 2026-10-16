package com.notiregx.app.model

import java.util.Calendar
import java.util.TimeZone

/** Times of day are stored as minutes since midnight. */
fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/**
 * When a rule is active: on [days] (Calendar.DAY_OF_WEEK values, 1 = Sunday) between
 * [startMinute] and [endMinute]. A range that ends before it starts runs past midnight,
 * and counts as belonging to the day it started. Equal start and end means all day.
 */
data class Schedule(
    val days: Set<Int> = ALL_DAYS,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
) {
    fun isActive(time: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val c = Calendar.getInstance(zone).apply { timeInMillis = time }
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val day = c.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (day == Calendar.SUNDAY) Calendar.SATURDAY else day - 1
        return when {
            startMinute == endMinute -> day in days
            startMinute < endMinute -> day in days && minute >= startMinute && minute < endMinute
            else -> (day in days && minute >= startMinute) || (yesterday in days && minute < endMinute)
        }
    }

    val summary: String
        get() {
            val dayText = when (days) {
                ALL_DAYS -> "Every day"
                WEEKDAYS -> "Weekdays"
                WEEKEND -> "Weekends"
                else -> DAY_ORDER.filter { it in days }.joinToString(", ") { DAY_NAMES.getValue(it) }
            }
            return if (startMinute == endMinute) "$dayText, all day"
            else "$dayText, ${formatMinute(startMinute)}–${formatMinute(endMinute)}"
        }

    companion object {
        val ALL_DAYS = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        val WEEKDAYS = (Calendar.MONDAY..Calendar.FRIDAY).toSet()
        val WEEKEND = setOf(Calendar.SATURDAY, Calendar.SUNDAY)

        /** Monday first, for display. */
        val DAY_ORDER = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
        )
        val DAY_NAMES = mapOf(
            Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun",
        )
    }
}

/**
 * Rate limit: the rule only applies once an app has sent more than [count] notifications
 * matching it within [windowMinutes]. The first [count] get through; the rest are caught.
 */
data class Burst(val count: Int = 3, val windowMinutes: Int = 5) {
    val windowMs: Long get() = windowMinutes * 60_000L

    val summary: String get() = "After $count within $windowMinutes min"
}

/** Digest delivery: a bundle with delivery times only shows itself at those times of day. */
object Delivery {
    /** Whether any of [times] (minutes of day) occurred in (from, to]. */
    fun dueBetween(times: List<Int>, from: Long, to: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (times.isEmpty() || to <= from) return false
        return times.any { minute ->
            val next = nextOccurrence(minute, from, zone)
            next <= to
        }
    }

    /** The first moment strictly after [now] when any of [times] occurs, or null if none. */
    fun nextAfter(times: Collection<Int>, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
        times.minOfOrNull { nextOccurrence(it, now, zone) }

    private fun nextOccurrence(minute: Int, after: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
}
