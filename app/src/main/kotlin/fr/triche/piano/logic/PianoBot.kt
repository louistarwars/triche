package fr.triche.piano.logic

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/** Point à toucher, en pixels de l'image analysée, [delay] secondes après le début du geste (touchers successifs, jamais simultanés). */
class TapPoint(val x: Double, val y: Double, val delay: Double = 0.0)

/**
 * Jeu « PIANO » : des touches blanches (4 colonnes, rangées de 300 px sur une image de 720) défilent vers le bas, de plus en plus
 * vite ; il faut toucher chacune une fois, et toucher le vide termine la partie.
 *
 * Toutes les touches partagent la même position verticale de défilement Φ(t) : rangée R, colonne c = touche dont le haut est à
 * Φ + 300·R. On mesure Φ modulo 300 sur les bords des touches blanches de quatre colonnes, on la « déroule » dans le temps
 * (vitesse par régression), puis on touche chaque case blanche non encore touchée, à l'endroit où elle sera quand le toucher
 * arrivera dans le jeu (latence apprise d'après l'instant où la touche s'éteint).
 */
class PianoBot(initialLead: Double = 0.06) {
    var log: (String) -> Unit = {}
    @Volatile var status = "En attente de la partie"
    @Volatile var taps = 0
        private set
    @Volatile var speed = 0.0
        private set

    private val lock = Any()

    // géométrie (pour une image de 720 px de large)
    private val pitch = 299.86
    private val tileH = 296.3
    private val colCenters = doubleArrayOf(90.0, 270.0, 450.0, 630.0)
    private val sampleX = doubleArrayOf(100.0, 200.0, 520.0, 630.0)         // colonnes de balayage (loin du score au centre)

    // suivi du défilement
    private var init = false
    private var phi = 0.0                 // position (px de l'image) du haut de la rangée R = 0 à l'instant tLast
    private var tLast = 0.0
    private var v = 0.0
    private val histT = ArrayList<Double>()
    private val histP = ArrayList<Double>()
    private var lastBgOk = 0.0
    private var lastLock = 0.0
    private var lastFrameT = 0.0
    private var scale = 1.0
    private var clipTop = 0.0
    private var bottomLimit = 0.0

    // cases
    private class Rec(val sentAt: Double, val tries: Int) { var ghostAt = Double.NaN }
    private val tapped = HashMap<Long, Rec>()
    private val wanted = LinkedHashMap<Long, IntArray>()                 // clé -> (R, c)
    private val lats = ArrayList<Double>()
    @Volatile var lead = initialLead
        private set
    private var lastLog = 0.0
    private var firstTaps = 0
    private var lockedFrames = 0
    private var frames = 0

    private fun key(r: Int, c: Int) = r.toLong() * 4 + c

    // ---------- lecture de l'image ----------

    private fun luma(rgb: Int) = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)

    /** Le décor est le violet foncé du jeu (et non le menu clair, l'écran noir de fin ou le voile rouge). */
    private fun bgOk(fr: Frame): Boolean {
        var good = 0
        var n = 0
        for (j in 0 until 8) {
            val y = ((0.25 + 0.09 * j) * fr.h).toInt()
            for (i in 0 until 6) {
                val x = ((0.04 + 0.19 * i) * fr.w).toInt().coerceAtMost(fr.w - 1)
                val p = fr.rgb(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                n++
                val bg = r in 12..80 && g in 0..52 && b in 35..120 && b > r + 8
                val white = r > 200 && g > 200 && b > 200
                if (bg) good++ else if (white) good += 0     // les touches blanches ne comptent ni pour ni contre
            }
        }
        return good >= 0.35 * n
    }

    /** Position Φ mod pitch (px), d'après les bords des touches blanches, et nombre de bords concordants. */
    private fun measurePhase(fr: Frame): Pair<Double, Int>? {
        val s = scale
        val p = pitch * s
        val hh = tileH * s
        val ys = ArrayList<Double>()
        val y0 = (clipTop + 3).toInt()
        val y1 = bottomLimit.toInt()
        for (xs in sampleX) {
            val x = (xs * s).toInt().coerceIn(0, fr.w - 1)
            var prev = luma(fr.rgb(x, y0)) / 3.0
            for (y in y0 until y1) {
                val cur = luma(fr.rgb(x, y + 1)) / 3.0
                if (prev <= 135.0 && cur > 135.0) {
                    val e = y + (135.0 - prev) / (cur - prev)
                    if (e > clipTop + 4.0) ys.add(e % p)                       // haut d'une touche (hors bord du cadre)
                } else if (prev > 135.0 && cur <= 135.0) {
                    val e = y + (prev - 135.0) / (prev - cur)
                    if (e < bottomLimit - 4.0) ys.add(((e - hh) % p + p) % p)  // bas d'une touche
                }
                prev = cur
            }
        }
        if (ys.size < 2) return null
        // moyenne circulaire, puis rejet des bords qui s'écartent
        var cur = circMean(ys, p, null)
        for (pass in 0..1) {
            val keep = ys.filter { abs(wrap(it - cur, p)) <= 2.5 * s + 0.6 }
            if (keep.size < 2) return null
            cur = circMean(keep, p, null)
            if (pass == 1) return cur to keep.size
        }
        return null
    }

    private fun circMean(v: List<Double>, p: Double, w: DoubleArray?): Double {
        var sx = 0.0; var sy = 0.0
        for (x in v) { val a = 2 * PI * x / p; sx += cos(a); sy += sin(a) }
        var m = atan2(sy, sx) * p / (2 * PI)
        if (m < 0) m += p
        return m
    }

    /** Ramène un écart dans ]−p/2, p/2]. */
    private fun wrap(d: Double, p: Double): Double {
        var x = d % p
        if (x > p / 2) x -= p
        if (x <= -p / 2) x += p
        return x
    }

    // ---------- traitement d'une image ----------

    fun onFrame(t: Double, fr: Frame) {
        scale = fr.w / 720.0
        clipTop = 0.192 * fr.h
        bottomLimit = 0.965 * fr.h
        frames++
        val ok = bgOk(fr)
        if (!ok) {
            synchronized(lock) {
                if (init && t - lastBgOk > 0.6) endGame(t)
            }
            return
        }
        val ph = measurePhase(fr)
        synchronized(lock) {
            lastBgOk = t
            lastFrameT = t
            if (ph != null) { track(t, ph.first); lockedFrames++; lastLock = t }
            if (!init) return
            if (hist() < 4) return                       // vitesse pas encore connue
            decide(t, fr)
            if (t - lastLog > 3.0) {
                lastLog = t
                log("JEU t=%.1f : %d touches touchées, vitesse %.0f px/s (image 720), latence apprise %.0f ms, verrouillé sur %d%% des images".format(
                    t, taps, v / scale, lead * 1000, 100 * lockedFrames / max(1, frames)))
            }
        }
    }

    private fun hist() = histT.size

    private fun track(t: Double, ph: Double) {
        val p = pitch * scale
        if (!init) {
            init = true
            phi = ph
            tLast = t
            v = 0.0
            histT.clear(); histP.clear()
            histT.add(t); histP.add(phi)
            status = "Partie en cours"
            log("PARTIE détectée à t=%.2f (phase %.1f px)".format(t, ph))
            return
        }
        val dt = t - tLast
        if (dt <= 0) return
        val pred = phi + v * dt
        val d = wrap(ph - ((pred % p + p) % p), p)
        phi = pred + d
        tLast = t
        histT.add(t); histP.add(phi)
        if (histT.size > 10) { histT.removeAt(0); histP.removeAt(0) }
        if (histT.size >= 3) {
            val n = histT.size
            val t0 = histT[0]
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in 0 until n) { val x = histT[i] - t0; sx += x; sy += histP[i]; sxx += x * x; sxy += x * histP[i] }
            val det = n * sxx - sx * sx
            if (det > 1e-9) v = ((n * sxy - sx * sy) / det).coerceIn(0.0, 8000.0 * scale)
        }
        speed = v / scale
    }

    private fun endGame(t: Double) {
        log("FIN de la partie détectée (écran différent) : %d touches touchées".format(taps))
        status = "Partie terminée : $taps touches"
        init = false
        tapped.clear(); wanted.clear(); histT.clear(); histP.clear()
        lats.clear()
    }

    /** Case (R, c) : blanche ? Quelques points à l'intérieur de la partie visible de la touche. */
    private fun isWhite(fr: Frame, yTop: Double, c: Int): Boolean {
        val s = scale
        val visTop = max(yTop, clipTop + 3)
        val visBot = min(yTop + tileH * s, bottomLimit)
        if (visBot - visTop < 0.25 * tileH * s) return false
        val y = ((visTop + visBot) / 2).toInt().coerceIn(0, fr.h - 1)
        var seen = 0
        for (dx in doubleArrayOf(-50.0, 50.0)) {
            val xr = colCenters[c] + dx
            val yr = y / s
            if (xr in 270.0..445.0 && yr in 360.0..455.0) continue        // zone du score
            val p = fr.rgb((xr * s).toInt().coerceIn(0, fr.w - 1), y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (!(r > 190 && g > 190 && b > 190 && abs(r - b) < 40)) return false
            seen++
        }
        return seen > 0
    }

    private fun decide(t: Double, fr: Frame) {
        val s = scale
        val p = pitch * s
        val phi = phi + v * (t - tLast)              // position à l'instant de l'image (le suivi continue sans touche visible)
        val rMin = ceil((clipTop - tileH * s + 5 - phi) / p).toInt()
        val rMax = floor((bottomLimit - phi) / p).toInt()
        // oublie les cases sorties de l'écran
        val it = tapped.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val r = floorDiv4(e.key)
            if (r > rMax + 3) it.remove()
        }
        for (r in rMin..rMax) {
            val yTop = phi + p * r
            for (c in 0..3) {
                val k = key(r, c)
                val white = isWhite(fr, yTop, c)
                val rec = tapped[k]
                if (rec != null) {
                    if (!white && rec.ghostAt.isNaN()) {
                        rec.ghostAt = t
                        val lat = t - rec.sentAt
                        if (lat in 0.0..0.6) {
                            lats.add(lat)
                            if (lats.size > 25) lats.removeAt(0)
                            if (lats.size >= 5) lead = (lats.sorted()[lats.size / 2] - 0.025).coerceIn(0.02, 0.25)
                        }
                    }
                    if (white && t - rec.sentAt > 0.45 && rec.tries < 3) {
                        wanted[k] = intArrayOf(r, c)                      // toujours blanche : le toucher s'est perdu
                        continue
                    }
                    continue
                }
                if (white) {
                    val yc = yTop + tileH * s / 2 + v * lead
                    if (yc >= clipTop + 0.17 * tileH * s && yc <= bottomLimit - 0.08 * tileH * s) wanted[k] = intArrayOf(r, c)
                } else wanted.remove(k)
            }
        }
        // retire des cases voulues celles qui ont disparu de la zone
        val w = wanted.entries.iterator()
        while (w.hasNext()) { val e = w.next(); if (e.value[0] < rMin - 1 || e.value[0] > rMax + 1) w.remove() }
    }

    private fun floorDiv4(k: Long): Int = Math.floorDiv(k, 4L).toInt()

    /**
     * Touches à envoyer maintenant (appelé par le fil des touchers) : positions calculées à l'instant [now] pour l'arrivée du
     * toucher dans le jeu (maintenant + latence). Vide si le suivi est périmé.
     */
    fun collectTaps(now: Double, max: Int = 6): List<TapPoint> {
        synchronized(lock) {
            if (!init || wanted.isEmpty() || now - lastFrameT > 0.25 || now - lastLock > 1.5) return emptyList()
            val s = scale
            val p = pitch * s
            val phiNow = phi + v * (now - tLast)
            // les plus basses d'abord (les plus pressées), espacées de 12 ms : jamais deux doigts à la fois
            val order = wanted.entries.sortedByDescending { phiNow + p * it.value[0] }
            val out = ArrayList<TapPoint>()
            val gap = 0.012
            for (e in order) {
                if (out.size >= max) break
                val rc = e.value
                val delay = gap * out.size
                val yc = phiNow + p * rc[0] + tileH * s / 2 + v * (lead + delay)
                if (yc < clipTop + 0.12 * tileH * s || yc > bottomLimit) { continue }       // plus sûr de ne pas toucher
                out.add(TapPoint(colCenters[rc[1]] * s, yc, delay))
                val old = tapped[e.key]
                tapped[e.key] = Rec(now + delay, (old?.tries ?: 0) + 1)
                taps++
                if (firstTaps < 12) { firstTaps++; log("TOUCHER case (rangée %d, colonne %d) en (%.0f, %.0f) +%.0f ms, vitesse %.0f px/s".format(rc[0], rc[1], colCenters[rc[1]] * s, yc, delay * 1000, v / s)) }
            }
            for (e in order) if (tapped[e.key]?.sentAt?.let { it >= now } == true) wanted.remove(e.key)
            return out
        }
    }

    /** Pour les tests : position suivie du haut de la rangée 0. */
    val trackedPhi get() = phi

    fun summary() = "$taps touches touchées, latence apprise ${(lead * 1000).toInt()} ms"
}
