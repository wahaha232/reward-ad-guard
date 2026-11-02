package com.rewardadguard.app.guard

/**
 * Decides whether an ad's in-app landing page gets a Back press.
 *
 * Review 2026-11-02, 07:31: one `IABLandingPageActivity` raised two window
 * events 33 ms apart, each scheduled a Back, and the presses landed 27 ms apart.
 * The first left the landing page; the second hit the ad itself, which can
 * close it before the reward is granted. The old counter was also reset by the
 * ad activity's own event in between, so both presses were logged as
 * "attempt=1".
 *
 * Rules:
 *  - while a Back scheduled within [cooldownMillis] has not had time to take
 *    effect, no further Back is scheduled, whatever the event says;
 *  - at most [maxPerAd] Back presses per ad, counted when they are
 *    *scheduled*, and only reset when the ad is really over ([reset]).
 *
 * Pure so the 07:31 sequence can be replayed on the JVM.
 */
class LandingBackGate(
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
    private val maxPerAd: Int = MAX_BACKS_PER_AD
) {

    private var lastScheduledAt: Long? = null
    private var scheduled = 0

    /** Back presses scheduled for the current ad, for the log. */
    val attempts: Int get() = scheduled

    /** True when a Back may be scheduled for a landing page seen at [now]. */
    fun shouldScheduleBack(now: Long): Boolean {
        val last = lastScheduledAt
        if (last != null && now - last < cooldownMillis) return false
        if (scheduled >= maxPerAd) return false
        scheduled++
        lastScheduledAt = now
        return true
    }

    /** The ad is over (the reward app's own screen is back, or the session ended). */
    fun reset() {
        lastScheduledAt = null
        scheduled = 0
    }

    companion object {
        /**
         * A scheduled Back is sent after 120 ms and the activity transition it
         * causes takes a few hundred more; 1.5 s covers both on the field device.
         */
        const val COOLDOWN_MILLIS = 1_500L

        /** A page that survives this many presses is not going away. */
        const val MAX_BACKS_PER_AD = 3
    }
}
