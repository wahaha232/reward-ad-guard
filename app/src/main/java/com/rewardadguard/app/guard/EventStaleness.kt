package com.rewardadguard.app.guard

/**
 * Tells a live window event from one that sat in a backlog.
 *
 * Review 2026-11-02: on the field device the guard received 30-60 s old window
 * events in one burst (an ad that started at 07:57:44 was only seen at
 * 07:58:48). Acting on them meant blocking a jump that was long over and
 * pressing Back in whatever app was in front *now* - the user's own LINE was
 * closed that way. A stale event may still update what the guard knows, but it
 * must never make it act.
 *
 * Pure so the threshold is unit tested; the service supplies
 * `AccessibilityEvent.eventTime` and `SystemClock.uptimeMillis()`, which share
 * the same uptime clock.
 */
object EventStaleness {

    /**
     * Older than this, an event no longer describes the screen the user sees.
     *
     * A normal event reaches the service within a few hundred milliseconds
     * (notificationTimeout is 120 ms); 2 s leaves room for a busy device while
     * staying far below the 30-60 s backlog that caused the review case.
     */
    const val STALE_EVENT_MILLIS = 2_000L

    /**
     * Age of an event in milliseconds.
     *
     * An event time of 0 or less means the event was synthesised without one
     * (the debug entry point builds events with `obtain()`); it is treated as
     * brand new rather than as infinitely old. A clock that appears to run
     * backwards also yields 0.
     */
    fun ageMillis(eventUptimeMillis: Long, nowUptimeMillis: Long): Long =
        if (eventUptimeMillis <= 0L) 0L else (nowUptimeMillis - eventUptimeMillis).coerceAtLeast(0L)

    fun isStale(ageMillis: Long): Boolean = ageMillis > STALE_EVENT_MILLIS
}
