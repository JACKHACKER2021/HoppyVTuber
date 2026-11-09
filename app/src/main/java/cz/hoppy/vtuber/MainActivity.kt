package cz.hoppy.vtuber

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix as GMatrix
import android.media.projection.MediaProjectionManager
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Hoppy VTuber: kamera -> MediaPipe FaceLandmarker -> kosti/obličej postavy (SceneView/Filament).
 */
class MainActivity : AppCompatActivity() {

    // ---- ladicí konstanty: pokud se postava otáčí na opačnou stranu, změň znaménko ----
    private val MIRROR = true            // postava se chová jako zrcadlo
    private val YAW_SIGN = 1f
    private val PITCH_SIGN = 1f
    private val ROLL_SIGN = 1f
    private val ARM_DEG = 120f           // jak vysoko jdou ruce (V tvar)
    private val CAM_Z = 2.5f             // vzdálenost kamery; zvětši = postava menší
    private val MODEL_UNITS = 1.6f       // výška postavy ve scéně
    // pozadí: levandule, chroma-green (pro OBS), tmavé, bílé
    private val BACKGROUNDS = arrayOf(
        floatArrayOf(0.965f, 0.953f, 0.988f), floatArrayOf(0f, 0.69f, 0.25f),
        floatArrayOf(0.08f, 0.09f, 0.12f), floatArrayOf(1f, 1f, 1f)
    )
    private var bgIndex = 0
    private var skybox: Skybox? = null
    private var faceSeenAt = 0L
    private var faceShown = false

    private lateinit var sceneView: SceneView
    private lateinit var engine: Engine
    private var modelNode: ModelNode? = null

    private class Bone(val entity: Int, val base: FloatArray)
    private val bones = HashMap<String, Bone>()

    // data z trackingu (zapisuje analyzer vlákno)
    @Volatile private var tYaw = 0f
    @Volatile private var tPitch = 0f
    @Volatile private var tRoll = 0f
    @Volatile private var tBlinkL = 0f
    @Volatile private var tBlinkR = 0f
    @Volatile private var tJaw = 0f
    @Volatile private var tSmile = 0f
    private var offYaw = 0f; private var offPitch = 0f; private var offRoll = 0f

    // vyhlazené hodnoty
    private var sYaw = 0f; private var sPitch = 0f; private var sRoll = 0f
    private var sJaw = 0f
    private var armsAmt = 0f; private var waveAmt = 0f

    // stavy tlačítek
    private var armsOn = false; private var waveOn = false
    private var happyOn = false; private var blushOn = false
    private var recording = false

    private val camExecutor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Bez kamery se postava nehýbe", Toast.LENGTH_LONG).show()
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
        engine = sceneView.engine
        applyInsets()

        setupScene()
        loadAvatar()
        setupButtons()
        setupLandmarker()

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())

        sceneView.onFrame = { updateAvatar() }

        ScreenRecordService.onFinished = { uri ->
            runOnUiThread {
                setRecordingUi(false)
                Toast.makeText(this, if (uri != null) "Uloženo do Movies/HoppyVTuber" else "Nahrávání selhalo", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun setupScene() {
        // rovnoměrné ambientní světlo, aby postava nebyla tmavá z boku/zespodu
        val il = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(28_000f)
            .build(engine)
        sceneView.scene.indirectLight = il
        setBackground(0)
        sceneView.cameraNode.position = Position(0f, 0f, CAM_Z)
    }

    private fun setBackground(i: Int) {
        bgIndex = i % BACKGROUNDS.size
        val c = BACKGROUNDS[bgIndex]
        val old = skybox
        skybox = Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
        sceneView.scene.skybox = skybox
        old?.let { engine.destroySkybox(it) }
    }

    // ------------------------------------------------------------------ avatar
    private fun loadAvatar() {
        val instance = sceneView.modelLoader.createModelInstance("models/hoppy.glb")
        val node = ModelNode(
            modelInstance = instance,
            autoAnimate = false,                 // animace řídíme sami
            scaleToUnits = MODEL_UNITS,
            centerOrigin = Position(0f, 0f, 0f)
        )
        sceneView.addChildNode(node)
        modelNode = node
        val asset = instance.asset
        val tm = engine.transformManager
        for (name in listOf(
            "Head", "LeftUpperArm", "RightUpperArm", "RightLowerArm", "UpperTorso", "Tail",
            "Face_EyeLeft", "Face_EyeRight", "Face_EyesClosed",
            "Face_MouthClosed", "Face_MouthOpen", "Face_BlushLeft", "Face_BlushRight"
        )) {
            val e = asset.getFirstEntityByName(name)
            if (e == 0) continue
            val m = FloatArray(16)
            tm.getTransform(tm.getInstance(e), m)
            bones[name] = Bone(e, m)
        }
        // modelové výchozí skrytí (zavřené oči, otevřená ústa, blush)
        for (n in listOf("Face_EyesClosed", "Face_MouthOpen", "Face_BlushLeft", "Face_BlushRight")) setVisible(n, false)
    }

    private fun setRot(name: String, rx: Float, ry: Float, rz: Float) {
        val b = bones[name] ?: return
        val r = FloatArray(16); val a = FloatArray(16); val t = FloatArray(16)
        Matrix.setIdentityM(r, 0)
        Matrix.setRotateM(a, 0, ry, 0f, 1f, 0f); Matrix.multiplyMM(t, 0, r, 0, a, 0)
        Matrix.setRotateM(a, 0, rx, 1f, 0f, 0f); Matrix.multiplyMM(r, 0, t, 0, a, 0)
        Matrix.setRotateM(a, 0, rz, 0f, 0f, 1f); Matrix.multiplyMM(t, 0, r, 0, a, 0)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, b.base, 0, t, 0)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(b.entity), out)
    }

    private fun setVisible(name: String, on: Boolean) {
        val b = bones[name] ?: return
        val m = b.base.copyOf()
        if (!on) for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) m[i] *= 0.0001f
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(b.entity), m)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private var closedEyes = false; private var openMouth = false
    private var blushShown = false

    /** volá se každý snímek */
    private fun updateAvatar() {
        val t = SystemClock.uptimeMillis() / 1000f
        sYaw = lerp(sYaw, tYaw - offYaw, 0.35f)
        sPitch = lerp(sPitch, tPitch - offPitch, 0.35f)
        sRoll = lerp(sRoll, tRoll - offRoll, 0.35f)
        sJaw = lerp(sJaw, tJaw, 0.5f)

        val m = if (MIRROR) -1f else 1f
        setRot("Head", PITCH_SIGN * sPitch, m * YAW_SIGN * sYaw, m * ROLL_SIGN * sRoll)
        setRot("UpperTorso", 0f, m * YAW_SIGN * sYaw * 0.2f, 0f)
        setRot("Tail", 0f, 0f, 10f * sin(t * 5f))

        // ruce
        armsAmt = lerp(armsAmt, if (armsOn) 1f else 0f, 0.15f)
        waveAmt = lerp(waveAmt, if (waveOn) 1f else 0f, 0.15f)
        setRot("LeftUpperArm", 0f, 0f, ARM_DEG * armsAmt)
        setRot("RightUpperArm", 0f, 0f, -maxOf(ARM_DEG * armsAmt, 135f * waveAmt))
        setRot("RightLowerArm", 0f, 0f, 35f * sin(t * 14f) * waveAmt)

        // oči: zavřené při mrknutí NEBO při "Radost"
        val blink = (tBlinkL + tBlinkR) / 2f > 0.55f
        val closed = blink || happyOn
        if (closed != closedEyes) {
            closedEyes = closed
            setVisible("Face_EyeLeft", !closed); setVisible("Face_EyeRight", !closed)
            setVisible("Face_EyesClosed", closed)
        }
        val open = sJaw > 0.25f
        if (open != openMouth) {
            openMouth = open
            setVisible("Face_MouthClosed", !open); setVisible("Face_MouthOpen", open)
        }
        if (blushOn != blushShown) {
            blushShown = blushOn
            setVisible("Face_BlushLeft", blushOn); setVisible("Face_BlushRight", blushOn)
        }
        modelNode?.modelInstance?.animator?.updateBoneMatrices()

        val seen = SystemClock.uptimeMillis() - faceSeenAt < 700
        if (seen != faceShown) {
            faceShown = seen
            runOnUiThread {
                findViewById<TextView>(R.id.statusText).apply {
                    text = if (seen) "● sleduji obličej" else "● hledám obličej…"
                    setTextColor(if (seen) Color.parseColor("#5BD07A") else Color.parseColor("#FFB74D"))
                }
            }
        }
    }

    // ----------------------------------------------------------------- tlačítka
    private fun setupButtons() {
        val cArms = findViewById<Chip>(R.id.chipArms)
        val cWave = findViewById<Chip>(R.id.chipWave)
        val cHappy = findViewById<Chip>(R.id.chipHappy)
        val cBlush = findViewById<Chip>(R.id.chipBlush)
        cArms.setOnCheckedChangeListener { _, c -> armsOn = c; if (c) cWave.isChecked = false }
        cWave.setOnCheckedChangeListener { _, c -> waveOn = c; if (c) cArms.isChecked = false }
        cHappy.setOnCheckedChangeListener { _, c -> happyOn = c }
        cBlush.setOnCheckedChangeListener { _, c -> blushOn = c }
        findViewById<Chip>(R.id.chipBg).setOnClickListener { setBackground(bgIndex + 1) }
        findViewById<Chip>(R.id.chipCalib).setOnClickListener {
            offYaw = tYaw; offPitch = tPitch; offRoll = tRoll
            Toast.makeText(this, "Kalibrováno – dívej se rovně do kamery", Toast.LENGTH_SHORT).show()
        }
        findViewById<ExtendedFloatingActionButton>(R.id.btnRec).setOnClickListener { toggleRecording() }
        // klepnutí na postavu během nahrávání schová/ukáže panel
        sceneView.setOnClickListener {
            if (recording) {
                val p = findViewById<View>(R.id.panel)
                p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

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
    }

    // ----------------------------------------------------------------- tracking
    private fun setupLandmarker() {
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { res, _ -> onFace(res) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(this, opts)
    }

    private fun onFace(res: FaceLandmarkerResult) {
        res.facialTransformationMatrixes().ifPresent { list ->
            if (list.isNotEmpty()) {
                faceSeenAt = SystemClock.uptimeMillis()
                val m = list[0]                       // 4x4, column-major: m[col*4+row]
                fun r(i: Int, j: Int) = m[j * 4 + i]
                val deg = (180f / PI.toFloat())
                tYaw = atan2(r(0, 2), r(2, 2)) * deg
                tPitch = asin((-r(1, 2)).coerceIn(-1f, 1f)) * deg
                tRoll = atan2(r(1, 0), r(1, 1)) * deg
            }
        }
        res.faceBlendshapes().ifPresent { all ->
            if (all.isNotEmpty()) {
                for (c in all[0]) when (c.categoryName()) {
                    "eyeBlinkLeft" -> tBlinkL = c.score()
                    "eyeBlinkRight" -> tBlinkR = c.score()
                    "jawOpen" -> tJaw = c.score()
                    "mouthSmileLeft" -> tSmile = c.score()
                }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(findViewById<PreviewView>(R.id.camPreview).surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(camExecutor) { proxy ->
                val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
                val rot = GMatrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rot, true)
                landmarker?.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        landmarker?.close()
        camExecutor.shutdown()
        super.onDestroy()
    }
}
