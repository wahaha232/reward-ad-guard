package com.rewardadguard.app.collector

/**
 * Decides whether a sequential "全部" run may launch its next app.
 *
 * The controller runs profiles one after another, so a user pressing 結束 while
 * the first app is active used to be forgotten: the engine stopped, then the
 * next profile called `environment.start()` and the phone opened another app.
 * The same must not happen when the engine stopped itself because the phone was
 * locked, paused too long, or hit the run timeout.
 *
 * Pure so the rule can be tested without a phone.
 */
object CollectorRunPolicy {

    /** Engine end reasons after which no further app may be started. */
    private val STOPPING_REASONS = setOf(
        "stopped",
        "paused_too_long",
        "run_timeout",
        "cancelled",
        "error"
    )

    fun shouldStartNext(
        stopRequested: Boolean,
        previousEndReason: String?,
        keyguardLocked: Boolean
    ): Boolean {
        if (stopRequested) return false
        if (keyguardLocked) return false
        return previousEndReason == null || previousEndReason !in STOPPING_REASONS
    }
}
