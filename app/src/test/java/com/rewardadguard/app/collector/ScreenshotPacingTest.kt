package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two reads 30 ms apart lost their OCR to the platform's screenshot rate limit. */
class ScreenshotPacingTest {

    @Test
    fun firstRequestNeverWaits() {
        assertEquals(0L, ScreenshotPacing.waitMillis(-1L, 5_000L))
    }

    @Test
    fun aRequestRightAfterAnotherWaitsForTheGap() {
        assertEquals(320L, ScreenshotPacing.waitMillis(1_000L, 1_030L))
        assertEquals(0L, ScreenshotPacing.waitMillis(1_000L, 1_350L))
        assertEquals(0L, ScreenshotPacing.waitMillis(1_000L, 9_000L))
    }

    @Test
    fun aClockGoingBackwardsDoesNotBlock() {
        assertEquals(0L, ScreenshotPacing.waitMillis(2_000L, 1_000L))
    }
}
