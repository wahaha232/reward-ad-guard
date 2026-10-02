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
 * Finds the ad close control and, in ASSIST mode, clicks it (spec sections
 * 24-30).
 *
 * Safety rules that prevent "false-positive closing" (an explicit requirement):
 *  - the node must reach [CloseButtonDetector.MATCH_THRESHOLD] score,
 *  - the node must be inside an inferred ad session (caller enforces this),
 *  - at most one assisted action per [THROTTLE_MILLIS],
 *  - at most [maxAssistPerSession] assisted actions per session,
 *  - while the user is actively touching the screen nothing is clicked.
 */
class CloseButtonGuard(
    private val logger: EventLogger,
    private val sessionManager: SessionManager
) {

    private var lastAssistAt: Long = 0L
    private var assistCountInSession: Int = 0

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
     * Decides whether the user should be assisted for [match].
     *
     * @param isAdWindow whether the match was found inside an inferred ad session
     * @param userInteracting true while the user is actively touching the screen
     */
    fun shouldAssist(
        match: CloseMatch?,
        settings: AppSettings,
        isAdWindow: Boolean,
        now: Long = System.currentTimeMillis(),
        userInteracting: Boolean = false
    ): Boolean {
        if (match == null || !match.matched) return false
        if (!settings.closeButtonAssistance) return false
        if (settings.assistAction == AssistAction.NONE) return false
        if (!isAdWindow) return false
        if (userInteracting) return false
        if (now - lastAssistAt < THROTTLE_MILLIS) return false
        if (assistCountInSession >= maxAssistPerSession) return false
        return true
    }

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
        sessionManager.recordCloseDetection()
        MonitoringState.update { it.copy(todayCloseAssisted = it.todayCloseAssisted + 1) }
        logger.log(
            eventType = EventType.CLOSE_ACTION,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = destinationPackage,
            detectionMethod = match.method,
            action = if (clicked) "CLOSE_ASSIST_CLICK" else "CLOSE_ASSIST_ATTEMPT",
            result = if (clicked) "SUCCESS" else "FAILED",
            message = "score=${match.score} bounds=${match.boundsLabel()}",
            timestamp = now
        )
        if (clicked) {
            logger.log(
                eventType = EventType.CLOSE_RESULT,
                sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
                sourcePackage = destinationPackage,
                detectionMethod = match.method,
                action = "CLOSE_RESULT",
                result = "SUCCESS",
                message = "assisted close dispatched",
                timestamp = now
            )
        }
    }

    /** A detection that was only logged (MONITOR mode). */
    fun onDetectedOnly(
        match: CloseMatch,
        destinationPackage: String?,
        now: Long = System.currentTimeMillis()
    ) {
        sessionManager.recordCloseDetection()
        MonitoringState.update { it.copy(todayCloseDetected = it.todayCloseDetected + 1) }
        logger.log(
            eventType = EventType.CLOSE_DETECT,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = destinationPackage,
            detectionMethod = match.method,
            action = "CLOSE_DETECTED",
            result = "SUCCESS",
            message = "score=${match.score} bounds=${match.boundsLabel()}",
            timestamp = now
        )
    }

    fun reset() {
        lastAssistAt = 0L
        assistCountInSession = 0
        lastDetection = null
    }

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

        /** Hard cap so the guard can never click repeatedly inside one session. */
        const val DEFAULT_MAX_ASSIST_PER_SESSION = 10

        /** Traversal budget. */
        const val MAX_NODES = 400

        /** Overridable cap, defaulting to [DEFAULT_MAX_ASSIST_PER_SESSION]. */
        @Volatile
        var maxAssistPerSession: Int = DEFAULT_MAX_ASSIST_PER_SESSION
    }
}
