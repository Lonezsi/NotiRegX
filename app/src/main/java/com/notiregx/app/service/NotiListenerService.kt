package com.notiregx.app.service

import android.app.NotificationManager
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notiregx.app.MuteStatus
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.model.Action
import com.notiregx.app.model.ChatMessage
import com.notiregx.app.model.ConversationTracker
import com.notiregx.app.model.Decision
import com.notiregx.app.model.Interaction
import com.notiregx.app.model.Outcome
import com.notiregx.app.model.Notification
import com.notiregx.app.model.RuleManager
import com.notiregx.app.model.RuleType
import com.notiregx.app.model.VibrationEvent
import com.notiregx.app.model.VibrationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Receives every posted notification and applies the rule list's decision to it. */
class NotiListenerService : NotificationListenerService() {
    private val app get() = application as NotiRegXApp
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** Last content seen per notification key, so app updates with the same text aren't logged twice. */
    private val lastSeen = lru<Pair<Int, Action>>(512)

    /** Every key seen, filterable or not, to tell new notifications from updates. */
    private val seenKeys = lru<Unit>(1024)

    /** Groups whose children we removed; their summaries get removed once orphaned. */
    private val handledGroups = lru<Unit>(256)

    /** Notifications we let through, by key, so we can tell whether the user opened or swiped them. */
    private val inShade = lru<Notification>(1000)

    /** Per-message decisions for chat apps' conversation notifications. */
    private val conversations = ConversationTracker()

    private var scope: CoroutineScope? = null
    private var muter: ChannelMuter? = null
    private var vibrationMuter: VibrationMuter? = null

    private val muting get() = muter != null && app.companionLinked.value

    override fun onListenerConnected() {
        super.onListenerConnected()
        app.listenerConnected.value = true
        app.watchdog.check()
        val muter = ChannelMuter(this, this).also { muter = it }
        val vibrationMuter = VibrationMuter(this, this).also { vibrationMuter = it }
        app.vibrationMuted.value = vibrationMuter.mutedPackages
        scope = MainScope().apply {
            launch {
                app.vibrationMuteRequests.collect { (pkg, mute) ->
                    app.vibrationMuteError.value = if (mute) vibrationMuter.mute(pkg) else vibrationMuter.unmute(pkg)
                    app.vibrationMuted.value = vibrationMuter.mutedPackages
                }
            }
            launch {
                // Only re-sync when the set of silenced apps changes, not on every caught notification.
                val wanted = app.ruleManager.rules
                    .map { RuleManager.mutedPackages(it) to RuleManager.learnsChannels(it) }
                    .distinctUntilChanged()
                combine(wanted, app.companionLinked) { w, linked -> w to linked }
                    .collect { (w, linked) -> if (linked) publish(muter.sync(w.first, w.second)) }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        app.listenerConnected.value = false
        // Ask to come straight back; the watchdog follows up if this doesn't work.
        app.watchdog.rebind()
        scope?.cancel()
        scope = null
        muter = null
        vibrationMuter = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName == packageName) return
        val ranking = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        val isUpdate = sbn.key in seenKeys
        seenKeys[sbn.key] = Unit
        logLikelyVibration(sbn, ranking, isUpdate)
        // A vibration-muted app may have added a category since it was muted.
        if (ranking?.channel?.shouldVibrate() == true) vibrationMuter?.takeIf { it.isMuted(sbn.packageName) }?.muteNewChannels(sbn.packageName)

        if (!app.reader.isFilterable(sbn)) return
        if (NotificationReader.isGroupSummary(sbn)) {
            if (sbn.groupKey in handledGroups) removeOrphanedSummary(sbn.groupKey, exceptKey = null)
            return
        }

        // Chat apps add every message to one conversation notification: decide message by message.
        val messages = app.reader.readMessages(sbn)
        if (messages.any { !it.fromMe }) {
            handleConversation(sbn, ranking, messages)
            return
        }

        val n = app.reader.read(sbn, app.ruleManager.newNotificationId())
            ?.let { it.copy(personId = app.people.personFor(it.packageName, it.title)?.id) } ?: return
        val signature = n.content.hashCode()
        val previous = lastSeen[sbn.key]
        if (previous != null && previous.first == signature) {
            if (previous.second != Action.PASS) remove(sbn)
            return
        }

        sbn.notification.contentIntent?.let { app.contentIntents.put(n.id, it) }
        app.senderStats.record(n)
        app.people.record(n)
        val decision = app.ruleManager.process(n, app.settings.maxSaved.value, app.settings.filteringEnabled.value, app.friendRules())
        lastSeen[sbn.key] = signature to decision.action
        if (decision.action != Action.PASS) remove(sbn) else inShade[sbn.key] = n
        val alwaysAlert = decision.rule?.type == RuleType.ALERT
        if (alwaysAlert && needsBreakthrough(sbn, ranking, isUpdate = previous != null)) app.alerter.breakthrough(sbn, n)
        // An "Always alert" match already got its alert above; don't re-alert it a second time.
        if (muting && !alwaysAlert) handleDebuzz(sbn, ranking, n, decision, isUpdate = previous != null)
    }

    /**
     * A conversation notification: each new message from someone else goes through the rules on its
     * own (so a "Reacted ❤️" line is caught without taking the friend's real messages with it), and
     * the notification is only removed when every message in it is filtered.
     */
    private fun handleConversation(sbn: StatusBarNotification, ranking: Ranking?, messages: List<ChatMessage>) {
        val key = sbn.key
        val update = conversations.update(key, messages)
        val fresh = update.fresh
        // Messages that vanished from the middle of the conversation were unsent: keep and flag them.
        update.deleted.forEach { gone ->
            val person = app.people.markDeleted(gone)
            if (person != null && app.settings.rescueAlerts.value) app.alerter.rescued(gone, person.name)
        }
        var lastPassing: Pair<Notification, Decision>? = null
        var lastCaught: Pair<Notification, Decision>? = null
        var alert: Notification? = null
        for (m in fresh) {
            val person = app.people.personFor(m.notification.packageName, m.notification.title)
            val n = m.notification.copy(id = app.ruleManager.newNotificationId(), personId = person?.id)
            if (person != null && !person.hasPhoto) saveAvatar(sbn, person.id, n.title)
            sbn.notification.contentIntent?.let { app.contentIntents.put(n.id, it) }
            app.senderStats.record(n)
            app.people.record(n)
            val d = app.ruleManager.process(n, app.settings.maxSaved.value, app.settings.filteringEnabled.value, app.friendRules())
            conversations.record(key, m.id, d.action, n)
            if (d.action == Action.PASS) lastPassing = n to d else lastCaught = n to d
            if (d.rule?.type == RuleType.ALERT) alert = n
        }

        if (conversations.allFiltered(key)) {
            inShade.remove(key)
            remove(sbn)
        } else {
            (lastPassing?.first ?: messages.lastOrNull { !it.fromMe }?.notification)?.let { inShade[key] = it }
        }
        if (fresh.isEmpty()) return

        val isUpdate = fresh.size < messages.count { !it.fromMe }
        alert?.let { if (needsBreakthrough(sbn, ranking, isUpdate)) app.alerter.breakthrough(sbn, it) }
        val (n, d) = lastPassing ?: lastCaught ?: return
        if (muting && alert == null) handleDebuzz(sbn, ranking, n, d, isUpdate)
    }

    /** Uses the chat app's picture for a person who has no contact photo yet. */
    private fun saveAvatar(sbn: StatusBarNotification, personId: String, sender: String) {
        val bitmap = app.reader.senderAvatar(sbn, sender) ?: return
        val saved = try {
            app.photoFile(personId).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: java.io.IOException) {
            false
        }
        if (saved) app.people.update(personId) { it.copy(hasPhoto = true) }
    }

    /**
     * Learns from what the user does with notifications we let through: opened or swiped away.
     * Removals by the app itself or by NotiRegX say nothing about what the user wanted.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // A conversation the user or the app cleared starts over; one we removed ourselves may come back with more.
        if (reason != REASON_LISTENER_CANCEL) conversations.forget(sbn.key)
        val n = inShade.remove(sbn.key) ?: return
        val outcome = when (reason) {
            REASON_CLICK -> Outcome.OPENED
            REASON_CANCEL, REASON_CANCEL_ALL, REASON_GROUP_SUMMARY_CANCELED -> Outcome.DISMISSED
            else -> return
        }
        app.interactions.record(Interaction(n, outcome, System.currentTimeMillis()))
    }

    /**
     * Records the notification in the vibration log if its channel, Do Not Disturb and the ringer
     * mode say the phone vibrated for it. Filtered notifications count too: they buzz before we
     * get to remove them (unless de-buzzed, in which case their channel no longer vibrates).
     */
    private fun logLikelyVibration(sbn: StatusBarNotification, ranking: Ranking?, isUpdate: Boolean) {
        if (NotificationReader.isGroupSummary(sbn)) return
        val channel = ranking?.channel ?: return
        if (!ranking.matchesInterruptionFilter() || ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (isUpdate && sbn.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        val ringer = audio.ringerMode
        if (ringer == AudioManager.RINGER_MODE_SILENT) return
        if (!channel.shouldVibrate() && !(ringer == AudioManager.RINGER_MODE_VIBRATE && channel.sound != null)) return

        val n = app.reader.read(sbn, id = 0)
        app.vibrationLog.add(
            listOf(
                VibrationEvent(
                    time = sbn.postTime,
                    packageName = sbn.packageName,
                    appName = n?.appName ?: app.reader.appName(sbn.packageName),
                    source = VibrationSource.NOTIFICATION,
                    detail = n?.let { it.title.ifEmpty { it.text } }.orEmpty().take(120),
                )
            )
        )
    }

    /**
     * Whether an "Always alert" match would otherwise go unnoticed: Do Not Disturb blocks it, or
     * its category is silent (possibly because NotiRegX de-buzzed it). If it alerts on its own,
     * NotiRegX stays out of the way rather than buzzing twice.
     */
    private fun needsBreakthrough(sbn: StatusBarNotification, ranking: Ranking?, isUpdate: Boolean): Boolean {
        if (isUpdate && sbn.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0) return false
        if (ranking == null) return true
        return !ranking.matchesInterruptionFilter() || ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    /**
     * Re-alerts let-through notifications from channels we silenced, and silences channels that
     * any-app de-buzz rules catch things from (the first one still alerts; later ones won't).
     */
    private fun handleDebuzz(sbn: StatusBarNotification, ranking: Ranking?, n: Notification, decision: Decision, isUpdate: Boolean) {
        val muter = muter ?: return
        // For conversations, the ranking has the conversation's own channel.
        val channelId = ranking?.channel?.id ?: sbn.notification.channelId ?: return
        if (decision.action == Action.PASS) {
            val original = muter.originalImportance(sbn.packageName, channelId)
            if (original == null) {
                // A channel the app created after we silenced it.
                if (sbn.packageName in RuleManager.mutedPackages(app.ruleManager.rules.value)) publish(muter.mutePackage(sbn.packageName))
                return
            }
            val onlyOnce = sbn.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0
            if (!(isUpdate && onlyOnce)) app.alerter.alert(sbn, n, original)
        } else if (decision.rule?.let { it.debuzz && it.packageName.isNullOrEmpty() } == true) {
            publish(muter.learn(sbn.packageName, channelId))
        }
    }

    private fun publish(error: String?) {
        app.muteStatus.value = MuteStatus(mutedChannels = muter?.mutedCount ?: 0, error = error)
    }

    private fun remove(sbn: StatusBarNotification) {
        cancelNotification(sbn.key)
        handledGroups[sbn.groupKey] = Unit
        removeOrphanedSummary(sbn.groupKey, exceptKey = sbn.key)
    }

    /** Removes a group summary that is the only thing left in its group. */
    private fun removeOrphanedSummary(groupKey: String, exceptKey: String?) {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return
        val group = active.filter { it.groupKey == groupKey && it.key != exceptKey }
        if (group.isNotEmpty() && group.all(NotificationReader::isGroupSummary)) group.forEach { cancelNotification(it.key) }
    }

    private fun <V> lru(max: Int) = object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }
}
