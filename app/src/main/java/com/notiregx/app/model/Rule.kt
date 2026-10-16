package com.notiregx.app.model

import java.util.UUID

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Searched (not fully matched) in [field]. Empty matches everything. */
    val regex: String = "",
    val field: MatchField = MatchField.ANY,
    /** Restricts the rule to one app; null means any app. */
    val packageName: String? = null,
    val appName: String? = null,
    /** Inverts the regex test only; the app restriction still applies. */
    val invert: Boolean = false,
    val type: RuleType = RuleType.HIDE,
    val description: String = "",
    val enabled: Boolean = true,
    /**
     * Silence the app's own alerts and let NotiRegX alert for whatever passes the rules instead,
     * so filtered notifications never buzz. Needs a linked companion device.
     */
    val debuzz: Boolean = false,
    /** Only active at these times; null means always. */
    val schedule: Schedule? = null,
    /** Only applies once an app sends notifications in a burst; null means always. */
    val burst: Burst? = null,
    /** For bundles: times of day (minutes) to show the bundle; empty means right away. */
    val deliveryTimes: List<Int> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** When this rule was the final match, newest first, for the last [STATS_WINDOW_MS]. */
    val hits: List<Long> = emptyList(),
    /** Notifications this rule ultimately caught, newest first. */
    val history: List<Notification> = emptyList(),
    val bundle: Bundle = Bundle(),
) {
    private val compiled: Regex? by lazy { compileOrNull(regex) }

    /** Whether [n] matches, ignoring [burst] (which needs history; see [RuleManager]). */
    fun matches(n: Notification): Boolean {
        if (!enabled) return false
        if (!packageName.isNullOrEmpty() && packageName != n.packageName) return false
        if (schedule != null && !schedule.isActive(n.postedAt)) return false
        // An invalid pattern never matches, even when inverted.
        val re = compiled ?: return false
        return re.containsMatchIn(field.of(n)) != invert
    }

    /** Records [n] as caught by this rule, keeping at most [maxSaved] entries. */
    fun catch(n: Notification, maxSaved: Int): Rule {
        val withBundle = if (type == RuleType.BUNDLE) copy(bundle = bundle.add(n.id)) else this
        return withBundle.copy(history = listOf(n) + history).trimmed(maxSaved)
    }

    fun trimmed(maxSaved: Int): Rule {
        val kept = history.take(maxSaved.coerceAtLeast(1))
        return copy(history = kept, bundle = bundle.retain(kept.mapTo(HashSet()) { it.id }))
    }

    fun recordHit(time: Long): Rule =
        copy(hits = (listOf(time) + hits).filter { it > time - STATS_WINDOW_MS }.take(MAX_HITS))

    fun hitsSince(time: Long): Int = hits.count { it > time }

    val isDigest: Boolean get() = type == RuleType.BUNDLE && deliveryTimes.isNotEmpty()

    fun unseenNotifications(): List<Notification> {
        val byId = history.associateBy { it.id }
        return bundle.unseen.mapNotNull { byId[it] }
    }

    companion object {
        const val STATS_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_HITS = 5000

        fun compileOrNull(pattern: String): Regex? =
            try {
                Regex(pattern)
            } catch (e: IllegalArgumentException) {
                null
            }

        fun regexError(pattern: String): String? =
            try {
                Regex(pattern)
                null
            } catch (e: IllegalArgumentException) {
                e.message?.lineSequence()?.firstOrNull() ?: "Invalid regex"
            }

        private val special = Regex("""[\\^$.|?*+()\[\]{}]""")

        /** Escapes [text] so it matches literally. */
        fun escape(text: String): String = special.replace(text) { "\\" + it.value }
    }
}
