package com.notiregx.app.model

/** What happens to a notification when this rule is the final effective match. */
enum class RuleType(val label: String, val shortLabel: String, val explanation: String) {
    HIDE("Hide", "Hide", "Removed from the phone and saved to this rule's history."),
    BUNDLE("Bundle", "Bundle", "Removed from the phone and collected into this rule's bundle notification."),

    /** An exception: lets a later rule carve a hole in an earlier HIDE/BUNDLE rule. */
    ALLOW("Let through", "Allow", "Delivered normally. Use it below a Hide/Bundle rule to make an exception."),

    /** Let through, and make sure it's noticed: NotiRegX alerts even in Do Not Disturb or when the app is silenced. */
    ALERT(
        "Always alert",
        "Alert",
        "Delivered, and always alerts: NotiRegX buzzes and rings for it even in Do Not Disturb or when the " +
            "app is silenced. Use it for urgent words or important people.",
    );

    /** Whether the notification stays on the phone (nothing is caught or saved). */
    val letsThrough: Boolean get() = this == ALLOW || this == ALERT
}
