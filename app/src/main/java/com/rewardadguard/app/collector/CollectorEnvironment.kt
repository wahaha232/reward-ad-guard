package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.manager.EventLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** State plus the reason of the current pause, for the UI and notification. */
    data class Status(val state: CollectorState, val pauseReason: String? = null)

    @Volatile
    var state: CollectorState = CollectorState.IDLE
        private set

    /** Why the collector is paused (`keyguard_locked`, `user_interaction`, `user_requested`), else null. */
    @Volatile
    var pauseReason: String? = null
        private set

    /**
     * Set by [requestStop] and kept until [beginSequence]: a stop pressed while
     * a multi-app run is between two apps must still stop the next one, so
     * [start] refuses while it is set (review P0-1 follow-up).
     */
    @Volatile
    var stopRequested: Boolean = false
        private set

    private val _status = MutableStateFlow(Status(CollectorState.IDLE))

    /** Every state change, so the UI shows a pause the collector took by itself. */
    val status: StateFlow<Status> = _status.asStateFlow()

    private val overlayLock = Any()
    private var overlay: View? = null
    private var overlayPending = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Clears a previous stop request; call once before a run of one or more apps. */
    fun beginSequence() {
        synchronized(overlayLock) { stopRequested = false }
    }

    /** Stops the current app and every app after it in the same run. */
    fun requestStop() {
        synchronized(overlayLock) { stopRequested = true }
        stop()
    }

    /** Starts a run: shows the wake overlay and takes the screen from the guard. */
    fun start() {
        synchronized(overlayLock) {
            if (state != CollectorState.IDLE || stopRequested) return
            showOverlayLocked()
            state = CollectorState.RUNNING
            publishLocked()
        }
        runCatching(onStarted)
        log("COLLECTOR_START", "SUCCESS", "collector owns the screen")
    }

    /** Pauses without dropping the wake lock; the guard takes over again. */
    fun pause(reason: String) {
        synchronized(overlayLock) {
            if (state != CollectorState.RUNNING) return
            state = CollectorState.PAUSED
            pauseReason = reason
            publishLocked()
        }
        log("COLLECTOR_PAUSE", "SUCCESS", "reason=$reason")
    }

    fun resume() {
        synchronized(overlayLock) {
            if (state != CollectorState.PAUSED) return
            showOverlayLocked()
            state = CollectorState.RUNNING
            pauseReason = null
            publishLocked()
        }
        log("COLLECTOR_RESUME", "SUCCESS", "collector owns the screen again")
    }

    /** Ends the run and removes the overlay. */
    fun stop() {
        synchronized(overlayLock) {
            if (state == CollectorState.IDLE) return
            hideOverlayLocked()
            state = CollectorState.IDLE
            pauseReason = null
            publishLocked()
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
        when (state) {
            CollectorState.RUNNING -> {
                val reason = CollectorInterruption.reason(
                    keyguardLocked = keyguardLocked(),
                    lastUserInteractionAt = userInteractionAt(),
                    now = clock()
                ) ?: return
                pause(reason)
            }

            CollectorState.PAUSED -> {
                val reason = pauseReason
                if (!CollectorInterruption.canAutoResume(
                        pauseReason = reason,
                        keyguardLocked = isKeyguardLocked(),
                        lastUserInteractionAt = userInteractionAt(),
                        now = clock()
                    )
                ) return
                resume()
                log("COLLECTOR_AUTO_RESUME", "SUCCESS", "reason=$reason cleared")
            }

            CollectorState.IDLE -> Unit
        }
    }

    private fun publishLocked() {
        _status.value = Status(state, pauseReason)
    }

    /**
     * Shows the wake overlay on the main thread.
     *
     * A view can only be added from a thread with a Looper. The 自動收取 tab
     * starts runs from CollectorController's Dispatchers.Default scope, so
     * addView failed every time ("Can't create handler inside thread",
     * 2026-11-12 21:09) and the screen timed out and locked mid-run. The adb
     * entry happened to start on the main thread, which hid the bug.
     */
    private fun showOverlayLocked() {
        if (overlay != null || overlayPending) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addOverlayNow()
        } else {
            overlayPending = true
            mainHandler.post {
                synchronized(overlayLock) {
                    overlayPending = false
                    // The run may have ended before the main thread got here.
                    if (state != CollectorState.IDLE && overlay == null) addOverlayNow()
                }
            }
        }
    }

    private fun addOverlayNow() {
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
            .onSuccess {
                overlay = view
                log("OVERLAY", "SUCCESS", "keepScreenOn overlay shown")
            }
            .onFailure {
                log("OVERLAY", "FAILED", "keepScreenOn overlay rejected: ${it.message}")
            }
    }

    private fun hideOverlayLocked() {
        val view = overlay ?: return
        overlay = null
        val remove = {
            runCatching { service.getSystemService(WindowManager::class.java)?.removeView(view) }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove() else mainHandler.post(remove)
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
        keyguardLocked -> REASON_KEYGUARD
        lastUserInteractionAt > 0 && now - lastUserInteractionAt < USER_INTERACTION_GRACE_MILLIS ->
            REASON_USER_INTERACTION
        else -> null
    }

    const val REASON_KEYGUARD = "keyguard_locked"
    const val REASON_USER_INTERACTION = "user_interaction"
    const val REASON_USER_REQUESTED = "user_requested"

    /** Quiet time after the last real touch before an automatic pause ends. */
    const val AUTO_RESUME_IDLE_MILLIS = 5_000L

    /**
     * Whether a pause the collector took by itself may end (review P1-3).
     *
     * Only the automatic reasons qualify: a pause the user asked for ends only
     * when the user presses 繼續. The phone must be unlocked and nobody may
     * have touched it for [AUTO_RESUME_IDLE_MILLIS].
     */
    fun canAutoResume(
        pauseReason: String?,
        keyguardLocked: Boolean,
        lastUserInteractionAt: Long,
        now: Long
    ): Boolean {
        if (pauseReason != REASON_KEYGUARD && pauseReason != REASON_USER_INTERACTION) return false
        if (keyguardLocked) return false
        return lastUserInteractionAt <= 0L || now - lastUserInteractionAt >= AUTO_RESUME_IDLE_MILLIS
    }
}
