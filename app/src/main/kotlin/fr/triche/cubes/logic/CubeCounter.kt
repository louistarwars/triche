package fr.triche.cubes.logic

import kotlin.math.max

/** Résultat d'un passage de plateforme. */
class PassResult(
    val count: Int,
    /** Part des images exploitables qui donnent ce même nombre (1.0 = toutes d'accord). */
    val agreement: Double,
    val framesUsed: Int,
    val framesSeen: Int,
    val sure: Boolean,
    val tStart: Double,
    val tEnd: Double,
) {
    override fun toString() = "%d boîtes (%d images sur %d, accord %.0f %%, %s)".format(
        count, framesUsed, framesSeen, agreement * 100, if (sure) "plateau net" else "plateau court : estimation")
}

private class Comp(val area: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cx: Double, val cy: Double)

private class Obs(val t: Double, val plat: Int, val full: Boolean, val count: Int)

/**
 * Compte les boîtes qui passent sur la plateforme orange : à chaque image on compte les dessus clairs des boîtes (un seul par
 * boîte), seulement quand toute la plateforme est dans l'image ; le nombre retenu pour un passage est le plus fréquent.
 */
class CubeCounter {
    var log: (String) -> Unit = {}

    /** Observation de chaque image du passage (pour les tests et le journal) : temps, surface, entière ?, nombre. */
    var trace: ((Double, Int, Boolean, Int) -> Unit)? = null

    /** Plateforme en vue / nombre provisoire du passage en cours (pour l'affichage en direct). */
    @Volatile var active = false
        private set
    @Volatile var liveCount = 0
        private set

    private val obs = ArrayList<Obs>()
    private var lastPresent = 0.0
    private var tStart = 0.0
    private var maxPlat = 0
    private var stack = IntArray(0)

    /** Traite une image ; retourne le résultat quand une plateforme vient de finir de passer. */
    fun onGrid(t: Double, g: ClassGrid): PassResult? {
        val n = g.w * g.h
        var plat = 0
        val c = g.c
        for (i in 0 until n) if (c[i] == Pix.PLAT) plat++
        val present = plat >= START * n
        if (!present) {
            if (active && t - lastPresent > GAP) return finish(t)
            return null
        }
        if (stack.size < n) stack = IntArray(n)
        val big = largest(g, Pix.PLAT, 0)
        if (big == null || big.area < START * n) {
            if (active && t - lastPresent > GAP) return finish(t)
            return null
        }
        if (!active) {
            active = true
            obs.clear()
            maxPlat = 0
            tStart = t
            liveCount = 0
        }
        lastPresent = t
        if (big.area > maxPlat) maxPlat = big.area
        val full = isFull(g, big)
        val count = countTops(g, big)
        obs.add(Obs(t, big.area, full, count))
        trace?.invoke(t, big.area, full, count)
        liveCount = obs.takeLast(8).maxOf { it.count }
        return null
    }

    /**
     * Termine le passage en cours. Les boîtes entrent et sortent de l'écran du jeu pendant que la plateforme passe : le
     * nombre exact est celui du plateau de plusieurs images de suite où il ne bouge plus (filtre médian sur 3 images pour
     * ignorer une image floue, puis plus longue série d'images de même nombre ; parmi les séries presque aussi longues, la
     * plus grande valeur, car une plateforme à moitié entrée ne montre que certaines boîtes).
     */
    private fun finish(t: Double): PassResult? {
        active = false
        val all = obs.toList()
        obs.clear()
        if (all.isEmpty() || maxPlat < MIN_PASS) return null
        val seq = all.filter { it.plat >= 0.25 * maxPlat }.map { it.count }
        val sm = IntArray(seq.size) { i ->
            if (i == 0 || i == seq.size - 1) seq[i] else median3(seq[i - 1], seq[i], seq[i + 1])
        }
        // séries d'images de même nombre
        val runs = ArrayList<IntArray>()          // (valeur, longueur)
        var i = 0
        while (i < sm.size) {
            var j = i
            while (j + 1 < sm.size && sm[j + 1] == sm[i]) j++
            if (sm[i] > 0) runs.add(intArrayOf(sm[i], j - i + 1))
            i = j + 1
        }
        if (runs.isEmpty()) return null
        val longest = runs.maxOf { it[1] }
        val best = runs.filter { it[1] >= max(2, (0.6 * longest).toInt()) }.maxByOrNull { it[0] } ?: runs.maxByOrNull { it[1] }!!
        liveCount = best[0]
        return PassResult(best[0], best[1].toDouble() / sm.size, best[1], all.size, best[1] >= 3, tStart, t)
    }

    private fun median3(a: Int, b: Int, c: Int) = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    // ---------- analyse d'une image ----------

    /** Plus grande tache de la classe [cls] (4-connexité). */
    private fun largest(g: Grid_, cls: Byte, minArea: Int) = components(g.c, g.w, g.h, cls, minArea).maxByOrNull { it.area }

    private fun components(c: ByteArray, w: Int, h: Int, cls: Byte, minArea: Int): List<Comp> {
        val out = ArrayList<Comp>()
        val seen = BooleanArray(w * h)
        if (stack.size < w * h) stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (c[start] != cls || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            var sx = 0L; var sy = 0L
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                sx += x; sy += y
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && c[p - 1] == cls && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && c[p + 1] == cls && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && c[p - w] == cls && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && c[p + w] == cls && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            if (area >= minArea) out.add(Comp(area, x0, y0, x1, y1, sx.toDouble() / area, sy.toDouble() / area))
        }
        return out
    }

    /** Toute la plateforme et toutes les boîtes sont dans l'image : rien ne touche le bord. */
    private fun isFull(g: Grid_, plat: Comp): Boolean {
        val m = 3
        if (plat.x0 <= m || plat.y0 <= m || plat.x1 >= g.w - 1 - m || plat.y1 >= g.h - 1 - m) return false
        val c = g.c
        for (y in 0 until g.h) {
            val edge = y < m || y >= g.h - m
            for (x in 0 until g.w) {
                if (!edge && x >= m && x < g.w - m) { continue }
                val k = c[y * g.w + x]
                if (k == Pix.TOP || k == Pix.TAN) return false
            }
        }
        return true
    }

    /** Nombre de dessus de boîtes (taches claires) posés sur la plateforme. */
    private fun countTops(g: Grid_, plat: Comp): Int {
        val w = g.w; val h = g.h; val c = g.c
        // érosion : sépare deux dessus qui se touchent à peine
        val er = ByteArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val p = y * w + x
            if (c[p] == Pix.TOP && c[p - 1] == Pix.TOP && c[p + 1] == Pix.TOP && c[p - w] == Pix.TOP && c[p + w] == Pix.TOP) er[p] = Pix.TOP
        }
        val minArea = max(8, (0.006 * plat.area).toInt())
        val bw = plat.x1 - plat.x0
        val bh = plat.y1 - plat.y0
        var k = 0
        for (comp in components(er, w, h, Pix.TOP, minArea)) {
            if (comp.cx < plat.x0 - 0.3 * bw || comp.cx > plat.x1 + 0.3 * bw) continue
            if (comp.cy < plat.y0 - 0.4 * bh || comp.cy > plat.y1 + 0.2 * bh) continue
            k++
        }
        return k
    }

    companion object {
        private const val START = 0.004            // part de l'image occupée par la plateforme pour la voir arriver
        private const val GAP = 0.35               // secondes sans plateforme = passage terminé
        private const val MIN_PASS = 400           // taille minimale (pixels) de la plus grande plateforme d'un vrai passage
    }
}

private typealias Grid_ = ClassGrid
