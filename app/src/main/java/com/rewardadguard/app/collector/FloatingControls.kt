package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating 暫停/繼續 and 停止 buttons shown while a run is active (asked by
 * the user, 2026-11-13), so the run can be controlled from inside the reward
 * app without pulling the notification shade down.
 *
 * It is a TYPE_ACCESSIBILITY_OVERLAY on the left edge, mid-height: no extra
 * permission, and away from where ads put their controls (top corners) and
 * where reward buttons sit (centre). Its screen box is published in [bounds]
 * so the collector never taps it and OCR never reads its labels as content.
 */
class FloatingControls(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var pauseButton: TextView? = null

    /** Shows, updates or hides the panel for [state]; safe from any thread. */
    fun render(state: CollectorController.State) {
        main.post {
            if (!state.running) {
                hideNow()
            } else {
                showNow()
                pauseButton?.text = if (state.paused) "繼續" else "暫停"
            }
        }
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun showNow() {
        if (panel != null) return
        val manager = service.getSystemService(WindowManager::class.java) ?: return
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(0xCC202124.toInt())
            }
            val pad = dp(6f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val pause = button("暫停") { if (CollectorController.state.value.paused) CollectorController.resume() else CollectorController.pause() }
        val stop = button("停止") { CollectorController.stop() }
        layout.addView(pause)
        layout.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }
        val added = runCatching { manager.addView(layout, params) }.isSuccess
        if (!added) return
        panel = layout
        pauseButton = pause
        layout.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            bounds = Box(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }
    }

    private fun hideNow() {
        val view = panel ?: return
        panel = null
        pauseButton = null
        bounds = null
        runCatching { service.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private fun button(label: String, onTap: () -> Unit): TextView = TextView(service).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val h = dp(10f).toInt()
        val v = dp(12f).toInt()
        setPadding(h, v, h, v)
        minWidth = dp(56f).toInt()
        isClickable = true
        setOnClickListener {
            // A tap on our own panel is the user commanding the run, not a
            // finger on the reward app: the service ignores clicks from this
            // package, and the click itself acts right away.
            onTap()
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)

    companion object {
        /** The panel's box on screen while shown, else null. */
        @Volatile
        var bounds: Box? = null
            internal set

        /** True when (x, y) would land on the panel (with a small margin). */
        fun covers(x: Int, y: Int, margin: Int = 24): Boolean {
            val box = bounds ?: return false
            return x in (box.left - margin)..(box.right + margin) && y in (box.top - margin)..(box.bottom + margin)
        }

        /** Drops text read off the panel itself ("暫停", "停止"). */
        fun withoutPanel(boxes: List<TextBox>): List<TextBox> {
            val box = bounds ?: return boxes
            return boxes.filterNot { box.containsPoint(it.bounds.centerX, it.bounds.centerY) }
        }
    }
}
