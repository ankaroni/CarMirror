package com.carmirror.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

class ProjectionRequestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        MirrorHub.awaitingConsentUntil = System.currentTimeMillis() + 20_000
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        MirrorHub.awaitingConsentUntil = 0
        CmLog.i("projection consent result=$res")
        if (res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, MirrorService::class.java)
                .putExtra("code", res).putExtra("data", data))
        } else MirrorHub.pendingPkg = null
        ProjectionRequester.cancelNotification(this)
        finish()
    }
}
