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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notiregx.app.Settings
import com.notiregx.app.model.RuleType
import kotlinx.coroutines.launch

private const val PAGES = 5

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
                if (!last) TextButton(onClick = onDone) { Text("Skip") }
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
                    Text(if (last) "Get started" else "Next")
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
        "Welcome to NotiRegX",
        "Your phone gets a lot of notifications. NotiRegX lets you decide which ones deserve your attention, " +
            "using rules that match the app, the title or the text.",
    )
    Point("Hide the noise", "Likes, promos and reactions disappear, but stay saved in the Archive in case you want them.")
    Point("Bundle the rest", "Collect less urgent things into one notification with a count, or a digest at set times.")
    Point("Never miss what matters", "Let important people and urgent words through, even in Do Not Disturb.")
    Point("Find out what buzzed", "See every vibration, including \"ghost\" buzzes with no notification behind them.")
}

@Composable
private fun RulesPage() {
    PageHeader(
        Icons.AutoMirrored.Filled.List,
        "How rules work",
        "A rule matches notifications by app and by a pattern in the title or text. Rules run from top to " +
            "bottom, and the last rule that matches decides what happens.",
    )
    RuleType.entries.forEach { Point(it.label, it.explanation) }
    Point(
        "Example",
        "Rule 1: Hide everything from Instagram. Rule 2 (below it): Always alert when the text mentions your name. " +
            "Mentions get through and ring; everything else from Instagram is hidden.",
    )
}

@Composable
private fun PermissionsPage(settings: Settings, resumeTick: Int, onOpenIntent: (android.content.Intent) -> Unit) {
    val hasAccess = remember(resumeTick) { settings.hasNotificationAccess() }
    val canPost = remember(resumeTick) { settings.canPostNotifications() }
    PageHeader(
        Icons.Default.Lock,
        "Let NotiRegX see notifications",
        "Android needs your permission before an app can read and filter notifications. Everything stays on " +
            "your phone; nothing is sent anywhere.",
    )
    PermissionRow(
        title = "Notification access",
        hint = "Required. Turn NotiRegX on in the list that opens.",
        granted = hasAccess,
        onFix = { onOpenIntent(settings.notificationAccessIntent()) },
    )
    PermissionRow(
        title = "Post notifications",
        hint = "For bundles, digests and alerts.",
        granted = canPost,
        onFix = { onOpenIntent(settings.appNotificationSettingsIntent()) },
    )
    Hint(
        "Samsung: if Android says notification access is a \"restricted setting\", open App info, tap ⋮ and " +
            "choose \"Allow restricted settings\", then try again."
    )
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
        "Optional extras",
        "None of these are needed to start. You can turn them on later in Settings.",
    )
    PermissionRow(
        title = "Unrestricted battery use",
        hint = "Recommended on Samsung, so the phone doesn't put the filter to sleep.",
        granted = unrestricted,
        onFix = { onOpenIntent(settings.batteryOptimizationIntent()) },
    )
    PermissionRow(
        title = "Do Not Disturb access",
        hint = "Lets \"Always alert\" rules ring through Do Not Disturb.",
        granted = dnd,
        onFix = { onOpenIntent(settings.dndAccessIntent()) },
    )
    if (settings.supportsCompanionDevices()) {
        PermissionRow(
            title = "De-buzz (link a device)",
            hint = "Stops filtered notifications from buzzing at all. Pick any nearby Bluetooth device; nothing is sent to it.",
            granted = companionLinked,
            onFix = onLinkCompanion,
        )
    }
    Point(
        "Ghost vibrations",
        "To see vibrations that come with no notification, run this once from a computer with adb: " +
            "adb shell pm grant com.notiregx.app android.permission.DUMP",
    )
}

@Composable
private fun StartPage() {
    PageHeader(
        Icons.Default.Check,
        "Where to start",
        "Let NotiRegX run for a while, then turn what you see into rules.",
    )
    Point("Make rules from real notifications", "Archive › Recent: tap Make rule on anything annoying. NotiRegX suggests patterns based on similar notifications.")
    Point("Accept suggestions", "Swipe away what you don't want as usual. After a few, \"Suggested for you\" proposes rules on the Rules tab.")
    Point("See who sends what", "Archive › Senders ranks people and apps by how much they send you.")
    Point("Check what buzzed", "The Vibrations tab lists every buzz. Add the \"Last buzz\" quick-settings tile to check in one tap.")
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
