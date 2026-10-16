@file:OptIn(ExperimentalMaterial3Api::class)

package com.notiregx.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.notiregx.app.R
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.model.Notification
import com.notiregx.app.model.Bundle
import com.notiregx.app.model.DismissalAdvisor
import com.notiregx.app.model.Rule
import com.notiregx.app.model.RuleAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Tab(@StringRes val title: Int) {
    RULES(R.string.root_tab_rules),
    ARCHIVE(R.string.root_tab_archive),
    VIBRATIONS(R.string.root_tab_vibrations),
    SETTINGS(R.string.root_tab_settings);

    @Composable
    fun Icon() = when (this) {
        RULES -> Icon(Icons.Default.Home, contentDescription = null)
        ARCHIVE -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
        VIBRATIONS -> Icon(painterResource(R.drawable.ic_vibration), contentDescription = null)
        SETTINGS -> Icon(Icons.Default.Settings, contentDescription = null)
    }
}

@Composable
fun AppRoot(
    app: NotiRegXApp,
    openRuleId: MutableState<String?>,
    openTab: MutableState<String?>,
    resumeTick: Int,
    actions: AppActions,
) {
    val context = LocalContext.current
    val manager = app.ruleManager
    val rules by manager.rules.collectAsState()
    val recent by manager.recent.collectAsState()
    val filtering by app.settings.filteringEnabled.collectAsState()
    val companionLinked by app.companionLinked.collectAsState()
    val muteStatus by app.muteStatus.collectAsState()
    val vibrations by app.vibrationLog.events.collectAsState()
    val vibrationMuted by app.vibrationMuted.collectAsState()
    val senders by app.senderStats.senders.collectAsState()
    val listenerConnected by app.listenerConnected.collectAsState()
    // The system binds the listener a moment after the app starts; don't flash a warning before then.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(4_000)
        settled = true
    }
    val dndAccess = remember(resumeTick) { app.settings.hasDndAccess() }
    val interactions by app.interactions.events.collectAsState()
    val ignoredAdvice by app.settings.ignoredAdvice.collectAsState()
    // Only rule configuration matters for advice, not histories that change with every notification.
    val ruleConfig = remember(rules) { rules.map { it.copy(history = emptyList(), hits = emptyList(), bundle = Bundle(), totalCaught = 0) } }
    val advice by produceState(emptyList<RuleAdvice>(), interactions, ruleConfig, ignoredAdvice) {
        value = withContext(Dispatchers.Default) { DismissalAdvisor.advise(interactions, ruleConfig, ignoredAdvice) }
    }
    val vibrationMuteError by app.vibrationMuteError.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.RULES) }
    /** Selected archive tab: a rule id, or null for the Recent log. */
    var archiveRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var showTutorial by rememberSaveable { mutableStateOf(!app.settings.tutorialSeen) }
    /** Notification the user wants to make a rule from; shows the suggestions dialog. */
    var suggestFor by remember { mutableStateOf<Notification?>(null) }

    val hasAccess = remember(resumeTick) { app.settings.hasNotificationAccess() }
    val canPost = remember(resumeTick) { app.settings.canPostNotifications() }
    val systemLogAvailable = remember(resumeTick) { app.systemVibrations.hasPermission() }

    val requestPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(showTutorial) {
        val permission = MainActivity.POST_NOTIFICATIONS
        if (!showTutorial && permission != null && !app.settings.canPostNotifications()) requestPost.launch(permission)
    }

    LaunchedEffect(openTab.value) {
        if (openTab.value == MainActivity.TAB_VIBRATIONS) {
            tab = Tab.VIBRATIONS
            editing = null
        }
        openTab.value = null
    }

    LaunchedEffect(openRuleId.value) {
        val id = openRuleId.value ?: return@LaunchedEffect
        archiveRuleId = id
        tab = Tab.ARCHIVE
        editing = null
        openRuleId.value = null
    }

    if (showTutorial) {
        TutorialScreen(
            settings = app.settings,
            resumeTick = resumeTick,
            companionLinked = companionLinked,
            onLinkCompanion = actions.linkCompanion,
            onOpenIntent = actions.openIntent,
            onDone = {
                app.settings.markTutorialSeen()
                showTutorial = false
            },
        )
        return
    }

    val draft = editing
    if (draft != null) {
        BackHandler { editing = null }
        RuleEditorScreen(
            initial = draft,
            isNew = rules.none { it.id == draft.id },
            recent = recent,
            companionLinked = companionLinked,
            onLinkCompanion = actions.linkCompanion,
            senders = senders.values,
            dndAccess = dndAccess,
            onGrantDnd = { actions.openIntent(app.settings.dndAccessIntent()) },
            onSave = { manager.save(it); editing = null },
            onDelete = { manager.delete(draft.id); editing = null },
            onBack = { editing = null },
        )
        return
    }

    val target = suggestFor
    if (target != null) {
        BackHandler { suggestFor = null }
        SuggestionScreen(
            target = target,
            known = recent.map { it.notification } + rules.flatMap { it.history },
            onPick = { editing = it; suggestFor = null },
            onBack = { suggestFor = null },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == Tab.RULES) "NotiRegX" else stringResource(tab.title)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { it.Icon() },
                        label = { Text(stringResource(it.title)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.RULES) {
                FloatingActionButton(onClick = { editing = Rule(name = "") }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.root_add_rule))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.RULES -> HomeScreen(
                    rules = rules,
                    hasAccess = hasAccess,
                    canPost = canPost,
                    filteringEnabled = filtering,
                    onGrantAccess = { actions.openIntent(app.settings.notificationAccessIntent()) },
                    onGrantPost = { actions.openIntent(app.settings.appNotificationSettingsIntent()) },
                    listenerStopped = settled && hasAccess && !listenerConnected,
                    onRestartListener = app.watchdog::rebind,
                    onEdit = { editing = it },
                    onMoveTo = manager::moveTo,
                    onToggle = manager::setEnabled,
                    onOpenArchive = { archiveRuleId = it; tab = Tab.ARCHIVE },
                    advice = advice,
                    onUseAdvice = { editing = ruleFromAdvice(context, it) },
                    onIgnoreAdvice = app.settings::ignoreAdvice,
                )
                Tab.ARCHIVE -> ArchiveScreen(
                    rules = rules,
                    recent = recent,
                    senders = senders.values,
                    onMakeRule = { editing = it },
                    selectedRuleId = archiveRuleId?.takeIf { id -> id == SENDERS_TAB || rules.any { it.id == id } },
                    onSelect = { archiveRuleId = it },
                    onMarkSeen = manager::markSeen,
                    onClearHistory = manager::clearHistory,
                    onCreateRule = { suggestFor = it },
                    onRestore = actions.restore,
                    actions = actions,
                )
                Tab.VIBRATIONS -> VibrationsScreen(
                    events = vibrations,
                    systemLogAvailable = systemLogAvailable,
                    onRefresh = app::refreshSystemVibrations,
                    onClear = app.vibrationLog::clear,
                    lastDump = { app.lastVibrationDump },
                    notifications = recent.map { it.notification } + rules.flatMap { it.history },
                    companionLinked = companionLinked,
                    vibrationMuted = vibrationMuted,
                    muteError = vibrationMuteError,
                    onSetVibrationMuted = { pkg, mute -> app.vibrationMuteRequests.tryEmit(pkg to mute) },
                    onLinkCompanion = actions.linkCompanion,
                    onCreateRule = { suggestFor = it },
                    actions = actions,
                )
                Tab.SETTINGS -> SettingsScreen(
                    settings = app.settings,
                    resumeTick = resumeTick,
                    companionLinked = companionLinked,
                    muteStatus = muteStatus,
                    onLinkCompanion = actions.linkCompanion,
                    onExport = actions.exportRules,
                    onImport = actions.importRules,
                    listenerConnected = listenerConnected,
                    onRestartListener = app.watchdog::rebind,
                    onShowTutorial = { showTutorial = true },
                    onOpenIntent = actions.openIntent,
                )
            }
        }
    }
}
