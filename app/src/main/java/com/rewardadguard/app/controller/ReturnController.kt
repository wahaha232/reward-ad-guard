package com.rewardadguard.app.controller

import android.os.Handler
import android.os.Looper
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.session.SessionManager

/**
 * Brings the user back to the reward app after a blocked redirect
 * (spec sections 21-23).
 *
 * Order of attempts, cheapest and least intrusive first:
 *  1. `GLOBAL_ACTION_BACK` of the destination window.
 *  2. `performGlobalAction(GLOBAL_ACTION_BACK)` once more (single re-entrant ad
 *     chains).
 *  3. `GLOBAL_ACTION_RECENTS` is **never** used because it would steal focus.
 *
 * ### Why the scheduled path is asynchronous (ISS-01)
 *
 * The attempt used to run synchronously on the main looper and `Thread.sleep`
 * between the BACK press and the verification probe. Sleeping the main looper
 * freezes the accessibility service's own `onAccessibilityEvent`, so the
 * `TYPE_WINDOW_STATE_CHANGED` that updates [foregroundPackageProvider] cannot be
 * delivered. The probe therefore read the *old* foreground package, the return
 * looked failed, and the guard pressed BACK again - pushing the already-returned
 * reward app away - while the looper stayed blocked for up to ~1.8s (ANR risk).
 *
 * [scheduleReturn] now releases the looper after each BACK and re-checks
 * [RETURN_SETTLE_MILLIS] later with `Handler.postDelayed`, so the service keeps
 * receiving events in between. The blocking [attemptReturn] is kept only for a
 * background/instrumentation caller that needs a result synchronously.
 *
 * The controller never launches the reward app itself unless the user enabled
 * AUTO_LAUNCH, keeping the "no aggressive app switching" promise.
 */
class ReturnController(
    private val logger: EventLogger,
    private val sessionManager: SessionManager,
    private val backAction: () -> Boolean,
    private val launchSourceAction: (String) -> Boolean
) {

    /** Result of a return attempt, so callers can log and update the session. */
    data class ReturnResult(
        val success: Boolean,
        val method: String,
        val attempts: Int
    )

    private val handler = Handler(Looper.getMainLooper())

    /** The next runnable of the in-flight async chain, so it can be cancelled. */
    private var pendingRunnable: Runnable? = null

    @Volatile
    private var returnInProgress: Boolean = false

    /**
     * Verification hook. The accessibility service overrides this through
     * [foregroundPackageProvider]; returning true means the reward app is back.
     */
    var foregroundPackageProvider: (() -> String?)? = null

    /**
     * Schedules a return attempt on the main looper.
     *
     * Returns immediately: the BACK press and the verification probe run as two
     * separate, non-blocking steps so the looper (and therefore
     * `onAccessibilityEvent`) stays responsive in between.
     *
     * @param destinationPackage the app that must be left
     * @param sourcePackage the reward app to come back to
     * @param onComplete invoked on the main looper with the final result
     */
    fun scheduleReturn(
        destinationPackage: String,
        sourcePackage: String,
        settings: AppSettings,
        delayMillis: Long = 0L,
        onComplete: ((ReturnResult) -> Unit)? = null
    ) {
        if (!settings.autoReturnEnabled) {
            logger.log(
                eventType = EventType.RETURN,
                sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
                sourcePackage = sourcePackage,
                destinationPackage = destinationPackage,
                detectionMethod = "RETURN_DISABLED",
                action = "RETURN_SKIPPED",
                result = "SKIPPED",
                message = "auto return disabled by user"
            )
            return
        }
        cancelPending()
        val start = Runnable {
            stepReturnAsync(destinationPackage, sourcePackage, settings, attempt = 1, onComplete = onComplete)
        }
        pendingRunnable = start
        handler.postDelayed(start, delayMillis.coerceAtLeast(0L))
    }

    /** One BACK press, then a deferred verification step. Never blocks. */
    private fun stepReturnAsync(
        destinationPackage: String,
        sourcePackage: String,
        settings: AppSettings,
        attempt: Int,
        onComplete: ((ReturnResult) -> Unit)?
    ) {
        // The reward app may already be back: the 120 ms delay between
        // scheduling and this step (and the settle time after a previous BACK)
        // is enough for the ad to close on its own. Pressing BACK here lands on
        // the reward app's main screen and closes it (field report 2026-10-30).
        if (isSourceForeground(sourcePackage)) {
            finishReturn(true, "ALREADY_IN_SOURCE", attempt, destinationPackage, sourcePackage, onComplete)
            return
        }
        returnInProgress = true
        val dispatched = runCatching { backAction() }.getOrDefault(false)
        if (!dispatched) {
            finishReturn(false, "BACK_NOT_DISPATCHED", attempt, destinationPackage, sourcePackage, onComplete)
            return
        }
        if (!settings.verifyReturn) {
            finishReturn(true, "GLOBAL_ACTION_BACK", attempt, destinationPackage, sourcePackage, onComplete)
            return
        }
        // Hand the looper back before probing. During the wait the accessibility
        // service receives the real window transition and updates the probe.
        scheduleVerify(destinationPackage, sourcePackage, settings, attempt, onComplete)
    }

    /**
     * Probes the foreground up to [RETURN_VERIFY_POLLS] times before deciding a
     * BACK did not work.
     *
     * A cold-starting destination (Shopee takes ~2 s on the field device) is not
     * in front after a single [RETURN_SETTLE_MILLIS] probe, so the old code
     * pressed BACK again - on the wrong screen - and then reported a failure
     * (field report 2026-10-30, root cause 3).
     */
    private fun scheduleVerify(
        destinationPackage: String,
        sourcePackage: String,
        settings: AppSettings,
        attempt: Int,
        onComplete: ((ReturnResult) -> Unit)?,
        pollsLeft: Int = RETURN_VERIFY_POLLS
    ) {
        val verify = Runnable {
            if (isSourceForeground(sourcePackage)) {
                finishReturn(true, "GLOBAL_ACTION_BACK", attempt, destinationPackage, sourcePackage, onComplete)
            } else if (pollsLeft > 1) {
                scheduleVerify(destinationPackage, sourcePackage, settings, attempt, onComplete, pollsLeft - 1)
            } else if (attempt < settings.maxReturnAttempts) {
                stepReturnAsync(destinationPackage, sourcePackage, settings, attempt + 1, onComplete)
            } else if (settings.autoLaunchRewardApp) {
                relaunchAndVerify(destinationPackage, sourcePackage, attempt, onComplete)
            } else {
                finishReturn(false, "GLOBAL_ACTION_BACK", attempt, destinationPackage, sourcePackage, onComplete)
            }
        }
        pendingRunnable = verify
        handler.postDelayed(verify, RETURN_SETTLE_MILLIS)
    }

    /** Launches the reward app, then verifies it one settle interval later. */
    private fun relaunchAndVerify(
        destinationPackage: String,
        sourcePackage: String,
        attempt: Int,
        onComplete: ((ReturnResult) -> Unit)?,
        pollsLeft: Int = RETURN_VERIFY_POLLS
    ) {
        // Never relaunch an app that is already in front: that would restart its
        // main activity for no reason (field report 2026-10-30).
        if (isSourceForeground(sourcePackage)) {
            finishReturn(true, "ALREADY_IN_SOURCE", attempt, destinationPackage, sourcePackage, onComplete)
            return
        }
        val launched = runCatching { launchSourceAction(sourcePackage) }.getOrDefault(false)
        if (!launched) {
            finishReturn(false, "AUTO_LAUNCH_FAILED", attempt, destinationPackage, sourcePackage, onComplete)
            return
        }
        scheduleLaunchVerify(destinationPackage, sourcePackage, attempt, onComplete, pollsLeft)
    }

    private fun scheduleLaunchVerify(
        destinationPackage: String,
        sourcePackage: String,
        attempt: Int,
        onComplete: ((ReturnResult) -> Unit)?,
        pollsLeft: Int
    ) {
        val verify = Runnable {
            // Without a probe (unit tests) a successful launch is trusted.
            val ok = foregroundPackageProvider == null || isSourceForeground(sourcePackage)
            if (ok) {
                finishReturn(true, "AUTO_LAUNCH_REWARD_APP", attempt, destinationPackage, sourcePackage, onComplete)
            } else if (pollsLeft > 1) {
                scheduleLaunchVerify(destinationPackage, sourcePackage, attempt, onComplete, pollsLeft - 1)
            } else {
                finishReturn(false, "AUTO_LAUNCH_REWARD_APP", attempt, destinationPackage, sourcePackage, onComplete)
            }
        }
        pendingRunnable = verify
        handler.postDelayed(verify, RETURN_SETTLE_MILLIS)
    }

    /** Logs the outcome once, then reports it to the optional completion callback. */
    private fun finishReturn(
        success: Boolean,
        method: String,
        attempts: Int,
        destinationPackage: String,
        sourcePackage: String,
        onComplete: ((ReturnResult) -> Unit)?
    ) {
        pendingRunnable = null
        returnInProgress = false
        logger.log(
            eventType = if (success) EventType.RETURN else EventType.RETURN_FAILED,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = sourcePackage,
            destinationPackage = destinationPackage,
            detectionMethod = method,
            action = "RETURN_ATTEMPT",
            result = if (success) "SUCCESS" else "FAILED",
            message = "attempts=$attempts"
        )
        onComplete?.invoke(ReturnResult(success, method, attempts))
    }

    /**
     * Blocking variant, kept for a background/instrumentation caller that wants
     * the result synchronously. Production uses [scheduleReturn]; do **not** call
     * this from the main looper, because it sleeps between attempts.
     *
     * @param destinationPackage the app that must be left
     * @param sourcePackage the reward app to come back to
     */
    fun attemptReturn(
        destinationPackage: String,
        sourcePackage: String,
        settings: AppSettings
    ): ReturnResult {
        if (returnInProgress) {
            return ReturnResult(false, "ALREADY_IN_PROGRESS", 0)
        }
        returnInProgress = true
        var attempts = 0
        var success = false
        var method = "NONE"

        try {
            // Attempt 1..maxAttempts: BACK presses.
            while (attempts < settings.maxReturnAttempts && !success) {
                // Already back: a BACK here would close the reward app.
                if (isSourceForeground(sourcePackage)) {
                    method = "ALREADY_IN_SOURCE"
                    success = true
                    break
                }
                attempts++
                val dispatched = runCatching { backAction() }.getOrDefault(false)
                if (!dispatched) {
                    method = "BACK_NOT_DISPATCHED"
                    break
                }
                method = "GLOBAL_ACTION_BACK"
                if (!settings.verifyReturn) {
                    // Verification disabled: trust the dispatch but still log it.
                    success = true
                    break
                }
                // Give the window manager time to settle before the next probe.
                Thread.sleep(RETURN_SETTLE_MILLIS)
                if (isSourceForeground(sourcePackage)) {
                    success = true
                }
            }

            if (!success && settings.autoLaunchRewardApp && attempts >= settings.maxReturnAttempts) {
                if (isSourceForeground(sourcePackage)) {
                    method = "ALREADY_IN_SOURCE"
                    success = true
                } else {
                    val launched = runCatching { launchSourceAction(sourcePackage) }.getOrDefault(false)
                    if (launched) {
                        method = "AUTO_LAUNCH_REWARD_APP"
                        Thread.sleep(RETURN_SETTLE_MILLIS)
                        // Verify the launch instead of trusting it. The previous
                        // `isSourceForeground(sourcePackage) || true` reported success
                        // unconditionally, so a launch that never reached the
                        // foreground was still logged as a RETURN (root cause 4.4).
                        success = foregroundPackageProvider == null || isSourceForeground(sourcePackage)
                    }
                }
            }
        } catch (t: Throwable) {
            logger.log(
                eventType = EventType.ERROR,
                sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
                sourcePackage = sourcePackage,
                destinationPackage = destinationPackage,
                detectionMethod = "ReturnController",
                action = "RETURN_ERROR",
                result = "FAILED",
                message = "return failed",
                error = t.message
            )
        } finally {
            returnInProgress = false
        }

        logger.log(
            eventType = if (success) EventType.RETURN else EventType.RETURN_FAILED,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = sourcePackage,
            destinationPackage = destinationPackage,
            detectionMethod = method,
            action = "RETURN_ATTEMPT",
            result = if (success) "SUCCESS" else "FAILED",
            message = "attempts=$attempts"
        )
        return ReturnResult(success, method, attempts)
    }

    private fun isSourceForeground(sourcePackage: String): Boolean =
        foregroundPackageProvider?.invoke() == sourcePackage

    fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        returnInProgress = false
    }

    val isReturnInProgress: Boolean get() = returnInProgress

    companion object {
        /** Time given to the window manager between a BACK press and a probe. */
        const val RETURN_SETTLE_MILLIS = 450L

        /**
         * How many probes one BACK gets before it is considered failed.
         *
         * Polling (instead of one 450 ms probe) covers slow cold starts such as
         * Shopee (~2 s on the field device) without pressing BACK a second time
         * on the wrong screen (field report 2026-10-30). Five probes give the
         * destination ~2.25 s to settle.
         */
        const val RETURN_VERIFY_POLLS = 5
    }
}
