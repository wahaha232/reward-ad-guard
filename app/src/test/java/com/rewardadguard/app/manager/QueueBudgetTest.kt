package com.rewardadguard.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 2026-11-23 review: queue places are reserved atomically before sending. */
class QueueBudgetTest {

    @Test
    fun routineRowsStopAtTheSoftLimitImportantOnesAtCapacity() {
        val budget = QueueBudget(softLimit = 3, capacity = 5)
        repeat(3) { assertTrue(budget.tryReserve(important = false)) }
        assertFalse(budget.tryReserve(important = false))
        assertTrue(budget.tryReserve(important = true))
        assertTrue(budget.tryReserve(important = true))
        assertFalse(budget.tryReserve(important = true))
        assertEquals(5, budget.count)
        budget.release()
        assertEquals(4, budget.count)
    }

    @Test
    fun releaseNeverGoesBelowZero() {
        val budget = QueueBudget(softLimit = 3, capacity = 5)
        budget.release()
        assertEquals(0, budget.count)
    }

    @Test
    fun manyThreadsNeverPassTheLimits() {
        val soft = 1_500
        val capacity = 2_000
        val budget = QueueBudget(soft, capacity)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val routineAccepted = AtomicInteger(0)
        val importantAccepted = AtomicInteger(0)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(500) {
                    val important = (t + it) % 4 == 0
                    if (budget.tryReserve(important)) {
                        if (important) importantAccepted.incrementAndGet() else routineAccepted.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        // Nothing was released: the count is exactly what was accepted, and
        // neither limit was passed, however the threads interleaved.
        assertEquals(routineAccepted.get() + importantAccepted.get(), budget.count)
        assertEquals(capacity, budget.count)
        assertTrue("routine rows ${routineAccepted.get()} passed the soft limit", routineAccepted.get() <= soft)
    }
}
