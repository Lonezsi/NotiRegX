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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.notiregx.app.R
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleAdvice
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.formatMinute

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** A Long count as the Int that getQuantityString picks the plural form by. */
private fun Long.quantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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
                    title = stringResource(R.string.home_stopped_title),
                    body = stringResource(R.string.home_stopped_body),
                    action = stringResource(R.string.home_stopped_action),
                    onAction = onRestartListener,
                )
            }
        }
        if (!hasAccess) {
            item(key = "notice-access") {
                NoticeCard(
                    title = stringResource(R.string.home_access_title),
                    body = stringResource(R.string.home_access_body),
                    action = stringResource(R.string.home_access_action),
                    onAction = onGrantAccess,
                )
            }
        }
        if (!canPost && rules.any { it.type == RuleType.BUNDLE || it.debuzz }) {
            item(key = "notice-post") {
                NoticeCard(
                    title = stringResource(R.string.home_post_title),
                    body = stringResource(R.string.home_post_body),
                    action = stringResource(R.string.home_post_action),
                    onAction = onGrantPost,
                )
            }
        }
        if (!filteringEnabled) {
            item(key = "notice-paused") {
                NoticeCard(title = stringResource(R.string.home_paused_title), body = stringResource(R.string.home_paused_body))
            }
        }
        if (advice.isNotEmpty()) {
            item(key = "advice-header") {
                Text(stringResource(R.string.home_suggested), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            items(advice, key = { "advice-" + it.key }) { a ->
                AdviceCard(a, onUse = { onUseAdvice(a) }, onIgnore = { onIgnoreAdvice(a.key) })
            }
        }
        item(key = "intro") {
            val filtered = rules.filter { !it.type.letsThrough }.sumOf { it.totalCaught }
            Text(
                if (rules.isEmpty()) stringResource(R.string.home_intro_empty)
                else LocalContext.current.resources
                    .getQuantityString(R.plurals.home_intro_filtered, filtered.quantity(), "%,d".format(filtered)),
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
    val context = LocalContext.current
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (dragged) 8.dp else 1.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.home_drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handle.padding(8.dp),
                )
                AppIcon(rule.packageName, size = 28.dp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            stringResource(rule.type.label),
                            rule.appName ?: stringResource(R.string.any_app),
                            stringResource(R.string.home_debuzzed).takeIf { rule.debuzz },
                        )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Column(Modifier.padding(start = 44.dp, top = 4.dp)) {
                Text(
                    (if (rule.field == MatchField.ANY) "" else "${stringResource(rule.field.label).lowercase()}: ") +
                        "/${rule.regex}/".let { if (rule.invert) stringResource(R.string.home_regex_not, it) else it },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val conditions = listOfNotNull(
                    rule.schedule?.let { context.scheduleSummary(it) },
                    rule.burst?.let { context.burstSummary(it) },
                    rule.deliveryTimes.takeIf { rule.isDigest }?.let { t ->
                        context.getString(R.string.home_delivered_at, t.sorted().joinToString(", ") { formatMinute(it) })
                    },
                )
                if (conditions.isNotEmpty()) {
                    Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                val week = rule.hitsSince(now - Rule.STATS_WINDOW_MS)
                val stale = week == 0 && rule.enabled && now - rule.createdAt > DAY_MS
                Text(
                    if (stale) stringResource(R.string.home_stale)
                    else listOfNotNull(
                        context.resources.getQuantityString(R.plurals.home_matches_week, week, week),
                        stringResource(R.string.home_matches_today, rule.hitsSince(now - DAY_MS)),
                        context.resources.getQuantityString(R.plurals.home_caught_total, rule.totalCaught.quantity(), "%,d".format(rule.totalCaught))
                            .takeIf { !rule.type.letsThrough },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!rule.type.letsThrough) {
                    val unseen = rule.bundle.count
                    TextButton(onClick = onOpenArchive) {
                        Text(
                            context.resources.getQuantityString(R.plurals.home_saved, rule.history.size, rule.history.size) +
                                if (unseen > 0) " · " + context.resources.getQuantityString(R.plurals.home_unseen, unseen, unseen) else "",
                        )
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
