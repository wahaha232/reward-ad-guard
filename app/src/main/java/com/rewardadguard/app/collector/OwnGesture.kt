package com.rewardadguard.app.collector

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether one of the collector's own gestures is being dispatched right now
 * (review 2026-11-23). Not the 1.5 s self-click window, which exists to
 * recognise the click events the system reports afterwards: an injected touch
 * from another tool in the rest of that window is not ours.
 *
 * In flight from just before dispatch until [TAIL_MILLIS] after it ends, the
 * tail covering input that reaches our overlays slightly after the callback.
 */
object OwnGesture {

    const val TAIL_MILLIS = 250L

    private val active = AtomicInteger(0)

    @Volatile
    private var endedAt = -1L

    fun begin() {
        active.incrementAndGet()
    }

    fun end(now: Long = SystemClock.elapsedRealtime()) {
        endedAt = now
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isInFlight(now: Long = SystemClock.elapsedRealtime()): Boolean =
        inFlight(active.get(), endedAt, now)

    /** Pure part of [isInFlight], for tests. */
    fun inFlight(activeCount: Int, endedAt: Long, now: Long): Boolean =
        activeCount > 0 || (endedAt >= 0L && now - endedAt in 0..TAIL_MILLIS)
}
