import fr.triche.bot.logic.Controller
import fr.triche.bot.logic.Observation
import fr.triche.bot.logic.Pillar
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Simulation du jeu avec la physique mesurée sur la vidéo (écran 720 x 1594, trait plafond y=404, sol y=1549).
 * Le contrôleur reçoit des images à ~60 Hz, bruitées, retardées, et ses touchers arrivent avec une latence aléatoire.
 */
class SimTest {
    private val w = 720
    private val fieldTop = 404
    private val fieldBottom = 1549
    private val ballX = 311f
    private val half = 28f
    private val pillarW = 94f
    private val spacing = 498f

    private class P(var x: Float, val gapTop: Float, val gapBottom: Float)

    /** @return nombre de piliers passés avant de mourir (plafonné à maxPillars) */
    private fun play(seed: Long, gap: Float, latMin: Float, latMax: Float, speed: Float, maxPillars: Int, fps: Int = 60, jumpScale: Float = 1f): Int {
        val rnd = Random(seed)
        val ctl = Controller()
        val dt = 1.0 / fps
        var y = 976f
        var vy = 0f
        var t = 0.0
        val pillars = ArrayList<P>()
        var nextX = 720f + 200f
        val landings = ArrayList<Double>() // moments où un toucher prend effet
        var passed = 0
        var lastC = 930f
        val g = Controller.GRAVITY * w
        val jump = Controller.JUMP * w * jumpScale
        val scroll = 0.483f * w * speed
        // état de la ligne de retard d'affichage (une image)
        var prevObs: Observation? = null
        var steps = 0
        val trace = ArrayList<String>()

        while (passed < maxPillars && steps < 60 * fps * 600) {
            steps++
            t += dt
            // physique
            val it = landings.iterator()
            while (it.hasNext()) if (it.next() <= t) { vy = jump; it.remove() }
            vy += g * dt.toFloat()
            y += vy * dt.toFloat()
            for (p in pillars) p.x -= scroll * dt.toFloat()
            if (nextX - 0 < 720f + 300f && pillars.size < 4 && (pillars.isEmpty() || pillars.last().x + spacing <= 720f + 200f)) {
                val minC = fieldTop + gap / 2 + 60
                val maxC = fieldBottom - gap / 2 - 60
                // les vrais trous varient de ±205 px d'un pilier au suivant
                lastC = (lastC + (rnd.nextFloat() * 2 - 1) * 210f).coerceIn(minC, maxC)
                val c = lastC
                pillars.add(P(if (pillars.isEmpty()) 720f + 200f else pillars.last().x + spacing, c - gap / 2, c + gap / 2))
            }
            while (pillars.isNotEmpty() && pillars.first().x + pillarW < ballX - half - 5) {
                pillars.removeAt(0); passed++
            }
            // collisions
            if (y - half < fieldTop || y + half > fieldBottom) { if (System.getenv("SIMDEBUG") != null) println("DEAD wall t=$t y=$y vy=$vy passed=$passed"); return passed }
            for (p in pillars) {
                val overlapX = ballX + half > p.x && ballX - half < p.x + pillarW
                if (overlapX && (y - half < p.gapTop || y + half > p.gapBottom)) { if (System.getenv("SIMDEBUG") != null) trace.takeLast(100).forEach { println(it) }; println("DEAD pillar t=$t y=$y vy=$vy gap=${p.gapTop}..${p.gapBottom} px=${p.x} passed=$passed"); return passed }
            }
            // observation (une image de retard + bruit 1 px)
            val obs = Observation(
                ballX + rnd.nextFloat() - 0.5f, y + (rnd.nextFloat() - 0.5f) * 2f, half, fieldTop, fieldBottom,
                pillars.map { Pillar(it.x.toInt(), (it.x + pillarW).toInt(), it.gapTop.toInt(), it.gapBottom.toInt()) },
            )
            val shown = prevObs
            prevObs = obs
            val tapped = shown != null && ctl.step(t, shown, w)
            trace.add("t=%.3f y=%.0f vy=%.0f tap=%s lat=%.3f".format(t, y, vy, tapped, ctl.latency)); if (trace.size > 150) trace.removeAt(0)
            if (tapped) {
                landings.add(t + latMin + rnd.nextFloat() * (latMax - latMin))
            }
        }
        return passed
    }

    private fun campaign(name: String, gap: Float, latMin: Float, latMax: Float, speed: Float, fps: Int = 60, runs: Int = 40, pillars: Int = 60, jumpScale: Float = 1f, maxDeaths: Int = 0) {
        var worst = Int.MAX_VALUE
        var deaths = 0
        for (s in 1..runs) {
            val r = play(s.toLong(), gap, latMin, latMax, speed, pillars, fps, jumpScale)
            if (r < pillars) deaths++
            worst = minOf(worst, r)
        }
        println("SIM $name: $deaths morts sur $runs parties de $pillars piliers (pire = $worst)")
        assertTrue(deaths <= maxDeaths, "$name: $deaths morts (pire = $worst piliers)")
    }

    @Test fun nominal() = campaign("nominal 340px, latence 60-120ms", 340f, 0.06f, 0.12f, 1f)
    @Test fun gapSerre() = campaign("trou 300px", 300f, 0.06f, 0.12f, 1f)
    @Test fun latenceFaible() = campaign("latence 20-50ms", 340f, 0.02f, 0.05f, 1f)
    @Test fun latenceForte() = campaign("latence 100-160ms (extrême)", 340f, 0.10f, 0.16f, 1f, maxDeaths = 2)
    @Test fun plusRapide() = campaign("vitesse x1.4 (extrême)", 340f, 0.06f, 0.12f, 1.4f, maxDeaths = 2)
    @Test fun sautPlusFort() = campaign("saut +25%", 340f, 0.06f, 0.12f, 1f, jumpScale = 1.25f)
    @Test fun sautPlusFortTrouSerre() = campaign("saut +25% trou 310px", 310f, 0.06f, 0.12f, 1f, jumpScale = 1.25f)
    @Test fun trouTresSerre() = campaign("trou 280px", 280f, 0.06f, 0.12f, 1f)
    @Test fun sautPlusFaible() = campaign("saut -15%", 340f, 0.06f, 0.12f, 1f, jumpScale = 0.85f)
    @Test fun capture30fps() = campaign("capture 30 fps", 340f, 0.06f, 0.12f, 1f, fps = 30)
}
