package com.rewardadguard.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStalenessTest {

    @Test
    fun `a fresh event is not stale`() {
        val age = EventStaleness.ageMillis(eventUptimeMillis = 10_000L, nowUptimeMillis = 10_300L)
        assertEquals(300L, age)
        assertFalse(EventStaleness.isStale(age))
    }

    /** Review 2026-11-02: Google Play opened at 07:58:16 was handled at 07:58:48. */
    @Test
    fun `a 32 second old event from a backlog is stale`() {
        val age = EventStaleness.ageMillis(eventUptimeMillis = 10_000L, nowUptimeMillis = 42_000L)
        assertTrue(EventStaleness.isStale(age))
    }

    @Test
    fun `the threshold itself is still live`() {
        assertFalse(EventStaleness.isStale(EventStaleness.STALE_EVENT_MILLIS))
        assertTrue(EventStaleness.isStale(EventStaleness.STALE_EVENT_MILLIS + 1))
    }

    @Test
    fun `a synthesised event without a time is treated as new`() {
        assertEquals(0L, EventStaleness.ageMillis(eventUptimeMillis = 0L, nowUptimeMillis = 99_000L))
    }

    @Test
    fun `a clock running backwards never yields a negative age`() {
        assertEquals(0L, EventStaleness.ageMillis(eventUptimeMillis = 5_000L, nowUptimeMillis = 4_000L))
    }
}
