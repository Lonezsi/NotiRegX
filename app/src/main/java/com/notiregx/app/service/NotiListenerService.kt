package com.notiregx.app.service

import android.app.NotificationManager
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notiregx.app.MuteStatus
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.model.Action
import com.notiregx.app.model.Decision
import com.notiregx.app.model.Notification
import com.notiregx.app.model.RuleManager
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

    private var scope: CoroutineScope? = null
    private var muter: ChannelMuter? = null

    private val muting get() = muter != null && app.companionLinked.value

    override fun onListenerConnected() {
        super.onListenerConnected()
        val muter = ChannelMuter(this, this).also { muter = it }
        scope = MainScope().apply {
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
        scope?.cancel()
        scope = null
        muter = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName == packageName) return
        val ranking = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        val isUpdate = sbn.key in seenKeys
        seenKeys[sbn.key] = Unit
        logLikelyVibration(sbn, ranking, isUpdate)

        if (!app.reader.isFilterable(sbn)) return
        if (NotificationReader.isGroupSummary(sbn)) {
            if (sbn.groupKey in handledGroups) removeOrphanedSummary(sbn.groupKey, exceptKey = null)
            return
        }

        val n = app.reader.read(sbn, app.ruleManager.newNotificationId()) ?: return
        val signature = n.content.hashCode()
        val previous = lastSeen[sbn.key]
        if (previous != null && previous.first == signature) {
            if (previous.second != Action.PASS) remove(sbn)
            return
        }

        sbn.notification.contentIntent?.let { app.contentIntents.put(n.id, it) }
        val decision = app.ruleManager.process(n, app.settings.maxSaved.value, app.settings.filteringEnabled.value)
        lastSeen[sbn.key] = signature to decision.action
        if (decision.action != Action.PASS) remove(sbn)
        if (muting) handleDebuzz(sbn, ranking, n, decision, isUpdate = previous != null)
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
