package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimingTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-12 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, minute)
        }.timeInMillis

    @Test
    fun daytimeScheduleCoversItsHoursOnItsDays() {
        val work = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60)
        assertTrue(work.isActive(at(12, 9), utc))
        assertTrue(work.isActive(at(12, 16, 59), utc))
        assertFalse(work.isActive(at(12, 17), utc))
        assertFalse(work.isActive(at(12, 8, 59), utc))
        assertFalse(work.isActive(at(17, 12), utc)) // Saturday
    }

    @Test
    fun overnightScheduleBelongsToTheDayItStarts() {
        val fridayNight = Schedule(setOf(Calendar.FRIDAY), 22 * 60, 7 * 60)
        assertTrue(fridayNight.isActive(at(16, 23), utc)) // Friday 23:00
        assertTrue(fridayNight.isActive(at(17, 6), utc)) // Saturday 06:00
        assertFalse(fridayNight.isActive(at(17, 7), utc))
        assertFalse(fridayNight.isActive(at(15, 23), utc)) // Thursday
    }

    @Test
    fun equalStartAndEndMeansAllDay() {
        val sundays = Schedule(setOf(Calendar.SUNDAY), 0, 0)
        assertTrue(sundays.isActive(at(18, 3), utc))
        assertFalse(sundays.isActive(at(19, 3), utc))
        assertEquals("Sun, all day", sundays.summary)
    }

    @Test
    fun deliveryFindsNextTimeAndDueWindows() {
        val times = listOf(12 * 60, 18 * 60)
        assertEquals(at(12, 18), Delivery.nextAfter(times, at(12, 12), utc)) // exactly 12:00 -> next is 18:00
        assertEquals(at(13, 12), Delivery.nextAfter(times, at(12, 19), utc))
        assertNull(Delivery.nextAfter(emptyList(), at(12, 0), utc))

        assertTrue(Delivery.dueBetween(times, at(12, 11, 50), at(12, 12, 5), utc))
        assertFalse(Delivery.dueBetween(times, at(12, 12, 5), at(12, 17, 0), utc))
        assertTrue(Delivery.dueBetween(times, at(12, 19), at(13, 13), utc)) // across midnight
    }

    private fun n(text: String, time: Long, pkg: String = "com.chat") =
        Notification(time, pkg, pkg, "", text, postedAt = time)

    @Test
    fun scheduledRuleOnlyMatchesInsideItsWindow() {
        val rule = Rule(name = "work", schedule = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60))
        // Schedules use the default zone; build times in it.
        val zone = TimeZone.getDefault()
        val monNoon = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, 12, 12, 0) }.timeInMillis
        val monNight = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, 12, 22, 0) }.timeInMillis
        assertTrue(rule.matches(n("x", monNoon)))
        assertFalse(rule.matches(n("x", monNight)))
    }

    @Test
    fun burstRuleLetsTheFirstFewThroughThenCatches() {
        val m = RuleManager(listOf(Rule(id = "b", name = "burst", type = RuleType.BUNDLE, burst = Burst(count = 2, windowMinutes = 1))))
        val actions = (0 until 4).map { m.process(n("msg $it", 1_000L + it * 1000), maxSaved = 10).action }
        assertEquals(listOf(Action.PASS, Action.PASS, Action.BUNDLE, Action.BUNDLE), actions)

        // After the window passes, the count starts over.
        assertEquals(Action.PASS, m.process(n("later", 200_000L), maxSaved = 10).action)
        // Other apps have their own count.
        assertEquals(Action.PASS, m.process(n("other", 200_500L, pkg = "com.other"), maxSaved = 10).action)
    }

    @Test
    fun hitsAreCountedForEveryFinalMatchIncludingAllow() {
        val m = RuleManager(listOf(Rule(id = "a", name = "allow", type = RuleType.ALLOW)))
        m.process(n("x", 5_000L), maxSaved = 10)
        m.process(n("y", 6_000L), maxSaved = 10)
        assertEquals(2, m.rules.value[0].hitsSince(0))
        assertTrue(m.rules.value[0].history.isEmpty())
    }

    @Test
    fun releaseOnlyAffectsBundlesWithUnseenItems() {
        val m = RuleManager(
            listOf(
                Rule(id = "d", name = "digest", type = RuleType.BUNDLE, deliveryTimes = listOf(720)),
                Rule(id = "e", name = "empty", type = RuleType.BUNDLE, regex = "never", deliveryTimes = listOf(720)),
            )
        )
        m.process(n("x", 1L), maxSaved = 10)
        m.release(setOf("d", "e"))
        assertTrue(m.rules.value[0].bundle.released)
        assertFalse(m.rules.value[1].bundle.released)
        m.markSeen("d")
        assertFalse(m.rules.value[0].bundle.released)
    }

    @Test
    fun importSkipsDuplicatesAndDropsHistory() {
        val existing = Rule(id = "1", name = "ads", regex = "Ad:")
        val m = RuleManager(listOf(existing))
        val added = m.import(
            listOf(
                existing.copy(id = "x"),
                Rule(id = "y", name = "promo", regex = "sale", history = listOf(n("sale", 1L))),
            )
        )
        assertEquals(1, added)
        val imported = m.rules.value.last()
        assertEquals("promo", imported.name)
        assertTrue(imported.history.isEmpty())
        assertTrue(imported.id != "y")
    }

    @Test
    fun moveToRepositions() {
        val m = RuleManager(listOf(Rule(id = "a", name = "a"), Rule(id = "b", name = "b"), Rule(id = "c", name = "c")))
        m.moveTo("a", 2)
        assertEquals(listOf("b", "c", "a"), m.rules.value.map { it.id })
    }
}
