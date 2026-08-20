package com.androidcompress.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
/**
 * Draws tap ripples and a pointer while a recording with "show taps" is active.
 * Requires the user to enable this accessibility service. It does not read text.
 */
class TapHighlightService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: RippleOverlay? = null
    private var sessionActive = false

    override fun onServiceConnected() {
        instance = this
        val recording = runCatching { applicationContext.containerRecording() }.getOrNull()
        val active = recording?.state?.value?.active == true
        if (active && pendingShowTaps) setSession(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!sessionActive || Build.VERSION.SDK_INT >= 33) return
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event?.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            return
        }
        val src = event.source ?: return
        val rect = android.graphics.Rect()
        src.getBoundsInScreen(rect)
        overlay?.pulse(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)
        if (!sessionActive) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                overlay?.pulse(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                    event.isFromSource(InputDevice.SOURCE_STYLUS)
                ) {
                    overlay?.cursor(event.x, event.y)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> overlay?.hideCursor()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        setSession(false)
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun setSession(active: Boolean) {
        handler.post {
            sessionActive = active
            if (active) ensureOverlay() else removeOverlay()
        }
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val view = RippleOverlay(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "RecordingCompressorTaps"
        }
        overlay = view
        runCatching { wm.addView(view, params) }
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        overlay = null
    }

    private class RippleOverlay(context: android.content.Context) : View(context) {
        private data class Ripple(var x: Float, var y: Float, var born: Long)

        private val ripples = mutableListOf<Ripple>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private var cursorX = Float.NaN
        private var cursorY = Float.NaN
        private val lifetime = 350L

        fun pulse(x: Float, y: Float) {
            ripples.add(Ripple(x, y, android.os.SystemClock.uptimeMillis()))
            invalidate()
        }

        fun cursor(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            invalidate()
        }

        fun hideCursor() {
            cursorX = Float.NaN
            cursorY = Float.NaN
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val now = android.os.SystemClock.uptimeMillis()
            val iter = ripples.iterator()
            while (iter.hasNext()) {
                val r = iter.next()
                val t = (now - r.born).toFloat() / lifetime
                if (t >= 1f) {
                    iter.remove()
                    continue
                }
                paint.alpha = ((1f - t) * 220).toInt().coerceIn(0, 255)
                canvas.drawCircle(r.x, r.y, 24f + t * 80f, paint)
            }
            if (!cursorX.isNaN() && !cursorY.isNaN()) {
                cursorPaint.alpha = 200
                canvas.drawCircle(cursorX, cursorY, 10f, cursorPaint)
            }
            if (ripples.isNotEmpty()) {
                postInvalidateOnAnimation()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TapHighlightService? = null

        @Volatile
        var pendingShowTaps: Boolean = false

        fun isEnabled(): Boolean = instance != null

        fun setRecording(active: Boolean, showTaps: Boolean) {
            pendingShowTaps = active && showTaps
            instance?.setSession(active && showTaps)
        }
    }
}

private fun android.content.Context.containerRecording() =
    (applicationContext as com.androidcompress.app.CompressApplication).container.recording
