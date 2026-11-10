package cz.hoppy.vtuber

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Nastavení hlasu: výběr hlasu z ElevenLabs (jen ukázka), automatické napodobení
 * (výška + barva), ruční doladění a test (originál vs. Hoppy hlas).
 */
class VoiceActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs.get(this) }
    private var voices: List<ElevenLabs.Voice> = emptyList()
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var meter: View
    private var testPcm: ByteArray? = null
    @Volatile private var busy = false

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) toast("Bez mikrofonu test nepůjde")
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private val sliders = HashMap<String, Pair<SeekBar, (Float) -> Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 32.dp, 20.dp, 32.dp)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#141820"))
            addView(col)
        })

        col.addView(header("🎙️ Hlas Hoppy"))
        col.addView(note("Mluvíš ty a slyšíš se hned (bez zpoždění, bez internetu, bez kreditů) – jen jiným hlasem. " +
                "Z ElevenLabs se vezme jen krátká ukázka vybraného hlasu a aplikace podle ní nastaví výšku a barvu. " +
                "Používej sluchátka, jinak bude pískat zpětná vazba."))

        // --- ElevenLabs jako knihovna hlasů
        col.addView(header("1️⃣ Vyber hlas z ElevenLabs"))
        col.addView(label("API klíč (jen pro seznam hlasů – nic se negeneruje)"))
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("elKey", ""))
            hint = "sk_…"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.addView(keyField)
        col.addView(button("💾 Uložit klíč a načíst hlasy") {
            prefs.edit().putString("elKey", keyField.text.toString().trim()).apply()
            loadVoices()
        })
        spinner = Spinner(this)
        col.addView(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                voices.getOrNull(pos)?.let {
                    prefs.edit().putString("elVoiceId", it.id).putString("elVoiceName", it.name)
                        .putString("elPreview", it.previewUrl).apply()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        prefs.getString("elVoiceName", null)?.let { name ->
            voices = listOf(ElevenLabs.Voice(prefs.getString("elVoiceId", "")!!, name, prefs.getString("elPreview", "")!!))
            spinner.adapter = adapter(voices.map { it.name })
        }
        col.addView(button("▶ Poslechnout ukázku hlasu") { playPreview() })

        // --- nahrání sebe a napodobení
        col.addView(header("2️⃣ Nahraj sebe a napodob hlas"))
        meter = View(this).apply { setBackgroundColor(Color.parseColor("#5BD07A")) }
        col.addView(LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#2A303C"))
            addView(meter, LinearLayout.LayoutParams(0, 10.dp))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10.dp).apply { topMargin = 8.dp })
        col.addView(button("⏺ Nahrát 5 s (mluv normálně)") { recordTest() })
        col.addView(button("🎯 Napodobit vybraný hlas") { matchVoice() })
        status = note("").also { it.setTextColor(Color.parseColor("#FFB74D")) }
        col.addView(status)

        // --- ruční doladění
        col.addView(header("3️⃣ Dolaď a vyzkoušej"))
        col.addView(slider("Výška hlasu", "voicePitch", 0f, -12f, 12f) { "%+.1f půltónu".format(it) })
        col.addView(slider("Barva (tmavší ↔ jasnější)", "voiceTone", 0f, -1f, 1f) { "%+.0f %%".format(it * 100) })
        col.addView(slider("Citlivost mikrofonu", "voiceSens", 0.6f, 0f, 1f) { "${(it * 100).toInt()} %" })
        col.addView(button("▶ Přehrát můj originál") { playTest(false) })
        col.addView(button("🐰 Přehrát s hlasem Hoppy") { playTest(true) })
        col.addView(switch("✅ Měnič hlasu zapnutý (v aplikaci i v bublině)", "voiceOn", false))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ------------------------------------------------------------------ akce
    private fun loadVoices() {
        val key = prefs.getString("elKey", "").orEmpty()
        if (key.isBlank()) { toast("Zadej API klíč"); return }
        setStatus("Načítám hlasy…")
        Thread {
            try {
                val list = ElevenLabs.voices(key)
                runOnUiThread {
                    voices = list
                    spinner.adapter = adapter(list.map { it.name })
                    val sel = list.indexOfFirst { it.id == prefs.getString("elVoiceId", "") }
                    if (sel >= 0) spinner.setSelection(sel)
                    setStatus("Načteno ${list.size} hlasů ✔")
                }
            } catch (e: Exception) { setStatus(e.message ?: "Chyba") }
        }.start()
    }

    private fun previewFile(): java.io.File {
        val url = prefs.getString("elPreview", "").orEmpty()
        val id = prefs.getString("elVoiceId", "").orEmpty()
        if (url.isBlank()) throw java.io.IOException("Nejdřív načti a vyber hlas")
        return ElevenLabs.downloadPreview(url, cacheDir, id)
    }

    private fun playPreview() {
        setStatus("Stahuji ukázku…")
        Thread {
            try {
                val f = previewFile()
                runOnUiThread {
                    android.media.MediaPlayer().apply {
                        setDataSource(f.path); setOnCompletionListener { it.release() }
                        prepare(); start()
                    }
                    setStatus("▶ ${prefs.getString("elVoiceName", "")}")
                }
            } catch (e: Exception) { setStatus(e.message ?: "Chyba") }
        }.start()
    }

    private fun recordTest() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        busy = true
        setStatus("⏺ Mluv… (5 s)")
        Thread {
            val rec = VoiceChanger.newRecorder()
            val out = ByteArrayOutputStream()
            val buf = ShortArray(480)
            var heard = false
            val thr = VoiceChanger.threshold(this)
            try {
                rec.startRecording()
                val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val r = VoiceChanger.rms(buf, n)
                    if (r > thr * 2) heard = true
                    showLevel(r / 0.1f)
                    for (i in 0 until n) { val v = buf[i].toInt(); out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                }
            } finally { runCatching { rec.stop() }; rec.release(); showLevel(0f) }
            testPcm = out.toByteArray()
            busy = false
            setStatus(if (heard) "Nahráno ✔ – teď 🎯 Napodobit nebo ▶ Přehrát" else "Skoro nic jsem neslyšel – mluv hlasitěji")
        }.start()
    }

    private fun matchVoice() {
        val pcm = testPcm ?: run { toast("Nejdřív se nahraj (5 s)"); return }
        setStatus("Analyzuji tvůj hlas a ukázku…")
        Thread {
            try {
                val mine = VoiceFx.shortsToFloats(pcm)
                val myHz = VoiceFx.pitchHz(mine, VoiceChanger.RATE)
                    ?: throw Exception("V nahrávce není dost hlasu – nahraj se znovu")
                val (target, tRate) = VoiceFx.decode(previewFile())
                val tHz = VoiceFx.pitchHz(target, tRate) ?: throw Exception("Z ukázky nejde zjistit výšku hlasu")
                val (semis, tone) = VoiceFx.match(myHz, tHz,
                    VoiceFx.brightness(mine, VoiceChanger.RATE), VoiceFx.brightness(target, tRate))
                prefs.edit().putFloat("voicePitch", semis).putFloat("voiceTone", tone).apply()
                runOnUiThread { refreshSlider("voicePitch", semis); refreshSlider("voiceTone", tone) }
                setStatus("Tvůj hlas ${myHz.toInt()} Hz → ${prefs.getString("elVoiceName", "")} ${tHz.toInt()} Hz\n" +
                        "Nastaveno: %+.1f půltónu, barva %+.0f %%. Zkus 🐰 Přehrát.".format(semis, tone * 100))
            } catch (e: Exception) { setStatus(e.message ?: "Chyba") }
        }.start()
    }

    private fun playTest(withFx: Boolean) {
        val pcm = testPcm ?: run { toast("Nejdřív se nahraj (5 s)"); return }
        if (busy) return
        busy = true
        Thread {
            val t = VoiceChanger.newTrack(lowLatency = false); t.play()
            val fx = VoiceChanger.fx(this)
            val shorts = ShortArray(pcm.size / 2) {
                ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort()
            }
            var i = 0
            val block = ShortArray(480)
            while (i < shorts.size) {
                val n = minOf(480, shorts.size - i)
                System.arraycopy(shorts, i, block, 0, n)
                val l = if (withFx) fx.process(block, n) else VoiceChanger.rms(block, n) * 6
                showLevel(l)
                t.write(block, 0, n)
                i += n
            }
            runCatching { t.stop() }; t.release(); showLevel(0f); busy = false
        }.start()
    }

    // ------------------------------------------------------------------ UI pomůcky
    private fun setStatus(s: String) = runOnUiThread { status.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun showLevel(l: Float) = runOnUiThread {
        val parentW = (meter.parent as View).width
        meter.layoutParams = meter.layoutParams.apply { width = (parentW * l.coerceIn(0f, 1f)).toInt() }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.WHITE)
        setPadding(0, 24.dp, 0, 8.dp)
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#B8C0CC"))
        setPadding(0, 16.dp, 0, 4.dp)
    }
    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#8A93A3"))
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 8.dp }
    }

    private fun slider(title: String, key: String, def: Float, min: Float, max: Float, fmt: (Float) -> String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = label("")
        val toProg = { v: Float -> (((v - min) / (max - min)) * 100).toInt() }
        fun upd(v: Float) { lbl.text = "$title: ${fmt(v)}" }
        val v0 = prefs.getFloat(key, def); upd(v0)
        val sb = SeekBar(this).apply {
            this.max = 100; progress = toProg(v0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    upd(v); if (fromUser) prefs.edit().putFloat(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliders[key] = sb to toProg
        box.addView(lbl); box.addView(sb)
        return box
    }

    private fun refreshSlider(key: String, v: Float) {
        sliders[key]?.let { (sb, toProg) -> sb.progress = toProg(v) }
    }

    private fun switch(title: String, key: String, def: Boolean) = SwitchCompat(this).apply {
        text = title; setTextColor(Color.WHITE); textSize = 15f
        isChecked = prefs.getBoolean(key, def)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 12.dp, 0, 12.dp)
        setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(key, c).apply()
        }
    }
}
