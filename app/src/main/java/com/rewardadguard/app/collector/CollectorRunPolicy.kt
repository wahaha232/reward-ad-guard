package com.rewardadguard.app.collector

/**
 * Decides whether a sequential "全部" run may launch its next app.
 *
 * The controller runs profiles one after another, so a user pressing 結束 while
 * the first app is active used to be forgotten: the engine stopped, then the
 * next profile called `environment.start()` and the phone opened another app.
 * The same must not happen when the engine stopped itself because the phone was
 * locked, paused too long, or hit the run timeout.
 *
 * Pure so the rule can be tested without a phone.
 */
object CollectorRunPolicy {

    /** Engine end reasons after which no further app may be started. */
    private val STOPPING_REASONS = setOf(
        "stopped",
        "paused_too_long",
        "run_timeout",
        "cancelled",
        "error"
    )

    /**
     * Runs [items] in order and returns each one's end reason.
     *
     * Shared by the 自動收取 tab and the adb `collector_run` entry, which used
     * to have their own loops - and the adb one still opened the next app after
     * `collector_stop`. [stopRequested] and [keyguardLocked] are read before
     * every item, so a stop pressed during item 1 means items 2 and 3 never
     * start.
     */
    suspend fun <T> runSequence(
        items: List<T>,
        stopRequested: () -> Boolean,
        keyguardLocked: () -> Boolean,
        runOne: suspend (T) -> String
    ): List<String> {
        val reasons = ArrayList<String>(items.size)
        var previous: String? = null
        for (item in items) {
            if (!shouldStartNext(stopRequested(), previous, keyguardLocked())) break
            previous = runOne(item)
            reasons.add(previous)
        }
        return reasons
    }

    /**
     * The 60-minute run cap applies only to a run with a number. 不限數量 means
     * "until I press 結束" (review P1-4): BitWalk allows 50 ads a day and the
     * lottery can take hundreds of draws, both past an hour.
     */
    fun runCapApplies(rewardLimit: Int): Boolean = rewardLimit != CollectorLimits.UNLIMITED

    /**
     * Whether a pause may end the run after the pause limit. A pause the user
     * asked for waits for the user's 繼續; only a pause the collector took by
     * itself (locked phone, finger on the screen) gives up after a while.
     */
    fun pauseTimesOut(pauseReason: String?): Boolean =
        pauseReason != CollectorInterruption.REASON_USER_REQUESTED

    /**
     * Whether one Back after a redirect already left the store sheet and put
     * the reward app (and the ad inside it) back in front. Then the app must
     * not be relaunched: that drops the ad and its reward.
     */
    fun backReturnedToApp(foregroundAfterBack: String?, appPackage: String): Boolean =
        foregroundAfterBack?.trim() == appPackage

    fun shouldStartNext(
        stopRequested: Boolean,
        previousEndReason: String?,
        keyguardLocked: Boolean
    ): Boolean {
        if (stopRequested) return false
        if (keyguardLocked) return false
        return previousEndReason == null || previousEndReason !in STOPPING_REASONS
    }
}
