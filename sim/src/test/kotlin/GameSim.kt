import fr.triche.stack.logic.Comp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Simulateur du jeu, au niveau des « faces supérieures » telles que le Segmenter les voit.
 * Mécanique mesurée sur la vidéo : le bloc glisse (vitesse ~380 → 480 px/s selon le niveau) de part et d'autre du
 * sommet de la tour ; au toucher il s'arrête, tombe, et est rogné du décalage (le sommet perd |décalage| de large,
 * côté dépassé) ; les blocs successifs viennent alternativement de gauche et de droite.
 */
class GameSim(
    seed: Long,
    /** Délai fixe entre l'envoi du toucher et la prise en compte par le jeu (s). */
    private val inputLatency: Double = 0.05,
    private val inputJitter: Double = 0.008,
    /** Retard de la capture : l'image montre le jeu tel qu'il était il y a [captureLag] secondes. */
    private val captureLag: Double = 0.045,
    private val perfectTol: Double = 4.0,
    /** Le jeu avance par pas d'image (60/s) : positions et prise en compte du toucher tombent sur ces instants. */
    val period: Double = 1.0 / 60,
    private val screenW: Int = 720,
    private val startLevel: Int = 0,
    private val speedScale: Double = 1.0,
    /** Un élément de décor immobile, de couleur vive, plus « gros » que la tour (comme le parasite vu sur téléphone). */
    private val decoy: Boolean = false,
    /** Dérive de la latence (s ajoutées par niveau) : un téléphone qui chauffe répond de plus en plus tard. */
    private val latencyDrift: Double = 0.0,
) {
    private val rnd = java.util.Random(seed)

    class St(
        var tx0: Double = 120.0,
        var width: Double = 475.0,
        var level: Int = 0,
        var over: Boolean = false,
        var cT: IntArray = intArrayOf(75, 75, 75),     // couleur du sommet (socle gris au début)
        var cS: IntArray = intArrayOf(217, 122, 75),
        var dir: Int = 1,
        var startT: Double = 0.0,
        var frozenAt: Double = Double.NaN,
        var frozenC: Double = 0.0,
        var cutDone: Boolean = true,
    ) {
        fun copy() = St(tx0, width, level, over, cT, cS, dir, startT, frozenAt, frozenC, cutDone)
    }

    private var colorIdx = 0
    private var s = St()
    private val history = ArrayList<Pair<Double, St>>()
    private fun snap(t: Double) { history.add(t to s.copy()) }
    private fun at(t: Double): St {
        var r = history[0].second
        for (h in history) { if (h.first <= t) r = h.second else break }
        return r
    }

    init {
        if (startLevel > 0) {
            s.level = startLevel
            colorIdx = startLevel + 1
            s.cT = colorAt(startLevel)
            s.cS = colorAt(startLevel + 1)
            s.dir = if (startLevel % 2 == 0) 1 else -1
            s.width = 300.0
            s.tx0 = 200.0
        }
        history.add(-1.0 to s.copy())
    }

    val over get() = s.over
    val level get() = s.level
    val width get() = s.width
    val errors = ArrayList<Double>()      // décalage de chaque pose (signé, px)
    private var pendingTapAt = Double.NaN

    private val phase = 0.0031
    /** Dernier instant de mise à jour du jeu ≤ t. */
    fun gridFloor(t: Double) = Math.floor((t - phase) / period) * period + phase
    private fun gridCeil(t: Double) = Math.ceil((t - phase) / period - 1e-9) * period + phase

    private fun tc(st: St) = st.tx0 + st.width / 2
    private fun speed(level: Int) = min(500.0, 380.0 + 4.0 * level) * speedScale

    private fun colorAt(k: Int): IntArray {
        val ring = 6 * 141
        val p = ((k * 10 + 47) % ring + ring) % ring
        val seg = p / 141
        val f = p % 141
        return when (seg) {
            0 -> intArrayOf(217, 75 + f, 75)
            1 -> intArrayOf(217 - f, 216, 75)
            2 -> intArrayOf(75, 216, 75 + f)
            3 -> intArrayOf(75, 216 - f, 216)
            4 -> intArrayOf(75 + f, 75, 216)
            else -> intArrayOf(216, 75, 216 - f)
        }
    }

    private fun nextColor(): IntArray {
        colorIdx++
        return colorAt(colorIdx)
    }

    /**
     * Position du centre du bloc mobile à l'instant [t] : oscillation sinusoïdale autour de la tour, comme dans la
     * vidéo (la vitesse croît de ~0,7 à 1 × la vitesse maximale entre 350 px et 0 px du centre). Le bloc apparaît à
     * 350 px du centre, côté alternant, et arrive vers la tour.
     */
    private fun slabCenter(st: St, t: Double): Double {
        if (!st.frozenAt.isNaN() && t >= st.frozenAt) return st.frozenC
        val a = 500.0
        val w = speed(st.level) / a                     // vitesse max = A·ω au centre
        val phase0 = Math.asin(-350.0 / a)               // départ à -350 px
        val p = a * Math.sin(phase0 + w * (t - st.startT))
        return tc(st) + st.dir * p
    }

    fun tap(t: Double) {
        if (!s.frozenAt.isNaN() || s.over) return
        pendingTapAt = t + inputLatency + latencyDrift * s.level + (rnd.nextDouble() * 2 - 1) * inputJitter
    }

    private var respawnAt = Double.NaN

    /** Fait avancer les règles du jeu jusqu'à l'instant réel [t]. */
    fun advance(t: Double) {
        if (s.over) return
        if (!pendingTapAt.isNaN() && t >= pendingTapAt && s.frozenAt.isNaN()) {
            val g = gridCeil(pendingTapAt)
            s.frozenC = slabCenter(s, g)
            s.frozenAt = g
            s.cutDone = false
            pendingTapAt = Double.NaN
            snap(s.frozenAt)
        }
        if (!s.frozenAt.isNaN() && !s.cutDone && t >= s.frozenAt + 0.05) {
            val tt = s.frozenAt + 0.05
            s.cutDone = true
            val d = s.frozenC - tc(s)
            errors.add(d * s.dir)           // > 0 : dépassé
            if (abs(d) >= s.width) {
                s.over = true
                snap(tt)
                return
            }
            if (abs(d) > perfectTol) {
                if (d > 0) s.tx0 += d
                s.width -= abs(d)
            }
            s.level++
            s.cT = s.cS
            s.cS = nextColor()
            snap(tt)
            respawnAt = s.frozenAt + 0.12
        }
        if (!respawnAt.isNaN() && t >= respawnAt) {
            s.dir = if (s.level % 2 == 0) 1 else -1
            s.startT = respawnAt
            s.frozenAt = Double.NaN
            respawnAt = Double.NaN
            snap(s.startT)
        }
    }

    private fun comp(x0: Double, x1: Double, y0: Double, col: IntArray): Comp? {
        var a = x0
        var b = x1
        if (b < 0 || a > screenW - 1) return null
        a = max(a, 0.0)
        b = min(b, (screenW - 1).toDouble())
        if (b - a < 8) return null
        val area = ((b - a) * 120).toInt()
        if (area < 600) return null
        return Comp(a.toInt(), b.toInt(), y0.toInt(), y0.toInt() + 240, area, col[0], col[1], col[2])
    }

    /** Ce que le Segmenter verrait dans l'image qui arrive à l'instant réel [t] (le jeu y est vu avec [captureLag] de retard). */
    fun comps(t: Double): List<Comp> {
        val tau = t - captureLag
        val st = at(tau)
        val out = ArrayList<Comp>()
        if (decoy) out.add(Comp(272, 337, 600, 760, 99000, 254, 218, 87))
        val towerY = 700.0
        comp(st.tx0, st.tx0 + st.width, towerY, st.cT)?.let { out.add(it) }
        // bloc mobile : visible tant qu'il n'est pas posé (rogné) ; il apparaît à son point de départ
        val landed = !st.frozenAt.isNaN() && st.cutDone
        if (!landed && !st.over) {
            val c = slabCenter(st, gridFloor(tau))
            val fall = if (!st.frozenAt.isNaN() && tau >= st.frozenAt) min(18.0, (tau - st.frozenAt) * 400) else 0.0
            val sy = towerY - 18 - 0.4 * abs(c - tc(st)).coerceAtMost(300.0) + fall
            comp(c - st.width / 2, c + st.width / 2, sy, st.cS)?.let { out.add(it) }
        }
        return out
    }
}
