package com.rewardadguard.app.manager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reward_ad_guard_settings")

/**
 * Single source of truth for settings (spec section 48).
 *
 * The accessibility service collects [settings] into memory so that the
 * event-driven hot path never performs disk or DataStore I/O.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROTECTION_MODE = stringPreferencesKey("protection_mode")
        val EXTERNAL_REDIRECT = booleanPreferencesKey("external_redirect_protection")
        val CLOSE_ASSISTANCE = booleanPreferencesKey("close_button_assistance")
        val X_MULTIPLIER = intPreferencesKey("x_click_area_multiplier")
        val SMART_REDIRECT = booleanPreferencesKey("smart_redirect")
        val REDIRECT_POLICY = stringPreferencesKey("redirect_policy")
        val BLOCK_GRACE_MILLIS = longPreferencesKey("block_grace_millis")
        val NEW_APP_SAFE_MODE = booleanPreferencesKey("new_app_safe_mode")
        val LOGGING = booleanPreferencesKey("logging_enabled")
        val MAX_LOG_EVENTS = intPreferencesKey("max_log_events")
        val MAX_SESSIONS = intPreferencesKey("max_sessions")
        val STATUS_NOTIFICATION = booleanPreferencesKey("status_notification")
        val TEST_MODE = booleanPreferencesKey("test_mode")
        val ASSIST_ACTION = stringPreferencesKey("assist_action")
        val AUTO_RETURN = booleanPreferencesKey("auto_return")
        val AUTO_LAUNCH_REWARD_APP = booleanPreferencesKey("auto_launch_reward_app")
        val VERIFY_RETURN = booleanPreferencesKey("verify_return")
        val MAX_RETURN_ATTEMPTS = intPreferencesKey("max_return_attempts")
    }

    /**
     * One-shot synchronous read used during service bootstrap.
     *
     * The accessibility service must know the current policy before the first
     * event arrives; this only touches the [kotlinx.coroutines.flow.first] value
     * once per service connection, never the event hot path.
     */
    suspend fun settingsNow(): AppSettings = settings.first()

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            protectionMode = ProtectionMode.fromName(prefs[Keys.PROTECTION_MODE] ?: defaults.protectionMode.name),
            externalRedirectProtection = prefs[Keys.EXTERNAL_REDIRECT] ?: defaults.externalRedirectProtection,
            closeButtonAssistance = prefs[Keys.CLOSE_ASSISTANCE] ?: defaults.closeButtonAssistance,
            xClickAreaMultiplier = prefs[Keys.X_MULTIPLIER] ?: defaults.xClickAreaMultiplier,
            smartRedirectEnabled = prefs[Keys.SMART_REDIRECT] ?: defaults.smartRedirectEnabled,
            redirectPolicy = RedirectPolicy.fromName(prefs[Keys.REDIRECT_POLICY] ?: defaults.redirectPolicy.name),
            blockGraceMillis = prefs[Keys.BLOCK_GRACE_MILLIS] ?: defaults.blockGraceMillis,
            newAppSafeModeEnabled = prefs[Keys.NEW_APP_SAFE_MODE] ?: defaults.newAppSafeModeEnabled,
            loggingEnabled = prefs[Keys.LOGGING] ?: defaults.loggingEnabled,
            maxLogEvents = prefs[Keys.MAX_LOG_EVENTS] ?: defaults.maxLogEvents,
            maxSessions = prefs[Keys.MAX_SESSIONS] ?: defaults.maxSessions,
            statusNotificationEnabled = prefs[Keys.STATUS_NOTIFICATION] ?: defaults.statusNotificationEnabled,
            testModeEnabled = prefs[Keys.TEST_MODE] ?: defaults.testModeEnabled,
            assistAction = AssistAction.fromName(prefs[Keys.ASSIST_ACTION] ?: defaults.assistAction.name),
            autoReturnEnabled = prefs[Keys.AUTO_RETURN] ?: defaults.autoReturnEnabled,
            autoLaunchRewardApp = prefs[Keys.AUTO_LAUNCH_REWARD_APP] ?: defaults.autoLaunchRewardApp,
            verifyReturn = prefs[Keys.VERIFY_RETURN] ?: defaults.verifyReturn,
            maxReturnAttempts = prefs[Keys.MAX_RETURN_ATTEMPTS] ?: defaults.maxReturnAttempts
        )
    }

    suspend fun setProtectionMode(mode: ProtectionMode) = edit { it[Keys.PROTECTION_MODE] = mode.name }

    suspend fun setExternalRedirectProtection(enabled: Boolean) =
        edit { it[Keys.EXTERNAL_REDIRECT] = enabled }

    suspend fun setCloseButtonAssistance(enabled: Boolean) =
        edit { it[Keys.CLOSE_ASSISTANCE] = enabled }

    suspend fun setXClickAreaMultiplier(multiplier: Int) =
        edit { it[Keys.X_MULTIPLIER] = multiplier.coerceIn(1, 5) }

    suspend fun setSmartRedirect(enabled: Boolean) = edit { it[Keys.SMART_REDIRECT] = enabled }

    suspend fun setRedirectPolicy(policy: RedirectPolicy) =
        edit { it[Keys.REDIRECT_POLICY] = policy.name }

    suspend fun setBlockGraceMillis(millis: Long) =
        edit { it[Keys.BLOCK_GRACE_MILLIS] = millis.coerceIn(0L, 5_000L) }

    suspend fun setNewAppSafeMode(enabled: Boolean) = edit { it[Keys.NEW_APP_SAFE_MODE] = enabled }

    suspend fun setLoggingEnabled(enabled: Boolean) = edit { it[Keys.LOGGING] = enabled }

    suspend fun setMaxLogEvents(max: Int) = edit { it[Keys.MAX_LOG_EVENTS] = max.coerceIn(500, 200_000) }

    suspend fun setMaxSessions(max: Int) = edit { it[Keys.MAX_SESSIONS] = max.coerceIn(20, 20_000) }

    suspend fun setStatusNotification(enabled: Boolean) =
        edit { it[Keys.STATUS_NOTIFICATION] = enabled }

    suspend fun setTestMode(enabled: Boolean) = edit { it[Keys.TEST_MODE] = enabled }

    suspend fun setAssistAction(action: AssistAction) = edit { it[Keys.ASSIST_ACTION] = action.name }

    suspend fun setAutoReturn(enabled: Boolean) = edit { it[Keys.AUTO_RETURN] = enabled }

    suspend fun setAutoLaunchRewardApp(enabled: Boolean) =
        edit { it[Keys.AUTO_LAUNCH_REWARD_APP] = enabled }

    suspend fun setVerifyReturn(enabled: Boolean) = edit { it[Keys.VERIFY_RETURN] = enabled }

    /** Clamped to 1..3 Back presses, the range the return controller can act on. */
    suspend fun setMaxReturnAttempts(attempts: Int) =
        edit { it[Keys.MAX_RETURN_ATTEMPTS] = attempts.coerceIn(1, 3) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { prefs ->
            try {
                block(prefs)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Failed to persist settings", t)
                throw t
            }
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
