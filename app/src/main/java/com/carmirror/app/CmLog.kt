package com.carmirror.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent log (survives process death) so car-side problems can be diagnosed afterwards. */
object CmLog {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) { file = File(ctx.filesDir, "carmirror.log") }

    @Synchronized fun i(msg: String) {
        Log.i("CarMirror", msg)
        val f = file ?: return
        try {
            if (f.length() > 400_000) {
                val keep = f.readText().takeLast(150_000)
                f.writeText(keep)
            }
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) {}
    }

    fun tail(n: Int): String = try {
        file?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    fun clear() { try { file?.writeText("") } catch (_: Exception) {} }
}

class CarMirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CmLog.init(this)
        CmLog.i("=== process start pid=${android.os.Process.myPid()} sdk=${Build.VERSION.SDK_INT} ${Build.MODEL}")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            CmLog.i("CRASH on ${t.name}: ${Log.getStackTraceString(e).take(1500)}")
            old?.uncaughtException(t, e)
        }
    }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); CmLog.i("onTrimMemory level=$level") }
    override fun onLowMemory() { super.onLowMemory(); CmLog.i("onLowMemory") }
}
