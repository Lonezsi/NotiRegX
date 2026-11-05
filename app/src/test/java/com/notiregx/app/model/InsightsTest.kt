package com.notiregx.app.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class InsightsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 60 * 60 * 1000L
    private val msg = "com.facebook.orca"

    private fun n(text: String, title: String = "Zénó", group: String? = null, personId: String? = null, time: Long = 0) =
        Notification(time, msg, "Messenger", title, text, postedAt = time, group = group, personId = personId)

    @Test
    fun searchIgnoresAccentsAndCaseAndFilters() {
        val sources = listOf(
            SearchHit(n("Jössz ma este?", time = 3), "z"),
            SearchHit(n("Szia", time = 2), "z"),
            SearchHit(n("Jössz ma este?", title = "Anna", time = 1), null),
        )
        assertEquals(2, Search.search("jossz", sources).size)
        assertEquals(listOf(3L), Search.search("JÖSSZ", sources, personId = "z").map { it.notification.postedAt })
        assertEquals(1, Search.search("zeno szia", sources).size) // all words, title included
        assertTrue(Search.search("", sources, packageName = "other").isEmpty())
    }

    @Test
    fun activityCountsHoursAndWeekdays() {
        val monday9 = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 2, 9, 30) }.timeInMillis
        val times = listOf(monday9, monday9 + 60_000, monday9 + day + 3_600_000) // Mon 9:30 twice, Tue 10:30
        assertEquals(2, Activity.byHour(times, utc)[9])
        assertEquals(1, Activity.byHour(times, utc)[10])
        assertArrayEquals(intArrayOf(2, 1, 0, 0, 0, 0, 0), Activity.byWeekday(times, utc))
    }

    private fun sender(days: Map<Int, Int>, lastSeen: Long = 0) = SenderCount(msg, "Messenger", "Zénó", days.values.sum().toLong(), days, lastSeen)

    @Test
    fun remindersFireOncePerSilence() {
        val zeno = Person(id = "z", name = "Zénó", aliases = listOf(Alias(msg, "Messenger", "Zénó")), remindAfterDays = 7)
        val now = 100 * day
        val s = listOf(sender(mapOf(90 to 1), lastSeen = 90 * day))
        assertEquals(listOf("z" to 10), Reminders.due(listOf(zeno), s, emptyMap(), now).map { it.first.id to it.second })
        assertTrue(Reminders.due(listOf(zeno), s, mapOf("z" to now), now + day).isEmpty()) // already reminded
        assertTrue(Reminders.due(listOf(zeno.copy(remindAfterDays = 14)), s, emptyMap(), now).isEmpty())
    }

    @Test
    fun friendshipFadesWhenTheLastTwoWeeksAreFarBelowUsual() {
        val today = 1000
        // 10 messages a week for the 12 weeks before the last two, then 1 in the last 14 days.
        val days = HashMap<Int, Int>()
        for (d in today - 98 until today - 14) days[d] = if (d % 7 == 0) 10 else 0
        days[today - 2] = 1
        val s = SenderCount(msg, "Messenger", "Zénó", 0, days.filterKeys { it > today - 90 }, 0, SenderCount.weeksFrom(days))
        val trend = Friendship.trend(listOf(s), today)
        assertEquals(1, trend.recent)
        assertTrue(trend.usual >= 15)
        assertTrue(trend.fading)

        // Same history but still chatting normally: not fading.
        days[today - 3] = 15
        val active = SenderCount(msg, "Messenger", "Zénó", 0, days.filterKeys { it > today - 90 }, 0, SenderCount.weeksFrom(days))
        assertFalse(Friendship.trend(listOf(active), today).fading)

        // Someone who barely messages anyway isn't flagged.
        val quiet = sender(mapOf(today - 50 to 2))
        assertFalse(Friendship.trend(listOf(quiet), today).fading)
    }

    @Test
    fun weeklySeriesEndsWithTheCurrentWeek() {
        val today = SenderStats.dayOf(System.currentTimeMillis())
        val s = sender(mapOf(today to 3, today - 7 to 2))
        val weekly = Friendship.weekly(listOf(s), today, weeks = 4)
        assertEquals(3, weekly.last())
        assertEquals(5, weekly.sum())
    }

    @Test
    fun personAndChatScopeRules() {
        val fromZeno = Rule(name = "z", personId = "z")
        assertTrue(fromZeno.matches(n("hi", personId = "z")))
        assertFalse(fromZeno.matches(n("hi", personId = "a")))

        val groupsOnly = Rule(name = "g", chatScope = ChatScope.GROUP)
        val directOnly = Rule(name = "d", chatScope = ChatScope.DIRECT)
        assertTrue(groupsOnly.matches(n("hi", group = "Kutyák")))
        assertFalse(groupsOnly.matches(n("hi")))
        assertTrue(directOnly.matches(n("hi")))
        assertFalse(directOnly.matches(n("hi", group = "Kutyák")))

        val hideGroup = Rule(name = "k", regex = "^Kutyák$", field = MatchField.GROUP)
        assertTrue(hideGroup.matches(n("hi", group = "Kutyák")))
        assertFalse(hideGroup.matches(n("Kutyák", title = "Kutyák")))
    }

    @Test
    fun unsentMessagesAreTheOnesMissingFromTheMiddle() {
        val tracker = ConversationTracker()
        fun msg(id: String, text: String) = ChatMessage(id, n(text), fromMe = false)
        val a = msg("1", "first"); val b = msg("2", "oops"); val c = msg("3", "third")
        tracker.update("k", listOf(a, b, c)).fresh.forEach { tracker.record("k", it.id, Action.PASS, it.notification) }

        val update = tracker.update("k", listOf(a, c)) // "oops" vanished, later message still there
        assertEquals(listOf("oops"), update.deleted.map { it.text })

        // Old messages dropping off the start are just the app trimming, not unsends.
        assertTrue(tracker.update("k", listOf(c)).deleted.isEmpty())
    }

    @Test
    fun peopleBookMarksUnsentMessages() {
        val book = PeopleBook()
        val zeno = book.create("Zénó", listOf(Alias(msg, "Messenger", "Zénó")))
        book.record(n("oops"))
        assertEquals(zeno.id, book.markDeleted(n("oops"))?.id)
        assertTrue(book.log.value.single().deleted)
    }

    @Test
    fun recapCountsTodayOnly() {
        val today = 100
        val dayStart = today * day
        val recent = listOf(
            LoggedNotification(n("x", time = dayStart + 10), Action.HIDE, "r", true),
            LoggedNotification(n("y", time = dayStart + 20), Action.BUNDLE, "r", true),
            LoggedNotification(n("z", time = dayStart - 10), Action.HIDE, "r", true), // yesterday
        )
        val senders = listOf(sender(mapOf(today to 5)))
        val recap = Recap.build(recent, senders, emptyList(), emptyList(), dayStart, today)
        assertEquals(1, recap.hidden)
        assertEquals(1, recap.bundled)
        assertEquals("Messenger" to 2, recap.topFilteredApp)
        assertEquals(listOf("Zénó" to 5L), recap.topPeople)
    }
}
