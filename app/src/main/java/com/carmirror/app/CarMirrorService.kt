package com.carmirror.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.*
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarMirrorService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session { CmLog.i("car: onCreateSession"); return CarMirrorSession() }
    override fun onDestroy() { CmLog.i("car: service onDestroy"); super.onDestroy() }
}

class CarMirrorSession : Session() {
    private val main = Handler(Looper.getMainLooper())
    private var scrollX = 0f; private var scrollY = 0f
    private var dragging = false
    @Volatile private var lastTouch = 0L
    private val endDrag = Runnable {
        MirrorAccessibilityService.instance?.dragEnd()
        dragging = false; scrollX = 0f; scrollY = 0f
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(c: SurfaceContainer) {
            CmLog.i("surface available ${c.width}x${c.height}")
            val s: Surface? = c.surface
            MirrorHub.setCarSurface(s, c.width, c.height)
        }
        override fun onStableAreaChanged(r: android.graphics.Rect) {
            CmLog.i("stableArea $r")
        }
        override fun onVisibleAreaChanged(r: android.graphics.Rect) {
            CmLog.i("visibleArea $r")
            // Use only the horizontal extent (side panels/media card); keep full height
            val a = android.graphics.Rect(r.left, 0, r.right, MirrorHub.carH)
            MirrorHub.area = a
            MirrorHub.renderer?.setArea(a.left, a.top, a.right, a.bottom)
        }
        override fun onSurfaceDestroyed(c: SurfaceContainer) { CmLog.i("car: surface destroyed"); MirrorHub.setCarSurface(null, 0, 0) }
        override fun onClick(x: Float, y: Float) {
            CmLog.i("surface onClick $x,$y a11y=${MirrorAccessibilityService.instance != null} r=${MirrorHub.renderer != null}")
            val now = System.currentTimeMillis()
            val idle = now - lastTouch > 3500
            lastTouch = now
            // Strip is hidden after idle; the revealing tap must not leak to the phone.
            if (idle && x > MirrorHub.carW * 0.68f && y < MirrorHub.carH * 0.2f) return
            MirrorAccessibilityService.instance?.tapCar(x, y)
        }
        override fun onScroll(dx: Float, dy: Float) {
            val svc = MirrorAccessibilityService.instance ?: return
            val cx = MirrorHub.carW / 2f; val cy = MirrorHub.carH / 2f
            if (!dragging) { dragging = true; scrollX = 0f; scrollY = 0f; svc.dragStart(cx, cy) }
            scrollX += dx; scrollY += dy
            svc.dragMove((cx - scrollX).coerceIn(2f, MirrorHub.carW - 2f), (cy - scrollY).coerceIn(2f, MirrorHub.carH - 2f))
            main.removeCallbacks(endDrag); main.postDelayed(endDrag, 110)
        }
    }

    override fun onNewIntent(intent: Intent) {
        CmLog.i("car: onNewIntent action=${intent.action} data=${intent.dataString}")
        // Navigation/launch requests simply bring the menu forward; never throw.
        try { val sm = carContext.getCarService(ScreenManager::class.java); while (sm.stackSize > 1) sm.pop() } catch (_: Exception) {}
    }

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                CmLog.i("car: session onCreate active=${MirrorHub.active}")
                try { carContext.getSystemService(android.app.NotificationManager::class.java).cancel(4) } catch (_: Exception) {}
                connected = true
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
                val pr = Prefs(carContext)
                if (pr.openLast) pr.lastApp?.let { MirrorHub.pendingPkg = it }
                if (!MirrorHub.active && pr.autoStart)
                    ProjectionRequester.request(carContext.applicationContext)
                else if (MirrorHub.active && pr.openLast) pr.lastApp?.let { AppLauncher.launch(carContext, it) }
            }
            override fun onDestroy(owner: LifecycleOwner) { CmLog.i("car: session onDestroy"); connected = false; MirrorHub.setCarSurface(null, 0, 0) }
        })
        return MenuScreen(carContext)
    }

    companion object { @Volatile var connected = false }
}

private fun icon(ctx: CarContext, res: Int) =
    CarIcon.Builder(IconCompat.createWithResource(ctx, res)).setTint(CarColor.DEFAULT).build()

class MenuScreen(ctx: CarContext) : Screen(ctx) {
    private val listener = { invalidate() }
    init {
        MirrorHub.listeners.add(listener)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { MirrorHub.listeners.remove(listener) }
        })
    }

    override fun onGetTemplate(): Template {
        val prefs = Prefs(carContext)
        val limit = try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } catch (_: Exception) { 6 }
        val list = ItemList.Builder()
        val extra = if (MirrorHub.active) 0 else 1
        val pm = carContext.packageManager
        for (pkg in prefs.apps.take(limit - extra)) {
            val bmp = try {
                val d = pm.getApplicationIcon(pkg)
                val b = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b); d.setBounds(0, 0, 128, 128); d.draw(c); b
            } catch (_: Exception) { null }
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val item = GridItem.Builder().setTitle(label)
            if (bmp != null) item.setImage(CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build(), GridItem.IMAGE_TYPE_LARGE)
            else item.setImage(icon(carContext, R.drawable.ic_app))
            item.setOnClickListener { open(pkg) }
            list.addItem(item.build())
        }
        if (!MirrorHub.active) {
            list.addItem(GridItem.Builder().setTitle("Ekranı yakala")
                .setImage(icon(carContext, R.drawable.ic_app))
                .setOnClickListener { ProjectionRequester.request(carContext.applicationContext) }.build())
        }
        return GridTemplate.Builder().setTitle("CarMirror").setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build()).build()
    }

    private fun open(pkg: String) {
        CmLog.i("menu: open $pkg active=${MirrorHub.active}")
        Prefs(carContext).lastApp = pkg
        if (MirrorHub.active) AppLauncher.launch(carContext, pkg)
        else { MirrorHub.pendingPkg = pkg; ProjectionRequester.request(carContext.applicationContext) }
        screenManager.push(MirrorScreen(carContext))
    }
}

class MirrorScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        fun act(res: Int, f: () -> Unit) = Action.Builder().setIcon(icon(carContext, res))
            .setOnClickListener { CmLog.i("action click"); f() }.build()
        val strip = ActionStrip.Builder()
            .addAction(act(R.drawable.ic_menu) { screenManager.pop() })
            .addAction(act(R.drawable.ic_back) { MirrorAccessibilityService.instance?.back() })
            .addAction(act(R.drawable.ic_home) { MirrorAccessibilityService.instance?.home() })
            .build()
        val map = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder().setActionStrip(strip).setMapActionStrip(map)
            .setPanModeListener { }.build()
    }
}
