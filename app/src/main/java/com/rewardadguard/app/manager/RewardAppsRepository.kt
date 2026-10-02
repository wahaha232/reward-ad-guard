package com.rewardadguard.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rewardadguard.app.data.ProtectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * UI-facing operations on the reward app list. Wraps [RewardAppStore] and
 * [AppManager] so that Compose screens stay free of Android plumbing.
 *
 * A newly added app always starts in LOG_ONLY when New App Safe Mode is on
 * (spec section 37/38) and is never auto-blocked.
 */
class RewardAppsRepository(
    private val context: Context,
    private val store: RewardAppStore,
    private val appManager: AppManager
) {

    fun current(): List<RewardAppInfo> = store.all().map { entry ->
        RewardAppInfo(
            packageName = entry.packageName,
            label = entry.label,
            enabled = entry.enabled,
            protectionMode = entry.mode,
            isSystemApp = appManager.isSystemApp(entry.packageName)
        )
    }.also { MonitoringState.updateRewardApps(it) }

    /**
     * Polling-free view of the reward app list.
     *
     * A SharedPreferences listener is registered instead of using
     * `OnSharedPreferenceChangeListener` on the store itself, so the flow emits
     * as soon as the UI adds or removes an app.
     */
    fun observeRewardApps(): Flow<List<RewardAppInfo>> = callbackFlow {
        trySend(current())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("reward_apps")) {
                trySend(current())
            }
        }
        store.registerListener(listener)
        awaitClose { store.unregisterListener(listener) }
    }.conflate()

    suspend fun candidates(search: String?): List<RewardAppInfo> = withContext(Dispatchers.IO) {
        try {
            val configured = store.all().associateBy { it.packageName }
            appManager.launcherApps()
                .filter { !appManager.isSystemInfrastructure(it.packageName) }
                .filter { info ->
                    search.isNullOrBlank() ||
                        info.label.contains(search, ignoreCase = true) ||
                        info.packageName.contains(search, ignoreCase = true)
                }
                .map { info ->
                    val existing = configured[info.packageName]
                    info.copy(
                        enabled = existing?.enabled ?: false,
                        protectionMode = existing?.mode,
                        label = existing?.label ?: info.label
                    )
                }
        } catch (t: Throwable) {
            Log.e(TAG, "candidates failed", t)
            emptyList()
        }
    }

    /** Adds [packageName] as a reward app (source). */
    fun add(
        packageName: String,
        label: String? = null,
        enabled: Boolean = true,
        mode: ProtectionMode? = ProtectionMode.LOG_ONLY
    ) {
        if (packageName.isBlank()) return
        if (packageName == context.packageName) {
            Log.w(TAG, "Refusing to add the guard itself as a reward app")
            return
        }
        store.put(
            packageName = packageName,
            label = label ?: appManager.appLabel(packageName),
            enabled = enabled,
            mode = mode
        )
        current()
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        store.setEnabled(packageName, enabled)
        current()
    }

    fun setMode(packageName: String, mode: ProtectionMode?) {
        store.setMode(packageName, mode)
        current()
    }

    fun remove(packageName: String) {
        store.remove(packageName)
        current()
    }

    fun setAllEnabled(enabled: Boolean) {
        store.all().forEach { store.setEnabled(it.packageName, enabled) }
        current()
    }

    fun dismissPendingNewApp() = MonitoringState.setPendingNewApp(null)

    /** Promotes a candidate detected at runtime (Safe Mode confirmation). */
    fun confirmPendingNewApp(mode: ProtectionMode) {
        val pending = MonitoringState.pendingNewApp.value ?: return
        add(pending.packageName, pending.label, enabled = true, mode = mode)
        MonitoringState.setPendingNewApp(null)
    }

    private companion object {
        const val TAG = "RewardAppsRepository"
    }
}
