package fr.triche.react.logic

import kotlin.math.abs

/** Où toucher (fractions de la largeur / hauteur de l'écran) et quel cercle s'est allumé (0..8, ligne par ligne). */
class Target(val index: Int, val fx: Double, val fy: Double)

/**
 * REACT : une grille 3×3 de cercles sombres ; après un délai au hasard l'un d'eux s'allume en vert, il faut le toucher
 * le plus vite possible. Le détecteur regarde seulement le centre des 9 cercles : en dehors de la grille (menus, fin de
 * partie, publicités, animation de fin) la signature « un seul cercle vert, les autres sombres » n'apparaît jamais.
 */
object ReactDetector {
    // positions mesurées sur la vidéo (écran 720×1594) : centres des cercles, rayon ≈ 80 px
    val FX = doubleArrayOf(177 / 720.0, 360 / 720.0, 542 / 720.0)
    val FY = doubleArrayOf(793 / 1594.0, 975 / 1594.0, 1158 / 1594.0)
    private const val RADIUS = 80 / 720.0

    private fun isLit(r: Int, g: Int, b: Int) = g > 160 && r < 80 && g - r > 100 && b > 110
    private fun isIdle(r: Int, g: Int, b: Int) = r < 75 && g < 90 && b < 110 && b >= r && b - r > 12

    /** Cercle allumé s'il y en a exactement un et que les huit autres sont sombres, sinon null. */
    fun find(f: Frame): Target? {
        var lit = -1
        var idle = 0
        val dx = (RADIUS * f.w * 0.35).toInt().coerceAtLeast(1)
        for (j in 0 until 3) for (i in 0 until 3) {
            val cx = (FX[i] * f.w).toInt()
            val cy = (FY[j] * f.h).toInt()
            // moyenne de cinq points (centre + 4 voisins) : insensible au bruit de compression
            var r = 0; var g = 0; var b = 0
            val pts = arrayOf(0 to 0, dx to 0, -dx to 0, 0 to dx, 0 to -dx)
            for ((ox, oy) in pts) {
                val p = f.rgb((cx + ox).coerceIn(0, f.w - 1), (cy + oy).coerceIn(0, f.h - 1))
                r += red(p); g += green(p); b += blue(p)
            }
            r /= 5; g /= 5; b /= 5
            when {
                isLit(r, g, b) -> { if (lit >= 0) return null; lit = j * 3 + i }
                isIdle(r, g, b) -> idle++
            }
        }
        if (lit < 0 || idle < 8) return null
        return Target(lit, FX[lit % 3], FY[lit / 3])
    }
}

/**
 * Décide quand toucher : dès qu'un cercle vert apparaît (une fois par allumage). Si le même cercle est encore vert 0,6 s
 * après le toucher (toucher perdu), on recommence.
 */
class ReactBot {
    var rounds = 0
        private set
    var status = "En attente de la grille…"
        private set
    var log: ((String) -> Unit)? = null

    private var armed = true
    private var litSince = Double.NaN
    private var tappedAt = Double.NaN
    private var tapCount = 0
    private var lastSeenLit = Double.NaN
    private var noLitSince = Double.NaN
    private var gridSeen = false

    /** À appeler pour chaque image (heure [t] en secondes) ; renvoie la cible à toucher tout de suite, ou null. */
    fun onFrame(t: Double, f: Frame): Target? {
        val target = ReactDetector.find(f)
        if (target == null) {
            if (noLitSince.isNaN()) noLitSince = t
            if (t - noLitSince > 0.25 && !armed) {
                armed = true                      // le cercle s'est éteint : manche suivante
                tapCount = 0
                litSince = Double.NaN
                tappedAt = Double.NaN
                status = "Manche $rounds jouée — j'attends le prochain cercle"
            }
            return null
        }
        noLitSince = Double.NaN
        lastSeenLit = t
        gridSeen = true
        if (armed) {
            armed = false
            tappedAt = t
            litSince = t
            tapCount = 1
            rounds++
            status = "Manche $rounds : cercle ${target.index + 1} touché"
            log?.invoke("VERT détecté : cercle ${target.index} (manche $rounds)")
            return target
        }
        // toujours vert longtemps après notre toucher : il n'a pas été pris en compte
        if (!tappedAt.isNaN() && t - tappedAt > 0.6 && tapCount < 4) {
            tappedAt = t
            tapCount++
            log?.invoke("Cercle ${target.index} toujours vert : nouveau toucher ($tapCount)")
            return target
        }
        return null
    }
}
