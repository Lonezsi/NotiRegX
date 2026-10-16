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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notiregx.app.MuteStatus
import com.notiregx.app.R
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
    val context = LocalContext.current

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LanguageSetting()
        HorizontalDivider()
        Section(stringResource(R.string.settings_section_filtering))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_filter_service), style = MaterialTheme.typography.bodyLarge)
                Hint(
                    when {
                        !hasAccess -> stringResource(R.string.settings_filter_service_no_access)
                        listenerConnected -> stringResource(R.string.settings_filter_service_running_hint)
                        else -> stringResource(R.string.settings_filter_service_disconnected)
                    }
                )
            }
            if (hasAccess && !listenerConnected) {
                TextButton(onClick = onRestartListener) { Text(stringResource(R.string.settings_restart)) }
            } else if (hasAccess) {
                Text(stringResource(R.string.settings_running), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_filtering_enabled), style = MaterialTheme.typography.bodyLarge)
                Hint(stringResource(R.string.settings_filtering_enabled_hint))
            }
            Switch(checked = filtering, onCheckedChange = settings::setFilteringEnabled)
        }
        Column {
            Text(stringResource(R.string.settings_saved_per_rule, sliderValue.roundToInt()), style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(R.string.settings_saved_per_rule_hint))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it / 10).roundToInt() * 10f },
                onValueChangeFinished = { settings.setMaxSaved(sliderValue.roundToInt()) },
                valueRange = Settings.MIN_MAX_SAVED.toFloat()..500f,
            )
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_section_debuzz))
        Hint(stringResource(R.string.settings_debuzz_explanation))
        when {
            !settings.supportsCompanionDevices() -> Hint(stringResource(R.string.settings_debuzz_unsupported))
            !companionLinked -> {
                Hint(stringResource(R.string.settings_debuzz_link_hint))
                TextButton(onClick = onLinkCompanion) { Text(stringResource(R.string.settings_link_device)) }
            }
            else -> Hint(
                muteStatus.error?.let { stringResource(R.string.settings_debuzz_problem, it) }
                    ?: context.resources.getQuantityString(
                        R.plurals.settings_debuzz_ready, muteStatus.mutedChannels, muteStatus.mutedChannels,
                    )
            )
        }
        Hint(stringResource(R.string.settings_debuzz_uninstall_hint))

        HorizontalDivider()
        Section(stringResource(R.string.settings_section_backup))
        Hint(stringResource(R.string.settings_backup_hint))
        Row {
            TextButton(onClick = onExport) { Text(stringResource(R.string.settings_export_rules)) }
            TextButton(onClick = onImport) { Text(stringResource(R.string.settings_import_rules)) }
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_section_permissions))
        PermissionRow(
            title = stringResource(R.string.settings_perm_notification_access),
            hint = stringResource(R.string.settings_perm_notification_access_hint),
            granted = hasAccess,
            onFix = { onOpenIntent(settings.notificationAccessIntent()) },
        )
        PermissionRow(
            title = stringResource(R.string.settings_perm_post_notifications),
            hint = stringResource(R.string.settings_perm_post_notifications_hint),
            granted = canPost,
            onFix = { onOpenIntent(settings.appNotificationSettingsIntent()) },
        )
        PermissionRow(
            title = stringResource(R.string.settings_perm_dnd_access),
            hint = stringResource(R.string.settings_perm_dnd_access_hint),
            granted = dndAccess,
            onFix = { onOpenIntent(settings.dndAccessIntent()) },
        )
        PermissionRow(
            title = stringResource(R.string.settings_perm_battery),
            hint = stringResource(R.string.settings_perm_battery_hint),
            granted = unrestricted,
            onFix = { onOpenIntent(settings.batteryOptimizationIntent()) },
        )

        HorizontalDivider()
        Section(stringResource(R.string.settings_section_samsung))
        Hint(stringResource(R.string.settings_samsung_tips))
        TextButton(onClick = { onOpenIntent(settings.appDetailsIntent()) }) { Text(stringResource(R.string.settings_open_app_info)) }

        HorizontalDivider()
        TextButton(onClick = onShowTutorial) { Text(stringResource(R.string.settings_show_tutorial)) }
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
            Text(stringResource(R.string.settings_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onFix) { Text(stringResource(R.string.settings_allow)) }
        }
    }
}
