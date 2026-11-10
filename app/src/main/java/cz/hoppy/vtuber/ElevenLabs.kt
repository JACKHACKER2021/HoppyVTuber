package cz.hoppy.vtuber

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * ElevenLabs slouží JEN jako knihovna hlasů: stáhneme seznam a krátkou ukázku hlasu
 * (preview MP3 – zdarma, nespotřebuje kredity). Nic se negeneruje.
 * Z ukázky se spočítá výška a barva hlasu a tvůj hlas se jí přizpůsobí přímo v telefonu.
 */
object ElevenLabs {
    private const val BASE = "https://api.elevenlabs.io/v1"

    data class Voice(val id: String, val name: String, val previewUrl: String)

    fun voices(apiKey: String): List<Voice> {
        val c = URL("$BASE/voices").openConnection() as HttpURLConnection
        c.setRequestProperty("xi-api-key", apiKey)
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        val code = c.responseCode
        if (code !in 200..299) throw IOException(if (code == 401) "Špatný API klíč (401)" else "ElevenLabs chyba $code")
        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("voices")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Voice(o.getString("voice_id"), o.optString("name", "?"), o.optString("preview_url", ""))
        }.filter { it.previewUrl.isNotBlank() }.sortedBy { it.name.lowercase() }
    }

    /** Stáhne ukázku hlasu do cache (veřejný odkaz, bez kreditů). */
    fun downloadPreview(url: String, dir: File, id: String): File {
        val f = File(dir, "preview_$id.mp3")
        if (f.exists() && f.length() > 1000) return f
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        if (c.responseCode !in 200..299) throw IOException("Ukázku nejde stáhnout (${c.responseCode})")
        c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }
}
