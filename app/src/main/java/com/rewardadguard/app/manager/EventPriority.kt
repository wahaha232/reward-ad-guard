package com.rewardadguard.app.manager

import com.rewardadguard.app.data.EventType

/**
 * Which rows are kept when the event queue is crowded (2026-11-23 review).
 * Errors, settings, service state, blocks and failed returns, and each
 * collection run's result outlive routine detection rows.
 */
object EventPriority {

    private val IMPORTANT = setOf(
        EventType.ERROR,
        EventType.SETTINGS_CHANGED,
        EventType.SERVICE_CONNECTED,
        EventType.SERVICE_DISCONNECTED,
        EventType.SERVICE_INTERRUPTED,
        EventType.BLOCK,
        EventType.REDIRECT_DETECTED,
        EventType.RETURN_FAILED,
        EventType.SESSION_END,
        EventType.SESSION_SUMMARY,
        EventType.LOG_CLEARED,
        EventType.LOG_EXPORTED
    )

    /** Collector rows that carry a run's outcome. */
    private val IMPORTANT_COLLECTOR_ACTIONS = setOf("RUN_END", "COLLECTOR_START", "COLLECTOR_STOP", "COLLECTOR_PAUSE")

    fun isImportant(type: EventType, action: String?): Boolean =
        type in IMPORTANT || (type == EventType.COLLECTOR && action in IMPORTANT_COLLECTOR_ACTIONS)

    /**
     * Whether a row is queued when [pending] rows wait: below [softLimit]
     * everything, up to [capacity] only important rows.
     */
    fun accept(pending: Int, important: Boolean, softLimit: Int, capacity: Int): Boolean =
        pending < capacity && (important || pending < softLimit)
}
