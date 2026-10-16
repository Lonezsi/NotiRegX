package com.notiregx.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.PeoplePattern
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.SenderCount
import com.notiregx.app.model.SenderStats

private enum class Period(val label: String, val days: Int?) {
    TODAY("Today", 1),
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    ALL("All time", null),
}

/** Who sends you the most notifications, per app, over a chosen period. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendersView(senders: Collection<SenderCount>, onMakeRule: (Rule) -> Unit) {
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var app by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<SenderCount?>(null) }

    val firstDay = period.days?.let { SenderStats.dayOf(System.currentTimeMillis()) - it + 1 } ?: Int.MIN_VALUE
    val inPeriod = remember(senders, firstDay) { SenderStats.ranked(senders, firstDay) }
    val apps = remember(inPeriod) {
        inPeriod.groupBy { it.first.packageName }
            .map { (pkg, list) -> Triple(pkg, list.first().first.appName, list.sumOf { it.second }) }
            .sortedByDescending { it.third }
    }
    val rows = remember(inPeriod, app) { inPeriod.filter { app == null || it.first.packageName == app } }
    val top = rows.firstOrNull()?.second ?: 1

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = period == p,
                        onClick = { period = p },
                        shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                    ) { Text(p.label, maxLines = 1) }
                }
            }
        }
        if (apps.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = app == null, onClick = { app = null }, label = { Text("All apps") }) }
                    items(apps, key = { it.first }) { (pkg, name, count) ->
                        FilterChip(
                            selected = app == pkg,
                            onClick = { app = if (app == pkg) null else pkg },
                            leadingIcon = { AppIcon(pkg, size = 18.dp) },
                            label = { Text("$name · $count") },
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Counts every notification, whatever your rules did with it. For chat apps the sender is the " +
                    "person or group; for others it's the notification's title. Tap one to make a rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    "Nothing counted for this period yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(rows, key = { it.first.key }) { (s, count) ->
            Card(onClick = { picked = s }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(s.packageName, size = 24.dp)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.appName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("%,d".format(count), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(progress = { count.toFloat() / top }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    picked?.let { s ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(s.sender) },
            text = {
                Text("${"%,d".format(s.total)} notifications from ${s.appName} in total. What should happen to them?")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onMakeRule(senderRule(s, RuleType.ALLOW)); picked = null }) { Text("Always let through") }
                    TextButton(onClick = { onMakeRule(senderRule(s, RuleType.BUNDLE)); picked = null }) { Text("Bundle") }
                    TextButton(onClick = { onMakeRule(senderRule(s, RuleType.HIDE)); picked = null }) { Text("Hide") }
                    TextButton(onClick = { picked = null }) { Text("Cancel") }
                }
            },
        )
    }
}

private fun senderRule(s: SenderCount, type: RuleType) = Rule(
    name = "${s.sender} (${s.appName})".take(60),
    regex = PeoplePattern.build(listOf(s.sender)),
    field = MatchField.TITLE,
    packageName = s.packageName,
    appName = s.appName,
    type = type,
)

/** Pick people (notification titles) seen from [packageName] (or any app) to build a title pattern. */
@Composable
fun PeoplePickerDialog(
    senders: Collection<SenderCount>,
    packageName: String?,
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    // One row per name; with no app chosen, the same name from different apps is merged.
    val names = remember(senders, packageName) {
        SenderStats.ranked(senders, Int.MIN_VALUE, packageName)
            .groupBy { it.first.sender }
            .map { (name, list) -> Triple(name, list.sumOf { it.second }, list.first().first.packageName) }
            .sortedByDescending { it.second }
    }
    val shown = names.filter { query.isEmpty() || it.first.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick senders") },
        text = {
            Column {
                Text(
                    "Matches notifications whose title is exactly one of these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (names.isEmpty()) {
                    Text("No senders seen yet${if (packageName != null) " for this app" else ""}.")
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.first }) { (name, count, pkg) ->
                        val checked = name in selected
                        ListItem(
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("%,d notifications".format(count)) },
                            trailingContent = { if (packageName == null) AppIcon(pkg, size = 20.dp) },
                            modifier = Modifier.clickable { selected = if (checked) selected - name else selected + name },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(selected) }, enabled = selected.isNotEmpty()) {
                Text(if (selected.isEmpty()) "Use" else "Use ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
