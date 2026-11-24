package com.notiregx.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.notiregx.app.NotiRegXApp
import com.notiregx.app.R
import com.notiregx.app.model.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateState(
    val lastChecked: Long = 0,
    val latest: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Looks for a newer release on GitHub, downloads its APK and installs it over this app with
 * PackageInstaller. Android refuses an APK signed with a different key, so only our own builds go in.
 */
class Updater(private val app: NotiRegXApp) {
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _state = MutableStateFlow(UpdateState(lastChecked = prefs.getLong(KEY_LAST_CHECKED, 0)))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun currentVersion(): String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"

    /** [force] is the "Check now" button: ignores the switch, the 12-hour wait and metered networks. */
    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            if (!app.settings.autoUpdate.value || !UpdatePolicy.shouldCheck(_state.value.lastChecked, now)) return
            if (isMetered()) return
        }
        if (!lock.tryLock()) return
        try {
            _state.value = _state.value.copy(busy = true, error = null)
            val error = withContext(Dispatchers.IO) { run() }
            prefs.edit().putLong(KEY_LAST_CHECKED, now).apply()
            _state.value = _state.value.copy(busy = false, lastChecked = now, error = error)
        } finally {
            lock.unlock()
        }
    }

    /** Whether the network costs money; if Android won't say, assume it does and wait. */
    private fun isMetered(): Boolean = try {
        app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    } catch (e: SecurityException) {
        true
    }

    /**
     * Returns an error text, or null if all went well (including "already up to date"). Never
     * throws: a failed update must not take the app (and its notification filtering) down with it.
     */
    private fun run(): String? = try {
        val release = UpdatePolicy.parseRelease(fetch(UpdatePolicy.RELEASES_URL).bufferedReader().use { it.readText() })
        if (release == null) null
        else {
            _state.value = _state.value.copy(latest = release.version)
            if (!UpdatePolicy.isNewer(release.version, currentVersion())) null
            else if (!app.packageManager.canRequestPackageInstalls()) app.getString(R.string.update_needs_permission)
            else {
                val file = File(app.cacheDir, "update.apk")
                fetch(release.apkUrl).use { input -> file.outputStream().use { input.copyTo(it) } }
                install(file)
                null
            }
        }
    } catch (e: IOException) {
        e.message ?: "network error"
    } catch (e: JSONException) {
        e.message ?: "bad answer"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun fetch(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "NotiRegX")
        setRequestProperty("Accept", "application/vnd.github+json")
        if (responseCode != 200) throw IOException("HTTP $responseCode")
        inputStream
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("NotiRegX.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                app, 3, Intent(app, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    internal fun fail(message: String?) {
        _state.value = _state.value.copy(error = message ?: "install failed")
    }

    /** Android wants a tap on Install (the first time, or on versions that always ask). */
    internal fun notifyTapToInstall(confirm: Intent) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            app, 5, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.update_ready_title))
                .setContentText(app.getString(R.string.update_ready_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val KEY_LAST_CHECKED = "last_checked"
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 4243
    }
}

/** Receives the installer's progress for an update started by [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as NotiRegXApp).updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let(updater::notifyTapToInstall)
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> updater.fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
