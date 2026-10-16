@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleSuggester
import com.notiregx.app.model.Suggestion
import com.notiregx.app.model.SuggestionGroup

/**
 * "Make a rule" from [target]: start from scratch (the default), or pick one of the guesses built
 * from the app's other notifications, grouped by what they do.
 */
@Composable
fun SuggestionScreen(
    target: Notification,
    known: List<Notification>,
    onPick: (Rule) -> Unit,
    onBack: () -> Unit,
) {
    val suggestions = remember(target.id) { RuleSuggester.suggest(target, known) }
    val total = suggestions.lastOrNull()?.total ?: 1
    val groups = remember(suggestions) { suggestions.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.suggestions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.suggestions_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SourceCard(target) }
            item {
                Button(
                    onClick = { onPick(scratchRule(target)) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.suggestions_start_from_scratch), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.suggestions_scratch_hint, target.appName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                Text(
                    context.resources.getQuantityString(R.plurals.suggestions_header, total, total, target.appName),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            groups.forEach { (group, list) ->
                item(key = "header-${group.name}") {
                    Text(
                        stringResource(group.titleRes()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(list, key = { "${it.group}|${it.field}|${it.invert}|${it.regex}" }) { s ->
                    SuggestionCard(s, onClick = { onPick(ruleFor(context, target, s)) })
                }
            }
        }
    }
}

@Composable
private fun SourceCard(n: Notification) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            AppIcon(n.packageName, size = 40.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (n.title.isNotEmpty()) Text(n.title, style = MaterialTheme.typography.titleMedium)
                if (n.text.isNotEmpty()) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.text(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.regex.isNotEmpty()) {
                Text(
                    if (s.invert) stringResource(R.string.suggestions_regex_not, s.regex) else "/${s.regex}/",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { s.matches.toFloat() / s.total.coerceAtLeast(1) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.suggestions_match_count, s.matches, s.total),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            s.examples.forEach { e ->
                Text(
                    stringResource(
                        R.string.suggestions_example,
                        listOf(e.title, e.text).filter { it.isNotEmpty() }.joinToString(": ").replace('\n', ' '),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The app is picked, everything else blank. */
private fun scratchRule(target: Notification) =
    Rule(name = target.appName, packageName = target.packageName, appName = target.appName)

private fun ruleFor(context: Context, target: Notification, s: Suggestion): Rule {
    val name = if (s.group == SuggestionGroup.EVERYTHING) target.appName else "${target.appName}: ${context.suggestionText(s)}"
    return scratchRule(target).copy(name = name.take(60), regex = s.regex, field = s.field, invert = s.invert)
}

/** Heading for a group of suggestions, in the app's language. */
@StringRes
private fun SuggestionGroup.titleRes(): Int = when (this) {
    SuggestionGroup.SIMILAR -> R.string.suggestions_group_similar
    SuggestionGroup.CONTAINS -> R.string.suggestions_group_contains
    SuggestionGroup.EXACT -> R.string.suggestions_group_exact
    SuggestionGroup.EXCEPT -> R.string.suggestions_group_except
    SuggestionGroup.EVERYTHING -> R.string.suggestions_group_everything
}
