package com.notiregx.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notiregx.app.NotiRegXApp

/**
 * Fires at digest delivery times (and after a reboot, which restarts the app and its schedule).
 * Releasing due digests happens in [NotiRegXApp.deliverDueDigests].
 */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as NotiRegXApp).deliverDueDigests()
    }
}
