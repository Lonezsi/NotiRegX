package com.notiregx.app

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Global preferences plus the permission checks and system screens they depend on. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _maxSaved = MutableStateFlow(prefs.getInt(KEY_MAX_SAVED, DEFAULT_MAX_SAVED))
    val maxSaved: StateFlow<Int> = _maxSaved.asStateFlow()

    private val _filteringEnabled = MutableStateFlow(prefs.getBoolean(KEY_FILTERING, true))
    val filteringEnabled: StateFlow<Boolean> = _filteringEnabled.asStateFlow()

    fun setMaxSaved(value: Int) {
        val clamped = value.coerceIn(MIN_MAX_SAVED, MAX_MAX_SAVED)
        prefs.edit().putInt(KEY_MAX_SAVED, clamped).apply()
        _maxSaved.value = clamped
    }

    fun setFilteringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILTERING, enabled).apply()
        _filteringEnabled.value = enabled
    }

    fun hasNotificationAccess(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun canPostNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun supportsCompanionDevices(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    @Suppress("DEPRECATION")
    fun isCompanionLinked(): Boolean =
        supportsCompanionDevices() &&
            context.getSystemService(CompanionDeviceManager::class.java).associations.isNotEmpty()

    fun notificationAccessIntent() = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun appNotificationSettingsIntent() = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)

    @Suppress("BatteryLife")
    fun batteryOptimizationIntent() =
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent() =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    companion object {
        const val MIN_MAX_SAVED = 10
        const val MAX_MAX_SAVED = 1000
        private const val DEFAULT_MAX_SAVED = 100
        private const val KEY_MAX_SAVED = "max_saved_per_rule"
        private const val KEY_FILTERING = "filtering_enabled"
    }
}
