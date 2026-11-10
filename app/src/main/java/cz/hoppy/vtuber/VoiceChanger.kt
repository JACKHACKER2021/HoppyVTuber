package cz.hoppy.vtuber

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Měnič hlasu: poslouchá mikrofon, když domluvíš větu, pošle ji do ElevenLabs
 * a přehraje ji hlasem Hoppy. Pusa postavy se hýbe podle přehrávaného hlasu ([level]).
 *
 * Zpoždění ~1–2 s je dané tím, že se převádí celá věta (ne živě po slabikách).
 * Doporučení: sluchátka, ať mikrofon neslyší přehrávaný hlas.
 */
class VoiceChanger(private val context: Context) {

    companion object {
        private const val RATE = ElevenLabs.SAMPLE_RATE
        private const val FRAME = RATE / 50                 // 20 ms
        private const val PRE_ROLL_FRAMES = 10              // 200 ms před začátkem řeči
        private const val SILENCE_END_MS = 650
        private const val MAX_SEGMENT_MS = 12_000
        private const val MIN_SEGMENT_MS = 350

        fun isConfigured(c: Context): Boolean {
            val p = Prefs.get(c)
            return p.getString("elKey", "").orEmpty().isNotBlank() && p.getString("elVoiceId", "").orEmpty().isNotBlank()
        }

        fun settings(c: Context): ElevenLabs.Settings {
            val p = Prefs.get(c)
            return ElevenLabs.Settings(
                stability = p.getFloat("elStability", 0.5f),
                similarity = p.getFloat("elSimilarity", 0.75f),
                style = p.getFloat("elStyle", 0f),
                removeNoise = p.getBoolean("elNoise", true),
            )
        }

        /** Práh hlasitosti z citlivosti 0..1 (vyšší citlivost = nižší práh). */
        fun threshold(c: Context): Float {
            val sens = Prefs.get(c).getFloat("voiceSens", 0.5f)
            return 0.06f - sens * 0.055f
        }

        fun rms(buf: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
            return sqrt(sum / n.coerceAtLeast(1)).toFloat()
        }

        @SuppressLint("MissingPermission")
        fun newRecorder(): AudioRecord {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_COMMUNICATION = potlačení ozvěny a šumu z telefonu
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8)
            )
        }

        fun newTrack(): AudioTrack {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // jde i do záznamu obrazovky s vnitřním zvukem
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        /** Hlasitost kousku PCM bajtů (0..1) pro pusu. */
        fun levelOf(b: ByteArray, len: Int): Float {
            var sum = 0.0; var n = 0
            var i = 0
            while (i + 1 < len) {
                val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort() / 32768.0
                sum += v * v; n++; i += 2
            }
            return if (n == 0) 0f else (sqrt(sum / n) * 6).toFloat().coerceIn(0f, 1f)
        }

        /** Přehraje PCM a průběžně hlásí hlasitost (blokuje). */
        fun play(track: AudioTrack, b: ByteArray, len: Int, onLevel: (Float) -> Unit) {
            onLevel(levelOf(b, len))
            track.write(b, 0, len)
        }
    }

    /** Hlasitost právě přehrávaného Hoppy hlasu 0..1 – pro pusu. */
    @Volatile var level = 0f; private set
    /** Krátký stav pro UI: "poslouchám", "převádím…", chyba… */
    @Volatile var status = ""; private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running || !isConfigured(context)) return
        running = true
        thread = Thread({ loop() }, "HoppyVoice").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        level = 0f
    }

    private fun loop() {
        val p = Prefs.get(context)
        val key = p.getString("elKey", "").orEmpty()
        val voice = p.getString("elVoiceId", "").orEmpty()
        val s = settings(context)
        val thr = threshold(context)

        val rec = try { newRecorder() } catch (e: Exception) { status = "Mikrofon nejde otevřít"; running = false; return }
        val track = newTrack()
        track.play()
        val frame = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        val seg = ByteArrayOutputStream()
        var speaking = false
        var silentMs = 0
        var segMs = 0

        fun addTo(out: ByteArrayOutputStream, f: ShortArray, n: Int) {
            for (i in 0 until n) { val v = f[i].toInt(); out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        }

        try {
            rec.startRecording()
            status = "poslouchám"
            while (running) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) continue
                val loud = rms(frame, n) > thr
                if (!speaking) {
                    pre.addLast(frame.copyOf(n)); if (pre.size > PRE_ROLL_FRAMES) pre.removeFirst()
                    if (loud) {
                        speaking = true; silentMs = 0; segMs = 0; seg.reset()
                        pre.forEach { addTo(seg, it, it.size) }; pre.clear()
                    }
                    continue
                }
                addTo(seg, frame, n); segMs += 20
                silentMs = if (loud) 0 else silentMs + 20
                if (silentMs < SILENCE_END_MS && segMs < MAX_SEGMENT_MS) continue

                // konec věty -> převést a přehrát (mikrofon mezitím stojí, ať neslyší sám sebe)
                speaking = false
                if (segMs - silentMs < MIN_SEGMENT_MS) continue
                rec.stop()
                status = "převádím…"
                try {
                    ElevenLabs.convert(key, voice, seg.toByteArray(), s) { b, len ->
                        if (running) play(track, b, len) { level = it }
                    }
                    status = "poslouchám"
                } catch (e: Exception) {
                    Log.w("VoiceChanger", "převod selhal", e)
                    status = e.message ?: "chyba převodu"
                }
                level = 0f
                if (running) rec.startRecording()
            }
        } catch (e: Exception) {
            Log.w("VoiceChanger", "smyčka skončila", e)
        } finally {
            runCatching { rec.stop() }; rec.release()
            runCatching { track.stop() }; track.release()
            level = 0f
            status = ""
        }
    }
}
