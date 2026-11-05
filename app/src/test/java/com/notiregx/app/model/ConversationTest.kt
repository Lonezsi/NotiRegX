package com.notiregx.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private val hideReactions = listOf(Rule(id = "r", name = "reactions", regex = "Reacted", packageName = "com.facebook.orca"))

    private fun msg(time: Long, text: String, fromMe: Boolean = false) = ChatMessage(
        id = "$time|Anna|${text.hashCode()}",
        notification = Notification(0, "com.facebook.orca", "Messenger", "Anna", text, postedAt = time),
        fromMe = fromMe,
    )

    /** Mimics the listener: decide each unseen message, then ask whether the whole notification can go. */
    private fun post(tracker: ConversationTracker, manager: RuleManager, messages: List<ChatMessage>): Pair<List<Action>, Boolean> {
        val actions = tracker.unseen("chat", messages).map { m ->
            manager.process(m.notification, maxSaved = 100).action.also { tracker.record("chat", m.id, it) }
        }
        return actions to tracker.allFiltered("chat")
    }

    @Test
    fun aReactionInAConversationDoesNotRemoveTheRealMessages() {
        val tracker = ConversationTracker()
        val manager = RuleManager(hideReactions)

        val (first, removeFirst) = post(tracker, manager, listOf(msg(1, "Hey!"), msg(2, "Are you coming tonight?")))
        assertEquals(listOf(Action.PASS, Action.PASS), first)
        assertFalse(removeFirst)

        // The app re-posts the conversation with a reaction added at the end.
        val (second, removeSecond) = post(
            tracker, manager,
            listOf(msg(1, "Hey!"), msg(2, "Are you coming tonight?"), msg(3, "Reacted ❤️ to your message")),
        )
        assertEquals(listOf(Action.HIDE), second) // only the new line is decided, and it's caught
        assertFalse(removeSecond) // the real messages keep the notification
        assertEquals(1, manager.rules.value[0].history.size)
    }

    @Test
    fun aConversationOfOnlyFilteredMessagesIsRemoved() {
        val tracker = ConversationTracker()
        val (_, remove) = post(tracker, RuleManager(hideReactions), listOf(msg(1, "Reacted 😂 to your message")))
        assertTrue(remove)
    }

    @Test
    fun ownRepliesAreIgnoredBothWays() {
        val tracker = ConversationTracker()
        val manager = RuleManager(hideReactions)
        val (actions, remove) = post(
            tracker, manager,
            listOf(msg(1, "see you!", fromMe = true), msg(2, "Reacted 👍 to your message")),
        )
        assertEquals(listOf(Action.HIDE), actions) // my reply isn't run through the rules
        assertTrue(remove) // and doesn't keep the notification alive either
    }

    @Test
    fun messagesThatLeaveTheNotificationAreForgotten() {
        val tracker = ConversationTracker()
        val manager = RuleManager(hideReactions)
        post(tracker, manager, listOf(msg(1, "Hey!"), msg(2, "Reacted ❤️ to your message")))
        // The app drops the old real message (e.g. it was read elsewhere): only the reaction is left.
        val (actions, remove) = post(tracker, manager, listOf(msg(2, "Reacted ❤️ to your message")))
        assertTrue(actions.isEmpty())
        assertTrue(remove)
    }

    @Test
    fun repostsWithNothingNewDecideNothing() {
        val tracker = ConversationTracker()
        val manager = RuleManager(hideReactions)
        val messages = listOf(msg(1, "Hey!"))
        post(tracker, manager, messages)
        assertTrue(post(tracker, manager, messages).first.isEmpty())
        assertEquals(1, manager.recent.value.size)
    }
}
