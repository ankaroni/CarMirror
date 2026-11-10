package com.carmirror.app

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide shared state between service, car session and accessibility service. */
object MirrorHub {
    @Volatile var active = false
    @Volatile var renderer: MirrorRenderer? = null
    @Volatile var awaitingConsentUntil = 0L
    @Volatile var pendingPkg: String? = null

    // Car surface state (kept so renderer can pick it up when it starts later)
    @Volatile var carSurface: Surface? = null
    @Volatile var carW = 0
    @Volatile var carH = 0

    @Volatile var onCarSize: ((Int, Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun notifyChanged() = main.post { listeners.forEach { it() } }

    fun setCarSurface(s: Surface?, w: Int, h: Int) {
        carSurface = s; carW = w; carH = h
        val r = renderer
        if (s != null) { r?.setOutput(s, w, h); onCarSize?.invoke(w, h) } else r?.clearOutput()
    }
}
