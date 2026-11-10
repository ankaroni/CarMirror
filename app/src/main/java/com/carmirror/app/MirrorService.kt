package com.carmirror.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log

class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private var nativeW = 2340; private var nativeH = 1080; private var dpi = 420

    /** Smallest capture size that still covers the car surface 1:1 (fill or fit), capped at native. */
    private fun targetSize(carW: Int, carH: Int): Pair<Int, Int> {
        val at = nativeW.toFloat() / nativeH
        var h = if (carW > 0 && carH > 0) maxOf(carH.toFloat(), carW / at).toInt() else 1080
        h = h.coerceIn(minOf(886, nativeH), nativeH)
        val w = (h * at).toInt()
        return (w and 1.inv()) to (h and 1.inv())
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(false); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        // Android 14: startForeground BEFORE getMediaProjection
        startForeground(1, notification("Yansıtma aktif"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (data == null || code != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        try { begin(code, data) } catch (e: Exception) {
            Log.e(TAG, "begin failed", e); shutdown(false)
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        A11yEnabler.ensure(this)
        teardown()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection onStop")
                main.post { shutdown(true) }
            }
        }, main)

        // Landscape native size; capture resolution follows the car screen (sharp, not oversized)
        val dm = DisplayMetrics()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0).getRealMetrics(dm)
        nativeW = maxOf(dm.widthPixels, dm.heightPixels); nativeH = minOf(dm.widthPixels, dm.heightPixels)
        dpi = dm.densityDpi
        val (w, h) = targetSize(MirrorHub.carW, MirrorHub.carH)

        val r = MirrorRenderer(w, h, Prefs(this).fill)
        r.fps30 = Prefs(this).fps30
        val surface = r.start()
        MirrorHub.renderer = r
        vd = p.createVirtualDisplay("CarMirror", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        MirrorHub.onCarSize = { cw, ch ->
            main.post {
                val (nw, nh) = targetSize(cw, ch)
                val rr = MirrorHub.renderer
                if (rr != null && (Math.abs(nw - rr.vdW) > 64 || Math.abs(nh - rr.vdH) > 64)) {
                    rr.setInputSize(nw, nh); vd?.resize(nw, nh, dpi)
                }
            }
        }
        MirrorHub.carSurface?.let { r.setOutput(it, MirrorHub.carW, MirrorHub.carH) }

        MirrorHub.active = true
        MirrorHub.awaitingConsentUntil = 0
        MirrorAccessibilityService.instance?.setKeepLandscape(true)
        MirrorHub.notifyChanged()
        ProjectionRequester.cancelNotification(this)
        MirrorHub.pendingPkg?.let { pkg ->
            MirrorHub.pendingPkg = null
            main.postDelayed({ AppLauncher.launch(this, pkg) }, 600)
        }
    }

    private fun teardown() {
        MirrorHub.onCarSize = null
        vd?.release(); vd = null
        MirrorHub.renderer?.let { it.release() }
        MirrorHub.renderer = null
        projection?.stop(); projection = null
    }

    private fun shutdown(restart: Boolean) {
        val wasActive = MirrorHub.active
        MirrorHub.active = false
        teardown()
        MirrorAccessibilityService.instance?.setKeepLandscape(false)
        MirrorHub.notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (restart && wasActive && Prefs(this).autoStart && CarMirrorSession.connected) {
            main.postDelayed({ ProjectionRequester.request(applicationContext) }, 800)
        }
    }

    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mirror", "CarMirror", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mirror").setContentTitle("CarMirror").setContentText(text)
            .setSmallIcon(R.drawable.ic_menu).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build()).build()
    }

    companion object {
        const val TAG = "CarMirror"
        const val ACTION_STOP = "com.carmirror.STOP"
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
