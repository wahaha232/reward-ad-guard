package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.guard.SelfClickTracker
import com.rewardadguard.app.manager.EventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The collector's only way to touch the screen.
 *
 * Every action goes through one class on purpose: the guard has to be able to
 * say "the collector is driving", every click has to be marked as our own (so
 * [SelfClickTracker] stops the guard mistaking it for a finger), and every
 * action has to leave a row in the event log. pc-collector had the same
 * separation (`adb.py`), which is what made its runs auditable.
 */
class Actor(
    private val service: AccessibilityService,
    private val logger: EventLogger,
    private val selfClicks: SelfClickTracker,
    private val sessionId: () -> String?,
    private val stateProvider: () -> CollectorState,
    /**
     * Called between wait polls to let the runtime pause the collector (lock
     * screen, real touch). A lambda instead of a [CollectorEnvironment] so this
     * class stays independent of the environment and usable from a JVM test.
     */
    private val interruptionCheck: (() -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    /**
     * The reward app this run works on. Required, like [foregroundNow]: no
     * constructor default may switch the foreground gate off (review 2026-11-23).
     */
    private val targetPackage: String,
    /** The active window's package, read fresh at the moment of acting. */
    private val foregroundNow: () -> String?,
    /** Launchers, the shade, settings: Back is never pressed on them. */
    private val protectedSurface: (String) -> Boolean = { false }
) {

    @Volatile
    private var backAuthorizedPackage: String? = null

    @Volatile
    private var backAuthorizedUntil = 0L

    /**
     * The run judged [packageName] (not the target) to be its ad's redirect:
     * the next Back may be pressed on it, for [BACK_AUTH_MILLIS] only. Any
     * other foreign app is refused (2026-11-23 review).
     */
    fun authorizeBack(packageName: String) {
        backAuthorizedPackage = packageName
        backAuthorizedUntil = monotonicClock() + BACK_AUTH_MILLIS
    }

    @Volatile
    private var adHostPackage: String? = null

    @Volatile
    private var adHostUntil = 0L

    /**
     * [packageName] is drawing this run's ad ([RemoteAdHost]): taps and Back
     * may go to it for [AD_HOST_AUTH_MILLIS]. The ad loop renews this on every
     * frame it reads, so it lapses on its own once the ad is gone.
     */
    fun authorizeAdHost(packageName: String) {
        adHostPackage = packageName
        adHostUntil = monotonicClock() + AD_HOST_AUTH_MILLIS
    }

    private fun currentAdHost(): String? =
        adHostPackage?.takeIf { monotonicClock() <= adHostUntil }

    private fun currentBackAuthorization(): String? =
        backAuthorizedPackage?.takeIf { monotonicClock() <= backAuthorizedUntil }

    /** Wall time of the last action, for comparing with the user's last touch; 0 before any. */
    @Volatile
    var lastActionWallAt: Long = 0L
        private set

    @Volatile
    private var lastHomeAt = -1L

    /** True when the run itself pressed Home within [withinMillis]. */
    fun pressedHomeWithin(withinMillis: Long): Boolean =
        lastHomeAt >= 0L && monotonicClock() - lastHomeAt <= withinMillis

    /**
     * Taps (x, y).
     *
     * The tap is refused unless the collector is [CollectorState.RUNNING], so a
     * stale coroutine can never click after a pause or stop, and the self-click
     * marker is set *before* dispatch because the system can deliver the
     * resulting `TYPE_VIEW_CLICKED` before the gesture callback returns.
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        if (!mayAct("TAP")) return false
        if (!foregroundAllows(ActionGate.Action.TAP, "TAP")) return false
        if (FloatingControls.covers(x, y)) {
            // Never press our own 暫停/停止 panel by accident.
            log("TAP", "SKIPPED", "x=$x y=$y is on the floating controls")
            return false
        }
        selfClicks.markOwnClick(clock())
        markAction()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val dispatched = dispatch(path, TAP_DURATION_MILLIS)
        log(
            action = "TAP",
            result = if (dispatched) "SUCCESS" else "FAILED",
            message = "x=$x y=$y"
        )
        return dispatched
    }

    /**
     * Drags from (x1, y1) to (x2, y2) to scroll the reward app (AHEALTH's
     * home goes on below the first screen, 2026-11-14). Same gates as [tap].
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Long = SWIPE_DURATION_MILLIS): Boolean {
        if (!mayAct("SWIPE")) return false
        if (!foregroundAllows(ActionGate.Action.TAP, "SWIPE")) return false
        if (FloatingControls.covers(x1, y1) || FloatingControls.covers(x2, y2)) {
            log("SWIPE", "SKIPPED", "path touches the floating controls")
            return false
        }
        selfClicks.markOwnClick(clock())
        markAction()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val dispatched = dispatch(path, durationMillis)
        log("SWIPE", if (dispatched) "SUCCESS" else "FAILED", "($x1,$y1)->($x2,$y2)")
        return dispatched
    }

    // While the screen is dimmed, the black overlay steps aside for our tap.
    // OwnGesture marks the real dispatch (not the 1.5 s self-click window), so
    // a touch injected by something else afterwards is not taken for ours.
    private suspend fun dispatch(path: Path, durationMillis: Long): Boolean {
        OwnGesture.begin()
        return try {
            ScreenDimmer.passThrough { dispatchNow(path, durationMillis) }
        } finally {
            OwnGesture.end()
        }
    }

    private suspend fun dispatchNow(path: Path, durationMillis: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()
        val resumed = AtomicBoolean(false)
        val dispatched = try {
            suspendCancellableCoroutine { continuation ->
                val started = runCatching {
                    service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (resumed.compareAndSet(false, true)) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (resumed.compareAndSet(false, true)) continuation.resume(false)
                            }
                        },
                        null
                    )
                }.getOrDefault(false)
                if (!started && resumed.compareAndSet(false, true)) continuation.resume(false)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
        return dispatched
    }

    @Volatile
    private var lastActionAt = -1L

    /** Time since the last tap, Back, Home or launch this actor performed; -1 before any. */
    fun sinceLastActionMillis(): Long =
        if (lastActionAt < 0L) -1L else monotonicClock() - lastActionAt

    private fun markAction() {
        lastActionAt = monotonicClock()
        lastActionWallAt = clock()
    }

    suspend fun back(): Boolean = guardAction("BACK", AccessibilityService.GLOBAL_ACTION_BACK)

    suspend fun home(): Boolean = guardAction("HOME", AccessibilityService.GLOBAL_ACTION_HOME)

    /**
     * Starts [packageName] the same way the guard's return controller does:
     * a launcher intent from a background accessibility service, with
     * NEW_TASK so it works without a foreground activity.
     */
    suspend fun launch(packageName: String, fresh: Boolean = false): Boolean {
        if (!mayAct("LAUNCH")) return false
        val ok = withContext(Dispatchers.Main) {
            try {
                val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    false
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    // fresh: clear the app's task, so an ad activity stacked
                    // on top of it (one Back cannot leave) is gone and the
                    // app opens on its own first screen.
                    if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    markAction()
                    service.startActivity(intent)
                    true
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
        }
        log(
            action = "LAUNCH",
            result = if (ok) "SUCCESS" else "FAILED",
            message = "package=$packageName"
        )
        return ok
    }

    /**
     * Waits for [condition] while respecting pause and stop.
     *
     * Time spent [CollectorState.PAUSED] does not count towards [timeoutMillis]
     * (pc-collector's `paused_seconds` rule): a user who locks the phone for a
     * minute must not make the 10-minute ad timeout expire. Reaching
     * [CollectorState.IDLE] ends the wait at once - "stop" must be immediate,
     * not after the timeout. Between checks, [interruptionCheck] lets the
     * runtime pause the collector when the keyguard comes up or a real finger
     * touches the screen.
     */
    suspend fun waitFor(timeoutMillis: Long, condition: suspend () -> Boolean): Boolean {
        val budget = ActiveTimeBudget(timeoutMillis)
        budget.start(monotonicClock())
        while (true) {
            interruptionCheck?.invoke()

            val state = stateProvider()
            if (state == CollectorState.IDLE) {
                log("WAIT", "SKIPPED", "collector ended")
                return false
            }
            budget.advance(monotonicClock(), paused = state == CollectorState.PAUSED)
            if (state == CollectorState.PAUSED) {
                delay(pollMillis)
                continue
            }
            if (budget.isExpired()) {
                log("WAIT", "FAILED", "timeout after ${timeoutMillis}ms active (paused=${budget.paused}ms)")
                return false
            }
            val matched = try {
                condition()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log("WAIT", "FAILED", "condition failed: ${t::class.java.simpleName}: ${t.message}")
                return false
            }
            if (matched) return true
            delay(pollMillis)
        }
    }

    /**
     * A global action is refused unless the collector is running, exactly like
     * [tap]. A pause or stop that lands between a decision and its execution
     * must not let a stale coroutine press Back, go Home, or launch an app.
     */
    private suspend fun guardAction(name: String, action: Int): Boolean {
        if (!mayAct(name)) return false
        if (action == AccessibilityService.GLOBAL_ACTION_BACK &&
            !foregroundAllows(ActionGate.Action.BACK, name)
        ) {
            return false
        }
        markAction()
        if (action == AccessibilityService.GLOBAL_ACTION_HOME) lastHomeAt = monotonicClock()
        val ok = withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        }
        // One authorised Back per judgement: the next foreign app is judged anew.
        if (action == AccessibilityService.GLOBAL_ACTION_BACK) backAuthorizedPackage = null
        log(action = name, result = if (ok) "SUCCESS" else "FAILED", message = "global action $action")
        return ok
    }

    /**
     * Re-reads the foreground right before acting. A window that cannot be
     * identified gets a few short retries (it is usually a transition), then
     * the action is refused rather than guessed.
     */
    private suspend fun foregroundAllows(action: ActionGate.Action, name: String): Boolean {
        val target = targetPackage
        val read = foregroundNow
        var last: String? = null
        repeat(GATE_ATTEMPTS) { attempt ->
            last = runCatching { read() }.getOrNull()
            when (ActionGate.decide(action, last, target, currentBackAuthorization(), protectedSurface, currentAdHost())) {
                ActionGate.Decision.ALLOW -> return true
                ActionGate.Decision.DENY -> {
                    log(name, "SKIPPED", "foreground is $last, not $target")
                    return false
                }
                ActionGate.Decision.UNKNOWN -> if (attempt < GATE_ATTEMPTS - 1) delay(GATE_RETRY_MILLIS)
            }
        }
        log(name, "SKIPPED", "foreground app unknown; refusing to act blind")
        return false
    }

    /**
     * The check every action starts with. A locked phone or a finger pauses the
     * run here, before it acts, not at the next wait poll; and nothing is done
     * unless the run is RUNNING, so a stale coroutine can never act after a
     * pause or stop. Only ever pauses: an automatic resume belongs to a wait
     * loop, never to the moment a stale decision is about to be executed.
     */
    private fun mayAct(name: String): Boolean {
        if (stateProvider() == CollectorState.RUNNING) interruptionCheck?.invoke()
        if (stateProvider() != CollectorState.RUNNING) {
            log(name, "SKIPPED", "collector is not running")
            return false
        }
        return true
    }

    private fun log(action: String, result: String, message: String) {
        logger.log(
            eventType = EventType.COLLECTOR,
            sessionId = sessionId(),
            detectionMethod = "Actor",
            action = action,
            result = result,
            message = message
        )
    }

    companion object {
        /** Long enough for the system to report a real click, short enough to be a tap. */
        const val TAP_DURATION_MILLIS = 60L
        const val SWIPE_DURATION_MILLIS = 450L

        /** One wait poll; also the granularity at which pause/stop is noticed. */
        const val DEFAULT_POLL_MILLIS = 250L

        /** How long a redirect judgement authorises Back on that package. */
        const val BACK_AUTH_MILLIS = 15_000L

        /** How long one ad frame on another app's window authorises acting there. */
        const val AD_HOST_AUTH_MILLIS = 5_000L

        private const val GATE_ATTEMPTS = 3
        private const val GATE_RETRY_MILLIS = 300L
    }
}
