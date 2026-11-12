package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.manager.EventLogger

/**
 * The runtime conditions a collection run needs, in one place.
 *
 * Three requirements from the plan meet here:
 *  1. **Keep the screen awake.** A 1x1 fully transparent
 *     `TYPE_ACCESSIBILITY_OVERLAY` with `keepScreenOn` does it without the
 *     "display over other apps" permission. It is not touchable, so it cannot
 *     interfere with the ad underneath.
 *  2. **Never fight the lock screen.** `KeyguardManager.isKeyguardLocked` is
 *     checked between wait polls; the collector pauses and logs, and never
 *     attempts an unlock (an accessibility service cannot, and should not).
 *  3. **Yield to a real finger.** The guard already timestamps genuine touches
 *     (`TYPE_TOUCH_INTERACTION_START` / non-self `TYPE_VIEW_CLICKED`), so the
 *     same timestamp drives the collector's pause.
 *
 * The state transitions are deliberately simple and owner-driven: the engine
 * (phase 2) calls [start]/[pause]/[resume]/[stop]; the guard only reads
 * [state] through its own provider.
 */
class CollectorEnvironment(
    private val service: AccessibilityService,
    private val logger: EventLogger,
    private val userInteractionAt: () -> Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val keyguardLocked: () -> Boolean = {
        runCatching {
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)
    },
    /**
     * Called once when a run starts, after the state is RUNNING. The service
     * uses it to cancel queued returns: a Back that was already scheduled would
     * otherwise land after the collector took over the screen.
     */
    private val onStarted: () -> Unit = {}
) {

    @Volatile
    var state: CollectorState = CollectorState.IDLE
        private set

    private val overlayLock = Any()
    private var overlay: View? = null

    /** Starts a run: shows the wake overlay and takes the screen from the guard. */
    fun start() {
        synchronized(overlayLock) {
            if (state != CollectorState.IDLE) return
            showOverlayLocked()
            state = CollectorState.RUNNING
        }
        runCatching(onStarted)
        log("COLLECTOR_START", "SUCCESS", "collector owns the screen")
    }

    /** Pauses without dropping the wake lock; the guard takes over again. */
    fun pause(reason: String) {
        synchronized(overlayLock) {
            if (state != CollectorState.RUNNING) return
            state = CollectorState.PAUSED
        }
        log("COLLECTOR_PAUSE", "SUCCESS", "reason=$reason")
    }

    fun resume() {
        synchronized(overlayLock) {
            if (state != CollectorState.PAUSED) return
            showOverlayLocked()
            state = CollectorState.RUNNING
        }
        log("COLLECTOR_RESUME", "SUCCESS", "collector owns the screen again")
    }

    /** Ends the run and removes the overlay. */
    fun stop() {
        synchronized(overlayLock) {
            if (state == CollectorState.IDLE) return
            hideOverlayLocked()
            state = CollectorState.IDLE
        }
        log("COLLECTOR_STOP", "SUCCESS", "collector released the screen")
    }

    /** Public view of the lock state for the run policy (no app may start while locked). */
    fun isKeyguardLocked(): Boolean = runCatching { keyguardLocked() }.getOrDefault(true)

    /**
     * Pauses when the phone is locked or a real finger touched the screen.
     * Called from [Actor.waitFor] between polls; cheap (two reads).
     */
    fun checkInterruptions() {
        if (state != CollectorState.RUNNING) return
        val reason = CollectorInterruption.reason(
            keyguardLocked = keyguardLocked(),
            lastUserInteractionAt = userInteractionAt(),
            now = clock()
        ) ?: return
        pause(reason)
    }

    private fun showOverlayLocked() {
        if (overlay != null) return
        val manager = runCatching { service.getSystemService(WindowManager::class.java) }.getOrNull() ?: return
        val view = View(service).apply { keepScreenOn = true }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { manager.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure {
                log("OVERLAY", "FAILED", "keepScreenOn overlay rejected: ${it.message}")
            }
    }

    private fun hideOverlayLocked() {
        val view = overlay ?: return
        overlay = null
        runCatching {
            service.getSystemService(WindowManager::class.java)?.removeView(view)
        }
    }

    private fun log(action: String, result: String, message: String) {
        logger.log(
            eventType = EventType.COLLECTOR,
            sessionId = null,
            detectionMethod = "CollectorEnvironment",
            action = action,
            result = result,
            message = message
        )
    }
}

/**
 * Decides whether the collector must pause itself.
 *
 * Both triggers are hard requirements from the plan: the phone cannot be
 * unlocked by an accessibility service, and fighting a real finger for the
 * screen is how the PC script and the guard used to click at the same time.
 * Extracted as a pure function so both thresholds have tests.
 */
object CollectorInterruption {

    /** Same grace as the guard's own `INTERACTION_GRACE_MILLIS`. */
    const val USER_INTERACTION_GRACE_MILLIS = 1_200L

    /**
     * A reason string when the collector must pause, otherwise null.
     *
     * @param keyguardLocked `KeyguardManager.isKeyguardLocked()`.
     * @param lastUserInteractionAt last real-finger event, 0 when none.
     */
    fun reason(keyguardLocked: Boolean, lastUserInteractionAt: Long, now: Long): String? = when {
        keyguardLocked -> "keyguard_locked"
        lastUserInteractionAt > 0 && now - lastUserInteractionAt < USER_INTERACTION_GRACE_MILLIS ->
            "user_interaction"
        else -> null
    }
}
