package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleManagerTest {
    private var nextId = 1L

    private fun notif(text: String, pkg: String = "com.chat", title: String = "") =
        Notification(nextId++, pkg, pkg, title, text, postedAt = 0)

    private fun rule(
        name: String,
        regex: String = "",
        type: RuleType = RuleType.HIDE,
        pkg: String? = null,
        invert: Boolean = false,
        enabled: Boolean = true,
        field: MatchField = MatchField.ANY,
        debuzz: Boolean = false,
    ) = Rule(
        id = name, name = name, regex = regex, packageName = pkg, invert = invert, type = type,
        enabled = enabled, field = field, debuzz = debuzz,
    )

    @Test
    fun noRulesPassesThrough() {
        val d = RuleManager.decide(emptyList(), notif("hi"))
        assertEquals(Action.PASS, d.action)
        assertNull(d.rule)
    }

    @Test
    fun regexIsSearchedInTitleAndText() {
        val r = rule("promo", regex = "(?i)sale")
        assertTrue(r.matches(notif("Big SALE today")))
        assertTrue(r.matches(notif("", title = "Summer sale")))
        assertFalse(r.matches(notif("hello")))
    }

    @Test
    fun appRestrictionMustMatch() {
        val r = rule("chat only", pkg = "com.chat")
        assertTrue(r.matches(notif("x", pkg = "com.chat")))
        assertFalse(r.matches(notif("x", pkg = "com.mail")))
    }

    @Test
    fun invertNegatesRegexButNotApp() {
        val r = rule("not mom", regex = "Mom", pkg = "com.chat", invert = true)
        assertTrue(r.matches(notif("Bob: hi")))
        assertFalse(r.matches(notif("Mom: call me")))
        assertFalse(r.matches(notif("Bob: hi", pkg = "com.mail")))
    }

    @Test
    fun invalidRegexNeverMatchesEvenInverted() {
        assertFalse(rule("bad", regex = "(", invert = true).matches(notif("anything")))
        assertFalse(rule("bad", regex = "(").matches(notif("(")))
    }

    @Test
    fun disabledRulesAreSkipped() {
        val d = RuleManager.decide(listOf(rule("off", enabled = false)), notif("x"))
        assertEquals(Action.PASS, d.action)
    }

    @Test
    fun lastMatchingRuleWins() {
        val bundleAll = rule("bundle chat", pkg = "com.chat", type = RuleType.BUNDLE)
        val hideAds = rule("hide ads", regex = "Ad:", type = RuleType.HIDE)
        val rules = listOf(bundleAll, hideAds)

        val ad = RuleManager.decide(rules, notif("Ad: buy now"))
        assertEquals(Action.HIDE, ad.action)
        assertSame(hideAds, ad.rule)
        assertEquals(listOf(bundleAll, hideAds), ad.matched)

        assertEquals(Action.BUNDLE, RuleManager.decide(rules, notif("Bob: hi")).action)
    }

    @Test
    fun laterAllowRuleMakesAnException() {
        val rules = listOf(
            rule("bundle chat", pkg = "com.chat", type = RuleType.BUNDLE),
            rule("mentions", regex = "@me", type = RuleType.ALLOW),
        )
        val d = RuleManager.decide(rules, notif("Bob: hey @me"))
        assertEquals(Action.PASS, d.action)
        assertEquals("mentions", d.rule?.name)
        assertEquals(Action.BUNDLE, RuleManager.decide(rules, notif("Bob: hey all")).action)
    }

    @Test
    fun laterInverseRuleOverridesEarlierOutcome() {
        // Bundle everything from chat, but hide whatever doesn't mention "urgent".
        val rules = listOf(
            rule("bundle chat", pkg = "com.chat", type = RuleType.BUNDLE),
            rule("hide non-urgent", regex = "urgent", pkg = "com.chat", invert = true, type = RuleType.HIDE),
        )
        assertEquals(Action.HIDE, RuleManager.decide(rules, notif("lunch?")).action)
        assertEquals(Action.BUNDLE, RuleManager.decide(rules, notif("urgent: server down")).action)
    }

    @Test
    fun orderMatters() {
        val hide = rule("hide", type = RuleType.HIDE)
        val allow = rule("allow", type = RuleType.ALLOW)
        assertEquals(Action.PASS, RuleManager.decide(listOf(hide, allow), notif("x")).action)
        assertEquals(Action.HIDE, RuleManager.decide(listOf(allow, hide), notif("x")).action)
    }

    @Test
    fun processSavesOnlyToTheFinalRule() {
        val m = RuleManager(listOf(rule("a", type = RuleType.BUNDLE), rule("b", regex = "x", type = RuleType.HIDE)))
        m.process(notif("x"), maxSaved = 10)
        val (a, b) = m.rules.value
        assertTrue(a.history.isEmpty())
        assertEquals(1, b.history.size)
        assertEquals(0, b.bundle.count)
    }

    @Test
    fun bundleTracksUnseenAndMarkSeenClearsIt() {
        val m = RuleManager(listOf(rule("b", type = RuleType.BUNDLE)))
        m.process(notif("1"), maxSaved = 10)
        m.process(notif("2"), maxSaved = 10)
        assertEquals(2, m.rules.value[0].bundle.count)
        assertEquals(listOf("2", "1"), m.rules.value[0].unseenNotifications().map { it.text })

        m.markSeen("b")
        assertEquals(0, m.rules.value[0].bundle.count)
        assertEquals(2, m.rules.value[0].history.size)
    }

    @Test
    fun passThroughIsLoggedButNotSaved() {
        val m = RuleManager(listOf(rule("allow", type = RuleType.ALLOW)))
        m.process(notif("x"), maxSaved = 10)
        m.process(notif("y"), maxSaved = 10, filteringEnabled = false)
        assertTrue(m.rules.value[0].history.isEmpty())
        assertEquals(listOf("Filtering paused", "Let through by allow"), m.recent.value.map { it.outcomeLabel })
    }

    @Test
    fun historyIsCappedAndBundleFollows() {
        val m = RuleManager(listOf(rule("b", type = RuleType.BUNDLE)))
        repeat(5) { m.process(notif("n$it"), maxSaved = 3) }
        val r = m.rules.value[0]
        assertEquals(listOf("n4", "n3", "n2"), r.history.map { it.text })
        assertEquals(r.history.map { it.id }, r.bundle.unseen)

        m.trimHistories(1)
        assertEquals(1, m.rules.value[0].bundle.count)
    }

    @Test
    fun savingEditKeepsHistoryAndDropsBundleWhenNoLongerBundling() {
        val m = RuleManager(listOf(rule("b", type = RuleType.BUNDLE)))
        m.process(notif("x"), maxSaved = 10)
        m.save(m.rules.value[0].copy(name = "renamed", type = RuleType.HIDE, history = emptyList()))
        val r = m.rules.value[0]
        assertEquals("renamed", r.name)
        assertEquals(1, r.history.size)
        assertEquals(0, r.bundle.count)
    }

    @Test
    fun moveReorders() {
        val m = RuleManager(listOf(rule("a"), rule("b"), rule("c")))
        m.move("c", -2)
        assertEquals(listOf("c", "a", "b"), m.rules.value.map { it.id })
        m.move("c", -1) // already first: no-op
        assertEquals(listOf("c", "a", "b"), m.rules.value.map { it.id })
    }

    @Test
    fun mutedPackagesComeFromEnabledDebuzzRules() {
        val rules = listOf(
            rule("hide chat", pkg = "com.chat", debuzz = true),
            rule("bundle mail", pkg = "com.mail", type = RuleType.BUNDLE),
            rule("off", pkg = "com.off", enabled = false, debuzz = true),
        )
        assertEquals(setOf("com.chat"), RuleManager.mutedPackages(rules))
        assertFalse(RuleManager.learnsChannels(rules))
        assertTrue(RuleManager.learnsChannels(rules + rule("ads", regex = "Ad:", debuzz = true)))
        assertFalse(RuleManager.learnsChannels(rules + rule("ads", regex = "Ad:")))
        assertFalse(RuleManager.learnsChannels(listOf(rule("allow all", type = RuleType.ALLOW, debuzz = true))))
    }

    @Test
    fun fieldLimitsWhereTheRegexIsSearched() {
        val n = notif("Your order shipped", title = "Shop")
        assertTrue(rule("t", regex = "Shop", field = MatchField.TITLE).matches(n))
        assertFalse(rule("t", regex = "order", field = MatchField.TITLE).matches(n))
        assertTrue(rule("x", regex = "order", field = MatchField.TEXT).matches(n))
        assertFalse(rule("x", regex = "Shop", field = MatchField.TEXT).matches(n))
    }

    @Test
    fun idsContinueAfterRestoredRecentLog() {
        val old = LoggedNotification(Notification(41, "p", "p", "", "x", 0), Action.PASS, null, true)
        assertEquals(42L, RuleManager(initialRecent = listOf(old)).newNotificationId())
    }

    @Test
    fun escapeMatchesLiterally() {
        val r = rule("lit", regex = Rule.escape("Price: $5 (50% off?)"))
        assertTrue(r.matches(notif("Price: $5 (50% off?)")))
        assertFalse(r.matches(notif("Price: 5 50 off")))
    }
}
