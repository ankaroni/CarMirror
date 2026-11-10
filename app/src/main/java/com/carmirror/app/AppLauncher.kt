package com.carmirror.app

import android.content.Context
import android.content.Intent

object AppLauncher {
    fun launch(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val svc = MirrorAccessibilityService.instance
        try { (svc ?: ctx).startActivity(i) } catch (_: Exception) {}
    }
}
