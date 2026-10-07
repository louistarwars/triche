package fr.triche.dangerwall.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Physique verticale : gravité [g] (px/tick²) et vitesse de saut [j] (px/tick, un tap fixe la vitesse à -j). Un tick = 1/60 s. */
class Dyn(val g: Double, val j: Double)

/**
 * Problème posé à chaque image : où doit se trouver la balle quand elle touche le mur d'en face,
 * et quand faut-il taper pour y arriver sans toucher plafond / sol.
 */
class Problem(
    val y0: Double,
    val v0: Double,
    /** Instants d'effet (ticks à partir de maintenant) des taps déjà envoyés mais pas encore visibles. */
    val pending: DoubleArray,
    /** Délai entre l'envoi d'un tap et son effet visible, en ticks. */
    val latency: Double,
    val dyn: Dyn,
    /** Ticks jusqu'au contact avec le mur. */
    val arrival: Double,
    /** Durée (ticks) avant le contact pendant laquelle les pics menacent la balle. */
    val window: Double,
    /** Intervalle sûr pour le centre de la balle pendant cette fenêtre. */
    val lo: Double,
    val hi: Double,
    /** Hauteur préférée (milieu du trou, ou direction du prochain trou). */
    val pref: Double,
    /** Bornes du centre de la balle pendant tout le trajet (plafond / sol avec marge). */
    val yMin: Double,
    val yMax: Double,
    /** Trou à atteindre à la traversée suivante (intervalle sûr) et durée prévue de cette traversée (ticks) ; 0 = ignoré. */
    val nextLo: Double = 0.0,
    val nextHi: Double = 0.0,
    val nextTime: Double = 0.0,
    val nextWindow: Double = 0.0,
)

class Plan(val tapNow: Boolean, val cost: Double, val firstTap: Int, val taps: Int, val arrivalY: Double, val violation: Double, val hard: Double = 0.0)

object Planner {
    const val MAX_TAPS = 3
    const val MIN_SPACING = 3        // ticks mini entre deux effets de taps
    const val MAX_LOOKAHEAD = 80

    /** Scénarios testés : (décalage de l'instant d'arrivée, décalage de l'effet des taps, erreur de hauteur, échelle du saut). */
    private val SHIFTS = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 1.0),      // nominal
        doubleArrayOf(-1.5, 0.0, 0.0, 1.0),
        doubleArrayOf(1.5, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, -1.0, 0.0, 1.0),
        doubleArrayOf(0.0, 1.0, 0.0, 1.0),
        doubleArrayOf(0.0, 2.5, 0.0, 1.0),      // un tap qui arrive en retard
        doubleArrayOf(0.0, 0.0, 20.0, 1.0),     // la balle est en fait 20 px plus bas
        doubleArrayOf(0.0, 0.0, -20.0, 1.0),    // ... ou 20 px plus haut
        doubleArrayOf(0.0, 0.0, 0.0, 0.93),     // saut 7 % plus faible
        doubleArrayOf(0.0, 0.0, 0.0, 1.07),     // saut 7 % plus fort
    )

    private class Scratch {
        val land = DoubleArray(8)
        val times = DoubleArray(4)
        val ys = DoubleArray(4)
        val vs = DoubleArray(4)
        var boundViol = 0.0
        var arrivalY = 0.0
        var hard = 0.0
        var arrivalV = 0.0
    }

    /** Simule la trajectoire avec les effets de taps [land] (triés) et relève y aux instants [times] ; renvoie le dépassement de bornes. */
    private fun simulate(p: Problem, s: Scratch, n: Int, nt: Int, jScale: Double = 1.0): Double {
        val g = p.dyn.g
        var tc = 0.0
        var y = p.y0
        var v = p.v0
        var li = 0
        var ti = 0
        var viol = 0.0
        val tEnd = s.times[nt - 1]
        while (ti < nt) {
            val nextL = if (li < n) s.land[li] else Double.MAX_VALUE
            val nextT = s.times[ti]
            val te = min(nextL, nextT)
            val dt = max(0.0, te - tc)
            if (dt > 0) {
                if (v < 0) {
                    val ta = -v / g
                    if (ta < dt) viol = max(viol, p.yMin - (y + v * ta + 0.5 * g * ta * ta))
                }
                val ye = y + v * dt + 0.5 * g * dt * dt
                viol = max(viol, max(ye - p.yMax, p.yMin - ye))
                y = ye
                v += g * dt
                tc = te
            }
            if (nextL <= nextT) {
                v = -p.dyn.j * jScale
                li++
            } else {
                s.vs[ti] = v
                s.ys[ti++] = y
            }
            if (tc > tEnd + 1) break
        }
        return viol
    }

    private fun windowViolation(p: Problem, ys: DoubleArray, nt: Int, dy: Double = 0.0): Double {
        var worst = 0.0
        for (i in 0 until nt) worst = max(worst, max(p.lo - (ys[i] + dy), (ys[i] + dy) - p.hi))
        return worst
    }

    /**
     * Pénalité si, depuis l'état d'arrivée (ya, va), le trou de la traversée suivante n'est plus atteignable :
     * en tapant sans arrêt la balle monte à ~ -0,85 J par tick après le délai d'effet, en tombant sans taper elle accélère.
     */
    private fun terminalPenalty(p: Problem, ya: Double, va: Double): Double {
        if (p.nextTime <= 0.0) return 0.0
        val l = p.latency
        val g = p.dyn.g
        val t = max(l, p.nextTime - 6.0)
        val y1 = ya + va * l + 0.5 * g * l * l
        val rate = -p.dyn.j + 0.5 * g * 4.0
        val yUp = y1 + rate * (t - l)
        val yDown = min(ya + va * t + 0.5 * g * t * t, p.yMax - 40.0)
        return 25.0 * (max(0.0, yUp - (p.nextHi - 20.0)) + max(0.0, (p.nextLo + 20.0) - yDown))
    }

    private fun evaluate(p: Problem, s: Scratch, issue: IntArray, k: Int): Double {
        var nominalViolation = 0.0
        var arrivalY = 0.0
        var robust = 0.0
        var terminal = 0.0
        var boundRobust = 0.0
        var vArr = 0.0
        for (idx in SHIFTS.indices) {
            val sh = SHIFTS[idx]
            val sigma = sh[0]
            val delta = sh[1]
            val dyErr = sh[2]
            val jScale = sh[3]
            // instants d'effet : taps en attente + candidats (décalés de delta)
            var n = 0
            for (x in p.pending) s.land[n++] = x
            for (i in 0 until k) s.land[n++] = issue[i] + p.latency + delta
            java.util.Arrays.sort(s.land, 0, n)
            val a = p.arrival + sigma
            s.times[0] = a - p.window
            s.times[1] = a - p.window * 0.5
            s.times[2] = a
            s.times[3] = a + 1.0
            val bv = simulate(p, s, n, 4, jScale)
            val wv = windowViolation(p, s.ys, 3, dyErr)
            if (idx > 0) boundRobust = max(boundRobust, bv)
            if (idx == 0) {
                nominalViolation = bv
                arrivalY = s.ys[2]
                s.arrivalY = arrivalY
                s.arrivalV = s.vs[2]
                terminal = terminalPenalty(p, s.ys[2], s.vs[2])
                vArr = s.vs[2]
                if (bv > 0.5 || wv > 0.5) {
                    s.boundViol = bv
                    s.hard = 1000.0 + 40.0 * wv + 30.0 * bv
                    return s.hard
                }
            }
            robust = max(robust, wv)
        }
        s.boundViol = nominalViolation
        // les bornes (plafond/sol) pèsent aussi dans les scénarios décalés
        // arriver au sommet d'un saut (vitesse verticale ~ 0) : insensible au timing, et bon état pour la suite
        val apex = 0.9 * abs(vArr)
        s.hard = 40.0 * robust + 30.0 * max(nominalViolation, 0.6 * boundRobust) + terminal
        return 40.0 * robust + 30.0 * max(0.0, 0.6 * boundRobust - 5.0) + terminal + apex + 30.0 * nominalViolation + 0.04 * abs(arrivalY - p.pref) + 0.12 * (if (arrivalY < (p.lo + p.hi) / 2) 1.4 else 1.0) * abs(arrivalY - (p.lo + p.hi) / 2) + 0.35 * k
    }

    private class Cand(val cost: Double, val issue: IntArray, val k: Int, val arrivalY: Double, val arrivalV: Double, val hard: Double, val violation: Double)

    fun plan(p: Problem, lookNext: Boolean = true, light: Boolean = false): Plan {
        val s = Scratch()
        val issue = IntArray(MAX_TAPS)
        val top = ArrayList<Cand>()
        val keep = if (lookNext && p.nextTime > 0.0) 12 else 1

        fun consider(k: Int) {
            val c = evaluate(p, s, issue, k) + 1e-3 * (if (k > 0) issue[0] else 0)
            if (top.size < keep || c < top.last().cost) {
                val cand = Cand(c, issue.copyOf(), k, s.arrivalY, s.arrivalV, s.hard, s.boundViol)
                var i = top.size
                while (i > 0 && top[i - 1].cost > c) i--
                top.add(i, cand)
                if (top.size > keep) top.removeAt(top.size - 1)
            }
        }

        consider(0)
        val dmax = min((if (light) 40 else MAX_LOOKAHEAD).toDouble(), p.arrival - p.latency - 1).toInt()
        var d1 = 0
        while (d1 <= dmax) {
            issue[0] = d1
            consider(1)
            var d2 = d1 + MIN_SPACING
            while (d2 <= dmax) {
                issue[1] = d2
                consider(2)
                d2 += 3
            }
            d1 += if (d1 < 24) 1 else 2
        }
        if (!light && top[0].cost > 1.5) {
            d1 = 0
            while (d1 <= dmax) {
                issue[0] = d1
                var d2 = d1 + MIN_SPACING
                while (d2 <= dmax) {
                    issue[1] = d2
                    var d3 = d2 + MIN_SPACING + 1
                    while (d3 <= dmax) {
                        issue[2] = d3
                        consider(3)
                        d3 += 4
                    }
                    d2 += 4
                }
                d1 += if (d1 < 6) 1 else 4
            }
        }

        var chosen = top[0]
        var chosenTotal = chosen.cost
        if (keep > 1) {
            // coup d'avance : depuis l'état d'arrivée de chaque plan, la traversée suivante est-elle faisable ?
            var bestTotal = Double.MAX_VALUE
            for (c in top) {
                val inner = Problem(
                    y0 = c.arrivalY, v0 = c.arrivalV, pending = DoubleArray(0), latency = p.latency, dyn = p.dyn,
                    arrival = p.nextTime, window = p.nextWindow, lo = p.nextLo, hi = p.nextHi,
                    pref = (p.nextLo + p.nextHi) / 2, yMin = p.yMin, yMax = p.yMax,
                )
                val ip = plan(inner, lookNext = false, light = true)
                val total = c.cost + 0.7 * ip.hard
                if (total < bestTotal) { bestTotal = total; chosen = c }
            }
            chosenTotal = bestTotal
        }
        val firstTap = if (chosen.k > 0) chosen.issue[0] else -1
        return Plan(firstTap == 0, chosenTotal, firstTap, chosen.k, chosen.arrivalY, chosen.violation, chosen.hard + 0.0)
    }
}
