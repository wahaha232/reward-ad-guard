package com.rewardadguard.app.collector

import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rewardadguard.app.service.RewardAdAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Starts a due schedule (2026-11-15) once the phone allows it, following
 * [ScheduleMath.decide]: wait while locked, wait until nobody used the phone
 * for three minutes, skip after the grace period.
 *
 * While it waits on an unlocked phone it keeps the screen lit (a 1x1
 * keep-screen-on overlay), otherwise the screen would time out long before
 * the three idle minutes and the run could never start. While waiting it
 * also asks the service for click/scroll events ([onWaitingChanged]), the
 * only signal of "someone is using the phone" an accessibility service gets.
 */
class ScheduledRunner(
    private val service: RewardAdAccessibilityService,
    private val scope: CoroutineScope,
    /** Last real tap, scroll or app switch by the user (0 when none). */
    private val userActivityAt: () -> Long,
    /** Turns the extra click/scroll events on while a run waits. */
    private val onWaitingChanged: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var wakeView: View? = null
    private var unlockedAt = 0L
    private var wasUsable = false
    private var waiting = false
    /** The pending scan had to wait for a collection run. */
    private var scanWaitedForRun = false

    /** Checks the pending run now (alarm fired, service connected). */
    fun kick() {
        if (job?.isActive == true) return
        CollectorScheduler.load(service)
        if (CollectorScheduler.pending.value == null) return
        scanWaitedForRun = false
        job = scope.launch {
            try {
                loop()
            } catch (t: Throwable) {
                Log.w(TAG, "scheduled run loop failed", t)
            } finally {
                setWaiting(false)
                keepScreenOn(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val pending = CollectorScheduler.pending.value ?: return
            val now = System.currentTimeMillis()
            val locked = service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
            val screenOn = service.getSystemService(PowerManager::class.java)?.isInteractive ?: false
            val usable = !locked && screenOn
            if (usable && !wasUsable) unlockedAt = now
            wasUsable = usable
            val idleSince = maxOf(userActivityAt(), unlockedAt)
            val decision = ScheduleMath.decide(
                pending = pending,
                now = now,
                locked = locked,
                screenOn = screenOn,
                collectorRunning = CollectorController.state.value.running,
                idleSince = idleSince
            )
            if (pending.scan && decision == ScheduleMath.Decision.WAIT_BUSY) scanWaitedForRun = true
            if (pending.scan && scanWaitedForRun && decision != ScheduleMath.Decision.WAIT_BUSY) {
                // A scan that queued behind a collection: right after a run
                // it only re-reads what was just collected, and it looked like
                // the run moving on to other apps (2026-11-16 08:34).
                scanWaitedForRun = false
                CollectorScheduler.clearPending(service, null)
                return
            }
            setWaiting(decision == ScheduleMath.Decision.WAIT_IDLE)
            keepScreenOn(decision == ScheduleMath.Decision.WAIT_IDLE)
            when (decision) {
                ScheduleMath.Decision.EXPIRED -> {
                    CollectorScheduler.clearPending(
                        service,
                        if (pending.scan) "排程掃描已略過：手機一直沒有解鎖並閒置" else "排程已略過：3 小時內手機都沒有解鎖並閒置"
                    )
                    return
                }
                ScheduleMath.Decision.WAIT_BUSY -> CollectorScheduler.setStatus(service, "另一輪收取進行中，結束後開始")
                ScheduleMath.Decision.WAIT_UNLOCK -> CollectorScheduler.setStatus(service, "等待解鎖手機")
                ScheduleMath.Decision.WAIT_IDLE -> {
                    val left = ScheduleMath.idleRemaining(pending, now, idleSince)
                    val text = "手機閒置 ${formatLeft(left)} 後開始（碰手機會重新計時）"
                    CollectorScheduler.setStatus(service, text)
                }
                ScheduleMath.Decision.START -> {
                    start(pending)
                    return
                }
            }
            delay(POLL_MILLIS)
        }
    }

    private fun start(pending: PendingRun) {
        val profiles = pending.appKeys.mapNotNull { CollectorProfiles.byKey(it) }.distinct()
        keepScreenOn(false)
        setWaiting(false)
        val error = CollectorController.start(service, profiles, CollectorLimits.UNLIMITED, dryRun = false, scan = pending.scan)
        if (error == null) {
            CollectorScheduler.clearPending(service, null)
        } else {
            CollectorScheduler.clearPending(service, "排程無法開始：$error")
        }
    }

    private fun setWaiting(value: Boolean) {
        if (waiting == value) return
        waiting = value
        main.post { onWaitingChanged(value) }
    }

    private fun keepScreenOn(on: Boolean) {
        main.post {
            val manager = service.getSystemService(WindowManager::class.java) ?: return@post
            if (on && wakeView == null) {
                val view = View(service)
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                if (runCatching { manager.addView(view, params) }.isSuccess) wakeView = view
            } else if (!on) {
                wakeView?.let { runCatching { manager.removeView(it) } }
                wakeView = null
            }
        }
    }

    private fun formatLeft(millis: Long): String {
        val s = (millis + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val TAG = "ScheduledRunner"
        private const val POLL_MILLIS = 5_000L
    }
}
