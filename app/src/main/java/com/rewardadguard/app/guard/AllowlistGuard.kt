package com.rewardadguard.app.guard

import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.RewardAppStore
import com.rewardadguard.app.session.SessionManager

/**
 * Final safety net in front of every guard action (spec section 31).
 *
 * It answers one question: "is the guard allowed to act at all right now?"
 *
 * Reasons the guard must stay passive:
 *  - the app was disabled by the user,
 *  - the foreground app is not a monitored reward app,
 *  - the system UI / launcher / an IME owns the screen,
 *  - the user explicitly told us to ignore this package.
 */
class AllowlistGuard(
    private val rewardAppStore: RewardAppStore,
    private val logger: EventLogger,
    private val sessionManager: SessionManager
) {

    /** Protection scopes, in the order they are consulted. */
    enum class Scope {
        /** The source app is monitored and the session belongs to it. */
        MONITORED_SOURCE,

        /** Foreground app is not monitored -> passive. */
        NOT_MONITORED,

        /** The system UI, launcher or an input method -> passive. */
        SYSTEM_SURFACE,

        /** The user's own package ignore list -> passive. */
        IGNORED_PACKAGE,

        /** Protection disabled in settings -> passive. */
        DISABLED
    }

    fun evaluate(
        foregroundPackage: String?,
        protectionEnabled: Boolean,
        sourcePackage: String? = null
    ): Scope {
        if (!protectionEnabled) return Scope.DISABLED
        val pkg = foregroundPackage ?: return Scope.NOT_MONITORED
        if (isSystemSurface(pkg)) return Scope.SYSTEM_SURFACE
        if (rewardAppStore.isIgnored(pkg)) return Scope.IGNORED_PACKAGE
        if (sourcePackage != null && pkg == sourcePackage) return Scope.MONITORED_SOURCE
        return if (rewardAppStore.isMonitored(pkg)) Scope.MONITORED_SOURCE else Scope.NOT_MONITORED
    }

    fun canAct(scope: Scope): Boolean = scope == Scope.MONITORED_SOURCE

    /**
     * Logs a blocked-by-policy decision once, so the log explains *why* nothing
     * happened even though an ad was on screen.
     */
    /** Logs a blocked-by-policy decision once, so the log explains *why* nothing
     * happened even though an ad was on screen.
     */
    fun logPassive(scope: Scope, foregroundPackage: String?, reason: String) {
        logger.log(
            eventType = EventType.EXTRA_INFO,
            sessionId = sessionManager.currentSessionId ?: EventLogger.NO_SESSION,
            sourcePackage = foregroundPackage,
            detectionMethod = scope.name,
            action = "PASSIVE",
            result = "SKIPPED",
            message = reason
        )
    }

    companion object {
        /** Surfaces that must never be touched. */
        private val SYSTEM_SURFACES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        private val LAUNCHER_HINTS = listOf(
            "launcher", "home", "oneplus.launcher"
        )

        private val IME_HINTS = listOf(
            "inputmethod", "keyboard", "gboard", "swiftkey", "sogou", "ime"
        )

        /**
         * True for the status bar, launchers, IMEs and system dialogs.
         *
         * A launcher is detected by package-name heuristics rather than by
         * resolving the HOME intent, so that a user-chosen third-party launcher
         * is also recognised.
         */
        fun isSystemSurface(packageName: String): Boolean {
            if (packageName in SYSTEM_SURFACES) return true
            val lower = packageName.lowercase()
            if (LAUNCHER_HINTS.any { lower.contains(it) }) return true
            if (IME_HINTS.any { lower.contains(it) }) return true
            return false
        }
    }
}
