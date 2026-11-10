package com.carmirror.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val listener = { runOnUiThread { build() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(root) })
        MirrorHub.listeners.add(listener)
        // Self-enable accessibility if WRITE_SECURE_SETTINGS was granted via adb
        enableAccessibilityIfPossible()
    }
    override fun onResume() { super.onResume(); build() }
    override fun onDestroy() { MirrorHub.listeners.remove(listener); super.onDestroy() }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/${MirrorAccessibilityService::class.java.name}")
    }

    private fun enableAccessibilityIfPossible() {
        if (a11yOn() || checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val cn = ComponentName(this, MirrorAccessibilityService::class.java).flattenToString()
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (cur.isNullOrEmpty()) cn else "$cur:$cn")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun row(title: String, ok: Boolean, onTap: () -> Unit) {
        val t = TextView(this).apply {
            text = (if (ok) "●  " else "●  ") + title
            textSize = 18f; setPadding(24, 28, 24, 28)
            setTextColor(if (ok) Color.rgb(30, 140, 60) else Color.rgb(200, 40, 40))
            setOnClickListener { onTap() }
        }
        root.addView(t)
    }

    private fun build() {
        root.removeAllViews()
        val prefs = Prefs(this)
        root.addView(TextView(this).apply { text = "CarMirror"; textSize = 28f })
        root.addView(TextView(this).apply { text = "İzinler (dokun → git)"; textSize = 14f; setPadding(0, 30, 0, 8) })
        row("Erişilebilirlik: " + if (a11yOn()) "AÇIK" else "KAPALI", a11yOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        row("Bildirim: " + if (notifOk) "AÇIK" else "KAPALI", notifOk) {
            if (!notifOk) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        row("Ekran yakalama: " + if (MirrorHub.active) "AKTİF" else "KAPALI (dokun → başlat)", MirrorHub.active) {
            if (MirrorHub.active) MirrorService.stop(this) else ProjectionRequester.request(this)
        }

        val sw = Switch(this).apply { text = "Android Auto bağlanınca otomatik başlat"; isChecked = prefs.autoStart
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.autoStart = c } }
        val fl = Switch(this).apply { text = "Siyah kenarsız doldur (kırpar)"; isChecked = prefs.fill
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.fill = c; MirrorHub.renderer?.fill = c; MirrorHub.renderer?.redraw() } }
        root.addView(sw); root.addView(fl)

        root.addView(TextView(this).apply { text = "Araç menüsündeki uygulamalar"; textSize = 14f; setPadding(0, 30, 0, 8) })
        val launch = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second.lowercase() }
        val chosen = prefs.apps.toMutableList()
        for ((pkg, label) in launch) {
            root.addView(CheckBox(this).apply {
                text = label; isChecked = pkg in chosen
                setOnCheckedChangeListener { _, c ->
                    if (c && pkg !in chosen) chosen.add(pkg) else if (!c) chosen.remove(pkg)
                    prefs.apps = chosen.toList(); MirrorHub.notifyChanged()
                }
            })
        }
    }
}
