package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-23: routine screenshots at most once per five minutes each. */
class ShotPolicyTest {

    private val minute = 60_000L

    @Test
    fun routineShotsAreSpacedFiveMinutesPerKind() {
        val policy = ShotPolicy()
        assertTrue(policy.shouldSave("bw_1_ad_first", 0))
        assertFalse(policy.shouldSave("bw_2_ad_first", 4 * minute))
        assertTrue("another kind is separate", policy.shouldSave("bw_2_ad_end", 4 * minute))
        assertTrue(policy.shouldSave("bw_3_ad_first", 5 * minute))
        assertTrue(policy.shouldSave("bw_1_claimed", 1 * minute))
        assertFalse(policy.shouldSave("bw_2_claimed", 2 * minute))
    }

    @Test
    fun problemShotsAreAlwaysSaved() {
        val policy = ShotPolicy()
        repeat(5) { assertTrue(policy.shouldSave("bw_${it}_redirect", it.toLong())) }
        repeat(5) { assertTrue(policy.shouldSave("bw_${it}_changed", it.toLong())) }
        assertTrue(policy.shouldSave("bw_1_ad_redirect", 0))
        assertTrue(policy.shouldSave("bw_2_ad_redirect", 1))
    }

    @Test
    fun routineKinds() {
        assertEquals("_ad_first", ShotPolicy.routineKind("x_ad_first"))
        assertEquals("_claimed", ShotPolicy.routineKind("x_claimed"))
        assertNull(ShotPolicy.routineKind("x_ad_redirect"))
        assertNull(ShotPolicy.routineKind("dry_home"))
    }
}
