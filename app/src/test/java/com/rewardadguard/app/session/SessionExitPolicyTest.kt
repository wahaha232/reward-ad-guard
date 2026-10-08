package com.rewardadguard.app.session

import com.rewardadguard.app.guard.AllowlistGuard
import com.rewardadguard.app.session.SessionExitPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for H2: a session never ended, so leaving the reward app on
 * purpose was handled as a redirect and answered with BACK presses.
 */
class SessionExitPolicyTest {

    private fun verdict(
        current: String,
        previous: String? = SOURCE,
        lastSourceSeenAt: Long = NOW - 1_000L
    ) = SessionExitPolicy.evaluate(
        current = current,
        previous = previous,
        sessionSource = SOURCE,
        isLauncher = AllowlistGuard.isLauncher(current),
        isSystemSurface = AllowlistGuard.isSystemSurface(current),
        lastSourceSeenAt = lastSourceSeenAt,
        now = NOW,
        timeoutMillis = TIMEOUT
    )

    @Test
    fun `pressing Home from the reward app ends the session instead of being a redirect`() {
        assertEquals(Verdict.END_LEFT_TO_HOME, verdict(LAUNCHER))
    }

    @Test
    fun `an IME or the status bar is not a redirect`() {
        assertEquals(Verdict.IGNORE_SYSTEM_SURFACE, verdict("com.google.android.inputmethod.latin"))
        assertEquals(Verdict.IGNORE_SYSTEM_SURFACE, verdict("com.android.systemui"))
    }

    @Test
    fun `leaving the reward app directly for another app is still checked as a redirect`() {
        assertEquals(Verdict.CHECK_REDIRECT, verdict(STORE))
    }

    @Test
    fun `an app reached from a non-source app leaves the session alone`() {
        assertEquals(Verdict.STAY, verdict(CHAT, previous = STORE))
    }

    @Test
    fun `being away from the source longer than the timeout ends the session`() {
        assertEquals(Verdict.END_TIMEOUT, verdict(CHAT, previous = STORE, lastSourceSeenAt = NOW - TIMEOUT))
        assertEquals(Verdict.STAY, verdict(CHAT, previous = STORE, lastSourceSeenAt = NOW - TIMEOUT + 1))
    }

    @Test
    fun `the launcher is recognised, other system surfaces are not launchers`() {
        assertTrue(AllowlistGuard.isLauncher(LAUNCHER))
        assertFalse(AllowlistGuard.isLauncher("com.android.systemui"))
        assertFalse(AllowlistGuard.isLauncher(STORE))
    }

    private companion object {
        const val SOURCE = "com.example.reward"
        const val STORE = "com.android.vending"
        const val CHAT = "jp.naver.line.android"
        const val LAUNCHER = "com.google.android.apps.nexuslauncher"
        const val NOW = 1_700_000_000_000L
        const val TIMEOUT = 10 * 60 * 1000L
    }
}
