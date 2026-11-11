package com.carmirror.app

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives the virtual display frames in a SurfaceTexture and draws them
 * onto the car surface (center-crop = no black bars, or letterbox fit).
 */
class MirrorRenderer(vdW0: Int, vdH0: Int, @Volatile var fill: Boolean) {
    @Volatile var vdW = vdW0; private set
    @Volatile var vdH = vdH0; private set

    fun setInputSize(w: Int, h: Int) {
        vdW = w; vdH = h
        handler.post { st?.setDefaultBufferSize(w, h) }
    }

    private val thread = HandlerThread("mirror-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var win: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cfg: EGLConfig? = null
    private var program = 0
    private var tex = 0
    private var st: SurfaceTexture? = null
    private var inSurface: Surface? = null
    private val tm = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile private var outW = 0
    @Volatile private var outH = 0
    // usable (unobstructed) area inside the car surface, top-left origin
    @Volatile private var ax = 0; @Volatile private var ay = 0
    @Volatile private var aw = 0; @Volatile private var ah = 0

    fun setArea(l: Int, t: Int, r: Int, b: Int) {
        ax = l; ay = t; aw = r - l; ah = b - t
        handler.post { render(false) }
    }
    private fun areaW() = if (aw > 0) aw else outW
    private fun areaH() = if (ah > 0) ah else outH
    private var hasFrame = false
    @Volatile var fps30 = true
    private var lastDraw = 0L
    private val retry = Runnable { render(false) }

    private val quad = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
    private val quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(quad); position(0) }

    fun start(): Surface {
        val latch = CountDownLatch(1)
        handler.post { initGl(); latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return inSurface!!
    }

    private fun initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)

        program = buildProgram()
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(tex).apply {
            setDefaultBufferSize(vdW, vdH)
            setOnFrameAvailableListener({ render(true) }, handler)
        }
        inSurface = Surface(st)
    }

    private fun buildProgram(): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        val vs = sh(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPos; attribute vec2 aTex;
            uniform mat4 uTex; uniform vec2 uScale; uniform vec2 uOff;
            varying vec2 vTex;
            void main(){ gl_Position=aPos; vec2 t=aTex*uScale+uOff; vTex=(uTex*vec4(t,0.0,1.0)).xy; }""")
        val fs = sh(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex; varying vec2 vTex;
            void main(){ gl_FragColor=texture2D(sTex,vTex); }""")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it)
        }
    }

    /** Fill only when it crops very little (<=6%); otherwise fit so no UI edge is ever cut off. */
    private fun useFill(): Boolean {
        if (!fill || outW == 0 || outH == 0) return false
        val ac = areaW().toFloat() / areaH(); val at = vdW.toFloat() / vdH
        val crop = if (ac < at) 1f - ac / at else 1f - at / ac
        return crop <= 0.06f
    }

    /** Fraction of the texture visible (fill) – shared with touch mapping. */
    private fun cropScale(): FloatArray {
        val ac = areaW().toFloat() / areaH(); val at = vdW.toFloat() / vdH
        return if (!useFill()) floatArrayOf(1f, 1f)
        else if (ac < at) floatArrayOf(ac / at, 1f) else floatArrayOf(1f, at / ac)
    }

    /** Map car-surface pixel to normalized (0..1, top-left) coordinate inside the virtual display. */
    fun carToVd(cx: Float, cy: Float): FloatArray? {
        if (outW == 0 || outH == 0) return null
        val aW = areaW().toFloat(); val aH = areaH().toFloat()
        val oX = if (aw > 0) ax.toFloat() else 0f; val oY = if (ah > 0) ay.toFloat() else 0f
        var u = (cx - oX) / aW; var v = (cy - oY) / aH
        if (useFill()) {
            val s = cropScale()
            u = 0.5f + (u - 0.5f) * s[0]; v = 0.5f + (v - 0.5f) * s[1]
        } else {
            val ac = aW / aH; val at = vdW.toFloat() / vdH
            if (ac < at) { val f = ac / at; v = 0.5f + (v - 0.5f) / f } else { val f = at / ac; u = 0.5f + (u - 0.5f) / f }
        }
        return floatArrayOf(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    fun setOutput(s: Surface, w: Int, h: Int) {
        handler.post {
            releaseWin()
            outW = w; outH = h
            win = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            render(false)
        }
    }

    fun clearOutput() {
        val latch = CountDownLatch(1)
        handler.post { releaseWin(); outW = 0; outH = 0; latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun redraw() = handler.post { render(false) }

    private fun releaseWin() {
        if (win != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
            EGL14.eglDestroySurface(dpy, win); win = EGL14.EGL_NO_SURFACE
        }
    }

    private fun render(newFrame: Boolean) {
        if (dpy == EGL14.EGL_NO_DISPLAY) return
        val target = if (win != EGL14.EGL_NO_SURFACE) win else pbuf
        EGL14.eglMakeCurrent(dpy, target, target, ctx)
        if (newFrame) { st?.updateTexImage(); st?.getTransformMatrix(tm); hasFrame = true }
        if (win == EGL14.EGL_NO_SURFACE || !hasFrame) return
        if (fps30 && newFrame) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDraw < 30) {           // drop frame, but make sure the last one still gets drawn
                handler.removeCallbacks(retry); handler.postDelayed(retry, 34); return
            }
        }
        lastDraw = android.os.SystemClock.uptimeMillis()
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val aW = areaW(); val aH = areaH()
        val oX = if (aw > 0) ax else 0; val oY = if (ah > 0) ay else 0
        val ac = aW.toFloat() / aH; val at = vdW.toFloat() / vdH
        var vx = oX; var vy = oY; var vw = aW; var vh = aH
        if (!useFill()) {
            if (ac < at) { vh = (aW / at).toInt(); vy = oY + (aH - vh) / 2 }
            else { vw = (aH * at).toInt(); vx = oX + (aW - vw) / 2 }
        }
        GLES20.glViewport(vx, outH - (vy + vh), vw, vh)
        val s = cropScale()
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, tm, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), s[0], s[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOff"), (1 - s[0]) / 2, (1 - s[1]) / 2)
        val ap = GLES20.glGetAttribLocation(program, "aPos"); val at2 = GLES20.glGetAttribLocation(program, "aTex")
        quadBuf.position(0); GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
        GLES20.glEnableVertexAttribArray(ap)
        quadBuf.position(2); GLES20.glVertexAttribPointer(at2, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
        GLES20.glEnableVertexAttribArray(at2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(dpy, win)
    }

    fun release() {
        handler.post {
            releaseWin()
            inSurface?.release(); st?.release()
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, pbuf); EGL14.eglDestroyContext(dpy, ctx); EGL14.eglTerminate(dpy)
                dpy = EGL14.EGL_NO_DISPLAY
            }
            thread.quitSafely()
        }
    }
}
