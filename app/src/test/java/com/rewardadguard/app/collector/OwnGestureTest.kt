package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-23 review: "ours" means a gesture is really being dispatched. */
class OwnGestureTest {

    @Test
    fun inFlightWhileDispatching() {
        assertTrue(OwnGesture.inFlight(activeCount = 1, endedAt = -1, now = 10_000))
    }

    @Test
    fun aShortTailAfterTheGestureEnds() {
        assertTrue(OwnGesture.inFlight(activeCount = 0, endedAt = 10_000, now = 10_000 + OwnGesture.TAIL_MILLIS))
        assertFalse(OwnGesture.inFlight(activeCount = 0, endedAt = 10_000, now = 10_000 + OwnGesture.TAIL_MILLIS + 1))
    }

    @Test
    fun notTheWholeSelfClickWindow() {
        // 1 s after our tap ended (inside the old 1.5 s window): another tool's
        // injected touch is no longer taken for ours.
        assertFalse(OwnGesture.inFlight(activeCount = 0, endedAt = 10_000, now = 11_000))
        assertFalse("never dispatched", OwnGesture.inFlight(activeCount = 0, endedAt = -1, now = 10_000))
    }
}
