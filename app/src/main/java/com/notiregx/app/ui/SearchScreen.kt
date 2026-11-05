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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Person
import com.notiregx.app.model.Search
import com.notiregx.app.model.SearchHit
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Find anything NotiRegX kept: by words (accents don't matter), person and app. */
@Composable
fun SearchScreen(
    sources: List<SearchHit>,
    people: List<Person>,
    photoFile: (String) -> File,
    onBack: () -> Unit,
    onOpen: (Notification) -> Unit,
    onMakeRule: (Notification) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var app by rememberSaveable { mutableStateOf<String?>(null) }
    val apps = remember(sources) {
        sources.groupBy { it.notification.packageName }.map { (pkg, hits) -> pkg to hits.first().notification.appName }
            .sortedBy { it.second.lowercase() }
    }
    val results = remember(sources, query, personId, app) { Search.search(query, sources, personId, app) }
    val byId = remember(people) { people.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (people.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = personId == null, onClick = { personId = null }, label = { Text(stringResource(R.string.search_all_people)) }) }
                        items(people, key = { it.id }) { p ->
                            FilterChip(
                                selected = personId == p.id,
                                onClick = { personId = if (personId == p.id) null else p.id },
                                leadingIcon = { Avatar(p, photoFile(p.id), 18.dp) },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = app == null, onClick = { app = null }, label = { Text(stringResource(R.string.search_all_apps)) }) }
                    items(apps, key = { it.first }) { (pkg, name) ->
                        FilterChip(
                            selected = app == pkg,
                            onClick = { app = if (app == pkg) null else pkg },
                            leadingIcon = { AppIcon(pkg, size = 18.dp) },
                            label = { Text(name) },
                        )
                    }
                }
            }
            item {
                Text(
                    if (results.isEmpty()) stringResource(R.string.search_nothing)
                    else LocalContext.current.resources.getQuantityString(R.plurals.search_results, results.size, results.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(results, key = { "${it.notification.packageName}|${it.notification.postedAt}|${it.notification.content.hashCode()}" }) { hit ->
                val n = hit.notification
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            hit.personId?.let { byId[it] }?.let { Avatar(it, photoFile(it.id), 20.dp); Spacer(Modifier.size(6.dp)) }
                            AppIcon(n.packageName, size = 16.dp)
                            Spacer(Modifier.size(6.dp))
                            Text(n.title.ifEmpty { n.appName }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.postedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        n.group?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (hit.deleted) Text(stringResource(R.string.search_deleted), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onMakeRule(n) }) { Text(stringResource(R.string.person_make_rule)) }
                            TextButton(onClick = { onOpen(n) }) { Text(stringResource(R.string.person_open)) }
                        }
                    }
                }
            }
        }
    }
}
