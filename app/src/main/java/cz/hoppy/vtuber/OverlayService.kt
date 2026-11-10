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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
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

        // velikosti bubliny (šířka v dp, výška = 1.25×)
        private val SIZES_DP = intArrayOf(120, 170, 230)
        // kamera v bublině: trochu blíž než v aplikaci, ať je postava větší
        private val BUBBLE_CAM = Position(0f, 0.1f, 2.2f)
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var sceneView: SceneView
    private lateinit var controls: LinearLayout
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
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, 2, n, type)
    }

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
            isOpaque = false,                // průhledné pozadí – vidíš hru za postavou
            cameraManipulator = null,        // tahání = přesun bubliny, ne otáčení kamery
            sharedLifecycle = registry,
        )
        sceneView.scene.skybox = null
        sceneView.cameraNode.position = BUBBLE_CAM

        val t = FaceTracker(this).also { tracker = it }
        val a = HoppyAvatar(sceneView, t).also { avatar = it }
        a.loadExpressions()
        sceneView.onFrame = { a.update() }

        controls = buildControls()
        controls.visibility = View.GONE

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sceneView, LinearLayout.LayoutParams(SIZES_DP[sizeIndex].dp, (SIZES_DP[sizeIndex] * 1.25f).toInt().dp))
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
        button("🎯", null) { tracker?.calibrate() }
        button("⤢", null) { cycleSize() }
        button("✕", null) { stopSelf() }
        return bar
    }

    private fun cycleSize() {
        sizeIndex = (sizeIndex + 1) % SIZES_DP.size
        Prefs.get(this).edit().putInt("bubbleSize", sizeIndex).apply()
        sceneView.layoutParams = sceneView.layoutParams.apply {
            width = SIZES_DP[sizeIndex].dp
            height = (SIZES_DP[sizeIndex] * 1.25f).toInt().dp
        }
        root?.let { wm.updateViewLayout(it, params) }
    }

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        tracker?.close()
        if (registry.currentState != Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.DESTROYED   // SceneView se uklidí sama
        }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
