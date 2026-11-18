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

    /** A message the sender changed: what it said [before], and the message it is [now]. */
    class Edit(val before: Notification, val now: ChatMessage)

    /**
     * [fresh]: messages (from other people) not decided yet. [deleted]: messages that disappeared
     * from the middle of the conversation while later ones stayed, i.e. the sender unsent them.
     * (Messages dropping off the start are just the app trimming old ones.) [edited]: messages
     * the sender changed: one vanished from the middle and a new one from the same sender took
     * its place (or kept its timestamp), which is an edit, not an unsend and not a new message.
     */
    class Update(val fresh: List<ChatMessage>, val deleted: List<Notification>, val edited: List<Edit> = emptyList())

    fun update(key: String, messages: List<ChatMessage>): Update {
        val known = conversations.getOrPut(key) { LinkedHashMap() }
        val present = messages.mapTo(HashSet()) { it.id }
        val order = known.keys.toList()
        // Gone from before the first message that's still there: trimmed. Gone from after it: unsent, or edited.
        val firstPresent = order.indexOfFirst { it in present }
        val gone = order.withIndex().filter { (i, id) -> firstPresent >= 0 && i > firstPresent && id !in present }

        // A message's id is its sender and text, so an edit looks like the old one leaving and a
        // new one arriving. Pair them up: same sender, and in the same place between the
        // messages around it, or with the same timestamp.
        val position = messages.withIndex().associate { (i, m) -> m.id to i }
        val taken = HashSet<String>()
        val edits = ArrayList<Edit>()
        val kept = HashMap<String, Action>() // an edited message's new id -> the original's decision
        val deleted = ArrayList<Notification>()
        for ((i, id) in gone) {
            val before = known[id]?.notification ?: continue
            val after = order.subList(0, i).lastOrNull { it in present }?.let { position[it] } ?: -1
            val until = order.subList(i + 1, order.size).firstOrNull { it in present }?.let { position[it] } ?: messages.size
            fun newFromSender(m: ChatMessage) = !m.fromMe && m.id !in known && m.id !in taken && m.notification.title == before.title
            val now = messages.withIndex().firstOrNull { (j, m) -> j in (after + 1) until until && newFromSender(m) }?.value
                ?: messages.firstOrNull { newFromSender(it) && it.notification.postedAt == before.postedAt }
            if (now != null) {
                taken += now.id
                edits += Edit(before, now)
                kept[now.id] = known.getValue(id).action
            } else {
                deleted += before
            }
        }

        // the edited messages keep their place and the decision the original had
        known.keys.retainAll(present)
        edits.forEach { known[it.now.id] = Known(kept.getValue(it.now.id), it.now.notification) }
        return Update(messages.filter { !it.fromMe && it.id !in known }, deleted, edits)
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

    /** Whether [key] is a conversation decided message by message. */
    fun knows(key: String): Boolean = key in conversations

    fun forget(key: String) {
        conversations.remove(key)
    }
}
