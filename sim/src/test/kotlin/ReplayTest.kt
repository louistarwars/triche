import fr.triche.piano.logic.*
import kotlin.test.Test
import kotlin.test.assertTrue

/** Rejeu de la vraie vidéo en « observateur » : le suivi du défilement doit rester verrouillé et mesurer la bonne vitesse. */
class ReplayTest {
    @Test
    fun suiviSurLaVraieVideo() {
        val pts = ReplayTest::class.java.getResourceAsStream("/pts.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map { it.toDouble() }
        val bot = PianoBot()
        val speeds = ArrayList<Pair<Double, Double>>()
        var played = 0
        var i = 200
        while (i < 1754) {
            val f = Support.load("v%04d".format(i), "jpg")
            bot.onFrame(pts[i], f)
            if (pts[i] in 6.0..27.0) { played++; speeds.add(pts[i] to bot.speed * 0.25 * 4) }       // images de 180 px : ×4 pour revenir à 720
            i += 2
        }
        for (lo in listOf(7, 11, 15, 19, 23)) {
            val s = speeds.filter { it.first in lo.toDouble()..(lo + 2.0) && it.second > 0 }.map { it.second }.sorted()
            if (s.isNotEmpty()) println("REPLAY t=%d-%d : vitesse médiane %.0f px/s (720)".format(lo, lo + 2, s[s.size / 2]))
        }
        println("REPLAY " + bot.status)
        val med = { lo: Double, hi: Double -> speeds.filter { it.first in lo..hi }.map { it.second }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] } }
        // vitesses mesurées à part sur la vidéo : ≈ 730 (t 9-11), 970 (15-17), 1190 (21-23), 1370 (25-27)
        assertTrue(med(9.0, 11.0) in 650.0..820.0, "vitesse 9-11 s : ${med(9.0, 11.0)}")
        assertTrue(med(15.0, 17.0) in 870.0..1070.0, "vitesse 15-17 s : ${med(15.0, 17.0)}")
        assertTrue(med(25.0, 27.0) in 1230.0..1510.0, "vitesse 25-27 s : ${med(25.0, 27.0)}")
    }
}

class RealFramesTest {
    private fun phase(n: Int): Double {
        val bot = PianoBot()
        bot.onFrame(0.0, Support.load("r%04d".format(n), "png"))
        return bot.trackedPhi % 299.86
    }

    @Test
    fun phaseSurDeVraiesImages() {
        // phases mesurées à part (bords des touches, image de 720 px)
        for ((n, expected) in listOf(380 to 87.1, 1200 to 293.2, 1500 to 35.95)) {
            val p = phase(n)
            assertTrue(kotlin.math.abs(p - expected) < 0.8, "image $n : phase $p au lieu de $expected")
        }
    }

    @Test
    fun rienSurLesAutresEcrans() {
        val bot = PianoBot()
        for (i in listOf(20, 100, 150, 190)) {
            bot.onFrame(i / 60.0, Support.load("v%04d".format(i), "jpg"))
            assertTrue(bot.collectTaps(i / 60.0 + 0.001).isEmpty(), "menu, image $i")
        }
        assertTrue(bot.status == "En attente de la partie", bot.status)
        // écrans de fin (voile rouge, écran noir) : jamais de toucher
        val pts = ReplayTest::class.java.getResourceAsStream("/pts.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map { it.toDouble() }
        for (i in 1660..1750 step 2) {
            bot.onFrame(pts[i], Support.load("v%04d".format(i), "jpg"))
            assertTrue(bot.collectTaps(pts[i] + 0.001).isEmpty(), "écran de fin, image $i")
        }
    }
}
