package com.rewardadguard.app.data

/** Protection level, spec section 8. */
enum class ProtectionMode {
    OFF,
    LOG_ONLY,
    BLOCK;

    companion object {
        fun fromName(name: String?): ProtectionMode =
            entries.firstOrNull { it.name == name } ?: BLOCK
    }
}

/** What the guard is allowed to do with a suspicious redirect (spec section 20). */
enum class RedirectPolicy {
    LOG_ONLY,
    BLOCK_IMMEDIATELY,
    BLOCK_AFTER_GRACE;

    companion object {
        fun fromName(name: String?): RedirectPolicy =
            entries.firstOrNull { it.name == name } ?: BLOCK_AFTER_GRACE
    }
}

/** What the close-button guard is allowed to do (spec section 24-27). */
enum class AssistAction {
    /** Only detect and log the X, never click. */
    NONE,

    /** Highlight/log the X and click it through the accessibility node. */
    ASSIST_CLICK,

    /**
     * Same as [ASSIST_CLICK] but only after the user has been idle, so
     * user-initiated closes always win.
     */
    ASSIST_WHEN_IDLE,

    /** Re-read the tree and click the X as soon as it appears (most aggressive). */
    ASSIST_TIMED_RETRY;

    companion object {
        fun fromName(name: String?): AssistAction =
            entries.firstOrNull { it.name == name } ?: ASSIST_WHEN_IDLE
    }
}

/**
 * Immutable snapshot of all user settings. Held in memory by the accessibility
 * service and refreshed by a DataStore flow, so hot paths never touch disk.
 */
data class AppSettings(
    val protectionMode: ProtectionMode = ProtectionMode.BLOCK,
    val externalRedirectProtection: Boolean = true,
    val closeButtonAssistance: Boolean = true,
    /** Assisted close control, see [AssistAction]. */
    val assistAction: AssistAction = AssistAction.ASSIST_CLICK,
    val xClickAreaMultiplier: Int = 3,
    val smartRedirectEnabled: Boolean = true,
    /** Block immediately on the first suspicious jump instead of using the grace period. */
    val redirectPolicy: RedirectPolicy = RedirectPolicy.BLOCK_AFTER_GRACE,
    /** Milliseconds the destination must stay in the foreground before blocking. */
    val blockGraceMillis: Long = 700L,
    /** Log only for apps that were not explicitly promoted to BLOCK. */
    val newAppSafeModeEnabled: Boolean = true,
    val loggingEnabled: Boolean = true,
    val maxLogEvents: Int = 10_000,
    val maxSessions: Int = 500,
    /** Send a single low-priority notification when the service connects. */
    val statusNotificationEnabled: Boolean = true,
    /** Test mode: simulate reward app -> external app transitions. */
    val testModeEnabled: Boolean = false,
    /** Return to the reward app after a blocked redirect. */
    val autoReturnEnabled: Boolean = true,
    /** Launch the reward app if Back presses were not enough. */
    val autoLaunchRewardApp: Boolean = false,
    /** Verify that the reward app really came back, instead of trusting dispatch. */
    val verifyReturn: Boolean = true,
    /** Maximum number of Back presses per return attempt (1..3). */
    val maxReturnAttempts: Int = 2
) {
    val xClickAreaScale: Float
        get() = xClickAreaMultiplier.coerceIn(1, 5).toFloat()

    /** True when redirect protection may interrupt the user right now. */
    val canBlock: Boolean
        get() = protectionMode == ProtectionMode.BLOCK && externalRedirectProtection

    /** True when the guard must stay observational. */
    val isLogOnly: Boolean
        get() = protectionMode == ProtectionMode.LOG_ONLY

    val protectionEnabled: Boolean
        get() = protectionMode != ProtectionMode.OFF
}
