package com.rewardadguard.app.guard

import android.util.Log
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.detector.DestinationKind
import com.rewardadguard.app.detector.PackageClassifier
import com.rewardadguard.app.detector.RedirectDetector
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.session.SessionManager

/**
 * Turns a [RedirectDetector.Detection] into a logged action.
 *
 * Two-stage behaviour (spec sections 18-20):
 *  1. MONITOR / LOG_ONLY -> the jump is recorded, nothing is interrupted.
 *  2. BLOCK -> the guard asks the controller to bring the reward app back.
 *
 * Grace handling: a redirect is not immediately punished. The guard keeps the
 * destination as *pending* and only escalates once either the grace period
 * elapsed while the destination stayed in front, or the destination shows the
 * classic ad signatures (extra hops, active ad session).
 */
class RedirectGuard(
    private val classifier: PackageClassifier,
    private val detector: RedirectDetector,
    private val logger: EventLogger,
    private val sessionManager: SessionManager
) {

    /** Destination currently under observation, if any. */
    data class Pending(
        val sourcePackage: String,
        val destinationPackage: String,
        val kind: DestinationKind,
        val startedAt: Long,
        val risk: Int,
        val reason: String
    )

    @Volatile
    var pending: Pending? = null
        private set

    private val recentRedirects = ArrayDeque<Long>()
    private val recentReturns = ArrayDeque<Long>()

    val redirectCountInWindow: Int get() = recentRedirects.size
    val returnCountInWindow: Int get() = recentReturns.size

    /**
     * Handles a foreground transition.
     *
     * @return the decision that was taken, or null when the transition is not a
     *         redirect at all (the vast majority of transitions).
     */
    fun onForegroundChanged(
        previousPackage: String?,
        currentPackage: String?,
        settings: AppSettings,
        sessionActive: Boolean,
        sessionSourcePackage: String?,
        adSessionActive: Boolean,
        lastAction: String?,
        lastCloseDetectionAt: Long?,
        userInteractionDetected: Boolean,
        uriScheme: String? = null,
        now: Long = System.currentTimeMillis()
    ): RedirectDecision? {
        val detection = detector.detect(
            previousPackage = previousPackage,
            currentPackage = currentPackage,
            sessionSourcePackage = sessionSourcePackage,
            sessionActive = sessionActive,
            uriScheme = uriScheme
        ) ?: return null

        remember(recentRedirects, now)
        logger.log(
            eventType = EventType.REDIRECT_DETECTED,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = detection.sourcePackage,
            destinationPackage = detection.destinationPackage,
            detectionMethod = detection.detectionMethod,
            action = "REDIRECT_DETECTED",
            result = "SUCCESS",
            message = "kind=${detection.kind} previous=$previousPackage",
            timestamp = now
        )

        val config = RedirectPolicyConfig(
            smartRedirectEnabled = settings.smartRedirectEnabled,
            policy = settings.redirectPolicy,
            blockGraceMillis = settings.blockGraceMillis
        )
        val context = RedirectContext(
            sourcePackage = detection.sourcePackage,
            destinationPackage = detection.destinationPackage,
            previousPackage = previousPackage,
            currentPackage = currentPackage ?: "",
            timestamp = now,
            kind = detection.kind,
            redirectCountInWindow = recentRedirects.size,
            returnCountInWindow = recentReturns.size,
            lastAction = lastAction,
            lastCloseDetectionAt = lastCloseDetectionAt,
            adSessionActive = adSessionActive,
            userInteractionDetected = userInteractionDetected
        )

        val decision = SmartRedirectEngine.decide(context, config, graceAlreadyElapsed = false)
        pending = Pending(
            sourcePackage = detection.sourcePackage,
            destinationPackage = detection.destinationPackage,
            kind = detection.kind,
            startedAt = now,
            risk = decision.riskScore,
            reason = decision.reason
        )

        logger.log(
            eventType = EventType.REDIRECT_RISK,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = detection.sourcePackage,
            destinationPackage = detection.destinationPackage,
            detectionMethod = detection.detectionMethod,
            action = if (decision.block) "GUARD_BLOCK" else "GUARD_MONITOR",
            result = "SUCCESS",
            message = "risk=${decision.riskScore} reason=${decision.reason} kind=${classifier.eventTypeFor(detection.kind)}",
            timestamp = now
        )
        sessionManager.recordRedirect(decision.riskScore)
        return decision
    }

    /**
     * Called when the pending destination is still in front after the grace
     * period. Re-runs the decision with `graceAlreadyElapsed = true`.
     */
    fun onGraceElapsed(settings: AppSettings, now: Long = System.currentTimeMillis()): RedirectDecision? {
        val current = pending ?: return null
        val context = RedirectContext(
            sourcePackage = current.sourcePackage,
            destinationPackage = current.destinationPackage,
            previousPackage = current.sourcePackage,
            currentPackage = current.destinationPackage,
            timestamp = now,
            kind = current.kind,
            redirectCountInWindow = recentRedirects.size,
            returnCountInWindow = recentReturns.size,
            lastAction = "GRACE_ELAPSED",
            lastCloseDetectionAt = null,
            adSessionActive = true,
            userInteractionDetected = false
        )
        val config = RedirectPolicyConfig(
            smartRedirectEnabled = settings.smartRedirectEnabled,
            policy = settings.redirectPolicy,
            blockGraceMillis = settings.blockGraceMillis
        )
        val decision = SmartRedirectEngine.decide(context, config, graceAlreadyElapsed = true)
        logger.log(
            eventType = EventType.REDIRECT_RISK,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = current.sourcePackage,
            destinationPackage = current.destinationPackage,
            detectionMethod = "GRACE_ELAPSED",
            action = if (decision.block) "GUARD_BLOCK" else "GUARD_MONITOR",
            result = "SUCCESS",
            message = "risk=${decision.riskScore} reason=${decision.reason}",
            timestamp = now
        )
        return decision
    }

    /** Called when the reward app comes back to the foreground. */
    fun onReturnedToSource(success: Boolean, now: Long = System.currentTimeMillis()) {
        if (success) remember(recentReturns, now)
        pending = null
        sessionManager.recordReturn(success)
    }

    fun clearPending() {
        pending = null
    }

    private fun remember(deck: ArrayDeque<Long>, now: Long) {
        deck.addLast(now)
        while (deck.isNotEmpty() && now - deck.first() > RISK_WINDOW_MILLIS) {
            deck.removeFirst()
        }
    }

    companion object {
        private const val TAG = "RedirectGuard"
        /** Sliding window used to judge repeated jumps inside one session. */
        const val RISK_WINDOW_MILLIS = 5 * 60 * 1000L

        /** Human readable transition description, used by the log. */
        fun describe(source: String, destination: String, kind: DestinationKind) =
            "$source -> $destination ($kind)"
    }
}
