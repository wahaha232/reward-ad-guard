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

    /** The app the current run works on; set by the engine when it starts. */
    @Volatile
    var targetPackage: String? = null

    /** The active window's package, read when deciding about a pause. */
    private fun foregroundNow(): String? =
        runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()

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
            when (CollectorInterruption.pauseTransition(state, pauseReason, reason)) {
                CollectorInterruption.PauseTransition.PAUSE -> {
                    state = CollectorState.PAUSED
                    pauseReason = reason
                }
                // Reaching 暫停 means touching the screen, which already paused
                // the run as user_interaction; that pause ends by itself 5 s
                // later, so the user's 暫停 never held (2026-11-12 21:59). The
                // explicit request now takes over the pause.
                CollectorInterruption.PauseTransition.UPGRADE -> pauseReason = reason
                CollectorInterruption.PauseTransition.IGNORE -> return
            }
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
    @Volatile
    private var lastEventCheckAt = 0L

    /**
     * The service calls this for every accessibility event while the
     * collector runs, and an ad delivers CONTENT_CHANGED in floods. Checking
     * the keyguard more than a few times a second adds binder calls on the
     * main thread for nothing; a real finger still pauses at once through the
     * service's own path.
     */
    fun checkInterruptionsThrottled() {
        val now = clock()
        if (now - lastEventCheckAt < EVENT_CHECK_INTERVAL_MILLIS) return
        lastEventCheckAt = now
        checkInterruptions()
    }

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
                val front = foregroundNow()
                // The user went to another app while the run was paused by
                // their finger: they are using the phone. Make the pause a
                // real one (only 繼續 ends it) instead of pulling them back
                // to the reward app five seconds later (2026-11-13).
                if (CollectorInterruption.userLeftApp(reason, front, targetPackage)) {
                    synchronized(overlayLock) {
                        if (state == CollectorState.PAUSED) {
                            pauseReason = CollectorInterruption.REASON_USER_LEFT
                            publishLocked()
                        }
                    }
                    log("COLLECTOR_PAUSE", "SUCCESS", "reason=${CollectorInterruption.REASON_USER_LEFT} front=$front")
                    return
                }
                if (!CollectorInterruption.canAutoResume(
                        pauseReason = reason,
                        keyguardLocked = isKeyguardLocked(),
                        lastUserInteractionAt = userInteractionAt(),
                        now = clock(),
                        targetInFront = targetPackage == null || front == targetPackage
                    )
                ) return
                resume()
                log("COLLECTOR_AUTO_RESUME", "SUCCESS", "reason=$reason cleared")
            }

            CollectorState.IDLE -> Unit
        }
    }

    private companion object {
        const val EVENT_CHECK_INTERVAL_MILLIS = 300L
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

    /** The user switched to another app during a finger pause; like a 暫停 press. */
    const val REASON_USER_LEFT = "user_left_app"

    /**
     * A finger pause during which the user opened another app (not the
     * status-bar shade, not an unknown window) becomes a manual pause.
     */
    fun userLeftApp(pauseReason: String?, foreground: String?, target: String?): Boolean {
        if (pauseReason != REASON_USER_INTERACTION || target == null) return false
        val front = foreground?.trim().orEmpty()
        if (front.isEmpty() || front == target || front == "com.android.systemui") return false
        return true
    }

    enum class PauseTransition { PAUSE, UPGRADE, IGNORE }

    /**
     * What a pause request does in the current state: a running collector
     * pauses; an automatic pause becomes the user's own pause when the user
     * asks for one (so it no longer resumes by itself); anything else is
     * ignored.
     */
    fun pauseTransition(state: CollectorState, currentReason: String?, requested: String): PauseTransition =
        when {
            state == CollectorState.RUNNING -> PauseTransition.PAUSE
            state == CollectorState.PAUSED && requested == REASON_USER_REQUESTED &&
                currentReason != REASON_USER_REQUESTED -> PauseTransition.UPGRADE
            else -> PauseTransition.IGNORE
        }

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
        now: Long,
        targetInFront: Boolean = true
    ): Boolean {
        if (pauseReason != REASON_KEYGUARD && pauseReason != REASON_USER_INTERACTION) return false
        if (keyguardLocked) return false
        // Only resume where the run left off: with another app (or the home
        // screen) in front the user is doing something else.
        if (!targetInFront) return false
        return lastUserInteractionAt <= 0L || now - lastUserInteractionAt >= AUTO_RESUME_IDLE_MILLIS
    }
}
