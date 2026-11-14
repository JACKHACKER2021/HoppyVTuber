package cz.hoppy.vtuber

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.filament.Skybox
import io.github.sceneview.SceneView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.sin

/**
 * Hoppy Mazlíček: virtuální mazlíček. Používá stejnou postavu, animace, efekty a oblečky jako VTuber,
 * jen místo kamery řídí hlavu „mozek" (rozhlížení, mrkání, sledování prstu).
 */
class PetActivity : AppCompatActivity(), FaceSource {

    companion object { var current: WeakReference<PetActivity>? = null }

    // ---- FaceSource: hlava mazlíčka
    override var yaw = 0f; override var pitch = 0f; override var roll = 0f
    override val offYaw = 0f; override val offPitch = 0f; override val offRoll = 0f
    override var blinkL = 0f; override var blinkR = 0f
    override var jaw = 0f; override val smile = 0f

    private lateinit var sceneView: SceneView
    private lateinit var avatar: HoppyAvatar
    private val state by lazy { PetState(this) }
    private val ui = Handler(android.os.Looper.getMainLooper())
    private var ready = false
    private var skybox: Skybox? = null

    private lateinit var speech: TextView
    private lateinit var panel: View
    private lateinit var toiletDoor: TextView
    private val bars = HashMap<String, ProgressBar>()
    private lateinit var sleepBtn: TextView

    // dočasné stavy (čas v sekundách, uptime)
    private var tickleUntil = 0f; private var petUntil = 0f; private var washUntil = 0f
    private var busyUntil = 0f
    private var lastEmit = 0f
    private var sayUntil = 0f
    private var lookX = 0f; private var lookY = 0f; private var lookUntil = 0f
    private var nextBlink = 3f

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun now() = SystemClock.uptimeMillis() / 1000f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        current = WeakReference(this)
        // jen jeden 3D engine naráz: zavřít VTuber i bublinu a chvilku počkat
        val vt = MainActivity.current?.get()?.takeIf { !it.isDestroyed }
        vt?.finish()
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        if (vt != null || OverlayService.running) ui.postDelayed({ if (!isFinishing && !isDestroyed) build() }, 800)
        else build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val root = FrameLayout(this)
        sceneView = SceneView(context = this, cameraManipulator = null, sharedLifecycle = lifecycle)
        root.addView(sceneView, FrameLayout.LayoutParams(-1, -1))

        // --- ukazatele potřeb
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            background = round("#B3141820", 20)
        }
        for ((key, emoji) in listOf("hunger" to "🥕", "toilet" to "🚽", "clean" to "🛁", "energy" to "💤", "fun" to "🎾")) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(TextView(this).apply { text = emoji; textSize = 16f; gravity = Gravity.CENTER })
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            bars[key] = pb
            col.addView(pb, LinearLayout.LayoutParams(-1, 8.dp).apply { setMargins(4.dp, 2.dp, 4.dp, 0) })
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(12.dp, 12.dp, 12.dp, 0) })

        // --- bublina s řečí
        speech = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#141820")); gravity = Gravity.CENTER
            setPadding(16.dp, 8.dp, 16.dp, 8.dp); background = round("#F2FFFFFF", 18); visibility = View.GONE
        }
        root.addView(speech, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = 84.dp })

        // --- tlačítka
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(10.dp, 10.dp, 10.dp, 10.dp) }
        fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(14.dp, 12.dp, 14.dp, 12.dp); background = round("#FF2A303C", 20)
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        }
        btn("🥕 Nakrmit") { feed() }
        btn("🛁 Umýt") { wash() }
        btn("🚽 Záchod") { toilet() }
        sleepBtn = btn("💤 Spát") { toggleSleep() }
        btn("🎾 Hrát") { play() }
        btn("👕 Obleček") { nextOutfit() }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; background = round("#E6141820", 28); addView(row)
        }
        panel = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(12.dp, 0, 12.dp, 12.dp) })

        // --- dveře záchodu
        toiletDoor = TextView(this).apply {
            text = "🚪\nObsazeno!"; textSize = 40f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F2182030")); visibility = View.GONE
        }
        root.addView(toiletDoor, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (top.layoutParams as ViewGroup.MarginLayoutParams).topMargin = b.top + 8.dp
            (speech.layoutParams as ViewGroup.MarginLayoutParams).topMargin = b.top + 80.dp
            (scroll.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = b.bottom + 12.dp
            root.requestLayout(); insets
        }

        // --- postava
        avatar = HoppyAvatar(sceneView, this)
        avatar.faceCam = false
        avatar.poseHook = { p, t -> petPose(p, t) }
        avatar.wardrobe?.setOutfit(savedOutfit())
        sceneView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reframe() }
        sceneView.onFrame = {
            try { brain(now()); avatar.update() } catch (e: Exception) { CrashLog.note(this, "mazlíček snímek", e) }
        }
        sceneView.setOnTouchListener { _, e -> touch(e); true }

        state.load(); state.tick()
        setRoom()
        CrashLog.showIfAny(this)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        ready = true
        slowTick.run()
    }

    private fun round(color: String, r: Int) = GradientDrawable().apply { cornerRadius = r.dp.toFloat(); setColor(Color.parseColor(color)) }

    private fun reframe() {
        val h = sceneView.height
        val cover = if (h > 0) (h - panel.top).toFloat() / h else 0f
        avatar.applyFraming(cover.coerceIn(0f, 0.4f))
    }

    /** Pokoj: ve dne světlý, při spaní tma. */
    private fun setRoom() {
        val c = if (state.sleeping) floatArrayOf(0.05f, 0.06f, 0.14f) else floatArrayOf(1f, 0.93f, 0.85f)
        val engine = sceneView.engine
        val old = skybox
        skybox = Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
        sceneView.scene.skybox = skybox
        old?.let { runCatching { engine.destroySkybox(it) } }
        sleepBtn.text = if (state.sleeping) "☀️ Vzbudit" else "💤 Spát"
    }

    // ------------------------------------------------------------- mozek (hlava, mrkání)
    private fun brain(t: Float) {
        // rozhlížení; když se jí dotýkáš, dívá se za prstem
        val ty: Float; val tp: Float
        if (t < lookUntil) { ty = (lookX * 45f).coerceIn(-25f, 25f); tp = ((1.05f - lookY) * 25f).coerceIn(-15f, 15f) }
        else { ty = 10f * sin(t * 0.45f) + 4f * sin(t * 1.3f); tp = 3f * sin(t * 0.3f) }
        yaw += (ty - yaw) * 0.08f; pitch += (tp - pitch) * 0.08f
        roll = 3f * sin(t * 0.7f)
        // mrkání
        if (t > nextBlink) { blinkL = 1f; blinkR = 1f; if (t > nextBlink + 0.15f) nextBlink = t + 2f + (t * 7f % 3f) }
        else { blinkL = 0f; blinkR = 0f }
        // částice podle stavu
        if (t - lastEmit > 0.09f && t < washUntil) { lastEmit = t; avatar.effects.emit("bubble") }
        else if (t - lastEmit > 0.35f && t < petUntil) { lastEmit = t; avatar.effects.emit("hearts") }
        else if (avatar.effects.active == null && !state.sleeping && t > busyUntil) {
            if (state.clean < PetState.LOW && t - lastEmit > 0.5f) { lastEmit = t; avatar.effects.emit("fly") }
            else if (state.toilet < PetState.LOW && t - lastEmit > 1.4f) { lastEmit = t; avatar.effects.emit("sweat") }
        }
    }

    /** Póza podle potřeb – volá se každý snímek po efektech. */
    private fun petPose(p: FxPose, t: Float) {
        if (avatar.effects.active != null) return
        when {
            t < washUntil -> { p.eyes = Eyes.CLOSED; p.mouth = 0f; p.roll = sin(t * 6f) * 5f }
            t < tickleUntil -> {
                p.eyes = Eyes.CLOSED; p.blush = true; p.wag = 1f
                p.mouth = 0.6f + 0.4f * abs(sin(t * 12f)); p.roll = sin(t * 14f) * 7f; p.lift = abs(sin(t * 12f)) * 0.015f
            }
            t < petUntil -> { p.eyes = Eyes.CLOSED; p.blush = true; p.wag = 0.8f; p.pitch = 6f; p.mouth = 0f }
            state.sleeping -> {
                p.eyes = Eyes.CLOSED; p.mouth = 0f; p.pitch = 14f + sin(t * 1.2f) * 2f; p.earDroop = 1f; p.wag = -1f
            }
            state.toilet < PetState.LOW -> {     // přešlapuje
                p.roll = sin(t * 9f) * 6f; p.lift = abs(sin(t * 9f)) * 0.02f; p.squint = 0.7f; p.mouth = 0f; p.blush = true
            }
            state.hunger < PetState.LOW -> { p.earDroop = 0.7f; p.pitch = 8f; p.squint = 0.8f; p.wag = -0.6f
                p.mouth = if (t % 5f < 0.8f) 0.7f else 0f }
            state.energy < PetState.LOW -> {     // zívá
                p.earDroop = 1f; p.squint = 0.45f; p.wag = -0.8f
                val y = t % 6f
                if (y < 1.4f) { p.mouth = 1f; p.eyes = Eyes.CLOSED; p.pitch = -8f } else p.mouth = 0f
            }
            state.clean < PetState.LOW -> { p.squint = 0.7f; p.earDroop = 0.4f; p.mouth = 0f }
            state.fun_ < PetState.LOW -> { p.earDroop = 0.5f; p.pitch = 10f; p.wag = -0.5f; p.mouth = 0f }
            else -> p.wag = 0.3f
        }
    }

    // ------------------------------------------------------------- dotyky
    private var lastPhrase = 0
    private fun touch(e: MotionEvent) {
        if (!ready || e.actionMasked == MotionEvent.ACTION_UP) return
        val w = avatar.worldAt(e.x, e.y) ?: return
        val t = now()
        lookX = w[0]; lookY = w[1]; lookUntil = t + 1.5f
        if (abs(w[0]) > 0.45f || w[1] > 1.65f) return
        if (state.sleeping) { if (e.actionMasked == MotionEvent.ACTION_DOWN) say("Pššt… Hoppy spí 💤", 2f); return }
        if (t < busyUntil || t < washUntil || avatar.effects.active != null) return
        val moving = e.actionMasked == MotionEvent.ACTION_MOVE
        when {
            // hlava a uši: hlazení
            w[1] > 0.85f -> {
                petUntil = t + 1f
                if (moving) state.fun_ = (state.fun_ + 0.15f).coerceAtMost(100f)
                if (t > sayUntil) say(listOf("To je příjemné 💚", "Ještě! 🥰", "Mám tě ráda!")[lastPhrase++ % 3], 2f)
            }
            // bříško a tlapky: lechtání
            (w[1] in 0.5f..0.85f && abs(w[0]) < 0.2f) || w[1] < 0.2f -> if (moving) {
                tickleUntil = t + 1.2f
                state.fun_ = (state.fun_ + 0.2f).coerceAtMost(100f)
                if (t > sayUntil) say(listOf("Hihi! To lechtá! 😆", "Přestaň, hahaha! 😳", "Néé, tam jsem lechtivá! 🤣")[lastPhrase++ % 3], 1.6f)
            }
        }
    }

    // ------------------------------------------------------------- akce
    private fun busy() = now() < busyUntil || now() < washUntil || avatar.effects.active != null
    private fun asleep(): Boolean { if (state.sleeping) say("Hoppy spí. Nejdřív ji vzbuď ☀️", 2f); return state.sleeping }

    private fun feed() {
        if (busy() || asleep()) return
        if (state.hunger > 92f) { say("Už nemám hlad! 😋", 2f); return }
        avatar.trigger(Fx.CARROT)
        state.hunger = (state.hunger + 35f).coerceAtMost(100f); state.toilet = (state.toilet - 8f).coerceAtLeast(0f)
        say("Mňam, mrkvička! 🥕", 3f); refresh()
    }

    private fun wash() {
        if (busy() || asleep()) return
        washUntil = now() + 3.5f
        say("Šplouch! 🫧", 3f)
        ui.postDelayed({ state.clean = 100f; say("Jsem čistá! ✨", 2f); refresh() }, 3500)
    }

    private fun toilet() {
        if (busy() || asleep()) return
        if (state.toilet > 85f) { say("Teď nepotřebuju 🙂", 2f); return }
        busyUntil = now() + 2.8f
        toiletDoor.alpha = 0f; toiletDoor.visibility = View.VISIBLE
        toiletDoor.animate().alpha(1f).setDuration(250).start()
        ui.postDelayed({
            state.toilet = 100f; state.clean = (state.clean - 6f).coerceAtLeast(0f)
            toiletDoor.animate().alpha(0f).setDuration(250).withEndAction { toiletDoor.visibility = View.GONE }.start()
            say("Ufff, to se ulevilo! 😌", 2.5f); refresh()
        }, 2500)
    }

    private fun toggleSleep() {
        if (busy()) return
        if (!state.sleeping && state.energy > 90f) { say("Nejsem unavená! 😄", 2f); return }
        state.tick(); state.sleeping = !state.sleeping; state.save()
        setRoom()
        say(if (state.sleeping) "Dobrou noc… 💤" else "Dobré ráno! ☀️", 2.5f)
    }

    private fun play() {
        if (busy() || asleep()) return
        if (state.energy < 15f) { say("Jsem moc unavená… 😴", 2f); return }
        avatar.trigger(Fx.EXCITED)
        state.fun_ = (state.fun_ + 30f).coerceAtMost(100f)
        state.energy = (state.energy - 8f).coerceAtLeast(0f); state.hunger = (state.hunger - 5f).coerceAtLeast(0f)
        say("Jupí! 🎾", 3f); refresh()
    }

    private fun savedOutfit() = runCatching { Outfit.valueOf(Prefs.get(this).getString("petOutfit", "AUTO")!!) }.getOrDefault(Outfit.AUTO)

    private fun nextOutfit() {
        val w = avatar.wardrobe ?: return
        val all = Outfit.values()
        val o = all[(w.outfit.ordinal + 1) % all.size]
        w.setOutfit(o); Prefs.get(this).edit().putString("petOutfit", o.name).apply()
        val eff = w.effective(this)
        say(if (o == Outfit.AUTO) "📅 Podle data: ${eff.emoji} ${eff.label}" else "${o.emoji} ${o.label}", 2f)
    }

    // ------------------------------------------------------------- UI
    private fun say(text: String, sec: Float) {
        speech.text = text; speech.visibility = View.VISIBLE; sayUntil = now() + sec
    }

    private fun refresh() {
        val vals = mapOf("hunger" to state.hunger, "toilet" to state.toilet, "clean" to state.clean,
            "energy" to state.energy, "fun" to state.fun_)
        for ((k, v) in vals) bars[k]?.apply {
            progress = v.toInt()
            progressTintList = ColorStateList.valueOf(Color.parseColor(
                if (v < PetState.LOW) "#FF5252" else if (v < 55f) "#FFB74D" else "#5BD07A"))
        }
    }

    /** Dvakrát za sekundu: čas běží, ukazatele, co Hoppy říká. */
    private val slowTick = object : Runnable {
        override fun run() {
            if (!ready || isDestroyed) return
            val wasSleeping = state.sleeping
            state.tick()
            if (wasSleeping && !state.sleeping) { setRoom(); say("Vyspala jsem se! ☀️", 3f) }
            refresh()
            val t = now()
            if (t > sayUntil) {
                val need = if (state.sleeping) "Zzz… 💤" else when (state.worst()) {
                    "toilet" -> "Musím na záchod! 🚽"
                    "hunger" -> "Mám hlad… 🥕"
                    "energy" -> "Jsem unavená… 💤"
                    "clean" -> "Chci se umýt! 🛁"
                    "fun" -> "Nudím se… 🎾"
                    else -> null
                }
                if (need != null) { speech.text = need; speech.visibility = View.VISIBLE } else speech.visibility = View.GONE
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        PetReminder.cancel(this)
        if (ready) { state.load(); state.tick(); setRoom(); ui.removeCallbacks(slowTick); slowTick.run() }
    }

    override fun onStop() {
        if (ready) {
            ui.removeCallbacks(slowTick)
            state.tick(); state.save()
            PetReminder.schedule(this, state.msUntilNeed())
        }
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
