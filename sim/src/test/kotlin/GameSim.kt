import fr.triche.dangerwall.logic.Ball
import fr.triche.dangerwall.logic.Pilot
import fr.triche.dangerwall.logic.PilotSettings
import fr.triche.dangerwall.logic.Scene
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Dangerwall simulé d'après la vidéo (écran 720 x 1594, terrain 406..1547) : la balle traverse l'écran à vitesse
 * croissante, rebondit sur chaque mur (+1 point), les pics du mur quitté sont retirés puis régénérés ; un tap fixe
 * la vitesse verticale à -J ; la gravité et J grandissent avec la vitesse du jeu.
 */
class GameSim(
    seed: Long,
    private val g0: Double = 0.36,
    private val j0: Double = 7.1,
    private val gExp: Double = 0.9,
    private val jExp: Double = 0.85,
    private val vxStep: Double = 0.245,     // gain de vitesse horizontale par rebond (px/tick)
    private val vxCap: Double = 99.0,
    private val dispatch: ClosedFloatingPointRange<Double> = 3.0..6.0,   // délai d'envoi du tap (ticks)
    private val captureLag: Double = 2.0,   // retard de la capture d'écran (ticks)
    private val noise: Double = 1.5,        // bruit de mesure des bords de la balle (px)
    private val maxSpikes: Int = 6,
    private val spikesEvery: Int = 7,
    private val gapMin: Double = 0.0,       // le plus grand trou entre pics fait au moins ça
    private val spikeLo: Double = 484.0,    // plage des centres de pics (mesurée sur la vidéo)
    private val spikeHi: Double = 1204.0,
    private val boxHalf: Double = 46.0,
    private val hitFactor: Double = 0.85,   // le pic est un triangle : on tolère un peu de chevauchement
) {
    companion object {
        const val W = 720
        const val FIELD_TOP = 406.0
        const val FIELD_BOTTOM = 1547.0
        const val SPIKE_HALF = 37.5
        const val SPIKE_DEPTH = 58.0
    }

    private val rnd = Random(seed)
    var y = 976.0
    var vy = 0.0
    private var lead = 100.0        // bord avant de la balle
    private var dir = 1
    var bounces = 0
    private var vxn = 4.43
    private var theta = rnd.nextDouble() * 6.28
    private val omega = (rnd.nextDouble() * 2.5 + 1.0) * Math.PI / 180
    private var left = ArrayList<Double>()
    private var right = ArrayList<Double>()
    var dead: String? = null
    var tick = 0.0
    private val landings = ArrayList<Double>() // instants (jeu) où un tap prend effet
    private var lastVy = 0.0

    // état passé, pour simuler le retard de capture
    private class Snap(val tick: Double, val y: Double, val vy: Double, val lead: Double, val dir: Int, val theta: Double, val left: List<Double>, val right: List<Double>, val vx: Double)
    private val history = ArrayList<Snap>()

    init {
        left = genSpikes(0)
        right = genSpikes(0)
        landings.add(1.0) // le tap qui lance la partie
    }

    private fun s() = vxn / 4.43
    private fun g() = g0 * s().pow(gExp)
    private fun j() = j0 * s().pow(jExp)

    private fun count(n: Int) = min(maxSpikes, 1 + n / spikesEvery)

    private fun genSpikes(n: Int): ArrayList<Double> {
        val c = count(n)
        while (true) {
            val l = ArrayList<Double>()
            // dans la vidéo, les centres de pics restent entre 484 et 1204 : le bas du terrain est toujours libre
            val lo = spikeLo
            val hi = spikeHi
            var tries = 0
            while (l.size < c && tries < 200) {
                tries++
                val v = lo + rnd.nextDouble() * (hi - lo)
                if (l.all { abs(it - v) >= 2 * SPIKE_HALF + 5 }) l.add(v)
            }
            l.sort()
            // plus grand trou (entre bords de pics et limites du terrain)
            var maxGap = 0.0
            var prev = FIELD_TOP
            for (x in l) { maxGap = max(maxGap, x - SPIKE_HALF - prev); prev = x + SPIKE_HALF }
            maxGap = max(maxGap, FIELD_BOTTOM - prev)
            if (l.size == c && maxGap >= gapMin) return l
        }
    }

    /** Un tap demandé à l'instant (du pilote) [at] prend effet après le délai d'envoi. */
    fun tap(at: Double) {
        landings.add(at + dispatch.start + rnd.nextDouble() * (dispatch.endInclusive - dispatch.start))
    }

    fun trueG() = g()
    fun trueJ() = j()

    private fun halfV() = boxHalf * (abs(cos(theta)) + abs(sin(theta)))

    /** Avance d'un tick. */
    fun step() {
        if (dead != null) return
        tick += 1.0
        theta += omega
        // taps
        val it = landings.iterator()
        while (it.hasNext()) { val l = it.next(); if (l <= tick) { vy = -j(); it.remove() } }
        // vertical
        y += vy + 0.5 * g()
        vy += g()
        // horizontal : légèrement plus lent vers la fin de la traversée
        val progress = if (dir > 0) (lead - 92) / (W - 92.0) else 1.0 - lead / (W - 92.0)
        val vx = vxn * (1.03 - 0.06 * progress.coerceIn(0.0, 1.0))
        lead += dir * vx
        val dist = if (dir > 0) (W - 1) - lead else lead
        val hv = halfV()
        if (y - hv < FIELD_TOP || y + hv > FIELD_BOTTOM) { dead = "plafond/sol (y=${"%.0f".format(y)}, rebonds $bounces)"; return }
        if (dist <= SPIKE_DEPTH) {
            val spikes = if (dir > 0) right else left
            for (c in spikes) if (abs(y - c) < SPIKE_HALF + hv * hitFactor) { dead = "pic (y=${"%.0f".format(y)}, pic=${"%.0f".format(c)}, rebonds $bounces)"; return }
        }
        if (dist <= 0) {
            bounces++
            if (dir > 0) right = genSpikes(bounces) else left = genSpikes(bounces)
            dir = -dir
            vxn = min(vxCap, 4.43 + vxStep * bounces)
        }
        history.add(Snap(tick, y, vy, lead, dir, theta, ArrayList(left), ArrayList(right), vx))
        if (history.size > 12) history.removeAt(0)
    }

    /** Ce que la capture montre au pilote à l'instant courant (état de l'écran il y a [captureLag] ticks). */
    fun scene(): Scene? {
        val target = tick - captureLag
        val snap = history.lastOrNull { it.tick <= target } ?: return null
        val hv = boxHalf * (abs(cos(snap.theta)) + abs(sin(snap.theta)))
        val hw = hv
        val cx = if (snap.dir > 0) snap.lead - hw else snap.lead + hw
        var xmin = cx - hw
        var xmax = cx + hw
        var ymin = snap.y - hv
        var ymax = snap.y + hv
        // fantômes blancs derrière la balle
        val gx = snap.vx * 1.5
        if (snap.dir > 0) xmin -= gx else xmax += gx
        val gy = abs(snap.vy) * 1.2
        if (snap.vy > 0) ymin -= gy else ymax += gy
        fun n() = rnd.nextGaussian() * noise
        val ball = Ball((xmin + n()).toInt(), (xmax + n()).toInt(), (ymin + n()).toInt(), (ymax + n()).toInt(), 8000)
        return Scene(406, 1547, ball, snap.left.map { (it + rnd.nextGaussian()).toFloat() }, snap.right.map { (it + rnd.nextGaussian()).toFloat() })
    }
}

class SimResult(val bounces: Int, val dead: String?, val taps: Int, val latency: Double, val log: String = "", val predErrors: List<Double> = emptyList(), val deathInfo: String = "", val hardHist: DoubleArray = DoubleArray(0), val arrHist: DoubleArray = DoubleArray(0))

/** Fait jouer le pilote ; s'arrête à [target] rebonds ou à la mort. */
fun runPilot(sim: GameSim, settings: PilotSettings = PilotSettings(), target: Int = 60, maxTicks: Int = 60 * 600, fpsDiv: Int = 1, jitterSeed: Long = 7, verbose: Boolean = false): SimResult {
    val pilot = Pilot(GameSim.W, settings)
    val rnd = Random(jitterSeed)
    val sb = StringBuilder()
    var n = 0
    val predErrors = ArrayList<Double>()
    var predY = Double.NaN
    var lastBounces = 0
    var lastY = 0.0
    var predGap = ""
    while (sim.dead == null && sim.bounces < target && n < maxTicks) {
        sim.step()
        n++
        if (n % fpsDiv != 0) continue
        val scene = sim.scene() ?: continue
        val t = (sim.tick + rnd.nextGaussian() * 0.15) / 60.0
        val tapped = pilot.step(t, scene)
        if (pilot.dbgArrival in 9.0..13.0 && pilot.dbgPredY > 0) { predY = pilot.dbgPredY; predGap = "%.0f..%.0f".format(pilot.dbgGapLo, pilot.dbgGapHi) }
        if (sim.bounces != lastBounces) { if (!predY.isNaN()) predErrors.add(sim.y - predY); lastBounces = sim.bounces; predY = Double.NaN }
        lastY = sim.y
        if (tapped) sim.tap(sim.tick)
        if (verbose && (n % 3 == 0 || tapped)) sb.append("tick %.0f y=%.0f vy=%.1f | pilote y=%.0f v=%.1f g=%.2f/%.2f J=%.1f/%.1f arr=%.1f yArr=%.0f cout=%.1f d1=%d trou=%.0f..%.0f %s %s\n".format(sim.tick, sim.y, sim.vy, pilot.dbgY, pilot.dbgV, pilot.dbgG, sim.trueG(), pilot.dbgJ, sim.trueJ(), pilot.dbgArrival, pilot.dbgPredY, pilot.dbgCost, pilot.dbgNextTap, pilot.dbgGapLo, pilot.dbgGapHi, pilot.dbgCands, if (tapped) "TAP" else ""))
    }
    return SimResult(sim.bounces, sim.dead, pilot.tapsSent, pilot.latencyTicks, sb.toString(), predErrors, "prédit=${"%.0f".format(predY)} réel=${"%.0f".format(sim.y)} trou=$predGap", pilot.dbgHardHistory.copyOf(), pilot.dbgArrHistory.copyOf())
}
