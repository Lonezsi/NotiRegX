package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogAndAlertTest {
    private val grace = WatchdogPolicy.STARTUP_GRACE_MS
    private val wait = WatchdogPolicy.REBIND_WAIT_MS

    @Test
    fun watchdogLeavesThingsAloneWhenFineOrNoAccess() {
        assertEquals(WatchdogAction.CLEAR, WatchdogPolicy.decide(true, connected = true, sinceStartMs = grace * 10, sinceRebindMs = null))
        assertEquals(WatchdogAction.NOTHING, WatchdogPolicy.decide(false, connected = false, sinceStartMs = grace * 10, sinceRebindMs = null))
        // Just started: the system may not have bound the listener yet.
        assertEquals(WatchdogAction.NOTHING, WatchdogPolicy.decide(true, connected = false, sinceStartMs = grace - 1, sinceRebindMs = null))
    }

    @Test
    fun watchdogRebindsThenWarnsIfThatDidntHelp() {
        assertEquals(WatchdogAction.REBIND, WatchdogPolicy.decide(true, false, grace, sinceRebindMs = null))
        assertEquals(WatchdogAction.NOTHING, WatchdogPolicy.decide(true, false, grace, sinceRebindMs = wait - 1))
        assertEquals(WatchdogAction.WARN, WatchdogPolicy.decide(true, false, grace, sinceRebindMs = wait))
    }

    private fun n(text: String) = Notification(1, "chat", "Chat", "Anna", text, postedAt = 0)

    @Test
    fun alwaysAlertLetsThroughAndOverridesEarlierHide() {
        val rules = listOf(
            Rule(id = "h", name = "hide chat", packageName = "chat"),
            Rule(id = "a", name = "urgent", regex = "(?i)urgent", type = RuleType.ALERT),
        )
        val urgent = RuleManager.decide(rules, n("URGENT: call me"))
        assertEquals(Action.PASS, urgent.action)
        assertEquals(RuleType.ALERT, urgent.rule?.type)
        assertEquals(Action.HIDE, RuleManager.decide(rules, n("lunch?")).action)
    }

    @Test
    fun alertRulesCatchNothingAndDontLearnChannels() {
        val m = RuleManager(listOf(Rule(id = "a", name = "urgent", type = RuleType.ALERT, debuzz = true)))
        m.process(n("x"), maxSaved = 10)
        val rule = m.rules.value[0]
        assertTrue(rule.history.isEmpty())
        assertEquals(0L, rule.totalCaught)
        assertEquals(1, rule.hitsSince(-1))
        assertTrue(RuleType.ALERT.letsThrough)
        assertFalse(RuleType.BUNDLE.letsThrough)
        assertFalse(RuleManager.learnsChannels(m.rules.value))
    }
}
