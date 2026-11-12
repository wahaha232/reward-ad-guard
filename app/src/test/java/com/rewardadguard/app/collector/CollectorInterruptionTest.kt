package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two automatic pause triggers. Both are safety rules, not conveniences:
 * an accessibility service cannot unlock the phone, and two actors clicking at
 * once is exactly what interfered on 2026-10-16.
 */
class CollectorInterruptionTest {

    @Test
    fun lockScreenPausesTheCollector() {
        assertEquals(
            "keyguard_locked",
            CollectorInterruption.reason(keyguardLocked = true, lastUserInteractionAt = 0L, now = 1_000L)
        )
        // A locked screen wins even right after a touch.
        assertEquals(
            "keyguard_locked",
            CollectorInterruption.reason(keyguardLocked = true, lastUserInteractionAt = 999L, now = 1_000L)
        )
    }

    @Test
    fun realFingerPausesForTheGuardGrace() {
        assertEquals(
            "user_interaction",
            CollectorInterruption.reason(keyguardLocked = false, lastUserInteractionAt = 5_000L, now = 5_500L)
        )
        // Exactly at the end of the grace the collector may continue; this
        // matches the guard's own 1.2 s interaction window.
        assertNull(
            CollectorInterruption.reason(keyguardLocked = false, lastUserInteractionAt = 5_000L, now = 6_200L)
        )
        assertNull(
            CollectorInterruption.reason(keyguardLocked = false, lastUserInteractionAt = 0L, now = 10_000L)
        )
    }
}
