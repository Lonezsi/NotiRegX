package com.notiregx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleType

@Composable
fun HomeScreen(
    rules: List<Rule>,
    hasAccess: Boolean,
    canPost: Boolean,
    filteringEnabled: Boolean,
    onGrantAccess: () -> Unit,
    onGrantPost: () -> Unit,
    onEdit: (Rule) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenArchive: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasAccess) {
            item {
                NoticeCard(
                    title = "Notification access is off",
                    body = "NotiRegX can't see or filter anything until you allow it in Android settings.",
                    action = "Grant access",
                    onAction = onGrantAccess,
                )
            }
        }
        if (!canPost && rules.any { it.type == RuleType.BUNDLE }) {
            item {
                NoticeCard(
                    title = "Bundle notifications are blocked",
                    body = "Allow NotiRegX to post notifications so bundles can show their counts.",
                    action = "Allow",
                    onAction = onGrantPost,
                )
            }
        }
        if (!filteringEnabled) {
            item { NoticeCard(title = "Filtering is paused", body = "Every notification is let through. Resume it in Settings.") }
        }
        item {
            Text(
                if (rules.isEmpty()) "No rules yet. Tap + to create one."
                else "Rules run top to bottom. The last rule that matches decides what happens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            RuleCard(
                rule = rule,
                canMoveUp = index > 0,
                canMoveDown = index < rules.lastIndex,
                onClick = { onEdit(rule) },
                onMove = { onMove(rule.id, it) },
                onToggle = { onToggle(rule.id, it) },
                onOpenArchive = { onOpenArchive(rule.id) },
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenArchive: () -> Unit,
) {
    ElevatedCard(onClick = onClick) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(rule.type.label, rule.appName ?: "Any app", "De-buzzed".takeIf { rule.debuzz })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                (if (rule.field == MatchField.ANY) "" else "${rule.field.label.lowercase()}: ") +
                    (if (rule.invert) "NOT " else "") + "/${rule.regex}/",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rule.type != RuleType.ALLOW) {
                    val unseen = rule.bundle.count
                    TextButton(onClick = onOpenArchive) {
                        Text("${rule.history.size} saved" + if (unseen > 0) " · $unseen unseen" else "")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
        }
    }
}

@Composable
fun NoticeCard(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    ElevatedCard {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
