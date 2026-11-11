package com.carmirror.app

import android.app.*
import android.content.Context
import android.content.Intent

object ProjectionRequester {
    private const val NID = 2
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    /** Keep asking (every 25s, max 8 times) while the car is connected and no mirroring is active. */
    fun requestWithRetry(ctx: Context, attempt: Int = 0) {
        if (MirrorHub.active || attempt >= 8) return
        if (!MirrorHub.carConnected && !CarMirrorSession.connected) return
        request(ctx)
        h.postDelayed({ requestWithRetry(ctx, attempt + 1) }, 25_000)
    }

    fun request(ctx: Context) {
        if (MirrorHub.active) return
        CmLog.i("request projection")
        A11yEnabler.ensure(ctx)
        MirrorHub.awaitingConsentUntil = System.currentTimeMillis() + 20_000
        val i = Intent(ctx, ProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { ctx.startActivity(i) } catch (_: Exception) {}
        // Fallback in case background activity start is blocked: tap notification.
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("req", "CarMirror izin", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NID, Notification.Builder(ctx, "req").setContentTitle("CarMirror")
            .setContentText("Yansıtmayı başlatmak için dokun").setSmallIcon(R.drawable.ic_menu)
            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build())
    }

    fun cancelNotification(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(NID)
}
