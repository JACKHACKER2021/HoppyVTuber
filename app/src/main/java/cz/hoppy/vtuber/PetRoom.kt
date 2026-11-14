package cz.hoppy.vtuber

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/**
 * Pokojíček mazlíčka nakreslený v kódu (žádné obrázky navíc): tapeta, okno, police s mrkví,
 * dřevěná podlaha a koberec, kde Hoppy stojí. Den = slunce a mraky, noc = měsíc, hvězdy a lampička.
 */
object PetRoom {
    private const val W = 1080
    private const val H = 1920
    private const val FLOOR = 0.70f          // kde začíná podlaha (část výšky)

    fun draw(night: Boolean): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fy = H * FLOOR

        // --- stěna + tapeta
        p.shader = LinearGradient(0f, 0f, 0f, fy,
            if (night) Color.rgb(52, 58, 92) else Color.rgb(255, 236, 214),
            if (night) Color.rgb(38, 42, 72) else Color.rgb(250, 214, 190), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), fy, p); p.shader = null
        p.color = if (night) Color.argb(40, 255, 255, 255) else Color.argb(60, 255, 255, 255)
        var x = 0f
        while (x < W) { c.drawRect(x, 0f, x + 40f, fy, p); x += 120f }
        p.color = if (night) Color.argb(35, 160, 220, 140) else Color.argb(70, 120, 200, 110)
        val rnd = Random(4)
        for (i in 0 until 26) c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * fy * 0.95f, 9f, p)

        // --- okno (vlevo nahoře)
        val win = RectF(90f, 260f, 470f, 760f)
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom,
            if (night) Color.rgb(14, 20, 52) else Color.rgb(120, 200, 255),
            if (night) Color.rgb(40, 50, 100) else Color.rgb(200, 236, 255), Shader.TileMode.CLAMP)
        c.drawRect(win, p); p.shader = null
        if (night) {
            p.color = Color.WHITE
            val r2 = Random(9)
            for (i in 0 until 22) c.drawCircle(win.left + r2.nextFloat() * win.width(), win.top + r2.nextFloat() * win.height() * 0.8f, 2f + r2.nextFloat() * 3f, p)
            p.color = Color.rgb(255, 246, 200); c.drawCircle(win.right - 110f, win.top + 120f, 55f, p)
            p.color = Color.rgb(14, 20, 52); c.drawCircle(win.right - 88f, win.top + 105f, 50f, p)   // srpek
        } else {
            p.color = Color.rgb(255, 222, 90); c.drawCircle(win.right - 100f, win.top + 110f, 55f, p)
            p.color = Color.WHITE
            for ((cx, cy) in listOf(170f to 470f, 330f to 600f)) {
                c.drawCircle(cx, cy, 38f, p); c.drawCircle(cx + 40f, cy - 18f, 46f, p); c.drawCircle(cx + 85f, cy, 36f, p)
            }
        }
        p.color = if (night) Color.rgb(200, 190, 210) else Color.WHITE
        p.style = Paint.Style.STROKE; p.strokeWidth = 22f; c.drawRect(win, p)
        p.strokeWidth = 12f
        c.drawLine(win.centerX(), win.top, win.centerX(), win.bottom, p)
        c.drawLine(win.left, win.centerY(), win.right, win.centerY(), p)
        p.style = Paint.Style.FILL
        // záclony
        p.color = if (night) Color.rgb(120, 70, 110) else Color.rgb(255, 150, 170)
        for (side in listOf(win.left - 60f, win.right - 10f)) {
            val path = Path().apply {
                moveTo(side, win.top - 50f); lineTo(side + 70f, win.top - 50f)
                quadTo(side + 50f, win.centerY(), side + 80f, win.bottom + 60f); lineTo(side, win.bottom + 60f); close()
            }
            c.drawPath(path, p)
        }
        p.color = Color.rgb(150, 100, 70); c.drawRect(win.left - 90f, win.top - 70f, win.right + 90f, win.top - 48f, p)

        // --- police (vpravo) s mrkví, květinou a knížkami
        p.color = Color.rgb(170, 115, 75); c.drawRect(690f, 560f, 1010f, 585f, p)
        val books = listOf(Color.rgb(90, 160, 230), Color.rgb(240, 120, 110), Color.rgb(250, 200, 80))
        books.forEachIndexed { i, col -> p.color = col; c.drawRect(710f + i * 38f, 440f + i * 12f, 742f + i * 38f, 560f, p) }
        p.color = Color.rgb(200, 110, 70); c.drawRect(880f, 500f, 960f, 560f, p)                   // květináč
        p.color = Color.rgb(80, 170, 80)
        c.drawCircle(905f, 470f, 30f, p); c.drawCircle(940f, 455f, 30f, p); c.drawCircle(922f, 430f, 28f, p)
        // obrázek mrkve na zdi
        val pic = RectF(720f, 220f, 960f, 420f)
        p.color = Color.rgb(160, 110, 70); c.drawRoundRect(pic, 10f, 10f, p)
        p.color = Color.rgb(255, 250, 240); c.drawRect(pic.left + 16f, pic.top + 16f, pic.right - 16f, pic.bottom - 16f, p)
        p.color = Color.rgb(255, 140, 30)
        c.drawPath(Path().apply { moveTo(800f, 280f); lineTo(880f, 280f); lineTo(840f, 395f); close() }, p)
        p.color = Color.rgb(70, 170, 60)
        c.drawPath(Path().apply { moveTo(825f, 285f); lineTo(810f, 240f); lineTo(840f, 280f); lineTo(870f, 240f); lineTo(855f, 285f); close() }, p)

        // --- lampička (v noci svítí)
        if (night) {
            p.shader = RadialGradient(930f, 900f, 420f, Color.argb(110, 255, 220, 140), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(930f, 900f, 420f, p); p.shader = null
        }
        p.color = Color.rgb(120, 90, 70); c.drawRect(920f, 920f, 940f, fy, p)
        p.color = if (night) Color.rgb(255, 225, 150) else Color.rgb(255, 205, 120)
        c.drawPath(Path().apply { moveTo(870f, 920f); lineTo(990f, 920f); lineTo(960f, 840f); lineTo(900f, 840f); close() }, p)

        // --- podlaha (prkna) + lišta
        p.color = if (night) Color.rgb(96, 70, 60) else Color.rgb(196, 140, 96)
        c.drawRect(0f, fy, W.toFloat(), H.toFloat(), p)
        p.color = if (night) Color.rgb(80, 58, 50) else Color.rgb(170, 118, 80)
        var y = fy + 70f
        while (y < H) { c.drawRect(0f, y, W.toFloat(), y + 6f, p); y += 90f }
        p.color = if (night) Color.rgb(200, 190, 210) else Color.WHITE
        c.drawRect(0f, fy - 18f, W.toFloat(), fy + 6f, p)

        // --- koberec (Hoppy na něm stojí)
        p.color = if (night) Color.rgb(90, 140, 110) else Color.rgb(140, 210, 150)
        c.drawOval(RectF(170f, fy + 120f, 910f, fy + 400f), p)
        p.color = if (night) Color.rgb(120, 170, 130) else Color.rgb(190, 235, 190)
        c.drawOval(RectF(260f, fy + 165f, 820f, fy + 355f), p)

        // --- miska s mrkvemi na zemi
        p.color = Color.rgb(90, 150, 230); c.drawOval(RectF(60f, fy + 380f, 260f, fy + 460f), p)
        p.color = Color.rgb(255, 140, 30)
        c.drawOval(RectF(95f, fy + 360f, 170f, fy + 400f), p); c.drawOval(RectF(150f, fy + 355f, 225f, fy + 395f), p)
        return b
    }
}
