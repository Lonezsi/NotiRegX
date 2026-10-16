package com.notiregx.app.model

/** References (by id) to the unseen notifications in a rule's history, newest first. */
data class Bundle(val unseen: List<Long> = emptyList()) {
    val count: Int get() = unseen.size

    fun add(id: Long) = Bundle(listOf(id) + unseen.filter { it != id })

    /** Drops references to notifications that are no longer in the history. */
    fun retain(ids: Set<Long>) = Bundle(unseen.filter { it in ids })

    fun markAllSeen() = Bundle()
}
