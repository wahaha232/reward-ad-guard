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
    /** The reward app this run works on; null disables the foreground gate (tests). */
    private val targetPackage: String? = null,
    /** The active window's package, read fresh at the moment of acting. */
    private val foregroundNow: (() -> String?)? = null
) {

    /**
     * Taps (x, y).
     *
     * The tap is refused unless the collector is [CollectorState.RUNNING], so a
     * stale coroutine can never click after a pause or stop, and the self-click
     * marker is set *before* dispatch because the system can deliver the
     * resulting `TYPE_VIEW_CLICKED` before the gesture callback returns.
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        // A locked phone or a finger must pause the collector before it acts,
        // not at the next wait poll: no tap and no app launch on a lock screen.
        pauseIfInterrupted()
        if (stateProvider() != CollectorState.RUNNING) {
            log("TAP", "SKIPPED", "collector is not running")
            return false
        }
        if (!foregroundAllows(ActionGate.Action.TAP, "TAP")) return false
        if (FloatingControls.covers(x, y)) {
            // Never press our own 暫停/停止 panel by accident.
            log("TAP", "SKIPPED", "x=$x y=$y is on the floating controls")
            return false
        }
        selfClicks.markOwnClick(clock())
        markAction()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MILLIS))
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
        log(
            action = "TAP",
            result = if (dispatched) "SUCCESS" else "FAILED",
            message = "x=$x y=$y"
        )
        return dispatched
    }

    @Volatile
    private var lastActionAt = -1L

    /** Time since the last tap, Back, Home or launch this actor performed; -1 before any. */
    fun sinceLastActionMillis(): Long =
        if (lastActionAt < 0L) -1L else monotonicClock() - lastActionAt

    private fun markAction() {
        lastActionAt = monotonicClock()
    }

    suspend fun back(): Boolean = guardAction("BACK", AccessibilityService.GLOBAL_ACTION_BACK)

    suspend fun home(): Boolean = guardAction("HOME", AccessibilityService.GLOBAL_ACTION_HOME)

    /**
     * Starts [packageName] the same way the guard's return controller does:
     * a launcher intent from a background accessibility service, with
     * NEW_TASK so it works without a foreground activity.
     */
    suspend fun launch(packageName: String, fresh: Boolean = false): Boolean {
        pauseIfInterrupted()
        if (stateProvider() != CollectorState.RUNNING) {
            log("LAUNCH", "SKIPPED", "collector is not running")
            return false
        }
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
        pauseIfInterrupted()
        if (stateProvider() != CollectorState.RUNNING) {
            log(name, "SKIPPED", "collector is not running")
            return false
        }
        if (action == AccessibilityService.GLOBAL_ACTION_BACK &&
            !foregroundAllows(ActionGate.Action.BACK, name)
        ) {
            return false
        }
        markAction()
        val ok = withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        }
        log(action = name, result = if (ok) "SUCCESS" else "FAILED", message = "global action $action")
        return ok
    }

    /**
     * Re-reads the foreground right before acting. A window that cannot be
     * identified gets a few short retries (it is usually a transition), then
     * the action is refused rather than guessed.
     */
    private suspend fun foregroundAllows(action: ActionGate.Action, name: String): Boolean {
        val target = targetPackage ?: return true
        val read = foregroundNow ?: return true
        var last: String? = null
        repeat(GATE_ATTEMPTS) { attempt ->
            last = runCatching { read() }.getOrNull()
            when (ActionGate.decide(action, last, target)) {
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
     * Only ever pauses here: an automatic resume belongs to a wait loop, never
     * to the moment a stale decision is about to be executed.
     */
    private fun pauseIfInterrupted() {
        if (stateProvider() == CollectorState.RUNNING) interruptionCheck?.invoke()
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

        /** One wait poll; also the granularity at which pause/stop is noticed. */
        const val DEFAULT_POLL_MILLIS = 250L

        private const val GATE_ATTEMPTS = 3
        private const val GATE_RETRY_MILLIS = 300L
    }
}
