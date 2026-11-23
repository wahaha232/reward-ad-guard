package com.rewardadguard.app.collector

/**
 * Who put a finger on the screen while a run holds it (2026-11-23 review).
 *
 * The wake overlay sees every finger-down on the phone. Our own gestures are
 * injected (deviceId -1 / 0) and marked as own clicks just before dispatch; a
 * physical touchscreen has a real device id. A press on the floating panel is
 * the user commanding the run, not leaving it.
 */
enum class TouchOrigin {
    USER, OWN_GESTURE, PANEL;

    companion object {
        fun classify(deviceId: Int, ownGestureInFlight: Boolean, onFloatingPanel: Boolean): TouchOrigin = when {
            onFloatingPanel -> PANEL
            // A physical touchscreen is always the user, even mid-gesture.
            deviceId > 0 -> USER
            // Injected and inside our own gesture's window: ours.
            ownGestureInFlight -> OWN_GESTURE
            // Injected by something else (adb input, another service): treat
            // as a person, the safe side.
            else -> USER
        }
    }
}
