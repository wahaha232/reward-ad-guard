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
