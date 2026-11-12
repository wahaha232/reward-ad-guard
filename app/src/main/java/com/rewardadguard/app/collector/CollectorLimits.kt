package com.rewardadguard.app.collector

/**
 * How many reward iterations a run may attempt.
 *
 * Three values are needed because three callers mean different things by "no
 * number": the UI's 不限 switch, the CLI default, and an explicit user cap.
 * Keeping the mapping in one tested place stops the engine and the UI from
 * disagreeing about what 0 means.
 */
object CollectorLimits {

    /** No user cap; the engine still stops on no candidate, timeout and 60 min. */
    const val UNLIMITED = -1

    /** Use the app profile's own safety cap (pc-collector's `max_rewards`). */
    const val PROFILE_DEFAULT = 0

    fun iterations(rewardLimit: Int, profileMax: Int): Int = when {
        rewardLimit < 0 -> Int.MAX_VALUE
        rewardLimit > 0 -> rewardLimit
        else -> profileMax
    }

    /**
     * True when the user typed an N. The cap is per app: in 全部 every app
     * collects up to N (each engine counts from zero), which the UI now says
     * ("每個 App 收滿 N 個"). Unlimited and the profile default have no user cap.
     */
    fun hasUserCap(rewardLimit: Int): Boolean = rewardLimit > 0
}
