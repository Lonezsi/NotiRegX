@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notiregx.app.R
import com.notiregx.app.model.Alias
import com.notiregx.app.model.FriendMode
import com.notiregx.app.model.PeopleBook
import com.notiregx.app.model.Person
import com.notiregx.app.model.SenderCount
import com.notiregx.app.model.SenderStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The People tab: best friends, "same person?" suggestions, and everyone the user has combined. */
@Composable
fun PeopleScreen(
    people: List<Person>,
    senders: Collection<SenderCount>,
    friendMode: FriendMode,
    ignoredMerges: Set<String>,
    photoFile: (String) -> File,
    onSetFriendMode: (FriendMode) -> Unit,
    onOpenPerson: (String) -> Unit,
    onCombine: (List<SenderCount>) -> Unit,
    onIgnoreMerge: (String) -> Unit,
) {
    val suggestions = remember(senders, people, ignoredMerges) {
        PeopleBook.mergeSuggestions(senders, people, ignoredMerges).take(5)
    }
    val week = SenderStats.dayOf(System.currentTimeMillis()) - 6
    val friends = people.filter { it.favorite }
    val sorted = remember(people, senders) {
        people.sortedWith(compareByDescending<Person> { it.favorite }.thenByDescending { personCount(it, senders, week) })
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.people_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (friends.isNotEmpty()) {
            item {
                Section(stringResource(R.string.people_best_friends))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(friends, key = { it.id }) { p ->
                        Column(
                            Modifier.width(72.dp).clickable { onOpenPerson(p.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(p, photoFile(p.id), 56.dp)
                            Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.people_friend_mode), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                FriendMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = friendMode == mode,
                        onClick = { onSetFriendMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, FriendMode.entries.size),
                    ) { Text(stringResource(mode.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(
                stringResource(friendMode.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (suggestions.isNotEmpty()) {
            item { Section(stringResource(R.string.people_suggested)) }
            items(suggestions, key = { g -> "suggest-" + g.joinToString { it.key } }) { group ->
                val name = PeopleBook.displayName(group.maxBy { it.total }.sender)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            group.map { it.packageName }.distinct().forEach { AppIcon(it, size = 22.dp, modifier = Modifier.padding(end = 4.dp)) }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(R.string.people_suggestion_text, name, group.map { it.appName }.distinct().joinToString(", ")),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onIgnoreMerge(name.lowercase()) }) { Text(stringResource(R.string.people_not_same)) }
                            TextButton(onClick = { onCombine(group) }) { Text(stringResource(R.string.people_combine)) }
                        }
                    }
                }
            }
        }
        item { Section(stringResource(R.string.people_all)) }
        if (people.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sorted, key = { it.id }) { p ->
            val count = personCount(p, senders, week)
            Card(onClick = { onOpenPerson(p.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, photoFile(p.id), 44.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            LocalContext.current.resources.getQuantityString(R.plurals.people_week_count, count.toInt(), count.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 4.dp)) {
                            p.packages.forEach { AppIcon(it, size = 16.dp, modifier = Modifier.padding(end = 4.dp)) }
                        }
                    }
                    if (p.favorite) Icon(Icons.Default.Star, stringResource(R.string.people_star), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private val FriendMode.label: Int
    get() = when (this) {
        FriendMode.OFF -> R.string.people_friend_mode_off
        FriendMode.ALLOW -> R.string.people_friend_mode_allow
        FriendMode.ALERT -> R.string.people_friend_mode_alert
    }

private val FriendMode.hint: Int
    get() = when (this) {
        FriendMode.OFF -> R.string.people_friend_mode_hint_off
        FriendMode.ALLOW -> R.string.people_friend_mode_hint_allow
        FriendMode.ALERT -> R.string.people_friend_mode_hint_alert
    }

/** How many notifications [p] sent since [firstDay] (Int.MIN_VALUE: ever), across all their usernames. */
fun personCount(p: Person, senders: Collection<SenderCount>, firstDay: Int): Long {
    val keys = p.aliases.mapTo(HashSet()) { it.key }
    return senders.filter { Alias.keyOf(it.packageName, it.sender) in keys }.sumOf { it.countSince(firstDay) }
}

/** The person's contact photo, or their initial on a colour picked from their name. */
@Composable
fun Avatar(person: Person, photo: File, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, person.id, person.hasPhoto) {
        value = if (!person.hasPhoto) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(photo.path)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(size).background(avatarColor(person.name), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                person.name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

private fun avatarColor(name: String): Color {
    val palette = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8E24AA, 0xFF43A047, 0xFFD81B60, 0xFF1E88E5, 0xFF6D4C41)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/**
 * Pick usernames (app + sender) from everything NotiRegX has seen. Ones that already belong to
 * someone say who; picking them moves them.
 */
@Composable
fun AliasPickerDialog(
    senders: Collection<SenderCount>,
    people: List<Person>,
    initial: Set<String> = emptySet(),
    exclude: Set<String> = emptySet(),
    confirmLabel: @Composable (Int) -> String,
    onDone: (List<Alias>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial) }
    val rows = remember(senders, exclude) {
        senders.filter { Alias.keyOf(it.packageName, it.sender) !in exclude }.sortedByDescending { it.total }
    }
    val shown = rows.filter {
        query.isEmpty() || it.sender.contains(query, ignoreCase = true) || it.appName.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alias_picker_title)) },
        text = {
            Column {
                Text(stringResource(R.string.alias_picker_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.alias_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (rows.isEmpty()) Text(stringResource(R.string.alias_picker_none))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.key }) { s ->
                        val key = Alias.keyOf(s.packageName, s.sender)
                        val checked = key in selected
                        val owner = PeopleBook.personFor(people, s.packageName, s.sender)
                        ListItem(
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            headlineContent = { Text(s.sender, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(owner?.let { stringResource(R.string.alias_picker_taken, it.name) } ?: s.appName, maxLines = 1)
                            },
                            trailingContent = { AppIcon(s.packageName, size = 22.dp) },
                            modifier = Modifier.clickable { selected = if (checked) selected - key else selected + key },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onDone(rows.filter { Alias.keyOf(it.packageName, it.sender) in selected }.map { Alias(it.packageName, it.appName, it.sender) })
                },
            ) { Text(confirmLabel(selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Name a new person made from the chosen usernames (prefilled from the busiest one). */
@Composable
fun NamePersonDialog(initialName: String, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_person_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.person_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text(stringResource(R.string.create_person_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Small round icon-button star used in lists. */
@Composable
fun StarButton(on: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Default.Star,
            contentDescription = stringResource(if (on) R.string.people_unstar else R.string.people_star),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
