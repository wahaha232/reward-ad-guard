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
        mainHandler.post { hideNow() }
        service = null
        onUserWake = null
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
        withContext(Dispatchers.Main) { setTouchable(false) }
        delay(PASS_THROUGH_SETTLE_MILLIS)
        return try {
            block()
        } finally {
            withContext(Dispatchers.Main) { setTouchable(true) }
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
                if (event.actionMasked == MotionEvent.ACTION_DOWN) wakeByUser()
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
