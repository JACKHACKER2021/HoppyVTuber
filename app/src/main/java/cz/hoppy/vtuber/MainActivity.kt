package cz.hoppy.vtuber

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
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

    // pozadí: levandule, chroma-green (pro OBS), tmavé, bílé
    private val BACKGROUNDS = arrayOf(
        floatArrayOf(0.965f, 0.953f, 0.988f), floatArrayOf(0f, 0.69f, 0.25f),
        floatArrayOf(0.08f, 0.09f, 0.12f), floatArrayOf(1f, 1f, 1f)
    )
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

    private val overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) launchBubble()
        else Toast.makeText(this, "Bez povolení „Zobrazovat přes jiné aplikace“ bublina nepůjde", Toast.LENGTH_LONG).show()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            val i = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_CODE, r.resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, r.data)
            ContextCompat.startForegroundService(this, i)
            setRecordingUi(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        sceneView = findViewById(R.id.sceneView)
        applyInsets()

        setBackground(0)
        sceneView.cameraManipulator = null      // dotyk neotáčí kamerou, záběr řídí Facecam
        tracker = FaceTracker(this)
        avatar = HoppyAvatar(sceneView, tracker)
        avatar.loadExpressions()
        // záběr přepočítat při každé změně velikosti (panel dole zakrývá část obrazovky)
        sceneView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reframe() }
        setupButtons()

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())

        sceneView.onFrame = {
            avatar.update()
            updateStatus()
        }

        ScreenRecordService.onFinished = { uri ->
            runOnUiThread {
                setRecordingUi(false)
                Toast.makeText(this, if (uri != null) "Uloženo do Movies/HoppyVTuber" else "Nahrávání selhalo", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // návrat do aplikace = bublina se zavře a postava se vrátí sem
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            sceneView.postDelayed({ startTracking() }, 300)   // ať kamera stihne přejít zpět
        } else {
            startTracking()
        }
        syncChips()
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
        val c = BACKGROUNDS[bgIndex]
        val old = skybox
        skybox = Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
        sceneView.scene.skybox = skybox
        old?.let { engine.destroySkybox(it) }
    }

    private fun reframe() {
        val panel = findViewById<View>(R.id.panel)
        val h = sceneView.height
        val cover = if (h > 0 && panel.visibility == View.VISIBLE) (h - panel.top).toFloat() / h else 0f
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
        findViewById<Chip>(R.id.chipBg).setOnClickListener { setBackground(bgIndex + 1) }
        findViewById<Chip>(R.id.chipCalib).setOnClickListener {
            tracker.calibrate()
            Toast.makeText(this, "Kalibrováno – dívej se rovně do kamery", Toast.LENGTH_SHORT).show()
        }
        findViewById<Chip>(R.id.chipFaceCam).setOnCheckedChangeListener { _, c ->
            avatar.faceCam = c; avatar.saveExpressions(); reframe()
        }
        findViewById<Chip>(R.id.chipBubble).setOnClickListener { startBubble() }
        findViewById<ExtendedFloatingActionButton>(R.id.btnRec).setOnClickListener { toggleRecording() }
        // klepnutí na postavu během nahrávání schová/ukáže panel
        sceneView.setOnClickListener {
            if (recording) {
                val p = findViewById<View>(R.id.panel)
                p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                sceneView.post { reframe() }
            }
        }
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
        moveTaskToBack(true)               // pryč z cesty – spusť si hru
        Toast.makeText(this, "Hoppy je v bublině – táhni ji, klepnutím ovládání", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- nahrávání
    private fun toggleRecording() {
        if (!recording) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recording = on
        findViewById<ExtendedFloatingActionButton>(R.id.btnRec).apply {
            text = if (on) "■  Zastavit nahrávání" else "●  Nahrát video"
        }
        findViewById<View>(R.id.recBadge).visibility = if (on) View.VISIBLE else View.GONE
        // během nahrávání schováme vše kromě postavy, ať to není ve videu
        findViewById<View>(R.id.camCard).alpha = if (on) 0f else 1f
        findViewById<View>(R.id.topBar).alpha = if (on) 0f else 1f
        findViewById<View>(R.id.panel).visibility = if (on) View.GONE else View.VISIBLE
        sceneView.post { reframe() }
    }

    override fun onDestroy() {
        tracker.close()
        super.onDestroy()
    }
}
