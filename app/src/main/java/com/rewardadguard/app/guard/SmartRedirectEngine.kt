package com.rewardadguard.app.guard

import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.detector.DestinationKind

/**
 * Context handed to [SmartRedirectEngine] for every suspicious transition
 * (spec section 19).
 */
data class RedirectContext(
    val sourcePackage: String,
    val destinationPackage: String,
    val previousPackage: String?,
    val currentPackage: String,
    val timestamp: Long,
    val kind: DestinationKind,
    val redirectCountInWindow: Int,
    val returnCountInWindow: Int,
    val lastAction: String?,
    val lastCloseDetectionAt: Long?,
    val adSessionActive: Boolean,
    val userInteractionDetected: Boolean
)

/** Config pulled from [com.rewardadguard.app.data.AppSettings]. */
data class RedirectPolicyConfig(
    val smartRedirectEnabled: Boolean,
    val policy: RedirectPolicy,
    val blockGraceMillis: Long,
    val redirectRiskThreshold: Int = 4
)

/** The decision the guard reached for one transition. */
data class RedirectDecision(
    val logOnly: Boolean,
    val block: Boolean,
    val riskScore: Int,
    val reason: String
) {
    companion object {
        fun observe(risk: Int, reason: String) = RedirectDecision(false, false, risk, reason)
        fun logOnly(risk: Int, reason: String) = RedirectDecision(true, false, risk, reason)
        fun block(risk: Int, reason: String) = RedirectDecision(false, true, risk, reason)
    }
}

/**
 * Pure, deterministic decision logic for suspicious redirects.
 *
 * It is intentionally free of Android APIs so that it can be unit tested:
 *  * Test 06-10 -> external destinations produce `observe` / `block`
 *  * Test 12/13 -> a non-monitored source is rejected before this point
 *  * Test 55    -> a single store/browser hop stays observational, giving the
 *                  legitimate `Store -> Back -> Ad -> X` flow room to complete
 */
object SmartRedirectEngine {

    /** Base risk for each destination class. */
    fun baseRisk(kind: DestinationKind): Int = when (kind) {
        DestinationKind.STORE -> 1
        DestinationKind.BROWSER -> 1
        DestinationKind.GAME -> 2
        DestinationKind.DEEP_LINK -> 3
        DestinationKind.EXTERNAL_APP -> 2
        DestinationKind.SELF, DestinationKind.UNKNOWN -> 0
    }

    /**
     * Computes the risk score (0..9).
     *
     * Signals that raise risk:
     *  - repeated jumps inside the same session (`redirectCountInWindow`)
     *  - no return to the reward app after previous jumps
     *  - a deep link / game destination (base risk)
     *  - an ad session being active at the time
     *
     * Signals that lower risk:
     *  - the user interacted right before (a real tap may legitimately open a CTA)
     *  - a close control was seen recently (normal ad dismissal flow)
     *  - the user already returned from external apps several times
     */
    fun riskScore(context: RedirectContext): Int {
        var risk = baseRisk(context.kind)
        if (context.redirectCountInWindow >= 2) risk += 1
        if (context.redirectCountInWindow >= 3) risk += 2
        if (context.returnCountInWindow == 0 && context.redirectCountInWindow >= 1) risk += 1
        if (context.adSessionActive) risk += 1
        if (context.lastCloseDetectionAt != null) risk -= 1
        if (context.userInteractionDetected && context.redirectCountInWindow <= 1) risk -= 1
        if (context.returnCountInWindow >= 2) risk -= 1
        return risk.coerceIn(0, MAX_RISK)
    }

    /**
     * Full decision for one transition.
     *
     * @param graceAlreadyElapsed true when the destination has already stayed in
     *        the foreground for at least the configured grace period, or when the
     *        user explicitly pressed Back (so the jump is not transient).
     */
    fun decide(
        context: RedirectContext,
        config: RedirectPolicyConfig,
        graceAlreadyElapsed: Boolean
    ): RedirectDecision {
        val risk = riskScore(context)

        // Smart Redirect disabled -> simple, predictable strict behaviour.
        if (!config.smartRedirectEnabled) {
            return when (config.policy) {
                RedirectPolicy.LOG_ONLY -> RedirectDecision.logOnly(risk, "POLICY_LOG_ONLY")
                RedirectPolicy.BLOCK_IMMEDIATELY ->
                    RedirectDecision.block(risk, "STRICT_POLICY_IMMEDIATE")
                RedirectPolicy.BLOCK_AFTER_GRACE ->
                    if (graceAlreadyElapsed) RedirectDecision.block(risk, "STRICT_POLICY_GRACE_ELAPSED")
                    else RedirectDecision.observe(risk, "GRACE_PERIOD")
            }
        }

        return when (config.policy) {
            RedirectPolicy.LOG_ONLY -> RedirectDecision.logOnly(risk, "POLICY_LOG_ONLY")

            RedirectPolicy.BLOCK_IMMEDIATELY ->
                // Even here a first, low-risk hop right after a real tap is only
                // recorded, so legitimate CTA flows (spec test 55) still work.
                if (risk <= LOW_RISK && context.redirectCountInWindow <= 1 &&
                    context.userInteractionDetected
                ) {
                    RedirectDecision.observe(risk, "FIRST_LOW_RISK_INTERACTION")
                } else {
                    RedirectDecision.block(risk, "IMMEDIATE_POLICY")
                }

            RedirectPolicy.BLOCK_AFTER_GRACE -> when {
                risk >= config.redirectRiskThreshold ->
                    RedirectDecision.block(risk, "HIGH_RISK_$risk")
                risk >= MEDIUM_RISK && graceAlreadyElapsed ->
                    RedirectDecision.block(risk, "RISK_${risk}_GRACE_ELAPSED")
                risk >= MEDIUM_RISK ->
                    RedirectDecision.observe(risk, "RISK_${risk}_GRACE_ACTIVE")
                graceAlreadyElapsed && risk >= LOW_RISK ->
                    RedirectDecision.block(risk, "RISK_${risk}_PERSISTED")
                else ->
                    RedirectDecision.observe(risk, "LOW_RISK_$risk")
            }
        }
    }

    const val LOW_RISK = 2
    const val MEDIUM_RISK = 4
    const val MAX_RISK = 9
}
