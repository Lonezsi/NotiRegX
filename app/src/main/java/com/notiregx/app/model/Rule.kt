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
    /** Notifications this rule ultimately caught, newest first. */
    val history: List<Notification> = emptyList(),
    val bundle: Bundle = Bundle(),
) {
    private val compiled: Regex? by lazy { compileOrNull(regex) }

    fun matches(n: Notification): Boolean {
        if (!enabled) return false
        if (!packageName.isNullOrEmpty() && packageName != n.packageName) return false
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

    fun unseenNotifications(): List<Notification> {
        val byId = history.associateBy { it.id }
        return bundle.unseen.mapNotNull { byId[it] }
    }

    companion object {
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
