package fr.triche.colis.logic

import java.nio.ByteBuffer

/** Image RGBA_8888 (comme fournie par ImageReader), lue sans copie. */
class Frame(
    private val buf: ByteBuffer,
    val w: Int,
    val h: Int,
    private val rowStride: Int,
    private val pixelStride: Int,
) {
    /** Pixel packé 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int {
        val o = y * rowStride + x * pixelStride
        return ((buf.get(o).toInt() and 0xFF) shl 16) or
            ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
            (buf.get(o + 2).toInt() and 0xFF)
    }
}

/** Couleurs de colis : chacune correspond à un sens de glissement. */
object Color {
    const val NONE = 0
    const val RED = 1     // -> gauche
    const val YELLOW = 2  // -> haut
    const val BLUE = 3    // -> droite

    /** Classe un pixel (teinte/saturation/luminosité mesurées sur la vidéo du jeu). */
    fun classify(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx < 115) return NONE
        val d = mx - mn
        val s = d.toFloat() / mx
        if (s < 0.40f) return NONE
        val hue = when (mx) {
            r -> 60f * (((g - b).toFloat() / d + 6f) % 6f)
            g -> 60f * ((b - r).toFloat() / d + 2f)
            else -> 60f * ((r - g).toFloat() / d + 4f)
        }
        val v = mx / 255f
        return when {
            (hue <= 22f || hue >= 345f) && v >= 0.45f -> RED
            hue in 36f..68f && v >= 0.50f && s >= 0.50f -> YELLOW
            hue in 175f..215f && v >= 0.45f && s >= 0.55f -> BLUE
            else -> NONE
        }
    }
}

/** Un colis (ou un paquet de colis de même couleur collés) sur le tapis. */
class Parcel(val color: Int, val top: Int, val bottom: Int, val share: Float)

class Scene(val beltLeft: Int, val beltRight: Int, val top: Int, val bottom: Int, val parcels: List<Parcel>)

/**
 * Lit l'écran du jeu : repère le tapis (deux rails jaunes) puis les colis qui y descendent.
 * Renvoie null quand ce n'est pas une partie visible (menu, tutoriel assombri, game over...).
 */
class Detector {
    companion object {
        // Géométrie mesurée sur l'écran 720 x 1594 du jeu, en fractions de l'écran.
        const val RAIL_SPAN_MIN = 0.26f
        const val RAIL_SPAN_MAX = 0.37f
        const val BELT_TOP = 0.528f
        const val BELT_BOTTOM = 0.860f
        const val MIN_ROW = 14        // pixels colorés (pas 2) pour qu'une ligne compte
        const val MIN_HEIGHT = 24     // hauteur minimale d'un colis, en pixels
    }

    private fun isRail(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r > 205 && g in 150..225 && b < 125
    }

    private fun isBeltDark(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r < 95 && g < 115 && b < 130 && b > r
    }

    /** Position horizontale des rails, ou null si le tapis n'est pas là (ou assombri). */
    private fun findRails(f: Frame): IntArray? {
        var l = 0
        var r = 0
        var votes = 0
        for (fy in floatArrayOf(0.64f, 0.70f, 0.76f)) {
            val y = (f.h * fy).toInt()
            var first = -1
            var last = -1
            for (x in (f.w * 0.28f).toInt()..(f.w * 0.72f).toInt()) {
                if (isRail(f.rgb(x, y))) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            val span = (last - first).toFloat() / f.w
            if (span in RAIL_SPAN_MIN..RAIL_SPAN_MAX) {
                l += first; r += last; votes++
            }
        }
        if (votes < 2) return null
        return intArrayOf(l / votes, r / votes)
    }

    fun detect(f: Frame): Scene? {
        val rails = findRails(f) ?: return null
        val xa = rails[0] + (f.w * 0.04f).toInt()
        val xb = rails[1] - (f.w * 0.04f).toInt()
        val y0 = (f.h * BELT_TOP).toInt()
        val y1 = (f.h * BELT_BOTTOM).toInt()

        // Le fond du tapis doit être sombre (sinon on est sur un autre écran).
        var dark = 0
        for (fy in floatArrayOf(0.70f, 0.76f, 0.82f)) {
            if (isBeltDark(f.rgb(xa + 6, (f.h * fy).toInt()))) dark++
        }
        if (dark < 2) return null

        val parcels = ArrayList<Parcel>()
        val counts = IntArray(4)
        var runColor = Color.NONE
        var runTop = -1
        var runBottom = -1
        var gap = 0
        val runCounts = IntArray(4)
        var y = y0

        fun closeRun() {
            if (runColor != Color.NONE && runBottom - runTop >= MIN_HEIGHT) {
                val tot = runCounts[1] + runCounts[2] + runCounts[3]
                parcels.add(Parcel(runColor, runTop, runBottom, runCounts[runColor].toFloat() / tot))
            }
            runColor = Color.NONE
            runCounts.fill(0)
        }

        while (y <= y1) {
            counts.fill(0)
            var x = xa
            while (x <= xb) {
                counts[Color.classify(f.rgb(x, y))]++
                x += 2
            }
            var best = Color.NONE
            var bestN = MIN_ROW - 1
            for (c in 1..3) if (counts[c] > bestN) { best = c; bestN = counts[c] }
            if (best != Color.NONE && best == runColor) {
                runBottom = y
                gap = 0
                for (c in 1..3) runCounts[c] += counts[c]
            } else if (best != Color.NONE) {
                closeRun()
                runColor = best
                runTop = y
                runBottom = y
                gap = 0
                for (c in 1..3) runCounts[c] += counts[c]
            } else if (runColor != Color.NONE && ++gap > 2) {
                closeRun()
            }
            y += 2
        }
        closeRun()
        return Scene(xa, xb, y0, y1, parcels)
    }
}
