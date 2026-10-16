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
) {
    /** The text rules match against: title and body on separate lines. */
    val content: String
        get() = listOf(title, text).filter { it.isNotEmpty() }.joinToString("\n")
}
