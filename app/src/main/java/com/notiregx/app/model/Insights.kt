package com.notiregx.app.model

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/** A notification found by [Search], and whose it is if they're a known person. */
data class SearchHit(val notification: Notification, val personId: String?, val deleted: Boolean = false)

/** Full-text search over everything NotiRegX keeps: Recent, rule histories and people's timelines. */
object Search {
    private val MARKS = Regex("\\p{Mn}+")

    /** Lowercase and without accents, so "jossz" finds "Jössz" and "zeno" finds "Zénó". */
    fun normalize(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    fun search(
        query: String,
        sources: List<SearchHit>,
        personId: String? = null,
        packageName: String? = null,
        limit: Int = 300,
    ): List<SearchHit> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return sources.asSequence()
            .filter { personId == null || it.personId == personId }
            .filter { packageName == null || it.notification.packageName == packageName }
            .filter { hit ->
                if (words.isEmpty()) return@filter true
                val n = hit.notification
                val haystack = normalize(listOfNotNull(n.title, n.text, n.appName, n.group).joinToString(" "))
                words.all { it in haystack }
            }
            .distinctBy { Triple(it.notification.packageName, it.notification.postedAt, it.notification.content) }
            .sortedByDescending { it.notification.postedAt }
            .take(limit)
            .toList()
    }
}

/** When someone messages: by hour of day, by weekday, and day by day. */
object Activity {
    /** Messages per hour of the day, 0–23. */
    fun byHour(times: List<Long>, zone: TimeZone = TimeZone.getDefault()): IntArray {
        val out = IntArray(24)
        val c = Calendar.getInstance(zone)
        times.forEach { c.timeInMillis = it; out[c.get(Calendar.HOUR_OF_DAY)]++ }
        return out
    }

    /** Messages per weekday, Monday first. */
    fun byWeekday(times: List<Long>, zone: TimeZone = TimeZone.getDefault()): IntArray {
        val out = IntArray(7)
        val c = Calendar.getInstance(zone)
        times.forEach {
            c.timeInMillis = it
            out[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
        }
        return out
    }

    /** The last [days] days' counts, oldest first, from per-day sender counts. */
    fun daily(senders: Collection<SenderCount>, today: Int, days: Int = 30): IntArray =
        IntArray(days) { i -> senders.sumOf { it.days[today - (days - 1 - i)] ?: 0 } }
}

/**
 * How a friendship's message volume develops: weekly totals over a long stretch, and whether a
 * best friend is fading (the last two weeks well below what's normal for them).
 */
object Friendship {
    /** Below this share of their usual, a friend counts as fading. */
    const val FADING_SHARE = 0.35

    /** Need at least this many messages per two weeks normally, so quiet friends aren't flagged. */
    const val MIN_USUAL = 6.0

    data class Trend(val recent: Int, val usual: Double) {
        val fading: Boolean get() = usual >= MIN_USUAL && recent < usual * FADING_SHARE
    }

    /** Weekly totals for the last [weeks] weeks, oldest first (the last one is the current week). */
    fun weekly(senders: Collection<SenderCount>, today: Int, weeks: Int = 52): IntArray {
        val current = SenderStats.weekOf(today)
        return IntArray(weeks) { i -> senders.sumOf { it.weeks[current - (weeks - 1 - i)] ?: 0 } }
    }

    /**
     * The last 14 days against the person's normal: the average fortnight over the 12 weeks
     * before that. Weeks before NotiRegX started counting are zeros, which only makes it calmer.
     */
    fun trend(senders: Collection<SenderCount>, today: Int): Trend {
        val recent = senders.sumOf { s -> (0 until 14).sumOf { s.days[today - it] ?: 0 } }
        val current = SenderStats.weekOf(today)
        val before = (current - 14..current - 3).sumOf { w -> senders.sumOf { it.weeks[w] ?: 0 } }
        return Trend(recent, before / 12.0 * 2)
    }
}

/** How far back a time chart goes. Short ranges are daily; longer ones weekly. */
enum class ChartRange(val buckets: Int?, val weekly: Boolean) {
    DAYS_30(30, false),
    MONTHS_3(13, true),
    YEAR(52, true),
    /** From the first week anything was counted. */
    ALL(null, true),
}

/**
 * Counts over time for charts: one value per day or week, oldest first, with the epoch day each
 * point starts on (for date labels). Several series (people) line up on the same points.
 */
object TimeSeries {
    class Points(val startDays: List<Int>, val series: List<IntArray>, val weekly: Boolean)

    fun of(groups: List<Collection<SenderCount>>, today: Int, range: ChartRange): Points {
        if (!range.weekly) {
            val n = range.buckets ?: 30
            val days = (0 until n).map { today - (n - 1 - it) }
            return Points(days, groups.map { senders -> IntArray(n) { i -> senders.sumOf { it.days[days[i]] ?: 0 } } }, weekly = false)
        }
        val current = SenderStats.weekOf(today)
        val n = range.buckets ?: run {
            val first = groups.flatten().flatMap { it.weeks.keys }.minOrNull() ?: current
            (current - first + 1).coerceIn(2, SenderStats.KEEP_WEEKS)
        }
        val weeks = (0 until n).map { current - (n - 1 - it) }
        return Points(
            startDays = weeks.map { it * 7 - 3 },
            series = groups.map { senders -> IntArray(n) { i -> senders.sumOf { it.weeks[weeks[i]] ?: 0 } } },
            weekly = true,
        )
    }
}

/** "You haven't heard from Zénó in 14 days." */
object Reminders {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The last time anything arrived from [person], from per-sender counts (0: never). */
    fun lastHeard(person: Person, senders: Collection<SenderCount>): Long {
        val keys = person.aliases.mapTo(HashSet()) { it.key }
        return senders.filter { Alias.keyOf(it.packageName, it.sender) in keys }.maxOfOrNull { it.lastSeen } ?: 0L
    }

    /**
     * People whose silence passed their reminder threshold and who haven't been reminded about
     * this silence yet ([lastReminded]: when each was last reminded). Returns them with the days.
     */
    fun due(people: List<Person>, senders: Collection<SenderCount>, lastReminded: Map<String, Long>, now: Long): List<Pair<Person, Int>> =
        people.mapNotNull { p ->
            val after = p.remindAfterDays ?: return@mapNotNull null
            val last = lastHeard(p, senders)
            if (last == 0L) return@mapNotNull null
            val silentDays = ((now - last) / DAY_MS).toInt()
            val reminded = lastReminded[p.id] ?: 0L
            if (silentDays >= after && reminded < last) p to silentDays else null
        }
}

/** The day in numbers, for the evening recap notification. */
data class Recap(
    val hidden: Int,
    val bundled: Int,
    /** App with the most filtered notifications today, and how many. */
    val topFilteredApp: Pair<String, Int>?,
    /** People (or senders) who sent the most today. */
    val topPeople: List<Pair<String, Long>>,
    val ghosts: Int,
) {
    val isEmpty: Boolean get() = hidden == 0 && bundled == 0 && topPeople.isEmpty() && ghosts == 0

    companion object {
        fun build(
            recent: List<LoggedNotification>,
            senders: Collection<SenderCount>,
            people: List<Person>,
            vibrations: List<VibrationRow>,
            dayStart: Long,
            today: Int,
        ): Recap {
            val todays = recent.filter { it.notification.postedAt >= dayStart }
            val filtered = todays.filter { it.action != Action.PASS }
            val topApp = filtered.groupingBy { it.notification.appName }.eachCount().maxByOrNull { it.value }?.toPair()
            // People count as one; senders who aren't anyone yet count on their own.
            val taken = people.flatMap { it.aliases }.mapTo(HashSet()) { it.key }
            val byPerson = people.map { p ->
                val keys = p.aliases.mapTo(HashSet()) { it.key }
                p.name to senders.filter { Alias.keyOf(it.packageName, it.sender) in keys }.sumOf { (it.days[today] ?: 0).toLong() }
            }
            val bySender = senders.filter { Alias.keyOf(it.packageName, it.sender) !in taken }
                .map { PeopleBook.displayName(it.sender) to (it.days[today] ?: 0).toLong() }
            val top = (byPerson + bySender).filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
            return Recap(
                hidden = filtered.count { it.action == Action.HIDE },
                bundled = filtered.count { it.action == Action.BUNDLE },
                topFilteredApp = topApp,
                topPeople = top,
                ghosts = vibrations.count { it.isGhost && it.event.time >= dayStart },
            )
        }
    }
}
