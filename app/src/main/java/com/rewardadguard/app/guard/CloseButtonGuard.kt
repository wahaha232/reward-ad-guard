package com.rewardadguard.app.guard

import android.view.accessibility.AccessibilityNodeInfo
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.detector.CloseButtonDetector
import com.rewardadguard.app.detector.CloseMatch
import com.rewardadguard.app.detector.NodeSnapshot
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.MonitoringState
import com.rewardadguard.app.session.SessionManager

/**
 * Why an otherwise-valid close-button match was *not* acted upon.
 *
 * This type exists because of a real, expensive bug. `shouldAssist()` used to
 * return a bare `Boolean`, and the caller collapsed every `false` into the single
 * message `"assist action disabled or throttled"`. A deliberate user setting
 * (`AssistAction.NONE`), a per-session safety cap and a live finger on the screen
 * all produced byte-identical log rows, so a guard that was working exactly as
 * configured looked like a guard that was failing.
 *
 * Returning the reason lets the log name the actual cause. The reason is also
 * used to decide *what* to record: a detection the user explicitly declined to
 * act on is not an error and must never be counted as a "miss".
 */
enum class AssistDecision {
    /** Action was taken (or was allowed to be taken). */
    ALLOW,

    /** [AppSettings.closeButtonAssistance] is off - the user disabled the feature. */
    DISABLED,

    /** [AppSettings.assistAction] is [AssistAction.NONE] - detect only, never click. */
    ACTION_NONE,

    /** The match was found outside an inferred ad session. */
    NOT_AD_WINDOW,

    /** A real finger is on the screen, so auto-clicking would be hostile. */
    USER_INTERACTING,

    /** Two assisted clicks would land within [THROTTLE_MILLIS]. */
    THROTTLED,

    /** The per-session cap [maxAssistPerSession] was reached. */
    SESSION_CAP,

    /** No node reached [CloseButtonDetector.MATCH_THRESHOLD]. */
    NO_MATCH;

    /** True when the guard actively chose not to click (as opposed to failing). */
    val isDeliberate: Boolean
        get() = this == DISABLED || this == ACTION_NONE

    /** Short, log-safe description. Kept untranslated: it is a diagnostic value. */
    fun describe(): String = when (this) {
        ALLOW -> "allowed"
        DISABLED -> "close button assistance disabled in settings"
        ACTION_NONE -> "assist action is NONE (detect only, never click)"
        NOT_AD_WINDOW -> "match outside an inferred ad session"
        USER_INTERACTING -> "user is touching the screen"
        THROTTLED -> "throttled: another assist is within ${THROTTLE_MILLIS}ms"
        SESSION_CAP -> "session assist cap reached (${maxAssistPerSession})"
        NO_MATCH -> "no node reached the match threshold"
    }

    companion object {
        /** The distinct diagnostic name written to the `CLOSE_DECISION` row. */
        fun fromName(name: String?): AssistDecision? =
            entries.firstOrNull { it.name == name }

        /** [THROTTLE_MILLIS] is referenced above, so it must be visible here. */
        private const val THROTTLE_MILLIS = CloseButtonGuard.THROTTLE_MILLIS

        /** [maxAssistPerSession] is a mutable var, hence the accessor. */
        private val maxAssistPerSession: Int
            get() = CloseButtonGuard.maxAssistPerSession
    }
}

/**
 * Finds the ad close control and, in ASSIST mode, clicks it (spec sections
 * 24-30).
 *
 * Safety rules that prevent "false-positive closing" (an explicit requirement):
 *  - the node must reach [CloseButtonDetector.MATCH_THRESHOLD] score,
 *  - the node must be inside an inferred ad session (caller enforces this),
 *  - at most one assisted action per [THROTTLE_MILLIS],
 *  - at most [maxAssistPerSession] assisted actions per session,
 *  - while the user is actively touching the screen nothing is clicked.
 *
 * [logger] and [sessionManager] are nullable on purpose. [decide] is pure and is
 * the entire correctness surface of this class, so it must be testable on the JVM
 * without a Room database behind [EventLogger]. Only the logging entry points need
 * the collaborators, and they no-op when the collaborators are absent.
 */
class CloseButtonGuard(
    private val logger: EventLogger?,
    private val sessionManager: SessionManager?
) {

    private var lastAssistAt: Long = 0L
    private var assistCountInSession: Int = 0

    /** Identity of the last detection actually written to the log. */
    private var lastLoggedDetection: DetectionKey? = null
    private var lastLoggedDetectionAt: Long = 0L

    @Volatile
    var lastDetection: CloseMatch? = null
        private set

    /**
     * Walks the accessibility tree and returns the best close candidate.
     *
     * Traversal is breadth-limited ([MAX_NODES]) so a pathological tree cannot
     * freeze the service.
     */
    fun findCloseCandidate(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): CloseMatch? {
        if (root == null) return null
        val candidates = ArrayList<NodeSnapshot>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.toSnapshot()?.let { candidates.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        val match = CloseButtonDetector.bestMatch(candidates, screenWidth, screenHeight)
        lastDetection = match
        return match
    }

    /**
     * Decides whether the user should be assisted for [match], and *why not* when
     * the answer is no.
     *
     * The returned [AssistDecision] is the single source of truth for both the
     * action and the diagnostic log, so the two can never disagree.
     *
     * @param isAdWindow whether the match was found inside an inferred ad session
     * @param userInteracting true while the user is actively touching the screen
     */
    fun decide(
        match: CloseMatch?,
        settings: AppSettings,
        isAdWindow: Boolean,
        now: Long = System.currentTimeMillis(),
        userInteracting: Boolean = false
    ): AssistDecision {
        if (match == null || !match.matched) return AssistDecision.NO_MATCH
        if (!settings.closeButtonAssistance) return AssistDecision.DISABLED
        if (settings.assistAction == AssistAction.NONE) return AssistDecision.ACTION_NONE
        if (!isAdWindow) return AssistDecision.NOT_AD_WINDOW
        if (userInteracting) return AssistDecision.USER_INTERACTING
        if (now - lastAssistAt < THROTTLE_MILLIS) return AssistDecision.THROTTLED
        if (assistCountInSession >= maxAssistPerSession) return AssistDecision.SESSION_CAP
        return AssistDecision.ALLOW
    }

    /**
     * Convenience wrapper over [decide] for callers that only need the verdict.
     *
     * Retained because the boolean question ("may I click?") is still the right
     * one in tests and in any future caller that does not log.
     */
    fun shouldAssist(
        match: CloseMatch?,
        settings: AppSettings,
        isAdWindow: Boolean,
        now: Long = System.currentTimeMillis(),
        userInteracting: Boolean = false
    ): Boolean = decide(match, settings, isAdWindow, now, userInteracting) == AssistDecision.ALLOW

    /**
     * Records an assisted close attempt.
     *
     * @param clicked true when the accessibility action was dispatched successfully
     */
    fun onAssisted(
        match: CloseMatch,
        clicked: Boolean,
        destinationPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        lastAssistAt = now
        assistCountInSession++
        sessionManager?.recordCloseDetection()
        MonitoringState.update { it.copy(todayCloseAssisted = it.todayCloseAssisted + 1) }
        logger?.log(
            eventType = EventType.CLOSE_ACTION,
            sessionId = sessionId(),
            sourcePackage = destinationPackage,
            detectionMethod = match.method,
            action = if (clicked) "CLOSE_ASSIST_CLICK" else "CLOSE_ASSIST_ATTEMPT",
            result = if (clicked) "SUCCESS" else "FAILED",
            message = "score=${match.score} bounds=${match.boundsLabel()}",
            timestamp = now
        )
        if (clicked) {
            logger?.log(
                eventType = EventType.CLOSE_RESULT,
                sessionId = sessionId(),
                sourcePackage = destinationPackage,
                detectionMethod = match.method,
                action = "CLOSE_RESULT",
                result = "SUCCESS",
                message = "assisted close dispatched",
                timestamp = now
            )
        }
    }

    /**
     * Records that a match was deliberately left alone, naming the reason.
     *
     * Emitted as [EventType.CLOSE_DECISION] rather than `CLOSE_MISS` on purpose.
     * `CLOSE_MISS` shares its name with both an event type and an action and
     * originally meant "there was a close button and we did not press it" for
     * every possible cause, including causes that were not misses at all. A
     * decision row is informational; a miss is a defect. Keeping them apart is
     * what makes the log readable.
     */
    fun onDecisionLogged(
        decision: AssistDecision,
        match: CloseMatch,
        destinationPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        if (decision == AssistDecision.ALLOW) return
        // A detection the user explicitly declined to act on is a choice, not a
        // failure; it is recorded once via onDetectedOnly() and nothing more.
        if (decision.isDeliberate) return
        logger?.log(
            eventType = EventType.CLOSE_DECISION,
            sessionId = sessionId(),
            sourcePackage = destinationPackage,
            detectionMethod = match.method,
            action = "CLOSE_SKIPPED_${decision.name}",
            result = "SKIPPED",
            message = decision.describe(),
            timestamp = now
        )
    }

    /**
     * A detection that changed nothing and produced no action.
     *
     * De-duplicated on purpose. `TYPE_WINDOW_CONTENT_CHANGED` fires continuously
     * while a rewarded ad animates, and the old code wrote two rows
     * (`CLOSE_DETECT` + `CLOSE_BOUNDS`) on *every* callback. On a real device that
     * produced 9,630 rows - 96% of the whole event table - burying the handful of
     * rows that actually described what the guard did.
     *
     * A detection is only new information when the matched node moved or the
     * score changed; repeating an identical observation adds nothing a reader
     * cannot infer from the first row plus the session duration. The suppression
     * is bounded by [DETECTION_DEDUPE_MILLIS] so a long ad still leaves a
     * heartbeat rather than going silent.
     *
     * @return true when a row was actually written.
     */
    fun onDetectedOnly(
        match: CloseMatch,
        destinationPackage: String?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        sessionManager?.recordCloseDetection()
        MonitoringState.update { it.copy(todayCloseDetected = it.todayCloseDetected + 1) }

        if (!isDetectionWorthLogging(match, now)) return false
        logger?.log(
            eventType = EventType.CLOSE_DETECT,
            sessionId = sessionId(),
            sourcePackage = destinationPackage,
            detectionMethod = match.method,
            action = "CLOSE_DETECTED",
            result = "SUCCESS",
            message = "score=${match.score} bounds=${match.boundsLabel()}",
            timestamp = now
        )
        return true
    }

    /**
     * True when [match] differs from the last logged detection, or when the
     * heartbeat interval elapsed.
     *
     * Deliberately ignores the raw node count: only geometry and score decide
     * whether the user-visible situation changed.
     */
    private fun isDetectionWorthLogging(match: CloseMatch, now: Long): Boolean {
        val previous = lastLoggedDetection
        if (previous == null) {
            lastLoggedDetection = DetectionKey.from(match)
            lastLoggedDetectionAt = now
            return true
        }
        val current = DetectionKey.from(match)
        if (current != previous) {
            lastLoggedDetection = current
            lastLoggedDetectionAt = now
            return true
        }
        if (now - lastLoggedDetectionAt >= DETECTION_DEDUPE_MILLIS) {
            lastLoggedDetectionAt = now
            return true
        }
        return false
    }

    fun reset() {
        lastAssistAt = 0L
        assistCountInSession = 0
        lastDetection = null
        lastLoggedDetection = null
        lastLoggedDetectionAt = 0L
    }

    /**
     * Session id for a log row, falling back to the "no session" marker.
     *
     * Centralised because the null-safe collaborators made the old inline
     * `sessionManager.currentSessionId ?: NO_SESSION` expression easy to get wrong.
     */
    private fun sessionId(): String =
        sessionManager?.currentSessionId ?: EventLogger.NO_SESSION

    private fun AccessibilityNodeInfo.toSnapshot(): NodeSnapshot? = try {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        NodeSnapshot(
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            className = className?.toString(),
            clickable = isClickable,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            visible = isVisibleToUser
        )
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** Minimum gap between two assisted clicks. */
        const val THROTTLE_MILLIS = 800L

        /**
         * Minimum gap between two *identical* `CLOSE_DETECT` rows.
         *
         * One row per 10s of unchanged detection is enough to show that the guard
         * was alive; anything more is noise. See [onDetectedOnly].
         */
        const val DETECTION_DEDUPE_MILLIS = 10_000L

        /** Hard cap so the guard can never click repeatedly inside one session. */
        const val DEFAULT_MAX_ASSIST_PER_SESSION = 10

        /** Traversal budget. */
        const val MAX_NODES = 400

        /** Overridable cap, defaulting to [DEFAULT_MAX_ASSIST_PER_SESSION]. */
        @Volatile
        var maxAssistPerSession: Int = DEFAULT_MAX_ASSIST_PER_SESSION
    }
}

/**
 * Cheap value identity for a close-button match.
 *
 * Only the fields a human would use to answer "did anything change?" are kept,
 * so a re-layout that leaves the button where it was is correctly treated as the
 * same observation.
 */
internal data class DetectionKey(
    val score: Int,
    val method: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    companion object {
        fun from(match: CloseMatch): DetectionKey = DetectionKey(
            score = match.score,
            method = match.method,
            left = match.node?.left ?: 0,
            top = match.node?.top ?: 0,
            right = match.node?.right ?: 0,
            bottom = match.node?.bottom ?: 0
        )
    }
}
