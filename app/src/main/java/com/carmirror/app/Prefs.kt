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

    var dim: Boolean
        get() = sp.getBoolean("dim", true)
        set(v) { sp.edit().putBoolean("dim", v).apply() }

    /** 0..100, % of max brightness while mirroring */
    var dimLevel: Int
        get() = sp.getInt("dimLevel", 0)
        set(v) { sp.edit().putInt("dimLevel", v).apply() }

    var fps30: Boolean
        get() = sp.getBoolean("fps30", true)
        set(v) { sp.edit().putBoolean("fps30", v).apply() }

    var openLast: Boolean
        get() = sp.getBoolean("openLast", false)
        set(v) { sp.edit().putBoolean("openLast", v).apply() }

    var lastApp: String?
        get() = sp.getString("lastApp", null)
        set(v) { sp.edit().putString("lastApp", v).apply() }

    // brightness backup so we can always restore (even after a crash)
    var savedBrightness: Int
        get() = sp.getInt("savedBr", -1)
        set(v) { sp.edit().putInt("savedBr", v).apply() }
    var savedMode: Int
        get() = sp.getInt("savedMode", -1)
        set(v) { sp.edit().putInt("savedMode", v).apply() }

    private fun installed(pkg: String) =
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    companion object {
        val DEFAULTS = listOf(
            "com.google.android.youtube", "com.spotify.music",
            "com.google.android.apps.maps", "com.android.chrome"
        )
    }
}
