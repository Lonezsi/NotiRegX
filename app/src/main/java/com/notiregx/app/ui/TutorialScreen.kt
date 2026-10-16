@file:OptIn(ExperimentalFoundationApi::class)

package com.notiregx.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.Settings
import com.notiregx.app.model.RuleType
import kotlinx.coroutines.launch

private const val PAGES = 5

/** Not translated: it is typed as-is on a computer. */
private const val ADB_GRANT_DUMP = "adb shell pm grant com.notiregx.app android.permission.DUMP"

/** First-launch walkthrough: what NotiRegX does, how rules work, the permissions it needs, and where to start. */
@Composable
fun TutorialScreen(
    settings: Settings,
    resumeTick: Int,
    companionLinked: Boolean,
    onLinkCompanion: () -> Unit,
    onOpenIntent: (android.content.Intent) -> Unit,
    onDone: () -> Unit,
) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.tutorial_skip)) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> RulesPage()
                        2 -> PermissionsPage(settings, resumeTick, onOpenIntent)
                        3 -> ExtrasPage(settings, resumeTick, companionLinked, onLinkCompanion, onOpenIntent)
                        else -> StartPage()
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    repeat(PAGES) { i ->
                        Box(
                            Modifier
                                .size(if (i == pager.currentPage) 10.dp else 8.dp)
                                .background(
                                    if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                        )
                    }
                }
                Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(stringResource(if (last) R.string.tutorial_get_started else R.string.tutorial_next))
                }
            }
        }
    }
}

@Composable
private fun PageHeader(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.size(8.dp))
    Box(
        Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(body, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Point(title: String, body: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomePage() {
    PageHeader(
        Icons.Default.Notifications,
        stringResource(R.string.tutorial_welcome_title),
        stringResource(R.string.tutorial_welcome_body),
    )
    // Let people switch language before reading the rest.
    LanguageSetting()
    Point(stringResource(R.string.tutorial_hide_title), stringResource(R.string.tutorial_hide_body))
    Point(stringResource(R.string.tutorial_bundle_title), stringResource(R.string.tutorial_bundle_body))
    Point(stringResource(R.string.tutorial_never_miss_title), stringResource(R.string.tutorial_never_miss_body))
    Point(stringResource(R.string.tutorial_what_buzzed_title), stringResource(R.string.tutorial_what_buzzed_body))
}

@Composable
private fun RulesPage() {
    PageHeader(
        Icons.AutoMirrored.Filled.List,
        stringResource(R.string.tutorial_rules_title),
        stringResource(R.string.tutorial_rules_body),
    )
    RuleType.entries.forEach { Point(stringResource(it.label), stringResource(it.explanation)) }
    Point(
        stringResource(R.string.tutorial_example_title),
        stringResource(R.string.tutorial_example_body),
    )
}

@Composable
private fun PermissionsPage(settings: Settings, resumeTick: Int, onOpenIntent: (android.content.Intent) -> Unit) {
    val hasAccess = remember(resumeTick) { settings.hasNotificationAccess() }
    val canPost = remember(resumeTick) { settings.canPostNotifications() }
    PageHeader(
        Icons.Default.Lock,
        stringResource(R.string.tutorial_permissions_title),
        stringResource(R.string.tutorial_permissions_body),
    )
    PermissionRow(
        title = stringResource(R.string.tutorial_access_title),
        hint = stringResource(R.string.tutorial_access_hint),
        granted = hasAccess,
        onFix = { onOpenIntent(settings.notificationAccessIntent()) },
    )
    PermissionRow(
        title = stringResource(R.string.tutorial_post_title),
        hint = stringResource(R.string.tutorial_post_hint),
        granted = canPost,
        onFix = { onOpenIntent(settings.appNotificationSettingsIntent()) },
    )
    Hint(stringResource(R.string.tutorial_samsung_restricted_hint))
}

@Composable
private fun ExtrasPage(
    settings: Settings,
    resumeTick: Int,
    companionLinked: Boolean,
    onLinkCompanion: () -> Unit,
    onOpenIntent: (android.content.Intent) -> Unit,
) {
    val unrestricted = remember(resumeTick) { settings.isIgnoringBatteryOptimizations() }
    val dnd = remember(resumeTick) { settings.hasDndAccess() }
    PageHeader(
        Icons.Default.Star,
        stringResource(R.string.tutorial_extras_title),
        stringResource(R.string.tutorial_extras_body),
    )
    PermissionRow(
        title = stringResource(R.string.tutorial_battery_title),
        hint = stringResource(R.string.tutorial_battery_hint),
        granted = unrestricted,
        onFix = { onOpenIntent(settings.batteryOptimizationIntent()) },
    )
    PermissionRow(
        title = stringResource(R.string.tutorial_dnd_title),
        hint = stringResource(R.string.tutorial_dnd_hint),
        granted = dnd,
        onFix = { onOpenIntent(settings.dndAccessIntent()) },
    )
    if (settings.supportsCompanionDevices()) {
        PermissionRow(
            title = stringResource(R.string.tutorial_debuzz_title),
            hint = stringResource(R.string.tutorial_debuzz_hint),
            granted = companionLinked,
            onFix = onLinkCompanion,
        )
    }
    Point(
        stringResource(R.string.tutorial_ghost_title),
        stringResource(R.string.tutorial_ghost_body, ADB_GRANT_DUMP),
    )
}

@Composable
private fun StartPage() {
    PageHeader(
        Icons.Default.Check,
        stringResource(R.string.tutorial_start_title),
        stringResource(R.string.tutorial_start_body),
    )
    Point(stringResource(R.string.tutorial_make_rules_title), stringResource(R.string.tutorial_make_rules_body))
    Point(stringResource(R.string.tutorial_accept_title), stringResource(R.string.tutorial_accept_body))
    Point(stringResource(R.string.tutorial_senders_title), stringResource(R.string.tutorial_senders_body))
    Point(stringResource(R.string.tutorial_check_buzz_title), stringResource(R.string.tutorial_check_buzz_body))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
