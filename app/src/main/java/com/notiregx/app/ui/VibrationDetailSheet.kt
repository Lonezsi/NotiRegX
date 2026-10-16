@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.Notification
import com.notiregx.app.model.VibrationKind
import com.notiregx.app.model.VibrationRow
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** How close a notification must be to a vibration to count as the one that caused it. */
private const val SAME_MS = 10_000L

/** Notifications from the same app around a ghost, which may hint at what was going on. */
private const val NEARBY_MS = 10 * 60_000L

/**
 * Details and actions for one vibration: the notification behind it (if any), opening the app,
 * making a rule, and muting the app's vibrations.
 */
@Composable
fun VibrationDetailSheet(
    row: VibrationRow,
    notifications: List<Notification>,
    companionLinked: Boolean,
    vibrationMuted: Boolean,
    muteError: String?,
    onSetVibrationMuted: (Boolean) -> Unit,
    onLinkCompanion: () -> Unit,
    onCreateRule: (Notification) -> Unit,
    actions: AppActions,
    onDismiss: () -> Unit,
) {
    val e = row.event
    val context = LocalContext.current
    val fromApp = remember(notifications, e.packageName) { notifications.filter { it.packageName == e.packageName } }
    val cause = remember(fromApp, e.time) {
        fromApp.filter { abs(it.postedAt - e.time) <= SAME_MS }.minByOrNull { abs(it.postedAt - e.time) }
    }
    val nearby = remember(fromApp, e.time, cause) {
        fromApp.filter { it != cause && abs(it.postedAt - e.time) <= NEARBY_MS }.sortedBy { abs(it.postedAt - e.time) }.take(3)
    }
    val launch = remember(e.packageName) { context.packageManager.getLaunchIntentForPackage(e.packageName) }
    val isNotificationBuzz = row.kind == VibrationKind.NOTIFICATION || row.kind == VibrationKind.ESTIMATE

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, size = 44.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.appName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(row.kind.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (row.isGhost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                listOfNotNull(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(e.time)),
                    e.durationMs?.let { stringResource(R.string.vibrations_duration_ms, it) },
                    e.usage?.lowercase()?.replace('_', ' '),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            // "Notification" is the stable marker SystemVibrationReader stores, not display text.
            e.detail.takeIf {
                it.isNotEmpty() && it != "Notification" && row.kind != VibrationKind.ESTIMATE &&
                    !it.equals(e.usage?.replace('_', ' '), ignoreCase = true)
            }?.let {
                Text(stringResource(R.string.vibration_sheet_android_reason, it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            HorizontalDivider()
            when {
                cause != null -> {
                    SectionTitle(stringResource(R.string.vibration_sheet_the_notification))
                    NotificationBox(cause, onOpen = { actions.openNotification(cause); onDismiss() })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.openNotification(cause); onDismiss() }) { Text(stringResource(R.string.vibration_sheet_open_notification)) }
                        OutlinedButton(onClick = { onCreateRule(cause); onDismiss() }) { Text(stringResource(R.string.vibration_sheet_make_rule)) }
                    }
                }
                isNotificationBuzz -> Hint(stringResource(R.string.vibration_sheet_notification_unseen))
                row.isGhost -> Hint(stringResource(R.string.vibration_sheet_ghost_explanation, e.appName))
                row.kind == VibrationKind.CALL -> Hint(stringResource(R.string.vibration_sheet_was_call))
                row.kind == VibrationKind.ALARM -> Hint(stringResource(R.string.vibration_sheet_was_alarm))
                else -> Hint(stringResource(R.string.vibration_sheet_was_other, stringResource(row.kind.label)))
            }
            if (nearby.isNotEmpty()) {
                SectionTitle(
                    if (cause == null) stringResource(R.string.vibration_sheet_nearby_from_app, e.appName)
                    else stringResource(R.string.vibration_sheet_nearby_other)
                )
                nearby.forEach { n -> NotificationBox(n, onOpen = { actions.openNotification(n); onDismiss() }) }
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.vibration_sheet_do_something))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (launch != null) OutlinedButton(onClick = { actions.openIntent(launch); onDismiss() }) { Text(stringResource(R.string.vibration_sheet_open_app)) }
                OutlinedButton(onClick = {
                    actions.openIntent(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.packageName}")))
                }) { Text(stringResource(R.string.vibration_sheet_app_info)) }
            }

            if (isNotificationBuzz || vibrationMuted) {
                MuteNotificationVibration(e.appName, companionLinked, vibrationMuted, muteError, onSetVibrationMuted, onLinkCompanion)
            }
            if (!isNotificationBuzz) {
                MuteDirectVibration(e.packageName, e.appName)
            }
        }
    }
}

@Composable
private fun MuteNotificationVibration(
    appName: String,
    companionLinked: Boolean,
    muted: Boolean,
    error: String?,
    onSetMuted: (Boolean) -> Unit,
    onLinkCompanion: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (muted) stringResource(R.string.vibration_sheet_notifications_muted_title, appName)
                else stringResource(R.string.vibration_sheet_mute_notification_title, appName),
                style = MaterialTheme.typography.titleSmall,
            )
            Hint(stringResource(R.string.vibration_sheet_mute_notification_hint, appName))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when {
                !companionLinked -> {
                    Hint(stringResource(R.string.vibration_sheet_needs_companion))
                    TextButton(onClick = onLinkCompanion) { Text(stringResource(R.string.vibration_sheet_link_device)) }
                }
                muted -> OutlinedButton(onClick = { onSetMuted(false) }) { Text(stringResource(R.string.vibration_sheet_unmute)) }
                else -> Button(onClick = { onSetMuted(true) }) { Text(stringResource(R.string.vibration_sheet_mute)) }
            }
        }
    }
}

/**
 * Vibrations an app plays directly can only be blocked by switching off its vibrate permission,
 * which Android only allows from a computer (adb), not from another app.
 */
@Composable
private fun MuteDirectVibration(packageName: String, appName: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val mute = "adb shell appops set $packageName VIBRATE ignore"
    val undo = "adb shell appops set $packageName VIBRATE allow"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.vibration_sheet_mute_direct_title, appName), style = MaterialTheme.typography.titleSmall)
            if (isSystemPackage(packageName)) {
                Text(
                    stringResource(R.string.vibration_sheet_system_app_warning, appName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Hint(stringResource(R.string.vibration_sheet_mute_direct_hint, appName))
            Text(mute, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(mute))
                    Toast.makeText(context, context.getString(R.string.vibrations_command_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.vibrations_copy_command)) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(undo))
                    Toast.makeText(context, context.getString(R.string.vibration_sheet_undo_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.vibration_sheet_copy_undo)) }
            }
        }
    }
}

private fun isSystemPackage(pkg: String) =
    pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.")

@Composable
private fun NotificationBox(n: Notification, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(n.postedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (n.title.isNotEmpty()) Text(n.title, style = MaterialTheme.typography.titleSmall)
            if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
