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
        A11yEnabler.ensure(this)
        ConnectionService.start(this)
    }
    override fun onResume() { super.onResume(); if (!MirrorHub.active) MirrorAccessibilityService.instance?.let { it.applyDim(false); it.applyRotation(false) }; build() }
    override fun onDestroy() { MirrorHub.listeners.remove(listener); super.onDestroy() }

    private fun a11yOn() = A11yEnabler.isEnabled(this)

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
        val fl = Switch(this).apply { text = "Ekranı doldur (kenarlardan kırpar)"; isChecked = prefs.fill
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.fill = c; MirrorHub.renderer?.fill = c; MirrorHub.renderer?.redraw() } }
        root.addView(sw); root.addView(fl)
        root.addView(Switch(this).apply { text = "Yansıtırken telefon ekranını karart (ısınmayı azaltır)"; isChecked = prefs.dim
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.dim = c; if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true) } })
        val lbl = TextView(this).apply { text = "Karartma parlaklığı: %" + prefs.dimLevel; setPadding(24, 8, 24, 0) }
        root.addView(lbl)
        root.addView(SeekBar(this).apply {
            max = 50; progress = prefs.dimLevel
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.dimLevel = p; lbl.text = "Karartma parlaklığı: %$p" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true) }
            })
        })
        root.addView(Switch(this).apply { text = "Yatayı zorla (dikey uygulamalar yan yatabilir)"; isChecked = prefs.forceLandscape
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.forceLandscape = c; if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true) } })
        root.addView(Switch(this).apply { text = "Hızlı mod: düşük gecikme (görüntü biraz yumuşar, yansıtmayı yeniden başlatınca geçerli)"; isChecked = prefs.fastMode
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.fastMode = c } })
        root.addView(Switch(this).apply { text = "Isı tasarrufu: 30 fps (kapalıyken 60 fps)"; isChecked = prefs.fps30
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.fps30 = c; MirrorHub.renderer?.fps30 = c } })
        root.addView(Switch(this).apply { text = "Bağlanınca son kullanılan uygulamayı aç"; isChecked = prefs.openLast
            setPadding(24, 24, 24, 24); setOnCheckedChangeListener { _, c -> prefs.openLast = c } })
        val pm = getSystemService(android.os.PowerManager::class.java)
        val batOk = pm.isIgnoringBatteryOptimizations(packageName)
        row("Pil optimizasyonu: " + if (batOk) "SINIRSIZ" else "KISITLI (dokun → sınırsız yap)", batOk) {
            if (!batOk) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        val wsOk = android.provider.Settings.System.canWrite(this)
        row("Sistem ayarlarını değiştirme (tam karartma): " + if (wsOk) "AÇIK" else "KAPALI", wsOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }

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
        root.addView(TextView(this).apply { text = "Tanılama günlüğü (araçta sorun olursa buna bak)"; textSize = 14f; setPadding(0, 30, 0, 8) })
        val logView = TextView(this).apply { textSize = 10f; setTextIsSelectable(true); visibility = View.GONE; setPadding(24, 8, 24, 8) }
        row("Günlüğü göster / gizle", true) {
            if (logView.visibility == View.GONE) { logView.text = CmLog.tail(80).ifEmpty { "(boş)" }; logView.visibility = View.VISIBLE }
            else logView.visibility = View.GONE
        }
        root.addView(logView)
    }
}
