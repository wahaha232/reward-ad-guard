package com.rewardadguard.app.guard

import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.detector.AdActionKind
import com.rewardadguard.app.detector.AdActionMatch
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.session.SessionManager

/**
 * Why an ad-page action (`[close]` / `[>>]` / `[->]`) was not dispatched.
 *
 * Named reasons instead of a bare `Boolean` so the log can tell a deliberate
 * setting (`AssistAction.NONE`) from a safety rejection (the reward app is not
 * the verified foreground app) and from a temporary throttle.
 */
enum class AdActionDecision {
    /** The action may be dispatched. */
    ALLOW,

    /** Close-button assistance is switched off in settings. */
    DISABLED,

    /** Detect-only mode: log, never click. */
    ACTION_NONE,

    /** Not inside a verified ad window of a monitored reward app. */
    NOT_AD_WINDOW,

    /** The foreground package could not be resolved, so nothing may be clicked. */
    FOREGROUND_UNKNOWN,

    /** The foreground app is not the reward app that owns this ad. */
    FOREGROUND_MISMATCH,

    /** A real finger is on the screen. */
    USER_INTERACTING,

    /** Another ad action was dispatched too recently. */
    THROTTLED,

    /** The same control on the same screen was already clicked. */
    REPEATED,

    /** The per-ad cap of next-step clicks was reached. */
    NEXT_CAP,

    /** No clickable target could be resolved on top of the labelled node. */
    NOT_OPERABLE;

    /** True when the guard actively chose not to click (not a failure). */
    val isDeliberate: Boolean
        get() = this == DISABLED || this == ACTION_NONE

    fun describe(): String = when (this) {
        ALLOW -> "allowed"
        DISABLED -> "close button assistance disabled in settings"
        ACTION_NONE -> "assist action is NONE (detect only, never click)"
        NOT_AD_WINDOW -> "no verified ad window of a monitored reward app"
        FOREGROUND_UNKNOWN -> "foreground package could not be verified"
        FOREGROUND_MISMATCH -> "foreground app is not the reward app that owns the ad"
        USER_INTERACTING -> "user is touching the screen"
        THROTTLED -> "throttled: another ad action is within ${AdActionGuard.THROTTLE_MILLIS}ms"
        REPEATED -> "the same control was already clicked on this screen"
        NEXT_CAP -> "per-ad next-step cap reached (${AdActionGuard.MAX_NEXT_STEPS_PER_AD})"
        NOT_OPERABLE -> "no clickable target on top of the label"
    }
}

/**
 * Safety policy for the literal `[close]` and `[>>]` / `[->]` ad controls.
 *
 * Two invariants are enforced before any click:
 *  1. the ad window belongs to a monitored reward app **and** the foreground
 *     package is exactly that app - a normal app can never be clicked, even if
 *     its screen contains the same labels;
 *  2. one click per [THROTTLE_MILLIS], the same labelled control is not clicked
 *     twice while it stays on screen, and at most [MAX_NEXT_STEPS_PER_AD] step
 *     clicks per ad so a looping ad cannot be chased forever.
 *
 * The collaborator references are nullable for the same reason as
 * [CloseButtonGuard]: [decide] must be testable on the JVM without Room.
 */
class AdActionGuard(
    private val logger: EventLogger?,
    private val sessionManager: SessionManager?
) {

    private var lastDispatchAt: Long = 0L
    private var lastKey: String? = null
    private var nextStepsThisAd: Int = 0

    /**
     * @param adWindowMonitored the ad window belongs to a monitored reward app
     * @param rootPackageMatches the live window's root really is [expectedPackage]
     * @param foregroundPackage the package actually in the foreground right now
     * @param expectedPackage the monitored reward app that owns the ad
     */
    fun decide(
        match: AdActionMatch,
        settings: AppSettings,
        adWindowMonitored: Boolean,
        rootPackageMatches: Boolean,
        foregroundPackage: String?,
        expectedPackage: String?,
        userInteracting: Boolean,
        now: Long = System.currentTimeMillis()
    ): AdActionDecision {
        if (!settings.closeButtonAssistance) return AdActionDecision.DISABLED
        if (settings.assistAction == AssistAction.NONE) return AdActionDecision.ACTION_NONE
        if (!adWindowMonitored) return AdActionDecision.NOT_AD_WINDOW
        if (foregroundPackage.isNullOrBlank()) return AdActionDecision.FOREGROUND_UNKNOWN
        if (expectedPackage.isNullOrBlank() || !rootPackageMatches ||
            foregroundPackage != expectedPackage
        ) {
            return AdActionDecision.FOREGROUND_MISMATCH
        }
        if (userInteracting) return AdActionDecision.USER_INTERACTING
        if (now - lastDispatchAt < THROTTLE_MILLIS) return AdActionDecision.THROTTLED
        // The same control (same token on the same rectangle) is never clicked
        // twice until the screen actually changed: a new control, no control at
        // all, or a verified successful click clears the key.
        if (keyOf(match) == lastKey) return AdActionDecision.REPEATED
        if (match.kind == AdActionKind.NEXT && nextStepsThisAd >= MAX_NEXT_STEPS_PER_AD) {
            return AdActionDecision.NEXT_CAP
        }
        return AdActionDecision.ALLOW
    }

    /** Records a dispatched action and its (unverified) outcome. */
    fun onDispatched(
        match: AdActionMatch,
        ownerPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        lastDispatchAt = now
        lastKey = keyOf(match)
        if (match.kind == AdActionKind.NEXT) nextStepsThisAd++
        logger?.log(
            eventType = EventType.AD_ACTION,
            sessionId = sessionId(),
            sourcePackage = ownerPackage,
            detectionMethod = match.method,
            action = if (match.kind == AdActionKind.CLOSE) "AD_CLOSE_CLICK" else "AD_NEXT_CLICK",
            // DISPATCHED, not SUCCESS: the click was sent; [onVerified] decides
            // whether the control actually went away.
            result = "DISPATCHED",
            message = "token=${match.token} bounds=${match.boundsLabel()} ${match.correlationId()}",
            timestamp = now
        )
    }

    /**
     * Records that an action was refused, naming the reason.
     *
     * Throttle and repeat rejections are not logged: they fire on every content
     * change while the same control stays on screen and would flood the log.
     */
    fun onSkipped(
        decision: AdActionDecision,
        match: AdActionMatch,
        ownerPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        if (decision == AdActionDecision.ALLOW) return
        if (decision == AdActionDecision.THROTTLED || decision == AdActionDecision.REPEATED) return
        logger?.log(
            eventType = EventType.AD_ACTION,
            sessionId = sessionId(),
            sourcePackage = ownerPackage,
            detectionMethod = match.method,
            action = "AD_ACTION_SKIPPED_${decision.name}",
            result = "SKIPPED",
            message = decision.describe(),
            timestamp = now
        )
    }

    /** Records whether the clicked control actually disappeared. */
    fun onVerified(
        match: AdActionMatch,
        verified: Boolean,
        ownerPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        // A verified click means the screen moved on, so the same coordinates may
        // legitimately carry the next step's control again.
        if (verified) lastKey = null
        logger?.log(
            eventType = EventType.AD_ACTION,
            sessionId = sessionId(),
            sourcePackage = ownerPackage,
            detectionMethod = match.method,
            action = if (match.kind == AdActionKind.CLOSE) "AD_CLOSE_RESULT" else "AD_NEXT_RESULT",
            result = if (verified) "VERIFIED" else "UNKNOWN",
            message = "token=${match.token} bounds=${match.boundsLabel()} ${match.correlationId()}",
            timestamp = now
        )
    }

    /**
     * Called when a scan sees no `[close]` / `[>>]` / `[->]` control at all.
     *
     * The screen no longer shows the control that was last clicked, so the same
     * rectangle may be used again by the next ad page.
     */
    fun onScreenCleared() {
        lastKey = null
    }

    /** Clears throttle/cap state; called when an ad session starts or ends. */
    fun reset() {
        lastDispatchAt = 0L
        lastKey = null
        nextStepsThisAd = 0
    }

    /** How many next-step clicks this ad has used, for the debug dump. */
    val nextSteps: Int get() = nextStepsThisAd

    /** Milliseconds left before another action may be dispatched. */
    fun millisUntilThrottleClears(now: Long = System.currentTimeMillis()): Long =
        (lastDispatchAt + THROTTLE_MILLIS - now).coerceAtLeast(0L)

    private fun keyOf(match: AdActionMatch): String =
        "${match.token}@${match.node.left},${match.node.top},${match.node.right},${match.node.bottom}"

    private fun sessionId(): String =
        sessionManager?.currentSessionId ?: EventLogger.NO_SESSION

    companion object {
        /** Minimum gap between two ad-page actions. */
        const val THROTTLE_MILLIS = 800L

        /** Hard cap on `[>>]` / `[->]` clicks inside one ad. */
        const val MAX_NEXT_STEPS_PER_AD = 8
    }
}
