package com.rewardadguard.app.collector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run loop itself, not only the rule (review P0-1 follow-up): with three
 * apps, a stop during the first one means the second and third never start.
 */
class CollectorRunSequenceTest {

    @Test
    fun stopDuringTheFirstAppStartsNoOtherApp() = runTest {
        var stop = false
        val started = ArrayList<String>()
        val reasons = CollectorRunPolicy.runSequence(
            items = listOf("bitwalk", "ahealth", "walkearn"),
            stopRequested = { stop },
            keyguardLocked = { false }
        ) { app ->
            started.add(app)
            stop = true // the user pressed 結束 while this app ran
            "stopped"
        }
        assertEquals(listOf("bitwalk"), started)
        assertEquals(listOf("stopped"), reasons)
    }

    @Test
    fun aStopAfterANormalEndStillBlocksTheNextApp() = runTest {
        var stop = false
        val started = ArrayList<String>()
        CollectorRunPolicy.runSequence(
            items = listOf("bitwalk", "ahealth", "walkearn"),
            stopRequested = { stop },
            keyguardLocked = { false }
        ) { app ->
            started.add(app)
            if (app == "ahealth") stop = true // pressed just as the app finished
            "done"
        }
        assertEquals(listOf("bitwalk", "ahealth"), started)
    }

    @Test
    fun aLockedPhoneStartsNothing() = runTest {
        val started = ArrayList<String>()
        CollectorRunPolicy.runSequence(
            items = listOf("bitwalk", "ahealth"),
            stopRequested = { false },
            keyguardLocked = { true }
        ) { app -> started.add(app); "done" }
        assertTrue(started.isEmpty())
    }

    @Test
    fun aNormalRunVisitsEveryApp() = runTest {
        val started = ArrayList<String>()
        val reasons = CollectorRunPolicy.runSequence(
            items = listOf("bitwalk", "ahealth", "walkearn"),
            stopRequested = { false },
            keyguardLocked = { false }
        ) { app -> started.add(app); if (app == "ahealth") "limit" else "done" }
        assertEquals(3, started.size)
        assertEquals(listOf("done", "limit", "done"), reasons)
    }

    @Test
    fun unlimitedHasNoSixtyMinuteCap() {
        assertFalse(CollectorRunPolicy.runCapApplies(CollectorLimits.UNLIMITED))
        assertTrue(CollectorRunPolicy.runCapApplies(30))
        assertTrue(CollectorRunPolicy.runCapApplies(CollectorLimits.PROFILE_DEFAULT))
    }
}
