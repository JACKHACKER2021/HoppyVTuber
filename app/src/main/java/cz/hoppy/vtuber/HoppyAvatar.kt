package cz.hoppy.vtuber

import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.SystemClock
import com.google.android.filament.IndirectLight
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.ModelNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Postava Hoppy ve SceneView.
 * Každý snímek: animace z modelu (Idle = houpání uší, ocásku a těla jako v originále; Wave; Happy)
 * -> navrch natočení hlavy z kamery -> pružiny uší a ocásku -> efekty -> obličej.
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
        private const val RAW_HEIGHT = 1.4806f   // výška modelu v souboru

        // mluvení: pusa se otevře nad OPEN, zavře pod CLOSE (hystereze = žádné blikání)
        const val MOUTH_OPEN = 0.16f
        const val MOUTH_CLOSE = 0.09f
        // úsměv (mouthSmile) nad touto hodnotou = šťastné oči
        const val SMILE_HAPPY = 0.7f
        // blush: poloha na tvářích (v prostoru hlavy) a velikost
        const val BLUSH_X = 0.108f
        const val BLUSH_Y = 0.066f
        const val BLUSH_SCALE = 0.9f

        // pružnost: jak moc uši a ocásek dobíhají za pohybem hlavy
        const val EAR_FLOP = 0.35f         // víc = uši víc plandají
        const val EAR_STIFF = 60f          // tuhost pružiny (víc = rychlejší návrat)
        const val EAR_DAMP = 7f            // tlumení (míň = víc se houpou)
        const val TAIL_WAG = 12f           // základní vrtění ocáskem (stupně)
        const val BODY_FOLLOW = 0.25f      // kolik natočení hlavy převezme tělo

        private const val EYE_CY = 0.1425f
        private const val EYE_CX = 0.065f

        // tan(fov/2)*2 pro výchozí objektiv 28 mm (senzor 24 mm)
        private const val VIEW_PER_DIST = 24f / 28f
    }

    /** true = jen hlava a ramena (facecam), false = celá postava */
    var faceCam = true

    /** Hlasitost hlasu z měniče (0..1) – pusa se hýbe i podle něj. */
    var voiceLevel: () -> Float = { 0f }

    // výrazy / gesta (přepínají tlačítka)
    var armsOn = false
    var waveOn = false      // animace Wave z modelu
    var happyOn = false     // animace Happy z modelu (ruce do stran, zavřené oči, blush)
    var blushOn = false

    private val engine = sceneView.engine
    private val tm = engine.transformManager
    private var modelNode: ModelNode? = null
    val effects = Effects(sceneView)
    private val pose = FxPose()
    /** Oblečky a sluchátka (null, dokud se nenačte model). */
    var wardrobe: Wardrobe? = null; private set

    private class Bone(val entity: Int, val base: FloatArray, val shown: FloatArray)
    private val bones = HashMap<String, Bone>()
    private val anim = HashMap<String, Int>()

    // vyhlazené hodnoty
    private var sYaw = 0f; private var sPitch = 0f; private var sRoll = 0f
    private var sJaw = 0f
    private var armsAmt = 0f; private var waveAmt = 0f; private var happyAmt = 0f
    private var pYaw = 0f; private var pPitch = 0f; private var pRoll = 0f
    private var lastT = -1f

    // pružiny [úhel, rychlost]
    private val earRoll = FloatArray(2); private val earPitch = FloatArray(2); private val tailSwing = FloatArray(2)

    private var closedEyes = false; private var openMouth = false

    // vlastní obrázek pozadí
    private var backdrop: ImageNode? = null
    private var backdropAspect = 1f

    init {
        // rovnoměrné ambientní světlo, aby postava nebyla tmavá z boku/zespodu
        sceneView.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(28_000f)
            .build(engine)
        load(modelUnits)
    }

    private fun load(modelUnits: Float) {
        val instance = sceneView.modelLoader.createModelInstance(ModelBuffers.get(sceneView.context, "models/hoppy.glb"))
        val node = ModelNode(
            modelInstance = instance,
            autoAnimate = false,                 // animace řídíme sami
            scaleToUnits = modelUnits,
            centerOrigin = Position(0f, 0f, 0f)
        )
        sceneView.addChildNode(node)
        modelNode = node
        val asset = instance.asset
        for (name in listOf(
            "Root", "Head", "UpperTorso", "Tail", "LeftEar", "RightEar",
            "LeftUpperArm", "RightUpperArm", "LeftLowerArm", "RightLowerArm",
            "Face_EyeLeft", "Face_EyeRight", "Face_EyesClosed",
            "Face_MouthClosed", "Face_MouthOpen", "Face_BlushLeft", "Face_BlushRight"
        )) {
            val e = asset.getFirstEntityByName(name)
            if (e == 0) continue
            val m = FloatArray(16)
            tm.getTransform(tm.getInstance(e), m)
            bones[name] = Bone(e, m, unitScale(m))
        }
        bones["Face_BlushLeft"]?.shown?.let { placeBlush(it, 1f) }
        bones["Face_BlushRight"]?.shown?.let { placeBlush(it, -1f) }
        val all = HashMap<String, Int>()
        for (n in Wardrobe.BONES) asset.getFirstEntityByName(n).takeIf { it != 0 }?.let { all[n] = it }
        wardrobe = Wardrobe(sceneView, node, all)
        instance.animator.let { a ->
            for (i in 0 until a.animationCount) anim[a.getAnimationName(i)] = i
        }
    }

    // ------------------------------------------------------------- matice
    private fun get(name: String): FloatArray? {
        val b = bones[name] ?: return null
        val m = FloatArray(16); tm.getTransform(tm.getInstance(b.entity), m); return m
    }
    private fun put(name: String, m: FloatArray) {
        val b = bones[name] ?: return
        tm.setTransform(tm.getInstance(b.entity), m)
    }
    /** Aktuální (animovaná) transformace kosti × rotace navíc. */
    private fun addRot(name: String, rx: Float, ry: Float, rz: Float) {
        val m = get(name) ?: return
        if (ry != 0f) Matrix.rotateM(m, 0, ry, 0f, 1f, 0f)
        if (rx != 0f) Matrix.rotateM(m, 0, rx, 1f, 0f, 0f)
        if (rz != 0f) Matrix.rotateM(m, 0, rz, 0f, 0f, 1f)
        put(name, m)
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

    /** Obličejová část: viditelná/skrytá, volitelně zmenšená/otočená kolem bodu (cx, cy). */
    private fun face(name: String, on: Boolean, sx: Float = 1f, sy: Float = 1f, cx: Float = 0f, cy: Float = 0f) {
        val b = bones[name] ?: return
        val m = b.shown.copyOf()
        if (!on) { for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) m[i] *= 0.0001f }
        else if (sx != 1f || sy != 1f) {
            Matrix.translateM(m, 0, cx, cy, 0f)
            Matrix.scaleM(m, 0, sx, sy, 1f)
            Matrix.translateM(m, 0, -cx, -cy, 0f)
        }
        put(name, m)
    }

    private fun spring(s: FloatArray, target: Float, dt: Float, stiff: Float = EAR_STIFF, damp: Float = EAR_DAMP) {
        s[1] += ((target - s[0]) * stiff - s[1] * damp) * dt
        s[0] += s[1] * dt
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
        placeBackdrop(camY, dist, needH, aspect)
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    fun trigger(fx: Fx) = effects.trigger(fx, SystemClock.uptimeMillis() / 1000f)

    /** Volat každý snímek (sceneView.onFrame). */
    fun update() {
        val inst = modelNode?.modelInstance ?: return
        val t = SystemClock.uptimeMillis() / 1000f
        val dt = if (lastT < 0) 1 / 60f else (t - lastT).coerceIn(0.001f, 0.05f)
        lastT = t

        // --- tracking (vyhlazený)
        sYaw = lerp(sYaw, tracker.yaw - tracker.offYaw, 0.35f)
        sPitch = lerp(sPitch, tracker.pitch - tracker.offPitch, 0.35f)
        sRoll = lerp(sRoll, tracker.roll - tracker.offRoll, 0.35f)
        sJaw = lerp(sJaw, max(tracker.jaw, voiceLevel() * 0.6f), 0.5f)

        // --- efekty (potřebují světovou matici hlavy z minulého snímku)
        pose.reset()
        val headW = FloatArray(16)
        bones["Head"]?.let { tm.getWorldTransform(tm.getInstance(it.entity), headW) }
        try { effects.update(t, dt, headW, pose) }
        catch (e: Exception) { CrashLog.note(sceneView.context, "efekt", e); effects.stop(); pose.reset() }

        // --- 1) animace z modelu: vše na výchozí pózu, pak Idle, Wave / Happy prolnuté
        for ((_, b) in bones) tm.setTransform(tm.getInstance(b.entity), b.base)
        val a = inst.animator
        waveAmt = lerp(waveAmt, if (waveOn) 1f else 0f, 0.12f)
        happyAmt = lerp(happyAmt, if (happyOn) 1f else 0f, 0.12f)
        val idle = anim["Idle"]
        val idleT = t % 4f
        idle?.let { a.applyAnimation(it, idleT) }
        anim["Wave"]?.let { w -> if (waveAmt > 0.01f) { a.applyAnimation(w, t % 2.4f); idle?.let { a.applyCrossFade(it, idleT, waveAmt) } } }
        anim["Happy"]?.let { h -> if (happyAmt > 0.01f) { a.applyAnimation(h, t % 2f); idle?.let { a.applyCrossFade(it, idleT, happyAmt) } } }

        // --- 2) hlava = animace × kamera × efekt
        val m = if (MIRROR) -1f else 1f
        val yaw = m * YAW_SIGN * sYaw + pose.yaw
        val pitch = PITCH_SIGN * sPitch + pose.pitch
        val roll = m * ROLL_SIGN * sRoll + pose.roll
        addRot("Head", pitch, yaw, roll)
        addRot("UpperTorso", pitch * 0.15f, yaw * BODY_FOLLOW, roll * 0.3f)
        if (pose.lift != 0f) get("Root")?.let { r -> r[13] += pose.lift * RAW_HEIGHT / MODEL_UNITS; put("Root", r) }

        // --- 3) pružné uši a ocásek: dobíhají za rychlostí hlavy
        val vYaw = (yaw - pYaw) / dt; val vPitch = (pitch - pPitch) / dt; val vRoll = (roll - pRoll) / dt
        pYaw = yaw; pPitch = pitch; pRoll = roll
        if (pose.kick != 0f) { earPitch[1] += 900f * pose.kick }
        spring(earRoll, (-vRoll - vYaw * 0.5f) * EAR_FLOP * 0.05f, dt)
        spring(earPitch, (vPitch * EAR_FLOP * 0.05f) + pose.lift * 300f, dt)
        earRoll[0] = earRoll[0].coerceIn(-35f, 35f); earPitch[0] = earPitch[0].coerceIn(-40f, 40f)
        // svěšené uši: dozadu a jen trochu do stran
        val droop = pose.earDroop
        addRot("LeftEar", earPitch[0] - 24f * droop, 0f, earRoll[0] - 10f * droop)
        addRot("RightEar", earPitch[0] - 24f * droop, 0f, earRoll[0] + 10f * droop)
        val wagSpeed = 6f + 6f * max(0f, pose.wag) - 3f * max(0f, -pose.wag)
        val wagAmp = TAIL_WAG * (1f + 1.5f * max(0f, pose.wag) - 0.6f * max(0f, -pose.wag))
        spring(tailSwing, -vYaw * 0.08f, dt, 40f, 5f)
        addRot("Tail", 0f, tailSwing[0], wagAmp * sin(t * wagSpeed))

        // --- 4) ruce: V pose přes animaci
        armsAmt = lerp(armsAmt, if (armsOn || pose.arms > 0.5f) 1f else 0f, 0.15f)
        if (armsAmt > 0.01f) {
            addRot("LeftUpperArm", 0f, 0f, ARM_DEG * armsAmt)
            addRot("RightUpperArm", 0f, 0f, -ARM_DEG * armsAmt)
        }

        // --- 5) obličej (animace ho přepsaly, nastavujeme každý snímek)
        val blinkAvg = (tracker.blinkL + tracker.blinkR) / 2f
        val blink = if (closedEyes) blinkAvg > 0.4f else blinkAvg > 0.55f
        val smiling = tracker.smile > SMILE_HAPPY && sJaw < MOUTH_OPEN
        val eyes = when {
            pose.eyes != Eyes.AUTO -> pose.eyes
            happyAmt > 0.5f || happyOn || blink || smiling -> Eyes.CLOSED
            else -> Eyes.AUTO
        }
        closedEyes = eyes == Eyes.CLOSED
        val openEyes = eyes == Eyes.AUTO || eyes == Eyes.WIDE
        val es = 1f
        face("Face_EyeLeft", openEyes, es, es * pose.squint, EYE_CX, EYE_CY)
        face("Face_EyeRight", openEyes, es, es * pose.squint, -EYE_CX, EYE_CY)
        when (eyes) {
            Eyes.CLOSED -> face("Face_EyesClosed", true)
            Eyes.UWU -> face("Face_EyesClosed", true, -1f, -1f, 0f, EYE_CY)   // otočené o 180° = „u u“
            else -> face("Face_EyesClosed", false)
        }

        val wantK: Float? = when {
            pose.mouth == 0f -> null
            pose.mouth > 0f -> pose.mouth.coerceIn(0.45f, 1f)
            else -> {
                val open = if (openMouth) sJaw > MOUTH_CLOSE else sJaw > MOUTH_OPEN
                if (open) (0.45f + (sJaw - MOUTH_CLOSE) / 0.35f).coerceIn(0.45f, 1f) else null
            }
        }
        openMouth = wantK != null
        face("Face_MouthClosed", wantK == null)
        if (wantK != null) face("Face_MouthOpen", true, 0.85f + 0.15f * wantK, wantK, 0f, 0.07f)
        else face("Face_MouthOpen", false)

        val blush = blushOn || pose.blush || happyAmt > 0.5f
        face("Face_BlushLeft", blush); face("Face_BlushRight", blush)

        a.updateBoneMatrices()
        wardrobe?.sync()
    }

    // ------------------------------------------------------------- vlastní pozadí
    /** Obrázek za postavou (null = zpět na barvu). Drží se kamery a vždy vyplní záběr. */
    fun setBackdrop(bmp: Bitmap?) {
        backdrop?.let { sceneView.removeChildNode(it); it.destroy() }
        backdrop = null
        if (bmp == null) return
        backdropAspect = bmp.width.toFloat() / bmp.height
        backdrop = ImageNode(sceneView.materialLoader, bmp, Size(backdropAspect, 1f), normal = Direction(z = 1f)).also {
            sceneView.addChildNode(it)
        }
        applyFramingAgain()
    }

    private var lastFrame: FloatArray? = null
    private fun applyFramingAgain() { lastFrame?.let { placeBackdrop(it[0], it[1], it[2], it[3]) } }

    private fun placeBackdrop(camY: Float, dist: Float, viewH: Float, aspect: Float) {
        lastFrame = floatArrayOf(camY, dist, viewH, aspect)
        val node = backdrop ?: return
        val back = 6f                                   // jak daleko za postavou
        val h = viewH * (dist + back) / dist            // výška záběru v té vzdálenosti
        val w = h * aspect
        // „cover“: obrázek pokryje celý záběr
        val s = max(w / backdropAspect, h) * 1.02f
        node.position = Position(0f, camY, -back)
        node.scale = io.github.sceneview.math.Scale(s, s, 1f)
    }

    // výrazy se ukládají, aby bublina převzala nastavení z aplikace a naopak
    fun loadExpressions() {
        val p = Prefs.get(sceneView.context)
        armsOn = p.getBoolean("arms", false); waveOn = p.getBoolean("wave", false)
        happyOn = p.getBoolean("happy", false); blushOn = p.getBoolean("blush", false)
        faceCam = p.getBoolean("faceCam", true)
        wardrobe?.let { w ->
            val o = runCatching { Outfit.valueOf(p.getString("outfit", "AUTO")!!) }.getOrDefault(Outfit.AUTO)
            if (w.outfit != o) w.setOutfit(o)
            val hs = p.getBoolean("headset", false)
            if (w.headset != hs) w.setHeadset(hs)
        }
    }

    fun saveExpressions() {
        Prefs.get(sceneView.context).edit()
            .putBoolean("arms", armsOn).putBoolean("wave", waveOn)
            .putBoolean("happy", happyOn).putBoolean("blush", blushOn)
            .putBoolean("faceCam", faceCam)
            .apply { wardrobe?.let { putString("outfit", it.outfit.name); putBoolean("headset", it.headset) } }
            .apply()
    }
}
