package com.carmirror.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MirrorAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    override fun onServiceConnected() { instance = this; main.post(poll) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) { tryConsent() }

    private val poll: Runnable = object : Runnable {
        override fun run() { tryConsent(); main.postDelayed(this, 700) }
    }

    /** Auto-confirm the MediaProjection consent dialog ("Start now"). */
    private fun tryConsent() {
        if (System.currentTimeMillis() > MirrorHub.awaitingConsentUntil) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString()?.contains("systemui") != true) return
        // Prefer entire-screen if a chooser is shown
        for (t in listOf("Tüm ekran", "Entire screen", "Share entire screen")) {
            root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isClickable }?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        }
        for (t in START_TEXTS) {
            val n = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isClickable && it.className?.contains("Button") == true }
                ?: root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isClickable }
            if (n != null) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
        }
    }

    // --- phone control ---
    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(0).getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    /** car surface pixel -> phone pixel */
    fun carToPhone(cx: Float, cy: Float): FloatArray? {
        val r = MirrorHub.renderer ?: return null
        val vd = r.carToVd(cx, cy) ?: return null
        val s = realSize(); val rw = s[0].toFloat(); val rh = s[1].toFloat()
        val av = r.vdW.toFloat() / r.vdH; val ap = rw / rh
        var u = vd[0]; var v = vd[1]
        if (ap < av) { u = 0.5f + (u - 0.5f) / (ap / av) } else if (ap > av) { v = 0.5f + (v - 0.5f) / (av / ap) }
        return floatArrayOf((u * rw).coerceIn(1f, rw - 1), (v * rh).coerceIn(1f, rh - 1))
    }

    fun tapCar(cx: Float, cy: Float) {
        val p = carToPhone(cx, cy) ?: return
        gesture(p[0], p[1], p[0], p[1], 40)
    }

    fun swipeCar(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val a = carToPhone(x1, y1) ?: return; val b = carToPhone(x2, y2) ?: return
        gesture(a[0], a[1], b[0], b[1], dur)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 1x1 overlay that forces landscape and keeps the screen on. */
    fun setKeepLandscape(on: Boolean) {
        main.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            overlay = null
            if (!on) return@post
            val v = View(this)
            v.keepScreenOn = true
            val lp = WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT)
            lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            try { wm.addView(v, lp); overlay = v } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile var instance: MirrorAccessibilityService? = null
        val START_TEXTS = listOf("Şimdi başlat", "Start now", "Ekranı paylaş", "Share screen", "Başlat", "Start")
    }
}
