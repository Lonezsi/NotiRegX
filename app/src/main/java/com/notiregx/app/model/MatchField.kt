package com.notiregx.app.model

/** Which part of a notification a rule's regex is searched in. */
enum class MatchField(val label: String) {
    ANY("Title + text"),
    TITLE("Title"),
    TEXT("Text");

    fun of(n: Notification): String = when (this) {
        ANY -> n.content
        TITLE -> n.title
        TEXT -> n.text
    }
}
