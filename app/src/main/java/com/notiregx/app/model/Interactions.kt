package com.notiregx.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the user did with a notification that reached the shade. */
enum class Outcome { OPENED, DISMISSED }

data class Interaction(val notification: Notification, val outcome: Outcome, val time: Long)

/** How the user treated notifications NotiRegX let through, newest first. */
class InteractionLog(initial: List<Interaction> = emptyList()) {
    private val _events = MutableStateFlow(initial)
    val events: StateFlow<List<Interaction>> = _events.asStateFlow()

    fun record(interaction: Interaction) = _events.update { (listOf(interaction) + it).take(LIMIT) }

    fun clear() = _events.update { emptyList() }

    companion object {
        const val LIMIT = 3000
    }
}

/** A rule NotiRegX proposes because the user keeps swiping these notifications away unread. */
data class RuleAdvice(
    val suggestion: Suggestion,
    val packageName: String,
    val appName: String,
    val dismissed: Int,
    val opened: Int,
) {
    /** Stable identity, for remembering "not now". */
    val key: String get() = listOf(packageName, suggestion.field, suggestion.invert, suggestion.regex).joinToString("|")
}

/**
 * What "not now" remembers, so a suggestion steps back instead of being replaced by the next
 * one straight away, and comes back only when it has more to say:
 *
 *  - per app: how many times its suggestions got "not now", and until when they rest. Each
 *    "not now" rests the app three times longer than the last (a day, three, nine, ... at most
 *    sixty days), so the more it's declined, the further it backs off;
 *  - per suggestion: how many swipes it had when declined. That one only returns once the
 *    swipes behind it have doubled: the pattern got clearly stronger, not just older.
 */
data class AdviceRest(
    val apps: Map<String, AppRest> = emptyMap(),
    val declined: Map<String, Int> = emptyMap(),
) {
    data class AppRest(val times: Int, val until: Long)

    fun allows(advice: RuleAdvice, now: Long): Boolean {
        val rest = apps[advice.packageName]
        if (rest != null && now < rest.until) return false
        val before = declined[advice.key] ?: return true
        return advice.dismissed >= before * 2
    }

    /** [advice] got "not now" at [now]. */
    fun notNow(advice: RuleAdvice, now: Long): AdviceRest {
        val times = (apps[advice.packageName]?.times ?: 0) + 1
        var days = 1L
        repeat(times - 1) { days = minOf(days * 3, MAX_DAYS) }
        return copy(
            apps = apps + (advice.packageName to AppRest(times, now + days * DAY_MS)),
            declined = declined + (advice.key to advice.dismissed),
        )
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_DAYS = 60L
        /** What an old "not now" (from before this, which hid a suggestion for good) counts as. */
        const val OLD_DECLINE_SWIPES = 10
    }
}

/**
 * Finds patterns the user dismisses without ever opening: for each app with enough swipes, it
 * asks [RuleSuggester] for candidate patterns around the dismissed notifications, and keeps the
 * ones that catch many dismissed notifications and (almost) no opened ones.
 */
object DismissalAdvisor {
    const val MIN_DISMISSED = 5

    /** At most one opened notification per this many dismissed ones. */
    private const val OPENED_RATIO = 10
    private const val TARGETS_PER_APP = 15

    fun advise(
        interactions: List<Interaction>,
        rules: List<Rule>,
        rest: AdviceRest = AdviceRest(),
        now: Long = System.currentTimeMillis(),
        perApp: Int = 2,
        max: Int = 6,
    ): List<RuleAdvice> {
        val out = ArrayList<RuleAdvice>()
        for ((pkg, events) in interactions.groupBy { it.notification.packageName }) {
            val dismissed = events.filter { it.outcome == Outcome.DISMISSED }.map { it.notification }
            if (dismissed.size < MIN_DISMISSED) continue
            val opened = events.filter { it.outcome == Outcome.OPENED }.map { it.notification }
            // Already handled by a current rule? Then there's nothing to suggest for those.
            val unhandled = dismissed.filter { RuleManager.decide(rules, it).action == Action.PASS }
            if (unhandled.size < MIN_DISMISSED) continue

            val all = events.map { it.notification }
            val targets = unhandled.distinctBy { it.title to it.text }.take(TARGETS_PER_APP)
            val candidates = targets.flatMap { RuleSuggester.suggest(it, all) }
                .filter { it.group != SuggestionGroup.EXCEPT }
                .distinctBy { Triple(it.field, it.invert, it.regex) }

            val scored = candidates.mapNotNull { s ->
                val rule = Rule(name = "", regex = s.regex, field = s.field, invert = s.invert, packageName = pkg)
                val hitDismissed = unhandled.count(rule::matches)
                val hitOpened = opened.count(rule::matches)
                if (hitDismissed < MIN_DISMISSED || hitOpened * OPENED_RATIO > hitDismissed) return@mapNotNull null
                RuleAdvice(s, pkg, unhandled.first().appName, hitDismissed, hitOpened)
            }
                // Most swipes caught first; on a tie, the more specific pattern (not "everything").
                .sortedWith(
                    compareByDescending<RuleAdvice> { it.dismissed }
                        .thenBy { it.suggestion.group == SuggestionGroup.EVERYTHING }
                        .thenBy { it.opened }
                )
                .distinctBy { it.dismissed to it.opened }
                .filter { rest.allows(it, now) }
                .take(perApp)
            out += scored
        }
        return out.sortedByDescending { it.dismissed }.take(max)
    }
}
