package com.carmirror.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Re-enables the accessibility service if WRITE_SECURE_SETTINGS was granted (adb pm grant). */
object A11yEnabler {
    fun isEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("${ctx.packageName}/${MirrorAccessibilityService::class.java.name}")
    }

    fun ensure(ctx: Context) {
        if (isEnabled(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val cn = ComponentName(ctx, MirrorAccessibilityService::class.java).flattenToString()
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (cur.isNullOrEmpty()) cn else "$cur:$cn")
        Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
}
