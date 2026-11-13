package com.rewardadguard.app.collector

/**
 * Giving up on an ad early, and remembering the reward for later.
 *
 * Before: an ad with no way out (a playable "開始演奏", a frozen "Ad 1 of 2")
 * held the run for its whole timeout plus two Back waits - about three
 * minutes - and then the same button was tapped again at once. Now an ad that
 * shows no countdown, offers no control and does not change for a minute is
 * abandoned, its reward is retried once after everything else, and a second
 * failure leaves it to the user (2026-11-13).
 */
object AdStuck {

    /** No change, no countdown, nothing to tap: this long means stuck. */
    const val NO_PROGRESS_MILLIS = 60_000L

    fun isStuck(adSeen: Boolean, elapsedMillis: Long, minWatchMillis: Long, sinceProgressMillis: Long): Boolean =
        adSeen && elapsedMillis >= minWatchMillis && sinceProgressMillis >= NO_PROGRESS_MILLIS

    /**
     * Identity of a reward button across passes. LINE's labels carry a count
     * ("獲得票券\n4") that is not part of what the button is, so digits are
     * dropped; the position grid tells two buttons with the same words apart.
     */
    fun deferId(tab: String, target: Target): String {
        val words = TextMatch.normalizeLoose(target.text).filterNot { it.isDigit() }.take(8)
        return "$tab|$words@${target.centerX / GRID},${target.centerY / GRID}"
    }

    /** The button's words for the user: one line, no counts. */
    /**
     * The button's words for the user. Only a line that is just a count
     * ("獲得票券\n17") is dropped; numbers inside the words stay
     * ("獲得5倍的BTC" read "獲得倍的BTC" before, 2026-11-13).
     */
    fun label(text: String): String =
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() } }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

    /** Bad ads in a row on the same button before it is deferred. */
    const val DEFER_AFTER_STREAK = 2

    fun shouldDefer(streak: Int): Boolean = streak >= DEFER_AFTER_STREAK

    private const val GRID = 80
}
