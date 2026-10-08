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
    /** Viser le milieu de la fenêtre de prise en compte du toucher et ne toucher que quand une image tombe pile sur la tour. */
    val precisionMode: Boolean = true,
    /** Résidu maximal toléré (px) entre le bloc, à l'image visée, et le centre de la tour. */
    val maxResidual: Double = 1.5,
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

    // cadence des images du jeu
    private val pd = DoubleArray(64)
    private var pn = 0
    private var lastFrameT = Double.NaN
    private var gridP = 0.0

    // précision : après calibrage du délai « envoi -> image du jeu qui prend le toucher », on vise le milieu de la fenêtre
    private val obsY = ArrayList<Double>()   // écarts (heure de l'image où le bloc s'est figé) − (heure d'envoi du toucher)
    private var calibrated = false
    private var dHat = 0.0
    private val flips = ArrayList<Int>()
    private var aimLocked = false
    private var aimResidual = 0.0
    private var aimTj = 0.0
    private var lastLandT = Double.NaN
    private var predBias = 0.0               // écart moyen (réel − prévu) : la droite ajustée sous-estime un bloc qui accélère
    private var lastMoveStamp = Double.NaN
    private var needFar = false              // après un toucher abandonné : on attend le prochain passage du bloc
    private var frozenT2 = Double.NaN
    private var frozenCs = Double.NaN        // position du bloc à l'image où il s'est figé (avant le rognage)
    private var lastMoveCs = Double.NaN

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
        const val MAXS = 36
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
    /** Vrai tant que le bot vise la précision (faux quand il rate volontairement après le score visé). */
    val precise: Boolean get() = !(settings.targetScore > 0 && score >= settings.targetScore)

    @Synchronized
    fun onTapped(t: Double) {
        tapTime = t
        pendingTap = false
        tapDir = if (lastV >= 0) 1 else -1
        tapVel = abs(lastV)
        log?.invoke("TAP t=%.3f (visée verrouillée : %s, résidu visé %.1f px, image visée à t=%.4f)".format(t, aimLocked, aimResidual, aimTj))
    }

    /** Le toucher programmé n'est pas parti (retard d'exécution trop grand) : on attend le prochain passage du bloc. */
    @Synchronized
    fun onTapAborted() {
        pendingTap = false
        needFar = true
        log?.invoke("TOUCHER ABANDONNÉ (parti en retard) : j'attends le prochain passage du bloc")
    }

    /** Période des images du jeu (moyenne des écarts réguliers entre images) ; 0 tant qu'elle n'est pas fiable. */
    private fun updateGrid(t: Double) {
        if (!lastFrameT.isNaN()) {
            val d = t - lastFrameT
            if (d in 0.003..0.045) {
                pd[pn % pd.size] = d
                pn++
            }
        }
        lastFrameT = t
        val n = minOf(pn, pd.size)
        if (n < 20) { gridP = 0.0; return }
        val sorted = pd.copyOf(n).also { it.sort() }
        val med = sorted[n / 2]
        var sum = 0.0
        var near = 0
        for (i in 0 until n) if (abs(pd[i] - med) < 0.12 * med) { sum += pd[i]; near++ }
        gridP = if (med in 0.0065..0.0215 && near >= 0.6 * n) sum / near else 0.0
    }

    /**
     * Après chaque pose de notre toucher : y = (heure de l'image où le bloc s'est figé) − (heure d'envoi). Le jeu prend le
     * toucher à l'image qui suit son arrivée : y = D + u, D = délai fixe (envoi -> arrivée, + retard de capture) et u ∈ [0 ; 1 image[.
     * Tant que les phases sont au hasard (calibrage), la médiane de y − ½ image donne D. Une fois D connu, on vise le
     * milieu de la fenêtre ; un toucher pris une image trop tôt / trop tard (m ≠ 0) ramène la visée d'une demi-image.
     */
    private fun observeFreeze(t: Double, ours: Boolean) {
        if (!ours || frozenT2.isNaN() || tapTime.isNaN() || gridP <= 0) return
        val y = frozenT2 - tapTime
        if (y !in 0.0..0.5) return
        if (!calibrated) {
            obsY.add(y)
            while (obsY.size > 14) obsY.removeAt(0)
            // D est dans ]y_max − P ; y_min] pour chaque observation : on prend le milieu de l'intersection si elle existe
            val lo = obsY.max() - gridP
            val hi = obsY.min()
            val med = obsY.sorted()[obsY.size / 2] - gridP / 2
            dHat = if (hi > lo) (lo + hi) / 2 else med
            log?.invoke("   calibrage %d/8 : y=%.1f ms, délai estimé %.1f ms (intersection %.1f..%.1f, médiane %.1f)".format(obsY.size, y * 1000, dHat * 1000, lo * 1000, hi * 1000, med * 1000))
            if (obsY.size >= 8 && gridP > 0) {
                calibrated = true
                flips.clear()
                log?.invoke("CALIBRAGE TERMINÉ : délai %.1f ms, pas d'image %.2f ms -> visée au milieu de la fenêtre (%.1f ms)".format(dHat * 1000, gridP * 1000, (dHat + gridP / 2) * 1000))
            }
        } else if (aimLocked) {
            val lq = dHat + gridP / 2
            val m = Math.round((y - lq) / gridP).toInt()
            if (m == 0 && !frozenCs.isNaN()) {
                val eT = (frozenCs - (tx0 + tx1) / 2) * (if (lastV >= 0) 1 else -1)
                predBias += 0.3 * ((eT - aimResidual) - predBias)
                predBias = predBias.coerceIn(-8.0, 8.0)
                log?.invoke("   biais de prévision : réel %.1f px − prévu %.1f px -> biais appris %.1f px".format(eT, aimResidual, predBias))
            }
            flips.add(if (m != 0) 1 else 0)
            while (flips.size > 6) flips.removeAt(0)
            if (m != 0) {
                dHat += m * gridP / 2
                log?.invoke("   TOUCHER PRIS %d IMAGE(S) D'ÉCART : visée ramenée d'une demi-image (délai %.1f ms)".format(m, dHat * 1000))
                if (flips.sum() >= 2) {
                    calibrated = false
                    obsY.clear()
                    log?.invoke("   trop d'écarts : je recalibre le délai")
                }
            }
        }
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

        updateGrid(t)
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
        // instant où le bloc s'arrête : sa position à la dernière image où il bougeait encore (avant la chute et le rognage)
        if (ns > 0 && frozenCs.isNaN()) {
            val step = abs(moved)
            if (step in 2.5..25.0) { lastMoveCs = cs; lastMoveStamp = t }
            else if (step <= 1.5 && !lastMoveCs.isNaN() && abs(lastV) > 150) { frozenCs = lastMoveCs; frozenT2 = lastMoveStamp }
        }
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
        if (needFar) {
            // un toucher a été abandonné : on laisse passer le bloc et on reprend quand il revient de loin
            if (abs(csNow - tc) > 250 && ((csNow - tc) * v < 0)) needFar = false else return null
        }
        if (calibrated && gridP > 0 && settings.precisionMode) {
            // Le jeu avance par pas d'image et prend le toucher à l'image suivante : on choisit l'image où le bloc sera le plus
            // près du centre de la tour, et on envoie le toucher pour qu'il arrive au milieu de la fenêtre de cette image
            // (insensible à une gigue de ± une demi-image). Si aucune image ne tombe assez près (résidu > seuil), on laisse
            // passer : le bloc revient toutes les ~2 s.
            val lq = dHat + gridP / 2
            val tl = st[ns - 1]
            val sgn = if (v > 0) 1 else -1
            val off = sgn * predBias
            var best = -1
            var prevAd = abs(fit.at(tl) - tc + off)
            var j = 1
            while (j <= 60) {
                val tj = tl + j * gridP
                val ad = abs(fit.at(tj) - tc + off)
                if (tj - lq >= t - 0.002) {
                    if (best < 0) {
                        if (ad > prevAd) return null          // déjà passé
                        best = j
                    } else if (ad < abs(fit.at(tl + best * gridP) - tc + off)) best = j else break
                } else if (ad > prevAd && j > 2) break
                prevAd = ad
                j++
            }
            if (best < 0) return null
            val tj = tl + best * gridP
            val rawResid = (fit.at(tj) - tc) * sgn
            val resid = rawResid + predBias
            // pour lancer un toucher le résidu doit être petit ; une fois lancé, une prévision plus précise peut seulement
            // déplacer la visée (ou l'annuler si elle devient franchement mauvaise)
            if (lastLandT.isNaN()) lastLandT = t
            // garde-fou : si aucun passage n'est assez propre pendant très longtemps (phase du jeu défavorable), on assouplit
            val relax = ((t - lastLandT - 25.0) * 0.1).coerceIn(0.0, 2.5)
            val limit = (if (pendingTap) settings.maxResidual + 1.5 else settings.maxResidual) + relax
            if (abs(resid) > limit) {
                status = "Score estimé $score — j'attends un passage plus propre (résidu %.1f px)".format(resid)
                return null
            }
            val tTap = tj - lq
            // on ne s'engage que dans les dernières ~50 ms avant l'envoi : la prévision est alors la plus précise
            if (tTap > t + 0.05) return null
            debug?.invoke("DECISION t=%.3f image+%d tTap-t=%.1f ms brut=%.1f biais=%.1f resid=%.1f v=%.0f rms=%.2f".format(t, best, (tTap - t) * 1000, rawResid, predBias, resid, v, fit.rms))
            aimV = abs(v)
            aimResidual = rawResid
            aimTj = tj
            aimLocked = true
            if (!pendingTap) log?.invoke("PRÉVU (visée au milieu de la fenêtre) toucher dans %.0f ms, image +%d, pas %.2f ms, résidu %.1f px : centre bloc %.0f -> tour %.0f, vitesse %.0f px/s, rms %.1f, délai %.1f ms".format(
                (max(tTap, t) - t) * 1000, best, gridP * 1000, resid, csNow, tc, v, fit.rms, lq * 1000))
            pendingTap = true
            return max(tTap, t)
        }
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
        // erreur réelle : position du bloc à l'image où il s'est figé (le jeu l'arrondit à « parfait » en dessous de ~4 px,
        // et le rognage ne dit rien alors)
        observeFreeze(t, ours)
        val eTrue = if (!frozenCs.isNaN()) (frozenCs - (tx0 + tx1) / 2) * dir else e
        log?.invoke("   position figée : e réel %.1f px (rognage : %.1f px)".format(eTrue, e))
        if (ours && abs(eTrue) < 0.4 * (tx1 - tx0)) {
            val speed = max(150.0, if (aimV > 0) aimV else abs(lastV))
            run {
                val lim = if (nLearn < 4) 0.15 else 0.03
                val dl = (eTrue / speed).coerceIn(-lim, lim)
                val streak = abs(eTrue) > 6 && abs(lastE) > 6 && eTrue * lastE > 0
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
                if (abs(eTrue) > 6) lastE = eTrue else if (abs(eTrue) <= 4) lastE = 0.0
            }
            nLearn++
        }
        tx0 = slab.xmin.toDouble()
        tx1 = slab.xmax.toDouble()
        frozenT = true
        bestArea = 0
        lockUntil = t + 0.10
        lastV = 0.0
        aimV = 0.0
        lastLandT = t
        frozenCs = Double.NaN
        frozenT2 = Double.NaN
        lastMoveCs = Double.NaN
        lastMoveStamp = Double.NaN
        aimLocked = false
        vSeen = 0.0
        slabW = 0.0     // le bloc suivant a la taille du sommet rogné : on repart de la largeur de la tour
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

    /**
     * Trajectoire ajustée du centre du bloc : droite (c0 + v·dt) ou oscillation sinusoïdale autour du centre de la tour
     * (tc + a·sin ω·dt + b·cos ω·dt) — le bloc accélère en approchant du centre, une droite sous-estime alors sa position
     * future de plusieurs pixels.
     */
    private class Fit(val v: Double, val c0: Double, val t0: Double, val rms: Double,
                      val w: Double = 0.0, val tc: Double = 0.0, val a: Double = 0.0, val b: Double = 0.0) {
        fun at(t: Double): Double {
            val dt = t - t0
            return if (w > 0) tc + a * Math.sin(w * dt) + b * Math.cos(w * dt) else c0 + v * dt
        }
    }

    private fun fit(): Fit? {
        val lin = linearFit() ?: return null
        if (!hasTower || ns < 20) return lin
        val tLast = st[ns - 1]
        val tcn = (tx0 + tx1) / 2
        var first = ns
        for (i in 0 until ns) if (tLast - st[i] <= 0.5) { first = i; break }
        val n = ns - first
        if (n < 20 || tLast - st[first] < 0.3) return lin
        var best: Fit? = null
        var w = 0.5
        while (w <= 2.6) {
            // moindres carrés de x = sc − tc sur (sin ωτ, cos ωτ), τ = t − tLast
            var ss = 0.0; var cc = 0.0; var sc2 = 0.0; var sy = 0.0; var cy = 0.0
            for (i in first until ns) {
                val tau = st[i] - tLast
                val si = Math.sin(w * tau); val ci = Math.cos(w * tau); val y = sc[i] - tcn
                ss += si * si; cc += ci * ci; sc2 += si * ci; sy += si * y; cy += ci * y
            }
            val det = ss * cc - sc2 * sc2
            if (det > 1e-9) {
                val aa = (sy * cc - cy * sc2) / det
                val bb = (cy * ss - sy * sc2) / det
                var se = 0.0
                for (i in first until ns) {
                    val tau = st[i] - tLast
                    val e = (sc[i] - tcn) - (aa * Math.sin(w * tau) + bb * Math.cos(w * tau))
                    se += e * e
                }
                val rms = Math.sqrt(se / n)
                if (best == null || rms < best.rms) best = Fit(aa * w, 0.0, tLast, rms, w, tcn, aa, bb)
            }
            w += 0.04
        }
        // on garde l'oscillation si elle explique au moins aussi bien les images que la droite (sur la même fenêtre)
        return if (best != null && best.rms <= 1.5 * lin.rms + 0.3 && best.rms < 3.0) best else lin
    }

    private fun linearFit(): Fit? {
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
