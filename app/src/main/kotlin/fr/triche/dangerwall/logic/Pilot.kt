package fr.triche.dangerwall.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class PilotSettings(
    /** Marge de sécurité (px) ajoutée autour de la balle par rapport aux pics et au terrain. */
    val margin: Double = 26.0,
    /** S'arrêter (ne plus taper) après ce nombre de rebonds ; 0 = jamais. */
    val stopAt: Int = 0,
)

/**
 * Pilote de "Dangerwall". À chaque image : suit la balle (direction, vitesse, hauteur, physique des sauts),
 * choisit le trou à viser sur le mur d'en face, et demande au planificateur s'il faut taper maintenant.
 * Unités : pixels de l'image, 1 tick = 1/60 s.
 */
class Pilot(private val width: Int, var settings: PilotSettings = PilotSettings()) {
    companion object {
        const val VX0 = 4.43            // vitesse horizontale au premier rebond (px/tick)
        const val G_REF = 0.36          // gravité à vitesse du jeu 1 (px/tick²)
        const val J_REF = 7.1           // vitesse de saut à vitesse du jeu 1 (px/tick)
        const val G_EXP = 0.9
        const val J_EXP = 0.85
        const val HALF0 = 52.0          // demi-hauteur de la balle
        const val HALF_MAX = 66.0       // demi-hauteur maximale (carré tourné de 45°)
        const val SPIKE_HALF = 40.5     // demi-hauteur d'un pic (37,5) + 3 px de marge
        const val PLAN_HORIZON = 90.0   // loin du mur, on vise un point virtuel à cette distance
        const val MIN_TAP_GAP = 2.0
        const val SAFE_BAND_Y = 1000.0   // la bande en dessous est visée en priorité : aucun pic n'y est descendu dans la vidéo
        const val CEILING_Y = 640.0      // au-dessus, on ne tape jamais : la balle redescend d'elle-même
        const val MIN_TAP_SPACING = 4.0  // ticks mini entre deux taps (anti-emballement)
    }

    var status = "En attente d'une partie…"
        private set
    var bounces = 0
        private set
    var latencyTicks = 5.0
        private set
    var tapsSent = 0
        private set

    // diagnostics (tests)
    val dbgY get() = y
    val dbgV get() = v
    val dbgG get() = dyn().g
    val dbgJ get() = dyn().j
    var dbgGapLo = 0.0
        private set
    var dbgGapHi = 0.0
        private set
    var dbgArrival = 0.0
        private set
    var dbgPredY = 0.0
        private set
    var dbgCost = 0.0
        private set
    var dbgNextTap = -1
        private set
    var dbgCands = ""
        private set
    var dbgHard = 0.0
        private set
    var dbgProblem: Problem? = null
        private set
    var dbgAll: List<Problem> = emptyList()
        private set
    var dbgHardHistory = DoubleArray(40)
        private set
    var dbgArrHistory = DoubleArray(40)
        private set

    // ---- suivi horizontal ----
    private val hT = DoubleArray(6)
    private val hCx = DoubleArray(6)
    private val hXmin = DoubleArray(6)
    private val hXmax = DoubleArray(6)
    private var hn = 0
    private var dir = 0
    private var vx = 0.0
    private var lostFrames = 0

    // ---- suivi vertical ----
    private var haveState = false
    private var y = 0.0
    private var v = 0.0
    private var lastTick = 0.0
    private var half = HALF0
    private var ghostK = 1.0
    private var lastYm = 0.0
    private var prevYm = 0.0
    private var prevTick = 0.0

    // ---- physique des sauts apprise ----
    private var gRef = G_REF
    private var jRef = J_REF
    private val segTau = DoubleArray(64)
    private val segY = DoubleArray(64)
    private var segN = 0
    private var segStart = 0.0
    private var segS = 1.0

    // ---- taps en attente ----
    private val pendingIssue = ArrayList<Double>()
    private var lastIssue = -100.0

    fun reset() {
        hn = 0; dir = 0; vx = 0.0; haveState = false; lostFrames = 0
        bounces = 0; segN = 0; pendingIssue.clear(); lastIssue = -100.0
        status = "En attente d'une partie…"
    }

    private fun speedFactor() = (vx / VX0).coerceIn(0.5, 6.0)

    private fun dyn(): Dyn {
        val s = speedFactor()
        return Dyn(gRef * s.pow(G_EXP), jRef * s.pow(J_EXP))
    }

    /** @return true s'il faut taper maintenant. */
    fun step(t: Double, scene: Scene?): Boolean {
        if (scene == null) {
            if (hn > 0 || haveState) {
                lostFrames++
                if (lostFrames > 30) reset()
            }
            return false
        }
        val ball = scene.ball
        if (ball == null) {
            lostFrames++
            if (lostFrames > 30) reset()
            return false
        }
        lostFrames = 0
        val tick = t * 60.0

        // ---------- horizontal ----------
        if (hn == hT.size) {
            for (i in 1 until hn) { hT[i - 1] = hT[i]; hCx[i - 1] = hCx[i]; hXmin[i - 1] = hXmin[i]; hXmax[i - 1] = hXmax[i] }
            hn--
        }
        hT[hn] = tick; hCx[hn] = ball.cx.toDouble(); hXmin[hn] = ball.xMin.toDouble(); hXmax[hn] = ball.xMax.toDouble()
        hn++
        if (hn < 3) return false
        val back = max(0, hn - 4)
        val dTicks = hT[hn - 1] - hT[back]
        if (dTicks < 0.5) return false
        val dcx = (hCx[hn - 1] - hCx[back]) / dTicks
        val newDir = if (dcx > 0.8) 1 else if (dcx < -0.8) -1 else 0
        if (newDir != 0) {
            if (dir != 0 && newDir != dir) bounces++
            dir = newDir
        }
        if (dir == 0) return false
        val lead = if (dir < 0) hXmin else hXmax
        val leadSpeed = abs(lead[hn - 1] - lead[back]) / dTicks
        vx = if (vx <= 0.0) leadSpeed else 0.7 * vx + 0.3 * leadSpeed.coerceIn(0.85 * vx, 1.15 * vx)
        if (vx < 1.0) return false

        val dist = if (dir < 0) ball.xMin.toDouble() else (width - 1 - ball.xMax).toDouble()
        val arrival = max(0.0, dist) / (vx * 0.985)

        // ---------- vertical ----------
        val dt = if (haveState) max(0.3, tick - lastTick) else 1.0
        val d = dyn()
        // Le carré a une boîte englobante carrée : son demi-côté se lit sur la largeur, une fois retiré l'étirement
        // horizontal des fantômes (proportionnel à la vitesse, appris quand la balle ne bouge pas verticalement).
        val width = (ball.xMax - ball.xMin).toDouble()
        if (haveState && abs(v) < 2.0 && vx > 2.0) {
            val gk = (width - (ball.yMax - ball.yMin)) / vx
            ghostK += 0.1 * (gk.coerceIn(0.0, 4.0) - ghostK)
        }
        val halfNow = ((width - ghostK * vx) / 2.0).coerceIn(44.0, HALF_MAX)
        half = halfNow
        val ym = when {
            haveState && v > 3 -> ball.yMax - halfNow
            haveState && v < -3 -> ball.yMin + halfNow
            else -> ball.cy.toDouble()
        }
        if (!haveState) {
            y = ym; v = 0.0; haveState = true; lastTick = tick; lastYm = ym
            segN = 0; segStart = tick; segS = speedFactor()
        } else {
            val yPred = y + v * dt + 0.5 * d.g * dt * dt
            val vInst = (ym - lastYm) / dt
            val e = ym - yPred
            val pending = pendingIssue.isNotEmpty() && tick >= pendingIssue[0] + 0.4 * latencyTicks
            val thr = if (pending) 0.30 * d.j else 0.9 * d.j
            if (e < -max(2.5, thr) && vInst < -0.4 * d.j) {
                // un tap vient de prendre effet
                finishSegment(tick)
                if (pendingIssue.isNotEmpty()) {
                    val measured = tick - pendingIssue.removeAt(0)
                    latencyTicks = (0.7 * latencyTicks + 0.3 * measured).coerceIn(2.0, 14.0)
                }
                y = ym
                v = -d.j
                segN = 0; segStart = tick; segS = speedFactor()
            } else {
                y = yPred + 0.7 * e
                v = v + d.g * dt + 0.25 * e / dt
            }
            // un tap perdu ne reste pas en attente éternellement
            while (pendingIssue.isNotEmpty() && tick > pendingIssue[0] + 3 * latencyTicks + 3) pendingIssue.removeAt(0)
            prevYm = lastYm
            prevTick = lastTick
            lastTick = tick
            lastYm = ym
        }
        if (segN < segTau.size) { segTau[segN] = tick - segStart; segY[segN] = ym; segN++ }

        if (settings.stopAt > 0 && bounces >= settings.stopAt) {
            status = "Score visé atteint ($bounces rebonds) : le bot ne tape plus"
            return false
        }

        // ---------- choix du trou et planification ----------
        val targetSpikes = if (dir < 0) scene.left else scene.right
        val otherSpikes = if (dir < 0) scene.right else scene.left
        val topB = scene.fieldTop + HALF_MAX + settings.margin * 0.8
        val botB = scene.fieldBottom - HALF_MAX - settings.margin * 0.8
        val window = Detector.SPIKE_DEPTH / vx + 1.0
        val far = arrival > PLAN_HORIZON
        val slack = if (far) 140.0 else 0.0
        val pending = DoubleArray(pendingIssue.size) { max(0.5, pendingIssue[it] + latencyTicks - tick) }

        val candidates = gaps(targetSpikes, topB, botB)
        val otherGaps = gaps(otherSpikes, topB, botB)
        val nextBest = otherGaps.filter { it.width >= 90.0 }.maxByOrNull { it.center() } ?: otherGaps.firstOrNull()
        var bestPlan: Plan? = null
        var bestGap: Gap? = null
        var bestCost = Double.MAX_VALUE
        var bestProblem: Problem? = null
        val allProblems = ArrayList<Problem>()
        val dbg = StringBuilder()
        // candidats : le trou le plus large, et le plus bas qui reste assez large (les pics ne descendent jamais très bas)
        val lowest = candidates.filter { it.width >= 90.0 }.maxByOrNull { it.center() }
        val tryList = ArrayList<Gap>()
        for (gp in candidates.take(2)) tryList.add(gp)
        if (lowest != null && tryList.none { it === lowest }) tryList.add(lowest)
        for (gp in tryList) {
            // dans un grand trou on se tient plutôt vers le bas : c'est là que les pics n'arrivent presque jamais
            val pref = if (gp.width > 150.0) gp.lo + 0.8 * gp.width else gp.center()
            val p = Problem(
                y0 = y, v0 = v, pending = pending, latency = latencyTicks, dyn = d,
                arrival = if (far) PLAN_HORIZON else arrival,
                window = if (far) 0.0 else window,
                lo = max(topB, gp.lo - slack) + (if (gp.width > 60) 8.0 else 0.0), hi = min(botB, gp.hi + slack) - (if (gp.width > 60) 8.0 else 0.0),
                pref = pref, yMin = topB, yMax = botB,
                nextLo = nextBest?.lo ?: 0.0, nextHi = nextBest?.hi ?: 0.0,
                nextTime = if (far || nextBest == null) 0.0 else 624.0 / (vx + 0.25),
                nextWindow = Detector.SPIKE_DEPTH / (vx + 0.25) + 1.0,
            )
            allProblems.add(p)
            val plan = Planner.plan(p)
            // légère préférence pour le trou déjà visé (évite de changer d'avis à chaque image)
            // d'abord la faisabilité (coût dur), puis le trou le plus large, puis le trou déjà visé
            val c = plan.hard + 0.6 * (120.0 - min(gp.width, 120.0)) - 0.03 * gp.center() - (if (abs(gp.center() - lastGapCenter) < 25) 1.5 else 0.0) + 0.01 * plan.cost
            dbg.append("[%.0f..%.0f c=%.1f]".format(gp.lo, gp.hi, plan.cost))
            if (c < bestCost) { bestCost = c; bestPlan = plan; bestGap = gp; bestProblem = p }
        }
        val gap = bestGap ?: Gap((topB + botB) / 2, (topB + botB) / 2, 0.0)
        dbgProblem = bestProblem
        dbgAll = allProblems
        val plan = bestPlan ?: Plan(false, 0.0, -1, 0, 0.0, 0.0)
        lastGapCenter = gap.center()
        dbgCands = dbg.toString()
        dbgHard = plan.hard
        System.arraycopy(dbgHardHistory, 1, dbgHardHistory, 0, 39); dbgHardHistory[39] = plan.hard
        System.arraycopy(dbgArrHistory, 1, dbgArrHistory, 0, 39); dbgArrHistory[39] = arrival
        dbgGapLo = gap.lo; dbgGapHi = gap.hi; dbgArrival = arrival; dbgPredY = plan.arrivalY; dbgCost = plan.cost; dbgNextTap = plan.firstTap
        status = "Rebonds : $bounces • vitesse %.1f px/tick • trou %.0f–%.0f • taps %d".format(vx, gap.lo, gap.hi, tapsSent)
        // Garde-fous indépendants du modèle : jamais de tap en haut de l'écran, ni si la balle monte déjà vite,
        // ni trop vite après le précédent (un tap perdu ou mal vu ne doit pas emballer la balle vers le plafond).
        val rising = (lastYm - prevYm) / max(0.3, lastTick - prevTick) < -0.5 * d.j
        val tooHigh = y < CEILING_Y && v < 0.5 * d.j
        val tooSoon = tick - lastIssue < MIN_TAP_SPACING
        if (plan.tapNow && !rising && !tooHigh && !tooSoon && tick - lastIssue >= MIN_TAP_GAP && pendingIssue.isEmpty()) {
            lastIssue = tick
            pendingIssue.add(tick)
            tapsSent++
            return true
        }
        return false
    }

    /**
     * Fin d'un saut : apprend la physique. La vitesse de saut J vient de la pente des 10 premiers ticks après le tap
     * (une régression linéaire, une fois retirée la gravité connue), la gravité de la courbure du saut entier.
     */
    private fun finishSegment(tick: Double) {
        if (segN < 6) return
        val d = dyn()
        val s = segS
        // --- J : y - g τ²/2 = a + b τ sur les premiers ticks ---
        var n = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until segN) {
            val x = segTau[i]
            if (x > 10.0) break
            val yy = segY[i] - 0.5 * d.g * x * x
            n++; sx += x; sy += yy; sxx += x * x; sxy += x * yy
        }
        if (n >= 5 && sxx * n - sx * sx > 1e-6) {
            val b = (n * sxy - sx * sy) / (n * sxx - sx * sx)
            val jMeas = (-b + 0.7 * d.g) / s.pow(J_EXP)
            if (jMeas > 0.5 * J_REF && jMeas < 3.0 * J_REF) jRef = 0.7 * jRef + 0.3 * jMeas
        }
        // --- g : courbure sur le saut entier (au moins 16 ticks) ---
        if (segN >= 10 && segTau[segN - 1] - segTau[0] >= 16.0) {
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s4 = 0.0
            var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
            var m22 = 0.0; var m23 = 0.0
            for (i in 0 until segN) {
                val x = segTau[i]
                val x2 = x * x * 0.5
                s0 += 1.0; s1 += x; s2 += x2; s4 += x2 * x2
                t0 += segY[i]; t1 += segY[i] * x; t2 += segY[i] * x2
                m22 += x * x; m23 += x * x * x * 0.5
            }
            val sol = solve3(s0, s1, s2, s1, m22, m23, s2, m23, s4, t0, t1, t2)
            if (sol != null) {
                val gMeas = sol[2] / s.pow(G_EXP)
                if (gMeas > 0.5 * G_REF && gMeas < 2.5 * G_REF) gRef = 0.8 * gRef + 0.2 * gMeas
            }
        }
    }

    private fun solve3(a11: Double, a12: Double, a13: Double, a21: Double, a22: Double, a23: Double, a31: Double, a32: Double, a33: Double, b1: Double, b2: Double, b3: Double): DoubleArray? {
        val det = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) + a13 * (a21 * a32 - a22 * a31)
        if (abs(det) < 1e-9) return null
        val x1 = (b1 * (a22 * a33 - a23 * a32) - a12 * (b2 * a33 - a23 * b3) + a13 * (b2 * a32 - a22 * b3)) / det
        val x2 = (a11 * (b2 * a33 - a23 * b3) - b1 * (a21 * a33 - a23 * a31) + a13 * (a21 * b3 - b2 * a31)) / det
        val x3 = (a11 * (a22 * b3 - b2 * a32) - a12 * (a21 * b3 - b2 * a31) + b1 * (a21 * a32 - a22 * a31)) / det
        return doubleArrayOf(x1, x2, x3)
    }

    /** Un trou : intervalle sûr pour le centre de la balle ([lo], [hi]) et espace brut entre les pics ([raw]). */
    class Gap(val lo: Double, val hi: Double, val raw: Double, val loRelaxed: Double = lo, val hiRelaxed: Double = hi) {
        fun center() = (lo + hi) / 2
        val width get() = hi - lo
    }

    private var lastGapCenter = -1000.0

    /**
     * Trous entre les pics du mur, du plus spacieux au moins spacieux. La marge de sécurité demandée est réduite
     * quand le trou est étroit (jusqu'à 4 px), pour toujours viser le milieu du trou.
     */
    private fun gaps(spikes: List<Float>, topB: Double, botB: Double): List<Gap> {
        val sorted = spikes.sorted()
        val bounds = ArrayList<DoubleArray>() // [bas du pic précédent ou plafond, haut du pic suivant ou sol]
        var prev = topB - HALF_MAX - 4.0
        for (c in sorted) {
            bounds.add(doubleArrayOf(prev, c - SPIKE_HALF))
            prev = c + SPIKE_HALF
        }
        bounds.add(doubleArrayOf(prev, botB + HALF_MAX + 4.0))
        val out = ArrayList<Gap>()
        val hv = max(half, HALF0)
        for (b in bounds) {
            val raw = b[1] - b[0]
            // zone possible du centre : chaque bord est à au moins (demi-balle + marge m) ; m maximal dans [4, margin]
            var m = settings.margin
            var lo = 0.0
            var hi = -1.0
            while (m >= 4.0) {
                lo = max(topB, b[0] + hv + m)
                hi = min(botB, b[1] - hv - m)
                if (hi - lo >= 50.0) break
                m -= 4.0
            }
            if (hi < lo) {
                val mid = (max(topB, b[0] + hv) + min(botB, b[1] - hv)) / 2
                if (min(botB, b[1]) - max(topB, b[0]) < 1.6 * hv) continue
                lo = mid; hi = mid
            }
            // intervalle relâché (marge minimale), utilisé quand aucun plan ne tient avec la marge normale
            val loR = max(topB, b[0] + hv + 3.0)
            val hiR = min(botB, b[1] - hv - 3.0)
            out.add(if (hiR >= loR) Gap(lo, hi, raw, min(lo, loR), max(hi, hiR)) else Gap(lo, hi, raw))
        }
        out.sortByDescending { min(it.hi - it.lo, 260.0) + 0.0001 * it.raw }
        if (out.isEmpty()) out.add(Gap((topB + botB) / 2, (topB + botB) / 2, 0.0))
        return out
    }
}
