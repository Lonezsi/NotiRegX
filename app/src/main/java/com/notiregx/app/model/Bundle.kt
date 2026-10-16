package com.notiregx.app.model

/**
 * References (by id) to the unseen notifications in a rule's history, newest first.
 * [released] marks a digest bundle whose delivery time has come, so it may show itself.
 */
data class Bundle(val unseen: List<Long> = emptyList(), val released: Boolean = false) {
    val count: Int get() = unseen.size

    fun add(id: Long) = copy(unseen = listOf(id) + unseen.filter { it != id })

    /** Drops references to notifications that are no longer in the history. */
    fun retain(ids: Set<Long>) = copy(unseen = unseen.filter { it in ids })

    fun markAllSeen() = Bundle()
}
