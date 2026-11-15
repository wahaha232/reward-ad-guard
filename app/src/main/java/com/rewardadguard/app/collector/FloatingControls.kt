package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating 暫停/繼續 and 停止 buttons shown while a run is active (asked by
 * the user, 2026-11-13), so the run can be controlled from inside the reward
 * app without pulling the notification shade down.
 *
 * It is a TYPE_ACCESSIBILITY_OVERLAY (no extra permission). It starts on the
 * left edge, a little above mid-height, and the user can drag it anywhere
 * (2026-11-15): a press that moves past the touch slop drags the whole panel,
 * a press that does not is a button tap. The last position is remembered for
 * the next run. Its screen box is published in [bounds] wherever it is, so the
 * collector never taps it and OCR never reads its labels as content.
 */
class FloatingControls(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var panel: DragPanel? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var pauseButton: TextView? = null

    /** Shows, updates or hides the panel for [state]; safe from any thread. */
    fun render(state: CollectorController.State) {
        main.post {
            runCatching { renderNow(state) }
                .onFailure { android.util.Log.e(TAG, "floating panel render failed", it) }
        }
    }

    private fun renderNow(state: CollectorController.State) {
        if (!state.running) {
            hideNow()
        } else {
            showNow()
            applyState(state)
        }
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun applyState(state: CollectorController.State) {
        val paused = state.paused
        (statusDot?.background as? GradientDrawable)?.setColor(if (paused) AMBER else GREEN)
        statusText?.text = if (paused) "已暫停" else "收集 ${state.rewards}"
        pauseButton?.apply {
            text = if (paused) "▶ 繼續" else "❚❚ 暫停"
            background = pill(if (paused) GREEN_DARK else BUTTON_GREY)
        }
    }

    private fun showNow() {
        if (panel != null) return
        val manager = service.getSystemService(WindowManager::class.java) ?: run {
            android.util.Log.w(TAG, "no WindowManager for the floating panel")
            return
        }
        val layout = DragPanel(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(0xE6202124.toInt())
                setStroke(dp(1f).toInt(), 0x33FFFFFF)
            }
            val pad = dp(6f).toInt()
            setPadding(pad, dp(4f).toInt(), pad, pad)
        }

        // Grip: shows the panel can be dragged (any part of it can).
        val grip = TextView(service).apply {
            text = "• • •"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
        }
        layout.addView(grip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val statusRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2f).toInt(), 0, dp(5f).toInt())
        }
        val dot = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREEN)
            }
        }
        val dotSize = dp(8f).toInt()
        statusRow.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = dp(5f).toInt() })
        val status = TextView(service).apply {
            setTextColor(0xFFE8EAED.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        statusRow.addView(status)
        layout.addView(statusRow)

        val pause = button("❚❚ 暫停", BUTTON_GREY) {
            if (CollectorController.state.value.paused) CollectorController.resume() else CollectorController.pause()
        }
        val stop = button("■ 停止", RED_DARK) { CollectorController.stop() }
        fun buttonParams(top: Int) =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = top }
        layout.addView(pause, buttonParams(0))
        layout.addView(stop, buttonParams(dp(6f).toInt()))

        val (screenW, screenH) = screenSize(manager)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // The spot of the last drag, else the left edge a little above
            // mid-height (where the old fixed panel sat).
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, (screenH * 0.42).toInt())
        }
        layout.onDrag = { dx, dy ->
            lp.x = (lp.x + dx).coerceIn(0, (screenW - layout.width).coerceAtLeast(0))
            lp.y = (lp.y + dy).coerceIn(0, (screenH - layout.height).coerceAtLeast(0))
            runCatching { manager.updateViewLayout(layout, lp) }
        }
        layout.onDragEnd = {
            prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
            layout.post { publishBounds(layout) }
        }

        val added = runCatching { manager.addView(layout, lp) }
            .onFailure { android.util.Log.w(TAG, "floating panel not shown", it) }
            .isSuccess
        if (!added) return
        android.util.Log.i(TAG, "floating panel shown at ${lp.x},${lp.y}")
        panel = layout
        statusDot = dot
        statusText = status
        pauseButton = pause
        layout.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            // A spot saved in the other orientation could leave the panel
            // half off screen: pull it back in.
            val maxX = (screenW - view.width).coerceAtLeast(0)
            val maxY = (screenH - view.height).coerceAtLeast(0)
            if (lp.x > maxX || lp.y > maxY) {
                lp.x = lp.x.coerceAtMost(maxX)
                lp.y = lp.y.coerceAtMost(maxY)
                runCatching { manager.updateViewLayout(view, lp) }
            }
            view.post { publishBounds(view) }
        }
    }

    private fun publishBounds(view: View) {
        if (view !== panel) return
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        bounds = Box(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun hideNow() {
        val view = panel ?: return
        panel = null
        statusDot = null
        statusText = null
        pauseButton = null
        bounds = null
        runCatching { service.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private fun button(label: String, color: Int, onTap: () -> Unit): TextView = TextView(service).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val h = dp(10f).toInt()
        val v = dp(9f).toInt()
        setPadding(h, v, h, v)
        minWidth = dp(72f).toInt()
        background = pill(color)
        isClickable = true
        setOnClickListener {
            // A tap on our own panel is the user commanding the run, not a
            // finger on the reward app: the service ignores clicks from this
            // package, and the click itself acts right away.
            onTap()
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10f)
        setColor(color)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(manager: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = manager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            manager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)

    /**
     * The panel's root. A short press reaches the buttons (a tap); once the
     * finger moves past the touch slop the panel takes the gesture over, so it
     * can be dragged by any part of it, buttons included.
     */
    private class DragPanel(context: Context) : LinearLayout(context) {
        var onDrag: ((Int, Int) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        private fun press(ev: MotionEvent) {
            downX = ev.rawX; downY = ev.rawY
            lastX = ev.rawX; lastY = ev.rawY
            dragging = false
        }

        private fun pastSlop(ev: MotionEvent) =
            abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(ev)
                MotionEvent.ACTION_MOVE -> if (!dragging && pastSlop(ev)) dragging = true
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                // A press on the panel's own background (grip, status line).
                MotionEvent.ACTION_DOWN -> press(ev)
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && pastSlop(ev)) dragging = true
                    if (dragging) {
                        val dx = (ev.rawX - lastX).toInt()
                        val dy = (ev.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            onDrag?.invoke(dx, dy)
                            lastX += dx; lastY += dy
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd?.invoke()
                    dragging = false
                }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "FloatingControls"
        private const val PREFS = "floating_controls"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        private const val GREEN = 0xFF34A853.toInt()
        private const val AMBER = 0xFFFBBC04.toInt()
        private const val GREEN_DARK = 0xFF1E8E3E.toInt()
        private const val RED_DARK = 0xFFC5221F.toInt()
        private const val BUTTON_GREY = 0xFF3C4043.toInt()

        /** The panel's box on screen while shown, else null. */
        @Volatile
        var bounds: Box? = null
            internal set

        /** True when (x, y) would land on the panel (with a small margin). */
        fun covers(x: Int, y: Int, margin: Int = 24): Boolean {
            val box = bounds ?: return false
            return x in (box.left - margin)..(box.right + margin) && y in (box.top - margin)..(box.bottom + margin)
        }

        /** Drops text read off the panel itself ("暫停", "停止", "收集 3"). */
        fun withoutPanel(boxes: List<TextBox>): List<TextBox> {
            val box = bounds ?: return boxes
            return boxes.filterNot { box.containsPoint(it.bounds.centerX, it.bounds.centerY) }
        }
    }
}
