package com.rewardadguard.app.collector

/**
 * Whether the collector may act on what is in front right now.
 *
 * A tap on a screen that does not belong to the target app is how a
 * non-reward app gets operated by mistake (2026-11-12: HAHAGO received Back
 * presses for four minutes while the collector believed it was in LINE). The
 * only safe rule is positive identification: a tap needs the active window to
 * be the target package, and a window the collector cannot identify is
 * treated as foreign.
 *
 * Back on another app needs the same kind of proof (2026-11-23 review): it is
 * allowed only on the exact package the run judged to be the ad's redirect a
 * moment ago ([backAuthorized]), never on a home screen or system surface,
 * and never on an app the run knows nothing about - that is the user's.
 */
object ActionGate {

    enum class Action { TAP, BACK, HOME, LAUNCH }

    enum class Decision { ALLOW, DENY, UNKNOWN }

    fun decide(
        action: Action,
        foreground: String?,
        target: String,
        /** The redirect package the run authorised for Back, still valid; else null. */
        backAuthorized: String? = null,
        /** Launchers, the shade, settings: never pressed Back on by the run. */
        protectedSurface: (String) -> Boolean = { false },
        /**
         * Another app drawing this run's ad ([RemoteAdHost]), judged so on the
         * frame just read; else null. Its X and Back belong to the ad.
         */
        adHostAuthorized: String? = null
    ): Decision {
        val adHost = adHostAuthorized?.trim().orEmpty()
        val fg = foreground?.trim().orEmpty()
        return when (action) {
            // Only ever tap inside the target app.
            Action.TAP -> when {
                fg.isEmpty() -> Decision.UNKNOWN
                fg == target -> Decision.ALLOW
                adHost.isNotEmpty() && fg == adHost -> Decision.ALLOW
                else -> Decision.DENY
            }
            Action.BACK -> when {
                fg.isEmpty() -> Decision.UNKNOWN
                // Inside the reward app (its own ad activities included).
                fg == target -> Decision.ALLOW
                protectedSurface(fg) -> Decision.DENY
                // The store, browser or app this run's ad opened, judged so
                // just before this Back.
                fg == backAuthorized?.trim() -> Decision.ALLOW
                adHost.isNotEmpty() && fg == adHost -> Decision.ALLOW
                else -> Decision.DENY
            }
            // Home and launching the target app cannot operate another app.
            Action.HOME, Action.LAUNCH -> Decision.ALLOW
        }
    }
}
