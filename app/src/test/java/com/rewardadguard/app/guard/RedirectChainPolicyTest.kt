package com.rewardadguard.app.guard

import com.rewardadguard.app.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays the 2026-10-30 field scenarios for [RedirectChainPolicy].
 *
 * The policy is pure Kotlin, so these run as plain JVM tests: no Android, no
 * Robolectric.
 */
class RedirectChainPolicyTest {

    private val source = "com.tracerofficial"
    private val shopee = "com.shopee.tw"
    private val island = "miui.systemui.plugin"
    private val resolver = "com.android.intentresolver"

    private fun policy() = RedirectChainPolicy()

    @Test
    fun `ad in front then Shopee is a redirect`() {
        val outcome = policy().onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = shopee,
            sessionState = SessionState.AD_SESSION_ACTIVE,
            now = 1_000L
        )
        assertEquals(RedirectChainPolicy.Outcome.REDIRECT, outcome)
    }

    @Test
    fun `ad in front then resolver then Shopee is a redirect`() {
        val policy = policy()
        val resolverOutcome = policy.onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = resolver,
            sessionState = SessionState.AD_SESSION_ACTIVE,
            now = 1_000L,
            isOverlay = true
        )
        assertEquals(RedirectChainPolicy.Outcome.IGNORE, resolverOutcome)

        // The service filters the resolver, so the reward app is still "previous".
        val shopeeOutcome = policy.onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = shopee,
            sessionState = SessionState.AD_SESSION_ACTIVE,
            now = 1_050L
        )
        assertEquals(RedirectChainPolicy.Outcome.REDIRECT, shopeeOutcome)
    }

    @Test
    fun `ad in front then dynamic island then Shopee is a redirect`() {
        val policy = policy()
        assertEquals(
            RedirectChainPolicy.Outcome.IGNORE,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = island,
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_000L,
                isOverlay = true
            )
        )
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = shopee,
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_050L
            )
        )
    }

    @Test
    fun `ad in front then dynamic island then back to the reward app is not a redirect`() {
        val policy = policy()
        assertEquals(
            RedirectChainPolicy.Outcome.IGNORE,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = island,
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_000L,
                isOverlay = true
            )
        )
        assertEquals(
            RedirectChainPolicy.Outcome.IGNORE,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = source,
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_100L
            )
        )
    }

    @Test
    fun `a plain app switch outside an ad ends the session`() {
        val outcome = policy().onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = "jp.naver.line.android",
            sessionState = SessionState.REWARD_APP_ACTIVE,
            now = 1_000L
        )
        assertEquals(RedirectChainPolicy.Outcome.END_SESSION, outcome)
    }

    @Test
    fun `going Home during an ad ends the session and is never chased`() {
        val outcome = policy().onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = "com.miui.home",
            sessionState = SessionState.AD_SESSION_ACTIVE,
            now = 1_000L,
            isHome = true
        )
        assertEquals(RedirectChainPolicy.Outcome.END_SESSION, outcome)
    }

    @Test
    fun `an external app after the chase window is not a redirect`() {
        val policy = policy()
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = shopee,
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_000L
            )
        )
        assertEquals(
            RedirectChainPolicy.Outcome.IGNORE,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = shopee,
                currentPackage = "com.android.chrome",
                sessionState = SessionState.RETURNING,
                now = 1_000L + RedirectChainPolicy.CHASE_WINDOW_MILLIS + 1
            )
        )
    }

    @Test
    fun `a second hop during the return is taken over`() {
        val policy = policy()
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = source,
                currentPackage = "com.android.chrome",
                sessionState = SessionState.AD_SESSION_ACTIVE,
                now = 1_000L
            )
        )
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(
                sourcePackage = source,
                previousPackage = "com.android.chrome",
                currentPackage = "com.android.vending",
                sessionState = SessionState.RETURNING,
                now = 1_500L
            )
        )
    }

    @Test
    fun `the number of chases per session is capped`() {
        val policy = RedirectChainPolicy(maxChasesPerSession = 2)
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(source, source, "com.a", SessionState.AD_SESSION_ACTIVE, 1_000L)
        )
        assertEquals(
            RedirectChainPolicy.Outcome.REDIRECT,
            policy.onTransition(source, "com.a", "com.b", SessionState.RETURNING, 1_100L)
        )
        assertEquals(
            "the guard must stop bouncing the user around",
            RedirectChainPolicy.Outcome.END_SESSION,
            policy.onTransition(source, "com.b", "com.c", SessionState.RETURNING, 1_200L)
        )
    }

    @Test
    fun `the guard own UI is never a redirect destination`() {
        val outcome = policy().onTransition(
            sourcePackage = source,
            previousPackage = source,
            currentPackage = "com.rewardadguard.app",
            sessionState = SessionState.AD_SESSION_ACTIVE,
            now = 1_000L,
            isSelf = true
        )
        assertEquals(RedirectChainPolicy.Outcome.IGNORE, outcome)
    }
}
