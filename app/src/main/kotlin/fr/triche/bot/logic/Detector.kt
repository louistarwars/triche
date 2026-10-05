package fr.triche.bot.logic

import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Image RGBA_8888 (comme fournie par ImageReader), lue sans copie. */
class Frame(
    private val buf: ByteBuffer,
    val w: Int,
    val h: Int,
    private val rowStride: Int,
    private val pixelStride: Int,
) {
    /** Rose des piliers et des bandes plafond/sol (237,103,130), tolérant au flou de redimensionnement. */
    fun pink(x: Int, y: Int): Boolean {
        val o = y * rowStride + x * pixelStride
        val r = buf.get(o).toInt() and 0xFF
        val g = buf.get(o + 1).toInt() and 0xFF
        val b = buf.get(o + 2).toInt() and 0xFF
        return r >= 165 && g in 50..155 && b in 90..180 && r - g >= 60
    }

    /** Blanc de la balle (252,243,237). */
    fun white(x: Int, y: Int): Boolean {
        val o = y * rowStride + x * pixelStride
        val r = buf.get(o).toInt() and 0xFF
        val g = buf.get(o + 1).toInt() and 0xFF
        val b = buf.get(o + 2).toInt() and 0xFF
        return r >= 222 && g >= 212 && b >= 205
    }
}

class Pillar(val left: Int, val right: Int, val gapTop: Int, val gapBottom: Int)

class Observation(
    val ballX: Float,
    val ballY: Float,
    val ballHalf: Float,
    val fieldTop: Int,
    val fieldBottom: Int,
    val pillars: List<Pillar>,
)

/**
 * Lit l'écran du jeu : bandes roses plafond/sol, balle blanche, piliers roses.
 * Renvoie null quand l'écran n'est pas une partie en cours (menu, game over...).
 */
class Detector {
    private var fieldTop = -1
    private var fieldBottom = -1

    fun reset() {
        fieldTop = -1
        fieldBottom = -1
    }

    fun detect(f: Frame): Observation? {
        if (!fieldStillValid(f) && !findField(f)) return null
        val ball = findBall(f) ?: return null
        return Observation(ball[0], ball[1], ball[2], fieldTop, fieldBottom, findPillars(f))
    }

    private fun rowPinkFraction(f: Frame, y: Int): Float {
        var n = 0
        var tot = 0
        var x = 0
        while (x < f.w) {
            if (f.pink(x, y)) n++
            tot++
            x += 4
        }
        return n.toFloat() / tot
    }

    private fun fieldStillValid(f: Frame): Boolean =
        fieldTop > 0 && fieldBottom < f.h &&
            rowPinkFraction(f, fieldTop) >= 0.85f && rowPinkFraction(f, fieldBottom) >= 0.85f

    /** Cherche les deux bandes roses pleine largeur : le plafond (fin de bande) et le sol (début de bande). */
    private fun findField(f: Frame): Boolean {
        val bands = ArrayList<IntArray>()
        var start = -1
        for (y in (f.h * 0.10f).toInt() until f.h) {
            val on = rowPinkFraction(f, y) >= 0.85f
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                bands.add(intArrayOf(start, y - 1))
                start = -1
            }
        }
        if (start >= 0) bands.add(intArrayOf(start, f.h - 1))
        if (bands.size < 2) return false
        val top = bands.first()[1]
        val bottom = bands.last()[0]
        if (bottom - top < f.h * 0.3f) return false
        fieldTop = top
        fieldBottom = bottom
        return true
    }

    /** Balle : centroïde des pixels blancs dans la colonne de la balle, hors zone du score. */
    private fun findBall(f: Frame): FloatArray? {
        val x0 = (f.w * 0.28f).toInt()
        val x1 = (f.w * 0.60f).toInt()
        val y0 = fieldTop + (f.h * 0.09f).toInt()
        var n = 0
        var sx = 0L
        var sy = 0L
        var y = y0
        while (y < fieldBottom) {
            var x = x0
            while (x < x1) {
                if (f.white(x, y)) {
                    n++
                    sx += x
                    sy += y
                }
                x += 2
            }
            y += 2
        }
        val area = n * 4f
        val w2 = f.w.toFloat() * f.w
        if (area < 0.0012f * w2 || area > 0.012f * w2) return null
        return floatArrayOf(sx.toFloat() / n, sy.toFloat() / n, sqrt(area) / 2f)
    }

    private fun findPillars(f: Frame): List<Pillar> {
        val yTop = fieldTop + 3
        val yBot = fieldBottom - 3
        val runs = ArrayList<IntArray>()
        runs.addAll(pinkRuns(f, yTop))
        runs.addAll(pinkRuns(f, yBot))
        runs.sortBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else merged.add(r.copyOf())
        }
        val out = ArrayList<Pillar>()
        // Le score (chiffres blancs) est dessiné par-dessus les piliers en haut : on le traverse.
        val zoneBottom = fieldTop + (f.h * 0.105f).toInt()
        for (m in merged) {
            val xc = (m[0] + m[1]) / 2
            var top = fieldTop
            if (f.pink(xc, yTop)) {
                var y = yTop + 1
                top = yTop
                while (y < fieldBottom) {
                    if (f.pink(xc, y)) top = y else if (y > zoneBottom) break
                    y++
                }
            }
            var bottom = fieldBottom
            if (f.pink(xc, yBot)) {
                var y = yBot
                while (y - 1 > fieldTop && f.pink(xc, y - 1)) y--
                bottom = y
            }
            if (bottom - top < f.h * 0.08f) continue
            out.add(Pillar(m[0], m[1], top, bottom))
        }
        return out
    }

    private fun pinkRuns(f: Frame, y: Int): List<IntArray> {
        val minW = maxOf(8, f.w / 40)
        val res = ArrayList<IntArray>()
        var start = -1
        for (x in 0 until f.w) {
            val p = f.pink(x, y)
            if (p && start < 0) start = x
            if ((!p || x == f.w - 1) && start >= 0) {
                val end = if (p) x else x - 1
                if (end - start + 1 >= minW) res.add(intArrayOf(start, end))
                start = -1
            }
        }
        return res
    }
}
