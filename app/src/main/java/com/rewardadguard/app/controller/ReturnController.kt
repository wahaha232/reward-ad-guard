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
        delayMillis: Long = 0L,
        onComplete: (ReturnResult) -> Unit = {}
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
        val attempt = Attempt(destinationPackage, sourcePackage, settings, onComplete)
        returnInProgress = true
        post(delayMillis) { pressBack(attempt) }
    }

    /** State of one return sequence; each step is a separate main-looper message. */
    private class Attempt(
        val destinationPackage: String,
        val sourcePackage: String,
        val settings: AppSettings,
        val onComplete: (ReturnResult) -> Unit
    ) {
        var attempts = 0
        var method = "NONE"
    }

    /*
     * The steps below never block. Accessibility events are delivered on the
     * main thread, so sleeping here froze the very foreground package being
     * verified: every return pressed BACK maxReturnAttempts times, and a second
     * BACK inside the reward app can dismiss the reward screen.
     */

    private fun pressBack(attempt: Attempt): Unit = step(attempt) {
        if (isSourceForeground(attempt.sourcePackage)) {
            // Already back (the destination closed itself, or the last BACK
            // worked): another BACK would act on the reward app itself.
            if (attempt.attempts == 0) attempt.method = "ALREADY_RETURNED"
            finish(attempt, success = true)
            return@step
        }
        if (attempt.attempts >= attempt.settings.maxReturnAttempts) {
            launchSource(attempt)
            return@step
        }
        attempt.attempts++
        val dispatched = runCatching { backAction() }.getOrDefault(false)
        if (!dispatched) {
            attempt.method = "BACK_NOT_DISPATCHED"
            finish(attempt, success = false)
            return@step
        }
        attempt.method = "GLOBAL_ACTION_BACK"
        if (!attempt.settings.verifyReturn) {
            // Verification disabled: trust the dispatch but still log it.
            finish(attempt, success = true)
            return@step
        }
        // Give the window manager time to settle before the next probe.
        post(RETURN_SETTLE_MILLIS) { pressBack(attempt) }
    }

    private fun launchSource(attempt: Attempt) {
        if (!attempt.settings.autoLaunchRewardApp) {
            finish(attempt, success = false)
            return
        }
        val launched = runCatching { launchSourceAction(attempt.sourcePackage) }.getOrDefault(false)
        if (!launched) {
            finish(attempt, success = false)
            return
        }
        attempt.method = "AUTO_LAUNCH_REWARD_APP"
        post(RETURN_SETTLE_MILLIS) {
            step(attempt) { finish(attempt, isSourceForeground(attempt.sourcePackage)) }
        }
    }

    private inline fun step(attempt: Attempt, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.log(
                eventType = EventType.ERROR,
                sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
                sourcePackage = attempt.sourcePackage,
                destinationPackage = attempt.destinationPackage,
                detectionMethod = "ReturnController",
                action = "RETURN_ERROR",
                result = "FAILED",
                message = "return failed",
                error = t.message
            )
            finish(attempt, success = false)
        }
    }

    private fun post(delayMillis: Long, action: () -> Unit) {
        val runnable = Runnable {
            pendingRunnable = null
            action()
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0L))
    }

    private fun finish(attempt: Attempt, success: Boolean) {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        returnInProgress = false
        val destinationPackage = attempt.destinationPackage
        val sourcePackage = attempt.sourcePackage
        val method = attempt.method
        val attempts = attempt.attempts

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
        attempt.onComplete(ReturnResult(success, method, attempts))
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
        returnInProgress = false
    }

    val isReturnInProgress: Boolean get() = returnInProgress

    companion object {
        /** Time given to the window manager between a BACK press and a probe. */
        const val RETURN_SETTLE_MILLIS = 450L
    }
}
