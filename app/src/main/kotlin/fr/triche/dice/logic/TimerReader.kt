package fr.triche.dice.logic

import kotlin.math.roundToInt

/**
 * Lit le chronomètre du jeu (gris, « 6.4 », au-dessus des dés) : retourne sa valeur en dixièmes de seconde, ou null si
 * l'image ne montre pas un chronomètre lisible. Chaque chiffre est comparé aux dix signatures de [TimerFont].
 */
object TimerReader {
    private const val REF_W = 720.0
    private const val REF_H = 1594.0
    private const val ROW0 = 352.0
    private const val ROW1 = 400.0
    private const val LIT = 110          // luminosité minimale d'un pixel de chiffre (le fond est ~20)
    private const val MAX_MSE = 0.03f

    private fun luma(rgb: Int) = (299 * red(rgb) + 587 * green(rgb) + 114 * blue(rgb)) / 1000

    /** Signature d'un glyphe : 20 lignes × 14 colonnes de luminosité moyenne, normalisées sur 0..1. */
    private fun features(img: Array<IntArray>, h: Int, x0: Int, x1: Int): FloatArray {
        val w = x1 - x0 + 1
        val f = FloatArray(280)
        for (r in 0 until 20) {
            val ya = r * h / 20
            val yb = maxOf((r + 1) * h / 20, ya + 1)
            for (c in 0 until 14) {
                val xa = x0 + c * w / 14
                val xb = maxOf(x0 + (c + 1) * w / 14, xa + 1)
                var s = 0L
                for (y in ya until yb) for (x in xa until xb) s += img[y][x]
                f[r * 14 + c] = s.toFloat() / ((yb - ya) * (xb - xa))
            }
        }
        val mn = f.min()
        val mx = f.max()
        if (mx - mn < 40f) return f.also { java.util.Arrays.fill(it, 0f) }
        for (i in f.indices) f[i] = (f[i] - mn) / (mx - mn)
        return f
    }

    fun read(fr: Frame): Int? {
        val sx = fr.w / REF_W
        val sy = fr.h / REF_H
        val y0 = (ROW0 * sy).roundToInt()
        val y1 = (ROW1 * sy).roundToInt()
        val h = y1 - y0
        if (h < 20) return null
        val xa = (110 * sx).roundToInt()
        val xb = minOf(fr.w, (610 * sx).roundToInt())
        val img = Array(h) { IntArray(xb - xa) }
        val lit = BooleanArray(xb - xa)
        for (y in 0 until h) {
            val row = img[y]
            for (x in 0 until xb - xa) {
                val l = luma(fr.rgb(xa + x, y0 + y))
                row[x] = l
                if (l > LIT) lit[x] = true
            }
        }
        // glyphes = suites de colonnes allumées
        val segs = ArrayList<IntArray>()
        var s = -1
        for (x in 0..lit.size) {
            val on = x < lit.size && lit[x]
            if (on && s < 0) s = x
            if (!on && s >= 0) { segs.add(intArrayOf(s, x - 1)); s = -1 }
        }
        if (segs.size < 3 || segs.size > 5) return null
        val sb = StringBuilder()
        for (g in segs) {
            val width = g[1] - g[0] + 1
            if (width < 22 * sx) {
                // point : étroit et seulement dans le bas de la ligne
                var top = h
                for (y in 0 until h) { var any = false; for (x in g[0]..g[1]) if (img[y][x] > LIT) { any = true; break }; if (any) { top = y; break } }
                if (top < h * 0.6) return null
                sb.append('.')
                continue
            }
            val f = features(img, h, g[0], g[1])
            var best = '?'
            var bestE = Float.MAX_VALUE
            for ((ch, gl) in TimerFont.digits) {
                if (width < gl.w * sx * 0.75 || width > gl.w * sx * 1.25) continue
                var e = 0f
                for (i in f.indices) { val d = f[i] - gl.f[i]; e += d * d }
                e /= f.size
                if (e < bestE) { bestE = e; best = ch }
            }
            if (best == '?' || bestE > MAX_MSE) return null
            sb.append(best)
        }
        val str = sb.toString()
        val dot = str.indexOf('.')
        if (dot < 1 || dot != str.length - 2 || str.count { it == '.' } != 1) return null
        return (str.substring(0, dot) + str[dot + 1]).toInt()
    }
}
