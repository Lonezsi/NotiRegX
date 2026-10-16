package com.notiregx.app.model

/** How a suggestion relates to the notification it was made from, for grouping in the UI. */
enum class SuggestionGroup(val title: String) {
    SIMILAR("Messages like this one"),
    CONTAINS("Containing a word"),
    EXACT("Exactly this"),
    EXCEPT("Everything except this"),
    EVERYTHING("Everything from the app"),
}

/** The wording of a suggestion, so the UI can phrase it in any language. */
enum class SuggestionKind { LIKE, STARTS_WITH, ENDS_WITH, CONTAINS, IS, MENTIONS, EXCEPT_TITLE, EXCEPT_TITLES_LIKE, EVERYTHING }

/** A guessed rule for "make a rule from this notification", with how much of the app it would catch. */
data class Suggestion(
    /** English description, e.g. "Title like “# new messages”" (the UI uses [kind] and [value] instead). */
    val label: String,
    val field: MatchField,
    val regex: String,
    /** How many of the app's known notifications (including the target) it matches. */
    val matches: Int,
    val total: Int,
    val invert: Boolean = false,
    val group: SuggestionGroup = SuggestionGroup.SIMILAR,
    /** A few other notifications it would also catch, to show what it means in practice. */
    val examples: List<Notification> = emptyList(),
    val kind: SuggestionKind = SuggestionKind.LIKE,
    /** The text shown inside the quotes (or the app name, for [SuggestionKind.EVERYTHING]). */
    val value: String = "",
)

/**
 * Guesses what the user wants to filter when making a rule from [target], by grouping it with the
 * same app's other notifications: aligned word templates ("# new messages"), number-agnostic
 * patterns, shared starts and endings, recurring words, exact matches, "everything except this"
 * exceptions, and "everything from the app".
 */
object RuleSuggester {
    private const val MAX = 16
    private const val EXAMPLES = 2
    private val SPACE = Regex("""\s+""")
    private val DIGITS = Regex("""\d+""")
    private val WORD = Regex("""\p{L}[\p{L}\p{N}'’-]{3,}""")
    private val STOPWORDS = setOf(
        "this", "that", "with", "from", "have", "your", "will", "been", "were", "they", "there", "what",
        "hogy", "nem", "egy", "van", "vagy", "csak", "mint", "már",
    )

    private data class Candidate(
        val field: MatchField,
        val regex: String,
        val invert: Boolean,
        val label: String,
        val group: SuggestionGroup,
        val kind: SuggestionKind,
        val value: String,
    )

    fun suggest(target: Notification, others: List<Notification>): List<Suggestion> {
        val samples = (listOf(target) + others.filter { it.packageName == target.packageName })
            .distinctBy { it.title to it.text }

        // Insertion order is priority: when two patterns catch exactly the same notifications,
        // the earlier (more general) one is kept.
        val candidates = ArrayList<Candidate>()
        fun add(field: MatchField, regex: String, kind: SuggestionKind, value: String, group: SuggestionGroup, invert: Boolean = false) {
            if (candidates.none { it.field == field && it.regex == regex && it.invert == invert }) {
                candidates += Candidate(field, regex, invert, englishLabel(kind, field, value), group, kind, value)
            }
        }
        for (field in listOf(MatchField.TITLE, MatchField.TEXT)) {
            val value = field.of(target).trim()
            if (value.isEmpty()) continue
            val values = samples.map { field.of(it).trim() }
            template(value, values)?.let { (re, shown) -> add(field, re, SuggestionKind.LIKE, shown, SuggestionGroup.SIMILAR) }
            digitPattern(value)?.let { (re, shown) -> add(field, re, SuggestionKind.LIKE, shown, SuggestionGroup.SIMILAR) }
            commonPrefixes(value, values).forEach { add(field, "^" + Rule.escape(it), SuggestionKind.STARTS_WITH, it, SuggestionGroup.SIMILAR) }
            commonSuffixes(value, values).forEach { add(field, Rule.escape(it) + "$", SuggestionKind.ENDS_WITH, it, SuggestionGroup.SIMILAR) }
            recurringWords(value, values).forEach {
                add(field, "(?i)\\b" + Rule.escape(it) + "\\b", SuggestionKind.CONTAINS, it, SuggestionGroup.CONTAINS)
            }
            add(field, "^" + Rule.escape(value) + "$", SuggestionKind.IS, shorten(value), SuggestionGroup.EXACT)
        }
        // Words from anywhere in the notification, for rules that don't care where they appear.
        recurringWords(target.content, samples.map { it.content }).forEach {
            add(MatchField.ANY, "(?i)\\b" + Rule.escape(it) + "\\b", SuggestionKind.MENTIONS, it, SuggestionGroup.CONTAINS)
        }
        // Exceptions: catch the rest of the app, but not things like this one.
        target.title.trim().takeIf { it.isNotEmpty() }?.let { title ->
            add(MatchField.TITLE, "^" + Rule.escape(title) + "$", SuggestionKind.EXCEPT_TITLE, shorten(title), SuggestionGroup.EXCEPT, invert = true)
        }
        template(target.title.trim(), samples.map { it.title.trim() })?.let { (re, shown) ->
            add(MatchField.TITLE, re, SuggestionKind.EXCEPT_TITLES_LIKE, shown, SuggestionGroup.EXCEPT, invert = true)
        }

        val everythingSet = samples.indices.toSet()
        val scored = candidates.mapNotNull { c ->
            val re = Rule.compileOrNull(c.regex) ?: return@mapNotNull null
            val hits = samples.indices.filterTo(HashSet()) { re.containsMatchIn(c.field.of(samples[it])) != c.invert }
            // A rule made from a notification must catch it; an exception must leave it out.
            if ((0 in hits) == c.invert || hits.isEmpty()) return@mapNotNull null
            val examples = hits.filter { it != 0 }.sorted().take(EXAMPLES).map { samples[it] }
            Suggestion(c.label, c.field, c.regex, hits.size, samples.size, c.invert, c.group, examples, c.kind, c.value) to hits
        }
            .filter { it.second != everythingSet || samples.size == 1 }
            // Same notifications caught the same way is a duplicate; a different field or kind of
            // pattern is a real alternative, since it behaves differently on future notifications.
            .distinctBy { Triple(it.first.field, it.first.group, it.second) }
            .sortedByDescending { it.first.matches }
            .map { it.first }

        val everything = Suggestion(
            "Everything from ${target.appName}", MatchField.ANY, "", samples.size, samples.size,
            group = SuggestionGroup.EVERYTHING,
            examples = samples.drop(1).take(EXAMPLES),
            kind = SuggestionKind.EVERYTHING,
            value = target.appName,
        )
        return scored.take(MAX - 1) + everything
    }

    private fun englishLabel(kind: SuggestionKind, field: MatchField, value: String): String {
        val name = if (field == MatchField.TEXT) "Text" else "Title"
        return when (kind) {
            SuggestionKind.LIKE -> "$name like “$value”"
            SuggestionKind.STARTS_WITH -> "$name starts with “$value”"
            SuggestionKind.ENDS_WITH -> "$name ends with “$value”"
            SuggestionKind.CONTAINS -> "$name contains “$value”"
            SuggestionKind.IS -> "$name is “$value”"
            SuggestionKind.MENTIONS -> "Mentions “$value”"
            SuggestionKind.EXCEPT_TITLE -> "Everything except title “$value”"
            SuggestionKind.EXCEPT_TITLES_LIKE -> "Everything except titles like “$value”"
            SuggestionKind.EVERYTHING -> "Everything from $value"
        }
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

    /** Word-aligned endings [value] shares with at least one other notification, most shared first. */
    private fun commonSuffixes(value: String, values: List<String>): List<String> =
        commonPrefixes(value.reversed(), values.map { it.reversed() }).map { it.reversed().trimStart() }
            .filter { it.length >= 4 && values.count { v -> v.endsWith(it) } >= 2 }

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
            .take(5)
            .map { it.first }
            .toList()

    private fun shorten(text: String) = text.replace('\n', ' ').let { if (it.length > 40) it.take(39) + "…" else it }
}
