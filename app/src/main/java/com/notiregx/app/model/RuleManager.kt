package com.notiregx.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class Action { PASS, HIDE, BUNDLE }

/**
 * The outcome of evaluating the whole rule list against one notification.
 * [rule] is the final effective match (null if nothing matched); [matched] lists every
 * rule that matched, in order.
 */
data class Decision(val action: Action, val rule: Rule?, val matched: List<Rule>) {
    companion object {
        val NONE = Decision(Action.PASS, null, emptyList())
    }
}

/** An entry in the in-memory log of recent notifications, whatever their outcome. */
data class LoggedNotification(
    val notification: Notification,
    val action: Action,
    val ruleName: String?,
    val filtering: Boolean,
) {
    val outcomeLabel: String
        get() = when {
            !filtering -> "Filtering paused"
            ruleName == null -> "No match"
            action == Action.HIDE -> "Hidden by $ruleName"
            action == Action.BUNDLE -> "Bundled by $ruleName"
            else -> "Let through by $ruleName"
        }
}

/** Holds the ordered rule list and decides what happens to each incoming notification. */
class RuleManager(
    initialRules: List<Rule> = emptyList(),
    initialRecent: List<LoggedNotification> = emptyList(),
) {
    private val _rules = MutableStateFlow(initialRules)
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val _recent = MutableStateFlow(initialRecent)
    val recent: StateFlow<List<LoggedNotification>> = _recent.asStateFlow()

    private val nextId = AtomicLong(
        ((initialRules.flatMap { it.history } + initialRecent.map { it.notification }).maxOfOrNull { it.id } ?: 0L) + 1
    )

    fun newNotificationId(): Long = nextId.getAndIncrement()

    /** Decides the outcome for [n] and records it in the catching rule's history. */
    fun process(n: Notification, maxSaved: Int, filteringEnabled: Boolean = true): Decision {
        val decision = if (filteringEnabled) decide(_rules.value, n) else Decision.NONE
        val rule = decision.rule
        if (rule != null && decision.action != Action.PASS) {
            updateRule(rule.id) { it.catch(n, maxSaved) }
        }
        _recent.update { log ->
            (listOf(LoggedNotification(n, decision.action, rule?.name, filteringEnabled)) + log).take(RECENT_LIMIT)
        }
        return decision
    }

    /** Adds [edited] or replaces the existing rule's configuration, keeping its history. */
    fun save(edited: Rule) = _rules.update { list ->
        if (list.none { it.id == edited.id }) {
            list + edited.copy(history = emptyList(), bundle = Bundle())
        } else {
            list.map {
                if (it.id != edited.id) it
                else edited.copy(
                    history = it.history,
                    bundle = if (edited.type == RuleType.BUNDLE) it.bundle else Bundle(),
                )
            }
        }
    }

    fun delete(id: String) = _rules.update { list -> list.filter { it.id != id } }

    fun move(id: String, delta: Int) = _rules.update { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) list
        else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun setEnabled(id: String, enabled: Boolean) = updateRule(id) { it.copy(enabled = enabled) }

    fun markSeen(id: String) = updateRule(id) {
        if (it.bundle.count == 0) it else it.copy(bundle = it.bundle.markAllSeen())
    }

    fun clearHistory(id: String) = updateRule(id) { it.copy(history = emptyList(), bundle = Bundle()) }

    fun clearRecent() = _recent.update { emptyList() }

    fun trimHistories(maxSaved: Int) = _rules.update { list -> list.map { it.trimmed(maxSaved) } }

    private fun updateRule(id: String, transform: (Rule) -> Rule) = _rules.update { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    companion object {
        const val RECENT_LIMIT = 500

        /**
         * Evaluates every rule in order; the last one that matches is the final effective
         * match and decides the outcome. No match, or a final ALLOW rule, means PASS.
         */
        fun decide(rules: List<Rule>, n: Notification): Decision {
            val matched = rules.filter { it.matches(n) }
            val final = matched.lastOrNull() ?: return Decision.NONE
            val action = when (final.type) {
                RuleType.HIDE -> Action.HIDE
                RuleType.BUNDLE -> Action.BUNDLE
                RuleType.ALLOW -> Action.PASS
            }
            return Decision(action, final, matched)
        }

        /** Apps whose alerts are silenced up front: those targeted by an enabled de-buzz rule. */
        fun mutedPackages(rules: List<Rule>): Set<String> =
            rules.filter { it.enabled && it.debuzz && !it.packageName.isNullOrEmpty() }
                .mapNotNullTo(HashSet()) { it.packageName }

        /**
         * Whether an enabled de-buzz hide/bundle rule applies to any app, so channels it catches
         * something from get silenced as they're found.
         */
        fun learnsChannels(rules: List<Rule>): Boolean =
            rules.any { it.enabled && it.debuzz && it.type != RuleType.ALLOW && it.packageName.isNullOrEmpty() }
    }
}
