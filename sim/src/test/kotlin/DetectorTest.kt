import fr.triche.colis.logic.Color
import fr.triche.colis.logic.Detector
import fr.triche.colis.logic.Frame
import fr.triche.colis.logic.Scene
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vraies images de la vidéo d'écran du jeu (720 x 1594). */
class DetectorTest {
    companion object {
        fun frameOf(img: java.awt.image.BufferedImage): Frame {
            val w = img.width
            val h = img.height
            val bytes = ByteArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val p = img.getRGB(x, y)
                val o = (y * w + x) * 4
                bytes[o] = (p shr 16).toByte()
                bytes[o + 1] = (p shr 8).toByte()
                bytes[o + 2] = p.toByte()
                bytes[o + 3] = 0xFF.toByte()
            }
            return Frame(ByteBuffer.wrap(bytes), w, h, w * 4, 4)
        }

        fun frame(name: String) = frameOf(ImageIO.read(DetectorTest::class.java.getResourceAsStream("/frames/$name.png")))
    }

    private fun describe(s: Scene?) = s?.parcels?.joinToString { "c${it.color}[${it.top}-${it.bottom} ${"%.2f".format(it.share)}]" } ?: "null"

    @Test fun ecransHorsPartie() {
        for (n in listOf("p0060", "p0200", "p1650")) {
            val s = Detector().detect(frame(n))
            println("HORS-PARTIE $n -> ${describe(s)}")
            assertNull(s, n)
        }
    }

    @Test fun colisBleu() {
        val s = assertNotNull(Detector().detect(frame("p0250")))
        println("p0250 -> ${describe(s)}")
        assertEquals(listOf(Color.BLUE), s.parcels.map { it.color })
    }

    @Test fun colisJaune() {
        val s = assertNotNull(Detector().detect(frame("p0420")))
        println("p0420 -> ${describe(s)}")
        assertEquals(listOf(Color.YELLOW), s.parcels.map { it.color })
    }

    @Test fun deuxColisRouges() {
        val s = assertNotNull(Detector().detect(frame("p0900")))
        println("p0900 -> ${describe(s)}")
        assertEquals(listOf(Color.RED, Color.RED), s.parcels.map { it.color })
        assertTrue(abs(s.parcels.last().bottom - 990) < 20, "bas ${s.parcels.last().bottom}")
    }

    @Test fun bleuEtRouge() {
        val s = assertNotNull(Detector().detect(frame("p1000")))
        println("p1000 -> ${describe(s)}")
        assertEquals(listOf(Color.BLUE, Color.RED), s.parcels.map { it.color })
    }

    @Test fun bleuEtJaune() {
        val s = assertNotNull(Detector().detect(frame("p1500")))
        println("p1500 -> ${describe(s)}")
        assertEquals(listOf(Color.BLUE, Color.YELLOW), s.parcels.map { it.color })
    }

    @Test fun colisQuiSortDeLaGoulotte() {
        // le second colis n'a pas encore 24 px de haut hors de la goulotte : seul le premier compte
        val s = assertNotNull(Detector().detect(frame("p1380")))
        println("p1380 -> ${describe(s)}")
        assertEquals(listOf(Color.RED), s.parcels.map { it.color })
    }
}
