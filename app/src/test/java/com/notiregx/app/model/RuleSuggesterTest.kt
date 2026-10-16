package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggesterTest {
    private var nextId = 1L

    private fun n(title: String, text: String = "", pkg: String = "com.shop") =
        Notification(nextId++, pkg, "Shop", title, text, postedAt = 0)

    private fun Suggestion.catches(x: Notification) =
        Rule(name = "s", regex = regex, field = field).matches(x)

    @Test
    fun alignsSimilarTitlesIntoATemplate() {
        val target = n("3 new messages")
        val others = listOf(n("5 new messages"), n("12 new messages"), n("Order shipped"))
        val s = RuleSuggester.suggest(target, others)
        val template = s.first { it.label == "Title like “# new messages”" }
        assertEquals(3, template.matches)
        assertEquals(4, template.total)
        assertTrue(template.catches(n("40 new messages")))
        assertFalse(template.catches(n("Order shipped")))
    }

    @Test
    fun wildcardsWordsThatVary() {
        val target = n("Anna liked your photo")
        val others = listOf(n("Bob liked your photo"), n("Cy liked your photo"), n("New follower"))
        val s = RuleSuggester.suggest(target, others)
        val template = s.first { it.label == "Title like “… liked your photo”" }
        assertEquals(3, template.matches)
        assertTrue(template.catches(n("Dora liked your photo")))
    }

    @Test
    fun findsSharedPrefixesAndRecurringWords() {
        val target = n("Deal", "Flash sale: 50% off shoes")
        val others = listOf(
            n("Deal", "Flash sale: socks for less"),
            n("Weekend", "Weekend sale on hats"),
            n("Promo", "Flash sale: hats"),
            n("Shipping", "Your parcel is on its way"),
        )
        val labels = RuleSuggester.suggest(target, others).map { it.label }
        assertTrue(labels.toString(), "Text starts with “Flash sale:”" in labels)
        assertTrue(labels.toString(), "Text contains “sale”" in labels)
    }

    @Test
    fun ignoresOtherAppsAndAlwaysOffersEverythingLast() {
        val target = n("Hello")
        val s = RuleSuggester.suggest(target, listOf(n("Hello there", pkg = "com.other")))
        assertEquals("Everything from Shop", s.last().label)
        assertEquals(1, s.last().total)
        assertTrue(s.all { it.catches(target) })
    }

    @Test
    fun dropsPatternsEquivalentToEverything() {
        val target = n("Ping 1")
        val s = RuleSuggester.suggest(target, listOf(n("Ping 2"), n("Ping 3")))
        // "Title like Ping #" catches all three, the same as "Everything", so only Everything stays.
        assertTrue(s.none { it.label.startsWith("Title like") })
        assertEquals("Everything from Shop", s.last().label)
    }

    @Test
    fun offersExceptionsThatLeaveTheTargetOut() {
        val target = n("Mom", "call me")
        val others = listOf(n("Bob", "hi"), n("Cy", "yo"), n("Mom", "dinner?"))
        val except = RuleSuggester.suggest(target, others).first { it.group == SuggestionGroup.EXCEPT }
        assertEquals("Everything except title “Mom”", except.label)
        assertTrue(except.invert)
        assertEquals(2, except.matches) // Bob and Cy
        val rule = Rule(name = "x", regex = except.regex, field = except.field, invert = except.invert)
        assertFalse(rule.matches(target))
        assertTrue(rule.matches(n("Dan", "hey")))
    }

    @Test
    fun includesEndingsMentionsAndExamples() {
        val target = n("Order 1", "Your parcel was delivered today")
        val others = listOf(n("Order 2", "Your parcel was delivered today"), n("Order 3", "Item delivered today"), n("Promo", "Big sale"))
        val s = RuleSuggester.suggest(target, others)
        val labels = s.map { it.label }
        assertTrue(labels.toString(), labels.any { it.startsWith("Text ends with") })
        assertTrue(labels.toString(), labels.any { it.startsWith("Mentions") })
        val like = s.first { it.label == "Title like “Order #”" }
        assertEquals(listOf("Order 2", "Order 3"), like.examples.map { it.title })
        assertTrue(s.size > 4)
    }

    @Test
    fun sortsByHowManyNotificationsMatch() {
        val target = n("Sale today", "Up to 70% off")
        val others = listOf(n("Sale today", "Up to 30% off"), n("Sale today", "Free gift"), n("Receipt", "Thanks"))
        val counts = RuleSuggester.suggest(target, others).dropLast(1).map { it.matches }
        assertEquals(counts.sortedDescending(), counts)
    }
}
