package com.rewardadguard.app.session

/** Session state machine, spec section 13. */
enum class SessionState {
    IDLE,
    REWARD_APP_ACTIVE,
    AD_SESSION_ACTIVE,
    EXTERNAL_APP_DETECTED,
    BLOCKING,
    RETURNING,
    COMPLETED,
    ERROR;

    /** States in which redirect protection should be armed. */
    fun isActive(): Boolean = this != IDLE && this != COMPLETED && this != ERROR
}

/** Reason a session was closed; stored with the session record. */
enum class SessionEndReason {
    LEFT_REWARD_APP,
    TIMEOUT,
    SERVICE_DESTROYED,
    REWARD_APP_DISABLED,
    APP_CRASH,
    MANUAL
}
