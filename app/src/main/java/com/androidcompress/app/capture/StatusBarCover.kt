package com.androidcompress.app.capture

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.androidcompress.app.encode.RecordingCrop

/**
 * Status-bar cover is burned into captured frames. A [TYPE_APPLICATION_OVERLAY]
 * window cannot sit above system UI, so MediaProjection would still record the
 * real status bar and privacy indicators.
 */
object StatusBarCover {
    fun destPixels(sourceCoverPx: Int, cropY: Int = 0, destHeight: Int): Int {
        if (sourceCoverPx <= 0 || destHeight <= 0) return 0
        return (sourceCoverPx - cropY).coerceIn(0, destHeight)
    }

    fun destPixels(sourceCoverPx: Int, crop: RecordingCrop?): Int {
        val y = crop?.y ?: 0
        val h = crop?.height ?: Int.MAX_VALUE
        return destPixels(sourceCoverPx, y, h)
    }

    fun scalePx(displayPx: Int, displayHeight: Int, captureHeight: Int): Int {
        if (displayPx <= 0 || captureHeight <= 0) return 0
        val dh = displayHeight.coerceAtLeast(1)
        if (dh == captureHeight) return displayPx.coerceIn(0, captureHeight)
        return ((displayPx.toLong() * captureHeight) / dh).toInt().coerceIn(0, captureHeight)
    }

    fun sourcePixels(context: Context, captureHeight: Int): Int {
        val displayHeight: Int
        val insetTop: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            displayHeight = metrics.bounds.height().coerceAtLeast(1)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
            )
            insetTop = insets.top
        } else {
            displayHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            insetTop = 0
        }
        val fallback = resourceStatusBarHeight(context)
        val top = if (insetTop > 0) insetTop else fallback
        return scalePx(top, displayHeight, captureHeight)
    }

    /** OpenGL scissor origin is bottom-left; cover is the top of the frame. */
    fun glScissor(destWidth: Int, destHeight: Int, coverTopPx: Int): IntArray? {
        val h = coverTopPx.coerceAtMost(destHeight)
        if (h <= 0 || destWidth <= 0 || destHeight <= 0) return null
        return intArrayOf(0, destHeight - h, destWidth, h)
    }

    fun resourceStatusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) return context.resources.getDimensionPixelSize(id)
        return (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    fun drawboxFilter(coverTopPx: Int): String? {
        if (coverTopPx <= 0) return null
        return "drawbox=x=0:y=0:w=iw:h=$coverTopPx:color=black:t=fill"
    }
}
