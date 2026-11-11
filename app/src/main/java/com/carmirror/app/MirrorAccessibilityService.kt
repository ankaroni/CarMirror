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

    override fun onServiceConnected() {
        CmLog.i("a11y connected"); instance = this; main.post(poll)
        if (MirrorHub.active) setKeepLandscape(true)
    }
    override fun onDestroy() { CmLog.i("a11y destroyed"); instance = null; super.onDestroy() }
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
        gesture(p[0], p[1], p[0], p[1], if (Prefs(this).fastMode) 20 else 40)
    }

    fun swipeCar(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val a = carToPhone(x1, y1) ?: return; val b = carToPhone(x2, y2) ?: return
        gesture(a[0], a[1], b[0], b[1], dur)
    }

    // ---- continuous drag (scroll that follows the finger) ----
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragCur = FloatArray(2)
    private var dragPending: FloatArray? = null
    private var dragBusy = false
    private var dragEnding = false
    private var dragActive = false

    fun dragStart(carX: Float, carY: Float) {
        val p = carToPhone(carX, carY) ?: return
        dragCur = p; dragStroke = null; dragPending = null; dragBusy = false; dragEnding = false; dragActive = true
    }

    fun dragMove(carX: Float, carY: Float) {
        if (!dragActive) return
        val t = carToPhone(carX, carY) ?: return
        dragPending = t
        if (!dragBusy) dragPump()
    }

    fun dragEnd() {
        if (!dragActive) return
        dragEnding = true
        if (!dragBusy) dragPump()
    }

    private fun dragPump() {
        val ending = dragEnding
        val t = dragPending ?: if (ending) dragCur else return
        dragPending = null
        val path = Path().apply { moveTo(dragCur[0], dragCur[1]); lineTo(t[0] + if (t[0] == dragCur[0]) 0.5f else 0f, t[1]) }
        val last = ending && dragPending == null
        val prev = dragStroke
        val stroke = try {
            if (prev == null) GestureDescription.StrokeDescription(path, 0, 16, !last)
            else prev.continueStroke(path, 0, 16, !last)
        } catch (e: Exception) { CmLog.i("drag stroke error $e"); dragReset(); return }
        dragStroke = stroke
        dragBusy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    dragCur = t; dragBusy = false
                    if (last) dragReset() else if (dragPending != null || dragEnding) dragPump()
                }
                override fun onCancelled(d: GestureDescription?) { dragReset() }
            }, main)
        if (!ok) dragReset()
    }

    private fun dragReset() { dragStroke = null; dragPending = null; dragBusy = false; dragEnding = false; dragActive = false }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val ok = dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
        CmLog.i("gesture $x1,$y1 -> $x2,$y2 ok=$ok")
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

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
            val prefs = Prefs(this)
            if (prefs.forceLandscape) lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (prefs.dim) lp.screenBrightness = maxOf(prefs.dimLevel / 100f, 0.003f)
            try { wm.addView(v, lp); overlay = v } catch (_: Exception) {}
        }
        applyDim(on)
        applyRotation(on)
    }

    /** Soft landscape: lock system rotation to landscape. Portrait-only apps still render upright. */
    fun applyRotation(on: Boolean) {
        val prefs = Prefs(this)
        try {
            if (!android.provider.Settings.System.canWrite(this)) return
            val cr = contentResolver
            if (on) {
                if (prefs.savedAccel < 0) {
                    prefs.savedAccel = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1)
                    prefs.savedUserRot = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.USER_ROTATION, 0)
                }
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1)
            } else if (prefs.savedAccel >= 0) {
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.USER_ROTATION, prefs.savedUserRot)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, prefs.savedAccel)
                prefs.savedAccel = -1
            }
        } catch (_: Exception) {}
    }

    /** System-level dimming (needs WRITE_SETTINGS) on top of the window override; always restored. */
    fun applyDim(on: Boolean) {
        val prefs = Prefs(this)
        try {
            if (!android.provider.Settings.System.canWrite(this)) return
            val cr = contentResolver
            if (on && prefs.dim) {
                if (prefs.savedBrightness < 0) {
                    prefs.savedBrightness = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
                    prefs.savedMode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                }
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, (prefs.dimLevel * 2.55f).toInt().coerceAtLeast(1))
            } else if (prefs.savedBrightness >= 0) {
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, prefs.savedBrightness)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.savedMode.coerceAtLeast(0))
                prefs.savedBrightness = -1
            }
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile var instance: MirrorAccessibilityService? = null
        val START_TEXTS = listOf("Şimdi başlat", "Start now", "Ekranı paylaş", "Share screen", "Başlat", "Start")
    }
}
