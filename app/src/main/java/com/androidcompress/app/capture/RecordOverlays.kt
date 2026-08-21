package com.androidcompress.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.androidcompress.app.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

class RecordOverlayHost(private val context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private var regionView: RegionSelectView? = null
    private var countdownView: TextView? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var facecam: FacecamOverlay? = null

    fun showRegion(onConfirm: (RecordRegion) -> Unit, onCancel: () -> Unit) {
        if (!canDrawOverlays(app)) {
            onConfirm(RecordRegion.FULL)
            return
        }
        hideRegion()
        val view = RegionSelectView(app, onConfirm = {
            hideRegion()
            onConfirm(it)
        }, onCancel = {
            hideRegion()
            onCancel()
        })
        regionView = view
        runCatching { wm.addView(view, overlayParams()) }
            .onFailure { onConfirm(RecordRegion.FULL) }
    }

    fun hideRegion() {
        regionView?.let { runCatching { wm.removeView(it) } }
        regionView = null
    }

    fun showCountdown(seconds: Int) {
        if (!canDrawOverlays(app)) return
        val existing = countdownView
        if (existing == null) {
            val tv = TextView(app).apply {
                setTextColor(Color.WHITE)
                textSize = 72f
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                gravity = Gravity.CENTER
            }
            countdownView = tv
            val params = overlayParams(
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            )
            runCatching { wm.addView(tv, params) }
        }
        countdownView?.text = if (seconds > 0) seconds.toString() else ""
        countdownView?.visibility = if (seconds > 0) View.VISIBLE else View.GONE
    }

    fun hideCountdown() {
        countdownView?.let { runCatching { wm.removeView(it) } }
        countdownView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble(
        paused: Boolean,
        onPauseResume: () -> Unit,
        onStop: () -> Unit,
        onBookmark: (() -> Unit)? = null,
    ) {
        if (!canDrawOverlays(app)) return
        hideBubble()
        val density = app.resources.displayMetrics.density
        val bar = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC101413"))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            elevation = 8 * density
        }
        val pause = ImageButton(app).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            )
            contentDescription = app.getString(if (paused) R.string.record_resume else R.string.record_pause)
            setOnClickListener { onPauseResume() }
        }
        val stop = ImageButton(app).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = app.getString(R.string.record_stop)
            setOnClickListener { onStop() }
        }
        bar.addView(pause, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        if (onBookmark != null) {
            val mark = ImageButton(app).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(android.R.drawable.ic_input_add)
                contentDescription = app.getString(R.string.record_bookmark)
                setOnClickListener { onBookmark() }
            }
            bar.addView(mark, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        }
        bar.addView(stop, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        val params = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            y = (48 * density).toInt(),
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        enableDrag(bar, params)
        bubbleView = bar
        bubbleParams = params
        runCatching { wm.addView(bar, params) }
    }

    fun updateBubble(paused: Boolean) {
        val bar = bubbleView as? LinearLayout ?: return
        val pause = bar.getChildAt(0) as? ImageButton ?: return
        pause.setImageResource(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
        pause.contentDescription = app.getString(if (paused) R.string.record_resume else R.string.record_pause)
    }

    fun hideBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    fun showFacecam(options: RecordOptions) {
        if (!canDrawOverlays(app)) return
        hideFacecam()
        val overlay = FacecamOverlay(app, wm, options)
        facecam = overlay
        overlay.show()
    }

    fun setFacecamVisible(visible: Boolean) {
        facecam?.setVisible(visible)
    }

    fun hideFacecam() {
        facecam?.dismiss()
        facecam = null
    }

    fun dismissAll() {
        hideRegion()
        hideCountdown()
        hideBubble()
        hideFacecam()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > 16f) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun overlayParams(
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
        gravity: Int = Gravity.TOP or Gravity.START,
        x: Int = 0,
        y: Int = 0,
        flags: Int = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
        title = "RecordingCompressorOverlay"
    }
}

@SuppressLint("ViewConstructor")
private class RegionSelectView(
    context: Context,
    private val onConfirm: (RecordRegion) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val handle = 28f * density
    private val minSize = 96f * density
    private val rect = RectF()
    private val dim = Paint().apply { color = Color.parseColor("#99000000") }
    private val clear = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val stroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Drag { NONE, MOVE, L, T, R, B, TL, TR, BL, BR }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#CC101413"))
        }
        bar.addView(textButton(context.getString(R.string.record_region_cancel)) { onCancel() })
        bar.addView(textButton(context.getString(R.string.record_region_full)) {
            onConfirm(RecordRegion.FULL)
        })
        bar.addView(textButton(context.getString(R.string.record_region_confirm)) {
            onConfirm(currentRegion())
        })
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rect.width() < 8f) {
            val insetX = w * 0.08f
            val insetY = h * 0.12f
            rect.set(insetX, insetY, w - insetX, h - insetY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(rect, clear)
        canvas.restoreToCount(saved)
        canvas.drawRect(rect, stroke)
        drawHandle(canvas, rect.left, rect.top)
        drawHandle(canvas, rect.right, rect.top)
        drawHandle(canvas, rect.left, rect.bottom)
        drawHandle(canvas, rect.right, rect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handle / 3f, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hit(x, y)
                lastX = x
                lastY = y
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                val dx = x - lastX
                val dy = y - lastY
                applyDrag(dx, dy)
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return super.onTouchEvent(event)
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (drag) {
            Drag.MOVE -> {
                rect.offset(dx, dy)
                if (rect.left < 0) rect.offset(-rect.left, 0f)
                if (rect.top < 0) rect.offset(0f, -rect.top)
                if (rect.right > w) rect.offset(w - rect.right, 0f)
                if (rect.bottom > h) rect.offset(0f, h - rect.bottom)
            }
            Drag.L, Drag.TL, Drag.BL -> rect.left = (rect.left + dx).coerceIn(0f, rect.right - minSize)
            Drag.R, Drag.TR, Drag.BR -> rect.right = (rect.right + dx).coerceIn(rect.left + minSize, w)
            else -> Unit
        }
        when (drag) {
            Drag.T, Drag.TL, Drag.TR -> rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - minSize)
            Drag.B, Drag.BL, Drag.BR -> rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, h)
            else -> Unit
        }
    }

    private fun hit(x: Float, y: Float): Drag {
        val l = near(x, y, rect.left, rect.top)
        val r = near(x, y, rect.right, rect.top)
        val bl = near(x, y, rect.left, rect.bottom)
        val br = near(x, y, rect.right, rect.bottom)
        return when {
            l -> Drag.TL
            r -> Drag.TR
            bl -> Drag.BL
            br -> Drag.BR
            abs(x - rect.left) < handle && y in rect.top..rect.bottom -> Drag.L
            abs(x - rect.right) < handle && y in rect.top..rect.bottom -> Drag.R
            abs(y - rect.top) < handle && x in rect.left..rect.right -> Drag.T
            abs(y - rect.bottom) < handle && x in rect.left..rect.right -> Drag.B
            rect.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun near(x: Float, y: Float, hx: Float, hy: Float): Boolean =
        hypot(x - hx, y - hy) <= handle

    private fun currentRegion(): RecordRegion {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return RecordRegion(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h).sanitized()
    }

    private fun textButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (4 * density).toInt()
            marginEnd = (4 * density).toInt()
        }
    }
}

private class FacecamOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val options: RecordOptions,
) {
    private val density = context.resources.displayMetrics.density
    private val round = options.facecamShape == FacecamShape.ROUND
    private val sizePx = options.facecamSize.pixelSize(density, round)
    private val widthPx = sizePx.first
    private val heightPx = sizePx.second
    private var host: FrameLayout? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null

    fun show() {
        val texture = TextureView(context)
        if (options.facecamLens != FacecamLens.BACK) texture.scaleX = -1f
        val frame = FrameLayout(context).apply {
            addView(texture, FrameLayout.LayoutParams(widthPx, heightPx))
            setBackgroundColor(Color.parseColor("#FF1A1F1E"))
            elevation = 10 * density
            if (round) {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }
        val screen = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screen.widthPixels - widthPx - (16 * density).roundToInt()
            y = screen.heightPixels - heightPx - (96 * density).roundToInt()
            title = "RecordingCompressorFacecam"
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        frame.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                else -> false
            }
        }
        host = frame
        runCatching { wm.addView(frame, params) }.onFailure { return }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (texture.isAvailable) {
            texture.surfaceTexture?.let { openCamera(it, texture.width, texture.height) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(texture: SurfaceTexture, viewW: Int, viewH: Int) {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        val wanted = if (options.facecamLens == FacecamLens.BACK) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        val id = manager.cameraIdList.firstOrNull { camId ->
            manager.getCameraCharacteristics(camId).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: manager.cameraIdList.firstOrNull() ?: return
        val map = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val preview = pickSize(map.getOutputSizes(SurfaceTexture::class.java), viewW, viewH)
        texture.setDefaultBufferSize(preview.width, preview.height)
        val camThread = HandlerThread("facecam").also { it.start() }
        thread = camThread
        val handler = Handler(camThread.looper)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val surface = Surface(texture)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    val config = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        java.util.concurrent.Executor { handler.post(it) },
                        callback,
                    )
                    runCatching { device.createCaptureSession(config) }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { device.createCaptureSession(listOf(surface), callback, handler) }
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, handler)
    }

    private fun pickSize(sizes: Array<Size>, viewW: Int, viewH: Int): Size {
        val target = viewW.coerceAtLeast(1) * viewH.coerceAtLeast(1)
        return sizes.minByOrNull { size ->
            abs(size.width * size.height - target)
        } ?: Size(640, 480)
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        thread?.quitSafely()
        thread = null
    }

    fun setVisible(visible: Boolean) {
        host?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun dismiss() {
        closeCamera()
        host?.let { runCatching { wm.removeView(it) } }
        host = null
    }
}
