package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13 17:47: the user's own Twitter was taken for an ad redirect. */
class RedirectJudgeTest {

    @Test
    fun theStoreAndBrowsersAreAlwaysRedirects() {
        assertTrue(RedirectJudge.isAdRedirect("com.android.vending", 60_000))
        assertTrue(RedirectJudge.isAdRedirect("com.android.chrome", 60_000))
    }

    @Test
    fun anAppRightAfterOurOwnTapIsTheAdsDoing() {
        assertTrue(RedirectJudge.isAdRedirect("com.shopback.app", 2_000))
        assertTrue(RedirectJudge.isAdRedirect("com.coupang.mobile.lightspeed", 8_000))
    }

    @Test
    fun anAppTheUserOpenedLaterIsTheUsers() {
        assertFalse(RedirectJudge.isAdRedirect("com.twitter.android", 20_000))
        assertFalse(RedirectJudge.isAdRedirect("com.mi.android.globallauncher", 30_000))
        assertFalse("before any action", RedirectJudge.isAdRedirect("com.twitter.android", -1))
    }

    @Test
    fun anUnknownWindowKeepsTheCarefulHandling() {
        assertTrue(RedirectJudge.isAdRedirect(null, 60_000))
    }

    @Test
    fun aUserLeftPauseTakesOverAFingerPause() {
        assertEquals(
            CollectorInterruption.PauseTransition.UPGRADE,
            CollectorInterruption.pauseTransition(
                CollectorState.PAUSED,
                CollectorInterruption.REASON_USER_INTERACTION,
                CollectorInterruption.REASON_USER_LEFT
            )
        )
    }
}
