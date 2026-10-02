package com.rewardadguard.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the session state machine and the session id generator
 * (spec sections 13-14). Both are pure Kotlin.
 */
class SessionStateTest {

    @Test
    fun `operational states are reported as active`() {
        assertTrue(SessionState.REWARD_APP_ACTIVE.isActive())
        assertTrue(SessionState.AD_SESSION_ACTIVE.isActive())
        assertTrue(SessionState.EXTERNAL_APP_DETECTED.isActive())
        assertTrue(SessionState.BLOCKING.isActive())
        assertTrue(SessionState.RETURNING.isActive())
    }

    @Test
    fun `idle completed and error are not active`() {
        assertFalse(SessionState.IDLE.isActive())
        assertFalse(SessionState.COMPLETED.isActive())
        assertFalse(SessionState.ERROR.isActive())
    }

    @Test
    fun `only the three terminal states are inactive`() {
        assertEquals(
            listOf(SessionState.IDLE, SessionState.COMPLETED, SessionState.ERROR),
            SessionState.entries.filter { !it.isActive() }
        )
    }

    @Test
    fun `state machine returns to idle after completion`() {
        var state = SessionState.IDLE
        assertFalse(state.isActive())
        state = SessionState.REWARD_APP_ACTIVE
        assertTrue(state.isActive())
        state = SessionState.EXTERNAL_APP_DETECTED
        state = SessionState.BLOCKING
        state = SessionState.RETURNING
        state = SessionState.REWARD_APP_ACTIVE
        state = SessionState.COMPLETED
        assertFalse(state.isActive())
        state = SessionState.IDLE
        assertFalse(state.isActive())
    }

    @Test
    fun `end reasons cover every exit path`() {
        val reasons = SessionEndReason.entries
        assertEquals(reasons.size, reasons.toSet().size)
        assertTrue(reasons.contains(SessionEndReason.LEFT_REWARD_APP))
        assertTrue(reasons.contains(SessionEndReason.SERVICE_DESTROYED))
        assertTrue(reasons.contains(SessionEndReason.MANUAL))
    }

    @Test
    fun `ids are unique`() {
        val ids = (1..500).map { SessionIdFactory.next(it.toLong()) }
        assertEquals("ids must be unique", 500, ids.toSet().size)
    }

    @Test
    fun `ids follow the documented format`() {
        val id = SessionIdFactory.next(1_700_000_000_000L)
        assertTrue(
            "id '$id' should match SESSION_yyyyMMdd_HHmmss_NNN",
            Regex("^SESSION_\\d{8}_\\d{6}_\\d{3}$").matches(id)
        )
    }

    @Test
    fun `ids inside the same second increment the suffix`() {
        val first = SessionIdFactory.next(1_700_000_000_000L)
        val second = SessionIdFactory.next(1_700_000_000_500L)
        assertEquals("SESSION", first.substringBefore('_'))
        assertEquals("SESSION", second.substringBefore('_'))
        assertNotEquals(first, second)
        assertEquals(1, first.substringAfterLast('_').toInt())
        assertEquals(2, second.substringAfterLast('_').toInt())
    }

    @Test
    fun `a new second restarts the suffix counter`() {
        SessionIdFactory.next(1_700_000_000_000L)
        SessionIdFactory.next(1_700_000_000_500L)
        val nextSecond = SessionIdFactory.next(1_700_000_001_000L)
        assertEquals(1, nextSecond.substringAfterLast('_').toInt())
    }
}
