package com.notiregx.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notiregx.app.MuteStatus
import com.notiregx.app.Settings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    resumeTick: Int,
    companionLinked: Boolean,
    muteStatus: MuteStatus,
    onLinkCompanion: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    listenerConnected: Boolean,
    onRestartListener: () -> Unit,
    onShowTutorial: () -> Unit,
    onOpenIntent: (Intent) -> Unit,
) {
    val filtering by settings.filteringEnabled.collectAsState()
    val maxSaved by settings.maxSaved.collectAsState()
    var sliderValue by remember(maxSaved) { mutableFloatStateOf(maxSaved.toFloat()) }

    val hasAccess = remember(resumeTick) { settings.hasNotificationAccess() }
    val canPost = remember(resumeTick) { settings.canPostNotifications() }
    val unrestricted = remember(resumeTick) { settings.isIgnoringBatteryOptimizations() }
    val dndAccess = remember(resumeTick) { settings.hasDndAccess() }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Filtering")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filter service", style = MaterialTheme.typography.bodyLarge)
                Hint(
                    when {
                        !hasAccess -> "Not running: notification access is off."
                        listenerConnected -> "Running. NotiRegX checks this regularly and reconnects it if Android stops it."
                        else -> "Not running. Android disconnected it; tap Restart."
                    }
                )
            }
            if (hasAccess && !listenerConnected) {
                TextButton(onClick = onRestartListener) { Text("Restart") }
            } else if (hasAccess) {
                Text("Running", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filtering enabled", style = MaterialTheme.typography.bodyLarge)
                Hint("When off, every notification is let through but still shows up in Recent.")
            }
            Switch(checked = filtering, onCheckedChange = settings::setFilteringEnabled)
        }
        Column {
            Text("Saved per rule: ${sliderValue.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
            Hint("Older notifications are dropped from a rule's history beyond this.")
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it / 10).roundToInt() * 10f },
                onValueChangeFinished = { settings.setMaxSaved(sliderValue.roundToInt()) },
                valueRange = Settings.MIN_MAX_SAVED.toFloat()..500f,
            )
        }

        HorizontalDivider()
        Section("De-buzz")
        Hint(
            "Android buzzes before NotiRegX can remove a notification. Rules with De-buzz turned on silence their " +
                "app instead, and NotiRegX buzzes itself for whatever gets through. A de-buzz rule for one app " +
                "silences it right away; one for any app silences a notification category the first time it " +
                "catches something from it. Turning De-buzz off or deleting the rule puts everything back."
        )
        when {
            !settings.supportsCompanionDevices() -> Hint("This phone doesn't support companion devices, so De-buzz isn't available.")
            !companionLinked -> {
                Hint(
                    "Android only allows this for apps linked to a companion device. Turn on Bluetooth and pick any " +
                        "nearby device (earbuds, watch, speaker, TV). Nothing is ever sent to it."
                )
                TextButton(onClick = onLinkCompanion) { Text("Link a device") }
            }
            else -> Hint(
                muteStatus.error?.let { "Problem: $it" }
                    ?: "Ready. ${muteStatus.mutedChannels} notification categories currently silenced."
            )
        }
        Hint("Before uninstalling NotiRegX, turn De-buzz off on every rule so the apps get their sound back.")

        HorizontalDivider()
        Section("Backup")
        Hint("Save your rules to a file, or add rules from one (yours or a friend's). History isn't included.")
        Row {
            TextButton(onClick = onExport) { Text("Export rules") }
            TextButton(onClick = onImport) { Text("Import rules") }
        }

        HorizontalDivider()
        Section("Permissions")
        PermissionRow(
            title = "Notification access",
            hint = "Required to read and filter notifications.",
            granted = hasAccess,
            onFix = { onOpenIntent(settings.notificationAccessIntent()) },
        )
        PermissionRow(
            title = "Post notifications",
            hint = "Required to show bundle notifications.",
            granted = canPost,
            onFix = { onOpenIntent(settings.appNotificationSettingsIntent()) },
        )
        PermissionRow(
            title = "Do Not Disturb access",
            hint = "Optional. Lets \"Always alert\" rules ring through Do Not Disturb.",
            granted = dndAccess,
            onFix = { onOpenIntent(settings.dndAccessIntent()) },
        )
        PermissionRow(
            title = "Unrestricted battery use",
            hint = "Stops the system from putting NotiRegX to sleep.",
            granted = unrestricted,
            onFix = { onOpenIntent(settings.batteryOptimizationIntent()) },
        )

        HorizontalDivider()
        Section("Samsung tips")
        Hint(
            "If Android says notification access is a \"restricted setting\", open App info, tap ⋮ in the corner " +
                "and choose \"Allow restricted settings\", then try again.\n\n" +
                "To keep filtering reliable, also add NotiRegX to Settings › Battery › Background usage limits › " +
                "Never sleeping apps."
        )
        TextButton(onClick = { onOpenIntent(settings.appDetailsIntent()) }) { Text("Open App info") }

        HorizontalDivider()
        TextButton(onClick = onShowTutorial) { Text("Show the tutorial again") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PermissionRow(title: String, hint: String, granted: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        if (granted) {
            Text("Granted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onFix) { Text("Allow") }
        }
    }
}
