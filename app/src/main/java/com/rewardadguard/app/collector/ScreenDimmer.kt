package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「收取時關閉螢幕」(2026-11-23): the screen looks off while a run keeps going.
 *
 * An app cannot switch the backlight off (that needs shell, like the PC
 * 手機螢幕開關), so this is a full-screen black accessibility overlay with the
 * window brightness at its minimum. On the phone's OLED black pixels are dark,
 * and because the display is really on, the phone neither sleeps nor locks
 * (the overlay also keeps the screen on).
 *
 * - A finger on the black screen wakes it: the overlay goes away and the touch
 *   counts as the user's, so the run pauses as for any other touch.
 * - Volume keys wake it too (the service forwards them while dimmed).
 * - The power key cannot be intercepted by an app: it really switches the
 *   screen off and locks; the run pauses and continues after unlocking.
 * - The collector's own taps must reach the app below, so the overlay is made
 *   untouchable for the length of each gesture ([passThrough]).
 * - Screenshots of the display would be black; [ScreenReader] reads the app's
 *   window instead while dimmed (Android 14+, which is why the setting needs it).
 */
object ScreenDimmer {

    private const val PREFS = "collector_debug"
    private const val KEY_ENABLED = "screen_off_while_collecting"

    /** Wait before going dark, so the user sees the run start. */
    const val DIM_DELAY_MILLIS = 3_000L

    /** Window layout settles before an injected tap goes through. */
    private const val PASS_THROUGH_SETTLE_MILLIS = 60L

    /** Longest wait for the untouchable frame before the gesture goes anyway. */
    private const val PASS_THROUGH_FALLBACK_MILLIS = 300L

    /** The window screenshot the reader needs is Android 14+. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @Volatile
    private var enabledCache: Boolean? = null

    fun isEnabled(context: Context): Boolean =
        isSupported() && (enabledCache ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false).also { enabledCache = it })

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        enabledCache = enabled
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var service: AccessibilityService? = null
    private var onUserWake: (() -> Unit)? = null
    private val dimRunnable = Runnable { showNow() }

    /** True while the screen is dark; read by the screenshot path and key filter. */
    @Volatile
    var isDimmed: Boolean = false
        private set

    /** Called by the service when it connects; [onWake] runs when the user wakes the screen. */
    fun attach(service: AccessibilityService, onWake: () -> Unit) {
        this.service = service
        this.onUserWake = onWake
    }

    fun detach() {
        // The service is cleared only after the overlay is gone: hideNow()
        // needs it to reach the WindowManager (review 2026-11-23).
        mainHandler.removeCallbacks(dimRunnable)
        val clear = {
            hideNow()
            service = null
            onUserWake = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) clear() else mainHandler.post(clear)
    }

    /** Goes dark after [DIM_DELAY_MILLIS] when the setting is on. */
    fun dimSoon() {
        val s = service ?: return
        if (!isEnabled(s)) return
        mainHandler.removeCallbacks(dimRunnable)
        mainHandler.postDelayed(dimRunnable, DIM_DELAY_MILLIS)
    }

    /** Lights the screen again (run ended, paused, or locked). */
    fun wake() {
        mainHandler.removeCallbacks(dimRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) hideNow() else mainHandler.post { hideNow() }
    }

    /** The user woke the screen: light it and tell the service it was a real finger. */
    fun wakeByUser() {
        val wasDimmed = isDimmed
        wake()
        if (wasDimmed) onUserWake?.invoke()
    }

    /**
     * Runs one injected gesture with the overlay untouchable, so the tap lands
     * on the app below instead of waking the screen.
     */
    suspend fun <T> passThrough(block: suspend () -> T): T {
        if (!isDimmed) return block()
        // A fixed 60 ms was not always enough (2026-11-23 10:42): the window
        // flag had not reached the input system yet, our taps landed on the
        // overlay, never reached the app and were taken for a finger. Wait
        // until a frame with the new flag has been committed.
        passingThrough = true
        return try {
            withContext(Dispatchers.Main) { awaitUntouchable() }
            delay(PASS_THROUGH_SETTLE_MILLIS)
            block()
        } finally {
            // Also when the run is cancelled mid-gesture: an untouchable black
            // screen could not be woken by a finger any more.
            withContext(NonCancellable + Dispatchers.Main) { setTouchable(true) }
            passingThrough = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showNow() {
        val s = service ?: return
        if (view != null) return
        val manager = s.getSystemService(WindowManager::class.java) ?: return
        val black = View(s).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            setOnTouchListener { _, event ->
                // A finger wakes the screen. An injected touch is ours only
                // while one of our gestures is passing through; any other is
                // a person (adb, another tool), the same rule as TouchOrigin.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (isUserTouch(event.deviceId, passingThrough)) {
                        wakeByUser()
                    } else {
                        android.util.Log.w("ScreenDimmer", "an injected tap hit the overlay (device ${event.deviceId})")
                    }
                }
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Lowest backlight the window may ask for; restored when it goes.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        runCatching { manager.addView(black, lp) }.onSuccess {
            view = black
            params = lp
            isDimmed = true
        }
    }

    private fun hideNow() {
        val v = view ?: return
        view = null
        params = null
        isDimmed = false
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    /** A touch from a physical touchscreen; injected events have no device (-1). */
    fun isRealFinger(event: MotionEvent): Boolean = isRealDevice(event.deviceId)

    /** Pure part of [isRealFinger], for tests. */
    fun isRealDevice(deviceId: Int): Boolean = deviceId > 0

    /** A touch on the black screen that is not one of our own gestures. */
    fun isUserTouch(deviceId: Int, ownGestureInFlight: Boolean): Boolean =
        isRealDevice(deviceId) || !ownGestureInFlight

    /** True while one of our gestures is being let through the overlay. */
    @Volatile
    private var passingThrough = false

    /** Makes the overlay untouchable and returns once a frame with that flag is committed. */
    private suspend fun awaitUntouchable() {
        val v = view
        setTouchable(false)
        if (v == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            delay(PASS_THROUGH_FALLBACK_MILLIS)
            return
        }
        val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
        runCatching {
            v.viewTreeObserver.registerFrameCommitCallback { committed.complete(Unit) }
            v.invalidate()
        }.onFailure { committed.complete(Unit) }
        kotlinx.coroutines.withTimeoutOrNull(PASS_THROUGH_FALLBACK_MILLIS) { committed.await() }
    }

    private fun setTouchable(touchable: Boolean) {
        val v = view ?: return
        val lp = params ?: return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { service?.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) }
    }
}
