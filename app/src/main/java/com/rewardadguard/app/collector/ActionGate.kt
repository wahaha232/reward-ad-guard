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
 */
object ActionGate {

    enum class Action { TAP, BACK, HOME, LAUNCH }

    enum class Decision { ALLOW, DENY, UNKNOWN }

    fun decide(action: Action, foreground: String?, target: String): Decision {
        val fg = foreground?.trim().orEmpty()
        return when (action) {
            // Only ever tap inside the target app.
            Action.TAP -> when {
                fg.isEmpty() -> Decision.UNKNOWN
                fg == target -> Decision.ALLOW
                else -> Decision.DENY
            }
            // Back is how the collector leaves a store page or a browser the
            // ad opened, so a known foreign app is fine; an unknown one is not.
            Action.BACK -> if (fg.isEmpty()) Decision.UNKNOWN else Decision.ALLOW
            // Home and launching the target app cannot operate another app.
            Action.HOME, Action.LAUNCH -> Decision.ALLOW
        }
    }
}
