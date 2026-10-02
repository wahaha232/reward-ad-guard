package com.rewardadguard.app.guard

import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.detector.DestinationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the redirect decision logic (spec sections 19-20).
 *
 * The engine is pure Kotlin, so these run on the JVM without a device.
 */
class SmartRedirectEngineTest {

    private fun context(
        kind: DestinationKind = DestinationKind.BROWSER,
        redirectCount: Int = 0,
        returnCount: Int = 0,
        adSessionActive: Boolean = false,
        lastCloseDetectionAt: Long? = null,
        userInteraction: Boolean = false
    ) = RedirectContext(
        sourcePackage = "com.example.reward",
        destinationPackage = "com.example.dest",
        previousPackage = "com.example.reward",
        currentPackage = "com.example.dest",
        timestamp = 1_000L,
        kind = kind,
        redirectCountInWindow = redirectCount,
        returnCountInWindow = returnCount,
        lastAction = null,
        lastCloseDetectionAt = lastCloseDetectionAt,
        adSessionActive = adSessionActive,
        userInteractionDetected = userInteraction
    )

    private fun config(
        smart: Boolean = true,
        policy: RedirectPolicy = RedirectPolicy.BLOCK_AFTER_GRACE,
        grace: Long = 700L,
        threshold: Int = 4
    ) = RedirectPolicyConfig(smart, policy, grace, threshold)

    // ------------------------------------------------------------ base risk

    @Test
    fun `self and unknown destinations carry no risk`() {
        assertEquals(0, SmartRedirectEngine.baseRisk(DestinationKind.SELF))
        assertEquals(0, SmartRedirectEngine.baseRisk(DestinationKind.UNKNOWN))
    }

    @Test
    fun `deep links are riskier than browsers and stores`() {
        assertTrue(
            SmartRedirectEngine.baseRisk(DestinationKind.DEEP_LINK) >
                SmartRedirectEngine.baseRisk(DestinationKind.BROWSER)
        )
        assertTrue(
            SmartRedirectEngine.baseRisk(DestinationKind.GAME) >
                SmartRedirectEngine.baseRisk(DestinationKind.STORE)
        )
        assertEquals(
            SmartRedirectEngine.baseRisk(DestinationKind.STORE),
            SmartRedirectEngine.baseRisk(DestinationKind.BROWSER)
        )
    }

    // ------------------------------------------------------------ risk score

    @Test
    fun `single browser hop after a real tap scores below the low risk bound`() {
        val risk = SmartRedirectEngine.riskScore(
            context(kind = DestinationKind.BROWSER, redirectCount = 1, userInteraction = true)
        )
        // base 1 + no-return 1 - interaction 1 == 1
        assertEquals(1, risk)
        assertTrue(risk <= SmartRedirectEngine.LOW_RISK)
    }

    @Test
    fun `repeated jumps without return raise the risk into the medium band`() {
        val risk = SmartRedirectEngine.riskScore(
            context(
                kind = DestinationKind.BROWSER,
                redirectCount = 3,
                returnCount = 0,
                adSessionActive = true
            )
        )
        // base 1 + (>=2) 1 + (>=3) 2 + no-return 1 + ad 1 == 6
        assertEquals(6, risk)
        assertTrue(risk >= SmartRedirectEngine.MEDIUM_RISK)
    }

    @Test
    fun `risk is clamped to the documented maximum`() {
        val risk = SmartRedirectEngine.riskScore(
            context(
                kind = DestinationKind.DEEP_LINK,
                redirectCount = 9,
                returnCount = 0,
                adSessionActive = true
            )
        )
        // base 3 + (>=2) 1 + (>=3) 2 + no-return 1 + ad 1 == 8, below MAX_RISK.
        assertEquals(8, risk)
        assertTrue(risk <= SmartRedirectEngine.MAX_RISK)
    }

    @Test
    fun `risk never becomes negative`() {
        val risk = SmartRedirectEngine.riskScore(
            context(
                kind = DestinationKind.SELF,
                redirectCount = 0,
                returnCount = 5,
                lastCloseDetectionAt = 500L,
                userInteraction = true
            )
        )
        assertEquals(0, risk)
    }

    @Test
    fun `risk never drops below the destination base risk`() {
        val neverReturned = SmartRedirectEngine.riskScore(
            context(kind = DestinationKind.BROWSER, redirectCount = 1, returnCount = 0)
        )
        val returnedTwice = SmartRedirectEngine.riskScore(
            context(kind = DestinationKind.BROWSER, redirectCount = 1, returnCount = 2)
        )
        // Browser base risk is 1, plus the no-return bonus for the one jump.
        assertEquals(2, neverReturned)
        assertEquals(0, returnedTwice)
        assertTrue(returnedTwice < neverReturned)
    }

    @Test
    fun `seeing a close control lowers the risk`() {
        val withoutClose = SmartRedirectEngine.riskScore(
            context(kind = DestinationKind.BROWSER, redirectCount = 2, returnCount = 0)
        )
        val withClose = SmartRedirectEngine.riskScore(
            context(
                kind = DestinationKind.BROWSER,
                redirectCount = 2,
                returnCount = 0,
                lastCloseDetectionAt = 900L
            )
        )
        assertEquals(withoutClose - 1, withClose)
    }

    // ------------------------------------------------------------ decisions

    @Test
    fun `log only policy never blocks`() {
        val decision = SmartRedirectEngine.decide(
            context(kind = DestinationKind.DEEP_LINK, redirectCount = 4),
            config(policy = RedirectPolicy.LOG_ONLY),
            graceAlreadyElapsed = true
        )
        assertTrue(decision.logOnly)
        assertFalse(decision.block)
        assertEquals("POLICY_LOG_ONLY", decision.reason)
    }

    @Test
    fun `grace policy observes an unresolved medium risk while the window is open`() {
        val decision = SmartRedirectEngine.decide(
            context(
                kind = DestinationKind.BROWSER,
                redirectCount = 3,
                returnCount = 0,
                adSessionActive = false
            ),
            config(policy = RedirectPolicy.BLOCK_AFTER_GRACE, threshold = 9),
            graceAlreadyElapsed = false
        )
        // base 1 + (>=2) 1 + (>=3) 2 + no-return 1 == 5 -> medium, under the raised threshold.
        assertFalse(decision.block)
        assertFalse(decision.logOnly)
        assertEquals(5, decision.riskScore)
        assertEquals("RISK_5_GRACE_ACTIVE", decision.reason)
    }

    @Test
    fun `grace policy blocks the same situation once the grace period elapsed`() {
        val decision = SmartRedirectEngine.decide(
            context(kind = DestinationKind.BROWSER, redirectCount = 2, returnCount = 0),
            config(policy = RedirectPolicy.BLOCK_AFTER_GRACE),
            graceAlreadyElapsed = true
        )
        assertTrue(decision.block)
    }

    @Test
    fun `high risk blocks even before the grace period elapsed`() {
        val decision = SmartRedirectEngine.decide(
            context(
                kind = DestinationKind.DEEP_LINK,
                redirectCount = 3,
                returnCount = 0,
                adSessionActive = true
            ),
            config(policy = RedirectPolicy.BLOCK_AFTER_GRACE),
            graceAlreadyElapsed = false
        )
        assertTrue(decision.block)
        assertTrue(decision.reason.startsWith("HIGH_RISK_"))
    }

    /**
     * Spec test 55: `Store -> Back -> Ad -> X` must still complete. The first
     * low-risk store hop right after a real tap therefore stays observational
     * even under the most aggressive policy.
     */
    @Test
    fun `legitimate store hop after a real tap survives the immediate policy`() {
        val decision = SmartRedirectEngine.decide(
            context(kind = DestinationKind.STORE, redirectCount = 1, userInteraction = true),
            config(policy = RedirectPolicy.BLOCK_IMMEDIATELY),
            graceAlreadyElapsed = true
        )
        assertFalse(decision.block)
        assertFalse(decision.logOnly)
        assertEquals("FIRST_LOW_RISK_INTERACTION", decision.reason)
    }

    @Test
    fun `immediate policy blocks a repeated jump`() {
        val decision = SmartRedirectEngine.decide(
            context(kind = DestinationKind.BROWSER, redirectCount = 3, returnCount = 0),
            config(policy = RedirectPolicy.BLOCK_IMMEDIATELY),
            graceAlreadyElapsed = true
        )
        assertTrue(decision.block)
        assertEquals("IMMEDIATE_POLICY", decision.reason)
    }

    @Test
    fun `smart redirect disabled follows the raw policy`() {
        val observed = SmartRedirectEngine.decide(
            context(kind = DestinationKind.DEEP_LINK, redirectCount = 4),
            config(smart = false, policy = RedirectPolicy.BLOCK_AFTER_GRACE),
            graceAlreadyElapsed = false
        )
        assertEquals("GRACE_PERIOD", observed.reason)
        assertFalse(observed.block)

        val blocked = SmartRedirectEngine.decide(
            context(kind = DestinationKind.DEEP_LINK, redirectCount = 4),
            config(smart = false, policy = RedirectPolicy.BLOCK_IMMEDIATELY),
            graceAlreadyElapsed = false
        )
        assertTrue(blocked.block)
        assertEquals("STRICT_POLICY_IMMEDIATE", blocked.reason)
    }

    @Test
    fun `no decision ever both logs only and blocks`() {
        for (kind in DestinationKind.entries) {
            for (policy in RedirectPolicy.entries) {
                for (elapsed in listOf(true, false)) {
                    for (redirects in 0..4) {
                        val decision = SmartRedirectEngine.decide(
                            context(kind = kind, redirectCount = redirects),
                            config(policy = policy),
                            graceAlreadyElapsed = elapsed
                        )
                        assertFalse(
                            "logOnly and block were both true for $kind/$policy/$elapsed/$redirects",
                            decision.logOnly && decision.block
                        )
                    }
                }
            }
        }
    }
}
