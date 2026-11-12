package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pc-collector subtracted paused seconds from every deadline; the phone must do
 * the same, or locking the screen for a minute makes a 10-minute ad timeout
 * expire while nothing is happening.
 */
class ActiveTimeBudgetTest {

    @Test
    fun pausedTimeDoesNotConsumeTheTimeout() {
        val budget = ActiveTimeBudget(timeoutMillis = 1_000L)
        budget.start(0L)

        budget.advance(400L, paused = false)
        assertEquals(400L, budget.active)
        assertFalse(budget.isExpired())

        // The user locks the phone for a long time.
        budget.advance(10_400L, paused = true)
        assertEquals(400L, budget.active)
        assertEquals(10_000L, budget.paused)
        assertFalse("paused time must not expire the wait", budget.isExpired())

        budget.advance(10_700L, paused = false)
        assertEquals(700L, budget.active)
        assertFalse(budget.isExpired())

        budget.advance(11_000L, paused = false)
        assertTrue(budget.isExpired())
        assertEquals(0L, budget.remainingMillis())
    }

    @Test
    fun clockGoingBackwardsAddsNothing() {
        val budget = ActiveTimeBudget(timeoutMillis = 100L)
        budget.start(1_000L)
        budget.advance(900L, paused = false)
        assertEquals(0L, budget.active)
        budget.advance(1_050L, paused = false)
        assertEquals(50L, budget.active)
    }
}
