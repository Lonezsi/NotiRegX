@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import android.text.format.DateUtils
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.notiregx.app.R
import com.notiregx.app.model.Activity
import com.notiregx.app.model.Alias
import com.notiregx.app.model.Friendship
import com.notiregx.app.model.Reminders
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.Schedule
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Person
import com.notiregx.app.model.PersonEntry
import com.notiregx.app.model.SenderCount
import com.notiregx.app.model.SenderStats
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One person: their usernames, linked contact, best-friend star, stats, and everything they sent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonScreen(
    person: Person,
    entries: List<PersonEntry>,
    senders: Collection<SenderCount>,
    people: List<Person>,
    photo: File,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSetFavorite: (Boolean) -> Unit,
    onSetSaveAll: (Boolean) -> Unit,
    onAddAliases: (List<Alias>) -> Unit,
    onRemoveAlias: (Alias) -> Unit,
    onLinkContact: () -> Unit,
    onClearLog: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (Notification) -> Unit,
    onMakeRule: (Notification) -> Unit,
    onPersonRule: (RuleType) -> Unit,
    onHideGroup: (packageName: String, appName: String, group: String) -> Unit,
    onSetReminder: (Int?) -> Unit,
    onExport: () -> Unit,
) {
    var name by remember(person.id) { mutableStateOf(person.name) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val today = SenderStats.dayOf(System.currentTimeMillis())
    val mine = remember(entries, person.id) { entries.filter { it.personId == person.id } }
    val context = LocalContext.current
    val mySenders = remember(senders, person) {
        val keys = person.aliases.mapTo(HashSet()) { it.key }
        senders.filter { Alias.keyOf(it.packageName, it.sender) in keys }
    }
    val lastHeard = remember(mySenders) { Reminders.lastHeard(person, mySenders) }
    val times = remember(mine) { mine.map { it.notification.postedAt } }
    val hours = remember(times) { Activity.byHour(times) }
    val weekdays = remember(times) { Activity.byWeekday(times) }
    val daily = remember(mySenders, today) { Activity.daily(mySenders, today) }
    val weekly = remember(mySenders, today) { Friendship.weekly(mySenders, today) }
    val trend = remember(mySenders, today) { Friendship.trend(mySenders, today) }
    // Groups they talk in, busiest first, with the app they're in.
    val groups = remember(mine) {
        mine.mapNotNull { e -> e.notification.group?.let { Triple(e.notification.packageName, e.notification.appName, it) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
    }
    var pickingReminder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.person_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (name.isNotBlank() && name != person.name) onRename(name.trim()); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onExport) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.person_export)) }
                    StarButton(person.favorite) { onSetFavorite(!person.favorite) }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.person_delete))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(person, photo, 72.dp)
                    Spacer(Modifier.size(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.person_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Text(
                    stringResource(
                        R.string.person_stats,
                        personCount(person, senders, today).toInt(),
                        personCount(person, senders, today - 6).toInt(),
                        personCount(person, senders, Int.MIN_VALUE).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (lastHeard > 0) {
                    Hint(
                        stringResource(
                            R.string.person_last_heard,
                            DateUtils.getRelativeTimeSpanString(lastHeard, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        )
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.person_reminder), style = MaterialTheme.typography.bodyLarge)
                        Hint(
                            person.remindAfterDays?.let { context.resources.getQuantityString(R.plurals.person_reminder_days, it, it) }
                                ?: stringResource(R.string.person_reminder_off)
                        )
                    }
                    TextButton(onClick = { pickingReminder = true }) { Text(stringResource(R.string.language_change)) }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.person_contact), style = MaterialTheme.typography.bodyLarge)
                        Hint(person.contactName ?: stringResource(R.string.person_no_contact))
                    }
                    TextButton(onClick = onLinkContact) {
                        Text(stringResource(if (person.contactName == null) R.string.person_link_contact else R.string.person_change_contact))
                    }
                }
            }
            item {
                Text(stringResource(R.string.person_usernames), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    person.aliases.forEach { a ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            leadingIcon = { AppIcon(a.packageName, size = 18.dp) },
                            label = { Text(a.sender, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(onClick = { onRemoveAlias(a) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Clear, stringResource(R.string.person_remove_username, a.sender))
                                }
                            },
                        )
                    }
                }
                OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.person_add_usernames)) }
            }
            item {
                SwitchRow(stringResource(R.string.people_star), stringResource(R.string.person_best_friend_hint), person.favorite, onSetFavorite)
                SwitchRow(stringResource(R.string.person_save_all), stringResource(R.string.person_save_all_hint), person.saveAll, onSetSaveAll)
            }
            item {
                Text(stringResource(R.string.person_rules), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPersonRule(RuleType.HIDE) }) { Text(stringResource(R.string.person_rule_hide)) }
                    OutlinedButton(onClick = { onPersonRule(RuleType.ALERT) }) { Text(stringResource(R.string.person_rule_alert)) }
                }
            }
            if (groups.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.person_groups), style = MaterialTheme.typography.bodyLarge)
                    groups.forEach { (g, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(g.first, size = 18.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.person_group_entry, g.third, count), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onHideGroup(g.first, g.second, g.third) }) { Text(stringResource(R.string.person_mute_group)) }
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    Text(
                        stringResource(R.string.friendship_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (trend.fading) {
                        Text(stringResource(R.string.friendship_fading), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
                Hint(stringResource(R.string.friendship_trend, trend.recent, trend.usual.toInt()))
                if (trend.fading) Hint(stringResource(R.string.friendship_fading_hint))
                Text(stringResource(R.string.friendship_weeks), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                // Only from the first week with anything in it, so a new person's graph isn't mostly empty.
                val first = weekly.indexOfFirst { it > 0 }.coerceAtLeast(0).coerceAtMost(weekly.size - 8)
                MiniBarChart(weekly.copyOfRange(first, weekly.size), emptyList(), height = 64.dp)
            }
            item {
                Text(
                    stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                if (times.size < 5) {
                    Hint(stringResource(R.string.activity_empty))
                } else {
                    val busiest = hours.indices.maxBy { hours[it] }
                    Hint(stringResource(R.string.activity_busiest, "%02d:00".format(busiest)))
                    Text(stringResource(R.string.activity_hours), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    MiniBarChart(hours, (0 until 24).map { if (it % 6 == 0) "$it" else "" })
                    Text(stringResource(R.string.activity_weekdays), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    MiniBarChart(weekdays, Schedule.DAY_ORDER.map { context.dayName(it) }, height = 56.dp)
                }
                if (daily.any { it > 0 }) {
                    Text(stringResource(R.string.activity_days), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    MiniBarChart(daily, emptyList(), height = 48.dp)
                }
            }
            item {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.person_timeline),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (mine.isNotEmpty()) TextButton(onClick = onClearLog) { Text(stringResource(R.string.person_clear)) }
                }
                if (mine.isEmpty()) {
                    Hint(stringResource(if (person.saveAll) R.string.person_timeline_empty else R.string.person_timeline_off))
                }
            }
            items(mine, key = { it.notification.id }) { entry ->
                val n = entry.notification
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(n.packageName, size = 18.dp)
                            Spacer(Modifier.size(6.dp))
                            Text(n.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.postedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        n.group?.let { Hint(it) }
                        if (entry.deleted) {
                            Text(
                                stringResource(R.string.person_deleted_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onMakeRule(n) }) { Text(stringResource(R.string.person_make_rule)) }
                            TextButton(onClick = { onOpen(n) }) { Text(stringResource(R.string.person_open)) }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AliasPickerDialog(
            senders = senders,
            people = people,
            exclude = person.aliases.mapTo(HashSet()) { it.key },
            confirmLabel = { stringResource(R.string.alias_picker_add, it) },
            onDone = { onAddAliases(it); adding = false },
            onDismiss = { adding = false },
        )
    }
    if (pickingReminder) {
        AlertDialog(
            onDismissRequest = { pickingReminder = false },
            title = { Text(stringResource(R.string.person_reminder)) },
            text = {
                Column {
                    (listOf<Int?>(null) + listOf(3, 7, 14, 30, 60)).forEach { days ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSetReminder(days); pickingReminder = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = person.remindAfterDays == days, onClick = null)
                            Spacer(Modifier.size(8.dp))
                            Text(days?.let { context.resources.getQuantityString(R.plurals.person_reminder_days, it, it) } ?: stringResource(R.string.person_reminder_off))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingReminder = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.person_delete)) },
            text = { Text(stringResource(R.string.person_delete_text, person.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.person_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
