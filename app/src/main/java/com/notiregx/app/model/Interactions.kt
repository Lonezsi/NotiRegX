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
        ignored: Set<String> = emptySet(),
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
                .filter { it.key !in ignored }
                .take(perApp)
            out += scored
        }
        return out.sortedByDescending { it.dismissed }.take(max)
    }
}
