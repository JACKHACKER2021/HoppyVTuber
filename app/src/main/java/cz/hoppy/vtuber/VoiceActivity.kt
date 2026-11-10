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
 * Nastavení hlasu: API klíč ElevenLabs, výběr hlasu, posuvníky a TEST
 * (nahraj se -> poslechni originál -> poslechni Hoppy hlasem).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Hlas Hoppy"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 32.dp, 20.dp, 32.dp)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#141820"))
            addView(col)
        })

        col.addView(header("🎙️ Hlas Hoppy (ElevenLabs)"))
        col.addView(note("Mluvíš ty – tvoje slova, tvoje intonace – ale ozve se vybraný hlas. " +
                "Žádné MP3 ani text, převádí se tvůj živý hlas (Speech-to-Speech). " +
                "Zpoždění je asi 1–2 s po každé větě. Použij sluchátka."))

        // --- API klíč
        col.addView(label("API klíč (elevenlabs.io → Profile → API Keys)"))
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

        // --- výběr hlasu
        col.addView(label("Hlas"))
        spinner = Spinner(this)
        col.addView(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                voices.getOrNull(pos)?.let {
                    prefs.edit().putString("elVoiceId", it.id).putString("elVoiceName", it.name).apply()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        prefs.getString("elVoiceName", null)?.let { name ->
            voices = listOf(ElevenLabs.Voice(prefs.getString("elVoiceId", "")!!, name))
            spinner.adapter = adapter(voices.map { it.name })
        }

        // --- posuvníky
        col.addView(slider("Stabilita (víc = klidnější, míň = emotivnější)", "elStability", 0.5f))
        col.addView(slider("Podobnost hlasu (víc = věrnější originálu hlasu)", "elSimilarity", 0.75f))
        col.addView(slider("Styl / přehánění (0 = nejrychlejší)", "elStyle", 0f))
        col.addView(slider("Citlivost mikrofonu (kdy začne poslouchat)", "voiceSens", 0.5f))
        col.addView(switch("Odstranit šum v pozadí", "elNoise", true))
        col.addView(switch("✅ Měnič hlasu zapnutý (v aplikaci i v bublině)", "voiceOn", false))

        // --- test
        col.addView(header("🧪 Test nastavení"))
        meter = View(this).apply { setBackgroundColor(Color.parseColor("#5BD07A")) }
        col.addView(LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#2A303C"))
            addView(meter, LinearLayout.LayoutParams(0, 10.dp))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10.dp).apply { topMargin = 8.dp })
        col.addView(button("⏺ Nahrát 5 s") { recordTest() })
        col.addView(button("▶ Přehrát můj originál") { playOriginal() })
        col.addView(button("🐰 Přehrát Hoppy hlasem") { playConverted() })
        status = note("").also { it.setTextColor(Color.parseColor("#FFB74D")) }
        col.addView(status)

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
            val buf = ShortArray(320)
            val thr = VoiceChanger.threshold(this)
            var heard = false
            try {
                rec.startRecording()
                val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val r = VoiceChanger.rms(buf, n)
                    if (r > thr) heard = true
                    showLevel(r / 0.1f)
                    for (i in 0 until n) { val v = buf[i].toInt(); out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                }
            } finally { runCatching { rec.stop() }; rec.release(); showLevel(0f) }
            testPcm = out.toByteArray()
            busy = false
            setStatus(if (heard) "Nahráno ✔ – zkus přehrát" else "Skoro nic jsem neslyšel – zvyš citlivost nebo mluv hlasitěji")
        }.start()
    }

    private fun playOriginal() {
        val pcm = testPcm ?: run { toast("Nejdřív nahraj test"); return }
        if (busy) return
        busy = true
        Thread {
            val t = VoiceChanger.newTrack(); t.play()
            pcm.toList().chunked(3200).forEach { c ->
                val b = c.toByteArray(); VoiceChanger.play(t, b, b.size) { showLevel(it) }
            }
            runCatching { t.stop() }; t.release(); showLevel(0f); busy = false
        }.start()
    }

    private fun playConverted() {
        val pcm = testPcm ?: run { toast("Nejdřív nahraj test"); return }
        if (!VoiceChanger.isConfigured(this)) { toast("Zadej klíč a vyber hlas"); return }
        if (busy) return
        busy = true
        setStatus("Převádím…")
        val key = prefs.getString("elKey", "").orEmpty()
        val voice = prefs.getString("elVoiceId", "").orEmpty()
        val s = VoiceChanger.settings(this)
        Thread {
            val t = VoiceChanger.newTrack(); t.play()
            val started = System.currentTimeMillis()
            var first = 0L
            try {
                ElevenLabs.convert(key, voice, pcm, s) { b, len ->
                    if (first == 0L) first = System.currentTimeMillis() - started
                    VoiceChanger.play(t, b, len) { showLevel(it) }
                }
                setStatus("Hotovo ✔ (první zvuk za ${first} ms)")
            } catch (e: Exception) { setStatus(e.message ?: "Chyba") }
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

    private fun slider(title: String, key: String, def: Float): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = label("")
        fun upd(v: Float) { lbl.text = "$title: ${(v * 100).toInt()} %" }
        val v0 = prefs.getFloat(key, def); upd(v0)
        val sb = SeekBar(this).apply {
            max = 100; progress = (v0 * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    upd(p / 100f); prefs.edit().putFloat(key, p / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(lbl); box.addView(sb)
        return box
    }

    private fun switch(title: String, key: String, def: Boolean) = SwitchCompat(this).apply {
        text = title; setTextColor(Color.WHITE); textSize = 15f
        isChecked = prefs.getBoolean(key, def)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 12.dp, 0, 12.dp)
        setOnCheckedChangeListener { _, c ->
            if (key == "voiceOn" && c && !VoiceChanger.isConfigured(this@VoiceActivity)) {
                isChecked = false; toast("Nejdřív zadej klíč a vyber hlas"); return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(key, c).apply()
        }
    }
}
