package cz.hoppy.vtuber

import android.opengl.Matrix
import android.os.SystemClock
import com.google.android.filament.IndirectLight
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlin.math.sin

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
        const val MODEL_UNITS = 1.6f       // výška postavy ve scéně
    }

    // výrazy / gesta (přepínají tlačítka)
    var armsOn = false
    var waveOn = false
    var happyOn = false
    var blushOn = false

    private val engine = sceneView.engine
    private var modelNode: ModelNode? = null

    private class Bone(val entity: Int, val base: FloatArray)
    private val bones = HashMap<String, Bone>()

    // vyhlazené hodnoty
    private var sYaw = 0f; private var sPitch = 0f; private var sRoll = 0f
    private var sJaw = 0f
    private var armsAmt = 0f; private var waveAmt = 0f

    private var closedEyes = false; private var openMouth = false
    private var blushShown = false

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

    /** Volat každý snímek (sceneView.onFrame). */
    fun update() {
        val t = SystemClock.uptimeMillis() / 1000f
        sYaw = lerp(sYaw, tracker.yaw - tracker.offYaw, 0.35f)
        sPitch = lerp(sPitch, tracker.pitch - tracker.offPitch, 0.35f)
        sRoll = lerp(sRoll, tracker.roll - tracker.offRoll, 0.35f)
        sJaw = lerp(sJaw, tracker.jaw, 0.5f)

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
        val blink = (tracker.blinkL + tracker.blinkR) / 2f > 0.55f
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
    }

    // výrazy se ukládají, aby bublina převzala nastavení z aplikace a naopak
    fun loadExpressions() {
        val p = Prefs.get(sceneView.context)
        armsOn = p.getBoolean("arms", false); waveOn = p.getBoolean("wave", false)
        happyOn = p.getBoolean("happy", false); blushOn = p.getBoolean("blush", false)
    }

    fun saveExpressions() {
        Prefs.get(sceneView.context).edit()
            .putBoolean("arms", armsOn).putBoolean("wave", waveOn)
            .putBoolean("happy", happyOn).putBoolean("blush", blushOn)
            .apply()
    }
}
