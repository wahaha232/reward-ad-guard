package com.rewardadguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rewardadguard.app.BuildConfig
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.manager.MonitoringState

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
 */
class DebugTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A release build silently ignores everything. This is the primary
        // safety gate; the manifest restriction is defence in depth.
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_TEST) return

        val command = intent.getStringExtra(EXTRA_COMMAND)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        Log.i(TAG, "test command=$command package=$packageName")

        val app = context.applicationContext as? RewardAdGuardApp
        if (app == null) {
            Log.e(TAG, "application is not RewardAdGuardApp; ignoring")
            return
        }

        try {
            when (command) {
                CMD_SIMULATE_FOREGROUND -> simulateForeground(packageName)
                CMD_SET_REWARD_APP -> setRewardApp(app, packageName)
                CMD_CLEAR_REWARD_APPS -> clearRewardApps(app)
                CMD_DUMP_STATE -> dumpState()
                else -> Log.w(TAG, "unknown test command '$command'")
            }
        } catch (t: Throwable) {
            // A test hook must never take the app down with it.
            Log.e(TAG, "test command '$command' failed", t)
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
        app.rewardAppsRepository.add(
            packageName = packageName,
            label = packageName,
            enabled = true,
            mode = ProtectionMode.BLOCK
        )
        Log.i(TAG, "reward app set: $packageName")
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

    companion object {
        private const val TAG = "RewardAdGuard"

        /** Action the script uses: `am broadcast -a <ACTION_TEST>`. */
        const val ACTION_TEST = "com.rewardadguard.app.action.DEBUG_TEST"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_PACKAGE = "package"

        const val CMD_SIMULATE_FOREGROUND = "simulate_foreground"
        const val CMD_SET_REWARD_APP = "set_reward_app"
        const val CMD_CLEAR_REWARD_APPS = "clear_reward_apps"
        const val CMD_DUMP_STATE = "dump_state"
    }
}
