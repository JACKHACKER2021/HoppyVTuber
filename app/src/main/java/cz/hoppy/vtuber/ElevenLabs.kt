package cz.hoppy.vtuber

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Minimální klient ElevenLabs API.
 * Speech-to-Speech = tvoje slova, tvoje intonace, ale hlas vybrané postavy (ne text z MP3).
 */
object ElevenLabs {
    private const val BASE = "https://api.elevenlabs.io/v1"
    const val MODEL = "eleven_multilingual_sts_v2"   // umí i češtinu
    const val SAMPLE_RATE = 16000

    data class Voice(val id: String, val name: String)

    data class Settings(
        val stability: Float = 0.5f,
        val similarity: Float = 0.75f,
        val style: Float = 0f,
        val removeNoise: Boolean = true,
    )

    /** Seznam hlasů z tvého účtu (včetně klonovaných a z Voice Library). */
    fun voices(apiKey: String): List<Voice> {
        val c = URL("$BASE/voices").openConnection() as HttpURLConnection
        c.setRequestProperty("xi-api-key", apiKey)
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        val body = readOrThrow(c)
        val arr = JSONObject(body).getJSONArray("voices")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Voice(o.getString("voice_id"), o.optString("name", "?"))
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Převede nahraný úsek (PCM 16 kHz mono 16 bit) do hlasu [voiceId].
     * Výsledek (taky PCM 16 kHz) chodí po kouscích do [onChunk], ať se může hned přehrávat.
     */
    fun convert(
        apiKey: String, voiceId: String, pcm: ByteArray, s: Settings,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        val boundary = "----hoppy" + UUID.randomUUID().toString().replace("-", "")
        val c = URL("$BASE/speech-to-speech/$voiceId/stream?output_format=pcm_16000")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("xi-api-key", apiKey)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        val settings = JSONObject()
            .put("stability", s.stability.toDouble())
            .put("similarity_boost", s.similarity.toDouble())
            .put("style", s.style.toDouble())
            .put("use_speaker_boost", true)
            .toString()

        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model_id", MODEL)
        field("voice_settings", settings)
        field("remove_background_noise", s.removeNoise.toString())
        field("file_format", "pcm_s16le_16")
        out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.pcm\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
        out.write(pcm)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val bytes = out.toByteArray()
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }

        if (c.responseCode !in 200..299) readOrThrow(c)
        c.inputStream.use { input ->
            val buf = ByteArray(3200)
            var carry = -1                      // PCM po 2 bajtech – lichý bajt přeneseme
            while (true) {
                val n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                if (n < 0) break
                var len = n
                if (carry >= 0) { buf[0] = carry.toByte(); len += 1; carry = -1 }
                if (len % 2 == 1) { carry = buf[len - 1].toInt() and 0xFF; len -= 1 }
                if (len > 0) onChunk(buf, len)
            }
        }
    }

    private fun readOrThrow(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
        val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val msg = runCatching {
            val d = JSONObject(err).opt("detail")
            if (d is JSONObject) d.optString("message", err) else d?.toString() ?: err
        }.getOrDefault(err)
        throw IOException(when (code) {
            401 -> "Špatný API klíč (401)"
            402, 429 -> "Došly kredity nebo limit ElevenLabs ($code): $msg"
            else -> "ElevenLabs chyba $code: $msg"
        })
    }
}
