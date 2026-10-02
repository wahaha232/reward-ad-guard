package com.rewardadguard.app.manager

import android.content.Context
import android.util.Log
import com.rewardadguard.app.data.ProtectionMode

/**
 * Durable "which apps are reward apps" store.
 *
 * Why SharedPreferences instead of DataStore: the accessibility service must be
 * able to decide synchronously inside `onAccessibilityEvent` whether the
 * foreground package is monitored. A blocking first read of a small XML file is
 * done exactly once at service creation and cached in memory afterwards, so the
 * event path itself never performs I/O.
 *
 * Format: one CSV line per app
 *     packageName,enabled,modeName,label
 * Labels are stored only for display convenience and are not required to
 * resolve a monitored app.
 */
class RewardAppStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory cache, read by the accessibility service on the hot path. */
    @Volatile
    private var cache: Map<String, RewardAppEntry> = load()

    data class RewardAppEntry(
        val packageName: String,
        val enabled: Boolean,
        val mode: ProtectionMode?,
        val label: String
    ) {
        fun serialize(): String = "$packageName,$enabled,${mode?.name ?: ""},${label.replace(',', ' ')}"
    }

    fun all(): List<RewardAppEntry> = cache.values.sortedBy { it.label.lowercase() }

    /**
     * Re-reads the shared preferences file.
     *
     * Called once when the accessibility service connects and whenever another
     * process writes, so the hot path can keep using the in-memory [cache].
     */
    @Synchronized
    fun refresh() {
        cache = load()
    }

    fun isMonitored(packageName: String?): Boolean {
        if (packageName == null) return false
        val entry = cache[packageName] ?: return false
        return entry.enabled
    }

    /** Per app override, falls back to null => use the global protection mode. */
    fun modeOverride(packageName: String?): ProtectionMode? {
        if (packageName == null) return null
        return cache[packageName]?.mode
    }

    fun contains(packageName: String): Boolean = cache.containsKey(packageName)

    /**
     * True when the user explicitly removed / never trusted [packageName]
     * (present in the store but disabled). Used by [AllowlistGuard] as an extra
     * "do not touch this app" signal.
     */
    fun isIgnored(packageName: String?): Boolean {
        if (packageName == null) return false
        val entry = cache[packageName] ?: return false
        return !entry.enabled
    }

    var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Registers a preferences listener. Caller owns unregistering. */
    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(l)
        if (listener === l) listener = null
    }

    @Synchronized
    fun put(packageName: String, label: String, enabled: Boolean, mode: ProtectionMode?) {
        val updated = cache.toMutableMap()
        val existing = updated[packageName]
        updated[packageName] = RewardAppEntry(
            packageName = packageName,
            enabled = enabled,
            mode = mode ?: existing?.mode,
            label = label.ifBlank { existing?.label ?: packageName }
        )
        persist(updated)
    }

    @Synchronized
    fun setEnabled(packageName: String, enabled: Boolean) {
        val existing = cache[packageName] ?: return
        put(packageName, existing.label, enabled, existing.mode)
    }

    @Synchronized
    fun setMode(packageName: String, mode: ProtectionMode?) {
        val existing = cache[packageName] ?: return
        put(packageName, existing.label, existing.enabled, mode)
    }

    @Synchronized
    fun remove(packageName: String) {
        val updated = cache.toMutableMap()
        updated.remove(packageName)
        persist(updated)
    }

    @Synchronized
    fun replaceAll(entries: List<RewardAppEntry>) {
        persist(entries.associateBy { it.packageName })
    }

    @Synchronized
    fun reload() {
        cache = load()
    }

    private fun persist(updated: Map<String, RewardAppEntry>) {
        cache = updated
        try {
            prefs.edit()
                .putStringSet(KEY_APPS, updated.values.map { it.serialize() }.toSet())
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist reward app list", t)
        }
    }

    private fun load(): Map<String, RewardAppEntry> = try {
        val raw = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty()
        raw.mapNotNull { line ->
            val parts = line.split(',')
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            val pkg = parts[0]
            val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
            val mode = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ProtectionMode.fromName(it) }
            val label = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: pkg
            pkg to RewardAppEntry(pkg, enabled, mode, label)
        }.toMap()
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load reward app list", t)
        emptyMap()
    }

    companion object {
        private const val TAG = "RewardAppStore"
        private const val PREFS_NAME = "reward_app_store"
        private const val KEY_APPS = "reward_apps_v1"
    }
}
