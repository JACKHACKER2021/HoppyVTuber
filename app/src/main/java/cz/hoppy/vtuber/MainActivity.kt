package cz.hoppy.vtuber

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.ContentValues
import android.media.MediaRecorder
import android.os.SystemClock
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.filament.Skybox
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import io.github.sceneview.SceneView

/**
 * Hoppy VTuber: kamera -> FaceTracker -> HoppyAvatar (SceneView/Filament).
 * Tlačítko 🫧 Bublina pošle postavu do plovoucího okna nad hry (OverlayService).
 */
class MainActivity : AppCompatActivity() {

    private val BACKGROUNDS = Prefs.BACKGROUNDS
    private var voice: VoiceChanger? = null
    private var bgIndex = 0
    private var skybox: Skybox? = null
    private var faceShown = false

    private lateinit var sceneView: SceneView
    private lateinit var tracker: FaceTracker
    private lateinit var avatar: HoppyAvatar

    private var recording = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) startTracking()
        else Toast.makeText(this, "Bez kamery se postava nehýbe", Toast.LENGTH_LONG).show()
    }

    private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (CustomBg.save(this, uri)) avatar.setBackdrop(CustomBg.load(this))
        else Toast.makeText(this, "Obrázek nejde načíst", Toast.LENGTH_SHORT).show()
    }

    private val overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) launchBubble()
        else Toast.makeText(this, "Bez povolení „Zobrazovat přes jiné aplikace“ bublina nepůjde", Toast.LENGTH_LONG).show()
    }


    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OverlayService.running) {
            // nejdřív zavřít bublinu a počkat, až zmizí její 3D engine (jen jeden engine naráz)
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            android.os.Handler(mainLooper).postDelayed({ if (!isFinishing && !isDestroyed) build() }, 700)
        } else build()
    }

    private fun build() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        sceneView = findViewById(R.id.sceneView)
        applyInsets()

        setBackground(Prefs.get(this).getInt("bgIndex", 0))
        sceneView.cameraManipulator = null      // dotyk neotáčí kamerou, záběr řídí Facecam
        tracker = FaceTracker(this)
        avatar = HoppyAvatar(sceneView, tracker)
        avatar.loadExpressions()
        // záběr přepočítat při každé změně velikosti (panel dole zakrývá část obrazovky)
        sceneView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reframe() }
        setupButtons()
        CrashLog.showIfAny(this)
        avatar.setBackdrop(CustomBg.load(this))

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())

        sceneView.onFrame = {
            try { avatar.update(); updateStatus() }
            catch (e: Exception) { CrashLog.note(this, "snímek", e) }
        }

        ready = true
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) resumeApp()
    }

    override fun onStart() {
        super.onStart()
        if (ready) resumeApp()
    }

    private fun resumeApp() {
        // návrat do aplikace = bublina se zavře a postava se vrátí sem
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            sceneView.postDelayed({ startTracking() }, 300)   // ať kamera stihne přejít zpět
        } else {
            startTracking()
        }
        syncChips()
        // chvilku počkat, ať bublina stihne pustit mikrofon
        sceneView.postDelayed({
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startVoice()
        }, 400)
    }

    override fun onStop() {
        if (ready) { stopVoice(); if (recording) stopSceneRecording() }
        super.onStop()
    }

    private fun startVoice() {
        stopVoice()
        if (!Prefs.get(this).getBoolean("voiceOn", false) || !VoiceChanger.isConfigured(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val v = VoiceChanger(this).also { voice = it }
        avatar.voiceLevel = { v.level }
        v.start()
    }

    private fun stopVoice() {
        voice?.stop(); voice = null
        if (::avatar.isInitialized) avatar.voiceLevel = { 0f }
    }

    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (OverlayService.running) return
        tracker.start(this, findViewById<PreviewView>(R.id.camPreview))
    }

    // ------------------------------------------------------------------ scéna
    private fun applyInsets() {
        val top = findViewById<View>(R.id.topBar)
        val cam = findViewById<View>(R.id.camCard)
        val panel = findViewById<View>(R.id.panel)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (top.layoutParams as ViewGroup.MarginLayoutParams).topMargin = b.top + 16.dp
            (cam.layoutParams as ViewGroup.MarginLayoutParams).topMargin = b.top + 16.dp
            (panel.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = b.bottom + 12.dp
            top.requestLayout(); cam.requestLayout(); panel.requestLayout()
            insets
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun setBackground(i: Int) {
        val engine = sceneView.engine
        bgIndex = i % BACKGROUNDS.size
        Prefs.get(this).edit().putInt("bgIndex", bgIndex).apply()
        val c = BACKGROUNDS[bgIndex]
        val old = skybox
        skybox = Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
        sceneView.scene.skybox = skybox
        old?.let { engine.destroySkybox(it) }
    }

    private fun reframe() {
        val panel = findViewById<View>(R.id.panel)
        val h = sceneView.height
        val cover = if (h > 0 && panel.visibility == View.VISIBLE && !recording) (h - panel.top).toFloat() / h else 0f
        avatar.applyFraming(cover.coerceIn(0f, 0.5f))
    }

    private fun updateStatus() {
        val seen = tracker.faceVisible
        if (seen != faceShown) {
            faceShown = seen
            findViewById<TextView>(R.id.statusText).apply {
                text = if (seen) "● sleduji obličej" else "● hledám obličej…"
                setTextColor(if (seen) Color.parseColor("#5BD07A") else Color.parseColor("#FFB74D"))
            }
        }
    }

    // ----------------------------------------------------------------- tlačítka
    private fun setupButtons() {
        val cArms = findViewById<Chip>(R.id.chipArms)
        val cWave = findViewById<Chip>(R.id.chipWave)
        val cHappy = findViewById<Chip>(R.id.chipHappy)
        val cBlush = findViewById<Chip>(R.id.chipBlush)
        cArms.setOnCheckedChangeListener { _, c -> avatar.armsOn = c; if (c) cWave.isChecked = false; avatar.saveExpressions() }
        cWave.setOnCheckedChangeListener { _, c -> avatar.waveOn = c; if (c) cArms.isChecked = false; avatar.saveExpressions() }
        cHappy.setOnCheckedChangeListener { _, c -> avatar.happyOn = c; avatar.saveExpressions() }
        cBlush.setOnCheckedChangeListener { _, c -> avatar.blushOn = c; avatar.saveExpressions() }
        findViewById<Chip>(R.id.chipBg).setOnClickListener {
            if (CustomBg.isOn(this)) { CustomBg.setOn(this, false); avatar.setBackdrop(null) }
            else setBackground(bgIndex + 1)
        }
        findViewById<Chip>(R.id.chipBgImage).setOnClickListener { pickBg.launch("image/*") }
        // efekty s vlastními modely
        val row = findViewById<android.widget.LinearLayout>(R.id.effectsRow)
        for (fx in Fx.values()) {
            val c = layoutInflater.inflate(R.layout.chip_effect, row, false) as Chip
            c.text = "${fx.emoji} ${fx.label}"
            c.setOnClickListener { avatar.trigger(fx) }
            row.addView(c)
        }
        findViewById<Chip>(R.id.chipCalib).setOnClickListener {
            tracker.calibrate()
            Toast.makeText(this, "Kalibrováno – dívej se rovně do kamery", Toast.LENGTH_SHORT).show()
        }
        findViewById<Chip>(R.id.chipFaceCam).setOnCheckedChangeListener { _, c ->
            avatar.faceCam = c; avatar.saveExpressions(); reframe()
        }
        findViewById<Chip>(R.id.chipBubble).setOnClickListener { startBubble() }
        findViewById<Chip>(R.id.chipBubble).setOnLongClickListener {
            Prefs.get(this).edit().remove("bubbleX").remove("bubbleY").remove("padX").remove("padY").apply()
            Toast.makeText(this, "Poloha bubliny vrácena na střed", Toast.LENGTH_SHORT).show(); true
        }
        findViewById<Chip>(R.id.chipVoice).setOnClickListener {
            startActivity(Intent(this, VoiceActivity::class.java))
        }
        findViewById<ExtendedFloatingActionButton>(R.id.btnRec).setOnClickListener { toggleRecording() }
        // klepnutí na postavu během nahrávání schová/ukáže panel
    }

    /** Výrazy mohla změnit bublina – promítnout je do čipů. */
    private fun syncChips() {
        avatar.loadExpressions()
        val a = avatar
        val arms = a.armsOn; val wave = a.waveOn; val happy = a.happyOn; val blush = a.blushOn
        val face = a.faceCam
        findViewById<Chip>(R.id.chipArms).isChecked = arms
        findViewById<Chip>(R.id.chipWave).isChecked = wave
        findViewById<Chip>(R.id.chipHappy).isChecked = happy
        findViewById<Chip>(R.id.chipBlush).isChecked = blush
        findViewById<Chip>(R.id.chipFaceCam).isChecked = face
        reframe()
    }

    // ----------------------------------------------------------------- bublina
    private fun startBubble() {
        if (recording) {
            Toast.makeText(this, "Nejdřív zastav nahrávání", Toast.LENGTH_SHORT).show(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bublina potřebuje kameru", Toast.LENGTH_SHORT).show(); return
        }
        if (Settings.canDrawOverlays(this)) launchBubble()
        else {
            Toast.makeText(this, "Povol „Zobrazovat přes jiné aplikace“ pro Hoppy VTuber", Toast.LENGTH_LONG).show()
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun launchBubble() {
        tracker.stop()                     // kameru převezme bublina
        avatar.saveExpressions()
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        // aplikaci zavřeme úplně: jen jeden 3D engine naráz (dva najednou na některých GPU padají)
        finish()
        Toast.makeText(this, "Hoppy je v bublině – táhni ji, klepnutím ovládání", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- nahrávání
    // ---------------------------------------------------------------- nahrávání
    // Nahrává se přímo 3D scéna (ne celá obrazovka) – tlačítka jsou vidět a fungují, ale ve videu nejsou.
    private var recorder: MediaRecorder? = null
    private var recUri: android.net.Uri? = null
    private var recPfd: android.os.ParcelFileDescriptor? = null
    private var recStart = 0L
    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val sec = (SystemClock.elapsedRealtime() - recStart) / 1000
            findViewById<TextView>(R.id.recBadge).text = "🔴 REC  %02d:%02d".format(sec / 60, sec % 60)
            sceneView.postDelayed(this, 500)
        }
    }

    private fun toggleRecording() { if (recording) stopSceneRecording() else startSceneRecording() }

    private fun startSceneRecording() {
        // video = velikost scény, max 1080 na šířku, sudé rozměry (kodek)
        val k = minOf(1f, 1080f / sceneView.width.coerceAtLeast(1))
        val w = ((sceneView.width * k).toInt() / 2) * 2
        val h = ((sceneView.height * k).toInt() / 2) * 2
        if (w < 16 || h < 16) return
        val withAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "hoppy_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HoppyVTuber")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            val pfd = contentResolver.openFileDescriptor(uri, "w")!!
            val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000); setAudioSamplingRate(44100)
                }
                prepare()
                start()
            }
            sceneView.startMirroring(r.surface, 0, 0, w, h)
            recorder = r; recUri = uri; recPfd = pfd
            recStart = SystemClock.elapsedRealtime()
            setRecordingUi(true)
        } catch (e: Exception) {
            CrashLog.note(this, "nahrávání start", e)
            Toast.makeText(this, "Nahrávání nejde spustit: ${e.message}", Toast.LENGTH_LONG).show()
            recorder?.release(); recorder = null
            recUri?.let { runCatching { contentResolver.delete(it, null, null) } }; recUri = null
        }
    }

    private fun stopSceneRecording() {
        val r = recorder ?: return
        recorder = null
        runCatching { sceneView.stopMirroring(r.surface) }
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        runCatching { recPfd?.close() }; recPfd = null
        if (!ok) recUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        recUri = null
        setRecordingUi(false)
        Toast.makeText(this, if (ok) "Uloženo do Movies/HoppyVTuber" else "Nahrávání selhalo", Toast.LENGTH_LONG).show()
    }

    private fun setRecordingUi(on: Boolean) {
        recording = on
        findViewById<ExtendedFloatingActionButton>(R.id.btnRec).apply {
            text = if (on) "■  Zastavit nahrávání" else "●  Nahrát video"
        }
        findViewById<View>(R.id.recBadge).visibility = if (on) View.VISIBLE else View.GONE
        if (on) recTick.run()
        // tlačítka zůstávají – do videa se nedostanou (nahrává se jen 3D scéna)
        sceneView.post { reframe() }
    }

    override fun onDestroy() {
        if (ready) tracker.close()
        super.onDestroy()
    }
}
