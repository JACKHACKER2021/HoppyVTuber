package cz.hoppy.vtuber

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zpracování hlasu přímo v telefonu (žádný internet, žádné kredity):
 * posun výšky + barva (tmavší/jasnější) + brána proti šumu.
 */
class VoiceFx(private val rate: Int) {
    /** posun výšky v půltónech (-12..+12) */
    var semitones = 0f
    /** barva -1 (tmavší) .. +1 (jasnější) */
    var tone = 0f
    /** práh brány (RMS 0..1), pod ním ticho */
    var gate = 0.01f

    private val n = 2048                        // délka zpožďovací linky (~43 ms při 48 kHz)
    private val buf = FloatArray(n)
    private var w = 0
    private var phase = 0f
    private var lp = 0f
    private val lpA = 1f - exp(-2f * PI.toFloat() * 1800f / rate)
    private var gateGain = 0f
    private var env = 0f

    /** Zpracuje blok vzorků na místě. Vrací hlasitost vstupu 0..1 (pro pusu). */
    fun process(x: ShortArray, len: Int): Float {
        val ratio = 2f.pow(semitones / 12f)
        val step = (1f - ratio) / n
        var sum = 0.0
        val envA = 1f - exp(-1f / (0.02f * rate))
        for (i in 0 until len) {
            val s = x[i] / 32768f
            sum += s * s
            env += (abs(s) - env) * envA
            val target = if (env > gate) 1f else 0f
            gateGain += (target - gateGain) * (if (target > gateGain) 0.01f else 0.0005f)

            // --- posun výšky: dvě čtecí hlavy v kruhové lince, prolínané sin²/cos²
            buf[w] = s
            var y = s
            if (abs(semitones) > 0.05f) {
                phase += step
                if (phase >= 1f) phase -= 1f
                if (phase < 0f) phase += 1f
                val p2 = (phase + 0.5f) % 1f
                val g1 = sin(PI.toFloat() * phase).let { it * it }
                y = read(phase * (n - 2) + 1) * g1 + read(p2 * (n - 2) + 1) * (1f - g1)
            }
            w = (w + 1) % n

            // --- barva
            lp += (y - lp) * lpA
            val hp = y - lp
            y = if (tone >= 0) y + tone * hp * 1.5f else y * (1f + tone) + (-tone) * lp

            x[i] = (y * gateGain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return (sqrt(sum / len.coerceAtLeast(1)) * 6).toFloat().coerceIn(0f, 1f)
    }

    private fun read(delay: Float): Float {
        var pos = w - delay
        while (pos < 0) pos += n
        val i = pos.toInt(); val f = pos - i
        return buf[i % n] * (1 - f) + buf[(i + 1) % n] * f
    }

    companion object {
        /** Zmenší vzorkování na ~16 kHz (průměr sousedů), ať je analýza rychlá. */
        private fun down(pcm: FloatArray, rate: Int): Pair<FloatArray, Int> {
            val k = (rate / 16000).coerceAtLeast(1)
            if (k == 1) return pcm to rate
            val out = FloatArray(pcm.size / k)
            for (i in out.indices) { var s = 0f; for (j in 0 until k) s += pcm[i * k + j]; out[i] = s / k }
            return out to rate / k
        }

        /** Základní výška hlasu (Hz) – medián přes znělé úseky, autokorelace 70–450 Hz. */
        fun pitchHz(src: FloatArray, srcRate: Int): Float? {
            val (pcm, rate) = down(src, srcRate)
            val win = rate / 25                       // 40 ms
            val minLag = rate / 450; val maxLag = rate / 70
            val found = ArrayList<Float>()
            var start = 0
            while (start + win + maxLag < pcm.size) {
                var e = 0f
                for (i in start until start + win) e += pcm[i] * pcm[i]
                if (sqrt(e / win) > 0.02f) {
                    var best = 0f; var bestLag = 0
                    for (lag in minLag..maxLag) {
                        var c = 0f
                        for (i in start until start + win) c += pcm[i] * pcm[i + lag]
                        if (c > best) { best = c; bestLag = lag }
                    }
                    if (bestLag > 0 && best / e > 0.5f) found += rate.toFloat() / bestLag
                }
                start += win / 2
            }
            if (found.size < 5) return null
            found.sort()
            return found[found.size / 2]
        }

        /** Jas hlasu = podíl energie nad ~1,8 kHz (0..1). */
        fun brightness(pcm: FloatArray, rate: Int): Float {
            val a = 1f - exp(-2f * PI.toFloat() * 1800f / rate)
            var lp = 0f; var hi = 0.0; var all = 0.0
            for (s in pcm) { lp += (s - lp) * a; val h = s - lp; hi += h * h; all += s * s }
            return if (all <= 0) 0f else (hi / all).toFloat()
        }

        /** Kolik půltónů a jakou barvu nastavit, aby můj hlas zněl jako cílový. */
        fun match(myHz: Float, targetHz: Float, myBright: Float, targetBright: Float): Pair<Float, Float> {
            val semis = (12f * ln(targetHz / myHz) / ln(2f)).coerceIn(-12f, 12f)
            val tone = ((targetBright - myBright) * 6f).coerceIn(-1f, 1f)
            return semis to tone
        }

        fun shortsToFloats(b: ByteArray): FloatArray {
            val out = FloatArray(b.size / 2)
            for (i in out.indices) out[i] = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xFF)).toShort() / 32768f
            return out
        }

        /** Dekóduje MP3 ukázku na mono float PCM. Vrací (vzorky, vzorkovací frekvence). */
        fun decode(file: File, maxSeconds: Int = 20): Pair<FloatArray, Int> {
            val ex = MediaExtractor(); ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val out = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val limit = rate * maxSeconds
            while (!outDone && out.size < limit) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val bb = codec.getOutputBuffer(oi)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val sb = bb.asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until ch) s += sb.get(f * ch + c) / 32768f
                        out += s / ch
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release(); ex.release()
            return out.toFloatArray() to rate
        }
    }
}
