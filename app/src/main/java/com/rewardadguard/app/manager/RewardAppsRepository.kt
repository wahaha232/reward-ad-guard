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
 * Pure filter used by the "installed apps" search box.
 *
 * Extracted from [RewardAppsRepository.candidates] so the matching rule can be
 * unit tested without a `PackageManager`.
 *
 * A blank query matches everything, so the initial state always shows the full
 * installed list; otherwise the query is matched against both the visible label
 * and the package name, case-insensitively.
 */
internal fun matchesCandidateQuery(
    label: String,
    packageName: String,
    query: String?
): Boolean {
    val trimmed = query?.trim()
    if (trimmed.isNullOrEmpty()) return true
    return label.contains(trimmed, ignoreCase = true) ||
        packageName.contains(trimmed, ignoreCase = true)
}

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
                    matchesCandidateQuery(info.label, info.packageName, search)
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

    /** Outcome of an [add] attempt, so the UI can explain a refusal. */
    enum class AddResult {
        /** The app was added. */
        ADDED,

        /** The package name was blank. */
        REJECTED_BLANK,

        /** The user tried to add the guard itself. */
        REJECTED_SELF,

        /** The package is a keyboard, launcher, SystemUI or other infrastructure. */
        REJECTED_INFRASTRUCTURE
    }

    /**
     * Adds [packageName] as a reward app (source).
     *
     * The blanket infrastructure check is the important part. The picker already
     * hid these apps, but [add] is also reachable from the runtime
     * "new app detected" prompt and from debug entry points, so validating only
     * in the UI left a hole. A real device had Gboard - an **input method** -
     * configured as a reward app with mode LOG_ONLY, which is meaningless and
     * quietly poisoned the app list.
     *
     * @return why the call succeeded or was refused.
     */
    fun add(
        packageName: String,
        label: String? = null,
        enabled: Boolean = true,
        mode: ProtectionMode? = ProtectionMode.LOG_ONLY
    ): AddResult {
        if (packageName.isBlank()) return AddResult.REJECTED_BLANK
        if (packageName == context.packageName) {
            Log.w(TAG, "Refusing to add the guard itself as a reward app")
            return AddResult.REJECTED_SELF
        }
        if (appManager.isSystemInfrastructure(packageName)) {
            Log.w(TAG, "Refusing to add infrastructure package as a reward app: $packageName")
            return AddResult.REJECTED_INFRASTRUCTURE
        }
        store.put(
            packageName = packageName,
            label = label ?: appManager.appLabel(packageName),
            enabled = enabled,
            mode = mode
        )
        current()
        return AddResult.ADDED
    }

    /**
     * Removes apps from the store that can no longer legitimately be reward apps.
     *
     * Called on startup so that a list polluted by an older build (before the
     * infrastructure check existed in [add]) heals itself instead of asking the
     * user to find and delete a keyboard entry by hand.
     *
     * @return the package names that were removed.
     */
    fun purgeInvalidApps(): List<String> {
        val invalid = store.all()
            .map { it.packageName }
            .filter { it.isBlank() || appManager.isSystemInfrastructure(it) }
        if (invalid.isEmpty()) return emptyList()
        invalid.forEach {
            Log.w(TAG, "Purging invalid reward app entry: $it")
            store.remove(it)
        }
        current()
        return invalid
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

    /**
     * Promotes a candidate detected at runtime (Safe Mode confirmation).
     *
     * Runs the same infrastructure check as [add]; an unknown foreground package
     * can be SystemUI or a keyboard, so this path must not bypass validation.
     */
    fun confirmPendingNewApp(mode: ProtectionMode) {
        val pending = MonitoringState.pendingNewApp.value ?: return
        add(pending.packageName, pending.label, enabled = true, mode = mode)
        MonitoringState.setPendingNewApp(null)
    }

    private companion object {
        const val TAG = "RewardAppsRepository"
    }
}
