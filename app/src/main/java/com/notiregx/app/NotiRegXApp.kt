package com.notiregx.app

import android.app.Application
import android.app.PendingIntent
import android.util.LruCache
import com.notiregx.app.data.Storage
import com.notiregx.app.model.RuleManager
import com.notiregx.app.model.VibrationLog
import com.notiregx.app.service.BundleNotifier
import com.notiregx.app.service.NotificationReader
import com.notiregx.app.service.SystemVibrationReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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

    private companion object {
        const val SAVE_DELAY_MS = 2_000L

        /** Android keeps only a few dozen recent vibrations, so check often enough not to miss any. */
        const val VIBRATION_POLL_MS = 2 * 60_000L
    }
}

data class MuteStatus(val mutedChannels: Int = 0, val error: String? = null)
