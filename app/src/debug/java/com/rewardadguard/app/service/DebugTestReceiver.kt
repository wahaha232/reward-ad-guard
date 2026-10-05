package com.rewardadguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rewardadguard.app.BuildConfig
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.manager.MonitoringState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only command channel that lets an ADB-driven test drive the guard.
 *
 * Why this exists: rewarded ads can only be watched once per day, so the most
 * valuable device tests must not depend on one. This receiver lets a script feed
 * a *synthetic* foreground transition into the real [RewardAdAccessibilityService]
 * code path, so redirect, return and close-button behaviour can be exercised
 * on demand, as often as wanted, without a single ad.
 *
 * Safety properties, all deliberate:
 *  * **Debug builds only.** Every entry point returns immediately unless
 *    [BuildConfig.DEBUG], so a release build behaves exactly as if the class did
 *    not exist. The receiver is also declared only in the debug manifest.
 *  * **No new privileges.** It performs no action the user could not perform
 *    themselves; it only feeds the already-reachable event path.
 *  * **Explicitly signed in the log.** Anything routed through here is logged
 *    with the `RewardAdGuard` tag, so synthetic runs are easy to isolate.
 *
 * Supported commands (`adb shell am broadcast`):
 *  * `--es cmd simulate_foreground --es package <pkg>` - pretend `<pkg>` became
 *    the foreground app, exactly as a real window transition would.
 *  * `--es cmd set_reward_app --es package <pkg>` - add a package to the
 *    monitored list, so a test does not need a real reward app installed.
 *  * `--es cmd clear_reward_apps` - remove all monitored packages.
 *  * `--es cmd dump_state` - log a one-line summary of the current snapshot.
 *  * `--es cmd set_assist_action --es value <NAME>` - persist an
 *    [AssistAction] through the real [com.rewardadguard.app.manager.SettingsRepository].
 *    This exists because `assist_action = NONE` silently disables the entire
 *    close-button guard and is impossible to diagnose from the outside. It writes
 *    through production code rather than editing the DataStore protobuf by hand,
 *    so it also proves the settings write path works.
 *  * `--es cmd dump_settings` - log every settings field the guard reads.
 */
class DebugTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A release build silently ignores everything. This is the primary
        // safety gate; the manifest restriction is defence in depth.
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_TEST) return

        val command = intent.getStringExtra(EXTRA_COMMAND)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val value = intent.getStringExtra(EXTRA_VALUE)
        Log.i(TAG, "test command=$command package=$packageName value=$value")

        val app = context.applicationContext as? RewardAdGuardApp
        if (app == null) {
            Log.e(TAG, "application is not RewardAdGuardApp; ignoring")
            return
        }

        // A broadcast receiver is torn down as soon as onReceive returns, so the
        // suspend commands (they hit DataStore) run on a scope that outlives us.
        val pending = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pending.launch {
            try {
                when (command) {
                    CMD_SIMULATE_FOREGROUND -> simulateForeground(packageName)
                    CMD_SET_REWARD_APP -> setRewardApp(app, packageName)
                    CMD_CLEAR_REWARD_APPS -> clearRewardApps(app)
                    CMD_DUMP_STATE -> dumpState()
                    CMD_SET_ASSIST_ACTION -> setAssistAction(app, value)
                    CMD_DUMP_SETTINGS -> dumpSettings(app)
                    else -> Log.w(TAG, "unknown test command '$command'")
                }
            } catch (t: Throwable) {
                // A test hook must never take the app down with it.
                Log.e(TAG, "test command '$command' failed", t)
            }
        }
    }

    /**
     * Feeds a synthetic foreground transition into the service.
     *
     * The service builds a genuine [android.view.accessibility.AccessibilityEvent]
     * with the same type a real window change carries, then runs its normal
     * logic: session handling, redirect detection, return attempts and logging
     * all behave as in production. Only the trigger is fake.
     */
    private fun simulateForeground(packageName: String?) {
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "simulate_foreground needs --es package")
            return
        }
        val service = RewardAdAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "accessibility service is not connected; cannot simulate")
            return
        }
        service.simulateForeground(packageName)
        Log.i(TAG, "simulated foreground package=$packageName")
    }

    /** Adds a package to the monitored list so no real reward app is needed. */
    private fun setRewardApp(app: RewardAdGuardApp, packageName: String?) {
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "set_reward_app needs --es package")
            return
        }
        // enabled=true and mode BLOCK so the simulated session exercises the
        // active guard rather than the passive LOG_ONLY default.
        val result = app.rewardAppsRepository.add(
            packageName = packageName,
            label = packageName,
            enabled = true,
            mode = ProtectionMode.BLOCK
        )
        if (result == com.rewardadguard.app.manager.RewardAppsRepository.AddResult.ADDED) {
            Log.i(TAG, "reward app set: $packageName")
        } else {
            // A shell script asserting on this must see a failure, not a silent
            // no-op: set_reward_app("com.google.android.inputmethod.latin") used to
            // "succeed" and quietly configure a keyboard as a reward app.
            Log.e(TAG, "reward app refused: $packageName ($result)")
        }
    }

    private fun clearRewardApps(app: RewardAdGuardApp) {
        app.rewardAppsRepository.current().forEach { app.rewardAppsRepository.remove(it.packageName) }
        Log.i(TAG, "all reward apps cleared")
    }

    /** Writes the live snapshot to logcat so a script can assert on it. */
    private fun dumpState() {
        val snapshot = MonitoringState.snapshot.value
        Log.i(
            TAG,
            buildString {
                append("STATE")
                append(" serviceConnected=").append(snapshot.serviceConnected)
                append(" monitoringActive=").append(snapshot.monitoringActive)
                append(" sessionState=").append(snapshot.sessionState)
                append(" sourcePackage=").append(snapshot.sourcePackage)
                append(" currentForeground=").append(snapshot.currentForegroundPackage)
                append(" previousForeground=").append(snapshot.previousForegroundPackage)
                append(" adSessionActive=").append(snapshot.adSessionActive)
                append(" redirectRisk=").append(snapshot.redirectRisk)
            }
        )
    }

    /**
     * Persists an [AssistAction] through the production settings repository.
     *
     * `assist_action = NONE` is the single nastiest state this app can be in: the
     * guard keeps finding the close button, keeps declining to press it, and every
     * log line looks like an ordinary throttle. There is no UI affordance that
     * makes the difference obvious, so this command lets a test script put the
     * device into a known-good state and then prove the guard actually clicks.
     */
    private suspend fun setAssistAction(app: RewardAdGuardApp, value: String?) {
        if (value.isNullOrBlank()) {
            Log.w(TAG, "set_assist_action needs --es value <${AssistAction.entries.joinToString("|")}>")
            return
        }
        val action = AssistAction.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        if (action == null) {
            Log.e(TAG, "unknown assist action '$value'")
            return
        }
        app.settingsRepository.setAssistAction(action)

        // Read it back from disk. Trusting the write alone is exactly how the
        // original NONE trap hid for so long.
        val persisted = app.settingsRepository.settingsNow().assistAction
        if (persisted == action) {
            Log.i(TAG, "assist action set: $action")
        } else {
            Log.e(TAG, "assist action write did not stick: wanted $action but read back $persisted")
        }
    }

    /** Logs every setting the guard consults, so a script can diff the real state. */
    private suspend fun dumpSettings(app: RewardAdGuardApp) {
        val s = app.settingsRepository.settingsNow()
        Log.i(
            TAG,
            buildString {
                append("SETTINGS")
                append(" protectionMode=").append(s.protectionMode)
                append(" assistAction=").append(s.assistAction)
                append(" closeButtonAssistance=").append(s.closeButtonAssistance)
                append(" externalRedirectProtection=").append(s.externalRedirectProtection)
                append(" xClickAreaMultiplier=").append(s.xClickAreaMultiplier)
                append(" smartRedirectEnabled=").append(s.smartRedirectEnabled)
                append(" redirectPolicy=").append(s.redirectPolicy)
                append(" blockGraceMillis=").append(s.blockGraceMillis)
                append(" newAppSafeModeEnabled=").append(s.newAppSafeModeEnabled)
                append(" canBlock=").append(s.canBlock)
                append(" loggingEnabled=").append(s.loggingEnabled)
            }
        )
    }

    companion object {
        private const val TAG = "RewardAdGuard"

        /** Action the script uses: `am broadcast -a <ACTION_TEST>`. */
        const val ACTION_TEST = "com.rewardadguard.app.action.DEBUG_TEST"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_VALUE = "value"

        const val CMD_SIMULATE_FOREGROUND = "simulate_foreground"
        const val CMD_SET_REWARD_APP = "set_reward_app"
        const val CMD_CLEAR_REWARD_APPS = "clear_reward_apps"
        const val CMD_DUMP_STATE = "dump_state"
        const val CMD_SET_ASSIST_ACTION = "set_assist_action"
        const val CMD_DUMP_SETTINGS = "dump_settings"
    }
}
