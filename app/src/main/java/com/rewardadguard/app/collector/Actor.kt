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
    private val pollMillis: Long = DEFAULT_POLL_MILLIS
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
        if (stateProvider() != CollectorState.RUNNING) {
            log("TAP", "SKIPPED", "collector is not running")
            return false
        }
        selfClicks.markOwnClick(clock())
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

    suspend fun back(): Boolean = globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "BACK")

    suspend fun home(): Boolean = globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "HOME")

    /**
     * Starts [packageName] the same way the guard's return controller does:
     * a launcher intent from a background accessibility service, with
     * NEW_TASK so it works without a foreground activity.
     */
    suspend fun launch(packageName: String): Boolean {
        val ok = withContext(Dispatchers.Main) {
            try {
                val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    false
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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

    private suspend fun globalAction(action: Int, name: String): Boolean {
        val ok = withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        }
        log(action = name, result = if (ok) "SUCCESS" else "FAILED", message = "global action $action")
        return ok
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
    }
}
