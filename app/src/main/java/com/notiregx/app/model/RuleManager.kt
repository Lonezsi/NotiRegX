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

    /** Recent arrival times per burst rule and app ("ruleId|package"), oldest first. */
    private val arrivals = HashMap<String, ArrayDeque<Long>>()

    fun newNotificationId(): Long = nextId.getAndIncrement()

    /** Decides the outcome for [n], records it in the catching rule's history and updates stats. */
    fun process(n: Notification, maxSaved: Int, filteringEnabled: Boolean = true): Decision {
        val rules = _rules.value
        val decision = if (filteringEnabled) decide(rules, n, recordArrivals(rules, n)) else Decision.NONE
        val rule = decision.rule
        if (rule != null) {
            updateRule(rule.id) {
                val counted = it.recordHit(n.postedAt)
                if (decision.action == Action.PASS) counted else counted.catch(n, maxSaved)
            }
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
                    hits = it.hits,
                    createdAt = it.createdAt,
                    bundle = if (edited.type == RuleType.BUNDLE) it.bundle else Bundle(),
                )
            }
        }
    }

    fun delete(id: String) = _rules.update { list -> list.filter { it.id != id } }

    /** Adds imported rules (configuration only) under fresh ids, skipping ones already present. */
    fun import(imported: List<Rule>): Int {
        var added = 0
        _rules.update { list ->
            val existing = list.mapTo(HashSet()) { it.signature() }
            val fresh = imported.filter { it.signature() !in existing }.distinctBy { it.signature() }
            added = fresh.size
            list + fresh.map {
                it.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    history = emptyList(),
                    bundle = Bundle(),
                    hits = emptyList(),
                    createdAt = System.currentTimeMillis(),
                )
            }
        }
        return added
    }

    /** Lets digest bundles whose delivery time came show themselves. */
    fun release(ids: Set<String>) = _rules.update { list ->
        list.map { if (it.id in ids && it.bundle.count > 0) it.copy(bundle = it.bundle.copy(released = true)) else it }
    }

    fun move(id: String, delta: Int) = _rules.update { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) list
        else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Moves the rule [id] to position [to]. */
    fun moveTo(id: String, to: Int) = _rules.update { list ->
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || to !in list.indices || from == to) list
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

    /**
     * Notes [n]'s arrival for every burst rule it otherwise matches, and returns how many such
     * arrivals each has seen from this app within its window (this one included).
     */
    private fun recordArrivals(rules: List<Rule>, n: Notification): (Rule) -> Int {
        val counts = HashMap<String, Int>()
        synchronized(arrivals) {
            for (rule in rules) {
                val burst = rule.burst ?: continue
                if (!rule.matches(n)) continue
                val times = arrivals.getOrPut(rule.id + "|" + n.packageName) { ArrayDeque() }
                times.addLast(n.postedAt)
                while (times.isNotEmpty() && times.first() <= n.postedAt - burst.windowMs) times.removeFirst()
                counts[rule.id] = times.size
            }
        }
        return { counts[it.id] ?: 0 }
    }

    private fun Rule.signature() = listOf(name, regex, field, packageName, invert, type)

    companion object {
        const val RECENT_LIMIT = 500

        /**
         * Evaluates every rule in order; the last one that matches is the final effective
         * match and decides the outcome. No match, or a final ALLOW rule, means PASS.
         * [burstCount] says how many recent matching arrivals a burst rule has seen; without
         * that history, burst rules are treated as already bursting.
         */
        fun decide(rules: List<Rule>, n: Notification, burstCount: (Rule) -> Int = { Int.MAX_VALUE }): Decision {
            val matched = rules.filter { rule ->
                rule.matches(n) && rule.burst.let { it == null || burstCount(rule) > it.count }
            }
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
