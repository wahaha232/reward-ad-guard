package com.rewardadguard.app.collector

/**
 * A timeout that only counts time the collector was actually running.
 *
 * pc-collector's `Control.checkpoint` subtracts paused time from its deadlines
 * (`paused_seconds`), because a ten-minute ad timeout must not expire while the
 * user has the phone locked. The same rule is needed here, but the comparison
 * has to be testable without a clock, so the caller feeds it monotonic
 * timestamps instead of this class reading one.
 */
class ActiveTimeBudget(private val timeoutMillis: Long) {

    private var lastAt: Long = -1L
    private var activeMillis: Long = 0L
    private var pausedMillis: Long = 0L

    /** Time counted towards [timeoutMillis]. */
    val active: Long get() = activeMillis

    /** Time spent paused since the first [advance]. */
    val paused: Long get() = pausedMillis

    /** Starts the clock. Later calls measure from the previous tick. */
    fun start(nowMillis: Long) {
        lastAt = nowMillis
    }

    /**
     * Accounts for the time since the previous tick. Paused intervals go to
     * [paused] and never towards the timeout. A clock that goes backwards (or
     * repeats) adds nothing.
     */
    fun advance(nowMillis: Long, paused: Boolean) {
        if (lastAt < 0L) {
            lastAt = nowMillis
            return
        }
        if (nowMillis < lastAt) return
        val delta = nowMillis - lastAt
        if (paused) pausedMillis += delta else activeMillis += delta
        lastAt = nowMillis
    }

    fun isExpired(): Boolean = activeMillis >= timeoutMillis

    fun remainingMillis(): Long = (timeoutMillis - activeMillis).coerceAtLeast(0L)
}

/**
 * A monotonic clock that stands still while the collector is paused.
 *
 * The engine's ad deadline, the lottery's 240-second wait and the run cap used
 * the wall clock, so a two-minute pause (locked phone, a finger on the screen)
 * made the ad "time out" the moment the run resumed and the reward was lost
 * (review P1-5, 2026-11-12). Deadlines measured with [activeAt] only count the
 * time the collector was actually running, like [ActiveTimeBudget] does for
 * [Actor.waitFor].
 */
class ActiveClock {

    private var pausedTotal = 0L
    private var pausedAt = -1L

    /** Marks the start of a pause; repeated calls keep the first timestamp. */
    fun pauseStarted(nowMillis: Long) {
        if (pausedAt < 0L) pausedAt = nowMillis
    }

    /** Ends the current pause, if any, and banks its length. */
    fun pauseEnded(nowMillis: Long) {
        if (pausedAt < 0L) return
        if (nowMillis > pausedAt) pausedTotal += nowMillis - pausedAt
        pausedAt = -1L
    }

    /** True between [pauseStarted] and [pauseEnded]. */
    val isPaused: Boolean get() = pausedAt >= 0L

    /** Running time at [nowMillis]: the raw clock minus every pause so far. */
    fun activeAt(nowMillis: Long): Long {
        val ongoing = if (pausedAt >= 0L && nowMillis > pausedAt) nowMillis - pausedAt else 0L
        return nowMillis - pausedTotal - ongoing
    }
}
