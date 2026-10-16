package com.notiregx.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.ui.MainActivity

/** Swiping a bundle notification away counts as having seen its contents. */
class BundleDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(MainActivity.EXTRA_RULE_ID) ?: return
        (context.applicationContext as NotiRegXApp).ruleManager.markSeen(ruleId)
    }
}
