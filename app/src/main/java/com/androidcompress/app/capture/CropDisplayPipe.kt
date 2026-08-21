package com.androidcompress.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.androidcompress.app.encode.RecordingCrop
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirtualDisplay always mirrors the full screen. This pipe samples a crop
 * rectangle into the encoder surface so MediaRecorder never sees the rest.
 */
class CropDisplayPipe private constructor(
    val inputSurface: Surface,
    private val thread: HandlerThread,
    private val handler: Handler,
    private val surfaceTexture: SurfaceTexture,
    private val textureId: Int,
    private val eglDisplay: EGLDisplay,
    private val eglContext: EGLContext,
    private val eglSurface: EGLSurface,
    private val program: Int,
    private val posHandle: Int,
    private val texHandle: Int,
    private val matrixHandle: Int,
    private val uvBuffer: java.nio.FloatBuffer,
    private val destWidth: Int,
    private val destHeight: Int,
    private val coverTopPx: Int,
) {
    private val texMatrix = FloatArray(16)
    private val paused = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pendingFrame = AtomicBoolean(false)
    private var lastPtsNs = 0L

    fun setPaused(value: Boolean) {
        paused.set(value)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val done = CountDownLatch(1)
        handler.post {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                inputSurface.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                val textures = intArrayOf(textureId)
                GLES20.glDeleteTextures(1, textures, 0)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    // Destroying the window surface signals EOS to MediaRecorder.
                    // Must happen before MediaRecorder.stop() or stop() blocks and
                    // the file is padded with a frozen last frame (~10s).
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } finally {
                done.countDown()
            }
        }
        runCatching { done.await(1, TimeUnit.SECONDS) }
        thread.quitSafely()
    }

    private fun requestFrame() {
        if (released.get()) return
        pendingFrame.set(true)
        if (Looper.myLooper() == handler.looper) {
            drain()
        } else {
            handler.post { drain() }
        }
    }

    private fun drain() {
        if (released.get()) return
        while (pendingFrame.compareAndSet(true, false)) {
            runCatching { drawFrame() }
            if (released.get()) return
        }
    }

    private fun drawFrame() {
        if (released.get()) return
        try {
            surfaceTexture.updateTexImage()
        } catch (_: Throwable) {
            return
        }
        if (paused.get()) return
        surfaceTexture.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, destWidth, destHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)
        VERTICES.position(0)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
        GLES20.glEnableVertexAttribArray(posHandle)
        uvBuffer.position(0)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val scissor = StatusBarCover.glScissor(destWidth, destHeight, coverTopPx)
        if (scissor != null) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        val pts = presentationTimeNs()
        runCatching {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun presentationTimeNs(): Long {
        val tex = surfaceTexture.timestamp
        val candidate = if (tex > lastPtsNs) tex else System.nanoTime()
        lastPtsNs = if (candidate > lastPtsNs) candidate else lastPtsNs + 1_000_000L
        return lastPtsNs
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val VERTICES = java.nio.ByteBuffer.allocateDirect(8 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(
                floatArrayOf(
                    -1f, -1f,
                    1f, -1f,
                    -1f, 1f,
                    1f, 1f,
                ),
            )
            .also { it.position(0) }

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
              gl_Position = aPos;
              vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTex);
            }
        """

        fun cropUv(crop: RecordingCrop, sourceWidth: Int, sourceHeight: Int): FloatArray {
            val w = sourceWidth.coerceAtLeast(1).toFloat()
            val h = sourceHeight.coerceAtLeast(1).toFloat()
            val left = (crop.x / w).coerceIn(0f, 1f)
            val right = ((crop.x + crop.width) / w).coerceIn(0f, 1f)
            val androidTop = (crop.y / h).coerceIn(0f, 1f)
            val androidBottom = ((crop.y + crop.height) / h).coerceIn(0f, 1f)
            // SurfaceTexture's transform expects GL texels: (0,0) is the bottom-left
            // of the image. Display y=0 is the top of the screen, so invert Y.
            // Applying both this invert and the ST matrix used to double-flip
            // full-screen cover recordings.
            val texBottom = 1f - androidBottom
            val texTop = 1f - androidTop
            // Triangle strip: BL, BR, TL, TR in clip space.
            return floatArrayOf(
                left, texBottom,
                right, texBottom,
                left, texTop,
                right, texTop,
            )
        }

        fun start(
            encoderSurface: Surface,
            sourceWidth: Int,
            sourceHeight: Int,
            crop: RecordingCrop,
            coverTopPx: Int = 0,
        ): CropDisplayPipe {
            val thread = HandlerThread("crop-gl").also { it.start() }
            val handler = Handler(thread.looper)
            val ready = CountDownLatch(1)
            var created: CropDisplayPipe? = null
            var error: Throwable? = null
            handler.post {
                try {
                    created = create(
                        encoderSurface,
                        sourceWidth,
                        sourceHeight,
                        crop,
                        coverTopPx,
                        thread,
                        handler,
                    )
                } catch (t: Throwable) {
                    error = t
                } finally {
                    ready.countDown()
                }
            }
            if (!ready.await(2, TimeUnit.SECONDS)) {
                thread.quitSafely()
                error("Timed out starting live crop")
            }
            val pipe = created
            if (pipe == null) {
                thread.quitSafely()
                throw error ?: IllegalStateException("Live crop failed")
            }
            return pipe
        }

        private fun create(
            encoderSurface: Surface,
            sourceWidth: Int,
            sourceHeight: Int,
            crop: RecordingCrop,
            coverTopPx: Int,
            thread: HandlerThread,
            handler: Handler,
        ): CropDisplayPipe {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) &&
                    num[0] > 0 &&
                    configs[0] != null,
            ) { "eglChooseConfig" }
            val context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            val surface = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(sourceWidth, sourceHeight)
            val input = Surface(st)
            val program = buildProgram()
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            val matrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val uv = cropUv(crop, sourceWidth, sourceHeight)
            val uvBuffer = java.nio.ByteBuffer.allocateDirect(uv.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(uv)
            uvBuffer.position(0)
            val pipe = CropDisplayPipe(
                inputSurface = input,
                thread = thread,
                handler = handler,
                surfaceTexture = st,
                textureId = texId,
                eglDisplay = display,
                eglContext = context,
                eglSurface = surface,
                program = program,
                posHandle = pos,
                texHandle = tex,
                matrixHandle = matrix,
                uvBuffer = uvBuffer,
                destWidth = crop.width,
                destHeight = crop.height,
                coverTopPx = coverTopPx.coerceAtLeast(0),
            )
            st.setOnFrameAvailableListener({ pipe.requestFrame() }, handler)
            return pipe
        }

        private fun buildProgram(): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val link = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            check(link[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
    }
}
