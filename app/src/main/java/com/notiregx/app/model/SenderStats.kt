package com.notiregx.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * How many notifications one sender has sent through one app. The "sender" is the notification
 * title, which for chat apps is the person or conversation.
 */
data class SenderCount(
    val packageName: String,
    val appName: String,
    val sender: String,
    val total: Long,
    /** Count per local day (days since the epoch), for the last [SenderStats.KEEP_DAYS] days. */
    val days: Map<Int, Int>,
    val lastSeen: Long,
    /** Count per week (see [SenderStats.weekOf]) for the last [SenderStats.KEEP_WEEKS] weeks, for long-term graphs. */
    val weeks: Map<Int, Int> = weeksFrom(days),
) {
    val key: String get() = keyOf(packageName, sender)

    fun countSince(firstDay: Int): Long = if (firstDay == Int.MIN_VALUE) total else days.filterKeys { it >= firstDay }.values.sum().toLong()

    companion object {
        fun keyOf(packageName: String, sender: String) = packageName + "\u0000" + sender

        /** Weekly totals from daily counts, for data saved before weeks were kept. */
        fun weeksFrom(days: Map<Int, Int>): Map<Int, Int> =
            days.entries.groupBy { SenderStats.weekOf(it.key) }.mapValues { (_, e) -> e.sumOf { it.value } }
    }
}

/** Running counts of who sends what, kept whatever the rules do with the notifications. */
class SenderStats(initial: List<SenderCount> = emptyList()) {
    private val _senders = MutableStateFlow(initial.associateBy { it.key })
    val senders: StateFlow<Map<String, SenderCount>> = _senders.asStateFlow()

    fun record(n: Notification, zone: TimeZone = TimeZone.getDefault()) {
        val sender = n.title.trim()
        if (sender.isEmpty()) return
        val day = dayOf(n.postedAt, zone)
        _senders.update { all ->
            val key = SenderCount.keyOf(n.packageName, sender)
            val old = all[key]
            val days = (old?.days.orEmpty() + (day to (old?.days?.get(day) ?: 0) + 1)).filterKeys { it > day - KEEP_DAYS }
            val week = weekOf(day)
            val weeks = (old?.weeks.orEmpty() + (week to (old?.weeks?.get(week) ?: 0) + 1)).filterKeys { it > week - KEEP_WEEKS }
            val updated = SenderCount(n.packageName, n.appName, sender, (old?.total ?: 0) + 1, days, maxOf(old?.lastSeen ?: 0, n.postedAt), weeks)
            val next = all + (key to updated)
            if (next.size <= MAX_SENDERS) next
            else next.values.sortedByDescending { it.lastSeen }.take(MAX_SENDERS).associateBy { it.key }
        }
    }

    fun clear() = _senders.update { emptyMap() }

    /** Seeds the counts from notifications seen before counting started (each counted once). */
    fun backfill(notifications: List<Notification>, zone: TimeZone = TimeZone.getDefault()) =
        notifications.distinctBy { it.id }.sortedBy { it.postedAt }.forEach { record(it, zone) }

    companion object {
        const val KEEP_DAYS = 90
        const val KEEP_WEEKS = 104

        /** Week number (weeks since the epoch, starting on Mondays) of a [dayOf] day. */
        fun weekOf(day: Int): Int = Math.floorDiv(day + 3, 7)
        private const val MAX_SENDERS = 3000

        fun dayOf(time: Long, zone: TimeZone = TimeZone.getDefault()): Int =
            TimeUnit.MILLISECONDS.toDays(time + zone.getOffset(time)).toInt()

        /** Senders ranked by how much they sent since [firstDay] (Int.MIN_VALUE for all time). */
        fun ranked(senders: Collection<SenderCount>, firstDay: Int, packageName: String? = null): List<Pair<SenderCount, Long>> =
            senders.asSequence()
                .filter { packageName == null || it.packageName == packageName }
                .map { it to it.countSince(firstDay) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .toList()
    }
}

/** Builds and reads the title pattern for "messages from these people". */
object PeoplePattern {
    fun build(names: Collection<String>): String =
        if (names.isEmpty()) "" else "^(?:" + names.sorted().joinToString("|") { Rule.escape(it) } + ")$"

    /** The names in a pattern made by [build], or null if it's some other pattern. */
    fun parse(regex: String): Set<String>? {
        if (!regex.startsWith("^(?:") || !regex.endsWith(")$")) return null
        val body = regex.removePrefix("^(?:").removeSuffix(")$")
        val names = LinkedHashSet<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { current.append(body[i + 1]); i++ }
                c == '|' -> { names += current.toString(); current.clear() }
                c in "^$.?*+()[]{}" -> return null // not a plain list of names
                else -> current.append(c)
            }
            i++
        }
        names += current.toString()
        return names.filter { it.isNotEmpty() }.toSet()
    }
}
