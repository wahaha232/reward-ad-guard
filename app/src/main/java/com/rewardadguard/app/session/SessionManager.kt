package com.rewardadguard.app.session

import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.SessionRecord
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.MonitoringState

/**
 * Owns the lifecycle of a reward session (spec sections 13-15).
 *
 * Honesty constraint: Android does not expose "this screen is an ad" to a
 * non-root app, so an ad session is always *inferred* from window changes,
 * close-button nodes and external transitions. The manager never claims an
 * official ad state (Limit 4 in DEVELOPMENT_REPORT.md).
 *
 * All mutating methods are @Synchronized: they are called from the
 * accessibility callback thread and must stay cheap (no I/O).
 */
class SessionManager(
    private val logger: EventLogger,
    private val idFactory: (Long) -> String = { SessionIdFactory.next(it) }
) {

    /** Immutable view of the running session. */
    data class ActiveSession(
        val sessionId: String,
        val sourcePackage: String,
        val startedAt: Long,
        val redirectCount: Int = 0,
        val blockCount: Int = 0,
        val returnSuccessCount: Int = 0,
        val returnFailedCount: Int = 0,
        val closeDetectCount: Int = 0,
        val possibleAdSessions: Int = 0,
        val errorCount: Int = 0,
        val maxRedirectRisk: Int = 0
    ) {
        /**
         * True when the session never saw anything worth recording: no redirect,
         * no block, no return attempt, no close button, no inferred ad session
         * and no error.
         *
         * Such a session is an artefact of the service being restarted while a
         * reward app merely sat in the foreground, so it is not persisted.
         */
        fun hasNoObservations(): Boolean =
            redirectCount == 0 &&
                blockCount == 0 &&
                returnSuccessCount == 0 &&
                returnFailedCount == 0 &&
                closeDetectCount == 0 &&
                possibleAdSessions == 0 &&
                errorCount == 0
    }

    @Volatile
    private var currentSession: ActiveSession? = null

    @Volatile
    private var state: SessionState = SessionState.IDLE

    private var windowChangeCount: Int = 0

    fun activeSession(): ActiveSession? = currentSession

    fun currentState(): SessionState = state

    fun activeSessionId(): String? = currentSession?.sessionId

    /** Convenience accessor used by log statements in the service. */
    val currentSessionId: String?
        get() = currentSession?.sessionId

    /** True while a reward session is running (any state except IDLE). */
    fun isActive(): Boolean = currentSession != null && state != SessionState.IDLE

    /**
     * Starts (or reuses) a session for [sourcePackage].
     * Switching to a different reward app closes the previous session first.
     */
    @Synchronized
    fun startSession(sourcePackage: String, now: Long = System.currentTimeMillis()): ActiveSession {
        currentSession?.let { existing ->
            if (existing.sourcePackage == sourcePackage) return existing
            endSession(SessionEndReason.LEFT_REWARD_APP, now)
        }

        val session = ActiveSession(
            sessionId = idFactory(now),
            sourcePackage = sourcePackage,
            startedAt = now
        )
        currentSession = session
        state = SessionState.REWARD_APP_ACTIVE
        windowChangeCount = 0

        logger.log(
            eventType = EventType.SESSION_START,
            sessionId = session.sessionId,
            sourcePackage = sourcePackage,
            action = "START_SESSION",
            result = "SUCCESS",
            timestamp = now
        )
        publish()
        return session
    }

    /**
     * Ends the active session and persists its record.
     * Safe to call when nothing is active (no-op returning null).
     */
    @Synchronized
    fun endSession(reason: SessionEndReason, now: Long = System.currentTimeMillis()): SessionRecord? {
        val session = currentSession
        if (session == null) {
            state = SessionState.IDLE
            publish()
            return null
        }

        // The accessibility service is restarted by the system very often on
        // vendor ROMs (MIUI in particular). Each restart calls endSession() with
        // SERVICE_DESTROYED, which used to discard an empty session record and
        // leave the UI showing a session that never appeared to end.
        //
        // A session that never observed a single signal carries no information,
        // so it is closed silently and not persisted.
        if (reason == SessionEndReason.SERVICE_DESTROYED && session.hasNoObservations()) {
            currentSession = null
            state = SessionState.IDLE
            windowChangeCount = 0
            publish()
            return null
        }

        state = SessionState.COMPLETED
        val record = SessionRecord(
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            startedAt = session.startedAt,
            endedAt = now,
            endReason = reason.name,
            redirectCount = session.redirectCount,
            blockCount = session.blockCount,
            returnSuccessCount = session.returnSuccessCount,
            returnFailedCount = session.returnFailedCount,
            closeDetectCount = session.closeDetectCount,
            possibleAdSessions = session.possibleAdSessions,
            errorCount = session.errorCount,
            maxRedirectRisk = session.maxRedirectRisk
        )

        logger.log(
            eventType = EventType.SESSION_END,
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            action = "END_SESSION",
            result = "SUCCESS",
            message = "reason=$reason",
            timestamp = now
        )
        logger.log(
            eventType = EventType.SESSION_SUMMARY,
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            action = "SESSION_SUMMARY",
            result = "SUCCESS",
            message = "redirects=${record.redirectCount} blocked=${record.blockCount} " +
                "returns=${record.returnSuccessCount}/${record.returnFailedCount} " +
                "closeDetect=${record.closeDetectCount} risk=${record.maxRedirectRisk}",
            timestamp = now
        )
        logger.persistSession(record)

        currentSession = null
        state = SessionState.IDLE
        windowChangeCount = 0
        publish()
        return record
    }

    /** Observes a window change inside the reward app (ad-session heuristic). */
    @Synchronized
    fun onWindowChanged(now: Long = System.currentTimeMillis()) {
        val session = currentSession ?: return
        windowChangeCount++
        if (windowChangeCount < WINDOWS_FOR_AD_HINT) return
        if (state == SessionState.AD_SESSION_ACTIVE) return

        state = SessionState.AD_SESSION_ACTIVE
        val updated = session.copy(possibleAdSessions = session.possibleAdSessions + 1)
        currentSession = updated
        logger.log(
            eventType = EventType.POSSIBLE_AD_SESSION,
            sessionId = updated.sessionId,
            sourcePackage = updated.sourcePackage,
            detectionMethod = "WINDOW_CHANGE_HEURISTIC",
            action = "INFER_AD_SESSION",
            result = "ATTEMPTED",
            message = "windowChanges=$windowChangeCount (heuristic only, not a confirmed ad)",
            timestamp = now
        )
        publish()
    }

    /** Confirms a stronger ad signal (e.g. a close button was detected). */
    @Synchronized
    fun markAdSessionActive(reason: String, now: Long = System.currentTimeMillis()) {
        val session = currentSession ?: return
        if (state == SessionState.AD_SESSION_ACTIVE) return
        state = SessionState.AD_SESSION_ACTIVE
        currentSession = session.copy(possibleAdSessions = session.possibleAdSessions + 1)
        logger.log(
            eventType = EventType.AD_SESSION_ACTIVE,
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            detectionMethod = reason,
            action = "MARK_AD_SESSION",
            result = "SUCCESS",
            timestamp = now
        )
        publish()
    }

    @Synchronized
    fun enterState(newState: SessionState, now: Long = System.currentTimeMillis(), note: String? = null) {
        val session = currentSession ?: return
        if (state == newState) return
        val previous = state
        state = newState
        logger.log(
            eventType = EventType.SESSION_STATE,
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            action = "STATE_CHANGE",
            result = "SUCCESS",
            message = "from=$previous to=$newState${note?.let { " note=$it" } ?: ""}",
            timestamp = now
        )
        publish()
    }

    @Synchronized
    fun recordRedirect(risk: Int) {
        val session = currentSession ?: return
        currentSession = session.copy(
            redirectCount = session.redirectCount + 1,
            maxRedirectRisk = maxOf(session.maxRedirectRisk, risk)
        )
        publish()
    }

    @Synchronized
    fun recordBlock() {
        val session = currentSession ?: return
        currentSession = session.copy(blockCount = session.blockCount + 1)
        publish()
    }

    @Synchronized
    fun recordReturn(success: Boolean) {
        val session = currentSession ?: return
        currentSession = if (success) {
            session.copy(returnSuccessCount = session.returnSuccessCount + 1)
        } else {
            session.copy(returnFailedCount = session.returnFailedCount + 1)
        }
        publish()
    }

    @Synchronized
    fun recordCloseDetection() {
        val session = currentSession ?: return
        currentSession = session.copy(closeDetectCount = session.closeDetectCount + 1)
        publish()
    }

    /** Records a module error without destroying the session (crash resilience). */
    @Synchronized
    fun recordError(
        module: String,
        message: String,
        error: String?,
        now: Long = System.currentTimeMillis()
    ) {
        val session = currentSession
        if (session != null) {
            currentSession = session.copy(errorCount = session.errorCount + 1)
        }
        logger.log(
            eventType = EventType.ERROR,
            sessionId = currentSession?.sessionId ?: EventLogger.NO_SESSION,
            sourcePackage = session?.sourcePackage,
            detectionMethod = module,
            action = "ERROR",
            result = "FAILED",
            message = message,
            error = error,
            timestamp = now
        )
        publish()
    }

    /**
     * Pushes the current session into [MonitoringState].
     *
     * `sourcePackage` deliberately follows `session` instead of falling back to
     * the previous snapshot: once a session ends there is no source app any
     * more, and keeping the outdated id made the dashboard report the *last*
     * reward app as the active source long after monitoring stopped.
     */
    private fun publish() {
        val session = currentSession
        val currentState = state
        MonitoringState.update { snapshot ->
            snapshot.copy(
                sessionId = session?.sessionId,
                sessionState = currentState,
                sourcePackage = session?.sourcePackage,
                adSessionActive = currentState == SessionState.AD_SESSION_ACTIVE,
                redirectRisk = session?.maxRedirectRisk ?: 0
            )
        }
    }

    /** Records an error without a session (service-level failure). */
    @Synchronized
    fun recordServiceError(module: String, message: String, detail: String? = null) {
        logger?.log(
            eventType = EventType.ERROR,
            sessionId = currentSession?.sessionId ?: EventLogger.NO_SESSION,
            detectionMethod = module,
            action = "ERROR",
            result = "FAILED",
            message = message,
            error = detail
        )
    }

    companion object {
        /**
         * Window changes inside the reward app before an ad session is inferred.
         * Tuned so ordinary in-app navigation is not flagged as an ad.
         */
        const val WINDOWS_FOR_AD_HINT = 2
    }
}
