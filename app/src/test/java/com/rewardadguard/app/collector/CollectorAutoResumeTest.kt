package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review P1-3: an automatic pause ends by itself, a user pause does not. */
class CollectorAutoResumeTest {

    private val now = 1_000_000L

    @Test
    fun lockScreenPauseResumesAfterUnlockAndFiveQuietSeconds() {
        assertFalse(
            "still locked",
            CollectorInterruption.canAutoResume(CollectorInterruption.REASON_KEYGUARD, true, 0L, now)
        )
        assertTrue(
            "unlocked, never touched",
            CollectorInterruption.canAutoResume(CollectorInterruption.REASON_KEYGUARD, false, 0L, now)
        )
        assertFalse(
            "unlocked but touched 2 s ago",
            CollectorInterruption.canAutoResume(CollectorInterruption.REASON_KEYGUARD, false, now - 2_000L, now)
        )
        assertTrue(
            "unlocked, last touch 5 s ago",
            CollectorInterruption.canAutoResume(CollectorInterruption.REASON_KEYGUARD, false, now - 5_000L, now)
        )
    }

    @Test
    fun fingerPauseResumesOnlyAfterTheFingerIsGone() {
        val reason = CollectorInterruption.REASON_USER_INTERACTION
        assertFalse(CollectorInterruption.canAutoResume(reason, false, now - 1_000L, now))
        assertTrue(CollectorInterruption.canAutoResume(reason, false, now - 6_000L, now))
    }

    @Test
    fun aPauseTheUserAskedForNeverResumesByItself() {
        val reason = CollectorInterruption.REASON_USER_REQUESTED
        assertFalse(CollectorInterruption.canAutoResume(reason, false, 0L, now))
        assertFalse(CollectorInterruption.canAutoResume(reason, false, now - 3_600_000L, now))
        assertFalse(CollectorInterruption.canAutoResume(null, false, 0L, now))
    }

    @Test
    fun onlyAutomaticPausesTimeOut() {
        assertTrue(CollectorRunPolicy.pauseTimesOut(CollectorInterruption.REASON_KEYGUARD))
        assertTrue(CollectorRunPolicy.pauseTimesOut(CollectorInterruption.REASON_USER_INTERACTION))
        assertFalse(CollectorRunPolicy.pauseTimesOut(CollectorInterruption.REASON_USER_REQUESTED))
    }
}

/** 2026-11-12 21:59: touching 暫停 auto-paused first, so the user's pause never held. */
class ManualPauseTest {

    @Test
    fun aUserPauseTakesOverAPauseTheFingerCaused() {
        org.junit.Assert.assertEquals(
            CollectorInterruption.PauseTransition.UPGRADE,
            CollectorInterruption.pauseTransition(
                CollectorState.PAUSED,
                CollectorInterruption.REASON_USER_INTERACTION,
                CollectorInterruption.REASON_USER_REQUESTED
            )
        )
        // ...after which it no longer resumes by itself.
        assertFalse(
            CollectorInterruption.canAutoResume(CollectorInterruption.REASON_USER_REQUESTED, false, 0L, 1_000_000L)
        )
    }

    @Test
    fun runningPausesAndRepeatsAreIgnored() {
        org.junit.Assert.assertEquals(
            CollectorInterruption.PauseTransition.PAUSE,
            CollectorInterruption.pauseTransition(CollectorState.RUNNING, null, CollectorInterruption.REASON_USER_REQUESTED)
        )
        org.junit.Assert.assertEquals(
            CollectorInterruption.PauseTransition.IGNORE,
            CollectorInterruption.pauseTransition(
                CollectorState.PAUSED,
                CollectorInterruption.REASON_USER_REQUESTED,
                CollectorInterruption.REASON_USER_REQUESTED
            )
        )
        org.junit.Assert.assertEquals(
            CollectorInterruption.PauseTransition.IGNORE,
            CollectorInterruption.pauseTransition(
                CollectorState.PAUSED,
                CollectorInterruption.REASON_KEYGUARD,
                CollectorInterruption.REASON_USER_INTERACTION
            )
        )
        org.junit.Assert.assertEquals(
            CollectorInterruption.PauseTransition.IGNORE,
            CollectorInterruption.pauseTransition(CollectorState.IDLE, null, CollectorInterruption.REASON_USER_REQUESTED)
        )
    }
}
