package cz.hoppy.vtuber

import android.content.Context
import android.content.SharedPreferences

/** Sdílené nastavení mezi aplikací a plovoucí bublinou (kalibrace, výrazy, poloha bubliny). */
object Prefs {
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("hoppy", Context.MODE_PRIVATE)
}
