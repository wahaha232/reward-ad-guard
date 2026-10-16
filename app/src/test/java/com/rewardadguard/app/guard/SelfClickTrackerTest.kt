package com.rewardadguard.app.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "a click the guard made is not a user click" window
 * (field report 2026-10-16, root cause 4.3).
 */
class SelfClickTrackerTest {

    @Test
    fun `a click is not the guard's before anything was dispatched`() {
        val tracker = SelfClickTracker()
        assertFalse(tracker.isOwnClick(1_000L))
    }

    @Test
    fun `a click inside the window is the guard's own`() {
        val tracker = SelfClickTracker(suppressMillis = 1_500L)
        tracker.markOwnClick(10_000L)

        assertTrue(tracker.isOwnClick(10_000L))
        assertTrue(tracker.isOwnClick(10_000L + 1_499L))
    }

    @Test
    fun `the window expires exactly at the boundary`() {
        val tracker = SelfClickTracker(suppressMillis = 1_500L)
        tracker.markOwnClick(10_000L)

        assertFalse("a click at the boundary belongs to the user again", tracker.isOwnClick(11_500L))
    }

    @Test
    fun `reset clears a pending window`() {
        val tracker = SelfClickTracker()
        tracker.markOwnClick(10_000L)
        tracker.reset()

        assertFalse(tracker.isOwnClick(10_000L))
    }

    @Test
    fun `the default window outlives the interaction grace`() {
        // The service's interaction grace is 1_200ms; the suppression window must
        // be strictly longer so the guard's own click can never keep it alive.
        assertTrue(SelfClickTracker.DEFAULT_SUPPRESS_MILLIS > 1_200L)
    }
}
