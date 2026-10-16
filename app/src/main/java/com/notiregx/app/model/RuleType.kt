package com.notiregx.app.model

import androidx.annotation.StringRes
import com.notiregx.app.R

/** What happens to a notification when this rule is the final effective match. */
enum class RuleType(@StringRes val label: Int, @StringRes val shortLabel: Int, @StringRes val explanation: Int) {
    HIDE(R.string.type_hide, R.string.type_hide_short, R.string.type_hide_explanation),
    BUNDLE(R.string.type_bundle, R.string.type_bundle_short, R.string.type_bundle_explanation),

    /** An exception: lets a later rule carve a hole in an earlier HIDE/BUNDLE rule. */
    ALLOW(R.string.type_allow, R.string.type_allow_short, R.string.type_allow_explanation),

    /** Let through, and make sure it's noticed: NotiRegX alerts even in Do Not Disturb or when the app is silenced. */
    ALERT(R.string.type_alert, R.string.type_alert_short, R.string.type_alert_explanation);

    /** Whether the notification stays on the phone (nothing is caught or saved). */
    val letsThrough: Boolean get() = this == ALLOW || this == ALERT
}
