import fr.triche.dangerwall.logic.Planner
import fr.triche.dangerwall.logic.Problem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/** Cherche par force brute (jusqu'à 4 taps, pas de 1 tick) s'il existait un plan sans violation nominale. */
object Brute {
    fun minViolation(p: Problem, maxTaps: Int = 4): Pair<Double, String> {
        val g = p.dyn.g
        val j = p.dyn.j
        var best = Double.MAX_VALUE
        var bestDesc = ""
        val dmax = (p.arrival - p.latency - 1).toInt().coerceAtMost(60)
        val issue = IntArray(maxTaps)
        fun eval(k: Int) {
            // simulation nominale, atterrissages triés
            val land = DoubleArray(k + p.pending.size)
            var n = 0
            for (x in p.pending) land[n++] = x
            for (i in 0 until k) land[n++] = issue[i] + p.latency
            land.sort()
            val times = doubleArrayOf(p.arrival - p.window, p.arrival - p.window / 2, p.arrival)
            var tc = 0.0; var y = p.y0; var v = p.v0; var li = 0; var ti = 0
            var bound = 0.0; val ys = DoubleArray(3)
            while (ti < 3) {
                val nl = if (li < n) land[li] else Double.MAX_VALUE
                val nt = times[ti]
                val te = min(nl, nt)
                val dt = max(0.0, te - tc)
                if (dt > 0) {
                    if (v < 0) { val ta = -v / g; if (ta < dt) bound = max(bound, p.yMin - (y + v * ta + 0.5 * g * ta * ta)) }
                    val ye = y + v * dt + 0.5 * g * dt * dt
                    bound = max(bound, max(ye - p.yMax, p.yMin - ye))
                    y = ye; v += g * dt; tc = te
                }
                if (nl <= nt) { v = -j; li++ } else ys[ti++] = y
            }
            var w = 0.0
            for (yy in ys) w = max(w, max(p.lo - yy, yy - p.hi))
            val viol = w + bound
            if (viol < best) { best = viol; bestDesc = "taps=${issue.take(k)} yArr=${"%.0f".format(ys[2])} (fenêtre ${"%.0f".format(w)}, bornes ${"%.0f".format(bound)})" }
        }
        eval(0)
        for (d1 in 0..dmax) {
            issue[0] = d1; eval(1)
            for (d2 in d1 + 3..dmax) {
                issue[1] = d2; eval(2)
                if (maxTaps >= 3) for (d3 in d2 + 3..dmax) {
                    issue[2] = d3; eval(3)
                    if (maxTaps >= 4) for (d4 in d3 + 3..dmax) { issue[3] = d4; eval(4) }
                }
            }
        }
        return best to bestDesc
    }
}

class OracleTest {
    @Test fun etatsPerdus() {
        if (System.getenv("ORACLE") == null) return
        var tot = 0; var avoidable = 0
        for (seed in 1..12) {
            val sim = GameSim(seed.toLong())
            val pilot = fr.triche.dangerwall.logic.Pilot(GameSim.W)
            val rnd = java.util.Random(7)
            var snapshot: Problem? = null
            var snapAt = -1
            // on garde le Problem vu ~35 ticks avant la mort (arrivée encore lointaine mais plan déjà serré)
            val history = ArrayList<Problem>()
            val allHist = ArrayList<List<Problem>>()
            var n = 0
            while (sim.dead == null && sim.bounces < 60 && n < 60 * 600) {
                sim.step(); n++
                val scene = sim.scene() ?: continue
                val t = (sim.tick + rnd.nextGaussian() * 0.15) / 60.0
                if (pilot.step(t, scene)) sim.tap(sim.tick)
                if (pilot.dbgProblem != null && pilot.dbgProblem!!.arrival in 25.0..60.0) { history.add(pilot.dbgProblem!!); allHist.add(pilot.dbgAll) }
                if (history.size > 400) history.removeAt(0)
            }
            if (sim.dead == null) { println("ORACLE seed $seed : a survécu (${sim.bounces})"); continue }
            // le Problem le plus lointain de la dernière traversée avec arrivée entre 45 et 60
            val idx = history.indexOfLast { it.arrival in 40.0..60.0 }
            if (idx < 0) continue
            val cand = history[idx]
            val (v, desc) = Brute.minViolation(cand)
            for (other in allHist[idx]) { val (v2, d2) = Brute.minViolation(other, 3); println("ORACLE    autre trou ${"%.0f".format(other.lo)}..${"%.0f".format(other.hi)} : violation min ${"%.0f".format(v2)} px $d2") }
            tot++
            if (v < 1.0) avoidable++
            println("ORACLE seed $seed : mort à ${sim.bounces} rebonds ; état à ${"%.0f".format(cand.arrival)} ticks de l'arrivée (y=${"%.0f".format(cand.y0)}, v=${"%.1f".format(cand.v0)}, trou ${"%.0f".format(cand.lo)}..${"%.0f".format(cand.hi)}) ; meilleur plan en force brute : violation ${"%.0f".format(v)} px : $desc")
        }
        println("ORACLE morts évitables (plan de violation < 1 px existait) : $avoidable / $tot")
    }
}
