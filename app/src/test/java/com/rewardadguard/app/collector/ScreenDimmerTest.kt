package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-23: only a physical touchscreen wakes the dimmed screen. */
class ScreenDimmerTest {

    @Test
    fun injectedGesturesAreNotTheUser() {
        assertFalse("dispatchGesture / adb input have no device", ScreenDimmer.isRealDevice(-1))
        assertFalse(ScreenDimmer.isRealDevice(0))
        assertTrue("the phone's touchscreen", ScreenDimmer.isRealDevice(6))
    }
}
