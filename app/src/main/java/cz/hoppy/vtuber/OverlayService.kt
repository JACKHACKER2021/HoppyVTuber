package cz.hoppy.vtuber

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.sceneview.SceneView
import kotlin.math.abs

/**
 * Plovoucí bublina s postavou nad ostatními aplikacemi (hry, prohlížeč…).
 * Kamera dál sleduje obličej, postava je na průhledném pozadí.
 *
 * Ovládání: táhnutím bublinu přesuneš, klepnutím ukážeš/schováš mini panel.
 */
class OverlayService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_REC = "rec"            // zapnout/vypnout nahrávání (z notifikace)
        const val ACTION_REC_GRANTED = "recGranted"   // uživatel povolil záznam obrazovky
        /** Zavolá se, jakmile je okno bubliny na obrazovce (aplikace se pak může zavřít). */
        @Volatile var onShown: (() -> Unit)? = null
        @Volatile var running = false
            private set

        // velikosti okýnka (šířka v dp); facecam = čtverec, celá postava = 3:5
        private val SIZES_DP = intArrayOf(120, 160, 210)
        private const val BORDER_DP = 3
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var sceneView: SceneView
    private lateinit var controls: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var voiceTag: TextView
    private lateinit var recTag: TextView
    private lateinit var placeholder: TextView
    private var voice: VoiceChanger? = null
    private var bgIndex = 0
    private var mySkybox: com.google.android.filament.Skybox? = null
    private var tracker: FaceTracker? = null
    private var avatar: HoppyAvatar? = null
    private var sizeIndex = 1

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) {
                updateRotation()
                // po otočení (hra na šířku) vrátit bublinu na obrazovku
                if (root != null) { clampToScreen(); runCatching { wm.updateViewLayout(root, params) } }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REC) { toggleRec(); return START_NOT_STICKY }
        if (intent?.action == ACTION_REC_GRANTED) {
            @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
            if (data != null) startScreenRec(intent.getIntExtra("code", 0), data)
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null && !starting) {
            starting = true; running = true
            // 1) okno hned – aplikace je ještě vpředu, takže ho Xiaomi/HyperOS nezablokuje
            try { showShell() } catch (e: Exception) {
                running = false; starting = false
                CrashLog.note(this, "bublina okno", e)
                android.widget.Toast.makeText(this, "Bublinu nejde zobrazit: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                onShown?.invoke(); onShown = null
                stopSelf(); return START_NOT_STICKY
            }
            onShown?.invoke(); onShown = null
            // 2) 3D scéna až po chvilce, až aplikace uklidí svůj 3D engine (jen jeden engine naráz)
            android.os.Handler(mainLooper).postDelayed({
                starting = false
                if (stopped) return@postDelayed
                try { showBubble() } catch (e: Exception) {
                    running = false
                    CrashLog.note(this, "bublina start", e)
                    android.widget.Toast.makeText(this, "Bublina se nespustila: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }, 800)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val type0 = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        var type = if (Build.VERSION.SDK_INT >= 30 && hasMic()) type0 or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else type0
        if (projectionGranted && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, 2, notification(), type)
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(2, notification()) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bubble", "Plovoucí Hoppy", NotificationManager.IMPORTANCE_LOW))
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), piFlags
        )
        val recPi = PendingIntent.getService(
            this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_REC), piFlags
        )
        val isRec = isRec()
        return Notification.Builder(this, "bubble")
            .setContentTitle(if (isRec) "🔴 Hoppy natáčí" else "Hoppy je v bublině")
            .setContentText(if (isRec) "Zastavíš tlačítkem níž (funguje, i když bublinu nevidíš)."
                            else "Kamera sleduje obličej. Klepni pro návrat do aplikace.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, if (isRec) "⏹ Zastavit nahrávání" else "⏺ Nahrávat", recPi).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Zavřít bublinu", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun hasMic() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ bublina
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /** Krok 1: prázdné okýnko (rámeček + „načítám“) – hned, dokud je aplikace vpředu. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showShell() {
        wm = getSystemService(WindowManager::class.java)
        val prefs = Prefs.get(this)
        sizeIndex = prefs.getInt("bubbleSize", 1).coerceIn(0, SIZES_DP.lastIndex)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(buildFrame())
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: klávesnice/tlačítka zůstávají hře
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubbleX", 24.dp)
            y = prefs.getInt("bubbleY", 120.dp)
        }
        clampToScreen()
        placeholder.setOnTouchListener(DragListener())
        wm.addView(root, params)
    }

    /** Krok 2: 3D scéna, kamera, ovládání. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        running = true
        val prefs = Prefs.get(this)

        sceneView = SceneView(
            context = this,
            cameraManipulator = null,        // tahání = přesun bubliny, ne otáčení kamery
            sharedLifecycle = registry,
        )

        val t = FaceTracker(this).also { tracker = it }
        val a = HoppyAvatar(sceneView, t).also { avatar = it }
        a.loadExpressions()
        bgIndex = prefs.getInt("bgIndex", 0)
        setBackground(bgIndex)
        a.setBackdrop(CustomBg.load(this))
        sceneView.onFrame = {
            try { a.update(); updateVoiceTag(); if (pad != null && a.effects.active != lastFx) { lastFx = a.effects.active; refreshPad() } }
            catch (e: Exception) { CrashLog.note(this, "bublina snímek", e) }
        }
        sceneView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> a.applyFraming() }

        controls = buildControls()
        controls.visibility = View.GONE
        root!!.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4.dp })

        val (w, h) = sceneSize()
        frame.removeView(placeholder)
        frame.addView(sceneView, 0, FrameLayout.LayoutParams(w, h))
        sceneView.setOnTouchListener(DragListener())
        runCatching { wm.updateViewLayout(root, params) }
        if (prefs.getBoolean("voiceOn", false)) startVoice()

        // teprve teď "resumed": SceneView začne kreslit a kamera se spustí
        registry.currentState = Lifecycle.State.RESUMED
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        updateRotation()
        t.start(this)
    }

    /** Bublina vždy celá na obrazovce (aspoň kus, ať jde chytit). */
    private fun clampToScreen() {
        val dm = resources.displayMetrics
        val (w, h) = sceneSize()
        val maxX = (dm.widthPixels - w / 2).coerceAtLeast(0)
        val maxY = (dm.heightPixels - h / 2).coerceAtLeast(0)
        params.x = params.x.coerceIn(-w / 2, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun updateRotation() {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        tracker?.setRotation(d.rotation)
    }

    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        clampToScreen()
                        root?.let { wm.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        Prefs.get(this@OverlayService).edit()
                            .putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
                    } else {
                        if (isRec()) showControlsNow()
                        else if (::controls.isInitialized)
                            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------- mini panel
    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6.dp, 4.dp, 6.dp, 4.dp)
            background = GradientDrawable().apply {
                cornerRadius = 20.dp.toFloat()
                setColor(Color.parseColor("#E6141820"))
            }
        }
        val a = avatar!!
        fun button(label: String, isOn: (() -> Boolean)?, onClick: () -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            }
            fun refresh() {
                tv.background = GradientDrawable().apply {
                    cornerRadius = 14.dp.toFloat()
                    setColor(if (isOn?.invoke() == true) Color.parseColor("#665BD07A") else Color.TRANSPARENT)
                }
            }
            refresh()
            tv.setOnClickListener { onClick(); refresh(); a.saveExpressions(); hideControlsLater() }
            bar.addView(tv)
            return tv
        }
        button("👋", { a.waveOn }) { a.waveOn = !a.waveOn; if (a.waveOn) a.armsOn = false }
        button("😊", { a.happyOn }) { a.happyOn = !a.happyOn }
        button("😳", { a.blushOn }) { a.blushOn = !a.blushOn }
        button("👤", { a.faceCam }) { a.faceCam = !a.faceCam; resizeFrame() }
        button("👕", null) {
            val w = a.wardrobe ?: return@button
            val all = Outfit.values()
            w.setOutfit(all[(w.outfit.ordinal + 1) % all.size])
            val eff = w.effective(this)
            toast(if (w.outfit == Outfit.AUTO) "📅 Podle data: ${eff.emoji} ${eff.label}" else "${w.outfit.emoji} ${w.outfit.label}")
        }
        button("🎧", { a.wardrobe?.headset == true }) { a.wardrobe?.let { it.setHeadset(!it.headset) } }
        button("🎙", { voice != null }) {
            val on = voice == null
            if (on && !VoiceChanger.isConfigured(this)) return@button
            Prefs.get(this).edit().putBoolean("voiceOn", on).apply()
            if (on) startVoice() else stopVoice()
        }
        button("⏺", { isRec() }) { toggleRec() }
        button("✨", { pad != null }) { togglePad() }
        button("🎨", null) {
            // barvy dokola, po poslední barvě vlastní obrázek (když existuje)
            if (CustomBg.isOn(this)) { CustomBg.setOn(this, false); a.setBackdrop(null); setBackground(0) }
            else if (bgIndex == Prefs.BACKGROUNDS.lastIndex && CustomBg.exists(this)) {
                CustomBg.setOn(this, true); a.setBackdrop(CustomBg.load(this))
            } else setBackground(bgIndex + 1)
        }
        button("🎯", null) { tracker?.calibrate() }
        button("⤢", null) { cycleSize() }
        button("✕", null) { stopSelf() }
        return bar
    }

    private fun cycleSize() {
        sizeIndex = (sizeIndex + 1) % SIZES_DP.size
        Prefs.get(this).edit().putInt("bubbleSize", sizeIndex).apply()
        resizeFrame()
    }

    // ------------------------------------------------------------- okýnko
    /** Velikost scény: facecam = čtverec (žádný prázdný prostor kolem), celá postava = na výšku. */
    private fun sceneSize(): Pair<Int, Int> {
        val w = SIZES_DP[sizeIndex].dp
        val faceCam = avatar?.faceCam ?: Prefs.get(this).getBoolean("faceCam", true)
        return w to (if (faceCam) w else (w * 1.6f).toInt())
    }

    /** Okýnko = rámeček s pozadím + jmenovka; scéna je neprůhledná, takže nikde nic neprosvítá. */
    private fun buildFrame(): FrameLayout {
        val (w, h) = sceneSize()
        frame = FrameLayout(this).apply {
            setPadding(BORDER_DP.dp, BORDER_DP.dp, BORDER_DP.dp, BORDER_DP.dp)
            background = GradientDrawable().apply {
                cornerRadius = 10.dp.toFloat()
                setColor(Color.parseColor("#5BD07A"))
            }
        }
        placeholder = TextView(this).apply {
            text = "Načítám\nHoppy…"; gravity = Gravity.CENTER; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#141820"))
        }
        frame.addView(placeholder, FrameLayout.LayoutParams(w, h))
        val tagBg = GradientDrawable().apply { cornerRadius = 8.dp.toFloat(); setColor(Color.parseColor("#B3141820")) }
        frame.addView(TextView(this).apply {
            text = "Hoppy"; textSize = 10f; setTextColor(Color.WHITE)
            setPadding(6.dp, 1.dp, 6.dp, 1.dp); background = tagBg
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(4.dp, 0, 0, 4.dp) })
        voiceTag = TextView(this).apply {
            textSize = 10f; setTextColor(Color.WHITE)
            setPadding(6.dp, 1.dp, 6.dp, 1.dp); background = tagBg; visibility = View.GONE
        }
        frame.addView(voiceTag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(0, 4.dp, 4.dp, 0) })
        // 🔴 REC s časem – jen na obrazovce, do videu se nedostane (nahrává se jen 3D scéna)
        recTag = TextView(this).apply {
            textSize = 10f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(6.dp, 1.dp, 6.dp, 1.dp)
            background = GradientDrawable().apply { cornerRadius = 8.dp.toFloat(); setColor(Color.parseColor("#CCB00020")) }
            visibility = View.GONE
            setOnClickListener { toggleRec() }       // klepnutím zastavíš
        }
        frame.addView(recTag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(4.dp, 4.dp, 0, 0) })
        return frame
    }

    private fun resizeFrame() {
        val (w, h) = sceneSize()
        sceneView.layoutParams = (sceneView.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }
        root?.let { wm.updateViewLayout(it, params) }
    }

    private fun setBackground(i: Int) {
        bgIndex = i % Prefs.BACKGROUNDS.size
        Prefs.get(this).edit().putInt("bgIndex", bgIndex).apply()
        val c = Prefs.BACKGROUNDS[bgIndex]
        val engine = sceneView.engine
        val old = mySkybox      // výchozí skybox SceneView neničíme, uklidí si ho sama
        mySkybox = com.google.android.filament.Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
        sceneView.scene.skybox = mySkybox
        old?.let { runCatching { engine.destroySkybox(it) } }
    }

    // ------------------------------------------------------------- panel efektů
    private var pad: LinearLayout? = null
    private var padParams: WindowManager.LayoutParams? = null

    /** Samostatné plovoucí okénko s tlačítky efektů – mačkáš je mimo bublinu, třeba vedle hry. */
    @SuppressLint("ClickableViewAccessibility")
    private fun togglePad() {
        pad?.let { runCatching { wm.removeView(it) }; pad = null; return }
        val prefs = Prefs.get(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            background = GradientDrawable().apply { cornerRadius = 18.dp.toFloat(); setColor(Color.parseColor("#E6141820")) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("padX", 8.dp); y = prefs.getInt("padY", 260.dp)
        }
        // úchyt na přesun
        val handle = TextView(this).apply {
            text = "⠿"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#8A93A3"))
            setPadding(0, 2.dp, 0, 2.dp)
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt()
                    runCatching { wm.updateViewLayout(col, lp) }
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("padX", lp.x).putInt("padY", lp.y).apply()
            }
            true
        }
        col.addView(handle)
        // všechno: gesta, výrazy, efekty i nahrávání – po dvou vedle sebe
        val a = avatar
        val items = ArrayList<Triple<String, () -> Boolean, () -> Unit>>()
        if (a != null) {
            items += Triple("👋", { a.waveOn }) { a.waveOn = !a.waveOn; if (a.waveOn) a.armsOn = false; a.saveExpressions() }
            items += Triple("🙌", { a.armsOn }) { a.armsOn = !a.armsOn; if (a.armsOn) a.waveOn = false; a.saveExpressions() }
            items += Triple("😊", { a.happyOn }) { a.happyOn = !a.happyOn; a.saveExpressions() }
            items += Triple("😳", { a.blushOn }) { a.blushOn = !a.blushOn; a.saveExpressions() }
            for (fx in Fx.values()) items += Triple(fx.emoji, { a.effects.active == fx }) { a.trigger(fx) }
        }
        items += Triple("⏺", { isRec() }) { toggleRec() }
        padItems.clear()
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (emoji, isOn, act) ->
                val tv = TextView(this).apply {
                    text = emoji; textSize = 20f; gravity = Gravity.CENTER
                    setPadding(7.dp, 5.dp, 7.dp, 5.dp)
                    setOnClickListener { act(); refreshPad(); hideControlsLater() }
                }
                padItems += tv to isOn
                row.addView(tv)
            }
            col.addView(row)
        }
        col.addView(TextView(this).apply {
            text = "✕"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(0, 4.dp, 0, 4.dp); setOnClickListener { togglePad() }
        })
        wm.addView(col, lp)
        pad = col; padParams = lp
        refreshPad()
    }

    /** Zvýrazní běžící efekt (hlavně přepínač 🤒). */
    private val padItems = ArrayList<Pair<TextView, () -> Boolean>>()

    /** Zvýrazní zapnuté věci (gesta, běžící efekt, nahrávání). */
    private fun refreshPad() {
        if (pad == null) return
        for ((tv, isOn) in padItems) {
            val on = runCatching { isOn() }.getOrDefault(false)
            tv.background = GradientDrawable().apply {
                cornerRadius = 12.dp.toFloat()
                setColor(if (on) Color.parseColor("#665BD07A") else Color.TRANSPARENT)
            }
        }
    }

    // ------------------------------------------------------------- nahrávání v bublině
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    // Bublina nahrává CELOU obrazovku (hra + Hoppy). Ovládání se při nahrávání samo schová,
    // ať ho ve videu nemáš; klepnutím na Hoppy se na chvíli ukáže a funguje.
    private var screen: ScreenRecorder? = null
    private var projectionGranted = false
    private fun isRec() = screen?.isRecording == true

    /** Spustí/zastaví nahrávání. Jde to z mini panelu, z panelu efektů, z REC štítku i z notifikace. */
    private fun toggleRec() {
        if (isRec()) { screen?.stop(); return }
        // systémový dotaz na záznam obrazovky (průhledná aktivita), výsledek přijde jako ACTION_REC_GRANTED
        runCatching {
            startActivity(Intent(this, ScreenCaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("Nahrávání nejde spustit: ${it.message}") }
    }

    private fun startScreenRec(code: Int, data: Intent) {
        projectionGranted = true
        // Android 14+: služba musí běžet s typem mediaProjection DŘÍV, než se projekce vytvoří
        runCatching { startInForeground() }.onFailure { CrashLog.note(this, "fgs projekce", it) }
        val r = ScreenRecorder(this) { ok ->
            projectionGranted = false
            if (::recTag.isInitialized) recTag.visibility = View.GONE
            showControlsNow(false)
            toast(if (ok) "Uloženo do Movies/HoppyVTuber" else "Nahrávání selhalo")
            refreshPad(); updateNotification()
            runCatching { startInForeground() }
        }
        if (r.start(code, data)) {
            screen = r
            recTag.visibility = View.VISIBLE
            recTick.run()
            hideControlsLater()
            toast("🔴 Nahrává se obrazovka. Klepni na Hoppy pro ovládání.")
        } else toast("Nahrávání nejde spustit")
        refreshPad(); updateNotification()
    }

    private val recTick = object : Runnable {
        override fun run() {
            val r = screen ?: return
            if (!r.isRecording) return
            recTag.text = "🔴 ${r.timeText()}"
            recTag.postDelayed(this, 500)
        }
    }

    // --- ovládání se při nahrávání schovává
    private val hideControls = Runnable {
        if (!isRec()) return@Runnable
        if (::controls.isInitialized) controls.visibility = View.GONE
        pad?.visibility = View.GONE
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    /** Při nahrávání: ukázat ovládání (a panel efektů, pokud je otevřený) a za 3 s zase schovat. */
    private fun showControlsNow(autoHide: Boolean = true) {
        if (::controls.isInitialized) controls.visibility = View.VISIBLE
        pad?.visibility = View.VISIBLE
        if (autoHide) hideControlsLater()
    }

    private fun hideControlsLater() {
        mainHandler.removeCallbacks(hideControls)
        if (isRec()) mainHandler.postDelayed(hideControls, 3000)
    }

    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    // ------------------------------------------------------------- hlas
    private fun startVoice() {
        stopVoice()
        if (!hasMic() || !VoiceChanger.isConfigured(this)) return
        val v = VoiceChanger(this).also { voice = it }
        avatar?.voiceLevel = { v.level }
        v.start()
    }

    private fun stopVoice() {
        voice?.stop(); voice = null
        avatar?.voiceLevel = { 0f }
    }

    private var lastTag = ""
    private var lastFx: Fx? = null
    private fun updateVoiceTag() {
        val v = voice
        val t = when {
            v == null -> ""
            v.status == "převádím…" -> "🎙 …"
            v.level > 0.02f -> "🔊"
            v.status == "poslouchám" -> "🎙"
            else -> "⚠"
        }
        if (t != lastTag) {
            lastTag = t
            voiceTag.text = t; voiceTag.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private var starting = false
    private var stopped = false

    override fun onDestroy() {
        // rozdělané video vždy uložit
        screen?.let { if (it.isRecording) it.stop() }
        mainHandler.removeCallbacksAndMessages(null)
        onShown = null
        stopped = true
        running = false
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        stopVoice()
        pad?.let { runCatching { wm.removeView(it) } }; pad = null
        tracker?.close()
        if (registry.currentState != Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.DESTROYED   // SceneView se uklidí sama
        }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
