package com.rewardadguard.app.manager

import java.util.concurrent.atomic.AtomicInteger

/**
 * Places in the event queue, reserved atomically before a row is sent
 * (review 2026-11-23). Counting after the send let the writer take the row and
 * count it down first, so the count could read low and routine rows could
 * pass the soft limit under load.
 */
class QueueBudget(private val softLimit: Int, private val capacity: Int) {

    private val pending = AtomicInteger(0)

    val count: Int get() = pending.get()

    /** Reserves one place, or false when [EventPriority.accept] says no. */
    fun tryReserve(important: Boolean): Boolean {
        while (true) {
            val current = pending.get()
            if (!EventPriority.accept(current, important, softLimit, capacity)) return false
            if (pending.compareAndSet(current, current + 1)) return true
        }
    }

    /** A reserved row was written, or could not be sent after all. */
    fun release() {
        pending.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}
