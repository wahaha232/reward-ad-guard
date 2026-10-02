package com.rewardadguard.app.data

/**
 * Log category. Mirrors the categories required by the spec (section 30).
 * Stored as a plain string in Room for forward compatibility.
 */
enum class EventCategory {
    APP,
    SESSION,
    AD,
    CLOSE,
    JUMP,
    BLOCK,
    RETURN,
    ERROR,
    EXTRA, 
    SYSTEM;

    companion object {
        fun fromName(name: String?): EventCategory =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Machine readable event type. Kept as an enum so that both the logger and the
 * tests share one single source of truth.
 */
enum class EventType {
    SESSION_START,
    SESSION_STATE,
    SESSION_END,
    SESSION_SUMMARY,
    APP_CHANGE,
    POSSIBLE_AD_SESSION,
    AD_SESSION_ACTIVE,
    AD_SESSION_END,
    POSSIBLE_REDIRECT,
    REDIRECT_DETECTED,
    REDIRECT_RISK,
    EXTERNAL_APP,
    BROWSER,
    STORE,
    DEEP_LINK,
    GAME,
    RETURNED_TO_SOURCE,
    BLOCK,
    BLOCK_SKIPPED,
    RETURN,
    RETURN_DECISION,
    RETURN_FAILED,
    CLOSE_DETECT,
    CLOSE_BOUNDS,
    CLOSE_RESULT,
    CLOSE_ACTION,
    CLOSE_MISS,
    ERROR,
    SERVICE_CONNECTED,
    SERVICE_DISCONNECTED,
    SERVICE_INTERRUPTED,
    SETTINGS_CHANGED,
    TEST_REDIRECT_SIMULATED,
    /** Informational entry that explains why the guard stayed passive. */
    EXTRA_INFO,
    LOG_CLEARED,
    LOG_EXPORTED;

    fun category(): EventCategory = when (this) {
        SESSION_START, SESSION_STATE, SESSION_END, SESSION_SUMMARY -> EventCategory.SESSION
        APP_CHANGE -> EventCategory.APP
        POSSIBLE_AD_SESSION, AD_SESSION_ACTIVE, AD_SESSION_END -> EventCategory.AD
        CLOSE_DETECT, CLOSE_BOUNDS, CLOSE_RESULT, CLOSE_ACTION, CLOSE_MISS -> EventCategory.CLOSE
        POSSIBLE_REDIRECT, REDIRECT_DETECTED, REDIRECT_RISK, EXTERNAL_APP, BROWSER, STORE,
        DEEP_LINK, GAME, RETURNED_TO_SOURCE -> EventCategory.JUMP
        BLOCK, BLOCK_SKIPPED -> EventCategory.BLOCK
        RETURN, RETURN_DECISION, RETURN_FAILED -> EventCategory.RETURN
        ERROR -> EventCategory.ERROR
        EXTRA_INFO -> EventCategory.EXTRA
        SERVICE_CONNECTED, SERVICE_DISCONNECTED, SERVICE_INTERRUPTED, SETTINGS_CHANGED,
        TEST_REDIRECT_SIMULATED, LOG_CLEARED, LOG_EXPORTED -> EventCategory.SYSTEM
    }
}
