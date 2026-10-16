package com.notiregx.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleSuggester
import com.notiregx.app.model.Suggestion

/** "What do you want to filter?" — rule guesses for [target], built from the app's other notifications. */
@Composable
fun SuggestionDialog(
    target: Notification,
    known: List<Notification>,
    onPick: (Rule) -> Unit,
    onDismiss: () -> Unit,
) {
    val suggestions = remember(target.id) { RuleSuggester.suggest(target, known) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make a rule") },
        text = {
            Column {
                Text(
                    "Guesses from ${suggestions.lastOrNull()?.total ?: 1} notifications NotiRegX has seen from ${target.appName}. " +
                        "Pick one to fine-tune it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(suggestions) { s ->
                        ListItem(
                            headlineContent = { Text(s.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    if (s.regex.isNotEmpty()) {
                                        Text(s.regex, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("Matches ${s.matches} of ${s.total}")
                                }
                            },
                            modifier = Modifier.clickable { onPick(ruleFor(target, s)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(ruleFor(target, null)) }) { Text("Start from scratch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ruleFor(target: Notification, s: Suggestion?): Rule {
    val base = Rule(name = target.appName, packageName = target.packageName, appName = target.appName)
    if (s == null) return base.copy(regex = Rule.escape(target.title))
    val name = if (s.regex.isEmpty()) target.appName else "${target.appName}: ${s.label}"
    return base.copy(name = name.take(60), regex = s.regex, field = s.field)
}
