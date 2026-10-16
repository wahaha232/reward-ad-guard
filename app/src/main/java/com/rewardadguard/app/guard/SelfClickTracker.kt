package com.rewardadguard.app.guard

/**
 * Remembers when the guard itself dispatched a click, so the accessibility
 * `TYPE_VIEW_CLICKED` events those dispatches generate are not mistaken for a
 * real finger.
 *
 * Why this exists (field report 2026-10-16, root cause 4.3): an assisted close
 * is performed with `performAction(ACTION_CLICK)` / `dispatchGesture`, and the
 * system then delivers a `TYPE_VIEW_CLICKED` event for it. The old service
 * treated *every* such event as user activity, which
 *   * pushed `isUserInteracting()` true right after the guard's own click,
 *     suppressing the next assist and (in `BLOCK_IMMEDIATELY`) downgrading a
 *     real redirect to "first low-risk interaction" (346 `CLOSE_SKIPPED_USER_
 *     INTERACTING` rows and one escaped redirect on the measured run), and
 *   * made a working guard indistinguishable from a user that kept tapping.
 *
 * Keeping the logic in its own tiny class keeps it unit-testable on the JVM,
 * away from the Android service it is used from.
 */
class SelfClickTracker(
    private val suppressMillis: Long = DEFAULT_SUPPRESS_MILLIS
) {

    @Volatile
    private var ownClickUntil: Long = 0L

    /** Records that the guard dispatched a click at [now]. */
    fun markOwnClick(now: Long) {
        ownClickUntil = now + suppressMillis
    }

    /** True while a click event at [now] must be attributed to the guard. */
    fun isOwnClick(now: Long): Boolean = now < ownClickUntil

    fun reset() {
        ownClickUntil = 0L
    }

    companion object {
        /**
         * Long enough to swallow the whole `TYPE_VIEW_CLICKED` burst of one
         * synthetic tap, and longer than
         * `RewardAdAccessibilityService.INTERACTION_GRACE_MILLIS` so the guard's
         * own click can never keep its own interaction flag alive.
         */
        const val DEFAULT_SUPPRESS_MILLIS = 1_500L
    }
}
