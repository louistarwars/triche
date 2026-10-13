package fr.triche.dice.logic

import kotlin.math.abs
import kotlin.math.roundToInt

/** Quatre dés immobiles et lisibles : [pips] dans l'ordre haut-gauche, haut-droite, bas-gauche, bas-droite. */
class DiceReading(val pips: IntArray, val boxes: IntArray) {
    val sum get() = pips.sum()

    /** Même tirage, aux mêmes endroits (à quelques pixels près) : les dés ne bougent plus. */
    fun sameAs(o: DiceReading, tol: Int) =
        pips.contentEquals(o.pips) && boxes.indices.all { abs(boxes[it] - o.boxes[it]) <= tol }

    override fun toString() = pips.joinToString("+") + "=" + sum
}

/**
 * Lit les quatre dés (carrés blancs à points noirs, disposés 2×2) : on cherche les quatre grandes taches blanches de
 * même taille, puis on compte dans chacune les taches sombres entièrement entourées de blanc (les points).
 */
object DiceReader {
    private const val WHITE = 200
    private const val DARK = 130

    private fun luma(rgb: Int) = (299 * red(rgb) + 587 * green(rgb) + 114 * blue(rgb)) / 1000

    fun read(fr: Frame): DiceReading? {
        val s = fr.w / 720.0
        val rx0 = (0.10 * fr.w).toInt()
        val rx1 = (0.90 * fr.w).toInt()
        val ry0 = (0.28 * fr.h).toInt()
        val ry1 = (0.58 * fr.h).toInt()
        val step = 2
        val gw = (rx1 - rx0) / step
        val gh = (ry1 - ry0) / step
        val white = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (luma(fr.rgb(rx0 + gx * step, ry0 + gy * step)) > WHITE) white[gy * gw + gx] = true
        }
        // taches blanches (4-connexité sur la grille)
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val dice = ArrayList<IntArray>()
        for (start in white.indices) {
            if (!white[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var n = 0
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                n++
                val x = p % gw
                val y = p / gw
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && white[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < gw - 1 && white[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && white[p - gw] && !seen[p - gw]) { seen[p - gw] = true; stack[sp++] = p - gw }
                if (y < gh - 1 && white[p + gw] && !seen[p + gw]) { seen[p + gw] = true; stack[sp++] = p + gw }
            }
            val w = (maxX - minX + 1) * step
            val h = (maxY - minY + 1) * step
            if (n * step * step < 6500 * s * s) continue           // trop petit : pas un dé (le plus petit dé ≈ 12 000 px)
            if (w < 90 * s || w > 160 * s || h < 90 * s || h > 160 * s || abs(w - h) > 14 * s) return null
            dice.add(intArrayOf(rx0 + minX * step, ry0 + minY * step, rx0 + (maxX + 1) * step - 1, ry0 + (maxY + 1) * step - 1))
        }
        if (dice.size != 4) return null
        dice.sortBy { it[1] }
        val top = dice.subList(0, 2).sortedBy { it[0] }
        val bottom = dice.subList(2, 4).sortedBy { it[0] }
        if (abs(top[0][1] - top[1][1]) > 20 * s || abs(bottom[0][1] - bottom[1][1]) > 20 * s) return null
        val ordered = top + bottom
        val pips = IntArray(4)
        val boxes = IntArray(16)
        for ((i, b) in ordered.withIndex()) {
            val p = countPips(fr, b, s) ?: return null
            pips[i] = p
            for (k in 0 until 4) boxes[i * 4 + k] = b[k]
        }
        return DiceReading(pips, boxes)
    }

    /** Points d'un dé : taches sombres de taille raisonnable qui ne touchent pas le bord du carré. */
    private fun countPips(fr: Frame, b: IntArray, s: Double): Int? {
        val x0 = b[0]; val y0 = b[1]; val w = b[2] - b[0] + 1; val h = b[3] - b[1] + 1
        val dark = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (luma(fr.rgb(x0 + x, y0 + y)) < DARK) dark[y * w + x] = true
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var pips = 0
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var n = 0
            var touches = false
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                n++
                val x = p % w
                val y = p / w
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                if (x > 0 && dark[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && dark[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && dark[p - w] && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && dark[p + w] && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            if (touches) continue                                   // fond sombre dans un coin arrondi
            if (n < 150 * s * s || n > 1500 * s * s) continue
            pips++
        }
        return if (pips in 1..6) pips else null
    }
}

/** Position du pavé numérique : centres des touches (en pixels de l'image). */
class Numpad(val xs: DoubleArray, val ys: DoubleArray) {
    /** Centre de la touche [key] ('0'..'9', 'B' = effacer). */
    fun center(key: Char): Pair<Double, Double> {
        val (r, c) = when (key) {
            in '1'..'9' -> (key - '1') / 3 to (key - '1') % 3
            '0' -> 3 to 1
            else -> 3 to 2
        }
        return xs[c] to ys[r]
    }

    companion object {
        // disposition mesurée sur la vidéo (720×1594) ; repli quand la détection échoue
        fun default(w: Int, h: Int) = Numpad(
            doubleArrayOf(151.0 / 720 * w, 360.0 / 720 * w, 567.0 / 720 * w),
            doubleArrayOf(1126.0 / 1594 * h, 1233.0 / 1594 * h, 1341.0 / 1594 * h, 1448.0 / 1594 * h),
        )

        /**
         * Cherche les touches (rectangles gris-bleu un peu plus clairs que le fond) le long d'une colonne qui évite les
         * chiffres : les trois rangées 1-4-7 donnent les hauteurs, la quatrième se déduit du pas.
         */
        fun detect(fr: Frame): Numpad? {
            val x = (0.14 * fr.w).toInt()
            val runs = ArrayList<IntArray>()
            var start = -1
            val y0 = (0.60 * fr.h).toInt()
            val y1 = (0.97 * fr.h).toInt()
            for (y in y0..y1) {
                val c = if (y < y1) fr.rgb(x, y) else 0
                val key = y < y1 && abs(red(c) - 41) < 10 && abs(green(c) - 42) < 10 && abs(blue(c) - 63) < 12
                if (key && start < 0) start = y
                if (!key && start >= 0) { runs.add(intArrayOf(start, y - 1)); start = -1 }
            }
            val keys = runs.filter { it[1] - it[0] + 1 > 0.04 * fr.h }
            if (keys.size != 3) return null
            val centers = keys.map { (it[0] + it[1]) / 2.0 }
            val p1 = centers[1] - centers[0]
            val p2 = centers[2] - centers[1]
            if (abs(p1 - p2) > 4 || p1 < 0.05 * fr.h) return null
            val d = default(fr.w, fr.h)
            return Numpad(d.xs, doubleArrayOf(centers[0], centers[1], centers[2], centers[2] + (p1 + p2) / 2))
        }
    }
}
