package cz.hoppy.vtuber

import android.opengl.Matrix
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Efekty na tlačítka. dur < 0 = přepínač (běží, dokud ho znovu nezmáčkneš). */
enum class Fx(val emoji: String, val label: String, val dur: Float) {
    SNEEZE("🤧", "Kýchnutí", 1.7f),
    EXCITED("🤩", "Nadšení", 3f),
    SICK("🤒", "Nemocná", -1f),
    CARROT("🥕", "Mrkvička", 4.5f),
    VOMIT("🤮", "Zvracení", 3.2f),
    LOVED("😍", "Zamilovaná", 4.5f),
    NOSEBLEED("🩸", "Krev z nosu", 3.5f),
    UWU("🥺", "UwU", 3.5f),
}

enum class Eyes { AUTO, CLOSED, UWU, HEARTS, WIDE }

/** Co efekt chce od postavy v tomto snímku (avatar to přičte k trackingu). */
class FxPose {
    var pitch = 0f; var yaw = 0f; var roll = 0f   // stupně, + pitch = kývnutí dopředu
    var lift = 0f                                  // poskok těla (světové jednotky)
    var arms = 0f                                  // 0..1 ruce nahoru
    var eyes = Eyes.AUTO
    var squint = 1f                                // výška otevřených očí
    var mouth = -1f                                // -1 = podle obličeje, 0 = zavřená, 0..1 = otevřená
    var blush = false
    var wag = 0f                                   // extra vrtění ocáskem (-1 líné .. 1 divoké)
    var earDroop = 0f                              // 0..1 svěšené uši
    var kick = 0f                                  // náraz pro pružiny uší (jednorázově)

    fun reset() {
        pitch = 0f; yaw = 0f; roll = 0f; lift = 0f; arms = 0f
        eyes = Eyes.AUTO; squint = 1f; mouth = -1f; blush = false; wag = 0f; earDroop = 0f; kick = 0f
    }
}

/**
 * Efekty s vlastními modely (assets/models/props, generuje tools/make_props.py).
 * Rekvizity buď letí jako částice ve světě (kapky, hvězdičky…), nebo jsou přichycené k hlavě (mrkev, teploměr).
 */
class Effects(private val sceneView: SceneView) {

    private val engine = sceneView.engine
    private val tm = engine.transformManager
    private val rnd = Random(7)

    /** Fond instancí jednoho modelu. Neaktivní instance jsou zmenšené na nulu. */
    private inner class Pool(file: String, count: Int) {
        val roots: IntArray
        private var next = 0
        init {
            val list = runCatching { sceneView.modelLoader.createInstancedModel("models/props/$file.glb", count) }
                .getOrDefault(emptyList())
            roots = IntArray(list.size)
            list.forEachIndexed { i, inst ->
                sceneView.addChildNode(ModelNode(modelInstance = inst, autoAnimate = false))
                roots[i] = inst.root
                hide(inst.root)
            }
        }
        fun take(): Int? { if (roots.isEmpty()) return null; val r = roots[next]; next = (next + 1) % roots.size; return r }
    }

    private class Particle(
        val root: Int, val pos: FloatArray, val vel: FloatArray,
        val gravity: Float, val drag: Float, val life: Float, val size: Float,
        val grow: Float, var spin: Float, val spinV: Float, val axis: Int = 2,
    ) { var age = 0f }

    private val puff = Pool("puff", 18)
    private val star = Pool("star", 14)
    private val blood = Pool("drop_blood", 18)
    private val sweat = Pool("drop_sweat", 3)
    private val vomit = Pool("blob_vomit", 30)
    private val crumb = Pool("crumb", 14)
    private val heartEyes = Pool("heart", 2)
    private val hearts = Pool("heart_pink", 12)
    private val carrot = Pool("carrot", 1)
    private val thermo = Pool("thermometer", 1)

    private val particles = ArrayList<Particle>()

    var active: Fx? = null; private set
    private var startT = 0f
    private var lastSpawn = 0f
    private var lastChomp = 0f
    private var did = false

    fun trigger(fx: Fx, now: Float) {
        if (active == fx && fx.dur < 0) { stop(); return }
        stop()
        active = fx; startT = now; lastSpawn = -1f; lastChomp = 0f; did = false
    }

    fun stop() {
        active = null
        heartEyes.roots.forEach { hide(it) }; carrot.roots.forEach { hide(it) }; thermo.roots.forEach { hide(it) }
    }

    // ------------------------------------------------------------- snímek
    /** [head] = světová matice hlavy, [t] = čas v s, [dt] = krok. Vyplní [pose]. */
    fun update(t: Float, dt: Float, head: FloatArray, pose: FxPose) {
        val fx = active
        if (fx != null) {
            val u = t - startT
            if (fx.dur in 0f..u) stop() else script(fx, u, head, pose)
        }
        stepParticles(dt)
    }

    private fun script(fx: Fx, u: Float, head: FloatArray, p: FxPose) {
        val fade = if (fx.dur > 0) env(u, fx.dur) else min(1f, u * 3)
        when (fx) {
            Fx.SNEEZE -> {
                if (u < 0.85f) {
                    val k = ease(u / 0.85f)
                    p.pitch = -18f * k; p.mouth = 0.45f * k
                    p.eyes = if (u > 0.35f) Eyes.CLOSED else Eyes.AUTO
                    p.squint = 1f - 0.5f * k
                } else {
                    if (!did) {
                        did = true; p.kick = 1f
                        repeat(16) {
                            spawn(puff, head, 0f, 0.085f, 0.17f,
                                vx = rnd.f(-0.25f, 0.25f), vy = rnd.f(-0.15f, 0.25f), vz = rnd.f(0.7f, 1.2f),
                                gravity = 0.3f, drag = 2.2f, life = rnd.f(0.6f, 1f), size = rnd.f(0.6f, 1.2f), grow = 1.6f)
                        }
                    }
                    val k = (u - 0.85f)
                    p.pitch = if (k < 0.15f) lerp(-18f, 22f, ease(k / 0.15f)) else lerp(22f, 0f, ease((k - 0.15f) / 0.7f))
                    p.mouth = if (k < 0.3f) 1f else -1f
                    p.eyes = if (k < 0.5f) Eyes.CLOSED else Eyes.AUTO
                }
            }
            Fx.EXCITED -> {
                p.lift = abs(sin(u * 9f)) * 0.05f * fade
                p.roll = sin(u * 9f) * 6f * fade
                p.arms = fade; p.eyes = Eyes.WIDE; p.mouth = 0.8f; p.wag = 1f
                every(u, 0.11f) {
                    val a = rnd.f(0f, 2f * PI.toFloat())
                    spawn(star, head, cos(a) * 0.24f, rnd.f(0.0f, 0.3f), sin(a) * 0.12f + 0.05f,
                        vx = cos(a) * 0.15f, vy = rnd.f(0.25f, 0.45f), vz = 0.05f,
                        gravity = 0f, drag = 0.5f, life = 0.9f, size = rnd.f(0.7f, 1.2f), grow = 0f, spinV = rnd.f(-6f, 6f))
                }
            }
            Fx.SICK -> {
                p.pitch = 10f * fade; p.roll = sin(u * 1.3f) * 7f * fade
                p.squint = 0.5f; p.mouth = 0f; p.wag = -0.6f; p.earDroop = fade
                attach(thermo, head, local(0.02f, 0.045f, 0.155f, ry = 25f, rx = 18f, s = 1f))
                every(u, 2.2f) {
                    spawn(sweat, head, 0.15f, 0.2f, 0.09f, vx = 0.02f, vy = -0.05f, vz = 0f,
                        gravity = 0.9f, drag = 0.4f, life = 1.4f, size = 1f, grow = 0f)
                }
            }
            Fx.CARROT -> {
                p.eyes = Eyes.CLOSED; p.blush = true; p.wag = 1f
                val eatEnd = fx.dur - 0.9f
                if (u < eatEnd) {
                    val prog = u / eatEnd
                    val c = sin(u * 14f)
                    p.mouth = max(0f, c) * 0.9f
                    if (c < 0 && lastChomp >= 0) repeat(3) {
                        spawn(crumb, head, rnd.f(-0.02f, 0.03f), 0.04f, 0.19f,
                            vx = rnd.f(-0.2f, 0.2f), vy = rnd.f(0f, 0.2f), vz = rnd.f(0.1f, 0.3f),
                            gravity = 1.8f, drag = 0.3f, life = 0.9f, size = rnd.f(0.7f, 1.3f), grow = 0f, spinV = 5f)
                    }
                    lastChomp = c
                    attach(carrot, head, local(0.15f, 0.04f, 0.21f, rz = -90f, sy = 1f - 0.6f * prog))
                } else {
                    carrot.roots.forEach { hide(it) }
                    p.mouth = 0f
                    every(u, 0.2f) { floatHeart(head) }
                }
            }
            Fx.VOMIT -> {
                p.eyes = Eyes.CLOSED; p.earDroop = 0.5f * fade
                when {
                    u < 0.6f -> { p.pitch = 8f; p.roll = sin(u * 40f) * 3f; p.mouth = 0f; p.squint = 0.6f }
                    u < 2.6f -> {
                        p.pitch = 25f; p.mouth = 1f; p.roll = sin(u * 25f) * 2f
                        every(u, 0.03f) {
                            spawn(vomit, head, rnd.f(-0.02f, 0.02f), 0.04f, 0.17f,
                                vx = rnd.f(-0.12f, 0.12f), vy = rnd.f(-0.25f, 0f), vz = rnd.f(0.4f, 0.65f),
                                gravity = 1.8f, drag = 0.2f, life = 1.2f, size = rnd.f(0.7f, 1.4f), grow = 0.3f)
                        }
                    }
                    else -> { p.pitch = lerp(25f, 0f, (u - 2.6f) / 0.6f); p.mouth = 0f }
                }
            }
            Fx.LOVED -> {
                p.eyes = Eyes.HEARTS; p.blush = true; p.wag = 0.6f
                p.roll = sin(u * 2f) * 8f * fade
                val pulse = (1f + 0.18f * sin(u * 9f)) * fade
                attach(heartEyes, head, local(0.064f, 0.142f, 0.172f, s = pulse), 0)
                attach(heartEyes, head, local(-0.064f, 0.142f, 0.172f, s = pulse), 1)
                every(u, 0.28f) { floatHeart(head) }
            }
            Fx.NOSEBLEED -> {
                p.pitch = -6f * fade; p.eyes = Eyes.CLOSED; p.blush = true; p.wag = 0.8f
                every(u, 0.09f) {
                    spawn(blood, head, rnd.f(-0.012f, 0.012f), 0.078f, 0.168f,
                        vx = rnd.f(-0.03f, 0.03f), vy = -0.05f, vz = rnd.f(0.05f, 0.15f),
                        gravity = 1.6f, drag = 0.1f, life = 1.1f, size = rnd.f(0.8f, 1.2f), grow = 0f)
                }
            }
            Fx.UWU -> {
                p.eyes = Eyes.UWU; p.mouth = 0f; p.blush = true; p.wag = 0.5f
                p.roll = sin(u * 3f) * 10f * fade
                if (!did) { did = true; repeat(3) { floatHeart(head) } }
            }
        }
    }

    private fun floatHeart(head: FloatArray) {
        spawn(hearts, head, rnd.f(-0.22f, 0.22f), rnd.f(0.1f, 0.3f), rnd.f(0f, 0.12f),
            vx = rnd.f(-0.05f, 0.05f), vy = rnd.f(0.2f, 0.35f), vz = 0f,
            gravity = 0f, drag = 0.2f, life = 1.4f, size = rnd.f(0.7f, 1.2f), grow = 0.2f, spinV = rnd.f(-1f, 1f))
    }

    private inline fun every(u: Float, period: Float, block: () -> Unit) {
        if (lastSpawn < 0f || u - lastSpawn >= period) { lastSpawn = u; block() }
    }

    // ------------------------------------------------------------- částice
    /** Vypustí částici z bodu [x,y,z] v prostoru hlavy; rychlost je taky v prostoru hlavy (otáčí se s ní). */
    private fun spawn(
        pool: Pool, head: FloatArray, x: Float, y: Float, z: Float,
        vx: Float, vy: Float, vz: Float, gravity: Float, drag: Float, life: Float,
        size: Float, grow: Float, spinV: Float = 0f,
    ) {
        val root = pool.take() ?: return
        particles.removeAll { it.root == root }
        val pw = FloatArray(4); Matrix.multiplyMV(pw, 0, head, 0, floatArrayOf(x, y, z, 1f), 0)
        val vw = FloatArray(4); Matrix.multiplyMV(vw, 0, head, 0, floatArrayOf(vx, vy, vz, 0f), 0)
        val sc = scaleOf(head)
        particles += Particle(root, floatArrayOf(pw[0], pw[1], pw[2]), floatArrayOf(vw[0], vw[1], vw[2]),
            gravity * sc, drag, life, size * sc, grow, rnd.f(0f, 6f), spinV)
    }

    private fun stepParticles(dt: Float) {
        val it = particles.iterator()
        val m = FloatArray(16)
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) { hide(p.root); it.remove(); continue }
            val d = max(0f, 1f - p.drag * dt)
            p.vel[0] *= d; p.vel[1] = p.vel[1] * d - p.gravity * dt; p.vel[2] *= d
            for (k in 0..2) p.pos[k] += p.vel[k] * dt
            p.spin += p.spinV * dt
            val k = p.age / p.life
            val shrink = if (k > 0.75f) (1f - k) / 0.25f else 1f
            val s = p.size * (1f + p.grow * k) * shrink * min(1f, p.age * 12f)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, p.pos[0], p.pos[1], p.pos[2])
            Matrix.rotateM(m, 0, p.spin * 57.3f, 0f, 0f, 1f)
            Matrix.scaleM(m, 0, s, s, s)
            set(p.root, m)
        }
    }

    // ------------------------------------------------------------- rekvizity na hlavě
    private fun attach(pool: Pool, head: FloatArray, localM: FloatArray, index: Int = 0) {
        val root = pool.roots.getOrNull(index) ?: return
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, head, 0, localM, 0)
        set(root, out)
    }

    private fun local(
        x: Float, y: Float, z: Float, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
        s: Float = 1f, sy: Float = 1f,
    ): FloatArray {
        val m = FloatArray(16); Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, x, y, z)
        if (ry != 0f) Matrix.rotateM(m, 0, ry, 0f, 1f, 0f)
        if (rx != 0f) Matrix.rotateM(m, 0, rx, 1f, 0f, 0f)
        if (rz != 0f) Matrix.rotateM(m, 0, rz, 0f, 0f, 1f)
        Matrix.scaleM(m, 0, s, s * sy, s)
        return m
    }

    // ------------------------------------------------------------- pomůcky
    private fun set(root: Int, m: FloatArray) = tm.setTransform(tm.getInstance(root), m)
    private fun hide(root: Int) {
        val m = FloatArray(16); Matrix.setIdentityM(m, 0); Matrix.scaleM(m, 0, 0f, 0f, 0f); set(root, m)
    }
    private fun scaleOf(m: FloatArray) = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)
    private fun env(u: Float, dur: Float) = min(min(1f, u / 0.25f), min(1f, (dur - u) / 0.35f)).coerceAtLeast(0f)
    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)
}
