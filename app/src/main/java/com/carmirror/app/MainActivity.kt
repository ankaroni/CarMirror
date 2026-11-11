package com.carmirror.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    // ---- palette ----
    private val cBg = 0xFF0D1117.toInt()
    private val cCard = 0xFF161B22.toInt()
    private val cStroke = 0xFF262C36.toInt()
    private val cText = 0xFFE6EDF3.toInt()
    private val cMuted = 0xFF8B949E.toInt()
    private val cAccent = 0xFF4C8DFF.toInt()
    private val cOk = 0xFF3FB950.toInt()
    private val cBad = 0xFFF85149.toInt()
    private val cWarn = 0xFFD29922.toInt()

    private lateinit var content: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var heroDot: View
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroBtn: Button
    private lateinit var prefs: Prefs
    private val listener = { runOnUiThread { refreshStatus() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        buildUi()
        MirrorHub.listeners.add(listener)
        A11yEnabler.ensure(this)          // self-enable accessibility if WRITE_SECURE_SETTINGS was granted via adb
        ConnectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!MirrorHub.active) MirrorAccessibilityService.instance?.let { it.applyDim(false); it.applyRotation(false) }
        refreshStatus()
    }

    override fun onDestroy() { MirrorHub.listeners.remove(listener); super.onDestroy() }

    // ---------------------------------------------------------------- building blocks

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(parent: ViewGroup): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cCard, 18, cStroke)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun sectionTitle(parent: ViewGroup, t: String) {
        parent.addView(text(t.uppercase(), 12f, cMuted, true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(8); marginStart = dp(6) })
    }

    private fun divider(parent: ViewGroup) {
        parent.addView(View(this).apply { setBackgroundColor(cStroke) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun dot(color: Int) = View(this).apply { background = round(color, 8) }

    private fun tinted(sw: Switch) = sw.apply {
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B8C4.toInt()))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF5B9BFF.toInt(), 0xFF3A414C.toInt()))
    }

    /** Permission / state row: coloured dot, title, state text, chevron. */
    private fun statusRow(parent: ViewGroup, title: String, state: String, ok: Boolean, onTap: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { onTap() }
        }
        row.addView(dot(if (ok) cOk else cBad), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, cText))
        col.addView(text(state, 12f, if (ok) cOk else cBad))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("›", 22f, cMuted))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun switchRow(parent: ViewGroup, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, cText))
        if (sub.isNotEmpty()) col.addView(text(sub, 12f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val sw = tinted(Switch(this)).apply { isChecked = checked; setOnCheckedChangeListener { _, c -> onChange(c) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------------------------------------------------------------- screen

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(cBg); isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_app); background = round(cAccent, 14); clipToOutline = true },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(text("CarMirror", 26f, cText, true))
        ht.addView(text("Android Auto ekran yansıtma", 13f, cMuted))
        header.addView(ht)
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(18) })

        // hero
        val hero = card(content).apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroDot = dot(cMuted)
        hr.addView(heroDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = text("", 18f, cText, true); heroSub = text("", 13f, cMuted)
        hc.addView(heroTitle); hc.addView(heroSub)
        hr.addView(hc, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(hr)
        heroBtn = Button(this).apply {
            isAllCaps = false; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            background = round(cAccent, 12)
            setOnClickListener { if (MirrorHub.active) MirrorService.stop(this@MainActivity) else ProjectionRequester.request(this@MainActivity) }
        }
        hero.addView(heroBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })

        // permissions (rebuilt by refreshStatus)
        sectionTitle(content, "İzinler")
        statusBox = card(content)

        // mirroring settings
        sectionTitle(content, "Yansıtma")
        val mc = card(content)
        switchRow(mc, "Bağlanınca otomatik başlat", "Android Auto bağlanınca yansıtma kendiliğinden başlar", prefs.autoStart) { prefs.autoStart = it }
        divider(mc)
        switchRow(mc, "Bağlanınca son uygulamayı aç", "En son araçtan açtığın uygulama tekrar açılır", prefs.openLast) { prefs.openLast = it }
        divider(mc)
        switchRow(mc, "Ekranı doldur", "Siyah kenarı azaltır, kenarlardan kırpabilir", prefs.fill) {
            prefs.fill = it; MirrorHub.renderer?.fill = it; MirrorHub.renderer?.redraw()
        }
        divider(mc)
        switchRow(mc, "Yatayı zorla", "Dikey çalışan uygulamalar yan yatabilir", prefs.forceLandscape) {
            prefs.forceLandscape = it; if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true)
        }

        sectionTitle(content, "Performans ve ısı")
        val pc = card(content)
        switchRow(pc, "Hızlı mod", "Düşük gecikme: görüntü biraz yumuşar. Yansıtmayı yeniden başlatınca geçerli", prefs.fastMode) { prefs.fastMode = it }
        divider(pc)
        switchRow(pc, "30 fps ısı tasarrufu", "Kapalıyken 60 fps", prefs.fps30) { prefs.fps30 = it; MirrorHub.renderer?.fps30 = it }
        divider(pc)
        switchRow(pc, "Telefon ekranını karart", "Yansıtırken parlaklığı düşürür, ısınmayı azaltır", prefs.dim) {
            prefs.dim = it; if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true)
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        val lbl = text("Karartma parlaklığı: %${prefs.dimLevel}", 13f, cMuted)
        seekRow.addView(lbl)
        seekRow.addView(SeekBar(this).apply {
            max = 50; progress = prefs.dimLevel
            progressTintList = ColorStateList.valueOf(cAccent); thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.dimLevel = p; lbl.text = "Karartma parlaklığı: %$p" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { if (MirrorHub.active) MirrorAccessibilityService.instance?.setKeepLandscape(true) }
            })
        })
        pc.addView(seekRow, LinearLayout.LayoutParams(-1, -2))

        // car menu apps
        sectionTitle(content, "Araç menüsündeki uygulamalar")
        buildAppList(card(content))

        // diagnostics
        sectionTitle(content, "Tanılama")
        val dc = card(content)
        val logView = text("", 10f, cMuted).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); visibility = View.GONE; setPadding(0, dp(8), 0, dp(12))
        }
        statusRowStatic(dc, "Günlüğü göster / gizle", "Araçta sorun olursa buna bak") {
            if (logView.visibility == View.GONE) { logView.text = CmLog.tail(80).ifEmpty { "(boş)" }; logView.visibility = View.VISIBLE }
            else logView.visibility = View.GONE
        }
        dc.addView(logView, LinearLayout.LayoutParams(-1, -2))

        content.addView(text("CarMirror 1.0", 11f, cMuted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun statusRowStatic(parent: ViewGroup, title: String, sub: String, onTap: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14)); setOnClickListener { onTap() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, cText)); col.addView(text(sub, 12f, cMuted))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("›", 22f, cMuted))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildAppList(parent: LinearLayout) {
        val pm = packageManager
        val chosen = prefs.apps.toMutableList()
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != packageName }
            .sortedWith(compareBy({ it.first !in chosen }, { it.second.lowercase() }))
        val icons = ArrayList<Pair<ImageView, String>>()
        for ((i, app) in launch.withIndex()) {
            val (pkg, label) = app
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
            }
            val iv = ImageView(this).apply { background = round(cStroke, 10); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3)) }
            icons.add(iv to pkg)
            row.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            row.addView(text(label, 15f, cText), LinearLayout.LayoutParams(0, -2, 1f))
            val sw = tinted(Switch(this)).apply {
                isChecked = pkg in chosen
                setOnCheckedChangeListener { _, c ->
                    if (c && pkg !in chosen) chosen.add(pkg) else if (!c) chosen.remove(pkg)
                    prefs.apps = chosen.toList(); MirrorHub.notifyChanged()
                }
            }
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (i < launch.size - 1) divider(parent)
        }
        // icons are loaded off the UI thread so the screen opens instantly
        Thread {
            for ((iv, pkg) in icons) {
                val d = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null } ?: continue
                runOnUiThread { if (!isDestroyed) iv.setImageDrawable(d) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- live status

    private fun refreshStatus() {
        if (!::statusBox.isInitialized) return
        val a11y = A11yEnabler.isEnabled(this)
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val bat = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val ws = Settings.System.canWrite(this)
        val missing = listOf(a11y, notif, bat, ws).count { !it }

        // hero
        when {
            MirrorHub.active -> { heroDot.background = round(cOk, 8); heroTitle.text = "Yansıtma çalışıyor"; heroSub.text = "Telefon ekranı araç ekranında" }
            MirrorHub.carConnected -> { heroDot.background = round(cWarn, 8); heroTitle.text = "Araç bağlı"; heroSub.text = "Yansıtma başlatılmayı bekliyor" }
            else -> { heroDot.background = round(cMuted, 8); heroTitle.text = "Araç bekleniyor"; heroSub.text = "Telefonu arabaya bağlayınca otomatik başlar" }
        }
        if (missing > 0) heroSub.text = "${heroSub.text}  •  $missing izin eksik"
        heroBtn.text = if (MirrorHub.active) "Yansıtmayı durdur" else "Şimdi başlat"
        heroBtn.background = round(if (MirrorHub.active) cBad else cAccent, 12)

        // permissions
        statusBox.removeAllViews()
        statusRow(statusBox, "Erişilebilirlik", if (a11y) "Açık" else "Kapalı, dokunup aç", a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        divider(statusBox)
        statusRow(statusBox, "Bildirim", if (notif) "Açık" else "Kapalı, dokunup izin ver", notif) {
            if (!notif) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        divider(statusBox)
        statusRow(statusBox, "Pil optimizasyonu", if (bat) "Sınırsız" else "Kısıtlı, dokunup sınırsız yap", bat) {
            if (!bat) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        divider(statusBox)
        statusRow(statusBox, "Sistem ayarlarını değiştirme", if (ws) "Açık (tam karartma ve döndürme)" else "Kapalı, dokunup aç", ws) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
