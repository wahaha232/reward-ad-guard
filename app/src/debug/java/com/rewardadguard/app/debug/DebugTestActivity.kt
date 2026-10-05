package com.rewardadguard.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.rewardadguard.app.BuildConfig
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.manager.MonitoringState
import com.rewardadguard.app.manager.RewardAppsRepository.AddResult
import com.rewardadguard.app.service.RewardAdAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only entry point for scripted tests, started with `am start`.
 *
 * ### Why this exists
 *
 * `DebugTestReceiver` cannot be driven from `adb shell` on modern Android.
 * Measured on the device (Android 16 / HyperOS V816):
 *
 * * `am broadcast` reaches `ActivityManager` (`Broadcasting:` in logcat) but the
 *   target process is **never started** (`ps -A` stays empty, no `FATAL`), because
 *   a broadcast from the shell cannot force-start a stopped app's process.
 * * `Broadcast completed: result=0` therefore means "nothing ran", not "success".
 *   Making the receiver `exported="true"` does **not** help; neither does adding a
 *   `signature` permission (it only adds a second, independent way to fail, since
 *   `com.android.shell` does not hold an app-private signature permission).
 *
 * An explicit **Activity** started by `am start -n` is guaranteed to work, which is
 * the fallback the original debug manifest comment pointed at. This class is that
 * entry point; `DebugTestReceiver` is kept for in-process use.
 *
 * ### Safety
 *
 * Debug variant only (`src/debug`), declared `exported="false"` in the debug
 * manifest, and every command re-checks [BuildConfig.DEBUG]. It cannot be reached
 * from another app, and it does not exist in a release build.
 *
 * ### Usage
 *
 * ```text
 * adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity \
 *     -f 0x10008000 --es cmd set_assist_action --es value ASSIST_WHEN_IDLE
 * adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity \
 *     -f 0x10008000 --es cmd dump_settings
 * ```
 *
 * > **Never add `-S`.** `-S` force-stops the app, which unbinds the accessibility
 * > service and makes a working build look broken — see [onNewIntent] and
 * > `ANALYSIS_REPORT.md` §3. `-f 0x10008000` is `FLAG_ACTIVITY_NEW_TASK |
 * > FLAG_ACTIVITY_CLEAR_TASK`; combined with [onNewIntent] it re-runs the command
 * > without killing the process.
 *
 * Output goes to **two** places on purpose:
 *
 * 1. logcat, under `RewardAdGuardDebug` (a unique tag, so `adb logcat -s` is
 *    unambiguous — the production service logs under `RewardAdGuardService` and the
 *    old receiver under `RewardAdGuard`, which is easy to get wrong);
 * 2. the activity's own `TextView`, for when logcat is unavailable.
 */
class DebugTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCommand()

        // NOTE: onNewIntent() below re-runs the same dispatch when a second
        // `am start` targets this already-running Activity. That pairing is what
        // lets the script omit `-S` (see the onNewIntent doc comment).
    }

    /**
     * Re-runs the test command when an `am start` lands on this already-live
     * Activity.
     *
     * ### Why this method exists (do not delete it)
     *
     * `TOOLS/auto_test.ps1` used to pass `-S` to every `am start`, because without
     * it the second command reaches the existing instance and `onCreate` never runs
     * again — the Activity just sits there and logcat replays the previous output.
     *
     * But `-S` means **force-stop the app first**, and force-stopping unbinds the
     * accessibility service (`AccessibilityManagerService.onHandleForceStop`
     * strips it from `enabled_accessibility_services`). The test tool was therefore
     * killing the very service it was trying to measure, producing a convincing
     * but false "this ROM never binds the service" conclusion.
     *
     * Implementing `onNewIntent()` fixes the original problem at its source: the
     * script drops `-S` (it now uses `-f 0x10008000`, NEW_TASK | CLEAR_TASK) and
     * each command is still executed, while the process — and therefore the bound
     * accessibility service — stays alive.
     *
     * `setIntent()` is required: without it `getStringExtra()` inside
     * [runCommand] would return the *first* intent's extras forever.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommand()
    }

    /**
     * Dispatches whatever command the current intent carries.
     *
     * Split out of [onCreate] so both the first launch and every subsequent
     * `am start` (via [onNewIntent]) go through exactly the same path.
     */
    private fun handleCommand() {
        // Defence in depth: the debug manifest plus this check means a release
        // build can never expose these hooks even if the manifest is mis-merged.
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "not a debug build; refusing to run test command")
            finish()
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)
        val app = applicationContext as? RewardAdGuardApp
        if (app == null) {
            report("FAILED: application is not RewardAdGuardApp")
            return
        }

        Log.i(TAG, "test command=$command package=${intent.getStringExtra(EXTRA_PACKAGE)}")
        scope.launch {
            val outcome = try {
                runCommand(app, command)
            } catch (t: Throwable) {
                // A test hook must never take the app down with it.
                Log.e(TAG, "test command '$command' failed", t)
                "FAILED: ${t::class.java.simpleName}: ${t.message}"
            }
            withContext(Dispatchers.Main) { report(outcome) }
        }
    }

    /** Publishes the outcome to logcat and the window. */
    private fun report(outcome: String) {
        Log.i(TAG, "RESULT $outcome")
        setResult(if (outcome.startsWith("OK")) RESULT_OK else RESULT_CANCELED)
        setContentView(TextView(this).apply { text = outcome })
    }

    /** Executes one command and returns a `OK:` / `FAILED:` summary. */
    private suspend fun runCommand(app: RewardAdGuardApp, command: String?): String =
        when (command) {
            CMD_DUMP_SETTINGS -> dumpSettings(app)
            CMD_SET_ASSIST_ACTION -> setAssistAction(app)
            CMD_SET_REWARD_APP -> setRewardApp(app)
            CMD_CLEAR_REWARD_APPS -> clearRewardApps(app)
            CMD_SIMULATE_FOREGROUND -> simulateForeground()
            CMD_DUMP_STATE -> dumpState()
            else -> "FAILED: unknown test command '$command'"
        }

    /**
     * Feeds a synthetic foreground transition into the real guard.
     *
     * This is the **only** reason the debug harness exists: it lets the redirect,
     * return and close-button paths be exercised on demand, as often as wanted,
     * without spending the once-per-day rewarded ad.
     *
     * It delegates to [RewardAdAccessibilityService.simulateForeground], which
     * builds a genuine `AccessibilityEvent` and hands it to the production
     * `handleWindowEvent`. Only the trigger is synthetic; the code under test is
     * exactly the shipped code.
     *
     * Reachability caveat, measured on HyperOS (see known-issues.md): some ROMs
     * never *bind* third-party accessibility services even though they list them
     * as enabled. When that happens `instance` is null and `onServiceConnected`
     * has never run, so there is no live object to drive. That case is reported
     * explicitly here instead of silently reporting success.
     */
    private fun simulateForeground(): String {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            return "FAILED: simulate_foreground needs --es package"
        }
        val service = RewardAdAccessibilityService.instance
            ?: return "FAILED: accessibility service is not bound, so no synthetic " +
                "transition can be delivered. Check dump_state (serviceConnected) and " +
                "whether this ROM actually binds the service."
        service.simulateForeground(packageName)
        // Report the resulting snapshot, so a script can assert on the effect and
        // not merely on the absence of an error.
        val s = MonitoringState.snapshot.value
        return "OK: simulated foreground=$packageName " +
            "sessionState=${s.sessionState} sourcePackage=${s.sourcePackage} " +
            "currentForeground=${s.currentForegroundPackage} " +
            "previousForeground=${s.previousForegroundPackage} " +
            "adSessionActive=${s.adSessionActive} redirectRisk=${s.redirectRisk}"
    }

    /**
     * Persists an [AssistAction] through the production settings repository.
     *
     * `assist_action = NONE` is the nastiest state this app can be in: the guard
     * keeps finding the close button, keeps declining to press it, and every log
     * line looks like an ordinary throttle. This command puts the device into a
     * known-good state and reads it back, so a script can prove the write landed.
     */
    private suspend fun setAssistAction(app: RewardAdGuardApp): String {
        val value = intent.getStringExtra(EXTRA_VALUE)
        if (value.isNullOrBlank()) {
            return "FAILED: set_assist_action needs --es value <${AssistAction.entries.joinToString("|")}>"
        }
        val action = AssistAction.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: return "FAILED: unknown assist action '$value'"

        app.settingsRepository.setAssistAction(action)
        // Read back from disk; trusting the write alone is how NONE hid for so long.
        val persisted = app.settingsRepository.settingsNow().assistAction
        return if (persisted == action) {
            "OK: assistAction=$persisted"
        } else {
            "FAILED: wanted $action but read back $persisted"
        }
    }

    /** Adds a package to the monitored list so no real reward app is needed. */
    private suspend fun setRewardApp(app: RewardAdGuardApp): String {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            return "FAILED: set_reward_app needs --es package"
        }
        // enabled=true and BLOCK, so a simulated session exercises the active
        // guard rather than the passive LOG_ONLY default.
        val result = app.rewardAppsRepository.add(
            packageName = packageName,
            label = packageName,
            enabled = true,
            mode = ProtectionMode.BLOCK
        )
        return if (result == AddResult.ADDED) {
            "OK: reward app set $packageName"
        } else {
            // A refused add must be visible: set_reward_app(com.google.android.inputmethod.latin)
            // used to "succeed" and quietly configure a keyboard as a reward app.
            "FAILED: reward app refused $packageName ($result)"
        }
    }

    private suspend fun clearRewardApps(app: RewardAdGuardApp): String {
        val current = app.rewardAppsRepository.current()
        current.forEach { app.rewardAppsRepository.remove(it.packageName) }
        return "OK: cleared ${current.size} reward app(s)"
    }

    /** Reports the live snapshot, so a script can assert on it. */
    private fun dumpState(): String {
        val s = MonitoringState.snapshot.value
        return "OK: STATE serviceConnected=${s.serviceConnected} " +
            "monitoringActive=${s.monitoringActive} sessionState=${s.sessionState} " +
            "sourcePackage=${s.sourcePackage} currentForeground=${s.currentForegroundPackage} " +
            "previousForeground=${s.previousForegroundPackage} " +
            "adSessionActive=${s.adSessionActive} redirectRisk=${s.redirectRisk}"
    }

    /** Logs every setting the guard consults, so a script can diff the real state. */
    private suspend fun dumpSettings(app: RewardAdGuardApp): String {
        val s = app.settingsRepository.settingsNow()
        return "OK: SETTINGS protectionMode=${s.protectionMode} assistAction=${s.assistAction} " +
            "closeButtonAssistance=${s.closeButtonAssistance} " +
            "externalRedirectProtection=${s.externalRedirectProtection} " +
            "xClickAreaMultiplier=${s.xClickAreaMultiplier} " +
            "smartRedirectEnabled=${s.smartRedirectEnabled} redirectPolicy=${s.redirectPolicy} " +
            "blockGraceMillis=${s.blockGraceMillis} " +
            "newAppSafeModeEnabled=${s.newAppSafeModeEnabled} canBlock=${s.canBlock} " +
            "loggingEnabled=${s.loggingEnabled}"
    }

    companion object {
        /**
         * A dedicated tag. `RewardAdGuard` was already taken by the receiver and
         * `RewardAdGuardService` by the accessibility service, and mixing them up
         * makes `adb logcat -s` silently look empty.
         */
        const val TAG = "RewardAdGuardDebug"

        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_VALUE = "value"

        const val CMD_DUMP_SETTINGS = "dump_settings"
        const val CMD_SET_ASSIST_ACTION = "set_assist_action"
        const val CMD_SET_REWARD_APP = "set_reward_app"
        const val CMD_CLEAR_REWARD_APPS = "clear_reward_apps"
        const val CMD_SIMULATE_FOREGROUND = "simulate_foreground"
        const val CMD_DUMP_STATE = "dump_state"
    }
}
