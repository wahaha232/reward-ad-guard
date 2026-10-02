package com.rewardadguard.app.detector

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Answers one question: which package is in the foreground right now?
 *
 * Strategy (no polling):
 *  1. `TYPE_WINDOW_STATE_CHANGED` carries the package name for the window that
 *     just became active, which is the cheapest and most reliable signal.
 *  2. If that is missing or is our own app / the system UI, the active window is
 *     taken from [AccessibilityService.windows] (our own IME and system overlays
 *     are filtered out).
 *
 * The detector keeps the previous package so that transitions can be logged as
 * `SOURCE -> DESTINATION` (spec section 12).
 */
class ForegroundAppDetector(private val context: Context) {

    data class ForegroundChange(
        val previousPackage: String?,
        val currentPackage: String?,
        /** Where the package name came from, for the log. */
        val detectionMethod: String
    ) {
        val changed: Boolean
            get() = currentPackage != null && currentPackage != previousPackage
    }

    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var previousPackage: String? = null
        private set

    /** Resolves the package for [event] with a fallback to the active window. */
    fun resolve(
        eventPackage: String?,
        activeWindowPackage: String?
    ): ForegroundChange {
        val fromEvent = eventPackage?.takeIf { it.isNotBlank() && it != SYSTEM_UI }
        val resolved = fromEvent
            ?: activeWindowPackage?.takeIf { it.isNotBlank() && it != SYSTEM_UI }
            ?: currentPackage

        val method = when {
            fromEvent != null -> "EVENT_PACKAGE"
            activeWindowPackage != null -> "ACTIVE_WINDOW"
            else -> "CACHED_PACKAGE"
        }

        val previous = currentPackage
        return ForegroundChange(
            previousPackage = previous,
            currentPackage = resolved,
            detectionMethod = method
        ).also {
            if (it.changed) {
                previousPackage = previous
                currentPackage = resolved
            }
        }
    }

    /** Extracts the package of the currently focused window, if any. */
    fun activeWindowPackage(windows: List<AccessibilityWindowInfo>?): String? {
        if (windows.isNullOrEmpty()) return null
        val candidates = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.isActive || it.isFocused }
        val fallback = windows.asSequence().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = (candidates.firstOrNull() ?: fallback.firstOrNull()) ?: return null
        return try {
            window.root?.packageName?.toString()
        } catch (t: Throwable) {
            null
        } finally {
            @Suppress("DEPRECATION")
            runCatching { window.recycle() }
        }
    }

    fun isFrom(event: AccessibilityEvent): Boolean =
        event.packageName?.toString() == context.packageName

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
