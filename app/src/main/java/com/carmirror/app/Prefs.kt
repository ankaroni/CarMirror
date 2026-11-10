package com.carmirror.app

import android.content.Context

class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("carmirror", Context.MODE_PRIVATE)

    var apps: List<String>
        get() {
            val raw = sp.getString("apps", null)
            val list = raw?.split(",")?.filter { it.isNotBlank() } ?: DEFAULTS
            return list.filter { installed(it) }
        }
        set(v) { sp.edit().putString("apps", v.joinToString(",")).apply() }

    var fill: Boolean
        get() = sp.getBoolean("fill", true)
        set(v) { sp.edit().putBoolean("fill", v).apply() }

    var autoStart: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) { sp.edit().putBoolean("auto", v).apply() }

    private fun installed(pkg: String) =
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    companion object {
        val DEFAULTS = listOf(
            "com.google.android.youtube", "com.spotify.music",
            "com.google.android.apps.maps", "com.android.chrome"
        )
    }
}
