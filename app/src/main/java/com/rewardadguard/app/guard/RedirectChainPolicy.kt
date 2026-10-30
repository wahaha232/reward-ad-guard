package com.rewardadguard.app.guard

import com.rewardadguard.app.session.SessionState

/**
 * Decides whether a foreground transition still belongs to the ad's redirect
 * chain.
 *
 * Field report 2026-10-30 (Shopee not blocked): an ad opened a `shopee.tw` URL
 * through the system intent resolver, and the HyperOS dynamic island appeared
 * while Shopee cold-started. The old rule only fired when the *immediately*
 * previous package was the reward app, so the resolver/island became "previous"
 * and Shopee was let through as an unrelated app. The same island also caused a
 * Back press after the ad had already returned to the reward app.
 *
 * Deliberately Android-free: the decisions are pure so the field scenarios can
 * be replayed on the JVM.
 */
class RedirectChainPolicy(
    private val chaseWindowMillis: Long = CHASE_WINDOW_MILLIS,
    private val maxChasesPerSession: Int = MAX_CHASES_PER_SESSION
) {

    /** What the service should do with the transition. */
    enum class Outcome {
        /** Not part of a redirect chain: stay passive. */
        IGNORE,

        /** An ad-caused departure: hand it to the redirect/return flow. */
        REDIRECT,

        /** The user left on purpose, or the guard fought long enough: end it. */
        END_SESSION
    }

    /** When the active session last stopped owning the foreground. */
    private var leftSourceAt: Long? = null

    /** Redirects chased during the current session, the loop safeguard. */
    private var chases = 0

    /** Forgets the chain, e.g. when a session starts or ends. */
    fun reset() {
        leftSourceAt = null
        chases = 0
    }

    /** The reward app is in front again, so nothing is being chased right now. */
    fun onSourceReturned() {
        leftSourceAt = null
    }

    /**
     * @param sourcePackage the reward app that owns the running session
     * @param previousPackage the last non-overlay foreground package
     * @param currentPackage the package that just came to the front
     * @param sessionState the running session's state
     * @param isOverlay true for a system overlay (island, resolver, system UI)
     * @param isHome true for the user's launcher
     * @param isSelf true for the guard's own UI
     */
    fun onTransition(
        sourcePackage: String,
        previousPackage: String?,
        currentPackage: String,
        sessionState: SessionState,
        now: Long,
        isOverlay: Boolean = false,
        isHome: Boolean = false,
        isSelf: Boolean = false
    ): Outcome {
        // Overlays are transparencies over the same foreground app: they neither
        // end the chain nor start one.
        if (isOverlay) return Outcome.IGNORE
        if (currentPackage == sourcePackage) {
            leftSourceAt = null
            return Outcome.IGNORE
        }
        // Duplicate window events for the same app are not transitions.
        if (currentPackage == previousPackage) return Outcome.IGNORE
        if (isSelf) return Outcome.IGNORE
        if (isHome) {
            // Home is always the user leaving on purpose; never pull them back.
            reset()
            return Outcome.END_SESSION
        }
        if (!sessionState.isActive()) return Outcome.IGNORE

        val leavingSource = previousPackage == sourcePackage
        if (leavingSource) leftSourceAt = now

        if (leavingSource) {
            return if (sessionState == SessionState.AD_SESSION_ACTIVE ||
                sessionState == SessionState.EXTERNAL_APP_DETECTED ||
                sessionState == SessionState.BLOCKING ||
                sessionState == SessionState.RETURNING
            ) {
                chase()
            } else {
                // REWARD_APP_ACTIVE means no ad was on screen: the user simply
                // switched apps, which must end the session, not fight it.
                Outcome.END_SESSION
            }
        }

        // A second hop after the first departure (e.g. Chrome -> Play Store) is
        // still the ad's chain while it happens inside the chase window.
        val chasing = sessionState == SessionState.AD_SESSION_ACTIVE ||
            sessionState == SessionState.EXTERNAL_APP_DETECTED ||
            sessionState == SessionState.BLOCKING ||
            sessionState == SessionState.RETURNING
        if (!chasing) return Outcome.IGNORE

        val leftAt = leftSourceAt ?: return Outcome.IGNORE
        if (now - leftAt > chaseWindowMillis) return Outcome.IGNORE
        return chase()
    }

    private fun chase(): Outcome {
        // Never fight forever: on a destination that keeps opening new apps,
        // stop after a few returns and let the session end instead of bouncing
        // the user between apps.
        if (chases >= maxChasesPerSession) return Outcome.END_SESSION
        chases++
        return Outcome.REDIRECT
    }

    companion object {
        /** How long after leaving the reward app a new app still counts as a hop. */
        const val CHASE_WINDOW_MILLIS = 5_000L

        /** Hard cap on chased redirects per session. */
        const val MAX_CHASES_PER_SESSION = 5
    }
}
