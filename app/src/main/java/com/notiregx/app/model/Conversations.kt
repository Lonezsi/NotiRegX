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
    private val conversations = object : LinkedHashMap<String, LinkedHashMap<String, Action>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Action>>?) =
            size > maxConversations
    }

    /**
     * The messages in [messages] (from other people) not decided yet for conversation [key].
     * Messages no longer in the notification are forgotten.
     */
    fun unseen(key: String, messages: List<ChatMessage>): List<ChatMessage> {
        val known = conversations.getOrPut(key) { LinkedHashMap() }
        val present = messages.mapTo(HashSet()) { it.id }
        known.keys.retainAll(present)
        return messages.filter { !it.fromMe && it.id !in known }
    }

    fun record(key: String, messageId: String, action: Action) {
        conversations.getOrPut(key) { LinkedHashMap() }[messageId] = action
    }

    /** Whether the whole conversation notification can go: every message in it from others is filtered. */
    fun allFiltered(key: String): Boolean {
        val actions = conversations[key]?.values ?: return false
        return actions.isNotEmpty() && actions.all { it != Action.PASS }
    }

    fun forget(key: String) {
        conversations.remove(key)
    }
}
