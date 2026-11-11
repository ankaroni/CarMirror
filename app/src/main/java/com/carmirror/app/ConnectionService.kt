package com.carmirror.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.notification.CarPendingIntent
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer

/** Always-on watcher: starts mirroring as soon as Android Auto connects, stops it on disconnect. */
class ConnectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var observer: Observer<Int>? = null
    private var carConnection: CarConnection? = null

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "CarMirror bekçi", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "watch").setContentTitle("CarMirror")
            .setContentText("Araç bağlantısı bekleniyor").setSmallIcon(R.drawable.ic_menu).setOngoing(true).build()
        startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (observer == null) {
            val o = Observer<Int> { type ->
                val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION
                CmLog.i("car connection type=$type")
                if (connected && !MirrorHub.carConnected) onConnected()
                else if (!connected && MirrorHub.carConnected) onDisconnected()
            }
            observer = o
            main.post { carConnection = CarConnection(applicationContext).also { it.type.observeForever(o) } }
        }
        return START_STICKY
    }

    /** Heads-up notification on the car screen: one tap opens CarMirror there. */
    private fun postCarNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("carhead", "CarMirror araç bildirimi", NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(CarContext.ACTION_NAVIGATE)
                .setComponent(android.content.ComponentName(this, CarMirrorService::class.java))
                .setData(android.net.Uri.parse("geo:0,0"))
            val pi = CarPendingIntent.getCarApp(this, 1, open, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, "carhead")
                .setSmallIcon(R.drawable.ic_menu).setContentTitle("CarMirror")
                .setContentText("Araç ekranında açmak için dokun")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .extend(CarAppExtender.Builder().setImportance(NotificationManagerCompat.IMPORTANCE_HIGH).setContentIntent(pi).build())
                .build()
            nm.notify(4, n)
            CmLog.i("watch: posted car notification")
        } catch (e: Exception) { CmLog.i("watch: car notification failed $e") }
    }

    private fun onConnected() {
        MirrorHub.carConnected = true
        main.removeCallbacks(stopRunnable)
        CmLog.i("watch: car connected")
        main.postDelayed({ if (MirrorHub.carConnected && !CarMirrorSession.connected) postCarNotification() }, 4000)
        val p = Prefs(this)
        if (p.openLast) p.lastApp?.let { MirrorHub.pendingPkg = it }
        if (p.autoStart && !MirrorHub.active) {
            main.postDelayed({ if (MirrorHub.carConnected) ProjectionRequester.requestWithRetry(applicationContext) }, 1500)
        }
    }

    private val stopRunnable = Runnable {
        if (!MirrorHub.carConnected && MirrorHub.active) { CmLog.i("watch: disconnect confirmed, stopping mirror"); MirrorService.stop(this) }
    }

    private fun onDisconnected() {
        MirrorHub.carConnected = false
        CmLog.i("watch: car disconnected (waiting 20s before stopping)")
        // Debounce: Android Auto briefly reports "not connected" while it restarts its projection.
        main.removeCallbacks(stopRunnable); main.postDelayed(stopRunnable, 20_000)
    }

    override fun onTaskRemoved(rootIntent: Intent?) { CmLog.i("watch: onTaskRemoved"); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        CmLog.i("watch: onDestroy")
        observer?.let { o -> carConnection?.type?.removeObserver(o) }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, ConnectionService::class.java)) } catch (_: Exception) {}
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { ConnectionService.start(ctx) }
}
