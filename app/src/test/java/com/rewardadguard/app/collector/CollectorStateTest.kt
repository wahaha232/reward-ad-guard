package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard must behave exactly as before unless the collector is RUNNING.
 *
 * This is the highest-risk part of the integration: a wrong answer here means
 * either the collector fights the guard for the screen, or pausing the
 * collector silently leaves the user unprotected.
 */
class CollectorStateTest {

    @Test
    fun guardActsInIdleAndPaused() {
        assertTrue(GuardYield.shouldGuardAct(CollectorState.IDLE))
        assertTrue(GuardYield.shouldGuardAct(CollectorState.PAUSED))
        assertFalse(GuardYield.shouldGuardAct(CollectorState.RUNNING))
    }
}
