package com.rewardadguard.app.guard

import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.detector.AdActionKind
import com.rewardadguard.app.detector.AdActionMatch
import com.rewardadguard.app.detector.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy that decides whether a `[close]` / `[>>]` / `[->]` click may run.
 *
 * The most important cases are the negative ones: a non-reward app, an unknown
 * foreground and a foreground app that is not the reward app that owns the ad
 * must all be refused.
 */
class AdActionGuardTest {

    private val source = "com.example.reward"
    private val other = "com.example.other"
    private val guard = AdActionGuard(logger = null, sessionManager = null)

    private fun node(left: Int = 900, top: Int = 120) = NodeSnapshot(
        text = "[>>]",
        contentDescription = null,
        viewIdResourceName = null,
        className = "android.widget.TextView",
        clickable = true,
        left = left,
        top = top,
        right = left + 80,
        bottom = top + 80
    )

    private fun match(kind: AdActionKind = AdActionKind.NEXT, left: Int = 900, top: Int = 120) =
        AdActionMatch(kind, node(left, top), "TEXT_TOKEN_${kind.name}")

    private fun decide(
        match: AdActionMatch = match(),
        settings: AppSettings = AppSettings(),
        monitored: Boolean = true,
        rootMatches: Boolean = true,
        foreground: String? = source,
        expected: String? = source,
        userInteracting: Boolean = false,
        now: Long = 10_000L
    ) = guard.decide(
        match = match,
        settings = settings,
        adWindowMonitored = monitored,
        rootPackageMatches = rootMatches,
        foregroundPackage = foreground,
        expectedPackage = expected,
        userInteracting = userInteracting,
        now = now
    )

    @Test
    fun `an action in the monitored reward app is allowed`() {
        assertEquals(AdActionDecision.ALLOW, decide())
    }

    @Test
    fun `a non-reward app is never automated`() {
        assertEquals(AdActionDecision.NOT_AD_WINDOW, decide(monitored = false))
    }

    @Test
    fun `an unknown foreground is refused`() {
        assertEquals(AdActionDecision.FOREGROUND_UNKNOWN, decide(foreground = null))
        assertEquals(AdActionDecision.FOREGROUND_UNKNOWN, decide(foreground = "  "))
    }

    @Test
    fun `another app in front is refused`() {
        assertEquals(AdActionDecision.FOREGROUND_MISMATCH, decide(foreground = other))
        assertEquals(AdActionDecision.FOREGROUND_MISMATCH, decide(expected = null))
        assertEquals(AdActionDecision.FOREGROUND_MISMATCH, decide(rootMatches = false))
    }

    @Test
    fun `settings can disable all ad-page automation`() {
        assertEquals(
            AdActionDecision.DISABLED,
            decide(settings = AppSettings(closeButtonAssistance = false))
        )
        assertEquals(
            AdActionDecision.ACTION_NONE,
            decide(settings = AppSettings(assistAction = AssistAction.NONE))
        )
    }

    @Test
    fun `a touch on the screen suppresses the action`() {
        assertEquals(AdActionDecision.USER_INTERACTING, decide(userInteracting = true))
    }

    @Test
    fun `a second action within the throttle window is refused`() {
        assertEquals(AdActionDecision.ALLOW, decide(now = 10_000L))
        guard.onDispatched(match(), source, now = 10_000L)
        assertEquals(
            AdActionDecision.THROTTLED,
            decide(match(kind = AdActionKind.CLOSE, left = 10, top = 10), now = 10_400L)
        )
    }

    @Test
    fun `the same control is not clicked twice`() {
        guard.onDispatched(match(), source, now = 10_000L)
        assertEquals(AdActionDecision.REPEATED, decide(now = 11_000L))
        assertEquals("still suppressed much later", AdActionDecision.REPEATED, decide(now = 60_000L))
    }

    @Test
    fun `a cleared screen allows the same control again`() {
        guard.onDispatched(match(), source, now = 10_000L)
        guard.onScreenCleared()
        assertEquals(AdActionDecision.ALLOW, decide(now = 11_000L))
    }

    @Test
    fun `a different control is allowed after the throttle window`() {
        guard.onDispatched(match(), source, now = 10_000L)
        assertEquals(
            AdActionDecision.ALLOW,
            decide(match(kind = AdActionKind.CLOSE, left = 10, top = 10), now = 11_000L)
        )
    }

    @Test
    fun `next-step clicks are capped per ad`() {
        var now = 10_000L
        repeat(AdActionGuard.MAX_NEXT_STEPS_PER_AD) { i ->
            val m = match(left = 100 + i, top = 100 + i)
            assertEquals(AdActionDecision.ALLOW, decide(match = m, now = now))
            guard.onDispatched(m, source, now = now)
            now += AdActionGuard.THROTTLE_MILLIS
        }
        assertEquals(
            AdActionDecision.NEXT_CAP,
            decide(match = match(left = 999, top = 999), now = now)
        )
    }

    @Test
    fun `reset restores the cap and the throttle`() {
        guard.onDispatched(match(), source, now = 10_000L)
        guard.reset()
        assertEquals(0, guard.nextSteps)
        assertEquals(AdActionDecision.ALLOW, decide(now = 10_100L))
    }

    /** Review 2026-11-02: detect-only wrote one row per scan (about two a second). */
    @Test
    fun `a detect-only skip is logged once per control and interval`() {
        val m = match()
        assertTrue(guard.shouldLogSkip(AdActionDecision.ACTION_NONE, m, now = 1_000L))
        assertFalse(guard.shouldLogSkip(AdActionDecision.ACTION_NONE, m, now = 1_500L))
        assertFalse(guard.shouldLogSkip(AdActionDecision.ACTION_NONE, m, now = 9_000L))
        assertTrue(
            guard.shouldLogSkip(AdActionDecision.ACTION_NONE, m, now = 1_000L + AdActionGuard.DELIBERATE_LOG_INTERVAL_MILLIS)
        )
    }

    @Test
    fun `a different control is logged even inside the interval`() {
        assertTrue(guard.shouldLogSkip(AdActionDecision.ACTION_NONE, match(left = 900), now = 1_000L))
        assertTrue(guard.shouldLogSkip(AdActionDecision.ACTION_NONE, match(left = 100), now = 1_100L))
    }

    @Test
    fun `safety rejections are always logged`() {
        val m = match()
        assertTrue(guard.shouldLogSkip(AdActionDecision.FOREGROUND_MISMATCH, m, now = 1_000L))
        assertTrue(guard.shouldLogSkip(AdActionDecision.FOREGROUND_MISMATCH, m, now = 1_100L))
        assertFalse(guard.shouldLogSkip(AdActionDecision.THROTTLED, m, now = 1_200L))
    }
}
