package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LearningTest {
    private var nextId = 1L
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 60 * 60 * 1000L

    private fun n(title: String, text: String = "", pkg: String = "com.insta", time: Long = 0) =
        Notification(nextId++, pkg, "Insta", title, text, postedAt = time)

    private fun swiped(title: String, pkg: String = "com.insta") = Interaction(n(title, pkg = pkg), Outcome.DISMISSED, 0)
    private fun opened(title: String, pkg: String = "com.insta") = Interaction(n(title, pkg = pkg), Outcome.OPENED, 0)

    @Test
    fun suggestsAPatternThatIsAlwaysSwipedAway() {
        val events = listOf("Anna", "Bob", "Cy", "Dora", "Ed", "Fay").map { swiped("$it liked your photo") } +
            listOf(opened("Mom sent you a message"), opened("Dad sent you a message"))
        val advice = DismissalAdvisor.advise(events, rules = emptyList())
        val top = advice.first()
        assertEquals(6, top.dismissed)
        assertEquals(0, top.opened)
        assertEquals("Title like “… liked your photo”", top.suggestion.label)
        val rule = Rule(name = "x", regex = top.suggestion.regex, field = top.suggestion.field)
        assertTrue(rule.matches(n("Gus liked your photo")))
        assertFalse(rule.matches(n("Mom sent you a message")))
    }

    @Test
    fun staysQuietWhenThingsAreOpenedOrTooFew() {
        val few = (1..4).map { swiped("Promo $it") }
        assertTrue(DismissalAdvisor.advise(few, emptyList()).isEmpty())

        val mixed = (1..6).map { swiped("Promo $it") } + (1..3).map { opened("Promo ${it + 10}") }
        assertTrue(DismissalAdvisor.advise(mixed, emptyList()).none { it.suggestion.label.startsWith("Title like “Promo") })
    }

    @Test
    fun skipsWhatRulesAlreadyHandleAndWhatWasIgnored() {
        val events = (1..6).map { swiped("Promo $it") }
        val handled = listOf(Rule(name = "promo", regex = "Promo", packageName = "com.insta"))
        assertTrue(DismissalAdvisor.advise(events, handled).isEmpty())

        val first = DismissalAdvisor.advise(events, emptyList())
        assertTrue(first.isNotEmpty())
        val rest = DismissalAdvisor.advise(events, emptyList(), ignored = first.map { it.key }.toSet())
        assertTrue(rest.none { it.key in first.map { a -> a.key } })
    }

    @Test
    fun countsSendersPerDayAndOverall() {
        val stats = SenderStats()
        stats.record(n("Mom", pkg = "chat", time = 10 * day), utc)
        stats.record(n("Mom", pkg = "chat", time = 10 * day + 5), utc)
        stats.record(n("Mom", pkg = "chat", time = 12 * day), utc)
        stats.record(n("Bob", pkg = "chat", time = 12 * day), utc)
        stats.record(n("", pkg = "chat", time = 12 * day), utc) // no sender: ignored
        val all = stats.senders.value.values
        assertEquals(2, all.size)

        val allTime = SenderStats.ranked(all, Int.MIN_VALUE)
        assertEquals(listOf("Mom" to 3L, "Bob" to 1L), allTime.map { it.first.sender to it.second })
        val recent = SenderStats.ranked(all, firstDay = 11)
        assertEquals(listOf(1L, 1L), recent.map { it.second })
        assertTrue(SenderStats.ranked(all, Int.MIN_VALUE, packageName = "other").isEmpty())
    }

    @Test
    fun backfillCountsEachNotificationOnce() {
        val mom = n("Mom", pkg = "chat", time = day)
        val stats = SenderStats()
        stats.backfill(listOf(mom, mom, n("Mom", pkg = "chat", time = 2 * day)), utc)
        assertEquals(2L, stats.senders.value.values.single().total)
    }

    @Test
    fun peoplePatternRoundTrips() {
        val names = setOf("Mom", "Kovács B. (work)", "Dad")
        val regex = PeoplePattern.build(names)
        assertEquals(names, PeoplePattern.parse(regex))
        val rule = Rule(name = "p", regex = regex, field = MatchField.TITLE)
        assertTrue(rule.matches(n("Kovács B. (work)")))
        assertFalse(rule.matches(n("Momo")))
        assertNull(PeoplePattern.parse("(?i)sale"))
        assertEquals("", PeoplePattern.build(emptyList()))
    }

    @Test
    fun totalCaughtKeepsCountingPastTheHistoryLimit() {
        val m = RuleManager(listOf(Rule(id = "h", name = "hide")))
        repeat(5) { m.process(n("x$it"), maxSaved = 2) }
        val rule = m.rules.value[0]
        assertEquals(2, rule.history.size)
        assertEquals(5L, rule.totalCaught)
        m.save(rule.copy(name = "renamed"))
        assertEquals(5L, m.rules.value[0].totalCaught)
    }
}
