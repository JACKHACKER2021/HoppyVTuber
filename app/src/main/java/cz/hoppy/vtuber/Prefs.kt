package cz.hoppy.vtuber

import android.content.Context
import android.content.SharedPreferences

/** Sdílené nastavení mezi aplikací a plovoucí bublinou (kalibrace, výrazy, poloha bubliny). */
object Prefs {
    // pozadí: levandule, chroma-green (pro OBS), tmavé, bílé
    val BACKGROUNDS = arrayOf(
        floatArrayOf(0.965f, 0.953f, 0.988f), floatArrayOf(0f, 0.69f, 0.25f),
        floatArrayOf(0.08f, 0.09f, 0.12f), floatArrayOf(1f, 1f, 1f)
    )

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("hoppy", Context.MODE_PRIVATE)
}

/** Vlastní obrázek pozadí (uložený v aplikaci, zmenšený na max 1600 px). */
object CustomBg {
    private fun file(c: android.content.Context) = java.io.File(c.filesDir, "custom_bg.jpg")

    fun exists(c: android.content.Context) = file(c).exists()

    fun isOn(c: android.content.Context) = Prefs.get(c).getBoolean("customBg", false) && exists(c)

    fun setOn(c: android.content.Context, on: Boolean) = Prefs.get(c).edit().putBoolean("customBg", on).apply()

    fun save(c: android.content.Context, uri: android.net.Uri): Boolean = runCatching {
        val src = c.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return false
        val k = minOf(1f, 1600f / maxOf(src.width, src.height))
        val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        file(c).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        setOn(c, true)
        true
    }.getOrDefault(false)

    fun load(c: android.content.Context): android.graphics.Bitmap? =
        if (isOn(c)) runCatching { android.graphics.BitmapFactory.decodeFile(file(c).path) }.getOrNull() else null
}
