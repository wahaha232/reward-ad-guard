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

    /**
     * Video motion counts as progress only this long into an ad, so an
     * animated playable that never ends is still left in the end.
     */
    const val MOTION_PROGRESS_MAX_MILLIS = 90_000L

    private const val SIG_GRID = 12

    /** A coarse gray thumbnail of the screen (12x12 samples). */
    fun signature(bitmap: android.graphics.Bitmap): IntArray? = runCatching {
        val w = bitmap.width
        val h = bitmap.height
        IntArray(SIG_GRID * SIG_GRID) { i ->
            val x = ((i % SIG_GRID) + 0.5) * w / SIG_GRID
            val y = ((i / SIG_GRID) + 0.5) * h / SIG_GRID
            val c = bitmap.getPixel(x.toInt().coerceIn(0, w - 1), y.toInt().coerceIn(0, h - 1))
            ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        }
    }.getOrNull()

    /**
     * True when a fair part of the screen changed between two thumbnails:
     * a playing video. A blinking cursor or a ticking clock moves a sample or
     * two, which is not enough.
     */
    fun imageMoved(previous: IntArray?, current: IntArray?): Boolean {
        if (previous == null || current == null || previous.size != current.size) return false
        val changed = previous.indices.count { kotlin.math.abs(previous[it] - current[it]) > 24 }
        return changed >= previous.size / 10
    }

    /** Bad ads in a row on the same button before it is deferred. */
    const val DEFER_AFTER_STREAK = 2

    fun shouldDefer(streak: Int): Boolean = streak >= DEFER_AFTER_STREAK

    private const val GRID = 80
}
