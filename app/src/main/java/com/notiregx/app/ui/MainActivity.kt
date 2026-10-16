package com.notiregx.app.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.model.Notification

class MainActivity : ComponentActivity() {
    private val app get() = application as NotiRegXApp

    /** Rule whose archive should be shown, set when a bundle notification is tapped. */
    private val openRuleId = mutableStateOf<String?>(null)

    /** Bumped on every resume so permission states are re-read after visiting system settings. */
    private val resumeTick = mutableIntStateOf(0)

    private val companionChooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        app.companionLinked.value = app.settings.isCompanionLinked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val actions = AppActions(
            openNotification = ::openNotification,
            openUrl = ::openUrl,
            openIntent = ::startSafely,
            linkCompanion = ::linkCompanion,
            canOpenDirectly = { app.contentIntents.get(it.id) != null },
        )
        setContent {
            NotiRegXTheme {
                AppRoot(app, openRuleId, resumeTick.intValue, actions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        app.companionLinked.value = app.settings.isCompanionLinked()
    }

    /**
     * Shows the system's companion device picker. Any nearby Bluetooth device will do: the
     * association only unlocks changing other apps' notification channels, nothing is sent to it.
     */
    private fun linkCompanion() {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Still called by onAssociationPending's default implementation")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                companionChooser.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                toast("Couldn't link a device${error?.let { ": $it" } ?: ""}")
            }
        }
        @Suppress("DEPRECATION")
        getSystemService(CompanionDeviceManager::class.java).associate(request, callback, null)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_RULE_ID)?.let { openRuleId.value = it }
    }

    /** Fires the original notification's click action, or falls back to launching its app. */
    private fun openNotification(n: Notification) {
        app.contentIntents.get(n.id)?.let { pending ->
            try {
                pending.send(this, 0, null, null, null, null, backgroundStartOptions())
                return
            } catch (e: PendingIntent.CanceledException) {
                app.contentIntents.remove(n.id)
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(n.packageName)
        if (launch != null) startSafely(launch) else toast("Can't open ${n.appName}")
    }

    @Suppress("DEPRECATION")
    private fun backgroundStartOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }

    private fun openUrl(url: String) = startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Nothing on this phone can open that")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_RULE_ID = "com.notiregx.app.RULE_ID"
        val POST_NOTIFICATIONS: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    }
}

class AppActions(
    val openNotification: (Notification) -> Unit,
    val openUrl: (String) -> Unit,
    val openIntent: (Intent) -> Unit,
    val linkCompanion: () -> Unit,
    val canOpenDirectly: (Notification) -> Boolean,
)
