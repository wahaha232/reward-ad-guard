package com.rewardadguard.app.session

/**
 * Decides what a foreground change away from the session source means for the
 * running session.
 *
 * Without this, a session never ended: pressing Home or switching to a chat app
 * from the reward app was treated as a redirect, escalated after the grace
 * period and answered with BACK presses.
 *
 * Pure logic so the order of the checks is covered by JVM tests.
 */
object SessionExitPolicy {

    enum class Verdict {
        /** The user went to the home screen: they left on purpose. */
        END_LEFT_TO_HOME,

        /** Away from the source for longer than the session timeout. */
        END_TIMEOUT,

        /** Status bar, IME or a system dialog: neither a redirect nor an exit. */
        IGNORE_SYSTEM_SURFACE,

        /** The source was left directly for another app: may be a redirect. */
        CHECK_REDIRECT,

        /** Some other app is in front; the session stays as it is. */
        STAY
    }

    /**
     * @param current the foreground package, already known not to be the
     *   session source and not a monitored reward app
     * @param lastSourceSeenAt the last time the session source was in front
     */
    fun evaluate(
        current: String,
        previous: String?,
        sessionSource: String,
        isLauncher: Boolean,
        isSystemSurface: Boolean,
        lastSourceSeenAt: Long,
        now: Long,
        timeoutMillis: Long
    ): Verdict {
        if (isLauncher) return Verdict.END_LEFT_TO_HOME
        if (now - lastSourceSeenAt >= timeoutMillis) return Verdict.END_TIMEOUT
        if (isSystemSurface) return Verdict.IGNORE_SYSTEM_SURFACE
        if (previous == sessionSource && current != sessionSource) return Verdict.CHECK_REDIRECT
        return Verdict.STAY
    }
}
