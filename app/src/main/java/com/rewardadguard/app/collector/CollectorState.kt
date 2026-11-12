package com.rewardadguard.app.collector

/**
 * Who is allowed to act on the screen right now (integration plan §3).
 *
 * Held by [com.rewardadguard.app.service.RewardAdAccessibilityService] and read
 * by the guard on its event path. It is intentionally a plain enum with no
 * Android dependency: the mutual-exclusion rule is the most safety-critical
 * part of the integration and must be testable on the JVM.
 */
enum class CollectorState {
    /** No collection run: the guard behaves exactly as it did before. */
    IDLE,

    /** The collector owns the screen; the guard only observes and logs. */
    RUNNING,

    /** The collector is paused (lock screen, user touch): the guard resumes. */
    PAUSED
}

/**
 * The guard's side of the mutual-exclusion contract.
 *
 * Only [CollectorState.RUNNING] takes the screen away from the guard. While the
 * collector is [CollectorState.PAUSED] nobody is driving the collector, so the
 * guard's passive protection must keep working - pausing the collector must
 * never leave the user unprotected.
 */
object GuardYield {

    fun shouldGuardAct(state: CollectorState): Boolean = state != CollectorState.RUNNING

    /** Message written when the guard steps aside, for the event log. */
    fun yieldMessage(what: String): String = "guard skipped $what and left it to the collector"
}
