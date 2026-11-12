package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review P1-5: a pause must not eat an ad's or a draw's timeout. */
class ActiveClockTest {

    @Test
    fun aFiveMinutePauseDoesNotAdvanceTheActiveClock() {
        val clock = ActiveClock()
        val adStart = clock.activeAt(10_000L)
        clock.pauseStarted(40_000L)
        clock.pauseEnded(340_000L) // paused for five minutes
        val elapsed = clock.activeAt(345_000L) - adStart
        // 30 s before the pause + 5 s after it; the 300 s pause is not counted.
        assertEquals(35_000L, elapsed)
        // A 120 s ad deadline therefore has not expired.
        assertTrue(elapsed < 120_000L)
    }

    @Test
    fun theClockStandsStillDuringAnOngoingPause() {
        val clock = ActiveClock()
        clock.pauseStarted(1_000L)
        assertTrue(clock.isPaused)
        assertEquals(1_000L, clock.activeAt(1_000L))
        assertEquals(1_000L, clock.activeAt(61_000L))
        clock.pauseEnded(61_000L)
        assertFalse(clock.isPaused)
        assertEquals(2_000L, clock.activeAt(62_000L))
    }

    @Test
    fun repeatedPauseCallsKeepTheFirstStartAndPausesAddUp() {
        val clock = ActiveClock()
        clock.pauseStarted(100L)
        clock.pauseStarted(500L)
        clock.pauseEnded(1_100L)
        clock.pauseEnded(2_000L) // no pause open: ignored
        clock.pauseStarted(3_000L)
        clock.pauseEnded(3_500L)
        assertEquals(4_000L - 1_000L - 500L, clock.activeAt(4_000L))
    }
}
