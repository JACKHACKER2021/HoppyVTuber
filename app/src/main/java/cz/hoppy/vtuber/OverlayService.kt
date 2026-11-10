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
            if (id == Display.DEFAULT_DISPLAY) updateRotation()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) showBubble()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
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
        val n = Notification.Builder(this, "bubble")
            .setContentTitle("Hoppy je v bublině")
            .setContentText("Kamera sleduje obličej. Klepni pro návrat do aplikace.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Zavřít bublinu", stop).build())
            .setOngoing(true)
            .build()
        var type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        if (Build.VERSION.SDK_INT >= 30 && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, 2, n, type)
    }

    private fun hasMic() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ bublina
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        running = true
        wm = getSystemService(WindowManager::class.java)
        val prefs = Prefs.get(this)
        sizeIndex = prefs.getInt("bubbleSize", 1).coerceIn(0, SIZES_DP.lastIndex)

        sceneView = SceneView(
            context = this,
            cameraManipulator = null,        // tahání = přesun bubliny, ne otáčení kamery
            sharedLifecycle = registry,
        )
        bgIndex = prefs.getInt("bgIndex", 0)
        setBackground(bgIndex)

        val t = FaceTracker(this).also { tracker = it }
        val a = HoppyAvatar(sceneView, t).also { avatar = it }
        a.loadExpressions()
        sceneView.onFrame = { a.update(); updateVoiceTag() }
        sceneView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> a.applyFraming() }

        controls = buildControls()
        controls.visibility = View.GONE

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(buildFrame())
            addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp })
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: klávesnice/tlačítka zůstávají hře
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubbleX", 24.dp)
            y = prefs.getInt("bubbleY", 120.dp)
        }

        sceneView.setOnTouchListener(DragListener())
        wm.addView(root, params)
        if (prefs.getBoolean("voiceOn", false)) startVoice()

        // teprve teď "resumed": SceneView začne kreslit a kamera se spustí
        registry.currentState = Lifecycle.State.RESUMED
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        updateRotation()
        t.start(this)
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
                        root?.let { wm.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        Prefs.get(this@OverlayService).edit()
                            .putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
                    } else {
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
            tv.setOnClickListener { onClick(); refresh(); a.saveExpressions() }
            bar.addView(tv)
            return tv
        }
        button("👋", { a.waveOn }) { a.waveOn = !a.waveOn; if (a.waveOn) a.armsOn = false }
        button("😊", { a.happyOn }) { a.happyOn = !a.happyOn }
        button("😳", { a.blushOn }) { a.blushOn = !a.blushOn }
        button("👤", { a.faceCam }) { a.faceCam = !a.faceCam; resizeFrame() }
        button("🎙", { voice != null }) {
            val on = voice == null
            if (on && !VoiceChanger.isConfigured(this)) return@button
            Prefs.get(this).edit().putBoolean("voiceOn", on).apply()
            if (on) startVoice() else stopVoice()
        }
        button("🎨", null) { setBackground(bgIndex + 1) }
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
        val faceCam = avatar?.faceCam ?: true
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
        frame.addView(sceneView, FrameLayout.LayoutParams(w, h))
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

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        stopVoice()
        tracker?.close()
        if (registry.currentState != Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.DESTROYED   // SceneView se uklidí sama
        }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
