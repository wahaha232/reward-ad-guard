package com.rewardadguard.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingBackGateTest {

    /** Review 2026-11-02, 07:31:15.981 and 07:31:16.014: one page, two events. */
    @Test
    fun `two events for the same landing page 33 ms apart get one Back`() {
        val gate = LandingBackGate()
        assertTrue(gate.shouldScheduleBack(now = 15_981L))
        assertFalse(gate.shouldScheduleBack(now = 16_014L))
        assertEquals(1, gate.attempts)
    }

    @Test
    fun `a new landing page after the first Back took effect is handled`() {
        val gate = LandingBackGate()
        assertTrue(gate.shouldScheduleBack(now = 1_000L))
        assertTrue(gate.shouldScheduleBack(now = 1_000L + LandingBackGate.COOLDOWN_MILLIS))
        assertEquals(2, gate.attempts)
    }

    @Test
    fun `presses are capped per ad`() {
        val gate = LandingBackGate(cooldownMillis = 0L, maxPerAd = 2)
        assertTrue(gate.shouldScheduleBack(1L))
        assertTrue(gate.shouldScheduleBack(2L))
        assertFalse(gate.shouldScheduleBack(3L))
    }

    @Test
    fun `reset starts a fresh ad`() {
        val gate = LandingBackGate(cooldownMillis = 10_000L, maxPerAd = 1)
        assertTrue(gate.shouldScheduleBack(1L))
        assertFalse(gate.shouldScheduleBack(2L))
        gate.reset()
        assertTrue(gate.shouldScheduleBack(3L))
    }
}
