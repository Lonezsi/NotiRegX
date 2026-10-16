package com.notiregx.app.model

/** What happens to a notification when this rule is the final effective match. */
enum class RuleType(val label: String, val explanation: String) {
    HIDE("Hide", "Removed from the phone and saved to this rule's history."),
    BUNDLE("Bundle", "Removed from the phone and collected into this rule's bundle notification."),

    /** An exception: lets a later rule carve a hole in an earlier HIDE/BUNDLE rule. */
    ALLOW("Let through", "Delivered normally. Use it below a Hide/Bundle rule to make an exception."),
}
