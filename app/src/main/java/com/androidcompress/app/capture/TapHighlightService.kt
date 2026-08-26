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
import com.androidcompress.app.util.runCatchingLog
import kotlin.math.hypot
/**
 * Draws tap ripples, a laser pointer, and optional ink while a recording is
 * active. Requires the user to enable this accessibility service. It does not
 * read text.
 */
class TapHighlightService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: PointerOverlay? = null
    private var sessionActive = false
    private var showTaps = false
    private var showLaser = false
    private var showAnnotation = false

    override fun onServiceConnected() {
        instance = this
        val recording = runCatchingLog(TAG, "recording store") { applicationContext.containerRecording() }.getOrNull()
        val active = recording?.state?.value?.active == true
        if (active && pendingActive) {
            setSession(true, pendingTaps, pendingLaser, pendingDraw)
        }
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
        if (showTaps) overlay?.pulse(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)
        if (!sessionActive) return
        val i = event.actionIndex
        val x = event.getX(i)
        val y = event.getY(i)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (showTaps) overlay?.pulse(x, y)
                if (showLaser) overlay?.laser(x, y, down = true)
                if (showAnnotation) overlay?.strokeStart(x, y)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (showLaser) overlay?.laser(event.x, event.y, down = true)
                if (showAnnotation && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    overlay?.strokeMove(event.x, event.y)
                }
                if (showTaps && (
                        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                            event.isFromSource(InputDevice.SOURCE_STYLUS)
                        )
                ) {
                    overlay?.cursor(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (showLaser) overlay?.laser(x, y, down = false)
                if (showAnnotation) overlay?.strokeEnd()
                if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) overlay?.hideCursor()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        setSession(false, false, false, false)
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun setSession(active: Boolean, taps: Boolean, laser: Boolean, draw: Boolean) {
        handler.post {
            showTaps = taps
            showLaser = laser
            showAnnotation = draw
            sessionActive = active && (taps || laser || draw)
            if (sessionActive) ensureOverlay() else removeOverlay()
        }
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val view = PointerOverlay(this)
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
        runCatchingLog(TAG, "add overlay") { wm.addView(view, params) }
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            runCatchingLog(TAG, "remove overlay") { getSystemService(WindowManager::class.java).removeView(view) }
        }
        overlay = null
    }

    private class PointerOverlay(context: android.content.Context) : View(context) {
        private data class Ripple(var x: Float, var y: Float, var born: Long)
        private data class Stroke(val points: MutableList<android.graphics.PointF>)

        private val ripples = mutableListOf<Ripple>()
        private val strokes = mutableListOf<Stroke>()
        private var liveStroke: Stroke? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF3B30")
        }
        private val laserGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66FF3B30")
        }
        private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#FFD60A")
        }
        private var cursorX = Float.NaN
        private var cursorY = Float.NaN
        private var laserX = Float.NaN
        private var laserY = Float.NaN
        private var laserDown = false
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

        fun laser(x: Float, y: Float, down: Boolean) {
            laserDown = down
            if (down) {
                laserX = x
                laserY = y
            } else {
                laserX = Float.NaN
                laserY = Float.NaN
            }
            invalidate()
        }

        fun strokeStart(x: Float, y: Float) {
            val stroke = Stroke(mutableListOf(android.graphics.PointF(x, y)))
            liveStroke = stroke
            if (strokes.size >= 80) strokes.removeAt(0)
            strokes.add(stroke)
            invalidate()
        }

        fun strokeMove(x: Float, y: Float) {
            val stroke = liveStroke ?: return
            val last = stroke.points.lastOrNull()
            if (last == null || hypot(x - last.x, y - last.y) >= 2f) {
                if (stroke.points.size < 400) stroke.points.add(android.graphics.PointF(x, y))
            }
            invalidate()
        }

        fun strokeEnd() {
            liveStroke = null
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
            for (stroke in strokes) {
                if (stroke.points.size < 2) {
                    val p = stroke.points.firstOrNull() ?: continue
                    canvas.drawCircle(p.x, p.y, 4f, inkPaint)
                    continue
                }
                val path = android.graphics.Path()
                stroke.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, inkPaint)
            }
            if (!cursorX.isNaN() && !cursorY.isNaN()) {
                cursorPaint.alpha = 200
                canvas.drawCircle(cursorX, cursorY, 10f, cursorPaint)
            }
            if (laserDown && !laserX.isNaN() && !laserY.isNaN()) {
                canvas.drawCircle(laserX, laserY, 22f, laserGlow)
                canvas.drawCircle(laserX, laserY, 8f, laserPaint)
            }
            if (ripples.isNotEmpty() || laserDown) {
                postInvalidateOnAnimation()
            }
        }
    }

    companion object {
        private const val TAG = "TapHighlight"
        @Volatile
        private var instance: TapHighlightService? = null

        @Volatile
        var pendingActive: Boolean = false

        @Volatile
        var pendingTaps: Boolean = false

        @Volatile
        var pendingLaser: Boolean = false

        @Volatile
        var pendingDraw: Boolean = false

        fun isEnabled(): Boolean = instance != null

        fun setRecording(
            active: Boolean,
            showTaps: Boolean,
            showLaser: Boolean = false,
            showAnnotation: Boolean = false,
        ) {
            pendingActive = active && (showTaps || showLaser || showAnnotation)
            pendingTaps = showTaps
            pendingLaser = showLaser
            pendingDraw = showAnnotation
            instance?.setSession(active, showTaps, showLaser, showAnnotation)
        }
    }
}

private fun android.content.Context.containerRecording() =
    (applicationContext as com.androidcompress.app.CompressApplication).container.recording
