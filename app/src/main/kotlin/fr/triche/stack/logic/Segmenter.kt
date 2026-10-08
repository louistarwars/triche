package fr.triche.stack.logic

import kotlin.math.abs
import kotlin.math.max

/** Une face supérieure de bloc : zone de couleur unie. Coordonnées en pixels de l'image. */
class Comp(
    val xmin: Int,
    val xmax: Int,
    val ymin: Int,
    val ymax: Int,
    /** Aire en pixels (approchée : échantillonnage de pas [Segmenter.step]). */
    val area: Int,
    val r: Int,
    val g: Int,
    val b: Int,
) {
    val cx get() = (xmin + xmax) / 2.0
    val gray get() = abs(r - g) <= 4 && abs(g - b) <= 4 && g in 66..86

    fun colorDist(o: Comp) = max(abs(r - o.r), max(abs(g - o.g), abs(b - o.b)))
    fun colorDist(rr: Int, gg: Int, bb: Int) = max(abs(r - rr), max(abs(g - gg), abs(b - bb)))
}

/**
 * Découpe l'image en faces supérieures de blocs : pixels vifs (les faces du dessus sont saturées et claires,
 * les côtés sont plus sombres) regroupés tant que la couleur reste la même (±4) ; le socle gris de départ
 * est accepté aussi. Deux blocs voisins mais de couleurs différentes (≥ 9 d'écart) restent donc séparés.
 */
class Segmenter(val step: Int = 2, private val roiTop: Double = 0.30, private val roiBottom: Double = 0.72) {
    private var gw = 0
    private var gh = 0
    private var parent = IntArray(0)
    private var col = IntArray(0)

    private fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(red(a) - red(b)) <= 4 && abs(green(a) - green(b)) <= 4 && abs(blue(a) - blue(b)) <= 4

    fun segment(f: Frame, minArea: Int = 600): List<Comp> {
        val y0 = (f.h * roiTop).toInt()
        val y1 = (f.h * roiBottom).toInt()
        gw = f.w / step
        gh = (y1 - y0) / step
        val n = gw * gh
        if (parent.size < n) {
            parent = IntArray(n)
            col = IntArray(n)
        }
        for (j in 0 until gh) {
            val y = y0 + j * step
            for (i in 0 until gw) {
                val p = f.rgb(i * step, y)
                val r = red(p)
                val g = green(p)
                val b = blue(p)
                val mx = max(r, max(g, b))
                val mn = minOf(r, minOf(g, b))
                val vivid = mx > 183 && (mx - mn) * 10 > 3 * mx
                val socle = abs(r - g) <= 3 && abs(g - b) <= 3 && g in 68..84
                val k = j * gw + i
                col[k] = if (vivid || socle) p else -1
                parent[k] = k
            }
        }
        for (j in 0 until gh) {
            for (i in 0 until gw) {
                val k = j * gw + i
                val c = col[k]
                if (c < 0) continue
                if (i + 1 < gw && col[k + 1] >= 0 && close(c, col[k + 1])) union(k, k + 1)
                if (j + 1 < gh && col[k + gw] >= 0 && close(c, col[k + gw])) union(k, k + gw)
            }
        }
        // statistiques par racine
        val cnt = HashMap<Int, IntArray>() // racine -> [n, xmin, xmax, ymin, ymax, sr, sg, sb]
        for (j in 0 until gh) {
            for (i in 0 until gw) {
                val k = j * gw + i
                val c = col[k]
                if (c < 0) continue
                val root = find(k)
                val s = cnt.getOrPut(root) { intArrayOf(0, Int.MAX_VALUE, -1, Int.MAX_VALUE, -1, 0, 0, 0) }
                s[0]++
                val x = i * step
                val y = y0 + j * step
                if (x < s[1]) s[1] = x
                if (x > s[2]) s[2] = x
                if (y < s[3]) s[3] = y
                if (y > s[4]) s[4] = y
                s[5] += red(c)
                s[6] += green(c)
                s[7] += blue(c)
            }
        }
        val out = ArrayList<Comp>()
        for (s in cnt.values) {
            val area = s[0] * step * step
            if (area < minArea) continue
            // le dégradé violet du fond (certains téléphones le rendent plus clair) n'est pas un bloc : il occupe toute la largeur
            if (s[2] - s[1] > 0.85 * f.w) continue
            out.add(Comp(s[1], s[2] + step - 1, s[3], s[4] + step - 1, area, s[5] / s[0], s[6] / s[0], s[7] / s[0]))
        }
        return out
    }

    private fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}
