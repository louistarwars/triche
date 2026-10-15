import fr.triche.timesup.logic.*
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jeu simulé d'après les mesures de la vidéo. Temps « vrai » du jeu g ; les images sont horodatées g + [lambda] (retard entre
 * le dessin et la capture) ; un toucher envoyé à l'instant S arrive dans le jeu à S + [tapDelay]. Latence vraie du bot :
 * lambda + tapDelay. Le décalage affiché par le jeu est e = (arrivée du toucher) − (fin vraie de la barre), positif = trop tard.
 */
class GameSimTest {
    class Result(val errors: List<Double>, val log: List<String>, val bot: TimesUpBot)

    private fun play(
        durations: List<Double>, latencySetting: Double, shift: Double = 0.0, seed: Long = 1, lambda: Double = 0.03,
        tapDelay: Double = 0.055, hz: Double = 60.0, autoNext: Boolean = false, autoLatency: Boolean = false,
        bottomBias: Double = 0.0,           // décalage vrai du bas de la barre (px) par rapport à celui qu'on mesure
        noise: Double = 0.0,
    ): Result {
        val rnd = Random(seed)
        val bot = TimesUpBot(Settings(latencySetting, shift, autoNext, autoLatency))
        val log = ArrayList<String>()
        bot.log = { log.add(it) }
        val synth = Synth()
        val errors = ArrayList<Double>()
        val frameDt = 1.0 / hz
        var clock = 0.0
        var nextFrame = rnd.nextDouble() * frameDt
        var level = 0
        var t0 = 3.0                      // début de la descente (temps vrai) de la manche courante
        var tEnd = Double.NaN
        var recvT = Double.NaN            // arrivée du toucher dans le jeu
        val pendingTaps = ArrayList<Double>()
        var resultAt = Double.NaN
        var barVisible = true
        val slope = (synth.bottom - synth.topFull + bottomBias) / durations[0]
        var curSlope = (synth.bottom - synth.topFull + bottomBias) / durations[0]
        var curD = durations[0]
        tEnd = t0 + curD
        var done = false
        var nextShown = false
        var cleared = false
        var nextTapAt = Double.NaN
        while (!done && clock < 120) {
            clock += 0.001
            // toucher du bot
            val tp = bot.nextTap()
            if (tp != null && clock >= tp.at) {
                val sendT = clock + rnd.nextDouble() * 0.002
                bot.onSent(tp, sendT)
                if (tp.main) {
                    recvT = sendT + tapDelay + (rnd.nextDouble() - 0.5) * 0.004
                    errors.add(recvT - tEnd)
                    resultAt = recvT + 0.012
                    barVisible = false
                } else {
                    nextTapAt = sendT + tapDelay
                }
            }
            if (!nextTapAt.isNaN() && clock >= nextTapAt) {          // « niveau suivant » pris en compte
                nextTapAt = Double.NaN
                level++
                if (level >= durations.size) { done = true; continue }
                synth.drawResult(0.0); synth.drawButton(false)
                t0 = clock - lambda + 2.0
                curD = durations[level]
                curSlope = (synth.bottom - synth.topFull + bottomBias) / curD
                tEnd = t0 + curD
                resultAt = Double.NaN; barVisible = true; nextShown = false; cleared = false
            }
            if (clock >= nextFrame) {
                val tf = nextFrame + (rnd.nextDouble() - 0.5) * 0.0004
                nextFrame += frameDt
                val g = tf - lambda                                 // temps de jeu montré par cette image
                if (!resultAt.isNaN() && g >= resultAt) {
                    val a = ((g - resultAt) / 0.2).coerceIn(0.0, 1.0)
                    if (!cleared) { synth.clearBar(); cleared = true }
                    synth.drawResult(a)
                    if (!nextShown && g > resultAt + 0.4) { synth.drawButton(true); nextShown = true }
                } else if (barVisible && g > t0 - 1.0) {
                    val p = ((g - t0) / curD).coerceIn(0.0, 1.0)
                    val top = synth.topFull + (synth.bottom - synth.topFull) * p + (if (noise > 0) rnd.nextGaussian() * noise else 0.0)
                    val alpha = if (p < 0.25) 1.0 else (1 - (p - 0.25) / 0.25).coerceIn(0.0, 1.0)
                    if (alpha > 0) synth.drawBar(top, alpha) else synth.clearBar()
                }
                bot.onFrame(tf, synth.frame)
                if (!autoNext && !resultAt.isNaN() && g > resultAt + 1.0 && errors.size == level + 1) {
                    level++
                    if (level >= durations.size) done = true
                    else {                                           // le joueur appuie lui-même sur « niveau suivant »
                        t0 = g + 2.0
                        curD = durations[level]
                        tEnd = t0 + curD
                        resultAt = Double.NaN; barVisible = true; cleared = false
                        synth.drawResult(0.0)
                    }
                }
            }
        }
        return Result(errors, log, bot)
    }

    @Test
    fun troisManchesAuMilliseconde() {
        // durées mesurées sur la vidéo : 3,76 s (165 px/s), 9,83 s (63 px/s), 11,43 s (54 px/s)
        for (seed in 1L..4L) {
            val r = play(listOf(3.76, 9.83, 11.43), latencySetting = 0.085, seed = seed)
            println("SIM seed $seed : erreurs ms " + r.errors.joinToString { "%+.1f".format(it * 1000) })
            assertEquals(3, r.errors.size, r.log.joinToString("\n"))
            for (e in r.errors) assertTrue(abs(e) < 0.012, "erreur ${e * 1000} ms\n" + r.log.takeLast(25).joinToString("\n"))
        }
    }

    @Test
    fun latenceFausseDecaleDeLaMemeValeur() {
        val r = play(listOf(3.76, 9.83, 11.43), latencySetting = 0.06, seed = 5)     // vraie 0,085 : 25 ms de moins que la vraie
        println("SIM latence fausse : " + r.errors.joinToString { "%+.1f".format(it * 1000) })
        for (e in r.errors) assertEquals(0.025, e, 0.012)      // toucher 25 ms trop tard
    }

    @Test
    fun calibrationParLesDecalagesAffiches() {
        // vraie latence 85 ms ; bas de la barre mesuré 2 px trop bas (la vraie fin arrive 2 px / pente plus tôt ou plus tard)
        val obs = ArrayList<Obs>()
        var l = 0.07
        var b = 0.0
        for (run in 1..3) {
            val r = play(listOf(3.76, 9.83, 11.43), latencySetting = l, shift = b, seed = 10L + run, bottomBias = -2.0)
            val levels = r.bot.levels
            assertEquals(3, levels.size, r.log.joinToString("\n"))
            for ((i, rec) in levels.withIndex()) {
                val shown = Math.round(r.errors[i] * 100) / 100.0           // le jeu affiche des centièmes
                obs.add(Calibrator.obs(rec.inv, shown, rec.latencyUsed, rec.shiftUsed))
            }
            val (nl, nb) = Calibrator.solve(obs)!!
            println("SIM calibration partie $run : erreurs ms " + r.errors.joinToString { "%+.1f".format(it * 1000) } + " -> latence %.1f ms, bas %+.2f px".format(nl * 1000, nb))
            l = nl; b = nb
        }
        val r = play(listOf(3.76, 9.83, 11.43), latencySetting = l, shift = b, seed = 99, bottomBias = -2.0)
        println("SIM après calibration : " + r.errors.joinToString { "%+.1f".format(it * 1000) })
        for (e in r.errors) assertTrue(abs(e) < 0.015, "erreur ${e * 1000} ms")
    }

    @Test
    fun enchaineLesManchesToutSeul() {
        val r = play(listOf(3.76, 9.83, 6.0), latencySetting = 0.085, seed = 7, autoNext = true)
        assertEquals(3, r.errors.size, r.log.joinToString("\n"))
        for (e in r.errors) assertTrue(abs(e) < 0.012, "erreur ${e * 1000} ms")
    }
}
