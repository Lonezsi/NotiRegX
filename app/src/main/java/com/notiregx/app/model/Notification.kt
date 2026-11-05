package com.notiregx.app.model

/** A captured notification, independent of Android's own notification objects. */
data class Notification(
    val id: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val url: String? = null,
    /** For chat messages in a group conversation: the group's name. Null for direct chats and everything else. */
    val group: String? = null,
    /** The person (see [PeopleBook]) whose username sent it, if any. */
    val personId: String? = null,
) {
    /** The text rules match against: title and body on separate lines. */
    val content: String
        get() = listOf(title, text).filter { it.isNotEmpty() }.joinToString("\n")
}
