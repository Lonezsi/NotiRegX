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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.VibrationEvent
import com.notiregx.app.model.VibrationLog
import com.notiregx.app.model.VibrationRow
import com.notiregx.app.model.VibrationSource
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val GRANT_COMMAND = "adb shell pm grant com.notiregx.app android.permission.DUMP"
private const val DAY_MS = 24 * 60 * 60 * 1000L

@Composable
fun VibrationsScreen(
    events: List<VibrationEvent>,
    systemLogAvailable: Boolean,
    onRefresh: suspend () -> Unit,
    onClear: () -> Unit,
    lastDump: () -> String?,
) {
    val rows = remember(events) { VibrationLog.timeline(events) }
    val topApps = remember(rows) {
        val since = System.currentTimeMillis() - DAY_MS
        rows.filter { it.event.time >= since }
            .groupingBy { it.event.appName }.eachCount()
            .entries.sortedByDescending { it.value }.take(6)
    }
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
                        Text("System vibration log: on", style = MaterialTheme.typography.titleSmall)
                        Hint(
                            "Android's own vibration history is read every 2 minutes, so vibrations without a " +
                                "notification show up too, marked as ghosts."
                        )
                        Row {
                            TextButton(onClick = { scope.launch { onRefresh() } }) { Text("Refresh now") }
                            TextButton(onClick = {
                                val dump = lastDump()
                                if (dump != null) {
                                    clipboard.setText(AnnotatedString(dump))
                                    Toast.makeText(context, "Raw system log copied", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Copy raw log") }
                        }
                    } else {
                        Text("System vibration log: off", style = MaterialTheme.typography.titleSmall)
                        Hint(
                            "Only notifications that should have vibrated are listed. To also catch ghost vibrations " +
                                "from apps that never show a notification, run this once from a computer with adb:"
                        )
                        Text(GRANT_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(GRANT_COMMAND))
                            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy command") }
                    }
                }
            }
        }
        if (topApps.isNotEmpty()) {
            item {
                Text("Last 24 hours", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(topApps) { (app, count) -> AssistChip(onClick = {}, label = { Text("$app · $count") }) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("${rows.size} vibrations", Modifier.weight(1f))
                if (rows.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (rows.isEmpty()) {
            item { Hint("Nothing yet. Vibrations will appear here as they happen.", Modifier.padding(vertical = 24.dp)) }
        }
        items(rows, key = { it.event.key }) { VibrationCard(it) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear vibration log?") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VibrationCard(row: VibrationRow) {
    val e = row.event
    val colors = if (row.isGhost) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, size = 20.dp)
                Spacer(Modifier.padding(start = 8.dp))
                Text(e.appName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(formatTime(e.time), style = MaterialTheme.typography.labelSmall)
            }
            val what = when {
                row.isGhost -> "Ghost vibration: no notification"
                e.source == VibrationSource.NOTIFICATION -> "Notification (estimated): ${e.detail}"
                row.notification != null -> "Notification: ${row.notification.detail}"
                else -> e.detail
            }
            Text(what, style = MaterialTheme.typography.bodyMedium)
            val extra = listOfNotNull(
                e.usage?.lowercase()?.replace('_', ' '),
                e.durationMs?.let { "${it} ms" },
                e.detail.takeIf { row.isGhost && it.isNotEmpty() },
            )
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
