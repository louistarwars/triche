package fr.triche.stack.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StackSettings(
    /** Score visé (nombre de blocs posés) ; 0 = jouer sans fin. */
    val targetScore: Int = 0,
    /** Une fois le score visé atteint : rater volontairement le bloc suivant pour terminer la partie. */
    val endOnTarget: Boolean = true,
    /** Délai initial (s) entre l'ordre de toucher et le moment où le jeu pose le bloc, vu dans l'image. */
    val latencyLeft: Double = 0.11,
    val latencyRight: Double = 0.11,
)

/**
 * Joue à Stack : un bloc glisse au-dessus de la tour (de gauche à droite, puis de droite à gauche…) ; il faut
 * toucher l'écran quand il est exactement au-dessus du sommet de la tour.
 *
 * Le bloc avance à vitesse à peu près constante : on suit son centre (en x) image après image, on en déduit
 * l'instant où il sera aligné avec le sommet de la tour, et on programme le toucher un peu avant (délai appris :
 * après chaque pose on mesure de combien on a raté et on corrige).
 */
class StackBot(private val w: Int, private val h: Int, var settings: StackSettings) {
    enum class State { TRACK, DONE }

    var state = State.TRACK
        private set
    var score = 0
        private set
    var latLeft = settings.latencyLeft
        private set
    var latRight = settings.latencyRight
        private set
    var status = "En attente du jeu…"
        private set
    var lastError = 0.0
        private set

    /** Ajoute une ligne au journal (utilisé aussi par le service Android). */
    fun note(s: String) { log?.invoke(s) }

    /** Journal : lignes de diagnostic (état chaque seconde, poses, touchers). */
    var log: ((String) -> Unit)? = null
    var debug: ((String) -> Unit)? = null

    // ---- mémoire du jeu ----
    private var ct: IntArray? = null                   // couleur du sommet de la tour
    private val known = ArrayDeque<IntArray>()         // couleurs des blocs déjà posés (et du socle)
    private var hasTower = false
    private var tx0 = 0.0
    private var tx1 = 0.0

    private class Static(var r: Int, var g: Int, var b: Int, var cx: Double, var t0: Double)
    private val statics = ArrayList<Static>()
    private val ignored = ArrayList<IntArray>()      // couleurs écartées (pas la tour)

    // ---- suivi du bloc mobile ----
    private val st = DoubleArray(MAXS)
    private val sc = DoubleArray(MAXS)
    private var ns = 0
    private var slabRgb: IntArray? = null
    private var slabW = 0.0
    private var staticRun = 0
    private var lastV = 0.0
    private var aimV = 0.0
    private var nLearn = 0
    private var lastE = 0.0

    private var tapTime = Double.NaN
    private var tapVel = 0.0
    private var tapDir = 0
    private var pendingTap = false

    private var lastY = 0.0
    private var nextDiag = 0.0
    private var dRms = Double.NaN
    private var staticSince = 0.0
    private var staticRef = 0.0
    private var vSeen = 0.0
    private var lastMoveT = 0.0
    private var lastAlign = 0.0
    private var lastAlignV = 0.0
    private var lockUntil = 0.0
    private var bestArea = 0
    private var frozenT = false      // sommet de la tour mesuré une fois pour toutes après chaque pose

    private companion object {
        const val MAXS = 14
        const val COLOR_TOL = 5
        const val MIN_SLAB_AREA = 1200
    }

    fun resetGame() {
        ct = null
        known.clear()
        ignored.clear()
        statics.clear()
        hasTower = false
        ns = 0
        slabRgb = null
        staticRun = 0
        tapTime = Double.NaN
        pendingTap = false
        lockUntil = 0.0
        frozenT = false
        bestArea = 0
        score = 0
        state = State.TRACK
    }

    /** À appeler quand le toucher programmé a été envoyé. */
    @Synchronized
    fun onTapped(t: Double) {
        tapTime = t
        pendingTap = false
        tapDir = if (lastV >= 0) 1 else -1
        tapVel = abs(lastV)
        log?.invoke("TAP t=%.3f".format(t))
    }

    private fun isKnown(c: Comp): Boolean {
        ct?.let { if (c.colorDist(it[0], it[1], it[2]) <= COLOR_TOL) return true }
        for (k in known) if (c.colorDist(k[0], k[1], k[2]) <= COLOR_TOL) return true
        return false
    }

    private fun remember(r: Int, g: Int, b: Int) {
        for (k in known) if (abs(k[0] - r) <= 2 && abs(k[1] - g) <= 2 && abs(k[2] - b) <= 2) return
        known.addLast(intArrayOf(r, g, b))
        while (known.size > 24) known.removeFirst()
    }

    /**
     * Traite une image prise à l'instant [t] (secondes). Renvoie l'instant (même horloge) où il faut toucher
     * l'écran, ou null ; la valeur est recalculée à chaque image tant que le toucher n'est pas parti.
     */
    @Synchronized
    fun onFrame(t: Double, comps: List<Comp>): Double? {
        // nouvelle partie : le socle gris apparaît
        val socle = comps.firstOrNull { it.gray && it.area >= 10000 }
        if (socle != null && (ct == null || !(abs(ct!![0] - ct!![1]) <= 4 && abs(ct!![1] - ct!![2]) <= 4))) {
            resetGame()
            ct = intArrayOf(socle.r, socle.g, socle.b)
            remember(socle.r, socle.g, socle.b)
            log?.invoke("NOUVELLE PARTIE (socle gris) t=%.3f".format(t))
        }
        if (state == State.DONE) return null

        updateStatics(t, comps)

        val cands = if (t < lockUntil) emptyList() else comps.filter { !it.gray && it.area >= MIN_SLAB_AREA && !isKnown(it) }
        val prevRgb = slabRgb
        // on reste sur le bloc déjà suivi (même couleur) plutôt que sur le plus gros
        val slabC = (if (prevRgb != null) cands.filter { it.colorDist(prevRgb[0], prevRgb[1], prevRgb[2]) <= 4 }.maxByOrNull { it.area } else null)
            ?: cands.maxByOrNull { it.area }

        if (t >= nextDiag) {
            nextDiag = t + 2.0
            val shapes = comps.sortedByDescending { it.area }.take(4).joinToString(" ") { "[${it.xmin}-${it.xmax} y${it.ymin}-${it.ymax} a${it.area} (${it.r},${it.g},${it.b})]" }
            log?.invoke("ÉTAT score=%d tour=%s ct=%s bloc=%s échantillons=%d vitesse=%.0f rms=%.1f latence=%.0f/%.0f ms formes=%s".format(
                score, if (hasTower) "%.0f..%.0f".format(tx0, tx1) else "inconnue", ct?.joinToString(",") ?: "?",
                slabC?.let { "${it.xmin}-${it.xmax}" } ?: "aucun", ns, lastV, dRms, latLeft * 1000, latRight * 1000, if (slabC == null) shapes else "-"))
        }

        // sommet de la tour : mesuré tant que le bloc mobile ne le recouvre pas
        val c0 = ct
        if (c0 != null) {
            val tower = comps.filter { it.colorDist(c0[0], c0[1], c0[2]) <= COLOR_TOL }
            if (tower.isNotEmpty()) {
                val big = tower.maxOf { it.area }
                val top = tower.filter { it.area >= 0.25 * big }.minByOrNull { it.ymin }!!
                // le bloc mobile peut cacher une partie du sommet : on ne mesure que s'il est loin
                val overlap = slabC != null &&
                    slabC.xmax >= top.xmin - 20 && slabC.xmin <= top.xmax + 20 && slabC.ymax >= top.ymin - 60 && slabC.ymin <= top.ymax + 20
                // pas encore de mesure propre (bloc mobile toujours au-dessus) : on garde la plus complète vue jusque-là
                if (!frozenT && (!overlap || top.area > bestArea)) {
                    tx0 = top.xmin.toDouble()
                    tx1 = top.xmax.toDouble()
                    hasTower = true
                    bestArea = if (overlap) top.area else 0
                }
            }
        }

        if (slabC == null) {
            debug?.invoke("t=%.3f pas de bloc hasT=%s T=%.0f..%.0f ct=%s frozen=%s comps=%d".format(t, hasTower, tx0, tx1, ct?.joinToString(","), frozenT, comps.size))
            ns = 0
            slabRgb = null
            staticRun = 0
            if (!tapTime.isNaN() && t - tapTime > 1.0) tapTime = Double.NaN
            status = if (hasTower) "Score estimé $score — j'attends le bloc" else "En attente du jeu…"
            return null
        }

        // ---- échantillon du bloc mobile ----
        val clipL = slabC.xmin <= 1
        val clipR = slabC.xmax >= w - 2
        val towerW = if (hasTower) tx1 - tx0 else 0.0
        if (!clipL && !clipR) {
            val ww = (slabC.xmax - slabC.xmin).toDouble()
            slabW = if (slabW <= 0 || ns == 0) ww else 0.7 * slabW + 0.3 * ww
        } else if (slabW <= 0) slabW = towerW
        if (clipL && clipR) { ns = 0; return null }
        val cs = when {
            clipL -> slabC.xmax - slabW / 2
            clipR -> slabC.xmin + slabW / 2
            else -> (slabC.xmin + slabC.xmax) / 2.0
        }
        val prev = slabRgb
        val colorJump = prev != null && slabC.colorDist(prev[0], prev[1], prev[2]) > 4
        val posJump = ns > 0 && abs(cs - sc[ns - 1]) > 45
        if (colorJump || posJump) { ns = 0; staticRun = 0 }
        slabRgb = intArrayOf(slabC.r, slabC.g, slabC.b)

        val moved = if (ns > 0) cs - sc[ns - 1] else 99.0
        // immobile = reste près d'une position de référence (le bloc posé « tremble » de quelques pixels) ; le sommet
        // monte / descend un peu quand la caméra bouge, d'où la tolérance en y
        if (ns > 0 && abs(cs - staticRef) <= 6.0 && abs(slabC.ymin - lastY) <= 4.0) {
            staticRun++
        } else {
            staticRun = 0
            staticRef = cs
            staticSince = t
        }
        lastY = slabC.ymin.toDouble()
        // l'image arrive parfois deux fois de suite (même position) : on ne duplique pas l'échantillon
        if (ns > 0 && t - st[ns - 1] < 0.004) return null
        if (ns == MAXS) {
            System.arraycopy(st, 1, st, 0, MAXS - 1)
            System.arraycopy(sc, 1, sc, 0, MAXS - 1)
            ns--
        }
        st[ns] = t
        sc[ns] = cs
        ns++

        // ---- le bloc s'est arrêté : posé ----
        if (staticRun >= 1 && t - staticSince >= 0.10 && abs(lastV) > 150 && hasTower && !clipL && !clipR && abs(cs - (tx0 + tx1) / 2) < 0.9 * (tx1 - tx0)) {
            onLanded(t, slabC)
            return null
        }

        debug?.invoke("t=%.3f slab x%d-%d y%d-%d a%d cs=%.1f ns=%d run=%d lastV=%.0f hasT=%s T=%.0f..%.0f".format(t, slabC.xmin, slabC.xmax, slabC.ymin, slabC.ymax, slabC.area, cs, ns, staticRun, lastV, hasTower, tx0, tx1))
        // ---- régression ----
        val fit = fit() ?: run { status = "Score estimé $score — je suis le bloc…"; return null }
        val v = fit.v
        dRms = fit.rms
        if (fit.rms < 3.0 && abs(v) > abs(vSeen) && abs(v) < 900) vSeen = v
        if (lastV == 0.0 || (abs(v) >= 0.7 * abs(lastV) && abs(v) > 150)) lastV = v
        if (abs(v) < 150 || fit.rms > 3.0 || !hasTower) return null
        // le sommet de la tour a la taille du bloc : sinon c'est autre chose (un décor, un effet) qu'on a pris pour la tour
        if (slabW > 0 && (tx1 - tx0) < 0.7 * slabW && abs(slabC.xmax - slabC.xmin) < 1.3 * slabW) {
            log?.invoke("TOUR DOUTEUSE %.0f..%.0f (bloc de %.0f px) : j'oublie cette couleur".format(tx0, tx1, slabW))
            ct?.let { ignored.add(it) }
            ct = null
            hasTower = false
            frozenT = false
            return null
        }
        if (!tapTime.isNaN() && t - tapTime < 0.9) return null     // toucher déjà envoyé : on attend la pose
        val tc = (tx0 + tx1) / 2
        val csNow = fit.at(st[ns - 1])
        val tau = (tc - csNow) / v
        if (abs(moved) > 2.5) { lastMoveT = st[ns - 1]; lastAlign = st[ns - 1] + tau; lastAlignV = v }
        val lat = if (v > 0) latRight else latLeft

        val target = settings.targetScore
        val ending = target > 0 && score >= target
        if (ending) {
            if (!settings.endOnTarget) {
                state = State.DONE
                status = "Objectif atteint : $score blocs. Je m'arrête."
                return null
            }
            status = "Objectif atteint ($score) : je rate volontairement les blocs pour finir la partie."
            // rater : on touche quand le bloc est loin de la tour (le plus loin possible, ~410 px de chaque côté)
            if (abs(csNow - tc) >= min(1.1 * max(towerW, slabW), 330.0) && (tapTime.isNaN() || t - tapTime > 0.9)) {
                pendingTap = true
                return t
            }
            return null
        }
        status = "Score estimé $score — latence %.0f ms".format(lat * 1000)
        if (tau < -0.01 || tau > 1.5) return null
        val tTap = st[ns - 1] + tau - lat
        if (tTap > t + 0.18) return null
        aimV = abs(v)
        if (!pendingTap) log?.invoke("PRÉVU toucher dans %.0f ms : centre bloc %.0f -> tour %.0f, vitesse %.0f px/s, rms %.1f, latence %.0f ms, bloc %.0f px / tour %.0f px".format(
            (max(tTap, t) - t) * 1000, csNow, tc, v, fit.rms, lat * 1000, slabW, towerW))
        pendingTap = true
        return max(tTap, t)
    }

    /**
     * Le bloc s'est arrêté, rogné : il devient le sommet de la tour. Le bloc est rogné de la valeur du décalage,
     * qu'on lit donc en comparant le sommet de la tour avant / après la pose.
     */
    private fun onLanded(t: Double, slab: Comp) {
        val dir = if (lastV >= 0) 1 else -1
        val ours = !tapTime.isNaN() && t - tapTime < 1.2
        val right = slab.xmin - tx0          // le bord gauche a avancé : le bloc était en avance vers la droite
        val left = tx1 - slab.xmax           // le bord droit a reculé : le bloc était décalé vers la gauche
        val e = dir * (right - left)         // > 0 : le bloc a dépassé la cible (toucher trop tard)
        lastError = e
        log?.invoke("   prédiction : dernier mouvement t=%.3f, alignement prévu à t=%.3f => écart prédit %.1f px (e mesuré %.1f)".format(lastMoveT, lastAlign, (lastMoveT - lastAlign) * lastAlignV * dir, e))
        log?.invoke("POSE t=%.3f dir=%d vmax=%.0f v=%.0f e=%.1fpx tour %.0f..%.0f -> %d..%d (slabW=%.0f) ours=%s".format(
            t, dir, vSeen, lastV, e, tx0, tx1, slab.xmin, slab.xmax, slabW, ours))
        if (ours && abs(e) < 0.4 * (tx1 - tx0)) {
            // décalage en secondes, avec la vitesse du bloc au moment de la visée (pas celle d'après la pose)
            val speed = max(150.0, if (aimV > 0) aimV else abs(lastV))
            val lim = if (nLearn < 4) 0.15 else 0.03
            val dl = (e / speed).coerceIn(-lim, lim)
            // la latence (envoi du toucher -> prise en compte) est physiquement la même dans les deux sens : on met à jour
            // les deux, le sens concerné davantage ; gain fort au début, faible ensuite (gigue d'un toucher)
            // deux erreurs de suite dans le même sens : la latence dérive, on rattrape plus vite
            val streak = abs(e) > 6 && abs(lastE) > 6 && e * lastE > 0
            val g = max(if (streak) 0.4 else 0.15, 1.0 / (nLearn + 1.5))
            if (dir > 0) {
                latRight += g * dl
                latLeft += 0.6 * g * dl
            } else {
                latLeft += g * dl
                latRight += 0.6 * g * dl
            }
            val mean = (latLeft + latRight) / 2
            latLeft = latLeft.coerceIn(mean - 0.012, mean + 0.012).coerceIn(0.02, 0.45)
            latRight = latRight.coerceIn(mean - 0.012, mean + 0.012).coerceIn(0.02, 0.45)
            nLearn++
            if (abs(e) > 6) lastE = e else if (abs(e) <= 4) lastE = 0.0
        }
        tx0 = slab.xmin.toDouble()
        tx1 = slab.xmax.toDouble()
        frozenT = true
        bestArea = 0
        lockUntil = t + 0.10
        lastV = 0.0
        aimV = 0.0
        vSeen = 0.0
        ns = 0
        staticRun = 0
        tapTime = Double.NaN
        pendingTap = false
        score++
        ct?.let { remember(it[0], it[1], it[2]) }
        ct = intArrayOf(slab.r, slab.g, slab.b)
        slabRgb = null
        status = "Score estimé $score"
    }

    // ---- statiques : les blocs qui ne bougent pas sont des blocs posés, pas le bloc mobile ----
    private fun updateStatics(t: Double, comps: List<Comp>) {
        val seen = BooleanArray(statics.size)
        var bestArea = 0
        var bestComp: Comp? = null
        for (c in comps) {
            if (c.area < 1500 || c.gray) continue
            var m = -1
            for (i in statics.indices) {
                val s = statics[i]
                if (c.colorDist(s.r, s.g, s.b) <= 3 && abs(c.cx - s.cx) <= 1.5) { m = i; break }
            }
            if (m < 0) {
                statics.add(Static(c.r, c.g, c.b, c.cx, t))
            } else {
                seen[m] = true
                val s = statics[m]
                if (t - s.t0 >= 0.5) {
                    if (!isKnown(c)) remember(c.r, c.g, c.b)
                    val ign = ignored.any { c.colorDist(it[0], it[1], it[2]) <= COLOR_TOL }
                    if (!ign && c.area > bestArea) { bestArea = c.area; bestComp = c }
                }
            }
        }
        // en cours de partie : la tour est le plus grand bloc immobile
        if (ct == null && bestComp != null) ct = intArrayOf(bestComp.r, bestComp.g, bestComp.b)
        // oublie les statiques qui ont bougé ou disparu
        var i = statics.size - 1
        while (i >= 0) {
            if (i < seen.size && !seen[i]) statics.removeAt(i)
            i--
        }
    }

    private class Fit(val v: Double, val c0: Double, val t0: Double, val rms: Double) {
        fun at(t: Double) = c0 + v * (t - t0)
    }

    private fun fit(): Fit? {
        if (ns < 6) return null
        val tLast = st[ns - 1]
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until ns) { if (tLast - st[i] > 0.3) continue; n++; sx += st[i] - tLast; sy += sc[i] }
        if (n < 6) return null
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until ns) { if (tLast - st[i] > 0.3) continue; val dx = st[i] - tLast - mx; sxx += dx * dx; sxy += dx * (sc[i] - my) }
        if (sxx < 1e-6) return null
        val v = sxy / sxx
        val c0 = my - v * mx           // position à t = tLast
        var se = 0.0
        for (i in 0 until ns) { if (tLast - st[i] > 0.3) continue; val e = sc[i] - (c0 + v * (st[i] - tLast)); se += e * e }
        return Fit(v, c0, tLast, Math.sqrt(se / n))
    }
}
