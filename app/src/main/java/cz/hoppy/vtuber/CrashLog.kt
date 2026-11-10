package cz.hoppy.vtuber

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Aplikace: zapíná zapisování pádů (platí i pro bublinu, běží ve stejném procesu). */
class HoppyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Hlášení pádů: při pádu zapíše chybu do souboru, při dalším spuštění ji ukáže
 * s tlačítkem Kopírovat (pošli to vývojáři). Na Androidu 11+ zachytí i nativní pády (Filament).
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file(app).writeText("Vlákno: ${t.name}\n${info()}\n\n${Log.getStackTraceString(e)}")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Zapíše chybu, která aplikaci nezabila (pro diagnostiku). */
    fun note(c: Context, where: String, e: Throwable) {
        Log.e("Hoppy", where, e)
        runCatching { if (!file(c).exists()) file(c).writeText("Chyba ($where, aplikace běžela dál)\n${info()}\n\n${Log.getStackTraceString(e)}") }
    }

    private fun info() = "Hoppy ${BuildConfigSafe.version} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    /** Ukáže poslední pád (jednou). */
    fun showIfAny(ctx: Context) {
        val p = Prefs.get(ctx)
        var text = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
        if (text == null && Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = p.getLong("lastExitTs", 0L)
            val ex = runCatching { am.getHistoricalProcessExitReasons(null, 0, 5) }.getOrDefault(emptyList())
                .firstOrNull { (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH ||
                        it.reason == ApplicationExitInfo.REASON_ANR) && it.timestamp > last }
            if (ex != null) {
                p.edit().putLong("lastExitTs", ex.timestamp).apply()
                val trace = runCatching { ex.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    ?.lines()?.take(80)?.joinToString("\n").orEmpty()
                text = "Pád typu ${reason(ex.reason)} (${ex.processName})\n${info()}\n${ex.description ?: ""}\n\n$trace"
            }
        }
        if (text.isNullOrBlank()) return
        file(ctx).delete()
        val t = text
        AlertDialog.Builder(ctx)
            .setTitle("Hoppy minule spadla 😵")
            .setMessage(t.take(3500))
            .setPositiveButton("Kopírovat") { _, _ ->
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hoppy pád", t))
            }
            .setNegativeButton("Zavřít", null)
            .show()
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativní (3D/Filament)"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin"
        ApplicationExitInfo.REASON_ANR -> "zamrznutí (ANR)"
        else -> r.toString()
    }
}

private object BuildConfigSafe {
    val version: String get() = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("?")
}
