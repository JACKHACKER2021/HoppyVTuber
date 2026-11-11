package cz.hoppy.vtuber

import android.content.Context
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import java.util.Calendar

/** Oblečky (assets/models/outfits). AUTO = vybere se samo podle data. */
enum class Outfit(val file: String?, val emoji: String, val label: String) {
    NONE(null, "👕", "Bez oblečku"),
    AUTO(null, "📅", "Podle data"),
    CHRISTMAS("ChristmasOutfit", "🎄", "Vánoce"),
    NEWYEAR("NewYearOutfit", "🥂", "Silvestr"),
    HALLOWEEN("HalloweenOutfit", "🎃", "Halloween"),
    BIRTHDAY("BirthdayOutfit", "🎂", "Narozeniny"),
}

/**
 * Šatník: oblečky a sluchátka jsou samostatné modely se stejnou kostrou jako Hoppy.
 * Každý snímek se do nich zkopíruje póza Hoppy (kosti), takže se hýbou s ní – i s efekty a animacemi.
 */
class Wardrobe(
    private val sceneView: SceneView,
    private val hoppy: ModelNode,
    private val hoppyBones: Map<String, Int>,
) {
    companion object {
        const val HEADSET = "GamingHeadset"
        val BONES = listOf(
            "Root", "LowerTorso", "UpperTorso", "Head", "LeftEar", "RightEar", "Tuft",
            "LeftCheekFluff", "RightCheekFluff", "LeftUpperArm", "LeftLowerArm", "LeftHand",
            "RightUpperArm", "RightLowerArm", "RightHand", "LeftUpperLeg", "LeftLowerLeg", "LeftFoot",
            "RightUpperLeg", "RightLowerLeg", "RightFoot", "Tail",
        )

        /** Svátek podle data (a narozenin uložených v nastavení). */
        fun forDate(c: Context, cal: Calendar = Calendar.getInstance()): Outfit {
            val m = cal.get(Calendar.MONTH) + 1; val d = cal.get(Calendar.DAY_OF_MONTH)
            val p = Prefs.get(c)
            val bm = p.getInt("birthMonth", 0); val bd = p.getInt("birthDay", 0)
            return when {
                bm == m && bd == d -> Outfit.BIRTHDAY
                (m == 12 && d == 31) || (m == 1 && d == 1) -> Outfit.NEWYEAR
                m == 12 && d in 1..26 -> Outfit.CHRISTMAS
                m == 10 && d >= 24 || (m == 11 && d == 1) -> Outfit.HALLOWEEN
                else -> Outfit.NONE
            }
        }
    }

    private val tm = sceneView.engine.transformManager

    private class Piece(val node: ModelNode, val bones: IntArray /* stejné pořadí jako BONES, 0 = chybí */)
    private val cache = HashMap<String, Piece>()
    private val worn = LinkedHashMap<String, Piece>()

    var outfit = Outfit.NONE; private set
    var headset = false; private set

    /** Skutečně oblečený oblek (AUTO přeložené na dnešní svátek). */
    fun effective(c: Context) = if (outfit == Outfit.AUTO) forDate(c) else outfit

    fun setOutfit(o: Outfit) { outfit = o; refresh() }
    fun setHeadset(on: Boolean) { headset = on; refresh() }

    private fun refresh() {
        val want = LinkedHashSet<String>()
        effective(sceneView.context).file?.let { want += it }
        if (headset) want += HEADSET
        // sundat, co už nechceme
        worn.keys.filter { it !in want }.forEach { k -> worn.remove(k)?.let { sceneView.removeChildNode(it.node) } }
        // obléct nové
        for (f in want) if (f !in worn) load(f)?.let { p -> sceneView.addChildNode(p.node); worn[f] = p }
    }

    private fun load(file: String): Piece? = cache[file] ?: runCatching {
        val inst = sceneView.modelLoader.createModelInstance(ModelBuffers.get(sceneView.context, "models/outfits/$file.glb"))
        val node = ModelNode(modelInstance = inst, autoAnimate = false)
        val ids = IntArray(BONES.size) { inst.asset.getFirstEntityByName(BONES[it]) }
        Piece(node, ids).also { cache[file] = it }
    }.onFailure { CrashLog.note(sceneView.context, "oblek $file", it) }.getOrNull()

    private val m = FloatArray(16)

    /** Volat každý snímek po nastavení kostí Hoppy. */
    fun sync() {
        if (worn.isEmpty()) return
        for (p in worn.values) {
            p.node.transform = hoppy.transform            // stejná velikost a poloha jako Hoppy
            for (i in BONES.indices) {
                val src = hoppyBones[BONES[i]] ?: continue
                val dst = p.bones[i]; if (dst == 0) continue
                tm.getTransform(tm.getInstance(src), m)
                tm.setTransform(tm.getInstance(dst), m)
            }
            p.node.modelInstance.animator.updateBoneMatrices()
        }
    }
}
