package com.notiregx.app.model

/** A guessed rule for "make a rule from this notification", with how much of the app it would catch. */
data class Suggestion(
    val label: String,
    val field: MatchField,
    val regex: String,
    /** How many of the app's known notifications (including the target) it matches. */
    val matches: Int,
    val total: Int,
)

/**
 * Guesses what the user wants to filter when making a rule from [target], by grouping it with the
 * same app's other notifications: aligned word templates ("# new messages"), number-agnostic
 * patterns, shared prefixes and recurring words, plus exact matches and "everything from the app".
 */
object RuleSuggester {
    private const val MAX = 8
    private val SPACE = Regex("""\s+""")
    private val DIGITS = Regex("""\d+""")
    private val WORD = Regex("""\p{L}[\p{L}\p{N}'’-]{3,}""")
    private val STOPWORDS = setOf("this", "that", "with", "from", "have", "your", "will", "been", "were", "they", "there", "what")

    fun suggest(target: Notification, others: List<Notification>): List<Suggestion> {
        val samples = (listOf(target) + others.filter { it.packageName == target.packageName })
            .distinctBy { it.title to it.text }

        // Insertion order is priority: when two patterns catch exactly the same notifications,
        // the earlier (more general) one is kept.
        val candidates = LinkedHashMap<Pair<MatchField, String>, String>()
        for (field in listOf(MatchField.TITLE, MatchField.TEXT)) {
            val value = field.of(target).trim()
            if (value.isEmpty()) continue
            val values = samples.map { field.of(it).trim() }
            val name = field.label
            template(value, values)?.let { (re, shown) -> candidates.putIfAbsent(field to re, "$name like “$shown”") }
            digitPattern(value)?.let { (re, shown) -> candidates.putIfAbsent(field to re, "$name like “$shown”") }
            commonPrefixes(value, values).forEach { candidates.putIfAbsent(field to "^" + Rule.escape(it), "$name starts with “$it”") }
            recurringWords(value, values).forEach {
                candidates.putIfAbsent(field to "(?i)\\b" + Rule.escape(it) + "\\b", "$name contains “$it”")
            }
            candidates.putIfAbsent(field to "^" + Rule.escape(value) + "$", "$name is “${shorten(value)}”")
        }

        val everythingSet = samples.indices.toSet()
        val scored = candidates.mapNotNull { (key, label) ->
            val re = Rule.compileOrNull(key.second) ?: return@mapNotNull null
            val hits = samples.indices.filterTo(HashSet()) { re.containsMatchIn(key.first.of(samples[it])) }
            if (0 !in hits) return@mapNotNull null // must catch the notification it was made from
            Suggestion(label, key.first, key.second, hits.size, samples.size) to hits
        }
            .filter { it.second != everythingSet || samples.size == 1 }
            .distinctBy { it.second }
            .sortedByDescending { it.first.matches }
            .map { it.first }

        val everything = Suggestion("Everything from ${target.appName}", MatchField.ANY, "", samples.size, samples.size)
        return scored.take(MAX - 1) + everything
    }

    /**
     * Aligns [value] word by word with the app's other notifications of the same length; words
     * that differ become wildcards. Returns the regex and a readable form, or null if nothing lines up.
     */
    private fun template(value: String, values: List<String>): Pair<String, String>? {
        val tokens = value.split(SPACE)
        if (tokens.size < 2) return null
        val needed = (tokens.size + 1) / 2
        val cluster = values.map { it.split(SPACE) }.filter { other ->
            other.size == tokens.size && other != tokens && tokens.indices.count { tokens[it] == other[it] } >= needed
        }
        if (cluster.isEmpty()) return null

        val parts = ArrayList<String>()
        val shown = ArrayList<String>()
        var variable = 0
        for (i in tokens.indices) {
            val column = cluster.map { it[i] } + tokens[i]
            when {
                column.all { it == tokens[i] } -> {
                    parts += Rule.escape(tokens[i])
                    shown += tokens[i]
                }
                column.map { DIGITS.replace(it, "#") }.distinct().size == 1 -> {
                    parts += digitsAsWildcards(tokens[i])
                    shown += DIGITS.replace(tokens[i], "#")
                    variable++
                }
                else -> {
                    parts += "\\S+"
                    shown += "…"
                    variable++
                }
            }
        }
        if (variable == 0 || variable == tokens.size) return null
        return "^" + parts.joinToString("\\s+") + "$" to shown.joinToString(" ")
    }

    /** [value] with every number replaced by "any number". */
    private fun digitPattern(value: String): Pair<String, String>? {
        if (!DIGITS.containsMatchIn(value)) return null
        return "^" + digitsAsWildcards(value) + "$" to shorten(DIGITS.replace(value, "#"))
    }

    private fun digitsAsWildcards(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in DIGITS.findAll(text)) {
            out.append(Rule.escape(text.substring(last, m.range.first))).append("\\d+")
            last = m.range.last + 1
        }
        return out.append(Rule.escape(text.substring(last))).toString()
    }

    /** Word-aligned prefixes [value] shares with at least one other notification, most shared first. */
    private fun commonPrefixes(value: String, values: List<String>): List<String> {
        val prefixes = values.filter { it != value }.mapNotNull { other ->
            var n = value.commonPrefixWith(other).length
            if (n == 0 || n == value.length) return@mapNotNull null
            // Don't cut a word in half.
            if (n < other.length && value[n].isLetterOrDigit() && value[n - 1].isLetterOrDigit()) {
                n = value.lastIndexOf(' ', n - 1).coerceAtLeast(0)
            }
            value.take(n).trimEnd().takeIf { it.length >= 4 }
        }.distinct()
        return prefixes
            .map { p -> p to values.count { it.startsWith(p) } }
            .filter { it.second >= 2 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
            .take(2)
            .map { it.first }
    }

    /** Words in [value] that some, but not all, of the app's notifications also contain. */
    private fun recurringWords(value: String, values: List<String>): List<String> =
        WORD.findAll(value).map { it.value }.distinct()
            .filter { it.lowercase() !in STOPWORDS }
            .map { word ->
                val re = Regex("(?i)\\b" + Rule.escape(word) + "\\b")
                word to values.count { re.containsMatchIn(it) }
            }
            .filter { it.second in 2 until values.size }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
            .toList()

    private fun shorten(text: String) = text.replace('\n', ' ').let { if (it.length > 40) it.take(39) + "…" else it }
}
