package com.notiregx.app.model

/** One message inside a chat app's conversation notification. */
data class ChatMessage(
    /** Stable within the conversation: time, sender and text. */
    val id: String,
    val notification: Notification,
    /** The user's own reply, shown in the conversation; never filtered and never a reason to keep it. */
    val fromMe: Boolean,
)

/**
 * Chat apps keep one notification per conversation and add each new message to it. Filtering the
 * whole notification because its newest line matched (say, "Reacted ❤️ to your message") would
 * also remove the friend's real messages, so each message is decided on its own and the
 * notification only goes away when everything in it from other people is filtered.
 */
class ConversationTracker(private val maxConversations: Int = 300) {
    private class Known(val action: Action, val notification: Notification?)

    private val conversations = object : LinkedHashMap<String, LinkedHashMap<String, Known>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Known>>?) =
            size > maxConversations
    }

    /**
     * [fresh]: messages (from other people) not decided yet. [deleted]: messages that disappeared
     * from the middle of the conversation while later ones stayed, i.e. the sender unsent them.
     * (Messages dropping off the start are just the app trimming old ones.)
     */
    class Update(val fresh: List<ChatMessage>, val deleted: List<Notification>)

    fun update(key: String, messages: List<ChatMessage>): Update {
        val known = conversations.getOrPut(key) { LinkedHashMap() }
        val present = messages.mapTo(HashSet()) { it.id }
        val order = known.keys.toList()
        // Gone from before the first message that's still there: trimmed. Gone from after it: unsent.
        val firstPresent = order.indexOfFirst { it in present }
        val deleted = order.withIndex()
            .filter { (i, id) -> firstPresent >= 0 && i > firstPresent && id !in present }
            .mapNotNull { (_, id) -> known[id]?.notification }
        known.keys.retainAll(present)
        return Update(messages.filter { !it.fromMe && it.id !in known }, deleted)
    }

    fun unseen(key: String, messages: List<ChatMessage>): List<ChatMessage> = update(key, messages).fresh

    fun record(key: String, messageId: String, action: Action, notification: Notification? = null) {
        conversations.getOrPut(key) { LinkedHashMap() }[messageId] = Known(action, notification)
    }

    /** Whether the whole conversation notification can go: every message in it from others is filtered. */
    fun allFiltered(key: String): Boolean {
        val actions = conversations[key]?.values?.map { it.action } ?: return false
        return actions.isNotEmpty() && actions.all { it != Action.PASS }
    }

    fun forget(key: String) {
        conversations.remove(key)
    }
}
