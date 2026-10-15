package fr.triche.timesup.logic

import kotlin.math.abs

/**
 * Une mesure de la barre de progression : positions (en pixels de l'image, au 1/10 de pixel) du haut et du bas de la partie
 * pleine, et niveau de vert de la partie pleine (diminue quand la barre s'efface).
 */
class BarSample(val top: Double, val bottom: Double, val level: Double, val width: Double)

/**
 * Lit la barre du jeu (rectangle vertical au centre : fond gris-bleu, partie pleine cyan qui descend). On suit le canal vert
 * sur 3 colonnes au centre ; le bord haut et le bord bas de la partie pleine sont placés à la demi-hauteur de la marche
 * (interpolation entre deux lignes), ce qui donne la position à mieux que le pixel.
 */
object BarReader {
    private const val FILL = 66.0            // vert d'une partie pleine (le fond de la barre est à ~46, le décor à ~25)
    private const val MIN_LEVEL = 74.0       // en dessous, la barre est presque effacée : mesure trop imprécise
    private const val BOTTOM = 0.7783        // bas de la barre (part de la hauteur) mesuré sur la vidéo
    private const val WIDTH = 0.2917         // largeur de la barre (part de la largeur de l'image)

    private fun green(rgb: Int) = (rgb shr 8) and 0xFF

    fun read(fr: Frame): BarSample? {
        val w = fr.w
        val h = fr.h
        val cx = w / 2
        val y0 = (0.30 * h).toInt()
        val y1 = (0.88 * h).toInt()
        val n = y1 - y0
        val g = DoubleArray(n) { i ->
            val y = y0 + i
            (green(fr.rgb(cx - 1, y)) + green(fr.rgb(cx, y)) + green(fr.rgb(cx + 1, y))) / 3.0
        }
        // première ligne au-dessus du fond de la barre (la partie pleine est précédée d'un halo qui monte de 47 à ~75)
        var first = -1
        for (i in 0 until n - 80) if (g[i] > 58.0 && g[i + 60] > FILL - 4) { first = i; break }
        if (first < 0) return null
        // bord haut : plus forte marche montante dans les 45 lignes suivantes
        var top = -1
        var best = 0.0
        for (i in maxOf(4, first)..minOf(n - 14, first + 45)) {
            val d = g[i + 3] - g[i - 3]
            if (d > best) { best = d; top = i }
        }
        if (top < 0 || best < 18.0) return null
        val fillTop = mean(g, top + 8, top + 14)
        if (fillTop < MIN_LEVEL) return null
        val lowTop = mean(g, top - 12, top - 6)
        val thrTop = (lowTop + fillTop) / 2
        var k = top - 4
        while (k < top + 5 && g[k] <= thrTop) k++
        if (k <= 0 || k >= n) return null
        val topEdge = (k - 1) + (thrTop - g[k - 1]) / (g[k] - g[k - 1] + 1e-9)

        // bord bas : plus forte marche descendante près du bas de la partie pleine
        var bot = -1
        var bestB = 0.0
        for (i in n - 8 downTo top + 50) {
            val d = g[i - 3] - g[i + 3]
            if (d > bestB) { bestB = d; bot = i; if (d > 60.0) break }
        }
        if (bot < 0 || bestB < 40.0) return null
        // la partie pleine est continue de haut en bas
        var i = top + 8
        while (i < bot - 6) { if (g[i] <= FILL - 4) return null; i += 3 }
        val fillBot = mean(g, bot - 14, bot - 6)
        val below = mean(g, bot + 6, minOf(n - 1, bot + 14))
        val thrBot = (fillBot + below) / 2
        var m = bot - 4
        while (m < bot + 5 && g[m + 1] > thrBot) m++
        if (m >= n - 1) return null
        val botEdge = m + (g[m] - thrBot) / (g[m] - g[m + 1] + 1e-9)

        val topPx = y0 + topEdge
        val botPx = y0 + botEdge
        if (abs(botPx - BOTTOM * h) > 0.012 * h) return null     // pas la barre du jeu (autre écran)
        if (botPx - topPx < 30) return null

        // largeur et centrage de la partie pleine, mesurés à mi-hauteur
        val ym = ((topPx + botPx) / 2).toInt()
        val thrW = (fillTop + 30.0) / 2
        var l = cx
        while (l > 0 && rowGreen(fr, l - 1, ym) > thrW) l--
        var r = cx
        while (r < w - 1 && rowGreen(fr, r + 1, ym) > thrW) r++
        val width = (r - l + 1).toDouble()
        if (abs(width / w - WIDTH) > 0.03 || abs((l + r) / 2.0 - cx) > 0.02 * w) return null
        return BarSample(topPx, botPx, fillTop, width)
    }

    private fun rowGreen(fr: Frame, x: Int, y: Int) =
        (green(fr.rgb(x, y - 1)) + green(fr.rgb(x, y)) + green(fr.rgb(x, y + 1))) / 3.0

    private fun mean(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        var c = 0
        for (i in maxOf(0, from)..minOf(a.size - 1, to)) { s += a[i]; c++ }
        return if (c == 0) 0.0 else s / c
    }
}

/** Bouton « NIVEAU SUIVANT » de l'écran de résultat : grand bouton cyan, ~75 % de la largeur, à ~67 % de la hauteur. */
object NextButton {
    /** Centre du bouton (pixels de l'image), ou null. */
    fun find(fr: Frame): Pair<Double, Double>? {
        val w = fr.w
        val h = fr.h
        val xl = (0.25 * w).toInt()
        var y = (0.60 * h).toInt()
        val yEnd = (0.74 * h).toInt()
        var start = -1
        var end = -1
        while (y < yEnd) {
            if (cyan(fr.rgb(xl, y))) { if (start < 0) start = y; end = y } else if (start >= 0 && y - end > (0.03 * h).toInt()) break   // le texte blanc du bouton interrompt la colonne
            y++
        }
        if (start < 0) return null
        val hh = end - start + 1
        if (hh < 0.045 * h || hh > 0.09 * h) return null
        val yc = (start + end) / 2
        // largeur à mi-hauteur : au moins 60 % de l'image
        var l = xl
        while (l > 0 && cyan(fr.rgb(l - 1, start + 8))) l--
        var r = xl
        while (r < w - 1 && cyan(fr.rgb(r + 1, start + 8))) r++
        val row = (r - l + 1).toDouble() / w
        if (row < 0.55) return null
        return ((l + r) / 2.0) to yc.toDouble()
    }

    private fun cyan(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r < 90 && g > 120 && b > 170
    }
}
