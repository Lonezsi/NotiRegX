package com.notiregx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.LoggedNotification
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.SenderCount

import java.text.DateFormat
import java.util.Date

/** Archive "tab" id for the senders leaderboard (rule ids are UUIDs, so this can't clash). */
const val SENDERS_TAB = "senders"

@Composable
fun ArchiveScreen(
    rules: List<Rule>,
    recent: List<LoggedNotification>,
    senders: Collection<SenderCount>,
    onMakeRule: (Rule) -> Unit,
    selectedRuleId: String?,
    onSelect: (String?) -> Unit,
    onMarkSeen: (String) -> Unit,
    onClearHistory: (String) -> Unit,
    onCreateRule: (Notification) -> Unit,
    onRestore: (Notification) -> Unit,
    actions: AppActions,
) {
    val archivable = rules.filter { !it.type.letsThrough || it.history.isNotEmpty() }
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = selectedRuleId == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.archive_tab_recent)) })
            }
            item {
                FilterChip(
                    selected = selectedRuleId == SENDERS_TAB,
                    onClick = { onSelect(SENDERS_TAB) },
                    label = { Text(stringResource(R.string.archive_tab_senders)) },
                )
            }
            items(archivable, key = { it.id }) { rule ->
                val unseen = rule.bundle.count
                FilterChip(
                    selected = selectedRuleId == rule.id,
                    onClick = { onSelect(rule.id) },
                    label = { Text(rule.name + if (unseen > 0) " ($unseen)" else "") },
                )
            }
        }
        val rule = rules.firstOrNull { it.id == selectedRuleId }
        if (selectedRuleId == SENDERS_TAB) {
            SendersView(senders, onMakeRule)
        } else if (rule == null) {
            RecentList(recent, onCreateRule, actions)
        } else {
            RuleHistory(rule, onMarkSeen, onClearHistory, onCreateRule, onRestore, actions)
        }
    }
}

@Composable
private fun RecentList(recent: List<LoggedNotification>, onCreateRule: (Notification) -> Unit, actions: AppActions) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                context.resources.getQuantityString(R.plurals.archive_recent_intro, recent.size, recent.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recent.isEmpty()) item { EmptyText(stringResource(R.string.archive_recent_empty)) }
        items(recent, key = { it.notification.id }) { entry ->
            NotificationCard(
                n = entry.notification,
                unseen = false,
                outcome = context.outcomeLabel(entry),
                actions = actions,
                onCreateRule = { onCreateRule(entry.notification) },
            )
        }
    }
}

@Composable
private fun RuleHistory(
    rule: Rule,
    onMarkSeen: (String) -> Unit,
    onClearHistory: (String) -> Unit,
    onCreateRule: (Notification) -> Unit,
    onRestore: (Notification) -> Unit,
    actions: AppActions,
) {
    // Snapshot what was unseen when the tab opened, so the dots survive marking them seen.
    val unseenAtOpen = remember(rule.id) { rule.bundle.unseen.toSet() }
    LaunchedEffect(rule.id, rule.bundle.count) {
        if (rule.bundle.count > 0) onMarkSeen(rule.id)
    }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rule.totalCaught > rule.history.size) {
                        stringResource(R.string.archive_saved_of_caught, rule.history.size, "%,d".format(rule.totalCaught))
                    } else {
                        context.resources.getQuantityString(R.plurals.archive_saved, rule.history.size, rule.history.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (rule.history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.archive_clear_history)) }
            }
        }
        if (rule.history.isEmpty()) item { EmptyText(stringResource(R.string.archive_rule_empty)) }
        items(rule.history, key = { it.id }) { n ->
            NotificationCard(
                n = n,
                unseen = n.id in unseenAtOpen,
                outcome = null,
                actions = actions,
                onCreateRule = { onCreateRule(n) },
                onRestore = { onRestore(n) }.takeIf { !rule.type.letsThrough },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.archive_clear_title)) },
            text = {
                Text(context.resources.getQuantityString(R.plurals.archive_clear_message, rule.history.size, rule.history.size, rule.name))
            },
            confirmButton = {
                TextButton(onClick = { onClearHistory(rule.id); confirmClear = false }) { Text(stringResource(R.string.archive_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NotificationCard(
    n: Notification,
    unseen: Boolean,
    outcome: String?,
    actions: AppActions,
    onCreateRule: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unseen) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.size(6.dp))
                }
                AppIcon(n.packageName, size = 18.dp)
                Spacer(Modifier.size(6.dp))
                Text(
                    n.appName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(n.postedAt), style = MaterialTheme.typography.labelSmall)
            }
            if (n.title.isNotEmpty()) Text(n.title, style = MaterialTheme.typography.titleSmall)
            if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
            if (outcome != null) {
                Text(outcome, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row {
                Spacer(Modifier.weight(1f))
                if (onRestore != null) TextButton(onClick = onRestore) { Text(stringResource(R.string.archive_restore)) }
                if (onCreateRule != null) TextButton(onClick = onCreateRule) { Text(stringResource(R.string.archive_make_rule)) }
                n.url?.let { url -> TextButton(onClick = { actions.openUrl(url) }) { Text(stringResource(R.string.archive_open_link)) } }
                TextButton(onClick = { actions.openNotification(n) }) {
                    Text(stringResource(if (actions.canOpenDirectly(n)) R.string.archive_open else R.string.archive_open_app))
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
