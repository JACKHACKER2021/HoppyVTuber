package cz.hoppy.vtuber

import android.opengl.Matrix
import android.os.SystemClock
import com.google.android.filament.IndirectLight
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Postava Hoppy ve SceneView: načte model a každý snímek ji natočí podle [FaceTracker].
 * Stejná třída běží v aplikaci i v plovoucí bublině nad hrou.
 */
class HoppyAvatar(
    private val sceneView: SceneView,
    private val tracker: FaceTracker,
    modelUnits: Float = MODEL_UNITS,
) {
    companion object {
        // ---- ladicí konstanty: pokud se postava otáčí na opačnou stranu, změň znaménko ----
        const val MIRROR = true            // postava se chová jako zrcadlo
        const val YAW_SIGN = 1f
        const val PITCH_SIGN = 1f
        const val ROLL_SIGN = 1f
        const val ARM_DEG = 120f           // jak vysoko jdou ruce (V tvar)
        const val MODEL_UNITS = 1.6f       // výška postavy ve scéně (chodidla y=0, špičky uší y=MODEL_UNITS)

        // mluvení: pusa se otevře nad OPEN, zavře pod CLOSE (hystereze = žádné blikání)
        const val MOUTH_OPEN = 0.16f
        const val MOUTH_CLOSE = 0.09f
        // úsměv (mouthSmile) nad touto hodnotou = šťastné oči
        const val SMILE_HAPPY = 0.7f
        // blush: poloha na tvářích (v prostoru hlavy) a velikost
        const val BLUSH_X = 0.108f
        const val BLUSH_Y = 0.066f
        const val BLUSH_SCALE = 0.9f

        // tan(fov/2)*2 pro výchozí objektiv 28 mm (senzor 24 mm)
        private const val VIEW_PER_DIST = 24f / 28f
    }

    /** true = jen hlava a ramena (facecam), false = celá postava */
    var faceCam = true

    /** Hlasitost Hoppy hlasu z měniče (0..1) – když mluví ElevenLabs hlas, hýbe se pusa podle něj. */
    var voiceLevel: () -> Float = { 0f }

    // výrazy / gesta (přepínají tlačítka)
    var armsOn = false
    var waveOn = false
    var happyOn = false
    var blushOn = false

    private val engine = sceneView.engine
    private var modelNode: ModelNode? = null

    /** base = transformace z modelu, shown = stejná, ale s měřítkem 1 (model ukládá skryté části zmenšené na 0.0001) */
    private class Bone(val entity: Int, val base: FloatArray, val shown: FloatArray)
    private val bones = HashMap<String, Bone>()

    // vyhlazené hodnoty
    private var sYaw = 0f; private var sPitch = 0f; private var sRoll = 0f
    private var sJaw = 0f
    private var armsAmt = 0f; private var waveAmt = 0f

    private var closedEyes = false; private var openMouth = false
    private var blushShown = false
    private var lastMouthK = -1f

    init {
        // rovnoměrné ambientní světlo, aby postava nebyla tmavá z boku/zespodu
        sceneView.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(28_000f)
            .build(engine)
        load(modelUnits)
    }

    private fun load(modelUnits: Float) {
        val instance = sceneView.modelLoader.createModelInstance("models/hoppy.glb")
        val node = ModelNode(
            modelInstance = instance,
            autoAnimate = false,                 // animace řídíme sami
            scaleToUnits = modelUnits,
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
            bones[name] = Bone(e, m, unitScale(m))
        }
        // blush trochu do stran a pod oči, ať nezasahuje do pusy
        bones["Face_BlushLeft"]?.shown?.let { placeBlush(it, 1f) }
        bones["Face_BlushRight"]?.shown?.let { placeBlush(it, -1f) }
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

    /** Kopie matice, kde mikroskopické osy (0.0001) vrátíme na délku 1. */
    private fun unitScale(src: FloatArray): FloatArray {
        val m = src.copyOf()
        for (c in 0..2) {
            val len = sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2])
            if (len in 1e-9f..0.01f) for (r in 0..2) m[c * 4 + r] /= len
        }
        return m
    }

    private fun placeBlush(m: FloatArray, side: Float) {
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) m[i] *= BLUSH_SCALE
        m[12] = side * BLUSH_X; m[13] = BLUSH_Y
    }

    private fun setVisible(name: String, on: Boolean) {
        val b = bones[name] ?: return
        val m = b.shown.copyOf()
        if (!on) for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) m[i] *= 0.0001f
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(b.entity), m)
    }

    /** Otevřená pusa: výška podle toho, jak moc otevřeš čelist (horní okraj zůstává na místě). */
    private fun setMouthOpenAmount(k: Float) {
        val b = bones["Face_MouthOpen"] ?: return
        val m = b.shown.copyOf()
        val top = 0.07f
        Matrix.translateM(m, 0, 0f, top, 0f)
        Matrix.scaleM(m, 0, 0.85f + 0.15f * k, k, 1f)
        Matrix.translateM(m, 0, 0f, -top, 0f)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(b.entity), m)
    }

    /**
     * Nastaví kameru tak, aby byla vidět hlava (facecam) nebo celá postava.
     * [bottomCover] = jaká část obrazovky dole je zakrytá panelem (0..1).
     */
    fun applyFraming(bottomCover: Float = 0f) {
        val w = sceneView.width; val h = sceneView.height
        if (w <= 0 || h <= 0) return
        val aspect = w.toFloat() / h
        val u = MODEL_UNITS
        // [spodní okraj, horní okraj, potřebná šířka] ve světových jednotkách
        val (lo, hi, needW) = if (faceCam) Triple(0.40f * u, 1.04f * u, 0.62f * u)
                              else Triple(-0.03f * u, 1.04f * u, 0.50f * u)
        val free = (1f - bottomCover).coerceIn(0.4f, 1f)
        val needH = max((hi - lo) / free, needW / aspect)
        val dist = needH / VIEW_PER_DIST
        // střed záběru posuneme dolů, aby postava byla nad panelem
        val camY = hi - needH / 2f
        sceneView.cameraNode.position = Position(0f, camY, dist)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Volat každý snímek (sceneView.onFrame). */
    fun update() {
        val t = SystemClock.uptimeMillis() / 1000f
        sYaw = lerp(sYaw, tracker.yaw - tracker.offYaw, 0.35f)
        sPitch = lerp(sPitch, tracker.pitch - tracker.offPitch, 0.35f)
        sRoll = lerp(sRoll, tracker.roll - tracker.offRoll, 0.35f)
        sJaw = lerp(sJaw, max(tracker.jaw, voiceLevel() * 0.6f), 0.5f)

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
        val blinkAvg = (tracker.blinkL + tracker.blinkR) / 2f
        val blink = if (closedEyes) blinkAvg > 0.4f else blinkAvg > 0.55f
        val smiling = tracker.smile > SMILE_HAPPY && sJaw < MOUTH_OPEN
        val closed = blink || happyOn || smiling
        if (closed != closedEyes) {
            closedEyes = closed
            setVisible("Face_EyeLeft", !closed); setVisible("Face_EyeRight", !closed)
            setVisible("Face_EyesClosed", closed)
        }
        val open = if (openMouth) sJaw > MOUTH_CLOSE else sJaw > MOUTH_OPEN
        if (open != openMouth) {
            openMouth = open
            setVisible("Face_MouthClosed", !open)
            if (!open) { setVisible("Face_MouthOpen", false); lastMouthK = -1f }
        }
        if (open) {
            val k = (0.45f + (sJaw - MOUTH_CLOSE) / 0.35f).coerceIn(0.45f, 1f)
            if (abs(k - lastMouthK) > 0.02f) { lastMouthK = k; setMouthOpenAmount(k) }
        }
        if (blushOn != blushShown) {
            blushShown = blushOn
            setVisible("Face_BlushLeft", blushOn); setVisible("Face_BlushRight", blushOn)
        }
        modelNode?.modelInstance?.animator?.updateBoneMatrices()
    }

    // výrazy se ukládají, aby bublina převzala nastavení z aplikace a naopak
    fun loadExpressions() {
        val p = Prefs.get(sceneView.context)
        armsOn = p.getBoolean("arms", false); waveOn = p.getBoolean("wave", false)
        happyOn = p.getBoolean("happy", false); blushOn = p.getBoolean("blush", false)
        faceCam = p.getBoolean("faceCam", true)
    }

    fun saveExpressions() {
        Prefs.get(sceneView.context).edit()
            .putBoolean("arms", armsOn).putBoolean("wave", waveOn)
            .putBoolean("happy", happyOn).putBoolean("blush", blushOn)
            .putBoolean("faceCam", faceCam)
            .apply()
    }
}
