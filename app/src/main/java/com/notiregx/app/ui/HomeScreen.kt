package com.notiregx.app.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleAdvice
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.formatMinute

private const val DAY_MS = 24 * 60 * 60 * 1000L

@Composable
fun HomeScreen(
    rules: List<Rule>,
    hasAccess: Boolean,
    canPost: Boolean,
    filteringEnabled: Boolean,
    onGrantAccess: () -> Unit,
    onGrantPost: () -> Unit,
    listenerStopped: Boolean,
    onRestartListener: () -> Unit,
    onEdit: (Rule) -> Unit,
    onMoveTo: (String, Int) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenArchive: (String) -> Unit,
    advice: List<RuleAdvice>,
    onUseAdvice: (RuleAdvice) -> Unit,
    onIgnoreAdvice: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentRules by rememberUpdatedState(rules)
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    /** Moves the dragged rule past a neighbour once it's dragged over half of it. */
    fun drag(delta: Float) {
        val id = dragging ?: return
        dragOffset += delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val index = currentRules.indexOfFirst { it.id == id }
        val ruleIds = currentRules.mapTo(HashSet()) { it.id }
        if (dragOffset > 0) {
            val next = visible.firstOrNull { it.index == current.index + 1 && it.key in ruleIds } ?: return
            if (dragOffset > next.size / 2f) {
                onMoveTo(id, index + 1)
                dragOffset -= next.size
            }
        } else {
            val prev = visible.firstOrNull { it.index == current.index - 1 && it.key in ruleIds } ?: return
            if (-dragOffset > prev.size / 2f) {
                onMoveTo(id, index - 1)
                dragOffset += prev.size
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (listenerStopped) {
            item(key = "notice-stopped") {
                NoticeCard(
                    title = "Filtering has stopped",
                    body = "Android disconnected NotiRegX's notification filter, so nothing is being filtered. " +
                        "If Restart doesn't help, turn NotiRegX's notification access off and on again.",
                    action = "Restart",
                    onAction = onRestartListener,
                )
            }
        }
        if (!hasAccess) {
            item(key = "notice-access") {
                NoticeCard(
                    title = "Notification access is off",
                    body = "NotiRegX can't see or filter anything until you allow it in Android settings.",
                    action = "Grant access",
                    onAction = onGrantAccess,
                )
            }
        }
        if (!canPost && rules.any { it.type == RuleType.BUNDLE || it.debuzz }) {
            item(key = "notice-post") {
                NoticeCard(
                    title = "NotiRegX can't post notifications",
                    body = "Allow it so bundles, digests and de-buzz alerts can show up.",
                    action = "Allow",
                    onAction = onGrantPost,
                )
            }
        }
        if (!filteringEnabled) {
            item(key = "notice-paused") {
                NoticeCard(title = "Filtering is paused", body = "Every notification is let through. Resume it in Settings.")
            }
        }
        if (advice.isNotEmpty()) {
            item(key = "advice-header") {
                Text("Suggested for you", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            items(advice, key = { "advice-" + it.key }) { a ->
                AdviceCard(a, onUse = { onUseAdvice(a) }, onIgnore = { onIgnoreAdvice(a.key) })
            }
        }
        item(key = "intro") {
            val filtered = rules.filter { !it.type.letsThrough }.sumOf { it.totalCaught }
            Text(
                if (rules.isEmpty()) "No rules yet. Tap + to create one, or Make rule on any notification in the Archive."
                else "Your rules have filtered ${"%,d".format(filtered)} notifications so far. Rules run top to bottom; " +
                    "the last rule that matches decides. Drag ≡ to reorder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { _, rule ->
            val isDragged = dragging == rule.id
            RuleCard(
                rule = rule,
                modifier = Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f },
                dragged = isDragged,
                handle = Modifier.pointerInput(rule.id) {
                    detectDragGestures(
                        onDragStart = { dragging = rule.id; dragOffset = 0f },
                        onDragEnd = { dragging = null; dragOffset = 0f },
                        onDragCancel = { dragging = null; dragOffset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            drag(amount.y)
                        },
                    )
                },
                onClick = { onEdit(rule) },
                onToggle = { onToggle(rule.id, it) },
                onOpenArchive = { onOpenArchive(rule.id) },
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    modifier: Modifier,
    dragged: Boolean,
    handle: Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenArchive: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (dragged) 8.dp else 1.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handle.padding(8.dp),
                )
                AppIcon(rule.packageName, size = 28.dp)
                Spacer(Modifier.size(10.dp))
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
            Column(Modifier.padding(start = 44.dp, top = 4.dp)) {
                Text(
                    (if (rule.field == MatchField.ANY) "" else "${rule.field.label.lowercase()}: ") +
                        (if (rule.invert) "NOT " else "") + "/${rule.regex}/",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val conditions = listOfNotNull(
                    rule.schedule?.summary,
                    rule.burst?.summary,
                    rule.deliveryTimes.takeIf { rule.isDigest }?.let { t -> "Delivered at " + t.sorted().joinToString(", ") { formatMinute(it) } },
                )
                if (conditions.isNotEmpty()) {
                    Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                val week = rule.hitsSince(now - Rule.STATS_WINDOW_MS)
                val stale = week == 0 && rule.enabled && now - rule.createdAt > DAY_MS
                Text(
                    if (stale) "No matches in the last 7 days. Check the regex or app?"
                    else "$week match${if (week == 1) "" else "es"} in the last 7 days · ${rule.hitsSince(now - DAY_MS)} today" +
                        if (!rule.type.letsThrough) " · ${"%,d".format(rule.totalCaught)} caught in total" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!rule.type.letsThrough) {
                    val unseen = rule.bundle.count
                    TextButton(onClick = onOpenArchive) {
                        Text("${rule.history.size} saved" + if (unseen > 0) " · $unseen unseen" else "")
                    }
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
