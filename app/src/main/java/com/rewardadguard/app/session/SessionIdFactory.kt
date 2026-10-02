package com.rewardadguard.app.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds identifiers of the form `SESSION_yyyyMMdd_HHmmss_XXX` (spec section 14),
 * e.g. `SESSION_20261002_093015_001`.
 */
object SessionIdFactory {

    private val FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Monotonic counter to keep the 3-digit suffix unique inside one second. */
    private var lastSecond: String = ""
    private var counter: Int = 0

    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): String {
        val stamp = FORMAT.format(Date(now))
        counter = if (stamp == lastSecond) (counter + 1) % 1000 else 1
        lastSecond = stamp
        return "SESSION_${stamp}_%03d".format(counter)
    }
}
