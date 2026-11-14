package cz.hoppy.vtuber

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Potřeby mazlíčka (0 = zle, 100 = super). Klesají s časem, i když je aplikace zavřená.
 */
class PetState(private val c: Context) {
    companion object {
        // o kolik bodů klesne za hodinu (vzhůru)
        const val HUNGER_H = 12f
        const val TOILET_H = 16f
        const val CLEAN_H = 8f
        const val ENERGY_H = 7f
        const val FUN_H = 14f
        const val SLEEP_GAIN_H = 200f     // spánek: plná energie za 30 min
        const val LOW = 25f               // pod tím si Hoppy stěžuje
    }

    var hunger = 80f; var toilet = 80f; var clean = 80f; var energy = 80f; var fun_ = 80f
    var sleeping = false
    private var last = System.currentTimeMillis()

    fun load() {
        val p = Prefs.get(c)
        hunger = p.getFloat("petHunger", 80f); toilet = p.getFloat("petToilet", 80f)
        clean = p.getFloat("petClean", 80f); energy = p.getFloat("petEnergy", 80f)
        fun_ = p.getFloat("petFun", 80f); sleeping = p.getBoolean("petSleep", false)
        last = p.getLong("petLast", System.currentTimeMillis())
    }

    fun save() {
        Prefs.get(c).edit()
            .putFloat("petHunger", hunger).putFloat("petToilet", toilet).putFloat("petClean", clean)
            .putFloat("petEnergy", energy).putFloat("petFun", fun_).putBoolean("petSleep", sleeping)
            .putLong("petLast", last).apply()
    }

    /** Posune potřeby o čas od posledního volání. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val h = ((now - last).coerceAtLeast(0L)) / 3_600_000f
        last = now
        val k = if (sleeping) 0.5f else 1f
        hunger = (hunger - HUNGER_H * h * k).coerceIn(0f, 100f)
        toilet = (toilet - TOILET_H * h * k).coerceIn(0f, 100f)
        clean = (clean - CLEAN_H * h * k).coerceIn(0f, 100f)
        fun_ = (fun_ - FUN_H * h * k).coerceIn(0f, 100f)
        if (sleeping) {
            energy = (energy + SLEEP_GAIN_H * h).coerceIn(0f, 100f)
            if (energy >= 100f) sleeping = false          // vyspaná -> sama se vzbudí
        } else energy = (energy - ENERGY_H * h).coerceIn(0f, 100f)
    }

    /** Za kolik ms bude první potřeba pod hranicí (pro připomínku). */
    fun msUntilNeed(): Long {
        val k = if (sleeping) 0.5f else 1f
        var hrs = minOf((hunger - LOW) / (HUNGER_H * k), (toilet - LOW) / (TOILET_H * k),
            (clean - LOW) / (CLEAN_H * k), (fun_ - LOW) / (FUN_H * k))
        if (!sleeping) hrs = minOf(hrs, (energy - LOW) / ENERGY_H)
        return (hrs.coerceAtLeast(0.25f) * 3_600_000f).toLong()
    }

    /** Nejnaléhavější potřeba pod hranicí, nebo null. */
    fun worst(): String? = listOf("toilet" to toilet, "hunger" to hunger, "energy" to energy, "clean" to clean, "fun" to fun_)
        .filter { it.second < LOW }.minByOrNull { it.second }?.first
}

/** Připomínka: „Hoppy tě potřebuje". */
class PetReminder : BroadcastReceiver() {
    companion object {
        private fun pi(c: Context) = PendingIntent.getBroadcast(
            c, 10, Intent(c, PetReminder::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(c: Context, inMs: Long) {
            runCatching {
                c.getSystemService(AlarmManager::class.java)
                    .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + inMs, pi(c))
            }
        }

        fun cancel(c: Context) {
            runCatching { c.getSystemService(AlarmManager::class.java).cancel(pi(c)) }
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(20) }
        }
    }

    override fun onReceive(c: Context, intent: Intent?) {
        val s = PetState(c).apply { load(); tick() }
        val text = when (s.worst()) {
            "toilet" -> "🚽 Hoppy musí na záchod!"
            "hunger" -> "🥕 Hoppy má hlad!"
            "energy" -> "💤 Hoppy je unavená."
            "clean" -> "🛁 Hoppy se chce umýt."
            "fun" -> "🎾 Hoppy se nudí, pojď si hrát!"
            else -> return
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pet", "Hoppy Mazlíček", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 11, Intent(c, PetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(20, Notification.Builder(c, "pet")
                .setContentTitle("Hoppy tě potřebuje").setContentText(text)
                .setSmallIcon(android.R.drawable.star_on).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
