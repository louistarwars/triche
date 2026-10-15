import fr.triche.timesup.logic.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lecture sur de vraies images de la vidéo (720×1594) et ajustement sur les positions réellement mesurées de la barre. */
class RealDataTest {
    @Test
    fun barreSurDeVraiesImages() {
        // (image, haut attendu en px) : positions mesurées indépendamment sur la vidéo
        for ((n, top) in listOf(264 to 617.5, 300 to 715.6, 330 to 798.8, 360 to 880.7, 456 to 650.8, 540 to 740.8, 700 to 909.3)) {
            val b = assertNotNull(BarReader.read(Support.frame("f%04d".format(n))), "image $n")
            assertEquals(top, b.top, 0.6, "haut, image $n")
            assertEquals(1240.5, b.bottom, 0.6, "bas, image $n")
            assertEquals(210.0, b.width, 3.0)
        }
    }

    @Test
    fun pasDeBarreAilleurs() {
        for (n in listOf(20, 150, 222, 378, 396, 420, 732, 800, 970)) assertNull(BarReader.read(Support.frame("f%04d".format(n))), "image $n")
    }

    @Test
    fun boutonNiveauSuivant() {
        for (n in listOf(420, 800)) {
            val b = assertNotNull(NextButton.find(Support.frame("f%04d".format(n))), "image $n")
            assertEquals(359.5, b.first, 3.0); assertEquals(1073.0, b.second, 4.0)
        }
        for (n in listOf(20, 264, 430, 540, 970)) assertNull(NextButton.find(Support.frame("f%04d".format(n))), "image $n")
    }

    /** Les positions réelles de la barre (horodatées par la vidéo) : fin prévue cohérente avec celle de l'ajustement à part. */
    @Test
    fun finPrevueSurLesMesuresDeLaVideo() {
        val lines = RealDataTest::class.java.getResourceAsStream("/video_bar.csv")!!.bufferedReader().readLines()
        for ((lab, expected) in listOf(1 to 8.853, 2 to 19.226)) {
            val pts = lines.map { it.split(',') }.filter { it[0].toInt() == lab }.map { Triple(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) }
            val bot = TimesUpBot(Settings(0.0, 0.0, autoNext = false, autoLatency = false))
            val synth = Synth()
            // la barre pleine d'abord (le bot attend de la voir arriver), puis les positions mesurées
            var t = pts.first().first - 0.5
            while (t < pts.first().first - 0.02) { synth.drawBar(synth.topFull, 1.0); bot.onFrame(t, synth.frame); t += 1 / 60.0 }
            for ((tt, y, level) in pts) {
                synth.drawBar(y, (level / 180.0).coerceIn(0.0, 1.0))
                bot.onFrame(tt, synth.frame)
            }
            val tp = assertNotNull(bot.nextTap(), "toucher prévu, manche $lab")
            println("REAL manche $lab : fin prévue (latence 0) à t=%.3f, attendue ≈ %.3f".format(tp.at, expected))
            assertTrue(abs(tp.at - expected) < 0.06, "fin prévue ${tp.at} au lieu de ≈ $expected")
        }
    }
}
