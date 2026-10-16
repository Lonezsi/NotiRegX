package com.notiregx.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.Notification
import com.notiregx.app.model.VibrationEvent
import com.notiregx.app.model.VibrationKind
import com.notiregx.app.model.VibrationLog
import com.notiregx.app.model.VibrationRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val GRANT_COMMAND = "adb shell pm grant com.notiregx.app android.permission.DUMP"
private const val DAY_MS = 24 * 60 * 60 * 1000L

private val KIND_ORDER = listOf(
    VibrationKind.GHOST, VibrationKind.NOTIFICATION, VibrationKind.ESTIMATE, VibrationKind.CALL, VibrationKind.ALARM,
)

@Composable
fun VibrationsScreen(
    events: List<VibrationEvent>,
    systemLogAvailable: Boolean,
    onRefresh: suspend () -> Unit,
    onClear: () -> Unit,
    lastDump: () -> String?,
    notifications: List<Notification>,
    companionLinked: Boolean,
    vibrationMuted: Set<String>,
    muteError: String?,
    onSetVibrationMuted: (String, Boolean) -> Unit,
    onLinkCompanion: () -> Unit,
    onCreateRule: (Notification) -> Unit,
    actions: AppActions,
) {
    var selected by remember { mutableStateOf<VibrationRow?>(null) }
    val rows = remember(events) { VibrationLog.timeline(events) }
    var kindFilter by rememberSaveable { mutableStateOf<VibrationKind?>(null) }
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val kindCounts = remember(rows) { rows.groupingBy { it.kind }.eachCount() }
    val byKind = remember(rows, kindFilter) { rows.filter { kindFilter == null || it.kind == kindFilter } }
    // App chips: busiest apps in the last 24 hours within the chosen kind, plus the selected one.
    val topApps = remember(byKind, appFilter) {
        val since = System.currentTimeMillis() - DAY_MS
        val counts = byKind.filter { it.event.time >= since }.groupingBy { it.event.packageName }.eachCount()
        val names = rows.associate { it.event.packageName to it.event.appName }
        val top = counts.entries.sortedByDescending { it.value }.take(8).map { it.key }
        (top + listOfNotNull(appFilter).filter { it !in top }).map { pkg -> Triple(pkg, names[pkg] ?: pkg, counts[pkg] ?: 0) }
    }
    val shown = remember(byKind, appFilter) { byKind.filter { appFilter == null || it.event.packageName == appFilter } }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp)) {
                    if (systemLogAvailable) {
                        Text(stringResource(R.string.vibrations_log_on), style = MaterialTheme.typography.titleSmall)
                        Hint(stringResource(R.string.vibrations_log_on_hint))
                        Row {
                            TextButton(onClick = { scope.launch { onRefresh() } }) { Text(stringResource(R.string.vibrations_refresh_now)) }
                            TextButton(onClick = {
                                val dump = lastDump()
                                if (dump != null) {
                                    clipboard.setText(AnnotatedString(dump))
                                    Toast.makeText(context, context.getString(R.string.vibrations_raw_log_copied), Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.vibrations_copy_raw_log)) }
                        }
                    } else {
                        Text(stringResource(R.string.vibrations_log_off), style = MaterialTheme.typography.titleSmall)
                        Hint(stringResource(R.string.vibrations_log_off_hint))
                        Text(GRANT_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(GRANT_COMMAND))
                            Toast.makeText(context, context.getString(R.string.vibrations_command_copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.vibrations_copy_command)) }
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = kindFilter == null,
                        onClick = { kindFilter = null; appFilter = null },
                        label = { Text(stringResource(R.string.vibrations_chip_all, rows.size)) },
                    )
                }
                items(KIND_ORDER.filter { (kindCounts[it] ?: 0) > 0 || it == kindFilter }) { kind ->
                    FilterChip(
                        selected = kindFilter == kind,
                        onClick = {
                            kindFilter = if (kindFilter == kind) null else kind
                            appFilter = null
                        },
                        label = { Text(stringResource(R.string.vibrations_chip_count, stringResource(kind.chipLabel), kindCounts[kind] ?: 0)) },
                    )
                }
            }
        }
        if (topApps.isNotEmpty()) {
            item {
                Hint(stringResource(R.string.vibrations_top_apps))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(topApps, key = { it.first }) { (pkg, name, count) ->
                        FilterChip(
                            selected = appFilter == pkg,
                            onClick = { appFilter = if (appFilter == pkg) null else pkg },
                            leadingIcon = { AppIcon(pkg, size = 18.dp) },
                            label = { Text(stringResource(R.string.vibrations_chip_count, name, count)) },
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    if (shown.size == rows.size) {
                        context.resources.getQuantityString(R.plurals.vibrations_count, rows.size, rows.size)
                    } else {
                        stringResource(R.string.vibrations_showing_of, shown.size, rows.size)
                    },
                    Modifier.weight(1f),
                )
                if (rows.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.vibrations_clear)) }
            }
        }
        if (shown.isEmpty()) {
            item {
                Column(Modifier.padding(vertical = 24.dp)) {
                    Hint(stringResource(if (rows.isEmpty()) R.string.vibrations_empty else R.string.vibrations_no_match))
                    if (rows.isNotEmpty()) TextButton(onClick = { kindFilter = null; appFilter = null }) { Text(stringResource(R.string.vibrations_clear_filters)) }
                }
            }
        }
        items(shown, key = { it.event.key }) { VibrationCard(it, onClick = { selected = it }) }
    }

    selected?.let { row ->
        VibrationDetailSheet(
            row = row,
            notifications = notifications,
            companionLinked = companionLinked,
            vibrationMuted = row.event.packageName in vibrationMuted,
            muteError = muteError,
            onSetVibrationMuted = { onSetVibrationMuted(row.event.packageName, it) },
            onLinkCompanion = onLinkCompanion,
            onCreateRule = onCreateRule,
            actions = actions,
            onDismiss = { selected = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.vibrations_clear_confirm_title)) },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text(stringResource(R.string.vibrations_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun VibrationCard(row: VibrationRow, onClick: () -> Unit) {
    val e = row.event
    val colors = if (row.isGhost) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, size = 20.dp)
                Spacer(Modifier.padding(start = 8.dp))
                Text(e.appName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(formatTime(e.time), style = MaterialTheme.typography.labelSmall)
            }
            val title = row.notification?.detail?.takeIf { it.isNotEmpty() }
                ?: e.detail.takeIf { row.kind == VibrationKind.ESTIMATE && it.isNotEmpty() }
            val label = stringResource(row.kind.label)
            Text(
                title?.let { stringResource(R.string.vibrations_card_title, label, it) } ?: label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.isGhost) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            val durationText = e.durationMs?.let { stringResource(R.string.vibrations_duration_ms, it) }
            val extra = listOfNotNull(
                e.usage?.lowercase()?.replace('_', ' '),
                durationText,
                e.detail.takeIf { row.isGhost && it.isNotEmpty() },
            ).distinctBy { it.lowercase() }
            if (extra.isNotEmpty()) {
                Spacer(Modifier.padding(top = 2.dp))
                Hint(extra.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))
