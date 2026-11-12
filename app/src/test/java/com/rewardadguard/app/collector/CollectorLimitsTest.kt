package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI's 不限 switch and the CLI's 0 must not mean the same thing. */
class CollectorLimitsTest {

    @Test
    fun unlimitedHasNoUserCapButStillEndsByItself() {
        assertEquals(Int.MAX_VALUE, CollectorLimits.iterations(-1, 60))
        assertFalse(CollectorLimits.stopsWholeRun(-1))
    }

    @Test
    fun zeroMeansTheProfileSafetyCap() {
        assertEquals(60, CollectorLimits.iterations(0, 60))
        assertEquals(3, CollectorLimits.iterations(0, 3))
        assertFalse(CollectorLimits.stopsWholeRun(0))
    }

    @Test
    fun explicitLimitStopsTheWholeRun() {
        assertEquals(30, CollectorLimits.iterations(30, 60))
        assertTrue(CollectorLimits.stopsWholeRun(30))
    }
}
