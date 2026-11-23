package com.rewardadguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-23: only NONE and ASSIST_CLICK remain (the other two behaved the
 * same). Values stored by older versions must still read without failing.
 */
class AssistActionCompatTest {

    @Test
    fun onlyRealModesAreOffered() {
        assertEquals(listOf(AssistAction.NONE, AssistAction.ASSIST_CLICK), AssistAction.entries.toList())
    }

    @Test
    fun storedNamesFromOlderVersionsStillRead() {
        assertEquals(AssistAction.NONE, AssistAction.fromName("NONE"))
        assertEquals(AssistAction.ASSIST_CLICK, AssistAction.fromName("ASSIST_CLICK"))
        assertEquals(AssistAction.ASSIST_CLICK, AssistAction.fromName("ASSIST_WHEN_IDLE"))
        assertEquals(AssistAction.ASSIST_CLICK, AssistAction.fromName("ASSIST_TIMED_RETRY"))
        assertEquals(AssistAction.ASSIST_CLICK, AssistAction.fromName(null))
        assertEquals(AssistAction.ASSIST_CLICK, AssistAction.fromName("garbage"))
    }
}
