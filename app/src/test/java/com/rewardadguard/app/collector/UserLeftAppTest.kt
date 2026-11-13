package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-13: a finger on the phone paused the run, the user opened another
 * app, and five seconds later the run pulled them back to the reward app.
 */
class UserLeftAppTest {

    private val bitwalk = "jp.paddleinc.walk"
    private val now = 1_000_000L

    @Test
    fun openingAnotherAppDuringAFingerPauseMakesItAManualPause() {
        assertTrue(CollectorInterruption.userLeftApp(CollectorInterruption.REASON_USER_INTERACTION, "com.facebook.katana", bitwalk))
        assertTrue(CollectorInterruption.userLeftApp(CollectorInterruption.REASON_USER_INTERACTION, "com.miui.home", bitwalk))
    }

    @Test
    fun stayingInTheRewardAppOrPullingTheShadeIsNotLeaving() {
        assertFalse(CollectorInterruption.userLeftApp(CollectorInterruption.REASON_USER_INTERACTION, bitwalk, bitwalk))
        assertFalse(CollectorInterruption.userLeftApp(CollectorInterruption.REASON_USER_INTERACTION, "com.android.systemui", bitwalk))
        assertFalse(CollectorInterruption.userLeftApp(CollectorInterruption.REASON_USER_INTERACTION, null, bitwalk))
        assertFalse("a lock pause is handled by unlocking", CollectorInterruption.userLeftApp(CollectorInterruption.REASON_KEYGUARD, "com.facebook.katana", bitwalk))
    }

    @Test
    fun anAutomaticPauseOnlyResumesWithTheRewardAppInFront() {
        assertTrue(CollectorInterruption.canAutoResume(CollectorInterruption.REASON_USER_INTERACTION, false, now - 6_000, now, targetInFront = true))
        assertFalse(CollectorInterruption.canAutoResume(CollectorInterruption.REASON_USER_INTERACTION, false, now - 6_000, now, targetInFront = false))
        assertFalse(CollectorInterruption.canAutoResume(CollectorInterruption.REASON_KEYGUARD, false, 0L, now, targetInFront = false))
    }

    @Test
    fun aLeftAppPauseNeverResumesOrTimesOutByItself() {
        assertFalse(CollectorInterruption.canAutoResume(CollectorInterruption.REASON_USER_LEFT, false, 0L, now, targetInFront = true))
        assertFalse(CollectorRunPolicy.pauseTimesOut(CollectorInterruption.REASON_USER_LEFT))
    }
}
