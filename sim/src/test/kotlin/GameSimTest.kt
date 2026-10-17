import fr.triche.piano.logic.*
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Partie simulée : le bot ne voit que les images (60/s, retard d'affichage nul, toucher qui arrive [ld] après son envoi, une seule
 * « geste » en vol à la fois comme avec le service d'accessibilité) ; toute erreur de toucher (vide, touche déjà touchée) ou
 * toute touche blanche qui sort en bas termine la partie.
 */
class GameSimTest {
    class Out(val sim: PianoSynth, val bot: PianoBot, val log: List<String>, val seconds: Double)

    private fun play(scale: Double, seconds: Double, v0: Double, accel: Double, seed: Long = 1, ld: Double = 0.06, hz: Double = 60.0): Out {
        val rnd = Random(seed)
        val sim = PianoSynth(scale, seed, v0, accel)
        val bot = PianoBot()
        val log = ArrayList<String>()
        bot.log = { log.add(it) }
        var clock = 0.0
        var nextFrame = 0.0
        var inFlightUntil = 0.0
        val arrivals = ArrayList<Triple<Double, Double, Double>>()       // (heure d'arrivée, x, y)
        while (clock < seconds && sim.gameOver == null) {
            clock += 0.001
            sim.advance(clock)
            // arrivée des touchers dans le jeu
            val it = arrivals.iterator()
            while (it.hasNext()) { val a = it.next(); if (a.first <= clock) { it.remove(); sim.touch(a.second, a.third) } }
            // fil des touchers : un geste à la fois
            if (clock >= inFlightUntil) {
                val pts = bot.collectTaps(clock)
                if (pts.isNotEmpty()) {
                    val arr = clock + ld + (rnd.nextDouble() - 0.5) * 0.02
                    for (p in pts) arrivals.add(Triple(arr + p.delay, p.x, p.y))
                    inFlightUntil = arr + (pts.maxOf { it.delay }) + 0.01
                }
            }
            if (clock >= nextFrame) {
                nextFrame += 1.0 / hz
                sim.render()
                bot.onFrame(clock, sim.frame)
            }
        }
        return Out(sim, bot, log, clock)
    }

    @Test
    fun partieCommeLaVideo() {
        // vitesse de la vidéo : 500 px/s au début, +30 px/s par seconde environ, 60 s
        val o = play(0.5, 60.0, 500.0, 35.0)
        println("SIM vidéo : %d touches ok, %d vides, fin : %s (t=%.1f s), apprise %s".format(o.sim.tapsOk, o.sim.emptyTaps, o.sim.gameOver, o.seconds, o.bot.summary()))
        assertTrue(o.sim.gameOver == null, "partie perdue : ${o.sim.gameOver}\n" + o.log.takeLast(15).joinToString("\n"))
        assertTrue(o.sim.tapsOk > 150, "touches ${o.sim.tapsOk}")
    }

    @Test
    fun jusquOuIlTient() {
        // vitesse qui monte jusqu'à ~3000 px/s : on cherche à partir de quand le bot perd
        val o = play(0.5, 90.0, 800.0, 60.0, seed = 3)
        val v = (800 + 60 * o.seconds)
        println("SIM extrême : fin %s à t=%.1f s (vitesse %.0f px/s), %d touches".format(o.sim.gameOver, o.seconds, v, o.sim.tapsOk))
        assertTrue(o.sim.tapsOk > 100)
    }

    @Test
    fun latencesEtCadencesVariees() {
        for ((ld, hz) in listOf(0.03 to 60.0, 0.12 to 60.0, 0.08 to 30.0, 0.05 to 120.0)) {
            val o = play(0.5, 40.0, 500.0, 35.0, seed = 5, ld = ld, hz = hz)
            println("SIM latence %.0f ms, %.0f images/s : %d touches, fin %s, apprise %s".format(ld * 1000, hz, o.sim.tapsOk, o.sim.gameOver, o.bot.summary()))
            assertTrue(o.sim.gameOver == null, "latence $ld, $hz images/s : ${o.sim.gameOver}\n" + o.log.takeLast(8).joinToString("\n"))
        }
    }

    @Test
    fun pleineEchelleAvecDessinFidele() {
        // image de 720 px (précision maximale), 20 s
        val o = play(1.0, 20.0, 500.0, 35.0, seed = 9)
        println("SIM 720 px : %d touches, fin %s".format(o.sim.tapsOk, o.sim.gameOver))
        assertTrue(o.sim.gameOver == null && o.sim.tapsOk > 40, "${o.sim.gameOver} / ${o.sim.tapsOk}")
    }
}
