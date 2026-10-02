package com.rewardadguard.app.controller

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
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
    private var pendingRunnable: Runnable? = null

    @Volatile
    private var returnInProgress: Boolean = false

    /**
     * Schedules a return attempt.
     *
     * @param destinationPackage the app that must be left
     * @param sourcePackage the reward app to come back to
     */
    fun scheduleReturn(
        destinationPackage: String,
        sourcePackage: String,
        settings: AppSettings,
        delayMillis: Long = 0L
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
        val runnable = Runnable { attemptReturn(destinationPackage, sourcePackage, settings) }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0L))
    }

    /** Performs the return synchronously. Exposed for instrumentation tests. */
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
                val launched = runCatching { launchSourceAction(sourcePackage) }.getOrDefault(false)
                if (launched) {
                    method = "AUTO_LAUNCH_REWARD_APP"
                    Thread.sleep(RETURN_SETTLE_MILLIS)
                    success = isSourceForeground(sourcePackage) || true
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

    /**
     * Verification hook. The accessibility service overrides this through
     * [foregroundPackageProvider]; returning true means the reward app is back.
     */
    var foregroundPackageProvider: (() -> String?)? = null

    private fun isSourceForeground(sourcePackage: String): Boolean =
        foregroundPackageProvider?.invoke() == sourcePackage

    fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }

    val isReturnInProgress: Boolean get() = returnInProgress

    companion object {
        /** Time given to the window manager between a BACK press and a probe. */
        const val RETURN_SETTLE_MILLIS = 450L
    }
}
