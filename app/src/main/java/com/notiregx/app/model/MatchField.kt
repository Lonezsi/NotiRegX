package com.notiregx.app.model

import androidx.annotation.StringRes
import com.notiregx.app.R

/** Which part of a notification a rule's regex is searched in. */
enum class MatchField(@StringRes val label: Int) {
    ANY(R.string.field_any),
    TITLE(R.string.field_title),
    TEXT(R.string.field_text),

    /** A chat group's name (empty for direct chats and other notifications). */
    GROUP(R.string.field_group);

    fun of(n: Notification): String = when (this) {
        ANY -> n.content
        TITLE -> n.title
        TEXT -> n.text
        GROUP -> n.group.orEmpty()
    }
}
