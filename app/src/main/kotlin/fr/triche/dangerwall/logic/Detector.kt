package fr.triche.dangerwall.logic

/**
 * La balle : étendue de la tache blanche (carré qui tourne + 1 ou 2 "fantômes" blancs derrière lui).
 * Le bord situé du côté où elle va est celui du vrai carré ; l'autre côté est étiré par les fantômes.
 */
class Ball(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int, val area: Int) {
    val cx get() = (xMin + xMax) / 2f
    val cy get() = (yMin + yMax) / 2f
}

/** Ce que l'on voit d'une image de partie. [left] et [right] sont les centres verticaux des pics de chaque mur. */
class Scene(val fieldTop: Int, val fieldBottom: Int, val ball: Ball?, val left: List<Float>, val right: List<Float>)

/**
 * Lit l'écran de "Dangerwall" : lignes rouges du terrain, balle blanche, pics rouges collés aux deux murs.
 * Géométrie mesurée sur l'écran 720 x 1594 de la vidéo (le terrain occupe toute la largeur).
 */
class Detector {
    companion object {
        const val SPIKE_HEIGHT = 75f      // hauteur d'un pic (mesurée)
        const val SPIKE_DEPTH = 58        // profondeur d'un pic dans le terrain
        const val BALL_MIN_WIDTH = 0.07f  // largeur minimale du corps de la balle (fraction de la largeur d'écran)
    }

    private var top = -1
    private var bottom = -1

    fun reset() {
        top = -1
        bottom = -1
    }

    private fun isLine(c: Int) = red(c) >= 100 && green(c) < 90 && blue(c) < 100 && red(c) - green(c) > 45
    private fun isSpike(c: Int) = red(c) >= 200 && green(c) < 120 && blue(c) < 120
    private fun isWhite(c: Int) = red(c) >= 245 && green(c) >= 245 && blue(c) >= 245

    private fun lineFraction(f: Frame, y: Int): Float {
        var n = 0
        var tot = 0
        var x = 0
        while (x < f.w) {
            if (isLine(f.rgb(x, y))) n++
            tot++
            x += 6
        }
        return n.toFloat() / tot
    }

    private fun findField(f: Frame): Boolean {
        var t = -1
        var b = -1
        for (y in (f.h * 0.15f).toInt() until (f.h * 0.45f).toInt()) {
            if (lineFraction(f, y) >= 0.8f) { t = y; break }
        }
        if (t < 0) return false
        for (y in (f.h * 0.99f).toInt() downTo (f.h * 0.80f).toInt()) {
            if (lineFraction(f, y) >= 0.8f) { b = y; break }
        }
        if (b < 0) return false
        var tt = t
        while (tt + 1 < b && lineFraction(f, tt + 1) >= 0.5f) tt++
        var bb = b
        while (bb - 1 > tt && lineFraction(f, bb - 1) >= 0.5f) bb--
        top = tt + 1
        bottom = bb - 1
        return true
    }

    private fun fieldValid(f: Frame) =
        top > 0 && bottom < f.h && lineFraction(f, top - 2) >= 0.7f && lineFraction(f, bottom + 2) >= 0.7f

    /** [hint] : dernière position connue de la balle, pour ne scanner qu'autour d'elle. */
    fun detect(f: Frame, hint: Ball? = null): Scene? {
        if (!fieldValid(f) && !findField(f)) return null
        var ball = findBall(f, hint)
        if (ball == null && hint != null) ball = findBall(f, null)
        return Scene(top, bottom, ball, spikes(f, 0, (f.w * 0.1f).toInt()), spikes(f, f.w - (f.w * 0.1f).toInt(), f.w))
    }

    private fun findBall(f: Frame, hint: Ball?): Ball? {
        val digitSkip = top + (f.h * 0.032f).toInt() // le score est dessiné en haut du terrain
        val x0: Int
        val x1: Int
        val y0: Int
        val y1: Int
        if (hint != null) {
            x0 = maxOf(0, hint.xMin - 140)
            x1 = minOf(f.w, hint.xMax + 140)
            y0 = maxOf(digitSkip, hint.yMin - 170)
            y1 = minOf(bottom, hint.yMax + 170)
        } else {
            x0 = 0; x1 = f.w; y0 = digitSkip; y1 = bottom
        }
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null
        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) mask[y * rw + x] = isWhite(f.rgb(x0 + x, y0 + y))

        val seen = BooleanArray(rw * rh)
        val stack = IntArray(rw * rh)
        var best: Ball? = null
        val minW = (f.w * BALL_MIN_WIDTH).toInt()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val rowCnt = IntArray(rh)
            val rowMin = IntArray(rh) { Int.MAX_VALUE }
            val rowMax = IntArray(rh) { -1 }
            var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % rw
                val y = p / rw
                area++
                rowCnt[y]++
                if (x < rowMin[y]) rowMin[y] = x
                if (x > rowMax[y]) rowMax[y] = x
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < rw - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - rw] && !seen[p - rw]) { seen[p - rw] = true; stack[sp++] = p - rw }
                if (y < rh - 1 && mask[p + rw] && !seen[p + rw]) { seen[p + rw] = true; stack[sp++] = p + rw }
            }
            if (area < 2500) continue
            val maxRow = rowCnt.max()
            if (maxRow < minW) continue
            // étendue en ignorant les filaments minces (moins de 12 pixels par ligne ou colonne)
            var ymin = -1
            var ymax = -1
            var xmin = Int.MAX_VALUE
            var xmax = -1
            for (y in 0 until rh) {
                if (rowCnt[y] >= 12) {
                    if (ymin < 0) ymin = y
                    ymax = y
                    if (rowMin[y] < xmin) xmin = rowMin[y]
                    if (rowMax[y] > xmax) xmax = rowMax[y]
                }
            }
            if (ymin < 0 || ymax - ymin < minW * 0.6f) continue
            val b = Ball(x0 + xmin, x0 + xmax, y0 + ymin, y0 + ymax, area)
            if (best == null || b.area > best.area) best = b
        }
        return best
    }

    /** Centres verticaux des pics collés au mur dans les colonnes [xa, xb). */
    private fun spikes(f: Frame, xa: Int, xb: Int): List<Float> {
        val out = ArrayList<Float>()
        var start = -1
        var last = -1
        fun close() {
            if (start < 0) return
            val h = last - start + 1
            if (h >= 14) {
                val n = maxOf(1, Math.round(h / SPIKE_HEIGHT))
                if (n == 1) out.add((start + last) / 2f)
                else for (i in 0 until n) out.add(start + (i + 0.5f) * h / n)
            }
            start = -1
        }
        for (y in top + 3 until bottom - 2) {
            var cnt = 0
            var x = xa
            while (x < xb) {
                if (isSpike(f.rgb(x, y))) cnt++
                x += 2
            }
            if (cnt >= 2) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last > 3) close()
        }
        close()
        return out
    }
}
