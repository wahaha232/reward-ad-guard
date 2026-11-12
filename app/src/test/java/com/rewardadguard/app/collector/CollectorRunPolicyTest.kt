package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop must survive into the next iteration of a multi-app run.
 *
 * The regression: profile 1 was stopped by the user, then profile 2 called
 * environment.start() and the phone kept opening apps.
 */
class CollectorRunPolicyTest {

    @Test
    fun stopBeforeTheNextAppMeansNoMoreApps() {
        // Three profiles: the first was stopped, so 2 and 3 are never started.
        assertFalse(
            CollectorRunPolicy.shouldStartNext(
                stopRequested = true,
                previousEndReason = "stopped",
                keyguardLocked = false
            )
        )
        assertFalse(
            CollectorRunPolicy.shouldStartNext(
                stopRequested = true,
                previousEndReason = "done",
                keyguardLocked = false
            )
        )
    }

    @Test
    fun engineStoppedItselfReasonsAlsoEndTheSequence() {
        for (reason in listOf("paused_too_long", "run_timeout", "cancelled", "error")) {
            assertFalse(
                "reason=$reason",
                CollectorRunPolicy.shouldStartNext(
                    stopRequested = false,
                    previousEndReason = reason,
                    keyguardLocked = false
                )
            )
        }
    }

    @Test
    fun normalCompletionContinuesButLockedPhoneDoesNot() {
        assertTrue(CollectorRunPolicy.shouldStartNext(false, "done", false))
        assertTrue(CollectorRunPolicy.shouldStartNext(false, "limit", false))
        assertTrue(CollectorRunPolicy.shouldStartNext(false, null, false))
        assertFalse(CollectorRunPolicy.shouldStartNext(false, "done", true))
    }
}
