import fr.triche.maths.logic.Mode
import fr.triche.maths.logic.Planner
import fr.triche.maths.logic.QuizBot
import fr.triche.maths.logic.QuizReader
import fr.triche.maths.logic.Settings
import fr.triche.maths.logic.TemplateData
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Le vrai jeu, rejoué avec les vraies images de la vidéo : question affichée -> le bot décide -> le tap arrive après une
 * latence -> flash 0,40 s -> question suivante ; le chrono du jeu va de l'affichage de la question 1 au dernier tap.
 */
class BotSimTest {
    private val reader = QuizReader(TemplateData.bank())
    private val qFrames = listOf("m0280", "m0400", "m0470", "m0650", "m0900")
    private val flashFrames = listOf("m0320", "m0430", "m0515", "m0800")
    private val correct = intArrayOf(3, 1, 1, 3, 2)

    private class Outcome(val total: Double, val taps: List<Double>, val wrong: Int, val measured: Double?)

    private fun playGame(
        target: Double,
        mode: Mode,
        seed: Long,
        offset: Double = -0.08,
        dispatchLatency: ClosedFloatingPointRange<Double> = 0.03..0.09,
        captureLag: Double = 0.03,
        fps: Int = 60,
        scoreFrames: Boolean = false,
    ): Outcome {
        val rnd = Random(seed)
        val bot = QuizBot(reader, Settings(target, offset, mode), Random(seed))
        val dt = 1.0 / fps
        val start = 1.0 // affichage de la question 1
        var t = 0.0
        var q = 0
        var qStart = start
        var flashUntil = -1.0
        var flashFrame = ""
        var over = -1.0
        var wrong = 0
        val taps = ArrayList<Double>()
        val pending = ArrayList<Pair<Double, Int>>() // (instant réel du tap, bouton)
        var scheduled: Pair<Double, Int>? = null
        var gameTotal = -1.0
        var measured: Double? = null

        while (t < 120.0) {
            t += dt
            // taps qui arrivent
            val it = pending.iterator()
            while (it.hasNext()) {
                val (at, button) = it.next()
                if (at > t) continue
                it.remove()
                if (q >= 5 || t < qStart) continue
                bot.onTapFired(at + captureLag - 0.0) // (le service notifie à l'envoi ; on garde l'horloge du bot)
                if (button == correct[q]) {
                    taps.add(at)
                    if (q == 4) { gameTotal = at - start; over = at + 0.1; q = 5 } else {
                        flashFrame = flashFrames[q]; flashUntil = at + Planner.TRANSITION; qStart = at + Planner.TRANSITION; q++
                    }
                } else wrong++
            }
            // image montrée à l'instant t (le bot la reçoit avec un retard de capture)
            val frameName = when {
                t < start -> "m0100"
                q >= 5 -> if (t >= over) "m1285" else "m0900"
                t < flashUntil -> flashFrame
                else -> qFrames[q]
            }
            val frame = Support.frame(frameName)
            val d = bot.step(t + captureLag, frame)
            if (d != null) {
                val real = d.tapAt - captureLag + dispatchLatency.start + rnd.nextDouble() * (dispatchLatency.endInclusive - dispatchLatency.start)
                pending.add(real to d.button)
            }
            if (scoreFrames && bot.result != null && measured == null) measured = bot.takeResult()?.measured
            if (q >= 5 && over > 0 && t > over + 3.0 && (!scoreFrames || measured != null)) break
        }
        return Outcome(gameTotal, taps, wrong, measured)
    }

    @Test fun cinqBonnesReponsesEtTempsVise() {
        for (target in listOf(6.0, 8.5, 12.0, 14.3)) {
            for (mode in Mode.values()) {
                var worst = 0.0
                for (seed in 1L..20L) {
                    val o = playGame(target, mode, seed)
                    assertEquals(0, o.wrong, "mauvaise réponse (cible $target, $mode, seed $seed)")
                    assertEquals(5, o.taps.size, "5 taps (cible $target, $mode, seed $seed)")
                    worst = maxOf(worst, abs(o.total - target))
                }
                println("SIM cible ${target}s $mode : écart max sur 20 parties = ${"%.3f".format(worst)} s")
                assertTrue(worst < 0.12, "cible $target $mode : écart $worst")
            }
        }
    }

    @Test fun tempsMinimumRespecte() {
        val o = playGame(1.0, Mode.NATURAL, 1)
        println("SIM cible impossible 1,0 s -> ${"%.2f".format(o.total)} s (minimum ${"%.2f".format(Planner.minTotal())})")
        assertEquals(0, o.wrong)
        assertTrue(o.total >= Planner.minTotal() - 0.05, "total ${o.total}")
    }

    @Test fun captureLente30fps() {
        var worst = 0.0
        for (seed in 1L..10L) {
            val o = playGame(9.0, Mode.NATURAL, seed, fps = 30, captureLag = 0.05)
            assertEquals(0, o.wrong)
            worst = maxOf(worst, abs(o.total - 9.0))
        }
        println("SIM 30 fps, capture 50 ms : écart max = ${"%.3f".format(worst)}")
        assertTrue(worst < 0.15)
    }

    @Test fun rapidePuisAttente() {
        val o = playGame(10.0, Mode.FAST_THEN_WAIT, 3)
        println("SIM rapide-puis-attente taps réels = ${o.taps.map { "%.2f".format(it - 1.0) }}")
        // les 4 premières réponses avant 4,5 s, la dernière à ~10 s
        assertTrue(o.taps[3] - 1.0 < 5.0, "quatrième tap ${o.taps[3] - 1.0}")
        assertTrue(abs(o.taps[4] - 1.0 - 10.0) < 0.12)
    }

    @Test fun lectureDuScoreDeFin() {
        val o = playGame(8.0, Mode.NATURAL, 5, scoreFrames = true)
        assertNotNull(o.measured)
        assertEquals(14.31, o.measured)
    }

    @Test fun planificateur() {
        val rnd = Random(1)
        for (mode in Mode.values()) for (total in listOf(3.2, 5.0, 8.0, 15.0, 40.0)) {
            val d = Planner.plan(total, mode, rnd)
            val sum = d.sum() + (Planner.QUESTIONS - 1) * Planner.TRANSITION
            assertTrue(abs(sum - maxOf(total, Planner.minTotal())) < 1e-9, "$mode $total somme $sum")
            assertTrue(d.all { it >= Planner.MIN_THINK - 1e-9 }, "$mode $total minimum")
        }
    }
}
