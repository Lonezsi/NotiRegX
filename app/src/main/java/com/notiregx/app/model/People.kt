package com.notiregx.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** One username a person uses in one app: the notification title their messages arrive under. */
data class Alias(val packageName: String, val appName: String, val sender: String) {
    val key: String get() = keyOf(packageName, sender)

    companion object {
        fun keyOf(packageName: String, sender: String) = packageName + "\u0000" + sender.trim().lowercase()
    }
}

/** Someone the user cares about, across all the apps they message from. */
data class Person(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val aliases: List<Alias> = emptyList(),
    val favorite: Boolean = false,
    /** Keep every notification from them in their timeline. */
    val saveAll: Boolean = true,
    /** Display name of the linked phone contact, if any. */
    val contactName: String? = null,
    /** Whether a contact photo was saved for them (see the app's people photo folder). */
    val hasPhoto: Boolean = false,
    /** Remind the user when they haven't heard from this person in this many days; null: never. */
    val remindAfterDays: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val packages: List<String> get() = aliases.map { it.packageName }.distinct()
}

/** A notification kept in a person's timeline. [deleted]: the sender unsent it afterwards. */
data class PersonEntry(val personId: String, val notification: Notification, val deleted: Boolean = false)

/** What happens to best friends' messages, whatever the rules say. */
enum class FriendMode { OFF, ALLOW, ALERT }

/** The user's people, their usernames, and the notifications saved for them. */
class PeopleBook(initialPeople: List<Person> = emptyList(), initialLog: List<PersonEntry> = emptyList()) {
    private val _people = MutableStateFlow(initialPeople)
    val people: StateFlow<List<Person>> = _people.asStateFlow()

    private val _log = MutableStateFlow(initialLog)
    /** Saved notifications, newest first. */
    val log: StateFlow<List<PersonEntry>> = _log.asStateFlow()

    fun personFor(packageName: String, sender: String): Person? = personFor(_people.value, packageName, sender)

    /** Saves [n] to its sender's timeline if they're a person with "save everything" on. Returns the person. */
    fun record(n: Notification): Person? {
        val person = personFor(n.packageName, n.title) ?: return null
        if (person.saveAll) {
            _log.update { log ->
                val mine = log.count { it.personId == person.id }
                val trimmed = if (mine < MAX_PER_PERSON) log else {
                    // Drop this person's oldest entry to make room.
                    val oldest = log.indexOfLast { it.personId == person.id }
                    log.filterIndexed { i, _ -> i != oldest }
                }
                (listOf(PersonEntry(person.id, n)) + trimmed).take(MAX_TOTAL)
            }
        }
        return person
    }

    /** Creates a person; usernames already belonging to someone else move to them. */
    fun create(name: String, aliases: List<Alias>, contactName: String? = null): Person {
        val person = Person(name = name.trim(), aliases = aliases.distinctBy { it.key }, contactName = contactName)
        _people.update { list -> list.map { it.withoutAliases(person.aliases) } + person }
        return person
    }

    fun update(id: String, transform: (Person) -> Person) = _people.update { list ->
        val updated = list.firstOrNull { it.id == id }?.let(transform) ?: return@update list
        list.map { if (it.id == id) updated else it.withoutAliases(updated.aliases) }
    }

    fun addAliases(id: String, aliases: List<Alias>) = update(id) { p -> p.copy(aliases = (p.aliases + aliases).distinctBy { it.key }) }

    fun removeAlias(id: String, alias: Alias) = update(id) { p -> p.copy(aliases = p.aliases.filter { it.key != alias.key }) }

    /** Folds [otherIds] into [intoId]: usernames, saved notifications, and the star. */
    fun merge(intoId: String, otherIds: Collection<String>) {
        val others = _people.value.filter { it.id in otherIds && it.id != intoId }
        if (others.isEmpty()) return
        _people.update { list ->
            list.filter { it !in others }.map { p ->
                if (p.id != intoId) p
                else p.copy(
                    aliases = (p.aliases + others.flatMap { it.aliases }).distinctBy { it.key },
                    favorite = p.favorite || others.any { it.favorite },
                    contactName = p.contactName ?: others.firstNotNullOfOrNull { it.contactName },
                )
            }
        }
        val ids = others.mapTo(HashSet()) { it.id }
        _log.update { log -> log.map { if (it.personId in ids) it.copy(personId = intoId) else it } }
    }

    /**
     * Adds already-known notifications (Recent log, rule histories) from [personId]'s usernames to
     * their timeline, so it isn't empty when someone is just created. Skips ones already there.
     */
    fun backfill(personId: String, known: List<Notification>) {
        val person = _people.value.firstOrNull { it.id == personId } ?: return
        if (!person.saveAll) return
        val keys = person.aliases.mapTo(HashSet()) { it.key }
        _log.update { log ->
            val have = log.filter { it.personId == personId }.mapTo(HashSet()) { it.notification.dedupeKey() }
            val found = known
                .filter { Alias.keyOf(it.packageName, it.title) in keys }
                .distinctBy { it.dedupeKey() }
                .filter { it.dedupeKey() !in have }
                .map { PersonEntry(personId, it) }
            if (found.isEmpty()) log
            else (log + found).sortedByDescending { it.notification.postedAt }.take(MAX_TOTAL)
        }
    }

    private fun Notification.dedupeKey() = Triple(packageName, postedAt, content)

    fun delete(id: String) {
        _people.update { list -> list.filter { it.id != id } }
        _log.update { log -> log.filter { it.personId != id } }
    }

    /** Marks a saved message as unsent by its sender (it stays saved). Returns the person, if it was theirs. */
    fun markDeleted(n: Notification): Person? {
        var personId: String? = null
        _log.update { log ->
            val i = log.indexOfFirst {
                !it.deleted && it.notification.packageName == n.packageName && it.notification.title == n.title && it.notification.text == n.text
            }
            if (i < 0) log else {
                personId = log[i].personId
                log.toMutableList().apply { this[i] = this[i].copy(deleted = true) }
            }
        }
        return personId?.let { id -> _people.value.firstOrNull { it.id == id } }
    }

    fun clearLog(id: String) = _log.update { log -> log.filter { it.personId != id } }

    private fun Person.withoutAliases(taken: List<Alias>): Person {
        val keys = taken.mapTo(HashSet()) { it.key }
        return if (aliases.none { it.key in keys }) this else copy(aliases = aliases.filter { it.key !in keys })
    }

    companion object {
        const val MAX_PER_PERSON = 2000
        const val MAX_TOTAL = 20000

        /** The phone's own apps (battery, system UI, Samsung services): their notification titles aren't people. */
        fun isSystemApp(packageName: String): Boolean =
            packageName == "android" || packageName.startsWith("com.android.") ||
                packageName.startsWith("com.samsung.") || packageName.startsWith("com.sec.")

        fun personFor(people: List<Person>, packageName: String, sender: String): Person? {
            val key = Alias.keyOf(packageName, sender)
            return people.firstOrNull { p -> p.aliases.any { it.key == key } }
        }

        /**
         * Rules that make best friends' messages get through (or always alert), whatever the user's
         * own rules say. They're evaluated after the user's rules, so they win ("last match wins").
         */
        fun friendRules(people: List<Person>, mode: FriendMode): List<Rule> {
            if (mode == FriendMode.OFF) return emptyList()
            val type = if (mode == FriendMode.ALERT) RuleType.ALERT else RuleType.ALLOW
            return people.filter { it.favorite }
                .flatMap { it.aliases }
                .groupBy { it.packageName }
                .map { (pkg, aliases) ->
                    Rule(
                        id = "friends:$pkg",
                        name = "Best friends",
                        regex = "(?i)" + PeoplePattern.build(aliases.map { it.sender }.distinct()),
                        field = MatchField.TITLE,
                        packageName = pkg,
                        type = type,
                    )
                }
        }

        /**
         * The person part of a sender title. Instagram, for one, prefixes the account that got the
         * message ("my.account: Anna Kovács"), which shouldn't stop Anna's usernames from lining up.
         */
        fun displayName(sender: String): String {
            val prefix = sender.substringBefore(": ", "")
            return if (prefix.isNotEmpty() && prefix.none { it.isWhitespace() }) sender.substringAfter(": ").trim() else sender.trim()
        }

        /**
         * Senders who look like the same person in different apps (same name, ignoring case and
         * account prefixes) and aren't anyone's username yet, busiest first.
         */
        fun mergeSuggestions(senders: Collection<SenderCount>, people: List<Person>, ignored: Set<String> = emptySet()): List<List<SenderCount>> {
            val taken = people.flatMap { it.aliases }.mapTo(HashSet()) { it.key }
            return senders
                .filter { Alias.keyOf(it.packageName, it.sender) !in taken && !isSystemApp(it.packageName) }
                .groupBy { displayName(it.sender).lowercase() }
                .filterKeys { it.length >= 2 && it !in ignored }
                .values
                .filter { group -> group.map { it.packageName }.distinct().size >= 2 }
                .sortedByDescending { group -> group.sumOf { it.total } }
        }
    }
}
