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
