package cz.hoppy.vtuber

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Měnič hlasu v reálném čase, přímo v telefonu: mikrofon -> [VoiceFx] -> sluchátka/reproduktor.
 * Nic se neposílá na internet a nic se negeneruje – mluvíš ty, jen jiným hlasem.
 * Nastavení (výška, barva) se dá automaticky odvodit z ukázky hlasu ElevenLabs.
 * Pusa postavy se hýbe podle hlasitosti ([level]).
 */
class VoiceChanger(private val context: Context) {

    companion object {
        const val RATE = 48000
        private const val BLOCK = 480                       // 10 ms

        /** Měnič nepotřebuje klíč – stačí nastavené parametry (výchozí = beze změny). */
        @Suppress("UNUSED_PARAMETER")
        fun isConfigured(c: Context) = true

        fun fx(c: Context): VoiceFx {
            val p = Prefs.get(c)
            return VoiceFx(RATE).apply {
                semitones = p.getFloat("voicePitch", 0f)
                tone = p.getFloat("voiceTone", 0f)
                gate = threshold(c)
            }
        }

        /** Práh brány z citlivosti 0..1 (vyšší citlivost = nižší práh). */
        fun threshold(c: Context): Float = 0.03f - Prefs.get(c).getFloat("voiceSens", 0.6f) * 0.028f

        fun rms(buf: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
            return sqrt(sum / n.coerceAtLeast(1)).toFloat()
        }

        @SuppressLint("MissingPermission")
        fun newRecorder(): AudioRecord {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, BLOCK * 8))
        }

        fun newTrack(lowLatency: Boolean = true): AudioTrack {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // jde i do záznamu obrazovky s vnitřním zvukem
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(if (lowLatency) min else maxOf(min, RATE))
                .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    /** Hlasitost hlasu 0..1 – pro pusu. */
    @Volatile var level = 0f; private set
    @Volatile var status = ""; private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "HoppyVoice").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        thread = null
        level = 0f
    }

    private fun loop() {
        val fx = fx(context)
        val rec = try { newRecorder() } catch (e: Exception) { status = "Mikrofon nejde otevřít"; running = false; return }
        val track = try { newTrack() } catch (e: Exception) {
            rec.release(); status = "Přehrávání nejde spustit"; running = false; return
        }
        val buf = ShortArray(BLOCK)
        try {
            rec.startRecording(); track.play()
            status = "poslouchám"
            while (running) {
                val n = rec.read(buf, 0, BLOCK)
                if (n <= 0) continue
                val l = fx.process(buf, n)
                level = if (l > fx.gate * 6) l else 0f
                track.write(buf, 0, n)
            }
        } catch (e: Exception) {
            Log.w("VoiceChanger", "smyčka skončila", e)
        } finally {
            runCatching { rec.stop() }; rec.release()
            runCatching { track.stop() }; track.release()
            level = 0f; status = ""
        }
    }
}
