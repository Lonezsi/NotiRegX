package com.notiregx.app.model

enum class WatchdogAction {
    NOTHING,

    /** Ask Android to reconnect the notification listener. */
    REBIND,

    /** A reconnect didn't help: try again and tell the user. */
    WARN,

    /** Connected: clear any earlier warning. */
    CLEAR,
}

/**
 * Decides what to do about the notification listener. Android (and Samsung's battery management in
 * particular) can unbind it silently, after which nothing is filtered until it's reconnected.
 */
object WatchdogPolicy {
    /** Right after the app starts, the system may not have bound the listener yet. */
    const val STARTUP_GRACE_MS = 90_000L

    /** How long a reconnect gets before it counts as failed. */
    const val REBIND_WAIT_MS = 60_000L

    fun decide(hasAccess: Boolean, connected: Boolean, sinceStartMs: Long, sinceRebindMs: Long?): WatchdogAction = when {
        // Without notification access there's nothing to reconnect; the app shows a banner for that.
        !hasAccess -> WatchdogAction.NOTHING
        connected -> WatchdogAction.CLEAR
        sinceStartMs < STARTUP_GRACE_MS -> WatchdogAction.NOTHING
        sinceRebindMs == null -> WatchdogAction.REBIND
        sinceRebindMs >= REBIND_WAIT_MS -> WatchdogAction.WARN
        else -> WatchdogAction.NOTHING
    }
}
