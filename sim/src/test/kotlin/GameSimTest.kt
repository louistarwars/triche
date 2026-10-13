import fr.triche.dice.logic.*
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jeu simulé selon ce qu'on a mesuré sur la vidéo : chronomètre au pas de 0,1 s dont le premier pas tombe au démarrage de
 * la manche, arrêté dès que la bonne somme est tapée, pause d'environ 2 s entre les manches ; images à 60/s qui montrent
 * l'écran avec [obsDelay] de retard, toucher qui n'arrive dans le jeu que [tapDelay] après son envoi.
 */
class GameSimTest {
    private class Sim(
        val total: Double, val latencySetting: Double, val obsDelay: Double = 0.05, val tapDelay: Double = 0.08,
        val seed: Long = 1, val dropFirstFinalTap: Boolean = false, val frameHz: Double = 60.0,
    ) {
        val rnd = Random(seed)
        val bot = DiceBot(Settings(total, latencySetting))
        val sums = IntArray(3) { 4 + rnd.nextInt(21) }
        val pause = 2.0
        // état du jeu (temps vrai)
        var round = 0
        var s = 0.0                  // début du chronomètre de la manche
        var typed = ""
        var accepted = Double.NaN    // heure vraie à laquelle la manche courante a été validée
        var score = 0.0
        var ticksBefore = 0          // valeur affichée au début de la manche (dixièmes)
        var over = false
        var dropped = false
        val log = ArrayList<String>()

        fun displayAt(t: Double): Int? {      // valeur affichée au temps vrai t
            if (over && round >= 3) return ticksBefore
            val end = if (accepted.isNaN()) Double.MAX_VALUE else accepted
            if (t < s) return ticksBefore
            val k = (Math.floor((minOf(t, end) - s) / 0.1 + 1e-9)).toInt() + 1
            return ticksBefore + k
        }

        fun run() {
            bot.log = { log.add(it) }
            s = 4.1
            val dt = 0.001
            var t = 0.0
            var nextFrame = rnd.nextDouble() / frameHz
            val pending = ArrayList<Pair<Double, Char>>()    // (heure vraie d'effet, touche)
            var guard = 0
            while (!over && t < 60) {
                t += dt
                // touches arrivées dans le jeu
                val it = pending.iterator()
                while (it.hasNext()) {
                    val (at, k) = it.next()
                    if (at <= t) { it.remove(); press(at, k) }
                }
                // fil des touchers (il tourne à vif : 1 ms de résolution ici)
                val n = bot.nextTap()
                if (n != null && t >= n.at - obsDelay * 0 && n.at <= t + 0.0) {
                    val lateness = rnd.nextDouble() * 0.004
                    val sendT = t + lateness
                    bot.onSent(n, sendT)
                    if (n.final && dropFirstFinalTap && !dropped) dropped = true
                    else pending.add((sendT + tapDelay) to n.key)
                }
                if (t >= nextFrame) {
                    nextFrame += 1.0 / frameHz
                    val seen = t - obsDelay
                    val diceVisible = seen >= s - 0.0 && seen < (if (accepted.isNaN()) 1e9 else accepted + 0.3) && round < 3
                    val reading = if (diceVisible) DiceReading(intArrayOf(sums[round]/4 + 0, 0, 0, 0).also { a ->
                        val rest = sums[round]; a[0] = minOf(6, rest); var r = rest - a[0]; a[1] = minOf(6, r); r -= a[1]; a[2] = minOf(6, r); r -= a[2]; a[3] = r
                    }, IntArray(16) { 100 }) else null
                    val timer = if (round == 0 && seen < s - 1.4) null
                        else if (round >= 3 || (over && seen > (if (accepted.isNaN()) 1e9 else accepted) + 1.0)) null
                        else displayAt(seen)
                    bot.step(t, timer, { reading }, { null })
                }
                // manche suivante
                if (!accepted.isNaN() && t >= accepted + pause - obsDelay * 0 && round < 3) {
                    if (round == 2) { over = true; round = 3; break }
                }
                if (!accepted.isNaN() && t >= accepted + pause && round < 2) {
                    ticksBefore = displayAt(t)!!
                    round++
                    s = t
                    typed = ""
                    accepted = Double.NaN
                }
                guard++
            }
            assertTrue(guard > 0)
        }

        private fun press(at: Double, k: Char) {
            if (accepted.isNaN().not()) return
            if (k == 'B') { typed = typed.dropLast(1); return }
            typed += k
            if (typed == sums[round].toString()) {
                accepted = at
                score += at - s
            } else if (typed.length >= sums[round].toString().length) typed = ""   // refusée
        }
    }

    @Test
    fun objectifAtteintAvecLaBonneLatence() {
        for (target in listOf(6.0, 9.0, 12.5)) for (seed in 1L..5L) {
            // latence vue du bot = retard d'observation + délai du geste (celle du simulateur)
            val sim = Sim(target, 0.13, seed = seed)
            sim.run()
            println("SIM objectif %.2f -> score %.3f (écart %+.3f) %s".format(target, sim.score, sim.score - target, sim.bot.summary()))
            assertTrue(abs(sim.score - target) < 0.06, "objectif $target seed $seed : score ${sim.score}\n" + sim.log.joinToString("\n"))
        }
    }

    @Test
    fun uneLatenceFausseDecaleLeScoreDeTroisFoisLErreur() {
        val sim = Sim(9.0, 0.10, seed = 3)    // vraie latence 0,13 : trop tôt de 30 ms par manche -> trop long de 3×30 ms
        sim.run()
        println("SIM latence fausse : score %.3f".format(sim.score))
        assertEquals(9.0 + 3 * 0.03, sim.score, 0.05)
    }

    @Test
    fun toucherPerduEtRepris() {
        val sim = Sim(9.0, 0.13, seed = 4, dropFirstFinalTap = true)
        sim.run()
        println("SIM toucher perdu : score %.3f\n%s".format(sim.score, sim.log.takeLast(12).joinToString("\n")))
        assertTrue(sim.bot.finished, "partie menée au bout\n" + sim.log.joinToString("\n"))
    }

    @Test
    fun tropCourtVaAuPlusVite() {
        val sim = Sim(1.0, 0.13, seed = 5)
        sim.run()
        println("SIM trop court : score %.3f".format(sim.score))
        assertTrue(sim.score < 1.8, "score ${sim.score}")
        assertTrue(sim.bot.finished)
    }
}
