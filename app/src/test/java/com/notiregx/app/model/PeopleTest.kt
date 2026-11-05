package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    private val messenger = "com.facebook.orca"
    private val snap = "com.snapchat.android"
    private val insta = "com.instagram.android"
    private var nextId = 1L

    private fun n(pkg: String, title: String, text: String = "hi") =
        Notification(nextId++, pkg, pkg, title, text, postedAt = nextId)

    private fun sender(pkg: String, name: String, total: Long) =
        SenderCount(pkg, pkg, name, total, emptyMap(), 0)

    @Test
    fun usernamesInDifferentAppsBelongToOnePerson() {
        val book = PeopleBook()
        val zeno = book.create("Zénó", listOf(Alias(messenger, "Messenger", "Zénó"), Alias(snap, "Snapchat", "zeno.k")))
        assertEquals(zeno.id, book.personFor(messenger, "zénó")?.id) // case-insensitive
        assertEquals(zeno.id, book.personFor(snap, "zeno.k")?.id)
        assertNull(book.personFor(insta, "Zénó")) // same name in another app isn't them until added
    }

    @Test
    fun aUsernameBelongsToOnlyOnePerson() {
        val book = PeopleBook()
        val a = book.create("A", listOf(Alias(messenger, "Messenger", "Sam")))
        val b = book.create("B", listOf(Alias(messenger, "Messenger", "Sam")))
        assertTrue(book.people.value.first { it.id == a.id }.aliases.isEmpty())
        assertEquals(b.id, book.personFor(messenger, "Sam")?.id)
    }

    @Test
    fun savesEveryNotificationFromAPersonUnlessTurnedOff() {
        val book = PeopleBook()
        val anna = book.create("Anna", listOf(Alias(messenger, "Messenger", "Anna")))
        book.record(n(messenger, "Anna", "one"))
        book.record(n(messenger, "Bob", "not her"))
        book.record(n(messenger, "Anna", "two"))
        assertEquals(listOf("two", "one"), book.log.value.map { it.notification.text })

        book.update(anna.id) { it.copy(saveAll = false) }
        book.record(n(messenger, "Anna", "three"))
        assertEquals(2, book.log.value.size)
    }

    @Test
    fun mergeCombinesUsernamesTimelinesAndStars() {
        val book = PeopleBook()
        val a = book.create("Zénó", listOf(Alias(messenger, "Messenger", "Zénó")))
        val b = book.create("zeno.k", listOf(Alias(snap, "Snapchat", "zeno.k")))
        book.update(b.id) { it.copy(favorite = true) }
        book.record(n(snap, "zeno.k"))
        book.merge(a.id, listOf(b.id))
        val people = book.people.value
        assertEquals(1, people.size)
        assertEquals(2, people[0].aliases.size)
        assertTrue(people[0].favorite)
        assertEquals(a.id, book.log.value.single().personId)
    }

    @Test
    fun bestFriendsBeatTheUsersRules() {
        val book = PeopleBook()
        val anna = book.create("Anna", listOf(Alias(messenger, "Messenger", "Anna")))
        book.update(anna.id) { it.copy(favorite = true) }
        val manager = RuleManager(listOf(Rule(id = "h", name = "hide messenger", packageName = messenger)))

        val off = PeopleBook.friendRules(book.people.value, FriendMode.OFF)
        assertEquals(Action.HIDE, manager.process(n(messenger, "Anna"), 10, extraRules = off).action)

        val alert = PeopleBook.friendRules(book.people.value, FriendMode.ALERT)
        val d = manager.process(n(messenger, "ANNA"), 10, extraRules = alert)
        assertEquals(Action.PASS, d.action)
        assertEquals(RuleType.ALERT, d.rule?.type)
        assertEquals(Action.HIDE, manager.process(n(messenger, "Bob"), 10, extraRules = alert).action)
    }

    @Test
    fun suggestsMergingTheSameNameAcrossApps() {
        val senders = listOf(
            sender(messenger, "Zénó", 10),
            sender(snap, "zénó", 3),
            sender(insta, "my.account: Hedda Mayer", 7),
            sender(messenger, "Hedda Mayer", 2),
            sender(messenger, "Bob", 50), // only one app
        )
        val suggestions = PeopleBook.mergeSuggestions(senders, emptyList())
        assertEquals(2, suggestions.size)
        assertEquals(setOf("Zénó", "zénó"), suggestions[0].map { it.sender }.toSet())
        assertEquals(setOf(insta, messenger), suggestions[1].map { it.packageName }.toSet())

        // Once Zénó is a person, he's no longer suggested.
        val book = PeopleBook()
        book.create("Zénó", listOf(Alias(messenger, "Messenger", "Zénó")))
        assertFalse(PeopleBook.mergeSuggestions(senders, book.people.value).any { g -> g.any { it.sender == "zénó" } })
    }

    @Test
    fun displayNameDropsAccountPrefixesOnly() {
        assertEquals("Hedda Mayer", PeopleBook.displayName("it.is.me.jozsef: Hedda Mayer"))
        assertEquals("Mom said hi", PeopleBook.displayName("Family: Mom said hi"))
        assertEquals("Work chat: Anna", PeopleBook.displayName("Work chat: Anna"))
    }
}
