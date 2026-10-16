package com.notiregx.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.model.VibrationLog
import com.notiregx.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick-settings tile answering "what just buzzed?"; tapping it opens the vibration log. */
class LastBuzzTileService : TileService() {
    private val app get() = application as NotiRegXApp
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        update()
        scope = MainScope().apply {
            launch {
                app.refreshSystemVibrations()
                update()
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_VIBRATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val last = VibrationLog.timeline(app.vibrationLog.events.value).firstOrNull()
        val text = last?.let {
            val ago = DateUtils.getRelativeTimeSpanString(it.event.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            (if (it.isGhost) "Ghost · " else "") + "${it.event.appName} · $ago"
        } ?: "Nothing yet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = "Last buzz"
            tile.subtitle = text
        } else {
            tile.label = text
        }
        tile.contentDescription = "Last vibration: $text"
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
