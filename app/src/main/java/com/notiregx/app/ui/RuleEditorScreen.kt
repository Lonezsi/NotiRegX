@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.LoggedNotification
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RuleEditorScreen(
    initial: Rule,
    isNew: Boolean,
    recent: List<LoggedNotification>,
    companionLinked: Boolean,
    onLinkCompanion: () -> Unit,
    onSave: (Rule) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var regex by remember { mutableStateOf(initial.regex) }
    var field by remember { mutableStateOf(initial.field) }
    var debuzz by remember { mutableStateOf(initial.debuzz) }
    var schedule by remember { mutableStateOf(initial.schedule) }
    var burst by remember { mutableStateOf(initial.burst) }
    var deliveryTimes by remember { mutableStateOf(initial.deliveryTimes) }
    var app by remember { mutableStateOf(initial.packageName?.let { AppInfo(it, initial.appName ?: it) }) }
    var invert by remember { mutableStateOf(initial.invert) }
    var type by remember { mutableStateOf(initial.type) }
    var description by remember { mutableStateOf(initial.description) }
    var testText by remember { mutableStateOf("") }
    var pickingApp by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val draft = initial.copy(
        name = name.trim(),
        regex = regex,
        field = field,
        debuzz = debuzz,
        schedule = schedule,
        burst = burst,
        deliveryTimes = if (type == RuleType.BUNDLE) deliveryTimes else emptyList(),
        packageName = app?.packageName,
        appName = app?.label,
        invert = invert,
        type = type,
        description = description.trim(),
    )
    val regexError = remember(regex) { Rule.regexError(regex) }
    val canSave = name.isNotBlank() && regexError == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New rule" else "Edit rule") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete rule") }
                    }
                    TextButton(onClick = { onSave(draft) }, enabled = canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = regex,
                onValueChange = { regex = it },
                label = { Text("Regex") },
                isError = regexError != null,
                supportingText = {
                    Text(regexError ?: "Empty matches everything. Start with (?i) to ignore case.")
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Search in", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MatchField.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = field == f,
                        onClick = { field = f },
                        shape = SegmentedButtonDefaults.itemShape(i, MatchField.entries.size),
                    ) { Text(f.label, maxLines = 1) }
                }
            }
            OutlinedCard(onClick = { pickingApp = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App", style = MaterialTheme.typography.labelMedium)
                        Text(app?.label ?: "Any app", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (app != null) {
                        IconButton(onClick = { app = null }) { Icon(Icons.Default.Clear, contentDescription = "Any app") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Invert", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Match notifications where the regex is NOT found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = invert, onCheckedChange = { invert = it })
            }
            Text("When this rule is the final match", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuleType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, RuleType.entries.size),
                    ) { Text(t.label, maxLines = 1) }
                }
            }
            Text(type.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("De-buzz", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            !companionLinked -> "Needs a linked device first (see Settings)."
                            app == null -> "Silences each notification category this rule catches something from; " +
                                "NotiRegX buzzes for whatever gets through instead."
                            else -> "Silences ${app?.label}; NotiRegX buzzes for whatever gets through instead, " +
                                "so filtered notifications never buzz."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (companionLinked) {
                    Switch(checked = debuzz, onCheckedChange = { debuzz = it })
                } else {
                    TextButton(onClick = onLinkCompanion) { Text("Link") }
                }
            }
            HorizontalDivider()
            Text("When", style = MaterialTheme.typography.titleSmall)
            RuleConditions(
                schedule = schedule,
                onSchedule = { schedule = it },
                burst = burst,
                onBurst = { burst = it },
                isBundle = type == RuleType.BUNDLE,
                deliveryTimes = deliveryTimes,
                onDeliveryTimes = { deliveryTimes = it },
            )
            HorizontalDivider()
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Try it", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Sample notification text") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (testText.isNotEmpty() && regexError == null) {
                val sample = Notification(
                    id = 0,
                    packageName = app?.packageName ?: "",
                    appName = "",
                    title = if (field == MatchField.TITLE) testText else "",
                    text = if (field == MatchField.TITLE) "" else testText,
                    postedAt = System.currentTimeMillis(),
                )
                val hit = draft.copy(enabled = true).matches(sample)
                Text(
                    if (hit) "✓ This rule matches" else "✗ No match",
                    color = if (hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (regexError == null && recent.isNotEmpty()) {
                val hits = recent.filter { draft.copy(enabled = true).matches(it.notification) }
                Text(
                    "Matches ${hits.size} of the last ${recent.size} notifications",
                    style = MaterialTheme.typography.bodyMedium,
                )
                hits.take(5).forEach { entry ->
                    val n = entry.notification
                    Text(
                        "${n.appName}: ${n.content.replace('\n', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (pickingApp) {
        val seen = recent.map { AppInfo(it.notification.packageName, it.notification.appName) }
        AppPickerDialog(
            extraApps = seen,
            onPick = { app = it; pickingApp = false },
            onDismiss = { pickingApp = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete rule?") },
            text = { Text("\"${initial.name}\" and its saved history will be removed.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

data class AppInfo(val packageName: String, val label: String)

@Composable
private fun AppPickerDialog(extraApps: List<AppInfo>, onPick: (AppInfo?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppInfo>?>(null) {
        value = withContext(Dispatchers.IO) { launchableApps(context.packageManager, extraApps) }
    }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                } else {
                    val filtered = list.filter {
                        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        if (query.isEmpty()) {
                            item {
                                ListItem(
                                    headlineContent = { Text("Any app") },
                                    modifier = Modifier.clickable { onPick(null) },
                                )
                            }
                        }
                        items(filtered, key = { it.packageName }) { info ->
                            ListItem(
                                leadingContent = { AppIcon(info.packageName, size = 32.dp) },
                                headlineContent = { Text(info.label) },
                                supportingContent = { Text(info.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable { onPick(info) },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Apps with a launcher icon, plus any that recently posted notifications (e.g. system apps). */
private fun launchableApps(pm: PackageManager, extra: List<AppInfo>): List<AppInfo> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val installed = pm.queryIntentActivities(launcher, 0).map {
        AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString())
    }
    return (installed + extra)
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
