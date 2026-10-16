package com.notiregx.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.LruCache
import com.notiregx.app.data.Storage
import com.notiregx.app.model.Delivery
import com.notiregx.app.model.Notification
import com.notiregx.app.model.RuleManager
import com.notiregx.app.model.VibrationLog
import com.notiregx.app.service.Alerter
import com.notiregx.app.service.BundleNotifier
import com.notiregx.app.service.DigestReceiver
import com.notiregx.app.service.NotificationReader
import com.notiregx.app.service.SystemVibrationReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotiRegXApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var ruleManager: RuleManager
        private set
    lateinit var reader: NotificationReader
        private set
    lateinit var vibrationLog: VibrationLog
        private set
    lateinit var systemVibrations: SystemVibrationReader
        private set
    lateinit var alerter: Alerter
        private set
    private lateinit var storage: Storage
    private lateinit var bundleNotifier: BundleNotifier

    /**
     * Click actions of captured notifications, by notification id. PendingIntents can't be
     * written to disk, so these only live as long as the process.
     */
    val contentIntents = LruCache<Long, PendingIntent>(500)

    /** Whether a companion device is linked, which is what allows silencing other apps' channels. */
    val companionLinked = MutableStateFlow(false)

    /** Result of the last channel-silencing attempt, published by the listener service. */
    val muteStatus = MutableStateFlow(MuteStatus())

    /** Apps whose notification vibration is turned off, published by the listener service. */
    val vibrationMuted = MutableStateFlow<Set<String>>(emptySet())

    /** Mute (true) / unmute (false) requests for an app's notification vibration, served by the listener. */
    val vibrationMuteRequests = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)

    /** Errors from the last vibration mute request, for the UI to show. */
    val vibrationMuteError = MutableStateFlow<String?>(null)

    /** The last raw system vibration dump, for sending along when parsing goes wrong. */
    @Volatile
    var lastVibrationDump: String? = null
        private set

    private val scope = MainScope()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        storage = Storage(filesDir)
        ruleManager = RuleManager(storage.loadRules(), storage.loadRecent())
        vibrationLog = VibrationLog(storage.loadVibrations())
        reader = NotificationReader(this)
        systemVibrations = SystemVibrationReader(this, reader)
        bundleNotifier = BundleNotifier(this)
        alerter = Alerter(this)
        companionLinked.value = settings.isCompanionLinked()

        scope.launch { ruleManager.rules.drop(1).collect { storage.saveRules(it) } }
        scope.launch { ruleManager.recent.drop(1).debounce(SAVE_DELAY_MS).collect { storage.saveRecent(it) } }
        scope.launch { vibrationLog.events.drop(1).debounce(SAVE_DELAY_MS).collect { storage.saveVibrations(it) } }
        scope.launch { ruleManager.rules.collect { bundleNotifier.sync(it) } }
        scope.launch { settings.maxSaved.collect { ruleManager.trimHistories(it) } }
        scope.launch {
            while (isActive) {
                refreshSystemVibrations()
                delay(VIBRATION_POLL_MS)
            }
        }
        scope.launch {
            // Catch up on deliveries missed while the app wasn't running, then keep the alarm set.
            deliverDueDigests()
            ruleManager.rules
                .map { rules -> rules.filter { it.isDigest }.flatMap { it.deliveryTimes }.toSet() }
                .distinctUntilChanged()
                .collect { scheduleNextDigest(it) }
        }
    }

    /** Pulls Android's own vibration history into the log, if the DUMP permission was granted. */
    suspend fun refreshSystemVibrations() {
        val events = withContext(Dispatchers.IO) {
            val dump = systemVibrations.dump() ?: return@withContext null
            lastVibrationDump = dump
            systemVibrations.parse(dump)
        } ?: return
        vibrationLog.add(events)
    }

    /** Releases digest bundles whose delivery time passed since the last check, and re-arms the alarm. */
    fun deliverDueDigests() {
        val prefs = getSharedPreferences("digests", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, now)
        val rules = ruleManager.rules.value
        val due = rules.filter { it.isDigest && Delivery.dueBetween(it.deliveryTimes, last, now) }.mapTo(HashSet()) { it.id }
        if (due.isNotEmpty()) ruleManager.release(due)
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        scheduleNextDigest(rules.filter { it.isDigest }.flatMap { it.deliveryTimes }.toSet())
    }

    private fun scheduleNextDigest(times: Set<Int>) {
        val alarms = getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            this, 0, Intent(this, DigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = Delivery.nextAfter(times, System.currentTimeMillis())
        // Inexact, so no special alarm permission is needed; digests may arrive a few minutes late.
        if (next == null) alarms.cancel(pending) else alarms.setWindow(AlarmManager.RTC_WAKEUP, next, DIGEST_WINDOW_MS, pending)
    }

    fun exportRules(): String = storage.exportRules(ruleManager.rules.value)

    /** Returns how many rules were added; throws if [json] isn't an export. */
    fun importRules(json: String): Int = ruleManager.import(storage.importRules(json))

    fun restore(n: Notification) = alerter.restore(n, contentIntents.get(n.id))

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val DIGEST_WINDOW_MS = 5 * 60_000L
        const val SAVE_DELAY_MS = 2_000L

        /** Android keeps only a few dozen recent vibrations, so check often enough not to miss any. */
        const val VIBRATION_POLL_MS = 2 * 60_000L
    }
}

data class MuteStatus(val mutedChannels: Int = 0, val error: String? = null)
