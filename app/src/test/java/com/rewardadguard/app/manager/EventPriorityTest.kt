package com.rewardadguard.app.manager

import com.rewardadguard.app.data.EventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-23: the event queue is bounded and keeps the rows that matter. */
class EventPriorityTest {

    private fun accept(pending: Int, important: Boolean) =
        EventPriority.accept(pending, important, softLimit = 1_500, capacity = 2_000)

    @Test
    fun belowTheSoftLimitEverythingIsQueued() {
        assertTrue(accept(0, important = false))
        assertTrue(accept(1_499, important = false))
    }

    @Test
    fun aCrowdedQueueKeepsOnlyImportantRows() {
        assertFalse(accept(1_500, important = false))
        assertTrue(accept(1_500, important = true))
        assertTrue(accept(1_999, important = true))
    }

    @Test
    fun aFullQueueTakesNothing() {
        assertFalse(accept(2_000, important = true))
        assertFalse(accept(2_000, important = false))
    }

    @Test
    fun errorsSettingsAndRunResultsAreImportant() {
        assertTrue(EventPriority.isImportant(EventType.ERROR, null))
        assertTrue(EventPriority.isImportant(EventType.SETTINGS_CHANGED, null))
        assertTrue(EventPriority.isImportant(EventType.BLOCK, null))
        assertTrue(EventPriority.isImportant(EventType.COLLECTOR, "RUN_END"))
        assertTrue(EventPriority.isImportant(EventType.COLLECTOR, "COLLECTOR_PAUSE"))
    }

    @Test
    fun routineRowsAreNot() {
        assertFalse(EventPriority.isImportant(EventType.APP_CHANGE, null))
        assertFalse(EventPriority.isImportant(EventType.EXTRA_INFO, null))
        assertFalse(EventPriority.isImportant(EventType.CLOSE_DETECT, null))
        assertFalse(EventPriority.isImportant(EventType.COLLECTOR, "TAP"))
    }
}
